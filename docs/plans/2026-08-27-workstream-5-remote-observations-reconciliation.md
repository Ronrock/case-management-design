# Workstream 5: Remote Observations and Reconciliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Guarantee that remote Operaton history is ingested completely, replayed safely, and reconciled with local state without silent gaps.

**Architecture:** Poll each engine history stream independently into a durable observation inbox. Read every page inside a bounded poll window, overlap the next window, and deduplicate by fingerprint. Advance a stream checkpoint only after all pages are durable. Apply inbox rows through Workstream 3's common lifecycle handler and use the same path for reconciliation repairs.

**Tech Stack:** Java 21, Spring schedulers and transactions, Operaton REST history APIs, Oracle, Liquibase, JUnit 5, real HTTP integration tests, Testcontainers, Micrometer-compatible metrics.

**Spec:** [`docs/plans/2026-08-27-bpmn-first-production-readiness-design.md`](2026-08-27-bpmn-first-production-readiness-design.md), sections 9.5 and 9.7, review comment 8, acceptance Gate 5.

## Global constraints

- A checkpoint never advances beyond unread or uncommitted engine data.
- Each stream has its own tenant-aware checkpoint.
- Re-reading an overlap is expected and harmless.
- Inbox insertion and lifecycle application are independently retryable.
- Reconciliation creates normal observations; it never edits projections directly.
- Remote state is eventual but its lag and inconsistencies must be visible.
- Preserve evidence for failed/poison observations and support audited replay.

---

## Problem statement

The current remote poller requests a fixed maximum of 500 records and advances a shared checkpoint to the time at which polling began. If 501 records are available, the unread record may fall behind the new checkpoint forever. A shared checkpoint also assumes tasks, activities, and process history have the same ordering and visibility behavior. Under load or after an outage, the platform can permanently miss tasks or completion events.

Polling alone also cannot prove long-term agreement. Late engine history, transient parsing failures, and bugs can leave local projections different from Operaton unless active cases and uncertain commands are periodically reconciled.

## Scope

### In scope

- Durable observation inbox and apply states.
- Tenant/stream checkpoints with compound cursors.
- Complete deterministic pagination and bounded windows.
- Overlap/deduplication and late/equal-timestamp behavior.
- Inbox worker using the common lifecycle handler.
- Active-case, stale-projection, and uncertain-command reconciliation.
- Operational status, replay APIs, metrics, and alerts.

### Out of scope

- Business lifecycle effects inside observations; Workstream 3 owns them.
- Remote command dispatch states; Workstream 4 owns them.
- Custom Operaton event push plugin; stock REST polling remains supported.

## Technical design

### Streams and cursors

Define explicit streams:

```java
public enum ObservationStream {
    PROCESS_INSTANCE,
    ACTIVITY_INSTANCE,
    USER_TASK,
    DEPLOYMENT
}

public record ObservationCursor(OffsetDateTime occurredAt, String stableId) {}
```

Checkpoint identity is `(engineId, tenantId, stream)`. Each poll captures `windowEnd = engine/server time when possible, otherwise local UTC now minus safety delay`. It reads from `checkpoint.occurredAt - overlap` through `windowEnd`, ordered by timestamp and stable ID. If an Operaton endpoint cannot express the compound filter fully, fetch by timestamp range and sort/deduplicate locally without advancing until all pages complete.

### Durable inbox

```java
public record ObservationEnvelope(
        String id, String engineId, String tenantId,
        ObservationStream stream, String fingerprint,
        String payloadJson, ObservationCursor sourceCursor,
        InboxStatus status, int attempts,
        OffsetDateTime receivedAt, String lastError) {}
```

Unique `(engineId, tenantId, fingerprint)` prevents duplicates. Inbox states are `RECEIVED`, `CLAIMED`, `APPLIED`, `RETRYABLE`, and `DEAD`. Claims use a lease. Payloads are bounded and sensitive variable values follow the platform's data handling policy.

### Checkpoint transaction

For each page, insert the entire page into the inbox transactionally. After the last page through `windowEnd` is durable, update the checkpoint to the last fully covered boundary. A failure before that leaves the old checkpoint, causing safe reread. Do not update a checkpoint based merely on poll start time.

### Reconciliation

Three reconcilers use the same observation factory and inbox:

- **Uncertain command reconciler:** searches by operation/correlation evidence and confirms or proves absence.
- **Stale active-case reconciler:** checks cases whose last engine update exceeds the configured threshold.
- **Full active inventory reconciler:** periodically compares all active linked processes and open tasks.

Differences become observations marked `source=RECONCILIATION`. Missing local state is repaired through normal lifecycle handling. A local object absent from the engine is not automatically deleted unless engine history proves the terminal event.

## File map

### Create

- `case-management-core/src/main/java/org/casemgmt/observation/ObservationStream.java`.
- `case-management-core/src/main/java/org/casemgmt/observation/ObservationCursor.java`.
- `case-management-core/src/main/java/org/casemgmt/observation/ObservationEnvelope.java`.
- `case-management-core/src/main/java/org/casemgmt/repo/ObservationInboxRepository.java`.
- `case-management-core/src/main/java/org/casemgmt/service/ObservationInboxWorker.java`.
- `case-management-core/src/main/resources/db/changelog/cm-remote-observation-inbox.xml`.
- `case-management-engine-remote/src/main/java/org/casemgmt/engine/remote/OperatonHistoryClient.java`.
- `case-management-engine-remote/src/main/java/org/casemgmt/engine/remote/RemoteObservationMapper.java`.
- `case-management-engine-remote/src/main/java/org/casemgmt/engine/remote/RemoteReconciliationService.java`.
- Repository, pagination, mapping, worker, reconciliation, and failure tests.

### Modify

- `case-management-core/src/main/java/org/casemgmt/projection/RemotePollingCheckpointRepository.java`.
- `case-management-engine-remote/src/main/java/org/casemgmt/engine/remote/RemoteObservationPoller.java`.
- `case-management-spring-boot-starter/src/main/java/org/casemgmt/starter/RemoteEngineAutoConfiguration.java`.
- `case-management-spring-boot-starter/src/main/java/org/casemgmt/starter/CaseManagementSchedulers.java`.
- `case-management-spring-boot-starter/src/main/java/org/casemgmt/starter/CaseManagementProperties.java`.
- `case-management-rest/src/main/java/org/casemgmt/rest/controller/OrchestrationOperationsController.java`.
- `.github/workflows/ci.yml` for remote high-volume/failure coverage.

## Database changes

- Create `CM_ENGINE_OBSERVATION_INBOX` with fingerprint uniqueness, payload, source cursor, apply status, attempts, lease, timestamps, and diagnostic.
- Replace/extend the single checkpoint with engine, tenant, stream, cursor time, cursor ID, window end, status, lag, last success, and row version.
- Add indexes for due inbox claims, dead records, stream checkpoint lookup, and reconciliation queries.
- Migrate the old global checkpoint conservatively as the starting time for each stream, subtracting the configured overlap so no unseen data is skipped.

## Test strategy

### Unit tests

- Mapping of every supported Operaton history response to canonical observation types.
- Deterministic cursor comparison for equal timestamps.
- Page loop termination and stable ordering.
- Overlap boundary calculation and clock-skew safety.
- Bounded payload/error handling.

### Oracle repository tests

- Duplicate fingerprint insert is harmless.
- Concurrent inbox claims are exclusive and expired leases recover.
- Checkpoint optimistic update rejects stale writers.
- Page failure leaves checkpoint unchanged.
- Old checkpoint migration creates all stream rows with overlap.

### Remote integration tests

- 1,201 records with page size 500 yield three pages and 1,201 applied observations.
- More records arrive while polling; only records inside the fixed window are checkpointed.
- Multiple records have identical timestamps and none are lost.
- Restart after page 2 rereads safely and finishes.
- Delayed history appears within overlap and is ingested.
- One poison observation does not block unrelated inbox rows indefinitely.

### Reconciliation tests

- Missing task projection is recreated with audit/event through lifecycle handler.
- Local open task with proven engine completion is completed.
- Uncertain process start finds its operation ID and confirms once.
- Absence that cannot be proved remains manual review.
- Cross-tenant engine results are rejected.

## Acceptance scenarios

| ID | Scenario | Expected result |
|---|---|---|
| WS5-AC1 | 1,201 task records exist before a poll. | All pages are stored/applied; checkpoint covers the bounded window only after page 3. |
| WS5-AC2 | Poller fails after page 2. | Checkpoint does not advance; restart rereads safely with no duplicate business effects. |
| WS5-AC3 | 600 records share one timestamp. | Compound cursor/fingerprint logic ingests all 600 exactly once. |
| WS5-AC4 | History record becomes visible late. | Overlap captures it and deduplication protects already seen records. |
| WS5-AC5 | Same observation appears in polling and reconciliation. | One inbox/lifecycle effect exists. |
| WS5-AC6 | A task projection is missing. | Reconciliation repairs it through normal audit/event lifecycle. |
| WS5-AC7 | One payload repeatedly fails mapping. | It reaches dead state with diagnostics; other observations continue; replay is audited. |
| WS5-AC8 | Tenant A poll receives tenant B data. | Data is rejected and security alerting fires; no projection changes. |
| WS5-AC9 | Operaton is unavailable. | Existing projections remain readable, lag grows visibly, and no checkpoint advances. |

## Implementation tasks

### Task 1: Add inbox and per-stream checkpoint persistence

- [ ] Write Oracle tests for schema, unique fingerprint, leases, optimistic checkpoints, and migration overlap.
- [ ] Add the new Liquibase changeset and repository types.
- [ ] Run `SchemaMigrationTest`, inbox, and checkpoint repository tests.
- [ ] Commit `feat: persist remote engine observations`.

### Task 2: Extract a paged Operaton history client

- [ ] Write HTTP fixture tests with empty, one-page, multi-page, equal-time, malformed, and transient-failure responses.
- [ ] Implement explicit page number/size, deterministic ordering, and bounded time filters for each stream.
- [ ] Run remote client tests.
- [ ] Commit `feat: page Operaton history streams`.

### Task 3: Map and ingest complete poll windows

- [ ] Write tests for all remote DTO-to-observation mappings and fingerprints.
- [ ] Refactor `RemoteObservationPoller` to process every page into the inbox before checkpoint update.
- [ ] Add overlap and no-progress protections to prevent infinite paging loops.
- [ ] Run poller tests including 1,201 records.
- [ ] Commit `fix: prevent remote history gaps`.

### Task 4: Apply inbox observations through the common handler

- [ ] Write worker tests for success, duplicate, retryable error, poison/dead, crash, and lease recovery.
- [ ] Implement claimed-batch processing using `EngineObservationHandler` only.
- [ ] Add ArchUnit rule preventing remote adapter writes to projection repositories.
- [ ] Run core/remote worker tests.
- [ ] Commit `feat: apply durable remote observations`.

### Task 5: Implement reconciliation

- [ ] Write mismatched inventory and uncertain-command fixtures.
- [ ] Implement uncertain-command, stale-case, and full-active reconciliation schedules.
- [ ] Store repairs in the inbox with `RECONCILIATION` source.
- [ ] Run reconciliation tests and verify requested/confirmed audit trails.
- [ ] Commit `feat: reconcile remote engine state`.

### Task 6: Add operational APIs and telemetry

- [ ] Extend HTTP tests for per-stream lag/status, dead observations, replay, and tenant/admin policy.
- [ ] Add metrics and alerts for poll lag, inbox backlog, apply failures, and differences.
- [ ] Update operations documentation and OpenAPI descriptions.
- [ ] Run REST/PoC operation tests.
- [ ] Commit `feat: operate remote observation pipeline`.

### Task 7: Run destructive/failure rehearsal in tests

- [ ] Kill/restart the poller between pages and the worker after claim.
- [ ] Simulate Operaton outage and delayed history.
- [ ] Run `./mvnw -pl case-management-engine-remote,case-management-poc-app -am test` and full reactor.
- [ ] Commit `test: prove remote observation recovery`.

## Completion evidence

- High-volume report showing counts requested, stored, applied, duplicated, and checkpointed.
- Restart/failure trace proving no skipped cursor.
- Reconciliation report showing differences repaired through normal lifecycle events.
- Dashboard/API output for every stream and inbox state.
- Evidence resolving review comment 8.

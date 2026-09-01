# Workstream 6: SLA Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make SLA measurement complete, versioned, idempotent, and consistent with case/process outcomes while allowing BPMN to react safely to SLA events.

**Architecture:** The SLA service owns business-time measurement and snapshots target/calendar revisions for each occurrence. Lifecycle observations trigger named SLA anchors. SLA transitions write audit/outbox atomically; optional BPMN reactions use durable idempotent message commands rather than duplicating the deadline as a BPMN timer.

**Tech Stack:** Java 21, Spring transactions/schedulers, Oracle, Liquibase, JSON Schema 2020-12, `java.time`, JUnit 5, AssertJ, Testcontainers, Workstreams 3–5 lifecycle/command infrastructure.

**Spec:** [`docs/plans/2026-08-27-bpmn-first-production-readiness-design.md`](2026-08-27-bpmn-first-production-readiness-design.md), section 9.8, review comments 6 and 7, acceptance Gate 6.

## Global constraints

- The SLA service is authoritative for business SLA calculation, calendars, warnings, pause, meet, cancel, and breach.
- BPMN owns process mechanics; do not model the same business deadline independently in BPMN and SLA.
- Each occurrence snapshots target and calendar revisions.
- Every transition is idempotent per case/target/occurrence/event.
- Root terminal outcomes close all applicable non-terminal SLA occurrences.
- SLA-to-BPMN reactions use durable message commands and stable event IDs.
- Existing SLA APIs remain compatible; additions are additive.

---

## Problem statement

The current contract leaves `slaBindings` open. Production code does not consistently start clocks from BPMN lifecycle anchors, and root process completion does not meet or cancel running records. The sweeper can therefore breach an SLA after the case has already completed. Calendar/target revisions and repeatable occurrences are not sufficiently explicit, and the same deadline can be modeled once in BPMN and once in the SLA service.

False SLA breaches directly affect business reporting, escalation, and contractual evidence. Duplicate timers can also trigger the same escalation twice.

## Scope

### In scope

- Typed SLA target/binding schema and cross-reference rules from Workstream 1.
- Target/calendar revision snapshots and occurrence identity.
- Start, warning, pause, resume, meet, cancel, and breach transitions.
- Lifecycle anchor integration with Workstream 3.
- Root terminal policy and `CANCELLED` status.
- Deduplicated SLA-to-BPMN message command.
- Sweeper safety, concurrency, metrics, and APIs.

### Out of scope

- General command infrastructure; Workstream 4 supplies it.
- General observation ingestion; Workstream 5 supplies it.
- Business-specific decision on which named case outcome meets versus cancels each target; the contract must declare this.

## Technical design

### Contract model

```java
public record SlaTargetDefinition(
        String id, int version, SlaScope scope,
        String calendarId, int calendarRevision,
        Duration duration, SlaAnchor start,
        SlaAnchor meet, SlaAnchor cancel,
        List<Duration> warnings,
        PausePolicy pausePolicy,
        Map<String, TerminalSlaOutcome> caseOutcomes,
        BpmnReaction reaction) {}
```

Scope supports case, stage, task, milestone interval, and named occurrence. Repeatable work supplies an occurrence key expression whose evaluated result is bounded and stable.

### Runtime identity and state

Use unique `(tenantId, caseId, targetId, occurrenceKey)`. Store target version, calendar ID/revision, anchor evidence IDs, effective due date, accumulated paused duration, warning mask, status, and optimistic version.

```java
public enum SlaStatus { RUNNING, PAUSED, MET, CANCELLED, BREACHED }
```

Terminal transitions are monotonic. Replaying the same anchor/event returns the existing record. A later contradictory terminal transition is rejected and audited as a conflict; it does not rewrite history.

### Anchor integration

`SlaLifecyclePort.onObservation(observation)` is called inside the common lifecycle transaction. It resolves only targets bound to the exact case contract. Root completion supplies the configured case outcome and closes every non-terminal occurrence as `MET` or `CANCELLED`. If the contract lacks a policy for a possible root outcome, publication fails.

### Sweeper

The sweeper claims due records with leases. It recalculates using the snapshotted calendar revision, emits each warning once, and breaches once. A terminal record can never be reclaimed. Clock and batch size are injected for deterministic tests.

### BPMN reaction

An SLA transition appends `sla.warning`, `sla.met`, `sla.cancelled`, or `sla.breached`. If the target declares a reaction, the outbox consumer creates `CORRELATE_MESSAGE` with idempotency key `sla:{occurrenceId}:{eventId}` and payload containing only approved fields. Message failure does not roll back the authoritative SLA transition, but remains visible in command status.

## File map

### Create

- `case-management-core/src/main/java/org/casemgmt/sla/SlaTargetDefinition.java` and supporting enums.
- `case-management-core/src/main/java/org/casemgmt/sla/SlaLifecyclePort.java`.
- `case-management-core/src/main/java/org/casemgmt/sla/ContractSlaLifecycleService.java`.
- `case-management-core/src/main/java/org/casemgmt/sla/SlaBpmnReactionHandler.java`.
- `case-management-core/src/main/resources/db/changelog/cm-sla-occurrence-v2.xml`.
- Unit, property-style calendar, Oracle transaction, sweeper concurrency, and BPMN reaction tests.

### Modify

- `docs/schemas/case-contract-v1.schema.json` and Workstream 1 typed contract records.
- `case-management-core/src/main/java/org/casemgmt/sla/SlaRecord.java`.
- `case-management-core/src/main/java/org/casemgmt/sla/SlaService.java`.
- `case-management-core/src/main/java/org/casemgmt/sla/SlaSweeper.java`.
- `case-management-core/src/main/java/org/casemgmt/repo/SlaRepository.java`.
- `case-management-core/src/main/java/org/casemgmt/observation/DefaultEngineObservationHandler.java`.
- `case-management-core/src/main/java/org/casemgmt/event/EventTypes.java`.
- `case-management-rest/src/main/java/org/casemgmt/rest/controller/SlaController.java`.
- Starter SLA configuration and schedulers.

## Database changes

- Add target version, occurrence key, scope/reference, calendar ID/revision, duration snapshot, start/meet/cancel evidence, pause total, warning state, and reaction operation ID.
- Add `CANCELLED` to status constraint through a new changeset.
- Add unique occurrence identity and due/claim indexes.
- Migrate existing rows to target version 1 and a stable default occurrence key. Existing running records without a resolvable calendar revision are paused for repair rather than recalculated with a guessed calendar.

## Test strategy

### Unit tests

- Due-date calculation across working hours, weekends, holidays, daylight-saving gaps/overlaps, leap day, and tenant time zones.
- Stable occurrence key and target/calendar snapshots.
- Legal transitions and idempotent replay.
- Root outcome mapping to met/cancelled.
- Duplicate deadline ownership validation.

### Transaction/concurrency tests

- Lifecycle event starts SLA plus audit/outbox in one transaction.
- Root completion closes case and every occurrence atomically.
- Two sweepers emit one warning/breach.
- Crash after claim recovers without duplicate event.
- SLA reaction command uses one idempotency key.

### End-to-end tests

- BPMN task create starts target; completion meets it.
- Stage entry starts a repeatable occurrence; each repetition gets a distinct key.
- Root success meets configured targets; root cancellation cancels configured targets.
- Breach sends one BPMN message and the process follows the modeled reaction.
- Remote duplicate observations do not duplicate clocks or messages.

## Acceptance scenarios

| ID | Scenario | Expected result |
|---|---|---|
| WS6-AC1 | Declared start anchor occurs twice through replay. | One SLA occurrence exists with one start audit/event. |
| WS6-AC2 | Case completes successfully. | Every running occurrence becomes its configured `MET` or `CANCELLED` state in the completion transaction. |
| WS6-AC3 | Sweeper runs after terminal case completion. | No former occurrence breaches or emits a warning. |
| WS6-AC4 | Two scheduler nodes claim one due occurrence. | One warning/breach transition and event occur. |
| WS6-AC5 | Calendar changes after occurrence start. | Existing occurrence uses its snapshotted revision; new occurrence uses the new revision. |
| WS6-AC6 | Repeatable task runs three times. | Three distinct occurrence keys exist and transition independently. |
| WS6-AC7 | Breach reaction HTTP response is lost. | SLA remains breached once; one command is reconciled without duplicate process reaction. |
| WS6-AC8 | Contract duplicates one deadline as SLA and platform-tagged BPMN timer. | Publication fails with both conflicting references. |
| WS6-AC9 | Unsupported root outcome lacks SLA policy. | Publication fails rather than guessing at runtime. |

## Implementation tasks

### Task 1: Finalise and test the SLA contract

- [ ] Add schema/typed mapping tests for all target fields, anchors, occurrence rules, terminal outcomes, and reactions.
- [ ] Add semantic tests for missing root-outcome policy and duplicate deadline ownership.
- [ ] Implement the closed schema and typed records through Workstream 1 interfaces.
- [ ] Run contract and BPMN validator tests.
- [ ] Commit `feat: define versioned SLA contracts`.

### Task 2: Migrate occurrence persistence

- [ ] Write Oracle tests for new columns, `CANCELLED`, unique occurrence, indexes, and conservative old-row migration.
- [ ] Add `cm-sla-occurrence-v2.xml` and repository mappings.
- [ ] Run schema and SLA repository tests.
- [ ] Commit `feat: persist SLA occurrence snapshots`.

### Task 3: Implement deterministic occurrence transitions

- [ ] Write transition tests for start, pause, resume, warning, meet, cancel, breach, replay, and contradiction.
- [ ] Refactor `SlaService` around target snapshot plus occurrence identity and injected clock.
- [ ] Add calendar edge-case/property tests.
- [ ] Run SLA unit tests.
- [ ] Commit `feat: complete SLA lifecycle`.

### Task 4: Connect lifecycle anchors and root terminalization

- [ ] Add transactional tests for task/stage/milestone anchors and every root outcome.
- [ ] Implement `ContractSlaLifecycleService` and call it only from the common observation handler.
- [ ] Assert case terminalization and SLA terminalization roll back together on failure.
- [ ] Run lifecycle and SLA integration tests.
- [ ] Commit `fix: close SLA with case lifecycle`.

### Task 5: Harden the sweeper

- [ ] Add concurrency, lease expiry, batch paging, warning deduplication, and terminal-row exclusion tests.
- [ ] Update repository claim and sweeper logic with injected time and metrics.
- [ ] Run `SlaServiceTransactionalIntegrationTest` and new sweeper IT.
- [ ] Commit `fix: make SLA sweeping idempotent`.

### Task 6: Deliver deduplicated BPMN reactions

- [ ] Write tests for reaction event mapping, payload allowlist, stable idempotency key, remote uncertainty, and no-reaction targets.
- [ ] Implement `SlaBpmnReactionHandler` using the production command service.
- [ ] Add embedded and remote E2E breach-reaction BPMN models.
- [ ] Run engine and PoC SLA E2E tests.
- [ ] Commit `feat: notify BPMN of SLA outcomes`.

### Task 7: Complete APIs, telemetry, and regression proof

- [ ] Add additive API fields for target/occurrence/calendar revisions and cancelled status.
- [ ] Add metrics for running/paused/overdue, transition conflicts, sweeper lag, and reaction failures.
- [ ] Update OpenAPI, guide, and operations runbook.
- [ ] Run `./mvnw clean install`.
- [ ] Commit `docs: operationalize SLA lifecycle`.

## Completion evidence

- Calendar edge-case and deterministic clock test report.
- Root completion transaction proof with zero later false breaches.
- Multi-node sweeper proof of one warning/breach.
- BPMN reaction trace with one SLA event and one confirmed message operation.
- Evidence resolving review comments 6 and 7.

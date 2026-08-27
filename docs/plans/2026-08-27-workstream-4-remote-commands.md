# Workstream 4: Production Remote Commands Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every remote Operaton mutation durable, truthful to users, safely retryable, and recoverable when the network outcome is uncertain.

**Architecture:** Replace the PoC queue lifecycle with a production command state machine. APIs accept responsibility and return an operation resource; projections remain unchanged until acknowledgement or observation. Definitive absence permits retry, while ambiguous non-idempotent outcomes enter reconciliation/manual review instead of being blindly resent.

**Tech Stack:** Java 21, Spring transactions and schedulers, Operaton REST, Oracle, Liquibase, REST `202 Accepted`, JUnit 5, the existing engine-only Spring HTTP integration-test harness, Testcontainers.

**Spec:** [`docs/plans/2026-08-27-bpmn-first-production-readiness-design.md`](2026-08-27-bpmn-first-production-readiness-design.md), sections 9.3–9.4, 11–12, review concerns on remote idempotency and premature local mutation, acceptance Gate 4.

## Global constraints

- A remote request is not a confirmed engine fact.
- Never mark a task claimed/completed or a process/message confirmed before acknowledgement or matching observation.
- Never blindly retry an uncertain process start or message correlation.
- Every command has a stable operation ID and tenant-scoped idempotency key.
- Payloads are validated and sensitive values are not exposed in diagnostics.
- Keep existing API fields; add operation/pending fields compatibly.
- Use additive migrations and retain historical command evidence.

---

## Problem statement

The current `CM_ENGINE_COMMAND` was explicitly introduced as PoC-only. It supports pending/retrying/claimed/done/dead but does not model a request whose remote outcome is unknown. `CaseTaskService` can update the local task immediately after the gateway call even when the remote gateway merely enqueues work. A lost HTTP response can cause either a false local success or an unsafe retry that duplicates a process/message effect.

The business needs truthful state: “we accepted your request” is different from “Operaton completed it.” Operations also need a supported way to resolve uncertain outcomes without editing the database.

## Scope

### In scope

- Production command domain, table migration, repository, leasing, dispatch, backoff, and diagnostics.
- Command classifications and safe retry policy.
- Operation-status REST resource and additive pending fields.
- Requested, confirmed, failed, conflict, and manual-review audit events.
- Remote process start, human task create/claim/complete, and message commands.
- Root start confirmation hand-off to Workstream 2.
- Lost-response and concurrent-dispatch tests.

### Out of scope

- Full remote observation paging and reconciliation implementation; Workstream 5.
- Engine-side custom deduplication plugin. The stock-engine policy uses quarantine/manual review.
- Advanced ad-hoc action definition; Workstream 7 consumes this protocol.

## Technical design

### State machine

```java
public enum EngineCommandStatus {
    PENDING,
    DISPATCHING,
    RETRYABLE,
    AWAITING_CONFIRMATION,
    CONFIRMED,
    FAILED,
    CONFLICT,
    MANUAL_REVIEW,
    CANCELLED
}
```

`DISPATCHING` is lease-owned. A dispatcher crash returns the command to classification, not automatically to retry. Safe commands with a provably unaccepted transport failure become `RETRYABLE`. A timeout after request bytes may have reached Operaton becomes `AWAITING_CONFIRMATION` for non-idempotent effects.

Command types declare policy:

```java
public enum EngineCommandType {
    START_PROCESS(false), CREATE_TASK(true), CLAIM_TASK(true),
    COMPLETE_TASK(true), CORRELATE_MESSAGE(false);

    private final boolean resourceTargeted;
}
```

“Resource-targeted” does not itself prove retry safety after a lost response; it allows stronger reconciliation. Actual transition policy uses HTTP phase/status and matching engine state.

### Operation resource

Expose:

```text
GET /case-api/v2/orchestration/operations/{operationId}
```

The response includes operation ID, case ID, command type, status, submitted/last-updated times, attempt count, safe problem summary, and links to the target resource. It excludes raw credentials and sensitive variable payloads.

Mutating remote endpoints return `202 Accepted`, `Location` to the operation, and additive response fields `operationId`, `pendingAction`, and `projectionStatus`. Conflicting task actions are removed from `availableActions` while a mutation is pending.

### Confirmation

Positive HTTP response can confirm commands whose response carries definitive engine state. A matching observation can also confirm. Confirmation is idempotent and calls the common lifecycle handler rather than directly editing projections. Start confirmation calls the atomic root-link method from Workstream 2. Complete-task confirmation applies the stored canonical patch from Workstream 3.

### Manual review

Admin APIs allow an authorised operator to request reconciliation, retry after reviewed evidence, or cancel an unapplied command. Every operator action is audited. There is no API that simply marks an effect confirmed without supporting engine identity/evidence.

## File map

### Create

- `case-management-core/src/main/java/org/casemgmt/engine/EngineCommandStatus.java`.
- `case-management-core/src/main/java/org/casemgmt/engine/EngineCommandPolicy.java`.
- `case-management-core/src/main/java/org/casemgmt/engine/CommandDispatchOutcome.java`.
- `case-management-core/src/main/java/org/casemgmt/service/EngineOperationService.java`.
- `case-management-core/src/main/resources/db/changelog/cm-production-engine-command.xml`.
- `case-management-rest/src/main/java/org/casemgmt/rest/controller/EngineOperationController.java`.
- `case-management-rest/src/main/java/org/casemgmt/rest/dto/EngineOperationResponse.java`.
- Tests for state policy, repository, dispatch, HTTP contract, concurrency, and failure injection.

### Modify

- `case-management-core/src/main/java/org/casemgmt/engine/EngineCommand.java`.
- `case-management-core/src/main/java/org/casemgmt/engine/EngineCommandDispatcher.java`.
- `case-management-core/src/main/java/org/casemgmt/repo/EngineCommandRepository.java`.
- `case-management-core/src/main/java/org/casemgmt/engine/OutboxEngineGateway.java`.
- `case-management-core/src/main/java/org/casemgmt/service/CaseTaskService.java`.
- `case-management-core/src/main/java/org/casemgmt/service/LinkedProcessService.java`.
- `case-management-engine-remote/src/main/java/org/casemgmt/engine/remote/RemoteEngineGateway.java`.
- `case-management-rest/src/main/java/org/casemgmt/rest/controller/TaskController.java`.
- `case-management-rest/src/main/java/org/casemgmt/rest/controller/OrchestrationOperationsController.java`.
- `case-management-rest/src/main/java/org/casemgmt/rest/dto/Dtos.java`.
- `case-management-rest/src/main/java/org/casemgmt/rest/policy/ActionPolicy.java`.
- Starter configuration/properties/schedulers for lease, retry, and alert thresholds.

## Database changes

Extend or migrate `CM_ENGINE_COMMAND` with tenant, idempotency key, operation ID, dispatch status, lease expiry, dispatched/updated/confirmed/failed timestamps, safe error code, correlation JSON, canonical patch JSON, expected case version, and row version. Preserve old rows and map `DONE -> CONFIRMED`, `DEAD -> FAILED`, `CLAIMED -> AWAITING_CONFIRMATION` unless evidence proves otherwise. Replace the old status check constraint through a new changeset.

Add unique constraints for tenant/operation ID and tenant/idempotency scope. Index due retry, expired lease, awaiting confirmation age, case/pending commands, and manual-review state.

## Test strategy

### Unit tests

- Every legal/illegal command transition.
- Retry classification for connection refused before send, HTTP 4xx, HTTP 5xx, timeout/read failure, malformed response, and explicit duplicate response.
- Exponential backoff with deterministic clock/jitter bounds.
- Redaction and maximum diagnostic size.
- Pending actions suppress conflicting `availableActions`.

### Repository/concurrency tests

- Two dispatchers cannot hold one lease.
- Expired lease is recovered without losing attempt history.
- Confirmation and retry racing resolve once.
- Idempotency keys with same payload replay; different payload conflicts.
- Historical status migration is deterministic.

### Remote integration/failure tests

- Response lost after Operaton starts a process: one engine process, command awaits then confirms through reconciliation; no blind second start.
- Response lost after task completion: local task remains pending until observed, then completes once.
- Definitive 400 fails without retry.
- Pre-connect failure retries safely.
- Message ambiguity enters awaiting confirmation/manual review.

### API tests

- Remote mutation returns 202 plus Location and operation ID.
- Operation visibility is tenant/authorization scoped.
- Pending task response is truthful and removes conflicting actions.
- Admin retry/cancel/reconcile actions require policy and create audit entries.
- Embedded synchronous response remains compatible.

## Acceptance scenarios

| ID | Scenario | Expected result |
|---|---|---|
| WS4-AC1 | User completes a remote task. | API returns 202 and task says completion requested, not completed. |
| WS4-AC2 | Operaton confirms completion. | Operation becomes confirmed and common lifecycle handler finalises projection/data/audit once. |
| WS4-AC3 | Operaton completes but response is lost. | Command enters awaiting confirmation; it is not resent blindly; observation later confirms it. |
| WS4-AC4 | Connection fails before any request is sent. | Command becomes retryable with bounded backoff. |
| WS4-AC5 | Operaton returns a definitive validation 400. | Command becomes failed; local business state stays unchanged. |
| WS4-AC6 | Two workers claim the same due command. | Exactly one receives the lease and dispatches. |
| WS4-AC7 | Worker crashes after dispatch. | Lease recovery classifies uncertainty and does not assume the request failed. |
| WS4-AC8 | Same idempotency key and payload is submitted twice. | Same operation is returned; no second command/effect. |
| WS4-AC9 | Same key is reused with different payload. | Stable idempotency conflict; neither payload is overwritten. |
| WS4-AC10 | Operator retries a manual-review message without permission. | Access is denied and security audit records the attempt. |

## Implementation tasks

### Task 1: Specify command transitions and failure classification

- [ ] Write parameterized `EngineCommandPolicyTest` covering every HTTP/transport outcome and current state.
- [ ] Confirm tests fail against the PoC status model.
- [ ] Implement `EngineCommandStatus`, `CommandDispatchOutcome`, and pure transition policy with injected clock.
- [ ] Run focused tests and commit `feat: define production command lifecycle`.

### Task 2: Migrate command persistence

- [ ] Add Oracle migration tests for columns, constraints, indexes, and old-row mapping.
- [ ] Add `cm-production-engine-command.xml` and include it after PoC changesets.
- [ ] Refactor repository claim to use lease token/expiry and optimistic version.
- [ ] Run `EngineCommandClaimSafetyTest`, repository tests, and `SchemaMigrationTest`.
- [ ] Commit `feat: persist recoverable engine operations`.

### Task 3: Make dispatch classification truthful

- [ ] Add dispatcher tests for pre-send failure, lost response, 4xx, 5xx, crash, confirmation, and max attempt policy.
- [ ] Change gateway dispatch to return typed outcomes with response evidence.
- [ ] Persist `AWAITING_CONFIRMATION` for ambiguous outcomes and never schedule an automatic non-idempotent resend.
- [ ] Run core dispatcher and remote gateway tests.
- [ ] Commit `fix: quarantine uncertain engine commands`.

### Task 4: Remove premature local task mutation

- [ ] Add `CaseTaskServiceTest` cases proving remote claim/complete leaves confirmed state unchanged.
- [ ] Store pending action and canonical patch intent with the command.
- [ ] Suppress conflicting available actions until terminal command status.
- [ ] Run core transactional task tests.
- [ ] Commit `fix: wait for remote task confirmation`.

### Task 5: Add operation and support APIs

- [ ] Write HTTP tests for 202/Location, operation retrieval, tenant isolation, redaction, admin retry/reconcile/cancel, and audit.
- [ ] Implement operation DTO/controller/service and extend task responses additively.
- [ ] Update `openapi-specs.md` and conformance fixtures.
- [ ] Run REST tests and `OpenApiConformanceIT`.
- [ ] Commit `feat: expose remote operation status`.

### Task 6: Prove uncertain-outcome behavior against a real HTTP boundary

- [ ] Add a controllable test endpoint that executes then drops/delays the response.
- [ ] Test process start, completion, and message cases through real HTTP.
- [ ] Assert engine history contains one business effect and command history contains all transitions.
- [ ] Run `./mvnw -pl case-management-engine-remote,case-management-poc-app -am test`.
- [ ] Commit `test: cover lost engine responses`.

### Task 7: Complete metrics and regression checks

- [ ] Add metrics for queue age, states, attempts, lease recovery, and manual review.
- [ ] Add scheduler configuration validation and safe defaults.
- [ ] Run `./mvnw clean install` and API compatibility tests.
- [ ] Commit `feat: operate remote command queue`.

## Completion evidence

- Lost-response traces showing one engine effect and no blind retry.
- Command transition/audit history for success, failure, and manual review.
- Concurrency test proving exclusive leases.
- API examples distinguishing requested and confirmed state.
- Migration and backward-compatibility reports.

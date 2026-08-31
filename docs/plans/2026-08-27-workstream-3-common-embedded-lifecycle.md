# Workstream 3: Common Embedded Lifecycle Implementation Plan

> **Superseded assumption:** The later BPMN-only decision removed `PLAN_MODEL`; compatibility
> requirements below are historical. Operaton is the sole lifecycle authority.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Process every accepted engine fact through one idempotent lifecycle handler that atomically updates projections, canonical case data, audit, outbox events, and SLA state.

**Architecture:** Introduce an engine-neutral observation envelope and lifecycle handler in core. The embedded Operaton bridge converts engine callbacks to observations and calls the handler inside the engine transaction. Remote ingestion in Workstream 5 will reuse the same handler.

**Tech Stack:** Java 21, Spring transactions, Operaton 2.1.3 embedded event listeners, Oracle, Liquibase, JUnit 5, Mockito, AssertJ, Testcontainers.

**Spec:** [`docs/plans/2026-08-27-bpmn-first-production-readiness-design.md`](2026-08-27-bpmn-first-production-readiness-design.md), sections 7.2–7.3, 9.5–9.6, review comments 4, 5, and 7, acceptance Gate 3.

## Global constraints

- Operaton remains authoritative for BPMN lifecycle and structured task activation.
- Projections are derived state, never a second workflow engine.
- One observation fingerprint can create business effects only once.
- Projection, canonical patch, audit, outbox, and SLA changes commit or roll back together.
- Only contract-approved output mappings update canonical data.
- Preserve `PLAN_MODEL` behavior and existing public API fields.
- Embedded and remote observations must use the same core types and lifecycle rules.

---

## Problem statement

The embedded bridge and projection port currently write task, stage, milestone, and process state directly. Equivalent audit records and domain events are not produced consistently. Completing a BPMN task passes variables to Operaton but does not reliably update canonical case data. Root completion can close the case without closing SLA clocks.

Direct writes are fast but create multiple partial definitions of “task completed.” As more adapters and event types are added, each direct path can forget canonical mapping, audit, event, or SLA behavior. The current state may be correct while its history and downstream notifications are incomplete.

## Scope

### In scope

- Versioned engine-neutral observation types and fingerprints.
- One lifecycle application service.
- Atomic projection, canonical mapping, audit, outbox, and SLA effects.
- Embedded bridge migration to the common handler.
- Duplicate/replay, ordering, transaction rollback, and parity tests.
- Pending canonical patch representation used later by remote commands.

### Out of scope

- Remote inbox and poll pagination; Workstream 5.
- Command dispatch and uncertain response behavior; Workstream 4.
- Full SLA target schema and scheduling; Workstream 6.

## Technical design

### Observation contract

Create a sealed hierarchy:

```java
public sealed interface EngineObservation permits ProcessObservation,
        UserTaskObservation, ActivityLifecycleObservation, MilestoneObservation {
    String observationId();
    String fingerprint();
    String source();
    String tenantId();
    String caseId();
    String processInstanceId();
    OffsetDateTime engineOccurredAt();
    OffsetDateTime receivedAt();
    Map<String, Object> attributes();
}
```

Use explicit event enums rather than loosely interpreted strings. The fingerprint is deterministic from tenant, engine/source, engine entity ID, event type, engine sequence/revision when present, and timestamp. It does not depend on receipt time.

### Lifecycle handler

```java
public interface EngineObservationHandler {
    ApplyResult apply(EngineObservation observation);
}

public record ApplyResult(String observationId, ApplyStatus status,
                          long caseVersion, List<String> eventIds) {}
```

`DefaultEngineObservationHandler.apply` is transactional. It claims the fingerprint, validates the linked process and tenant, applies projection state transitions, resolves contract mappings, applies canonical patches with optimistic conflict detection, transitions SLA anchors, writes audit, appends outbox events, and marks the fingerprint applied.

An embedded duplicate returns `DUPLICATE`; it does not increment versions or publish again. An observation older than the current entity engine timestamp is recorded as ignored/stale with audit-safe diagnostics; it cannot move state backwards.

### Canonical output mapping

Introduce `CaseDataMappingService`:

```java
public interface CaseDataMappingService {
    CanonicalPatch mapTaskOutput(String caseId, String taskDefinitionKey,
                                 Map<String, Object> engineVariables);
    PatchResult apply(CanonicalPatch patch);
}
```

The mapping reads the immutable contract bound to the case. It accepts only declared sources and targets, validates types, and records expected prior case version/value. Sensitive field values are redacted from general audit payloads; audit records field IDs and authorised change summaries.

### Transaction boundary

The embedded event bridge must run in the existing Operaton command transaction. It must not start an independent `REQUIRES_NEW` transaction. A forced failure in audit/outbox/SLA handling must fail the engine command so neither Operaton nor the case platform commits a half-result.

## File map

### Create

- `case-management-core/src/main/java/org/casemgmt/observation/EngineObservation.java`.
- `case-management-core/src/main/java/org/casemgmt/observation/ProcessObservation.java`.
- `case-management-core/src/main/java/org/casemgmt/observation/UserTaskObservation.java`.
- `case-management-core/src/main/java/org/casemgmt/observation/ActivityLifecycleObservation.java`.
- `case-management-core/src/main/java/org/casemgmt/observation/MilestoneObservation.java`.
- `case-management-core/src/main/java/org/casemgmt/observation/ObservationFingerprint.java`.
- `case-management-core/src/main/java/org/casemgmt/observation/EngineObservationHandler.java`.
- `case-management-core/src/main/java/org/casemgmt/observation/DefaultEngineObservationHandler.java`.
- `case-management-core/src/main/java/org/casemgmt/repo/AppliedObservationRepository.java`.
- `case-management-core/src/main/java/org/casemgmt/service/CaseDataMappingService.java`.
- `case-management-core/src/main/java/org/casemgmt/service/ContractCaseDataMappingService.java`.
- `case-management-core/src/main/java/org/casemgmt/service/CanonicalPatch.java`.
- `case-management-core/src/main/resources/db/changelog/cm-engine-observation-effects.xml`.
- Unit and Oracle integration tests in matching packages.

### Modify

- `case-management-core/src/main/resources/db/changelog/db.changelog-master.xml`.
- `case-management-core/src/main/java/org/casemgmt/projection/CaseProjectionPort.java` and `JdbcCaseProjectionPort.java` — become lower-level mutations called only by the handler.
- `case-management-core/src/main/java/org/casemgmt/service/CaseTaskService.java` — produce validated mapping intent; do not independently finalise canonical data.
- `case-management-core/src/main/java/org/casemgmt/repo/CaseRepository.java`, `AuditRepository.java`, `EventRepository.java`, and `SlaRepository.java`.
- `case-management-engine-embedded/src/main/java/org/casemgmt/engine/embedded/EmbeddedEngineEventBridge.java` — adapt callbacks to observations.
- `case-management-spring-boot-starter/src/main/java/org/casemgmt/starter/CaseManagementServiceConfiguration.java` and `EmbeddedEngineAutoConfiguration.java` — wire one handler.

## Test strategy

### Unit tests

- Fingerprint stability and differentiation for every observation type.
- Valid/invalid state transitions and stale observation handling.
- Output mapping direction, type, missing field, unknown field, and sensitive audit behavior.
- Duplicate apply returns `DUPLICATE` with no secondary calls.
- Wrong tenant/process/case link is rejected.

### Transactional integration tests

- Task completion updates task projection, canonical value, audit, and outbox in one commit.
- Inject failure at each effect and prove all effects roll back.
- Root completion closes case and calls the SLA terminalization port in the same transaction.
- Reapply the same observation and prove row counts and versions do not change.
- Apply a stale observation after a newer one and prove state does not regress.

### Embedded engine integration tests

- A real BPMN user task create/claim/complete sequence produces the same lifecycle observations and business effects.
- Stage entry/exit, milestone, subprocess completion, cancellation, and root completion are covered.
- Engine command failure caused by lifecycle persistence failure rolls back Operaton state.

### Contract tests for future remote reuse

- Serialize/deserialize each observation type without losing IDs, timestamps, or attributes.
- Feed the same fixture directly (embedded) and through a stored representation (remote simulation); assert identical database outcome.

## Acceptance scenarios

| ID | Scenario | Expected result |
|---|---|---|
| WS3-AC1 | Embedded task completes with mapped output. | Task projection completes, canonical field changes, one audit entry and one event appear in the same commit. |
| WS3-AC2 | Same task-completion observation is delivered twice. | Second apply is `DUPLICATE`; no version, audit, event, or SLA count changes. |
| WS3-AC3 | Audit/outbox insert fails during engine completion. | Engine task and all platform effects remain uncommitted. |
| WS3-AC4 | Output contains undeclared engine variable. | It remains engine-local and cannot alter canonical case data. |
| WS3-AC5 | Mapped output has wrong type. | Completion fails safely with the field/mapping path and no partial effects. |
| WS3-AC6 | Older task state arrives after completed state. | State stays completed; stale fact is recorded without a duplicate business event. |
| WS3-AC7 | Root process ends. | Case projection, audit, event, and SLA terminalization commit together. |
| WS3-AC8 | Observation tenant does not match linked case. | Entire observation is rejected and security telemetry is emitted. |
| WS3-AC9 | Legacy plan-model task completes. | Existing plan-model service path remains unchanged. |

## Implementation tasks

### Task 1: Define observation types and fingerprints

- [ ] Write `ObservationFingerprintTest` with stable fixtures for process, task, activity, and milestone events.
- [ ] Run the test and confirm missing types fail compilation.
- [ ] Implement the sealed observation hierarchy and SHA-256 fingerprint builder using canonical ordered fields.
- [ ] Run `./mvnw -pl case-management-core -Dtest=ObservationFingerprintTest test`.
- [ ] Commit with `git commit -m "feat: define engine observations"`.

### Task 2: Persist idempotent application claims

- [ ] Add failing Oracle tests for first claim, duplicate claim, failed apply retry, and concurrent claim.
- [ ] Add `CM_APPLIED_ENGINE_OBSERVATION` through a new changeset with unique tenant/fingerprint and status timestamps.
- [ ] Implement `AppliedObservationRepository.claim`, `markApplied`, and `markFailed` with transaction participation.
- [ ] Run `./mvnw -pl case-management-core -Dtest=AppliedObservationRepositoryTest,SchemaMigrationTest test`.
- [ ] Commit with `git commit -m "feat: deduplicate engine observations"`.

### Task 3: Implement typed canonical mappings

- [ ] Write tests for allowed input/output mappings, types, unknown sources, conflict metadata, and sensitive audit summaries.
- [ ] Implement `CanonicalPatch` and `ContractCaseDataMappingService` using Workstream 1 typed contracts.
- [ ] Add atomic compare-and-apply repository behavior based on case version and expected values.
- [ ] Run `./mvnw -pl case-management-core -Dtest=ContractCaseDataMappingServiceTest,CaseRepositoryTest test`.
- [ ] Commit with `git commit -m "feat: map BPMN outputs to canonical data"`.

### Task 4: Implement the common lifecycle handler

- [ ] Write handler tests that assert exact collaborator order/outcomes for every observation type.
- [ ] Implement validation, projection transition, mapping, audit, outbox, SLA port, and applied marker in one transaction.
- [ ] Add stale/out-of-order policy based on engine occurrence time plus stable engine revision when available.
- [ ] Run all observation/lifecycle unit tests.
- [ ] Commit with `git commit -m "feat: centralize engine lifecycle effects"`.

### Task 5: Prove atomicity against Oracle

- [ ] Add `EngineObservationTransactionalIntegrationTest` with injectable failures after each lifecycle effect.
- [ ] Assert rollback by querying every affected table after each failure.
- [ ] Assert duplicate replay does not change versions or row counts.
- [ ] Run the focused Oracle integration test.
- [ ] Commit with `git commit -m "test: prove lifecycle transaction atomicity"`.

### Task 6: Route embedded Operaton callbacks through the handler

- [ ] Extend `EmbeddedEngineEventBridgeTest` to capture canonical observation fixtures.
- [ ] Replace direct projection calls with observation construction and handler invocation.
- [ ] Add real-engine IT scenarios for task, activity, milestone, subprocess, cancel, and root end.
- [ ] Run `./mvnw -pl case-management-engine-embedded -am test`.
- [ ] Commit with `git commit -m "refactor: unify embedded lifecycle handling"`.

### Task 7: Run parity and regression gates

- [ ] Run core and embedded tests plus PoC embedded API tests.
- [ ] Assert no embedded adapter class writes projection repositories directly using an ArchUnit rule.
- [ ] Run `./mvnw clean install`.
- [ ] Commit with `git commit -m "test: enforce lifecycle handler boundary"`.

## Completion evidence

- One transaction trace showing projection, canonical data, audit, outbox, and SLA effects.
- Failure-injection report proving full rollback.
- Duplicate/stale replay report.
- Embedded real-engine lifecycle test report.
- ArchUnit proof that adapters do not bypass the lifecycle handler.

# Workstream 2: Release Lifecycle and Exact Engine Identities Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure a new case can start only from a fully validated active binding and always runs the exact Operaton process definition approved in that binding.

**Architecture:** Releases move through explicit validation and deployment states. Activation stores the exact Operaton deployment ID, process-definition ID, and tenant. Case start uses the exact process-definition ID; a BPMN key is descriptive metadata and never a runtime selector.

**Tech Stack:** Java 21, Spring Boot 4.0.7, Operaton 2.1.3 embedded and REST APIs, Oracle, Liquibase, JUnit 5, Mockito, AssertJ, Spring integration tests.

**Spec:** [`docs/plans/2026-08-27-bpmn-first-production-readiness-design.md`](2026-08-27-bpmn-first-production-readiness-design.md), sections 9.1, 9.2, review comments 1–3, acceptance Gate 2.

## Global constraints

- Only an `ACTIVE` case-definition binding may start a new case.
- A binding is immutable and pins orchestration, contract, and presentation releases.
- Persist exact `engineDeploymentId`, `engineProcessDefinitionId`, and `engineTenantId` for BPMN orchestration.
- Start by exact process-definition ID in embedded and remote modes.
- Keep correlation/command IDs separate from confirmed engine process-instance IDs.
- Preserve `PLAN_MODEL` and public API compatibility.
- Use additive Liquibase migrations; do not edit applied changesets.
- Never guess during backfill. Ambiguous historical releases remain unavailable until repaired.

---

## Problem statement

The current `ReleaseStatus` has only `DEPLOYING`, `ACTIVE`, and `FAILED`, while stored contract and presentation releases are created directly as `ACTIVE`. Version binding rejects only failed releases and “latest” selection can select a created/deploying definition. `BpmnOrchestration` starts by BPMN key, which tells Operaton to use its latest deployed version rather than the version approved by the binding. Remote root start also places a correlation placeholder where a real process-instance ID is expected and does not consistently replace it on the case row.

This breaks change control: deploying version 2 can change cases whose active binding points to version 1. It can also prevent a remotely completed root process from closing its case because the case still holds a placeholder.

## Scope

### In scope

- Release and binding lifecycle states.
- Exact engine identity persistence and deployment verification.
- Exact start request in core, embedded, and remote adapters.
- Root correlation versus confirmed identity.
- Historical data backfill and activation repair behavior.
- Multi-version, tenant, activation, and root-acknowledgement tests.

### Out of scope

- General command uncertainty and retries; Workstream 4 owns them.
- Observation inbox and paging; Workstream 5 owns them.
- Contract shape validation details; Workstream 1 owns them.

## Technical design

### State model

Use these release states:

```java
public enum ReleaseStatus {
    DRAFT,
    VALIDATED,
    DEPLOYING,
    ACTIVE,
    FAILED,
    RETIRED
}
```

`DRAFT -> VALIDATED -> DEPLOYING -> ACTIVE` is the successful path for orchestration releases. Contract and presentation releases use `DRAFT -> VALIDATED -> ACTIVE`. Failure records a bounded diagnostic. `ACTIVE -> RETIRED` stops new binding/activation but does not alter existing cases.

Give the version binding its own `BindingStatus` (`DRAFT`, `ACTIVE`, `RETIRED`, `FAILED`) rather than inferring usability from creation time. `CaseDefinitionRepository.findLatest` must either become `findLatestActive` or include an explicit `ACTIVE` predicate; call sites must not depend on ordering alone.

### Exact identity

Extend the immutable orchestration release identity:

```java
public record EngineDeploymentIdentity(
        String deploymentId,
        String processDefinitionId,
        String processDefinitionKey,
        Integer processDefinitionVersion,
        String tenantId) {}
```

Change core start request to:

```java
public record StartProcessRequest(
        String caseId,
        String processDefinitionId,
        String tenantId,
        String businessKey,
        Map<String, Object> variables,
        String operationId) {}
```

The embedded gateway calls `runtimeService.startProcessInstanceById`. The remote gateway posts to the REST start endpoint for the exact definition ID. Neither adapter falls back to key.

### Deployment verification

`OrchestrationDeploymentPort.deploy` returns `EngineDeploymentIdentity`, not only a deployment ID. The adapter queries definitions belonging to the returned deployment and tenant, finds exactly one executable root with the case-definition key, and rejects zero or multiple matches. The activation transaction stores the identity before marking the release active.

### Root correlation

Add a local `ROOT_CORRELATION_ID_`/linked-process correlation field. Keep `CM_CASE.ROOT_PROC_INST_ID_` null until Operaton confirms the real process-instance ID. `LinkedProcessRepository.confirmStarted` atomically updates both `CM_LINKED_PROCESS.PROC_INST_ID_` and `CM_CASE.ROOT_PROC_INST_ID_` when the link is the case root. A uniqueness constraint prevents two confirmed roots.

## File map

### Create

- `case-management-core/src/main/java/org/casemgmt/release/BindingStatus.java`.
- `case-management-core/src/main/java/org/casemgmt/orchestration/EngineDeploymentIdentity.java`.
- `case-management-core/src/main/resources/db/changelog/cm-bpmn-release-activation.xml`.
- `case-management-core/src/test/java/org/casemgmt/release/ReleaseLifecycleTest.java`.
- `case-management-core/src/test/java/org/casemgmt/service/ExactReleaseStartIntegrationTest.java`.
- `case-management-core/src/test/java/org/casemgmt/repo/LinkedProcessRootConfirmationTest.java`.

### Modify

- `case-management-core/src/main/resources/db/changelog/db.changelog-master.xml`.
- `case-management-core/src/main/java/org/casemgmt/release/ReleaseStatus.java`.
- `case-management-core/src/main/java/org/casemgmt/release/CaseDefinitionRelease.java`.
- `case-management-core/src/main/java/org/casemgmt/release/CaseDefinitionVersionBinding.java`.
- `case-management-core/src/main/java/org/casemgmt/orchestration/OrchestrationDeploymentPort.java` and implementations.
- `case-management-core/src/main/java/org/casemgmt/service/CaseDefinitionReleaseService.java`.
- `case-management-core/src/main/java/org/casemgmt/service/CaseDefinitionVersionService.java`.
- `case-management-core/src/main/java/org/casemgmt/repo/CaseDefinitionReleaseRepository.java`.
- `case-management-core/src/main/java/org/casemgmt/repo/CaseDefinitionVersionBindingRepository.java`.
- `case-management-core/src/main/java/org/casemgmt/repo/CaseDefinitionRepository.java`.
- `case-management-core/src/main/java/org/casemgmt/engine/StartProcessRequest.java`.
- `case-management-core/src/main/java/org/casemgmt/orchestration/BpmnOrchestration.java`.
- `case-management-core/src/main/java/org/casemgmt/repo/LinkedProcessRepository.java`.
- `case-management-core/src/main/java/org/casemgmt/service/LinkedProcessService.java`.
- `case-management-engine-embedded/src/main/java/org/casemgmt/engine/embedded/EmbeddedEngineGateway.java`.
- `case-management-engine-embedded/src/main/java/org/casemgmt/engine/embedded/EmbeddedOrchestrationDeploymentPort.java`.
- `case-management-engine-remote/src/main/java/org/casemgmt/engine/remote/RemoteEngineGateway.java`.
- `case-management-rest/src/main/java/org/casemgmt/rest/controller/CaseDefinitionReleaseController.java` and `CaseDefinitionController.java`.

## Database changes

- Add exact process-definition ID, key, numeric version, and tenant columns to orchestration release/binding storage.
- Add binding status and activation/retirement timestamps.
- Add root correlation ID separately from root engine instance ID.
- Add a filtered/function-based Oracle uniqueness rule or transactionally locked invariant ensuring one confirmed root per case.
- Backfill existing rows by deployment ID and tenant. Mark unambiguous rows active; mark ambiguous/unresolved rows failed with a repair reason.
- Add indexes for active definition lookup by tenant/key/version and engine identity lookup.

## Test strategy

### Unit tests

- Every legal and illegal release transition.
- Selectors exclude `DRAFT`, `VALIDATED`, `DEPLOYING`, `FAILED`, and `RETIRED`.
- Binding rejects any non-active constituent release.
- Start request requires a nonblank exact process-definition ID.
- Deployment result rejects no root, multiple roots, wrong tenant, or wrong key.

### Database integration tests

- Migration backfills one unambiguous deployment.
- Ambiguous deployment is not activated.
- Concurrent activation preserves one active binding version.
- Root confirmation updates case and linked process in one transaction.
- Failure between the two updates rolls back both.

### Embedded/remote integration tests

- Deploy BPMN v1 and v2 with the same key; activate v1; prove v1 starts.
- Repeat with v2 active.
- Prove tenant A cannot start tenant B's process definition.
- Remote acknowledgement replaces correlation state with the real root ID.

### API tests

- Deploy/publish returns non-active state until verification completes.
- Starting a case against a non-active binding returns a stable conflict response.
- Existing response fields remain; new lifecycle/identity fields are additive and appropriately permission-filtered.

## Acceptance scenarios

| ID | Scenario | Expected result |
|---|---|---|
| WS2-AC1 | v1 is active and v2 is deployed later under the same key. | A new case bound to v1 starts the exact v1 process-definition ID. |
| WS2-AC2 | Latest binding row is `DEPLOYING`. | Case creation selects the prior active binding or fails clearly; it never uses deploying content. |
| WS2-AC3 | Deployment contains no matching root or two matching roots. | Release becomes `FAILED`; no binding becomes active. |
| WS2-AC4 | Engine definition belongs to another tenant. | Activation/start fails; no cross-tenant process is created. |
| WS2-AC5 | Remote start is pending. | Root correlation exists, but root process-instance ID remains null. |
| WS2-AC6 | Remote start is confirmed. | Linked process and case root contain the same real engine ID in one commit. |
| WS2-AC7 | Root confirmation transaction fails halfway. | Neither row changes; retry safely completes both later. |
| WS2-AC8 | Historical deployment maps to multiple definitions. | Migration does not guess; release is unavailable with a repair diagnostic. |
| WS2-AC9 | Existing `PLAN_MODEL` case is created. | Existing selection and execution behavior remains unchanged. |

## Implementation tasks

### Task 1: Define lifecycle state machines

**Files:** `ReleaseStatus.java`, new `BindingStatus.java`, release/binding domain tests.

- [ ] Write parameterized failing tests for legal transitions and active-only selection.
- [ ] Run `./mvnw -pl case-management-core -Dtest=ReleaseLifecycleTest,CaseDefinitionVersionServiceTest test` and verify failure.
- [ ] Add state enums and transition guards; remove direct “stored means active” construction.
- [ ] Run the focused tests and expect pass.
- [ ] Commit with `git commit -m "feat: define release activation lifecycle"`.

### Task 2: Add additive identity and lifecycle migrations

**Files:** new `cm-bpmn-release-activation.xml`, master changelog, `SchemaMigrationTest.java`.

- [ ] Write migration assertions for new columns, constraints, indexes, and repeatability.
- [ ] Add a changeset that backfills only unambiguous identities and records unresolved rows.
- [ ] Run `./mvnw -pl case-management-core -Dtest=SchemaMigrationTest test` against Oracle Testcontainers.
- [ ] Commit with `git commit -m "feat: persist exact orchestration identities"`.

### Task 3: Verify deployment before activation

**Files:** deployment port and embedded/remote implementations, release service/repository tests.

- [ ] Write failing tests for zero/multiple/wrong-tenant roots and the successful exact result.
- [ ] Change deployment interfaces to return `EngineDeploymentIdentity`.
- [ ] Persist identity and activate in one transaction; persist failure diagnostic on definitive failure.
- [ ] Run core, embedded, and remote deployment tests.
- [ ] Commit with `git commit -m "feat: verify orchestration deployment activation"`.

### Task 4: Start by exact process-definition ID

**Files:** `StartProcessRequest.java`, `BpmnOrchestration.java`, both engine gateways, shared gateway contract.

- [ ] Extend `EngineGatewayContract` with a v1/v2 exact-selection assertion.
- [ ] Confirm it fails while start uses the key.
- [ ] Change both adapters to start by ID with tenant validation and stable operation correlation.
- [ ] Run `./mvnw -pl case-management-engine-embedded,case-management-engine-remote -am test`.
- [ ] Commit with `git commit -m "fix: start exact BPMN release"`.

### Task 5: Make definition selection active-only

**Files:** definition repositories/services and their tests.

- [ ] Add fixtures for every non-active state and assert they are excluded.
- [ ] Replace ambiguous `findLatest` usage with explicit active queries.
- [ ] Assert immutable bindings cannot be modified after activation.
- [ ] Run `./mvnw -pl case-management-core -Dtest=CaseDefinitionRepositoryTest,CaseDefinitionVersionServiceTest test`.
- [ ] Commit with `git commit -m "fix: select only active case definitions"`.

### Task 6: Separate root correlation from engine identity

**Files:** linked-process repository/service, case repository, migration, root tests.

- [ ] Write a failing transaction test for pending root, confirmed root, duplicate root, and rollback.
- [ ] Stop storing placeholders in `ROOT_PROC_INST_ID_`.
- [ ] Implement `confirmStarted(caseId, linkId, engineProcessInstanceId, confirmedAt)` as one transaction.
- [ ] Run repository and remote complaint tests.
- [ ] Commit with `git commit -m "fix: confirm remote root atomically"`.

### Task 7: Complete API and regression proof

- [ ] Add API tests for lifecycle states and non-active start rejection.
- [ ] Run `./mvnw clean install`.
- [ ] Capture Operaton v1/v2 engine IDs in test evidence.
- [ ] Commit with `git commit -m "test: prove exact release execution"`.

## Completion evidence

- Database migration report with active and unresolved historical counts.
- Embedded and remote v1/v2 test output proving exact IDs.
- Transaction test proving atomic root confirmation.
- API compatibility report and unchanged `PLAN_MODEL` tests.
- Evidence links for resolving review comments 1, 2, and 3.

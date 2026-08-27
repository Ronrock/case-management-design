# Workstream 7: Advanced Ad-hoc Actions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make discretionary `TASK`, `PROCESS`, and `MESSAGE` actions as safe, authorised, version-pinned, mapped, and auditable as planned BPMN work.

**Architecture:** The immutable contract exposes typed ad-hoc action definitions. `availableActions` is calculated by the server from case state, roles, Worker Permissions, and availability criteria, then rechecked at execution. Each variant delegates to the production lifecycle, exact-release, command, canonical-mapping, and observation services rather than maintaining special-case engine behavior.

**Tech Stack:** Java 21, Spring transactions/security, JUEL criteria, Worker Permissions, Operaton embedded/remote gateways, Oracle, JSON Schema 2020-12, JUnit 5, REST integration tests.

**Spec:** [`docs/plans/2026-08-27-bpmn-first-production-readiness-design.md`](2026-08-27-bpmn-first-production-readiness-design.md), section 9.11, acceptance Gate 7.

## Global constraints

- Only actions declared in the contract bound to the running case may execute.
- `availableActions` is advisory presentation; execution always revalidates authorization, state, version, and availability.
- `PROCESS` uses an exact approved process identity, never latest-by-key.
- Remote actions use Workstream 4 operation states and remain pending until confirmation.
- Outputs update canonical data only through Workstream 3 mappings.
- Requested and confirmed business events/audit records are distinct.
- All tenant and Worker Permissions checks happen before command creation.

---

## Problem statement

The current `AdHocActionService` reparses untyped contract maps and directly switches on strings. `PROCESS` accepts a process-definition key, `MESSAGE` can be correlated directly, and the service publishes an “executed” event immediately even when remote work is only pending. `TASK` creates local and engine records through a special path. These shortcuts can bypass exact release pinning, remote uncertainty handling, consistent mappings, and requested-versus-confirmed audit semantics.

Ad-hoc does not mean uncontrolled. Discretionary work can trigger the same customer, financial, compliance, or communication effects as planned BPMN work.

## Scope

### In scope

- Typed closed action variants from Workstream 1.
- Server calculation and execution recheck of action availability.
- Worker Permissions and tenant enforcement.
- `TASK`, `PROCESS`, and `MESSAGE` production semantics.
- Exact process allowlists and message correlation contracts.
- Form validation, input/output mappings, pending API responses, and audit/events.
- Embedded and remote success/failure/replay tests.

### Out of scope

- Adding new action variants beyond `TASK`, `PROCESS`, and `MESSAGE`.
- A general expression language replacement.
- Arbitrary process/message invocation not declared by the active contract.

## Technical design

### Typed definitions

Use the Workstream 1 sealed contract model:

```java
public sealed interface AdHocActionDefinition
        permits TaskAdHocAction, ProcessAdHocAction, MessageAdHocAction {
    String id();
    String label();
    Set<String> roles();
    String availabilityExpression();
    String permissionAction();
}

public record ProcessAdHocAction(
        String id, String label, Set<String> roles,
        String availabilityExpression, String permissionAction,
        String orchestrationReleaseId, String processDefinitionId,
        String formRef, List<DataMapping> mappings,
        CompletionPolicy completionPolicy) implements AdHocActionDefinition {}
```

The contract should normally reference an immutable orchestration release; publication resolves and stores the exact engine process-definition ID in the binding/action index. Runtime does not look up the latest key.

### Availability service

Create `AdHocActionAvailabilityService` that returns action descriptors with stable denial reasons internally. It checks terminal case state, optimistic case version, participant roles, Worker Permissions, criterion evaluation, pending conflicting operation, and referenced resources. Public APIs expose only allowed actions; execution errors use stable problem codes without leaking hidden policy details.

### Execution result

```java
public record AdHocOperationResult(
        String actionId, String type, String operationId,
        OperationStatus status, String planItemId,
        String taskId, String linkedProcessId) {}
```

Embedded actions may return a confirmed `201 Created`. Remote actions return `202 Accepted` and operation link. The “executed” event is replaced by `ad-hoc.requested`, followed by `ad-hoc.confirmed` or `ad-hoc.failed` from command/lifecycle evidence.

### Variant behavior

- **TASK:** Allocate stable platform plan-item/task IDs, validate form and mappings, create via the gateway/command path, and bind the engine task on confirmation. Do not expose the task as engine-confirmed while pending.
- **PROCESS:** Create a plan item/link correlation and start the exact permitted definition through the normal linked-process service. Apply the declared completion policy when the linked process ends.
- **MESSAGE:** Validate message name, target scope, correlation fields, and allowed payload. Use a stable operation ID. Confirm only from correlation acknowledgement/observation.

## File map

### Create

- `case-management-core/src/main/java/org/casemgmt/service/AdHocActionAvailabilityService.java`.
- `case-management-core/src/main/java/org/casemgmt/service/AdHocOperationResult.java`.
- `case-management-core/src/main/java/org/casemgmt/service/AdHocActionConfirmationHandler.java`.
- `case-management-core/src/test/java/org/casemgmt/service/AdHocActionAvailabilityServiceTest.java`.
- `case-management-core/src/test/java/org/casemgmt/service/AdHocActionServiceTransactionalIntegrationTest.java`.
- `case-management-poc-app/src/test/java/org/casemgmt/poc/AdHocActionsEmbeddedIT.java`.
- `case-management-poc-app/src/test/java/org/casemgmt/poc/AdHocActionsRemoteIT.java`.

### Modify

- `docs/schemas/case-contract-v1.schema.json` and Workstream 1 typed definitions.
- `case-management-core/src/main/java/org/casemgmt/service/AdHocActionService.java`.
- `case-management-core/src/main/java/org/casemgmt/service/FormValidator.java`.
- `case-management-core/src/main/java/org/casemgmt/service/LinkedProcessService.java`.
- `case-management-core/src/main/java/org/casemgmt/service/CaseTaskService.java`.
- `case-management-rest/src/main/java/org/casemgmt/rest/controller/AdHocActionController.java`.
- `case-management-rest/src/main/java/org/casemgmt/rest/policy/ActionPolicy.java`.
- `case-management-rest/src/main/java/org/casemgmt/rest/dto/Dtos.java`.
- Starter service wiring and sample contract/presentation resources.

## Test strategy

### Unit tests

- Typed parsing for every variant is covered in Workstream 1; service tests consume only typed definitions.
- Role and Worker Permissions allow/deny/unavailable behavior.
- Criterion true/false/error behavior.
- Terminal case and pending-conflict suppression.
- Input form and mapping allowlists.
- Requested/confirmed/failed event payloads and redaction.

### Transaction tests

- Permission or availability failure inserts no plan item, task, link, command, audit, or event.
- Embedded action commits all local/engine effects or none.
- Remote action commits plan/link correlation plus pending command and requested evidence atomically.
- Confirmation is replay-safe.

### End-to-end tests

- Embedded and remote `TASK`, `PROCESS`, and `MESSAGE` happy paths.
- Lost remote responses remain pending then reconcile once.
- Exact v1 action starts v1 after v2 deployment.
- Action disappears after case/data change and execution recheck rejects a stale UI invocation.
- Cross-tenant and Worker Permissions denial for every variant.
- Canonical output conflict follows Workstream 3 policy.

## Acceptance scenarios

| ID | Scenario | Expected result |
|---|---|---|
| WS7-AC1 | Allowed user executes an embedded `TASK`. | Task is created once with declared form/groups, and requested/confirmed evidence is complete. |
| WS7-AC2 | Remote `TASK` request is accepted. | API returns 202; task/action is pending until engine confirmation. |
| WS7-AC3 | `PROCESS` action pins v1 while v2 is latest. | Exact v1 process starts and links to the case. |
| WS7-AC4 | `MESSAGE` response is lost. | Operation awaits confirmation and is not blindly resent. |
| WS7-AC5 | Action was visible but availability becomes false before click reaches server. | Execution returns conflict; no side effect exists. |
| WS7-AC6 | Actor has contract role but Worker Permissions denies. | Action is not offered/executed; denial is safely audited. |
| WS7-AC7 | Input includes undeclared field. | Form/mapping validation rejects it before engine command creation. |
| WS7-AC8 | Same idempotency key is replayed. | Same operation/result is returned with one effect. |
| WS7-AC9 | Linked child process completes. | Declared plan-item completion policy applies through common lifecycle handler. |
| WS7-AC10 | Case is terminal. | No ad-hoc actions are available or executable unless an explicit terminal-safe variant is later versioned into the schema. |

## Implementation tasks

### Task 1: Consume typed action contracts

- [ ] Replace map/string parsing tests with typed `AdHocActionDefinition` fixtures.
- [ ] Refactor `AdHocActionService.requireAction` to use the validated contract repository/service.
- [ ] Remove support for ambiguous map-or-list runtime shapes after publication compatibility migration.
- [ ] Run core ad-hoc tests.
- [ ] Commit `refactor: use typed ad-hoc contracts`.

### Task 2: Centralize availability and authorization

- [ ] Write allow/deny/error/pending/terminal/stale-version tests.
- [ ] Implement `AdHocActionAvailabilityService` with roles, Worker Permissions, criteria, state, and pending-operation checks.
- [ ] Use it from both workspace `availableActions` composition and execution recheck.
- [ ] Run core and REST policy tests.
- [ ] Commit `feat: enforce ad-hoc availability`.

### Task 3: Implement production `TASK` behavior

- [ ] Write embedded/remote tests for stable IDs, form validation, pending state, confirmation, failure, and mapping.
- [ ] Delegate create/confirm to normal task/command/lifecycle services.
- [ ] Remove direct special-case projection finalization.
- [ ] Run focused task/ad-hoc tests.
- [ ] Commit `feat: complete discretionary task actions`.

### Task 4: Implement exact `PROCESS` behavior

- [ ] Write v1/v2, tenant, pending, confirmation, failure, and completion-policy tests.
- [ ] Resolve only the exact allowed orchestration identity at publication/binding time.
- [ ] Delegate runtime start/link/confirm to Workstreams 2 and 4 services.
- [ ] Run embedded and remote process-action tests.
- [ ] Commit `feat: pin discretionary process actions`.

### Task 5: Implement contracted `MESSAGE` behavior

- [ ] Write tests for allowed name/correlation/payload, unknown field, target absence, lost response, replay, and denial.
- [ ] Build message commands with stable operation ID and approved payload only.
- [ ] Confirm/fail through command and observation evidence.
- [ ] Run message-action tests.
- [ ] Commit `feat: secure discretionary message actions`.

### Task 6: Correct API and event semantics

- [ ] Write HTTP tests for 201 embedded, 202 remote, Location, pending status, operation lookup, ETag, and problem codes.
- [ ] Replace immediate executed event with requested/confirmed/failed sequence.
- [ ] Update DTOs, OpenAPI, sample UI manifest, and guide.
- [ ] Run REST and OpenAPI conformance tests.
- [ ] Commit `fix: report ad-hoc operation status truthfully`.

### Task 7: Run full variant and security matrix

- [ ] Execute all three variants in embedded and remote mode for allow, deny, stale, replay, engine reject, network uncertainty, and cross-tenant cases.
- [ ] Run `./mvnw clean install`.
- [ ] Commit `test: prove advanced ad-hoc actions`.

## Completion evidence

- Six happy-path traces: three variants in two engine modes.
- Exact process-version evidence for `PROCESS`.
- Lost-response and idempotency evidence for `MESSAGE`.
- Authorization/tenant test matrix.
- Requested/confirmed/failed audit and API examples.

# BPMN-only Production Delivery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the remaining BPMN-first production work with BPMN as the sole orchestration model, truthful remote operation state, Oracle-backed persistence proof, SLA and ad-hoc-action completion, and a final clean-room readiness gate.

**Architecture:** Remove legacy plan-model evaluation before extending the remaining runtime paths. Deliver remote behavior as a command-to-observation vertical slice: commands are accepted durably, projections stay confirmed until lifecycle evidence arrives, and reconciliation repairs uncertainty through the same handler. Build SLA and advanced actions on those boundaries, then prove the branch against a fresh Oracle schema and failure scenarios.

**Tech Stack:** Java 21, Spring transactions/scheduling, Liquibase, Oracle Database Free 23 (`gvenzl/oracle-free:23-slim-faststart`), Testcontainers 1.21.4, Operaton REST/embedded engine, JUnit 5, Micrometer, Maven, existing REST/OpenAPI tests.

**Spec:** [`docs/superpowers/specs/2026-08-29-bpmn-only-production-delivery-design.md`](../specs/2026-08-29-bpmn-only-production-delivery-design.md)

## Global constraints

- Accept and execute `BPMN` only; never reinterpret `PLAN_MODEL` data as BPMN.
- Halt clearly before use when an existing database contains an active legacy definition.
- A remote request is not a confirmed engine fact; only exact response/readback or authoritative observation may change confirmed projections.
- Every command payload, transition row, and operator action is tenant/type/target-bound, canonical, bounded, replayable, and fail-closed.
- Persisted changes are proven on Oracle; H2-only tests do not satisfy migration, concurrency, or transaction acceptance criteria.
- Keep existing compatible API fields additive where BPMN clients rely on them; remove only legacy-plan-model public behaviour.
- Each task starts with a failing test and ends with focused tests, a targeted Oracle gate when persistence changes, and one commit.
- Do not modify the currently uncommitted Task 3 hardening outside its dedicated task until its tests and review are complete.

---

## File structure

| Area | Responsibility |
|---|---|
| `case-management-core/.../orchestration`, `rules`, `observation`, `service` | BPMN-only release/lifecycle authority and common lifecycle application. |
| `case-management-core/.../engine`, `repo`, `resources/db/changelog` | Command state, payload/history validation, inbox/checkpoints, SLA occurrence persistence and migrations. |
| `case-management-engine-remote/.../remote` | Typed Operaton transport, history paging, reconciliation evidence, controllable HTTP-failure tests. |
| `case-management-rest/.../controller`, `dto`, `policy` | Pending operation and support APIs, authorization, redaction, accurate API responses. |
| `case-management-spring-boot-starter/...` | Production bean/scheduler wiring and metrics configuration. |
| `case-management-poc-app/.../test` and `docs/` | Oracle-backed acceptance evidence, migration rehearsal, operational runbook and release matrix. |

## Task 1: Complete and prove command-dispatch safety

**Files:**
- Modify: `case-management-core/src/main/java/org/casemgmt/engine/EngineCommandDispatcher.java`
- Modify: `case-management-core/src/main/java/org/casemgmt/engine/EngineCommandTransitionHistory.java`
- Modify: `case-management-core/src/main/java/org/casemgmt/engine/ProductionEngineCommandStore.java`
- Create/modify: `case-management-core/src/main/java/org/casemgmt/engine/EngineCommandPayload.java`
- Modify: `case-management-engine-remote/src/main/java/org/casemgmt/engine/remote/RemoteEngineGateway.java`
- Test: `case-management-core/src/test/java/org/casemgmt/engine/ProductionEngineCommandDispatcherTest.java`
- Test: `case-management-core/src/test/java/org/casemgmt/engine/EngineCommandTransitionHistoryTest.java`
- Test: `case-management-core/src/test/java/org/casemgmt/engine/EngineCommandPayloadTest.java`
- Test: `case-management-engine-remote/src/test/java/org/casemgmt/engine/remote/RemoteEngineGatewayTest.java`

**Consumes:** committed production command model, transition ledger, and policy from Workstream 4 Tasks 1–3.

**Produces:** a typed, fail-closed dispatch boundary that later task and reconciliation code can use without reclassifying transport failures.

- [ ] **Step 1: Preserve the existing failing regression tests for preclaimed work, tampered history, malformed payloads, wrong target/tenant, HTTP 202, partial task creation, and Retry-After dates.**

  The tests must prove: failure dispatching command A leaves command B unleased; changed normalized action evidence rejects rehydration; noncanonical JSON and numeric overflow reject rehydration; wrong payload causes zero HTTP calls; 202 remains awaiting confirmation; a failure after a remote primary effect remains uncertain rather than proven absent; a fixed `Clock` controls HTTP-date retry calculation.

- [ ] **Step 2: Run the focused tests before further production edits.**

  Run: `./mvnw -pl case-management-core,case-management-engine-remote -am test -Dtest='ProductionEngineCommandDispatcherTest,EngineCommandTransitionHistoryTest,EngineCommandPayloadTest,RemoteEngineGatewayTest'`

  Expected: each new regression fails on the unsafe implementation and identifies the missing invariant, rather than failing from setup.

- [ ] **Step 3: Implement the smallest safety changes.**

  The dispatcher claims exactly one due command before sending it. The history decoder enforces exact keys, integral ranges, duration ranges, bounded input, canonical re-encoding, and exact normalized operator-action equality. The payload codec binds all seven command payload variants to command tenant/type/target before persistence, rehydration, and HTTP. The gateway preserves actual status/evidence, treats non-terminal acceptance as pending, injects `Clock`, and records partial effects for idempotent readback/repair rather than retrying a completed primary effect.

- [ ] **Step 4: Run focused and Oracle persistence proof.**

  Run focused command/remote tests from Step 2, then: `./mvnw -pl case-management-core -am test -Dtest='ProductionEngineCommandStoreTest,EngineCommandClaimSafetyTest,SchemaMigrationTest'`.

  Expected: all tests pass; Testcontainers starts the repository-standard Oracle image; command migration/history rows survive a fresh Oracle schema.

- [ ] **Step 5: Commit the hardened command boundary.**

  Commit: `fix: harden remote command dispatch`

## Task 2: Remove the unused legacy plan model

**Files:**
- Delete: `case-management-core/src/main/java/org/casemgmt/orchestration/PlanModelOrchestration.java`
- Delete: `case-management-core/src/main/java/org/casemgmt/rules/PlanModelEvaluator.java`
- Delete: `case-management-core/src/main/java/org/casemgmt/rules/PlanModelInstantiator.java`
- Delete: `case-management-core/src/main/java/org/casemgmt/rules/PlanModelLoopException.java`
- Delete: `case-management-core/src/main/java/org/casemgmt/observation/LegacyPlanModelObservationHandler.java`
- Modify: `case-management-core/src/main/java/org/casemgmt/orchestration/OrchestrationMode.java`
- Modify: `case-management-core/src/main/java/org/casemgmt/service/CaseService.java`
- Modify: `case-management-core/src/main/java/org/casemgmt/service/CaseTaskService.java`
- Modify: `case-management-core/src/main/java/org/casemgmt/repo/CaseDefinitionRepository.java`
- Modify: `case-management-core/src/main/resources/db/changelog/*.xml`
- Modify: `case-management-spring-boot-starter/src/main/java/org/casemgmt/starter/CaseManagementServiceConfiguration.java`
- Modify: `case-management-spring-boot-starter/src/main/java/org/casemgmt/starter/CaseManagementPolicyConfiguration.java`
- Modify: `case-management-spring-boot-starter/src/main/java/org/casemgmt/starter/EmbeddedEngineAutoConfiguration.java`
- Modify: `case-management-engine-embedded/src/main/java/org/casemgmt/engine/embedded/EmbeddedEngineEventBridge.java`
- Modify/delete: legacy-only test fixtures, REST test-jar dependency, error mappings, schemas, guides, and tests found by `rg -n 'PLAN_MODEL|PlanModel'`.

**Consumes:** BPMN exact-release binding and common observation lifecycle already completed in Workstreams 1–3.

**Produces:** one orchestration mode with a preflight guard for residual legacy database data.

- [ ] **Step 1: Write removal contract tests.**

  Add tests proving a release with missing/`PLAN_MODEL` mode is rejected, a BPMN release still starts with its exact active identity, a persisted active legacy definition stops startup/migration with a stable remediation code, and no starter/embedded context contains legacy evaluator or handler beans.

- [ ] **Step 2: Run the removal tests to establish red.**

  Run: `./mvnw -pl case-management-core,case-management-engine-embedded,case-management-spring-boot-starter -am test -Dtest='*Orchestration*Test,*Legacy*Test,SchemaMigrationTest,AutoConfigurationTest'`

  Expected: failures identify existing acceptance/wiring of the legacy model.

- [ ] **Step 3: Remove legacy-only paths and narrow the persisted contract.**

  Replace the two-value mode with BPMN-only validation. Delete plan-model-only runtime types and test-only dependencies. Keep shared task/projection records used by BPMN. Add an additive, restart-safe Oracle precondition that halts if active legacy definitions exist; do not drop or convert rows silently. Simplify service branches and exception mappings so no deleted type remains reachable.

- [ ] **Step 4: Verify compiled surface and Oracle preflight behaviour.**

  Run the tests from Step 2, then: `./mvnw -pl case-management-core -am test -Dtest='SchemaMigrationTest,Ws2HistoricalMigrationRehearsalTest'`.

  Expected: BPMN-only migrations apply on a clean Oracle database; seeded legacy data fails at the documented guard; no production source reference remains from `rg -n 'PLAN_MODEL|PlanModel'`.

- [ ] **Step 5: Commit the BPMN-only hard cut.**

  Commit: `refactor: remove legacy plan model`

## Task 3: Deliver truthful task operations and support APIs

**Files:**
- Modify: `case-management-core/src/main/java/org/casemgmt/service/CaseTaskService.java`
- Create: `case-management-core/src/main/java/org/casemgmt/service/EngineOperationService.java`
- Modify: `case-management-core/src/main/java/org/casemgmt/engine/ProductionEngineCommandStore.java`
- Create/modify: `case-management-rest/src/main/java/org/casemgmt/rest/controller/EngineOperationController.java`
- Create/modify: `case-management-rest/src/main/java/org/casemgmt/rest/dto/EngineOperationResponse.java`
- Modify: `case-management-rest/src/main/java/org/casemgmt/rest/controller/TaskController.java`
- Modify: `case-management-rest/src/main/java/org/casemgmt/rest/policy/ActionPolicy.java`
- Modify: `case-management-spring-boot-starter/src/main/java/org/casemgmt/starter/{RemoteEngineAutoConfiguration,CaseManagementSchedulers}.java`
- Test: core task/operation tests, REST operation and task HTTP tests, starter wiring tests.

**Consumes:** Task 1 command semantics and Task 2 BPMN-only task lifecycle.

**Produces:** a pending/confirmed API contract and authorised support actions with no direct projection mutation.

- [ ] **Step 1: Write API and service tests.**

  Cover remote claim and complete returning `202`, `Location`, operation ID, pending action, unchanged confirmed task state, suppressed conflicting actions, operation tenant isolation/redaction, and authorised retry/reconcile/cancel audit. Cover embedded synchronous compatibility as a confirmed outcome.

- [ ] **Step 2: Run tests to establish red.**

  Run: `./mvnw -pl case-management-core,case-management-rest,case-management-spring-boot-starter -am test -Dtest='CaseTaskServiceTest,*EngineOperation*Test,*Task*HttpTest,AutoConfigurationTest'`

- [ ] **Step 3: Implement request intent and operation resources.**

  Store canonical patch intent with the command; task services return requested state without changing confirmation fields. Compose availability from confirmed state plus active commands. Implement operation lookup and support endpoints through the command store, authorization policy, and audit publisher. Do not expose payloads, credentials, raw remote bodies, or another tenant's operation.

- [ ] **Step 4: Verify API contract and Oracle command/task atomicity.**

  Run the focused suite, then `./mvnw -pl case-management-rest,case-management-poc-app -am test -Dtest='OpenApiConformanceIT,*Operation*IT,*Task*IT'`.

  Expected: remote operations remain pending until common lifecycle confirmation; duplicate idempotency returns the existing operation.

- [ ] **Step 5: Commit truthful operation APIs.**

  Commit: `feat: expose truthful engine operations`

## Task 4: Build durable remote observations and reconciliation

**Files:**
- Create: `case-management-core/src/main/java/org/casemgmt/observation/{ObservationStream,ObservationCursor,ObservationEnvelope,InboxStatus}.java`
- Create: `case-management-core/src/main/java/org/casemgmt/repo/{ObservationInboxRepository,ObservationCheckpointRepository}.java`
- Create: `case-management-core/src/main/java/org/casemgmt/service/{ObservationInboxService,RemoteReconciliationService}.java`
- Create: `case-management-core/src/main/resources/db/changelog/cm-remote-observation-inbox.xml`
- Create/modify: `case-management-engine-remote/src/main/java/org/casemgmt/engine/remote/{RemoteHistoryClient,RemoteObservationPoller}.java`
- Modify: common observation factory/handler and starter schedulers.
- Test: Oracle inbox/checkpoint tests, remote paging/failure tests, lifecycle idempotency tests, reconciliation tests.

**Consumes:** common lifecycle handler, Task 1 command evidence, and Task 3 operation/support state.

**Produces:** complete paged ingestion and reconciliation that writes observations rather than mutating projections directly.

- [ ] **Step 1: Write inbox/checkpoint and page-window failures first.**

  Cover independent tenant/stream cursors; 499, 500, 501, 1,000, and 1,201 records including equal timestamps; page insertion rollback; retry overlap deduplication; poison/dead-letter records; and reconciliation of an awaiting-confirmation command.

- [ ] **Step 2: Run red against real Oracle and controllable HTTP.**

  Run: `./mvnw -pl case-management-core,case-management-engine-remote -am test -Dtest='*ObservationInbox*Test,*Checkpoint*Test,*RemoteHistory*Test,*Reconciliation*Test'`.

- [ ] **Step 3: Implement page-complete checkpoints and lifecycle application.**

  Insert each page transactionally with bounded canonical payload/fingerprint. Advance a checkpoint only after every page in a window is durable. Claim/apply inbox rows through the common handler. Make reconcilers emit normal observations using command/target evidence, never direct projection writes. Retain safe diagnostics and audited replay support.

- [ ] **Step 4: Prove lost-response and high-volume recovery.**

  Run the focused suite plus `./mvnw -pl case-management-engine-remote,case-management-poc-app -am test -Dtest='*LostResponse*Test,*RemoteHighVolume*IT,*Reconciliation*IT'`.

  Expected: exactly one lifecycle effect per fingerprint, no unread record skipped, and uncertain commands resolve only with proof.

- [ ] **Step 5: Commit remote observation/reconciliation delivery.**

  Commit: `feat: reconcile remote engine observations`

## Task 5: Complete SLA lifecycle and delivery

**Files:**
- Create/modify: SLA domain records, repositories, services, and Liquibase changelog files under `case-management-core/src/main/java/org/casemgmt/{sla,repo,service}` and `resources/db/changelog`.
- Modify: common lifecycle handler/root terminalisation ports and starter SLA scheduler/configuration.
- Modify: remote/embedded message delivery adapters only through the command/observation boundary.
- Create/modify: SLA REST DTO/controller/policy, metrics, and operational documentation.
- Test: SLA contract, occurrence repository, sweeper race, root termination, calendar/DST, message deduplication, Oracle migration tests.

**Consumes:** Task 4 observation/reconciliation and BPMN-only root lifecycle.

**Produces:** one SLA truth source whose occurrence transition and BPMN notification are independently idempotent.

- [ ] **Step 1: Write failing SLA invariant tests.**

  Cover exact start/pause/resume/complete/cancel transition rules, calendar/DST boundaries, concurrent sweep/terminal case race, case root completion terminating remaining occurrences, duplicate BPMN notification, and tenant-scoped support APIs.

- [ ] **Step 2: Run the focused red suite.**

  Run: `./mvnw -pl case-management-core,case-management-rest,case-management-engine-remote -am test -Dtest='*Sla*Test,*Sla*IT,*RootTerminal*Test'`.

- [ ] **Step 3: Implement occurrence persistence and deterministic transitions.**

  Persist occurrence identity and versioned transition evidence in Oracle. Anchor lifecycle events through the common handler. The sweeper claims due occurrences safely; it emits an idempotent BPMN message command without changing SLA truth based on message transport alone. Root terminalisation closes/cancels all nonterminal occurrences in the same lifecycle transaction.

- [ ] **Step 4: Verify Oracle race and operational behaviour.**

  Run the focused tests plus `./mvnw -pl case-management-core,case-management-poc-app -am test -Dtest='*Sla*IT,SchemaMigrationTest'`.

- [ ] **Step 5: Commit SLA completion.**

  Commit: `feat: complete BPMN SLA lifecycle`

## Task 6: Deliver controlled ad-hoc actions

**Files:**
- Create/modify: `case-management-core/src/main/java/org/casemgmt/action/{AdHocActionDefinition,AdHocActionAvailabilityService,AdHocActionService}.java`
- Modify: contract publication/parser and JSON schema files for typed action definitions.
- Modify: task/process/message command creation, lifecycle completion mapping, REST action controller/DTOs/policy, OpenAPI and guide.
- Test: core action availability/execution tests; remote/embedded action ITs; security and idempotency HTTP tests.

**Consumes:** exact BPMN release identity, Task 3 operation API, Task 4 reconciliation, and Task 5 SLA/lifecycle contracts.

**Produces:** TASK, PROCESS, and MESSAGE actions that use normal command/observation evidence and cannot bypass authorization or release binding.

- [ ] **Step 1: Write failing variant tests.**

  Cover typed contract parsing, allow/deny/terminal/pending/stale checks, exact BPMN v1/v2 process selection, task form validation/canonical mapping, message target/correlation validation, lost response, duplicate idempotency, cross-tenant denial, and requested/confirmed/failed API/event states.

- [ ] **Step 2: Run focused tests to establish red.**

  Run: `./mvnw -pl case-management-core,case-management-rest,case-management-engine-embedded,case-management-engine-remote -am test -Dtest='*AdHoc*Test,*AdHoc*IT,*ActionPolicy*Test'`.

- [ ] **Step 3: Implement typed contracts and one availability gate.**

  Reject undeclared action fields at publication/execution. Evaluate roles, worker permissions, case state, release identity, criteria, expected version, and pending operation status once, and recheck just before submit. Delegate all variants to the normal task/process/message command and common lifecycle services. Remove direct special-case projection completion.

- [ ] **Step 4: Verify both engine modes and security.**

  Run focused tests plus `./mvnw -pl case-management-poc-app -am test -Dtest='*AdHoc*IT,*Security*IT,OpenApiConformanceIT'`.

- [ ] **Step 5: Commit controlled ad-hoc actions.**

  Commit: `feat: deliver controlled ad-hoc actions`

## Task 7: Establish production evidence and the final clean-room gate

**Files:**
- Create: `case-management-poc-app/src/test/java/org/casemgmt/poc/{BpmnFirstAcceptanceIT,BpmnFirstSecurityIT,BpmnFirstFailureRecoveryIT}.java`
- Create: `case-management-core/src/test/java/org/casemgmt/BpmnFirstMigrationIT.java`
- Create/modify: `case-management-engine-remote/src/test/java/org/casemgmt/engine/remote/RemoteHighVolumeIT.java`
- Create: `docs/plans/bpmn-first-release-readiness-checklist.md`
- Create: `docs/guide/bpmn-first-operations.md`
- Modify: `.github/workflows/ci.yml`, root/module `pom.xml`, OpenAPI/generic-client checks, system/operations documentation.

**Consumes:** all earlier slices.

**Produces:** objective release evidence for BPMN-only production readiness.

- [ ] **Step 1: Write discovery and clean-room test assertions.**

  Add a discovery sentinel for `*IT` classes and acceptance fixtures for exact v1/v2 releases, tenant copies, synthetic non-sensitive data, SLA calendars, and 499/500/501/1,000/1,201 history records. Replace legacy-compatibility assertions with BPMN-only rejection/preflight assertions.

- [ ] **Step 2: Run tests to show missing cross-slice evidence.**

  Run: `./mvnw -B -pl case-management-poc-app,case-management-core -am test -Dtest='BpmnFirstAcceptanceIT,BpmnFirstSecurityIT,BpmnFirstFailureRecoveryIT,BpmnFirstMigrationIT'`.

- [ ] **Step 3: Implement acceptance, migration, CI, and operations evidence.**

  Assert embedded/remote business parity for exact binding, confirmed projection/data/audit/event/SLA outcomes, response loss, replay, concurrency, high volume, restart, poison records, authorization, and rollback-compatible additive schema. Add CI jobs that publish counts/reports and run Oracle capacity-sensitive suites with explicit timeouts. Write the evidence matrix and operator procedures for pause, reconcile, replay, retry, cancel, and SLA-message disablement.

- [ ] **Step 4: Run all mandatory gates from a clean checkout.**

  Run: `./mvnw -B clean install`.

  Run web/modeler checks when their directories are present: `npm ci && npm test && npm run build` and `npm ci && npm test`.

  Expected: discovered tests are nonzero, Oracle migration/rehearsal succeeds, no disabled/quarantined required suite, and logs contain no secrets.

- [ ] **Step 5: Commit release readiness evidence.**

  Commit: `test: prove BPMN-only production readiness`

## Final acceptance matrix

| Invariant | Required evidence |
|---|---|
| BPMN is the sole orchestration mode | Release/schema/Oracle preflight tests and zero production `PLAN_MODEL` references. |
| Exact approved process version runs | Embedded and remote v1/v2 acceptance tests. |
| Remote truth is not premature | 202/pending API tests, lost-response tests, command history, observation confirmation. |
| No command evidence can be forged | Payload/history/action tamper tests and Oracle rehydration checks. |
| Polling loses no history | Page-volume/equal-time/restart tests with per-stream checkpoint assertions. |
| SLA is consistent with lifecycle | Oracle sweeper/root-terminal race and post-completion silence tests. |
| Ad-hoc actions remain controlled | Three-variant embedded/remote authorization/version/idempotency matrix. |
| Release is operationally safe | Fresh Oracle migration, full reactor, CI reports, evidence index, and runbook rehearsal. |

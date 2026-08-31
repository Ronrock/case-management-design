# PR #89 Review Findings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every actionable finding from the latest review of PR #89 with regression evidence, accurate architecture documentation, a reliable Java gate, and an operationally useful PR description.

**Architecture:** BPMN remains the sole behavioral authority. Remote engine history is converted into the same durable observation inbox used by incremental polling, while SLA behavior is admitted only through a typed, publication-validated contract and immutable tenant-scoped calendar revisions. All schema changes are additive so the release can roll forward or back without rewriting existing legacy calendar rows.

**Tech Stack:** Java 21, Spring JDBC, Oracle/Liquibase, Operaton REST history API, JSON Schema, JUnit 5, Testcontainers, Maven reactor, GitHub Actions.

**Source review:** [PR #89](https://github.com/Ronrock/case-management-design/pull/89), review `5067948007`, submitted 2026-08-31.

## Review Assessment

The current checkout is commit `63fa63b16d79e42d6947090ce2e480371d3cccdf`, the head of PR #89. The local branch itself has no associated PR, so this plan deliberately targets PR #89 because its head matches the checkout and it is the open PR with changes requested.

| Review finding | Assessment | Disposition |
|---|---|---|
| Pinned orchestration release was ignored at case start | Reviewer verified the fix; start now resolves the binding's pinned engine identity. | No further code change; retain regression test. |
| A `DEPLOYING` release could become selectable | Current publication/binding lifecycle selects only active releases and activates the binding only after deployment identity is verified. | No further code change; retain lifecycle tests. |
| Root-process placeholder could remain stale | Reviewer verified the fix. | No further code change. |
| Contract remained a second behavioral authority | Runtime `PLAN_MODEL` behavior was removed, but architecture docs still describe command/state-machine files as normative. | Fix in Task 4. |
| Task outputs bypassed canonical mappings | Reviewer verified the fix. | No further code change. |
| SLA contract schema accepted incomplete semantics | Shape/order defects were fixed, but arbitrary anchors/actions and ignored `messageName` still allow rules that never execute. | Fix in Task 3. |
| Root completion left SLA clocks running | Reviewer verified the fix. | No further code change. |
| Fixed 500-row fetch lost history | Incremental polling is now paged, but that does not repair history older than the first ten-minute lookback. | Superseded by the full-reconciliation fix in Task 1. |
| BPMN modeling vocabulary was inconsistent | Reviewer verified the vocabulary fix. | No further code change. |
| [`RemoteObservationPoller.reconcileAllActive`](https://github.com/Ronrock/case-management-design/pull/89#discussion_r3895629335) only observes terminal roots | Confirmed. It does not rebuild open/terminal tasks, stages, milestones, or linked subprocess projections. | Fix in Task 1. |
| [`calendarRevision`](https://github.com/Ronrock/case-management-design/pull/89#discussion_r3895629342) does not select a calendar revision | Confirmed. Runtime queries mutable `CM_BUSINESS_CALENDAR` by ID without tenant or revision. | Fix in Task 2. |
| [SLA anchors and breach actions](https://github.com/Ronrock/case-management-design/pull/89#discussion_r3895629345) accept unsupported values | Confirmed. Unknown strings pass publication and silently do nothing; `messageName` is accepted by the schema and discarded by the typed model. | Fix in Task 3. |
| [Functional-requirements assessment](https://github.com/Ronrock/case-management-design/pull/89#discussion_r3895629354) cites deleted runtime classes | Confirmed, with the same conceptual drift in `system-overview.md`. | Fix in Task 4. |
| [Java reactor check](https://github.com/Ronrock/case-management-design/pull/89#discussion_r3895629358) is red and hides downstream results | Confirmed. The mutable Oracle image resolved to a newer database and the custom SPFILE startup failed, after which Maven skipped all downstream modules. | Fix in Task 5. |
| [Compatibility proposal](https://github.com/Ronrock/case-management-design/pull/89#discussion_r3895629361) still promises legacy plan-model support | Confirmed; it contradicts the implemented BPMN-only migration preflight. | Fix in Task 4. |
| `declarative-case-model-architecture.md` still makes `behavior/*.yaml` normative | Confirmed in the follow-up on the earlier authority thread. | Fix in Task 4. |
| PR has no description despite a 399-file, 52k-line change | Confirmed at review time. Reviewers cannot reconstruct breaking changes, migration decisions, or rollout evidence from the diff alone. | Fix in Task 6. |

All review threads are currently unresolved, including items that the reviewer explicitly marked verified. Resolve threads only after the corresponding regression evidence is green; for no-change items, reply with the existing test/evidence before resolving.

## Global Constraints

- BPMN owns sequencing, lifecycle, task activation, gateways, timers, and engine correlation. The contract may describe canonical data, forms, authorization, search/presentation, mappings, SLA monitoring, and explicitly external capabilities, but must not introduce a parallel transition engine.
- Remote reconciliation must emit ordinary durable observations and drain the inbox; it must never update projections directly.
- Absence from a remote query is not terminal evidence. Emit completion/deletion/cancellation only from an explicit history field such as `endTime`, `deleteReason`, or `canceled`.
- Published contract-derived calendars are immutable by `(tenantId, calendarId, revision)`. Do not reinterpret existing tenantless `CM_BUSINESS_CALENDAR` rows as versioned contract calendars.
- Keep legacy SLA policy/calendar APIs working through their existing table. The new revision catalog is for contract-derived SLA bindings.
- Liquibase changes are append-only and guarded. Do not rewrite, drop, or repurpose applied change sets.
- Preserve the required GitHub check name `Java reactor` through a final aggregation job so branch protection does not need an out-of-band edit.

---

## Task 1: Rebuild every remote projection from full history

**Files:**

- Modify: `case-management-core/src/main/java/org/casemgmt/projection/ActiveBpmnCaseRepository.java`
- Modify: `case-management-engine-remote/src/main/java/org/casemgmt/engine/remote/RemoteObservationPoller.java`
- Modify: `case-management-engine-remote/src/main/java/org/casemgmt/engine/remote/RemoteProcessActivityClassifier.java`
- Test: `case-management-engine-remote/src/test/java/org/casemgmt/engine/remote/RemoteObservationPollerHttpTest.java`
- Test: `case-management-engine-remote/src/test/java/org/casemgmt/engine/remote/RemoteProcessActivityClassifierTest.java`

- [ ] **Step 1: Add failing cold-start inventory and reconstruction tests**

  Add an active case whose root and synced linked subprocess have history older than `INITIAL_LOOKBACK`. Mock full history containing:

  - an open task and a completed/deleted task;
  - a running and completed stage activity;
  - a reached milestone;
  - a completed or terminated linked subprocess;
  - a root process that is still active.

  Assert `pollOnce()` alone does not see the old facts, then assert `reconcileAllActive()` persists the expected task, activity, milestone, and process envelopes and drains them through the projection handler. Also assert the reconciliation query pages past 500 rows.

  Add a negative test where a row is absent from current history but has no explicit terminal evidence; assert reconciliation does not synthesize a deletion.

  Run:

  ```bash
  ./mvnw -pl case-management-engine-remote -Dtest=RemoteObservationPollerHttpTest test
  ```

  Expected: the new tests fail because only the root process endpoint is queried.

- [ ] **Step 2: Expose a reconciliation inventory for roots and synced linked processes**

  Replace the root-only `ActiveCase` result with an immutable reconciliation shape, for example:

  ```java
  public record ReconciliationProcess(
          String caseId,
          String tenantId,
          String engineId,
          String processInstanceId,
          String processDefinitionId,
          boolean root) {}
  ```

  Add `findAllProcessesForActiveCases()` that returns the root process plus every `CM_LINKED_PROCESS.PROC_INST_ID_` for active cases. Include linked rows regardless of their current projected state so reconciliation can correct a stale `ACTIVE`, `COMPLETED`, or `TERMINATED` projection. Deduplicate by process instance ID.

  Keep `findAll()` temporarily only if another caller needs it; otherwise remove it with the root-only reconciliation implementation.

- [ ] **Step 3: Share paged full-history collection with incremental polling**

  Refactor the remote HTTP conversion path so both polling modes use `taskEnvelope`, `activityEnvelope`, and `processEnvelope`.

  For each reconciliation process, page unbounded-by-time endpoints filtered by `processInstanceId`:

  ```text
  /history/task?processInstanceId=...&firstResult=...&maxResults=500&sortBy=taskId&sortOrder=asc
  /history/activity-instance?processInstanceId=...&firstResult=...&maxResults=500&sortBy=activityInstanceId&sortOrder=asc
  /history/process-instance/{processInstanceId}
  ```

  Persist open and terminal task/activity rows into the appropriate observation stream. The full pass must not advance incremental time cursors because it is an inventory repair, not a completed time window.

- [ ] **Step 4: Preserve milestone semantics and SLA metadata during remote conversion**

  Extend `RemoteProcessActivityClassifier.Classification` and `TaskMetadata` to return `slaTargetId` alongside existing metadata. In `activityEnvelope`, branch on `Classification.kind()`:

  - `STAGE` -> `ActivityLifecycleObservation`;
  - `MILESTONE` -> `MilestoneObservation` with the classifier's `milestoneId`.

  Add `slaTargetId` to observation attributes for stages, milestones, and tasks. Do not reduce milestone history to a stage projection.

- [ ] **Step 5: Prove replay safety and full reconstruction**

  Run:

  ```bash
  ./mvnw -pl case-management-engine-remote test
  ./mvnw -pl case-management-core,case-management-engine-remote -am test
  ```

  Expected: all tests pass; running `reconcileAllActive()` twice produces no duplicate projection state because inbox fingerprints and projection versions make replay idempotent.

- [ ] **Step 6: Commit the reconciliation slice**

  ```bash
  git add case-management-core/src/main/java/org/casemgmt/projection/ActiveBpmnCaseRepository.java case-management-engine-remote/src/main/java/org/casemgmt/engine/remote/RemoteObservationPoller.java case-management-engine-remote/src/main/java/org/casemgmt/engine/remote/RemoteProcessActivityClassifier.java case-management-engine-remote/src/test/java/org/casemgmt/engine/remote/RemoteObservationPollerHttpTest.java case-management-engine-remote/src/test/java/org/casemgmt/engine/remote/RemoteProcessActivityClassifierTest.java
  git commit -m "fix: rebuild remote projections from history"
  ```

---

## Task 2: Make SLA calendar revisions tenant-scoped and immutable

**Files:**

- Create: `case-management-core/src/main/resources/db/changelog/cm-sla-calendar-revisions.xml`
- Modify: `case-management-core/src/main/resources/db/changelog/db.changelog-master.xml`
- Modify: `db-design.sql`
- Create: `case-management-core/src/main/java/org/casemgmt/sla/SlaCalendarCatalog.java`
- Modify: `case-management-core/src/main/java/org/casemgmt/repo/SlaRepository.java`
- Modify: `case-management-core/src/main/java/org/casemgmt/sla/SlaLifecycleService.java`
- Modify: `case-management-core/src/main/java/org/casemgmt/service/CaseDefinitionVersionService.java`
- Modify: `case-management-core/src/main/java/org/casemgmt/service/CombinedCaseDefinitionDeploymentService.java`
- Modify: `case-management-spring-boot-starter/src/main/java/org/casemgmt/starter/CaseManagementServiceConfiguration.java`
- Test: `case-management-core/src/test/java/org/casemgmt/sla/SlaLifecycleServiceTest.java`
- Test: `case-management-core/src/test/java/org/casemgmt/service/CaseDefinitionVersionServiceTest.java`
- Create: `case-management-core/src/test/java/org/casemgmt/repo/SlaCalendarRevisionRepositoryTest.java`
- Create: `case-management-core/src/test/java/org/casemgmt/SlaCalendarRevisionMigrationTest.java`

- [ ] **Step 1: Write failing revision, tenant-isolation, and publication tests**

  Cover these invariants:

  - tenant A and tenant B may publish the same calendar ID/revision with different definitions;
  - reinserting the same `(tenant, id, revision)` and digest is idempotent;
  - reinserting that key with a different digest is rejected;
  - a contract binding is rejected before any artifact is published when its exact calendar revision is missing;
  - creating a later occurrence from an already pinned contract still uses revision 1 after revision 2 is inserted;
  - the SLA occurrence stores the selected revision, digest, and definition snapshot.

  Run the focused tests and confirm they fail on ID-only lookup.

- [ ] **Step 2: Add an append-only revision catalog**

  Create `CM_BUSINESS_CALENDAR_REVISION` with:

  ```text
  TENANT_ID_       VARCHAR2(64)  NOT NULL
  CALENDAR_ID_     VARCHAR2(128) NOT NULL
  REVISION_        NUMBER(10)    NOT NULL
  NAME_            VARCHAR2(255) NOT NULL
  DEFINITION_JSON_ CLOB          NOT NULL
  SHA256_          VARCHAR2(64)  NOT NULL
  CREATED_AT_      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
  PK (TENANT_ID_, CALENDAR_ID_, REVISION_)
  UNIQUE (TENANT_ID_, CALENDAR_ID_, SHA256_)
  CHECK (DEFINITION_JSON_ IS JSON)
  ```

  Add nullable `CALENDAR_SHA256_ VARCHAR2(64)` to `CM_SLA_RECORD`; legacy rows remain valid. Guard the change set using the same exact-shape/HALT conventions as `cm-sla-lifecycle.xml`. Add the changelog last in `db.changelog-master.xml` and reflect the target schema in `db-design.sql`.

  Do not modify the primary key or semantics of `CM_BUSINESS_CALENDAR`; legacy `SlaService` continues to use it.

- [ ] **Step 3: Introduce a typed immutable calendar lookup**

  Define:

  ```java
  public interface SlaCalendarCatalog {
      record Revision(String tenantId, String calendarId, int revision,
                      String sha256, Map<String, Object> definition) {}

      Revision require(String tenantId, String calendarId, int revision);
  }
  ```

  Implement it in `SlaRepository` with `insertCalendarRevision(...)` for deployment/seeding and an exact three-column lookup. Canonicalize JSON before hashing so formatting changes do not create a false revision identity.

- [ ] **Step 4: Validate exact calendar revisions before publication and binding**

  Thread `tenantId` into `CaseDefinitionVersionService.validateArtifacts(...)` and `validateBoundArtifacts(...)`. For every contract SLA binding, call the catalog with `(tenantId, calendarId, calendarRevision)` and report a bounded `InvalidCaseDefinitionException` when absent.

  Pass the tenant through `CombinedCaseDefinitionDeploymentService.deploy(...)` before its first `publish(...)` call. This preserves the current no-partial-evidence guarantee.

  Wire the catalog into `CaseDefinitionVersionService` from `CaseManagementServiceConfiguration`; update compatibility constructors in tests explicitly instead of allowing production to silently skip calendar validation.

- [ ] **Step 5: Resolve and snapshot the exact revision at runtime**

  In `SlaLifecycleService.createOccurrence`, replace `calendarExists(id)` plus `calendarDefinition(id)` with `catalog.require(caseTenant, id, revision)`. Calculate due/warning timestamps from that returned definition and persist its canonical JSON plus SHA-256 in the occurrence.

  Runtime lookup failure after publication is an invariant violation and must fail loudly; it must not fall back to the mutable legacy row or another revision.

- [ ] **Step 6: Prove migration and runtime compatibility**

  Run:

  ```bash
  ./mvnw -pl case-management-core -Dtest=SlaLifecycleServiceTest,CaseDefinitionVersionServiceTest test
  ./mvnw -pl case-management-core test
  ```

  Also run the repository's Oracle migration/restart test suite. Expected: legacy calendar policy tests still pass, new contract occurrences use the exact tenant revision, and Liquibase succeeds both from a clean schema and an upgraded pre-change schema.

- [ ] **Step 7: Commit the calendar migration slice**

  ```bash
  git add case-management-core/src/main/resources/db/changelog case-management-core/src/main/java/org/casemgmt/sla case-management-core/src/main/java/org/casemgmt/repo/SlaRepository.java case-management-core/src/main/java/org/casemgmt/service/CaseDefinitionVersionService.java case-management-core/src/main/java/org/casemgmt/service/CombinedCaseDefinitionDeploymentService.java case-management-spring-boot-starter/src/main/java/org/casemgmt/starter/CaseManagementServiceConfiguration.java case-management-core/src/test db-design.sql
  git commit -m "fix: pin tenant SLA calendar revisions"
  ```

---

## Task 3: Admit only executable SLA anchors and breach actions

**Files:**

- Modify: `docs/schemas/case-contract-v1.schema.json`
- Modify: `case-management-core/src/main/resources/schemas/case-contract-v1.schema.json`
- Modify: `case-management-core/src/main/java/org/casemgmt/release/ValidatedCaseContract.java`
- Modify: `case-management-core/src/main/java/org/casemgmt/release/JsonSchemaCaseContractValidator.java`
- Modify: `case-management-core/src/main/java/org/casemgmt/release/BpmnReleaseValidator.java`
- Modify: `case-management-core/src/main/java/org/casemgmt/service/CaseDefinitionVersionService.java`
- Modify: `case-management-core/src/main/java/org/casemgmt/observation/SlaLifecyclePort.java`
- Modify: `case-management-core/src/main/java/org/casemgmt/sla/SlaLifecycleService.java`
- Modify: `case-management-core/src/main/java/org/casemgmt/sla/SlaSweeper.java`
- Modify: `case-management-engine-embedded/src/main/java/org/casemgmt/engine/embedded/ProcessActivityClassifier.java`
- Modify: `case-management-engine-embedded/src/main/java/org/casemgmt/engine/embedded/RepositoryProcessActivityClassifier.java`
- Modify: `case-management-engine-embedded/src/main/java/org/casemgmt/engine/embedded/EmbeddedEngineEventBridge.java`
- Modify: remote classifier/poller files from Task 1
- Test: `case-management-core/src/test/java/org/casemgmt/release/JsonSchemaCaseContractValidatorTest.java`
- Test: `case-management-core/src/test/java/org/casemgmt/release/BpmnVocabularyTest.java`
- Test: `case-management-core/src/test/java/org/casemgmt/service/CaseDefinitionVersionServiceTest.java`
- Test: `case-management-core/src/test/java/org/casemgmt/sla/SlaLifecycleServiceTest.java`
- Test: embedded and remote bridge/classifier tests

- [ ] **Step 1: Define the supported vocabulary in failing tests**

  Introduce typed values matching the events the runtime already emits:

  ```text
  CASE_CREATED, CASE_CLOSED, CASE_CANCELLED
  USER_TASK_CREATED, USER_TASK_CLAIMED, USER_TASK_UNCLAIMED,
  USER_TASK_ASSIGNED, USER_TASK_COMPLETED, USER_TASK_DELETED
  ACTIVITY_STARTED, ACTIVITY_COMPLETED, ACTIVITY_CANCELLED
  MILESTONE_REACHED, MILESTONE_REOPENED, MILESTONE_CANCELLED
  ```

  Supported breach actions are exactly `EMIT_EVENT` and `ESCALATE`.

  Add tests rejecting an unknown anchor, an unknown action, `messageName`, a task anchor on a CASE target, a stage anchor on a task target, a mismatched BPMN element kind, and an `OCCURRENCE` target without `occurrenceKey`.

  Add positive tests for every accepted scope/anchor family and for repeated observations matching only their declared `casemgmt:slaTargetId`.

- [ ] **Step 2: Make schema and Java models closed rather than stringly typed**

  Replace the schema's open anchor pattern with an enum and `breachActions` with unique enum items. Remove SLA binding `messageName`; message correlation is not implemented and must not be promised by the public contract.

  Mirror the exact schema to the runtime resource and keep the existing resource-sync test green.

  Add `SlaAnchor` and `SlaBreachAction` enums to the typed contract model. Parse with explicit validation and store enum lists in `SlaBindingDefinition`. Convert to strings only at persistence/event boundaries that require JSON compatibility.

- [ ] **Step 3: Retain BPMN element kind and target metadata**

  Replace `BpmnReleaseValidator.Index.slaRefs()` with typed references such as:

  ```java
  public record SlaReference(String targetId, String elementId, ElementKind kind) {}
  ```

  Classify process roots as CASE, user tasks as TASK, stage-marked activities as STAGE, and milestone-marked activities as MILESTONE. Reject `slaTargetId` on an element whose runtime bridge cannot emit a corresponding lifecycle observation.

  Extend embedded and remote task/activity classification metadata with `slaTargetId`, and carry it in observation attributes. Extend `SlaLifecyclePort.Anchor` with a nullable target ID; CASE root observations intentionally have no BPMN target ID.

- [ ] **Step 4: Cross-validate scope, element kind, and anchor family at publication**

  In `CaseDefinitionVersionService.validateArtifacts(...)`:

  - require every static BPMN SLA reference to name a contract binding;
  - require CASE bindings on the process root, TASK on user tasks, STAGE on stage observations, and MILESTONE on milestone observations;
  - allow OCCURRENCE only on a repeatable non-root task/stage/milestone element and require `occurrenceKey`;
  - require all start/meet/cancel/pause/resume anchors to belong to the referenced element's event family.

  Report all bounded reference errors together, as the current validator does.

- [ ] **Step 5: Filter runtime anchors by target ID and exhaustively handle actions**

  In `SlaLifecycleService`, CASE bindings match root anchors; all other bindings must match both the typed anchor event and `binding.id() == anchor.slaTargetId()`. This prevents one task event from starting every TASK binding that shares `USER_TASK_CREATED`.

  In `SlaSweeper`, switch exhaustively over `SlaBreachAction`. There must be no default branch that silently drops a future action.

- [ ] **Step 6: Run schema, vocabulary, lifecycle, and bridge tests**

  ```bash
  ./mvnw -pl case-management-core -Dtest=JsonSchemaCaseContractValidatorTest,BpmnVocabularyTest,CaseDefinitionVersionServiceTest,SlaLifecycleServiceTest test
  ./mvnw -pl case-management-engine-embedded,case-management-engine-remote -am test
  ```

  Expected: unsupported semantics fail before publication; every admitted value has a runtime handler; target-specific observations affect only the intended SLA binding.

- [ ] **Step 7: Commit the SLA vocabulary slice**

  ```bash
  git add docs/schemas/case-contract-v1.schema.json case-management-core/src/main/resources/schemas/case-contract-v1.schema.json case-management-core/src/main/java/org/casemgmt/release case-management-core/src/main/java/org/casemgmt/service/CaseDefinitionVersionService.java case-management-core/src/main/java/org/casemgmt/sla case-management-engine-embedded/src case-management-engine-remote/src
  git commit -m "fix: validate executable SLA semantics"
  ```

---

## Task 4: Make the documentation describe the BPMN-only implementation

**Files:**

- Modify: `docs/declarative-case-model-architecture.md`
- Modify: `docs/functional-requirements-support-assessment.md`
- Modify: `docs/system-overview.md`
- Modify: `docs/bpmn-first-orchestration-proposal.md`
- Create: `case-management-core/src/test/java/org/casemgmt/DocumentationSourceLinkTest.java`

- [ ] **Step 1: Add a failing local source-link test**

  Parse repository-relative Markdown links in the four reviewed documents when their target is a local Java/source path. Assert the file exists; when a `#L<number>` suffix is present, assert the line exists. Ignore HTTP links and generated API anchors.

  Run:

  ```bash
  ./mvnw -pl case-management-core -Dtest=DocumentationSourceLinkTest test
  ```

  Expected: it reports deleted `TransitionApplier` and `StageCompletion` targets.

- [ ] **Step 2: Rewrite the declarative architecture boundary**

  Remove claims that `behavior/commands.yaml`, `behavior/lifecycle.yaml`, or `behavior/tasks.yaml` are normative executable state machines. State the ownership boundary explicitly:

  - BPMN: sequencing, gateways, stage/activity lifecycle, task activation, timers, call activities;
  - contract: canonical fields, forms, authorization, search/presentation metadata, explicit engine/case mappings, typed SLA monitoring bindings, and external capabilities;
  - projections: read models derived from engine observations, never a second transition authority.

  Delete examples and compatibility-table rows that imply commands apply lifecycle effects independently of BPMN.

- [ ] **Step 3: Re-audit the functional support assessment against current code**

  Replace the claim that `CaseService.create` materializes initial plan items with the actual flow: it persists the case, starts the pinned BPMN definition, and engine observations build task/activity/milestone projections.

  Replace deleted class links with the current services/handlers that prove each claim, or downgrade the requirement assessment when no implementation evidence exists. Search every Java/source link in the document, not only the two named by the reviewer.

- [ ] **Step 4: Correct the system overview and migration promise**

  In `system-overview.md`, remove the legacy definition-materialization and `TransitionApplier` diagrams/text. Show stages, tasks, milestones, and linked processes as projections of BPMN observations.

  In `bpmn-first-orchestration-proposal.md`, replace “every release keeps plan-model supported” with the implemented compatibility rule:

  - migration preflight blocks upgrade while legacy `PLAN_MODEL` data remains;
  - operators must explicitly migrate/export/retire/dispose that data before BPMN-only activation;
  - rollback is application/schema compatible only within the documented data boundary and does not restore removed plan-model behavior.

- [ ] **Step 5: Validate docs and stale vocabulary**

  ```bash
  ./mvnw -pl case-management-core -Dtest=DocumentationSourceLinkTest test
  rg -n 'TransitionApplier|StageCompletion|behavior/(commands|lifecycle|tasks)\.yaml|materiali[sz].*plan item|plan-model support' docs/declarative-case-model-architecture.md docs/functional-requirements-support-assessment.md docs/system-overview.md docs/bpmn-first-orchestration-proposal.md
  ```

  Expected: link test passes; any remaining search hits are explicitly historical/non-normative and cannot be read as current runtime behavior.

- [ ] **Step 6: Commit the documentation slice**

  ```bash
  git add docs/declarative-case-model-architecture.md docs/functional-requirements-support-assessment.md docs/system-overview.md docs/bpmn-first-orchestration-proposal.md case-management-core/src/test/java/org/casemgmt/DocumentationSourceLinkTest.java
  git commit -m "docs: align architecture with BPMN authority"
  ```

---

## Task 5: Restore trustworthy independent Java CI evidence

**Files:**

- Modify: `case-management-core/src/test/java/org/casemgmt/OracleTestBase.java`
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Reproduce and capture the infrastructure failure**

  Record the current failed run URL and the exact Oracle startup error in the PR evidence matrix. Confirm that `gvenzl/oracle-free:23-slim-faststart` resolved to a newer Oracle home while the test base injected version-specific SPFILE settings, ending with `ORA-00443` and container exit 187.

- [ ] **Step 2: Pin Oracle and remove the brittle boot override**

  In `OracleTestBase`, use the immutable multi-architecture reference:

  ```text
  gvenzl/oracle-free:23.26.3-slim-faststart@sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093
  ```

  Remove the custom SPFILE and entrypoint override so the image's supported startup path owns its database version. Keep only documented environment variables, add an explicit startup timeout, and preserve container logs on failure.

- [ ] **Step 3: Split the gate so one infrastructure failure cannot hide module results**

  Replace the single reactor test job with:

  - `java-core`: core test suite;
  - `java-module` matrix: `case-management-engine-embedded`, `case-management-engine-remote`, `case-management-rest`, `case-management-spring-boot-starter`, and `case-management-poc-app`. Each matrix entry first runs `./mvnw --batch-mode -DskipTests install`, then runs `./mvnw --batch-mode -pl "${{ matrix.module }}" test`;
  - `Java reactor`: an `if: always()` aggregation job that depends on both jobs and fails unless every dependency succeeded.

  Upload Surefire/Failsafe reports from every job with `if: always()`. Keep modeler-template and web-component jobs unchanged.

- [ ] **Step 4: Run local module gates**

  ```bash
  ./mvnw --batch-mode -pl case-management-core test
  ./mvnw --batch-mode -DskipTests install
  ./mvnw --batch-mode -fae -pl case-management-engine-embedded,case-management-engine-remote,case-management-rest,case-management-spring-boot-starter,case-management-poc-app test
  ```

  Expected: every command exits zero and each downstream module has an explicit result.

- [ ] **Step 5: Push and verify required checks**

  Push the branch, then inspect the new PR run. Expected: `Java reactor`, modeler templates, and web components are green; a future core failure may fail the aggregate but cannot mark downstream jobs as “skipped” without their own result.

- [ ] **Step 6: Commit the CI slice**

  ```bash
  git add case-management-core/src/test/java/org/casemgmt/OracleTestBase.java .github/workflows/ci.yml
  git commit -m "ci: make Java module evidence independent"
  ```

---

## Task 6: Publish reviewable scope, rollout, and evidence

**Files:**

- Create temporarily outside the repository or under `/tmp`: PR body text for `gh pr edit`.
- Update: PR #89 description and review threads on GitHub.

- [ ] **Step 1: Build the PR evidence matrix**

  Include rows for:

  - pinned release selection and deployment lifecycle;
  - BPMN-only authority and legacy-data preflight;
  - canonical task output mappings;
  - paged incremental polling plus full reconciliation;
  - task/stage/milestone/root SLA lifecycle;
  - calendar revision immutability and tenant isolation;
  - schema/resource synchronization and documentation links;
  - clean install, upgrade migration, restart, embedded, remote, REST, starter, and PoC tests.

  For every row, name the test class/command and link the green CI job. Do not use “covered by tests” without identifying the evidence.

- [ ] **Step 2: Write the PR description**

  Add these sections:

  1. Summary and intended architecture.
  2. Scope by Maven/frontend module.
  3. Breaking API removals and the removal of `PLAN_MODEL` behavior.
  4. Database changes, immutable data, and explicit legacy-data disposition.
  5. Evidence matrix.
  6. Rollout sequence: preflight, backup, calendar-revision seed, schema migration, application deploy, reconciliation, health verification.
  7. Rollback boundary: application/schema compatibility, what is append-only, and when rollback is no longer behaviorally safe.
  8. Known limitations and deliberately unsupported SLA message actions.

  Apply with `gh pr edit 89 --body-file /tmp/pr-89-body.md` after checking the rendered Markdown.

- [ ] **Step 3: Execute the final verification gate**

  ```bash
  ./mvnw --batch-mode clean install
  npm --prefix modeler-templates test
  npm --prefix case-management-web-components test
  git diff --check origin/main...HEAD
  ```

  Use the repository's actual package scripts if the two npm test commands differ. Expected: all local gates pass and the GitHub checks are green.

- [ ] **Step 4: Reply to and resolve every review thread**

  For each actionable thread, reply with:

  - the commit containing the fix;
  - the exact design choice;
  - the regression test and green job;
  - any intentionally rejected alternative and why.

  For the previously verified findings, reply with the retained test/evidence and resolve. Resolve the documentation authority thread only after all three architecture documents agree. Do not collapse multiple findings into one top-level “fixed” reply.

- [ ] **Step 5: Request re-review**

  Confirm the PR body is populated, all threads have evidence, and all required checks are green before requesting review from `haryselman`.

## Final Acceptance Criteria

- A remote case with engine history older than ten minutes reconstructs task, stage, milestone, and linked-process lifecycle projections from blank observation checkpoints and stale/empty derived rows, using the retained `CM_CASE` and `CM_LINKED_PROCESS` identities as inventory.
- Reconciliation is paged, replay-safe, and never infers terminal state from absence.
- A contract cannot publish or bind unless every SLA calendar `(tenant, id, revision)` exists immutably.
- New occurrences under a pinned contract continue to use its exact calendar revision after later revisions are added.
- Every accepted SLA anchor and breach action has an executable typed runtime path; unsupported values and scope mismatches fail before publication.
- BPMN element target identity prevents one observation from triggering unrelated SLA bindings.
- Reviewed architecture documents contain no broken source links or parallel behavioral authority.
- The Java gate uses an immutable Oracle image and produces independent results for core and every downstream Java module.
- PR #89 documents scope, breaking changes, migrations, evidence, rollout, rollback, and limitations, and every review thread is resolved with evidence.

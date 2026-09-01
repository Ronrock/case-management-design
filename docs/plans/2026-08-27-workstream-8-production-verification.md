# Workstream 8: Production Verification and Release Readiness Implementation Plan

> **Superseded assumption:** The later BPMN-only decision removed `PLAN_MODEL`; compatibility
> gates below are historical. The current gate proves rejection/preflight behavior instead.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce objective evidence that the complete BPMN-first pull request is safe to migrate, operate, roll back, and merge for embedded and remote modes.

**Architecture:** Verification is a release gate, not a final manual test pass. CI separates fast contract/unit checks from Oracle, embedded-engine, remote-engine, high-volume, failure-injection, security, migration, web, and compatibility suites. A release-readiness evidence index maps every architecture invariant and review comment to an automated check or rehearsed operational procedure.

**Tech Stack:** Maven/Surefire, Java 21, Node 22, Vitest, GitHub Actions, Oracle Testcontainers 1.21.4, Operaton 2.1.3, Spring Boot test, ArchUnit 1.4.1, Swagger Request Validator 2.44.9, and `BpmnFirstMigrationIT` for migration rehearsal.

**Spec:** [`docs/plans/2026-08-27-bpmn-first-production-readiness-design.md`](2026-08-27-bpmn-first-production-readiness-design.md), sections 12–18 and all acceptance gates.

## Global constraints

- No required check may be skipped because its test class suffix is not discovered.
- A green build must include `*IT` classes; the root Surefire configuration already declares this rule.
- Verification covers Oracle persistence, not only H2.
- Embedded and remote outcomes must be compared against the same business assertions.
- `PLAN_MODEL` and existing public API compatibility are release blockers.
- Failure/recovery tests must prove persisted outcomes, not only HTTP status.
- Logs, fixtures, and CI artifacts must not expose secrets or sensitive case data.
- The untracked/existing `.github/workflows/ci.yml` must be reviewed and incorporated without discarding user changes.

---

## Problem statement

The PR changes 136 files across core, two engine adapters, REST, starter configuration, database schema, web components, templates, examples, and documentation. The original PR had no reported GitHub checks. Ordinary happy-path unit tests cannot prove the key risks: exact version selection, transaction atomicity, uncertain remote responses, pagination beyond 500, duplicate replay, false SLA breach prevention, cross-tenant isolation, and safe migrations.

Without a structured gate, review threads can be marked resolved based on code inspection while production-only failure modes remain untested.

## Scope

### In scope

- CI job structure and required commands.
- Test discovery and dependency/version checks.
- Cross-workstream acceptance suite and evidence index.
- Migration from current branch schema/data and rollback rehearsal.
- Security/tenancy, API compatibility, and `PLAN_MODEL` characterization.
- Load, paging, concurrency, lost-response, restart, replay, and poison-data scenarios.
- Operational metrics, alerts, support APIs, and runbook validation.
- Review comment closure evidence.

### Out of scope

- Production deployment itself.
- Deciding organisation-specific SLO threshold values; the release owner supplies approved values before the gate runs.
- General performance optimisation unrelated to BPMN-first paths.

## Technical design

### CI jobs

| Job | Command | Purpose |
|---|---|---|
| Compile/static architecture | `./mvnw -B -DskipTests package` plus ArchUnit tests | Module boundaries, compilation, dependency consistency. |
| Core unit/contract | `./mvnw -B -pl case-management-core test` | Domain, schema, repositories, mappings, SLA, commands, lifecycle. |
| Embedded engine | `./mvnw -B -pl case-management-engine-embedded -am test` | Real embedded Operaton behavior and transaction rollback. |
| Remote engine | `./mvnw -B -pl case-management-engine-remote -am test` | Real HTTP, paging, uncertainty, reconciliation. |
| REST/API | `./mvnw -B -pl case-management-rest,case-management-poc-app -am test` | HTTP, security, OpenAPI, generic client, full scenarios. |
| Full reactor | `./mvnw -B clean install` | Cross-module assembly and all `*Test`/`*IT`. |
| Web components | `npm ci && npm test && npm run build` | Workspace presentation compatibility. |
| Modeler contracts | `npm ci && npm test` in `modeler-templates` | Vocabulary and round-trip integrity. |
| Migration rehearsal | dedicated Maven profile/test class against Oracle | Upgrade/backfill/restart/rollback compatibility. |
| Failure/high-volume | tagged Maven tests | >500 paging, response loss, concurrency, crash/restart, poison records. |

Do not duplicate the full reactor needlessly in every job. Focused jobs provide fast diagnosis; the final reactor is the authoritative aggregate.

### Test tags

Use JUnit tags `contract`, `oracle`, `embedded-engine`, `remote-engine`, `failure-injection`, `high-volume`, `security`, `compatibility`, and `migration`. Tags organize execution and reporting; tests remain discoverable by the normal build.

### Evidence index

Create a generated/maintained Markdown matrix with columns: invariant/comment, test class and method, CI job, latest result artifact, operational rehearsal, and owner. The index references evidence but never replaces automated assertions.

## File map

### Create

- `docs/plans/bpmn-first-release-readiness-checklist.md` — executable approval checklist and evidence index.
- `docs/guide/bpmn-first-operations.md` — operator states, metrics, replay/reconcile procedures, and rollback.
- `case-management-poc-app/src/test/java/org/casemgmt/poc/BpmnFirstAcceptanceIT.java` — cross-workstream business scenarios.
- `case-management-poc-app/src/test/java/org/casemgmt/poc/BpmnFirstCompatibilityIT.java` — legacy/API compatibility.
- `case-management-poc-app/src/test/java/org/casemgmt/poc/BpmnFirstSecurityIT.java` — tenant and permission matrix.
- `case-management-poc-app/src/test/java/org/casemgmt/poc/BpmnFirstFailureRecoveryIT.java` — crash, uncertainty, poison, and replay.
- `case-management-core/src/test/java/org/casemgmt/BpmnFirstMigrationIT.java` — baseline-to-target Oracle migration/backfill.
- `case-management-engine-remote/src/test/java/org/casemgmt/engine/remote/RemoteHighVolumeIT.java` — paging/load boundary.
- Test fixtures under each module's `src/test/resources` for exact versions, SLA, actions, and corrupt/late observations.

### Modify

- `.github/workflows/ci.yml` — focused jobs, service/resources, caches, timeouts, artifacts, and required summaries.
- Root and module `pom.xml` files — tags/profiles only where necessary; retain `*IT` Surefire discovery.
- `case-management-poc-app/src/test/java/org/casemgmt/poc/OpenApiConformanceIT.java`.
- `case-management-poc-app/src/test/java/org/casemgmt/poc/GenericConsumerIT.java`.
- `case-management-poc-app/src/test/java/org/casemgmt/poc/RemoteModeComplaintIT.java`.
- `case-management-core/src/test/java/org/casemgmt/ArchitectureTest.java` and PoC cross-module architecture tests.
- `docs/guide/operations.md` and `docs/system-overview.md`.

## Test data strategy

- Use deterministic tenants `tenant-a` and `tenant-b`, stable business keys, and injected clocks.
- Provide BPMN v1 and v2 with the same key and visibly different terminal marker variables.
- Provide one BPMN model covering task, stage, milestone, subprocess, message, and root outcomes.
- Provide SLA calendars around DST, weekend, holiday, and normal business day.
- Generate remote history counts of 499, 500, 501, 1,000, and 1,201 with equal timestamp groups.
- Use non-sensitive synthetic variables and assert log/event redaction.
- Seed database snapshots from the pre-production-readiness schema, including PoC command states and ambiguous release identities.

## Test strategy

### Functional acceptance

- Publish/activate exact artifacts; create and complete cases in embedded and remote modes.
- Canonical form output reaches case API/search after confirmed completion.
- Projection, audit, event, and SLA outcomes match across modes.
- All three ad-hoc variants behave consistently.

### Reliability

- Duplicate and out-of-order observation replay.
- HTTP response lost after engine success.
- Dispatcher/poller/inbox/sweeper process crash after claim.
- More than 500 equal/near-equal history records.
- Operaton outage and recovery.
- Poison observation and dead-letter replay.
- Concurrent case/task/SLA/command updates.

### Security

- Cross-tenant release, case, task, operation, observation, SLA, and ad-hoc access.
- Worker Permissions deny, unavailable, timeout, and malformed response.
- Role metadata cannot override platform denial.
- Diagnostics and audit payload redaction.
- Admin recovery endpoints require explicit policy.

### Compatibility

- Existing `PLAN_MODEL` lifecycle and public endpoints.
- Generic client consumes additive fields without requiring them.
- ETag/idempotency behavior remains stable.
- Existing web components render embedded confirmed and remote pending states.
- Old database records remain readable after migration.

### Operational rehearsal

- Pause new dispatch while observation ingestion continues.
- Reconcile awaiting-confirmation commands.
- Replay a dead observation through audited API.
- Retire/reactivate a binding for new cases.
- Disable SLA-to-BPMN delivery without changing SLA truth.
- Roll application code back while additive schema/evidence remains.

## Acceptance criteria

| ID | Scenario | Pass condition |
|---|---|---|
| WS8-AC1 | Full CI on a clean checkout. | Every declared job passes and reports non-zero discovered tests. |
| WS8-AC2 | Exact v1/v2 embedded and remote suite. | Bound ID equals executed ID in both modes. |
| WS8-AC3 | Lost-response suite. | No duplicate process/message effects and no false confirmed projection. |
| WS8-AC4 | 1,201-record poll. | 1,201 unique observations are durable/applied and checkpoint is correct. |
| WS8-AC5 | Lifecycle failure injection. | Engine/platform transaction is all-or-nothing in embedded mode; remote inbox remains recoverable. |
| WS8-AC6 | Root completion with running SLA. | Case and all occurrences terminalize together; later sweep emits nothing. |
| WS8-AC7 | Cross-tenant matrix. | Every forbidden read/mutation/recovery action fails without data leakage. |
| WS8-AC8 | Baseline database migration. | Data counts/evidence preserved; active identities are exact or explicitly unresolved; rerun is safe. |
| WS8-AC9 | Code rollback after schema upgrade. | Prior compatible build reads required legacy paths and does not destroy new evidence. |
| WS8-AC10 | Existing `PLAN_MODEL` and generic consumer suite. | Behavior and required response contract remain unchanged. |
| WS8-AC11 | All nine review comments. | Each has linked passing evidence and no unresolved architecture contradiction. |
| WS8-AC12 | Operational thresholds exceeded in rehearsal. | Alerts fire and documented pause/recovery actions work without direct DB edits. |

## Implementation tasks

### Task 1: Audit current test discovery and establish tags

- [ ] Run `./mvnw -B -DskipTests=false test` and capture test counts per module.
- [ ] Add a sentinel assertion or CI summary that fails when expected `*IT` categories discover zero tests.
- [ ] Apply tags to expensive/specialized suites without excluding them from the full reactor.
- [ ] Commit `test: classify production readiness suites`.

### Task 2: Build cross-workstream acceptance fixtures

- [ ] Add deterministic BPMN/DMN/contract/presentation v1/v2 fixtures and tenant copies.
- [ ] Add injected clock/calendar and history-volume builders.
- [ ] Verify fixtures themselves through schema/publication tests.
- [ ] Commit `test: add BPMN-first acceptance fixtures`.

### Task 3: Implement business outcome parity suite

- [ ] Write parameterized scenarios that execute against embedded and remote harnesses.
- [ ] Assert case/task/stage/milestone, canonical data, audit, event, SLA, and operation outcomes—not adapter internals.
- [ ] Cover structured and all ad-hoc paths.
- [ ] Commit `test: compare engine mode outcomes`.

### Task 4: Implement failure, concurrency, and volume suite

- [ ] Add controllable failure points for response loss, post-claim crash, page failure, handler failure, and sweeper race.
- [ ] Generate 499/500/501/1,000/1,201 record cases including equal timestamps.
- [ ] Assert persisted counts, versions, state, and recovery after restart.
- [ ] Commit `test: exercise distributed failure recovery`.

### Task 5: Complete security and compatibility matrices

- [ ] Write tenant/role/Worker Permissions/admin endpoint tests across all new resources.
- [ ] Extend OpenAPI and generic consumer tests for additive operation/pending/SLA fields.
- [ ] Run existing `PLAN_MODEL`, web component, ETag, and idempotency suites.
- [ ] Commit `test: gate security and compatibility`.

### Task 6: Rehearse migration and rollback

- [ ] Start Oracle at the baseline changelog, seed releases/cases/commands/SLA/projections, then apply target changesets.
- [ ] Assert counts, hashes, evidence, exact/ambiguous identity policy, and idempotent rerun.
- [ ] Start the previous compatible application build against the additive schema or run its repository compatibility suite.
- [ ] Document safe rollback limits and commands without destructive data removal.
- [ ] Commit `test: rehearse BPMN-first migration`.

### Task 7: Expand CI without discarding existing workflow changes

- [ ] Review current `.github/workflows/ci.yml` and preserve its Java/web jobs and user edits.
- [ ] Add focused jobs, dependency caches, explicit timeouts, concurrency cancellation, test reports, and failure artifacts.
- [ ] Ensure Oracle/Testcontainers runner capacity is sufficient and serialize resource-heavy suites where needed.
- [ ] Run every CI command locally or in the closest supported environment.
- [ ] Commit `ci: enforce BPMN-first readiness gates`.

### Task 8: Validate operations and evidence

- [ ] Add/runbook-test pause, replay, reconcile, retry, cancel, retire, and SLA-message-disable procedures.
- [ ] Verify metrics/alerts with injected stale/backlog/failure conditions.
- [ ] Create `bpmn-first-release-readiness-checklist.md` mapping every invariant/comment to evidence.
- [ ] Commit `docs: add BPMN-first operations evidence`.

### Task 9: Final clean-room gate

- [ ] Run `./mvnw -B clean install` from a clean checkout.
- [ ] Run `npm ci && npm test && npm run build` in web components.
- [ ] Run `npm ci && npm test` in modeler templates.
- [ ] Run migration/failure/high-volume profiles and confirm no quarantined or disabled test.
- [ ] Review logs/artifacts for secrets and flaky timing assumptions.
- [ ] Require approval from architecture, product/SLA owner, security, operations, and test owner.
- [ ] Commit only evidence-index updates after all checks are green.

## Review-comment evidence mapping

| Comment | Required evidence owner |
|---|---|
| Exact pinned BPMN version | Workstream 2 v1/v2 embedded and remote test. |
| Deploying release selectable | Workstream 2 state selector and migration tests. |
| Remote root placeholder | Workstream 2 atomic root confirmation plus Workstream 4 lost-response test. |
| Second process authority | Workstream 1 schema/docs/ArchUnit and `PLAN_MODEL` compatibility tests. |
| Canonical outputs missing | Workstream 3 mapping and transaction tests in both modes. |
| Open SLA/action/search schema | Workstream 1 schema negative matrix. |
| SLA survives root completion | Workstream 6 root terminalization and post-completion sweep test. |
| 500-row poll loss | Workstream 5 1,201/equal-time/restart test. |
| Vocabulary mismatch | Workstream 1 namespace and modeler round-trip tests. |

## Completion evidence

- Green required CI checks with test counts and reports.
- Completed release-readiness evidence index.
- Migration and rollback rehearsal report.
- Security/tenancy matrix and redaction confirmation.
- Operational rehearsal output and approved thresholds.
- Zero unresolved PR review comments and explicit sign-off for the six business questions in the master design.

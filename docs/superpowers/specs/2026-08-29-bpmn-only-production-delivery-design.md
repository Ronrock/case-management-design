# BPMN-only production delivery design

**Status:** Approved for planning

**Date:** 29 August 2026

**Decision:** The library has no `PLAN_MODEL` consumers. Remove that legacy orchestration path and complete the remaining BPMN-first work as four end-to-end delivery slices, using Oracle as a required verification gate.

## Goal

Finish the BPMN-first pull request with fewer repeated review cycles while retaining the safety guarantees required for production: a case reflects an engine action only when it is confirmed or authoritatively observed; all stored engine facts can be replayed and audited; and production migrations run against Oracle Database Free 23.

## Why this changes the delivery approach

The existing workstream plans correctly identify the required functionality, but their small task boundaries repeatedly re-open the same cross-cutting questions: transaction ownership, idempotency, projection truth, legacy branching, Oracle migration safety, and remote failure recovery. Workstream 3 required approximately 17 review/fix rounds, with its largest costs in lifecycle projection and embedded transaction compatibility.

`PLAN_MODEL` is a major multiplier. It is present in release validation, orchestration selection, lifecycle services, callback routing, policy, error mapping, test fixtures, and database compatibility paths. Keeping it requires every new BPMN-first invariant to be proved twice. Because there are no consumers, retaining that surface has cost without product value.

## Decisions

### 1. BPMN is the only supported orchestration mode

The public and persisted orchestration contract accepts `BPMN` only. Publication rejects absent or non-BPMN modes. Runtime code has one orchestration implementation and one observation path; no `PLAN_MODEL` evaluator, instantiator, callback handler, mode branch, fixture, or Spring bean remains.

This is an intentional breaking change for any database that still contains active legacy definitions. Before application code can run against such a database, a preflight/migration check halts with a clear remediation message. It must never silently treat a legacy definition as BPMN, drop legacy data, or start an unintended process.

The hard cut removes behaviour, not useful BPMN task/projector records. Shared domain records remain where BPMN projections use them; only plan-model evaluation and its legacy-only fields, APIs, validation, migrations, and tests are removed or narrowed.

### 2. One remote-operation truth invariant

For every remote engine mutation:

1. The API accepts a durable command and returns an operation resource.
2. The local case/projected task remains in its previously confirmed state while the command is pending.
3. A typed payload is bound to the immutable tenant, command type, and target before any request is sent.
4. A definitive synchronous response or matching engine observation confirms the command exactly once through the common lifecycle handler.
5. A pre-send failure can retry under policy. A post-send/lost-response or partial multi-step effect awaits confirmation/reconciliation; it is never blindly resent.
6. Operators resolve exceptional commands only through authorised, audited support actions backed by evidence.

The command row, ordered transition history, normalized operator action record, and lifecycle effect are one durable story. Rehydration validates the full chain and fails closed on malformed or altered evidence.

### 3. Oracle is a required verification gate

The repository-standard image is `gvenzl/oracle-free:23-slim-faststart`, compatible with Oracle Database Free 23.6. It is available as the healthy Compose service `case-management-oracle-1` on `localhost:1521/FREEPDB1` and is also the Testcontainers image.

Every delivery slice that changes persistence runs its targeted Oracle/Testcontainers migration and integration tests before review. A review cannot declare a schema, concurrency, or transaction claim clear solely from H2 or compilation. The final clean-room gate runs a fresh container and applies the complete Liquibase history.

### 4. Delivery slices replace repeated narrow re-reviews

The remaining plans are regrouped around independently observable business outcomes. Each slice has one invariant set, one explicit test matrix, one implementation report, and one independent gate review. A finding that affects a later slice is recorded as a concrete input contract rather than reopening completed unrelated work.

| Slice | Existing plan scope | Business outcome | Required Oracle proof |
|---|---|---|---|
| A. BPMN-only foundation and command safety | legacy removal; WS4 Task 3 completion | Only BPMN definitions run; uncertain commands retain truthful, replayable history. | Legacy preflight, command migration, lease race, history tamper rejection. |
| B. Remote operation truth and reconciliation | WS4 Tasks 4–7; WS5 Tasks 1–5 | Users see “requested” until the engine confirms; polling/reconciliation repairs uncertainty. | Pending task API, lost response, paged inbox/checkpoints, one lifecycle effect. |
| C. Time commitments and controlled ad-hoc actions | WS5 Tasks 6–7; WS6; WS7 | SLA and approved manual actions are auditable, idempotent, and do not bypass BPMN authority. | SLA occurrence transitions, message deduplication, authorization and tenant isolation. |
| D. Production verification and release evidence | WS8 | A fresh environment proves migration, resilience, compatibility, security, and operational readiness. | Full clean-room Liquibase/app suite, concurrency/failure rehearsal, metrics evidence. |

## Components and boundaries

### BPMN-only release boundary

`OrchestrationMode` becomes BPMN-only and release parsing/schema validation requires it. Case-definition repositories select only BPMN definitions. The orchestration registry contains `BpmnOrchestration` only. Spring configuration and embedded callbacks route all verified engine observations through the common handler.

The legacy plan evaluator and instantiator are deleted only after failing tests prove definitions cannot enter through release, repository, direct service, or callback paths. Exception mapping and REST test-jar dependencies are simplified at the same time so a deleted compatibility type cannot survive through a test-only API.

### Command and operation boundary

The production command store owns submission, canonical payload validation, leases, retry/confirmation decisions, ordered state history, and operator-action evidence. Transport owns HTTP execution and response evidence; it never mutates case projections. The lifecycle handler owns confirmed case, task, data, audit, event, and SLA effects.

Task-facing services submit an intent and canonical patch, add an additive pending representation, and suppress incompatible actions. They do not claim or complete the confirmed task projection on request acceptance.

### Observation and reconciliation boundary

Remote history polling writes durable inbox records and independently advances per-stream checkpoints only after the full page/window is safely ingested. The common handler processes the inbox idempotently. Reconciliation compares command/target evidence with Operaton, confirming proven effects and retaining ambiguous effects for authorised review.

### SLA and ad-hoc-action boundary

The SLA service owns occurrence calculation and transitions. BPMN reacts through idempotent, correlation-bound messages. Ad-hoc actions use the same release binding, authorization, command/observation evidence, and audit model as normal actions; they cannot directly alter engine or case truth.

## Error handling and recovery

- Invalid legacy data: fail before use with a stable, actionable code; no implicit conversion.
- Invalid payload/history: reject submission or rehydration before any remote call; record only safe diagnostics.
- Definitive remote refusal: terminal failure; confirmed local state unchanged.
- Ambiguous network/partial multi-step result: awaiting confirmation; persisted partial remote evidence supports idempotent repair or reconciliation.
- Duplicate observation or operator action: one durable effect and one audit/event outcome.
- Migration mismatch: halt on exact Oracle schema signature difference, including a same-named but structurally wrong object.

## Testing strategy

Each slice uses test-first implementation and has four levels of evidence:

1. Unit tests for closed contracts and illegal transitions.
2. Repository tests against Oracle for migration, constraints, leases, CAS races, and replay/tamper guards.
3. Engine/HTTP integration tests for request phases, lost responses, polling, and reconciliation.
4. REST acceptance tests for tenant isolation, pending/confirmed truth, authorization, redaction, and audit.

The final slice additionally executes a clean database/app run, high-volume paging, concurrent dispatcher/observer cases, recovery after restart, and API compatibility checks for the BPMN-only public contract.

## Non-goals

- Do not retain a hidden compatibility adapter for `PLAN_MODEL`.
- Do not rewrite reviewed BPMN-first foundations that already satisfy their acceptance gates.
- Do not add a custom engine-side deduplication plugin; ambiguous non-idempotent effects are reconciled or manually reviewed.
- Do not report Oracle safety as proved when Docker/Testcontainers tests are skipped or unavailable.

## Acceptance criteria

1. A legacy-mode publication or persisted active legacy definition is rejected clearly and before it can run.
2. A BPMN case uses the exact active release/definition identity and retains the existing common lifecycle semantics.
3. A remote task claim/complete returns an operation and leaves the confirmed task state unchanged until confirmed or observed.
4. A lost response creates at most one engine business effect and never triggers a blind resend of an uncertain non-idempotent command.
5. A malformed/tampered command payload, transition record, or normalized operator action fails closed before side effects.
6. Polling a multi-page remote stream skips no observation, and repeated pages/observations apply no duplicate lifecycle effect.
7. SLA and ad-hoc actions are tenant-authorized, idempotent, auditable, and reconciled through BPMN authority.
8. Targeted Oracle suites pass for every persistence slice; the final clean-room gate passes with the full schema and application.

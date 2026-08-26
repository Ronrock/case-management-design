# BPMN-First Case Orchestration — Accepted Architecture

Operaton is the orchestrator of record for BPMN-backed cases. Existing JSON plan-model case
definitions remain supported and are not migrated automatically. Both modes continue to expose the
same case API resources, actions, authorization rules, audit events, search, SLA, and presentation
contracts.

Status: accepted for incremental implementation. Companion documents:

- [`declarative-case-model-architecture.md`](declarative-case-model-architecture.md)
- [`declarative-case-ui-proposal.md`](declarative-case-ui-proposal.md), with Scenario A selected

## Architectural boundaries

Each case-definition version immutably binds three independently publishable releases:

1. **Orchestration release** — BPMN plus optional DMN and called-process resources deployed to
   Operaton.
2. **Contract release** — canonical case-data schema, field catalog, forms, SLA bindings, roles,
   search profiles, and declarative ad-hoc actions.
3. **Presentation release** — the Scenario A manifest interpreted by the Lit shell.

Changing forms, schemas, SLA bindings, or ad-hoc actions does not redeploy BPMN. Changing the UI
does not redeploy BPMN or mutate the contract. A combined ZIP remains the convenient initial deploy
format, but it publishes immutable releases and creates a version that binds their exact ids and
content hashes; it is not an inseparable runtime bundle.

Legacy `POST /case-api/v2/case-definitions` with `application/json` keeps its current plan-model
semantics. The same endpoint accepts `application/zip` for the combined BPMN publication flow.
Independent publication uses:

```text
POST /case-api/v2/case-definitions/{key}/orchestration-releases
POST /case-api/v2/case-definitions/{key}/contract-releases
POST /case-api/v2/case-definitions/{key}/presentation-releases
POST /case-api/v2/case-definitions/{key}/versions
```

Cross-artifact references are validated when the version is bound. Releases are content-addressed,
immutable, and reusable by more than one version. Embedded orchestration deployment becomes
`ACTIVE` in the request transaction. Remote deployment reports `DEPLOYING` and later `ACTIVE` or
`FAILED` after outbox execution and observation.

## Modeling and publication

Desktop Modeler plus a distributed element-template catalog is the first authoring path. The
templates constrain platform extension properties such as `casemgmt:formKey`,
`casemgmt:slaTargetId`, stage and milestone tags, and candidate groups. A later Studio uses the
same catalog; Studio is not a runtime dependency.

Publication rejects unsafe or inconsistent artifacts, including:

- ZIP traversal, duplicate paths, excessive file counts or decompressed size, unknown media types,
  and hash mismatches;
- XML external entities and other unsafe XML features;
- more or fewer than one root process, duplicate milestone ids, invalid template properties, and
  unresolved form, SLA, DMN, call-activity, candidate-group, field, action, search, or component
  references.

Running cases pin exact orchestration and contract releases. A presentation release may advance
only after compatibility validation against the pinned contract.

## Dual orchestration

`OrchestrationMode` has `PLAN_MODEL` and `BPMN`. A `CaseOrchestration` SPI is selected from the
case's pinned definition version:

- `PlanModelOrchestration` wraps today's evaluator, instantiator, and transition behavior. Its
  characterization suite prevents migration work from changing existing case behavior.
- `BpmnOrchestration` starts and cancels the root process, exposes engine-backed actions, and
  accepts engine observations through vendor-neutral core ports.

Engine integration is split into command and observation operations. Commands cover deploy,
start/cancel, claim/complete, message correlation, and migration. Observations cover active
processes/tasks, historic activities/tasks, and deployment lookup. Core imports no Operaton engine
types. Projection input consists only of neutral process, activity, task, milestone, assignment,
and completion observations.

The root process instance id and root activity ids are persisted on the case. Engine process,
activity-instance, and task ids are unique in projection tables, making replayed observations
idempotent. BPMN-derived case, plan-item, and task responses expose:

```json
{
  "projection": {
    "status": "CURRENT",
    "lastEngineUpdateAt": "2026-08-25T18:00:00Z",
    "lastProjectedAt": "2026-08-25T18:00:00Z"
  }
}
```

`engineSync` remains on tasks as a compatibility field.

## Lifecycle and action semantics

The BPMN root process ending closes its case. Explicit `close` is not advertised for BPMN cases.
Cancellation cancels the root process and terminalizes remaining projected and ad-hoc work.

Engine commands support `Idempotency-Key`, optimistic locking where applicable, authorization,
audit, and event publication. Ad-hoc actions are declared in the contract and invoked through:

```text
POST /case-api/v2/cases/{caseId}/ad-hoc-actions/{actionId}
```

They may create a task, start a side process, or correlate a message. Their roles, form reference,
candidate groups, and availability expression are contract data. Tasks and processes use the same
projections as BPMN work but remain outside the root token flow; root completion terminalizes any
still-open discretionary work.

## Embedded mode

Embedded integration consumes Operaton's built-in Spring task, execution, and history events. It
does not install a custom `ProcessEnginePlugin`. Event handlers translate engine objects at the
adapter boundary and update projections, audit, and the event outbox in the engine transaction.
Rollback therefore covers engine state, projections, audit, and emitted platform events together.

The projection covers user tasks, assignment changes, tagged subprocess stages, milestones,
multi-instance and repeated activities, historic completion, and root-process completion.

## Stock-Operaton remote mode

Remote Operaton remains unmodified: no custom engine plugin and no Kafka dependency. Commands use
the platform outbox with stable command and deployment identifiers plus duplicate filtering.
Mutations return `202` with `PENDING`; local projections become current only after REST observation.
Dead-lettered commands surface `FAILED` and an administrative retry action.

Observation uses overlapping runtime and history REST polls, stable fingerprints, idempotent
upserts, and persisted polling checkpoints. Because the stock history API does not offer a durable
lossless cursor, the poller deliberately rereads an overlap window, reports stale state during an
outage, and periodically performs a full reconciliation of every active BPMN case. At least Operaton
`AUDIT` history level is required. Remote timelines are reconciled observations, not a lossless
append-only engine event stream.

## Scenario A presentation

The Lit shell loads the case's presentation release and ordinary REST resources. It interprets a
bounded manifest vocabulary: summary fields, field grids, plan tree, task/document/milestone/SLA
lists, search placement, JSON Schema forms with per-form `uiSchema`, actions, and extension slots.
There is no `/view` API and no `ViewComposer`.

Canonical field ids resolve through the pinned contract. The server centrally projects fields
across case, task, document, collaboration, search, and error DTOs; missing or empty permission
decisions deny. The shell renders only server-returned `AvailableAction` values, while execution
reauthorizes every command.

Custom elements are allowlisted and versioned. They receive masked props and a case-scoped
capability facade, never credentials, the unrestricted API client, or raw portal adapters.
Unsupported manifest major versions fail predictably. The renderer supplies localization,
responsive layout, design-token hooks, and WCAG 2.2 behavior.

## Upgrades and migration

Presentation upgrades are allowed when all referenced fields, forms, actions, search profiles, and
components remain compatible with the pinned contract. Contract upgrades are explicit and reject
removed or renamed active forms and newly required data without defaults or migration.

Process migration validates source/target BPMN mappings, tenant and key compatibility, and active
executions. Embedded migration is synchronous; remote migration is an outbox command. A case's
pinned release references change only after the corresponding compatibility check, data upgrade, or
engine migration succeeds.

## Delivery order

1. Stabilize the reactor, executable PoC, locale-stable API contract, and Java/Node CI.
2. Publish and validate independent releases and immutable version bindings.
3. Extract the orchestration SPI and deliver the embedded complaint BPMN journey.
4. Deliver the Scenario A renderer and centralized security projection.
5. Add discretionary task/process/message behavior.
6. Add stock-Operaton polling, stale-state reporting, and reconciliation.
7. Add Studio and controlled presentation, contract, and process upgrades.

Each release remains runnable and keeps plan-model definitions supported.

## Sources

- Operaton [Spring event bridge](https://docs.operaton.org/docs/documentation/user-guide/spring-boot-integration/the-spring-event-bridge/)
- Operaton [REST API](https://docs.operaton.org/docs/documentation/reference/rest/)
- Operaton [history configuration](https://docs.operaton.org/docs/documentation/user-guide/process-engine/history/history-configuration/)
- [`declarative-case-ui-proposal.md`](declarative-case-ui-proposal.md)

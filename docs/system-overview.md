# System Documentation — Case Management on Operaton

> **Status: implementation reference.** This document describes the current architecture,
> runtime contracts, and remaining production-hardening decisions for the case-management
> platform.
>
> Companion documents: [`FINDINGS.md`](../FINDINGS.md) (risk verdicts and known defects),
> [`openapi-specs.md`](../openapi-specs.md) (the published API contract),
> [`db-design.md`](../db-design.md) / [`db-design.sql`](../db-design.sql) (schema),
> [`design principles.md`](../design%20principles.md) (design rationale),
> [`search-architecture.md`](search-architecture.md) (search provider architecture).

---

## 1. Purpose and scope

This service provides a reusable case-management layer for long-running, human-driven work.
Domain teams deploy it with their own case definitions and own their domain data, operations and
release lifecycle. The platform supplies the common lifecycle, task, event, SLA, authorization,
webhook and UI-integration mechanics; it is not a centrally operated case-management service.

**What exists today:** a backend case-management service built on the Operaton process engine.
It manages *cases* (long-running, human-driven work items) whose behavior is defined by BPMN.
Operaton owns token flow, gateways, stage/activity lifecycle, task activation, process timers,
call activities, subprocesses, and compensation. The service owns the shared case API, canonical
data, authorization, audit, typed SLA bindings, search, presentation contracts, and the projections
derived from engine observations. Those projections are read models, never a second transition
authority.

**UI scope:** the backend remains the main implemented surface, but a Lit Web Components package
now provides the standalone shell and a generic enterprise portal-adapter contract.
Detailed product UX and form-rendering decisions remain outside this PoC.

---

## 2. Module map

| Module | Responsibility | Depends on |
|---|---|---|
| `case-management-core` | Domain model, plan-item state machine, criterion evaluation, persistence, services, transactional outbox, webhook dispatch, SLA clocks | — (no Operaton engine types) |
| `case-management-engine-embedded` | `EngineGateway` over the in-process Operaton Java API | core |
| `case-management-engine-remote` | `EngineGateway` over Operaton `engine-rest` HTTP | core |
| `case-management-rest` | HTTP layer: controllers, authorization policy, problem+json, ETag, idempotency | core |
| `case-management-spring-boot-starter` | Auto-configuration, properties, schedulers, architecture rules | core, rest, both gateways (optional) |
| `case-management-poc-app` | Runnable demo application and the complaint case type | starter |
| `case-management-web-components` | Lit shell, generated-client entry point and adapters for standalone and embedded enterprise portal hosting | API contract |

**Architectural invariant, enforced by ArchUnit:** `case-management-core` must not depend on any
`org.operaton.bpm.engine` type. The one exception is `org.operaton.bpm.impl.juel` — an expression
library, not the engine. Enforced repo-wide by `CrossModuleArchitectureTest` in `poc-app`.

**Second invariant, enforced by `NoCaseTypeVocabularyTest`:** no case-type vocabulary
(`complaint`, and terms derived from the deployed definition) outside `case-management-poc-app`.

```mermaid
flowchart LR
    web["Lit Web Components\nstandalone shell / embedded-host adapter"]
    rest["case-management-rest\nHTTP API, policy, ETags, idempotency"]
    core["case-management-core\ncase model, services, repositories, events, SLA"]
    starter["spring-boot-starter\nautoconfiguration and schedulers"]
    embedded["engine-embedded\nin-process Operaton gateway"]
    remote["engine-remote\nengine-rest gateway + command outbox"]
    app["poc-app\nruntime composition and demo case type"]
    oracle["Oracle schema\nCM_* tables"]
    operaton["Operaton engine"]

    web --> rest
    rest --> core
    starter --> rest
    starter --> core
    starter --> embedded
    starter --> remote
    app --> starter
    core --> oracle
    embedded --> operaton
    remote --> operaton
```

---

## 3. HTTP API

All endpoints are under `/case-api/v2`. The authoritative contract is
[`openapi-specs.md`](../openapi-specs.md); this table is the implementation inventory.

### 3.1 Cases — `CaseController`

| Method | Path | Notes |
|---|---|---|
| `POST` | `/cases` | Accepts `Idempotency-Key`. Returns `201` + `ETag` |
| `GET` | `/cases` | `Page` envelope; filters: `state` (repeatable), plus others. `pageSize` capped at 200 |
| `GET` | `/cases/{caseId}` | Returns `availableActions[]` |
| `PATCH` | `/cases/{caseId}` | `application/merge-patch+json`; requires `If-Match` |
| `POST` | `/cases/{caseId}/close` | Requires `If-Match` |
| `POST` | `/cases/{caseId}/cancel` | Requires `If-Match` |

### 3.2 Plan items — `PlanItemController`

| Method | Path |
|---|---|
| `GET` | `/cases/{caseId}/plan-items` |

The rows are read-only BPMN engine projections; the API exposes no manual transition endpoint.

### 3.3 Tasks — `TaskController`

| Method | Path | Notes |
|---|---|---|
| `GET` | `/tasks` | Worklist. Tenant-scoped; hides unsynced tasks |
| `GET` | `/cases/{caseId}/tasks` | |
| `POST` | `/tasks/{taskId}/claim` | Requires `If-Match` |
| `POST` | `/tasks/{taskId}/complete` | Requires `If-Match`; validates the form payload |

### 3.4 Collaboration — `CollaborationController`

| Method | Path | Notes |
|---|---|---|
| `GET` / `POST` | `/cases/{caseId}/comments` | |
| `GET` / `POST` | `/cases/{caseId}/documents` | Document metadata references; binary content remains in DMS/S3 |
| `DELETE` | `/cases/{caseId}/documents/{documentId}` | Removes the case document reference |
| `GET` | `/cases/{caseId}/milestones` | |
| `GET` / `POST` | `/cases/{caseId}/processes` | `POST` accepts `planItemId`; requires `If-Match` |

### 3.5 SLA — `SlaController`

| Method | Path |
|---|---|
| `GET` | `/cases/{caseId}/slas` |
| `POST` | `/cases/{caseId}/slas/{slaId}/pause` |
| `POST` | `/cases/{caseId}/slas/{slaId}/resume` |

### 3.6 Case definitions — `CaseDefinitionController`

| Method | Path | Notes |
|---|---|---|
| `POST` | `/case-definitions` | Admin-gated. Tenant derived from the principal |
| `GET` | `/case-definitions` | Tenant-scoped |
| `GET` | `/case-definitions/{key}` | |
| `GET` | `/case-definitions/{key}/forms/{formKey}` | Tenant-scoped through the authenticated principal |

### 3.7 Events and webhooks — `EventController`

| Method | Path | Notes |
|---|---|---|
| `GET` | `/events` | Tenant-scoped cursor feed over serialized event sequence |
| `GET` | `/cases/{caseId}/events` | |
| `GET` / `POST` | `/webhooks` | `POST` admin-gated; tenant from the principal |
| `GET` | `/webhooks/{webhookId}/dead-letters` | Admin-gated; pageable and capped at 200 rows per page |
| `POST` | `/webhooks/{webhookId}/dead-letters/redeliver` | Admin-gated; resets all DEAD rows for the subscription to PENDING |

### 3.8 Search — `SearchController`

| Method | Path | Notes |
|---|---|---|
| `GET` | `/search/cases` | Tenant-scoped case search over local projections |
| `POST` | `/search/query` | Orchestrated search across requested scopes and registered providers |
| `GET` | `/search/suggestions` | Tenant-scoped suggestions over visible provider results |
| `GET` | `/search/facets` | Facet endpoint; returns warnings when no provider supplies facets |
| `GET` | `/search/providers` | Provider capabilities, status and freshness |

Search is provider-based rather than a direct dependency from the REST layer to every searchable
module. The first providers search the local case projection and document metadata references.
The document provider calls the Worker Permissions port before returning document results and
fails closed with an `authorization-unavailable` warning when authorization cannot be evaluated.
Task, timeline, enterprise-reference and semantic providers are extension points. See
[`search-architecture.md`](search-architecture.md).

---

## 4. Cross-cutting HTTP concerns

### 4.1 Errors — `ProblemDetailHandler`

RFC 9457 `application/problem+json` on every error path. Extends
`ResponseEntityExceptionHandler`, so framework-raised errors (malformed body) use the same shape.

Codes in use: `not-found`, `version-conflict`, `precondition-failed`, `if-match-required`,
`form-invalid`, `invalid-request`, `case-definition-invalid`, `idempotency-conflict`,
`forbidden`, `model-error`, `server-error`.

`form-invalid` carries a `violations[]` array whose `pointer` is a real **RFC 6901 JSON Pointer**
(`/outcome`, `/nested/outcome`, and `""` for a missing required field at the document root), so a
renderer can bind messages to inputs without domain knowledge.

### 4.2 Concurrency — `ETagSupport`

Every mutable row carries `VERSION_`. Updates are
`UPDATE … SET VERSION_ = VERSION_ + 1 WHERE ID_ = :id AND VERSION_ = :expected`; zero rows means
conflict, never retry. The version is the `ETag`, and it is **always constructed locally** as
`version + 1` — never re-read, because a re-read is a second statement a concurrent writer can
commit in front of.

`If-Match: *` is supported (proceed if a representation exists, else `412`).

### 4.3 Idempotency — `IdempotencySupport`

`Idempotency-Key` on `POST /cases`, scoped per caller. Claim → execute → complete. Replay returns
the original status and body. Duplicate requests against an unfinished claim receive `409` and do
not execute business work; client-error failures release their own claim with an owner token.

### 4.4 Identity, roles and tenancy — `CallerResolver`, `ActionPolicy`

- **Tenant** is derived from a `tenant:<id>` authority on the principal — **never from a request
  body**. Exactly one is required; zero or several is a `403`.
- **Case roles** come from `CM_PARTICIPANT` (`owner`, `handler`, `watcher`).
- **Identity groups** come from the principal and are matched against a task's candidate groups.

`ActionPolicy` is the single source of truth for both **projection** (`availableActions[]`) and
**enforcement** (`assertAllowedOn*`). Both surfaces delegate to the same rule so they cannot
disagree — but **enforcement lives in the service layer too**, because a client that POSTs a URL
directly never reads the projection.

Action vocabulary: `update`, `close`, `cancel`, `enable`, `start`, `complete`, `terminate`,
`claim`, `comment`, `add-document`, `remove-document`, `achieve`, `pause`, `resume`,
`start-process`, `deploy-case-definition`, `subscribe-webhook`.

Production deployments should keep three authorization concerns separate:

- **Tenant membership** decides which tenant-scoped records a caller may see. The API derives this
  from identity-provider authorities such as `tenant:t1`; request bodies never choose a tenant.
- **Enterprise groups** decide pool eligibility, for example whether a caller can claim a task whose
  candidate group is `complaints-handlers`.
- **Case participants** decide case-local rights such as owner, handler, reviewer or watcher.

The PoC keeps this compact, but the intended production model is OIDC/JWT at the boundary, a
tenant-aware `CallerResolver`, and an `ActionPolicy` backed by Worker Permissions or an equivalent
enterprise authorization source. Case-definition deployment rejects candidate groups that collide
with reserved participant role names, so a global group cannot accidentally become a case-local role.

---

## 5. Core domain

### 5.1 Model

`CaseDefinition` is the pinned binding of BPMN orchestration, contract, and presentation releases.
`CaseInstance` is a running case. `CM_PLAN_ITEM`, `CM_TASK`, `CM_MILESTONE`, and
`CM_LINKED_PROCESS` are BPMN-observation projections used for API reads, worklists, and audit
context. Their `STAGE`, `HUMAN_TASK`, `PROCESS_TASK`, and `MILESTONE` classifications do not grant
them authority to activate, complete, or route work.

```mermaid
flowchart LR
  bpmn["BPMN engine facts"] --> observations["Engine observations"]
  observations --> projections["task / stage / milestone / linked-process projections"]
  projections --> reads["case API and worklists"]
  bpmn --> lifecycle["sequencing and lifecycle authority"]
```

### 5.3 Services

`CaseService`, `CaseTaskService`, `CommentService`, `DocumentService`,
`MilestoneService`, `LinkedProcessService`, `WebhookService`, `CaseDefinitionService`,
`SlaService`.

The module pattern for a platform mutation is **row + event + audit in one transaction**. Engine
observation handling applies the associated projection effects atomically; it does not replay a
separate case lifecycle. `case-management-core` has a real `DataSourceTransactionManager`, so
`@Transactional` genuinely works — **but only through a Spring proxy**.

---

## 6. Engine integration

Engine commands cross the `EngineGateway` seam. Engine facts enter separately through the
observation ingress/handler seam, which validates ownership and updates read-model projections.
The two command implementations pass one shared gateway contract suite (`EngineGatewayContract`);
observation handling has its own neutral contracts.

| Mode | Gateway | Transaction semantics |
|---|---|---|
| `embedded` | `EmbeddedEngineGateway` | The engine call joins the case transaction |
| `remote` | `RemoteEngineGateway` behind `OutboxEngineGateway` | Cannot join; commands go to `CM_ENGINE_COMMAND` and are drained by `EngineCommandDispatcher` |

In remote mode a row is written with `ENGINE_SYNC_ = PENDING` and an engine id is written back on
success. The worklist hides unsynced tasks; linked processes deliberately do not.

```mermaid
sequenceDiagram
    participant API as "Case API"
    participant Core as "Case core"
    participant Engine as "Embedded engine"
    participant Outbox as "CM_ENGINE_COMMAND"
    participant Dispatcher as "Engine command dispatcher"
    participant Remote as "Remote Operaton REST"

    API->>Core: "Create case / request engine operation"
    alt "embedded mode"
        Core->>Engine: "Start process / complete engine task in same transaction"
        Engine-->>Core: "Lifecycle observations"
        Core-->>API: "Committed case and projections"
    else "remote mode"
        Core->>Outbox: "Insert command with claim metadata"
        Core-->>API: "Committed case view with ENGINE_SYNC_=PENDING"
        Dispatcher->>Outbox: "Claim due command"
        Dispatcher->>Remote: "Execute engine call"
        Remote-->>Dispatcher: "Engine ids"
        Dispatcher->>Outbox: "Mark completed"
        Dispatcher->>Core: "Observation confirms projections"
    end
```

---

## 7. Events, outbox and webhooks

### 7.1 The transactional outbox

A mutation writes the domain row, a `CM_EVENT` row, a `CM_AUDIT_LOG` row and any
`CM_WEBHOOK_DELIVERY` fan-out **in one local transaction**. Proven under rollback by mutation
testing (R4 in `FINDINGS.md`).

Events are CloudEvents. `source` is hard-set from the configured engine id.

Event types: `case.created`, `case.updated`, `case.closed`, `case.cancelled`,
`case.planitem.transitioned`, `case.task.created`, `case.task.claimed`, `case.task.completed`,
`case.comment.added`, `case.document.added`, `case.document.removed`,
`case.milestone.achieved`, `case.process.started`, `case.sla.started`, `case.sla.paused`,
`case.sla.resumed`, `case.sla.warning`, `case.sla.breached`,
`case.sla.escalated`.

### 7.2 Webhook delivery

`WebhookDispatcher` claims due deliveries with a claim-by-`UPDATE` protocol (per-call token +
lease), signs the body with HMAC-SHA256 (`X-Case-Signature: sha256=…`), POSTs it, and applies a
retry ladder before dead-lettering.

Secrets are stored as both a one-way verification hash (`SECRET_HASH_`) and encrypted signing
material (`SECRET_KEY_ID_`, `SECRET_CIPHERTEXT_`). The plaintext is returned exactly once at
subscription time; the dispatcher resolves the encrypted material through `WebhookSecretStore`, so
existing subscriptions survive application restarts when the same encryption key is configured.

### 7.3 The pull feed

`GET /events` is the designated recovery path for consumers that missed a webhook. `EventRepository`
serializes sequence allocation with a transaction-held append lock before writing `CM_EVENT`, so a
later sequence cannot become visible before an earlier committed one. This keeps the numeric cursor
safe for pull recovery at the cost of serializing event appends.

---

## 8. SLA

`SlaService` starts, pauses and resumes clocks; `SlaSweeper` emits warnings, breaches and
escalation events on a schedule; `BusinessCalendar` does working-time arithmetic.

The calendar deliberately **rejects** overnight/inverted intervals and overlapping same-day
intervals at parse time, and rejects negative durations — an earlier overlap bug *deflated* due
dates, which is the dangerous direction for a breach calculation.

Resume re-derives the deadline through the calendar rather than adding wall-clock time.

SLA targets use the contract vocabulary `CASE`, `STAGE`, `TASK`, `MILESTONE`, and `OCCURRENCE`.
An `OCCURRENCE` binding is keyed by the native BPMN loop/activity occurrence observed from the
engine; it is not inferred from a separate case lifecycle. SLA targets may whitelist pause reasons.
The legacy database column is named `PAUSED_STATES_JSON_`, but it is interpreted as pause reasons,
not lifecycle states.

---

## 9. Persistence

Schema source of truth: [`db-design.sql`](../db-design.sql), executed by an applied Liquibase
changeset and **never re-typed**. PoC-only additions go in `cm-poc-additions.xml`; an applied
changeset is never edited.

Treat changelog files as append-only once a changeset may have run in a developer, CI or shared
environment. New corrections are added as new changesets at the end of the relevant file with a
short rationale comment. Existing changesets are not moved merely to group related columns or tables
together, because the changelog is also the execution timeline.

**Access pattern:** Spring `JdbcClient`, no ORM. One repository per aggregate.

### 9.1 Tables with a code path

`CM_CASE`, `CM_CASE_DEF`, `CM_PLAN_ITEM`, `CM_PLAN_ITEM_DEF`, `CM_TASK`, `CM_PARTICIPANT`,
`CM_COMMENT`, `CM_MILESTONE`, `CM_LINKED_PROCESS`, `CM_EVENT`, `CM_AUDIT_LOG`, `CM_WEBHOOK_SUB`,
`CM_WEBHOOK_DELIVERY`, `CM_ENGINE_COMMAND`, `CM_IDEMPOTENCY_KEY`, `CM_SLA_POLICY`,
`CM_SLA_TARGET`, `CM_SLA_RECORD`, `CM_BUSINESS_CALENDAR`.

### 9.2 Tables with DDL and no code path

`CM_BULK_OPERATION`, `CM_BULK_OPERATION_ITEM`, `CM_CASE_LINK`, `CM_DEF_IDENTITY_LINK`,
`CM_DOCUMENT`, `CM_QUEUE`, `CM_SAVED_FILTER`.

Deliberate scope — `db-design.sql` is the full target schema and the PoC implements a subset.
Recorded so unused DDL is not mistaken for a missing implementation.

`FINDINGS.md` now aligns to this table inventory.

### 9.3 Identifier formats

- Case definition: `{tenant}:{key}:{version}` — tenant-qualified so two tenants can share a key.
- Case instance: `{engineId}:{uuid}`.

---

## 10. Configuration

| Property | Default | Meaning |
|---|---|---|
| `casemgmt.enabled` | `true` | Master switch; `false` leaves a plain Operaton app untouched |
| `casemgmt.engine-id` | — | Stamped as CloudEvent `source` |
| `casemgmt.engine.mode` | `embedded` | `embedded` \| `remote` |
| `casemgmt.engine.remote.base-url` | — | `engine-rest` root |
| `casemgmt.engine.remote.auth-mode` | `auto` | `auto` \| `none` \| `basic` \| `bearer` |
| `casemgmt.engine.remote.username` / `.password` | — | Basic auth credentials when `auth-mode=basic` |
| `casemgmt.engine.remote.bearer-token` | — | Static bearer token for controlled deployments; prefer a `RemoteEngineBearerTokenProvider` bean for OIDC/client-credential integration |
| `casemgmt.engine.remote.connect-timeout-ms` | `5000` | |
| `casemgmt.engine.remote.read-timeout-ms` | `10000` | Bounds a hung-but-connected engine |
| `casemgmt.events.type-prefix` | **none** | **Startup fails if webhooks are enabled and this is unset** |
| `casemgmt.webhooks.secret-encryption-key` | **none** | Base64 AES key for encrypted webhook signing material |
| `casemgmt.webhooks.secret-key-id` | `default` | Key identifier stored with encrypted webhook secrets |
| `casemgmt.security.mode` | `basic` | PoC local mode; set `oidc` for JWT/OIDC resource-server mode |
| `casemgmt.security.oidc.*` | claim defaults | Principal, tenant, group and Worker Permissions claim mapping |
| `casemgmt.schedulers.enabled` | `true` | |
| `casemgmt.schedulers.webhook-interval-ms` | `5000` | |
| `casemgmt.schedulers.engine-command-interval-ms` | `5000` | |
| `casemgmt.schedulers.sla-sweep-interval-ms` | `60000` | |
| `casemgmt.schedulers.idempotency-purge-interval-ms` | `3600000` | |

**Substitutable beans** (declared `@ConditionalOnMissingBean`): `CallerResolver`, `ActionPolicy`,
`CriterionEvaluator`, `FormValidator`, `EventPublisher`. Controllers arrive via `@Import` and are
**not** overridable.

Production deployments should monitor scheduler lease age, dead-letter volume, engine-command
backlog, SLA sweep lag, webhook signing-secret key configuration, and event cursor lag.

---

## 11. Testing strategy

- **Real Oracle 23ai via Testcontainers** for anything touching the schema. No H2 — the DDL uses
  `IS JSON` constraints H2 does not implement.
- **Real Operaton engines** in both modes; the remote-mode IT boots two application contexts.
- **One shared gateway contract** executed by both gateway implementations.
- **Mechanism stripping**: every test asserting a failure or a guard was proven able to fail by
  removing the mechanism and observing the expected failure.
- **Architecture rules** (`CrossModuleArchitectureTest`, `NoCaseTypeVocabularyTest`) run repo-wide.
- **OpenAPI conformance** validates live responses against the published contract, including case
  creation, case reads, task reads, case-definition discovery and dead-letter responses.

The test suite requires Docker for Oracle Testcontainers and, for the PoC module, embedded and
remote Operaton integration contexts.

---

## 12. Known limitations

Do not treat this section as complete — [`FINDINGS.md`](../FINDINGS.md) and the
[issue tracker](https://github.com/Ronrock/case-management-design/issues) remain the working risk
register. The items that most affect production use after the structural fixes are:

1. **Detailed UX/form-rendering behavior is still limited** — the Lit package provides shell and
   portal contracts, not a production-grade bank workflow UI.
2. **Remote engine authentication still needs bank integration** — OIDC/resource-server support and
   bearer-token hooks exist, but production deployments must plug in the bank's token source and
   Worker Permissions mapping.
3. **Direct Operaton Tasklist writes are blocked for ordinary users, not reconciled** — managed
   tasks should still be operated through the case API; if Tasklist must stay available for writes,
   an engine-side reconciliation listener is required.

---

## 13. Glossary

Glossary: case, case definition, plan item, stage, sentry/criterion, milestone, discretionary
item, worklist, outbox, tenant, participant role and identity group.

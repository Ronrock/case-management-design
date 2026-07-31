# Case Management on Operaton — PoC Design

**Version:** 1.0 · **Date:** 2026-07-31 · **Status:** Approved for planning

Companion to `design principles.md` (API design), `db-design.md` / `db-design.sql`
(schema), and `openapi-specs.md` (contract of record). Where this document and
those disagree, this document wins **for the PoC only** — the originals remain the
target design.

---

## 1. Purpose

Build a reference implementation of the case management service on **Operaton**
(the Camunda 7 fork, `2.2.0-SNAPSHOT`, Java 17+, Spring Boot 3.5.6) that proves
the design works end-to-end and finds where it is wrong.

This is a PoC, not a production service. It carries no real data and no real
load. Its deliverable is working code **plus a written verdict on four risks**.

### 1.1 Risks the PoC must retire

| # | Risk | Retired by |
|---|---|---|
| R1 | The CMMN-subset plan-item state machine — criteria re-evaluated after every mutation, repetition, required items, manual activation | Table-driven evaluator tests + the complaint model running end-to-end |
| R2 | The "wraps the engine" premise — human tasks delegating to Operaton, BPMN correlated by caseId, transaction boundaries | The same gateway contract suite passing in embedded *and* remote mode |
| R3 | The model-driven UI contract — `availableActions[]` + JSON Schema really driving a generic frontend | **Partially.** A generic-consumer contract test drives the whole complaint path knowing only the API (§9). Real rendering and UX friction stay open until the frontend phase (§8) |
| R4 | Events and federation — transactional outbox, CloudEvents, webhook retry/DLQ, cursor recovery | Webhook capture tests incl. forced failure to `DEAD`; a test consumer resuming from a cursor after simulated downtime |

### 1.2 Definition of done

1. The complaint case type runs end-to-end **through the API** in **embedded** mode
   and in **remote** mode.
2. The generic-consumer test (§9) completes that path using only
   `availableActions[]` and fetched form schemas — no case-type knowledge.
3. `FINDINGS.md` records, per risk: what held, what the spec got wrong, what it
   cost. A PoC that produces code but no findings has failed at its actual job.
   For R3 it must also state precisely what remains unproven without a UI.

---

## 2. Scope

The full spec is 60 endpoints and 25 tables — three to four implementation plans.
The PoC is one vertical slice through all four risks against one case type.

### 2.1 In scope (~24 endpoints)

| Group | Endpoints | Why |
|---|---|---|
| Case definitions | `POST /case-definitions`, `GET /case-definitions`, `GET /case-definitions/{key}`, `GET /case-definitions/{key}/forms/{formKey}` | Nothing runs without a deployed model; forms are R3 |
| Cases | `POST /cases` (Idempotency-Key), `GET /cases`, `GET`/`PATCH /cases/{id}` (ETag), `POST .../close`, `POST .../cancel` | Core resource + both cross-cutting mechanisms |
| Plan items | `GET /cases/{id}/plan-items`, `POST .../{itemId}/enable\|start\|complete\|terminate` | R1, in full |
| Tasks | `GET /tasks`, `POST /tasks/{id}/claim`, `POST /tasks/{id}/complete` | R2, human tasks through Operaton |
| Processes | `POST`/`GET /cases/{id}/processes` | R2, BPMN fragment correlated by caseId |
| Milestones | `POST /cases/{id}/milestones/{milestoneId}/achieve` | Falls out of the state machine, nearly free |
| Collaboration | `GET`/`POST /cases/{id}/comments` | Internal/external split (G13) at trivial cost |
| SLA | `GET /cases/{id}/slas`, `POST .../slas/{slaId}/pause\|resume` | The pause/resume clock, which naive designs get wrong |
| Events & webhooks | `GET /events`, `GET /cases/{id}/events`, `GET`/`POST /webhooks` | R4, push and pull both |

### 2.2 Deferred

Queues & routing · bulk operations & `/operations` · saved filters · documents ·
case links · `migrate` · `escalate` · `suspend`/`resume`/`reactivate` ·
`/case-history` · the audit-log *endpoint* · definition identity-links ·
attachment categories · DLQ redelivery endpoint · SLA policy CRUD · task
`delegate`/`unclaim` · `PATCH /tasks/{id}`.

This is the "layer on later" bucket from `design principles.md` §9, plus the
lifecycle transitions that add test surface without adding insight.

Two exceptions, because retrofitting them is the expensive half:

- **Audit rows are still written** on every mutation, even though
  `GET /cases/{id}/audit-log` is not implemented.
- **The full 25-table DDL is still deployed.** It is already written; running it
  against real Oracle validates it for free, even where tables sit unused.

### 2.3 Follow-on sub-projects (each gets its own spec)

1. **Generic frontend** — the model-driven SPA, built against a supplied design
   system and UX design once this backend is complete (§8). Finishes retiring R3.
2. Work distribution — queues, routing rules, bulk operations, saved filters
3. Content & relations — documents, case links, attachment categories
4. Federation — a cross-engine index service consuming the event stream
5. Lifecycle & compliance — case migration, retention, anonymize

---

## 3. Architecture

### 3.1 Decision

**One Spring Boot application with an embedded Operaton engine**, packaged as
reusable Maven artifacts with a Spring Boot starter as the import point. The case
service calls the engine through a narrow `EngineGateway` interface, which has two
implementations: in-process Java API, or `engine-rest` over HTTP.

Rejected alternatives:

- *Separate service, engine only over REST.* Cleaner component boundary, but it
  forces distributed-transaction work in every mode and contradicts
  `design principles.md` §10.2 ("same DB, separate schema, one transaction
  manager"). Kept available as the `remote` mode instead of imposed as the only mode.
- *In-tree Operaton module / `ProcessEnginePlugin`.* Deepest integration and a
  route to a community extension, but inherits a 15–20 minute build and
  fork-maintenance burden, and entangles PoC findings with engine internals.

### 3.2 Artifacts

```
case-management-core              domain, state machine, services, persistence;
                                  depends only on the EngineGateway interface
case-management-engine-embedded   gateway impl → Operaton Java API
case-management-engine-remote     gateway impl → engine-rest HTTP client
case-management-rest              controllers, ETag/If-Match + Idempotency filters, OpenAPI
case-management-spring-boot-starter   auto-configuration + properties (the import point)
case-management-poc-app           complaint case type, sample BPMN, docker-compose
```

A `case-management-ui` module is intentionally absent — see §8.

`case-management-core` must never import an `org.operaton` type. This is a build
rule, not a convention — enforced by an ArchUnit test — because it is what makes
both engine modes possible and keeps the state-machine tests free of engine setup.

### 3.3 Configuration

```properties
casemgmt.enabled=true
casemgmt.engine-id=eng-a
casemgmt.engine.mode=embedded            # embedded | remote
casemgmt.engine.remote.base-url=https://…/engine-rest
casemgmt.events.type-prefix=             # no default; see §6.2
```

- `casemgmt.enabled=false` leaves a plain Operaton application untouched: no
  beans, no schema, no endpoints.
- `mode=embedded` requires the Operaton starter on the classpath
  (`@ConditionalOnClass(ProcessEngine.class)`). If absent, auto-configuration
  **fails fast at startup** naming the missing dependency, rather than surfacing
  a `NoSuchBeanDefinitionException` deeper in.
- `mode=remote` needs no engine on the classpath at all.

### 3.4 EngineGateway

```java
public interface EngineGateway {
    EngineTaskRef createHumanTask(HumanTaskRequest request);
    void          claimTask(String engineTaskId, String userId);
    void          completeTask(String engineTaskId, Map<String, Object> variables);
    EngineProcessRef startProcess(StartProcessRequest request);
    void          cancelProcess(String processInstanceId, String reason);
    List<EngineTaskRef> findTasks(EngineTaskQuery query);
}
```

Everything Operaton-shaped lives behind this interface.

### 3.5 Consistency model

The two modes differ materially here, and the difference is explicit in the API
rather than hidden.

**Embedded.** One `DataSource`, two Oracle schemas (`OPERATON`, `CM`), one
transaction manager. Case mutation + engine call + `CM_EVENT` + `CM_AUDIT_LOG` +
`CM_WEBHOOK_DELIVERY` fan-out all commit together. The transactional outbox works
exactly as `db-design.md` §4.2 describes.

**Remote.** The local transaction covers the CM schema only; the engine is a
remote system and cannot join it. Engine effects therefore go through a **command
outbox**:

- `CM_ENGINE_COMMAND` — a 26th table, PoC-specific, not part of the target design.
  Columns: `ID_`, `CASE_ID_`, `TYPE_` (`CREATE_TASK`, `COMPLETE_TASK`,
  `CLAIM_TASK`, `START_PROCESS`, `CANCEL_PROCESS`), `PAYLOAD_JSON_`, `STATUS_`
  (`PENDING`/`RETRYING`/`DONE`/`DEAD`), `ATTEMPTS_`, `NEXT_ATTEMPT_AT_`,
  `LAST_ERROR_`, `CREATED_AT_`.
- Written in the same transaction as the case mutation; drained by a dispatcher
  with the same claim-and-backoff mechanics as the webhook dispatcher (§6.1).
- `CM_TASK` and `CM_LINKED_PROCESS` gain `ENGINE_SYNC_` (`PENDING`/`SYNCED`/
  `FAILED`). A task is not claimable until `SYNCED`, and `availableActions[]`
  reflects that — so remote mode is eventually consistent and *visibly* so,
  rather than silently half-applied.

**Known limitation (documented, not solved).** The CM API is the only supported
write path for case-bound tasks. Completing such a task directly in Operaton's own
Tasklist bypasses the state machine. In embedded mode a `TaskListener` catches it
and reconciles; in remote mode the engine has no outbound push channel, so this is
out of scope for the PoC and recorded as a federation gap in `FINDINGS.md`.

---

## 4. Domain core

### 4.1 Case definition format

JSON, deployed via `POST /case-definitions`, exploded into `CM_CASE_DEF` (forms,
roles, routing as JSON documents) and `CM_PLAN_ITEM_DEF` (one row per plan item,
normalized because the evaluator queries it on every mutation).

No BPMN/CMMN XML: the spec defines its own CMMN subset, and JSON keeps the deploy
payload and the form schemas in one language.

### 4.2 The complaint model

Chosen to exercise every state-machine feature:

| Plan item | Type | Flags | Exercises |
|---|---|---|---|
| `intake` → `registerComplaint` | Stage → human task | — | Baseline, auto-activation, form rendering |
| `acknowledged` | Milestone | entry criterion | Automatic achievement |
| `assessment` → `assessComplaint` | Stage → human task | `required` | Stage completion gating |
| `investigation` → `investigateAspect` | Stage → human task | `manualActivation`, `repetition` | Enable-then-start; repeated instantiation |
| `decision` → `sendDecisionLetter` | Stage → process task | — | BPMN correlation by caseId |
| `decided` | Milestone | entry criterion over sibling state | Cross-item criteria |
| `closure` → `closeComplaint` | Stage → human task | `required` | Required-item gating on case close |

### 4.3 Plan model evaluation

A single `PlanModelEvaluator` runs inside the same transaction after every case
mutation (`design principles.md` §3.2).

- **Fixpoint loop.** Achieving a milestone can satisfy another item's entry
  criterion, so evaluation repeats until no transition fires.
- **Iteration cap of 20**, then throw. A model with mutually-triggering criteria
  is a modelling bug and must surface loudly instead of spinning.
- **Deterministic order.** Items are evaluated by `SORT_ORDER_`, so transitions
  are reproducible and testable.
- Every transition emits a `planitem.transitioned` event through the outbox.

### 4.4 Expression language

**JUEL**, per Operaton's `juel` module (open question #1 in `design principles.md`
is hereby closed for the PoC).

The evaluator constructs an `ExpressionFactory` with a **context-only
`ELResolver` and no bean resolver**. Expressions can read:

```
case.state, case.priority, …          the case
vars.<name>                           case variables
items.<defKey>.state                  sibling plan-item states
```

They cannot reach Spring beans, static methods, or the filesystem. This matters
because definitions are deployed by teams over the API: without the restriction,
`POST /case-definitions` is a remote-code-execution endpoint. The restriction is
covered by an explicit negative test.

### 4.5 availableActions and enforcement

One `ActionPolicy` component holds the rule table, with two entry points:

- `policy.list(case, caller)` → populates `availableActions[]` on reads
- `policy.assertAllowed(case, caller, action)` → called by every mutation,
  returns `409` when it fails

Same rules, two callers, so the projection and the enforcement cannot drift —
the failure mode Appendix C warns about. Inputs: lifecycle state, plan-item
states, `required` flags, the caller's participant roles, and (in remote mode)
`ENGINE_SYNC_`.

### 4.6 Forms

JSON Schema stored in `CM_CASE_DEF.FORMS_JSON_`, served at
`GET /case-definitions/{key}/forms/{formKey}`, and used server-side to validate
task-completion payloads via `networknt/json-schema-validator`. One schema both
renders the form and validates the submission (Appendix D).

### 4.7 SLA slice

One policy for complaints:

- Targets: `firstResponse` = PT4H, `resolution` = P5D
- Business calendar: Dutch working hours + holidays, as one JSON document
- `pausedStates: ["WAITING_ON_CUSTOMER"]`

A `@Scheduled` sweeper polls `(STATUS_, WARN_AT_)` and `(STATUS_, DUE_AT_)` for
`RUNNING` records past threshold and emits `sla.warning` / `sla.breached`.
Pause records `PAUSED_AT_`; resume adds the elapsed pause to
`PAUSED_TOTAL_SECS_` and shifts `DUE_AT_`/`WARN_AT_` forward by that amount.

The business-calendar walker (add an ISO-8601 duration across working intervals,
skipping holidays) is the fiddliest piece here and gets dedicated unit tests
covering: duration spanning a weekend, a holiday, a pause crossing a
non-working interval, and DST transitions.

---

## 5. Persistence

**Spring `JdbcClient` with explicit SQL.** Hand-written SQL lives next to the
hand-written DDL: full control over the optimistic-locking update, CLOB/JSON
binding, and worklist queries hitting the indexes `db-design.md` §3.2 designed.
Rejected: JPA/Hibernate (fights `CLOB … IS JSON` mapping, less control over the
exact queries) and MyBatis (consistent with Operaton, but XML mapper overhead for
a greenfield module).

- **Oracle 23ai Free** in Docker, one database, two schemas (`OPERATON`, `CM`).
- **Liquibase** owns the CM schema; the initial changeset is a `sqlFile`
  reference to the existing `db-design.sql`, so that file stays the source of
  truth and is validated on every test run. Operaton creates its own schema.
- Optimistic locking:
  `UPDATE … SET VERSION_ = VERSION_ + 1 WHERE ID_ = :id AND VERSION_ = :expected`.
  Zero rows affected → `412`, with no separate read.
- PoC-only schema additions, marked as such: `CM_ENGINE_COMMAND` (§3.5), and
  `ENGINE_SYNC_` on `CM_TASK` / `CM_LINKED_PROCESS`.

---

## 6. Events, concurrency, errors

### 6.1 Transactional outbox

Every mutation writes, in one transaction: the entity change, a `CM_EVENT` row
(`SEQ_` from the sequence), a `CM_AUDIT_LOG` row with before/after images, and one
`CM_WEBHOOK_DELIVERY` row per matching subscription. Nothing is published by the
request thread.

A `@Scheduled` dispatcher claims due deliveries with
`SELECT … FOR UPDATE SKIP LOCKED` (so multiple instances never double-send),
POSTs with `X-Case-Signature: sha256=…` (HMAC over the raw body, per-subscription
secret), and backs off **1m, 5m, 25m, 2h, 10h** before parking the row in
`DEAD` — which *is* the dead-letter queue per `db-design.md` §3.6.

### 6.2 Event envelope

CloudEvents 1.0, structured JSON mode. `source` = `casemgmt.engine-id`,
`subject` = case ID, `type` = `{prefix}.case.created` etc.

`casemgmt.events.type-prefix` has **no default**. The docs use `com.example.*`,
and shipping a placeholder namespace into someone's broker is a mistake that is
expensive to undo — startup fails if the property is unset while webhooks are
enabled.

`GET /events?after={seq}&limit=` is cursor pagination over `SEQ_`: the recovery
path for a consumer that missed webhook deliveries.

### 6.3 ETag / If-Match

ETag is `VERSION_` as a strong tag. Mutations **without** `If-Match` get
`428 Precondition Required` rather than proceeding silently — a client that
forgot the header is a bug worth surfacing in a PoC. Mismatch is `412`.

### 6.4 Idempotency

On `POST /cases`: insert `(KEY_, SCOPE_)` as `IN_PROGRESS` before doing work.
On PK conflict —

| Stored row | Response |
|---|---|
| Complete, request hash matches | Replay original `201`, header `Idempotency-Replayed: true` |
| Complete, request hash differs | `409` |
| Still `IN_PROGRESS` | `409` with a retry hint |

Cleanup job drops rows older than 48h via the `CREATED_AT_` index.

### 6.5 Errors

RFC 9457 `application/problem+json` throughout, with a stable `code` field
frontends can switch on.

| Status | Used for |
|---|---|
| `409` | Illegal transition — body names the current state and the actions that *are* available, so a client that raced can self-correct |
| `412` | ETag mismatch |
| `422` | Form-schema violation — carries JSON Pointer paths per failed field, so a renderer can attach messages to inputs without knowing the domain |
| `428` | Mutation without `If-Match` |

---

## 7. Identity

**Operaton's own identity service with HTTP basic auth.** Real users and groups,
so participant roles and candidate groups behave honestly, and it is the identity
source the engine already trusts for tasks. No external infrastructure.

The OpenAPI declares OAuth2; the PoC deviates deliberately and records it. Swapping
in a real authorization server is a Spring Security configuration change, not a
design change, because `ActionPolicy` consumes a resolved principal + groups
rather than a token.

---

## 8. Frontend — deferred

**No UI is built in this project.** The frontend follows as its own sub-project
(§2.3) once a design system and UX design are supplied, and it is what finally
retires R3.

Deferring the UI removes the consumer that would have proven the model-driven
contract, so the backend takes on three obligations in its place:

1. **A generic-consumer contract test** (§9) drives the complaint path end to end
   knowing only the API — it reads `availableActions[]`, follows the `href` it
   finds, fetches the referenced form schema, and generates a conforming payload
   from that schema. No case-type constants anywhere in the test. This proves the
   contract is *sufficient* to drive a UI, without proving anything about UX.
2. **Every response carries what a renderer needs** on the first read: each entry
   in `availableActions[]` has `action`, `href`, `method`, and `formKey` when a
   form applies. No action requires a second call to discover how to invoke it.
3. **Form schemas may carry `ui:` annotations** and the service passes them
   through untouched, so the future design system can attach layout hints without
   a service change.

What stays unproven until the UI exists, and must be said so in `FINDINGS.md`:
whether the schemas are *renderable* into something a back-office worker can
actually use, whether `availableActions[]` maps cleanly onto real interaction
patterns, and whether event-cursor polling is a workable live-update mechanism
for an open case.

---

## 9. Testing

In the order things break:

| Layer | What |
|---|---|
| Unit, table-driven | `PlanModelEvaluator`: model + item states + variables → expected transitions. The densest tests in the codebase (R1), including the fixpoint cap and mutually-triggering criteria |
| Unit | JUEL criterion evaluation, **including a negative test that beans are unreachable**; business-calendar walker; `ActionPolicy` |
| Architecture | ArchUnit: `case-management-core` imports no `org.operaton` type |
| Integration | Testcontainers `gvenzl/oracle-free`; schema built by Liquibase from the real `db-design.sql`, so the DDL is validated on every run |
| Gateway contract | One suite, run against **both** implementations: embedded (in-process engine) and remote (a second Spring context in the same JVM running engine-only with `casemgmt.enabled=false` and `engine-rest` exposed). Proves the interface is honest and exercises the command outbox and `ENGINE_SYNC_` under real latency |
| Spec conformance | Responses validated against `openapi-specs.md` via `swagger-request-validator`, keeping the spec the contract of record rather than decoration |
| Webhooks | Local capture server: HMAC signature verification, ordering, at-least-once duplicates, forced-failure path through to `DEAD`; a consumer resuming from a stale cursor after simulated downtime |
| Generic consumer | The complaint path driven end to end using only `availableActions[]` + fetched form schemas, with no case-type constants in the test (§8). Stands in for the deferred UI as R3's partial proof |

Implementation follows TDD per `superpowers:test-driven-development`.

---

## 10. Environment and build

- Java 21 (Operaton tests on 17/21/25; Spring Boot 3.5 supports it)
- Maven wrapper, multi-module, artifacts per §3.2
- Docker Compose: Oracle 23ai Free, one database, two schemas
- **Operaton consumed as released artifacts from Maven Central.** The local
  `/Volumes/dockdrive/dev/operaton` clone (`2.2.0-SNAPSHOT`, shallow) is for
  reading and for debugging into engine internals — not a build dependency.
  If a needed fix exists only in `main`, that becomes an explicit, recorded
  decision to build the fork locally.
- Implementation lives in this repository alongside the design documents, so
  spec and code stay in one history.

---

## 11. Deviations from the target design

Recorded so the PoC's shortcuts never get mistaken for design decisions:

| # | Deviation | Reason |
|---|---|---|
| D1 | 24 of 60 endpoints | §2 — one vertical slice through four risks |
| D2 | Basic auth on Operaton identity instead of OAuth2 | §7 — configuration change, not a design change |
| D3 | `CM_ENGINE_COMMAND` + `ENGINE_SYNC_` columns added | §3.5 — remote mode cannot join the local transaction |
| D4 | Direct engine writes unsupported in remote mode | §3.5 — no outbound push channel from Operaton |
| D5 | JUEL, not FEEL | §4.4 — closes open question #1 for the PoC |
| D6 | Audit rows written, audit endpoint absent | §2.2 — write paths are the expensive half to retrofit |
| D7 | No UI; R3 only partially retired | §8 — frontend follows once a design system and UX design are supplied |

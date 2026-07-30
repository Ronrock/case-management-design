# Case Management Extension for Camunda 7 — API Design Document

**Version:** 1.0 · **Date:** 2026-07-29 · **Status:** Draft for review

---

## 1. Purpose and Context

Camunda 7's CMMN implementation is deprecated, yet enterprise back-office work is
case-shaped: long-running, knowledge-driven, partially ad-hoc, and only partially
automatable as BPMN. This document specifies a **case management service layer**
that runs alongside a Camunda 7 engine and exposes REST APIs for case work.

Operating constraints:

- **Decentralized deployment.** Every DevOps team runs its own engine + case
  service instance. There is no central case database. The API must therefore be
  self-describing, event-emitting, and federable, so that cross-team overview
  frontends can be built later without redesign.
- **Headless by design.** Back-office frontends are built separately on top of
  these APIs. The API must carry enough metadata (available actions, form
  schemas, state models) that frontends can be generic and model-driven rather
  than hardcoded per case type.
- **Camunda 7 underneath.** Human tasks delegate to the Camunda task service;
  structured fragments run as BPMN process instances correlated to the case.

## 2. Architecture Overview

```
┌────────────────────────────────────────────────────┐
│                Back-office Frontends               │
│        (generic, model-driven, per team or shared) │
└────────────▲───────────────────────▲───────────────┘
             │ REST (this spec)      │ Webhooks / event log
┌────────────┴───────────────────────┴───────────────┐
│              Case Management Service               │
│  Case store · Plan-item state machine · SLA clock  │
│  Queues & routing · Webhook dispatcher · Audit log │
└──────┬──────────────────────┬──────────────────────┘
       │ Java/REST API        │ process correlation
┌──────▼──────────────────────▼──────────────────────┐
│                  Camunda 7 Engine                  │
│        Task service · BPMN runtime · History       │
└────────────────────────────────────────────────────┘
```

The case service owns: case entities, case definitions, plan-item states,
milestones, SLA tracking, participants, comments, document metadata, links,
audit log, event log, webhook subscriptions, queues, and saved filters. It
*wraps* Camunda for human tasks and BPMN execution, storing `caseId` as a
process variable / business key so correlation works in both directions.

## 3. Domain Model

| Entity | Description |
|---|---|
| **CaseDefinition** | Versioned template per case type: stages, task templates, milestones, role model, SLA policy, form schemas, identity links (who may start it). |
| **Case** | Runtime instance. Globally unique ID (`{engineId}:{uuid}`), business key, state, variables, SLA status, tenant. |
| **PlanItem** | Stage, human task, process task, or milestone inside a case. Carries CMMN-style lifecycle state and activation semantics. |
| **Task** | Human task, backed by Camunda task service, enriched with case context. |
| **Milestone** | Achievable marker, optionally auto-achieved by conditions. |
| **SlaRecord** | Deadline instance with pause/resume, business calendar, warning/breach thresholds. |
| **Participant** | (userOrGroup, role) on a case: owner, handler, reviewer, watcher, … |
| **Comment** | Note on a case; `visibility: internal \| external` separates worker notes from customer-facing communication. |
| **Document** | Metadata + external storage reference (DMS/S3), with attachment category. |
| **CaseLink** | Typed relation between cases (parentOf, duplicateOf, relatedTo), possibly cross-engine. |
| **Event** | Immutable, CloudEvents-formatted record of every state change; feeds the audit log, the event-log API, and webhooks. |
| **WebhookSubscription** | Registered consumer endpoint with event-type filter, secret, retry/DLQ state. |
| **Queue** | Named work queue with candidate groups and optional routing rules. |
| **SavedFilter** | Persisted case query for worklists and bulk operations. |

### 3.1 Case lifecycle state model

```
            ┌──────────► SUSPENDED ─────────┐
            │  suspend        │ resume      │ cancel
CREATED ──► ACTIVE ──────────►│◄────────────┤
            │  close          ▼             ▼
            └──────────► CLOSED ──────► CANCELLED
                          │  reopen/reactivate
                          └──────────► ACTIVE
```

### 3.2 Plan-item state model (CMMN-aligned)

```
AVAILABLE ──(entry criterion met / enable)──► ENABLED ──(start)──► ACTIVE
    │                                            │                   │
    │ (autoActivate)────────────────────────────►│                   ├─(complete)─► COMPLETED
    │                                                                └─(terminate/exit criterion)─► TERMINATED
```

Activation semantics per plan item, declared in the definition:

- `manualActivation: true|false` — mirrors CMMN manual activation rule.
- `entryCriteria` / `exitCriteria` — expressions over case variables and
  sibling plan-item states (a pragmatic subset of CMMN sentries). The service
  **re-evaluates the plan model after every case mutation**, the same approach
  Flowable's CMMN engine takes.
- `repetition: true|false` — allow repeated instantiation.
- `required: true|false` — case cannot complete a stage while required items
  are unfinished.

This is a deliberate, documented subset of CMMN 1.1: expressive enough for
enterprise case work, small enough to implement and reason about.


## 4. API Design Principles

These principles come directly from the cross-check against market solutions
(Pega DX API, Flowable, Salesforce Service Cloud, ServiceNow CSM, Google Cloud
Support, Oracle EDQ Case Management):

1. **Event-driven, not poll-only.** Every state change emits a CloudEvents
   record. Consumers get it via webhook subscriptions (push, with HMAC
   signatures, retries with exponential backoff, and a dead-letter state) or
   via `GET /cases/{id}/events` and `GET /events` (pull). This is the
   foundation for reactive frontends and cross-engine federation.
2. **Optimistic concurrency everywhere.** Every mutable resource returns an
   `ETag`; every mutation requires `If-Match` and answers `412 Precondition
   Failed` on conflict. (Pega's DX API works the same way with eTags on case
   actions — multiple back-office users on one case is the norm, not the
   exception.)
3. **Action discoverability (HATEOAS-lite).** Case, plan-item, and task
   responses embed `availableActions[]` computed by the server from the state
   machine and the caller's permissions. Frontends never re-implement rules —
   this is what makes a *generic* case UI possible across differently-defined
   case types (the core idea behind Pega's model-driven DX API).
4. **Form metadata as part of the contract.** Each human task template and
   case action can declare a JSON Schema (`GET /case-definitions/{key}/forms/
   {formKey}`), so frontends render forms dynamically.
5. **Idempotent creation.** `POST /cases` and other non-idempotent calls
   accept an `Idempotency-Key` header; retries return the original result.
   Essential for decentralized clients with at-least-once delivery semantics.
6. **Tenant- and engine-aware.** Every resource carries `tenantId` and
   `engineId`; IDs are globally unique so a federated overview can merge
   result sets from many engines without collisions.
7. **Async where work is heavy.** Bulk operations return `202 Accepted` with
   an operation resource (`GET /operations/{id}`) for status polling and
   cancellation — the pattern Oracle's case API uses for bulk update/delete.

## 5. API Surface (overview)

| Group | Endpoints (abridged) |
|---|---|
| Case definitions | `GET/POST /case-definitions`, `GET /case-definitions/{key}`, `GET .../versions`, `GET .../forms/{formKey}`, `GET/POST/DELETE .../identity-links` |
| Case lifecycle | `POST /cases` (Idempotency-Key), `GET /cases` (rich query), `GET/PATCH /cases/{id}` (ETag), `POST .../close|cancel|suspend|resume|reactivate`, `POST .../migrate` |
| Plan items | `GET /cases/{id}/plan-items`, `POST .../plan-items/{itemId}/enable|start|complete|terminate`, `POST /cases/{id}/plan-items` (ad-hoc task) |
| Tasks | `GET /tasks` (worklist), `GET /cases/{id}/tasks`, `POST /tasks/{id}/claim|unclaim|delegate|complete`, `PATCH /tasks/{id}` |
| Processes | `POST /cases/{id}/processes`, `GET /cases/{id}/processes` |
| Milestones | `GET /cases/{id}/milestones`, `POST .../{milestoneId}/achieve` |
| SLA | `GET /sla-policies`, `GET /cases/{id}/slas`, `POST .../slas/{slaId}/pause|resume`, `POST /cases/{id}/escalate` |
| Participants | `GET/POST /cases/{id}/participants`, `DELETE .../participants/{participantId}` |
| Collaboration | `GET/POST /cases/{id}/comments` (visibility internal/external), `GET/POST/DELETE /cases/{id}/documents`, `GET /cases/{id}/attachment-categories`, `GET/POST/DELETE /cases/{id}/links` |
| Queues & routing | `GET /queues`, `GET /queues/{id}/items`, `POST /cases/{id}/assign` (manual or rule-based) |
| Worklists | `GET/POST/PATCH/DELETE /saved-filters`, `GET /saved-filters/{id}/results` |
| Bulk | `POST /cases/bulk` → `202` + `GET /operations/{id}`, `POST /operations/{id}/cancel` |
| Events & webhooks | `GET /events`, `GET /cases/{id}/events`, `GET/POST /webhooks`, `PATCH/DELETE /webhooks/{id}`, `POST /webhooks/{id}/test` |
| History & audit | `GET /cases/{id}/audit-log`, `GET /case-history` |

## 6. Eventing and Federation

- **Format:** CloudEvents 1.0 JSON. `type` namespace:
  `com.example.case.created|updated|closed|reactivated`,
  `com.example.case.planitem.transitioned`, `com.example.case.task.*`,
  `com.example.case.sla.warning|breached`, `com.example.case.milestone.achieved`, …
- **Delivery:** at-least-once; consumers deduplicate on event `id`.
  Retries: exponential backoff, capped attempts, then dead-letter; DLQ
  entries are visible via `GET /webhooks/{id}/dead-letters` and can be
  redelivered.
- **Security:** per-subscription secret; payloads signed with
  `X-Case-Signature: sha256=…` (HMAC).
- **Federation path:** because each engine emits the same event schema and
  globally unique IDs, a later cross-team index/overview service is a pure
  consumer — no engine-side redesign required.

## 7. SLA Model

Adapted from Salesforce's entitlement/milestone split, generalized:

- **SlaPolicy** (per case type, selected by priority/customer tier):
  ordered set of **targets** (e.g. `firstResponse`, `resolution`), each with
  duration, business calendar reference, warning threshold, and breach actions
  (emit event, escalate, reassign).
- **SLA clock pause/resume:** targets may declare `pausedInStates`
  (e.g. `WAITING_ON_CUSTOMER`) or be paused explicitly via API — the
  single most common real-world SLA requirement missing from naive designs.
- **Escalation:** `POST /cases/{id}/escalate` is a first-class action
  (level, reason), mirroring Google Cloud Support's `escalate_case`.

## 8. Findings from the Market Cross-Check (gap register)

| # | Gap in v1 proposal | Market evidence | Resolution in this design |
|---|---|---|---|
| G1 | Pull-only API, no events | Webhook/event APIs are standard practice; federation impossible without | §6: CloudEvents log + webhook subscriptions with retries/DLQ |
| G2 | No concurrency control | Pega DX eTag on every case action | ETag/If-Match on all mutations, 412 on conflict |
| G3 | Frontend must derive allowed actions | Pega returns `availableActions` per case/assignment | Server-computed `availableActions[]` embedded in responses |
| G4 | No form metadata | Pega DX returns view/UI metadata for model-driven frontends | JSON Schema per form key on the definition resource |
| G5 | Plan-item semantics too shallow | Flowable evaluates sentries/repetition after every change; explicit reactivation API | §3.2 CMMN subset: entry/exit criteria, repetition, required, manual activation; `reactivate` with plan-item selection instead of naive `reopen` |
| G6 | No work distribution | ServiceNow/Salesforce assignment rules, queues, skill routing | Queues, candidate groups, `POST /cases/{id}/assign`, routing rules on definitions |
| G7 | SLA too thin | Salesforce milestones: pause on waiting-for-customer, business hours, warning vs breach | §7 SlaPolicy with pause/resume, calendars, thresholds, escalation |
| G8 | No bulk operations | Oracle EDQ: bulk update/delete by filter, async status, cancel | `POST /cases/bulk` async pattern + operations resource |
| G9 | No saved worklists/export | Oracle EDQ saved filters & reports | SavedFilter resource + `/results`; CSV/JSON export flag |
| G10 | Non-idempotent create | Distributed-client retry reality | `Idempotency-Key` header support |
| G11 | Untyped attachments | Pega attachment categories | Attachment categories per case type |
| G12 | No start authorization on definitions | Flowable identity links on case definitions | `/case-definitions/{key}/identity-links` |
| G13 | Internal vs. customer communication conflated | ServiceNow/Salesforce separate notes from customer comms | `visibility` on comments; external channel integration left as extension point |

## 9. Prioritization

- **Build now (architectural, painful to retrofit):** G1 events/webhooks,
  G2 ETag concurrency, G3 availableActions, G10 idempotency.
- **Build with first case types:** G5 plan-item semantics, G4 form schemas,
  G7 SLA model, G12 definition identity links.
- **Layer on when back office scales:** G6 queues/routing, G8 bulk,
  G9 saved filters, G11, G13.

## 10. Open Questions

1. Expression language for entry/exit criteria and SLA selection — FEEL
   (aligns with DMN/Camunda) vs. JUEL (native to Camunda 7)?
2. Does the case store live in the engine's database (same transaction
   boundary, simpler consistency) or its own schema (cleaner upgrade path)?
   Recommendation: same DB, separate schema, one transaction manager.
3. Document storage backend per team (S3? existing DMS?) — the API only
   standardizes metadata + `contentUrl`.
4. Retention/GDPR: audit log and event log need per-tenant retention policies
   and a `POST /cases/{id}/anonymize` admin operation — flagged for a
   follow-up ADR.

---

## Appendix: Rationale per API Design Principle

One appendix section per principle in §4, in the same order.

### Appendix A: Event-Driven, Not Poll-Only (Principle 1)

Without events, every frontend must poll. A back-office worklist showing 50
cases that refreshes every 10 seconds generates 300 queries per minute per
open browser tab — almost all of them returning nothing new. Multiply by
employees and teams, and the engines spend most of their capacity answering
"has anything changed?" with "no".

The deeper problem is architectural. In the decentralized setup there is no
central case database, but the organization will eventually want one view
across teams ("all open complaints company-wide"). If engines are pull-only,
that overview service must poll every engine, page through result sets, and
diff snapshots to detect changes — fragile, slow, and O(engines × cases).
If every engine emits the same CloudEvents stream, the overview is a plain
consumer: it subscribes once and maintains its own index incrementally. The
engines never need to know it exists.

Why both push (webhooks) and pull (`GET /events`)? Webhooks give low
latency, but consumers crash, deploy, and lose events. The pull-based event
log with a cursor is the recovery path: after downtime, a consumer resumes
from its last cursor and replays what it missed. Push for speed, pull for
correctness.

Why CloudEvents specifically: it is a small, vendor-neutral CNCF envelope
(id, source, type, subject, time, data). Adopting it costs one thin wrapper
and buys interoperability with brokers and tooling (Kafka connectors,
Knative, Azure Event Grid all speak it), plus a stable contract for
federation. Delivery is at-least-once, so every event carries a unique `id`
and consumers deduplicate on it; undeliverable webhooks land in a visible
dead-letter queue instead of being silently dropped.

### Appendix B: ETags and Optimistic Concurrency (Principle 2)

An ETag is a version stamp for a resource. The server returns one with every
read (`ETag` response header); the client hands it back with every write
(`If-Match` request header). Its practical value in this system comes down to
one scenario:

#### B.1 Preventing lost updates

Two back-office employees open case `eng-a:4711` at the same time. Both see
version `"v17"`.

1. Employee A changes the priority and saves. The server accepts the write;
   the case is now `"v18"`.
2. Employee B, still looking at the stale screen, edits the description and
   saves with `If-Match: "v17"`.

Without ETags, B's save silently overwrites A's change — A's work vanishes
and nobody notices until something goes wrong weeks later. With ETags, the
server detects the version mismatch and rejects B's request with
`412 Precondition Failed`. The frontend catches the 412, reloads the case,
shows B what changed, and lets them redo their edit on the current state.

```
A: GET /cases/eng-a:4711            → 200, ETag: "v17"
B: GET /cases/eng-a:4711            → 200, ETag: "v17"
A: PATCH … If-Match: "v17"          → 200, ETag: "v18"
B: PATCH … If-Match: "v17"          → 412 Precondition Failed
B: GET /cases/eng-a:4711            → 200, ETag: "v18"   (reload, re-apply)
```

#### B.2 Protecting actions, not just field edits

The same mechanism guards lifecycle transitions. If two people click "close"
or "claim" almost simultaneously, or one closes a case while another
reassigns it, the second request fails cleanly instead of executing against a
state that no longer exists. This is why the spec requires `If-Match` on
transitions such as close, cancel, and claim: the ETag makes the request mean
"close the case *as I last saw it*", not "close it whatever it looks like
now".

#### B.3 Why optimistic rather than pessimistic locking

Optimistic concurrency locks nothing up front — conflicts are assumed rare
and cost is only paid when one actually occurs. The alternative, pessimistic
locking ("case is locked by user X"), is what older case management tools
did, and it fails in practice: locks are orphaned when someone closes their
laptop, administrators must break locks manually, and parallel work on one
case is impossible.

#### B.4 Secondary use: caching

A client may ask "return the case only if it changed since `"v17"`"
(`If-None-Match`), and the server answers `304 Not Modified` with an empty
body. This saves bandwidth for polling frontends, though it matters less
once webhook/event delivery (§6) is in place.

#### B.5 Implementation note

A version counter on the case row, incremented on every write and compared
on every conditional request, is sufficient — the same optimistic-locking
column JPA/Hibernate provides with `@Version`, exposed over HTTP. Include the
version in every write path (case, task, plan-item mutations) from day one;
retrofitting it later breaks all existing clients.

### Appendix C: Action Discoverability / availableActions (Principle 3)

Whether a case can be closed right now depends on its lifecycle state,
whether required plan items are finished, and the caller's role on the case.
If the API does not answer this, every frontend must re-implement the rules:
"show Close if state is ACTIVE and no required item is unfinished and the
user is owner or handler…". That logic will be duplicated in each team's
frontend, drift out of sync with the server, and break silently whenever a
rule changes — the user clicks a button that then fails with a 409.

With server-computed `availableActions[]`, the frontend renders exactly the
buttons the server lists and never guesses. This has three concrete payoffs:

1. **One generic frontend can serve every case type.** Different teams
   define different case types with different rules, yet the UI code is the
   same: render actions, invoke `href`, render the returned form if
   `formKey` is set. This is the core idea behind Pega's model-driven DX
   API, and the reason it can drive arbitrary UIs without per-case-type
   code.
2. **Permissions are enforced and displayed in one place.** A reviewer sees
   different actions than an owner — computed server-side from the
   participant roles, so UI and enforcement can't diverge.
3. **Rules change without frontend releases.** Tightening "who may cancel"
   is a service-side change; every frontend picks it up on the next read.

The cost is a modest response-size increase and the discipline that the
action list must be *the* authority (the server must still validate on
invocation — the list is a projection, not the enforcement point).

### Appendix D: Form Metadata as Part of the Contract (Principle 4)

Human tasks collect data, and each team's case types collect different data.
If form layouts are hardcoded in frontends, adding a field to "Assess
complaint" requires a frontend release, and a shared/generic frontend is
impossible — it cannot know what fields a foreign team's task needs.

Publishing a JSON Schema per form key
(`GET /case-definitions/{key}/forms/{formKey}`) inverts this: the definition
owns the form contract, the frontend renders it dynamically (JSON Schema form
renderers exist for React, Angular, and Vue), and the service validates task
completion payloads against the same schema — one source of truth for
rendering *and* validation, so client and server can never disagree about
what a valid submission is.

JSON Schema over a proprietary form DSL because it is a standard with
existing renderers and validators on every stack, and it degrades
gracefully: a team that wants a fully custom UI simply ignores the schema
and posts conforming payloads. Layout hints (ordering, grouping, widgets)
can ride along via `ui:` annotations without breaking validators.

### Appendix E: Idempotent Creation (Principle 5)

Networks fail after the server acts but before the client hears about it. A
frontend posts `POST /cases`, the case is created, the response times out,
the client (or the user hammering the button) retries — and now there are
two cases for one complaint. Back office finds duplicates weeks later; in
regulated processes, duplicated cases are an audit finding.

The `Idempotency-Key` header fixes the retry, not the client: the client
generates a unique key per logical creation attempt (a UUID minted when the
form is opened), sends it with every retry, and the server stores
`key → response` for a retention window. A retried request with a known key
returns the original `201` and the original case — no duplicate. The same
key with a *different* payload returns `409`, catching client bugs.

This matters more, not less, in a decentralized setup: automated clients
(other teams' services creating cases via the API) retry aggressively by
design, and message-driven integrations deliver at-least-once. Idempotent
creation is what makes "just retry on error" a safe client strategy, which
in turn keeps every integration simple.

Scope note: mutations guarded by `If-Match` (Appendix B) are already safe to
retry — a replayed transition fails on the stale ETag. The idempotency key
is needed precisely where no prior version exists: creation, and bulk
operation submission.

### Appendix F: Tenant- and Engine-Aware Resources (Principle 6)

Every DevOps team runs its own engine, so nothing prevents two engines from
generating the same local ID or business key. The moment anything spans
engines — a federated overview, cross-engine case links (`duplicateOf` a
case in another team), events flowing into one broker — colliding IDs
corrupt data silently: an event for team A's case 42 updates team B's
case 42 in the index.

Making IDs globally unique from day one (`{engineId}:{uuid}`) and stamping
every resource and event with `engineId` and `tenantId` costs nothing now
and removes the single hardest retrofit later. ID formats are the most
frozen part of any API: they leak into URLs, foreign systems, printed
documents, and users' bookmarks. Changing them after go-live is a breaking
migration for every consumer at once.

`tenantId` serves a different axis than `engineId`: an engine is an
operational boundary (who runs it), a tenant is an authorization and data-
segregation boundary (whose data it is). One team may host several tenants
(e.g. brands or subsidiaries) on one engine; queries, SLA policies, and
retention rules bind to tenant, not engine. Conflating the two would force
one engine per tenant forever.

### Appendix G: Async Bulk Operations (Principle 7)

An employee leaves and 200 of their cases must be reassigned. Done
synchronously, that request runs for minutes, hits the gateway timeout, and
aborts halfway — leaving 90 cases reassigned and 110 not, with no record of
which. The client retries, some cases are processed twice, and nobody can
say what state the system is in.

The async pattern makes the work itself a resource: `POST /cases/bulk`
validates the request, returns `202 Accepted` with an operation ID
immediately, and processes items in the background. The client polls
`GET /operations/{id}` (or subscribes to the operation's events) and gets
progress, per-item errors, and a terminal status
(`COMPLETED_WITH_ERRORS` rather than all-or-nothing, because case 137 being
in a non-reassignable state should not roll back 199 successful
reassignments). `POST /operations/{id}/cancel` stops a mistaken bulk action
mid-flight — irrelevant for 200 items, essential for 20,000.

This is the same model Oracle's case management API uses for bulk
update/delete (submit, check status, cancel), and it composes with the other
principles: bulk submission accepts an `Idempotency-Key` (Appendix E), each
item mutation emits its normal event (Appendix A), and per-item failures
surface in the operation resource instead of vanishing.

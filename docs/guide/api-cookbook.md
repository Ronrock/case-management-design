# API cookbook

All paths sit under `/case-api/v2`. Every call needs authentication; most mutations need
`If-Match`. The authoritative contract is [`openapi-specs.md`](../../openapi-specs.md).

## The golden rule

> **Read `availableActions[]`. Don't hardcode.**

Every case, plan item and task response carries `availableActions[]`, each entry with `action`,
`href`, `method` and `formKey`. A client that follows those needs no knowledge of your case types at
all. A client that hardcodes `POST /close` breaks the first time somebody edits the definition.

The same rule that produces that list also enforces it, so an offered action is a permitted one.

---

## Cases

### Create

```http
POST /case-api/v2/cases
Idempotency-Key: 7f3a…            (optional)
```
```json
{ "caseDefinitionKey": "access-request", "title": "…",
  "businessKey": "REQ-4471", "priority": "NORMAL",
  "variables": { "amount": 250 } }
```
→ `201`, `ETag: "0"`, plus `availableActions[]`.

### Read

```http
GET /case-api/v2/cases/{id}               # the case + current actions
GET /case-api/v2/cases/{id}/plan-items    # every item and its state
```

### List

```http
GET /case-api/v2/cases?state=OPEN&state=SUSPENDED&page=0&pageSize=50
```
```json
{ "items": [ … ], "page": 0, "pageSize": 50 }
```

`state` is repeatable. `pageSize` is capped at 200. There's no total count yet, so page until you
get a short page.

### Patch

```http
PATCH /case-api/v2/cases/{id}
Content-Type: application/merge-patch+json
If-Match: "7"
```
```json
{ "title": "New title", "variables": { "amount": 900 } }
```

> **Null does not clear.** The media type is declared but null-clearing isn't implemented — an
> explicit `null` is treated as absent
> ([issue #9](https://github.com/Ronrock/case-management-design/issues/9)).

### Close and cancel

```http
POST /case-api/v2/cases/{id}/close     If-Match: "…"   { "outcome": "granted" }
POST /case-api/v2/cases/{id}/cancel    If-Match: "…"   { "reason": "withdrawn" }
```

Close is refused while a `required` item is open. Cancel terminates everything still open.

---

## Tasks

```http
GET  /case-api/v2/tasks                      # your worklist, tenant-scoped
GET  /case-api/v2/cases/{id}/tasks           # tasks on one case
POST /case-api/v2/tasks/{taskId}/claim       If-Match: "3"
POST /case-api/v2/tasks/{taskId}/complete    If-Match: "4"
     { "variables": { "decision": "grant" } }
```

The worklist matches your identity groups against each task's `candidateGroups`, plus anything
assigned to you. Tasks not yet synced to the engine are hidden — you can't claim what the engine
doesn't have.

Completing a task triggers a full re-evaluation, so the next read may show entirely new work.

---

## Plan items

```http
POST /case-api/v2/cases/{id}/plan-items/{itemId}/enable      # AVAILABLE → ENABLED
POST /case-api/v2/cases/{id}/plan-items/{itemId}/start       # ENABLED   → ACTIVE
POST /case-api/v2/cases/{id}/plan-items/{itemId}/complete
POST /case-api/v2/cases/{id}/plan-items/{itemId}/terminate   { "reason": "…" }
```

- `start` is refused unless the parent stage is `ACTIVE`.
- `complete` on a stage is refused while required children are open — the `409` names them.
- `terminate` on a stage cascades to the whole subtree beneath it.

---

## Collaboration

```http
GET  /case-api/v2/cases/{id}/comments
POST /case-api/v2/cases/{id}/comments      { "text": "…", "visibility": "internal" }

GET  /case-api/v2/cases/{id}/milestones
POST /case-api/v2/cases/{id}/milestones/{mid}/achieve      If-Match

GET  /case-api/v2/cases/{id}/processes
POST /case-api/v2/cases/{id}/processes                     If-Match
     { "processDefinitionKey": "decision-letter", "planItemId": "…" }
```

`planItemId` is optional and must belong to the case in the URL.

---

## Concurrency: ETags

Every mutable row carries a version. Reads return it as `ETag`; writes require it as `If-Match`.

| You send | You get |
|---|---|
| No `If-Match` | `428 if-match-required` |
| Stale version | `409 version-conflict` |
| `If-Match: *` | Proceeds if the resource exists, else `412` |
| Garbage | `400 invalid-request` |

On `409`, re-read, re-decide, retry. The service never silently retries for you — it can't know
whether your decision still holds.

> **Why the ETag is trustworthy:** after a successful update the returned version is computed
> locally as `version + 1`, never re-read. A re-read is a second statement a concurrent writer can
> commit in front of — you'd be handed *their* version and would send it as your next `If-Match`.

---

## Idempotency

`POST /cases` accepts `Idempotency-Key`.

- Same key + same body → replays the original response, status included.
- Same key + different body → `409 idempotency-conflict`.
- Keys are scoped per caller, so two users choosing `"key-1"` don't collide.

An unfinished key returns `409 idempotency-conflict` to duplicates instead of letting a retry
execute the work again. Client-side validation failures release their own claim; unknown server
failures remain claimed until operational recovery or retention cleanup.

---

## Identity, roles and tenancy

Three separate things:

| Concept | Comes from | Controls |
|---|---|---|
| **Tenant** | A `tenant:<id>` authority on the principal | Which data you see at all |
| **Case role** | `CM_PARTICIPANT` rows per case | What you may do to *this* case |
| **Identity group** | Authorities on the principal | Which tasks you may claim |

> **Tenant comes from the principal, always** — never from a body or query parameter. If a body
> names a tenant it must equal yours, or `403`. This is load-bearing: an earlier version read it
> from the body, which made webhook subscription a cross-tenant exfiltration primitive.

Rules:

- **Case mutations** require a mutating role: `owner` or `handler`.
- **Task actions** require a mutating role *or* membership of one of the task's `candidateGroups`.
- **Administration** (deploy definitions, subscribe webhooks, read dead letters) requires `admin`.
- `watcher` can read and nothing else.

### 404 vs 403

An id you supplied that belongs to another tenant answers `404`, identically to one that doesn't
exist — a `403` there would confirm the resource is real. A `403` means you *named* a tenant that
isn't yours, or you have none.

---

## Error reference

All errors are RFC 9457 `application/problem+json` with a stable `code`. **Branch on `code`, not on
the message.**

| Code | Status | Meaning & what to do |
|---|---|---|
| `not-found` | 404 | Doesn't exist — or belongs to another tenant. Deliberately indistinguishable. |
| `version-conflict` | 409 | Somebody wrote first. Re-read, re-decide, retry. |
| `illegal-transition` | 409 | Not legal now. The body lists what *is*. |
| `action-not-available` | 409 | You lack the role or group. |
| `if-match-required` | 428 | Add the `If-Match` header from your last read. |
| `precondition-failed` | 412 | `If-Match: *` against something that doesn't exist. |
| `form-invalid` | 422 | Payload failed the schema. `violations[]` has JSON Pointers. |
| `case-definition-invalid` | 400 | Authoring error, e.g. a form key with no schema. |
| `invalid-request` | 400 | Malformed input; the detail names the legal values. |
| `idempotency-conflict` | 409 | Key reused with a different body, or claimed concurrently. |
| `forbidden` | 403 | You named a tenant that isn't yours, or have none. |
| `model-error` | 500 | The definition is structurally broken. Fix the model. |

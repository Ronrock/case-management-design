# API cookbook

All paths are below `/case-api/v2`. Calls require authentication. Tenant scope comes from the
authenticated principal, not from a request body. The authoritative HTTP reference remains
[`openapi-specs.md`](../../openapi-specs.md).

## Client rules

1. Render only server-returned `availableActions[]`.
2. Send the last ETag in `If-Match` when changing a mutable resource.
3. On a version conflict, re-read and let the user reconsider the action.
4. Use `Idempotency-Key` on case creation when a request may be retried.
5. Branch on the stable problem `code`, never localized detail text.
6. Treat `projectionStatus: STALE` as a possibly outdated view of Operaton.

An available action contains everything a generic client needs:

```json
{
  "action": "claim",
  "name": "Claim",
  "href": "/case-api/v2/tasks/01.../claim",
  "method": "POST",
  "formKey": null
}
```

The server reauthorizes execution. Rendering an action is not an authorization grant.

## Discover case definitions

```http
GET /case-api/v2/case-definitions
GET /case-api/v2/case-definitions/{key}
GET /case-api/v2/case-definitions/{key}/versions/{version}
```

BPMN definition responses include `orchestrationMode`, exact release IDs/hashes, and
`deploymentStatus`. `ACTIVE` is runnable; `DEPLOYING` is waiting for remote deployment;
`FAILED` requires administrative investigation.

## Publish a BPMN case type

Combined publication:

```http
POST /case-api/v2/case-definitions
Content-Type: application/zip

<contract.json + presentation.json + BPMN/DMN files>
```

Independent publication and binding:

```http
POST /case-api/v2/case-definitions/{key}/orchestration-releases
POST /case-api/v2/case-definitions/{key}/contract-releases
POST /case-api/v2/case-definitions/{key}/presentation-releases
POST /case-api/v2/case-definitions/{key}/versions
```

The final request binds exact release IDs:

```json
{
  "orchestrationReleaseId": "...",
  "contractReleaseId": "...",
  "presentationReleaseId": "..."
}
```

Publication and binding require the `admin` group. Legacy plan-model JSON continues to use the
same top-level POST with `Content-Type: application/json`.

## Create and read cases

```http
POST /case-api/v2/cases
Idempotency-Key: create-CMP-1042
Content-Type: application/json
```

```json
{
  "caseDefinitionKey": "complaint-bpmn",
  "businessKey": "CMP-1042",
  "title": "Delivery complaint",
  "priority": "HIGH",
  "variables": {"channel": "web"}
}
```

A BPMN create starts the pinned root process and returns `rootProcessInstanceId`. An explicit
`close` action is not advertised. Root completion closes the case.

```http
GET /case-api/v2/cases/{caseId}
GET /case-api/v2/cases?state=ACTIVE&page=0&pageSize=50
```

Case values are field-filtered. A missing property may mean the caller is not allowed to see it;
clients must not interpret absence as an empty business value.

## Update and cancel cases

```http
PATCH /case-api/v2/cases/{caseId}
Content-Type: application/merge-patch+json
If-Match: "7"

{"title":"Corrected title","variables":{"amount":900}}
```

```http
POST /case-api/v2/cases/{caseId}/cancel
If-Match: "8"
Content-Type: application/json

{"reason":"withdrawn"}
```

Cancellation cancels a BPMN root process and terminalizes remaining projected/ad-hoc work. The
explicit `/close` endpoint is for legacy plan-model cases when their required work is finished.

## Work with tasks

```http
GET /case-api/v2/tasks
GET /case-api/v2/cases/{caseId}/tasks
POST /case-api/v2/tasks/{taskId}/claim
POST /case-api/v2/tasks/{taskId}/complete
```

Claim and complete require `If-Match`. Complete submits contract-validated variables:

```http
POST /case-api/v2/tasks/{taskId}/complete
If-Match: "4"
Content-Type: application/json

{"variables":{"decision":"approve","rationale":"Checks passed"}}
```

`422 form-invalid` means the payload did not satisfy the pinned JSON Schema. Read
`violations[].pointer` and `violations[].message`.

The worklist contains tasks assigned to the caller or matching their candidate groups. A remote
task may expose `engineSync: PENDING` and no actionable commands until engine synchronization.

## Read BPMN projections

```http
GET /case-api/v2/cases/{caseId}/plan-items
GET /case-api/v2/cases/{caseId}/tasks
GET /case-api/v2/cases/{caseId}/milestones
GET /case-api/v2/cases/{caseId}/processes
```

Stages, milestones, repeated activity occurrences, assignments, and completion are projections of
engine observations. Inspect `projectionStatus`, `lastEngineUpdateAt`, and `lastProjectedAt` before
making freshness-sensitive decisions.

Plan-item mutation endpoints still serve legacy and discretionary work:

```http
POST /case-api/v2/cases/{caseId}/plan-items/{itemId}/enable
POST /case-api/v2/cases/{caseId}/plan-items/{itemId}/start
POST /case-api/v2/cases/{caseId}/plan-items/{itemId}/complete
POST /case-api/v2/cases/{caseId}/plan-items/{itemId}/terminate
```

Do not manually advance a BPMN-derived plan item unless its returned actions explicitly permit it.

## Execute an ad-hoc action

Contract-declared discretionary work uses:

```http
POST /case-api/v2/cases/{caseId}/ad-hoc-actions/{actionId}
If-Match: "5"
Content-Type: application/json

{"aspect":"shipping","finding":"Carrier scan missing"}
```

The response identifies the created task, plan item, or linked process. Embedded mode normally
returns `201` with `CURRENT`; remote engine work can return `202` with `PENDING`.

Ad-hoc work stays outside BPMN token flow. Root completion terminalizes any still open.

## Collaboration and SLAs

```http
GET  /case-api/v2/cases/{caseId}/comments
POST /case-api/v2/cases/{caseId}/comments
GET  /case-api/v2/cases/{caseId}/documents
POST /case-api/v2/cases/{caseId}/documents
DELETE /case-api/v2/cases/{caseId}/documents/{documentId}
GET  /case-api/v2/cases/{caseId}/slas
POST /case-api/v2/cases/{caseId}/slas/{slaId}/pause
POST /case-api/v2/cases/{caseId}/slas/{slaId}/resume
```

Field projection applies here too: restricted values must not leak through documents, comments,
search, or error details.

## Search

```http
GET  /case-api/v2/search/cases?q=delivery
POST /case-api/v2/search/query
GET  /case-api/v2/search/providers
GET  /case-api/v2/search/suggestions?q=del
GET  /case-api/v2/search/facets
```

Manifest search profiles are permission-aware. The server decides which profiles/providers the
caller may use and filters result fields. The maximum result window is 10,000; its problem detail
is locale-stable, but clients should still branch on `code`.

## Events and webhooks

```http
GET /case-api/v2/events?after=1042&limit=100
GET /case-api/v2/cases/{caseId}/events
POST /case-api/v2/webhooks
GET /case-api/v2/webhooks/{webhookId}/dead-letters
POST /case-api/v2/webhooks/{webhookId}/dead-letters/redeliver
```

Webhook secrets are returned once. Deliveries are HMAC-SHA256 signed and retried before entering
the dead-letter queue.

## Remote orchestration operations

Admin-only endpoints:

```http
GET  /case-api/v2/orchestration/remote-status
GET  /case-api/v2/orchestration/commands/dead-letters?limit=50
POST /case-api/v2/orchestration/commands/dead-letters/{commandId}/retry
```

Dead-letter responses omit command payloads so business data is not exposed operationally.

## ETags and conflicts

| Request | Result |
|---|---|
| Missing required `If-Match` | `428 if-match-required` |
| Stale exact version | `409 version-conflict` |
| `If-Match: *` and resource exists | Proceed using current version |
| Invalid header | `400 invalid-request` |

The wildcard is useful for scripts and administration. User-facing clients should send the exact
ETag they displayed to the user.

## Error handling

Errors use `application/problem+json` and a stable `code`:

| Code | Meaning |
|---|---|
| `not-found` | Missing or deliberately hidden cross-tenant resource |
| `forbidden` | Caller lacks tenant/administrative permission |
| `version-conflict` | Resource changed; re-read before retrying |
| `illegal-transition` | Action is not legal in current state |
| `action-not-available` | Policy or permission denies the action |
| `if-match-required` | Add the ETag from the last read |
| `form-invalid` | Submitted variables violate the pinned form |
| `case-definition-invalid` | Artifact or cross-artifact validation failed |
| `idempotency-conflict` | Key is already claimed for different input |

Messages are for humans and may evolve. Code against `status`, `code`, and structured fields.

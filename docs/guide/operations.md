# Operations

Deploying, integrating and debugging.

## Engine modes

One interface, `EngineGateway`, with two implementations that pass the same contract test suite.
Switch with one property.

| | `embedded` | `remote` |
|---|---|---|
| Engine location | In your JVM | Separate Operaton over HTTP |
| Transaction | Engine call joins your transaction | Cannot — uses a command outbox |
| Task visible | Immediately, `engineSync: SYNCED` | After the dispatcher drains; `PENDING` first |
| Failure mode | Rolls back with everything else | Retried, then dead-lettered |

### Why remote mode needs an outbox

A remote HTTP call cannot join a database transaction. If you called the engine directly and your
transaction then rolled back, the engine would hold a task for a case that doesn't exist.

So in remote mode the intent is written to `CM_ENGINE_COMMAND` **in the same transaction**, and a
dispatcher drains it afterwards, writing the real engine id back.

The consequence you must design for: **a task is not immediately actionable in remote mode.** It
appears with `engineSync: PENDING` and no available actions, and the worklist hides it until it
syncs.

```yaml
casemgmt:
  engine:
    mode: remote
    remote:
      base-url: http://engine:8080/engine-rest
      username: cm-service
      password: ${ENGINE_PASSWORD}
      connect-timeout-ms: 5000
      read-timeout-ms: 10000
```

> **Always set both timeouts.** An engine that is up but hung produces no exception at all. Without
> a read timeout the calling thread blocks forever and the outbox can never make its
> retry-or-dead-letter decision.

---

## Events and webhooks

### The transactional outbox

Every mutation writes the domain row, an event, an audit record and any webhook deliveries **in one
local transaction**. Either all of it commits or none does. There's no window in which a case
changed but its event didn't.

### Event types

CloudEvents, `source` from your `engine-id`, type prefixed by your `type-prefix`.

| Group | Types |
|---|---|
| Case | `case.created` `case.updated` `case.closed` `case.cancelled` |
| Work | `case.planitem.transitioned` `case.milestone.achieved` `case.process.started` |
| Tasks | `case.task.created` `case.task.claimed` `case.task.completed` |
| Collaboration | `case.comment.added` |
| SLA | `case.sla.started` `case.sla.paused` `case.sla.resumed` `case.sla.warning` `case.sla.breached` `case.sla.escalated` |

### Push — webhooks

```http
POST /case-api/v2/webhooks
{ "url": "https://you.example/hooks/cases",
  "eventTypes": ["case.closed", "case.sla.breached"] }
```
→ `201` with the plaintext `secret`, **shown exactly once**. A SHA-256 hash is stored for
verification and encrypted signing material is stored for delivery after restart.

Deliveries are signed HMAC-SHA256 in `X-Case-Signature: sha256=<hex>`. Verify before trusting the
body. Failures retry on a backoff ladder, then land in a dead-letter queue:

```http
GET /case-api/v2/webhooks/{id}/dead-letters?page=0&pageSize=25
POST /case-api/v2/webhooks/{id}/dead-letters/redeliver
```

Dead-letter listing is pageable and capped at 200 rows per page. Redelivery resets all DEAD rows
for the subscription to PENDING with a fresh retry budget.

### Pull — the cursor feed

```http
GET /case-api/v2/events?after=1042&limit=100
```

The pull feed is backed by serialized event sequence allocation. Consumers may still receive
at-least-once duplicates across webhook and pull recovery, but a committed lower event sequence
cannot appear after a consumer has advanced past a higher one.

---

## SLA clocks

Attach a policy to a case type and clocks start with the case. A sweeper emits a warning when the
threshold passes, a breach when the deadline does, and an escalation event when a target includes
the `ESCALATE` breach action.

### Business time, not wall time

Deadlines are computed through a **business calendar** — a four-hour target started at 16:00 on a
Friday lands Monday morning, not Friday evening.

The calendar is deliberately strict and rejects at parse time:

- Overnight or inverted intervals (`from` ≥ `to`).
- Overlapping intervals on the same day. Abutting ones (09:00–13:00, 13:00–17:00) are fine.
- Negative durations.

> **Why so strict:** overlapping intervals used to double-count the overlap, which made deadlines
> land *earlier* than the policy said. For a breach calculation that's the dangerous direction —
> you'd report breaches that hadn't happened.

### Pausing

```http
POST /case-api/v2/cases/{id}/slas/{slaId}/pause    { "reason": "waiting-on-customer" }
POST /case-api/v2/cases/{id}/slas/{slaId}/resume
```

Resume re-derives the deadline through the calendar rather than adding elapsed wall-clock time, so
a pause across a weekend costs the case only its working hours.

Targets may configure `pauseReasons` as a whitelist of accepted pause reasons. The legacy database
column is still named `PAUSED_STATES_JSON_`, but the runtime treats it as reasons, not lifecycle
states.

---

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `casemgmt.enabled` | `true` | Master switch. `false` leaves a plain Operaton app untouched. |
| `casemgmt.engine-id` | — | Stamped as the CloudEvents `source`. |
| `casemgmt.engine.mode` | `embedded` | `embedded` or `remote`. |
| `casemgmt.engine.remote.base-url` | — | `engine-rest` root. |
| `casemgmt.engine.remote.auth-mode` | `auto` | `auto`, `none`, `basic`, or `bearer`. |
| `casemgmt.engine.remote.username` / `.password` | — | Basic auth for the engine when `auth-mode=basic`. |
| `casemgmt.engine.remote.bearer-token` | — | Static bearer token for controlled deployments; prefer a `RemoteEngineBearerTokenProvider` for OIDC/client credentials. |
| `casemgmt.engine.remote.connect-timeout-ms` | `5000` | TCP connect bound. |
| `casemgmt.engine.remote.read-timeout-ms` | `10000` | Bounds a hung engine. |
| `casemgmt.events.type-prefix` | **none** | Startup fails if unset and webhooks are enabled. |
| `casemgmt.webhooks.secret-encryption-key` | **none** | Base64 AES key required by the default database-backed webhook secret store. A custom `WebhookSecretStore` does not require it. |
| `casemgmt.webhooks.secret-key-id` | `default` | Key id stored next to encrypted webhook secrets. |
| `casemgmt.security.mode` | `basic` | Local PoC mode. Use `oidc` for JWT/OIDC bearer-token mode. |
| `casemgmt.security.engine-integration-principal` | `admin` | Basic-mode integration principal allowed to write to Operaton REST. Ignored in OIDC mode. |
| `casemgmt.security.oidc.engine-permissions-claim` | `engine_permissions` | Dedicated OIDC claim for engine integration permissions; `api` maps to `engine:api`. |
| `casemgmt.schedulers.enabled` | `true` | Turns off all background workers. |
| `casemgmt.schedulers.webhook-interval-ms` | `5000` | Webhook drain. |
| `casemgmt.schedulers.engine-command-interval-ms` | `5000` | Remote-mode outbox drain. |
| `casemgmt.schedulers.sla-sweep-interval-ms` | `60000` | SLA warning/breach sweep. |
| `casemgmt.schedulers.idempotency-purge-interval-ms` | `3600000` | Retention purge. |

> **Multi-instance schedulers.** Webhook dispatch, engine-command dispatch and SLA sweeping all
> claim bounded batches before processing. This avoids duplicate scheduler work across application
> instances; leases still mean long-running claimed work should be monitored.

---

## Extending

Five beans are declared `@ConditionalOnMissingBean`. Declare your own and it wins.

| Bean | Substitute it to… |
|---|---|
| `CallerResolver` | Map a custom principal or Worker Permissions model. |
| `ActionPolicy` | Change the role model or the action vocabulary. |
| `CriterionEvaluator` | Swap the expression language. |
| `FormValidator` | Use a different schema dialect. |
| `EventPublisher` | Change the event envelope or add a sink. |

### Moving to OIDC

Set `casemgmt.security.mode=oidc`, configure Spring's
`spring.security.oauth2.resourceserver.jwt.*` issuer/JWKS settings, and map the configured OIDC
claims to the authorities the platform expects.

The default OIDC converter maps `tenant` to `tenant:<id>`, maps `groups` directly, and maps
`worker_permissions` directly by default. A bank deployment can still replace `CallerResolver` or
`ActionPolicy` when Worker Permissions require a different vocabulary.

> **Controllers are not overridable.** They arrive via `@Import`. To change an endpoint's behaviour,
> change the policy or the service behind it.

---

## Known limits

Documented gaps that change how you'd use this. The
[issue tracker](https://github.com/Ronrock/case-management-design/issues) has all of them;
[`FINDINGS.md`](../../FINDINGS.md) has the reasoning.

| Limit | What it means for you |
|---|---|
| Remote engine OIDC token source | Use `casemgmt.engine.remote.auth-mode=bearer` and provide a `RemoteEngineBearerTokenProvider` backed by the bank's OIDC/client-credential integration. The local PoC may still use Basic Auth for convenience. |
| Production UI completeness | The Lit package proves standalone and portal-adapter integration, but production form UX, accessibility, and bank design-system fit still require product work. |
| Engine Tasklist writes | Ordinary users are blocked from direct `engine-rest` writes. If Tasklist writes must remain available, add engine-side reconciliation back into the case state machine. |

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Startup fails naming `type-prefix` | Set `casemgmt.events.type-prefix`. It has no default on purpose. |
| Every request is `403` | The principal has no `tenant:<id>` authority, or has more than one. |
| A task never appears in the worklist | Remote mode and it hasn't synced (`engineSync: PENDING`), or your groups don't match its `candidateGroups`. |
| A criterion never fires | Check the `defKey` spelling. Unknown references are rejected when a definition is deployed. |
| Close is refused | A `required` item is still open. The `409` body names it. |
| Webhooks cannot be signed after startup | `casemgmt.webhooks.secret-encryption-key` is missing or changed since the subscription was created. |
| A child item never opens | Check its parent stage's state before its criteria — containment is enforced. |

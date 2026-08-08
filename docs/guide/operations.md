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
| SLA | `case.sla.started` `case.sla.paused` `case.sla.resumed` `case.sla.warning` `case.sla.breached` |

### Push — webhooks

```http
POST /case-api/v2/webhooks
{ "url": "https://you.example/hooks/cases",
  "eventTypes": ["case.closed", "case.sla.breached"] }
```
→ `201` with the plaintext `secret`, **shown exactly once**. Only a SHA-256 hash is stored.

Deliveries are signed HMAC-SHA256 in `X-Case-Signature: sha256=<hex>`. Verify before trusting the
body. Failures retry on a backoff ladder, then land in a dead-letter queue:

```http
GET /case-api/v2/webhooks/{id}/dead-letters
```

Redelivery is specified but not implemented
([issue #7](https://github.com/Ronrock/case-management-design/issues/7)).

### Pull — the cursor feed

```http
GET /case-api/v2/events?after=1042&limit=100
```

> **Two limits you must design around.**
>
> **The pull feed can skip events.** A transaction taking a lower sequence number but committing
> later falls below a cursor that already advanced past it. Don't treat it as a gap-free log
> ([issue #3](https://github.com/Ronrock/case-management-design/issues/3)).
>
> **Webhook secrets don't survive a restart.** They're held in memory, so every existing
> subscription must be re-created after the service restarts
> ([issue #4](https://github.com/Ronrock/case-management-design/issues/4)).

---

## SLA clocks

Attach a policy to a case type and clocks start with the case. A sweeper emits a warning when the
threshold passes and a breach when the deadline does.

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

---

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `casemgmt.enabled` | `true` | Master switch. `false` leaves a plain Operaton app untouched. |
| `casemgmt.engine-id` | — | Stamped as the CloudEvents `source`. |
| `casemgmt.engine.mode` | `embedded` | `embedded` or `remote`. |
| `casemgmt.engine.remote.base-url` | — | `engine-rest` root. |
| `casemgmt.engine.remote.username` / `.password` | — | Basic auth for the engine. |
| `casemgmt.engine.remote.connect-timeout-ms` | `5000` | TCP connect bound. |
| `casemgmt.engine.remote.read-timeout-ms` | `10000` | Bounds a hung engine. |
| `casemgmt.events.type-prefix` | **none** | Startup fails if unset and webhooks are enabled. |
| `casemgmt.schedulers.enabled` | `true` | Turns off all background workers. |
| `casemgmt.schedulers.webhook-interval-ms` | `5000` | Webhook drain. |
| `casemgmt.schedulers.engine-command-interval-ms` | `5000` | Remote-mode outbox drain. |
| `casemgmt.schedulers.sla-sweep-interval-ms` | `60000` | SLA warning/breach sweep. |
| `casemgmt.schedulers.idempotency-purge-interval-ms` | `3600000` | Retention purge. |

> **Single instance today.** Webhook and engine-command dispatch claim rows safely across instances.
> The SLA sweeper does not — run one instance, or accept duplicated sweep work
> ([issue #15](https://github.com/Ronrock/case-management-design/issues/15)).

---

## Extending

Five beans are declared `@ConditionalOnMissingBean`. Declare your own and it wins.

| Bean | Substitute it to… |
|---|---|
| `CallerResolver` | Map an OAuth2 principal instead of Operaton identity. |
| `ActionPolicy` | Change the role model or the action vocabulary. |
| `CriterionEvaluator` | Swap the expression language. |
| `FormValidator` | Use a different schema dialect. |
| `EventPublisher` | Change the event envelope or add a sink. |

### Moving to OAuth2

Replace the security configuration and `CallerResolver`; nothing else changes.

```java
@Bean
CallerResolver callerResolver(ParticipantRepository participants) {
    return new OAuth2CallerResolver(participants);   // yours wins
}
```

Your mapping must produce **exactly one** `tenant:<id>` authority — zero or several is rejected
rather than defaulted.

> **Controllers are not overridable.** They arrive via `@Import`. To change an endpoint's behaviour,
> change the policy or the service behind it.

---

## Known limits

Documented gaps that change how you'd use this. The
[issue tracker](https://github.com/Ronrock/case-management-design/issues) has all of them;
[`FINDINGS.md`](../../FINDINGS.md) has the reasoning.

| Limit | What it means for you |
|---|---|
| Webhook secrets don't survive restart | Re-create every subscription after a restart, or pushes silently dead-letter. |
| The pull feed can skip events | Don't rely on it as a gap-free log or sole recovery path. |
| `PROCESS_TASK` is inert | Entering the item won't start the BPMN process. Start it via `POST /cases/{id}/processes`. |
| Optional children of criteria-entered stages | A non-required child can be swept when its parent auto-completes. Mark it `required` to be safe. |
| Idempotency lease | Operations longer than 5 minutes can double-execute. |
| Merge-patch null | You cannot clear a field by sending `null`. |
| No page totals | Page until you get a short page. |
| Single-instance sweeper | Run one instance, or accept duplicated SLA sweeps. |

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Startup fails naming `type-prefix` | Set `casemgmt.events.type-prefix`. It has no default on purpose. |
| Every request is `403` | The principal has no `tenant:<id>` authority, or has more than one. |
| A task never appears in the worklist | Remote mode and it hasn't synced (`engineSync: PENDING`), or your groups don't match its `candidateGroups`. |
| A criterion never fires | Check the `defKey` spelling — an unknown reference fails silently. |
| Close is refused | A `required` item is still open. The `409` body names it. |
| Webhooks stopped after a deploy | The secret map is in memory. Re-create the subscriptions. |
| A child item never opens | Check its parent stage's state before its criteria — containment is enforced. |

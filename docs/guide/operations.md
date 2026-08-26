# Operations

This chapter covers runtime configuration, engine modes, consistency, monitoring, and recovery.
Use [Quick start](quickstart.md) first if you have not run the PoC.

## Build and CI baseline

The supported baseline is Java 21, Operaton 2.1.3, Spring Boot 4.0.7, and Oracle Free 23/23ai.

Local compile/package:

```bash
./mvnw -B -DskipTests package
```

Authoritative reactor gate:

```bash
./mvnw -B clean install
```

Oracle integration tests use Testcontainers and therefore require a working Docker daemon. The
checked-in CI workflow runs the full reactor on Temurin 21 and separately runs Node 22
`npm ci`, `npm test`, and `npm run build` for the Lit package.

## Embedded mode

Embedded mode runs Operaton in the application JVM:

```yaml
casemgmt:
  engine:
    mode: embedded

operaton:
  bpm:
    eventing:
      execution: true
      task: true
      history: true
```

The library consumes Operaton's built-in Spring event bridge. No custom `ProcessEnginePlugin` is
required. Task, execution, and history events are translated into vendor-neutral observations;
core imports no Operaton engine types.

Because engine and library share the transaction, engine state, projections, audit, and domain
events can roll back together. Definition deployment is immediately `ACTIVE` when it succeeds.

Use embedded mode for the PoC, simple deployments, and environments where one transaction and
immediate projection are more important than engine isolation.

## Remote mode

Remote mode talks to an existing stock Operaton REST API:

```yaml
casemgmt:
  engine:
    mode: remote
    remote:
      base-url: https://operaton.example/engine-rest
      auth-mode: bearer
      bearer-token: ${OPERATON_TOKEN}
      connect-timeout-ms: 5000
      read-timeout-ms: 10000
  schedulers:
    engine-command-interval-ms: 5000
    engine-poll-interval-ms: 5000
    engine-reconcile-interval-ms: 300000
```

Prefer a `RemoteEngineBearerTokenProvider` backed by the deployment's OIDC/client-credential
integration instead of a static token. Basic auth is suitable only for controlled local setups.

Always set finite connect and read timeouts. Without them, one hung engine call can block the
dispatcher and every command behind it.

### Command outbox

A remote HTTP call cannot join the local Oracle transaction. The service therefore writes command
intent to `CM_ENGINE_COMMAND` in the same transaction as the case change. A scheduler delivers the
command later and retries with stable command identifiers.

Commands include deployment, root start, task operations, message correlation, cancellation, and
other engine mutations implemented by the gateway. Exhausted commands become dead letters rather
than remaining silently pending.

### Remote observation and eventual consistency

Remote mode uses no custom Operaton plugin and no Kafka dependency. It observes stock runtime and
history REST resources using:

- A durable watermark in `CM_ENGINE_POLL_CHECKPOINT`.
- A two-minute overlapping history window.
- Stable engine IDs and idempotent projection upserts.
- A periodic authoritative reconciliation of every active BPMN root process.

Overlap intentionally produces duplicates; deduplication makes them safe. It also catches records
that become visible slightly after their engine timestamp.

This is eventual consistency. A local projection can temporarily lag or receive observations out
of order. Remote timelines describe reconciled observations, not a lossless append-only stream of
every internal engine event.

Require at least Operaton `AUDIT` history level. Lower history levels do not provide enough data to
reconstruct task/activity completion reliably.

## Projection health

Derived resources expose:

- `projectionStatus`: `PENDING`, `CURRENT`, `STALE`, or `FAILED`.
- `lastEngineUpdateAt`.
- `lastProjectedAt`.
- `engineSync` on tasks for compatibility.

The remote poller marks active BPMN projections stale after a polling failure. Applications should
show a non-blocking freshness warning and avoid presenting stale data as confirmed engine state.

Admin health endpoint:

```http
GET /case-api/v2/orchestration/remote-status
```

It reports the watermark, last success, error, seconds since success, and active BPMN case count.
Alert on repeated failures or an age greater than the operational freshness objective.

## Dead-letter recovery

```http
GET /case-api/v2/orchestration/commands/dead-letters?limit=50
POST /case-api/v2/orchestration/commands/dead-letters/{commandId}/retry
```

Both require administration permission. The list intentionally omits payloads. Before retrying:

1. Confirm Operaton is healthy and reachable.
2. Confirm credentials and history level.
3. Read the command type and last error.
4. Determine whether the target effect already happened in Operaton.
5. Retry only after the underlying cause is resolved.

Stable identifiers and duplicate filtering make retries safer, but operators should still inspect
partially applied multi-step effects.

## Required application configuration

```yaml
spring:
  datasource:
    url: jdbc:oracle:thin:@database:1521/FREEPDB1
    username: cm
    password: ${CASEMGMT_DB_PASSWORD}
  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.xml

casemgmt:
  enabled: true
  engine-id: production-eu-1
  events:
    type-prefix: com.example.cases
  webhooks:
    secret-encryption-key: ${CASEMGMT_WEBHOOK_SECRET_ENCRYPTION_KEY}
```

`type-prefix` and the webhook encryption key have no safe production placeholder. The encryption
key must be Base64-encoded AES material, stored in a secret manager, and stable across restarts.

## Configuration reference

| Property | Default | Meaning |
|---|---|---|
| `casemgmt.enabled` | `true` | Master switch |
| `casemgmt.engine-id` | none | Case ID/Event source namespace |
| `casemgmt.engine.mode` | `embedded` | `embedded` or `remote` |
| `casemgmt.engine.remote.base-url` | none | Operaton `engine-rest` root |
| `casemgmt.engine.remote.auth-mode` | `auto` | `auto`, `none`, `basic`, or `bearer` |
| `casemgmt.engine.remote.connect-timeout-ms` | `5000` | TCP connection bound |
| `casemgmt.engine.remote.read-timeout-ms` | `10000` | Response wait bound |
| `casemgmt.events.type-prefix` | none | CloudEvents type namespace |
| `casemgmt.webhooks.secret-encryption-key` | none | Base64 AES key for signing-secret storage |
| `casemgmt.schedulers.engine-command-interval-ms` | `5000` | Remote command dispatch interval |
| `casemgmt.schedulers.engine-poll-interval-ms` | `5000` | Incremental observation interval |
| `casemgmt.schedulers.engine-reconcile-interval-ms` | `300000` | Full active-case reconciliation interval |
| `casemgmt.schedulers.sla-sweep-interval-ms` | `60000` | SLA sweep interval |
| `casemgmt.schedulers.idempotency-purge-interval-ms` | `3600000` | Key-retention sweep interval |
| `casemgmt.schedulers.idempotency-retention-hours` | `48` | Completed idempotency retention |
| `casemgmt.worker-permissions.enabled` | `false` | Enable external Worker Permissions evaluation |

## Security model

Production should use OIDC/JWT. The PoC basic-auth users exist only for local learning.

The default OIDC mapping expects tenant, groups, Worker Permissions, and dedicated engine
permissions claims. Direct writes to `/engine-rest/**` are reserved for the configured integration
principal or `engine:api`; ordinary users must work through `/case-api/v2`.

Tenant isolation is fail-closed. A cross-tenant resource ID returns `404` so the API does not
confirm that another tenant's resource exists.

## Events and webhooks

Domain changes, audit, events, and local outbox rows commit together. CloudEvent types include case,
task, plan-item, milestone, process, collaboration, and SLA events.

Webhook creation returns the signing secret once. Store it immediately. Verify
`X-Case-Signature: sha256=<hex>` before processing a delivery. Failed deliveries retry and then use
the webhook dead-letter endpoints documented in the [API cookbook](api-cookbook.md).

## SLAs

SLA targets use a strict business calendar. It rejects inverted or overlapping working intervals
instead of guessing. Pausing records an allowed reason; resuming recalculates through business time
rather than adding raw wall-clock duration.

Monitor the SLA sweeper like other scheduled workers. Multi-instance workers claim bounded batches
to reduce duplicate work.

## Troubleshooting

| Symptom | Check |
|---|---|
| Application cannot connect to Oracle | `docker compose ps`, datasource URL, user/password, port 1521 |
| Oracle container exits before ready | Docker memory/platform logs; exit 137/139 occurs before Liquibase |
| Startup names `type-prefix` | Set `casemgmt.events.type-prefix` |
| Startup names webhook encryption key | Supply the same valid Base64 key on every restart |
| Every case request is `401` | Authentication configuration and credentials/token |
| Every case request is `403` | Exactly one `tenant:<id>` authority and required group/permission |
| BPMN definition stays `DEPLOYING` | Remote dispatcher health and deployment dead letters |
| Task has `engineSync: PENDING` | Command dispatcher, remote credentials, engine availability |
| Projection is `STALE` | `/orchestration/remote-status`, Operaton history, poller logs |
| Milestone/stage does not project | BPMN case-management tag, eventing config, activity history |
| Root process ended but case is active | Root ID correlation, history availability, reconciliation status |
| Form returns `422` | Pinned contract schema and `violations[]` pointers |
| Full Maven build has many setup errors | Look for one shared Oracle Testcontainer startup failure first |

## Known boundaries

- Existing plan-model cases remain supported; no automatic migration exists.
- Remote mode is reconciled eventual consistency, not an engine event-stream replica.
- Presentation metadata is not secret; field values must be masked server-side.
- Running-case contract/presentation upgrades, controlled process migration, and embedded Studio are
  roadmap work and must not be represented operationally as already available.

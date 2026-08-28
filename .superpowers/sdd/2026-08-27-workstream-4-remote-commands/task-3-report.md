# Workstream 4 Task 3 — Truthful remote command dispatch

## Outcome

Remote commands now cross a typed transport boundary and every committed policy decision has
an append-only, replayable history. A request that may have reached Operaton is quarantined as
`AWAITING_CONFIRMATION`; it is not blindly sent again. Only a failure with evidence that zero
request bytes left this process is eligible for an automatic retry.

The production Spring configuration uses this path. HTTP success no longer updates case/task/
linked-process projections directly; observation and reconciliation own those business effects.
The previous dispatcher constructors remain as an explicitly deprecated PoC compatibility path.

## Durable transition ledger

`CM_ENGINE_COMMAND_TRANSITION` stores an immutable version-zero baseline and one row for each
committed command version. A transition row contains:

- immutable tenant, operation, command type, and expected-target binding;
- contiguous command version and from/to states;
- a versioned, safe serialization of the exact `CommandDispatchOutcome`;
- canonical decision time and the operator-action sequence where applicable;
- SHA-256 digests of the complete previous and next policy decisions.

Native submissions write their baseline in the same transaction as the command. The restart-safe
Liquibase backfill writes exact native or legacy baselines before the new foreign keys are enabled.
Legacy `DONE` provenance remains the package-owned `LEGACY_MIGRATION` evidence; it is not
fabricated as a live HTTP confirmation.

Every repository-owned transition now performs this sequence in one required transaction:

1. load and validate the current command plus normalized action history;
2. replay the complete transition chain from its stored baseline;
3. ask `EngineCommandPolicy` for the next decision;
4. compare-and-set the command row;
5. append the exact transition row (and, for an operator action, bind it to the normalized action
   row through a foreign key).

Any missing version, broken digest, changed parent, forged outcome, action mismatch, or final row
that differs from replay fails rehydration. The old heuristic
`isPolicyReachableLegacyEvolution` and its tests were removed.

## Dispatcher and HTTP behavior

`EngineCommandDispatcher` now recovers expired leases as uncertainty, claims production leases,
calls `EngineCommandTransport`, and commits the returned typed fact with the lease token and row
version. An unexpected transport exception is conservatively reduced to `UNKNOWN` / possibly sent;
exception text is never persisted.

`RemoteEngineGateway` implements the typed boundary for all seven command types. Successful
responses carry command-bound confirmation evidence containing tenant, operation, command,
command type, expected target, remote identity/state, an exact 200/204 status, and a bounded safe
reference. The transport retains no raw response body, request payload, credential, or exception
message.

Failure classification is conservative:

| Evidence | Outcome |
|---|---|
| Connection/DNS failed before connection | proven zero bytes; automatic policy may retry |
| Socket reset during write, timeout, read failure, unknown client failure | possibly sent; await confirmation/manual handling |
| HTTP 400/404/409 | definitively rejected; policy applies command-specific terminal handling |
| HTTP 408 or 5xx | possibly accepted; await confirmation |
| HTTP 425/429 | definitively not accepted; policy may retry; `Retry-After` is retained safely |
| Malformed 2xx response or missing required identity | malformed response; await confirmation/manual handling |
| Matching duplicate confirmation | exact idempotent confirmation; no second effect |
| Worker crash / expired lease | possibly sent; never returned to `PENDING` |

The existing exhaustive policy matrix remains the authority for max-attempt behavior, operator
override, observation/reconciliation confirmation, 404 cancellation, and command-specific state.

## Files

- `case-management-core/src/main/java/org/casemgmt/engine/EngineCommandTransitionHistory.java`
- `case-management-core/src/main/java/org/casemgmt/engine/EngineCommandTransitionBaselineBackfill.java`
- `case-management-core/src/main/java/org/casemgmt/engine/EngineCommandTransport.java`
- `case-management-core/src/main/java/org/casemgmt/engine/ProductionEngineCommandStore.java`
- `case-management-core/src/main/java/org/casemgmt/engine/EngineCommandDispatcher.java`
- `case-management-core/src/main/resources/db/changelog/cm-production-engine-command.xml`
- `case-management-core/src/main/java/org/casemgmt/migration/OracleFinalSchemaPrecondition.java`
- focused core migration, policy, repository, dispatcher, and schema tests
- `case-management-engine-remote/src/main/java/org/casemgmt/engine/remote/RemoteEngineGateway.java`
- `case-management-engine-remote/src/test/java/org/casemgmt/engine/remote/RemoteEngineCommandTransportTest.java`
- `case-management-spring-boot-starter/src/main/java/org/casemgmt/starter/RemoteEngineAutoConfiguration.java`

## Verification evidence

- Core policy/history/dispatcher/static-schema gate: **1,304 tests passed**.
- Remote typed transport plus existing response/deployment contracts: **29 tests passed**.
- Starter auto-configuration/production-dispatcher wiring gate outside the sandbox:
  **19 tests passed**.
- Focused transition/static-schema gate after baseline codec changes: **16 tests passed**.
- Full core attempt: **1,904 tests executed with zero assertion failures**; the 39 errors were all
  Oracle-backed classes failing from the same unavailable Docker environment.
- Core, remote, and starter affected-reactor compilation: passed.
- `git diff --check`: passed.

### Oracle/Testcontainers limitation

The focused `EngineCommandMigrationRestartIntegrationTest` and
`EngineCommandRepositoryProductionTest` were attempted with the explicitly authorised Docker
Desktop socket (`unix:///Users/ron/.docker/run/docker.sock`). Testcontainers reached Docker
Desktop, but the daemon returned HTTP **503: "Docker Desktop is unable to start"**. Therefore the
new Oracle DDL/restart and repository transaction tests are present and compile, but could not run
live in this environment. This is an environment blocker, not a test assertion or compilation
failure.

## Commit

Committed with the required message: `fix: quarantine uncertain engine commands`.

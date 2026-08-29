# Task 1 report: command-dispatch safety

## Implementation

- Completed the inherited production dispatcher patch so it claims a single command at a time;
  a failed commit cannot lease a later command in the batch.
- Hardened transition-history decoding and replay: bounded canonical JSON, exact keys,
  integral/range checks, canonical re-encoding, and exact reconciliation of every operator
  transition with its normalized action row.
- Added `EngineCommandPayload`, a fail-closed, canonical contract for all seven command types.
  It validates immutable tenant/type/target binding during submission, rehydration, and before
  remote dispatch.
- Updated the remote gateway to return `MALFORMED_RESPONSE` for local invalid payloads without
  calling HTTP, inject a `Clock` for HTTP-date Retry-After calculations, and retain the existing
  conservative status/evidence handling. Remote transport fixtures now use payload-derived
  immutable targets, as the new production contract requires.

## TDD evidence

1. Added `RemoteEngineGatewayTest.malformedCommandPayloadProducesNoHttpCall` before moving
   payload validation inside the gateway's `try` block. RED command:

   ```text
   ./mvnw -pl case-management-engine-remote -am test -Dtest='RemoteEngineGatewayTest' -Dsurefire.failIfNoSpecifiedTests=false
   ```

   It errored with `IllegalArgumentException: Command payload target differs from immutable
   command target` at `RemoteEngineGateway.dispatch`, rather than returning the typed local
   malformed outcome. After the minimal production change, the same command passed (1 test).

2. Added `retryAfterHttpDateUsesTheInjectedClock` before adding the `Clock` constructor.
   RED command was the same focused remote command; test compilation failed because
   `RemoteEngineGateway(RestClient, Clock)` did not exist. After injecting `Clock` and using it
   in RFC-1123 Retry-After parsing, the command passed (2 tests).

The inherited preclaimed-work, tampered-history, malformed/canonical payload, target/tenant,
HTTP 202, and partial-effect coverage was retained. The initial prescribed focused run happened
before the required `RemoteEngineGatewayTest` class existed and failed only because Surefire could
not find that class in the remote module; it was then added and the prescribed command passed.

## Verification

Passed:

```text
./mvnw -pl case-management-core,case-management-engine-remote -am test -Dtest='ProductionEngineCommandDispatcherTest,EngineCommandTransitionHistoryTest,EngineCommandPayloadTest,RemoteEngineGatewayTest'
```

Result: 20 tests passed (18 core, 2 remote).

Passed additional transport coverage:

```text
./mvnw -pl case-management-engine-remote -am test -Dtest='RemoteEngineCommandTransportTest,RemoteEngineGatewayTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Result: 18 tests passed.

Oracle persistence gate attempted twice:

```text
./mvnw -pl case-management-core -am test -Dtest='ProductionEngineCommandStoreTest,EngineCommandClaimSafetyTest,SchemaMigrationTest'
```

The first run could not access Docker in the sandbox (`Operation not permitted` on both configured
Docker sockets). The approved retry connected to Docker Desktop, pulled/started
`gvenzl/oracle-free:23-slim-faststart`, and began a fresh Liquibase schema. It then failed before
the command tests could run on the unrelated changelog `cm-engine-observation-effects.xml` change
set `cm-applied-engine-observation-structure-guard`: Oracle rejected `[Failed SQL: BEGIN NULL; END]`
with `ORA-06550 / PLS-00103` because the PL/SQL block has no terminating semicolon. Consequently,
the mandatory real-Oracle command persistence assertion is blocked by that existing migration.
`ProductionEngineCommandStoreTest` also does not exist in this checkout; the similarly scoped
repository test is `EngineCommandRepositoryProductionTest`.

## Changed files

- `case-management-core/src/main/java/org/casemgmt/engine/EngineCommandDispatcher.java`
- `case-management-core/src/main/java/org/casemgmt/engine/EngineCommandTransitionHistory.java`
- `case-management-core/src/main/java/org/casemgmt/engine/ProductionEngineCommandStore.java`
- `case-management-core/src/main/java/org/casemgmt/engine/EngineCommandPayload.java`
- `case-management-core/src/test/java/org/casemgmt/engine/EngineCommandTransitionHistoryTest.java`
- `case-management-core/src/test/java/org/casemgmt/engine/ProductionEngineCommandDispatcherTest.java`
- `case-management-core/src/test/java/org/casemgmt/engine/EngineCommandPayloadTest.java`
- `case-management-core/src/test/java/org/casemgmt/repo/JsonCodecCanonicalizationTest.java`
- `case-management-engine-remote/src/main/java/org/casemgmt/engine/remote/RemoteEngineGateway.java`
- `case-management-engine-remote/src/test/java/org/casemgmt/engine/remote/RemoteEngineCommandTransportTest.java`
- `case-management-engine-remote/src/test/java/org/casemgmt/engine/remote/RemoteEngineGatewayTest.java`

## Self-review and concerns

- `git diff --check` is clean.
- Public one-argument `RemoteEngineGateway(RestClient)` construction remains supported and defaults
  to UTC system time; the new two-argument constructor makes temporal behavior testable.
- A local invalid payload now has no HTTP side effect and is represented as a typed malformed
  outcome; the dispatcher persists it through the existing policy rather than classifying it as a
  possibly-sent transport failure.
- Concern: full real-Oracle persistence proof remains blocked by the unrelated malformed
  `cm-engine-observation-effects.xml` guard. No unrelated migration change was made in this task.

## Review-fix round 1

### Implementation

- `COMPLETE_TASK` dispatch now uses a status-preserving exchange path. A real `202 Accepted`
  remains an accepted, confirmation-free fact, so policy keeps the command awaiting confirmation.
- Create-task repair no longer returns early when its deterministic task already exists. It retries
  identity links, variables, and read-back; a later HTTP failure after the primary create is
  classified `POSSIBLY_ACCEPTED` rather than proven absent.
- Replay now rejects normalized action rows not referenced by a persisted operator transition.
- All clean-path no-op guards in the observation and production-command changelogs use
  `splitStatements="false"`; default-value guards use Oracle's `DATA_DEFAULT_VC` to avoid LONG
  expression errors. The Oracle metadata precondition also reads an index expression only once.

### TDD evidence

- RED: `RemoteEngineGatewayTest` initially failed because `202` was fabricated as `204`, and a
  post-create `429` was `PROVEN_NOT_ACCEPTED`. GREEN: same focused suite passed 20 remote tests
  after the status-preserving and partial-effect changes.
- RED: the extra normalized-action test expected an exception but replay returned normally.
  GREEN: `EngineCommandTransitionHistoryTest` passed all 6 tests after exact set reconciliation.

### Verification and concern

- Focused remote transport suite: 20 tests passed.
- History suite: 6 tests passed.
- Real Docker/Testcontainers Oracle runs reached fresh Oracle and passed all repaired no-op guards.
  The final migration remains blocked in the unrelated `OracleFinalSchemaPrecondition` while
  reading `USER_IND_EXPRESSIONS.COLUMN_EXPRESSION` (`ORA-17027: Stream has already been closed`),
  before command persistence assertions. The attempted single-read repair did not resolve this
  Oracle JDBC metadata limitation; no command ledger/dispatcher behavior was changed.

## Review-fix round 2

- Reworked Oracle final-schema index metadata reads to query each expression separately and
  consume its character stream before closing the result set; this preserves the exact expected
  index-expression comparison without joining a LONG expression column into the index-column
  cursor.
- Focused remote transport verification remained green: 20 tests passed.
- Outstanding: the requested all-seven-status preservation and persisted repairable CREATE_TASK
  retry need a coordinated command-outcome/policy/store migration. They cannot be added without
  extending the durable outcome format, which this round explicitly forbade editing. Oracle
  revalidation after the JDBC reader change remains required.

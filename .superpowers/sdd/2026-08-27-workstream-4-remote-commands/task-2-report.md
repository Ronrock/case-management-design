# Workstream 4 Task 2 Report

Date: 2026-08-28

Base: `dd8bff1`

Commit: `feat: persist recoverable engine operations`

The final commit hash is recorded in the task handoff because a commit cannot contain its own
hash. The Workstream 4 progress ledger was read and was not edited.

## Outcome

Task 2 replaces the PoC command-row persistence contract with the durable state required by the
Task 1 policy. It adds restart-safe Oracle schema evolution, deterministic historical mapping,
tenant-scoped idempotency, exclusive leases, optimistic decision commits, exact state
rehydration, and an append-only normalized operator-action ledger. Dispatcher classification,
REST operation resources, and remote API behavior remain in Tasks 3–5.

The carried Task 1 and Workstream 3 rulings are also resolved:

- a persisted applied operator action is accepted only when
  `priorSummary.append(appliedAction) == actionLedgerSummary`;
- the action row, command summary, and command `ROW_VERSION_` advance in one REQUIRED local
  transaction;
- the observation ledger's initial table and authority/status indexes have exact, fail-closed
  structural guards before any existence-only `MARK_RAN` changeset.

Neither `AGENTS.md` nor `RTK.md` exists in this checkout. Repository conventions and the written
workstream plan/brief were therefore used directly.

## Production command schema

`cm-production-engine-command.xml` is included after the PoC and observation migrations. It
extends `CM_ENGINE_COMMAND` with:

- tenant, stable operation ID, tenant idempotency key, and SHA-256 payload digest;
- target identity, correlation JSON, canonical patch JSON, and expected case version;
- lease token, lease owner, lease expiry, dispatched/updated/decided/confirmed/failed times;
- safe diagnostic code/summary and typed confirmation identity/provenance;
- lifetime attempts, automatic-budget attempts/epoch/reset flag, row version, and normalized
  action summary/high-water/subtype totals;
- decision-review evidence;
- typed legacy-DONE provenance and retained raw historical payload, error, original status,
  claim token, and claimed timestamp.

The migration creates `CM_ENGINE_COMMAND_ACTION` as the normalized operator history. Unique
indexes on `(command, actionId)` and `(command, sequence)` make action identity and contiguous
ordering database-authoritative. The FK deliberately has no cascade deletion, preserving the
append-only evidence boundary. Check constraints protect attempt/action arithmetic, reset epochs,
lease tuple coherence, action sequence/flags, and review tuple completeness.

Tenant/operation and tenant/idempotency uniqueness use null-authority function indexes. Due,
lease recovery, case/status, and review-age access paths are indexed.

## Historical mapping

The mapping is deterministic and rerunnable:

| PoC status | Production status | Attempt interpretation |
|---|---|---|
| `PENDING` | `PENDING` | existing failure count is retained |
| `RETRYING` | `RETRYABLE` | existing failure count and due time are retained |
| `CLAIMED` | `AWAITING_CONFIRMATION` | `failureCount + 1`; a possibly-sent request is not resent |
| `DONE` | `CONFIRMED` | `failureCount + 1` with typed `LEGACY_MIGRATION` evidence |
| `DEAD` | `FAILED` | existing failure count is retained |

Old `DONE` rows are reconstructed only through the persistence mapper and the package-private
`LegacyDoneCommandMigration`. Rehydration verifies the retained old row ID/status, fixed migration
reference, migration/decision/confirmation timestamps, counters, absence of forged live evidence,
and empty action history. Live dispatch results cannot claim legacy provenance.

The old payload/error/status and claim token/time remain available as historical evidence before
the obsolete claim columns are cleared. The original `ATTEMPTS_`, `CREATED_AT_`, and payload are
also retained in place.

## Restart and structural safety

Oracle DDL autocommits, so every production object is guarded before an existence-only create may
be marked ran. Exact guards validate:

- all command columns, types, lengths, nullability phase, and absence of unexpected defaults;
- the old or production status check definition;
- the action table's complete column/type/null/default signature;
- command counter/lease and action invariant check definitions;
- action FK ownership/column/delete rule;
- uniqueness and ordered columns for both action indexes;
- uniqueness, function expression, and ordered columns for both tenant authority indexes;
- ordered columns and uniqueness for every due/lease/case/review index.

The Oracle migration test covers fresh application, rerun, representative partial prefixes,
all five old statuses, exact raw evidence retention, legacy state rehydration, and malformed
same-named columns/table/checks/FK/function/ordinary indexes. A malformed object must HALT and its
guard must remain absent from `DATABASECHANGELOG`.

The Workstream 3 carry adds the same exact guard behavior for
`CM_APPLIED_ENGINE_OBSERVATION`, `UQ_CM_AEO_AUTH_FINGERPRINT`, and `IX_CM_AEO_STATUS`, with empty,
malformed-width, nonunique, wrong-column, and wrong-function-expression fixtures.

## Repository behavior

`EngineCommandRepository` now provides the production persistence boundary:

- `submit` atomically inserts an operation. A repeated tenant/idempotency key returns the
  original row only when digest, case, command type, payload, target, correlation, canonical
  patch, and expected case version all match. Any different intent is a stable conflict.
- `claimDue(owner, limit, now, leaseDuration)` uses one Oracle update with a unique opaque token.
  It increments lifetime/automatic attempts and `ROW_VERSION_` exactly once and returns only rows
  owned by that token.
- expired `DISPATCHING` leases become `AWAITING_CONFIRMATION`; attempt history is preserved and
  the command is not made due for a blind resend.
- `commitLeaseDecision` requires tenant, operation, exact lease token, exact row version, and the
  unchanged action summary. It clears ownership and persists the complete typed policy decision
  atomically.
- loading validates identifiers, digest, timestamps, version, normalized history aggregates,
  diagnostic whitelist, evidence bindings, action high-water/prior summary, reset epoch/history,
  and legacy provenance before returning a `CommandState`.
- `appendActionAndTransition` catches Oracle duplicate keys inside PL/SQL so a race does not mark a
  caller-owned REQUIRED transaction rollback-only. It reloads the authoritative row, distinguishes
  exact replay from identity/sequence conflict, and CAS-updates the command summary/version in the
  same transaction. A failed CAS rolls the inserted action back.

No `REQUIRES_NEW` propagation is used. The production constructor derives `JdbcClient` and
`DataSourceTransactionManager` from the same unwrapped DataSource resource, so it participates in
an existing case transaction.

The old dispatcher-facing methods remain only as deprecated transitional adapters until Task 3.
They delegate submission/claiming to the production model, never expose or persist raw exception
text, never reclaim an ambiguous expired lease, and cannot perform the old unaudited dead-letter
retry. The starter now constructs the repository with the DataSource-backed transactional
constructor.

## TDD and verification evidence

RED evidence:

- `EngineCommandRepositoryProductionTest` was introduced before the production repository API;
  test compilation failed with 19 missing production methods/types.
- the carried high-water test first failed because a forged aggregate did not require its exact
  prior summary.
- the first post-refactor compile exposed two missing PoC adapter helpers; these were added before
  further verification.

GREEN evidence:

- `./mvnw -pl case-management-core -Dtest=AppliedObservationChangelogStaticValidationTest,EngineCommandPolicyTest,EngineCommandDurableStateTest,EngineCommandLegacyMigrationTest,EngineCommandNormalizedActionLedgerTest,ExactStartOutboxTest test`
  - 1,424 tests passed; 0 failures/errors/skips.
- `./mvnw -pl case-management-spring-boot-starter -Dtest=EmbeddedTransactionAuthorityAutoConfigurationTest,EmbeddedTransactionResourceValidatorTest test`
  - 12 tests passed; 0 failures/errors/skips.
- `./mvnw -pl case-management-core,case-management-spring-boot-starter -am -DskipTests package`
  - all six selected/reactor-required modules succeeded.
- `./mvnw -pl case-management-core -DskipTests test-compile`
  - production and all 93 core test sources compiled.
- `git diff --check`
  - clean.

The focused Oracle command was attempted with escalated Docker access:

```text
./mvnw -pl case-management-core \
  -Dtest=EngineCommandMigrationRestartIntegrationTest,EngineCommandRepositoryProductionTest,\
EngineCommandClaimSafetyTest,SchemaMigrationTest test
```

Testcontainers failed before Oracle could start. Both Unix-socket and Docker Desktop strategies
returned `Status 503: Docker Desktop is unable to start`. Four test classes consequently failed
their shared `OracleTestBase` startup gate; no test method or migration executed. A direct
`docker version` call also hung until interrupted and printed only the client section. This is an
environment failure, not a code/test assertion failure. `OracleTestBase` teardown now null-guards
the unavailable pool so the root Docker diagnostic is no longer obscured by a suppressed NPE.

## Files

Production:

- `case-management-core/src/main/java/org/casemgmt/engine/EngineCommandPolicy.java`
- `case-management-core/src/main/java/org/casemgmt/engine/LegacyDoneCommandMigration.java`
- `case-management-core/src/main/java/org/casemgmt/engine/EngineCommandPersistenceMapper.java`
- `case-management-core/src/main/java/org/casemgmt/repo/EngineCommandRepository.java`
- `case-management-core/src/main/resources/db/changelog/cm-engine-observation-effects.xml`
- `case-management-core/src/main/resources/db/changelog/cm-production-engine-command.xml`
- `case-management-core/src/main/resources/db/changelog/db.changelog-master.xml`
- `case-management-spring-boot-starter/src/main/java/org/casemgmt/starter/CaseManagementRepositoryConfiguration.java`

Tests:

- `AppliedObservationChangelogStaticValidationTest`
- `AppliedObservationMigrationRestartIntegrationTest`
- `EngineCommandMigrationRestartIntegrationTest`
- `EngineCommandRepositoryProductionTest`
- `EngineCommandClaimSafetyTest`
- `EngineCommandDurableStateTest`
- `EngineCommandNormalizedActionLedgerTest`
- `OracleTestBase`
- `SchemaMigrationTest`
- `TransactionManagerTest`

## Remaining environmental gate

When Docker Desktop is healthy, rerun the focused Oracle command above. It is the only Task 2
verification not executed at runtime in this environment. The tests are compiled and Liquibase's
Oracle offline parser validates the complete master changelog, but those are not substitutes for
the live Oracle restart/malformed-object/concurrency proof.

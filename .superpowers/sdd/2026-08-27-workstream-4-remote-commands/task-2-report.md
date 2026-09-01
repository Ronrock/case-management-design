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

Old `DONE` rows are reconstructed only inside `ProductionEngineCommandStore` through the
package-private `LegacyDoneCommandMigration`. Rehydration verifies the retained old row ID/status, fixed migration
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
- `commitLeaseOutcome` requires tenant, operation, exact lease token, exact row version, and a typed
  outcome. It derives the policy decision internally, clears ownership, and persists that exact
  decision atomically.
- loading validates identifiers, digest, timestamps, version, normalized history aggregates,
  diagnostic whitelist, evidence bindings, action high-water/prior summary, reset epoch/history,
  and legacy provenance before returning a `CommandState`.
- `applyOperatorOutcome` derives the transition internally and catches Oracle duplicate keys inside PL/SQL so a race does not mark a
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
- `case-management-core/src/main/java/org/casemgmt/engine/ProductionEngineCommandStore.java`
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

## Review hardening follow-up

Commit: `fix: harden command persistence invariants`

The final hash is recorded in the task handoff because a commit cannot contain its own hash. This
follow-up closes the persistence review without changing dispatcher or REST behavior.

### Policy-owned durable transitions

- `CURRENT_ACTION_SEQ_` is now a nullable pointer to the operator action that produced the current
  decision. It is deliberately separate from the append-only ledger high-water. A later dispatch
  clears the current pointer/review evidence while retaining the complete normalized history.
- claim and expired-lease recovery now execute in a REQUIRED local transaction. They load and
  validate `StoredCommand`, invoke `EngineCommandPolicy`, and CAS-persist the exact returned
  decision. If any selected row fails rehydration, all earlier claims in that batch roll back.
- public lease, general outcome, and operator APIs accept typed facts, not caller-constructed
  `Decision`/`OperatorTransition` objects. The repository validates lease/version/parent binding,
  invokes policy, and persists the exact result.
- persistence writes every mutable decision field, including nullable confirmation/review/current
  action fields, retry time, diagnostics, attempts/budget, terminal timestamps, lease tuple, and
  row version. Historical action aggregates remain intact.

### Idempotency and canonical intent

- command payloads, correlation JSON, and patch JSON are canonicalized internally. Object keys are
  sorted, equivalent numeric spellings such as `1` and `1.0` are normalized, null and Unicode are
  preserved, and array order remains significant.
- SHA-256 is computed from the canonical payload inside the repository. The transitional
  caller-digest constructor rejects any mismatch.
- duplicate insert races are swallowed inside Oracle PL/SQL and then classified by a read-back.
  This avoids a translated unique-key exception poisoning an outer transaction. Executable Oracle
  tests cover writes before/after exact replay and conflict, plus concurrent same/different intent.

### Rehydration and legacy boundary

- every normalized action row is loaded and checked for contiguous sequence, complete review
  tuple, and exact tenant/operation/command/type/target parent binding. The stored aggregate is
  recomputed from all rows; the current-action pointer must resolve to the high-water row.
- payload digest, canonical intent JSON, lease/status tuple, confirmation/review tuple, terminal
  timestamps, action summary, budget epoch/history, and legacy provenance fail closed before a
  command is returned or mutated.
- the public `EngineCommandPersistenceMapper` and public database-row forge were removed.
  Historical DONE reconstruction now lives in the engine-internal production store and calls only
  package-private `LegacyDoneCommandMigration`. A reflection test proves the public repository API
  exposes neither legacy evidence nor the removed trusted transition methods.

### Structural guards

- production command/action guards now reject non-zero NUMBER scales, unexpected defaults, and
  function/ordinary indexes with extra or reordered columns. Malformed scale/default/trailing-index
  Oracle fixtures were added.
- a rerun-safe Workstream 3 hardening guard validates observation kind/default, ignored timestamp,
  process/engine/definition ID widths/null/defaults, exact status/status-timestamp expressions, and
  the exact seven-column engine/entity index. Malformed later-column, constraint, and trailing-index
  fixtures were added.

### Follow-up verification

- strict RED: the strengthened repository test initially failed test compilation with 12 missing
  constructor/request/typed-transition API errors.
- `./mvnw -pl case-management-core -DskipTests test-compile` — all 95 core test sources compile.
- policy/durable/legacy/static/canonical/boundary focused suite — 1,429 tests passed, zero
  failures/errors/skips.
- `./mvnw -pl case-management-spring-boot-starter -am -DskipTests compile` — six reactor modules
  compiled successfully.
- `git diff --check` — clean.

- focused Oracle repository test was attempted both sandboxed and with escalated Docker access.
  Sandboxed access was denied; escalated Docker reached Docker Desktop but returned
  `Status 503: Docker Desktop is unable to start` before Oracle/Testcontainers startup. Therefore
  the new Oracle transaction, concurrency, migration-restart, and malformed-structure methods are
  compile-verified but not runtime-executed in this environment.

## Review hardening round 2

Commit: `fix: make command storage fail closed`

The final hash is recorded in the handoff. This follow-up addresses the second independent
persistence review.

- Submission now returns an internal `CREATED`, `REPLAY`, `IDEMPOTENCY_CONFLICT`, or
  `OPERATION_CONFLICT` classification from the transactional callback. Domain conflict exceptions
  are raised only after the nested REQUIRED callback returns, so catching them inside a caller's
  outer transaction does not mark that transaction rollback-only. Oracle tests write before and
  after both conflict types and verify the outer commit.
- A short-lived creation token in the obsolete PoC claim column identifies the actual winner of a
  concurrent insert and is cleared before the transaction returns. The winner is `CREATED`; exact
  same-command and cross-source losers are `REPLAY`; every same-ID request still undergoes the
  complete canonical intent comparison.
- Due claims and expired recovery select base-table rows with Oracle `FOR UPDATE SKIP LOCKED`.
  Policy rehydration and CAS persistence occur while that exact row lock is held. Tests hold one
  candidate from another connection and prove unrelated due/recovery work proceeds, then prove the
  skipped row is handled after unlock. Corrupt rows still fail and roll back the whole claimed batch.
- Migrated legacy payloads use `DBMS_CRYPTO.HASH` over the full CLOB, removing the old 32,767-byte
  prefix ambiguity. An executable migration test uses a multi-byte payload above that boundary and
  compares Oracle's digest with Java's full UTF-8 SHA-256 before repository rehydration.
- Rehydration now validates the complete native-versus-legacy tuple. All five old statuses are
  checked against raw payload/error/claim evidence, old attempts, due/terminal timestamps, status
  mapping, exact migration decision/update timestamps, and DONE-only provenance. Only a retained
  `CLAIMED` row may carry the complete raw claim token/time pair. Native rows reject every retained
  legacy artifact and require dispatched-time coherence. Forged fixtures cover every historical
  status plus claim-tuple and migration-timestamp corruption.
- Production guards now require enabled/validated constraints on the exact target table and
  valid/visible indexes with exact ownership, uniqueness, expressions, count, and order. Guards are
  rerun on restart so disabled/NOVALIDATE, unusable, trailing-column, and same-name/wrong-table
  partial states halt before a create can be marked ran.
- The Workstream 3 restart guard now accepts every truthful status-constraint deployment prefix
  (both old, either missing, both missing, either final, or both final) while rejecting wrong,
  disabled, or unvalidated definitions. It also validates both plan/task process indexes and all
  later observation columns and the engine/entity index exactly.

Verification:

- all 95 core test sources compile;
- focused static/policy/durable/canonical tests pass with zero failures;
- the final focused static/policy/durable/canonical run executed 1,429 tests with zero
  failures/errors/skips;
- Liquibase validates the complete master changelog;
- `git diff --check` is clean;
- the focused Oracle repository and both restart suites were attempted with escalated Docker
  access. Docker Desktop again returned `Status 503: Docker Desktop is unable to start` before the
  Testcontainers/Oracle startup gate, so the new Oracle test methods remain compile-verified only.

## Review hardening round 3

Commit: `fix: separate command migration provenance`

The final hash is recorded in the handoff. This round separates the immutable PoC migration
baseline from the command's current policy state, removes the database-package hashing dependency,
and adds strict final-state deployment gates.

### Immutable migration baseline and evolving current state

- Migrated rows now retain dedicated raw attempts, created time, reconstructed old update time,
  baseline decision time, and a baseline-active marker alongside the existing raw payload, error,
  claim token/time, and original status. Repository-owned transitions clear only the active marker;
  every raw field and deterministic legacy tenant/operation/idempotency/target binding remains
  immutable.
- An active baseline must still be the exact version-zero migration decision with no action
  history. Once the marker is cleared, rehydration requires a positive repository version and
  validates the current decision through the normal per-status tuple rules while continuing to
  compare every immutable legacy field. A forged version alone cannot turn an untouched baseline
  into an evolved command.
- Oracle tests evolve old `PENDING` and `RETRYING` rows through claim and transport outcome, and an
  old `CLAIMED` row through reconciliation confirmation. They verify the current decisions can
  change without altering raw payload, attempts, or creation evidence. Forged fixtures cover raw
  payload/error/attempt/time/claim fields, baseline decision time, and deterministic bindings for
  all five old statuses.
- The current-state validator now has an explicit matrix for created/decided/updated ordering,
  lease ownership, dispatch timestamp, terminal timestamp, diagnostic, pending, intent JSON, and
  confirmation/action evidence tuples. A parameterized Oracle test builds every native status and
  proves timestamp corruption is rejected for each one.

### Full CLOB digest without Oracle package grants

- The earlier `DBMS_CRYPTO` approach is superseded. A Liquibase Java custom change streams the
  complete retained CLOB through UTF-8 SHA-256 and writes the lower-case digest. It needs only the
  application's ordinary table privileges and does not depend on a `SYS.DBMS_CRYPTO` execute grant.
- The unit proof hashes a Unicode character stream well beyond 32 KiB and compares it with an
  independent Java SHA-256 calculation. The Oracle migration test retains its over-32-KiB CLOB
  fixture and compares the persisted digest with the same full-content Java policy.

### Strict final-state migration gates

- Prefix guards remain restart-tolerant while Oracle DDL may be partially applied. Final
  `runAlways` guards now require the complete production command/action and Workstream 3
  observation structures on every subsequent deployment.
- The command gate requires the exact final column counts/signatures, enabled and validated checks
  and FK, and all eight valid/visible indexes. The observation gate requires the full ledger
  column set, final 128-character process/engine definition fields, enabled and validated final
  checks, and all five valid/visible indexes. The preceding `runAlways` structural guards verify
  exact constraint definitions and exact index ownership, uniqueness, expressions, order, and
  column counts.
- Full-apply mutation tests remove or disable production constraints/indexes and revert observation
  status/width/index structures; rerunning Liquibase must halt rather than silently record success.

### Round-3 verification

- strict RED: static changelog tests first failed because the final command/observation guards and
  streaming digest change did not exist; the digest unit test initially failed compilation because
  the custom change class did not exist.
- `./mvnw -pl case-management-core -Dtest=AppliedObservationChangelogStaticValidationTest,Ws2ChangelogStaticValidationTest,JsonCodecCanonicalizationTest,LegacyMigrationBoundaryTest,EngineCommandPayloadDigestBackfillTest,EngineCommandPolicyTest,EngineCommandDurableStateTest,EngineCommandLegacyMigrationTest,EngineCommandNormalizedActionLedgerTest,ExactStartOutboxTest test`
  — 1,430 tests passed; zero failures/errors/skips.
- `./mvnw -pl case-management-spring-boot-starter -am -DskipTests compile` — all six selected and
  reactor-required modules compiled successfully.
- `./mvnw -pl case-management-core -DskipTests test-compile` — all 96 core test sources compiled.
- `git diff --check` — clean.

- The focused Oracle command for `EngineCommandMigrationRestartIntegrationTest`,
  `EngineCommandRepositoryProductionTest`, and `AppliedObservationMigrationRestartIntegrationTest`
  was attempted with escalated Docker access on 2026-08-29. Docker Desktop returned HTTP 503
  before Testcontainers could start Oracle, so the new live migration/evolution/mutation methods
  remain compile-verified but could not execute in this environment.

## Review hardening round 4

Commit: `fix: enforce final command schema`

The final hash is recorded in the handoff. This round closes the exhaustive final-schema and
migrated-baseline/current-state findings without changing the restart-tolerant prefix guards.

### Exact final schema contracts

- both final `runAlways` gates now invoke a strict Oracle metadata contract. The production
  contract checks all 61 `CM_ENGINE_COMMAND` and all 15 `CM_ENGINE_COMMAND_ACTION` columns for
  exact type dimensions, BYTE character semantics, nullability, and normalized defaults. The
  Workstream 3 contract checks all 19 observation-ledger columns plus the hardened plan-item,
  task, and linked-process columns.
- the same contracts validate enabled/validated named checks, the action foreign-key target and
  ordered columns, the command primary key and backing index, and every legacy/new command and
  observation index for current-schema ownership, target table, uniqueness, validity, visibility,
  and exact total ordered columns or expressions.
- post-apply Oracle mutation coverage now changes an original column, later command/observation
  columns, an action column, the original observation authority/status indexes, and unusable or
  replaced later indexes. The descriptor unit tests independently prove the 61/15/19 inventories,
  the hardened related columns, constraints/indexes, metadata normalization, and fail-closed
  column matching.

### Migration baseline, CAS, and time coherence

- an inactive migration marker is accepted only for a later policy-reachable decision with a
  causal attempt, normalized action, confirmation, or review fact appropriate to the resulting
  status. Marker/version-only `PENDING`, same-state `RETRYABLE`, impossible terminal-baseline
  dispatches, and evidence-free terminal/review states are rejected; valid retry, operator,
  observation, and reconciliation paths remain accepted.
- decision writes now compare the baseline marker, current-action pointer, payload digest/full raw
  CLOB equality, original status, and all retained raw scalar/timestamp values in the same CAS.
  Exact no-op outcome replays return the current row without clearing the baseline marker or
  advancing the row version.
- `RETRYABLE` requires `NEXT_ATTEMPT_AT_ > DECIDED_AT_`; `DISPATCHING` requires
  `LEASE_EXPIRES_AT_ > DECIDED_AT_`. A new restart-safe temporal constraint also enforces those
  relations, exact terminal timestamps, and dispatched-time bounds. A separate additive correction
  normalizes only untouched migrated retry rows before installing the constraint, preserving the
  checksum of the original migration changeset.
- the streaming digest helper now owns and closes every payload `Reader`, including Oracle CLOB
  readers.

### Round-4 verification

- strict RED: the new reader-ownership test failed until the digest helper closed its input; the
  expanded static ordering expectation failed when the new guarded temporal changesets first
  appeared and was then updated to the intended order.
- focused policy/durable/legacy/action/static/schema/digest suite: 1,430 tests passed, zero
  failures/errors/skips.
- `./mvnw -pl case-management-core -DskipTests test-compile` — all 98 core test sources compiled.
- `./mvnw -pl case-management-spring-boot-starter -am -DskipTests compile` — all six selected and
  reactor-required modules compiled successfully.
- focused Oracle command/repository/observation restart suites were attempted. Testcontainers could
  not access either configured Docker socket (`Operation not permitted`; no valid Docker
  environment), so the Oracle mutation, CAS, no-op, and migration-cycle scenarios are
  compile-verified but were not runtime-executed here.
- `git diff --check` — clean.

## Review hardening round 5

Commit: `fix: finalize command persistence contracts`

The final hash is recorded in the handoff. This breaker round replaces the remaining heuristic
legacy validation, makes Oracle string semantics deterministic, and closes the raw/current CLOB
compare-and-set gap.

### Exact legacy evolution validation

- migrated `DONE`/`CONFIRMED` and `DEAD`/`FAILED` baselines are immutable forever. Live
  `PENDING`, `RETRYING`, and `CLAIMED` baselines are checked against an explicit current-status,
  attempt-delta, normalized-action, review/confirmation provenance, row-version, and timestamp
  matrix.
- the matrix accounts for the exact number of repository decisions needed for claim/outcome
  cycles, operator retry/cancel actions, and an automatic reconciliation exit from
  `AWAITING_CONFIRMATION`. A capped claimed baseline may reconcile definitive absence to
  `FAILED` without another attempt; an uncapped or insufficient-version forgery is rejected.
- exhaustive tests cover every five original statuses by every nine production statuses plus
  marker-only, cap, multi-step reconciliation/retry, consumed action, cancel, confirmation, and
  provenance/version edge cases. Nullable PoC payloads preserve a null raw value while the live
  payload is canonicalized to `{}` for hashing and safe rehydration.

### Deterministic Oracle BYTE semantics

- every new production command/action and Workstream 3 observation/hardening `VARCHAR2`
  declaration explicitly uses `BYTE` semantics.
- a restart-safe custom Liquibase change derives the complete VARCHAR2 inventory from the same
  strict final-schema contracts (39 command/action and 16 observation/related columns). It
  preflights all CHAR-semantics columns with `LENGTHB` before issuing any DDL, halts before partial
  conversion on overflow, converts only compatible CHAR columns, and skips already-converted BYTE
  columns on restart.
- isolated-schema Oracle coverage sets `NLS_LENGTH_SEMANTICS=CHAR` on both the baseline and final
  Liquibase connections, migrates, asserts every contract column has `CHAR_USED='B'`, and reruns.
  Static tests independently validate the exact target inventory and all new XML declarations.

### Full null-safe raw/current CLOB CAS

- repository rehydration streams and closes both live and retained raw CLOB readers. The immutable
  CAS snapshot now carries each value separately and binds each as an Oracle CLOB.
- every decision update compares expected raw and expected live payload through independent,
  explicit null-safe predicates; it never compares the retained raw value to the mutable live
  column.
- Oracle concurrency tests interpose a separate committed connection exactly after rehydration and
  before the decision CAS. They cover expected raw null, expected raw non-null, divergent mutation
  of both columns (rejected), and an exact-value concurrent rewrite (accepted).

### Round-5 verification

- strict RED/GREEN evidence included missing exact evolution facts, terminal baseline evolution,
  consumed-action version accounting, nullable retained payload handling, BYTE target/declaration
  inventory, the decision-CAS interlock, and an insufficient-version claimed redispatch.
- focused policy/durable/legacy/action/static/schema/digest suite: 1,503 tests passed, zero
  failures/errors/skips (including 66 exact legacy-evolution cases).
- `./mvnw -pl case-management-spring-boot-starter -am -DskipTests compile` — all six selected and
  reactor-required modules compiled successfully.
- `git diff --check` and the no-implicit-VARCHAR2 scan for the two production changelogs are clean.
- the focused Oracle repository and migration-restart suites were attempted with escalated Docker
  access. Docker Desktop returned HTTP 503 before Testcontainers/Oracle startup, so the new CHAR
  session, nullable legacy payload, and three CLOB concurrency methods are compile-verified but
  remain runtime-unexecuted in this environment.

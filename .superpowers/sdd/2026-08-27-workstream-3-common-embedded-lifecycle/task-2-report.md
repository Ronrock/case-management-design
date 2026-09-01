# Workstream 3 — Task 2 report

## Outcome

Added the transaction-participating Oracle claim ledger that prevents one deterministic engine
fact from creating lifecycle business effects more than once. The change is limited to Task 2:
it does not add the common handler, remote inbox, projection behavior, or canonical mapping.

## RED evidence

`AppliedObservationRepositoryTest` and `AppliedObservationChangelogStaticValidationTest` were
added before the repository or migration existed. The first focused command was:

```text
./mvnw -pl case-management-core -Dtest=AppliedObservationRepositoryTest,AppliedObservationChangelogStaticValidationTest test
```

It failed during `testCompile` with the expected missing-production-API errors: 16 references to
`AppliedObservationRepository` (including `ClaimResult` and `ClaimOutcome`) could not resolve.
This is the acceptable compile-time RED state for a new Oracle-backed repository when Docker is
not available.

## Design

- `AppliedObservationRepository` accepts the existing Task 1 `EngineObservation` contract without
  changing it. `claim` derives and validates its public SHA-256 fingerprint and records envelope,
  source, case/process/entity, revision, event, and engine-occurrence metadata.
- `ClaimResult` returns `CLAIMED`, `RECLAIMED`, or `DUPLICATE`. Only an owner receives an optional
  `Claim`; duplicates receive no finalisation capability.
- `markApplied` and `markFailed` update only a `CLAIMED` row matching the owning envelope,
  tenant authority, and fingerprint. A zero-row update throws rather than hiding a stale-owner
  conflict. No transaction annotation or `REQUIRES_NEW` boundary is introduced, so all operations
  use the caller's transaction.
- A failed row is reclaimed through one guarded `UPDATE`, replacing its envelope/operational
  metadata and status while preserving the most recent failure timestamp and bounded diagnostic.

## SQL invariants

- New additive changeset: `cm-applied-engine-observation` in
  `cm-engine-observation-effects.xml`, included last from the master changelog.
- `CM_APPLIED_ENGINE_OBSERVATION` stores nullable `TENANT_ID_`, 64-character fingerprint,
  lifecycle status, claimed/applied/failed timestamps, and a 2,000-character failure diagnostic.
- `UQ_CM_AEO_AUTH_FINGERPRINT` is an Oracle function-based unique index over
  `CASE WHEN TENANT_ID_ IS NULL THEN 1 ELSE 0 END`, `TENANT_ID_`, and `FINGERPRINT_`.
  The non-null discriminator means tenant-less facts are indexed and unique; it uses no sentinel
  that could alias a legal tenant ID.
- `CK_CM_AEO_STATUS` restricts status to `CLAIMED`, `APPLIED`, or `FAILED`; timestamp checks
  require an applied or failed timestamp when the corresponding final status is recorded.

## Tests and compile results

- `AppliedObservationRepositoryTest` covers first ownership and metadata, duplicate no-mutation,
  failed retry/reclaim (including a null tenant), guarded stale-owner transition rejection, and
  two concurrent claim attempts with exactly one owner.
- `AppliedObservationChangelogStaticValidationTest` passes: Liquibase offline validation succeeds,
  the new changeset is the master-changelog tail, and the function-based null-tenant unique-index
  shape is present.
- `./mvnw -pl case-management-core -DskipTests package` passes, including production and test
  compilation and jar/test-jar creation.
- The requested Oracle command was attempted after the final source change:

  ```text
  ./mvnw -pl case-management-core -Dtest=AppliedObservationRepositoryTest,SchemaMigrationTest test
  ```

  It cannot start either Oracle test because Docker is unavailable on this host:
  `NoSuchFileException (/var/run/docker.sock)` from Testcontainers. The command compiles both
  production and test sources before that environmental failure.

## Self-review

- `git diff --check` reports no whitespace errors.
- The repository has no engine/vendor imports and consumes only the existing observation contract.
- The duplicate branch performs no mutation for `CLAIMED` or `APPLIED`; only `FAILED` rows match
  the reclaim update.
- Both finalisation writes include owner coordinates and `STATUS_ = 'CLAIMED'`, and their affected
  row counts are enforced.
- The Oracle reset fixture includes the new standalone table so later Oracle tests remain isolated.

## Commit

`feat: deduplicate engine observations`

## Concerns

Runtime Oracle/Liquibase verification remains pending a Docker-capable environment. The local
offline Liquibase guard and complete package compilation provide non-Docker evidence, but cannot
replace executing the Oracle function-based index and concurrent race test in CI.

---

## Fix round 1/5 — ownership capability and caller transactions

### RED evidence

Tests were extended before the fix with an opaque-claim API proof, token-column migration
assertion, same-observation-ID stale-owner case, and explicit Spring transaction contention and
rollback cases. The non-Docker RED command was:

```text
./mvnw -pl case-management-core -Dtest=AppliedObservationClaimApiTest,AppliedObservationChangelogStaticValidationTest test
```

It failed exactly as intended: `AppliedObservationClaimApiTest` found the public record
constructor `Claim(String, String, String)`, and the static Liquibase test could not find
`CLAIM_TOKEN_` in `CM_APPLIED_ENGINE_OBSERVATION`.

### Changes

- Replaced the public `Claim` record with a final nested class whose only constructor is private.
  Coordinates remain readable for diagnostics, while the ownership token has no public accessor
  and cannot be minted by callers.
- Every insert and `FAILED` reclaim generates a new 32-byte `SecureRandom` token encoded as a
  43-character URL-safe Base64 value. The token is bounded and format-validated both when
  generated and when an internal capability is validated.
- Added nullable-free `CLAIM_TOKEN_ VARCHAR2(43)` to the still-unapplied, additive changeset.
  `markApplied` and `markFailed` now require it in their guarded update predicates.
- The same envelope ID can now be reclaimed safely: its new claim capability differs from the
  old one, so the stale capability affects zero rows while the new owner can finalise.

### Tests and compile results

- `AppliedObservationClaimApiTest.claimHasNoPublicConstructionPath` passes.
- `AppliedObservationChangelogStaticValidationTest` passes, including `CLAIM_TOKEN_` presence,
  master ordering, and null-tenant unique-index shape.
- `AppliedObservationRepositoryTest.sameObservationIdReclaimInvalidatesTheStaleOwnershipToken`
  proves stale/new ownership separation; `contenderWaitsForTheOwningCallerTransactionThenReturnsDuplicate`
  holds the first `TransactionTemplate` transaction open with latches while a contender blocks;
  `rollbackOfOwningCallerTransactionLetsTheContenderClaimTheFact` proves rollback releases the
  fact to the contender. All waits have bounded timeouts and no sleep.
- The non-Docker GREEN command above passes: 2 tests, 0 failures/errors.
- `./mvnw -pl case-management-core -DskipTests package` passes, including complete production
  and test compilation plus jar/test-jar creation.
- The requested Oracle command was retried after the fix. It still compiles both source sets, but
  Testcontainers stops before migration/test execution because `/var/run/docker.sock` is absent.

### Self-review

- The token is not a tenant sentinel or public coordinate: it is freshly random on every owning
  insert/reclaim and is required by both final-state updates.
- `Claim` has no public constructor; the public result can only return a repository-created
  capability.
- The transaction tests use Spring's `DataSourceTransactionManager` through `TransactionTemplate`,
  hold/release ownership with latches, and check both committed and rolled-back contention paths.
- No `REQUIRES_NEW`, common handler, remote inbox, or Task 4 behavior was added.

### Fix commit

`fix: guard applied observation ownership`

### Remaining concern

Oracle execution of the function-based index, claim-token predicates, and held-transaction race
remains pending Docker-capable CI; the unchanged environmental block is the missing Docker socket.

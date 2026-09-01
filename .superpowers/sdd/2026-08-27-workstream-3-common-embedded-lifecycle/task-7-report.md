# Workstream 3 Task 7 Report

Date: 2026-08-28

## Outcome

Task 7 adds executable boundaries and parity contracts around the common lifecycle handler. The
embedded adapter is now mechanically prevented from taking ownership of projection, claim, audit,
event, webhook, or SLA writes. All four observation records have a stored-JSON round-trip
contract, and the Oracle transactional fixture contains a direct-versus-stored comparison of the
complete affected database outcome. Generated identifiers are canonicalized, while timestamp
presence, nullability, ordering, and the exact business event time remain explicit assertions.

The carried Task 6 cancellation findings were fixed first in a separate commit:

- `d952cba` — `fix: preserve embedded cancellation intent`

The Task 7 changes and this report are in the commit named:

- `test: enforce lifecycle handler boundary`

The review-hardening changes in this report are in the commit named:

- `fix: harden lifecycle parity gates`

`AGENTS.md` and `RTK.md` do not exist in this checkout. Repository conventions, the complete
workstream plan, Task 7 brief, progress ledger, and Task 6 report were used instead. The progress
ledger was not edited.

## Carried cancellation fixes

### Explicit cancellation intent

Operaton distinguishes ordinary completion from deletion using a nullable engine delete reason.
Passing a null public/domain reason straight through therefore made a bodyless cancellation look
like successful process completion. `EmbeddedEngineGateway` now encodes every cancellation in a
versioned, gateway-owned envelope. A null reason has an explicit null tag; every string, including
the empty string, old marker text, reserved-prefix text, and Unicode, is encoded as UTF-8 Base64URL.
This makes the mapping one-to-one and keeps engine protocol metadata out of the domain reason.
`EmbeddedEngineEventBridge` decodes only this exact format. Unowned raw Operaton deletion reasons,
including malformed or incomplete reserved-looking values, remain ordinary termination reasons.

### Narrow reason propagation

For a genuine user reason, `EmbeddedEngineEventBridge` adds only `cancellationReason` to a terminal
`ProcessObservation`. `DefaultEngineObservationHandler` reads that one attribute only when it is
publishing a root `CASE_CANCELLED` event and maps it to the existing public `reason` field. Missing
reason maps to the same empty-string payload already used by the service/remote path. Child
termination, audit-safe metadata, unrelated attributes, and sensitive/arbitrary attributes do not
receive this field.

Coverage includes:

- exact gateway encoding tests for null, empty, old-marker, reserved-prefix, and Unicode reasons;
- bridge tests for COMPLETED versus TERMINATED classification, owned-envelope decoding, raw
  external deletion reasons, malformed reserved-looking values, and the one whitelisted reason
  attribute;
- common-handler tests for root null/non-null payloads and child/arbitrary-attribute isolation;
- a real Spring + Operaton + production-handler H2 scenario proving a bodyless `CaseService`
  cancellation is TERMINATED, preserves a null domain reason, publishes one cancellation event,
  and cannot be mistaken for COMPLETED;
- a real HTTP no-request-body test (Oracle-backed) proving REST binding preserves null;
- service-owned null/non-null payload tests for the remote/no-synchronous-callback branch
  (Oracle-backed).

### Concurrency proof

The cancellation-race latch was moved out of the engine gateway and into a test-only
`CaseRepository.lockForObservation` subclass. The test now pauses only after the API transaction
owns the case-row lock, observes the competing engine callback arriving at the same lock boundary,
and then releases the owner. It asserts:

- the API cancellation wins with the expected reason and version;
- exactly one case event, engine audit, and API intent audit exist;
- the root process/link is terminated;
- the losing Operaton deletion reports the expected stale/not-found category (an Operaton
  `NullValueException` saying the process-instance execution list is empty), instead of its result
  being silently ignored.

No production concurrency seam was added.

## Task 7 implementation

### Embedded handler boundary

`EmbeddedLifecycleBoundaryArchitectureTest` imports the production embedded package and first
asserts that the real event bridge was imported, preventing a vacuous pass. It then uses ArchUnit
to forbid embedded production dependencies on every current CM persistence, projection, service,
event, SLA, and JDBC surface. This includes all repository and projection packages,
`EventPublisher`, `SlaLifecyclePort`, mapping/services, `JdbcClient`, `JdbcTemplate`, `DataSource`,
and JDBC connections. The rule checks structural bytecode dependencies, so constructor calls,
fields, ordinary calls, and method references cannot bypass it and a newly added writer method is
not silently allowed. CM dependencies are default-denied: only the enumerated engine DTO/gateway,
observation record/handler/authority, and deployment read-contract types are allowed.

Persisted process-to-case correlation and legacy PLAN_MODEL backfill were extracted to
`PersistedProcessCaseAuthority` in core. The embedded adapter now sees only
`ProcessCaseAuthority`, the common observation handlers/records, and a narrow engine-neutral
`EngineProcessAuthorityLookup`. `OperatonProcessAuthorityLookup` implements that read port with
runtime-variable, process-instance, and exact process-definition lookups. It exposes no Operaton
type to core. The compatibility `ProcessCaseCorrelation` name remains a read-only alias, and its
implementation only delegates to core; it owns no repository or write behavior.

Four mutation fixtures prove the rule fails for a repository dependency, an
`EventPublisher::publish` method reference plus SLA dependency, and direct `JdbcClient` access.
The fourth proves an effect-writing `OutboxEngineGateway` facade is rejected even though its
package also contains allowed engine request/response types.
The positive production import is non-empty and passes. Core handlers remain the only owner of
lifecycle effects.

ArchUnit was added only as a test dependency to `case-management-engine-embedded`.

### Stored-observation parity

`EngineObservationSerializationContractTest` serializes and deserializes process, user-task,
activity, and milestone fixtures with IDs, engine/source/tenant/case/process/entity identity,
revision, millisecond timestamps, enums, nested attributes, lists, booleans, and numeric values.
Each restored record and fingerprint must equal the direct fixture. Jackson's Java-time module was
added only at test scope.

`EngineObservationTransactionalIntegrationTest` now applies the same completed-task fixture in two
ways:

1. directly in an explicit transaction that commits;
2. after the mutable case fixture is deleted and identically reseeded, after JSON storage and
   restoration, in a second explicit transaction that commits.

The snapshot is taken only after each commit, so the handler's deferred `beforeCommit` event and
webhook delivery effects must exist in both paths. The comparison covers every selected column in
case, plan-item, task, linked-process, SLA, applied-observation, audit, event, and webhook-delivery
rows. Generated UUIDs, sequences, claim tokens, and processing timestamps are canonicalized only
after their semantics are asserted. Event and delivery sequences become stable ordinals while the
delivery-to-event foreign-key relationship is checked and preserved. `CM_EVENT.TIME_` must equal
the observation time exactly. `NEXT_ATTEMPT_AT`, `UPDATED_AT`, claim/applied timestamps, and all
nullable delivery processing timestamps retain explicit presence/null/order checks. Business
fields, versions, identity, fingerprint, statuses, JSON, and row counts remain in the equality.

### Changelog regression

`Ws2ChangelogStaticValidationTest` still proves the four WS2 changesets are present, contiguous,
and in their original order, but no longer incorrectly requires them to remain the tail of the
master changelog after later WS3 changesets are appended.

## Files

Carried fix commit `d952cba`:

- `case-management-core/src/main/java/org/casemgmt/observation/DefaultEngineObservationHandler.java`
- `case-management-core/src/test/java/org/casemgmt/observation/DefaultEngineObservationHandlerTest.java`
- `case-management-core/src/test/java/org/casemgmt/service/CaseServiceTest.java`
- `case-management-engine-embedded/src/main/java/org/casemgmt/engine/embedded/EmbeddedEngineGateway.java`
- `case-management-engine-embedded/src/main/java/org/casemgmt/engine/embedded/EmbeddedEngineEventBridge.java`
- `case-management-engine-embedded/src/test/java/org/casemgmt/engine/embedded/EmbeddedEngineGatewayResponseTest.java`
- `case-management-engine-embedded/src/test/java/org/casemgmt/engine/embedded/EmbeddedEngineEventBridgeTest.java`
- `case-management-rest/src/test/java/org/casemgmt/rest/http/CaseApiHttpTest.java`
- `case-management-spring-boot-starter/src/test/java/org/casemgmt/starter/ProductionEmbeddedLifecycleIT.java`

Task 7 commit:

- `case-management-core/pom.xml`
- `case-management-core/src/test/java/org/casemgmt/Ws2ChangelogStaticValidationTest.java`
- `case-management-core/src/test/java/org/casemgmt/observation/EngineObservationSerializationContractTest.java`
- `case-management-core/src/test/java/org/casemgmt/observation/EngineObservationTransactionalIntegrationTest.java`
- `case-management-engine-embedded/pom.xml`
- `case-management-engine-embedded/src/test/java/org/casemgmt/engine/embedded/EmbeddedLifecycleBoundaryArchitectureTest.java`
- this report.

Review-hardening commit:

- `case-management-core/src/main/java/org/casemgmt/observation/EngineProcessAuthorityLookup.java`
- `case-management-core/src/main/java/org/casemgmt/observation/ProcessCaseAuthority.java`
- `case-management-core/src/main/java/org/casemgmt/observation/PersistedProcessCaseAuthority.java`
- `case-management-core/src/test/java/org/casemgmt/observation/PersistedProcessCaseAuthorityTest.java`
- `case-management-core/src/test/java/org/casemgmt/observation/EngineObservationTransactionalIntegrationTest.java`
- `case-management-engine-embedded/src/main/java/org/casemgmt/engine/embedded/EmbeddedCancellationReason.java`
- `case-management-engine-embedded/src/main/java/org/casemgmt/engine/embedded/EmbeddedEngineGateway.java`
- `case-management-engine-embedded/src/main/java/org/casemgmt/engine/embedded/EmbeddedEngineEventBridge.java`
- `case-management-engine-embedded/src/main/java/org/casemgmt/engine/embedded/OperatonProcessAuthorityLookup.java`
- `case-management-engine-embedded/src/main/java/org/casemgmt/engine/embedded/PersistedProcessCaseCorrelation.java`
- `case-management-engine-embedded/src/main/java/org/casemgmt/engine/embedded/ProcessCaseCorrelation.java`
- `case-management-engine-embedded/src/main/java/org/casemgmt/engine/embedded/ProcessActivityClassifier.java`
- `case-management-engine-embedded/src/main/java/org/casemgmt/engine/embedded/RepositoryProcessActivityClassifier.java`
- corresponding embedded gateway, bridge, authority lookup, classifier, and ArchUnit tests;
- `case-management-engine-embedded/src/test/resources/archunit.properties`;
- `case-management-spring-boot-starter/src/main/java/org/casemgmt/starter/EmbeddedEngineAutoConfiguration.java`;
- `case-management-spring-boot-starter/src/main/java/org/casemgmt/starter/EmbeddedTransactionResourceValidator.java`;
- corresponding starter auto-configuration, validator, correlation, and real lifecycle tests;
- this updated report.

The old embedded repository-owning correlation implementation/tests and embedded-owned
transaction-validator implementation/tests were removed. Equivalent authority coverage now lives
in core, and transaction-resource validation lives with starter wiring.

## Verification evidence

### Green gates

1. Focused cancellation TDD after the initial red run:

   `./mvnw -pl case-management-core,case-management-engine-embedded -am -Dtest=DefaultEngineObservationHandlerTest,EmbeddedEngineGatewayResponseTest,EmbeddedEngineEventBridgeTest -Dsurefire.failIfNoSpecifiedTests=false test`

   Result: 42 tests passed (29 core and 13 embedded).

2. Real production handler and Operaton on H2:

   `./mvnw -pl case-management-spring-boot-starter -am -Dtest=ProductionEmbeddedLifecycleIT -Dsurefire.failIfNoSpecifiedTests=false test`

   Result after hardening: 16 tests passed. This includes lifecycle effects, rollback injection,
   null/empty/old-marker/prefix/Unicode cancellation, and the case-lock race.

3. Focused core Task 7 contracts:

   `./mvnw -pl case-management-core -Dtest=ArchitectureTest,Ws2ChangelogStaticValidationTest,AppliedObservationChangelogStaticValidationTest,ObservationFingerprintTest,EngineObservationSerializationContractTest,DefaultEngineObservationHandlerTest,DefaultEngineObservationAuthorityValidatorTest,ContractCaseDataMappingServiceTest test`

   Result: 66 tests passed.

4. Complete embedded module after installing the current core snapshot without tests:

   `./mvnw -pl case-management-engine-embedded test`

   Result after hardening: 60 tests passed with zero failures/errors/skips. The apparent count
   reduction is ownership movement: 11 persisted-correlation tests moved to core and six
   transaction-validator tests moved to starter, while two Operaton lookup tests and expanded
   cancellation/boundary tests were added. It includes real Operaton H2 lifecycle tests and all
   six structural ArchUnit gates.

5. Complete starter module outside the socket-restricted sandbox:

   `./mvnw -pl case-management-spring-boot-starter test`

   Result after a clean reactor rebuild: 52 tests passed with zero failures/errors/skips. This
   includes 16 production lifecycle tests, six transaction-authority wiring tests, six validator
   unit tests, and all auto-configuration backoff tests. A prior isolated run used stale installed
   embedded bytecode; the clean reactor run established that was build-state, not a product
   failure. Socket-using tests ran with explicit escalation.

6. PoC checks that do not require Oracle:

   `./mvnw -pl case-management-poc-app -Dtest=BpmnComplaintResourcesTest,NoCaseTypeVocabularyTest,PocJwtAuthenticationConverterTest,CrossModuleArchitectureTest test`

   Result: 11 tests passed.

7. Reactor compilation/install without tests:

   `./mvnw -pl case-management-poc-app -am -DskipTests install`

   Result: all seven reactor entries built and installed successfully.

8. `git diff --check` passed before commit.

9. Focused review-hardening core contracts:

   `./mvnw -pl case-management-core -Dtest=PersistedProcessCaseAuthorityTest,EngineObservationSerializationContractTest,DefaultEngineObservationHandlerTest test`

   Result: 44 tests passed (11 authority, four serialization, and 29 common handler tests).

10. Focused clean reactor starter verification:

   `./mvnw -pl case-management-spring-boot-starter -am clean -Dtest=AutoConfigurationTest,EmbeddedTransactionAuthorityAutoConfigurationTest,EmbeddedProcessCaseCorrelationTest,ProductionEmbeddedLifecycleIT -Dsurefire.failIfNoSpecifiedTests=false test`

   Result: command exited 0. It proves the default and consumer-substitute authority beans,
   transaction-validator move, correlation delegation, and all 16 real lifecycle scenarios use
   freshly compiled upstream classes.

### Docker/Oracle limitation

Docker access was retried with explicit escalation. The Docker 29.4.0 client could be read, but
the daemon did not answer `docker info`/`docker version`. Testcontainers 1.21.4 provided the
concrete diagnosis on both the Unix socket and Docker Desktop strategies:

`Status 503: Docker Desktop is unable to start`

The requested aggregate command used the repository's actual PoC module name
`case-management-poc-app` (there is no `case-management-poc-embedded-api` module):

`./mvnw -pl case-management-core,case-management-engine-embedded,case-management-poc-app,case-management-spring-boot-starter -am test`

It ran 395 core tests: 359 passed, zero assertion failures, and 36 Oracle-backed classes reported
the same Docker/Testcontainers setup error. Maven then correctly skipped downstream modules.

The required final command was also attempted:

`./mvnw clean install`

It freshly compiled 174 core production sources and 85 core test sources, then produced the same
395-test result (359 passed, zero failures, 36 Docker/Oracle setup errors) and skipped downstream
modules. This is an external runtime blocker, not a code/test assertion failure.

Consequently, this run does **not** claim live Oracle execution for the new database parity test,
the Oracle service/REST cancellation tests, the existing duplicate/stale concurrency tests, or the
full clean install. Those tests compile and their non-Docker counterparts pass; they must be rerun
when Docker Desktop can start the Oracle container.

After the hardening changes, the complete PoC suite was retried with escalation. It ran 32 tests:
11 non-Oracle tests passed and 21 Oracle-dependent tests errored from the same
`Could not find a valid Docker environment` initialization failure. The four-class non-Oracle PoC
gate was then run separately and all 11 tests passed. The focused parity test itself was also
retried directly; Testcontainers failed before an assertion with the Docker Desktop HTTP 503
response. Its committed transaction flow and complete fixture compile successfully, but this
report intentionally does not claim a live database result.

## Completion evidence mapping

- Transaction trace: `ProductionEmbeddedLifecycleIT` exercises real Operaton callbacks through
  the production common handler and asserts projection, audit/event, SLA, and rollback effects.
- Failure injection: all 16 production-handler scenarios pass; the Oracle every-effect atomicity
  test remains executable but runtime-blocked by Docker.
- Duplicate/stale: executable handler unit coverage passes; the complete row/version/count Oracle
  snapshots compile and remain runtime-blocked.
- Embedded real-engine lifecycle: all 60 embedded tests and all 16 production-handler tests pass.
- Adapter boundary: core now owns persisted authority/backfill; the non-vacuous structural
  ArchUnit rule and all four negative mutation fixtures pass.
- Remote reuse parity: all four JSON record contracts pass; complete database-outcome parity is
  executable in the Oracle integration fixture and is runtime-blocked only by Docker Desktop.

## Final review fix: restartable observation-ledger migration

The initial `CM_APPLIED_ENGINE_OBSERVATION` migration is now safe to resume after Oracle has
auto-committed any DDL prefix. Table creation, each check constraint, the null-tenant authority
index, and the status index are five ordered, one-change changeSets. Each uses an object-specific
precondition, `MARK_RAN` only when that exact object already exists, and `HALT` on precondition
errors. Constraint guards check the current owner, table, name, and check-constraint type.

`AppliedObservationChangelogStaticValidationTest` now parses the full master changelog and proves
that the initial five steps and every later observation schema mutation are granular and guarded.
`AppliedObservationMigrationRestartIntegrationTest` creates an isolated Oracle schema, applies the
real master only up to the observation include, then rehearses two auto-committed partial states:
the table alone, and the table with one constraint plus the function-based unique index. It applies
and reapplies the unchanged master and asserts every final ledger column, named constraint, index,
ordered index column, and all 18 changelog rows.

The parity fixture now orders applied observations by the same null-safe tenant authority and
fingerprint tuple as the uniqueness rule, and scopes webhook deliveries by joining their event to
`CM_EVENT.SUBJECT_ = caseId`. The stale numeric reset-count comment in `OracleTestBase` now describes
the dynamically sized table list.

### Final-fix verification

- Initial static test was red against the bundled five-DDL changeSet (expected one change, found
  five), then passed after the split: 3 tests, zero failures/errors.
- Focused non-Oracle observation contracts: 54 tests passed, zero failures/errors.
- Core reactor package/test compilation:
  `./mvnw -pl case-management-core -am -DskipTests package` passed and built both main and test jars.
- `git diff --check` passed.
- The focused Oracle restart test compiled, but its execution was blocked before any assertion.
  The sandboxed attempt could not access either Unix socket; the required escalated retry reached
  Docker Desktop, but both Testcontainers strategies received HTTP 503 (`Docker Desktop is unable
  to start`). No Oracle runtime result is claimed from this host.

Commit: `fix: make observation migration restartable`.

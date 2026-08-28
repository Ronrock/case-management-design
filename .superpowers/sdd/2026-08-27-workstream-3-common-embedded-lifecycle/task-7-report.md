# Workstream 3 Task 7 Report

Date: 2026-08-28

## Outcome

Task 7 adds executable boundaries and parity contracts around the common lifecycle handler. The
embedded adapter is now mechanically prevented from taking ownership of projection, claim, audit,
event, webhook, or SLA writes. All four observation records have a stored-JSON round-trip
contract, and the Oracle transactional fixture contains a direct-versus-stored comparison of the
complete affected database outcome after volatile identifiers and database timestamps are removed.

The carried Task 6 cancellation findings were fixed first in a separate commit:

- `d952cba` — `fix: preserve embedded cancellation intent`

The Task 7 changes and this report are in the commit named:

- `test: enforce lifecycle handler boundary`

`AGENTS.md` and `RTK.md` do not exist in this checkout. Repository conventions, the complete
workstream plan, Task 7 brief, progress ledger, and Task 6 report were used instead. The progress
ledger was not edited.

## Carried cancellation fixes

### Explicit cancellation intent

Operaton distinguishes ordinary completion from deletion using a nullable engine delete reason.
Passing a null public/domain reason straight through therefore made a bodyless cancellation look
like successful process completion. `EmbeddedEngineGateway` now sends a reserved non-null engine
marker when the caller supplies no reason. The case's domain `cancelReason` remains null. The
marker is engine protocol metadata only and is never included in a business event.

### Narrow reason propagation

For a genuine user reason, `EmbeddedEngineEventBridge` adds only `cancellationReason` to a terminal
`ProcessObservation`. `DefaultEngineObservationHandler` reads that one attribute only when it is
publishing a root `CASE_CANCELLED` event and maps it to the existing public `reason` field. Missing
reason maps to the same empty-string payload already used by the service/remote path. Child
termination, audit-safe metadata, unrelated attributes, and sensitive/arbitrary attributes do not
receive this field.

Coverage includes:

- gateway tests for null marker and non-null reason preservation;
- bridge tests for COMPLETED versus TERMINATED classification, marker suppression, and the one
  whitelisted reason attribute;
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
to forbid dependencies on:

- `CaseProjectionPort` and `JdbcCaseProjectionPort`;
- plan-item, case-task, milestone, applied-observation, audit, event, SLA, and webhook repositories.

Because persisted correlation legitimately reads `CaseRepository` and `LinkedProcessRepository`,
those types cannot be forbidden wholesale. A second ArchUnit condition inspects bytecode method
calls and forbids their projection/business writer methods while allowing authority reads and the
existing narrowly scoped correlation confirmation path. Core handlers are outside the adapter
package and remain allowed to own lifecycle effects.

ArchUnit was added only as a test dependency to `case-management-engine-embedded`.

### Stored-observation parity

`EngineObservationSerializationContractTest` serializes and deserializes process, user-task,
activity, and milestone fixtures with IDs, engine/source/tenant/case/process/entity identity,
revision, millisecond timestamps, enums, nested attributes, lists, booleans, and numeric values.
Each restored record and fingerprint must equal the direct fixture. Jackson's Java-time module was
added only at test scope.

`EngineObservationTransactionalIntegrationTest` now applies the same completed-task fixture in two
ways:

1. directly, inside an outer transaction that captures the database state and rolls back;
2. after JSON storage and restoration, followed by a normal commit.

The comparison covers case, plan-item, task, linked-process, SLA, applied-observation, audit,
event, and webhook-delivery rows. Only generated claim tokens, UUID/sequence identifiers, and
database-generated processing timestamps are excluded. Business fields, versions, identity,
fingerprint, statuses, audit/event JSON, and row counts remain part of the comparison.

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

## Verification evidence

### Green gates

1. Focused cancellation TDD after the initial red run:

   `./mvnw -pl case-management-core,case-management-engine-embedded -am -Dtest=DefaultEngineObservationHandlerTest,EmbeddedEngineGatewayResponseTest,EmbeddedEngineEventBridgeTest -Dsurefire.failIfNoSpecifiedTests=false test`

   Result: 42 tests passed (29 core and 13 embedded).

2. Real production handler and Operaton on H2:

   `./mvnw -pl case-management-spring-boot-starter -am -Dtest=ProductionEmbeddedLifecycleIT -Dsurefire.failIfNoSpecifiedTests=false test`

   Result: 11 tests passed. This includes lifecycle effects, rollback injection, null/non-null
   cancellation, and the case-lock race.

3. Focused core Task 7 contracts:

   `./mvnw -pl case-management-core -Dtest=ArchitectureTest,Ws2ChangelogStaticValidationTest,AppliedObservationChangelogStaticValidationTest,ObservationFingerprintTest,EngineObservationSerializationContractTest,DefaultEngineObservationHandlerTest,DefaultEngineObservationAuthorityValidatorTest,ContractCaseDataMappingServiceTest test`

   Result: 66 tests passed.

4. Complete embedded module after installing the current core snapshot without tests:

   `./mvnw -pl case-management-engine-embedded test`

   Result: 64 tests passed, including real Operaton H2 lifecycle/rollback tests and the new
   ArchUnit boundary.

5. Complete starter module outside the socket-restricted sandbox:

   `./mvnw -pl case-management-spring-boot-starter test`

   Result: 41 tests passed. The same command inside the sandbox first produced two
   `SocketException: Operation not permitted` errors from loopback HTTP fixtures; the escalated
   run proves those were environmental.

6. PoC checks that do not require Oracle:

   `./mvnw -pl case-management-poc-app -Dtest=BpmnComplaintResourcesTest,NoCaseTypeVocabularyTest,PocJwtAuthenticationConverterTest,CrossModuleArchitectureTest test`

   Result: 11 tests passed.

7. Reactor compilation/install without tests:

   `./mvnw -pl case-management-poc-app -am -DskipTests install`

   Result: all seven reactor entries built and installed successfully.

8. `git diff --check` passed before commit.

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

## Completion evidence mapping

- Transaction trace: `ProductionEmbeddedLifecycleIT` exercises real Operaton callbacks through
  the production common handler and asserts projection, audit/event, SLA, and rollback effects.
- Failure injection: all 11 production-handler scenarios pass; the Oracle every-effect atomicity
  test remains executable but runtime-blocked by Docker.
- Duplicate/stale: executable handler unit coverage passes; the complete row/version/count Oracle
  snapshots compile and remain runtime-blocked.
- Embedded real-engine lifecycle: all 64 embedded tests and all 11 production-handler tests pass.
- Adapter boundary: the new non-vacuous ArchUnit dependency and bytecode-call rules pass.
- Remote reuse parity: all four JSON record contracts pass; complete database-outcome parity is
  executable in the Oracle integration fixture and is runtime-blocked only by Docker Desktop.

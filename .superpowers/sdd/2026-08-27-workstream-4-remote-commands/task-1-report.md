# Workstream 4 Task 1 Report

Date: 2026-08-28

Base: `0a7680fa7d23fb199122d1747b4129118435a4db`

## Outcome

Task 1 replaces the PoC-only lifecycle vocabulary with a pure production command policy. It does
not integrate the policy into persistence or dispatch yet. The existing `EngineCommand.Type` enum
is consumed directly so all seven current command types remain source-compatible and later tasks
can adopt the policy without a second parallel type system.

The implementation and this report are in the commit named:

- `feat: define production command lifecycle`

The final commit hash is recorded in the task handoff because a commit cannot contain its own hash.
The Workstream 4 progress ledger was read but not edited.

## Lifecycle model

`EngineCommandStatus` defines the planned production states:

| Status | Meaning | Terminal |
|---|---|---|
| `PENDING` | Accepted and available for an initial dispatch | no |
| `DISPATCHING` | Owned by a dispatcher lease; the remote outcome is not yet classified | no |
| `RETRYABLE` | A new send is permitted because non-application was established | no |
| `AWAITING_CONFIRMATION` | The request may have been applied and must not be blindly resent | no |
| `CONFIRMED` | Matching response or observation proves the effect | yes |
| `FAILED` | Definitive rejection or exhausted safe retries | yes |
| `CONFLICT` | Duplicate/conflict response lacks enough matching evidence | no |
| `MANUAL_REVIEW` | Reconciliation was inconclusive or an operator requested review | no |
| `CANCELLED` | An unsent or definitively absent command was cancelled | yes |

Terminal states are immutable. Matching observations are idempotent in `CONFIRMED`, and repeated
equivalent cancellation is idempotent in `CANCELLED`; no other terminal transition is accepted.

## Outcome matrix

| Outcome | Policy decision |
|---|---|
| Dispatch requested | `PENDING`/`RETRYABLE` to `DISPATCHING` only |
| Failure proven before send | deterministic bounded retry, then `FAILED` at the attempt limit |
| 2xx with matching confirmation evidence | `CONFIRMED` |
| 2xx without confirmation evidence | `AWAITING_CONFIRMATION` |
| 400 or other definitive 4xx | `FAILED` |
| 404 for cancel | `CONFIRMED` because the requested absent state is already true |
| 409 | `CONFLICT` unless represented as a duplicate with matching evidence |
| 5xx, timeout after send, read failure, malformed response | `AWAITING_CONFIRMATION` |
| Duplicate with matching evidence | `CONFIRMED`; otherwise `CONFLICT` |
| Expired dispatch lease | `AWAITING_CONFIRMATION`, never automatic retry |
| Matching observation | `CONFIRMED` from every non-terminal state |
| Reconciliation proves effect | `CONFIRMED` |
| Reconciliation proves absence | deterministic bounded retry, then `FAILED` at the attempt limit |
| Reconciliation inconclusive | `MANUAL_REVIEW` |
| Operator retry/cancel | accepted only after definitive absence; unsent cancellation is narrower |

Every current command type uses the same conservative ambiguity rule. `START_PROCESS` and
`CORRELATE_MESSAGE` therefore cannot be blindly retried after a possibly sent request. Resource-
targeted commands (`CREATE_TASK`, `CLAIM_TASK`, `COMPLETE_TASK`, `CANCEL_PROCESS`) are explicitly
classified for later reconciliation, but resource identity alone never makes a lost-response
retry safe. `DEPLOY_ORCHESTRATION` is retained and receives the same fail-closed treatment.

## Design details

- `CommandDispatchOutcome` models dispatcher, HTTP, reconciliation, observation, lease-expiry,
  and authorised operator evidence without accepting raw response bodies or exception messages.
- Constructor invariants reject impossible evidence/status combinations, such as confirmation on
  an HTTP 400 or definitive-absence evidence attached to a plain HTTP response.
- `EngineCommandPolicy` uses an injected `Clock` and the existing five-step 1 minute, 5 minute,
  25 minute, 2 hour, and 10 hour retry ladder.
- Jitter is deterministic per command ID and attempt, bounded to ±20%, and cannot produce a zero
  or negative delay.
- Policy diagnostics are fixed safe summaries and restricted codes. They are capped at 256
  characters and never ingest remote payloads, credentials, or exception text.
- Attempt zero selects the first backoff delay; attempts 0–4 are retryable when non-application is
  proven, and attempt 5 is exhausted. This preserves the PoC counter/backoff convention for the
  persistence migration in Task 2.

## TDD evidence

The parameterized test was written before production code.

1. Initial RED:

   `./mvnw -pl case-management-core -Dtest=EngineCommandPolicyTest test`

   Test compilation failed because `EngineCommandStatus`, `CommandDispatchOutcome`, and
   `EngineCommandPolicy` did not exist in the PoC model.

2. Fail-closed refinement RED:

   The expanded evidence-invariant test failed because an HTTP 400 could initially be paired with
   confirmation evidence. The outcome constructor was then narrowed to reject that impossible
   pairing.

3. Final GREEN:

   `./mvnw -pl case-management-core -Dtest=EngineCommandPolicyTest test`

   Result: 1,845 tests passed, 0 failures, 0 errors, 0 skipped. This includes the complete
   status × current-command-type × representative-outcome legality grid plus HTTP/evidence,
   terminal, manual-action, confirmation, retry-limit, diagnostic, and backoff assertions.

4. Affected reactor compile/package:

   `./mvnw -pl case-management-core,case-management-engine-remote,case-management-spring-boot-starter -am -DskipTests package`

   Result: all six selected/reactor-required projects succeeded: root, core, embedded, remote,
   REST, and starter.

5. Scope and whitespace:

   `git diff --check`

   Result: clean. Production changes are limited to the three Task 1 policy types; there are no
   persistence, dispatcher, gateway, API, migration, or ledger edits.

## Files

- `case-management-core/src/main/java/org/casemgmt/engine/EngineCommandStatus.java`
- `case-management-core/src/main/java/org/casemgmt/engine/CommandDispatchOutcome.java`
- `case-management-core/src/main/java/org/casemgmt/engine/EngineCommandPolicy.java`
- `case-management-core/src/test/java/org/casemgmt/engine/EngineCommandPolicyTest.java`
- this report

## Review-hardening addendum

The follow-up commit named `fix: bind command outcomes to evidence` supersedes the original
generic-evidence, cancel-404, and attempt-counter statements above. No dispatcher, persistence,
gateway, API, migration, or ledger code is changed by this follow-up.

### Bound evidence

The old `Evidence.CONFIRMED` token is removed. `ConfirmationEvidence` now carries and the policy
matches all of:

- tenant ID, operation ID, command ID, and current `EngineCommand.Type`;
- expected target identity;
- observed remote identity and a command-specific terminal `RemoteState`;
- one exact source (`HTTP_RESPONSE`, `DUPLICATE_RESPONSE`, `OBSERVATION`, or `RECONCILIATION`);
- a bounded safe opaque evidence reference.

Claim, completion, and cancellation evidence additionally require the observed remote identity to
equal the target resource. The allowed terminal states are explicit for every current command
type. Cancellation confirms only with exact `PROCESS_CANCELLED` or `PROCESS_TERMINATED` evidence.
A 404 without that evidence becomes `CONFLICT` when non-acceptance is proven, or
`AWAITING_CONFIRMATION` when acceptance is uncertain; definitive absence authorises
reconciliation/retry, never confirmation.

`ReviewEvidence` is independently bound to the same tenant/operation/command/type/target identity.
Operator actions also carry those bindings plus a safe action ID, audit reference, stable action
time, and an explicit automatic-attempt-cap override flag. This prevents a valid confirmation,
absence review, or operator approval from being replayed against a different command.

Every matching confirmation source is idempotent after `CONFIRMED`, regardless of which source
confirmed first. Evidence validation runs before the terminal-state no-op, so mismatched tenant,
operation, command, type, target, remote identity, remote state, or source is rejected even after
the command is already terminal.

### Transport and HTTP classification

Transport failures now expose their request phase:

- proven pre-connect and zero-byte pre-send failures are the only transport outcomes eligible for
  automatic retry;
- mid-write failures, timeouts, read failures, and unknown failures are `POSSIBLY_SENT` and enter
  `AWAITING_CONFIRMATION`.

HTTP status is separate from acceptance: `PROVEN_NOT_ACCEPTED`, `POSSIBLY_ACCEPTED`, or
`ACCEPTED`. Only proven non-acceptance can schedule a retry. Accepted or possibly accepted
responses without exact confirmation evidence await confirmation. Definitive validation and
authorization rejections fail; 409 conflicts; and 408/425/429 or 5xx retry only when the caller
has proven non-acceptance. A safe `Retry-After` is honoured as a lower bound, capped at 30 days;
otherwise the deterministic bounded jitter ladder applies.

### Attempts, manual actions, and scheduling

`totalDispatchAttempts` now has one definition: the number of dispatches that have started,
including the request currently being classified. Entering `DISPATCHING` increments it. Five
automatic retries follow the initial attempt, so `MAX_AUTOMATIC_ATTEMPTS` is six; this is separate
from an audited operator override. An override is accepted only when the automatic budget is
exhausted, resets the automatic counter explicitly, and is replay-idempotent from the resulting
`RETRYABLE` state. Ordinary retry/review/reconcile/cancel operator actions are also replay-
idempotent and retain their action/audit identities in the policy decision.

Retry calculation is overflow-safe. Both calculated delay and bounded `Retry-After` saturate at
the Oracle-persistable UTC timestamp `9999-12-31T23:59:59.999999Z` rather than overflowing or
wrapping.

### Follow-up TDD and verification

The review suite was written before changing the production policy and first failed compilation
against the generic evidence API. Additional red-green cycles proved command binding for operator
actions and replay-idempotence after an attempt-cap reset.

`./mvnw -pl case-management-core -Dtest=EngineCommandPolicyTest test`

Result: 1,303 tests passed, 0 failures, 0 errors, 0 skipped. The suite uses literal expected
decisions rather than policy-derived allowed sets. It covers every status × every current command
type × every outcome kind, exact status/diagnostic/retry timestamps, all 4 × 4 confirmation-source
order permutations, every command-specific terminal state, binding mismatches before and after
confirmation, HTTP acceptance/status combinations, transport phases, operator replays, attempt
limits, Retry-After, and timestamp saturation.

`./mvnw -pl case-management-core,case-management-engine-remote,case-management-spring-boot-starter -am -DskipTests package`

Result: all six selected/reactor-required projects succeeded: root, core, embedded, remote, REST,
and starter.

## Durable decision-provenance addendum

The follow-up commit named `fix: preserve command decision provenance` replaces the transient
status/counter input with a `CommandState` containing the complete last committed `Decision`.
This remains a pure policy-model change: no dispatcher, persistence, gateway, API, migration, or
ledger code is changed.

### Durable terminal and operator replay

- A confirmed decision retains the complete first `ConfirmationEvidence`, including its source,
  safe evidence reference, remote identity/result correlation, and exact terminal remote state.
- A later matching HTTP, duplicate, observation, or reconciliation confirmation returns the
  committed decision unchanged. It cannot replace the first decision time, diagnostic fields,
  evidence source/reference, remote result identity, or either attempt counter.
- Confirmation equivalence always includes tenant, operation, command, command type, expected
  target, remote identity, and terminal state. This preserves result identities for create-task,
  start-process, deployment, and message-correlation commands as well as targeted resources.
- `OperatorAction` now carries an explicit `ActionType`: `MANUAL_REVIEW`, `RECONCILE`,
  `RETRY_OVERRIDE`, or `CANCEL`. Outcome construction rejects the wrong action kind.
- Decisions retain the complete applied action and its audit reference plus any reviewed-absence
  evidence. Only an exact replay is a no-op; reuse of the same action ID with changed kind,
  binding, audit reference, timestamp, override flag, or review evidence fails closed.

### Lifetime and automatic-budget accounting

`totalDispatchAttempts` is now a `long` lifetime counter and is never reset. Each transition into
`DISPATCHING` increments both that lifetime count and `automaticAttemptsInBudget`. The automatic
limit applies only to the latter. An audited `RETRY_OVERRIDE` at exhaustion resets the budget
counter to zero and increments `budgetEpoch`; neither ordinary review nor replay changes the
epoch. Lifetime and epoch overflow are rejected instead of wrapping. Every decision persists the
two counters, epoch, reset marker, decision time, terminal evidence, review evidence, and applied
operator action.

### Round-two TDD evidence

The replacement equality suite was written first. Its RED compile reported the missing
`CommandState`, `ActionType`, and expanded `Decision` model. After implementation:

`./mvnw -pl case-management-core -Dtest=EngineCommandPolicyTest test`

Result: 1,285 tests passed, 0 failures, 0 errors, 0 skipped. The independently authored matrix
covers all 7 current command types × all 9 statuses × 18 outcome scenarios and compares complete
`Decision` values. It additionally covers all 4 × 4 confirmation-source permutations, exact
first-decision preservation, result-identity/state mismatches, every operator action kind and
repackaging failure, lifetime/budget/epoch reset and overflow behavior, all transport phases,
HTTP acceptance and Retry-After, binding mismatches, safe references, and timestamp saturation.

A full core test run compiled all sources and executed 1,683 tests: 1,646 passed, and 37 Oracle
integration test classes errored because no Docker socket was available. No non-Oracle test failed;
the Oracle result is an environment limitation rather than policy-model verification.

`./mvnw -pl case-management-core,case-management-engine-remote,case-management-spring-boot-starter -am -DskipTests package`

Result: all six reactor projects succeeded (root, core, embedded, remote, REST, and starter).

`git diff --check` also completed cleanly.

## Durable-state validation addendum

The follow-up commit named `fix: validate durable command state` closes historical operator replay
and forged-rehydration gaps without changing repositories, dispatchers, gateways, migrations,
external APIs, or the workstream ledger.

### Normalized processed-action history

Every committed `Decision` now retains an immutable, ordered `List<ProcessedAction>`. Each entry is
a persistence-ready normalized record containing:

- a contiguous one-based sequence;
- the complete typed `OperatorAction` (tenant, operation, command, command type, expected target,
  action kind, action ID, audit reference, action time, and override flag);
- optional typed `ReviewEvidence`, permitted only for reviewed retry/cancellation actions and bound
  to the same command identity.

The ledger is append-only and capped at 64 entries for the command lifetime. New operator actions
fail closed when capacity is reached; the policy never evicts an old action and therefore never
loses replay protection. Sequence gaps, duplicate action IDs, duplicate cancellation, unsafe
review/action combinations are rejected, and input lists are defensively copied. This gives Task 2
explicit rows and ordering rather than arbitrary JSON payloads.

Before any operator transition, policy scans the full ledger. An exact action/evidence match
returns the current committed `Decision` unchanged, including after intervening actions, terminal
transitions, or persistence reconstruction. Reusing any historical action ID with a changed kind,
binding, audit reference, time, override flag, or evidence fails closed. Cancellation remains
irreversibly terminal; its exact replay survives preceding actions and reload, while no later new
action is accepted.

### Fail-closed rehydration invariants

`Decision` validates structural provenance, and `CommandState` validates every retained record
against its command identity and command-specific terminal state. In particular:

- `CONFIRMED` requires exact terminal confirmation and no competing action/review provenance;
- `CANCELLED` requires the one applied `CANCEL` action as the last exact history row;
- an applied action kind must match its resulting status;
- a budget reset requires an applied audited `RETRY_OVERRIDE`, definitive operator-reviewed
  absence, zero automatic attempts in the new budget, and a nonzero incremented epoch;
- reconciliation evidence is limited to retry/failed definitive absence or manual-review
  inconclusive outcomes;
- manual review requires review or operator provenance;
- dispatching requires a started lifetime and automatic-budget attempt;
- lifetime/budget/epoch counters, retry timestamps, terminal diagnostics, action history, and
  terminal/nonterminal evidence combinations are checked during construction.

### Round-three TDD evidence

Tests were written before the history and invariant implementation. The first RED compile named
the missing `ProcessedAction`, bounded history, expanded `Decision`, and replay API. Subsequent RED
runs isolated missing sequence validation, bare manual-review provenance, terminal diagnostic
rejection, action/review binding, dispatch counters, and duplicate cancellation.

`./mvnw -pl case-management-core -Dtest=EngineCommandDurableStateTest,EngineCommandPolicyTest test`

Result: 1,370 tests passed, 0 failures, 0 errors, 0 skipped. This includes the prior complete
transition/equality matrix plus 85 durable-state tests covering A-to-B-to-A historical replay,
all action-kind repackaging, terminal cancellation replay, bounded capacity, the full
status-by-provenance family matrix, forged persisted records, and fresh-record action and
confirmation roundtrips.

`./mvnw -pl case-management-core,case-management-engine-remote,case-management-spring-boot-starter -am -DskipTests package`

Result: all six affected/reactor-required modules succeeded. Final `git diff --check` completed
cleanly, and the changed paths remain limited to the pure policy, its tests, and this report.

## Persistence-readiness addendum

The follow-up commit named `fix: make command policy persistence-ready` supersedes the round-three
64-entry action-history limit and closes the remaining durable reconstruction gaps. The scope is
still the pure command model and its tests: no repository, migration, REST API, gateway, or
dispatcher implementation was added.

### Unbounded append-only operator-action ledger

Processed operator actions now use a positive `long` sequence and an immutable append-only list.
The policy no longer rejects a legitimate new action merely because 64 actions already exist, and
it never evicts old replay identities. Sequences must start at one and remain contiguous; sequence
increment overflow fails instead of wrapping. Duplicate action IDs remain fail-closed: an exact
historical action/evidence replay is a no-op, while reusing the ID with different content is
rejected. This shape can be normalized by Task 2 into one uniquely keyed row per command and
action ID while retaining a stable per-command order.

### Audited attempt-counter reconstruction

`budgetEpoch` must now equal the number of retained `RETRY_OVERRIDE` actions that explicitly reset
an exhausted automatic budget. The lifetime attempt counter must equal
`budgetEpoch * MAX_AUTOMATIC_ATTEMPTS + automaticAttemptsInBudget`; multiplication and addition use
overflow-checked arithmetic. Consequently a database row cannot claim extra resets, omit an
audited reset, lower or inflate its lifetime attempts, or wrap the counter without construction
failing. A reset is still accepted only after the current six-attempt budget is exhausted and only
with definitive reviewed-absence evidence.

### Closed persisted diagnostics and canonical timestamps

Persisted diagnostics are limited to the production policy's closed code-to-safe-summary
vocabulary. Both fields must be absent together or match an exact approved pair; arbitrary text,
including credential-shaped text shorter than the old 256-character limit, cannot be rehydrated.
Dynamic HTTP codes are accepted only for the policy's defined status families and retain their
single fixed safe summary.

All decision times, retry times, and operator-action times are normalized during construction to
UTC at Oracle-supported microsecond precision. Values outside years 0001 through 9999 are rejected.
Generated saturated retry times continue to clamp at the same storage bounds. Tests reconstruct
offset/nanosecond input in the form returned by a JDBC round trip and prove that equality and exact
operator replay still hold.

### Truthful migration provenance for historical `DONE`

Historical PoC `DONE` rows are represented by `LegacyConfirmationEvidence`, not fabricated HTTP,
observation, or reconciliation evidence. The migration-only factory binds the old row ID and
migration reference to tenant, operation, command, command type, expected target, old `DONE`
status, migrated time, and retained attempt count. Live confirmation construction explicitly
rejects the `LEGACY_MIGRATION` source, and the legacy evidence constructor is private, preventing
ordinary dispatch outcomes from manufacturing this provenance.

The behavior is covered for all seven command types. A reconstructed legacy terminal decision
retains its original evidence unchanged. Later live evidence is a conservative no-op only for
claim, complete, and cancel commands where the existing remote identity can be proven equal to the
expected target; command types that create a new remote identity reject enrichment because the old
row does not prove equivalence.

### Round-four TDD and verification evidence

Each concern was first expressed as a failing test. The RED stages respectively exposed the old
64-entry rejection, accepted forged epoch/counters, accepted arbitrary diagnostics, retained
nanosecond/offset timestamps, and lacked a legacy evidence model.

`./mvnw -pl case-management-core -Dtest=EngineCommandPolicyTest,EngineCommandDurableStateTest,EngineCommandLegacyMigrationTest test`

Result: 1,394 tests passed, 0 failures, 0 errors, 0 skipped.

`./mvnw -pl case-management-core -Dtest=EngineCommandDurableStateTest,EngineCommandPolicyTest,ExactStartOutboxTest,EngineCommandDispatcherRootConfirmationTest,StartProcessRequestTest,EngineCommandLegacyMigrationTest,StartProcessByKeyRequestTest test`

Result: 1,408 tests passed, 0 failures, 0 errors, 0 skipped. This is the complete relevant
non-Oracle engine gate.

The broader engine-package run executed 1,411 tests. Its three Oracle integration classes could
not start because Docker Desktop returned HTTP 503 (`Docker Desktop is unable to start`) even when
Maven/Testcontainers was rerun with authorized host access. The 1,408 non-Oracle tests passed; the
three errors were container-environment startup errors in `OutboxTransactionalIntegrationTest`,
`EngineCommandDispatcherTest`, and `EngineCommandClaimSafetyTest`, not assertion or compilation
failures.

`./mvnw -pl case-management-core,case-management-engine-remote,case-management-spring-boot-starter -am -DskipTests package`

Result: all six reactor projects succeeded (root, core, embedded, remote, REST, and starter).

## Normalized action-history final addendum

The final follow-up commit named `fix: normalize command action history` supersedes the
round-four in-memory unbounded list. The scope remains the pure command model and tests. Task 2
still owns the normalized action table, repository implementation, and transactional dispatcher.

### Package-isolated legacy migration

The public `EngineCommandPolicy.migrateLegacyDone` factory was removed. Historical `DONE` rows now
enter through the package-private `LegacyDoneCommandMigration` and its package-private,
strongly-typed `LegacyDoneRow`; neither the mapper, row constructor, evidence type, nor old-status
type is exposed as a public runtime API. Task 2 must implement its repository mapper in
`org.casemgmt.engine` (or add a comparably narrow package-local migration component). Ordinary
`CommandDispatchOutcome.ConfirmationEvidence` continues to reject `LEGACY_MIGRATION`, so a live
dispatcher cannot manufacture this terminal provenance.

The old `ATTEMPTS_` value is interpreted as the historical failure/retry count, not total started
dispatches. Migration accepts only 0 through 5, retains that raw count in legacy evidence, and
derives both lifetime and current-budget dispatch counts as `legacyFailureCount + 1` in epoch zero.
The canonical migration timestamp and raw count are bound to the decision and fail closed if a
persisted row is repackaged.

### O(1) normalized action-ledger contract

`Decision` and `CommandState` retain only an `ActionLedgerSummary` plus the action applied by the
current decision, rather than copying historical rows. The summary contains long-valued action
count/contiguous high-water sequence, audited retry-override count, and cancellation count.
Construction checks aggregate ranges, budget epoch against the repository-verified reset count,
cancellation/status consistency, the current action's high-water identity, and exact lifetime
attempt arithmetic. The API documentation states explicitly that row aggregates are not proof of
database history and must be verified by Task 2 under the same transaction.

Operator transitions now require an `AuthoritativeActionLookup` for the incoming action ID:

- `EXACT_MATCH` compares the complete normalized action and review evidence, then returns the
  current decision unchanged even after intervening transitions or reload;
- `CONFLICT` rejects reused identity with different provenance;
- `ABSENT` emits exactly one `ActionAppend` intent containing the expected summary, next
  overflow-checked sequence, normalized row, and resulting summary.

The persistence contract requires Task 2 to atomically insert under unique `(command, actionId)`
and `(command, sequence)` keys and compare-and-update the command from the expected to resulting
summary. Insert/CAS races must reload the command and authoritative lookup and rerun policy. This
keeps transition work O(1), makes large high-water values practical, and avoids silently dropping
replay identities or pretending an aggregate alone proves history.

### Final-round TDD and verification evidence

The first RED legacy run failed compilation on the missing package-isolated mapper, typed legacy
row, raw count, and canonical migration time. A second RED run produced seven provenance failures
when migrated time/count could be repackaged; the final accessibility RED exposed the public old
status type. The normalized-ledger RED run produced 23 compilation errors for the missing summary,
lookup, append, and operator-transition API before those contracts were implemented.

`./mvnw -pl case-management-core -Dtest=EngineCommandDurableStateTest,EngineCommandPolicyTest,ExactStartOutboxTest,EngineCommandDispatcherRootConfirmationTest,StartProcessRequestTest,EngineCommandLegacyMigrationTest,EngineCommandNormalizedActionLedgerTest,StartProcessByKeyRequestTest test`

Result: 1,426 tests passed, 0 failures, 0 errors, 0 skipped. This includes the exhaustive 1,285-case
policy matrix, the complete status/provenance reconstruction matrix, all seven legacy command
types and boundary conversions 0-to-1/5-to-6, public-forgery checks, large high-water O(1)
transitions, A-to-B-to-A reload replay, exact/conflict comparison, sequence overflow, and simulated
insert-race reload semantics.

`./mvnw -pl case-management-core,case-management-engine-remote,case-management-spring-boot-starter -am -DskipTests package`

Result: all six reactor projects succeeded (root, core, embedded, remote, REST, and starter).
Final `git diff --check` completed cleanly.

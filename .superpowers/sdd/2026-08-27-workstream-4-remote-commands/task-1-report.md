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

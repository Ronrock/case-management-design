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

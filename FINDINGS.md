# PoC Findings

Verdicts on the four risks from
`docs/superpowers/specs/2026-07-31-case-management-poc-design.md` §1.1, plus everything the
27-task build found on the way. The exercise's actual deliverable (§1.2 point 3) is this file:
*"A PoC that produces code but no findings has failed at its actual job."*

The findings below are recorded whether or not they are flattering, whether or not they were
fixed, and whether or not the code now works. Several were explicitly ruled **document, do not
fix** by the human partner and are marked as such. Nothing here has been softened because a later
task made the symptom go away.

**What was built:** 6 Maven modules, 32 endpoints under `/case-api/v2`, **351 tests, all green**,
all against real Oracle 23ai via Testcontainers (no H2 anywhere that touches the schema). Per
module: core 225, rest 81, engine-embedded 10, engine-remote 11, starter 7, poc-app 17.

---

## Summary of verdicts

| Risk | Verdict | One-line reason |
|---|---|---|
| R1 — plan-item state machine | **Held, with changes** | The evaluator works; the spec's CMMN subset under-specified containment, cascade-on-end and criteria-vs-autocompletion, and all three gaps produced real bugs. Two defects remain open. |
| R2 — Operaton integration | **Held** | Both gateways execute the identical shared contract, and the one silent divergence found is locked by an assertion proven to fail against the old code. |
| R3 — model-driven contract | **Held for sufficiency, partial by design** | A consumer with zero case-type knowledge drove a case to `CLOSED`. Six contract gaps found; rendering, UX and live-update remain unproven without a UI. |
| R4 — events and federation | **Split: atomicity held, federation did not** | The transactional outbox is proven under rollback. The pull-recovery path silently loses events under concurrency, which is the half a cross-engine index depends on. |

---

## R1 — Plan-item state machine

**Verdict: held with changes.** Fixpoint evaluation after every mutation is the right shape and
does work: the complaint model runs end to end through it in both engine modes, and a definition
loaded from Oracle drives the real `PlanModelInstantiator`/`PlanModelEvaluator` correctly. But the
spec's CMMN subset (`design principles.md` §3.2) defines plan-item lifecycle *states* and says
nothing about containment, cascade on end, or precedence between exit criteria and autocompletion.
Every one of those three silences produced a real, reproduced bug.

### What the spec left out, and what each omission cost

1. **Containment was never specified.** Nested plan items were not gated by their parent stage's
   state at all — a child of a never-started (`ENABLED`) stage activated anyway. The complaint
   model survived only because it duplicates entry criteria onto children. Fixed in Task 9;
   `PlanModelInstantiator.initialItems` also had to be fixed, since it left `parentStageId` null
   and would have made containment inert.
2. **Cascade on end was never specified.** Ending a stage left live children behind: first a
   `COMPLETED` stage with an `ACTIVE` child, then — after the first fix — a `TERMINATED` stage
   whose grandchild stayed `ACTIVE`, because the cascade descended exactly one level. Now a full
   subtree cascade on both termination paths, with a cycle guard on `parentStageId`.
3. **Precedence between exit criteria and autocompletion was never specified.** A restructuring
   flipped it: a stage with a satisfied exit criterion and all children ended reported `COMPLETED`
   instead of `TERMINATED`, silently discarding the exit criterion.

The plan's own rule for stage autocompletion (`canComplete`'s `anyMatch(ended)`) was the direct
enabler of bug 1. It was replaced, on a human ruling, with real CMMN autocomplete semantics: a
stage completes only when no required child is unfinished **and** no child is `ACTIVE`, and
remaining `AVAILABLE`/`ENABLED` children are `TERMINATED` and reported as real `Transition`s so the
service layer persists them and they reach the event stream.

### The iteration cap was never reachable

The 20-iteration fixpoint cap was never hit by a legitimate model, and cannot be: the state machine
is monotone over a fixed item set. It guards only against a future change — such as repetition
moving inside the loop, which an early Task 8 draft actually did. It is not evidence that
convergence is bounded; convergence is bounded by construction, and the cap is insurance against
losing that property.

### Repetition cost more than the spec accounts for, and still does

`PlanModelEvaluator.repeatable()` checks only "the latest instance ended **and** the criteria
hold". A repeatable `MILESTONE` satisfies that again the instant it completes, so:

- `create()` alone yields **two** instances of it, and
- one no-op `update()` yields a **third**.

That is unbounded growth from ordinary API traffic with no malicious input. **The spec's extra
"criteria changed since the last instance" guard is genuinely needed and is not implemented.** The
PoC mitigates with a repetition cap of 500 and a `WARN` at the ceiling — a bound, not a fix. Any
production implementation needs the criteria-transition guard, or repetition must be restricted to
item types where re-firing is meaningful.

### Two R1 defects found and deliberately not fixed

**(a) A probable `PlanModelEvaluator`/`StageCompletion` ordering defect.** `singlePass` builds
`completingStages` from the **pre-round snapshot**, and `claimedForTermination` is derived before
the loop, while `isContained` requires the parent to be `ACTIVE` in that same snapshot. A stage
that auto-completes in the round *after* it activates therefore evaluates its own completion
against a snapshot in which a criteria-admitted child is not yet materialised — and then sweeps
that child to `TERMINATED`. `blockingItems` does not block on a non-required, non-`ACTIVE` child,
so nothing stops it. **This loses any non-required child of a criteria-entered stage.** In the
complaint model `required: true` masks it for the one item that would otherwise hit it. Reported
rather than fixed because the fix is evaluator semantics, not a fixture change, and it arrived in
the last task; the write-up was independently verified against the code by a reviewer.

**(b) `ActionPolicy.listForPlanItem` does not consult `StageCompletion.blockingItems`.** The
case-level `close` action does consult it; the plan-item-level `complete` action does not. So a
stage — or a human task — can be force-completed through
`POST /cases/{id}/plan-items/{itemId}/complete` while its required children are still open. This is
not theoretical: the generic consumer hit it. Its first version completed any plan item that
offered `complete`, force-completed the `assessment` stage while `assessComplaint` was still open,
orphaned the worklist task, and then got a 409 when it later completed that task normally. The
consumer now excludes `STAGE` and `HUMAN_TASK` **by type** to stay out of the way — which means
*the API offered an action that corrupts the case, and the client had to know better*. That is an
R3 problem as much as an R1 one; see below.

### R1 spec changes required

- `design principles.md` §3.2 must specify containment, cascade-on-end for both `COMPLETED` and
  `TERMINATED`, and that a satisfied exit criterion outranks autocompletion.
- The autocompletion rule must be stated as CMMN's ("no required child unfinished **and** no child
  `ACTIVE`"), not as the plan's `anyMatch(ended)`.
- Repetition needs a "criteria changed" guard specified, not just a cap.
- The action projection must be specified as deriving from the same blocking rule the mutation
  enforces, on every surface.

---

## R2 — Operaton integration

**Verdict: held.** Two-mode equivalence is evidenced, not asserted. Both gateways execute the
**identical** shared `EngineGatewayContract` — 10 tests for embedded, 11 for remote (the extra one
is a connection-failure case that is structurally impossible for an in-process engine) — and the
contract lives in core's test-jar so neither module can quietly fork it. This verdict moved during
the build and the movement matters:

> **Task 12, before the fix round:** *"two-mode equivalence is NOT yet evidenced. 9/9 was real but
> incomplete; at least one code path returned materially different data between the modes."*
> **After:** *"two-mode equivalence IS now evidenced."*

The divergence was concrete: `RemoteEngineGateway.findTasks` echoed the **query's** `caseId` into
its results instead of reading the task's real one, so remote returned `caseId=null` for any query
without a `caseId` filter while embedded returned the real value. It is now locked by a contract
assertion that a reviewer independently reproduced failing against the old code
(`expected "eng-a:2" but was: null`).

**A contract test only protects against divergences it actually asserts.** `EngineTaskRef.createdAt`
was declared, plumbed and never asserted — a remote gateway returning `null` for it would have
passed all eight tests. That rule is now written into the contract file itself. This is the single
most transferable R2 lesson: the shared-contract pattern is only as strong as its assertion
density, and an unasserted field is a hole in the shape of that field.

### Standalone-task variable queries: which query form actually worked

`caseId` lands as a **task-local** variable for standalone tasks but as a **process** variable for
process-spawned tasks. A single-predicate query silently returns zero rows for one of the two cases
— silently, because "no tasks" is a legitimate answer. Correlation requires an `or()` over both
forms, and the shared contract had to be extended to cover the process path (`startProcess`
followed by `findTasks(caseId)`), which is exactly the intersection where the bug lived and which
the original contract never crossed.

### What remote mode actually cost

- **Eventual consistency is visible, by design and in practice.** A task exists in `CM_TASK`
  immediately (synchronously, in every mode) but starts `ENGINE_SYNC_ = PENDING` with an **empty**
  `availableActions[]`; only after `EngineCommandDispatcher` drains the outbox over HTTP does it
  flip to `SYNCED` and offer `claim`. The PoC's own remote IT asserts both halves and the negative
  case (dead port → never leaves `PENDING`), plus a wrong-password case that proves the gateway's
  credentials are load-bearing rather than merely present.
- **One extra HTTP round trip per task, forever.** `engine-rest` task DTOs carry no variables, so
  resolving `caseId` costs a `GET /task/{id}/variables/caseId` per task. Listing 50 tasks is 51
  calls. This is a property of Operaton's REST API, not of this design, and it is the strongest
  practical argument against remote mode at scale.
- **Timeouts are not optional and were missing.** The `RestClient` initially had no connect or read
  timeout, so a remote engine that is *up but hung* produces no exception at all and blocks the
  calling thread forever — defeating the outbox's retry-vs-dead-letter decision entirely. Catching
  `RestClientException` is necessary and not sufficient. Both bounds are now set explicitly on the
  production builder.

### What the shared contract structurally cannot enforce

Process-to-case correlation by **business key** is an unenforced convention, not a contract-checked
guarantee: `EngineGateway` exposes no method to query a process instance after start,
`EngineProcessRef` carries no `caseId` or `businessKey`, and core has no `operaton-engine`
dependency, so such a query could not compile there. Task 18 worked around the related
reconciliation problem by threading a `correlationId` (the `CM_LINKED_PROCESS` row id) through the
command payload — because `planItemId` is structurally unusable for an ad hoc process, where it is
legitimately null. Without that, remote-mode linked processes kept a placeholder UUID while the
engine held the real id, and `CM_LINKED_PROCESS` stayed `ACTIVE` forever.

### The starter's own contract contradicted itself

`CaseManagementProperties`' Javadoc documents that `casemgmt.enabled=false` *"leaves a plain
Operaton app completely untouched"*. It did the opposite: **it failed the application at startup.**
`CaseManagementSchedulers` was conditional only on `casemgmt.schedulers.enabled`, unlike its two
sibling configurations which both carry the master switch, and its constructor demanded
`WebhookDispatcher` and `SlaSweeper` as hard, non-`ObjectProvider` dependencies — so with the module
disabled those beans do not exist and the context dies with `NoSuchBeanDefinitionException`.
`RemoteEngineAutoConfiguration` had the same gap, reachable by the ordinary combination of
`casemgmt.enabled=false` left beside a stale `casemgmt.engine.mode=remote`.

This would have fired in **any real consumer application on the default embedded mode** — the one
setting whose entire purpose is "turn this off safely". The existing auto-configuration test could
not see it, because its runner never registered `CaseManagementSchedulers` at all. Fixed by stacking
the master switch onto both classes (verified a genuine AND, not a silent no-op, by checking that
`@ConditionalOnProperty` is `@Repeatable` and that `OnPropertyCondition` builds separate match and
no-match lists over the expanded stream).

**Recorded although it was fixed**, because a library whose off switch breaks the host application
is a contract failure, not a bug: the property's documented behaviour and its implemented behaviour
had been in direct contradiction since the class was written, and prose was the only place the
correct behaviour existed.

### R2 spec changes required

- §3.4 must state that the gateway contract is the equivalence proof and that every declared field
  must carry an assertion, or the contract is decorative in that field.
- §3.5 should state the per-task round-trip cost of remote mode explicitly; it changes the
  deployment calculus.
- Business-key correlation should either be lifted into `EngineGateway` (so the contract can check
  it) or documented as a convention with no enforcement.

---

## R3 — Model-driven contract (partial — no UI)

**Verdict: held for sufficiency; partial by design (spec D7).** `GenericConsumerIT` creates a case
of a type it discovered from `GET /case-definitions`, drives it to `CLOSED`, and asserts every
declared milestone was achieved — with **no case-type constant anywhere in the file**: no case-type
name, no plan-item key, no form-field name. It learns the case type from the definition listing,
navigates by following `href`/`method` verbatim out of `availableActions[]` (never building a
case-shaped URL), and fills forms from JSON Schema **shape** only — `required`, `properties`,
`enum`, `type`, `minimum` — never a property's name. The loop cannot pass by inaction: it must
reach `CLOSED`.

So the contract is *sufficient* to drive a UI. That is the claim §8 obligation 1 makes, and it
holds.

### Where the contract fell short

1. **The offered action set is not always safe to act on.** `ActionPolicy.listForPlanItem` offers
   `complete` on a stage with required children still open (R1 defect (b) above). A generic
   consumer that trusts `availableActions[]` — which is the entire premise — corrupts the case. The
   consumer had to add a type-based exclusion to work around it. **This is the sharpest R3 result
   in the build:** the model-driven contract is only as good as the agreement between what is
   projected and what is legal, and here they disagree.
   `design principles.md` Appendix C names the failure it is guarding against — "the user clicks a
   button that then fails with a 409" — and the actual outcome here is worse than the one it
   names: the button does not fail, it succeeds and corrupts the case.
2. **`PROCESS_TASK` plan items are inert.** Nothing in `TransitionApplier`, `CaseService` or
   `PlanModelInstantiator` reacts to a `PROCESS_TASK` item becoming `ACTIVE`.
   `processDefinitionKey` is parsed, stored on `PlanItemDefinition` and **never read at runtime**.
   The only way to start a process is an explicit `POST /cases/{caseId}/processes`, and completing
   that process does not complete the plan item either. Neither direction of the linkage exists.
   Checked against the plan before writing this: **the plan never specified that a `PROCESS_TASK`
   auto-starts its process**, so this is a design gap, not an implementation failure — but it means
   a generic consumer cannot cause a case's BPMN fragment to run at all. `CaseApiIT` now genuinely
   instantiates `decision-letter.bpmn` through the endpoint that does exist and drives its two user
   tasks to completion on the real engine, so the process is proven to run; nothing proves the plan
   model can trigger it.
3. **Actions carry no human-readable label.** `AvailableAction` is
   `{action, href, method, formKey}` — exactly what design spec §8 obligation 2 requires, so the
   implementation is correct — but a renderer has to invent the button text from the verb.
   `openapi-specs.md` declared a `name` field that nothing emits. Kept declared, marked NOT
   IMPLEMENTED, and recorded here: the first thing a real UI will ask for is a label, and probably a
   localisable one.
4. **`Violation.pointer` was not a JSON Pointer.** networknt 1.5.8 emits JSONPath dot notation by
   default — `$.outcome`, `$.nested.outcome`, bare `$` for a missing required field — while design
   spec §6.5 promises RFC 6901 *"so a renderer can attach messages to inputs without knowing the
   domain"*. `$.outcome` is not valid pointer syntax and no standard resolver parses it. Fixed
   (`PathType.JSON_POINTER`); the exact strings are now pinned, including `""` for a missing
   required field at the document root, which is RFC-correct and is the one most easily corrupted
   into `"/"` or dropped. **It went unnoticed for four tasks because the test asserted
   `pointer().contains("outcome")`, which passes either way.**
5. **A form key with no matching schema silently skipped validation entirely.** A case-definition
   authoring typo produced no signal at all — arguably the exact silent form-contract failure R3
   exists to catch. Now fails loudly.
6. **Two enforced action families are never projected — and the enforcement itself arrived late.**
   `listForCollaboration` and `listForAdministration` gate comment / start-process /
   deploy-definition / subscribe-webhook, but no `GET` body advertises them, so a permitted caller
   can only discover them by being refused something else — the "permitted but never offered" half
   of the projection/enforcement seam. Read that sentence as written and it implies the enforcement
   was always there. **It was not:** all four of those action families, plus SLA pause/resume, had
   *no authorization gate at all* when the controllers first shipped. See "Authorization and
   tenancy" below.

### A contract test that agrees with itself is not a correctness test

`ActionPolicy`'s "list and assertAllowed agree" property is a **consistency** property, not a
**correctness** one. Because `assertAllowedOnTask` derives from `listForTask`, agreement holds
perfectly even when the shared rule is missing an authorization check entirely — and it was:
`listForTask` accepted `callerRoles` and never read it, so a caller with only `watcher`, or with no
roles at all, could list and invoke `claim` on any open synced task. Every task-level test passed
`Set.of("handler")`, so the test blind spot mirrored the code blind spot exactly. **An agreement
test must exercise non-privileged roles or it can never detect a missing check.** All three surfaces
now do.

### Still unproven without a UI

Stated because §1.2 point 3 requires it:

- **Whether the schemas render into something usable.** The PoC proves a schema can be *satisfied*
  programmatically. It proves nothing about whether `ui:widget: textarea` plus a title produces a
  form a back-office worker can work in, or how a 40-field schema behaves.
- **Whether `availableActions[]` maps onto real interaction patterns.** Every action here is a
  single POST with an optional form. Bulk operations, multi-step wizards, optimistic UI, and "what
  happens to my open form when the case changes underneath me" are untouched. So is the missing
  label (gap 3).
- **Whether event-cursor polling is a workable live-update mechanism.** Sequential cursor resume is
  tested and works. But see R4: under concurrent writers the cursor **permanently skips events**, so
  as a live-update mechanism it is not merely unproven, it is known-unsound in its current form.

---

## R4 — Events and federation

**Verdict: split. The atomicity half held and is proven. The federation half did not.**

### The outbox holds under rollback — proven, not asserted

No event, audit row or webhook-delivery row survives a rolled-back change. This was verified by
**mutation testing, repeatedly**: stripping `@Transactional` makes the rollback tests fail, and
restoring it makes them pass, with assertion isolation confirming each table's assertion fails
specifically (`CM_TASK` 0→1 on create and 1→2 on close; `CM_WEBHOOK_DELIVERY` 0→3 and 3→5).
Three-subscription fan-out rolls back cleanly, an orphan delivery row is rejected by
`FK_CM_WHD_EVENT`, and sequence values are never reused after an abort.

That took real work to make true at all:

> **Architectural finding (Task 5).** `case-management-core` had **no transaction manager and no
> transaction boundary anywhere**. Every statement autocommitted. The design's transactional outbox
> (§6.1) requires the case mutation + `CM_EVENT` + `CM_AUDIT_LOG` + `CM_WEBHOOK_DELIVERY` fan-out to
> commit together, and the services carried `@Transactional` — **which is a no-op without a
> transaction manager**. As written, the outbox premise was unimplementable.

Resolved by a human ruling: core gets a real `DataSourceTransactionManager`, chosen over
connection-threading because it is the only option under which embedded mode's engine call can
genuinely join the case transaction — §3.5's central claim for that mode. Related trap confirmed
live: Spring self-invocation (`this.transactionalMethod()`) silently loses transactionality, and
`TestServices`' plain `new` wiring is never proxied, so `@Transactional` was **production-correct
and completely unexercised** until Spring-proxied integration tests were added.

### Retry and DLQ behaviour observed

The webhook dispatcher's five-rung backoff ladder is real and traced (six iterations = five rungs
plus one), signed bytes provably equal wire bytes, and the signature is pinned to RFC 4231 §4.3
case 2 and FIPS 180-4 `SHA-256("abc")` known-answer vectors. That last point earned itself: **the
pre-existing self-consistent signing test passed against uppercase-hex signatures**; only the
published vector caught it. Round-trip tests do not verify wire formats.

Three real defects were found in the dispatcher and fixed:

- **A throwing call outside the try aborted the entire 50-row batch.** The in-memory secret map does
  not survive a restart, so post-restart `secretResolver.apply()` returned null → NPE → escaped both
  `deliver()` and `drainOnce()`. `ATTEMPTS_` never incremented, so rows never retried and never
  dead-lettered; the lease expired, they were reclaimed, and it threw again — an unbounded
  claim/expire/throw loop. A bad row now costs itself one attempt.
- **The claim lease was shorter than the worst-case batch** (300 s lease vs 50 rows × 10 s = 500 s),
  making duplicate delivery the *expected* path rather than a crash-only one, and the three `mark*`
  statements had no `CLAIM_TOKEN_` guard, so a late mark from the original claimer silently
  clobbered the reclaimer's. Now `MAX_CLAIM_BATCH`, `MAX_TIME_PER_DELIVERY` and `CLAIM_LEASE` are
  related by an invariant the dispatcher's **constructor** re-derives and throws on — the numbers
  cannot drift apart again silently.
- **`MAX_RETRIES_` was stored per subscription and never read**, and `require()` scanned the whole
  table in Java per row and ignored `ACTIVE_`. Both fixed on a human ruling, with the deliberate
  semantic stated in Javadoc: deactivating a subscription stops future fan-out, it does not recall
  deliveries already committed to the outbox.

### Would this event stream support a cross-engine index? No — not as it stands

This is the finding that matters most in this section, and it was **ruled DOCUMENT-DO-NOT-FIX by the
human partner**. It is reproduced here in full because it invalidates a named design claim.

**Cursor pagination can permanently skip events.** `CM_EVENT.SEQ_` comes from an Oracle sequence,
taken at insert time, but visibility comes at commit time. Transaction T1 takes `SEQ_=5` and commits
slowly; T2 takes `SEQ_=6` and commits first. A consumer polling `after=0` sees only 6 and advances
its cursor to 6. When T1 finally commits, `SEQ_=5` is forever below the cursor, because
`EventRepository.after` filters `WHERE SEQ_ > :cursor`. **The event is never delivered and the
consumer cannot detect that it is missing.** Verified against real Oracle. Aborted transactions also
leave permanent sequence holes that are indistinguishable from this case, so a consumer cannot even
use gap detection to tell "lost" from "never existed".

**This breaks a named design claim.** `design principles.md` Appendix A argues *"Push for speed,
pull for correctness"* — the pull path is the designated recovery mechanism for consumers that
missed webhooks, and it is precisely the half that silently loses events under concurrency. The
webhook path is *not* affected in the same way: it derives its cursor from the delivery row's own
`EVENT_SEQ_`, written in the same transaction, rather than from an advancing read cursor.

Real fixes are production architecture decisions, not PoC improvisation: a commit-order watermark
(only serve events older than the oldest in-flight transaction), a gap-tolerant cursor that revisits
a trailing window, or serialised appends. All three have costs the PoC has no basis to choose
between. The shape is documented in `EventRepository.after`'s Javadoc as well as here, including an
explicit warning against the tempting `>` → `>=` mis-fix, which does nothing.

**Consequence for R4's stated retirement criterion.** §1.1 says R4 is retired by *"webhook capture
tests incl. forced failure to `DEAD`; a test consumer resuming from a cursor after simulated
downtime."* The first half exists. The second half exists only in its sequential form
(`CaseApiHttpTest` resumes from a returned cursor and gets the later events). **A cursor-recovery
test under concurrent writers was not written, because the mechanism it would test is known to be
wrong.** R4's federation half is therefore not retired; it is diagnosed.

### Other R4 findings

- **`source` was never stamped.** `EventPublisher` stored the `engineId` and never put it on
  `CaseEvent.source`, while design spec §6.2 makes `source = engine-id` a hard rule that federation
  depends on for engine-attributable events. Every test passed the same literal `"eng-a"` for both
  values, which masked that nothing enforced it. Now hard-set at publish time.
- **`GET /events` had no tenant predicate at all**, so it streamed every CloudEvent in the system,
  and `POST /webhooks` took `tenantId` **verbatim from the request body** — meaning any
  authenticated user could register `https://attacker/` against another tenant and receive that
  tenant's case events continuously. Both closed under a human ruling (tenant derives from the
  principal, never the body). It was not flagged by the implementer, and it is the sharpest finding
  on the event and webhook surface — though not the sharpest in the build; see "Authorization and
  tenancy" immediately below, which is where its companion holes are recorded.
- **Delivery-row consistency is guaranteed only by the database foreign key**, not by application
  code.
- **Terminations from the close/cancel sweep used to bypass the event stream entirely** — a plain
  `UPDATE` with no event and no audit row — so a consumer reconstructing history saw plan items
  vanish unexplained. Since the event stream *is* the federation contract, that mattered; the sweep
  now routes through `TransitionApplier`.
- **The sweep still never touches `CM_TASK` rows or the engine.** An optional `ACTIVE` human-task
  item at close or cancel leaves its `CM_TASK` row and the live Operaton task open forever — dead
  work in someone's worklist.

---

## Authorization and tenancy

Grouped together because they share one shape and one cause: **every one of them is a control that
was assumed to exist, was described in prose as existing, and did not exist in code.** All were
found by review rather than by a failing test, because a missing gate produces no error — the
request succeeds. All are fixed; they are recorded because "it works now" is not the finding.

### `/engine-rest` shipped completely unauthenticated

`PocSecurityConfig`'s filter chain ended in `.anyRequest().permitAll()` — the brief's own
configuration, verbatim — while matching only `/case-api/v2/**` as authenticated. **Operaton's
entire REST API was therefore reachable on the running application with no credentials at all.**
Anyone who could reach the port could complete tasks, deploy process definitions and read history
through `/engine-rest`, bypassing every role check, tenant check and `If-Match` precondition the
case API enforces — on an application that also seeds a real `admin` user. The case API's whole
authorization model was, in effect, optional.

It had a second consequence for the PoC's own claims: because the engine required no credentials,
**the remote gateway's basic-auth path was never actually verified.** The `admin/admin` that
`RemoteEngineAutoConfiguration.engineRestClient` sends was accepted because everything was accepted.
A remote-mode test could pass with the credentials wrong, or absent.

**Human ruling: require authentication on `/engine-rest`**, chosen over documenting the hole,
because it closes it *and* converts an untested code path into a tested one — the remote-mode IT
then genuinely proves the credentials work, which is part of what "runs in remote mode" ought to
mean. Both matchers are now `.authenticated()`, and `RemoteModeComplaintIT` gained a wrong-password
test proving the credentials are load-bearing rather than merely present.

**What guards it now, and why that needed its own work.** The wrong-password test proves credentials
are *checked*; it does not guard the matcher, because `BasicAuthenticationFilter` rejects a
malformed-credential `Authorization` header with 401 *before* any authorization rule is consulted.
Revert the matcher to `permitAll` and that test still passes. `CaseApiIT.engineRestRefusesUnauthenticatedRequests`
closes it: an unauthenticated `GET /engine-rest/task` must answer 401, which under `permitAll`
answers 200 instead (verified by reverting the matcher and observing exactly that).

**Fix direction for production:** the deployment should not expose `/engine-rest` at all. Requiring
authentication makes it as strong as the weakest identity in the engine's own user table; it does
not restore the case API's role, tenant and precondition checks, which `/engine-rest` still bypasses
for anyone who *is* authenticated.

### Six mutating endpoints had no authorization gate at all

When the controllers first shipped, `POST /cases/{id}/comments`, milestone achieve, process start,
SLA pause, SLA resume, `POST /webhooks` and `POST /case-definitions` performed **no authorization
check whatsoever**. The demonstration is exact, because the test fixture already contained the right
caller: `carol`, proven by a sibling test to be unable to patch a case's *title*, could comment on
that case, achieve its milestones, start a BPMN process against it, pause and resume its SLA clocks,
and — the sharpest two — **register a webhook and deploy a new version of any case definition in the
system.** A case-definition deploy is not a per-request effect: every future case of that key in that
tenant instantiates whatever was published.

The webhook and case-definition writes were the two nobody flagged; the implementer had raised the
first four. Deploying also crossed tenants — the fixture's `dave`, described as a **tenant t2**
administrator, could publish into t1, because `deploy` read `tenantId` from the request document.

**Human ruling:** extend `ActionPolicy` properly with real rules following the existing shape, with
agreement tests that exercise **non-privileged roles** — the method that had caught `ActionPolicy`'s
own earlier Critical. Plus: scope `GET /cases` to the caller's tenant, extended by controller
decision to `POST /webhooks`, `GET /events` and `GET /webhooks` on the same principle (**derive the
tenant from the principal, never from the body**). `deploy`'s tenant is now a parameter and the
document's value is ignored.

This is the companion to the cross-tenant exfiltration recorded under R4 above: same root cause,
same ruling, different surface.

### The least-privileged user got the broadest worklist

`CaseTaskRepository.worklist(null, List.of(), limit)` dropped the group clause **entirely** and
returned every `OPEN`/`CLAIMED`/`SYNCED` task in the system — **across tenants**. That is not a
pathological call: it is precisely what `CaseTaskService.worklist` issues, since the assignee
argument is always null there and `actor.groups()` is legitimately empty for a user who belongs to
no group. **So the fewer groups you were in, the more you could see, and a user in none saw
everything.**

It survived because the shipped tests never exercised the assignee-only, both-present or
neither-present branches — the defect lived entirely in the untested combinations of a four-way
branch. Fixed to short-circuit to an empty list before any SQL is built, with assignee and groups
`OR`ed (they had been `AND`ed, contradicting the `OR` semantics `ActionPolicy` applies to the same
"mine or pickable" question) and full branch coverage added.

**The transferable shape:** *a filter assembled by appending optional predicates degrades to "no
filter" when every predicate is absent, and "no filter" on a security-relevant query means "return
everything".* The safe default for an authorization-shaped filter is deny, and it has to be written
explicitly, before the query is built.

---

## The process finding: eight vacuous mechanisms

This plan produced **eight** mechanisms that passed while the thing they exist to protect could be
deleted. Most were inherited from the plan's own test code. This is recorded as a first-class
finding because it is the most portable thing the exercise learned.

| # | Task | The mechanism | Why it could not fail |
|---|---|---|---|
| 1 | 15 | Rollback test asserting `CM_TASK` empty | The fault was injected inside `engine.createHumanTask`, which runs **before** `tasks.insert` — no row was ever written either way |
| 2 | 16 | `staleVersionsAreRejected` | It completed the item first, so the `illegal-transition` guard fired; the `OptimisticLockException` → `version-conflict` mapping it names was never reached |
| 3 | 19 | `subscriptionsOnlyReceiveTheirSubscribedTypes` | The type filter lives in a different class; no delivery row was ever enqueued. Deleting the dispatcher's entire HTTP block left it green |
| 4 | 19 | The HMAC test | It compared `sign()` against `sign()`. Base64, uppercase hex or a different digest would all have passed — and uppercase hex **did** |
| 5 | 22 | `formViolationsMapTo422WithPointers` | Asserted only status 422 and `containsKey("violations")`. The test named for protecting the RFC 6901 fix through to the wire never looked at a pointer |
| 6 | 24 | Two-tenant definition isolation (self-caught) | It distinguished the tenants by display `name`, but `PlanItem.name` derives from `defKey`, so both tenants produced identical plan items |
| 7 | 25 | The `@Import(TransactionManagerConfig)` test | It asked whether `@Transactional` was *resolvable* on the class — a property of the type hierarchy that holds with or without a transaction manager. It never asserted the bean was **proxied** |
| 8 | 26 | The `manualActivation` discovery branch in `GenericConsumerIT` | A `STAGE`/`HUMAN_TASK` type guard `continue`d before the branch was reachable for any item this case type marks discretionary. The report had quoted it as one of four decisive proofs |

**And the largest instance of all was not a test at all — it was a whole test category.** The root
POM declared a `maven-failsafe-plugin` execution that **no module ever bound**. So `*IT` classes
matched neither surefire's default includes (`**/*Test.java`, `**/*Tests.java`, `**/*TestCase.java`)
nor any active execution: they were compiled, and then **silently skipped, with `BUILD SUCCESS` and
no "Tests run" line at all** — not "0 tests", not a skip notice, nothing to notice. Discovered in
Task 11 and fixed centrally by making surefire run `**/*IT.java` reactor-wide under `test` and
deleting the dead declaration. Tasks 24, 26 and 27 are entirely `*IT`-based and would all have been
affected; **this document's own "351 tests green" claim depends on that fix**, which is why it is
recorded here and not only in `pom.xml`'s comment. It is the same failure as the eight above, one
level up: a mechanism that reports success while protecting nothing, invisible because the report
looks exactly like the report you expect. The generalisation: **a green build is evidence only about
the tests that ran, and the count is the only thing that says which those were.**

**The root cause is one thing.** These tests were written **by inspection** — reasoning about what
*should* be true — without simulating the path that actually produces the assertion. That method
fails specifically where **multiple guards can produce the same observable outcome**: the right
exception type for the wrong reason, an empty table that was never written to, a state reached by a
cause other than the one under test. None of the eight is detectable by reading the assertion. Only
by tracing execution to the assertion point.

**The detection method that worked, and it is cheap.** Ask every reviewer to verify that each
failure-asserting, guard-asserting or absence-asserting test **can actually fail**: strip or invert
the mechanism, re-run, confirm it fails *for the expected reason*, restore, verify byte-identical.
Every one of the eight was caught that way, or by someone applying it to themselves — items 6 and 8
were self-caught by implementers who ran the strip on their own drafts.

**Two corollaries, both learned the hard way:**

- **"I ran it and it didn't reproduce" is only as strong as the isolation your harness actually
  provides.** Task 25's `NoClassDefFoundError` non-reproduction was structurally guaranteed to be a
  non-reproduction: `FilteredClassLoader` calls `super(new URL[0], parent)`, owns zero URLs, and
  therefore never *defines* a class — while `getDeclaredMethods()` resolves parameter types through
  the **defining** loader, which sees the "absent" jar. No in-process harness could produce the
  condition. Adjudicated by `javap` on the actual jars, not by deferring to either party. Had the
  implementer treated non-reproduction as disproof and skipped the restructuring, the defect would
  have shipped.
- **A test asserting a state is satisfiable by any cause that produces that state.** Establish
  attribution, not just outcome. Task 24's authorization tests are the model: each first reads the
  same resource as the same caller and asserts 200 (so a 409 is not routing), pins 401 as a distinct
  answer by its own test (so it is not the filter chain), and asserts the specific problem `code`
  (so it is not a generic container refusal).

**What this task did about it.** Both mechanisms added here carry their own falsifiability:
`OpenApiConformanceIT` pairs every "conforms" assertion with a mutated body that must be
**rejected** (an OpenAPI 3.0 schema with no `required` list accepts `{}`, so a bare "the report is
empty" assertion would have been the ninth), and `CrossModuleArchitectureTest` asserts up front that
its import really covered each of the five modules, because a rule's reach is invisible in its
result.

---

## Enforcement of the headline "no case-type knowledge" claim

**This claim was enforced by a rule that could not see four of five modules, and it was being
violated.**

`ArchitectureTest` lives in `case-management-core`, and `@AnalyzeClasses` scans the **running**
module's classpath. Core cannot depend on `rest`, either gateway, the starter or the PoC app — that
is the point of the module split — so its two rules only ever saw core. The exclusion clauses for
`engine.embedded` / `engine.remote` / `starter` / `poc` were dead text that read as coverage which
did not exist. Worse, the case-type rule matched class **names**
(`haveSimpleNameContaining("Complaint")`), so a `case.getType().equals("complaint")` branch or a
bare string literal sailed straight through. The "first automated enforcement" result after 24 tasks
was true for one module out of five.

Fixed in this task:

- `CrossModuleArchitectureTest` (in `case-management-poc-app`, the one module that pulls in all
  five) runs the same rules over the whole `org.casemgmt` classpath, and asserts one marker package
  per module first. Verified with reach: removing the `engine.embedded` exclusion produces 41
  violations naming `EmbeddedEngineGateway` — a class core's copy structurally cannot see.
- `NoCaseTypeVocabularyTest` scans **source text** of all five non-PoC modules, main *and* test, for
  vocabulary **derived from `complaint-v1.json` itself**, so a new plan item, form or process key is
  covered automatically. Its one documented limitation: only distinctive tokens are enforceable
  (interior capital or hyphen — `registerComplaint`, `closeForm`, `decision-letter`). Single
  lowercase plan-item keys (`intake`, `assessment`, `decision`, `closure`) are ordinary English and
  cannot be distinguished from prose, so a leak of exactly `"intake"` would not be caught.

**It caught real violations immediately.** Four `case-management-core` test files carried case-type
vocabulary as fixture data — `CM_CASE_DEF` rows keyed `'complaint'`, and `"decision-letter"` as a
process key in the collaboration service tests. Present since Tasks 4–18, invisible for 26 tasks,
and a direct violation of the global constraint "`complaint` is permitted only in
`case-management-poc-app`". Renamed to `widget-review` / `letter-process`.

Core's copy of the class-name rule was **removed** rather than duplicated: over core alone it could
never fail, and spelling the case type's own name there was itself the leak it claimed to prevent.
Core keeps the engine-free rule with an honest scope note.

---

## Deviations that must not be inherited

Recorded so the PoC's shortcuts never get mistaken for design decisions. The first four are the
spec's own (§11); the rest were found during the build.

| Deviation | Why it exists | What production needs |
|---|---|---|
| Webhook secrets held in an in-memory map for signing (Task 25) | No secret store in the PoC | A secret store or reversible encryption. The in-memory map is also what made the dispatcher's post-restart NPE possible |
| `CM_ENGINE_COMMAND` and `ENGINE_SYNC_` columns (spec D3) | Remote mode cannot join the local transaction | Fine as a pattern; the columns are PoC-shaped, not target-design |
| Direct writes in Operaton Tasklist bypass the state machine in remote mode (spec D4) | No outbound push channel from Operaton | Either an engine-side listener pushing back, or Tasklist access removed |
| Basic auth on Operaton identity instead of OAuth2 (spec D2) | §7 — configuration, not design | Replace `PocSecurityConfig`; nothing else changes |
| **The idempotency reclaim lease double-executes** | A 5-minute lease on an in-progress key, so a crash does not wedge it forever | An operation that legitimately runs longer than the lease **can be reclaimed by a duplicate, and both callers execute — the exact thing idempotency keys exist to prevent.** Standard lease-mutex trade-off (the Redis `SETNX`+TTL shape). Callers with long-running operations must know. Production needs a lease proportional to the operation, or a fencing token |
| `PATCH` declares `application/merge-patch+json` but does not implement null-clearing | Behaviour change with its own test surface | Real merge-patch semantics |
| `Page` carries no `totalItems`/`totalPages` | Needs a `COUNT` query `CaseRepository` does not have | Additive for every client when added |
| Schedulers assume a single instance | PoC | Webhook and engine-command dispatch use claim-by-`UPDATE` with a lease and are safe; the **SLA sweeper is not** — `dueRecords` has no `ORDER BY`, so two sweepers can take different row orders and deadlock (`ORA-00060`), which surfaces as `DataAccessException`, not `OptimisticLockException`, and therefore aborts the batch the per-record catch exists to protect |
| `ESCALATE` breach action is inert | §2.2 defers the SLA action surface | Only `EMIT_EVENT` is honoured; `BREACH_ACTIONS_JSON_` can name `ESCALATE` and nothing happens |
| `PAUSED_STATES_JSON_` reinterpreted | `db-design.sql:294` documents it as a list of **states**; it is wired as a whitelist of pause **reasons** | Pick one and make the schema comment agree |
| Identity groups are unioned with participant roles into one privilege check | `CallerResolver` | An identity group literally named `owner` or `handler` grants claim/complete on **every** task in **every** case, with no participant row. The Javadoc defers this to "namespace your groups in production" — a deployment convention standing in for a code invariant |
| `GET /case-definitions/{key}/forms/{formKey}` is not tenant-scoped | `formSchema(key, formKey)` has no tenant parameter | Accepted residual: it exposes a JSON-schema field list, not case data, and `GET /case-definitions` *is* scoped so keys are not enumerable. Closing it is a signature change |
| `application-remote.yaml` is never exercised | The remote IT supplies properties directly | The one deliverable representing "remote mode configuration" has no test behind it, and its `base-url` points at nothing |

---

## Spec and DDL defects found

### `openapi-specs.md` — found by `OpenApiConformanceIT`

Every row below was found by validating a **real response from the running application** against the
published document. The first run reported seven distinct mismatches across three endpoints.

| Where | Defect | Fix applied |
|---|---|---|
| `AvailableAction.id` | The field is called `action` in the implementation, in design spec §8 obligation 2, and in every consumer. **A client written from this document would have found no `id` on any response** — and this is the single field a generic client switches on | Renamed to `action` in the spec |
| `AvailableAction.name` | Declared; nothing emits it | Kept, marked NOT IMPLEMENTED. Recorded as R3 gap 3 — a renderer needs a label |
| `Case.outcome`, `Case.version` | Both emitted on every case response, both undeclared. `version` is the **ETag value**, so a spec-driven client could not find the number it needs for the next `If-Match` | Added |
| `Task.state`, `Task.engineSync`, `Task.version` | All three emitted, all undeclared. `engineSync` is a PoC-only addition (D3) and is what tells a client why `availableActions[]` is empty | Added, with `engineSync` marked as the deviation it is |
| `Case.businessKey/assignee/queueId/initiator`, `Task.formKey`, `AvailableAction.formKey` | Declared `type: string`; the API emits explicit `null` | `nullable: true` added |
| `Case.updatedAt` | Declared; `CaseResponse` carries `createdAt` and `closedAt` but no `updatedAt`, though `CM_CASE.UPDATED_AT_` exists and is maintained | Left declared, marked NOT IMPLEMENTED. A client cannot tell when a case last changed |
| The `Page` envelope, all 7 usages | `allOf: [Page, {properties: {items}}]` **has no satisfiable instance under any validator that treats undeclared properties as errors** — subschema 0 rejects `items`, subschema 1 rejects `page`/`pageSize`. It *is* satisfiable under bare JSON Schema, where `additionalProperties` defaults to true, so this is a validator-strictness-dependent defect and not an unconditional one. It matters because strictness is what makes the rest of this table's findings detectable at all: relax it and the undeclared `outcome`, `version`, `state` and `engineSync` fields above go unnoticed. `allOf` and implicit `additionalProperties: false` are mutually hostile in OpenAPI 3.0 | `additionalProperties: true` on `Page` and on each inline subschema. Noted in the spec that a production document should declare concrete per-collection page components, which keeps strictness *and* typing |

Two contract deviations were also resolved **towards** the spec during Task 24, on a controller
decision the human partner was given the reasoning for: the `Page` envelope was adopted over a bare
array (a bare array has nowhere to put a total or a next cursor, and event pagination has a known
cursor flaw where exactly that would go), and `planItemId` was exposed on
`POST /cases/{id}/processes` — a real capability loss, not a style preference, since Task 18 had
already threaded a `correlationId` through the outbox for precisely that correlation.

### `db-design.sql` — the schema's own result is the PoC's strongest positive

**`db-design.sql` executed against real Oracle 23ai with zero defects on the first clean attempt.**
All 25 `CREATE TABLE`s, the foreign-key ordering, the `IS JSON` check constraints and Liquibase's
comment stripping held. Independently corroborated by a reviewer with live probes: the 26-table
count genuinely composed (25 + `CM_ENGINE_COMMAND`), the `sqlFile` changeset verified as actually
executed, and >10 foreign keys, 15+ `IS JSON` constraints, `FK_CM_PI_CASE`, `CM_EVENT_SEQ` and the
`ENGINE_SYNC_` defaults all confirmed present. **The schema design needed no correction at all.**
For a 25-table schema written before any code existed, that is a genuinely strong result and it is
worth saying plainly.

The defects that were found are narrow:

| Where | Defect | Fix applied |
|---|---|---|
| `CM_CASE_DEF.ID_` | Derived as `{key}:{version}` — a primary key composed from a **strict subset** of the columns its own `UNIQUE(KEY_, VERSION_NO_, TENANT_ID_)` constraint spans, while `nextVersion` counts per tenant. Two tenants' first version of one key both minted `widget-review:1` and collided (`ORA-00001`). Net effect: a multi-tenant deployment could host a given case-definition key in exactly **one** tenant | Derivation changed to `{tenant}:{key}:{version}` on a human ruling, at one documented site; per-tenant version numbering kept. No changeset needed — the fix makes the PK **agree** with the pre-existing unique constraint, and `CM_PLAN_ITEM_DEF.CASE_DEF_ID_` / `CM_CASE.CASE_DEF_ID_` are opaque FK copies that are never parsed |
| `db-design.sql:27` comment | Still reads `-- {key}:{version}`; the authoritative file now misdescribes its own primary key | **Deliberately not edited.** That file is executed by Liquibase as a `sqlFile` changeset whose checksum is computed over the file's bytes, comments included, so editing one comment invalidates the already-applied `cm-schema-v1` changeset on every existing database. The alternative, `validCheckSum: ANY`, permanently disables integrity checking on the schema's most important changeset in order to fix a comment. The correction of record is in `db-design.md` (a documentation file Liquibase never reads) and here |
| `db-design.sql:513` | `CM_WEBHOOK_SUB.MAX_RETRIES_ DEFAULT 8` while `WebhookService.DEFAULT_MAX_RETRIES` is 5. Now that the column is actually read, any creation path omitting it retries 8 times against a 5-rung ladder — attempts 5–7 all wait the last rung's 10 hours | Not fixed (same changeset-checksum constraint). Recorded |
| `db-design.sql:294` | `PAUSED_STATES_JSON_` documented as a list of **states**, wired as a whitelist of pause **reasons** | Not fixed. Recorded above under deviations |

**The transferable shape:** *a primary key derived from a subset of the columns its own unique
constraint spans is a latent multi-tenancy bug that stays invisible until cross-tenant writes become
reachable.* It survived 23 tasks because nothing had ever deployed the same key into two tenants. It
failed **loudly** (`ORA-00001`) only because the primary key existed at all; the same mistake on a
non-key column would have been silent duplication.

### Defects in the plan's own SQL, found against real Oracle

The plan's code was written before any of it ran. Five of its statements do not work on Oracle 23ai:

| Symptom | Cause |
|---|---|
| `ORA-02014` | `FETCH FIRST` combined with `FOR UPDATE SKIP LOCKED`. Resolved by nesting order+limit in an inner subquery with `FOR UPDATE SKIP LOCKED` on an outer `WHERE ID_ IN (...)` — and ultimately by claim-by-`UPDATE` with a per-call token, which needs no row lock at all |
| `ORA-01400` | An empty-string sentinel in a `NOT NULL` column: Oracle normalises `''` to `NULL` |
| `ORA-02290` | The `"__IN_PROGRESS__"` idempotency sentinel violates `CK_CM_IDEM_RESP CHECK (RESPONSE_JSON_ IS JSON)`. Replaced with `RESPONSE_STATUS_ = 0` and a NULL body — the only slot the schema leaves, since `RESPONSE_STATUS_` is `NUMBER(3) NOT NULL` |
| `ORA-00900` | Java text blocks strip incidental trailing whitespace per line, so `""" SELECT """ + RECORD_COLUMNS` concatenates to `SELECTID_,...` |
| `ORA-12516` | Not a statement defect: cleanup opened 26 unpooled connections per test **method**, exhausting the listener's protocol handlers with only three test classes. Fixed by pooling and collapsing 26 deletes onto one batched statement on one connection |

---

## Concurrency findings worth carrying forward

Three of these are general shapes, not incidents.

**A versionless write beside a versioned one on the same row is a lost-update generator.** This is
the Task 21 SLA regression, and it is the cleanest illustration in the build of a fix making things
worse. Round 1 made the sweeper's `SLA_STATUS_` write deliberately skip the version bump, to stop an
ordinary user edit from aborting the whole sweep batch. But `CaseRepository.update` still wrote
`SLA_STATUS_` from the in-memory `CaseInstance` on every full-row update. Sequence: a user
transaction reads the case (`NONE`, `VERSION_ = N`) → the sweeper commits `BREACHED` without touching
`VERSION_` → the user's `UPDATE ... WHERE VERSION_ = N` **still matches** and writes `NONE` back. The
breach is lost permanently: the SLA record is already `BREACHED`, so `dueRecords` never re-selects it
and nothing re-derives the column. The window is not narrow — the sweeper holds the row lock for its
whole batch, so a concurrent edit blocks on it and then applies its stale value on release. **Before
that fix the same interleaving failed loudly with a 412; after it, it lost data silently.**
Optimistic locking was what made the conflict visible; removing the version bump to fix one
concurrency bug removed the detection for another. Fixed by dropping `SLA_STATUS_` from
`CaseRepository.update`'s `SET` list.

**`FOR UPDATE SKIP LOCKED` on an autocommit connection is decorative.** Double-claiming was
reproduced by calling `claimDue()` twice with no mark in between: the connection drops the row lock
when the `SELECT` completes, long before the engine call. Two dispatchers, or one rolling restart,
means duplicate remote engine calls. Replaced everywhere with claim-by-`UPDATE` carrying a per-call
token, where safety comes from Oracle re-checking the `UPDATE`'s `WHERE` at apply time.

**Check-then-update is a TOCTOU race even for something as innocuous as a milestone.** Manual
milestone achievement could double-publish under concurrency. Fixed by driving the decision off
`MilestoneRepository.achieve`'s affected-row count. Stress-tested at 8 threads × 10 rounds: exactly
one success, one conflict per loser, one `milestone.achieved` event per round, every time.

**`insert()` was not atomic** — a case definition's row and its N plan-item rows were independent
autocommitted statements, so a failure on item 3 left a definition that `findLatest` then served as
valid, and a case started from it silently lacked a plan item.

**The business calendar deflated due dates.** Overlapping same-day working intervals were
double-counted: `09:00–13:00` plus `11:00–17:00` computed 8 hours of work as ending at 15:00 instead
of 17:00. **Deadlines earlier than the policy specifies is the dangerous direction for a breach
calculation** — it manufactures breaches that did not happen. Overlapping intervals are now rejected
at parse time (abutting ones still compose), which makes the class total. A second deflation path
was found later and closed the same way: an unguarded negative pause shift in SLA resume, which
bypassed the calendar's negative-duration rejection entirely; resume now re-derives the deadline
through the calendar rather than shifting by wall-clock seconds.

---

## Environment findings

Recorded because two of them cost most of a day and neither is discoverable from the error message.

- **Testcontainers 1.21.3 hardcodes Docker API v1.32** in its connectivity-probe strategy, while
  Docker Engine 29.x raised `MinAPIVersion` to 1.40. **Every container start fails regardless of
  daemon health**, with "client version 1.32 is too old" against a perfectly working daemon (upstream
  `testcontainers-java` #11210/#11232). 1.21.4 probes v1.44 first. The root POM pins 1.21.4 as a
  floor with that reason inline.
- **`org.testcontainers:testcontainers` must be pinned directly.** Operaton's internal BOM chain
  overrides it to 2.0.5 while `oracle-free`/`jdbc`/`database-commons` stay on the declared 1.21.x
  line, and 2.0.5 does not publish an `oracle-free` artifact at all — a silent classpath skew.
- **A cached Oracle image can be corrupt in a way `docker pull` does not fix.** `ORA-01578` at file
  #21 block #30952, reproduced three times from fresh containers; the manifest digest matched, so the
  pull was a no-op. `docker rmi` plus a fresh pull resolved it: the damage was in the unpacked layer,
  not the VM disk.
- **`maven-compiler-plugin` needs `<parameters>true</parameters>` explicitly.** Spring MVC resolves
  `@PathVariable`/`@RequestParam` names by reflection when the annotation does not spell one out, and
  without the flag **every handler taking a path variable returns 500**.
  `spring-boot-starter-parent` normally supplies it and this project deliberately has no Boot parent.
- **ArchUnit 1.4.1's bundled ASM cannot parse JDK 26 class files** (major version 70) and logs a
  stack trace per JDK type it tries to resolve. Silenced with
  `resolveMissingDependenciesFromClassPath=false`, which costs nothing here: every rule in this
  repository matches on the name of a *directly* referenced type, read from the importing class's own
  constant pool.

---

## Dependency notes

- **The design spec's stated platform is stale.** §1 says Operaton `2.2.0-SNAPSHOT`, Java 17+,
  Spring Boot 3.5.6. The build uses **Operaton 2.1.3**, which transitively pins **Spring Boot 4.0.7 /
  Spring Framework 7.0.8**, on **Java 21**. Spring Boot 4 is not a drop-in: it relocated
  `DataSourceAutoConfiguration` out of `spring-boot-autoconfigure` into `spring-boot-jdbc`, and split
  Liquibase's auto-configuration into `spring-boot-starter-liquibase` — without which a real app
  silently skips its own schema migration.
- **Jackson generation resolved (Task 1 Step 6): both, strictly separated.** `case-management-core`
  is **Jackson 2 only** (`com.fasterxml.jackson.*`, BOM-managed at 2.21.5). Web-facing modules carry
  both, with **Jackson 3** (`tools.jackson.*`) for Spring Boot 4 HTTP JSON. **Never both in one
  class** — checked per module at review time, and it held throughout; `JsonCodec` carries the rule
  in its own Javadoc.
- `operaton-bom` manages only Operaton's artifact versions. The Spring Boot / Spring / Jackson
  versions arrive by importing `operaton-bpm-spring-boot-starter`'s POM as a BOM, which is the only
  way to get Operaton's actual pinned versions without declaring competing ones.
- The gateway modules are `<optional>true</optional>` in the starter, so they are **not transitive**
  — an application wanting both engine modes must declare both itself.

---

## Deferred items

Roughly 45 minor items were logged as `minor (deferred)` in
`.superpowers/sdd/2026-07-31-case-management-poc/progress.md` across the 27 tasks and are not
repeated here. They are individually small (a misnamed test, an N+1 at PoC volumes, a Javadoc whose
reasoning is right for the wrong reason) and the ledger is their record. The ones with real teeth
have been promoted into the sections above.

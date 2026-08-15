# PoC Findings

Verdicts on the four risks from
`docs/superpowers/specs/2026-07-31-case-management-poc-design.md` §1.1, plus everything the
27-task build found on the way. The exercise's actual deliverable (§1.2 point 3) is this file:
*"A PoC that produces code but no findings has failed at its actual job."*

The findings below are recorded whether or not they are flattering, whether or not they were
fixed, and whether or not the code now works. Several were explicitly ruled **document, do not
fix** by the human partner and are marked as such. Nothing here has been softened because a later
task made the symptom go away.

**What was built:** 6 Maven modules, 33 endpoints under `/case-api/v2`, **387 tests, all green**,
all against real Oracle 23ai via Testcontainers (no H2 anywhere that touches the schema). Per
module: core 244, rest 94, engine-embedded 10, engine-remote 11, starter 9, poc-app 19.

> Counted from an actual `./mvnw clean install`, and reconciled two ways after an earlier figure
> in this document was found to be wrong: surefire's per-module totals equal the sum of its
> per-class reports, and the core figure reconciles against source as 253 `@Test` annotations
> **minus** the 10 in the abstract `EngineGatewayContract` (which has no concrete subclass inside
> core, so they run in the two engine modules instead) **plus** the 1 ArchUnit `@ArchTest` field
> in `ArchitectureTest`, which a `@Test` grep does not see. Both static approximations are wrong
> on their own; that is why the number cited is the run's.

---

## Summary of verdicts

| Risk | Verdict | One-line reason |
|---|---|---|
| R1 — plan-item state machine | **Held, with changes** | The evaluator works; the spec's CMMN subset under-specified containment, cascade-on-end and criteria-vs-autocompletion, and all three gaps produced real bugs. The known evaluator ordering and manual-path defects are fixed. |
| R2 — Operaton integration | **Held** | Both gateways execute the identical shared contract, and the one silent divergence found is locked by an assertion proven to fail against the old code. |
| R3 — model-driven contract | **Held for sufficiency, partial by design** | A consumer with zero case-type knowledge drove a case to `CLOSED`. The main contract gaps around unsafe actions, process-task activation and action labels are fixed; rendering, UX and live-update remain unproven without a production UI. |
| R4 — events and federation | **Substantially fixed** | The transactional outbox is proven under rollback; event cursor ordering, webhook secret durability and DLQ redelivery are structurally closed. |

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

### R1 defects: one shipped documented, one fixed as a single seam

**Restructured in the final whole-branch review.** This section previously read "two R1 defects
found and deliberately not fixed" and listed the evaluator ordering defect alongside
`ActionPolicy.listForPlanItem`'s missing blocking check, as two independent items. That was
incomplete in a way that mattered: manual `terminate` on a stage had the *same* missing cascade and
was not mentioned at all, neither manual path was covered by any test, and — most importantly —
presenting the projection defect on its own invites someone to fix the projection and leave the
enforcement corrupting cases. The second item is now stated as **one finding with three instances**,
below, and has been fixed.

**(a) A `PlanModelEvaluator`/`StageCompletion` ordering defect.** FIXED. `singlePass` built
`completingStages` from the **pre-round snapshot**, while containment required the parent stage to
already be `ACTIVE` in that same snapshot. A stage that auto-completed in the round after it became
active could therefore sweep a non-required child before that child had one round to materialise.
The evaluator now tracks stages activated in the previous round and suppresses autocomplete for
those stages once, so children can enter before leftover-child termination is considered.

**(b) The manual plan-item path bypassed `StageCompletion` entirely — one seam, three instances.**
FIXED in Task 27.

`PlanItemService.transition` checked only that the item belonged to the case and that the source
state was legal. It never consulted `StageCompletion`, and `TransitionApplier.sideEffects` does not
cascade. `ActionPolicy.listForPlanItem` was written the same way — a bare state-transition table,
with no reference to `StageCompletion` at all. Neither is a separate bug: they are the same seam,
written by two tasks that never had to agree with Task 9, seen twice.

`cases.reevaluate` could not repair any of it. `PlanModelEvaluator.singlePass` derives
`cascadeTerminatedIds` only from stages whose **exit criteria** fired and `claimedForTermination`
only from stages **it** decided to complete, and it skips every already-ended item — so a manually
ended stage is invisible to it.

| Instance | What it produced |
|---|---|
| `complete` on a `STAGE` | A `COMPLETED` stage with live `ACTIVE` descendants — verbatim the orphan shape Task 9 spent three review rounds closing on the automatic path |
| `terminate` on a `STAGE` | The identical hole, cascading nowhere, and **recorded nowhere before this review**. Every live descendant orphaned; `AVAILABLE` descendants then **permanently frozen**, because `isContained` refuses entry to a child whose parent is not `ACTIVE` and is consulted only on the `AVAILABLE`→entry edge. If any was `required`, `caseBlockers` blocked close **forever** and the case was wedged with no API path out |
| `enable`/`start` | Neither checked containment. `PlanItemService`'s Javadoc argued `start` needed none, because an item could only reach `ENABLED` under an `ACTIVE` parent — sound for the evaluator, **false** once `enable` is manually invokable on any `AVAILABLE` item regardless of parent state |

The projection half was not theoretical: the generic consumer hit it. Its first version completed
any plan item that offered `complete`, force-completed the `assessment` stage while
`assessComplaint` was still open, orphaned the worklist task, and then got a 409 when it later
completed that task normally. It now excludes `STAGE` and `HUMAN_TASK` **by type** to stay out of the
way — which means *the API offered an action that corrupts the case, and the client had to know
better*.

**Fixed on both surfaces, and enforcement first.** `PlanItemService.assertModelInvariants` requires
`isContained` for `enable`/`start` and an empty `blockingItems` for `complete`; `cascadeTerminate`
sweeps the whole remaining subtree on `terminate`, through `TransitionApplier.apply` so every swept
descendant produces a real transitioned event. `ActionPolicy.listForPlanItem` mirrors the same two
calls, so the API never advertises an action the service then refuses — but the enforcement is in
the service, **not** in `ActionPolicy` alone, because a client that POSTs the URL directly never
reads the projection.

**Why this is also the ninth vacuous mechanism.** The orphan invariant had dedicated
`StageCompletionTest` cases, three review rounds and prominent Javadoc — and every one of those
tests exercised only the pure rules layer. `PlanItemServiceTest` completed and terminated only
*leaf* items. **No test anywhere completed or terminated a `STAGE` with live children through the
service.** See the vacuous-mechanisms section.

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

**And it contradicted itself a second way, found in the final whole-branch review.**
`CaseManagementAutoConfiguration` declared ~40 beans and **not one** carried
`@ConditionalOnMissingBean` — while the repo already had exactly one, on
`EmbeddedEngineAutoConfiguration`'s `EngineGateway`, so the convention was known and applied once.
`CallerResolver`'s Javadoc promises *"a consumer can substitute its own identity mapping without
excluding a component scan"*. As wired they could not: bean-definition overriding is off by default
in Boot 4, so a consumer declaring their own `CallerResolver` got `BeanDefinitionOverrideException`
at startup. Same for `ActionPolicy`, `FormValidator`, `CriterionEvaluator` and `EventPublisher` —
every documented extension point of the library.

Exactly the same shape as the `casemgmt.enabled` defect above: the *only* place the intended
behaviour existed was prose, and the code did the opposite. Fixed for those five, and proven by a
test that supplies its own beans and asserts the context comes up **and** that the bean handed out
is the consumer's own instance — identity, not type, since the substitutes are subclasses and a type
assertion would have passed against the starter's own beans.

**Not fixed, and stated rather than papered over:** the seven controllers and `ProblemDetailHandler`
arrive via `@Import`, which `@ConditionalOnMissingBean` has no bearing on — an imported
`@Component` is not overridable at all. Making them substitutable means converting each to a
`@Bean` method, which changes how every one of them is constructed. That is a restructuring, not a
fix round, and it is deliberately left undone. A consumer who needs a different controller today
must fork it.

### R2 spec changes required

- §3.4 must state that the gateway contract is the equivalence proof and that every declared field
  must carry an assertion, or the contract is decorative in that field.
- §3.5 should state the per-task round-trip cost of remote mode explicitly; it changes the
  deployment calculus.
- Business-key correlation should either be lifted into `EngineGateway` (so the contract can check
  it) or documented as a convention with no enforcement.

---

## R3 — Model-driven contract (partial — UI scaffold only)

**Verdict: held for sufficiency; partial by design (spec D7).** `GenericConsumerIT` creates a case
of a type it discovered from `GET /case-definitions`, drives it to `CLOSED`, and asserts every
declared milestone was achieved — with **no case-type constant anywhere in the file**: no case-type
name, no plan-item key, no form-field name. It learns the case type from the definition listing,
navigates by following `href`/`method` verbatim out of `availableActions[]` (never building a
case-shaped URL), and fills forms from JSON Schema **shape** only — `required`, `properties`,
`enum`, `type`, `minimum` — never a property's name. The loop cannot pass by inaction: it must
reach `CLOSED`.

So the contract is *sufficient* to drive a UI. That is the claim §8 obligation 1 makes, and it
holds. A Lit Web Components shell now exists under `case-management-web-components` with standalone,
IRIS and IB Portal adapters; it is an integration scaffold, not a production workflow UI.

### Where the contract fell short

1. **The offered action set was not always safe to act on.** FIXED. `ActionPolicy.listForPlanItem`
   and the manual plan-item mutation path now share the same containment/blocking semantics, so a
   generic consumer can trust `availableActions[]` without adding case-type-specific exclusions.
2. **`PROCESS_TASK` plan items were inert.** FIXED. When a `PROCESS_TASK` enters `ACTIVE`,
   `TransitionApplier` reads `processDefinitionKey`, starts the linked process through
   `EngineGateway`, records the `CM_LINKED_PROCESS` row and emits `case.process.started`.
3. **Actions carried no human-readable label.** FIXED. `AvailableAction` now emits `name` alongside
   `action`, `href`, `method` and optional `formKey`, giving a generic renderer stable button text.
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
  happens to my open form when the case changes underneath me" are untouched.
- **Whether event-cursor polling is a workable live-update mechanism.** Sequential cursor resume is
  tested and works, and the concurrent-writer skip was fixed by serializing event append sequence
  allocation. Live-update UX remains unproven because no production UI has exercised reconnect,
  optimistic refresh, or duplicate handling.

---

## R4 — Events and federation

**Verdict: substantially fixed. The atomicity half held and is proven; the recovery feed, webhook
secret durability and dead-letter redelivery gaps are now structurally closed.**

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

### Webhook subscriptions now survive application restart

The in-memory `ConcurrentHashMap` shortcut has been removed from the starter. `WebhookService`
persists encrypted signing material through `WebhookSecretStore` in the same transaction as the
subscription row, while `SECRET_HASH_` remains a one-way verification hash. The default starter
store is database-backed and AES-GCM encrypted by `casemgmt.webhooks.secret-encryption-key`; the
dispatcher resolves from that store, so a new process can sign deliveries for subscriptions created
before restart. `WebhookSecretStoreTest` proves a fresh store instance decrypts an existing
subscription secret.

Operational caveat: changing or losing the configured encryption key makes existing encrypted
secrets undecryptable. A bank deployment should hold that key in KMS/Vault or replace
`WebhookSecretStore` with the enterprise secret-store integration.

Dead-lettered deliveries can now be listed and redelivered. Redelivery resets all DEAD rows for the
subscription to PENDING with a fresh retry budget; the next dispatcher run handles delivery.

> **Correction (corrective round).** An earlier version of this paragraph said replay "needs a
> decision about at-least-once-again semantics that nothing in the spec makes." **That is false,
> and it was written without grepping the contract.** Three published documents specify it:
> `openapi-specs.md` defines `POST /webhooks/{webhookId}/dead-letters/redeliver` → `202
> Redelivery scheduled`; `design principles.md` §6 says DLQ entries "can be redelivered";
> `db-design.md` (the `CM_WEBHOOK_DELIVERY` entry) gives the mechanic — "redelivery resets them to
> PENDING", which is the at-least-once-again semantic claimed to be missing, stated exactly. Not implementing redelivery
> is a fine scope decision; calling it unspecified was not. **The accurate statement is: redelivery
	> is fully specified and was deliberately not implemented in the earlier PoC.** It is now
	> implemented by resetting DEAD deliveries to PENDING, resetting attempts, scheduling immediate
	> retry, and clearing claim/failure fields. This became useful only after the secret-durability
	> problem above was solved.
>
> This is the ninth mechanism a third time: the contract was never grepped. See the note added
> to the vacuous-mechanisms section.

### Would this event stream support a cross-engine index? No — not as it stands

This used to be the finding that invalidated the pull-recovery claim: `CM_EVENT.SEQ_` came from an
Oracle sequence allocated before commit, so a higher sequence could commit and become visible before
a lower one. A consumer could then advance past the lower event forever.

Fixed by serialized appends. `EventRepository.append` now takes the single
`CM_EVENT_APPEND_LOCK` row `FOR UPDATE` before `CM_EVENT_SEQ.NEXTVAL`. The lock is held by the
caller transaction until commit, so a second writer cannot allocate a higher event sequence until
the first writer commits or rolls back. Aborted transactions may still leave harmless numeric holes;
they no longer hide a later committed lower sequence. `EventRepositoryCursorSafetyTest` drives two
real Oracle transactions and proves the second append blocks while the first lower sequence is
uncommitted.

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

## The process finding: nine vacuous mechanisms

This plan produced **nine** mechanisms that passed while the thing they exist to protect could be
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
| **9** | **9 / 16 / 24** | **The plan-item orphan invariant** | **The best-defended invariant in the codebase — dedicated `StageCompletionTest` cases, three review rounds, prominent Javadoc — and every one of those tests exercised only the pure rules layer. `PlanItemServiceTest` completed and terminated only *leaf* items. No test anywhere completed or terminated a `STAGE` with live children through the service, so the entire manual path into the protected state was unguarded and untested at once** |

**The ninth is a different shape from the other eight, and it is the one most likely to recur.** The
other eight are tests that could not fail. This one is a test suite that could fail, did its job
perfectly, and *guarded only one of the two doors into the room*. `StageCompletion` was tested
exhaustively as a pure function; `PlanItemService`, a second writer of the exact state those tests
protect, never went through it. Nothing about reading `StageCompletionTest` would reveal that —
the tests are good, the Javadoc is accurate, the invariant is real. What was missing is a question
nobody asked.

**The detection method, and it is as cheap as the strip:**

- **When you write a Javadoc documenting a known limitation, grep every caller of that method.**
  This alone would have caught the `formSchema` defect (Important 1), at any point across the four
  tasks between documenting the limitation and adding the write-path caller.
- **When you protect an invariant with tests, grep every other writer of the state it protects.**
  This alone would have caught all three instances of the plan-item defect (Important 2) the day
  `PlanItemService` was written.

Both are one grep. Neither requires understanding the other task's design. The strip technique
proves *a test can fail*; these two prove *a mechanism is reached from everywhere it needs to be* —
which is the question the strip cannot ask, because a strip only ever exercises the paths the test
already knows about.

**And a third grep, learned the hardest way — by this document doing it wrong.** The fix wave that
closed the eight Importants added `GET /webhooks/{id}/dead-letters` and recorded, in this very
document, that the endpoint "is not in `openapi-specs.md`" and that redelivery "needs a decision
about at-least-once-again semantics that nothing in the spec makes". **Both claims were false.** The
endpoint had been specified all along (`openapi-specs.md`, path `/webhooks/{webhookId}/dead-letters`;
`design principles.md` §6, "DLQ entries are visible via ... and can be redelivered"), and
redelivery is specified in three places including its exact mechanic (`db-design.md`, the
`CM_WEBHOOK_DELIVERY` entry:
"redelivery resets them to PENDING"). Neither statement cost a single grep to check, and neither
grep was run.

That is the ninth mechanism a third time, and its sharpest instance: a *findings document*
asserting what the contract does not say, without reading the contract. The consequence was not
merely an inaccurate note — the endpoint was implemented from the database's columns instead of
from the published schema and diverged from it in six fields, because the schema was never opened.

> **When you write something down about a contract — implementing against it, or recording that it
> is silent — grep it first.** "The spec doesn't cover this" is a claim about a file you can read
> in one command, and it is the single cheapest claim in this whole document to be wrong about.

The corollary for the suite: **a conformance suite is evidence only about the operations it
exercises.** `OpenApiConformanceIT` cannot detect an endpoint the document omits, an endpoint the
document defines and the code never implements, or a response nobody hands it. Adding one case for
the dead-letter queue immediately found two further defects that had been latent for the whole
build — an `openapi-specs.md` field with no backing column, and a `CloudEvent` schema that never
declared the extension attributes the implementation had always emitted.

**And the largest instance of all was not a test at all — it was a whole test category.** The root
POM declared a `maven-failsafe-plugin` execution that **no module ever bound**. So `*IT` classes
matched neither surefire's default includes (`**/*Test.java`, `**/*Tests.java`, `**/*TestCase.java`)
nor any active execution: they were compiled, and then **silently skipped, with `BUILD SUCCESS` and
no "Tests run" line at all** — not "0 tests", not a skip notice, nothing to notice. Discovered in
Task 11 and fixed centrally by making surefire run `**/*IT.java` reactor-wide under `test` and
deleting the dead declaration. Tasks 24, 26 and 27 are entirely `*IT`-based and would all have been
affected; **this document's own "387 tests green" claim depends on that fix**, which is why it is
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

### The plan-level finding: the two largest defects came from plan omissions, not implementation

Worth separating from the list above, because no amount of implementation care would have prevented
either. Both of the final review's two largest findings have the **same shape**: a task did its own
job correctly and honestly, and nothing in the plan ever required it to agree with a neighbour.

- **The plan specified `PlanItemService`'s manual actions as a bare state-transition table** —
  enable/start/complete/terminate with their legal source states — while Task 9 was *simultaneously*
  establishing containment and cascade as model invariants. Neither brief referenced the other. The
  two tasks never had to agree, so they didn't, and both passed review because each was internally
  correct. `ActionPolicy.listForPlanItem` then copied the same bare table a second time.
- **`formSchema`'s limitation was documented honestly at definition time** (Task 5) and never
  revisited when a write-path caller appeared four tasks later (Task 17/24). The Javadoc said
  exactly what the method did; nothing prompted anyone to re-read it when the set of callers
  changed.

Same shape both times: **a correct local decision, invalidated by a later change elsewhere, with no
mechanism to notice.** Review caught neither, three times over, because each task's review looked at
that task's diff.

The remedy is not more review effort per task. It is a plan-level obligation: **when two tasks
touch the same invariant, one brief must name the other**, and a task that adds a caller to a method
carrying a documented limitation must re-read that limitation. The two greps above are the
implementation-level version of the same idea.

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
| Webhook secrets were held in an in-memory map for signing (Task 25) | Historical PoC shortcut | Fixed: `WebhookSecretStore` persists encrypted signing material and the starter wires a database-backed AES-GCM store. Production must manage `casemgmt.webhooks.secret-encryption-key` outside the database. |
| `CM_ENGINE_COMMAND` and `ENGINE_SYNC_` columns (spec D3) | Remote mode cannot join the local transaction | Fine as a pattern; the columns are PoC-shaped, not target-design |
| Direct writes in Operaton Tasklist bypass the state machine in remote mode (spec D4) | No outbound push channel from Operaton | Either an engine-side listener pushing back, or Tasklist access removed |
| Basic auth on Operaton identity instead of OIDC (spec D2) | Local PoC convenience | Fixed for the platform API and remote engine client surface: `PocSecurityConfig` supports `casemgmt.security.mode=oidc` with JWT validation and tenant/group/Worker Permissions claim mapping, and `RemoteEngineAutoConfiguration` supports `auth-mode=bearer` through a `RemoteEngineBearerTokenProvider` or configured bearer token. The local PoC still defaults to Basic Auth only for runnable developer convenience. |
| **Idempotency used to reclaim unfinished work** | Historical crash-recovery shortcut | Fixed by removing automatic duplicate reclaim. `begin` now returns an owner claim token; `complete` and `release` require that token. Duplicate requests against unfinished work receive `409` instead of executing business work. Client errors release their own claim; unknown server faults stay claimed until operational recovery or retention cleanup. |
| `PATCH` declared `application/merge-patch+json` but did not implement null-clearing | Historical contract gap | Fixed: `title: null` clears the title, `variables: null` clears variables, object patches merge recursively, and null variable members remove keys. |
| `Page` carried no `totalItems`/`totalPages` | Historical contract gap | Fixed: `GET /cases` now runs a matching `COUNT` query and returns totals in the `Page` envelope. |
| Schedulers assumed a single instance for SLA sweeping | The SLA sweeper originally selected due rows without claiming them | Fixed: SLA records now use the same claim-by-`UPDATE` pattern as webhook and engine-command dispatch. `CM_SLA_RECORD` carries claim metadata, `claimDueRecords` stamps a bounded batch, and `updateClaimed` requires the claim token |
| `ESCALATE` breach action was inert | Historical SLA action gap | Fixed: `SlaSweeper` emits `case.sla.escalated` and writes `sla.escalate` audit when a breached target declares `ESCALATE`. |
| `PAUSED_STATES_JSON_` was reinterpreted | Legacy column name | Fixed in documentation: the database column remains for compatibility, but schema and API prose now define it as pause reasons. |
| Identity groups were unioned with participant roles into one privilege check | Historical role/group namespace gap | Mitigated structurally: case definitions reject task candidate groups that reuse declared or reserved participant role names such as `owner`, `handler`, `reviewer`, or `watcher`. |
| `GET /case-definitions/{key}/forms/{formKey}` was not tenant-scoped | The endpoint used a form-schema lookup with no tenant parameter | Fixed: the controller now derives the tenant from the authenticated principal and the repository lookup requires the tenant. Write paths continue to resolve schemas by pinned case-definition id |
| `application-remote.yaml` was never exercised | Historical test gap | Fixed: `RemoteModeComplaintIT` now activates the `remote` profile and overrides the remote base URL/credentials through the same property path. |
| Several target-schema tables are created and not yet written to | `db-design.sql` is the full target schema; the PoC implements a subset of it | Deliberate scope, not oversight. The unwritten tables are `CM_BULK_OPERATION`, `CM_BULK_OPERATION_ITEM`, `CM_CASE_LINK`, `CM_DEF_IDENTITY_LINK`, `CM_DOCUMENT`, `CM_QUEUE`, and `CM_SAVED_FILTER`. |

### Resolved form-schema scoping finding

The original `formSchema(key, formKey)` method had two separate problems:

- **Version drift.** A write path once validated task completion against the latest deployed
  definition for a key rather than the in-flight case's pinned definition. That is fixed by
  `formSchemaOfDefinition(caseDefId, formKey)`, which resolves one exact `CM_CASE_DEF` row.
- **Cross-tenant discovery.** The form-rendering endpoint once resolved the latest schema for a key
  across all tenants. That is fixed by `formSchema(key, formKey, tenantId)`, which the controller
  calls only after deriving and validating the tenant from the authenticated principal.

The generalisable lesson remains: when a method documents a known limitation, every new caller has
to be checked against that limitation before the method is reused.

---

## Spec and DDL defects found

### `openapi-specs.md` — found by `OpenApiConformanceIT`

Every row below was found by validating a **real response from the running application** against the
published document. The first run reported seven distinct mismatches across three endpoints.

| Where | Defect | Fix applied |
|---|---|---|
| `AvailableAction.id` | The field is called `action` in the implementation, in design spec §8 obligation 2, and in every consumer. **A client written from this document would have found no `id` on any response** — and this is the single field a generic client switches on | Renamed to `action` in the spec |
| `AvailableAction.name` | Declared before the implementation emitted it | Fixed: actions now carry a human-readable `name` field. |
| `Case.outcome`, `Case.version` | Both emitted on every case response, both undeclared. `version` is the **ETag value**, so a spec-driven client could not find the number it needs for the next `If-Match` | Added |
| `Task.state`, `Task.engineSync`, `Task.version` | All three emitted, all undeclared. `engineSync` is a PoC-only addition (D3) and is what tells a client why `availableActions[]` is empty | Added, with `engineSync` marked as the deviation it is |
| `Case.businessKey/assignee/queueId/initiator`, `Task.formKey`, `AvailableAction.formKey` | Declared `type: string`; the API emits explicit `null` | `nullable: true` added |
| `Case.updatedAt` | Declared before the implementation emitted it | Fixed: `CaseResponse` now carries the maintained `CM_CASE.UPDATED_AT_` value. |
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
| `db-design.sql:27` comment | Originally read `-- {key}:{version}`; the authoritative file misdescribed its own primary key | Fixed in the SQL comments and tracked by schema tests. Existing environments need the normal Liquibase checksum policy decision for already-applied sqlFile changesets. |
| `db-design.sql:513` | `CM_WEBHOOK_SUB.MAX_RETRIES_ DEFAULT 8` while `WebhookService.DEFAULT_MAX_RETRIES` is 5 | Fixed to default 5, with a schema test proving the DDL and runtime ladder agree. Existing environments need the normal Liquibase checksum policy decision for already-applied sqlFile changesets. |
| `db-design.sql:294` | `PAUSED_STATES_JSON_` documented as a list of **states**, wired as a whitelist of pause **reasons** | Fixed in comments/specification: the legacy column stores pause reasons. |

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

---

## The final whole-branch review

A last review over the completed branch found eight Important findings and four contract-level
Minors that **only a whole-system view could see** — every one of the 27 tasks had already passed
its own review, with fix rounds. That is the single most useful data point about the process in this
document: *per-task review, done thoroughly 27 times, does not find cross-task defects*, because
each review looks at one diff. See "the plan-level finding" above.

All eight Importants were fixed. Their individual write-ups are in the sections they belong to; what
follows is the index, plus the three items that changed this document rather than the code.

| # | Finding | Where it is written up |
|---|---|---|
| 1 | `formSchema` resolved unscoped by tenant and unpinned by version on the **write** path | Deviations → "Correction: the `formSchema` row above was materially wrong" |
| 2 | The manual plan-item path bypassed `StageCompletion` (three instances, one seam) | R1 → "R1 defects", item (b) |
| 3 | The starter's extension points were not overridable, contradicting their own Javadoc | R2 → "The starter's own contract contradicted itself" |
| 4 | A failed operation wedged its idempotency key for the full lease | Deviations → idempotency reclaim lease row |
| 5 | Spec §6.4's 48h `CM_IDEMPOTENCY_KEY` retention had no caller; the table grew forever | Fixed in `CaseManagementSchedulers.purgeIdempotencyKeys`, with interval and retention as properties |
| 6 | Restarting the application permanently dead-letters every pre-existing subscription | R4 → its own section |
| 7 | Three Javadocs asserted core has no transaction manager, false since Task 5 — and one of them was load-bearing | Fixed: `CaseDefinitionRepository.insert` now uses `DataSourceUtils` |
| 8 | `SlaSweeper.sweep()` was `@Transactional` over an unbounded, unordered batch | Deviations → schedulers row |

**Three of the twelve changed the document, not the code:**

- **The `formSchema` row was materially wrong** — the one place this document was incorrect rather
  than incomplete. Corrected in full above.
- **The R1 section presented one seam as two independent defects** and omitted the worst of its three
  instances. Rewritten above.
- **The webhook restart failure existed only as three separate ingredients.** The sentence that
  matters — "restarting the application permanently dead-letters every pre-existing subscription,
  unobservably" — appeared nowhere. Added above. For a document whose job is findings, the
  composition *was* the finding.

### The fix wave was itself re-reviewed, and needed a corrective round

The re-review confirmed all eight Importants and the three actionable Minors, and then found that
the wave's own new endpoint had shipped with three problems of exactly the kinds this document
exists to record — plus two false statements *in this document*. Recorded in full because "the fix
wave needed a fix wave" is a real data point about the process, not an embarrassment to bury:

| Found | Where it is written up |
|---|---|
| Two false claims about what the contract says — the endpoint "is not in `openapi-specs.md`" and redelivery is "unspecified". Both were contradicted by documents in this repository | The R4 dead-letter section, and the redelivery correction above it |
| The response diverged from the published schema in six fields, because it was written from the table's columns | Same section |
| `openapi-specs.md` promised a `failedAt` no column backed | Same section — fixed by an additive changeset |
| The `CloudEvent` schema never declared `tenantid`/`cursor`, so every CloudEvent the API returns was non-conformant | "A third spec defect, found by the same test" |
| The new endpoint's admin gate and tenant scoping had no test at all | `CaseApiAuthorizationTest` — a third administration endpoint, with both strips |
| `IdempotencySupport` discarded `complete()`'s new boolean, so the one observable moment of the reclaim double-execute stayed unobserved | Deviations → idempotency reclaim lease row |
| A test comment claiming more than its assertion checked | `AutoConfigurationTest` — reworded |

The single cause behind the first four is one habit, now written up as the third grep in the
vacuous-mechanisms section: **the contract was never opened.**

**A second corrective round then found two more in the same path**, both of which are worth
recording because each is a mechanism that read as protective and was not:

- **The schema published to fix the divergence forbade the nulls the code emits.** `event` was
  declared as a bare `$ref` with a comment beside it reading "nullable on purpose" — and in
  OpenAPI 3.0 **a `$ref` ignores every sibling key**, so the comment was a statement of intent
  with nothing enforcing it. The usual workaround (`{nullable: true, allOf: [$ref]}`) was tried
  next and *also* rejected the null, because the `allOf` branch still evaluates against
  `CloudEvent`'s `type: object`. 3.0 simply cannot express a nullable `$ref`. Resolved by having
  the API **omit** the key instead — unambiguous under any validator, since `event` is optional —
  and by declaring the two plain scalars (`failedAt`, `lastStatusCode`) `nullable: true`, where
  3.0 works correctly. The conformance case now covers both null branches, which the original
  case could not: it exercised only the fully-resolvable row.
- **The `IN`-list finding was right, its stated cause was folklore, and the first test for it was
  vacuous.** "Oracle caps an `IN` list at 1000 expressions (ORA-01795)" is not true of Oracle
  23ai, which raised the ceiling to 65,535 — so the test written at 1,500 ids passed with the
  chunking stripped out and proved nothing. Measured against the real container: 1,500 binds
  succeed unchunked, 70,000 fail. Resized to 70,000, the test genuinely fails without the fix.
  The underlying finding stands, and the more important half of the fix needed no limit at all:
  the response embeds a full CloudEvent per row, so an unbounded queue was an unbounded response
  regardless, and `deadLetters` is now capped at 200.

**And one of the twelve was not a defect at all.** The review recorded as a Minor that
`ProblemDetail.title` is never set and therefore omitted from every problem body. On Spring Framework
7 that is false: `ProblemDetail.getTitle()` falls back to the status reason phrase when the field is
null — confirmed by disassembling `spring-web-7.0.8.jar`, and confirmed on the wire by writing the
proposed `setTitle(...)`, watching the new test pass, stripping it, and watching the test pass again
with identical values. The setter was **dropped rather than kept as belt and braces**: it wrote
exactly what the getter derives and could never change an outcome, which is precisely the ninth
mechanism's shape. The test was kept, because "the body a client receives carries a title" is a real
assertion whoever supplies the value. *Adjudicated against the artifact, not against the reviewer* —
the same method that settled Task 25's `NoClassDefFoundError` dispute.

**`GET /webhooks/{id}/dead-letters` diverged from a contract that already specified it.** An
earlier version of this paragraph said the endpoint "is not covered by `openapi-specs.md`" and that
"the published document does not describe it". **Both statements were false**, written without
grepping the document: `openapi-specs.md`'s `/webhooks/{webhookId}/dead-letters` has defined the
operation — tags, parameters, and a
200 response schema — all along, and `design principles.md` §6 names it too.

The real finding is worse than the one that was recorded, and it is an implementation defect rather
than a documentation gap: **the endpoint was implemented from the table's columns instead of from
the published schema, and diverged from it in six fields at once.** The contract specifies
`{event, attempts, lastError, failedAt}`; the first cut emitted
`{id, webhookId, eventSeq, attempts, lastStatusCode, lastError}` — two documented fields missing,
four undocumented ones added. Nothing caught it because `OpenApiConformanceIT` only validates the
operations it is handed, and no case exercised this path. Corrected: the response now carries
`event` (the full CloudEvent, resolved through a batched `EventRepository.bySeqs`) and `failedAt`,
and `OpenApiConformanceIT` covers the operation against a genuinely populated queue.

`failedAt` required a schema change, and that is a spec defect in its own right: **`openapi-specs.md`
promised a timestamp that `db-design.sql` defines no column for.** `DELIVERED_AT_` is null for a
DEAD row, `NEXT_ATTEMPT_AT_` is stale from the last retry scheduled, and `markDead` clears
`CLAIMED_AT_`. Added as the additive changeset `cm-poc-webhook-delivery-failed-at`, per this
schema's own convention. It is also the field an operator most needs for the restart failure the
endpoint exists to make visible — "did these all die at 09:14?" — and nothing recorded the answer.

Two fields were kept beyond the published four, and `openapi-specs.md` was corrected to declare
them with inline comments naming the reason (the precedent Task 27 set for the `Page` schema):
`id`, because a listing whose entries cannot be named is awkward for operator tooling, and
`lastStatusCode`, because null-versus-a-number is exactly what distinguishes "died before the
request went out" — the restart signature — from an HTTP failure, and that distinction is
unrecoverable once flattened into `lastError` prose. Both are additive and cannot break a client
written against the original four.

### A composition finding: the demo app could not reach a third of its own admin surface

`ActionPolicy` gates three deployment-wide endpoints on the `admin` identity group —
`POST /case-definitions`, `POST /webhooks`, and `GET /webhooks/{id}/dead-letters`. **`PocBootstrap`
seeded no user holding that group.** alice, bob and carol carry `intake`/`handlers`/`reviewers` and
a tenant group; none is an administrator.

So for the entire build, **three of this API's 33 endpoints were unreachable in the one application
whose stated job is to be the runnable demonstration of that API** — not refused by design, simply
unreachable by anybody. Neither half was wrong on its own: the authorization rule is correct and
well tested (in `case-management-rest`, whose own harness *does* seed an admin), and the seeding is
correct for the three case-working personas the PoC scenario needs. The gap exists only in the
composition, and nothing looked at both halves at once — the same shape as the webhook-restart
finding, and the reason that one is written up as a composition too.

It surfaced only because a conformance case needed to call one of those endpoints and could not.
That is worth noting on its own: **an endpoint no test and no seeded user can reach is
indistinguishable from an endpoint that does not work**, and nothing in a green build says which it
is. Fixed by seeding `olivia` (`admin` + `tenant:t1`) — purely additive, no existing caller's
privileges changed. Recorded here rather than only in the task report because a finding that lives
only in a working note is a finding this document was supposed to carry.

### A third spec defect, found by the same test: `CloudEvent` never declared its own extensions

Adding the dead-letter conformance case caused the **first** validation of a CloudEvent against the
published `CloudEvent` schema anywhere in the suite — the two event feeds were never covered. It
failed immediately: `CaseEvent.toCloudEvent` has always emitted a `tenantid` extension attribute,
and `GET /events`/`GET /cases/{caseId}/events` add a `cursor` one, and the schema declared neither.
So **the published schema under-declared the payload the implementation has always sent** —
undetected for the whole build because nothing validated one.

To be precise about how strong that claim is: under OpenAPI 3.0's own semantics an unspecified
`additionalProperties` is *permissive*, so the API was not breaching its contract in a strict
reading — the envelope was legal CloudEvents (1.0 permits extension attributes) and legal against a
permissive reading of the schema. What is unambiguous is that the schema did not describe what the
code sends, and that **under the strict default this project's own conformance tooling uses** — the
same swagger-request-validator behaviour already documented on the `Page` schema — every such
response is rejected. A schema whose own repository's validator rejects the API's real output is
wrong in the way that matters, whichever reading of 3.0 you prefer. Both extensions are now
declared, with a comment explaining that `cursor` appears on the feeds and deliberately not on a
dead-letter entry.

This is the same lesson as the two corrections above, one level down: **a conformance suite is
evidence only about the operations it exercises**, exactly as a green build is evidence only about
the tests that ran.

# Core concepts

Read this before authoring your first case definition. Five ideas cover almost everything.

## Definition vs. instance

A **case definition** is the template — versioned, and immutable once deployed. A **case instance**
is one running case, pinned to the definition version it started on.

Deploying v2 never disturbs cases running on v1. They keep their rules for life. The definition id
is `{tenant}:{key}:{version}`, so two tenants can hold the same key independently.

## Plan items

A **plan item** is one element of work inside a case.

| Type | What it is |
|---|---|
| `HUMAN_TASK` | Work a person does. Mirrored into the engine as a real task with candidate groups and a form. |
| `STAGE` | A container for other items. Completes when its required children are done. |
| `MILESTONE` | A marker with no work attached. Achieved when its criteria hold. |
| `PROCESS_TASK` | Names a BPMN process. **Currently inert** — see [Operations § Known limits](operations.md#known-limits). |

## The lifecycle

Every plan item moves through the same states. Learn these five and the system stops being
mysterious.

| State | Meaning |
|---|---|
| `AVAILABLE` | Exists, but its entry conditions aren't met yet. Nobody can act on it. |
| `ENABLED` | Conditions met, but it needs a human to opt in (`manualActivation`). |
| `ACTIVE` | Live and workable. A human task in this state is in somebody's worklist. |
| `COMPLETED` | Finished normally. |
| `TERMINATED` | Ended without completing — cancelled, or swept when its parent ended. |

`COMPLETED` and `TERMINATED` are both *ended*. An ended item is never reconsidered, except through
repetition (below).

## Criteria (sentries)

An **entry criterion** is a JUEL expression deciding when an item opens. Expressions read other
items through an `items` map and case variables through `vars`:

```
${items.register.state == 'COMPLETED'}
${items.assess.state == 'COMPLETED' && vars.amount > 1000}
```

An **exit criterion** ends an item early when it becomes true.

> **Exit beats completion.** If both could fire in the same pass, the item is `TERMINATED`, not
> `COMPLETED`. Exiting a stage cascades termination through its whole subtree.

Multiple entries in `entryCriteria` are ANDed.

## The evaluation pass

This is the heart of the system. After **every** mutation the service:

1. Takes a snapshot of the case and all its plan items.
2. Evaluates every criterion against that snapshot.
3. Collects the resulting transitions and applies them together.
4. Repeats until nothing more changes.

Criteria are evaluated against a *consistent* view, so two items whose conditions depend on each
other can't observe a half-applied world.

> The same property has a cost: an item admitted during a pass isn't visible to the rest of that
> same pass. That's the cause of one known defect — see [Operations § Known limits](operations.md#known-limits).

## Required, optional and discretionary

| Flag | Effect |
|---|---|
| `required: true` | The parent stage — and the case — cannot close until this is finished. |
| `manualActivation: true` | *Discretionary.* Reaches `ENABLED` and waits for someone to `start` it. |
| `repetition: true` | A fresh instance is created each time the criteria hold again. |

Discretionary items are how you model optional work a handler *may* choose to do — an investigation
that isn't always warranted. Repetition is how you model "and they may do it several times".

## Containment

A plan item with a `parentStageKey` is contained by that stage. Containment is enforced, not
decorative:

- A child cannot become `ACTIVE` unless its parent stage is `ACTIVE`.
- Ending a stage cascades to the entire subtree beneath it, at every depth.
- A stage will not complete while a `required` child is unfinished, or while any child is `ACTIVE`.

If you find a child that never opens, check its parent's state before you check its criteria.

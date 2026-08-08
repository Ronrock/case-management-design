# Writing a case definition

A definition is one JSON document. Deploying it mints a new version; the id is
`{tenant}:{key}:{version}`.

## Top level

| Field | Required | Meaning |
|---|---|---|
| `key` | yes | Stable identifier across versions. |
| `name` | yes | Human-readable label. |
| `tenantId` | no | If present it must equal the caller's tenant. |
| `roles` | no | Case-level role vocabulary, e.g. `owner`, `handler`. |
| `forms` | no | Map of form key → JSON Schema. |
| `slaPolicyId` | no | Attaches SLA targets to cases of this type. |
| `planItems` | yes | The work model. |

## Plan item fields

| Field | Applies to | Meaning |
|---|---|---|
| `defKey` | all | Unique within the definition. **This is what criteria reference.** |
| `type` | all | `STAGE` · `HUMAN_TASK` · `MILESTONE` · `PROCESS_TASK` |
| `name` | all | Display label. |
| `parentStageKey` | all | Nests this item inside a stage. Must resolve, or the deploy is rejected. |
| `sortOrder` | all | Deterministic ordering. Ties break on `defKey`. |
| `required` | all | Blocks parent/case completion until finished. |
| `manualActivation` | all | Discretionary — waits in `ENABLED` for a human. |
| `repetition` | all | Can recur while its criteria keep holding. |
| `entryCriteria` | all | Array of JUEL expressions, ANDed. |
| `exitCriteria` | all | Ends the item early when satisfied. |
| `formKey` | `HUMAN_TASK` | Names a schema in `forms`. Validated on complete. |
| `candidateGroups` | `HUMAN_TASK` | Identity groups that may claim it. |
| `processDefinitionKey` | `PROCESS_TASK` | BPMN process key. *Stored, not yet acted on.* |

## Expression language

Criteria are JUEL, evaluated in a sandbox with no access to arbitrary Java. Two roots:

| Root | Gives you | Example |
|---|---|---|
| `items` | Other plan items by `defKey` | `${items.assess.state == 'COMPLETED'}` |
| `vars` | Case variables | `${vars.amount > 1000}` |

> **Typos fail silently.** A criterion referencing a `defKey` that doesn't exist evaluates against a
> null and simply never fires — no error at deploy time, none at runtime. Check your `defKey`s.
> ([Issue #28](https://github.com/Ronrock/case-management-design/issues/28).)

## Forms

Each entry in `forms` is a JSON Schema. When a task carrying that `formKey` is completed, the
payload is validated against it. A failure returns `422` with RFC 6901 pointers:

```json
{
  "type": "…/form-invalid", "code": "form-invalid", "status": 422,
  "violations": [ { "pointer": "/decision", "message": "is required" } ]
}
```

A pointer of `""` (empty string) means the whole document — that's what a missing required field at
the root produces. It is RFC-correct; don't "fix" it to `"/"`.

A `formKey` naming a schema that doesn't exist fails loudly at completion time rather than skipping
validation.

## Patterns

### Sequential work

The second item waits on the first:

```json
{ "defKey": "register", "type": "HUMAN_TASK", "required": true, "sortOrder": 10 },
{ "defKey": "approve",  "type": "HUMAN_TASK", "required": true, "sortOrder": 20,
  "entryCriteria": ["${items.register.state == 'COMPLETED'}"] }
```

### A milestone marking progress

Milestones carry no work — they record that something became true, and they emit an event:

```json
{ "defKey": "acknowledged", "type": "MILESTONE", "name": "Acknowledged",
  "entryCriteria": ["${items.registerComplaint.state == 'COMPLETED'}"], "sortOrder": 30 }
```

### Optional investigation, repeatable

A discretionary stage a handler may open, containing a task they can run many times:

```json
{ "defKey": "investigation", "type": "STAGE", "name": "Investigation",
  "manualActivation": true, "sortOrder": 60 },

{ "defKey": "investigateAspect", "type": "HUMAN_TASK", "name": "Investigate aspect",
  "parentStageKey": "investigation", "manualActivation": true, "repetition": true,
  "formKey": "investigateForm", "candidateGroups": ["handlers"], "sortOrder": 70 }
```

Neither is `required`, so neither blocks closing. The stage sits in `ENABLED` until somebody starts
it; the task then runs as many times as there are aspects to examine.

### Conditional on a variable

```json
{ "defKey": "seniorReview", "type": "HUMAN_TASK", "required": true,
  "candidateGroups": ["seniors"], "sortOrder": 55,
  "entryCriteria": ["${items.assess.state == 'COMPLETED' && vars.amount > 1000}"] }
```

## Authoring checklist

- [ ] Every `defKey` referenced in a criterion actually exists — spelling included.
- [ ] Every `parentStageKey` resolves to a `STAGE`.
- [ ] Every `formKey` on a `HUMAN_TASK` has a matching entry in `forms`.
- [ ] `candidateGroups` match the identity groups your principals really carry.
- [ ] Work that must happen is `required`; work that's optional is not.
- [ ] Non-required children of stages that are themselves criteria-entered: consider marking them
      `required`, given the known evaluator defect
      ([issue #2](https://github.com/Ronrock/case-management-design/issues/2)).
- [ ] `sortOrder` set on everything, so ordering is deterministic.

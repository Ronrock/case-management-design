# Core concepts

This chapter explains the vocabulary used in the code and API. You do not need previous BPMN,
Camunda, or Operaton experience.

## Case definition and case instance

A **case definition** is a versioned template. A **case instance** is one real occurrence of that
template—for example, complaint `CMP-1042`.

A case instance is pinned to the exact definition version used when it starts. Publishing a newer
version changes future cases, not cases already running.

## What BPMN is

BPMN stands for **Business Process Model and Notation**. It is a standard vocabulary for drawing
executable processes. The saved `.bpmn` file is XML, but developers normally edit it as a diagram
in a modeler.

You need only a few symbols to understand the complaint example:

| BPMN element | Diagram shape | Meaning |
|---|---|---|
| Start event | Thin circle | Where a process instance begins |
| End event | Thick circle | Where a path ends |
| Sequence flow | Arrow | Where the token moves next |
| User task | Rounded rectangle with a person icon | Work that pauses until a person completes it |
| Subprocess | Rounded rectangle containing other elements | A named section of flow; it can be projected as a case stage |
| Intermediate event | Double circle | Something that occurs between start and end; tagged events can become milestones |
| Gateway | Diamond | A decision, split, or join in the path |

### The token mental model

Imagine a token placed on the start event. Operaton moves it along arrows. Automatic elements run
immediately. A user task holds the token until the task is completed. A gateway decides which
arrow is taken. When no root-process token remains because the process reached an end event, the
root process is complete.

This simplified model is enough for ordinary sequential processes. Parallel and multi-instance
models can have several active tokens and repeated activity instances; the projection uses engine
activity-instance and task IDs so each occurrence remains distinct.

## What Operaton is

Operaton is the process engine used by this project. It parses BPMN, creates process instances,
waits at user tasks, correlates messages, stores history, and completes or cancels processes.

If you encounter older material using “Camunda 7” terminology, many engine concepts and BPMN
extension names will look familiar. You do not need Camunda knowledge to use this project: use the
Operaton 2.1 documentation and the case API described here.

Normal application clients should call `/case-api/v2`, not `/engine-rest`. The case API adds the
case contract, tenancy, Worker Permissions, optimistic locking, form validation, audit, events,
and a stable projection that is independent of Operaton Java types.

## Root process and linked processes

Every BPMN-backed case has one **root process instance**. Its ID is stored on the case as
`rootProcessInstanceId`.

The root process owns the case lifecycle:

- Creating the case starts the root process.
- Root-process completion closes the case.
- Cancelling the case cancels the root process.
- Completion or cancellation terminalizes remaining projected and ad-hoc work.

A case can also start linked or ad-hoc child processes. Completing a child process does **not**
close the case. Only the process instance pinned as the root can do that.

## Projection

Operaton and the case API store different views of the same running work. A **projection** is the
case-management representation derived from engine observations.

The projection maps engine data into stable resources:

| Engine observation | Case API projection |
|---|---|
| Root process | Case lifecycle and `rootProcessInstanceId` |
| Tagged subprocess activity | Stage plan item |
| User-task occurrence | Human-task plan item plus task resource |
| Tagged intermediate event | Milestone and milestone plan item |
| Assignment change | Task assignee/state update |
| Root completion | Closed or cancelled case |

Each derived resource reports freshness:

| Field | Meaning |
|---|---|
| `projectionStatus` | `PENDING`, `CURRENT`, `STALE`, or `FAILED` |
| `lastEngineUpdateAt` | Timestamp reported by the engine |
| `lastProjectedAt` | Timestamp when the local projection was updated |
| `engineSync` | Compatibility field on task resources |

Projection writes are idempotent. Observing the same engine task or activity twice updates the
same row instead of creating duplicates.

## Embedded and remote observation

There are two deployment modes:

### Embedded

Operaton and the case library share one JVM and database transaction. The library consumes
Operaton's built-in Spring task, execution, and history events. Engine changes, projections,
domain events, and audit can commit or roll back together.

### Remote

Operaton runs elsewhere and is accessed through stock REST APIs—no custom engine plugin is
required. Commands use a transactional outbox. Observations use overlapping runtime/history polls,
stable fingerprints, idempotent upserts, and periodic full reconciliation.

Remote mode is explicitly eventually consistent. A successful case API command may return `202`
and `PENDING`; the projection becomes `CURRENT` only after REST observation confirms the engine
state. A remote timeline is a history of reconciled observations, not a guaranteed lossless copy
of every internal engine event.

## The three releases and one binding

A BPMN-backed definition version binds three immutable releases:

```text
orchestration release --\
contract release -------> immutable case-definition version -> running case
presentation release --/
```

### Orchestration release

Contains BPMN and optional DMN. It answers “what flow does Operaton execute?”

### Contract release

Contains canonical fields, JSON Schema forms, role and candidate-group vocabulary, SLA bindings,
search profiles, and declarative ad-hoc actions. It answers “what data and actions are valid?”

### Presentation release

Contains the Scenario A manifest interpreted by the Lit shell. It answers “how should already
authorized resources be arranged?” It is not a security boundary; the server masks values first.

### Case-definition version

Binds exact release IDs and SHA-256 hashes after cross-artifact validation. A release is immutable;
publish a new release rather than editing an old one.

## Forms and canonical fields

A BPMN user task carries a symbolic `operaton:formKey`, such as `assessForm`. The contract contains
the matching JSON Schema and an optional `uiSchema`.

On completion:

1. The API reauthorizes the action.
2. Submitted variables are validated against the pinned form schema.
3. A validation failure returns `422 form-invalid` with JSON Pointer violations.
4. Valid variables are passed to Operaton and projected through the case API.

Canonical field IDs prevent each form or component from inventing a different JSON path. The Lit
shell resolves field IDs through the pinned contract and receives only server-authorized values.

## Candidate groups, roles, and permissions

These concepts are related but not interchangeable:

| Concept | Example | Controls |
|---|---|---|
| Tenant authority | `tenant:t1` | Which tenant's resources can be seen |
| Identity/candidate group | `handlers` | Who may discover and claim a BPMN task |
| Case role | `handler` | What a participant may do on one case |
| Worker Permission | `task:complete` plus allowed fields | Whether the API may return a value or execute an action |

The contract declares the vocabulary. A BPMN task referencing an undeclared candidate group is
rejected when a case-definition version is bound.

Missing or empty permission decisions deny access. Rendering an action never authorizes its later
execution; the server reauthorizes every request.

## Stages, milestones, tasks, and discretionary work

- A **stage** is a meaningful group of work. In BPMN, a subprocess tagged
  `casemgmt:stage="true"` projects as a stage.
- A **milestone** records that something important happened. A tagged intermediate event projects
  as an achieved milestone.
- A **task** is actionable work. BPMN user tasks and contract-declared ad-hoc tasks use the same
  task projection.
- **Discretionary work** is allowed by the contract but is outside the BPMN token flow. It can be
  a task, child process, or message action. Root completion terminalizes discretionary work still
  open.

## `availableActions` is the client contract

Case, task, and plan-item responses contain `availableActions[]`. Each action contains a name,
HTTP method, URL, and sometimes a form reference.

Clients should render only those actions. They should not decide that an active-looking task must
be claimable or that an active case must be closable. The server uses the same policy vocabulary
to advertise and enforce actions, and then rechecks authorization at execution time.

## Optimistic locking and idempotency

Mutable resources carry a version and return an ETag. Send that value in `If-Match` when changing
the resource. On conflict, re-read the resource and ask the user to decide again; do not silently
replay an old decision against new data.

Case creation supports `Idempotency-Key`. The remote command outbox also uses stable command IDs
internally so retryable delivery does not intentionally create duplicate effects.

## Legacy plan-model definitions

The original JSON plan model remains supported as `orchestrationMode: PLAN_MODEL`. It contains
plan items, JUEL entry/exit criteria, manual activation, repetition, and explicit case closure.

It is a compatibility path, not something automatically converted into BPMN. The main differences
are:

| BPMN mode | Plan-model mode |
|---|---|
| Operaton decides token flow | Local evaluator decides plan-item transitions |
| Root completion closes case | Explicit close after required work finishes |
| Three independently published releases | One legacy JSON definition document |

The rest of this guide leads with BPMN. Legacy JSON fields are documented in a separate section of
[Writing a case definition](case-definitions.md#legacy-plan-model-json).

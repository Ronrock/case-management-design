# Developer Guide

Documentation for building on the case management library.

**Searchable version:** https://claude.ai/code/artifact/fc27b66e-85fd-4899-8d06-f7f8e0b47087

| Guide | Read it when |
|---|---|
| [Quick start](quickstart.md) | You want a running case in 15 minutes |
| [Core concepts](concepts.md) | Before you write your first case definition |
| [Writing a case definition](case-definitions.md) | You're authoring a case type |
| [API cookbook](api-cookbook.md) | You're writing a client |
| [Operations](operations.md) | You're deploying, integrating or debugging |
| [Search architecture](../search-architecture.md) | You're implementing or integrating search providers |
| [Document management and search](../document-management-and-search.md) | You're linking documents or adding document search/extraction |

Reference material lives elsewhere: [`openapi-specs.md`](../../openapi-specs.md) is the API
contract, [`db-design.sql`](../../db-design.sql) is the schema, [`FINDINGS.md`](../../FINDINGS.md)
records what this implementation proved and what it didn't, and
[`system-overview.md`](../system-overview.md) is the component inventory.

---

## What this is

A **case** is a unit of work that unfolds over time and can't be drawn as a straight line: a
complaint, a claim, an onboarding, an investigation. What happens next depends on what a human
decided a moment ago.

A BPMN process is a poor fit for that, because a process wants to know its path in advance. This
library gives you the other model — **CMMN-style case handling** — while still using the Operaton
process engine for what it's genuinely good at: human task inboxes and well-defined sub-processes.

### The division of labour

| This service owns | The engine owns |
|---|---|
| The case lifecycle and its state machine | Human task inboxes and assignment |
| Which work items exist and when they open | BPMN sub-processes |
| Authorization, tenancy, audit, events | Task claim/complete mechanics |

### Reach for it when

- The order of work is decided at runtime by people, not by a diagram.
- Optional work exists that someone may or may not choose to do.
- You need an audit trail and an event stream over the whole case, not per task.
- Case types must be authored and versioned as data, not shipped as code.

### The one-sentence mental model

> A case definition is a set of work items plus the conditions under which each becomes available;
> the service re-evaluates those conditions after every change and moves items between states
> accordingly.

---

## Architectural principles

Six ideas explain most of the code.

### 1. The model is data, not code

Case types are JSON, deployed at runtime and versioned. No case-type vocabulary appears anywhere in
`core`, `rest` or the gateway modules — an ArchUnit rule enforces it across every module. If you
find yourself adding a case-type branch to a service, the model is missing an expression.

### 2. The API is model-driven

The server tells the client what is possible via `availableActions[]`, and **the same rule that
produces that list also enforces it** — projection and enforcement delegate to one method, so the
API cannot advertise something the service will refuse.

Enforcement additionally lives in the *service* layer, not only in the policy, because a client that
POSTs a URL directly never reads the projection.

### 3. Nothing changes without an event

The domain row, the event and the audit record commit **together** in one local transaction. A
consumer reconstructing history from the event stream sees every transition, including ones swept
automatically when a stage ended.

### 4. Optimistic concurrency everywhere

Every mutable table carries `VERSION_`; every update checks it; zero rows affected means conflict,
never retry. And the returned version is always constructed locally as `version + 1`, never
re-read — a re-read is a second statement a concurrent writer can commit in front of, which would
hand the caller *their* version to use as the next `If-Match`.

> **Corollary, learned the hard way:** a versionless write sitting beside a versioned one on the
> same row is a lost-update generator. One fix during development removed a version bump to solve a
> batch-abort problem and thereby converted a loud `412` into silent data loss.

### 5. The engine is behind one seam

`EngineGateway` is the only place the process engine is visible, and `case-management-core` cannot
even import an engine type. That's what makes two deployment modes a configuration choice rather
than a fork — both implementations pass the same contract test suite.

### 6. Explicit over implicit, loudly

`casemgmt.events.type-prefix` has no default so events can't ship under an accidental namespace.
The business calendar rejects ambiguous input rather than guessing. A missing form schema fails
rather than skipping validation. Where the system can't be sure, it stops.

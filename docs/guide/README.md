# Developer Guide

This guide explains how to run, use, and extend the case-management library. It assumes you know
basic command-line use and HTTP, but it does not assume you know BPMN, Operaton, Camunda, case
management, or the repository.

## Recommended learning path

Read the chapters in this order the first time:

1. [Quick start](quickstart.md) — run the checked-in application and complete a BPMN-backed case
   from start to finish.
2. [Core concepts](concepts.md) — learn the small BPMN and case-management vocabulary used by the
   code and API.
3. [Writing a case definition](case-definitions.md) — author and publish your own BPMN, contract,
   and presentation manifest.
4. [API cookbook](api-cookbook.md) — call the API correctly from a script or application.
5. [Operations](operations.md) — configure embedded or remote Operaton and diagnose failures.

Additional reference material:

| Reference | Use it for |
|---|---|
| [BPMN-first orchestration proposal](../bpmn-first-orchestration-proposal.md) | Architecture and delivery decisions |
| [Declarative UI proposal](../declarative-case-ui-proposal.md) | Scenario A manifest-driven frontend design |
| [Combined ZIP format](../schemas/combined-case-definition-v1.md) | Deployment archive layout and limits |
| [Case-contract schema](../schemas/case-contract-v1.schema.json) | Contract validation |
| [Presentation-manifest schema](../schemas/presentation-manifest-v1.schema.json) | UI manifest validation |
| [`openapi-specs.md`](../../openapi-specs.md) | HTTP contract |
| [`db-design.sql`](../../db-design.sql) | Oracle schema |
| [System overview](../system-overview.md) | Module and component inventory |

## What this project does

A **case** is a long-running unit of work such as a complaint, insurance claim, onboarding, or
investigation. It has data, people, tasks, documents, milestones, SLAs, audit history, and a
lifecycle.

For a BPMN-backed case, an executable BPMN process describes the predictable flow. Operaton moves
a token through that process and owns the engine's process and task state. The case-management
service provides the stable case API around it: tenancy, authorization, contracts, forms,
projections, discretionary actions, collaboration, search, events, audit, and SLAs.

The root BPMN process is the lifecycle boundary:

```text
Create case -> start root process -> perform BPMN work -> root process ends -> case closes
```

You do not call the explicit case `close` action for a BPMN-backed case. The service closes it when
it observes the root process ending. Cancelling the case cancels the root process and terminalizes
remaining work.

## The three artifacts in a BPMN case type

A BPMN case type is not one inseparable deployment. It has three independently published releases:

| Release | Contains | Changes when |
|---|---|---|
| Orchestration | BPMN and optional DMN | The executable flow changes |
| Contract | Canonical fields, forms, roles, candidate groups, SLAs, ad-hoc actions | The case's data and action contract changes |
| Presentation | Scenario A layout manifest | The Lit UI layout changes |

A **case-definition version** binds the exact release IDs and hashes. A running case is pinned to
that immutable version, so a later publication cannot silently change its behavior.

## BPMN orchestration

The project supports one orchestration mode: `BPMN`. Operaton's root process owns lifecycle
decisions and its completion closes the case. Releases must declare `orchestrationMode: BPMN`.

## Division of responsibility

| Operaton owns | Case-management service owns |
|---|---|
| Executing BPMN tokens | Case identity and pinned definition version |
| Runtime and historic process state | Tenant and Worker Permissions enforcement |
| BPMN user-task lifecycle | Stable case/task/plan-item REST projections |
| Message correlation and process cancellation | Contracts, form validation, ad-hoc work, events, audit, SLA, search |

The core module imports no Operaton engine types. Embedded and remote engine adapters translate
engine-specific information into vendor-neutral observations before core stores projections.

## Rules that prevent common mistakes

- Read `availableActions[]` from each response. Do not guess which action is legal or visible.
- Send `If-Match` on mutable resources and handle version conflicts by re-reading.
- Send an `Idempotency-Key` on supported create requests that may be retried. Remote delivery uses
  stable internal command IDs.
- Treat missing or empty permission decisions as deny.
- Treat `projectionStatus: STALE` as a warning that the local view may lag Operaton.
- Do not use Operaton's REST API for normal case work. Use `/case-api/v2`, which enforces the case
  contract, tenancy, forms, audit, and authorization.
- Do not assume a BPMN diagram is only documentation. In a BPMN-backed case, it is executable.

Start with the [expanded quick start](quickstart.md).

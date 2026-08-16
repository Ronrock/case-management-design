# Search Architecture

Search is a reusable platform capability embedded in each domain-owned case application. It gives
workers one consistent way to find cases, tasks, worklist items, document references, timeline
entries and related business references without turning search into the system of record.

The default implementation is projection-first: local Oracle-backed projections and indexes are
used before any external search infrastructure is introduced. External search platforms, enterprise
reference providers and semantic/vector search are extension points, not MVP dependencies.

## Principles

| Principle | Requirement |
|---|---|
| Projection first | Default search uses domain-owned Oracle projections and indexes. |
| Provider based | Each searchable module exposes a `SearchProvider` contract instead of coupling the orchestrator to module internals. |
| Authorization before disclosure | Search may match on sensitive fields only when policy permits returning, counting or highlighting them. |
| Rebuildable indexes | Search indexes are read models and must be rebuildable from reliable events or agreed authoritative state. |
| Explicit freshness | Search results include provider status and warning metadata when projections or providers are stale or partial. |
| Portal neutral | IRIS, IB Portal and standalone shells consume the same Search API and typed client. |
| Optional federation | Cross-domain search is an event-fed projection concern, not central ownership of domain case data. |
| Controlled semantic search | AI/vector search is optional and only over approved indexed content with audit and masking controls. |

## Component Model

```mermaid
flowchart LR
  subgraph Hosts["Frontend hosts"]
    iris["IRIS Retail"]
    ib["IB Portal Wholesale"]
    shell["Standalone shell"]
  end

  component["Search UI / Lit component"]
  client["Generated API client"]
  api["Search API"]
  orchestrator["Search Orchestrator"]
  planner["Query planner"]
  auth["Authorization and field policy"]
  merge["Merge, rank, deduplicate"]

  subgraph Providers["Search providers"]
    cases["Case projection provider"]
    tasks["Task / worklist provider"]
    docs["Document metadata provider"]
    timeline["Timeline/comment provider"]
    enterprise["Enterprise reference provider"]
    semantic["Optional semantic provider"]
  end

  oracle["Oracle DBaaS projections"]
  documentService["Document service"]
  enterpriseServices["Enterprise services"]
  vector["Optional search/vector index"]

  iris --> component
  ib --> component
  shell --> component
  component --> client --> api --> orchestrator
  orchestrator --> planner
  orchestrator --> auth
  planner --> cases
  planner --> tasks
  planner --> docs
  planner --> timeline
  planner --> enterprise
  planner --> semantic
  cases --> oracle
  tasks --> oracle
  timeline --> oracle
  docs --> documentService
  enterprise --> enterpriseServices
  semantic --> vector
  cases --> merge
  tasks --> merge
  docs --> merge
  timeline --> merge
  enterprise --> merge
  semantic --> merge
  auth --> merge
  merge --> api
```

`SearchOrchestrator` owns provider selection, bounded execution, ranking, deduplication, provider
status reporting and warnings. Providers own their own query implementation, source freshness and
result enrichment.

## Cascading Query Flow

Search is planned as a cascade, not a blind fan-out.

```mermaid
sequenceDiagram
  actor User
  participant UI as Search UI
  participant API as Search API
  participant Orchestrator as Search Orchestrator
  participant Planner as Query Planner
  participant Auth as Authorization Policy
  participant Local as Local Projection Providers
  participant External as Optional External Providers
  participant Merge as Result Merger

  User->>UI: Enter query and filters
  UI->>API: POST /search/query
  API->>Orchestrator: Normalize request with tenant context
  Orchestrator->>Auth: Resolve allowed scopes and fields
  Orchestrator->>Planner: Build provider plan
  Planner->>Local: Exact identifier and structured query
  Local-->>Merge: Local matches and freshness
  alt Broader discovery requested
    Planner->>External: Document/reference/semantic providers
    External-->>Merge: Matches or warnings
  end
  Merge->>Auth: Apply final disclosure checks and masking
  Merge-->>Orchestrator: Ranked result set
  Orchestrator-->>API: Results, facets, warnings, provider status
  API-->>UI: Search response
```

Recommended cascade order:

1. Exact identifiers: case id, business key, task id, document id, customer id or account id.
2. Structured filters: case type, state, SLA state, queue, assignee, date range, priority and owner.
3. Approved full-text fields: case title, summaries, comments, timeline labels and document metadata.
4. Relationship expansion: linked cases, duplicate cases, related customers, accounts and documents.
5. Optional semantic search over approved indexed content with audit, masking and explainability.

The planner may stop early for a visible exact case id. It may continue when the UI asks for related
results, facets or broader discovery.

## Provider Contract

The core provider contract is intentionally small:

```text
SearchProvider
  providerId()
  supportedScopes()
  estimateCost(query)
  status()
  search(query)
```

Provider results must include stable ids, result type, title, source provider, matched fields,
optional highlights after masking, freshness and warning metadata. Providers must not return
resources or fields the caller cannot access.

The current implementation starts with `CaseProjectionSearchProvider`, which searches the local
case projection by tenant, state, assignee, case definition key, business key, case id and title.
Task, document, timeline, enterprise reference and semantic providers are extension points.

## API Surface

The Search API lives under `/case-api/v2/search`.

| Endpoint | Purpose |
|---|---|
| `GET /search/cases` | Simple case search for case-list and workbench screens. |
| `POST /search/query` | Orchestrated search across selected scopes, filters and providers. |
| `GET /search/suggestions` | Tenant-scoped suggestions for authorized visible results. |
| `GET /search/facets` | Authorized facets for selected scopes and filters. |
| `GET /search/providers` | Provider capabilities, health and freshness. |

Responses include items, page metadata, warnings and provider status. Facets are returned when a
registered provider supports them.

## Security

Search is a disclosure-sensitive surface because existence, counts, suggestions, facets and
highlights can leak information. All search endpoints derive tenant scope from the authenticated
principal. Request bodies and query parameters do not select tenant.

Required safeguards:

- Apply case/task/document authorization before returning results.
- Apply field-level masking before returning highlights.
- Suppress or mask facet counts when counts would reveal hidden records.
- Suppress suggestions that reveal unauthorized resource existence.
- Include negative authorization tests for results, suggestions, facets and highlights.
- Audit search activity where data classification or policy requires it.

## Operations

Search indexes and projections are read models. They must be observable and rebuildable.

Operational requirements:

- Provider latency, timeout count, partial-result count and error rate.
- Projection lag and rebuild status per provider.
- Bounded provider timeouts.
- Explicit warnings for stale, partial or failed providers.
- Rebuild runbooks for local projections and optional external indexes.
- Multi-instance safe rebuild and indexing jobs, or an explicit single-instance constraint.

## Implementation Status

This repository currently implements the first slice:

- `SearchProvider` and `SearchOrchestrator` in core.
- Local case projection provider.
- Search REST endpoints for cases, orchestrated queries, provider status, suggestions and facets.
- Tenant scope derived from the principal.

Still to implement:

- Task/worklist provider.
- Document metadata provider.
- Timeline/comment provider.
- Facet-producing projections.
- External search platform integration.
- Semantic search, disabled by default and subject to explicit approval.

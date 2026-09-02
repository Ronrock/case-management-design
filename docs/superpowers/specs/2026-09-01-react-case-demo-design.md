# React Case Management Demo Design

## Purpose

Build a small, standalone React application that demonstrates the case-management library through its live REST API. The screen is for a caseworker evaluating the library: connect to the local PoC backend, select or create a complaint case, understand its current state, and perform the next available task without leaving one workspace.

The demo is intentionally not a production portal and is not part of the library distribution. It must remain outside the Maven reactor and outside the existing `@casemgmt/web-components` package.

## Product Scope

The demo provides one authenticated caseworker workspace with:

- an HTTP Basic connection gate;
- a searchable case list;
- creation of a complaint case;
- case identity, state, priority, canonical variables, and SLA status;
- current tasks with claim and complete actions;
- simple contract-driven task forms for string, integer, and enum fields;
- an engine-observed case spine built from plan items, tasks, milestones, and case events;
- recent case events; and
- explicit loading, empty, authentication, conflict, and server-error states.

The first version does not include administration, model deployment, webhooks, document upload, comments, advanced search, OIDC, offline data, routing, or a mock backend.

## Project Boundary

Create the application at repository root as `case-management-react-demo/`.

- It has its own `package.json`, lockfile, TypeScript configuration, Vite configuration, tests, and README.
- It is not added to the root Maven `pom.xml`.
- It is not added as a dependency or workspace member of `case-management-web-components`.
- It consumes only the public `/case-api/v2` HTTP contract.
- Development uses a Vite proxy to the PoC backend. A production build assumes the same paths are reverse-proxied to the REST backend.

## Technology

- React and TypeScript on Vite.
- shadcn/ui components using the supported Vite and Tailwind CSS setup documented at <https://ui.shadcn.com/docs/installation/vite>.
- Radix-backed shadcn primitives for accessible buttons, dialogs, inputs, selects, badges, scroll areas, separators, skeletons, and alerts.
- Lucide icons supplied by the shadcn setup.
- Native `fetch` behind a typed API client; no additional query/state framework.
- Vitest, jsdom, React Testing Library, and user-event for behavior tests.

The demo owns the generated shadcn component source. This keeps the styling local and avoids adding a UI framework dependency to the library packages.

## Screen Architecture

The authenticated screen uses a two-column desktop workspace and a stacked mobile layout.

```text
┌ Case management demo                         admin · Connected ┐
├────────────────────┬───────────────────────────────────────────┤
│ Search cases       │ Complaint CM-1042              ACTIVE    │
│                    │ High priority · SLA due in 2d 4h          │
│ CM-1042  ACTIVE    ├───────────────────────────────────────────┤
│ CM-1038  ACTIVE    │ Canonical case fields                     │
│ CM-1029  CLOSED    │                                           │
│                    │ Case spine                                │
│ + New complaint    │ ● Created ─ ● Assess ─ ○ Close           │
│                    │                                           │
│                    │ Current task             Claim / Complete │
│                    │ Recent events                             │
└────────────────────┴───────────────────────────────────────────┘
```

### Connection Gate

The opening card accepts a username and password. The API base is configured through `VITE_CASE_API_BASE_URL` and defaults to `/case-api/v2`; the Vite development proxy target is configured separately through `VITE_CASE_API_PROXY_TARGET`. Credentials are held in React memory for the session and converted to an `Authorization: Basic ...` header per request; the password is not written to local storage, session storage, URLs, logs, or error messages.

Submitting the form calls `GET /cases?pageSize=1`. A successful response opens the workspace. HTTP 401 keeps the gate visible with “Credentials were not accepted.” Other failures state whether the configured backend could not be reached or returned an unexpected response.

### Case Rail

The left rail loads `GET /cases?pageSize=100&sort=-updatedAt`, filters the returned page locally by title, business key, or ID, and keeps the selected case ID in component state. Each row shows title, business key or shortened ID, state, priority, and SLA status. Empty states distinguish “no cases exist” from “no cases match this search.”

“New complaint” opens a dialog with tenant, business key, title, channel, summary, and amount. Submission calls `POST /cases` with a fresh idempotency key and `caseDefinitionKey: complaint`. The created case becomes selected and all case queries refresh.

### Case Workspace

Selecting a case loads in parallel:

- `GET /cases/{caseId}`;
- `GET /cases/{caseId}/tasks`;
- `GET /cases/{caseId}/plan-items`;
- `GET /cases/{caseId}/milestones`;
- `GET /cases/{caseId}/slas`; and
- `GET /cases/{caseId}/events?limit=25`.

The workspace header presents identity, state, priority, projection status, and the nearest SLA deadline. Canonical variables appear as a compact definition list. Missing optional data is shown as an em dash, not fabricated.

The case spine is the signature element. It combines the case creation fact, ordered observed plan items/tasks/milestones, and the current active task into a vertical operational narrative. It never invents process transitions: labels and states come from the REST projections and events. Current work receives the strongest visual emphasis; completed facts remain legible but quiet.

### Task Actions

Available actions from each task response control which buttons render. Claim sends `POST /tasks/{taskId}/claim` with `If-Match` and an idempotency key. Complete first resolves the case definition, pinned contract release, and task `formKey`, then renders the associated JSON Schema properties:

- `enum` string → select;
- plain string → text input or textarea when indicated by `uiSchema`;
- integer → numeric input.

Required fields are marked and validated before submission. Completion sends `POST /tasks/{taskId}/complete` with `{ "variables": ... }`, `If-Match`, and an idempotency key. Both direct `200` responses and accepted `202` engine-operation responses are treated as success, followed by a complete workspace refresh. The demo does not poll asynchronous operations in this first version.

## REST Client

One `CaseApiClient` owns URL joining, Basic authorization, correlation IDs, idempotency keys, JSON encoding, ETag formatting/capture, and problem-detail parsing. Feature components consume typed methods rather than calling `fetch` directly.

The client captures the response ETag for case detail and formats task `If-Match` values from the numeric `version` included in each task-list item, because the REST API has no separate task-detail read. Mutations require the caller to supply the current version token. A `412` response is rendered as “This item changed. Refresh and try again.” A `401` response returns the app to the connection gate and clears in-memory credentials.

## Visual System

The subject is operational complaint handling for caseworkers. The page should feel like a precise case file rather than a generic analytics dashboard.

- Registry blue `#14324B`: navigation, strong text, primary actions.
- Steel `#E8EEF2`: page field and quiet grouping.
- Signal teal `#168C86`: current/healthy state.
- Deadline amber `#D8891C`: warnings and near-due SLA.
- Breach red `#B43B45`: errors, breach, and destructive states.
- Paper white `#FBFCFD`: working surfaces.

IBM Plex Sans carries interface copy; IBM Plex Mono carries IDs, timestamps, and compact state labels. The design spends its visual emphasis on the case spine. Cards, shadows, gradients, and animation remain restrained. Motion is limited to one workspace entrance and short state transitions, disabled under `prefers-reduced-motion`.

The UI must remain usable at 360 CSS pixels, expose visible keyboard focus, use semantic headings and landmarks, and keep status meaning available in text rather than color alone.

## Error and Empty States

- Authentication failure: keep credentials form visible and identify rejected credentials.
- Backend unavailable: identify the configured backend and advise starting the PoC application.
- No cases: invite the user to create a complaint.
- No selected case: explain that selecting a case opens its workspace.
- Case detail partial failure: show one error panel with a Refresh action; do not mix stale and fresh sections.
- Mutation conflict: explain the version conflict and refresh automatically.
- Unsupported task form field: show the field name and unsupported schema type; do not submit a partial payload.

## Testing

Tests use a controllable `fetch` stub at the REST-client boundary and real rendered React components. Required behavior coverage:

1. Basic credentials are sent to the backend and rejected credentials remain on the gate.
2. Successful authentication loads and selects the first case.
3. Search filters the loaded cases without another request.
4. Creating a complaint sends the expected API payload and selects the response.
5. Selecting a case loads all workspace resources and renders canonical values, SLA, spine, and events.
6. Claim uses the task ETag and refreshes the workspace.
7. Contract form schema renders enum, string, textarea, and integer fields; required validation prevents an invalid completion.
8. Complete sends mapped variables, ETag, correlation ID, and idempotency key.
9. HTTP 401 clears the connection; HTTP 412 refreshes and gives conflict guidance.
10. Loading, no-case, no-match, and backend-unavailable states provide a next action.

The completion gate is `npm test`, `npm run build`, a production preview smoke check, and visual inspection at desktop and mobile widths against a running PoC backend.

## Acceptance Criteria

- A developer can start the PoC backend, run the demo with documented commands, authenticate with HTTP Basic, and see live cases.
- The screen can create a complaint, select it, display its current projections/SLA/events, and claim or complete an available task.
- No mock data or mock mode ships in the app.
- The demo does not modify the Maven reactor or the existing web-components package.
- Automated tests and the production build pass.

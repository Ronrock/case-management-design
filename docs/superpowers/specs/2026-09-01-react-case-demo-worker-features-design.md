# React Case Demo Worker Features Design

## Purpose

Expand the standalone React demo into a complete caseworker story using only the existing public REST API. A worker can open a personal work inbox, claim work, inspect and act on its case, manage SLA clocks, collaborate through comments, and see the resulting activity without leaving the demo.

This increment remains independent from the Maven reactor and the published web-components package. It adds no backend endpoints, polling, router, query library, mock-data mode, or production authentication mechanism.

## Scope

The increment adds four worker-facing capabilities:

1. A request-driven **My Work** inbox backed by `GET /case-api/v2/tasks`.
2. A case action bar driven by each case's `availableActions`.
3. An SLA cockpit driven by each clock's state and `availableActions`.
4. An activity view combining case events and comments, with comment creation.

The existing Cases view, complaint creation, task claim/completion, contract-driven forms, case facts, and case spine remain available.

## Application Structure

The authenticated application becomes a two-view operations shell with **My Work** and **Cases** navigation. Navigation is React state rather than URL routing because the demo has only two closely related views and does not need shareable deep links.

`CaseDemo` owns:

- the active primary view;
- the selected case id;
- the optional task id to highlight after navigating from My Work;
- the loaded case list; and
- a shared refresh generation incremented after successful mutations.

Both primary views reuse the existing `CaseWorkspace`; there is no second case-detail implementation.

New feature boundaries:

- `WorkInbox` loads and groups the authenticated user's tasks.
- `CaseActionBar` presents allowed case mutations.
- `SlaCockpit` presents all clocks and their allowed operations.
- `ActivityPanel` loads, merges, and renders comments and events.
- `CaseApiClient` remains the only HTTP boundary and gains typed methods for these resources and mutations.

## My Work

Entering My Work or pressing Refresh calls `GET /tasks`. Tasks are split into:

- **Assigned to me** when `assignee` equals the connected username.
- **Available to claim** when the response advertises a `claim` action.

Other readable tasks may appear in a quiet **Other visible work** group so the UI does not discard server-authorized data. Each row shows task name, state, assignee or candidate groups, projection status when present, and a shortened case id. The API does not provide batch case summaries with the worklist, so the inbox will not issue an N+1 set of case-detail requests merely to decorate rows.

Selecting a row switches to Cases, selects its `caseId`, and highlights that task after the workspace loads. Claim and Complete continue to use the existing task action component. A successful task mutation increments the shared refresh generation so both the workspace and the next visit to My Work obtain current data.

The inbox is request-driven. It loads on entry, after relevant mutations, and through an explicit Refresh button. It does not poll.

## Case Actions

The workspace header gains a `CaseActionBar`. It renders only actions advertised in the selected case's `availableActions` and uses the advertised `href` and HTTP method.

- **Update** opens a dialog for the title. The mutation sends JSON Merge Patch and preserves the existing canonical variables unchanged. Generic raw JSON variable editing is excluded because it is unsafe for a showcase and undermines the contract-driven form story.
- **Close** opens a confirmation dialog with an optional outcome.
- **Cancel** opens a destructive confirmation dialog and requires a reason.

Every mutation supplies `If-Match` from the current case version. Idempotency keys are supplied only where the API contract requires them. Buttons are disabled during submission, and a successful action refreshes the case list, selected workspace, inbox generation, SLA data, and activity data.

## SLA Cockpit

The existing nearest-SLA summary becomes a cockpit that displays every clock returned by `GET /cases/{caseId}/slas`.

Each clock shows:

- target name;
- textual status;
- due and warning timestamps;
- a derived time remaining or overdue label;
- paused time and reason when present; and
- projection-safe action buttons from `availableActions`.

Pause opens a dialog and requires a reason. Resume is immediate. Both use the action's advertised URL and method plus `If-Match` from the SLA version. A successful mutation reloads the entire workspace snapshot so the clock, events, and case-level SLA summary remain consistent.

Countdown text is calculated at render time from server timestamps. There is no ticking timer; a manual or post-action refresh recalculates it.

## Activity and Comments

`CaseWorkspace` gains **Overview** and **Activity** sections. Overview contains the current facts, SLA cockpit, tasks, and case spine. Activity loads:

- `GET /cases/{caseId}/events?after=0&limit=100`; and
- `GET /cases/{caseId}/comments`.

Events and comments are normalized into a small presentation type, merged, and sorted newest first. They remain visually distinguishable: events are system observations; comments are worker-authored collaboration. The UI does not claim that this is a complete compliance audit trail.

The comment composer posts `{ "text": ..., "visibility": "internal" }` through the `comment` action advertised in the case response's `collaborationActions`. Empty comments are rejected client-side. The server remains authoritative and can still return `403`. A successful post clears the composer and refreshes comments and events.

## REST Client Changes

The typed client gains methods for:

- listing the worklist;
- updating, closing, and cancelling a case;
- pausing and resuming an SLA;
- listing comments; and
- adding a comment through an advertised collaboration action.

A shared advertised-action executor owns safe URL normalization, headers, JSON bodies, and problem-detail parsing. It accepts API-root-relative action links such as `/tasks/{id}/claim` and prefixes the configured case API base while continuing to reject cross-origin and malformed links.

Mutations use the resource version supplied by the REST representation. The client does not infer versions from list order or cache unrelated ETags.

## State and Refresh Semantics

The application remains request-driven:

- entering a view loads its data;
- explicit Refresh reloads that view;
- a successful mutation refreshes every affected resource;
- no interval, event stream, or background poll runs;
- pending requests are ignored after their component or selected resource changes.

A workspace refresh is atomic from the user's perspective: the existing snapshot stays visible only until the new request begins, then the workspace uses its loading state and replaces the complete snapshot together. Activity may fail independently without erasing a valid Overview.

## Errors and Concurrency

- `401` invokes the existing unauthorized handler, clears credentials, and returns to the connection gate.
- `403` remains in the current feature and explains that the backend refused the action.
- `412` explains that the resource changed, closes no destructive confirmation implicitly, and refreshes the affected view.
- Other errors remain local to the inbox, action dialog, SLA panel, or activity panel.
- Submissions are disabled while active to prevent duplicate clicks.
- Close and Cancel require explicit confirmation; Cancel also requires non-blank reason text.

The UI never invents permissions. Case and task controls come from `availableActions`, SLA controls come from each clock's `availableActions`, and comment creation comes from the case's `collaborationActions`. Authorization is always enforced again by the backend.

## Responsive and Accessible Behavior

The existing visual language remains unchanged. On desktop, primary navigation sits in the application header and the Cases view retains its rail/workspace layout. My Work uses a compact responsive list. On small screens, navigation remains keyboard accessible, work groups stack, dialogs fit within the viewport, and action rows wrap.

Status meaning remains available in text rather than color alone. Dialog controls have labels, destructive actions identify their consequence, busy states are announced through button text, and newly refreshed sections retain semantic headings.

## Testing

Development follows red-green-refactor cycles. Tests exercise real rendered components with a controllable fetch boundary and assert consumer-visible behavior.

Required coverage:

1. My Work groups assigned and claimable tasks and navigates to the owning case.
2. Inbox refresh and task mutations request fresh server state.
3. Only advertised case actions render.
4. Update, Close, and Cancel send the correct method, body, and `If-Match` value.
5. Cancel rejects a blank reason and destructive actions require confirmation.
6. The SLA cockpit renders all clocks, warning/breach/paused detail, and only advertised operations.
7. Pause and Resume send the SLA version and refresh the workspace.
8. Activity merges comments and events in descending timestamp order without relabeling events as audit records.
9. Comment submission uses internal visibility, rejects empty text, and refreshes the activity feed.
10. `401`, `403`, and `412` produce the specified recovery behavior.
11. Existing creation, workspace, claim, completion, dynamic-form, and URL-normalization tests continue to pass.

Completion requires `npm test`, `npm run lint`, `npm run build`, `git diff --check`, and a live request-driven walkthrough against the PoC backend.

## Acceptance Criteria

- The connected user can switch between My Work and Cases without a page reload.
- My Work shows live REST tasks grouped by assignment and claimability.
- Selecting work opens the correct case and highlights the task.
- The workspace renders and executes only advertised case and SLA actions with optimistic concurrency.
- Every SLA clock is visible with understandable timing and pause state.
- The user can read events and comments together and add an internal comment.
- Every successful mutation refreshes affected state without periodic polling.
- The demo remains standalone and requires the live backend.
- No backend, Maven-reactor, or published web-component changes are introduced.

## Explicit Non-Goals

- URL routing or deep links.
- Periodic polling, SSE, or WebSocket updates.
- Participant management, task delegation, or arbitrary assignment.
- Raw JSON editing of canonical variables.
- Related cases or business-module workspaces.
- Binary document upload or download.
- A worker-facing reconstruction of the compliance audit log.
- Production identity, OIDC, or credential persistence.

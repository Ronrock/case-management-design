# React Case Management Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone React caseworker screen that authenticates with HTTP Basic and demonstrates live case creation, projections, SLA state, events, and task actions through `/case-api/v2`.

**Architecture:** A Vite React application lives in `case-management-react-demo/` and is intentionally absent from the Maven reactor and existing web-components package. One typed `CaseApiClient` owns all HTTP behavior; feature components receive that client and render a master-detail case workspace using locally generated shadcn/ui primitives.

**Tech Stack:** React 19.2.8, Vite 8.2.2 (scaffolded with create-vite 9.2.0), TypeScript, shadcn CLI 4.19.1 with Radix primitives, Tailwind CSS 4.3.3, Lucide React 1.38.0, IBM Plex fonts, Vitest 4.1.11, jsdom 30.0.1, and React Testing Library.

**Spec:** `docs/superpowers/specs/2026-09-01-react-case-demo-design.md`

## Global Constraints

- The app directory is exactly `case-management-react-demo/` at repository root.
- Do not add the app to the root Maven `pom.xml`, any npm workspace, or `case-management-web-components`.
- The app requires a live REST backend; do not add mock data or a mock runtime mode.
- The API base defaults to `/case-api/v2`; Vite proxies `/case-api` to `VITE_CASE_API_PROXY_TARGET`, default `http://localhost:8080`.
- Only HTTP Basic authentication is implemented. Keep credentials in React memory and never persist or log the password.
- Use shadcn/ui generated source for primitives; do not hand-roll substitute button/dialog/select primitives.
- Treat shadcn and create-vite output as generated code. Test composed application behavior, not generated primitive internals.
- Every handwritten behavior follows red-green-refactor: write the test, observe the intended failure, implement the minimum, and rerun the focused test before broader gates.
- Use REST-provided `availableActions`, task `version`, and pinned definition/contract identity; do not invent lifecycle or task authority in the frontend.
- Keep the first version to one screen with no router, OIDC, polling, administration, model deployment, document upload, comments, advanced search, or offline mode.

---

## File Map

### Configuration and generated foundation

- `case-management-react-demo/package.json` — isolated scripts and dependencies.
- `case-management-react-demo/package-lock.json` — reproducible npm graph.
- `case-management-react-demo/components.json` — shadcn Radix configuration.
- `case-management-react-demo/vite.config.ts` — React/Tailwind plugins, Vitest, and REST proxy.
- `case-management-react-demo/vitest.setup.ts` — DOM matchers and browser API shims.
- `case-management-react-demo/tsconfig*.json`, `eslint.config.js`, `index.html` — standalone Vite configuration.
- `case-management-react-demo/src/components/ui/*.tsx` — shadcn-generated primitives only.
- `case-management-react-demo/src/lib/utils.ts` — shadcn `cn` helper.

### Handwritten application units

- `src/main.tsx` — root mounting and font/global CSS imports.
- `src/index.css` — design tokens, responsive shell, case-spine styling, focus, reduced motion.
- `src/app.tsx` — in-memory authenticated session and transition into `CaseDemo`.
- `src/lib/api-types.ts` — exact REST response/request types used by the demo.
- `src/lib/case-api-client.ts` — Basic auth, request headers, errors, typed reads/mutations.
- `src/features/auth/connection-gate.tsx` — username/password gate.
- `src/features/cases/case-demo.tsx` — case list ownership, selection, and refresh coordination.
- `src/features/cases/case-rail.tsx` — search and case selection.
- `src/features/cases/new-case-dialog.tsx` — complaint creation.
- `src/features/workspace/case-workspace.tsx` — parallel detail loading and coherent error boundary.
- `src/features/workspace/case-spine.tsx` — observation-derived timeline.
- `src/features/tasks/task-actions.tsx` — claim/complete action coordination.
- `src/features/tasks/dynamic-task-form.tsx` — supported JSON Schema field rendering and validation.
- `src/test/fetch-script.ts` — deterministic request-aware fetch test double.
- `src/test/render.tsx` — shared Testing Library render helpers.
- colocated `*.test.ts`/`*.test.tsx` files — handwritten behavior tests.
- `case-management-react-demo/README.md` — backend prerequisites, environment variables, and commands.

---

### Task 1: Standalone Foundation, REST Client, and Basic Connection

**Files:**
- Create: `case-management-react-demo/**` through create-vite and shadcn generators
- Modify: `case-management-react-demo/package.json`
- Modify: `case-management-react-demo/vite.config.ts`
- Create: `case-management-react-demo/vitest.setup.ts`
- Create: `case-management-react-demo/src/lib/api-types.ts`
- Create: `case-management-react-demo/src/lib/case-api-client.ts`
- Create: `case-management-react-demo/src/features/auth/connection-gate.tsx`
- Modify: `case-management-react-demo/src/app.tsx`
- Create: `case-management-react-demo/src/test/fetch-script.ts`
- Create: `case-management-react-demo/src/test/render.tsx`
- Test: `case-management-react-demo/src/app.test.tsx`

**Interfaces:**
- Produces: `ApiCredentials`, `CaseApiClient`, `ApiError`, `CaseSummary`, `Page<T>`, and an authenticated `App` session consumed by every later task.
- `CaseApiClient.connect(): Promise<Page<CaseSummary>>` proves credentials and returns the first page without an extra read.
- `App` constructs the client from `VITE_CASE_API_BASE_URL` and never exposes the password to child display components.

- [ ] **Step 1: Generate the isolated Vite and shadcn foundation**

Run from repository root:

```bash
npm create vite@9.2.0 case-management-react-demo -- --template react-ts
cd case-management-react-demo
npm install
npm install @fontsource-variable/ibm-plex-sans@5.3.0 @fontsource/ibm-plex-mono@5.3.0
npm install --save-dev vitest@4.1.11 jsdom@30.0.1 @testing-library/react@16.3.3 @testing-library/user-event@14.6.6 @testing-library/jest-dom@7.0.1
npx shadcn@4.19.1 init --template vite --base radix --yes
npx shadcn@4.19.1 add button card input label badge dialog textarea select scroll-area separator skeleton alert
```

Delete only the generated Vite logo assets and demo counter CSS after the first application test is red. Do not edit the root `pom.xml` or any existing package manifest.

- [ ] **Step 2: Configure Vite, Vitest, and scripts**

Replace `vite.config.ts` with:

```ts
import path from "node:path"
import tailwindcss from "@tailwindcss/vite"
import react from "@vitejs/plugin-react"
import { loadEnv } from "vite"
import { defineConfig } from "vitest/config"

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "")
  return {
    plugins: [react(), tailwindcss()],
    resolve: { alias: { "@": path.resolve(__dirname, "./src") } },
    server: {
      proxy: {
        "/case-api": {
          target: env.VITE_CASE_API_PROXY_TARGET || "http://localhost:8080",
          changeOrigin: true,
        },
      },
    },
    test: {
      environment: "jsdom",
      setupFiles: "./vitest.setup.ts",
      css: true,
      restoreMocks: true,
    },
  }
})
```

Add scripts `test: "vitest run"`, `test:watch: "vitest"`, and retain `dev`, `build`, `lint`, and `preview`. In `vitest.setup.ts`, import `@testing-library/jest-dom/vitest` and provide a no-op `ResizeObserver` class for Radix/shadcn tests.

- [ ] **Step 3: Add the test-only fetch script**

Create `src/test/fetch-script.ts` with a request-aware queue:

```ts
import { vi } from "vitest"

export interface FetchCall { url: string; init: RequestInit }
export interface ScriptedResponse { status?: number; body?: unknown; headers?: HeadersInit }

export function installFetchScript(
  respond: (call: FetchCall) => ScriptedResponse,
): FetchCall[] {
  const calls: FetchCall[] = []
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const call = { url: String(input), init }
    calls.push(call)
    const scripted = respond(call)
    return new Response(
      scripted.body === undefined ? undefined : JSON.stringify(scripted.body),
      {
        status: scripted.status ?? 200,
        headers: { "Content-Type": "application/json", ...scripted.headers },
      },
    )
  }))
  return calls
}
```

- [ ] **Step 4: Write the failing Basic-auth connection test**

Create `src/app.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, describe, expect, it, vi } from "vitest"
import App from "./app"
import { installFetchScript } from "./test/fetch-script"

afterEach(() => vi.unstubAllGlobals())

describe("basic connection", () => {
  it("keeps credentials in memory and sends a Basic authorization header", async () => {
    const calls = installFetchScript(() => ({
      body: { items: [], page: 0, pageSize: 1, totalItems: 0, totalPages: 0 },
    }))
    const user = userEvent.setup()
    render(<App />)

    await user.type(screen.getByLabelText("Username"), "admin")
    await user.type(screen.getByLabelText("Password"), "admin")
    await user.click(screen.getByRole("button", { name: "Connect" }))

    expect(await screen.findByText("Connected as admin")).toBeInTheDocument()
    expect(new Headers(calls[0].init.headers).get("Authorization"))
      .toBe("Basic YWRtaW46YWRtaW4=")
  })
})
```

- [ ] **Step 5: Run the test and confirm RED**

Run:

```bash
cd case-management-react-demo
npm test -- src/app.test.tsx
```

Expected: FAIL because the generated Vite `App` has no Username, Password, or Connect controls.

- [ ] **Step 6: Implement the minimum authenticated shell**

Define these exact core types in `api-types.ts`:

```ts
export interface ApiCredentials { username: string; password: string }
export interface Page<T> { items: T[]; page: number; pageSize: number; totalItems: number; totalPages: number }
export interface AvailableAction { action: string; name: string; href: string; method: string; formKey?: string | null }
export interface CaseSummary {
  id: string; tenantId: string; caseDefinitionKey: string; caseDefinitionVersion: number
  businessKey?: string | null; title?: string | null; state: string; priority?: string | null
  slaStatus?: string | null; createdAt?: string | null; updatedAt?: string | null
  variables?: Record<string, unknown>
  version: number; projectionStatus?: string | null; availableActions: AvailableAction[]
}
```

Implement `CaseApiClient` with constructor `{ baseUrl, credentials, fetchImpl? }`, a private `request<T>()`, and:

```ts
connect(): Promise<Page<CaseSummary>> {
  return this.request("/cases?pageSize=1")
}
```

Every request adds `Accept: application/json`, `Authorization: Basic ${btoa(username + ":" + password)}`, and `X-Correlation-ID: crypto.randomUUID()`. `ApiError` exposes `status`, `title`, and `detail` parsed from RFC 9457 problem JSON without including request credentials.

Implement `ConnectionGate` as a semantic form using shadcn `Card`, `Input`, `Label`, `Button`, and `Alert`. `App` stores `{ username, client, initialCases }` in state only after `connect()` succeeds and renders a minimal heading plus “Connected as {username}”.

- [ ] **Step 7: Verify GREEN and the isolated build**

Run:

```bash
npm test -- src/app.test.tsx
npm run build
```

Expected: one passing test and a successful Vite production build.

- [ ] **Step 8: Commit Task 1**

```bash
git add case-management-react-demo
git commit -m "feat(demo): add React REST connection shell"
```

---

### Task 2: Case Rail, Search, and Complaint Creation

**Files:**
- Modify: `case-management-react-demo/src/lib/api-types.ts`
- Modify: `case-management-react-demo/src/lib/case-api-client.ts`
- Create: `case-management-react-demo/src/features/cases/case-demo.tsx`
- Create: `case-management-react-demo/src/features/cases/case-rail.tsx`
- Create: `case-management-react-demo/src/features/cases/new-case-dialog.tsx`
- Modify: `case-management-react-demo/src/app.tsx`
- Test: `case-management-react-demo/src/features/cases/case-demo.test.tsx`

**Interfaces:**
- Consumes: `CaseApiClient`, `CaseSummary`, `Page<T>`, and initial page from Task 1.
- Produces: `CaseDemo({ client, username, initialPage })`, selected case ID, `client.listCases()`, and `client.createComplaint(input)` for the workspace in Task 3.

- [ ] **Step 1: Write the failing case search test**

Create `case-demo.test.tsx` with an initial page containing “Lost transfer” and “Card complaint”, render `CaseDemo`, type `card` into `Search cases`, and assert only “Card complaint” remains. Also click the visible row and assert the Task 2 selection-confirmation panel renders `Case selected: case-2`; Task 3 replaces that panel with the live workspace while keeping the same selection state.

Core assertion:

```tsx
await user.type(screen.getByRole("searchbox", { name: "Search cases" }), "card")
expect(screen.getByText("Card complaint")).toBeInTheDocument()
expect(screen.queryByText("Lost transfer")).not.toBeInTheDocument()
```

- [ ] **Step 2: Run the case test and confirm RED**

Run `npm test -- src/features/cases/case-demo.test.tsx`.

Expected: FAIL because `CaseDemo` and the case rail do not exist.

- [ ] **Step 3: Implement the case rail and selection**

`CaseRail` receives:

```ts
interface CaseRailProps {
  cases: CaseSummary[]
  selectedId?: string
  onSelect(caseId: string): void
  onCreate(): void
}
```

Filter locally against lowercase title, business key, and ID. Use a real `<input type="search">`, buttons for case rows, text state labels inside `Badge`, and separate no-case/no-match messages. `CaseDemo` selects the first initial item once, but preserves an explicit later selection across refreshes.

- [ ] **Step 4: Verify the search test GREEN**

Run `npm test -- src/features/cases/case-demo.test.tsx`.

Expected: PASS.

- [ ] **Step 5: Write the failing complaint creation test**

Extend the same test file:

```tsx
it("creates a complaint with an idempotency key and selects it", async () => {
  // GET detail resources may return empty fixtures until Task 3.
  const calls = installFetchScript(call => call.init.method === "POST"
    ? { status: 201, body: complaintCase("case-new", "New card complaint") }
    : { body: emptyPage() })
  // render, click New complaint, fill Tenant/Business key/Title/Channel/Summary/Amount
  // submit and assert selected heading.
  const create = calls.find(call => call.init.method === "POST")!
  expect(create.url.endsWith("/case-api/v2/cases")).toBe(true)
  expect(new Headers(create.init.headers).get("Idempotency-Key")).toBeTruthy()
  expect(JSON.parse(String(create.init.body))).toEqual({
    caseDefinitionKey: "complaint",
    tenantId: "t1",
    businessKey: "CMP-1001",
    title: "New card complaint",
    priority: "MEDIUM",
    variables: { channel: "web", summary: "Charge disputed", amount: 125 },
  })
})
```

- [ ] **Step 6: Run the creation test and confirm RED**

Expected: FAIL because the dialog and `createComplaint` method do not exist.

- [ ] **Step 7: Implement complaint creation**

Add `CreateComplaintInput` and `CaseApiClient.createComplaint()` using `POST /cases`, JSON content type, and a new idempotency key. Build `NewCaseDialog` with shadcn `Dialog`, `Input`, `Textarea`, and `Select`. Require tenant, business key, title, channel, and summary; parse blank amount as omitted and a present amount as an integer. On success, prepend/deduplicate the response in `CaseDemo`, select it, close the dialog, and keep the entered password outside all props and state in this subtree.

- [ ] **Step 8: Verify Task 2 and commit**

Run:

```bash
npm test -- src/features/cases/case-demo.test.tsx
npm test
npm run build
git add case-management-react-demo
git commit -m "feat(demo): add case rail and complaint creation"
```

---

### Task 3: Live Case Workspace and Observation Spine

**Files:**
- Modify: `case-management-react-demo/src/lib/api-types.ts`
- Modify: `case-management-react-demo/src/lib/case-api-client.ts`
- Create: `case-management-react-demo/src/features/workspace/case-workspace.tsx`
- Create: `case-management-react-demo/src/features/workspace/case-spine.tsx`
- Modify: `case-management-react-demo/src/features/cases/case-demo.tsx`
- Test: `case-management-react-demo/src/features/workspace/case-workspace.test.tsx`

**Interfaces:**
- Consumes: selected case ID and `CaseApiClient`.
- Produces: `CaseWorkspaceSnapshot`, `client.loadWorkspace(caseId)`, `CaseWorkspace({ client, caseId, refreshKey })`, and `CaseSpine` used by task refresh in Task 4.

- [ ] **Step 1: Add exact workspace response types**

Add:

```ts
export interface TaskSummary {
  id: string; caseId: string; name: string; state: string; assignee?: string | null
  candidateGroups: string[]; formKey?: string | null; version: number
  projectionStatus?: string | null; availableActions: AvailableAction[]
}
export interface PlanItemSummary {
  id: string; type: string; name: string; state: string; parentStageId?: string | null
  repetitionNo: number; lastEngineUpdateAt?: string | null
}
export interface MilestoneSummary { id: string; name?: string; state: string; achievedAt?: string | null }
export interface SlaSummary {
  id: string; targetId: string; status: string; startedAt?: string | null
  dueAt?: string | null; warnAt?: string | null; version: number
}
export interface CaseEvent {
  id: string; type: string; time?: string; subject?: string; data?: Record<string, unknown>
}
export interface CaseWorkspaceSnapshot {
  case: CaseSummary; tasks: TaskSummary[]; planItems: PlanItemSummary[]
  milestones: MilestoneSummary[]; slas: SlaSummary[]; events: CaseEvent[]
}
```

- [ ] **Step 2: Write the failing parallel workspace test**

Use a URL-aware fetch script that returns one case, one active “Assess complaint” task, one completed intake item, one active SLA, and one event. Render `CaseWorkspace` and assert:

```tsx
expect(await screen.findByRole("heading", { name: "Card complaint" })).toBeInTheDocument()
expect(screen.getByText("Assess complaint")).toBeInTheDocument()
expect(screen.getByText("Resolution SLA")).toBeInTheDocument()
expect(screen.getByText("Charge disputed")).toBeInTheDocument()
expect(calls.map(call => call.url)).toEqual(expect.arrayContaining([
  expect.stringEndingWith("/cases/case-1"),
  expect.stringEndingWith("/cases/case-1/tasks"),
  expect.stringEndingWith("/cases/case-1/plan-items"),
  expect.stringEndingWith("/cases/case-1/milestones"),
  expect.stringEndingWith("/cases/case-1/slas"),
  expect.stringContaining("/cases/case-1/events?"),
]))
```

- [ ] **Step 3: Run the workspace test and confirm RED**

Expected: FAIL because workspace loading/rendering does not exist.

- [ ] **Step 4: Implement one coherent parallel workspace load**

Implement `loadWorkspace(caseId)` with `Promise.all` over the six specified endpoints. If any request fails, reject the whole snapshot so the UI never mixes stale and fresh sections. `CaseWorkspace` aborts/ignores a superseded selection, renders `Skeleton` while loading, and exposes one Refresh button on failure.

Render:

- header: title, business key/short ID, state, priority, projection status;
- nearest non-terminal SLA, with a humanized `{targetId} SLA` label, status, and due timestamp;
- canonical variables as a semantic definition list;
- current task cards;
- the case spine; and
- newest 25 event types/timestamps.

- [ ] **Step 5: Implement the case spine from evidence only**

`CaseSpine` accepts the snapshot. Build ordered entries from case creation, plan items, tasks, milestones, and events that contain a usable timestamp. Never synthesize completion or transition names. Sort by timestamp, then stable ID. Mark active task/item entries as current, terminal entries as complete, and everything else as observed. Include a text state label and CSS class for each state.

- [ ] **Step 6: Verify Task 3 and commit**

Run:

```bash
npm test -- src/features/workspace/case-workspace.test.tsx
npm test
npm run build
git add case-management-react-demo
git commit -m "feat(demo): show live case workspace"
```

---

### Task 4: Claim and Contract-Driven Complete Actions

**Files:**
- Modify: `case-management-react-demo/src/lib/api-types.ts`
- Modify: `case-management-react-demo/src/lib/case-api-client.ts`
- Create: `case-management-react-demo/src/features/tasks/dynamic-task-form.tsx`
- Create: `case-management-react-demo/src/features/tasks/task-actions.tsx`
- Modify: `case-management-react-demo/src/features/workspace/case-workspace.tsx`
- Test: `case-management-react-demo/src/features/tasks/dynamic-task-form.test.tsx`
- Test: `case-management-react-demo/src/features/tasks/task-actions.test.tsx`

**Interfaces:**
- Consumes: `TaskSummary`, `CaseSummary`, REST `availableActions`, task numeric version, and workspace refresh callback.
- Produces: `TaskFormDefinition`, `client.taskForm(case, task)`, `client.executeTaskAction(action, version, variables?)`, `DynamicTaskForm`, and `TaskActions`.

- [ ] **Step 1: Write the failing dynamic-form behavior test**

Render a form definition containing required enum `outcome`, textarea `rationale`, integer `amount`, and plain string `reference`. Assert submitting empty shows required guidance and does not call `onSubmit`; then fill each control and assert:

```ts
expect(onSubmit).toHaveBeenCalledWith({
  outcome: "upheld",
  rationale: "Merchant evidence confirms the charge",
  amount: 125,
  reference: "DOC-42",
})
```

- [ ] **Step 2: Run the form test and confirm RED**

Expected: FAIL because `DynamicTaskForm` does not exist.

- [ ] **Step 3: Implement the closed form renderer**

Define:

```ts
export interface JsonSchemaProperty {
  type: "string" | "integer"
  title?: string
  enum?: string[]
}
export interface TaskFormDefinition {
  schema: {
    type: "object"
    required?: string[]
    properties: Record<string, JsonSchemaProperty>
  }
  uiSchema?: Record<string, { widget?: "textarea" }>
}
```

Render only the three supported shapes from the spec. Convert integer input to `number`; preserve strings. Reject any other schema type with `Unsupported field {name}: {type}` and disable submission. Use native required/error associations plus shadcn controls.

- [ ] **Step 4: Verify the dynamic-form test GREEN**

Run `npm test -- src/features/tasks/dynamic-task-form.test.tsx`.

- [ ] **Step 5: Write failing claim and complete integration tests**

For claim, provide a task action `{ action: "claim", href: "/case-api/v2/tasks/task-1/claim", method: "POST" }`; click Claim and assert:

```ts
expect(new Headers(claim.init.headers).get("If-Match")).toBe('"7"')
expect(new Headers(claim.init.headers).get("Idempotency-Key")).toBeTruthy()
```

For complete, provide `formKey: "assessForm"`, return a definition containing `contractReleaseId`, then a contract containing `forms.assessForm`. Fill the form and assert POST to the advertised action href with `{ variables: { outcome: "upheld" } }`, `If-Match: "7"`, and one `onChanged()` call after either HTTP 200 or 202.

- [ ] **Step 6: Run the task-action tests and confirm RED**

Expected: FAIL because task actions and form resolution are missing.

- [ ] **Step 7: Implement hypermedia-driven task actions**

`taskForm(case, task)` performs:

1. `GET /case-definitions/{key}/versions/{version}`;
2. read exact `contractReleaseId`;
3. `GET /case-definitions/{key}/contract-releases/{releaseId}`; and
4. select `forms[action.formKey ?? task.formKey]`.

`executeTaskAction` accepts only same-origin absolute API paths, uses the action method/href, sends `If-Match: "{task.version}"`, a fresh idempotency key, and JSON `{ variables }` for complete. Render buttons only for actions actually returned by the task. After success invoke the workspace refresh callback once; do not optimistically alter task state.

- [ ] **Step 8: Verify Task 4 and commit**

Run:

```bash
npm test -- src/features/tasks
npm test
npm run build
git add case-management-react-demo
git commit -m "feat(demo): execute live task actions"
```

---

### Task 5: Failure Semantics, Responsive Visual Pass, and Operator Guide

**Files:**
- Modify: `case-management-react-demo/src/lib/case-api-client.ts`
- Modify: `case-management-react-demo/src/app.tsx`
- Modify: `case-management-react-demo/src/features/auth/connection-gate.tsx`
- Modify: `case-management-react-demo/src/features/cases/case-demo.tsx`
- Modify: `case-management-react-demo/src/features/workspace/case-workspace.tsx`
- Modify: `case-management-react-demo/src/index.css`
- Modify: `case-management-react-demo/src/main.tsx`
- Create: `case-management-react-demo/src/app-errors.test.tsx`
- Create: `case-management-react-demo/README.md`

**Interfaces:**
- Consumes: all earlier components and `ApiError`.
- Produces: final 401/412/network behavior, responsive visual system, documented startup, and completion evidence.

- [ ] **Step 1: Write failing authentication, conflict, and empty-state tests**

Cover these exact outcomes:

```tsx
it("keeps the connection gate open for rejected credentials", async () => {
  installFetchScript(() => ({ status: 401, body: { title: "Unauthorized" } }))
  // submit credentials
  expect(await screen.findByRole("alert")).toHaveTextContent("Credentials were not accepted")
  expect(screen.getByLabelText("Password")).toHaveValue("")
})

it("returns to connection when an authenticated request becomes unauthorized", async () => {
  // connect succeeds; subsequent workspace request returns 401
  expect(await screen.findByText("Your connection expired. Connect again.")).toBeInTheDocument()
})

it("refreshes stale work after a 412 task conflict", async () => {
  // claim returns 412; next workspace reads succeed
  expect(await screen.findByRole("alert")).toHaveTextContent("This item changed")
  expect(workspaceReadCount()).toBeGreaterThan(1)
})
```

Also assert the authenticated empty page says “No cases yet” with a visible “New complaint” action, and a filtered empty list says “No cases match this search.”

- [ ] **Step 2: Run error tests and confirm RED**

Run `npm test -- src/app-errors.test.tsx`.

Expected: FAIL on missing specialized 401/412/network messages and refresh behavior.

- [ ] **Step 3: Implement final error semantics**

Map:

- 401 during connect → clear password field, remain on gate, rejected-credentials message;
- 401 after connect → clear the entire in-memory client/session and return to gate;
- 412 → show conflict guidance and increment the workspace refresh key;
- fetch `TypeError` → backend-unavailable message naming `VITE_CASE_API_BASE_URL` and advising that the PoC backend be started;
- all other problem responses → display `detail`, otherwise `title`, otherwise `Request failed with status {status}`.

Keep one coherent workspace error panel and never render stale sections alongside a failed refresh.

- [ ] **Step 4: Verify error tests GREEN**

Run `npm test -- src/app-errors.test.tsx` and then `npm test`.

- [ ] **Step 5: Apply the approved visual system as a green refactor**

Import IBM Plex Sans Variable and IBM Plex Mono in `main.tsx`. In `index.css`, define the approved palette as CSS variables and map shadcn semantic tokens to registry blue, steel, signal teal, deadline amber, breach red, and paper white. Implement:

- 320px case rail plus flexible workspace above 900px;
- stacked rail/workspace below 900px and a 360px minimum-width layout;
- `.case-spine` vertical rule with current, complete, and observed markers;
- mono utility style for IDs/timestamps;
- visible `:focus-visible` outlines;
- no state conveyed by color without adjacent text; and
- `@media (prefers-reduced-motion: reduce)` disabling entrance/transition animation.

Take desktop and mobile screenshots during implementation, critique against the approved design, and remove any decorative card/shadow/gradient that does not clarify case state.

- [ ] **Step 6: Write the standalone README**

Document exact prerequisites and commands:

```bash
# Terminal 1: start Oracle, build, and run the PoC backend
docker compose up -d oracle
export CASEMGMT_WEBHOOK_SECRET_ENCRYPTION_KEY="$(openssl rand -base64 32)"
./mvnw -B -DskipTests package
java -jar case-management-poc-app/target/case-management-poc-app-0.1.0-SNAPSHOT.jar

# Terminal 2: start the standalone React demo
cd case-management-react-demo
npm install
VITE_CASE_API_PROXY_TARGET=http://localhost:8080 npm run dev
```

State that the local PoC defaults to HTTP Basic and seeds `alice` / `alice` as the tutorial caseworker, credentials remain in browser memory, `VITE_CASE_API_BASE_URL` defaults to `/case-api/v2`, and production hosting must reverse-proxy `/case-api` to the backend. Include `npm test`, `npm run lint`, `npm run build`, and `npm run preview` commands. Explicitly state that this directory is not part of the Maven reactor or library package.

- [ ] **Step 7: Run final automated gates**

Run:

```bash
cd case-management-react-demo
npm test
npm run lint
npm run build
git diff --check
```

Expected: every test passes, lint exits 0, Vite build succeeds, and diff hygiene is clean.

- [ ] **Step 8: Run the live smoke and visual checks**

Against the running PoC backend:

1. Connect with Basic credentials.
2. Create a complaint.
3. Verify the new case is selected and canonical fields render.
4. Claim the current task if offered.
5. Complete it with the contract-driven form.
6. Confirm task, spine, SLA, and event content refresh from REST data.
7. Inspect at 1440×900 and 390×844.
8. Navigate the complete flow with keyboard only and repeat with reduced motion enabled.

Expected: no mock data, no console errors, no horizontal overflow, visible focus, and all actions reflect server responses.

- [ ] **Step 9: Commit Task 5**

```bash
git add case-management-react-demo docs/superpowers/specs/2026-09-01-react-case-demo-design.md
git commit -m "docs(demo): document standalone case workspace"
```

---

## Plan Self-Review

- Spec coverage: connection, case list/search/create, parallel workspace, SLA/events/spine, contract-driven claim/complete, error states, responsive design, testing, live smoke, and isolation all map to Tasks 1–5.
- Scope: one SPA and one public REST integration; no independent subsystem needs a separate plan.
- Type consistency: `CaseApiClient`, `CaseSummary`, `TaskSummary`, `CaseWorkspaceSnapshot`, and `TaskFormDefinition` are introduced before their consumers and keep the same names throughout.
- Placeholder scan: no deferred marker or unspecified test step remains.
- Generated-code ruling: create-vite and shadcn primitive output is accepted as generated foundation; all handwritten behavior is test-first.

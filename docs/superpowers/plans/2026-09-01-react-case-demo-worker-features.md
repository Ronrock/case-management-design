# React Case Demo Worker Features Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a request-driven work inbox, advertised case actions, an SLA cockpit, and a combined activity/comments view to the standalone React demo.

**Architecture:** Keep `CaseDemo` as the authenticated shell and shared selection owner, with React state switching between My Work and Cases. Add focused feature components that consume typed `CaseApiClient` methods; mutations increment a shared refresh generation so affected REST projections reload without polling.

**Tech Stack:** React 19, TypeScript 6, Vite 8, shadcn-owned Radix components, Tailwind CSS 4, Vitest 4, React Testing Library.

**Spec:** `docs/superpowers/specs/2026-09-01-react-case-demo-worker-features-design.md`

## Global Constraints

- Keep the demo standalone: do not add it to the Maven reactor, an npm workspace, or `case-management-web-components`.
- Consume only the live public `/case-api/v2` REST contract; do not add backend changes or mock-data mode.
- Use React state rather than a routing dependency.
- Use request-driven refreshes only: entry, explicit Refresh, and successful mutation; no interval, SSE, or WebSocket.
- Render case, task, SLA, and collaboration controls only from their advertised action collections.
- Preserve Basic credentials in React memory only.
- Preserve the existing visual language and 360 CSS-pixel minimum layout.
- Preserve the unstaged native-fetch receiver fix and root-relative action normalization already present in `src/lib/case-api-client.ts` and its test.

## File Structure

- Modify `case-management-react-demo/src/lib/api-types.ts` — REST representations for collaboration actions, comments, and full SLA detail.
- Modify `case-management-react-demo/src/lib/case-api-client.ts` — typed worklist, advertised resource actions, SLA actions, and comments.
- Modify `case-management-react-demo/src/lib/case-api-client.test.ts` — request contract and action URL regression tests.
- Modify `case-management-react-demo/src/features/workspace/case-workspace.test.tsx` — keep the existing SLA fixture aligned with the REST representation.
- Create `case-management-react-demo/src/features/work/work-inbox.tsx` — request-driven grouped task inbox.
- Create `case-management-react-demo/src/features/work/work-inbox.test.tsx` — inbox grouping, refresh, and navigation behavior.
- Modify `case-management-react-demo/src/features/cases/case-demo.tsx` — primary navigation, shared selection, and refresh generation.
- Modify `case-management-react-demo/src/features/cases/case-demo.test.tsx` — shell integration and task-to-case navigation.
- Create `case-management-react-demo/src/features/cases/case-action-bar.tsx` — update, close, and cancel dialogs.
- Create `case-management-react-demo/src/features/cases/case-action-bar.test.tsx` — advertised action and concurrency behavior.
- Create `case-management-react-demo/src/features/slas/sla-cockpit.tsx` — all clocks, timing labels, pause, and resume.
- Create `case-management-react-demo/src/features/slas/sla-cockpit.test.tsx` — clock rendering and mutations.
- Create `case-management-react-demo/src/features/activity/activity-panel.tsx` — merged events/comments and comment composer.
- Create `case-management-react-demo/src/features/activity/activity-panel.test.tsx` — ordering, visibility, submission, and errors.
- Modify `case-management-react-demo/src/features/workspace/case-workspace.tsx` — Overview/Activity navigation and focused-task presentation.
- Modify `case-management-react-demo/src/features/workspace/case-workspace.test.tsx` — workspace integration and refresh behavior.
- Modify `case-management-react-demo/src/features/tasks/task-actions.tsx` — notify the shared shell after task mutation.
- Modify `case-management-react-demo/src/index.css` — shell navigation, inbox, SLA, activity, and responsive styling.
- Modify `case-management-react-demo/README.md` — describe the four new live-backend capabilities.

---

### Task 1: Complete the typed REST action boundary

**Files:**
- Modify: `case-management-react-demo/src/lib/api-types.ts`
- Modify: `case-management-react-demo/src/lib/case-api-client.ts`
- Modify: `case-management-react-demo/src/lib/case-api-client.test.ts`
- Modify: `case-management-react-demo/src/features/workspace/case-workspace.test.tsx`

**Interfaces:**
- Produces: `CaseComment`, expanded `SlaSummary`, and `CaseSummary.collaborationActions`.
- Produces: `listTasks(): Promise<TaskSummary[]>`.
- Produces: `executeCaseAction(action, version, payload): Promise<CaseSummary>`.
- Produces: `executeSlaAction(action, version, reason?): Promise<SlaSummary>`.
- Produces: `listComments(caseId): Promise<CaseComment[]>` and `addComment(action, text): Promise<CaseComment>`.
- Preserves: `executeTaskAction`, receiver-safe `fetchImpl`, and safe API-relative action normalization.
- Changes: the workspace event request from `limit=25` to `limit=100`, matching the Activity contract.

- [ ] **Step 1: Write failing request-contract tests**

Add tests that use a recording fetch and literal expectations:

```ts
it('executes advertised case and SLA actions with resource versions', async () => {
  const calls: Array<{ url: string; init: RequestInit }> = []
  const fetchImpl = (async (input: RequestInfo | URL, init: RequestInit = {}) => {
    calls.push({ url: String(input), init })
    return new Response('{}', { headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch
  const client = new CaseApiClient({
    baseUrl: '/case-api/v2',
    credentials: { username: 'alice', password: 'alice' },
    fetchImpl,
  })

  await client.executeCaseAction(
    { action: 'update', name: 'Update', href: '/cases/case-1', method: 'PATCH' },
    3,
    { title: 'Updated title' },
  )
  await client.executeSlaAction(
    { action: 'pause', name: 'Pause', href: '/cases/case-1/slas/sla-1/pause', method: 'POST' },
    5,
    'Waiting for customer',
  )

  expect(calls.map(({ url }) => url)).toEqual([
    '/case-api/v2/cases/case-1',
    '/case-api/v2/cases/case-1/slas/sla-1/pause',
  ])
  expect(new Headers(calls[0].init.headers).get('If-Match')).toBe('"3"')
  expect(new Headers(calls[0].init.headers).get('Content-Type')).toBe('application/merge-patch+json')
  expect(JSON.parse(String(calls[0].init.body))).toEqual({ title: 'Updated title' })
  expect(new Headers(calls[1].init.headers).get('If-Match')).toBe('"5"')
  expect(JSON.parse(String(calls[1].init.body))).toEqual({ reason: 'Waiting for customer' })
})

it('lists work and posts an internal comment through its advertised action', async () => {
  const calls: Array<{ url: string; init: RequestInit }> = []
  const fetchImpl = (async (input: RequestInfo | URL, init: RequestInit = {}) => {
    calls.push({ url: String(input), init })
    return new Response(init.method === 'POST'
      ? JSON.stringify({ id: 'comment-1', caseId: 'case-1', text: 'Checked', visibility: 'internal' })
      : '[]', { headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch
  const client = new CaseApiClient({
    baseUrl: '/case-api/v2', credentials: { username: 'alice', password: 'alice' }, fetchImpl,
  })

  await client.listTasks()
  await client.listComments('case-1')
  await client.addComment(
    { action: 'comment', name: 'Comment', href: '/cases/case-1/comments', method: 'POST' },
    'Checked',
  )

  expect(calls.map(({ url }) => url)).toEqual([
    '/case-api/v2/tasks',
    '/case-api/v2/cases/case-1/comments',
    '/case-api/v2/cases/case-1/comments',
  ])
  expect(JSON.parse(String(calls[2].init.body))).toEqual({ text: 'Checked', visibility: 'internal' })
})

it('loads enough case events for the activity view', async () => {
  const requested: string[] = []
  const fetchImpl = (async (input: RequestInfo | URL) => {
    requested.push(String(input))
    const body = String(input).endsWith('/cases/case-1') ? '{}': '[]'
    return new Response(body, { headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch
  const client = new CaseApiClient({
    baseUrl: '/case-api/v2', credentials: { username: 'alice', password: 'alice' }, fetchImpl,
  })

  await client.loadWorkspace('case-1')

  expect(requested).toContain('/case-api/v2/cases/case-1/events?after=0&limit=100')
})
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `npm test -- src/lib/case-api-client.test.ts`

Expected: FAIL because the new methods and response fields do not exist.

- [ ] **Step 3: Add the exact REST types**

Add these fields and interfaces to `api-types.ts`:

```ts
export interface CaseSummary {
  // existing fields stay unchanged
  collaborationActions?: AvailableAction[]
}

export interface SlaSummary {
  // existing fields stay unchanged
  pausedAt?: string | null
  pausedReason?: string | null
  pausedTotalSeconds?: number
  availableActions: AvailableAction[]
}

export interface CaseComment {
  id: string
  caseId: string
  author?: string | null
  text?: string | null
  visibility?: string | null
  createdAt?: string | null
}
```

Add `availableActions: []` to the existing SLA fixture in `case-workspace.test.tsx`. Treat `CaseSummary.collaborationActions` as optional so existing tests and older PoC responses remain readable; feature code always uses `caseItem.collaborationActions ?? []`.

- [ ] **Step 4: Implement the minimal client methods**

Add the public methods and keep action normalization private:

```ts
listTasks(): Promise<TaskSummary[]> {
  return this.request('/tasks')
}

executeCaseAction(
  action: AvailableAction,
  version: number,
  payload?: Record<string, unknown>,
): Promise<CaseSummary> {
  return this.executeVersionedAction(action, version, payload,
    action.action === 'update' ? 'application/merge-patch+json' : 'application/json')
}

executeSlaAction(action: AvailableAction, version: number, reason?: string): Promise<SlaSummary> {
  return this.executeVersionedAction(action, version,
    action.action === 'pause' ? { reason } : undefined, 'application/json')
}

listComments(caseId: string): Promise<CaseComment[]> {
  return this.request(`/cases/${encodeURIComponent(caseId)}/comments`)
}

addComment(action: AvailableAction, text: string): Promise<CaseComment> {
  return this.request(this.actionPath(action.href), {
    method: action.method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text, visibility: 'internal' }),
  })
}

private executeVersionedAction<T>(
  action: AvailableAction,
  version: number,
  payload?: Record<string, unknown>,
  contentType = 'application/json',
): Promise<T> {
  const headers: Record<string, string> = { 'If-Match': `"${version}"` }
  let body: string | undefined
  if (payload !== undefined) {
    headers['Content-Type'] = contentType
    body = JSON.stringify(payload)
  }
  return this.request(this.actionPath(action.href), { method: action.method, headers, body })
}
```

Change the event request inside `loadWorkspace` to `/events?after=0&limit=100`.

Refactor `executeTaskAction` only enough to share safe URL handling; retain its `Idempotency-Key` and completion body contract.

- [ ] **Step 5: Run focused and full client-adjacent tests**

Run: `npm test -- src/lib/case-api-client.test.ts src/features/tasks/task-actions.test.tsx`

Expected: PASS, including the existing illegal-invocation and root-relative task action regressions.

- [ ] **Step 6: Commit the REST client slice**

```bash
git add case-management-react-demo/src/lib/api-types.ts \
  case-management-react-demo/src/lib/case-api-client.ts \
  case-management-react-demo/src/lib/case-api-client.test.ts \
  case-management-react-demo/src/features/workspace/case-workspace.test.tsx
git commit -m "feat(demo): add typed worker action APIs"
```

---

### Task 2: Add My Work and shell navigation

**Files:**
- Create: `case-management-react-demo/src/features/work/work-inbox.tsx`
- Create: `case-management-react-demo/src/features/work/work-inbox.test.tsx`
- Modify: `case-management-react-demo/src/features/cases/case-demo.tsx`
- Modify: `case-management-react-demo/src/features/cases/case-demo.test.tsx`
- Modify: `case-management-react-demo/src/index.css`

**Interfaces:**
- Consumes: `CaseApiClient.listTasks()` and `TaskSummary`.
- Produces: `WorkInbox({ client, username, refreshKey, onOpenTask })`.
- Produces: `CaseDemo` state for active view, selected case, highlighted task, and shared refresh generation.

- [ ] **Step 1: Write the failing inbox component test**

At the top of the new test file, define the client locally so the test has no hidden dependency:

```ts
function client() {
  return new CaseApiClient({
    baseUrl: '/case-api/v2', credentials: { username: 'alice', password: 'alice' },
  })
}
```

```tsx
it('groups assigned and claimable work and opens its owning case', async () => {
  installFetchScript(() => ({ body: [
    { id: 'assigned', caseId: 'case-1', name: 'Assess complaint', state: 'CLAIMED', assignee: 'alice', candidateGroups: [], version: 2, availableActions: [] },
    { id: 'claimable', caseId: 'case-2', name: 'Register complaint', state: 'OPEN', candidateGroups: ['complaints-handlers'], version: 1, availableActions: [{ action: 'claim', name: 'Claim', href: '/tasks/claimable/claim', method: 'POST' }] },
  ] }))
  const onOpenTask = vi.fn()
  const user = userEvent.setup()
  render(<WorkInbox client={client()} username="alice" refreshKey={0} onOpenTask={onOpenTask} />)

  expect(await screen.findByRole('heading', { name: 'Assigned to me' })).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: 'Available to claim' })).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: /Register complaint/ }))
  expect(onOpenTask).toHaveBeenCalledWith(expect.objectContaining({ id: 'claimable', caseId: 'case-2' }))
})
```

- [ ] **Step 2: Run the inbox test and verify RED**

Run: `npm test -- src/features/work/work-inbox.test.tsx`

Expected: FAIL because `WorkInbox` does not exist.

- [ ] **Step 3: Implement grouped request-driven work**

Create `WorkInbox` with this public contract:

```tsx
interface WorkInboxProps {
  client: CaseApiClient
  username: string
  refreshKey: number
  onOpenTask(task: TaskSummary): void
}
```

Load on `[client, refreshKey]`, classify assigned first, then claimable, then remaining visible tasks, and render an explicit Refresh button that increments local retry state. Use task id as the list key and a real `<button>` for each selectable row.

Render distinct empty copy for no assigned work, no claimable work, and an entirely empty worklist.

- [ ] **Step 4: Run the inbox test and verify GREEN**

Run: `npm test -- src/features/work/work-inbox.test.tsx`

Expected: PASS.

- [ ] **Step 5: Write the failing shell-navigation test**

Extend `case-demo.test.tsx`:

```tsx
it('opens a work item in the shared case workspace', async () => {
  installFetchScript((call) => {
    if (call.url === '/case-api/v2/tasks') return { body: [{
      id: 'task-2', caseId: 'case-2', name: 'Register complaint', state: 'OPEN',
      candidateGroups: ['complaints-handlers'], version: 1,
      availableActions: [{ action: 'claim', name: 'Claim', href: '/tasks/task-2/claim', method: 'POST' }],
    }] }
    if (call.url.endsWith('/cases')) return { body: page([caseItem('case-2', 'Card complaint')]) }
    if (call.url.endsWith('/cases/case-2')) return { body: caseItem('case-2', 'Card complaint') }
    return { body: [] }
  })
  const user = userEvent.setup()
  render(<CaseDemo client={client()} username="alice" initialPage={page([])} />)

  await user.click(screen.getByRole('button', { name: 'My Work' }))
  await user.click(await screen.findByRole('button', { name: /Register complaint/ }))
  expect(await screen.findByRole('heading', { name: 'Card complaint' })).toBeInTheDocument()
})
```

- [ ] **Step 6: Run the shell test and verify RED**

Run: `npm test -- src/features/cases/case-demo.test.tsx`

Expected: FAIL because the shell has no My Work navigation.

- [ ] **Step 7: Implement shell state and shared navigation**

Add:

```ts
const [activeView, setActiveView] = useState<'work' | 'cases'>('cases')
const [highlightedTaskId, setHighlightedTaskId] = useState<string>()
const [refreshKey, setRefreshKey] = useState(0)
```

Render header buttons with `aria-pressed`. `onOpenTask` sets the case id and highlighted task before switching to Cases. Pass `highlightedTaskId` and `onDataChanged={() => setRefreshKey(value => value + 1)}` to `CaseWorkspace`. Make the case-list reload effect depend on `refreshKey` while preserving a just-created case until the next successful server list includes it.

- [ ] **Step 8: Add focused responsive styles**

Add `.primary-nav`, `.work-inbox`, `.work-group`, and `.work-row` rules. At 520px, allow navigation and rows to wrap; keep all state labels textual.

- [ ] **Step 9: Run the shell and inbox tests**

Run: `npm test -- src/features/work/work-inbox.test.tsx src/features/cases/case-demo.test.tsx`

Expected: PASS.

- [ ] **Step 10: Commit the My Work slice**

```bash
git add case-management-react-demo/src/features/work \
  case-management-react-demo/src/features/cases/case-demo.tsx \
  case-management-react-demo/src/features/cases/case-demo.test.tsx \
  case-management-react-demo/src/index.css
git commit -m "feat(demo): add request-driven work inbox"
```

---

### Task 3: Add advertised case lifecycle actions

**Files:**
- Create: `case-management-react-demo/src/features/cases/case-action-bar.tsx`
- Create: `case-management-react-demo/src/features/cases/case-action-bar.test.tsx`
- Modify: `case-management-react-demo/src/features/workspace/case-workspace.tsx`

**Interfaces:**
- Consumes: `CaseApiClient.executeCaseAction`, `CaseSummary.availableActions`, and `ApiError`.
- Produces: `CaseActionBar({ client, caseItem, onChanged })`.

- [ ] **Step 1: Write failing advertised-action tests**

Define `client()` in this test file exactly as in Task 2 and define a complete literal fixture:

```ts
const caseItem: CaseSummary = {
  id: 'case-1', tenantId: 't1', caseDefinitionKey: 'complaint', caseDefinitionVersion: 1,
  title: 'Card complaint', state: 'ACTIVE', version: 3, availableActions: [],
}
```

```tsx
it('renders only advertised actions and updates the title with the case version', async () => {
  const calls = installFetchScript(() => ({ body: caseItem }))
  const user = userEvent.setup()
  render(<CaseActionBar client={client()} caseItem={{
    ...caseItem, version: 3,
    availableActions: [{ action: 'update', name: 'Update', href: '/cases/case-1', method: 'PATCH' }],
  }} onChanged={vi.fn()} />)

  expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Update' }))
  await user.clear(screen.getByLabelText('Title'))
  await user.type(screen.getByLabelText('Title'), 'Updated complaint')
  await user.click(screen.getByRole('button', { name: 'Save changes' }))

  const request = calls.find((call) => call.init.method === 'PATCH')!
  expect(new Headers(request.init.headers).get('If-Match')).toBe('"3"')
  expect(JSON.parse(String(request.init.body))).toEqual({ title: 'Updated complaint' })
})
```

Add separate tests for Close with optional `{ outcome }`, Cancel rejecting blank reason and sending `{ reason }`, `403` inline guidance, and `412` invoking `onChanged` while showing stale-data guidance. Use literal advertised action fixtures even though the current complaint policy normally exposes Update and Cancel rather than Close.

- [ ] **Step 2: Run the component tests and verify RED**

Run: `npm test -- src/features/cases/case-action-bar.test.tsx`

Expected: FAIL because `CaseActionBar` does not exist.

- [ ] **Step 3: Implement one action bar with three bounded dialogs**

Use `action.action` to select `update`, `close`, or `cancel`; ignore unknown actions. Maintain one `pendingAction`, one `busy` flag, form state for title/outcome/reason, and one inline error. Call:

```ts
const payload = pendingAction.action === 'update' ? { title }
  : pendingAction.action === 'close' ? { outcome: outcome || undefined }
  : { reason }
await client.executeCaseAction(pendingAction, caseItem.version, payload)
```

For `412`, set `This case changed on the server. The workspace has been refreshed.` and invoke `onChanged`. For `403`, set `The backend refused this action for your account.` Close the dialog only on success.

- [ ] **Step 4: Integrate the bar in the workspace heading**

Render `CaseActionBar` beside state badges and route `onChanged` to the workspace refresh callback. Do not add hardcoded lifecycle buttons in `CaseWorkspace`.

- [ ] **Step 5: Run case action and workspace tests**

Run: `npm test -- src/features/cases/case-action-bar.test.tsx src/features/workspace/case-workspace.test.tsx`

Expected: PASS.

- [ ] **Step 6: Commit case actions**

```bash
git add case-management-react-demo/src/features/cases/case-action-bar.tsx \
  case-management-react-demo/src/features/cases/case-action-bar.test.tsx \
  case-management-react-demo/src/features/workspace/case-workspace.tsx
git commit -m "feat(demo): add advertised case actions"
```

---

### Task 4: Replace the SLA summary with an action-capable cockpit

**Files:**
- Create: `case-management-react-demo/src/features/slas/sla-cockpit.tsx`
- Create: `case-management-react-demo/src/features/slas/sla-cockpit.test.tsx`
- Modify: `case-management-react-demo/src/features/workspace/case-workspace.tsx`
- Modify: `case-management-react-demo/src/index.css`

**Interfaces:**
- Consumes: `SlaSummary.availableActions`, `CaseApiClient.executeSlaAction`, and `ApiError`.
- Produces: `SlaCockpit({ client, slas, onChanged })`.

- [ ] **Step 1: Write failing rendering and timing tests**

```tsx
it('shows every SLA with warning, pause, and overdue details', () => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-09-01T12:00:00Z'))
  render(<SlaCockpit client={client()} onChanged={vi.fn()} slas={[
    { id: 'sla-1', targetId: 'firstResponse', status: 'RUNNING', dueAt: '2026-09-01T14:00:00Z', warnAt: '2026-09-01T13:00:00Z', version: 1, availableActions: [] },
    { id: 'sla-2', targetId: 'resolution', status: 'PAUSED', dueAt: '2026-08-31T12:00:00Z', pausedReason: 'Waiting for customer', pausedTotalSeconds: 3600, version: 2, availableActions: [] },
  ]} />)

  expect(screen.getByText('First Response')).toBeInTheDocument()
  expect(screen.getByText('Due in 2 hours')).toBeInTheDocument()
  expect(screen.getByText('Resolution')).toBeInTheDocument()
  expect(screen.getByText('Waiting for customer')).toBeInTheDocument()
  vi.useRealTimers()
})
```

Define `client()` locally as in Task 2. Restore real timers in this file's `afterEach` as well, so a failed assertion cannot leak fake time into another test.

- [ ] **Step 2: Write failing pause/resume action tests**

Advertise Pause only on the RUNNING fixture and Resume only on the PAUSED fixture. Assert Pause requires a non-blank reason, sends the SLA version, and invokes `onChanged`; assert Resume sends no body and invokes `onChanged`.

- [ ] **Step 3: Run the SLA tests and verify RED**

Run: `npm test -- src/features/slas/sla-cockpit.test.tsx`

Expected: FAIL because the cockpit does not exist.

- [ ] **Step 4: Implement deterministic clock presentation**

Create pure helpers in the component module:

```ts
export function describeDeadline(dueAt: string | null | undefined, now: Date): string {
  if (!dueAt) return 'No deadline'
  const milliseconds = new Date(dueAt).getTime() - now.getTime()
  if (!Number.isFinite(milliseconds)) return 'Deadline unavailable'
  const hours = Math.max(1, Math.ceil(Math.abs(milliseconds) / 3_600_000))
  return milliseconds < 0 ? `Overdue by ${hours} ${hours === 1 ? 'hour' : 'hours'}`
    : `Due in ${hours} ${hours === 1 ? 'hour' : 'hours'}`
}
```

The component calls `describeDeadline(sla.dueAt, new Date())`; `now` is not exposed as a production prop.

Render all clocks as cards. Render action buttons solely by finding `pause` or `resume` in each clock's `availableActions`. Keep `403` and `412` messages local; `412` invokes `onChanged`.

When `slas` is empty, render `No SLA clocks are active for this case.` instead of an empty grid.

- [ ] **Step 5: Replace the nearest-SLA card and run GREEN**

Remove `chooseSla` from `CaseWorkspace`, render `<SlaCockpit ... />`, and run:

`npm test -- src/features/slas/sla-cockpit.test.tsx src/features/workspace/case-workspace.test.tsx`

Expected: PASS.

- [ ] **Step 6: Add cockpit styles and commit**

Add responsive `.sla-grid` and `.sla-card` rules, then:

```bash
git add case-management-react-demo/src/features/slas \
  case-management-react-demo/src/features/workspace/case-workspace.tsx \
  case-management-react-demo/src/index.css
git commit -m "feat(demo): add SLA cockpit controls"
```

---

### Task 5: Add the combined Activity and comments view

**Files:**
- Create: `case-management-react-demo/src/features/activity/activity-panel.tsx`
- Create: `case-management-react-demo/src/features/activity/activity-panel.test.tsx`
- Modify: `case-management-react-demo/src/features/workspace/case-workspace.tsx`
- Modify: `case-management-react-demo/src/index.css`

**Interfaces:**
- Consumes: `CaseApiClient.listComments`, `CaseApiClient.addComment`, `CaseEvent[]`, and `CaseSummary.collaborationActions`.
- Produces: `ActivityPanel({ client, caseItem, events, refreshKey, onChanged })`.

- [ ] **Step 1: Write the failing chronological activity test**

Define `client()` locally as in Task 2 and use this case fixture:

```ts
const caseItem: CaseSummary = {
  id: 'case-1', tenantId: 't1', caseDefinitionKey: 'complaint', caseDefinitionVersion: 1,
  title: 'Card complaint', state: 'ACTIVE', version: 3, availableActions: [],
  collaborationActions: [],
}
```

```tsx
it('merges comments and events newest first while preserving their kinds', async () => {
  installFetchScript(() => ({ body: [{
    id: 'comment-1', caseId: 'case-1', author: 'alice', text: 'Customer called',
    visibility: 'internal', createdAt: '2026-09-01T10:00:00Z',
  }] }))
  render(<ActivityPanel client={client()} caseItem={caseItem} refreshKey={0} onChanged={vi.fn()} events={[
    { id: 'event-1', type: 'case.task.completed', time: '2026-09-01T11:00:00Z' },
  ]} />)

  const entries = await screen.findAllByRole('listitem')
  expect(entries[0]).toHaveTextContent('Task Completed')
  expect(entries[0]).toHaveTextContent('System event')
  expect(entries[1]).toHaveTextContent('Customer called')
  expect(entries[1]).toHaveTextContent('alice')
})
```

- [ ] **Step 2: Write failing comment-composer tests**

Use a case fixture whose `collaborationActions` contains `comment`. Assert the composer is absent without that action, blank text does not POST, a valid comment sends internal visibility through the advertised href, clears the textarea, and invokes `onChanged`. Add a `403` local-error case; the comment endpoint is not versioned and therefore has no `412` workflow.

- [ ] **Step 3: Run activity tests and verify RED**

Run: `npm test -- src/features/activity/activity-panel.test.tsx`

Expected: FAIL because `ActivityPanel` does not exist.

- [ ] **Step 4: Implement normalization and request-driven comments**

Use this presentation boundary:

```ts
interface ActivityEntry {
  id: string
  kind: 'event' | 'comment'
  label: string
  detail?: string
  actor?: string
  time?: string | null
}
```

Load comments when `caseItem.id`, `client`, or `refreshKey` changes. Convert events without calling them audit records, merge with comments, and sort by `(time ?? '').localeCompare(...)` descending with `id` as a deterministic tie-breaker. Show comment visibility as text.

When both sources are empty, render `No activity has been recorded for this case.`

- [ ] **Step 5: Integrate Overview and Activity navigation**

Add local workspace state:

```ts
const [section, setSection] = useState<'overview' | 'activity'>('overview')
```

Render two semantic buttons with `aria-pressed`. Overview contains facts, SLA cockpit, tasks, and spine. Activity contains `ActivityPanel`. Reset to Overview when `caseId` changes so a new case never opens on stale activity context.

- [ ] **Step 6: Run activity and workspace tests**

Run: `npm test -- src/features/activity/activity-panel.test.tsx src/features/workspace/case-workspace.test.tsx`

Expected: PASS.

- [ ] **Step 7: Add activity styles and commit**

Add `.workspace-tabs`, `.activity-list`, `.activity-event`, `.activity-comment`, and mobile wrapping styles, then:

```bash
git add case-management-react-demo/src/features/activity \
  case-management-react-demo/src/features/workspace/case-workspace.tsx \
  case-management-react-demo/src/index.css
git commit -m "feat(demo): add case activity and comments"
```

---

### Task 6: Prove shared refresh, focused work, and recovery behavior

**Files:**
- Modify: `case-management-react-demo/src/features/tasks/task-actions.tsx`
- Modify: `case-management-react-demo/src/features/tasks/task-actions.test.tsx`
- Modify: `case-management-react-demo/src/features/workspace/case-workspace.tsx`
- Modify: `case-management-react-demo/src/features/workspace/case-workspace.test.tsx`
- Modify: `case-management-react-demo/src/features/cases/case-demo.test.tsx`

**Interfaces:**
- Consumes: the shared `onDataChanged` callback from `CaseDemo`.
- Produces: one refresh path for task, case, SLA, and comment mutations.

- [ ] **Step 1: Write the failing post-mutation refresh test**

In `case-demo.test.tsx`, script two `/tasks` responses and count workspace case reads. Claim from the selected workspace, navigate back to My Work, and assert the second worklist response appears. This proves a mutation invalidates both consumers rather than only rerendering the task card.

```tsx
expect(calls.filter((call) => call.url === '/case-api/v2/tasks').length).toBe(2)
expect(await screen.findByText('Assigned to me')).toBeInTheDocument()
```

- [ ] **Step 2: Write the failing focused-task test**

After opening a task from My Work, assert its workspace card has `data-highlighted="true"` and receives focus through a ref after loading. The focus target is the card heading button or card container with `tabIndex={-1}`, not a hidden element.

- [ ] **Step 3: Run integration tests and verify RED**

Run: `npm test -- src/features/cases/case-demo.test.tsx src/features/workspace/case-workspace.test.tsx src/features/tasks/task-actions.test.tsx`

Expected: FAIL on shared invalidation or focused-task behavior.

- [ ] **Step 4: Implement one shared mutation callback**

Every successful task, case, SLA, or comment mutation calls the callback supplied by `CaseDemo`. Increment refresh generation once per successful mutation. `CaseWorkspace` uses the generation in its request key; `WorkInbox` reloads the next time it is visible or when its current generation changes.

- [ ] **Step 5: Implement highlighted-task focus without scrolling unrelated cases**

Set `data-highlighted` only on the matching task card and focus it after the workspace snapshot containing that id has rendered. Clear the highlighted id when the user selects a case from the case rail.

- [ ] **Step 6: Add `403` and `412` integration assertions**

Use problem-detail responses from `installFetchScript`. Assert `403` leaves the active view and existing snapshot intact. Assert `412` shows stale guidance and causes another relevant GET. Do not assert exact backend prose beyond the stable user-facing copy owned by the demo.

- [ ] **Step 7: Add and prove explicit workspace refresh**

Add a Refresh button to the workspace heading. It calls the shared `onDataChanged` callback and therefore reloads the case snapshot and invalidates My Work without starting a timer. In `case-workspace.test.tsx`, click Refresh and assert the case-detail GET count changes from one to two.

- [ ] **Step 8: Run the complete frontend test suite**

Run: `npm test`

Expected: all test files pass, including `app-errors.test.tsx` coverage of the existing global `401` recovery, with no unhandled rejection or console warning.

- [ ] **Step 9: Commit refresh and recovery integration**

```bash
git add case-management-react-demo/src/features/tasks/task-actions.tsx \
  case-management-react-demo/src/features/tasks/task-actions.test.tsx \
  case-management-react-demo/src/features/workspace/case-workspace.tsx \
  case-management-react-demo/src/features/workspace/case-workspace.test.tsx \
  case-management-react-demo/src/features/cases/case-demo.test.tsx
git commit -m "feat(demo): unify worker data refresh"
```

---

### Task 7: Documentation, quality gates, and live flow

**Files:**
- Modify: `case-management-react-demo/README.md`
- Verify: all files changed by Tasks 1-6

**Interfaces:**
- Consumes: the complete worker feature set.
- Produces: startup instructions and repeatable acceptance evidence.

- [ ] **Step 1: Update the README capability description**

State that the live demo now provides My Work, advertised case actions, all SLA clocks with pause/resume, and an events/comments activity view. Preserve the existing backend and Vite startup commands and the warning that credentials live only in browser memory.

- [ ] **Step 2: Run all automated gates fresh**

Run:

```bash
npm test
npm run lint
npm run build
git diff --check
```

Expected: zero test failures, zero lint errors, successful TypeScript/Vite build, and no whitespace errors.

- [ ] **Step 3: Run the live request-driven walkthrough**

Against the local PoC backend and Vite server:

1. Connect with the documented tutorial caseworker.
2. Open My Work and use Refresh.
3. Select claimable work and confirm its case opens with the task highlighted.
4. Claim the task and confirm the workspace refreshes.
5. Update the case title if Update is advertised.
6. Pause an advertised running SLA with reason `Waiting for customer`, verify the paused state, then Resume it.
7. Open Activity, add `Customer contacted`, and verify the new comment appears with recent events.
8. Confirm Cancel appears only when advertised; do not submit it during the smoke test because that would terminate the walkthrough case.

Expected: every action uses the live REST backend, no periodic requests appear while idle, and no browser console error is produced.

- [ ] **Step 4: Inspect the final branch diff**

Run:

```bash
git status --short
git diff --stat d7ad822..HEAD
git log --oneline -8
```

Expected: only the standalone demo and its design/plan documents changed; no backend, Maven reactor, or published web-component files appear.

- [ ] **Step 5: Commit documentation**

```bash
git add case-management-react-demo/README.md
git commit -m "docs(demo): document worker feature showcase"
```

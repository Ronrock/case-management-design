import type { ApiCredentials, AvailableAction, CaseEvent, CaseSummary, CaseWorkspaceSnapshot, CreateComplaintInput, MilestoneSummary, Page, PlanItemSummary, SlaSummary, TaskFormDefinition, TaskSummary } from './api-types'

interface ProblemDetails {
  title?: string
  detail?: string
}

interface ClientOptions {
  baseUrl: string
  credentials: ApiCredentials
  fetchImpl?: typeof fetch
}

export class ApiError extends Error {
  status: number
  title?: string
  detail?: string

  constructor(status: number, problem: ProblemDetails = {}) {
    super(problem.detail ?? problem.title ?? `Request failed with status ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.title = problem.title
    this.detail = problem.detail
  }
}

export class CaseApiClient {
  private readonly baseUrl: string
  private readonly credentials: ApiCredentials
  private readonly fetchImpl: typeof fetch
  private unauthorizedHandler?: () => void

  constructor({ baseUrl, credentials, fetchImpl = fetch }: ClientOptions) {
    this.baseUrl = baseUrl.replace(/\/$/, '')
    this.credentials = credentials
    this.fetchImpl = fetchImpl
  }

  connect(): Promise<Page<CaseSummary>> {
    return this.request('/cases?pageSize=1')
  }

  onUnauthorized(handler: () => void) {
    this.unauthorizedHandler = handler
  }

  listCases(): Promise<Page<CaseSummary>> {
    return this.request('/cases')
  }

  createComplaint(input: CreateComplaintInput): Promise<CaseSummary> {
    const variables: Record<string, unknown> = {
      channel: input.channel,
      summary: input.summary,
    }
    if (input.amount !== undefined) variables.amount = input.amount

    return this.request('/cases', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID(),
      },
      body: JSON.stringify({
        caseDefinitionKey: 'complaint',
        tenantId: input.tenantId,
        businessKey: input.businessKey,
        title: input.title,
        priority: 'MEDIUM',
        variables,
      }),
    })
  }

  async loadWorkspace(caseId: string): Promise<CaseWorkspaceSnapshot> {
    const path = `/cases/${encodeURIComponent(caseId)}`
    const [caseItem, tasks, planItems, milestones, slas, events] = await Promise.all([
      this.request<CaseSummary>(path),
      this.request<TaskSummary[]>(`${path}/tasks`),
      this.request<PlanItemSummary[]>(`${path}/plan-items`),
      this.request<MilestoneSummary[]>(`${path}/milestones`),
      this.request<SlaSummary[]>(`${path}/slas`),
      this.request<CaseEvent[]>(`${path}/events?after=0&limit=25`),
    ])
    return { case: caseItem, tasks, planItems, milestones, slas, events }
  }

  async taskForm(caseItem: CaseSummary, task: TaskSummary): Promise<TaskFormDefinition> {
    const action = task.availableActions.find((candidate) => candidate.action === 'complete')
    const formKey = action?.formKey ?? task.formKey
    if (!formKey) throw new Error('This task does not advertise a completion form.')
    const version = await this.request<{ contractReleaseId?: string }>(
      `/case-definitions/${encodeURIComponent(caseItem.caseDefinitionKey)}/versions/${caseItem.caseDefinitionVersion}`,
    )
    if (!version.contractReleaseId) throw new Error('The pinned case definition has no contract release.')
    const contract = await this.request<{ forms?: Record<string, TaskFormDefinition> }>(
      `/case-definitions/${encodeURIComponent(caseItem.caseDefinitionKey)}/contract-releases/${encodeURIComponent(version.contractReleaseId)}`,
    )
    const definition = contract.forms?.[formKey]
    if (!definition) throw new Error(`Form ${formKey} is not present in the pinned contract.`)
    return definition
  }

  executeTaskAction(action: AvailableAction, version: number, variables?: Record<string, unknown>): Promise<unknown> {
    const href = this.actionPath(action.href)
    const headers: Record<string, string> = {
      'If-Match': `"${version}"`,
      'Idempotency-Key': crypto.randomUUID(),
    }
    let body: string | undefined
    if (action.action === 'complete') {
      headers['Content-Type'] = 'application/json'
      body = JSON.stringify({ variables: variables ?? {} })
    }
    return this.request(href, { method: action.method, headers, body })
  }

  private actionPath(href: string) {
    if (/^https?:\/\//i.test(href)) {
      const url = new URL(href)
      if (typeof window === 'undefined' || url.origin !== window.location.origin) throw new Error('Refusing a cross-origin task action.')
      href = `${url.pathname}${url.search}`
    }
    if (!href.startsWith('/case-api/v2/')) throw new Error('Refusing a task action outside the case API.')
    return href
  }

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const headers = new Headers(init.headers)
    headers.set('Accept', 'application/json')
    headers.set(
      'Authorization',
      `Basic ${btoa(`${this.credentials.username}:${this.credentials.password}`)}`,
    )
    headers.set('X-Correlation-ID', crypto.randomUUID())

    const url = path.startsWith(this.baseUrl) ? path : `${this.baseUrl}${path}`
    let response: Response
    try {
      response = await this.fetchImpl(url, { ...init, headers })
    } catch (reason) {
      if (reason instanceof TypeError) {
        throw new Error(`Backend unavailable at ${this.baseUrl}. Start the PoC backend and try again.`)
      }
      throw reason
    }

    if (!response.ok) {
      let problem: ProblemDetails = {}
      try {
        problem = (await response.json()) as ProblemDetails
      } catch {
        // A response without problem JSON still has a useful status code.
      }
      const apiError = new ApiError(response.status, problem)
      if (response.status === 401) this.unauthorizedHandler?.()
      throw apiError
    }

    return (await response.json()) as T
  }
}

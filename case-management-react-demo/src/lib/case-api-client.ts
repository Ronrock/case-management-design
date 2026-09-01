import type { ApiCredentials, CaseSummary, Page } from './api-types'

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

  constructor({ baseUrl, credentials, fetchImpl = fetch }: ClientOptions) {
    this.baseUrl = baseUrl.replace(/\/$/, '')
    this.credentials = credentials
    this.fetchImpl = fetchImpl
  }

  connect(): Promise<Page<CaseSummary>> {
    return this.request('/cases?pageSize=1')
  }

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const headers = new Headers(init.headers)
    headers.set('Accept', 'application/json')
    headers.set(
      'Authorization',
      `Basic ${btoa(`${this.credentials.username}:${this.credentials.password}`)}`,
    )
    headers.set('X-Correlation-ID', crypto.randomUUID())

    const response = await this.fetchImpl(`${this.baseUrl}${path}`, { ...init, headers })

    if (!response.ok) {
      let problem: ProblemDetails = {}
      try {
        problem = (await response.json()) as ProblemDetails
      } catch {
        // A response without problem JSON still has a useful status code.
      }
      throw new ApiError(response.status, problem)
    }

    return (await response.json()) as T
  }
}

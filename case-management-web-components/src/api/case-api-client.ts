export interface CaseTypeSummary {
  key: string;
  name: string;
  version?: number;
}

export interface CaseSummary {
  id: string;
  caseDefinitionId?: string;
  caseDefinitionKey?: string;
  caseDefinitionVersion?: number;
  title?: string;
  state: string;
  priority?: string;
  updatedAt?: string;
  availableActions?: import("../presentation/presentation-manifest").AvailableAction[];
  [key: string]: unknown;
}

export interface TaskSummary {
  id: string;
  caseId: string;
  name: string;
  state: string;
  assignee?: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  pageSize: number;
  totalItems: number;
  totalPages: number;
}

export interface CaseApiClientOptions {
  baseUrl: string;
  getAccessToken: () => Promise<string | undefined>;
  correlationId?: () => string;
}

export class CaseApiClient {
  constructor(private readonly options: CaseApiClientOptions) {}

  caseTypes(): Promise<CaseTypeSummary[]> {
    return this.get("/case-definitions");
  }

  cases(): Promise<Page<CaseSummary>> {
    return this.get("/cases");
  }

  tasks(): Promise<TaskSummary[]> {
    return this.get("/tasks");
  }

  case(caseId: string): Promise<CaseSummary> {
    return this.get(`/cases/${encodeURIComponent(caseId)}`);
  }

  caseTasks(caseId: string): Promise<TaskSummary[]> {
    return this.get(`/cases/${encodeURIComponent(caseId)}/tasks`);
  }

  casePlanItems(caseId: string): Promise<Record<string, unknown>[]> {
    return this.get(`/cases/${encodeURIComponent(caseId)}/plan-items`);
  }

  caseDocuments(caseId: string): Promise<Record<string, unknown>[]> {
    return this.get(`/cases/${encodeURIComponent(caseId)}/documents`);
  }

  caseMilestones(caseId: string): Promise<Record<string, unknown>[]> {
    return this.get(`/cases/${encodeURIComponent(caseId)}/milestones`);
  }

  caseSlas(caseId: string): Promise<Record<string, unknown>[]> {
    return this.get(`/cases/${encodeURIComponent(caseId)}/slas`);
  }

  definition(key: string, version: number): Promise<Record<string, unknown>> {
    return this.get(`/case-definitions/${encodeURIComponent(key)}/versions/${version}`);
  }

  presentation(key: string, releaseId: string): Promise<Record<string, unknown>> {
    return this.get(`/case-definitions/${encodeURIComponent(key)}/presentation-releases/${encodeURIComponent(releaseId)}`);
  }

  contract(key: string, releaseId: string): Promise<Record<string, unknown>> {
    return this.get(`/case-definitions/${encodeURIComponent(key)}/contract-releases/${encodeURIComponent(releaseId)}`);
  }

  async execute(href: string, method: string, input?: Record<string, unknown>): Promise<unknown> {
    const headers = await this.headers();
    headers.set("Content-Type", "application/json");
    headers.set("Idempotency-Key", crypto.randomUUID());
    const response = await fetch(this.resolveHref(href), {
      method,
      headers,
      body: method === "GET" || method === "DELETE" ? undefined : JSON.stringify(input ?? {})
    });
    if (!response.ok) throw new Error(`Case action returned ${response.status}`);
    return response.status === 204 ? undefined : response.json();
  }

  async get<T>(path: string): Promise<T> {
    const headers = await this.headers();

    const response = await fetch(`${this.options.baseUrl.replace(/\/$/, "")}${path}`, { headers });
    if (!response.ok) {
      throw new Error(`Case API ${path} returned ${response.status}`);
    }
    return response.json() as Promise<T>;
  }

  private async headers(): Promise<Headers> {
    const headers = new Headers({ Accept: "application/json" });
    const token = await this.options.getAccessToken();
    if (token) headers.set("Authorization", `Bearer ${token}`);
    headers.set("X-Correlation-ID", this.options.correlationId?.() ?? crypto.randomUUID());
    return headers;
  }

  private resolveHref(href: string): string {
    if (/^https?:\/\//.test(href)) {
      throw new Error("Server actions must use a same-origin Case API path");
    }
    const base = this.options.baseUrl.replace(/\/$/, "");
    if (href.startsWith(base)) return href;
    if (href.startsWith("/case-api/v2/")) return href;
    if (!href.startsWith("/")) throw new Error("Server action href must be absolute");
    return `${base}${href}`;
  }
}

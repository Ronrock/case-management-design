export interface CaseTypeSummary {
  key: string;
  name: string;
  version?: number;
}

export interface CaseSummary {
  id: string;
  title?: string;
  state: string;
  priority?: string;
  updatedAt?: string;
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

  private async get<T>(path: string): Promise<T> {
    const headers = new Headers();
    const token = await this.options.getAccessToken();
    if (token) {
      headers.set("Authorization", `Bearer ${token}`);
    }
    headers.set("Accept", "application/json");
    headers.set("X-Correlation-ID", this.options.correlationId?.() ?? crypto.randomUUID());

    const response = await fetch(`${this.options.baseUrl.replace(/\/$/, "")}${path}`, { headers });
    if (!response.ok) {
      throw new Error(`Case API ${path} returned ${response.status}`);
    }
    return response.json() as Promise<T>;
  }
}

export interface ApiCredentials {
  username: string
  password: string
}

export interface Page<T> {
  items: T[]
  page: number
  pageSize: number
  totalItems: number
  totalPages: number
}

export interface AvailableAction {
  action: string
  name: string
  href: string
  method: string
  formKey?: string | null
}

export interface CaseSummary {
  id: string
  tenantId: string
  caseDefinitionKey: string
  caseDefinitionVersion: number
  businessKey?: string | null
  title?: string | null
  state: string
  priority?: string | null
  slaStatus?: string | null
  createdAt?: string | null
  updatedAt?: string | null
  variables?: Record<string, unknown>
  version: number
  projectionStatus?: string | null
  availableActions: AvailableAction[]
}

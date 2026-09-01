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

export interface CreateComplaintInput {
  tenantId: string
  businessKey: string
  title: string
  channel: string
  summary: string
  amount?: number
}

export interface TaskSummary {
  id: string
  caseId: string
  name: string
  state: string
  assignee?: string | null
  candidateGroups: string[]
  formKey?: string | null
  version: number
  projectionStatus?: string | null
  availableActions: AvailableAction[]
}

export interface PlanItemSummary {
  id: string
  type: string
  name: string
  state: string
  parentStageId?: string | null
  repetitionNo: number
  lastEngineUpdateAt?: string | null
}

export interface MilestoneSummary {
  id: string
  name?: string
  state: string
  achievedAt?: string | null
}

export interface SlaSummary {
  id: string
  targetId: string
  status: string
  startedAt?: string | null
  dueAt?: string | null
  warnAt?: string | null
  version: number
}

export interface CaseEvent {
  id: string
  type: string
  time?: string
  subject?: string
  data?: Record<string, unknown>
}

export interface CaseWorkspaceSnapshot {
  case: CaseSummary
  tasks: TaskSummary[]
  planItems: PlanItemSummary[]
  milestones: MilestoneSummary[]
  slas: SlaSummary[]
  events: CaseEvent[]
}

export interface JsonSchemaProperty {
  type: 'string' | 'integer'
  title?: string
  enum?: string[]
}

export interface TaskFormDefinition {
  schema: {
    type: 'object'
    required?: string[]
    properties: Record<string, JsonSchemaProperty>
  }
  uiSchema?: Record<string, { widget?: 'textarea' }>
}

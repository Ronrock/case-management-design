import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { CaseSummary } from '@/lib/api-types'
import { CaseApiClient } from '@/lib/case-api-client'
import { installFetchScript } from '@/test/fetch-script'
import { CaseWorkspace } from './case-workspace'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const caseDetail: CaseSummary = {
  id: 'case-1', tenantId: 't1', caseDefinitionKey: 'complaint', caseDefinitionVersion: 1,
  title: 'Card complaint', businessKey: 'CMP-1', state: 'ACTIVE', priority: 'HIGH',
  variables: { summary: 'Charge disputed' }, version: 3, availableActions: [],
}

describe('case workspace', () => {
  it('loads a coherent workspace snapshot in parallel', async () => {
    const calls = installFetchScript((call) => {
      if (call.url.endsWith('/cases/case-1')) return { body: caseDetail }
      if (call.url.endsWith('/tasks')) return { body: [{ id: 'task-1', caseId: 'case-1', name: 'Assess complaint', state: 'ACTIVE', candidateGroups: [], version: 1, availableActions: [] }] }
      if (call.url.endsWith('/plan-items')) return { body: [{ id: 'item-1', type: 'HUMAN_TASK', name: 'Intake', state: 'COMPLETED', repetitionNo: 0, lastEngineUpdateAt: '2026-09-01T08:00:00Z' }] }
      if (call.url.endsWith('/milestones')) return { body: [] }
      if (call.url.endsWith('/slas')) return { body: [{ id: 'sla-1', targetId: 'resolution', status: 'ACTIVE', dueAt: '2026-09-02T08:00:00Z', version: 1, availableActions: [] }] }
      if (call.url.includes('/events?')) return { body: [{ id: 'event-1', type: 'CaseCreated', time: '2026-09-01T07:00:00Z' }] }
      throw new Error(`Unexpected ${call.url}`)
    })
    const client = new CaseApiClient({ baseUrl: '/case-api/v2', credentials: { username: 'alice', password: 'alice' } })
    render(<CaseWorkspace client={client} caseId="case-1" refreshKey={0} />)

    expect(await screen.findByRole('heading', { name: 'Card complaint' })).toBeInTheDocument()
    expect(screen.getByText('Assess complaint')).toBeInTheDocument()
    expect(screen.getByText('Resolution SLA')).toBeInTheDocument()
    expect(screen.getByText('Charge disputed')).toBeInTheDocument()
    const urls = calls.map((call) => call.url)
    expect(urls.some((url) => url.endsWith('/cases/case-1'))).toBe(true)
    expect(urls.some((url) => url.endsWith('/cases/case-1/tasks'))).toBe(true)
    expect(urls.some((url) => url.endsWith('/cases/case-1/plan-items'))).toBe(true)
    expect(urls.some((url) => url.endsWith('/cases/case-1/milestones'))).toBe(true)
    expect(urls.some((url) => url.endsWith('/cases/case-1/slas'))).toBe(true)
    expect(urls.some((url) => url.includes('/cases/case-1/events?'))).toBe(true)
  })
})

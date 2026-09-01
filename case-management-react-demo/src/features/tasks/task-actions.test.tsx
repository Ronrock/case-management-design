import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { CaseSummary, TaskSummary } from '@/lib/api-types'
import { CaseApiClient } from '@/lib/case-api-client'
import { installFetchScript } from '@/test/fetch-script'
import { TaskActions } from './task-actions'

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

const caseItem: CaseSummary = { id: 'case-1', tenantId: 't1', caseDefinitionKey: 'complaint', caseDefinitionVersion: 3, title: 'Complaint', state: 'ACTIVE', version: 1, availableActions: [] }
function client() { return new CaseApiClient({ baseUrl: '/case-api/v2', credentials: { username: 'alice', password: 'alice' } }) }

describe('task actions', () => {
  it('claims through the advertised action with optimistic concurrency', async () => {
    const calls = installFetchScript(() => ({ body: {} }))
    const onChanged = vi.fn()
    const task: TaskSummary = { id: 'task-1', caseId: 'case-1', name: 'Assess', state: 'AVAILABLE', candidateGroups: [], version: 7, availableActions: [{ action: 'claim', name: 'Claim', href: '/case-api/v2/tasks/task-1/claim', method: 'POST' }] }
    render(<TaskActions client={client()} caseItem={caseItem} task={task} onChanged={onChanged} />)
    await userEvent.click(screen.getByRole('button', { name: 'Claim' }))
    const claim = calls[0]
    expect(new Headers(claim.init.headers).get('If-Match')).toBe('"7"')
    expect(new Headers(claim.init.headers).get('Idempotency-Key')).toBeTruthy()
    expect(onChanged).toHaveBeenCalledOnce()
  })

  it('loads the pinned contract form and completes through the advertised action', async () => {
    const calls = installFetchScript((call) => {
      if (call.url.includes('/versions/3')) return { body: { contractReleaseId: 'contract-9' } }
      if (call.url.includes('/contract-releases/contract-9')) return { body: { forms: { assessForm: { schema: { type: 'object', required: ['outcome'], properties: { outcome: { type: 'string', enum: ['upheld'] } } } } } } }
      return { body: {} }
    })
    const onChanged = vi.fn()
    const task: TaskSummary = { id: 'task-1', caseId: 'case-1', name: 'Assess', state: 'CLAIMED', candidateGroups: [], formKey: 'assessForm', version: 7, availableActions: [{ action: 'complete', name: 'Complete', href: '/case-api/v2/tasks/task-1/complete', method: 'POST', formKey: 'assessForm' }] }
    render(<TaskActions client={client()} caseItem={caseItem} task={task} onChanged={onChanged} />)

    await userEvent.click(screen.getByRole('button', { name: 'Complete' }))
    await userEvent.selectOptions(await screen.findByLabelText('Outcome'), 'upheld')
    await userEvent.click(screen.getByRole('button', { name: 'Complete task' }))

    const complete = calls.find((call) => call.url.endsWith('/tasks/task-1/complete'))!
    expect(JSON.parse(String(complete.init.body))).toEqual({ variables: { outcome: 'upheld' } })
    expect(new Headers(complete.init.headers).get('If-Match')).toBe('"7"')
    expect(onChanged).toHaveBeenCalledOnce()
  })

  it('refreshes stale work after a task conflict', async () => {
    installFetchScript(() => ({ status: 412, body: { title: 'Precondition Failed' } }))
    const onChanged = vi.fn()
    const task: TaskSummary = { id: 'task-1', caseId: 'case-1', name: 'Assess', state: 'AVAILABLE', candidateGroups: [], version: 7, availableActions: [{ action: 'claim', name: 'Claim', href: '/case-api/v2/tasks/task-1/claim', method: 'POST' }] }
    render(<TaskActions client={client()} caseItem={caseItem} task={task} onChanged={onChanged} />)
    await userEvent.click(screen.getByRole('button', { name: 'Claim' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('This item changed')
    expect(onChanged).toHaveBeenCalledOnce()
  })
})

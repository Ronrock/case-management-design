import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { CaseSummary, Page } from '@/lib/api-types'
import { CaseApiClient } from '@/lib/case-api-client'
import { installFetchScript } from '@/test/fetch-script'
import { CaseDemo } from './case-demo'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function caseItem(id: string, title: string): CaseSummary {
  return {
    id,
    title,
    tenantId: 't1',
    caseDefinitionKey: 'complaint',
    caseDefinitionVersion: 1,
    state: 'ACTIVE',
    priority: 'MEDIUM',
    version: 1,
    availableActions: [],
  }
}

function page(items: CaseSummary[]): Page<CaseSummary> {
  return { items, page: 0, pageSize: 20, totalItems: items.length, totalPages: items.length ? 1 : 0 }
}

function client() {
  return new CaseApiClient({ baseUrl: '/case-api/v2', credentials: { username: 'alice', password: 'alice' } })
}

describe('case demo', () => {
  it('refreshes the full case rail after the one-item connection check', async () => {
    installFetchScript((call) => {
      if (call.url.endsWith('/cases')) return { body: page([caseItem('case-3', 'Refund complaint')]) }
      if (call.url.endsWith('/cases/case-3')) return { body: caseItem('case-3', 'Refund complaint') }
      return { body: [] }
    })
    render(<CaseDemo client={client()} username="alice" initialPage={page([])} />)
    expect(await screen.findByText('Refund complaint')).toBeInTheDocument()
  })

  it('filters the case rail and selects a visible case', async () => {
    installFetchScript((call) => {
      const id = call.url.includes('case-2') ? 'case-2' : 'case-1'
      if (call.url.endsWith('/cases')) return { body: page([caseItem('case-1', 'Lost transfer'), caseItem('case-2', 'Card complaint')]) }
      if (/\/cases\/case-[12]$/.test(call.url)) return { body: caseItem(id, id === 'case-2' ? 'Card complaint' : 'Lost transfer') }
      return { body: [] }
    })
    const user = userEvent.setup()
    render(<CaseDemo client={client()} username="alice" initialPage={page([caseItem('case-1', 'Lost transfer'), caseItem('case-2', 'Card complaint')])} />)

    await user.type(screen.getByRole('searchbox', { name: 'Filter loaded cases' }), 'card')
    const rail = within(screen.getByRole('complementary', { name: 'Cases' }))
    expect(rail.getByText('Card complaint')).toBeInTheDocument()
    expect(rail.queryByText('Lost transfer')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Card complaint/ }))
    expect(screen.getByRole('button', { name: /Card complaint/ })).toHaveAttribute('data-selected', 'true')
  })

  it('loads another page before filtering cases outside the initial page', async () => {
    const firstPage: Page<CaseSummary> = {
      items: [caseItem('case-1', 'Lost transfer')],
      page: 0,
      pageSize: 1,
      totalItems: 2,
      totalPages: 2,
    }
    const secondPage: Page<CaseSummary> = {
      items: [caseItem('case-2', 'Card complaint')],
      page: 1,
      pageSize: 1,
      totalItems: 2,
      totalPages: 2,
    }
    const calls = installFetchScript((call) => {
      if (call.url.endsWith('/cases?page=1&pageSize=1')) return { body: secondPage }
      if (call.url.endsWith('/cases')) return { body: firstPage }
      if (call.url.endsWith('/cases/case-1')) return { body: caseItem('case-1', 'Lost transfer') }
      return { body: [] }
    })
    const user = userEvent.setup()
    render(<CaseDemo client={client()} username="alice" initialPage={firstPage} />)

    await user.click(await screen.findByRole('button', { name: 'Load more cases' }))
    await user.type(screen.getByRole('searchbox', { name: 'Filter loaded cases' }), 'card')

    const rail = within(screen.getByRole('complementary', { name: 'Cases' }))
    expect(await rail.findByText('Card complaint')).toBeInTheDocument()
    expect(rail.queryByText('Lost transfer')).not.toBeInTheDocument()
    expect(calls.some((call) => call.url.endsWith('/cases?page=1&pageSize=1'))).toBe(true)
    expect(screen.queryByRole('button', { name: 'Load more cases' })).not.toBeInTheDocument()
  })

  it('creates a complaint with an idempotency key and selects it', async () => {
    const calls = installFetchScript((call) => {
      if (call.init.method === 'POST') return { status: 201, body: caseItem('case-new', 'New card complaint') }
      if (call.url.endsWith('/cases')) return { body: page([]) }
      if (call.url.endsWith('/cases/case-new')) return { body: caseItem('case-new', 'New card complaint') }
      return { body: [] }
    })
    const user = userEvent.setup()
    render(<CaseDemo client={client()} username="alice" initialPage={page([])} />)

    await user.click(screen.getByRole('button', { name: 'New complaint' }))
    await user.type(screen.getByLabelText('Tenant'), 't1')
    await user.type(screen.getByLabelText('Business key'), 'CMP-1001')
    await user.type(screen.getByLabelText('Title'), 'New card complaint')
    await user.type(screen.getByLabelText('Channel'), 'web')
    await user.type(screen.getByLabelText('Summary'), 'Charge disputed')
    await user.type(screen.getByLabelText('Amount'), '125')
    await user.click(screen.getByRole('button', { name: 'Create case' }))

    expect(await screen.findByRole('heading', { name: 'New card complaint' })).toBeInTheDocument()
    const create = calls.find((call) => call.init.method === 'POST')!
    expect(create.url.endsWith('/case-api/v2/cases')).toBe(true)
    expect(new Headers(create.init.headers).get('Idempotency-Key')).toBeTruthy()
    expect(JSON.parse(String(create.init.body))).toEqual({
      caseDefinitionKey: 'complaint',
      tenantId: 't1',
      businessKey: 'CMP-1001',
      title: 'New card complaint',
      priority: 'MEDIUM',
      variables: { channel: 'web', summary: 'Charge disputed', amount: 125 },
    })
  })

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

  it('focuses work opened from the inbox and refreshes both workspace and worklist after claim', async () => {
    let claimed = false
    let globalTaskReads = 0
    let caseReads = 0
    const task = () => ({
      id: 'task-2', caseId: 'case-2', name: 'Register complaint', state: claimed ? 'CLAIMED' : 'OPEN',
      assignee: claimed ? 'alice' : undefined, candidateGroups: ['complaints-handlers'], version: 1,
      availableActions: claimed ? [] : [{ action: 'claim', name: 'Claim', href: '/tasks/task-2/claim', method: 'POST' }],
    })
    const calls = installFetchScript((call) => {
      if (call.url === '/case-api/v2/tasks') {
        globalTaskReads += 1
        return { body: [task()] }
      }
      if (call.url.endsWith('/tasks/task-2/claim')) {
        claimed = true
        return { body: {} }
      }
      if (call.url.endsWith('/cases')) return { body: page([caseItem('case-2', 'Card complaint')]) }
      if (call.url.endsWith('/cases/case-2')) {
        caseReads += 1
        return { body: caseItem('case-2', 'Card complaint') }
      }
      if (call.url.endsWith('/cases/case-2/tasks')) return { body: [task()] }
      return { body: [] }
    })
    const user = userEvent.setup()
    render(<CaseDemo client={client()} username="alice" initialPage={page([])} />)

    await user.click(screen.getByRole('button', { name: 'My Work' }))
    await user.click(await screen.findByRole('button', { name: /Register complaint/ }))
    const taskTitle = await screen.findByText('Register complaint')
    const taskCard = taskTitle.closest('[data-slot="card"]')
    expect(taskCard).toHaveAttribute('data-highlighted', 'true')
    expect(taskCard).toHaveFocus()

    const caseReadsBeforeClaim = caseReads
    await user.click(screen.getByRole('button', { name: 'Claim' }))
    expect(caseReads).toBe(caseReadsBeforeClaim + 1)
    await user.click(screen.getByRole('button', { name: 'My Work' }))
    expect(await screen.findByRole('heading', { name: 'Assigned to me' })).toBeInTheDocument()
    expect(await screen.findByText('alice')).toBeInTheDocument()
    expect(globalTaskReads).toBe(2)
    expect(calls.filter((call) => call.url.endsWith('/tasks/task-2/claim'))).toHaveLength(1)
  })
})

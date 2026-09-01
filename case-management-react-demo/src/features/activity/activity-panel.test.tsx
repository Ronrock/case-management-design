import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { CaseSummary } from '@/lib/api-types'
import { CaseApiClient } from '@/lib/case-api-client'
import { installFetchScript } from '@/test/fetch-script'
import { ActivityPanel } from './activity-panel'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function client() {
  return new CaseApiClient({
    baseUrl: '/case-api/v2',
    credentials: { username: 'alice', password: 'alice' },
  })
}

const caseItem: CaseSummary = {
  id: 'case-1', tenantId: 't1', caseDefinitionKey: 'complaint', caseDefinitionVersion: 1,
  title: 'Card complaint', state: 'ACTIVE', version: 3, availableActions: [],
}

describe('activity panel', () => {
  it('combines comments and system events newest first', async () => {
    installFetchScript(() => ({
      body: [{ id: 'comment-1', caseId: 'case-1', author: 'alice', text: 'Customer called', visibility: 'internal', createdAt: '2026-09-01T10:00:00Z' }],
    }))
    render(<ActivityPanel
      client={client()}
      caseItem={caseItem}
      events={[{ id: 'event-1', type: 'case.task.completed', time: '2026-09-01T11:00:00Z' }]}
      refreshKey={0}
      onChanged={vi.fn()}
    />)

    const entries = await screen.findAllByRole('listitem')
    expect(entries[0]).toHaveTextContent('Task Completed')
    expect(entries[0]).toHaveTextContent('System event')
    expect(entries[1]).toHaveTextContent('Customer called')
    expect(entries[1]).toHaveTextContent('alice')
  })

  it('shows and submits the composer only when comment is advertised', async () => {
    const calls = installFetchScript((call) => call.init.method === 'POST'
      ? { body: { id: 'comment-2', caseId: 'case-1', text: 'Checked with customer' } }
      : { body: [] })
    const onChanged = vi.fn()
    const user = userEvent.setup()
    render(<ActivityPanel
      client={client()}
      caseItem={{ ...caseItem, collaborationActions: [{ action: 'comment', name: 'Add comment', href: '/cases/case-1/comments', method: 'POST' }] }}
      events={[]}
      refreshKey={0}
      onChanged={onChanged}
    />)

    const input = await screen.findByLabelText('Add internal comment')
    await user.click(screen.getByRole('button', { name: 'Add comment' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a comment')
    expect(calls.filter((call) => call.init.method === 'POST')).toHaveLength(0)

    await user.type(input, 'Checked with customer')
    await user.click(screen.getByRole('button', { name: 'Add comment' }))

    const request = calls.find((call) => call.init.method === 'POST')!
    expect(JSON.parse(String(request.init.body))).toEqual({ text: 'Checked with customer', visibility: 'internal' })
    expect(onChanged).toHaveBeenCalledOnce()
    expect(input).toHaveValue('')
  })

  it('hides the composer when the backend does not advertise comments', async () => {
    installFetchScript(() => ({ body: [] }))
    render(<ActivityPanel client={client()} caseItem={caseItem} events={[]} refreshKey={0} onChanged={vi.fn()} />)

    expect(await screen.findByText('No activity has been recorded for this case.')).toBeInTheDocument()
    expect(screen.queryByLabelText('Add internal comment')).not.toBeInTheDocument()
  })

  it('keeps a refused comment error in context', async () => {
    installFetchScript((call) => call.init.method === 'POST'
      ? { status: 403, body: { title: 'Forbidden' } }
      : { body: [] })
    const user = userEvent.setup()
    render(<ActivityPanel
      client={client()}
      caseItem={{ ...caseItem, collaborationActions: [{ action: 'comment', name: 'Add comment', href: '/cases/case-1/comments', method: 'POST' }] }}
      events={[]}
      refreshKey={0}
      onChanged={vi.fn()}
    />)

    await user.type(await screen.findByLabelText('Add internal comment'), 'Customer called')
    await user.click(screen.getByRole('button', { name: 'Add comment' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('The backend refused this comment for your account.')
  })
})

import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { CaseSummary } from '@/lib/api-types'
import { CaseApiClient } from '@/lib/case-api-client'
import { installFetchScript } from '@/test/fetch-script'
import { CaseActionBar } from './case-action-bar'

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

describe('case action bar', () => {
  it('renders only advertised actions and updates the title with the case version', async () => {
    const calls = installFetchScript(() => ({ body: caseItem }))
    const user = userEvent.setup()
    render(<CaseActionBar client={client()} caseItem={{
      ...caseItem,
      availableActions: [{ action: 'update', name: 'Update', href: '/cases/case-1', method: 'PATCH' }],
    }} onChanged={vi.fn()} />)

    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Update' }))
    await user.clear(screen.getByLabelText('Title'))
    await user.type(screen.getByLabelText('Title'), 'Updated complaint')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    const request = calls.find((call) => call.init.method === 'PATCH')!
    expect(new Headers(request.init.headers).get('If-Match')).toBe('"3"')
    expect(JSON.parse(String(request.init.body))).toEqual({ title: 'Updated complaint' })
  })

  it('requires a cancellation reason before confirming the advertised action', async () => {
    const calls = installFetchScript(() => ({ body: caseItem }))
    const user = userEvent.setup()
    render(<CaseActionBar client={client()} caseItem={{
      ...caseItem,
      availableActions: [{ action: 'cancel', name: 'Cancel', href: '/cases/case-1/cancel', method: 'POST' }],
    }} onChanged={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    await user.click(screen.getByRole('button', { name: 'Confirm cancellation' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a reason')
    expect(calls).toHaveLength(0)
    await user.type(screen.getByLabelText('Reason'), 'Duplicate complaint')
    await user.click(screen.getByRole('button', { name: 'Confirm cancellation' }))
    expect(JSON.parse(String(calls[0].init.body))).toEqual({ reason: 'Duplicate complaint' })
  })

  it('supports an advertised close action with an optional outcome', async () => {
    const calls = installFetchScript(() => ({ body: caseItem }))
    const user = userEvent.setup()
    render(<CaseActionBar client={client()} caseItem={{
      ...caseItem,
      availableActions: [{ action: 'close', name: 'Close', href: '/cases/case-1/close', method: 'POST' }],
    }} onChanged={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: 'Close' }))
    await user.type(screen.getByLabelText('Outcome'), 'Resolved')
    await user.click(screen.getByRole('button', { name: 'Confirm close' }))
    expect(JSON.parse(String(calls[0].init.body))).toEqual({ outcome: 'Resolved' })
  })

  it.each([
    [403, 'The backend refused this action for your account.'],
    [412, 'This case changed on the server. The workspace has been refreshed.'],
  ])('keeps action errors in context for status %s', async (status, message) => {
    installFetchScript(() => ({ status, body: { title: 'Action refused' } }))
    const onChanged = vi.fn()
    const user = userEvent.setup()
    render(<CaseActionBar client={client()} caseItem={{
      ...caseItem,
      availableActions: [{ action: 'update', name: 'Update', href: '/cases/case-1', method: 'PATCH' }],
    }} onChanged={onChanged} />)

    await user.click(screen.getByRole('button', { name: 'Update' }))
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(onChanged).toHaveBeenCalledTimes(status === 412 ? 1 : 0)
  })
})

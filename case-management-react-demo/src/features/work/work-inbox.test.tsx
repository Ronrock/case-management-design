import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { CaseApiClient } from '@/lib/case-api-client'
import { installFetchScript } from '@/test/fetch-script'
import { WorkInbox } from './work-inbox'

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

describe('work inbox', () => {
  it('groups assigned and claimable work and opens its owning case', async () => {
    installFetchScript(() => ({ body: [
      { id: 'assigned', caseId: 'case-1', name: 'Assess complaint', state: 'CLAIMED', assignee: 'alice', candidateGroups: [], version: 2, availableActions: [] },
      { id: 'claimable', caseId: 'case-2', name: 'Register complaint', state: 'OPEN', candidateGroups: ['complaints-handlers'], version: 1, availableActions: [{ action: 'claim', name: 'Claim', href: '/tasks/claimable/claim', method: 'POST' }] },
    ] }))
    const onOpenTask = vi.fn()
    const user = userEvent.setup()
    render(<WorkInbox client={client()} username="alice" refreshKey={0} onOpenTask={onOpenTask} />)

    expect(await screen.findByRole('heading', { name: 'Assigned to me' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Available to claim' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Register complaint/ }))
    expect(onOpenTask).toHaveBeenCalledWith(expect.objectContaining({ id: 'claimable', caseId: 'case-2' }))
  })

  it('reloads only when the user requests it', async () => {
    let requestCount = 0
    installFetchScript(() => ({ body: requestCount++ === 0 ? [] : [
      { id: 'assigned', caseId: 'case-1', name: 'Assess complaint', state: 'CLAIMED', assignee: 'alice', candidateGroups: [], version: 2, availableActions: [] },
    ] }))
    const user = userEvent.setup()
    render(<WorkInbox client={client()} username="alice" refreshKey={0} onOpenTask={vi.fn()} />)

    expect(await screen.findByText('No work is currently visible.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Refresh work' }))
    expect(await screen.findByText('Assess complaint')).toBeInTheDocument()
    expect(requestCount).toBe(2)
  })
})

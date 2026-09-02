import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { SlaSummary } from '@/lib/api-types'
import { CaseApiClient } from '@/lib/case-api-client'
import { installFetchScript } from '@/test/fetch-script'
import { SlaCockpit } from './sla-cockpit'

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

function client() {
  return new CaseApiClient({
    baseUrl: '/case-api/v2',
    credentials: { username: 'alice', password: 'alice' },
  })
}

describe('SLA cockpit', () => {
  it('shows every SLA with deadline and pause details', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-09-01T12:00:00Z'))
    render(<SlaCockpit client={client()} onChanged={vi.fn()} slas={[
      { id: 'sla-1', targetId: 'firstResponse', status: 'RUNNING', dueAt: '2026-09-01T14:00:00Z', warnAt: '2026-09-01T13:00:00Z', version: 1, availableActions: [] },
      { id: 'sla-2', targetId: 'resolution', status: 'PAUSED', dueAt: '2026-08-31T12:00:00Z', pausedReason: 'Waiting for customer', pausedTotalSeconds: 3600, version: 2, availableActions: [] },
    ]} />)

    expect(screen.getByText('First Response')).toBeInTheDocument()
    expect(screen.getByText('Due in 2 hours')).toBeInTheDocument()
    expect(screen.getByText('Resolution')).toBeInTheDocument()
    expect(screen.getByText('Waiting for customer')).toBeInTheDocument()
    expect(screen.getByText('Paused for 1 hour')).toBeInTheDocument()
  })

  it('requires a reason and pauses through the advertised action', async () => {
    const calls = installFetchScript(() => ({ body: {} }))
    const onChanged = vi.fn()
    const user = userEvent.setup()
    const sla: SlaSummary = {
      id: 'sla-1', targetId: 'resolution', status: 'RUNNING', version: 4,
      availableActions: [{ action: 'pause', name: 'Pause', href: '/cases/case-1/slas/sla-1/pause', method: 'POST' }],
    }
    render(<SlaCockpit client={client()} slas={[sla]} onChanged={onChanged} />)

    await user.click(screen.getByRole('button', { name: 'Pause' }))
    await user.click(screen.getByRole('button', { name: 'Confirm pause' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a reason')
    await user.type(screen.getByLabelText('Pause reason'), 'Waiting for customer')
    await user.click(screen.getByRole('button', { name: 'Confirm pause' }))

    expect(new Headers(calls[0].init.headers).get('If-Match')).toBe('"4"')
    expect(JSON.parse(String(calls[0].init.body))).toEqual({ reason: 'Waiting for customer' })
    expect(onChanged).toHaveBeenCalledOnce()
  })

  it('resumes immediately through the advertised action', async () => {
    const calls = installFetchScript(() => ({ body: {} }))
    const onChanged = vi.fn()
    const sla: SlaSummary = {
      id: 'sla-1', targetId: 'resolution', status: 'PAUSED', version: 5,
      availableActions: [{ action: 'resume', name: 'Resume', href: '/cases/case-1/slas/sla-1/resume', method: 'POST' }],
    }
    render(<SlaCockpit client={client()} slas={[sla]} onChanged={onChanged} />)

    await userEvent.click(screen.getByRole('button', { name: 'Resume' }))
    expect(new Headers(calls[0].init.headers).get('If-Match')).toBe('"5"')
    expect(calls[0].init.body).toBeUndefined()
    expect(onChanged).toHaveBeenCalledOnce()
  })

  it('refreshes a stale SLA while keeping the error in context', async () => {
    installFetchScript(() => ({ status: 412, body: { title: 'Precondition Failed' } }))
    const onChanged = vi.fn()
    const sla: SlaSummary = {
      id: 'sla-1', targetId: 'resolution', status: 'PAUSED', version: 5,
      availableActions: [{ action: 'resume', name: 'Resume', href: '/cases/case-1/slas/sla-1/resume', method: 'POST' }],
    }
    render(<SlaCockpit client={client()} slas={[sla]} onChanged={onChanged} />)

    await userEvent.click(screen.getByRole('button', { name: 'Resume' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('This SLA changed')
    expect(onChanged).toHaveBeenCalledOnce()
  })

  it('keeps a forbidden SLA action in context', async () => {
    installFetchScript(() => ({ status: 403, body: { title: 'Forbidden' } }))
    const sla: SlaSummary = {
      id: 'sla-1', targetId: 'resolution', status: 'PAUSED', version: 5,
      availableActions: [{ action: 'resume', name: 'Resume', href: '/cases/case-1/slas/sla-1/resume', method: 'POST' }],
    }
    render(<SlaCockpit client={client()} slas={[sla]} onChanged={vi.fn()} />)

    await userEvent.click(screen.getByRole('button', { name: 'Resume' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('The backend refused this SLA action for your account.')
  })

  it('explains when no SLA clocks exist', () => {
    render(<SlaCockpit client={client()} slas={[]} onChanged={vi.fn()} />)
    expect(screen.getByText('No SLA clocks are active for this case.')).toBeInTheDocument()
  })
})

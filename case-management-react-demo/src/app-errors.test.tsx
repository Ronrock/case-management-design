import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import App from './app'
import type { CaseSummary, Page } from './lib/api-types'
import { installFetchScript } from './test/fetch-script'

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

async function connect() {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('Username'), 'alice')
  await user.type(screen.getByLabelText('Password'), 'wrong')
  await user.click(screen.getByRole('button', { name: 'Connect' }))
  return user
}

const item: CaseSummary = { id: 'case-1', tenantId: 't1', caseDefinitionKey: 'complaint', caseDefinitionVersion: 1, title: 'Complaint', state: 'ACTIVE', version: 1, availableActions: [] }
const page: Page<CaseSummary> = { items: [item], page: 0, pageSize: 1, totalItems: 1, totalPages: 1 }

describe('application failures', () => {
  it('keeps the connection gate open for rejected credentials', async () => {
    installFetchScript(() => ({ status: 401, body: { title: 'Unauthorized' } }))
    render(<App />)
    await connect()
    expect(await screen.findByRole('alert')).toHaveTextContent('Credentials were not accepted')
    expect(screen.getByLabelText('Password')).toHaveValue('')
  })

  it('returns to connection when an authenticated request becomes unauthorized', async () => {
    installFetchScript((call) => call.url.includes('pageSize=1') ? { body: page } : { status: 401, body: { title: 'Unauthorized' } })
    render(<App />)
    await connect()
    expect(await screen.findByText('Your connection expired. Connect again.')).toBeInTheDocument()
    expect(screen.getByLabelText('Username')).toBeInTheDocument()
  })

  it('explains when the live backend cannot be reached', async () => {
    installFetchScript(() => { throw new TypeError('Network error') })
    render(<App />)
    await connect()
    expect(await screen.findByRole('alert')).toHaveTextContent('Backend unavailable at /case-api/v2')
  })

  it('shows both authenticated empty states', async () => {
    installFetchScript(() => ({ body: { ...page, items: [], totalItems: 0, totalPages: 0 } }))
    render(<App />)
    await connect()
    expect(await screen.findByText('No cases yet')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'New complaint' })).toBeInTheDocument()
  })
})

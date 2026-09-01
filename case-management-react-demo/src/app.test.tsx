import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import App from './app'
import { installFetchScript } from './test/fetch-script'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('basic connection', () => {
  it('keeps credentials in memory and sends a Basic authorization header', async () => {
    const calls = installFetchScript(() => ({
      body: {
        items: [],
        page: 0,
        pageSize: 1,
        totalItems: 0,
        totalPages: 0,
      },
    }))
    const user = userEvent.setup()
    render(<App />)

    await user.type(screen.getByLabelText('Username'), 'admin')
    await user.type(screen.getByLabelText('Password'), 'admin')
    await user.click(screen.getByRole('button', { name: 'Connect' }))

    expect(await screen.findByText('Connected as admin')).toBeInTheDocument()
    expect(new Headers(calls[0].init.headers).get('Authorization')).toBe(
      'Basic YWRtaW46YWRtaW4=',
    )
  })
})

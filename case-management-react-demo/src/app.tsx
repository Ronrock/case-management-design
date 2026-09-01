import { useState } from 'react'

import { ConnectionGate } from '@/features/auth/connection-gate'
import type { CaseSummary, Page } from '@/lib/api-types'
import { CaseApiClient } from '@/lib/case-api-client'

interface Session {
  username: string
  client: CaseApiClient
  initialPage: Page<CaseSummary>
}

const API_BASE_URL = import.meta.env.VITE_CASE_API_BASE_URL || '/case-api/v2'

export default function App() {
  const [session, setSession] = useState<Session>()

  if (!session) {
    return (
      <ConnectionGate
        onConnect={async (credentials) => {
          const client = new CaseApiClient({ baseUrl: API_BASE_URL, credentials })
          const initialPage = await client.connect()
          setSession({ username: credentials.username, client, initialPage })
        }}
      />
    )
  }

  return (
    <main className="p-6">
      <h1 className="text-2xl font-semibold">Case management demo</h1>
      <p>Connected as {session.username}</p>
    </main>
  )
}

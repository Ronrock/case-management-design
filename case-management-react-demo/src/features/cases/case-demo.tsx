import { useEffect, useRef, useState } from 'react'

import type { CaseSummary, Page } from '@/lib/api-types'
import type { CaseApiClient } from '@/lib/case-api-client'
import { CaseWorkspace } from '@/features/workspace/case-workspace'
import { CaseRail } from './case-rail'
import { NewCaseDialog } from './new-case-dialog'

interface CaseDemoProps {
  client: CaseApiClient
  username: string
  initialPage: Page<CaseSummary>
}

export function CaseDemo({ client, username, initialPage }: CaseDemoProps) {
  const [cases, setCases] = useState(initialPage.items)
  const [selectedId, setSelectedId] = useState(initialPage.items[0]?.id)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [refreshKey] = useState(0)
  const hasLocalChanges = useRef(false)

  useEffect(() => {
    let current = true
    client.listCases().then((nextPage) => {
      if (!current || hasLocalChanges.current) return
      setCases(nextPage.items)
      setSelectedId((selected) => selected && nextPage.items.some((item) => item.id === selected) ? selected : nextPage.items[0]?.id)
    }).catch(() => {
      // Keep the credential-check page visible; global 401 handling still ends the session.
    })
    return () => { current = false }
  }, [client])

  return (
    <main className="app-shell">
      <header className="app-header">
        <div><p className="eyebrow">Case management library</p><h1>Operations desk</h1></div>
        <p>Connected as {username}</p>
      </header>
      <div className="case-layout">
        <CaseRail cases={cases} selectedId={selectedId} onSelect={setSelectedId} onCreate={() => setDialogOpen(true)} />
        {selectedId ? <CaseWorkspace client={client} caseId={selectedId} refreshKey={refreshKey} /> : <section className="case-workspace-placeholder"><div><h2>No case selected</h2><p>Create a complaint to begin.</p></div></section>}
      </div>
      <NewCaseDialog
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        onCreate={async (input) => {
          const created = await client.createComplaint(input)
          hasLocalChanges.current = true
          setCases((current) => [created, ...current.filter((item) => item.id !== created.id)])
          setSelectedId(created.id)
        }}
      />
    </main>
  )
}

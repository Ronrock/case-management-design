import { useEffect, useState } from 'react'

import { Button } from '@/components/ui/button'
import { WorkInbox } from '@/features/work/work-inbox'
import type { CaseSummary, Page, TaskSummary } from '@/lib/api-types'
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
  const [activeView, setActiveView] = useState<'work' | 'cases'>('cases')
  const [highlightedTaskId, setHighlightedTaskId] = useState<string>()
  const [refreshKey, setRefreshKey] = useState(0)

  useEffect(() => {
    let current = true
    client.listCases().then((nextPage) => {
      if (!current) return
      setCases(nextPage.items)
      setSelectedId((selected) => selected ?? nextPage.items[0]?.id)
    }).catch(() => {
      // Keep the credential-check page visible; global 401 handling still ends the session.
    })
    return () => { current = false }
  }, [client, refreshKey])

  function openTask(task: TaskSummary) {
    setSelectedId(task.caseId)
    setHighlightedTaskId(task.id)
    setActiveView('cases')
  }

  return (
    <main className="app-shell">
      <header className="app-header">
        <div><p className="eyebrow">Case management library</p><h1>Operations desk</h1></div>
        <nav className="primary-nav" aria-label="Primary">
          <Button variant={activeView === 'work' ? 'secondary' : 'ghost'} aria-pressed={activeView === 'work'} onClick={() => setActiveView('work')}>My Work</Button>
          <Button variant={activeView === 'cases' ? 'secondary' : 'ghost'} aria-pressed={activeView === 'cases'} onClick={() => setActiveView('cases')}>Cases</Button>
        </nav>
        <p>Connected as {username}</p>
      </header>
      {activeView === 'work'
        ? <WorkInbox client={client} username={username} refreshKey={refreshKey} onOpenTask={openTask} />
        : <div className="case-layout">
          <CaseRail cases={cases} selectedId={selectedId} onSelect={(caseId) => { setHighlightedTaskId(undefined); setSelectedId(caseId) }} onCreate={() => setDialogOpen(true)} />
          {selectedId ? <CaseWorkspace client={client} caseId={selectedId} refreshKey={refreshKey} highlightedTaskId={highlightedTaskId} onDataChanged={() => setRefreshKey((value) => value + 1)} /> : <section className="case-workspace-placeholder"><div><h2>No case selected</h2><p>Create a complaint to begin.</p></div></section>}
        </div>}
      <NewCaseDialog
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        onCreate={async (input) => {
          const created = await client.createComplaint(input)
          setCases((current) => [created, ...current.filter((item) => item.id !== created.id)])
          setSelectedId(created.id)
          setHighlightedTaskId(undefined)
          setRefreshKey((value) => value + 1)
        }}
      />
    </main>
  )
}

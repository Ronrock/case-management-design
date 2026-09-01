import { RefreshCw } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Separator } from '@/components/ui/separator'
import { Skeleton } from '@/components/ui/skeleton'
import type { CaseWorkspaceSnapshot } from '@/lib/api-types'
import type { CaseApiClient } from '@/lib/case-api-client'
import { ActivityPanel } from '@/features/activity/activity-panel'
import { CaseActionBar } from '@/features/cases/case-action-bar'
import { SlaCockpit } from '@/features/slas/sla-cockpit'
import { humanize } from '@/lib/format'
import { TaskActions } from '@/features/tasks/task-actions'
import { CaseSpine } from './case-spine'

interface CaseWorkspaceProps {
  client: CaseApiClient
  caseId: string
  refreshKey: number
  highlightedTaskId?: string
  onDataChanged?(): void
}

export function CaseWorkspace({ client, caseId, refreshKey, highlightedTaskId, onDataChanged }: CaseWorkspaceProps) {
  const [load, setLoad] = useState<{ key: string; snapshot?: CaseWorkspaceSnapshot; error?: string }>({ key: '' })
  const [retry, setRetry] = useState(0)
  const [notice, setNotice] = useState<{ caseId: string; message: string }>()
  const [selection, setSelection] = useState<{ caseId: string; section: 'overview' | 'activity' }>({ caseId, section: 'overview' })
  const highlightedTaskRef = useRef<HTMLDivElement>(null)
  const section = selection.caseId === caseId ? selection.section : 'overview'
  const requestKey = `${caseId}:${refreshKey}:${retry}`

  useEffect(() => {
    let current = true
    client.loadWorkspace(caseId).then(
      (value) => { if (current) setLoad({ key: requestKey, snapshot: value }) },
      (reason) => { if (current) setLoad({ key: requestKey, error: reason instanceof Error ? reason.message : 'Could not load this case' }) },
    )
    return () => { current = false }
  }, [caseId, client, requestKey])

  useEffect(() => {
    if (load.key === requestKey && highlightedTaskId && load.snapshot?.tasks.some((task) => task.id === highlightedTaskId)) {
      highlightedTaskRef.current?.focus()
    }
  }, [highlightedTaskId, load, requestKey])

  if (load.key !== requestKey) return <section className="workspace-panel grid gap-4"><Skeleton className="h-10 w-2/3" /><Skeleton className="h-32 w-full" /><Skeleton className="h-64 w-full" /></section>
  if (load.error) return <section className="workspace-panel"><Alert variant="destructive"><AlertTitle>Case unavailable</AlertTitle><AlertDescription>{load.error}</AlertDescription></Alert><Button className="mt-4" variant="outline" onClick={() => setRetry((value) => value + 1)}><RefreshCw />Refresh</Button></section>
  if (!load.snapshot) return null

  const snapshot = load.snapshot
  const changed = (message?: string) => {
    setNotice(message ? { caseId, message } : undefined)
    if (onDataChanged) onDataChanged()
    else setRetry((value) => value + 1)
  }
  return (
    <section className="workspace-panel">
      <div className="workspace-heading">
        <div><p className="eyebrow mono">{snapshot.case.businessKey || snapshot.case.id}</p><h2>{snapshot.case.title || 'Untitled case'}</h2></div>
        <div className="workspace-heading-actions"><div className="flex flex-wrap gap-2"><Badge>{snapshot.case.state}</Badge>{snapshot.case.priority ? <Badge variant="outline">{snapshot.case.priority}</Badge> : null}{snapshot.case.projectionStatus ? <Badge variant="secondary">{snapshot.case.projectionStatus}</Badge> : null}</div><div className="case-actions"><Button size="sm" variant="outline" onClick={() => changed()}><RefreshCw />Refresh case</Button><CaseActionBar client={client} caseItem={snapshot.case} onChanged={changed} /></div></div>
      </div>
      {notice?.caseId === caseId ? <Alert className="mt-4" variant="destructive" role="alert"><AlertDescription>{notice.message}</AlertDescription></Alert> : null}

      <nav className="workspace-tabs" aria-label="Case workspace sections">
        <Button variant={section === 'overview' ? 'default' : 'ghost'} aria-pressed={section === 'overview'} onClick={() => setSelection({ caseId, section: 'overview' })}>Overview</Button>
        <Button variant={section === 'activity' ? 'default' : 'ghost'} aria-pressed={section === 'activity'} onClick={() => setSelection({ caseId, section: 'activity' })}>Activity</Button>
      </nav>

      {section === 'overview' ? (
        <>
          <div className="workspace-grid">
            <Card><CardHeader><CardTitle>Case facts</CardTitle></CardHeader><CardContent><dl className="facts-list">{Object.entries(snapshot.case.variables ?? {}).map(([key, value]) => <div key={key}><dt>{humanize(key)}</dt><dd>{formatValue(value)}</dd></div>)}</dl></CardContent></Card>
            <SlaCockpit client={client} slas={snapshot.slas} onChanged={changed} />
          </div>

          <section className="workspace-section"><h3>Current tasks</h3><div className="task-grid">{snapshot.tasks.length ? snapshot.tasks.map((task) => {
            const highlighted = task.id === highlightedTaskId
            return <Card className="task-card" key={task.id} ref={highlighted ? highlightedTaskRef : undefined} tabIndex={highlighted ? -1 : undefined} data-highlighted={highlighted || undefined}><CardHeader><CardTitle className="text-base">{task.name}</CardTitle></CardHeader><CardContent><div className="flex items-center justify-between"><Badge variant="secondary">{task.state}</Badge><span className="text-sm text-muted-foreground">{task.assignee || 'Unassigned'}</span></div><TaskActions client={client} caseItem={snapshot.case} task={task} onChanged={changed} /></CardContent></Card>
          }) : <p className="empty-copy">No current tasks.</p>}</div></section>
          <Separator />
          <section className="workspace-section"><h3>Case spine</h3><CaseSpine snapshot={snapshot} /></section>
        </>
      ) : (
        <ActivityPanel client={client} caseItem={snapshot.case} events={snapshot.events} refreshKey={refreshKey + retry} onChanged={changed} />
      )}
    </section>
  )
}

function formatValue(value: unknown) {
  if (value === null || value === undefined || value === '') return '—'
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}

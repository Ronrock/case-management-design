import { RefreshCw } from 'lucide-react'
import { useEffect, useState } from 'react'

import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Separator } from '@/components/ui/separator'
import { Skeleton } from '@/components/ui/skeleton'
import type { CaseWorkspaceSnapshot } from '@/lib/api-types'
import type { CaseApiClient } from '@/lib/case-api-client'
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

export function CaseWorkspace({ client, caseId, refreshKey, onDataChanged }: CaseWorkspaceProps) {
  const [load, setLoad] = useState<{ key: string; snapshot?: CaseWorkspaceSnapshot; error?: string }>({ key: '' })
  const [retry, setRetry] = useState(0)
  const requestKey = `${caseId}:${refreshKey}:${retry}`

  useEffect(() => {
    let current = true
    client.loadWorkspace(caseId).then(
      (value) => { if (current) setLoad({ key: requestKey, snapshot: value }) },
      (reason) => { if (current) setLoad({ key: requestKey, error: reason instanceof Error ? reason.message : 'Could not load this case' }) },
    )
    return () => { current = false }
  }, [caseId, client, requestKey])

  if (load.key !== requestKey) return <section className="workspace-panel grid gap-4"><Skeleton className="h-10 w-2/3" /><Skeleton className="h-32 w-full" /><Skeleton className="h-64 w-full" /></section>
  if (load.error) return <section className="workspace-panel"><Alert variant="destructive"><AlertTitle>Case unavailable</AlertTitle><AlertDescription>{load.error}</AlertDescription></Alert><Button className="mt-4" variant="outline" onClick={() => setRetry((value) => value + 1)}><RefreshCw />Refresh</Button></section>
  if (!load.snapshot) return null

  const snapshot = load.snapshot
  const changed = () => { if (onDataChanged) onDataChanged(); else setRetry((value) => value + 1) }
  return (
    <section className="workspace-panel">
      <div className="workspace-heading">
        <div><p className="eyebrow mono">{snapshot.case.businessKey || snapshot.case.id}</p><h2>{snapshot.case.title || 'Untitled case'}</h2></div>
        <div className="workspace-heading-actions"><div className="flex flex-wrap gap-2"><Badge>{snapshot.case.state}</Badge>{snapshot.case.priority ? <Badge variant="outline">{snapshot.case.priority}</Badge> : null}{snapshot.case.projectionStatus ? <Badge variant="secondary">{snapshot.case.projectionStatus}</Badge> : null}</div><CaseActionBar client={client} caseItem={snapshot.case} onChanged={changed} /></div>
      </div>

      <div className="workspace-grid">
        <Card><CardHeader><CardTitle>Case facts</CardTitle></CardHeader><CardContent><dl className="facts-list">{Object.entries(snapshot.case.variables ?? {}).map(([key, value]) => <div key={key}><dt>{humanize(key)}</dt><dd>{formatValue(value)}</dd></div>)}</dl></CardContent></Card>
        <SlaCockpit client={client} slas={snapshot.slas} onChanged={changed} />
      </div>

      <section className="workspace-section"><h3>Current tasks</h3><div className="task-grid">{snapshot.tasks.length ? snapshot.tasks.map((task) => <Card key={task.id}><CardHeader><CardTitle className="text-base">{task.name}</CardTitle></CardHeader><CardContent><div className="flex items-center justify-between"><Badge variant="secondary">{task.state}</Badge><span className="text-sm text-muted-foreground">{task.assignee || 'Unassigned'}</span></div><TaskActions client={client} caseItem={snapshot.case} task={task} onChanged={changed} /></CardContent></Card>) : <p className="empty-copy">No current tasks.</p>}</div></section>
      <Separator />
      <section className="workspace-section"><h3>Case spine</h3><CaseSpine snapshot={snapshot} /></section>
      <Separator />
      <section className="workspace-section"><h3>Recent events</h3><ul className="event-list">{snapshot.events.slice(-25).reverse().map((event) => <li key={event.id}><span>{humanize(event.type)}</span><span className="mono text-xs text-muted-foreground">{event.time ? new Date(event.time).toLocaleString() : 'Time unavailable'}</span></li>)}</ul></section>
    </section>
  )
}

function formatValue(value: unknown) {
  if (value === null || value === undefined || value === '') return '—'
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}

import { RefreshCw } from 'lucide-react'
import { useEffect, useState } from 'react'

import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import type { TaskSummary } from '@/lib/api-types'
import type { CaseApiClient } from '@/lib/case-api-client'

interface WorkInboxProps {
  client: CaseApiClient
  username: string
  refreshKey: number
  onOpenTask(task: TaskSummary): void
}

export function WorkInbox({ client, username, refreshKey, onOpenTask }: WorkInboxProps) {
  const [retry, setRetry] = useState(0)
  const [load, setLoad] = useState<{ key: string; tasks?: TaskSummary[]; error?: string }>({ key: '' })
  const requestKey = `${refreshKey}:${retry}`

  useEffect(() => {
    let current = true
    client.listTasks().then(
      (tasks) => { if (current) setLoad({ key: requestKey, tasks }) },
      (reason) => { if (current) setLoad({ key: requestKey, error: reason instanceof Error ? reason.message : 'Could not load work' }) },
    )
    return () => { current = false }
  }, [client, requestKey])

  const tasks = load.key === requestKey ? load.tasks : undefined
  const assigned = tasks?.filter((task) => task.assignee === username) ?? []
  const claimable = tasks?.filter((task) => task.assignee !== username && task.availableActions.some((action) => action.action === 'claim')) ?? []
  const other = tasks?.filter((task) => !assigned.includes(task) && !claimable.includes(task)) ?? []

  return (
    <section className="work-inbox" aria-labelledby="work-inbox-title">
      <div className="work-inbox-heading">
        <div><p className="eyebrow">Personal queue</p><h2 id="work-inbox-title">My Work</h2></div>
        <Button variant="outline" onClick={() => setRetry((value) => value + 1)}><RefreshCw />Refresh work</Button>
      </div>
      {load.key === requestKey && load.error ? <Alert variant="destructive"><AlertDescription>{load.error}</AlertDescription></Alert> : null}
      {!tasks ? <p className="empty-copy">Loading work…</p> : null}
      {tasks?.length === 0 ? <p className="empty-copy">No work is currently visible.</p> : null}
      {tasks && tasks.length > 0 ? <div className="work-groups">
        <WorkGroup title="Assigned to me" tasks={assigned} empty="No work is assigned to you." onOpenTask={onOpenTask} />
        <WorkGroup title="Available to claim" tasks={claimable} empty="No work is available to claim." onOpenTask={onOpenTask} />
        {other.length ? <WorkGroup title="Other visible work" tasks={other} onOpenTask={onOpenTask} /> : null}
      </div> : null}
    </section>
  )
}

function WorkGroup({ title, tasks, empty, onOpenTask }: { title: string; tasks: TaskSummary[]; empty?: string; onOpenTask(task: TaskSummary): void }) {
  return (
    <section className="work-group">
      <h3>{title}</h3>
      {tasks.length === 0 ? <p className="empty-copy">{empty}</p> : <div className="work-list">
        {tasks.map((task) => <button key={task.id} type="button" className="work-row" onClick={() => onOpenTask(task)} aria-label={`${task.name}, ${task.state}, case ${task.caseId}`}>
          <span><strong>{task.name}</strong><span className="mono">{task.caseId}</span></span>
          <span><Badge variant="secondary">{task.state}</Badge><small>{task.assignee || task.candidateGroups.join(', ') || 'Unassigned'}</small></span>
        </button>)}
      </div>}
    </section>
  )
}

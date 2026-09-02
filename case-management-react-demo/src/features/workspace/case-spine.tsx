import type { CaseWorkspaceSnapshot } from '@/lib/api-types'
import { humanize } from '@/lib/format'

interface SpineEntry {
  id: string
  label: string
  state: string
  time: string
  kind: 'current' | 'complete' | 'observed'
}

const TERMINAL = new Set(['COMPLETED', 'CLOSED', 'ACHIEVED', 'CANCELLED', 'TERMINATED'])
const CURRENT = new Set(['ACTIVE', 'AVAILABLE', 'CLAIMED', 'ENABLED'])

export function CaseSpine({ snapshot }: { snapshot: CaseWorkspaceSnapshot }) {
  const entries: SpineEntry[] = []
  if (snapshot.case.createdAt) entries.push(entry(snapshot.case.id, 'Case created', snapshot.case.state, snapshot.case.createdAt))
  for (const item of snapshot.planItems) {
    if (item.lastEngineUpdateAt) entries.push(entry(item.id, item.name, item.state, item.lastEngineUpdateAt))
  }
  for (const milestone of snapshot.milestones) {
    if (milestone.achievedAt) entries.push(entry(milestone.id, milestone.name || 'Milestone', milestone.state, milestone.achievedAt))
  }
  for (const event of snapshot.events) {
    if (event.time) entries.push(entry(event.id, event.type, 'OBSERVED', event.time))
  }
  entries.sort((a, b) => a.time.localeCompare(b.time) || a.id.localeCompare(b.id))

  return (
    <ol className="case-spine">
      {entries.map((item) => (
        <li key={`${item.id}-${item.time}`} className={`spine-${item.kind}`}>
          <div className="flex items-center justify-between gap-4"><span className="font-medium">{humanize(item.label)}</span><span className="mono text-xs">{formatDate(item.time)}</span></div>
          <span className="text-sm text-muted-foreground">{item.state}</span>
        </li>
      ))}
      {entries.length === 0 ? <li className="text-sm text-muted-foreground">No timestamped observations yet.</li> : null}
    </ol>
  )
}

function entry(id: string, label: string, state: string, time: string): SpineEntry {
  return { id, label, state, time, kind: TERMINAL.has(state) ? 'complete' : CURRENT.has(state) ? 'current' : 'observed' }
}

function formatDate(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.valueOf()) ? value : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

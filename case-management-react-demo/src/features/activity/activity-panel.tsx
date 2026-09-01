import { useEffect, useMemo, useState, type FormEvent } from 'react'

import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { Textarea } from '@/components/ui/textarea'
import type { CaseComment, CaseEvent, CaseSummary } from '@/lib/api-types'
import { ApiError, type CaseApiClient } from '@/lib/case-api-client'

interface ActivityPanelProps {
  client: CaseApiClient
  caseItem: CaseSummary
  events: CaseEvent[]
  refreshKey: number
  onChanged(): void
}

interface ActivityEntry {
  id: string
  kind: 'comment' | 'event'
  label: string
  actor?: string | null
  detail?: string
  time?: string | null
}

export function ActivityPanel({ client, caseItem, events, refreshKey, onChanged }: ActivityPanelProps) {
  const [load, setLoad] = useState<{ key: string; comments?: CaseComment[]; error?: string }>({ key: '' })
  const [text, setText] = useState('')
  const [submitError, setSubmitError] = useState('')
  const [busy, setBusy] = useState(false)
  const requestKey = `${caseItem.id}:${refreshKey}`
  const commentAction = (caseItem.collaborationActions ?? []).find((action) => action.action === 'comment')

  useEffect(() => {
    let current = true
    client.listComments(caseItem.id).then(
      (comments) => { if (current) setLoad({ key: requestKey, comments }) },
      (reason) => { if (current) setLoad({ key: requestKey, error: errorMessage(reason, 'Could not load comments.') }) },
    )
    return () => { current = false }
  }, [caseItem.id, client, requestKey])

  const entries = useMemo(
    () => combineActivity(events, load.comments ?? []),
    [events, load.comments],
  )

  async function submit(event: FormEvent) {
    event.preventDefault()
    const value = text.trim()
    if (!value) {
      setSubmitError('Enter a comment before submitting.')
      return
    }
    if (!commentAction) return
    setBusy(true)
    setSubmitError('')
    try {
      await client.addComment(commentAction, value)
      setText('')
      onChanged()
    } catch (reason) {
      setSubmitError(reason instanceof ApiError && reason.status === 403
        ? 'The backend refused this comment for your account.'
        : errorMessage(reason, 'Could not add this comment.'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="activity-panel" aria-labelledby="activity-heading">
      <div className="activity-heading">
        <div><p className="eyebrow">Case record</p><h3 id="activity-heading">Activity timeline</h3></div>
        <span className="text-sm text-muted-foreground">Events are recorded by the system.</span>
      </div>

      {commentAction ? (
        <Card className="activity-composer">
          <CardContent>
            <form className="grid gap-3" onSubmit={submit}>
              <div className="grid gap-2">
                <Label htmlFor="case-comment">Add internal comment</Label>
                <Textarea id="case-comment" value={text} onChange={(event) => setText(event.target.value)} disabled={busy} placeholder="Share an update with other case workers…" />
              </div>
              {submitError ? <Alert variant="destructive" role="alert"><AlertDescription>{submitError}</AlertDescription></Alert> : null}
              <Button className="justify-self-start" type="submit" disabled={busy}>{busy ? 'Adding…' : commentAction.name}</Button>
            </form>
          </CardContent>
        </Card>
      ) : null}

      {load.key !== requestKey ? <div className="grid gap-3"><Skeleton className="h-20 w-full" /><Skeleton className="h-20 w-full" /></div> : null}
      {load.key === requestKey && load.error ? <Alert variant="destructive" role="alert"><AlertDescription>{load.error}</AlertDescription></Alert> : null}
      {load.key === requestKey && !load.error && entries.length === 0 ? <p className="empty-copy">No activity has been recorded for this case.</p> : null}
      {load.key === requestKey && !load.error && entries.length > 0 ? (
        <ol className="activity-list" aria-label="Case activity">
          {entries.map((entry) => (
            <li className={`activity-${entry.kind}`} key={`${entry.kind}:${entry.id}`}>
              <div className="activity-entry-heading"><strong>{entry.label}</strong><Badge variant={entry.kind === 'event' ? 'secondary' : 'outline'}>{entry.kind === 'event' ? 'System event' : 'Internal comment'}</Badge></div>
              {entry.detail ? <p>{entry.detail}</p> : null}
              <div className="activity-meta"><span>{entry.actor || (entry.kind === 'event' ? 'Case engine' : 'Unknown author')}</span><time dateTime={entry.time ?? undefined}>{entry.time ? new Date(entry.time).toLocaleString() : 'Time unavailable'}</time></div>
            </li>
          ))}
        </ol>
      ) : null}
    </section>
  )
}

function combineActivity(events: CaseEvent[], comments: CaseComment[]): ActivityEntry[] {
  const eventEntries: ActivityEntry[] = events.map((event) => ({
    id: event.id,
    kind: 'event',
    label: eventLabel(event.type),
    detail: event.subject,
    time: event.time,
  }))
  const commentEntries: ActivityEntry[] = comments.map((comment) => ({
    id: comment.id,
    kind: 'comment',
    label: comment.text || 'Comment',
    actor: comment.author,
    time: comment.createdAt,
  }))
  return [...eventEntries, ...commentEntries].sort((left, right) =>
    String(right.time ?? '').localeCompare(String(left.time ?? ''))
      || `${left.kind}:${left.id}`.localeCompare(`${right.kind}:${right.id}`),
  )
}

function eventLabel(type: string) {
  return type
    .replace(/^case[._-]/i, '')
    .split(/[._-]+/)
    .filter(Boolean)
    .map((part) => `${part.charAt(0).toUpperCase()}${part.slice(1)}`)
    .join(' ')
}

function errorMessage(reason: unknown, fallback: string) {
  return reason instanceof Error ? reason.message : fallback
}

import { useState, type FormEvent } from 'react'

import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import type { AvailableAction, SlaSummary } from '@/lib/api-types'
import { ApiError, type CaseApiClient } from '@/lib/case-api-client'
import { humanize } from '@/lib/format'

interface SlaCockpitProps {
  client: CaseApiClient
  slas: SlaSummary[]
  onChanged(): void
}

export function SlaCockpit({ client, slas, onChanged }: SlaCockpitProps) {
  const [pauseTarget, setPauseTarget] = useState<{ sla: SlaSummary; action: AvailableAction }>()
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function execute(sla: SlaSummary, action: AvailableAction, pauseReason?: string) {
    setBusy(true)
    setError('')
    try {
      await client.executeSlaAction(action, sla.version, pauseReason)
      setPauseTarget(undefined)
      setReason('')
      onChanged()
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 412) {
        setError('This SLA changed on the server. The workspace has been refreshed.')
        onChanged()
      } else if (cause instanceof ApiError && cause.status === 403) {
        setError('The backend refused this SLA action for your account.')
      } else {
        setError(cause instanceof Error ? cause.message : 'Could not update this SLA')
      }
    } finally {
      setBusy(false)
    }
  }

  function submitPause(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!pauseTarget) return
    if (!reason.trim()) {
      setError('Enter a reason before pausing this SLA.')
      return
    }
    void execute(pauseTarget.sla, pauseTarget.action, reason.trim())
  }

  return (
    <section className="sla-cockpit" aria-labelledby="sla-title">
      <h3 id="sla-title">Service levels</h3>
      {error && !pauseTarget ? <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert> : null}
      {slas.length === 0 ? <p className="empty-copy">No SLA clocks are active for this case.</p> : <div className="sla-grid">
        {slas.map((sla) => {
          const pause = sla.availableActions.find((action) => action.action === 'pause')
          const resume = sla.availableActions.find((action) => action.action === 'resume')
          return <Card key={sla.id} className="sla-card">
            <CardHeader><div className="flex items-center justify-between gap-3"><CardTitle className="text-base">{humanize(sla.targetId)}</CardTitle><Badge variant={sla.status === 'BREACHED' ? 'destructive' : 'outline'}>{sla.status}</Badge></div></CardHeader>
            <CardContent className="grid gap-2 text-sm">
              <p className="font-medium">{describeDeadline(sla.dueAt, new Date())}</p>
              {sla.warnAt ? <p className="text-muted-foreground">Warning at <span className="mono">{formatTimestamp(sla.warnAt)}</span></p> : null}
              {sla.pausedReason ? <p>{sla.pausedReason}</p> : null}
              {sla.pausedTotalSeconds ? <p className="text-muted-foreground">{describePausedTime(sla.pausedTotalSeconds)}</p> : null}
              <div className="flex flex-wrap gap-2 pt-2">
                {pause ? <Button size="sm" variant="outline" onClick={() => { setPauseTarget({ sla, action: pause }); setReason(''); setError('') }}>Pause</Button> : null}
                {resume ? <Button size="sm" onClick={() => void execute(sla, resume)} disabled={busy}>Resume</Button> : null}
              </div>
            </CardContent>
          </Card>
        })}
      </div>}
      <Dialog open={Boolean(pauseTarget)} onOpenChange={(open) => { if (!open && !busy) setPauseTarget(undefined) }}>
        <DialogContent>
          <form onSubmit={submitPause}>
            <DialogHeader><DialogTitle>Pause {pauseTarget ? humanize(pauseTarget.sla.targetId) : 'SLA'}</DialogTitle><DialogDescription>The reason is recorded with the SLA clock.</DialogDescription></DialogHeader>
            <div className="grid gap-3 py-4"><Label htmlFor="pause-reason">Pause reason</Label><Textarea id="pause-reason" value={reason} onChange={(event) => setReason(event.target.value)} />{error ? <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert> : null}</div>
            <DialogFooter><Button type="submit" disabled={busy}>{busy ? 'Working…' : 'Confirm pause'}</Button></DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </section>
  )
}

export function describeDeadline(dueAt: string | null | undefined, now: Date): string {
  if (!dueAt) return 'No deadline'
  const milliseconds = new Date(dueAt).getTime() - now.getTime()
  if (!Number.isFinite(milliseconds)) return 'Deadline unavailable'
  const hours = Math.max(1, Math.ceil(Math.abs(milliseconds) / 3_600_000))
  return milliseconds < 0 ? `Overdue by ${hours} ${hours === 1 ? 'hour' : 'hours'}` : `Due in ${hours} ${hours === 1 ? 'hour' : 'hours'}`
}

function describePausedTime(seconds: number) {
  const hours = Math.max(1, Math.round(seconds / 3600))
  return `Paused for ${hours} ${hours === 1 ? 'hour' : 'hours'}`
}

function formatTimestamp(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.valueOf()) ? value : date.toLocaleString()
}

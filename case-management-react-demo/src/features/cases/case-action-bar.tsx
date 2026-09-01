import { useState, type FormEvent } from 'react'

import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import type { AvailableAction, CaseSummary } from '@/lib/api-types'
import { ApiError, type CaseApiClient } from '@/lib/case-api-client'

interface CaseActionBarProps {
  client: CaseApiClient
  caseItem: CaseSummary
  onChanged(): void
}

export function CaseActionBar({ client, caseItem, onChanged }: CaseActionBarProps) {
  const [pendingAction, setPendingAction] = useState<AvailableAction>()
  const [title, setTitle] = useState(caseItem.title ?? '')
  const [outcome, setOutcome] = useState('')
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const actions = caseItem.availableActions.filter((action) => ['update', 'close', 'cancel'].includes(action.action))

  function open(action: AvailableAction) {
    setPendingAction(action)
    setTitle(caseItem.title ?? '')
    setOutcome('')
    setReason('')
    setError('')
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!pendingAction) return
    if (pendingAction.action === 'cancel' && !reason.trim()) {
      setError('Enter a reason before cancelling this case.')
      return
    }

    const payload = pendingAction.action === 'update'
      ? { title }
      : pendingAction.action === 'close'
        ? { outcome: outcome.trim() || undefined }
        : { reason: reason.trim() }

    setBusy(true)
    setError('')
    try {
      await client.executeCaseAction(pendingAction, caseItem.version, payload)
      setPendingAction(undefined)
      onChanged()
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 412) {
        setError('This case changed on the server. The workspace has been refreshed.')
        onChanged()
      } else if (cause instanceof ApiError && cause.status === 403) {
        setError('The backend refused this action for your account.')
      } else {
        setError(cause instanceof Error ? cause.message : 'Could not perform this case action')
      }
    } finally {
      setBusy(false)
    }
  }

  if (actions.length === 0) return null

  const action = pendingAction?.action
  const confirmLabel = action === 'update' ? 'Save changes' : action === 'close' ? 'Confirm close' : 'Confirm cancellation'

  return (
    <div className="case-actions" aria-label="Case actions">
      {actions.map((available) => <Button key={available.action} size="sm" variant={available.action === 'cancel' ? 'destructive' : 'outline'} onClick={() => open(available)}>{available.name}</Button>)}
      <Dialog open={Boolean(pendingAction)} onOpenChange={(next) => { if (!next && !busy) setPendingAction(undefined) }}>
        <DialogContent>
          <form onSubmit={submit}>
            <DialogHeader>
              <DialogTitle>{pendingAction?.name} case</DialogTitle>
              <DialogDescription>This action uses the current case version and will refresh the workspace.</DialogDescription>
            </DialogHeader>
            <div className="grid gap-4 py-4">
              {action === 'update' ? <div className="grid gap-2"><Label htmlFor="case-title">Title</Label><Input id="case-title" value={title} onChange={(event) => setTitle(event.target.value)} /></div> : null}
              {action === 'close' ? <div className="grid gap-2"><Label htmlFor="case-outcome">Outcome</Label><Input id="case-outcome" value={outcome} onChange={(event) => setOutcome(event.target.value)} /></div> : null}
              {action === 'cancel' ? <div className="grid gap-2"><Label htmlFor="case-reason">Reason</Label><Textarea id="case-reason" value={reason} onChange={(event) => setReason(event.target.value)} /></div> : null}
              {error ? <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert> : null}
            </div>
            <DialogFooter><Button type="submit" variant={action === 'cancel' ? 'destructive' : 'default'} disabled={busy}>{busy ? 'Working…' : confirmLabel}</Button></DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  )
}

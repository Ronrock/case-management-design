import { useState } from 'react'

import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import type { CaseSummary, TaskFormDefinition, TaskSummary } from '@/lib/api-types'
import { ApiError, type CaseApiClient } from '@/lib/case-api-client'
import { DynamicTaskForm } from './dynamic-task-form'

interface TaskActionsProps {
  client: CaseApiClient
  caseItem: CaseSummary
  task: TaskSummary
  onChanged(message?: string): void
}

export function TaskActions({ client, caseItem, task, onChanged }: TaskActionsProps) {
  const [form, setForm] = useState<TaskFormDefinition>()
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const claim = task.availableActions?.find((action) => action.action === 'claim')
  const complete = task.availableActions?.find((action) => action.action === 'complete')

  async function runClaim() {
    if (!claim) return
    setBusy(true)
    setError('')
    try {
      await client.executeTaskAction(claim, task.version)
      onChanged()
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 403) {
        setError('The backend refused this task action for your account.')
      } else if (reason instanceof ApiError && reason.status === 412) {
        const message = 'This item changed on the server. The workspace has been refreshed.'
        setError(message)
        onChanged(message)
      } else {
        setError(reason instanceof Error ? reason.message : 'Could not claim this task')
      }
    } finally { setBusy(false) }
  }

  async function showComplete() {
    setBusy(true)
    setError('')
    try {
      setForm(await client.taskForm(caseItem, task))
      setOpen(true)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not load the task form')
    } finally { setBusy(false) }
  }

  return (
    <div className="mt-4">
      <div className="flex gap-2">
        {claim ? <Button size="sm" onClick={runClaim} disabled={busy}>{busy ? 'Working…' : claim.name}</Button> : null}
        {complete ? <Button size="sm" onClick={showComplete} disabled={busy}>{complete.name}</Button> : null}
      </div>
      {error ? <Alert className="mt-3" variant="destructive"><AlertDescription>{error}</AlertDescription></Alert> : null}
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>Complete {task.name}</DialogTitle><DialogDescription>Fields come from the case definition's pinned contract.</DialogDescription></DialogHeader>
          {form && complete ? <DynamicTaskForm definition={form} submitting={busy} onSubmit={async (variables) => {
            setBusy(true)
            setError('')
            try {
              await client.executeTaskAction(complete, task.version, variables)
              setOpen(false)
              onChanged()
            } catch (reason) {
              if (reason instanceof ApiError && reason.status === 403) {
                setError('The backend refused this task action for your account.')
              } else if (reason instanceof ApiError && reason.status === 412) {
                const message = 'This item changed on the server. The workspace has been refreshed.'
                setError(message)
                onChanged(message)
              } else {
                setError(reason instanceof Error ? reason.message : 'Could not complete this task')
              }
            } finally { setBusy(false) }
          }} /> : null}
        </DialogContent>
      </Dialog>
    </div>
  )
}

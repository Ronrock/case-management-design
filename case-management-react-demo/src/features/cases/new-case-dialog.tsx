import { useState, type FormEvent } from 'react'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import type { CreateComplaintInput } from '@/lib/api-types'

interface NewCaseDialogProps {
  open: boolean
  onOpenChange(open: boolean): void
  onCreate(input: CreateComplaintInput): Promise<void>
}

export function NewCaseDialog({ open, onOpenChange, onCreate }: NewCaseDialogProps) {
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    const amountText = String(data.get('amount') ?? '').trim()
    setSubmitting(true)
    setError('')
    try {
      await onCreate({
        tenantId: String(data.get('tenantId')),
        businessKey: String(data.get('businessKey')),
        title: String(data.get('title')),
        channel: String(data.get('channel')),
        summary: String(data.get('summary')),
        amount: amountText ? Number.parseInt(amountText, 10) : undefined,
      })
      onOpenChange(false)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not create the complaint')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader><DialogTitle>New complaint</DialogTitle><DialogDescription>Start a complaint case in the live backend.</DialogDescription></DialogHeader>
        <form className="grid gap-4" onSubmit={submit}>
          <Field label="Tenant" name="tenantId" defaultValue="" />
          <Field label="Business key" name="businessKey" />
          <Field label="Title" name="title" />
          <Field label="Channel" name="channel" />
          <div className="grid gap-2"><Label htmlFor="summary">Summary</Label><Textarea id="summary" name="summary" required /></div>
          <Field label="Amount" name="amount" type="number" required={false} />
          {error ? <p role="alert" className="text-sm text-destructive">{error}</p> : null}
          <DialogFooter><Button type="submit" disabled={submitting}>{submitting ? 'Creating…' : 'Create case'}</Button></DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function Field({ label, name, type = 'text', defaultValue, required = true }: { label: string; name: string; type?: string; defaultValue?: string; required?: boolean }) {
  return <div className="grid gap-2"><Label htmlFor={name}>{label}</Label><Input id={name} name={name} type={type} defaultValue={defaultValue} required={required} /></div>
}

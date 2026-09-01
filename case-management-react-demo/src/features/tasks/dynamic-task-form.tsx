import { useState, type FormEvent } from 'react'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import type { TaskFormDefinition } from '@/lib/api-types'

interface DynamicTaskFormProps {
  definition: TaskFormDefinition
  onSubmit(values: Record<string, unknown>): void | Promise<void>
  submitting?: boolean
}

export function DynamicTaskForm({ definition, onSubmit, submitting = false }: DynamicTaskFormProps) {
  const [error, setError] = useState('')
  const unsupported = Object.entries(definition.schema.properties).find(([, property]) => !['string', 'integer'].includes(property.type))

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (unsupported) return
    const form = event.currentTarget
    if (!form.reportValidity()) {
      setError('Complete every required field.')
      return
    }
    const data = new FormData(form)
    const values: Record<string, unknown> = {}
    for (const [name, property] of Object.entries(definition.schema.properties)) {
      const raw = String(data.get(name) ?? '')
      if (raw === '') continue
      values[name] = property.type === 'integer' ? Number.parseInt(raw, 10) : raw
    }
    setError('')
    await onSubmit(values)
  }

  return (
    <form className="grid gap-4" onSubmit={submit} noValidate>
      {Object.entries(definition.schema.properties).map(([name, property]) => {
        const label = property.title || humanize(name)
        const required = definition.schema.required?.includes(name)
        const id = `task-field-${name}`
        return (
          <div className="grid gap-2" key={name}>
            <Label htmlFor={id}>{label}{required ? <span aria-hidden="true"> *</span> : null}</Label>
            {property.enum ? (
              <select id={id} name={name} aria-label={label} required={required} className="h-9 rounded-md border border-input bg-background px-3 text-sm">
                <option value="">Select…</option>
                {property.enum.map((value) => <option key={value} value={value}>{humanize(value)}</option>)}
              </select>
            ) : definition.uiSchema?.[name]?.widget === 'textarea' ? (
              <Textarea id={id} name={name} aria-label={label} required={required} />
            ) : (
              <Input id={id} name={name} aria-label={label} type={property.type === 'integer' ? 'number' : 'text'} step={property.type === 'integer' ? 1 : undefined} required={required} />
            )}
          </div>
        )
      })}
      {unsupported ? <p role="alert" className="text-sm text-destructive">Unsupported field {unsupported[0]}: {unsupported[1].type}</p> : null}
      {error ? <p role="alert" className="text-sm text-destructive">{error}</p> : null}
      <Button type="submit" disabled={submitting || Boolean(unsupported)}>{submitting ? 'Completing…' : 'Complete task'}</Button>
    </form>
  )
}

function humanize(value: string) {
  return value.replace(/[-_]/g, ' ').replace(/^./, (letter) => letter.toUpperCase())
}

import { useState, type FormEvent } from 'react'

import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import type { ApiCredentials } from '@/lib/api-types'

interface ConnectionGateProps {
  onConnect(credentials: ApiCredentials): Promise<void>
}

export function ConnectionGate({ onConnect }: ConnectionGateProps) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [connecting, setConnecting] = useState(false)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setConnecting(true)
    setError('')
    try {
      await onConnect({ username, password })
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not connect')
    } finally {
      setConnecting(false)
    }
  }

  return (
    <main className="flex min-h-svh items-center justify-center bg-muted p-6">
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle>Case management demo</CardTitle>
          <CardDescription>Connect to the live REST backend.</CardDescription>
        </CardHeader>
        <CardContent>
          <form className="grid gap-5" onSubmit={submit}>
            <div className="grid gap-2">
              <Label htmlFor="username">Username</Label>
              <Input id="username" autoComplete="username" required value={username} onChange={(event) => setUsername(event.target.value)} />
            </div>
            <div className="grid gap-2">
              <Label htmlFor="password">Password</Label>
              <Input id="password" type="password" autoComplete="current-password" required value={password} onChange={(event) => setPassword(event.target.value)} />
            </div>
            {error ? <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert> : null}
            <Button type="submit" disabled={connecting}>{connecting ? 'Connecting…' : 'Connect'}</Button>
          </form>
        </CardContent>
      </Card>
    </main>
  )
}

import { vi } from 'vitest'

export interface FetchCall {
  url: string
  init: RequestInit
}

export interface ScriptedResponse {
  status?: number
  body?: unknown
  headers?: HeadersInit
}

export function installFetchScript(
  respond: (call: FetchCall) => ScriptedResponse,
): FetchCall[] {
  const calls: FetchCall[] = []

  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
      const call = { url: String(input), init }
      calls.push(call)
      const scripted = respond(call)
      return new Response(
        scripted.body === undefined ? undefined : JSON.stringify(scripted.body),
        {
          status: scripted.status ?? 200,
          headers: {
            'Content-Type': 'application/json',
            ...scripted.headers,
          },
        },
      )
    }),
  )

  return calls
}

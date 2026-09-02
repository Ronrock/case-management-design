import { describe, expect, it } from 'vitest'

import { CaseApiClient } from './case-api-client'

describe('case API client', () => {
  it('invokes fetch without using the client as its receiver', async () => {
    const receiverSensitiveFetch = function (this: unknown) {
      if (this !== undefined && this !== globalThis) {
        throw new TypeError("Failed to execute 'fetch' on 'Window': Illegal invocation")
      }

      return Promise.resolve(new Response(JSON.stringify({
        items: [],
        page: 0,
        pageSize: 1,
        totalItems: 0,
        totalPages: 0,
      }), {
        headers: { 'Content-Type': 'application/json' },
      }))
    } as typeof fetch

    const client = new CaseApiClient({
      baseUrl: '/case-api/v2',
      credentials: { username: 'alice', password: 'alice' },
      fetchImpl: receiverSensitiveFetch,
    })

    await expect(client.connect()).resolves.toEqual({
      items: [],
      page: 0,
      pageSize: 1,
      totalItems: 0,
      totalPages: 0,
    })
  })

  it('resolves root-relative task actions under the configured case API', async () => {
    let requestedUrl: string | URL | Request | undefined
    const recordingFetch = ((url: string | URL | Request) => {
      requestedUrl = url
      return Promise.resolve(new Response('{}', {
        headers: { 'Content-Type': 'application/json' },
      }))
    }) as typeof fetch

    const client = new CaseApiClient({
      baseUrl: '/case-api/v2',
      credentials: { username: 'alice', password: 'alice' },
      fetchImpl: recordingFetch,
    })

    await client.executeTaskAction({
      action: 'claim',
      name: 'Claim',
      href: '/tasks/task-1/claim',
      method: 'POST',
      formKey: null,
    }, 1)

    expect(requestedUrl).toBe('/case-api/v2/tasks/task-1/claim')
  })

  it('executes advertised case and SLA actions with resource versions', async () => {
    const calls: Array<{ url: string; init: RequestInit }> = []
    const fetchImpl = (async (input: RequestInfo | URL, init: RequestInit = {}) => {
      calls.push({ url: String(input), init })
      return new Response('{}', { headers: { 'Content-Type': 'application/json' } })
    }) as typeof fetch
    const client = new CaseApiClient({
      baseUrl: '/case-api/v2',
      credentials: { username: 'alice', password: 'alice' },
      fetchImpl,
    })

    await client.executeCaseAction(
      { action: 'update', name: 'Update', href: '/cases/case-1', method: 'PATCH' },
      3,
      { title: 'Updated title' },
    )
    await client.executeSlaAction(
      { action: 'pause', name: 'Pause', href: '/cases/case-1/slas/sla-1/pause', method: 'POST' },
      5,
      'Waiting for customer',
    )

    expect(calls.map(({ url }) => url)).toEqual([
      '/case-api/v2/cases/case-1',
      '/case-api/v2/cases/case-1/slas/sla-1/pause',
    ])
    expect(new Headers(calls[0].init.headers).get('If-Match')).toBe('"3"')
    expect(new Headers(calls[0].init.headers).get('Content-Type')).toBe('application/merge-patch+json')
    expect(JSON.parse(String(calls[0].init.body))).toEqual({ title: 'Updated title' })
    expect(new Headers(calls[1].init.headers).get('If-Match')).toBe('"5"')
    expect(JSON.parse(String(calls[1].init.body))).toEqual({ reason: 'Waiting for customer' })
  })

  it('lists work and posts an internal comment through its advertised action', async () => {
    const calls: Array<{ url: string; init: RequestInit }> = []
    const fetchImpl = (async (input: RequestInfo | URL, init: RequestInit = {}) => {
      calls.push({ url: String(input), init })
      return new Response(init.method === 'POST'
        ? JSON.stringify({ id: 'comment-1', caseId: 'case-1', text: 'Checked', visibility: 'internal' })
        : '[]', { headers: { 'Content-Type': 'application/json' } })
    }) as typeof fetch
    const client = new CaseApiClient({
      baseUrl: '/case-api/v2',
      credentials: { username: 'alice', password: 'alice' },
      fetchImpl,
    })

    await client.listTasks()
    await client.listComments('case-1')
    await client.addComment(
      { action: 'comment', name: 'Comment', href: '/cases/case-1/comments', method: 'POST' },
      'Checked',
    )

    expect(calls.map(({ url }) => url)).toEqual([
      '/case-api/v2/tasks',
      '/case-api/v2/cases/case-1/comments',
      '/case-api/v2/cases/case-1/comments',
    ])
    expect(JSON.parse(String(calls[2].init.body))).toEqual({ text: 'Checked', visibility: 'internal' })
  })

  it('loads enough case events for the activity view', async () => {
    const requested: string[] = []
    const fetchImpl = (async (input: RequestInfo | URL) => {
      requested.push(String(input))
      const body = String(input).endsWith('/cases/case-1') ? '{}' : '[]'
      return new Response(body, { headers: { 'Content-Type': 'application/json' } })
    }) as typeof fetch
    const client = new CaseApiClient({
      baseUrl: '/case-api/v2',
      credentials: { username: 'alice', password: 'alice' },
      fetchImpl,
    })

    await client.loadWorkspace('case-1')

    expect(requested).toContain('/case-api/v2/cases/case-1/events?after=0&limit=100')
  })

  it('loads an advertised task form from the public version-pinned route', async () => {
    const requested: string[] = []
    const definition = {
      schema: {
        type: 'object' as const,
        required: ['outcome'],
        properties: { outcome: { type: 'string' as const } },
      },
    }
    const fetchImpl = (async (input: RequestInfo | URL) => {
      requested.push(String(input))
      return new Response(JSON.stringify(definition), { headers: { 'Content-Type': 'application/schema+json' } })
    }) as typeof fetch
    const client = new CaseApiClient({
      baseUrl: '/case-api/v2',
      credentials: { username: 'alice', password: 'alice' },
      fetchImpl,
    })

    await expect(client.taskForm({
      id: 'case-1', tenantId: 't1', caseDefinitionKey: 'complaint', caseDefinitionVersion: 3,
      state: 'ACTIVE', version: 1, availableActions: [],
    }, {
      id: 'task-1', caseId: 'case-1', name: 'Assess', state: 'CLAIMED', assignee: 'alice',
      candidateGroups: [], version: 2,
      availableActions: [{ action: 'complete', name: 'Complete', href: '/tasks/task-1/complete', method: 'POST', formKey: 'assessForm' }],
    })).resolves.toEqual(definition)

    expect(requested).toEqual([
      '/case-api/v2/case-definitions/complaint/versions/3/forms/assessForm',
    ])
  })
})

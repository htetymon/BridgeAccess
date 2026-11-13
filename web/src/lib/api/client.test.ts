import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiRequest, parseRetryAfter, transport } from './client'
import { resetDeviceIdCacheForTests } from '../deviceId'

const DEVICE = '11111111-2222-4333-8444-555555555555'
const original = { ...transport }

type Call = { url: string; init: RequestInit }
let calls: Call[]
let sleeps: number[]

function json(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })
}

function envelope(code: string, message = 'msg', details?: unknown) {
  return { error: { code, message, details, requestId: 'server-req-id' } }
}

/** Queue of responses (or errors) returned by successive fetch calls. */
function respondWith(...results: Array<Response | Error | 'hang'>) {
  transport.fetch = (url, init) => {
    calls.push({ url, init })
    const next = results.shift()
    if (next === 'hang') {
      // Never resolves on its own; rejects when the request is aborted (timeout or caller).
      return new Promise((_, reject) => init.signal!.addEventListener('abort', () => reject(new Error('aborted'))))
    }
    if (next instanceof Error) return Promise.reject(next)
    if (!next) throw new Error('unexpected extra request')
    return Promise.resolve(next)
  }
}

const header = (c: Call, name: string) => (c.init.headers as Record<string, string>)[name]

beforeEach(() => {
  calls = []
  sleeps = []
  resetDeviceIdCacheForTests()
  vi.stubGlobal('localStorage', {
    getItem: () => DEVICE,
    setItem: () => {},
  })
  transport.sleep = async (ms) => void sleeps.push(ms)
  transport.isOnline = () => true
  transport.random = () => 1 // upper bound of full jitter, so delays are deterministic
})

afterEach(() => {
  Object.assign(transport, original)
  vi.unstubAllGlobals()
})

describe('request shape', () => {
  it('attaches device ID, request ID and Accept; drops empty query values', async () => {
    respondWith(json(200, { items: [], nextCursor: null }))

    const res = await apiRequest('/packages', { query: { q: 'solar', subject: undefined, cursor: '', limit: 20 } })

    expect(res).toEqual({ items: [], nextCursor: null })
    expect(calls[0].url).toBe('/api/v1/packages?q=solar&limit=20')
    expect(header(calls[0], 'X-Device-Id')).toBe(DEVICE)
    expect(header(calls[0], 'X-Request-Id')).toMatch(/^[0-9a-f-]{36}$/)
    expect(header(calls[0], 'Accept')).toBe('application/json')
  })

  it('serialises JSON bodies and leaves FormData to the browser', async () => {
    respondWith(new Response(null, { status: 204 }), json(202, { id: 'x' }))

    expect(await apiRequest('/me/bookmarks/1', { method: 'PUT', body: { a: 1 } })).toBeUndefined()
    expect(header(calls[0], 'Content-Type')).toBe('application/json')
    expect(calls[0].init.body).toBe('{"a":1}')

    const form = new FormData()
    await apiRequest('/packages', { method: 'POST', body: form })
    expect(header(calls[1], 'Content-Type')).toBeUndefined()
    expect(calls[1].init.body).toBe(form)
  })
})

describe('retry', () => {
  it('retries transient 5xx with exponential backoff and keeps one request ID', async () => {
    respondWith(json(503, envelope('SERVICE_UNAVAILABLE')), json(502, {}), json(200, { ok: true }))

    expect(await apiRequest('/packages')).toEqual({ ok: true })
    expect(calls).toHaveLength(3)
    expect(sleeps).toEqual([500, 1000])
    expect(new Set(calls.map((c) => header(c, 'X-Request-Id'))).size).toBe(1)
  })

  it('honours Retry-After on 429', async () => {
    respondWith(json(429, envelope('RATE_LIMITED'), { 'Retry-After': '3' }), json(200, {}))
    await apiRequest('/packages')
    expect(sleeps).toEqual([3000])
  })

  it('caps Retry-After at maxDelayMs', async () => {
    respondWith(json(429, envelope('RATE_LIMITED'), { 'Retry-After': '120' }), json(200, {}))
    await apiRequest('/packages')
    expect(sleeps).toEqual([8000])
  })

  it('gives up after the last attempt with the server error', async () => {
    respondWith(json(503, {}), json(503, {}), json(503, envelope('SERVICE_UNAVAILABLE', 'Busy')))

    const err = await apiRequest('/packages').catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err).toMatchObject({ kind: 'http', status: 503, code: 'SERVICE_UNAVAILABLE', message: 'Busy' })
    expect(calls).toHaveLength(3)
  })

  it('does not retry client errors and maps the envelope', async () => {
    const details = [{ field: 'q', code: 'TOO_SHORT', message: 'Enter at least 2 characters.' }]
    respondWith(json(400, envelope('VALIDATION_FAILED', 'Some fields need attention.', details)))

    const err = await apiRequest('/packages', { query: { q: 'a' } }).catch((e) => e)
    expect(err).toMatchObject({ kind: 'http', status: 400, code: 'VALIDATION_FAILED', details, requestId: 'server-req-id' })
    expect(calls).toHaveLength(1)
  })

  it('keeps code-specific error fields as extras', async () => {
    respondWith(
      json(409, {
        error: { code: 'DUPLICATE_LINK', message: 'Taken.', requestId: 'x', existingPackageId: 'p1', isOwn: true },
      }),
    )

    const err = (await apiRequest('/packages', { method: 'POST', body: {} }).catch((e: unknown) => e)) as ApiError
    expect(err).toMatchObject({ code: 'DUPLICATE_LINK', extras: { existingPackageId: 'p1', isOwn: true } })
    expect(err.extras).not.toHaveProperty('code')
  })

  it('does not retry POST unless opted in', async () => {
    respondWith(json(503, {}))
    await expect(apiRequest('/packages', { method: 'POST', body: {} })).rejects.toMatchObject({ status: 503 })
    expect(calls).toHaveLength(1)

    respondWith(json(503, {}), json(200, { results: [] }))
    await apiRequest('/me/sync', { method: 'POST', body: {}, retry: { attempts: 3 } })
    expect(calls).toHaveLength(3)
  })

  it('retries network failures', async () => {
    respondWith(new TypeError('Failed to fetch'), json(200, { ok: 1 }))
    expect(await apiRequest('/packages')).toEqual({ ok: 1 })
  })
})

describe('timeouts, offline and cancellation', () => {
  it('times out a hanging attempt, retries, then reports a timeout', async () => {
    respondWith('hang', 'hang')
    const err = await apiRequest('/packages', { timeoutMs: 20, retry: { attempts: 2 } }).catch((e) => e)
    expect(err).toMatchObject({ kind: 'timeout' })
    expect(calls).toHaveLength(2)
  })

  it('fails fast when offline, without a request', async () => {
    transport.isOnline = () => false
    respondWith()
    await expect(apiRequest('/packages')).rejects.toMatchObject({ kind: 'offline' })
    expect(calls).toHaveLength(0)
  })

  it('stops retrying when the connection drops between attempts', async () => {
    respondWith(new TypeError('Failed to fetch'))
    transport.sleep = async () => {
      transport.isOnline = () => false
    }
    await expect(apiRequest('/packages')).rejects.toMatchObject({ kind: 'offline' })
    expect(calls).toHaveLength(1)
  })

  it('treats caller cancellation as aborted and does not retry', async () => {
    const controller = new AbortController()
    respondWith('hang')
    const pending = apiRequest('/packages', { signal: controller.signal })
    controller.abort()
    await expect(pending).rejects.toMatchObject({ kind: 'aborted' })
    expect(calls).toHaveLength(1)
  })

  it('reports an unparseable success body as an ApiError', async () => {
    respondWith(new Response('<html>proxy</html>', { status: 200 }))
    await expect(apiRequest('/packages')).rejects.toMatchObject({ kind: 'http', status: 200 })
  })
})

describe('parseRetryAfter', () => {
  it('accepts seconds and HTTP dates', () => {
    expect(parseRetryAfter('5')).toBe(5000)
    expect(parseRetryAfter(null)).toBeUndefined()
    expect(parseRetryAfter('soon')).toBeUndefined()
    const inTwoSeconds = new Date(Date.now() + 2000).toUTCString()
    expect(parseRetryAfter(inTwoSeconds)).toBeGreaterThan(0)
    expect(parseRetryAfter(inTwoSeconds)).toBeLessThanOrEqual(2000)
  })
})

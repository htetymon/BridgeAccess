import 'fake-indexeddb/auto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { transport } from './api/client'
import { createDb, type BridgeAccessDb, type SyncOperation } from './db'
import { resetDeviceIdCacheForTests } from './deviceId'
import { enqueue } from './outbox'
import { getSyncStatus, resetSyncForTests, SYNC_BATCH, syncNow } from './sync'

const DEVICE = '11111111-2222-4333-8444-555555555555'
const original = { ...transport }

type Sent = { url: string; headers: Record<string, string>; ops: { opId: string; type: string; payload: unknown }[] }

let db: BridgeAccessDb
let n = 0
let sent: Sent[]
/** How the fake server answers each op; default ok. */
let answer: (op: Sent['ops'][number]) => 'ok' | 'rejected' | 'none'
let failNext: number

const bookmark = (packageId: string): SyncOperation => ({ type: 'bookmark.add', payload: { packageId } })

beforeEach(async () => {
  db = createDb(`sync-test-${n++}`)
  await db.open()
  sent = []
  answer = () => 'ok'
  failNext = 0
  resetSyncForTests()
  resetDeviceIdCacheForTests()
  vi.stubGlobal('localStorage', { getItem: () => DEVICE, setItem: () => {} })
  transport.isOnline = () => true
  transport.sleep = async () => {}
  transport.random = () => 1
  transport.fetch = async (url, init) => {
    const body = JSON.parse(init.body as string) as { ops: Sent['ops'] }
    sent.push({ url, headers: init.headers as Record<string, string>, ops: body.ops })
    if (failNext > 0) {
      failNext--
      return new Response(JSON.stringify({ error: { code: 'SERVICE_UNAVAILABLE', message: 'busy' } }), { status: 503 })
    }
    const results = body.ops
      .map((op) => ({ opId: op.opId, status: answer(op) }))
      .filter((r) => r.status !== 'none')
      .map((r) => (r.status === 'rejected' ? { ...r, error: { code: 'NOT_FOUND', message: 'gone' } } : r))
    return Response.json({ results })
  }
})

afterEach(async () => {
  resetSyncForTests()
  Object.assign(transport, original)
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  await db.delete()
})

describe('syncNow', () => {
  it('sends the outbox oldest first with the device ID, and empties it on ok', async () => {
    await enqueue(bookmark('a'), db)
    await enqueue({ type: 'download.remove', payload: { packageId: 'b' } }, db)

    await syncNow(db)

    expect(sent).toHaveLength(1)
    expect(sent[0].url).toBe('/api/v1/sync')
    expect(sent[0].headers['X-Device-Id']).toBe(DEVICE)
    expect(sent[0].ops.map((o) => [o.type, o.payload])).toEqual([
      ['bookmark.add', { packageId: 'a' }],
      ['download.remove', { packageId: 'b' }],
    ])
    expect(await db.outbox.count()).toBe(0)
    expect(getSyncStatus().state).toBe('idle')
  })

  it('sends a long outbox in batches of 50', async () => {
    for (let i = 0; i < SYNC_BATCH + 7; i++) await enqueue(bookmark(`p${i}`), db)

    await syncNow(db)

    expect(sent.map((s) => s.ops.length)).toEqual([SYNC_BATCH, 7])
    expect(await db.outbox.count()).toBe(0)
  })

  it('drops rejected actions too (permanent), and keeps any without a result', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    await enqueue(bookmark('ok'), db)
    await enqueue(bookmark('gone'), db)
    await enqueue(bookmark('later'), db)
    answer = (op) => {
      const id = (op.payload as { packageId: string }).packageId
      return id === 'gone' ? 'rejected' : id === 'later' ? 'none' : 'ok'
    }

    await syncNow(db)

    const left = await db.outbox.toArray()
    expect(left.map((e) => e.payload)).toEqual([{ packageId: 'later' }])
  })

  it('keeps everything when the request fails, counts the attempt, and reports the error', async () => {
    await enqueue(bookmark('a'), db)
    failNext = 99

    await syncNow(db)

    const [row] = await db.outbox.toArray()
    expect(row.attempts).toBe(1)
    expect(row.lastError).toBe('busy')
    expect(getSyncStatus()).toMatchObject({ state: 'error', lastError: 'busy' })
  })

  it('a later sync after a failure sends the same actions again and clears the error', async () => {
    await enqueue(bookmark('a'), db)
    failNext = 99
    await syncNow(db)
    failNext = 0

    await syncNow(db)

    expect(sent.at(-1)!.ops).toHaveLength(1)
    expect(await db.outbox.count()).toBe(0)
    expect(getSyncStatus().lastError).toBeUndefined()
  })

  it('does nothing offline, and nothing when the outbox is empty', async () => {
    await syncNow(db)
    transport.isOnline = () => false
    await enqueue(bookmark('a'), db)
    await syncNow(db)

    expect(sent).toEqual([])
    expect(await db.outbox.count()).toBe(1)
  })

  it('a second call while syncing joins the first instead of sending twice', async () => {
    await enqueue(bookmark('a'), db)
    await Promise.all([syncNow(db), syncNow(db)])
    expect(sent).toHaveLength(1)
  })

  it('an action queued during a sync is sent by the next one', async () => {
    await enqueue(bookmark('a'), db)
    const first = syncNow(db)
    await enqueue(bookmark('b'), db)
    await first
    await syncNow(db)

    expect(sent.flatMap((s) => s.ops.map((o) => (o.payload as { packageId: string }).packageId))).toEqual(
      expect.arrayContaining(['a', 'b']),
    )
    expect(await db.outbox.count()).toBe(0)
  })
})

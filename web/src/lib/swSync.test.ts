import 'fake-indexeddb/auto'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createDb, DB_NAME, META_DEVICE_ID, type BridgeAccessDb } from './db'
import { enqueue } from './outbox'
import { SYNC_TAG } from './sync'
import source from '../../public/sw-sync.js?raw'

/**
 * Runs public/sw-sync.js (the service worker's Background Sync handler) against a database
 * created by the app's real Dexie schema, to check that its plain-IndexedDB reads and deletes
 * match db.ts and that it speaks the same /sync protocol.
 */

const DEVICE = '11111111-2222-4333-8444-555555555555'

type SyncEvent = { tag: string; waitUntil: (p: Promise<unknown>) => void }

function loadWorker(fetchImpl: typeof fetch) {
  let handler: ((e: SyncEvent) => void) | undefined
  const messages: unknown[] = []
  const self = {
    addEventListener: (type: string, h: (e: SyncEvent) => void) => {
      if (type === 'sync') handler = h
    },
    clients: { matchAll: async () => [{ postMessage: (m: unknown) => messages.push(m) }] },
  }
  new Function('self', 'indexedDB', 'fetch', 'crypto', source)(self, indexedDB, fetchImpl, crypto)
  return {
    messages,
    async fire(tag = SYNC_TAG) {
      let work: Promise<unknown> = Promise.resolve()
      handler!({ tag, waitUntil: (p) => (work = p) })
      await work
    },
  }
}

let db: BridgeAccessDb
let requests: { headers: Record<string, string>; ops: { opId: string; type: string }[] }[]

beforeEach(async () => {
  db = createDb(DB_NAME) // the worker opens the app's database by name
  await db.open()
  requests = []
})

afterEach(async () => {
  db.close()
  await db.delete()
})

const okServer: typeof fetch = async (_url, init) => {
  const body = JSON.parse(init!.body as string)
  requests.push({ headers: init!.headers as Record<string, string>, ops: body.ops })
  return Response.json({ results: body.ops.map((op: { opId: string }) => ({ opId: op.opId, status: 'ok' })) })
}

describe('service worker Background Sync', () => {
  it('sends the outbox with the device ID from meta, deletes it, and tells open pages', async () => {
    await db.meta.put({ key: META_DEVICE_ID, value: DEVICE })
    await enqueue({ type: 'bookmark.add', payload: { packageId: 'a' } }, db)
    await enqueue({ type: 'download.remove', payload: { packageId: 'b' } }, db)
    db.close() // like a closed app: only the worker has the database open

    const worker = loadWorker(okServer)
    await worker.fire()

    await db.open()
    expect(requests).toHaveLength(1)
    expect(requests[0].headers['X-Device-Id']).toBe(DEVICE)
    expect(requests[0].ops.map((o) => o.type)).toEqual(['bookmark.add', 'download.remove'])
    expect(await db.outbox.count()).toBe(0)
    expect(worker.messages).toEqual([{ type: 'outbox-synced' }])
  })

  it('keeps the outbox and fails the sync (so the browser retries) when the server fails', async () => {
    await db.meta.put({ key: META_DEVICE_ID, value: DEVICE })
    await enqueue({ type: 'bookmark.add', payload: { packageId: 'a' } }, db)
    db.close()

    const worker = loadWorker(async () => new Response('busy', { status: 503 }))
    await expect(worker.fire()).rejects.toThrow('503')

    await db.open()
    expect(await db.outbox.count()).toBe(1)
  })

  it('waits for the app when the device ID is not stored yet, and ignores other tags', async () => {
    await enqueue({ type: 'bookmark.add', payload: { packageId: 'a' } }, db)
    db.close()

    const worker = loadWorker(okServer)
    await worker.fire()
    await worker.fire('something-else')

    await db.open()
    expect(requests).toEqual([])
    expect(await db.outbox.count()).toBe(1)
  })
})

import 'fake-indexeddb/auto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { transport } from './api/client'
import { createDb, type BridgeAccessDb, type LocalPackage, type LocalProgress } from './db'
import { resetDeviceIdCacheForTests } from './deviceId'
import { continueLearning, deleteDownload, progressPercent, recentDownloads } from './localLibrary'

const original = { ...transport }
let db: BridgeAccessDb
let n = 0
let calls: { url: string; method: string }[]

function pkg(id: string, extra: Partial<LocalPackage> = {}): LocalPackage {
  return {
    id,
    title: `Package ${id}`,
    subject: 'Science',
    resourceType: 'text',
    packageType: 'text_only',
    sizeBytes: 30_000,
    publishedAt: '2026-11-08T10:15:00.000Z',
    ...extra,
  }
}

const downloaded = (at: string) => ({ downloadStatus: 'downloaded' as const, downloadedAt: at })

function progress(packageId: string, lastOpenedAt: string, extra: Partial<LocalProgress> = {}): LocalProgress {
  return { packageId, completedSteps: [], lastStep: null, lastOpenedAt, learntAt: null, ...extra }
}

beforeEach(async () => {
  db = createDb(`library-test-${n++}`)
  await db.open()
  calls = []
  resetDeviceIdCacheForTests()
  vi.stubGlobal('localStorage', { getItem: () => '11111111-2222-4333-8444-555555555555', setItem: () => {} })
  transport.isOnline = () => true
  transport.sleep = async () => {}
  transport.fetch = async (url, init) => {
    calls.push({ url, method: init.method ?? 'GET' })
    return new Response(null, { status: 204 })
  }
})

afterEach(async () => {
  Object.assign(transport, original)
  vi.unstubAllGlobals()
  await db.delete()
})

describe('progressPercent', () => {
  it('counts only the steps the package has', () => {
    expect(progressPercent('text_only', ['summary'])).toBe(33)
    expect(progressPercent('with_visual', ['summary', 'diagram'])).toBe(50)
    expect(progressPercent('text_only', ['summary', 'diagram'])).toBe(33) // no diagram step here
    expect(progressPercent('text_only', ['summary', 'key_concepts', 'quiz'])).toBe(100)
  })
})

describe('continueLearning', () => {
  it('is the latest opened package that is downloaded and not yet Learnt', async () => {
    await db.packages.bulkAdd([
      pkg('learnt', downloaded('2026-11-10T00:00:00.000Z')),
      pkg('gone'), // progress kept after its download was deleted
      pkg('older', { ...downloaded('2026-11-10T00:00:00.000Z'), packageType: 'with_visual' }),
      pkg('newest', downloaded('2026-11-10T00:00:00.000Z')),
    ])
    await db.progress.bulkAdd([
      progress('learnt', '2026-11-14T00:00:00.000Z', { learntAt: '2026-11-14T00:00:00.000Z' }),
      progress('gone', '2026-11-13T00:00:00.000Z'),
      progress('newest', '2026-11-12T00:00:00.000Z', { completedSteps: ['summary'] }),
      progress('older', '2026-11-11T00:00:00.000Z'),
    ])

    const items = await continueLearning(1, db)
    expect(items.map((i) => i.pkg.id)).toEqual(['newest'])
    expect(items[0].percent).toBe(33)
    expect((await continueLearning(5, db)).map((i) => i.pkg.id)).toEqual(['newest', 'older'])
  })
})

describe('recentDownloads', () => {
  it('lists downloaded packages, latest first, with Learnt', async () => {
    await db.packages.bulkAdd([
      pkg('a', downloaded('2026-11-10T00:00:00.000Z')),
      pkg('b', { downloadStatus: 'downloading' }),
      pkg('c', downloaded('2026-11-12T00:00:00.000Z')),
      pkg('d', downloaded('2026-11-11T00:00:00.000Z')),
      pkg('bookmark-only', { bookmarkedAt: '2026-11-13T00:00:00.000Z' }),
    ])
    await db.progress.add(progress('d', '2026-11-11T00:00:00.000Z', { learntAt: '2026-11-11T00:00:00.000Z' }))

    const items = await recentDownloads(2, db)
    expect(items.map((i) => [i.pkg.id, i.learnt])).toEqual([
      ['c', false],
      ['d', true],
    ])
  })
})

describe('deleteDownload', () => {
  const blob = new Blob(['x'], { type: 'image/webp' })
  const image = { position: 1, source: 'contributor' as const, mimeType: 'image/webp', sizeBytes: 1, altText: null, blob }

  async function seed(extra: Partial<LocalPackage> = {}) {
    await db.packages.add(pkg('p', { ...downloaded('2026-11-10T00:00:00.000Z'), ...extra }))
    await db.bundles.add({ packageId: 'p', bundle: {} as never, storedAt: '2026-11-10T00:00:00.000Z' })
    await db.images.bulkAdd([
      { ...image, id: 'i1', packageId: 'p' },
      { ...image, id: 'other', packageId: 'q' },
    ])
    await db.progress.add(progress('p', '2026-11-10T00:00:00.000Z', { completedSteps: ['summary'] }))
  }

  it('removes the files, keeps progress, and tells the server', async () => {
    await seed()

    await deleteDownload('p', db)

    expect(await db.packages.get('p')).toBeUndefined()
    expect(await db.bundles.get('p')).toBeUndefined()
    expect(await db.images.toCollection().primaryKeys()).toEqual(['other'])
    expect((await db.progress.get('p'))!.completedSteps).toEqual(['summary'])
    expect(calls).toEqual([{ url: '/api/v1/me/downloads/p', method: 'DELETE' }])
    expect(await db.outbox.count()).toBe(0)
  })

  it('keeps a bookmarked package listed, without its download fields', async () => {
    await seed({ bookmarkedAt: '2026-11-11T00:00:00.000Z' })

    await deleteDownload('p', db)

    const row = (await db.packages.get('p'))!
    expect(row.bookmarkedAt).toBe('2026-11-11T00:00:00.000Z')
    expect(row.downloadStatus).toBeUndefined()
    expect(row.downloadedAt).toBeUndefined()
    expect(await db.packages.orderBy('downloadedAt').count()).toBe(0)
  })

  it('works offline and queues the server update', async () => {
    await seed()
    transport.isOnline = () => false

    await deleteDownload('p', db)

    expect(await db.packages.get('p')).toBeUndefined()
    expect(calls).toEqual([])
    const queued = await db.outbox.toArray()
    expect(queued.map((q) => [q.type, q.payload])).toEqual([['download.remove', { packageId: 'p' }]])
  })

  it('replaces a queued download record for the same package', async () => {
    await seed()
    transport.isOnline = () => false
    await db.outbox.add({
      type: 'download.record',
      payload: { packageId: 'p', status: 'completed', sizeBytes: 1 },
      opId: 'x',
      clientTime: '2026-11-10T00:00:00.000Z',
      attempts: 0,
    } as never)

    await deleteDownload('p', db)

    expect((await db.outbox.toArray()).map((q) => q.type)).toEqual(['download.remove'])
  })
})

import 'fake-indexeddb/auto'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { listBookmarks, toggleBookmark, type BookmarkMeta } from './bookmarks'
import { createDb, type BridgeAccessDb } from './db'

const meta = (id: string, title = `Package ${id}`): BookmarkMeta => ({
  id,
  title,
  subject: 'Science',
  resourceType: 'text',
  packageType: 'text_only',
  sizeBytes: 1000,
  publishedAt: '2026-11-08T10:15:00Z',
})

let db: BridgeAccessDb
let n = 0
beforeEach(async () => {
  db = createDb(`bookmarks-test-${n++}`)
  await db.open()
})
afterEach(async () => {
  await db.delete()
})

describe('toggleBookmark', () => {
  it('bookmarks a package that is not downloaded, storing its card data, and removes it again', async () => {
    expect(await toggleBookmark(meta('a'), db)).toBe(true)
    const row = (await db.packages.get('a'))!
    expect(row).toMatchObject({ title: 'Package a', subject: 'Science' })
    expect(row.bookmarkedAt).toBeDefined()
    expect(row.downloadStatus).toBeUndefined()

    expect(await toggleBookmark(meta('a'), db)).toBe(false)
    expect(await db.packages.get('a')).toBeUndefined()
  })

  it('keeps a downloaded package on the device when its bookmark is removed', async () => {
    await db.packages.add({ ...meta('d'), downloadStatus: 'downloaded', downloadedAt: '2026-11-10T00:00:00.000Z' })

    await toggleBookmark(meta('d'), db)
    expect((await db.packages.get('d'))!.downloadStatus).toBe('downloaded')
    await toggleBookmark(meta('d'), db)

    const row = (await db.packages.get('d'))!
    expect(row.bookmarkedAt).toBeUndefined()
    expect(row.downloadedAt).toBe('2026-11-10T00:00:00.000Z')
    expect(await db.packages.orderBy('bookmarkedAt').count()).toBe(0)
  })

  it('queues only the last toggle for sync', async () => {
    await toggleBookmark(meta('a'), db)
    await toggleBookmark(meta('a'), db)
    await toggleBookmark(meta('a'), db)
    await toggleBookmark(meta('b'), db)

    const queued = await db.outbox.toArray()
    expect(queued.map((q) => [q.type, q.payload])).toEqual([
      ['bookmark.add', { packageId: 'a' }],
      ['bookmark.add', { packageId: 'b' }],
    ])
  })
})

describe('listBookmarks', () => {
  it('lists bookmarks newest first, with Learnt, and nothing else', async () => {
    await db.packages.bulkAdd([
      { ...meta('old'), bookmarkedAt: '2026-11-10T00:00:00.000Z' },
      { ...meta('new'), bookmarkedAt: '2026-11-12T00:00:00.000Z' },
      { ...meta('download-only'), downloadStatus: 'downloaded', downloadedAt: '2026-11-13T00:00:00.000Z' },
    ])
    await db.progress.add({
      packageId: 'old',
      completedSteps: ['quiz'],
      lastStep: 'quiz',
      lastOpenedAt: '2026-11-10T00:00:00.000Z',
      learntAt: '2026-11-10T00:00:00.000Z',
    })

    const items = await listBookmarks(db)
    expect(items.map((i) => [i.pkg.id, i.learnt])).toEqual([
      ['new', false],
      ['old', true],
    ])
  })
})

import 'fake-indexeddb/auto'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createDb, type BridgeAccessDb, type LocalPackage } from './db'

const PKG = '0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10'

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

let db: BridgeAccessDb
let n = 0

beforeEach(async () => {
  db = createDb(`test-${n++}`)
  await db.open()
})

afterEach(async () => {
  await db.delete()
})

describe('schema', () => {
  it('creates every table', () => {
    expect(db.tables.map((t) => t.name).sort()).toEqual(
      ['bundles', 'images', 'outbox', 'packages', 'progress', 'quizAttempts'].sort(),
    )
  })

  it('lists downloads and bookmarks from sparse indexes, newest first', async () => {
    await db.packages.bulkAdd([
      pkg('a', { downloadStatus: 'downloaded', downloadedAt: '2026-11-10T09:00:00.000Z' }),
      pkg('b', { bookmarkedAt: '2026-11-11T09:00:00.000Z' }),
      pkg('c', { downloadStatus: 'downloaded', downloadedAt: '2026-11-12T09:00:00.000Z' }),
    ])

    const downloads = await db.packages.orderBy('downloadedAt').reverse().primaryKeys()
    expect(downloads).toEqual(['c', 'a'])
    expect(await db.packages.orderBy('bookmarkedAt').primaryKeys()).toEqual(['b'])
  })

  it("deletes a package's images by packageId", async () => {
    const blob = new Blob(['x'], { type: 'image/webp' })
    const image = { position: 1, source: 'contributor' as const, mimeType: 'image/webp', sizeBytes: 1, altText: null, blob }
    await db.images.bulkAdd([
      { ...image, id: 'i1', packageId: PKG },
      { ...image, id: 'i2', packageId: PKG },
      { ...image, id: 'i3', packageId: 'other' },
    ])

    await db.images.where('packageId').equals(PKG).delete()
    expect(await db.images.toCollection().primaryKeys()).toEqual(['i3'])
  })

  it("finds a package's latest quiz attempt", async () => {
    const attempt = { answers: [], score: 3, totalQuestions: 5, percentage: 60, passMarkPercent: 60, passed: true }
    await db.quizAttempts.bulkAdd([
      { ...attempt, id: 'q1', packageId: PKG, attemptedAt: '2026-11-10T09:20:00.000Z' },
      { ...attempt, id: 'q2', packageId: PKG, attemptedAt: '2026-11-11T09:20:00.000Z' },
      { ...attempt, id: 'q3', packageId: 'other', attemptedAt: '2026-11-12T09:20:00.000Z' },
    ])

    const latest = await db.quizAttempts
      .where('[packageId+attemptedAt]')
      .between([PKG, ''], [PKG, '￿'])
      .last()
    expect(latest?.id).toBe('q2')
  })

  it('keeps the outbox in insertion order and rejects a duplicate opId', async () => {
    const base = { clientTime: '2026-11-10T09:12:00.000Z', attempts: 0 }
    await db.outbox.add({ ...base, opId: 'op-1', type: 'bookmark.add', payload: { packageId: PKG } })
    await db.outbox.add({ ...base, opId: 'op-2', type: 'bookmark.remove', payload: { packageId: PKG } })

    const ops = await db.outbox.orderBy('seq').toArray()
    expect(ops.map((o) => o.opId)).toEqual(['op-1', 'op-2'])
    expect(await db.outbox.where('opId').equals('op-2').first()).toMatchObject({ type: 'bookmark.remove' })

    await expect(
      db.outbox.add({ ...base, opId: 'op-1', type: 'download.remove', payload: { packageId: PKG } }),
    ).rejects.toThrow()
  })
})

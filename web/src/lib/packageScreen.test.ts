import 'fake-indexeddb/auto'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { PackageBundle, PackageDetail } from './api/packages'
import { createDb, type BridgeAccessDb } from './db'
import { fromBundle, fromDetail, readLocal } from './packageScreen'

const ID = '0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10'

const bundle: PackageBundle = {
  schemaVersion: 1,
  package: {
    id: ID,
    title: 'Renewable Energy Basics',
    subject: 'Science',
    resourceType: 'text',
    packageType: 'with_visual',
    steps: ['summary', 'key_concepts', 'diagram', 'quiz'],
    original: { title: 'Renewable Energy 101', author: 'Laura Mitchell', link: 'https://example.org/re' },
    sizeBytes: 850_000,
    publishedAt: '2026-11-08T10:15:00Z',
  },
  content: { overview: 'Intro', keyTakeaways: ['a', 'b'], summary: 's', keyConcepts: [] },
  quiz: { questions: [] },
  images: [
    { id: 'photo', source: 'contributor', position: 1, mimeType: 'image/webp', sizeBytes: 1, altText: 'Turbines', url: '/p' },
    { id: 'diagram', source: 'ai_diagram', position: 0, mimeType: 'image/webp', sizeBytes: 1, altText: 'Diagram', url: '/d' },
  ],
}

let db: BridgeAccessDb
let n = 0
beforeEach(async () => {
  db = createDb(`screen-test-${n++}`)
  await db.open()
})
afterEach(async () => {
  await db.delete()
})

describe('package view', () => {
  it('a downloaded package comes from its bundle, with local badges and the lowest-position cover', () => {
    const view = fromBundle(bundle, {
      pkg: {
        id: ID,
        title: 'x',
        subject: 'Science',
        resourceType: 'text',
        packageType: 'with_visual',
        sizeBytes: 1,
        publishedAt: '',
        downloadStatus: 'downloaded',
        downloadedAt: '2026-11-10T09:00:00.000Z',
      },
      progress: { packageId: ID, completedSteps: [], lastStep: null, lastOpenedAt: '', learntAt: '2026-11-11T00:00:00.000Z' },
    })
    expect(view).toMatchObject({
      title: 'Renewable Energy Basics',
      overview: 'Intro',
      original: { author: 'Laura Mitchell' },
      downloaded: true,
      downloadedAt: '2026-11-10T09:00:00.000Z',
      learnt: true,
      coverUrl: '/d',
      coverAlt: 'Diagram',
    })
  })

  it('a package not on the device comes from the server; Learnt still comes from local progress', () => {
    const detail: PackageDetail = {
      id: ID,
      title: 'T',
      overview: 'o',
      keyTakeaways: [],
      subject: 'Math',
      resourceType: 'video',
      packageType: 'text_only',
      steps: ['summary', 'key_concepts', 'quiz'],
      original: { title: 'O', author: 'A', link: 'https://x.org' },
      sizeBytes: 1,
      imageCount: 0,
      coverImageUrl: null,
      publishedAt: '2026-11-08T10:15:00Z',
      isAiGenerated: true,
    }
    const view = fromDetail(detail, {
      progress: { packageId: ID, completedSteps: ['quiz'], lastStep: null, lastOpenedAt: '', learntAt: '2026-11-11T00:00:00.000Z' },
    })
    expect(view).toMatchObject({ downloaded: false, learnt: true, coverUrl: null })
    expect(view.downloadedAt).toBeUndefined()
  })

  it('readLocal gathers the row, bundle, progress and first stored image', async () => {
    const blob = new Blob(['x'], { type: 'image/webp' })
    await db.bundles.add({ packageId: ID, bundle, storedAt: '2026-11-10T00:00:00.000Z' })
    await db.images.bulkAdd([
      { id: 'photo', packageId: ID, position: 1, source: 'contributor', mimeType: 'image/webp', sizeBytes: 1, altText: null, blob },
      { id: 'diagram', packageId: ID, position: 0, source: 'ai_diagram', mimeType: 'image/webp', sizeBytes: 1, altText: null, blob },
    ])

    const local = await readLocal(ID, db)
    expect(local.bundle?.package.title).toBe('Renewable Energy Basics')
    expect(local.cover?.id).toBe('diagram')
    expect(local.pkg).toBeUndefined()
  })
})

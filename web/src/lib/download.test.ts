import 'fake-indexeddb/auto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, transport } from './api/client'
import type { PackageBundle } from './api/packages'
import { createDb, type BridgeAccessDb } from './db'
import { resetDeviceIdCacheForTests } from './deviceId'
import { downloadPackage, type DownloadProgress, type PackageManifest } from './download'

const DEVICE = '11111111-2222-4333-8444-555555555555'
const PKG = '0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10'
const IMG1 = '8e2b7a64-1c50-4f3d-9d6a-0b7e5c3a2f19'
const IMG2 = '9f3c8b75-2d61-4a4e-8e7b-1c8f6d4b3a20'
const original = { ...transport }

function bundleFor(imageIds: string[]): PackageBundle {
  return {
    schemaVersion: 1,
    package: {
      id: PKG,
      title: 'Renewable Energy Basics',
      subject: 'Science',
      resourceType: 'text',
      packageType: imageIds.length ? 'with_visual' : 'text_only',
      steps: ['summary', 'key_concepts', 'quiz'],
      original: { title: 'Orig', author: 'A', link: 'https://example.org' },
      sizeBytes: 64_740,
      publishedAt: '2026-11-08T10:15:00Z',
    },
    content: { overview: 'o', keyTakeaways: ['k'], summary: 's', keyConcepts: [] },
    quiz: { questions: [] },
    images: imageIds.map((id, i) => ({
      id,
      source: 'contributor',
      position: i + 1,
      mimeType: 'image/webp',
      sizeBytes: 0,
      altText: `alt ${i + 1}`,
      url: `/api/v1/packages/${PKG}/images/${id}`,
    })),
  }
}

/** The server's state: a bundle and some images, each with an ETag. */
type Server = { bundle: Uint8Array; bundleEtag: string; images: Map<string, { bytes: Uint8Array; etag: string }> }

function server(imageIds = [IMG1, IMG2], version = 'v1'): Server {
  return {
    bundle: new TextEncoder().encode(JSON.stringify(bundleFor(imageIds))),
    bundleEtag: `"b-${version}"`,
    images: new Map(
      imageIds.map((id, i) => [id, { bytes: bytesOf(1000 + i * 100, i + 1), etag: `"i-${id}-${version}"` }]),
    ),
  }
}

function bytesOf(length: number, seed: number) {
  return Uint8Array.from({ length }, (_, i) => (i * 7 + seed) % 256)
}

function manifestOf(s: Server): PackageManifest {
  const parts = [
    {
      kind: 'bundle' as const,
      id: PKG,
      url: `/api/v1/packages/${PKG}/bundle`,
      mimeType: 'application/json',
      sizeBytes: s.bundle.byteLength,
      etag: s.bundleEtag,
    },
    ...[...s.images].map(([id, img]) => ({
      kind: 'image' as const,
      id,
      url: `/api/v1/packages/${PKG}/images/${id}`,
      mimeType: 'image/webp',
      sizeBytes: img.bytes.byteLength,
      etag: img.etag,
    })),
  ]
  return { packageId: PKG, version: 'm', totalBytes: parts.reduce((t, p) => t + p.sizeBytes, 0), parts }
}

/** A body that delivers `bytes` and then fails, like a dropped connection. */
function brokenBody(bytes: Uint8Array) {
  // error() discards queued chunks, so fail on the pull after the bytes were read.
  let sent = false
  return new ReadableStream<Uint8Array>(
    {
      pull(c) {
        if (sent) return c.error(new Error('connection reset'))
        sent = true
        c.enqueue(bytes)
      },
    },
    { highWaterMark: 0 },
  )
}

type Call = { url: string; method: string; headers: Record<string, string>; body?: unknown }
type Override = (call: Call, s: Server) => Response | Error | undefined

let db: BridgeAccessDb
let n = 0
let calls: Call[]
let srv: Server
/** Per-request overrides, consumed in order; return undefined to fall through to the server. */
let overrides: Override[]

function route(call: Call): Response {
  const manifestUrl = `/api/v1/packages/${PKG}/manifest`
  if (call.url === manifestUrl) return Response.json(manifestOf(srv))
  if (call.url === `/api/v1/packages/${PKG}/bundle`) {
    return new Response(srv.bundle.slice(), { headers: { ETag: srv.bundleEtag, 'Content-Type': 'application/json' } })
  }
  const image = /\/images\/([^/]+)$/.exec(call.url)
  if (image) {
    const img = srv.images.get(image[1])
    if (!img) return Response.json({ error: { code: 'NOT_FOUND', message: 'nf' } }, { status: 404 })
    const range = /^bytes=(\d+)-$/.exec(call.headers.Range ?? '')
    if (range) {
      const start = Number(range[1])
      if (start >= img.bytes.byteLength) return new Response(null, { status: 416, headers: { ETag: img.etag } })
      return new Response(img.bytes.slice(start), {
        status: 206,
        headers: {
          ETag: img.etag,
          'Content-Range': `bytes ${start}-${img.bytes.byteLength - 1}/${img.bytes.byteLength}`,
        },
      })
    }
    return new Response(img.bytes.slice(), { headers: { ETag: img.etag, 'Content-Type': 'image/webp' } })
  }
  if (call.url === `/api/v1/me/downloads/${PKG}` && call.method === 'PUT') {
    return Response.json({ packageId: PKG, ...(call.body as object) })
  }
  throw new Error(`unexpected request ${call.method} ${call.url}`)
}

beforeEach(async () => {
  db = createDb(`download-test-${n++}`)
  await db.open()
  calls = []
  overrides = []
  srv = server()
  resetDeviceIdCacheForTests()
  vi.stubGlobal('localStorage', { getItem: () => DEVICE, setItem: () => {} })
  transport.sleep = async () => {}
  transport.isOnline = () => true
  transport.random = () => 1
  transport.fetch = async (url, init) => {
    const call: Call = {
      url,
      method: init.method ?? 'GET',
      headers: (init.headers ?? {}) as Record<string, string>,
      body: typeof init.body === 'string' ? JSON.parse(init.body) : undefined,
    }
    calls.push(call)
    const override = overrides.shift()?.(call, srv)
    if (override instanceof Error) throw override
    return override ?? route(call)
  }
})

afterEach(async () => {
  Object.assign(transport, original)
  vi.unstubAllGlobals()
  await db.delete()
})

const urls = () => calls.map((c) => `${c.method} ${c.url.replace(`/api/v1/packages/${PKG}`, '')}`)
const blobBytes = async (b: Blob) => new Uint8Array(await b.arrayBuffer())

describe('downloadPackage', () => {
  it('fetches every part, stores it, marks the package downloaded and records it', async () => {
    const progress: DownloadProgress[] = []
    const result = await downloadPackage(PKG, { store: db, onProgress: (p) => progress.push(p) })

    const total = manifestOf(srv).totalBytes
    expect(result).toEqual({ packageId: PKG, totalBytes: total, fetchedBytes: total, recorded: true })
    expect(urls()).toEqual([
      'GET /manifest',
      'GET /bundle',
      `GET /images/${IMG1}`,
      `GET /images/${IMG2}`,
      `PUT /api/v1/me/downloads/${PKG}`,
    ])
    expect(calls[1].headers['X-Device-Id']).toBe(DEVICE)

    const pkg = await db.packages.get(PKG)
    expect(pkg).toMatchObject({ title: 'Renewable Energy Basics', downloadStatus: 'downloaded' })
    expect(pkg!.downloadedAt).toBeDefined()
    expect((await db.bundles.get(PKG))!.etag).toBe(srv.bundleEtag)
    const img = (await db.images.get(IMG1))!
    expect(img).toMatchObject({ position: 1, altText: 'alt 1', etag: srv.images.get(IMG1)!.etag })
    expect(await blobBytes(img.blob)).toEqual(srv.images.get(IMG1)!.bytes)

    expect(progress.at(0)).toEqual({ receivedBytes: 0, totalBytes: total })
    expect(progress.at(-1)).toEqual({ receivedBytes: total, totalBytes: total })
    expect(calls.at(-1)!.body).toMatchObject({ status: 'completed', sizeBytes: total })
  })

  it('skips parts already stored with the same ETag', async () => {
    await downloadPackage(PKG, { store: db })
    calls = []
    const progress: DownloadProgress[] = []

    const result = await downloadPackage(PKG, { store: db, onProgress: (p) => progress.push(p) })

    expect(result.fetchedBytes).toBe(0)
    expect(urls()).toEqual(['GET /manifest', `PUT /api/v1/me/downloads/${PKG}`])
    expect(progress[0].receivedBytes).toBe(result.totalBytes)
  })

  it('fetches only changed parts of an updated package and drops images it no longer has', async () => {
    await downloadPackage(PKG, { store: db })
    const next = server([IMG1], 'v2')
    next.images.set(IMG1, srv.images.get(IMG1)!) // IMG1 unchanged, IMG2 removed, bundle changed
    srv = next
    calls = []

    await downloadPackage(PKG, { store: db })

    expect(urls()).toEqual(['GET /manifest', 'GET /bundle', `PUT /api/v1/me/downloads/${PKG}`])
    expect(await db.images.where('packageId').equals(PKG).primaryKeys()).toEqual([IMG1])
  })

  it('resumes an interrupted image with Range and keeps the bytes already received', async () => {
    const full = srv.images.get(IMG1)!
    overrides = [
      () => undefined, // manifest
      () => undefined, // bundle
      () => new Response(brokenBody(full.bytes.slice(0, 400)), { headers: { ETag: full.etag } }),
    ]
    const progress: DownloadProgress[] = []

    const result = await downloadPackage(PKG, { store: db, onProgress: (p) => progress.push(p) })

    const resumed = calls[3]
    expect(resumed.url).toContain(IMG1)
    expect(resumed.headers.Range).toBe('bytes=400-')
    expect(await blobBytes((await db.images.get(IMG1))!.blob)).toEqual(full.bytes)
    expect(result.fetchedBytes).toBe(result.totalBytes) // nothing counted twice
    expect(progress.at(-1)!.receivedBytes).toBe(result.totalBytes)
  })

  it('starts the image over when the server ignores Range', async () => {
    const full = srv.images.get(IMG1)!
    overrides = [
      () => undefined,
      () => undefined,
      () => new Response(brokenBody(full.bytes.slice(0, 400)), { headers: { ETag: full.etag } }),
      () => new Response(full.bytes.slice(), { headers: { ETag: full.etag } }), // 200, whole file
    ]
    const progress: DownloadProgress[] = []

    const result = await downloadPackage(PKG, { store: db, onProgress: (p) => progress.push(p) })

    expect(await blobBytes((await db.images.get(IMG1))!.blob)).toEqual(full.bytes)
    expect(result.fetchedBytes).toBe(result.totalBytes)
    expect(Math.max(...progress.map((p) => p.receivedBytes))).toBe(result.totalBytes)
  })

  it('retries a failed connection and gives up after repeated failures without progress', async () => {
    overrides = [() => undefined, () => undefined, ...Array(5).fill(() => new TypeError('Failed to fetch'))]

    await expect(downloadPackage(PKG, { store: db })).rejects.toMatchObject({ kind: 'network' })
    expect(calls).toHaveLength(2 + 5)
  })

  it('leaves a failed download not downloaded, and a retry fetches only what is missing', async () => {
    overrides = [
      () => undefined,
      () => undefined,
      () => undefined,
      () => Response.json({ error: { code: 'NOT_FOUND' } }, { status: 404 }),
    ]

    await expect(downloadPackage(PKG, { store: db })).rejects.toMatchObject({ kind: 'http', status: 404 })
    expect((await db.packages.get(PKG))!.downloadStatus).toBe('downloading')
    expect(await db.bundles.get(PKG)).toBeUndefined()
    expect(await db.images.get(IMG1)).toBeDefined()

    calls = []
    await downloadPackage(PKG, { store: db })
    expect(urls()).toEqual(['GET /manifest', 'GET /bundle', `GET /images/${IMG2}`, `PUT /api/v1/me/downloads/${PKG}`])
    expect((await db.packages.get(PKG))!.downloadStatus).toBe('downloaded')
  })

  it('starts again from a fresh manifest when a part changed mid-download', async () => {
    overrides = [
      () => undefined,
      () => {
        srv = server([IMG1, IMG2], 'v2') // published update lands between manifest and bundle
        return undefined
      },
    ]

    await downloadPackage(PKG, { store: db })

    expect(urls().slice(0, 3)).toEqual(['GET /manifest', 'GET /bundle', 'GET /manifest'])
    expect((await db.bundles.get(PKG))!.etag).toBe('"b-v2"')
  })

  it('queues the server record when it cannot be sent, and still counts as downloaded', async () => {
    overrides = [() => undefined, () => undefined, () => undefined, () => undefined]
    transport.fetch = ((inner) => async (url: string, init: RequestInit) => {
      if (init.method === 'PUT') throw new TypeError('Failed to fetch')
      return inner(url, init)
    })(transport.fetch)

    const result = await downloadPackage(PKG, { store: db })

    expect(result.recorded).toBe(false)
    expect((await db.packages.get(PKG))!.downloadStatus).toBe('downloaded')
    const queued = await db.outbox.toArray()
    expect(queued).toHaveLength(1)
    expect(queued[0]).toMatchObject({
      type: 'download.record',
      payload: { packageId: PKG, status: 'completed', sizeBytes: result.totalBytes },
    })
  })

  it('does not queue the record when the package was unpublished meanwhile', async () => {
    transport.fetch = ((inner) => async (url: string, init: RequestInit) => {
      if (init.method === 'PUT') return Response.json({ error: { code: 'NOT_FOUND' } }, { status: 404 })
      return inner(url, init)
    })(transport.fetch)

    const result = await downloadPackage(PKG, { store: db })

    expect(result.recorded).toBe(false)
    expect(await db.outbox.count()).toBe(0)
  })

  it('joins a download already running for the same package', async () => {
    const a = downloadPackage(PKG, { store: db })
    const b = downloadPackage(PKG, { store: db })
    expect(b).toBe(a)
    await a
    expect(calls.filter((c) => c.url.endsWith('/manifest'))).toHaveLength(1)
  })

  it('fails fast when offline', async () => {
    transport.isOnline = () => false
    const error = await downloadPackage(PKG, { store: db }).catch((e) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error.kind).toBe('offline')
    expect(calls).toHaveLength(0)
  })

  it('stops when cancelled', async () => {
    const controller = new AbortController()
    overrides = [
      () => undefined,
      () => {
        controller.abort()
        return undefined
      },
    ]
    await expect(downloadPackage(PKG, { store: db, signal: controller.signal })).rejects.toMatchObject({
      kind: 'aborted',
    })
  })
})

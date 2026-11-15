import { ApiError, apiRequest, parseRetryAfter, transport } from './api/client'
import type { PackageBundle } from './api/packages'
import { db as appDb, type BridgeAccessDb, type LocalImage, type LocalPackage } from './db'
import { getDeviceId, randomUuid } from './deviceId'
import { enqueue } from './outbox'

/**
 * Downloading a package for offline use (FR-4.3, FR-4.4, API doc 11.1).
 *
 * 1. GET /packages/{id}/manifest lists every part with its size and ETag.
 * 2. Parts already stored with the same ETag are skipped (a retried or updated download
 *    fetches only what is missing or changed).
 * 3. Each remaining part is streamed. An image interrupted midway resumes with `Range` from
 *    the bytes already received; a connection that stalls is aborted and retried.
 * 4. Each image is stored as soon as it is complete. The bundle is stored, and the package
 *    marked downloaded, in one final transaction, so a package never counts as downloaded
 *    with parts missing.
 * 5. PUT /me/downloads/{id} records it on the server; if that cannot be sent now it is queued
 *    in the outbox for POST /sync. The download itself has succeeded either way.
 */

export type ManifestPart = {
  kind: 'bundle' | 'image'
  /** Package id for the bundle, image id for an image. */
  id: string
  /** Absolute path including /api/v1. */
  url: string
  mimeType: string
  /** Uncompressed size; the bundle is gzipped in transit. */
  sizeBytes: number
  etag: string
}

export type PackageManifest = {
  packageId: string
  version: string
  totalBytes: number
  parts: ManifestPart[]
}

export type DownloadProgress = {
  /** Bytes stored or received so far, counting parts that were already current. */
  receivedBytes: number
  totalBytes: number
}

export type DownloadOptions = {
  signal?: AbortSignal
  onProgress?: (progress: DownloadProgress) => void
  /** For tests. */
  store?: BridgeAccessDb
}

export type DownloadResult = {
  packageId: string
  totalBytes: number
  /** Bytes actually fetched this time (0 when everything was already current). */
  fetchedBytes: number
  /** False when the server record was queued in the outbox instead of sent. */
  recorded: boolean
}

/** A connection that delivers no bytes for this long is aborted and retried. */
export const STALL_TIMEOUT_MS = 20_000
/** Attempts per part that make no progress before giving up. Attempts that do progress don't count. */
export const MAX_IDLE_ATTEMPTS = 5
const BASE_DELAY_MS = 500
const MAX_DELAY_MS = 8_000

const OFFLINE_MESSAGE = "You're offline. Connect to the internet and try again."
const FAILED_MESSAGE = "The download didn't finish. Please try again."

/** A part's ETag differs from the manifest: the package changed mid-download. */
class PartChanged extends Error {}

export function getManifest(packageId: string, signal?: AbortSignal) {
  return apiRequest<PackageManifest>(`/packages/${encodeURIComponent(packageId)}/manifest`, { signal })
}

const inFlight = new Map<string, Promise<DownloadResult>>()

/**
 * Downloads a package, or joins the download already running for it (a second tap must not
 * start a second copy). Rejects with ApiError; an interrupted download can simply be
 * started again and continues from what was stored.
 */
export function downloadPackage(packageId: string, options: DownloadOptions = {}): Promise<DownloadResult> {
  const running = inFlight.get(packageId)
  if (running) return running
  const promise = run(packageId, options).finally(() => inFlight.delete(packageId))
  inFlight.set(packageId, promise)
  return promise
}

async function run(packageId: string, options: DownloadOptions): Promise<DownloadResult> {
  // If the package changes between the manifest and a part, start again from a fresh manifest, once.
  try {
    return await attempt(packageId, options)
  } catch (e) {
    if (!(e instanceof PartChanged)) throw e
  }
  try {
    return await attempt(packageId, options)
  } catch (e) {
    if (e instanceof PartChanged) {
      throw new ApiError({ kind: 'http', status: 409, message: FAILED_MESSAGE, requestId: randomUuid() })
    }
    throw e
  }
}

async function attempt(packageId: string, options: DownloadOptions): Promise<DownloadResult> {
  const store = options.store ?? appDb
  const { signal } = options
  // A download always needs the network (nothing of it is served from a cache): fail at once.
  if (!transport.isOnline()) throw new ApiError({ kind: 'offline', message: OFFLINE_MESSAGE, requestId: randomUuid() })
  const manifest = await getManifest(packageId, signal)

  const bundlePart = manifest.parts.find((p) => p.kind === 'bundle')
  if (!bundlePart) throw new ApiError({ kind: 'http', message: FAILED_MESSAGE, requestId: randomUuid() })
  const imageParts = manifest.parts.filter((p) => p.kind === 'image')

  const [storedBundle, storedImages] = await Promise.all([
    store.bundles.get(packageId),
    store.images.where('packageId').equals(packageId).toArray(),
  ])
  const storedImageEtags = new Map(storedImages.map((i) => [i.id, i.etag]))
  const bundleCurrent = storedBundle?.etag !== undefined && sameEtag(storedBundle.etag, bundlePart.etag)
  const missingImages = imageParts.filter((p) => !sameEtag(storedImageEtags.get(p.id), p.etag))

  let received = manifest.parts
    .filter((p) => (p === bundlePart ? bundleCurrent : !missingImages.includes(p)))
    .reduce((sum, p) => sum + p.sizeBytes, 0)
  let fetched = 0
  const report = (delta: number) => {
    received += delta
    fetched += delta
    options.onProgress?.({ receivedBytes: received, totalBytes: manifest.totalBytes })
  }
  options.onProgress?.({ receivedBytes: received, totalBytes: manifest.totalBytes })

  // Bundle first: it is small, and it carries the image metadata (alt text, position).
  let bundle: PackageBundle
  if (bundleCurrent) {
    bundle = storedBundle!.bundle
  } else {
    const blob = await fetchPart(bundlePart, { signal, resumable: false, onBytes: report })
    try {
      bundle = JSON.parse(await blob.text()) as PackageBundle
    } catch {
      throw new ApiError({ kind: 'http', message: FAILED_MESSAGE, requestId: randomUuid() })
    }
  }

  // Visible as "downloading" while images arrive. An update of a downloaded package keeps
  // its old copy usable, so it stays "downloaded" until the new one is complete.
  const meta = bundle.package
  // Re-read inside the transaction: the row may have changed since the download started
  // (a bookmark added meanwhile must not be overwritten).
  await store.transaction('rw', store.packages, async () => {
    const current = await store.packages.get(packageId)
    const row: LocalPackage = {
      ...current,
      id: packageId,
      title: meta.title,
      subject: meta.subject,
      resourceType: meta.resourceType,
      packageType: meta.packageType,
      sizeBytes: meta.sizeBytes,
      publishedAt: meta.publishedAt,
      downloadStatus: current?.downloadStatus === 'downloaded' ? 'downloaded' : 'downloading',
    }
    await store.packages.put(row)
  })

  const imageMeta = new Map(bundle.images.map((i) => [i.id, i]))
  for (const part of missingImages) {
    const blob = await fetchPart(part, { signal, resumable: true, onBytes: report })
    const info = imageMeta.get(part.id)
    const image: LocalImage = {
      id: part.id,
      packageId,
      position: info?.position ?? 0,
      source: info?.source ?? 'contributor',
      mimeType: part.mimeType,
      sizeBytes: part.sizeBytes,
      altText: info?.altText ?? null,
      etag: part.etag,
      blob,
    }
    await store.images.put(image)
  }

  const downloadedAt = new Date().toISOString()
  const keep = new Set(imageParts.map((p) => p.id))
  await store.transaction('rw', [store.packages, store.bundles, store.images], async () => {
    await store.images
      .where('packageId')
      .equals(packageId)
      .filter((i) => !keep.has(i.id))
      .delete()
    await store.bundles.put({ packageId, bundle, etag: bundlePart.etag, storedAt: downloadedAt })
    await store.packages.update(packageId, { downloadStatus: 'downloaded', downloadedAt })
  })

  const recorded = await record(packageId, manifest.totalBytes, downloadedAt, store)
  return { packageId, totalBytes: manifest.totalBytes, fetchedBytes: fetched, recorded }
}

/**
 * Tells the server. Sent now when possible, otherwise queued; never fails the download.
 * A 404 means the package was unpublished meanwhile: the local copy stays (API doc 12, #10)
 * and there is nothing to record.
 */
async function record(packageId: string, sizeBytes: number, downloadedAt: string, store: BridgeAccessDb) {
  const payload = { status: 'completed' as const, sizeBytes, downloadedAt }
  try {
    await apiRequest(`/me/downloads/${encodeURIComponent(packageId)}`, { method: 'PUT', body: payload })
    return true
  } catch (e) {
    if (e instanceof ApiError && isTransient(e)) {
      await enqueue({ type: 'download.record', payload: { packageId, ...payload } }, store)
    }
    return false
  }
}

function isTransient(e: ApiError) {
  return (
    e.kind === 'offline' ||
    e.kind === 'network' ||
    e.kind === 'timeout' ||
    (e.kind === 'http' && (e.status === 408 || e.status === 429 || (e.status ?? 0) >= 500))
  )
}

type FetchPartOptions = {
  signal?: AbortSignal
  /** Resume with Range after an interruption (images). The bundle is small and restarts. */
  resumable: boolean
  /** Called with each chunk's size, and with a negative size when received bytes are discarded. */
  onBytes: (delta: number) => void
}

/**
 * Streams one part, retrying until all `sizeBytes` have arrived. Bytes received before an
 * interruption are kept and the rest is requested with `Range` (206); a server that answers
 * 200 instead, or a range that doesn't start where we stopped, means starting the part over.
 * Checks the part's ETag against the manifest, so bytes of two versions are never mixed.
 */
export async function fetchPart(part: ManifestPart, options: FetchPartOptions): Promise<Blob> {
  const requestId = randomUuid()
  let chunks: Uint8Array<ArrayBuffer>[] = []
  let received = 0
  let idle = 0
  const discard = () => {
    if (received > 0) options.onBytes(-received)
    chunks = []
    received = 0
  }

  for (;;) {
    if (options.signal?.aborted) throw aborted(requestId)
    if (!transport.isOnline()) throw new ApiError({ kind: 'offline', message: OFFLINE_MESSAGE, requestId })

    const before = received
    let failure: ApiError | undefined
    let retryAfterMs: number | undefined
    try {
      const headers: Record<string, string> = {
        Accept: part.mimeType,
        'X-Device-Id': getDeviceId(),
        'X-Request-Id': requestId,
      }
      if (options.resumable && received > 0) headers.Range = `bytes=${received}-`

      const stream = await openStream(part.url, headers, options.signal, requestId)
      try {
        const res = stream.response
        const etag = res.headers.get('ETag')
        if (etag && !sameEtag(etag, part.etag)) throw new PartChanged()

        if (res.status === 416 && received > 0) {
          // Asked from past the end: either everything had arrived, or our bytes are wrong.
          if (received === part.sizeBytes) break
          discard()
          continue
        }
        if (!res.ok) {
          failure = httpFailure(res.status, requestId)
          retryAfterMs = parseRetryAfter(res.headers.get('Retry-After'))
        } else {
          if (res.status === 206) {
            const start = Number(/^bytes (\d+)-/.exec(res.headers.get('Content-Range') ?? '')?.[1])
            if (start !== received) {
              discard()
              continue
            }
          } else if (received > 0) {
            discard() // 200: the whole part again
          }
          await stream.read((chunk) => {
            chunks.push(chunk)
            received += chunk.byteLength
            options.onBytes(chunk.byteLength)
          })
          if (received === part.sizeBytes) break
          if (received > part.sizeBytes) {
            discard()
            failure = new ApiError({ kind: 'http', status: res.status, message: FAILED_MESSAGE, requestId })
          } else {
            failure = new ApiError({ kind: 'network', message: FAILED_MESSAGE, requestId }) // ended early
          }
        }
      } finally {
        stream.close()
      }
    } catch (e) {
      if (e instanceof PartChanged) throw e
      if (!(e instanceof ApiError)) throw e
      if (e.kind === 'aborted' || e.kind === 'offline') throw e
      failure = e
    }

    if (failure) {
      const retryable =
        failure.kind === 'network' ||
        failure.kind === 'timeout' ||
        (failure.kind === 'http' && [408, 429, 500, 502, 503, 504].includes(failure.status ?? 0))
      if (!retryable) throw failure
      idle = received > before ? 0 : idle + 1
      if (idle >= MAX_IDLE_ATTEMPTS) throw failure
      const backoff = transport.random() * Math.min(MAX_DELAY_MS, BASE_DELAY_MS * 2 ** Math.max(0, idle - 1))
      try {
        await transport.sleep(Math.min(MAX_DELAY_MS, Math.max(backoff, retryAfterMs ?? 0)), options.signal)
      } catch {
        throw aborted(requestId)
      }
    }
  }
  return new Blob(chunks, { type: part.mimeType })
}

type OpenStream = {
  response: Response
  /** Reads the body to the end; rejects with a timeout if no bytes arrive for STALL_TIMEOUT_MS. */
  read: (onChunk: (chunk: Uint8Array<ArrayBuffer>) => void) => Promise<void>
  close: () => void
}

/** One request whose stall timer covers the wait for headers and every gap between chunks. */
async function openStream(
  url: string,
  headers: Record<string, string>,
  signal: AbortSignal | undefined,
  requestId: string,
): Promise<OpenStream> {
  const controller = new AbortController()
  let stalled = false
  let timer: ReturnType<typeof setTimeout> | undefined
  const arm = () => {
    clearTimeout(timer)
    timer = setTimeout(() => {
      stalled = true
      controller.abort()
    }, STALL_TIMEOUT_MS)
  }
  const onAbort = () => controller.abort()
  signal?.addEventListener('abort', onAbort, { once: true })
  const close = () => {
    clearTimeout(timer)
    signal?.removeEventListener('abort', onAbort)
    controller.abort() // releases the connection if the body was not read
  }
  const failure = () => {
    if (signal?.aborted) return aborted(requestId)
    if (stalled) return new ApiError({ kind: 'timeout', message: 'The connection is slow. Please try again.', requestId })
    return transport.isOnline()
      ? new ApiError({ kind: 'network', message: FAILED_MESSAGE, requestId })
      : new ApiError({ kind: 'offline', message: OFFLINE_MESSAGE, requestId })
  }

  arm()
  let response: Response
  try {
    response = await transport.fetch(url, { method: 'GET', headers, signal: controller.signal })
  } catch {
    const error = failure()
    close()
    throw error
  }

  return {
    response,
    close,
    read: async (onChunk) => {
      if (!response.body) return
      const reader = response.body.getReader()
      try {
        for (;;) {
          arm()
          const { done, value } = await reader.read()
          if (done) return
          if (value?.byteLength) onChunk(value as Uint8Array<ArrayBuffer>)
        }
      } catch {
        throw failure()
      }
    },
  }
}

/** ETags compared without a weak prefix (a proxy may add one when it re-compresses). */
function sameEtag(a: string | undefined | null, b: string) {
  if (!a) return false
  const strip = (s: string) => s.replace(/^W\//, '')
  return strip(a) === strip(b)
}

function httpFailure(status: number, requestId: string) {
  const message = status === 404 ? 'This package is no longer available.' : FAILED_MESSAGE
  return new ApiError({ kind: 'http', status, code: status === 404 ? 'NOT_FOUND' : undefined, message, requestId })
}

function aborted(requestId: string) {
  return new ApiError({ kind: 'aborted', message: 'Download cancelled.', requestId })
}

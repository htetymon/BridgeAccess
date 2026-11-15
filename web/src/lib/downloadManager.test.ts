import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, transport } from './api/client'
import type { DownloadOptions, DownloadResult } from './download'

const downloadPackage = vi.fn<(id: string, options: DownloadOptions) => Promise<DownloadResult>>()
vi.mock('./download', () => ({ downloadPackage: (id: string, o: DownloadOptions) => downloadPackage(id, o) }))

const {
  LARGE_DOWNLOAD_BYTES,
  MAX_AUTO_RETRIES,
  cancelDownload,
  dismissFailure,
  getJob,
  needsConfirmation,
  resetDownloadsForTests,
  startDownload,
  subscribe,
} = await import('./downloadManager')

const PKG = '0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10'
const original = { ...transport }
const ok: DownloadResult = { packageId: PKG, totalBytes: 1000, fetchedBytes: 1000, recorded: true }
const err = (init: Partial<ConstructorParameters<typeof ApiError>[0]>) =>
  new ApiError({ kind: 'network', message: 'x', requestId: 'r', ...init })

let sleeps: number[]
let online: boolean

/** Resolves once the job reaches a state matching `predicate` (or is gone, for undefined). */
function until(predicate: (s: ReturnType<typeof getJob>) => boolean) {
  return new Promise<void>((resolve) => {
    if (predicate(getJob(PKG))) return resolve()
    const off = subscribe(() => {
      if (predicate(getJob(PKG))) {
        off()
        resolve()
      }
    })
  })
}

beforeEach(() => {
  sleeps = []
  online = true
  downloadPackage.mockReset()
  transport.sleep = async (ms) => void sleeps.push(ms)
  transport.isOnline = () => online
  transport.random = () => 1
  vi.stubGlobal('window', new EventTarget())
})

afterEach(() => {
  resetDownloadsForTests()
  Object.assign(transport, original)
  vi.unstubAllGlobals()
})

describe('needsConfirmation', () => {
  it('asks above 1 MiB, not at or below it', () => {
    vi.stubGlobal('navigator', {})
    expect(needsConfirmation(LARGE_DOWNLOAD_BYTES)).toBe(false)
    expect(needsConfirmation(LARGE_DOWNLOAD_BYTES + 1)).toBe(true)
  })

  it('always asks while Data Saver is on', () => {
    vi.stubGlobal('navigator', { connection: { saveData: true } })
    expect(needsConfirmation(10_000)).toBe(true)
  })
})

describe('startDownload', () => {
  it('shows progress, then clears the job once the package is stored', async () => {
    let finish!: () => void
    downloadPackage.mockImplementation((_id, o) => {
      o.onProgress?.({ receivedBytes: 0, totalBytes: 1000 })
      o.onProgress?.({ receivedBytes: 450, totalBytes: 1000 })
      return new Promise((resolve) => (finish = () => resolve(ok)))
    })

    startDownload(PKG)
    expect(getJob(PKG)).toEqual({ status: 'downloading', receivedBytes: 450, totalBytes: 1000 })
    finish()
    await until((s) => s === undefined)
  })

  it('ignores a second start while one is running', () => {
    downloadPackage.mockReturnValue(new Promise(() => {}))
    startDownload(PKG)
    startDownload(PKG)
    expect(downloadPackage).toHaveBeenCalledTimes(1)
  })

  it('retries a transient failure with growing backoff, resuming each time', async () => {
    downloadPackage
      .mockRejectedValueOnce(err({ kind: 'network' }))
      .mockRejectedValueOnce(err({ kind: 'http', status: 503 }))
      .mockResolvedValueOnce(ok)

    startDownload(PKG)
    await until((s) => s === undefined)

    expect(downloadPackage).toHaveBeenCalledTimes(3)
    expect(sleeps).toEqual([2000, 4000])
  })

  it('gives up after the last automatic retry and says so', async () => {
    downloadPackage.mockRejectedValue(err({ kind: 'timeout' }))

    startDownload(PKG)
    await until((s) => s?.status === 'failed')

    expect(downloadPackage).toHaveBeenCalledTimes(1 + MAX_AUTO_RETRIES)
    expect(getJob(PKG)).toEqual({ status: 'failed', message: "The download didn't finish. Try again." })
  })

  it('does not retry a package that is gone', async () => {
    downloadPackage.mockRejectedValue(err({ kind: 'http', status: 404 }))

    startDownload(PKG)
    await until((s) => s?.status === 'failed')

    expect(downloadPackage).toHaveBeenCalledTimes(1)
    expect(getJob(PKG)).toEqual({ status: 'failed', message: 'This package is no longer available.' })
  })

  it('waits for the connection while offline, without using up retries, then resumes', async () => {
    downloadPackage.mockImplementationOnce(async (_id, o) => {
      o.onProgress?.({ receivedBytes: 300, totalBytes: 1000 })
      online = false
      throw err({ kind: 'offline' })
    })
    downloadPackage.mockResolvedValueOnce(ok)

    startDownload(PKG)
    await until((s) => s?.status === 'retrying')
    expect(getJob(PKG)).toEqual({ status: 'retrying', receivedBytes: 300, totalBytes: 1000, offline: true })
    expect(downloadPackage).toHaveBeenCalledTimes(1)

    online = true
    window.dispatchEvent(new Event('online'))
    await until((s) => s === undefined)
    expect(downloadPackage).toHaveBeenCalledTimes(2)
    expect(sleeps).toEqual([])
  })

  it('a failed download can be started again', async () => {
    downloadPackage.mockRejectedValueOnce(err({ kind: 'http', status: 400 })).mockResolvedValueOnce(ok)

    startDownload(PKG)
    await until((s) => s?.status === 'failed')
    startDownload(PKG)
    await until((s) => s === undefined)
    expect(downloadPackage).toHaveBeenCalledTimes(2)
  })
})

describe('cancelDownload', () => {
  it('aborts the running download and forgets it', async () => {
    let signal!: AbortSignal
    downloadPackage.mockImplementation((_id, o) => {
      signal = o.signal!
      return new Promise((_, reject) =>
        o.signal!.addEventListener('abort', () => reject(err({ kind: 'aborted' }))),
      )
    })

    startDownload(PKG)
    cancelDownload(PKG)

    expect(signal.aborted).toBe(true)
    expect(getJob(PKG)).toBeUndefined()
    await Promise.resolve()
    expect(downloadPackage).toHaveBeenCalledTimes(1) // no retry after a cancel
  })

  it('dismissFailure clears only a failed job', async () => {
    downloadPackage.mockRejectedValue(err({ kind: 'http', status: 404 }))
    startDownload(PKG)
    await until((s) => s?.status === 'failed')
    dismissFailure(PKG)
    expect(getJob(PKG)).toBeUndefined()
  })
})

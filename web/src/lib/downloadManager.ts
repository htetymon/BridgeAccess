import { useEffect, useState, useSyncExternalStore } from 'react'
import { liveQuery } from 'dexie'
import { ApiError, transport } from './api/client'
import { db as appDb, type BridgeAccessDb } from './db'
import { downloadPackage } from './download'

/**
 * Download manager (FR-4.3, FR-4.4, FR-4.6): starts package downloads, keeps their progress
 * where any screen can show it, and retries on its own.
 *
 * The part-level work (manifest, skipping parts already stored, Range resume, the final
 * transaction that marks the package downloaded) is in download.ts. This module adds:
 * - confirmation rules: large packages and Data Saver ask first (needsConfirmation);
 * - automatic retry of the whole download with backoff. When offline it waits for the
 *   connection instead of counting attempts. Each retry resumes, so finished parts are not
 *   fetched again;
 * - a store with one entry per running or failed download, so progress survives leaving
 *   and re-entering a screen (downloads keep running in the background).
 */

/** From GET /config (`largeDownloadBytes`, 1 MiB). A constant until that endpoint exists. */
export const LARGE_DOWNLOAD_BYTES = 1_048_576

/** Automatic retries after the first attempt (time spent waiting to be online is not counted). */
export const MAX_AUTO_RETRIES = 4
const RETRY_BASE_MS = 2_000
const RETRY_MAX_MS = 30_000

export type JobState =
  | { status: 'downloading'; receivedBytes: number; totalBytes: number }
  /** Between attempts: offline (waiting for the connection) or backing off. */
  | { status: 'retrying'; receivedBytes: number; totalBytes: number; offline: boolean }
  | { status: 'failed'; message: string }

/** What a screen shows for one package. */
export type DownloadState =
  | { status: 'none' }
  /** Started earlier and interrupted (app closed): its stored parts are kept, Resume continues. */
  | { status: 'paused' }
  | { status: 'downloaded' }
  | JobState

/** Browser Data Saver ("Lite mode"): the Network Information API, where the browser has it. */
export function dataSaverOn(): boolean {
  const connection = (navigator as Navigator & { connection?: { saveData?: boolean } }).connection
  return connection?.saveData === true
}

/** Ask before a download over 1 MB, or any download while Data Saver is on (FR-4.6). */
export function needsConfirmation(sizeBytes: number): boolean {
  return sizeBytes > LARGE_DOWNLOAD_BYTES || dataSaverOn()
}

// ------------------------------------------------------------------ store

type Job = { state: JobState; controller: AbortController }

const jobs = new Map<string, Job>()
const listeners = new Set<() => void>()

function emit() {
  for (const listener of listeners) listener()
}

function set(packageId: string, state: JobState) {
  const job = jobs.get(packageId)
  if (job) {
    job.state = state
    emit()
  }
}

export function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function getJob(packageId: string): JobState | undefined {
  return jobs.get(packageId)?.state
}

// ------------------------------------------------------------------ actions

export type StartOptions = {
  /** For tests. */
  store?: BridgeAccessDb
}

/**
 * Starts (or resumes) a download in the background. Confirmation is the caller's job, before
 * calling this. Calling it while the package is already downloading does nothing.
 */
export function startDownload(packageId: string, options: StartOptions = {}): void {
  const running = jobs.get(packageId)
  if (running && running.state.status !== 'failed') return
  const controller = new AbortController()
  jobs.set(packageId, { controller, state: { status: 'downloading', receivedBytes: 0, totalBytes: 0 } })
  emit()
  void run(packageId, controller.signal, options)
}

/** Stops a running download. Parts already stored are kept, so it can be resumed later. */
export function cancelDownload(packageId: string): void {
  const job = jobs.get(packageId)
  if (!job) return
  job.controller.abort()
  jobs.delete(packageId)
  emit()
}

/** Forgets a failed download's message (after the learner has seen it). */
export function dismissFailure(packageId: string): void {
  if (jobs.get(packageId)?.state.status === 'failed') {
    jobs.delete(packageId)
    emit()
  }
}

async function run(packageId: string, signal: AbortSignal, options: StartOptions) {
  let progress = { receivedBytes: 0, totalBytes: 0 }
  let retries = 0
  for (;;) {
    try {
      await downloadPackage(packageId, {
        signal,
        store: options.store,
        onProgress: (p) => {
          // Re-render on whole-percent steps only: chunks arrive far more often than that.
          const changed =
            p.totalBytes !== progress.totalBytes ||
            Math.floor((p.receivedBytes * 100) / (p.totalBytes || 1)) !==
              Math.floor((progress.receivedBytes * 100) / (progress.totalBytes || 1))
          progress = p
          if (changed && !signal.aborted) set(packageId, { status: 'downloading', ...p })
        },
      })
      if (signal.aborted) return
      // Done: the stored row now says "downloaded", which screens read from Dexie.
      jobs.delete(packageId)
      emit()
      return
    } catch (e) {
      if (signal.aborted || (e instanceof ApiError && e.kind === 'aborted')) return
      const error = e instanceof ApiError ? e : undefined
      // Wait for the connection only while the browser says offline; otherwise an "offline"
      // error would resolve the wait at once and loop. It then falls through to backoff.
      if (!transport.isOnline()) {
        set(packageId, { status: 'retrying', ...progress, offline: true })
        if (!(await waitForOnline(signal))) return
      } else if (error && isTransient(error) && retries < MAX_AUTO_RETRIES) {
        retries++
        set(packageId, { status: 'retrying', ...progress, offline: false })
        const backoff = Math.min(RETRY_MAX_MS, RETRY_BASE_MS * 2 ** (retries - 1))
        try {
          await transport.sleep(backoff / 2 + transport.random() * (backoff / 2), signal)
        } catch {
          return // cancelled while waiting
        }
      } else {
        set(packageId, { status: 'failed', message: failureMessage(error) })
        return
      }
      set(packageId, { status: 'downloading', ...progress })
    }
  }
}

function isTransient(e: ApiError) {
  return (
    e.kind === 'offline' ||
    e.kind === 'network' ||
    e.kind === 'timeout' ||
    (e.kind === 'http' && (e.status === 408 || e.status === 409 || e.status === 429 || (e.status ?? 0) >= 500))
  )
}

function failureMessage(e: ApiError | undefined) {
  if (e?.status === 404) return 'This package is no longer available.'
  return "The download didn't finish. Try again."
}

/** Resolves true when the browser is back online, false if cancelled first. */
function waitForOnline(signal: AbortSignal): Promise<boolean> {
  if (transport.isOnline()) return Promise.resolve(true)
  return new Promise((resolve) => {
    const done = (online: boolean) => {
      window.removeEventListener('online', onOnline)
      signal.removeEventListener('abort', onAbort)
      resolve(online)
    }
    const onOnline = () => done(true)
    const onAbort = () => done(false)
    window.addEventListener('online', onOnline)
    signal.addEventListener('abort', onAbort, { once: true })
  })
}

// ------------------------------------------------------------------ hook

/**
 * The download state of one package: a running or failed job if there is one, otherwise what
 * the device has stored (downloaded, interrupted, or nothing).
 */
export function useDownload(packageId: string, store: BridgeAccessDb = appDb): DownloadState {
  const job = useSyncExternalStore(subscribe, () => getJob(packageId))
  const [stored, setStored] = useState<DownloadState>({ status: 'none' })

  useEffect(() => {
    const subscription = liveQuery(() => store.packages.get(packageId)).subscribe({
      next: (row) =>
        setStored(
          row?.downloadStatus === 'downloaded'
            ? { status: 'downloaded' }
            : row?.downloadStatus === 'downloading'
              ? { status: 'paused' }
              : { status: 'none' },
        ),
      error: () => setStored({ status: 'none' }),
    })
    return () => subscription.unsubscribe()
  }, [packageId, store])

  // An update of a package already on the device shows its progress, not "Downloaded".
  return job ?? stored
}

/** For tests: forget every job. */
export function resetDownloadsForTests() {
  for (const job of jobs.values()) job.controller.abort()
  jobs.clear()
  emit()
}

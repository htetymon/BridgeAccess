import { useSyncExternalStore } from 'react'
import { ApiError, apiRequest, transport } from './api/client'
import { db as appDb, META_DEVICE_ID, type BridgeAccessDb, type OutboxEntry } from './db'
import { getDeviceId } from './deviceId'

/**
 * Sends the outbox to POST /sync (FR-8.2, API doc section 9).
 *
 * - Oldest first, at most 50 actions per request, until the outbox is empty.
 * - `ok` and `rejected` results leave the outbox (rejected is permanent; the local data
 *   stays). Anything without a result stays and goes with the next sync.
 * - A failed request (offline, timeout, 5xx) keeps everything and retries later with backoff.
 *   Resending is safe: every action is idempotent on the server.
 * - Runs on app start, on the `online` event, when the app returns to the foreground, a
 *   moment after something is queued, and through Background Sync where the browser has it
 *   (public/sw-sync.js does the same work from the service worker, even with the app closed).
 */

export const SYNC_TAG = 'bridgeaccess-outbox'
export const SYNC_BATCH = 50
/** Wait this long after something is queued, so a burst of taps goes in one request. */
export const SYNC_DEBOUNCE_MS = 1_500
const RETRY_BASE_MS = 5_000
const RETRY_MAX_MS = 5 * 60_000

export type SyncStatus = {
  state: 'idle' | 'syncing' | 'error'
  lastError?: string
  lastSyncedAt?: string
  /** Bumped when another context (the service worker) changed the outbox, to refresh views. */
  version: number
}

type SyncResponse = { results: { opId: string; status: 'ok' | 'rejected'; error?: { code: string; message: string } }[] }

// ------------------------------------------------------------------ status store

let status: SyncStatus = { state: 'idle', version: 0 }
const listeners = new Set<() => void>()

function setStatus(next: Partial<SyncStatus>) {
  status = { ...status, ...next }
  for (const l of listeners) l()
}

export function getSyncStatus() {
  return status
}

export function useSyncStatus(): SyncStatus {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l)
      return () => listeners.delete(l)
    },
    () => status,
  )
}

// ------------------------------------------------------------------ sending

let running: Promise<void> | null = null
let retryTimer: ReturnType<typeof setTimeout> | undefined
let debounceTimer: ReturnType<typeof setTimeout> | undefined
let failures = 0

/** Sends everything queued now. Calls while a sync is running join it. Never throws. */
export function syncNow(store: BridgeAccessDb = appDb): Promise<void> {
  running ??= run(store).finally(() => {
    running = null
  })
  return running
}

async function run(store: BridgeAccessDb) {
  clearTimeout(retryTimer)
  if (!transport.isOnline()) return // the online event starts the next one
  if ((await store.outbox.count()) === 0) {
    setStatus({ state: 'idle', lastError: undefined })
    return
  }
  setStatus({ state: 'syncing' })
  for (;;) {
    const batch = await store.outbox.orderBy('seq').limit(SYNC_BATCH).toArray()
    if (batch.length === 0) break

    let response: SyncResponse
    try {
      response = await apiRequest<SyncResponse>('/sync', {
        method: 'POST',
        body: { ops: batch.map(toOp) },
        // Safe to repeat as a whole (API doc section 9), so a POST may retry here.
        retry: { attempts: 2 },
        timeoutMs: 30_000,
      })
    } catch (e) {
      const message = e instanceof ApiError ? e.message : 'Sync failed.'
      await store.outbox
        .where('opId')
        .anyOf(batch.map((b) => b.opId))
        .modify((row) => {
          row.attempts += 1
          row.lastError = message
        })
      failed(store, message)
      return
    }

    const done = response.results.filter((r) => r.status === 'ok' || r.status === 'rejected').map((r) => r.opId)
    for (const r of response.results) {
      if (r.status === 'rejected') console.warn(`Sync action ${r.opId} rejected: ${r.error?.code}`)
    }
    await store.outbox.where('opId').anyOf(done).delete()
    if (done.length === 0) {
      // A reply that settles nothing: stop instead of resending the same batch forever.
      failed(store, 'The server did not accept the changes.')
      return
    }
  }
  failures = 0
  setStatus({ state: 'idle', lastError: undefined, lastSyncedAt: new Date().toISOString() })
}

function failed(store: BridgeAccessDb, message: string) {
  failures += 1
  setStatus({ state: 'error', lastError: message })
  const wait = Math.min(RETRY_MAX_MS, RETRY_BASE_MS * 2 ** (failures - 1))
  retryTimer = setTimeout(() => void syncNow(store), wait)
}

function toOp(entry: OutboxEntry) {
  return { opId: entry.opId, type: entry.type, clientTime: entry.clientTime, payload: entry.payload }
}

/**
 * Called after something is queued: sync shortly (one request for a burst of changes), and
 * ask for Background Sync so it is also sent if the app is closed before that.
 */
export function scheduleSync() {
  clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => void syncNow(), SYNC_DEBOUNCE_MS)
  void registerBackgroundSync()
}

async function registerBackgroundSync() {
  try {
    if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return
    const registration = (await navigator.serviceWorker.ready) as ServiceWorkerRegistration & {
      sync?: { register: (tag: string) => Promise<void> }
    }
    await registration.sync?.register(SYNC_TAG)
  } catch {
    // Not supported or not allowed: the in-page triggers still send it.
  }
}

// ------------------------------------------------------------------ start

/**
 * Wires the triggers once, at app start: sync now, then on `online`, on returning to the
 * foreground, and when the service worker reports that it synced (to refresh the views).
 */
export function startSync(store: BridgeAccessDb = appDb) {
  // The service worker has no localStorage: give it the device ID for Background Sync.
  void store.meta.put({ key: META_DEVICE_ID, value: getDeviceId() }).catch(() => {})

  window.addEventListener('online', () => void syncNow(store))
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') void syncNow(store)
  })
  navigator.serviceWorker?.addEventListener('message', (event: MessageEvent) => {
    if ((event.data as { type?: string } | null)?.type === 'outbox-synced') setStatus({ version: status.version + 1 })
  })
  void syncNow(store)
}

/** For tests. */
export function resetSyncForTests() {
  clearTimeout(retryTimer)
  clearTimeout(debounceTimer)
  running = null
  failures = 0
  status = { state: 'idle', version: 0 }
}

/*
 * Background Sync for the outbox (FR-8.2). Loaded into the generated service worker with
 * workbox `importScripts` (vite.config.ts). When the browser fires the sync event (the
 * connection is back, even if the app is closed), this sends the outbox to POST /sync exactly
 * like src/lib/sync.ts does in the page: oldest first, 50 at a time, removing `ok` and
 * `rejected` actions. Throwing makes the browser retry the sync later.
 *
 * Plain IndexedDB, no Dexie: the service worker has no bundler. Store and index names match
 * src/lib/db.ts (outbox: auto-increment key `seq`, unique index `opId`; meta: key `key`).
 * The device ID comes from meta, because a service worker has no localStorage.
 */

const SYNC_TAG = 'bridgeaccess-outbox'
const DB_NAME = 'bridgeaccess'
const BATCH = 50

self.addEventListener('sync', (event) => {
  if (event.tag === SYNC_TAG) event.waitUntil(syncOutbox())
})

async function syncOutbox() {
  const db = await openDb()
  try {
    if (!db.objectStoreNames.contains('outbox') || !db.objectStoreNames.contains('meta')) return
    const meta = await request(db.transaction('meta').objectStore('meta').get('deviceId'))
    if (!meta || !meta.value) return // the app has not started since the update: it will sync itself

    let changed = false
    for (;;) {
      const batch = await readBatch(db)
      if (batch.length === 0) break
      const response = await fetch('/api/v1/sync', {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          'X-Device-Id': meta.value,
          'X-Request-Id': crypto.randomUUID(),
        },
        body: JSON.stringify({
          ops: batch.map((e) => ({ opId: e.opId, type: e.type, clientTime: e.clientTime, payload: e.payload })),
        }),
      })
      if (!response.ok) throw new Error(`Sync failed with ${response.status}`)
      const { results } = await response.json()
      const done = results.filter((r) => r.status === 'ok' || r.status === 'rejected').map((r) => r.opId)
      if (done.length === 0) break // nothing settled: do not resend the same batch in a loop
      await deleteByOpId(db, done)
      changed = true
    }
    if (changed) {
      // Open pages refresh their "waiting to sync" count.
      const pages = await self.clients.matchAll({ type: 'window' })
      for (const page of pages) page.postMessage({ type: 'outbox-synced' })
    }
  } finally {
    db.close()
  }
}

function openDb() {
  // No version: open whatever version the app created.
  return request(indexedDB.open(DB_NAME))
}

function readBatch(db) {
  return new Promise((resolve, reject) => {
    const rows = []
    const cursor = db.transaction('outbox').objectStore('outbox').openCursor()
    cursor.onerror = () => reject(cursor.error)
    cursor.onsuccess = () => {
      const c = cursor.result
      if (c && rows.length < BATCH) {
        rows.push(c.value)
        c.continue()
      } else {
        resolve(rows)
      }
    }
  })
}

function deleteByOpId(db, opIds) {
  return new Promise((resolve, reject) => {
    const tx = db.transaction('outbox', 'readwrite')
    const index = tx.objectStore('outbox').index('opId')
    for (const opId of opIds) {
      const keyRequest = index.getKey(opId)
      keyRequest.onsuccess = () => {
        if (keyRequest.result !== undefined) tx.objectStore('outbox').delete(keyRequest.result)
      }
    }
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
    tx.onabort = () => reject(tx.error)
  })
}

function request(req) {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

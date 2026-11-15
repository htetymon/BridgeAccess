import { db as appDb, type BridgeAccessDb, type OutboxEntry, type SyncOperation } from './db'
import { randomUuid } from './deviceId'

/**
 * Adds an action to the outbox for a later POST /sync (FR-8.2). Some actions replace unsent
 * earlier ones for the same package, so the queue does not grow with every tap:
 * - download actions: only the latest state of a download matters;
 * - progress.upsert: callers send the full local progress each time (all completed steps),
 *   so the newest one includes everything the older ones said. Even if an older one is being
 *   sent at that moment, nothing is lost: the server merges, and the newer one is a superset.
 */
export async function enqueue(op: SyncOperation, store: BridgeAccessDb = appDb): Promise<void> {
  const entry = { ...op, opId: randomUuid(), clientTime: new Date().toISOString(), attempts: 0 } as Omit<
    OutboxEntry,
    'seq'
  >
  await store.transaction('rw', store.outbox, async () => {
    if (op.type === 'download.record' || op.type === 'download.remove') {
      const { packageId } = op.payload
      await store.outbox
        .where('type')
        .anyOf('download.record', 'download.remove')
        .filter((e) => e.payload.packageId === packageId)
        .delete()
    } else if (op.type === 'progress.upsert') {
      const { packageId } = op.payload
      await store.outbox
        .where('type')
        .equals('progress.upsert')
        .filter((e) => e.payload.packageId === packageId)
        .delete()
    }
    await store.outbox.add(entry as OutboxEntry)
  })
}

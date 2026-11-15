import { db as appDb, type BridgeAccessDb, type OutboxEntry, type SyncOperation } from './db'
import { randomUuid } from './deviceId'

/**
 * Adds an action to the outbox for a later POST /sync (FR-8.2). Download actions for a
 * package replace any unsent earlier ones for the same package: only the latest state of a
 * download matters, so the queue does not grow with retries.
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
    }
    await store.outbox.add(entry as OutboxEntry)
  })
}

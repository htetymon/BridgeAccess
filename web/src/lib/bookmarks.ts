import { db as appDb, type BridgeAccessDb, type LocalPackage } from './db'
import { enqueue } from './outbox'

/**
 * Bookmarks (FR-7.10), kept on the device so they work offline, then synced. The package's
 * card metadata is stored with the bookmark, so the Bookmarks tab can list a package that was
 * never downloaded without a connection.
 */

export type BookmarkMeta = Pick<
  LocalPackage,
  'id' | 'title' | 'subject' | 'resourceType' | 'packageType' | 'sizeBytes' | 'publishedAt'
>

/** Bookmarks or un-bookmarks; returns whether the package is bookmarked afterwards. */
export async function toggleBookmark(meta: BookmarkMeta, store: BridgeAccessDb = appDb): Promise<boolean> {
  const bookmarked = await store.transaction('rw', store.packages, async () => {
    const row = await store.packages.get(meta.id)
    if (row?.bookmarkedAt) {
      if (row.downloadStatus) {
        // Still on the device: keep the row, drop only the bookmark.
        await store.packages
          .where('id')
          .equals(meta.id)
          .modify((p) => {
            delete p.bookmarkedAt
          })
      } else {
        await store.packages.delete(meta.id)
      }
      return false
    }
    // Keep download fields of an existing row; refresh the card metadata.
    await store.packages.put({ ...row, ...meta, bookmarkedAt: new Date().toISOString() })
    return true
  })
  await enqueue({ type: bookmarked ? 'bookmark.add' : 'bookmark.remove', payload: { packageId: meta.id } }, store)
  return bookmarked
}

export type BookmarkItem = { pkg: LocalPackage; learnt: boolean }

/** Bookmarked packages, newest bookmark first, with their Learnt state. */
export async function listBookmarks(store: BridgeAccessDb = appDb): Promise<BookmarkItem[]> {
  const packages = await store.packages.orderBy('bookmarkedAt').reverse().toArray()
  const progress = await store.progress.bulkGet(packages.map((p) => p.id))
  return packages.map((pkg, i) => ({ pkg, learnt: Boolean(progress[i]?.learntAt) }))
}

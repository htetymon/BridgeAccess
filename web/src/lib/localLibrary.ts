import { ApiError, apiRequest } from './api/client'
import type { LearningStep, PackageType } from './api/packages'
import { db as appDb, type BridgeAccessDb, type LocalPackage, type LocalProgress } from './db'
import { cancelDownload } from './downloadManager'
import { enqueue } from './outbox'

/**
 * What is on this device, for Home and Learn (FR-2.2, FR-2.3, FR-2.6, FR-3.3). Everything
 * here reads or writes IndexedDB only, so it works with no connection; the server is told
 * about a delete afterwards, or later through the outbox.
 */

export type ContinueItem = {
  pkg: LocalPackage
  progress: LocalProgress
  /** Completed steps out of the package's steps, 0 to 100. */
  percent: number
}

export type DownloadItem = { pkg: LocalPackage; learnt: boolean }

/** Learning steps per package type (API doc section 3): with_visual adds the diagram. */
export function stepsFor(packageType: PackageType): LearningStep[] {
  return packageType === 'with_visual'
    ? ['summary', 'key_concepts', 'diagram', 'quiz']
    : ['summary', 'key_concepts', 'quiz']
}

export function progressPercent(packageType: PackageType, completed: readonly LearningStep[]): number {
  const steps = stepsFor(packageType)
  const done = steps.filter((s) => completed.includes(s)).length
  return Math.round((done * 100) / steps.length)
}

/**
 * In-progress packages, latest opened first: opened at least once, not yet Learnt, and still
 * downloaded (a deleted package keeps its progress, but cannot be opened offline).
 */
export async function continueLearning(limit: number, store: BridgeAccessDb = appDb): Promise<ContinueItem[]> {
  // One row per package the learner opened: small enough to read whole, newest first.
  const rows = await store.progress
    .orderBy('lastOpenedAt')
    .reverse()
    .filter((p) => !p.learntAt)
    .toArray()
  const packages = await store.packages.bulkGet(rows.map((p) => p.packageId))
  const items: ContinueItem[] = []
  for (let i = 0; i < rows.length && items.length < limit; i++) {
    const pkg = packages[i]
    if (pkg?.downloadStatus !== 'downloaded') continue
    items.push({ pkg, progress: rows[i], percent: progressPercent(pkg.packageType, rows[i].completedSteps) })
  }
  return items
}

/** Downloaded packages, latest downloaded first, with their Learnt state. */
export async function recentDownloads(limit: number, store: BridgeAccessDb = appDb): Promise<DownloadItem[]> {
  const packages = await store.packages
    .orderBy('downloadedAt')
    .reverse()
    .filter((p) => p.downloadStatus === 'downloaded')
    .limit(limit)
    .toArray()
  const progress = await store.progress.bulkGet(packages.map((p) => p.id))
  return packages.map((pkg, i) => ({ pkg, learnt: Boolean(progress[i]?.learntAt) }))
}

/**
 * Removes a package's files from the device (FR-3.3). Progress, quiz attempts and the
 * bookmark are kept. The local delete is one transaction and never needs the network; the
 * server record is removed now if possible, otherwise queued for POST /sync.
 */
export async function deleteDownload(packageId: string, store: BridgeAccessDb = appDb): Promise<void> {
  cancelDownload(packageId) // an update in progress must not re-store the files afterwards
  await store.transaction('rw', [store.packages, store.bundles, store.images], async () => {
    await store.bundles.delete(packageId)
    await store.images.where('packageId').equals(packageId).delete()
    const pkg = await store.packages.get(packageId)
    if (!pkg) return
    if (pkg.bookmarkedAt) {
      // Still listed under Bookmarks: keep the metadata, drop only the download fields.
      await store.packages
        .where('id')
        .equals(packageId)
        .modify((p) => {
          delete p.downloadStatus
          delete p.downloadedAt
        })
    } else {
      await store.packages.delete(packageId)
    }
  })

  try {
    await apiRequest(`/me/downloads/${encodeURIComponent(packageId)}`, { method: 'DELETE' })
  } catch (e) {
    // Offline, network trouble or a server error: send it later. Any 4xx means there is
    // nothing the server can record; the local delete stands either way.
    const transient = e instanceof ApiError && (e.kind !== 'http' || (e.status ?? 0) >= 500 || e.status === 429)
    if (transient) await enqueue({ type: 'download.remove', payload: { packageId } }, store)
  }
}

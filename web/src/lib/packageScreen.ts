import { useEffect, useState } from 'react'
import { ApiError } from './api/client'
import { getPackage, type LearningStep, type PackageBundle, type PackageDetail } from './api/packages'
import {
  db as appDb,
  type BridgeAccessDb,
  type LocalImage,
  type LocalPackage,
  type LocalProgress,
  type LocalQuizAttempt,
} from './db'
import { latestAttempt } from './quiz'
import { useLiveQuery } from './useLiveQuery'

/**
 * Data for the package screen (FR-7.1, FR-7.2). A downloaded package is shown entirely from
 * IndexedDB, so the screen works offline. Otherwise it comes from GET /packages/{id}; the
 * Learnt badge is still local (progress outlives a deleted download).
 */

export type PackageView = {
  id: string
  title: string
  subject: PackageDetail['subject']
  resourceType: PackageDetail['resourceType']
  packageType: PackageDetail['packageType']
  steps: LearningStep[]
  overview: string
  keyTakeaways: string[]
  original: { title: string; author: string; link: string }
  sizeBytes: number
  publishedAt: string
  /** Set when the package is on this device. */
  downloadedAt?: string
  downloaded: boolean
  learnt: boolean
  /** The latest quiz attempt on this device (FR-7.6), shown as the quiz result. */
  latest?: LocalQuizAttempt
  /** Remote cover URL; a downloaded package's cover is a stored blob instead (see coverBlob). */
  coverUrl: string | null
  coverAlt: string | null
}

/** A stored image (downloaded package) or a server URL; null for a text-only package. */
export type Cover = { blob: Blob } | { url: string } | null

export type PackageScreenState =
  | { status: 'loading' }
  | { status: 'ready'; view: PackageView; cover: Cover }
  | { status: 'error'; message: string; retry?: () => void }

type Local = {
  pkg?: LocalPackage
  bundle?: PackageBundle
  progress?: LocalProgress
  cover?: LocalImage
  latest?: LocalQuizAttempt
}

/** One read of everything stored for the package; re-runs when any of it changes. */
export async function readLocal(packageId: string, store: BridgeAccessDb = appDb): Promise<Local> {
  const [pkg, stored, progress, images, latest] = await Promise.all([
    store.packages.get(packageId),
    store.bundles.get(packageId),
    store.progress.get(packageId),
    store.images.where('packageId').equals(packageId).toArray(),
    latestAttempt(packageId, store),
  ])
  const cover = images.sort((a, b) => a.position - b.position)[0]
  return { pkg, bundle: stored?.bundle, progress, cover, latest }
}

export function fromBundle(bundle: PackageBundle, local: Local): PackageView {
  const p = bundle.package
  const first = [...bundle.images].sort((a, b) => a.position - b.position)[0]
  return {
    id: p.id,
    title: p.title,
    subject: p.subject,
    resourceType: p.resourceType,
    packageType: p.packageType,
    steps: p.steps,
    overview: bundle.content.overview,
    keyTakeaways: bundle.content.keyTakeaways,
    original: p.original,
    sizeBytes: p.sizeBytes,
    publishedAt: p.publishedAt,
    downloadedAt: local.pkg?.downloadedAt,
    downloaded: true,
    learnt: Boolean(local.progress?.learntAt),
    latest: local.latest,
    coverUrl: first?.url ?? null,
    coverAlt: first?.altText ?? null,
  }
}

export function fromDetail(detail: PackageDetail, local: Local): PackageView {
  return {
    id: detail.id,
    title: detail.title,
    subject: detail.subject,
    resourceType: detail.resourceType,
    packageType: detail.packageType,
    steps: detail.steps,
    overview: detail.overview,
    keyTakeaways: detail.keyTakeaways,
    original: detail.original,
    sizeBytes: detail.sizeBytes,
    publishedAt: detail.publishedAt,
    downloaded: false,
    learnt: Boolean(local.progress?.learntAt),
    latest: local.latest,
    coverUrl: detail.coverImageUrl,
    coverAlt: null,
  }
}

/** Only a fully downloaded package is read locally; a half-finished download still needs the server. */
function isStored(local: Local) {
  return local.pkg?.downloadStatus === 'downloaded' && local.bundle !== undefined
}

export function usePackageScreen(packageId: string, store: BridgeAccessDb = appDb): PackageScreenState {
  const local = useLiveQuery(() => readLocal(packageId, store), packageId)
  const stored = local.status === 'ready' && isStored(local.value)

  // Remote detail, only when the package is not on the device.
  const [remote, setRemote] = useState<
    { key: string; detail?: PackageDetail; error?: ApiError } | undefined
  >(undefined)
  const [attempt, setAttempt] = useState(0)
  const needRemote = local.status === 'ready' && !stored
  useEffect(() => {
    if (!needRemote) return
    const controller = new AbortController()
    const key = `${packageId}:${attempt}`
    getPackage(packageId, controller.signal).then(
      (detail) => setRemote({ key, detail }),
      (e: unknown) => {
        if (controller.signal.aborted) return
        setRemote({
          key,
          error: e instanceof ApiError ? e : new ApiError({ kind: 'network', message: 'Something went wrong.', requestId: '' }),
        })
      },
    )
    return () => controller.abort()
  }, [needRemote, packageId, attempt])

  if (local.status === 'loading') return { status: 'loading' }
  if (local.status === 'error') {
    return { status: 'error', message: 'This package could not be read from this device.' }
  }
  if (stored) {
    const view = fromBundle(local.value.bundle!, local.value)
    const blob = local.value.cover?.blob
    return { status: 'ready', view, cover: blob ? { blob } : null }
  }
  if (!remote || remote.key !== `${packageId}:${attempt}`) return { status: 'loading' }
  if (remote.detail) {
    const view = fromDetail(remote.detail, local.value)
    return { status: 'ready', view, cover: view.coverUrl ? { url: view.coverUrl } : null }
  }
  const e = remote.error!
  const retry = () => setAttempt((a) => a + 1)
  if (e.status === 404) return { status: 'error', message: 'This package is no longer available.' }
  if (e.kind === 'offline') {
    return { status: 'error', message: "You're offline. Download a package to open it without a connection.", retry }
  }
  return { status: 'error', message: e.message, retry }
}

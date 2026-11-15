import { useEffect } from 'react'
import type { PackageSummary } from './api/packages'
import { usePackageList } from './api/usePackageList'
import { useOnline } from './useOnline'

/**
 * Home's Explore preview (FR-2.4, FR-2.6): the newest published packages from GET /packages.
 * The last list that loaded is kept on the device, so Home still shows it offline (its
 * Download buttons wait for a connection). It loads again when the connection returns.
 */

export const PREVIEW_SIZE = 3
const STORAGE_KEY = 'bridgeaccess.explorePreview'

export type ExplorePreview =
  | { status: 'loading' }
  /** `stale`: the saved list from an earlier visit, shown because this load failed or is offline. */
  | { status: 'ready'; items: PackageSummary[]; stale: boolean }
  | { status: 'error'; message: string; retry: () => void }

export function readSavedPreview(): PackageSummary[] | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    const items: unknown = raw ? JSON.parse(raw) : null
    return Array.isArray(items) ? (items as PackageSummary[]) : null
  } catch {
    return null // storage blocked (private mode) or corrupted: just no saved list
  }
}

export function savePreview(items: PackageSummary[]) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(items))
  } catch {
    // Storage full or blocked: the preview simply will not be available offline.
  }
}

export function useExplorePreview(): ExplorePreview {
  const list = usePackageList({}, PREVIEW_SIZE)
  const online = useOnline()
  const { status, items, retry } = list

  useEffect(() => {
    if (status === 'ready') savePreview(items)
  }, [status, items])

  // Back online after a failed load: try again without the learner having to.
  useEffect(() => {
    if (online && status === 'error') retry()
  }, [online]) // eslint-disable-line react-hooks/exhaustive-deps

  if (status === 'ready' || status === 'loadingMore') return { status: 'ready', items, stale: false }
  const saved = readSavedPreview()
  if (saved && (status === 'error' || !online)) return { status: 'ready', items: saved, stale: true }
  // Online and loading: show the saved list meanwhile (no skeleton flash), without the
  // "from your last visit" note, since fresh results are a moment away.
  if (status === 'loading') return saved ? { status: 'ready', items: saved, stale: false } : { status: 'loading' }
  return { status: 'error', message: list.error?.message ?? 'Something went wrong.', retry }
}

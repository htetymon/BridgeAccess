import type { PackageType, ResourceType, Subject } from './api/packages'
import type { LocalPackage } from './db'
import { PACKAGE_TYPES, RESOURCE_TYPES, SUBJECTS } from './explore'

/**
 * Search, filters and sort of the Continue Learning and Recent Downloads full lists
 * (FR-3.2). Everything runs on the device over IndexedDB data, so it works offline. The
 * view lives in the URL like Explore's, so Back and refresh keep it.
 */

export type LibrarySort = 'latest_opened' | 'latest_downloaded'

export const LIBRARY_SORTS: readonly { value: LibrarySort; label: string }[] = [
  { value: 'latest_opened', label: 'Latest opened' },
  { value: 'latest_downloaded', label: 'Latest downloaded' },
]

export const MAX_LIBRARY_QUERY = 100

export type LibraryView = {
  q: string
  subject?: Subject
  resourceType?: ResourceType
  packageType?: PackageType
  sort: LibrarySort
}

/** One row of either list. `lastOpenedAt` is absent for a download never opened. */
export type LibraryItem = {
  pkg: LocalPackage
  lastOpenedAt?: string
  learnt: boolean
  /** Completed steps, 0 to 100. */
  percent: number
}

export function readLibraryView(params: URLSearchParams, defaultSort: LibrarySort): LibraryView {
  const subject = params.get('subject')
  const resourceType = params.get('resourceType')
  const packageType = params.get('packageType')
  const sort = params.get('sort')
  return {
    q: (params.get('q') ?? '').slice(0, MAX_LIBRARY_QUERY),
    subject: SUBJECTS.includes(subject as Subject) ? (subject as Subject) : undefined,
    resourceType: RESOURCE_TYPES.some((t) => t.value === resourceType) ? (resourceType as ResourceType) : undefined,
    packageType: PACKAGE_TYPES.some((t) => t.value === packageType) ? (packageType as PackageType) : undefined,
    sort: LIBRARY_SORTS.some((s) => s.value === sort) ? (sort as LibrarySort) : defaultSort,
  }
}

/** Empty values and the list's default sort are left out, so the plain list has a plain URL. */
export function writeLibraryView(view: LibraryView, defaultSort: LibrarySort): URLSearchParams {
  const params = new URLSearchParams()
  if (view.q.trim()) params.set('q', view.q.trim())
  if (view.subject) params.set('subject', view.subject)
  if (view.resourceType) params.set('resourceType', view.resourceType)
  if (view.packageType) params.set('packageType', view.packageType)
  if (view.sort !== defaultSort) params.set('sort', view.sort)
  return params
}

/** Case- and accent-insensitive, so "eco" finds "Écosystèmes" and "ECOSYSTEMS". */
function fold(text: string) {
  return text.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase()
}

/**
 * Filters and sorts. Search matches every word of the query anywhere in the title or
 * subject. Sorting puts the newest first; items without the sort's date (a download never
 * opened, sorted by Latest opened) go last, newest download first among them.
 */
export function applyLibraryView(items: readonly LibraryItem[], view: LibraryView): LibraryItem[] {
  const words = fold(view.q).split(/\s+/).filter(Boolean)
  const matches = items.filter(({ pkg }) => {
    if (view.subject && pkg.subject !== view.subject) return false
    if (view.resourceType && pkg.resourceType !== view.resourceType) return false
    if (view.packageType && pkg.packageType !== view.packageType) return false
    if (words.length === 0) return true
    const haystack = fold(`${pkg.title} ${pkg.subject}`)
    return words.every((w) => haystack.includes(w))
  })
  const primary = (i: LibraryItem) => (view.sort === 'latest_opened' ? i.lastOpenedAt : i.pkg.downloadedAt) ?? ''
  const secondary = (i: LibraryItem) => (view.sort === 'latest_opened' ? i.pkg.downloadedAt : i.lastOpenedAt) ?? ''
  // ISO 8601 UTC strings sort as time; '' (missing) sorts after every date in descending order.
  return [...matches].sort(
    (a, b) =>
      primary(b).localeCompare(primary(a)) ||
      secondary(b).localeCompare(secondary(a)) ||
      a.pkg.title.localeCompare(b.pkg.title),
  )
}

export function libraryFilterCount(view: LibraryView): number {
  return [view.subject, view.resourceType, view.packageType].filter(Boolean).length
}

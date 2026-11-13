import type { ListPackagesParams, PackageType, ResourceType, Subject } from './api/packages'

/**
 * Explore's search, filters and sort (FR-4.1, FR-4.2), kept in the URL query string so Back,
 * refresh and a shared link all bring the same view back. Values are checked on the way in:
 * a hand-edited or outdated link cannot send the server a value it would reject.
 */

export const SUBJECTS: readonly Subject[] = ['Science', 'Health', 'Math', 'Technology', 'Language', 'History', 'Other']
export const RESOURCE_TYPES: readonly { value: ResourceType; label: string }[] = [
  { value: 'text', label: 'Text' },
  { value: 'video', label: 'Video' },
]
export const PACKAGE_TYPES: readonly { value: PackageType; label: string }[] = [
  { value: 'text_only', label: 'Text only' },
  { value: 'with_visual', label: 'With visual' },
]
/** The server sorts only by newest published in the MVP; the control shows that honestly. */
export const SORTS = [{ value: 'latest_published', label: 'Latest published' }] as const

/** Server rules for `q`. */
export const MIN_QUERY = 2
export const MAX_QUERY = 100

export type ExploreView = {
  q: string
  subject?: Subject
  resourceType?: ResourceType
  packageType?: PackageType
}

export function readView(params: URLSearchParams): ExploreView {
  const subject = params.get('subject')
  const resourceType = params.get('resourceType')
  const packageType = params.get('packageType')
  return {
    q: (params.get('q') ?? '').slice(0, MAX_QUERY),
    subject: SUBJECTS.includes(subject as Subject) ? (subject as Subject) : undefined,
    resourceType: RESOURCE_TYPES.some((t) => t.value === resourceType) ? (resourceType as ResourceType) : undefined,
    packageType: PACKAGE_TYPES.some((t) => t.value === packageType) ? (packageType as PackageType) : undefined,
  }
}

/** The URL for a view; empty values are left out, so the plain catalogue is just /explore. */
export function writeView(view: ExploreView): URLSearchParams {
  const params = new URLSearchParams()
  if (view.q.trim()) params.set('q', view.q.trim())
  if (view.subject) params.set('subject', view.subject)
  if (view.resourceType) params.set('resourceType', view.resourceType)
  if (view.packageType) params.set('packageType', view.packageType)
  return params
}

/**
 * The search term to send, or undefined. Fewer than 2 characters is not sent at all (the
 * server would refuse it), so typing the first letter does not empty the list.
 */
export function searchTerm(q: string): string | undefined {
  const term = q.trim().replace(/\s+/g, ' ')
  return term.length >= MIN_QUERY ? term.slice(0, MAX_QUERY) : undefined
}

/** GET /packages filters for a view. */
export function toListParams(view: ExploreView): Omit<ListPackagesParams, 'limit' | 'cursor'> {
  return {
    q: searchTerm(view.q),
    subject: view.subject,
    resourceType: view.resourceType,
    packageType: view.packageType,
  }
}

export function activeFilterCount(view: ExploreView): number {
  return [view.subject, view.resourceType, view.packageType].filter(Boolean).length
}

/** The empty-result message: says what was searched, so the next step is obvious. */
export function noResultsMessage(view: ExploreView): string {
  const term = searchTerm(view.q)
  const filtered = activeFilterCount(view) > 0
  if (term && filtered) return `No packages match “${term}” with these filters.`
  if (term) return `No packages match “${term}”.`
  if (filtered) return 'No packages match these filters.'
  return 'No packages yet. Check back soon, or contribute the first one.'
}

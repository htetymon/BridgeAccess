import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from './client'
import { listPackages, type ListPackagesParams, type PackageSummary } from './packages'

/** FR-4.5: lists load 20 at a time with a Load more button. */
export const PAGE_SIZE = 20

export type PackageListState = {
  items: PackageSummary[]
  /** 'loading' is the first page; 'loadingMore' is a later page, with items still shown. */
  status: 'loading' | 'ready' | 'loadingMore' | 'error'
  /** Set when the first page failed (status 'error') or the last Load more failed. */
  error: ApiError | null
  hasMore: boolean
}

type Internal = PackageListState & {
  cursor: string | null
  /** The filters these items belong to. A different key means a new list is loading. */
  key: string
}

/**
 * Cursor-paged GET /packages. The first page loads on mount and again whenever the filters
 * change; `loadMore` appends the next page; `retry` reloads from the first page.
 *
 * Results are tagged with the filters they were loaded for, so after a filter change the
 * old list is never shown as the new one, Load more never uses the old cursor, and a slow
 * response for an earlier search cannot overwrite a newer one (it is aborted, and ignored if
 * it still arrives).
 */
export function usePackageList(filters: Omit<ListPackagesParams, 'limit' | 'cursor'> = {}, pageSize = PAGE_SIZE) {
  const filterKey = JSON.stringify({ ...filters, pageSize })
  const [state, setState] = useState<Internal>({ ...EMPTY, status: 'loading', key: '' })
  const [reloadKey, setReloadKey] = useState(0)
  const moreRequest = useRef<AbortController | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    const { pageSize: size, ...params } = JSON.parse(filterKey) as ListPackagesParams & { pageSize: number }
    listPackages({ ...params, limit: size }, controller.signal).then(
      (page) =>
        setState({
          items: page.items,
          cursor: page.nextCursor,
          hasMore: page.nextCursor !== null,
          status: 'ready',
          error: null,
          key: filterKey,
        }),
      (e: unknown) => {
        if (!controller.signal.aborted) setState({ ...EMPTY, status: 'error', error: toApiError(e), key: filterKey })
      },
    )
    return () => {
      controller.abort()
      moreRequest.current?.abort()
    }
  }, [filterKey, reloadKey])

  const current = state.key === filterKey

  const loadMore = useCallback(() => {
    if (!current || state.status !== 'ready' || !state.cursor) return
    const controller = new AbortController()
    moreRequest.current = controller
    const { pageSize: size, ...params } = JSON.parse(filterKey) as ListPackagesParams & { pageSize: number }
    setState((s) => ({ ...s, status: 'loadingMore', error: null }))
    listPackages({ ...params, limit: size, cursor: state.cursor }, controller.signal).then(
      (page) =>
        setState((s) =>
          s.key !== filterKey
            ? s
            : {
                ...s,
                items: appendUnique(s.items, page.items),
                cursor: page.nextCursor,
                hasMore: page.nextCursor !== null,
                status: 'ready',
                error: null,
              },
        ),
      (e: unknown) => {
        // Keep what is already shown; the button becomes a retry for the same cursor.
        if (!controller.signal.aborted)
          setState((s) => (s.key !== filterKey ? s : { ...s, status: 'ready', error: toApiError(e) }))
      },
    )
  }, [current, state.status, state.cursor, filterKey])

  const retry = useCallback(() => {
    setState((s) => ({ ...s, key: '' })) // shows loading until the reload answers
    setReloadKey((k) => k + 1)
  }, [])

  const visible: PackageListState = current
    ? { items: state.items, status: state.status, error: state.error, hasMore: state.hasMore }
    : { ...EMPTY, status: 'loading' }
  return { ...visible, loadMore, retry }
}

const EMPTY: PackageListState & { cursor: null } = {
  items: [],
  status: 'ready',
  error: null,
  hasMore: false,
  cursor: null,
}

/** A package published between page requests can shift the window; never show it twice. */
function appendUnique(existing: PackageSummary[], next: PackageSummary[]) {
  const seen = new Set(existing.map((p) => p.id))
  return [...existing, ...next.filter((p) => !seen.has(p.id))]
}

function toApiError(e: unknown): ApiError {
  return e instanceof ApiError
    ? e
    : new ApiError({ kind: 'network', message: 'Something went wrong. Please try again.', requestId: '' })
}

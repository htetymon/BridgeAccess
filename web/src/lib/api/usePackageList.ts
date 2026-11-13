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

type Internal = PackageListState & { cursor: string | null }

const LOADING: Internal = { items: [], status: 'loading', error: null, hasMore: false, cursor: null }

/**
 * Cursor-paged GET /packages. The first page loads on mount; `loadMore` appends the next
 * one; `retry` reloads from the first page. In-flight requests are cancelled on unmount.
 */
export function usePackageList(filters: Omit<ListPackagesParams, 'limit' | 'cursor'> = {}, pageSize = PAGE_SIZE) {
  const [state, setState] = useState<Internal>(LOADING)
  const [reloadKey, setReloadKey] = useState(0)
  const moreRequest = useRef<AbortController | null>(null)
  const filterKey = JSON.stringify(filters)

  useEffect(() => {
    const controller = new AbortController()
    listPackages({ ...(JSON.parse(filterKey) as ListPackagesParams), limit: pageSize }, controller.signal).then(
      (page) =>
        setState({ items: page.items, cursor: page.nextCursor, hasMore: page.nextCursor !== null, status: 'ready', error: null }),
      (e: unknown) => {
        if (!controller.signal.aborted) setState({ ...LOADING, status: 'error', error: toApiError(e) })
      },
    )
    return () => {
      controller.abort()
      moreRequest.current?.abort()
    }
  }, [filterKey, reloadKey, pageSize])

  const loadMore = useCallback(() => {
    if (state.status !== 'ready' || !state.cursor) return
    const controller = new AbortController()
    moreRequest.current = controller
    setState((s) => ({ ...s, status: 'loadingMore', error: null }))
    listPackages(
      { ...(JSON.parse(filterKey) as ListPackagesParams), limit: pageSize, cursor: state.cursor },
      controller.signal,
    ).then(
      (page) =>
        setState((s) => ({
          items: appendUnique(s.items, page.items),
          cursor: page.nextCursor,
          hasMore: page.nextCursor !== null,
          status: 'ready',
          error: null,
        })),
      (e: unknown) => {
        // Keep what is already shown; the button becomes a retry for the same cursor.
        if (!controller.signal.aborted) setState((s) => ({ ...s, status: 'ready', error: toApiError(e) }))
      },
    )
  }, [state.status, state.cursor, filterKey, pageSize])

  const retry = useCallback(() => {
    setState(LOADING)
    setReloadKey((k) => k + 1)
  }, [])

  const { cursor: _cursor, ...publicState } = state
  return { ...publicState, loadMore, retry }
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

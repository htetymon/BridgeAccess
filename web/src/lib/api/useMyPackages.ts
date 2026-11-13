import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { describeRetryError, statusAnnouncement, type RetryProblem } from '../contribution'
import { startStatusPolling } from '../statusPoller'
import { ApiError } from './client'
import { deletePackage, getMyPackage, listMyPackages, retryPackage, type MyPackage } from './contributions'

export type MyPackagesState = {
  items: MyPackage[]
  status: 'loading' | 'ready' | 'loadingMore' | 'error'
  error: ApiError | null
  hasMore: boolean
  /** Processing packages still not done after about 3 minutes of polling ("Still working"). */
  stalled: ReadonlySet<string>
  /** Latest status change to read out ("… is ready"), for a polite live region. */
  announcement: string
}

/**
 * This device's packages (GET /me/packages) for My Contributions, with:
 * - status polling of processing packages (see startStatusPolling): backoff, paused offline
 *   and in the background, checked at once when the app comes back;
 * - "Check again" for a package still working after the polling time limit;
 * - retry and delete that update the list at once and roll back if the server refuses.
 * Polling restarts whenever the screen is opened again, as the API doc suggests.
 */
export function useMyPackages(limit: number) {
  const [state, setState] = useState<MyPackagesState>({
    items: [],
    status: 'loading',
    error: null,
    hasMore: false,
    stalled: new Set(),
    announcement: '',
  })
  const cursor = useRef<string | null>(null)
  const [reloadKey, setReloadKey] = useState(0)
  /** Bumped to restart polling from the start of the schedule (retry, Check again). */
  const [pollEpoch, setPollEpoch] = useState(0)
  // Latest items for timers and callbacks, without re-creating them on every change.
  const items = useRef<MyPackage[]>([])
  useLayoutEffect(() => {
    items.current = state.items
  })

  // ------------------------------------------------------------- first page
  useEffect(() => {
    const controller = new AbortController()
    listMyPackages({ limit }, controller.signal).then(
      (page) => {
        cursor.current = page.nextCursor
        setState((s) => ({
          ...s,
          items: page.items,
          status: 'ready',
          error: null,
          hasMore: page.nextCursor !== null,
          stalled: new Set(),
        }))
      },
      (e: unknown) => {
        if (!controller.signal.aborted)
          setState((s) => ({ ...s, items: [], status: 'error', error: toApiError(e), hasMore: false }))
      },
    )
    return () => controller.abort()
  }, [limit, reloadKey])

  // ---------------------------------------------------------------- polling
  // One poller while anything is processing. It reads the processing IDs fresh each round,
  // so a package finishing does not restart the schedule for the others.
  const hasProcessing = state.items.some((p) => p.status === 'processing')

  useEffect(() => {
    if (!hasProcessing) return
    return startStatusPolling<MyPackage>({
      ids: () => items.current.filter((p) => p.status === 'processing').map((p) => p.id),
      check: (id, signal) =>
        getMyPackage(id, signal).catch((e: unknown) => {
          if (e instanceof ApiError && e.status === 404) return null // deleted elsewhere
          throw e // network trouble: no answer this round
        }),
      onResults: (results) =>
        setState((s) => {
          const announcements: string[] = []
          const next = s.items.flatMap((p) => {
            if (!results.has(p.id)) return [p]
            const fresh = results.get(p.id)
            if (!fresh) return []
            const said = statusAnnouncement(p, fresh)
            if (said) announcements.push(said)
            return [fresh]
          })
          const stalled = new Set([...s.stalled].filter((id) => next.some((p) => p.id === id && p.status === 'processing')))
          return { ...s, items: next, stalled, announcement: announcements.join(' ') || s.announcement }
        }),
      onGiveUp: (ids) => setState((s) => ({ ...s, stalled: new Set([...s.stalled, ...ids]) })),
    })
  }, [hasProcessing, pollEpoch])

  /** For a package showing "Still working": look again now, with a fresh schedule. */
  const checkAgain = useCallback((id: string) => {
    setState((s) => ({ ...s, stalled: without(s.stalled, id) }))
    setPollEpoch((n) => n + 1)
  }, [])

  // -------------------------------------------------------------- load more
  const loadMore = useCallback(() => {
    if (state.status !== 'ready' || !cursor.current) return
    setState((s) => ({ ...s, status: 'loadingMore', error: null }))
    listMyPackages({ limit, cursor: cursor.current }).then(
      (page) => {
        cursor.current = page.nextCursor
        setState((s) => {
          const seen = new Set(s.items.map((p) => p.id))
          return {
            ...s,
            items: [...s.items, ...page.items.filter((p) => !seen.has(p.id))],
            status: 'ready',
            hasMore: page.nextCursor !== null,
          }
        })
      },
      (e: unknown) => setState((s) => ({ ...s, status: 'ready', error: toApiError(e) })),
    )
  }, [state.status, limit])

  // ---------------------------------------------------------- retry, delete
  /** Resolves to null on success, or what to tell the contributor (and whether Retry still makes sense). */
  const retry = useCallback(async (id: string): Promise<RetryProblem | null> => {
    const before = items.current.find((p) => p.id === id)
    if (!before) return null
    setState((s) => ({
      ...s,
      items: s.items.map((p) => (p.id === id ? { ...p, status: 'processing', failureReason: null } : p)),
      stalled: without(s.stalled, id),
    }))
    try {
      await retryPackage(id)
      setPollEpoch((n) => n + 1)
      return null
    } catch (e) {
      const problem = describeRetryError(e)
      if (problem.gone) {
        setState((s) => ({ ...s, items: s.items.filter((p) => p.id !== id) }))
      } else {
        setState((s) => ({ ...s, items: s.items.map((p) => (p.id === id ? before : p)) }))
      }
      return problem
    }
  }, [])

  const remove = useCallback(async (id: string): Promise<string | null> => {
    const index = items.current.findIndex((p) => p.id === id)
    if (index < 0) return null
    const removed = items.current[index]
    setState((s) => ({ ...s, items: s.items.filter((p) => p.id !== id), stalled: without(s.stalled, id) }))
    try {
      await deletePackage(id)
      setState((s) => ({ ...s, announcement: `Deleted “${removed.title ?? removed.originalTitle}”.` }))
      return null
    } catch (e) {
      const error = toApiError(e)
      if (error.status === 404) return null // already gone
      setState((s) => {
        const next = [...s.items]
        next.splice(Math.min(index, next.length), 0, removed)
        return { ...s, items: next }
      })
      return error.kind === 'offline' ? "You're offline. Connect to the internet to delete this package." : error.message
    }
  }, [])

  const reload = useCallback(() => {
    setState((s) => ({ ...s, status: 'loading', error: null }))
    setReloadKey((k) => k + 1)
  }, [])

  return { ...state, loadMore, retry, remove, reload, checkAgain }
}

function without(set: ReadonlySet<string>, id: string) {
  const next = new Set(set)
  next.delete(id)
  return next
}

function toApiError(e: unknown): ApiError {
  return e instanceof ApiError
    ? e
    : new ApiError({ kind: 'network', message: 'Something went wrong. Please try again.', requestId: '' })
}

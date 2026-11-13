import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { pollDelay } from '../contribution'
import { ApiError } from './client'
import { deletePackage, getMyPackage, listMyPackages, retryPackage, type MyPackage } from './contributions'

export type MyPackagesState = {
  items: MyPackage[]
  status: 'loading' | 'ready' | 'loadingMore' | 'error'
  error: ApiError | null
  hasMore: boolean
  /** Processing packages still not done after about 3 minutes of polling ("Still working"). */
  stalled: ReadonlySet<string>
}

/**
 * This device's packages (GET /me/packages) for My Contributions, with:
 * - polling of processing packages (GET /me/packages/{id}) on the API doc's schedule
 *   (3, 5, 8, then 10 s), stopping at published/failed or after about 3 minutes;
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
  })
  const cursor = useRef<string | null>(null)
  const [reloadKey, setReloadKey] = useState(0)
  /** Bumped to restart the polling schedule (after a retry). */
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
        setState({ items: page.items, status: 'ready', error: null, hasMore: page.nextCursor !== null, stalled: new Set() })
      },
      (e: unknown) => {
        if (!controller.signal.aborted)
          setState((s) => ({ ...s, items: [], status: 'error', error: toApiError(e), hasMore: false }))
      },
    )
    return () => controller.abort()
  }, [limit, reloadKey])

  // ---------------------------------------------------------------- polling
  const processingKey = state.items
    .filter((p) => p.status === 'processing')
    .map((p) => p.id)
    .join(',')

  useEffect(() => {
    if (!processingKey) return
    const controller = new AbortController()
    const started = Date.now()
    let attempt = 0
    let timer: ReturnType<typeof setTimeout>

    const schedule = () => {
      const delay = pollDelay(attempt, Date.now() - started)
      if (delay === null) {
        const waiting = items.current.filter((p) => p.status === 'processing').map((p) => p.id)
        setState((s) => ({ ...s, stalled: new Set([...s.stalled, ...waiting]) }))
        return
      }
      timer = setTimeout(tick, delay)
    }

    const tick = async () => {
      attempt++
      const ids = items.current.filter((p) => p.status === 'processing').map((p) => p.id)
      const results = await Promise.allSettled(ids.map((id) => getMyPackage(id, controller.signal)))
      if (controller.signal.aborted) return
      const updates = new Map<string, MyPackage | null>()
      results.forEach((r, i) => {
        if (r.status === 'fulfilled') updates.set(ids[i], r.value)
        // 404: deleted elsewhere (another tab); drop it. Network errors: keep polling.
        else if (r.reason instanceof ApiError && r.reason.status === 404) updates.set(ids[i], null)
      })
      if (updates.size > 0) {
        setState((s) => ({
          ...s,
          items: s.items.flatMap((p) => {
            if (!updates.has(p.id)) return [p]
            const next = updates.get(p.id)
            return next ? [next] : []
          }),
        }))
      }
      // A status change re-runs this effect (new processingKey); otherwise keep going.
      schedule()
    }

    schedule()
    return () => {
      controller.abort()
      clearTimeout(timer)
    }
  }, [processingKey, pollEpoch])

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
  /** Resolves to an error message to show, or null on success. */
  const retry = useCallback(async (id: string): Promise<string | null> => {
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
      setState((s) => ({ ...s, items: s.items.map((p) => (p.id === id ? before : p)) }))
      return toApiError(e).message
    }
  }, [])

  const remove = useCallback(async (id: string): Promise<string | null> => {
    const index = items.current.findIndex((p) => p.id === id)
    if (index < 0) return null
    const removed = items.current[index]
    setState((s) => ({ ...s, items: s.items.filter((p) => p.id !== id) }))
    try {
      await deletePackage(id)
      return null
    } catch (e) {
      const error = toApiError(e)
      if (error.status === 404) return null // already gone
      setState((s) => {
        const next = [...s.items]
        next.splice(Math.min(index, next.length), 0, removed)
        return { ...s, items: next }
      })
      return error.message
    }
  }, [])

  const reload = useCallback(() => {
    setState((s) => ({ ...s, status: 'loading', error: null }))
    setReloadKey((k) => k + 1)
  }, [])

  return { ...state, loadMore, retry, remove, reload }
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

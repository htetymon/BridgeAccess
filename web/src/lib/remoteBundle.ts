import { useEffect, useState } from 'react'
import { getBundle, type PackageBundle } from './api/packages'
import { ApiError } from './api/client'
import { useOnline } from './useOnline'

/**
 * Learning a package without downloading it: the bundle is read from the server and kept in
 * memory for this visit only (Viewer and Review Answers share it, so moving between them does
 * not fetch again). Nothing is stored on the device; Download is still what makes it work
 * offline. Progress and quiz attempts are saved and synced exactly as for a downloaded one.
 */
const cache = new Map<string, PackageBundle>()

export type RemoteBundleState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready'; bundle: PackageBundle }
  | { status: 'offline' }
  | { status: 'error'; message: string; retry: () => void }

/** `enabled` is false while the device copy is being read, or when there is one. */
export function useRemoteBundle(packageId: string, enabled: boolean): RemoteBundleState {
  const online = useOnline()
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState<{ key: string; bundle?: PackageBundle; error?: string }>()
  const key = `${packageId}:${attempt}`

  useEffect(() => {
    if (!enabled || !online || cache.has(packageId)) return
    const controller = new AbortController()
    getBundle(packageId, controller.signal).then(
      (bundle) => {
        cache.set(packageId, bundle)
        setResult({ key, bundle })
      },
      (e: unknown) => {
        if (e instanceof ApiError && e.kind === 'aborted') return
        setResult({ key, error: e instanceof Error && e.message ? e.message : 'Could not load this package.' })
      },
    )
    return () => controller.abort()
  }, [packageId, enabled, online, key])

  if (!enabled) return { status: 'idle' }
  const cached = cache.get(packageId)
  if (cached) return { status: 'ready', bundle: cached }
  if (!online) return { status: 'offline' }
  if (result?.key === key && result.error) {
    return { status: 'error', message: result.error, retry: () => setAttempt((n) => n + 1) }
  }
  return { status: 'loading' }
}

import { useEffect, useRef, useState } from 'react'
import { liveQuery } from 'dexie'

export type LiveResult<T> = { status: 'loading' } | { status: 'ready'; value: T } | { status: 'error' }

/**
 * Runs an IndexedDB query and re-runs it whenever the tables it read change (Dexie
 * liveQuery), so a screen updates by itself after a download, a delete or a quiz.
 * The subscription restarts when `key` changes: put every input of the query in it
 * (for example a filter or a package id).
 */
export function useLiveQuery<T>(query: () => Promise<T>, key = ''): LiveResult<T> {
  const [result, setResult] = useState<LiveResult<T>>({ status: 'loading' })
  const latest = useRef(query)
  useEffect(() => {
    latest.current = query
  })

  useEffect(() => {
    const subscription = liveQuery(() => latest.current()).subscribe({
      next: (value) => setResult({ status: 'ready', value }),
      error: () => setResult({ status: 'error' }),
    })
    return () => subscription.unsubscribe()
  }, [key])
  return result
}

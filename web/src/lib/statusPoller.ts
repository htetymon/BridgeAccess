import { pollDelay } from './contribution'

/**
 * Polls the status of processing packages with backoff (API doc, GET /me/packages/{id}:
 * 3, 5, 8, then every 10 s, stop after about 3 minutes).
 *
 * Data and battery matter to this app's users, so it only polls while it can be useful:
 * - offline or with the app in the background, nothing is sent;
 * - coming back (online again, or the app shown again) checks at once and restarts the
 *   schedule, so a package that finished meanwhile shows up immediately.
 * A failed check (slow network) is not an answer: it waits for the next one. A 404 means the
 * package is gone (deleted elsewhere).
 */

export type PollEnv = {
  now: () => number
  setTimeout: (fn: () => void, ms: number) => unknown
  clearTimeout: (handle: unknown) => void
  isActive: () => boolean
  /** Calls `wake` when the app may poll again (online, or visible). Returns an unsubscribe. */
  onWake: (wake: () => void) => () => void
}

export type PollOptions<T> = {
  /** The IDs still processing, read fresh before each round. */
  ids: () => string[]
  /** Resolves to the latest state, or null when the package no longer exists. */
  check: (id: string, signal: AbortSignal) => Promise<T | null>
  /** Results of one round; only IDs that got an answer are included. */
  onResults: (results: Map<string, T | null>) => void
  /** Called once when the time limit passes with packages still processing. */
  onGiveUp: (ids: string[]) => void
  env?: PollEnv
}

export function startStatusPolling<T>(options: PollOptions<T>): () => void {
  const env = options.env ?? browserEnv()
  const controller = new AbortController()
  let attempt = 0
  let started = env.now()
  let timer: unknown = null
  let running = false
  let stopped = false

  const schedule = () => {
    if (stopped) return
    if (options.ids().length === 0) return
    const delay = pollDelay(attempt, env.now() - started)
    if (delay === null) {
      options.onGiveUp(options.ids())
      return
    }
    timer = env.setTimeout(() => void round(), delay)
  }

  /** `extra`: an immediate check on waking, which does not use up a step of the schedule. */
  const round = async (extra = false) => {
    timer = null
    if (stopped) return
    if (!env.isActive()) return // resumes through onWake
    const ids = options.ids()
    if (ids.length === 0) return
    if (!extra) attempt++
    running = true
    const settled = await Promise.allSettled(ids.map((id) => options.check(id, controller.signal)))
    running = false
    if (stopped) return
    const results = new Map<string, T | null>()
    settled.forEach((s, i) => {
      if (s.status === 'fulfilled') results.set(ids[i], s.value)
    })
    if (results.size > 0) options.onResults(results)
    schedule()
  }

  const unsubscribe = env.onWake(() => {
    if (stopped || running) return
    if (timer !== null) env.clearTimeout(timer)
    attempt = 0
    started = env.now()
    void round(true)
  })

  schedule()

  return () => {
    stopped = true
    controller.abort()
    if (timer !== null) env.clearTimeout(timer)
    unsubscribe()
  }
}

function browserEnv(): PollEnv {
  return {
    now: () => Date.now(),
    setTimeout: (fn, ms) => window.setTimeout(fn, ms),
    clearTimeout: (h) => window.clearTimeout(h as number),
    isActive: () => navigator.onLine !== false && document.visibilityState !== 'hidden',
    onWake: (wake) => {
      const onVisible = () => {
        if (document.visibilityState === 'visible') wake()
      }
      window.addEventListener('online', wake)
      document.addEventListener('visibilitychange', onVisible)
      return () => {
        window.removeEventListener('online', wake)
        document.removeEventListener('visibilitychange', onVisible)
      }
    },
  }
}

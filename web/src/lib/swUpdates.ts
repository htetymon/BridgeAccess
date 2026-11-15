/**
 * Makes sure a new version reaches learners who keep the app open or installed. Browsers look
 * for a new service worker mainly when a page is navigated to, and an installed PWA that
 * stays open rarely navigates. So the app also asks: every hour, and when it comes back to
 * the foreground (at most every few minutes). A new version then shows the update prompt.
 */

export const UPDATE_CHECK_INTERVAL_MS = 60 * 60 * 1000
/** Returning to the app checks again only if the last check is at least this old. */
export const FOREGROUND_CHECK_MIN_MS = 5 * 60 * 1000

type Env = {
  isOnline: () => boolean
  now: () => number
  setInterval: (fn: () => void, ms: number) => unknown
  clearInterval: (id: unknown) => void
  onForeground: (fn: () => void) => () => void
}

const browserEnv = (): Env => ({
  isOnline: () => navigator.onLine !== false,
  now: () => Date.now(),
  setInterval: (fn, ms) => setInterval(fn, ms),
  clearInterval: (id) => clearInterval(id as ReturnType<typeof setInterval>),
  onForeground: (fn) => {
    const listener = () => {
      if (document.visibilityState === 'visible') fn()
    }
    document.addEventListener('visibilitychange', listener)
    return () => document.removeEventListener('visibilitychange', listener)
  },
})

/** Starts the checks; returns a function that stops them. */
export function startUpdateChecks(registration: Pick<ServiceWorkerRegistration, 'update'>, env: Env = browserEnv()) {
  let lastCheck = env.now()
  const check = () => {
    if (!env.isOnline()) return
    lastCheck = env.now()
    // A failed check (flaky network, server down) is simply tried again next time.
    registration.update().catch(() => {})
  }
  const timer = env.setInterval(check, UPDATE_CHECK_INTERVAL_MS)
  const stopForeground = env.onForeground(() => {
    if (env.now() - lastCheck >= FOREGROUND_CHECK_MIN_MS) check()
  })
  return () => {
    env.clearInterval(timer)
    stopForeground()
  }
}

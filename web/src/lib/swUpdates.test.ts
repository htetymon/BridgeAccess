import { describe, expect, it, vi } from 'vitest'
import { FOREGROUND_CHECK_MIN_MS, startUpdateChecks, UPDATE_CHECK_INTERVAL_MS } from './swUpdates'

function setup(online = true) {
  let now = 0
  let tick: (() => void) | undefined
  let interval = 0
  let foreground: (() => void) | undefined
  const update = vi.fn(() => Promise.resolve(undefined as unknown as ServiceWorkerRegistration))
  const state = { online }
  const stop = startUpdateChecks(
    { update },
    {
      isOnline: () => state.online,
      now: () => now,
      setInterval: (fn, ms) => {
        tick = fn
        interval = ms
        return 1
      },
      clearInterval: () => {
        tick = undefined
      },
      onForeground: (fn) => {
        foreground = fn
        return () => {
          foreground = undefined
        }
      },
    },
  )
  return {
    update,
    state,
    stop,
    interval: () => interval,
    advance: (ms: number) => {
      now += ms
    },
    tick: () => tick?.(),
    foreground: () => foreground?.(),
  }
}

describe('startUpdateChecks', () => {
  it('asks for an update every hour', () => {
    const t = setup()
    expect(t.interval()).toBe(UPDATE_CHECK_INTERVAL_MS)
    t.tick()
    t.tick()
    expect(t.update).toHaveBeenCalledTimes(2)
  })

  it('asks when the app returns to the foreground, but not more than every few minutes', () => {
    const t = setup()
    t.foreground()
    expect(t.update).not.toHaveBeenCalled() // just started

    t.advance(FOREGROUND_CHECK_MIN_MS)
    t.foreground()
    t.foreground()
    expect(t.update).toHaveBeenCalledTimes(1)
  })

  it('does not ask while offline', () => {
    const t = setup(false)
    t.tick()
    expect(t.update).not.toHaveBeenCalled()
  })

  it('ignores a failed check', async () => {
    const t = setup()
    t.update.mockRejectedValueOnce(new Error('network'))
    expect(() => t.tick()).not.toThrow()
    await Promise.resolve()
  })

  it('stops', () => {
    const t = setup()
    t.stop()
    t.tick()
    t.advance(FOREGROUND_CHECK_MIN_MS)
    t.foreground()
    expect(t.update).not.toHaveBeenCalled()
  })
})

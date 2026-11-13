import { describe, expect, it } from 'vitest'
import { startStatusPolling, type PollEnv } from './statusPoller'

/** A controllable clock, connection and visibility. */
function fakeEnv() {
  let now = 0
  let active = true
  let timers: { at: number; fn: () => void; id: number }[] = []
  let nextId = 1
  let wake: (() => void) | null = null
  const env: PollEnv = {
    now: () => now,
    setTimeout: (fn, ms) => {
      const id = nextId++
      timers.push({ at: now + ms, fn, id })
      return id
    },
    clearTimeout: (id) => {
      timers = timers.filter((t) => t.id !== id)
    },
    isActive: () => active,
    onWake: (w) => {
      wake = w
      return () => {
        wake = null
      }
    },
  }
  return {
    env,
    /** Advances time, firing due timers, and lets their async work settle. */
    async advance(ms: number) {
      const end = now + ms
      for (;;) {
        timers.sort((a, b) => a.at - b.at)
        const due = timers[0]
        if (!due || due.at > end) break
        timers.shift()
        now = due.at
        due.fn()
        await flush()
      }
      now = end
    },
    setActive(a: boolean) {
      active = a
    },
    async wake() {
      wake?.()
      await flush()
    },
    pending: () => timers.map((t) => t.at - now),
  }
}

const flush = () => new Promise((r) => setTimeout(r, 0))

describe('startStatusPolling', () => {
  it('polls at 3, 5, 8, then every 10 seconds', async () => {
    const f = fakeEnv()
    const calls: number[] = []
    startStatusPolling({
      ids: () => ['a'],
      check: async () => {
        calls.push(f.env.now())
        return 'processing'
      },
      onResults: () => {},
      onGiveUp: () => {},
      env: f.env,
    })

    await f.advance(46_000)
    expect(calls).toEqual([3_000, 8_000, 16_000, 26_000, 36_000, 46_000])
  })

  it('reports results and stops when nothing is processing', async () => {
    const f = fakeEnv()
    let processing = ['a', 'b']
    const seen: Map<string, string | null>[] = []
    startStatusPolling({
      ids: () => processing,
      check: async (id) => (id === 'a' ? 'published' : null),
      onResults: (r) => {
        seen.push(r)
        processing = []
      },
      onGiveUp: () => {},
      env: f.env,
    })

    await f.advance(3_000)
    expect(seen).toEqual([
      new Map([
        ['a', 'published'],
        ['b', null],
      ]),
    ])
    expect(f.pending()).toEqual([]) // nothing left to poll
  })

  it('treats a failed check as no answer and keeps going', async () => {
    const f = fakeEnv()
    let n = 0
    const seen: unknown[] = []
    startStatusPolling({
      ids: () => ['a'],
      check: async () => {
        if (n++ === 0) throw new Error('network')
        return 'published'
      },
      onResults: (r) => seen.push(r.get('a')),
      onGiveUp: () => {},
      env: f.env,
    })

    await f.advance(3_000)
    expect(seen).toEqual([])
    await f.advance(5_000)
    expect(seen).toEqual(['published'])
  })

  it('gives up after about 3 minutes', async () => {
    const f = fakeEnv()
    const gaveUp: string[][] = []
    startStatusPolling({
      ids: () => ['a'],
      check: async () => 'processing',
      onResults: () => {},
      onGiveUp: (ids) => gaveUp.push(ids),
      env: f.env,
    })

    await f.advance(179_000)
    expect(gaveUp).toEqual([])
    await f.advance(10_000)
    expect(gaveUp).toEqual([['a']])
    expect(f.pending()).toEqual([])
  })

  it('sends nothing while offline or hidden, and checks at once on return', async () => {
    const f = fakeEnv()
    const calls: number[] = []
    startStatusPolling({
      ids: () => ['a'],
      check: async () => {
        calls.push(f.env.now())
        return 'processing'
      },
      onResults: () => {},
      onGiveUp: () => {},
      env: f.env,
    })

    f.setActive(false)
    await f.advance(60_000)
    expect(calls).toEqual([])

    f.setActive(true)
    await f.wake()
    expect(calls).toEqual([60_000])
    // Schedule restarted from the beginning: next check 3 s later.
    await f.advance(3_000)
    expect(calls).toEqual([60_000, 63_000])
  })

  it('stop() ends everything', async () => {
    const f = fakeEnv()
    const calls: number[] = []
    const stop = startStatusPolling({
      ids: () => ['a'],
      check: async () => {
        calls.push(1)
        return 'processing'
      },
      onResults: () => {},
      onGiveUp: () => {},
      env: f.env,
    })
    stop()
    await f.advance(60_000)
    await f.wake()
    expect(calls).toEqual([])
  })
})

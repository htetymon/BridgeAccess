import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getDeviceId, randomUuid, resetDeviceIdCacheForTests } from './deviceId'

const V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

function memoryStorage(initial: Record<string, string> = {}) {
  const data = new Map(Object.entries(initial))
  return {
    getItem: (k: string) => data.get(k) ?? null,
    setItem: (k: string, v: string) => void data.set(k, v),
    data,
  }
}

describe('getDeviceId', () => {
  beforeEach(() => resetDeviceIdCacheForTests())
  afterEach(() => vi.unstubAllGlobals())

  it('creates a v4 UUID on first launch and persists it', () => {
    const storage = memoryStorage()
    vi.stubGlobal('localStorage', storage)

    const id = getDeviceId()
    expect(id).toMatch(V4)
    expect(storage.data.get('bridgeaccess.deviceId')).toBe(id)
    expect(getDeviceId()).toBe(id)
  })

  it('reuses the stored ID on the next launch', () => {
    vi.stubGlobal('localStorage', memoryStorage({ 'bridgeaccess.deviceId': '11111111-2222-4333-8444-555555555555' }))
    expect(getDeviceId()).toBe('11111111-2222-4333-8444-555555555555')
  })

  it('replaces a corrupted stored value', () => {
    const storage = memoryStorage({ 'bridgeaccess.deviceId': 'not-a-uuid' })
    vi.stubGlobal('localStorage', storage)
    const id = getDeviceId()
    expect(id).toMatch(V4)
    expect(storage.data.get('bridgeaccess.deviceId')).toBe(id)
  })

  it('still works when storage is blocked', () => {
    vi.stubGlobal('localStorage', {
      getItem: () => {
        throw new Error('SecurityError')
      },
      setItem: () => {
        throw new Error('SecurityError')
      },
    })
    const id = getDeviceId()
    expect(id).toMatch(V4)
    expect(getDeviceId()).toBe(id)
  })
})

describe('randomUuid', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('falls back to getRandomValues without randomUUID (plain-HTTP LAN testing)', () => {
    const realCrypto = globalThis.crypto
    vi.stubGlobal('crypto', { getRandomValues: (a: Uint8Array<ArrayBuffer>) => realCrypto.getRandomValues(a) })
    const ids = new Set(Array.from({ length: 50 }, () => randomUuid()))
    expect(ids.size).toBe(50)
    for (const id of ids) expect(id).toMatch(V4)
  })
})

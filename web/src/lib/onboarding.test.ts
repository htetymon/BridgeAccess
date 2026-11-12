import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { hasCompletedOnboarding, markOnboardingComplete, resetOnboardingForTests } from './onboarding'

function memoryStorage(initial: Record<string, string> = {}) {
  const data = new Map(Object.entries(initial))
  return {
    getItem: (k: string) => data.get(k) ?? null,
    setItem: (k: string, v: string) => void data.set(k, v),
    data,
  }
}

describe('onboarding flag', () => {
  beforeEach(() => resetOnboardingForTests())
  afterEach(() => vi.unstubAllGlobals())

  it('is not complete on first launch', () => {
    vi.stubGlobal('localStorage', memoryStorage())
    expect(hasCompletedOnboarding()).toBe(false)
  })

  it('persists completion for the next launch', () => {
    const storage = memoryStorage()
    vi.stubGlobal('localStorage', storage)
    markOnboardingComplete()
    expect(storage.data.get('bridgeaccess.onboardingDone')).toBe('1')

    resetOnboardingForTests() // simulate a fresh launch
    expect(hasCompletedOnboarding()).toBe(true)
  })

  it('remembers completion for the session when storage is blocked', () => {
    const blocked = () => {
      throw new Error('SecurityError')
    }
    vi.stubGlobal('localStorage', { getItem: blocked, setItem: blocked })
    expect(hasCompletedOnboarding()).toBe(false)
    markOnboardingComplete()
    expect(hasCompletedOnboarding()).toBe(true)
  })
})

import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PackageSummary } from './api/packages'
import { readSavedPreview, savePreview } from './explorePreview'

const item: PackageSummary = {
  id: 'p',
  title: 'Renewable Energy Basics',
  subject: 'Science',
  resourceType: 'video',
  packageType: 'with_visual',
  sizeBytes: 850_000,
  publishedAt: '2026-11-08T10:15:00Z',
  isDownloaded: false,
  isOwn: false,
}

afterEach(() => vi.unstubAllGlobals())

function memoryStorage() {
  const data = new Map<string, string>()
  return { getItem: (k: string) => data.get(k) ?? null, setItem: (k: string, v: string) => void data.set(k, v) }
}

describe('saved Explore preview', () => {
  it('round-trips the last list', () => {
    vi.stubGlobal('localStorage', memoryStorage())
    expect(readSavedPreview()).toBeNull()
    savePreview([item])
    expect(readSavedPreview()).toEqual([item])
  })

  it('survives blocked or corrupted storage', () => {
    vi.stubGlobal('localStorage', {
      getItem: () => {
        throw new Error('blocked')
      },
      setItem: () => {
        throw new Error('blocked')
      },
    })
    expect(() => savePreview([item])).not.toThrow()
    expect(readSavedPreview()).toBeNull()

    vi.stubGlobal('localStorage', { getItem: () => '{not json', setItem: () => {} })
    expect(readSavedPreview()).toBeNull()
  })
})

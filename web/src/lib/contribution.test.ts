import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { ApiError } from './api/client'
import {
  clearDraft,
  describeCreateError,
  EMPTY_DRAFT,
  imageProblem,
  loadDraft,
  pollDelay,
  POLL_GIVE_UP_MS,
  saveDraft,
  toNewPackage,
  validateDraft,
  type Draft,
} from './contribution'

const valid: Draft = {
  resourceType: 'text',
  originalTitle: 'Renewable Energy 101',
  author: 'Laura Mitchell',
  content: '',
  resourceLink: 'https://example.org/renewable',
}

describe('validateDraft', () => {
  it('accepts a complete text draft', () => {
    expect(validateDraft(valid)).toEqual({})
  })

  it('requires title, author and link, ignoring spaces', () => {
    expect(validateDraft({ ...valid, originalTitle: '  ', author: '', resourceLink: ' ' })).toEqual({
      originalTitle: 'This field is required.',
      author: 'This field is required.',
      resourceLink: 'This field is required.',
    })
  })

  it('checks lengths with the server limits', () => {
    const errors = validateDraft({
      ...valid,
      originalTitle: 'x'.repeat(301),
      author: 'y'.repeat(201),
      content: 'z'.repeat(10_001),
    })
    expect(errors.originalTitle).toBe('Use at most 300 characters.')
    expect(errors.author).toBe('Use at most 200 characters.')
    expect(errors.content).toBe('Use at most 10,000 characters.')
  })

  it('needs an http(s) link', () => {
    expect(validateDraft({ ...valid, resourceLink: 'example.org/page' }).resourceLink).toMatch(/http/)
    expect(validateDraft({ ...valid, resourceLink: 'ftp://example.org' }).resourceLink).toMatch(/http/)
  })

  it('needs a YouTube link for a video', () => {
    const video = { ...valid, resourceType: 'video' as const }
    expect(validateDraft(video).resourceLink).toBe('Enter a YouTube link (youtube.com or youtu.be).')
    expect(validateDraft({ ...video, resourceLink: 'https://www.youtube.com/watch?v=abc' })).toEqual({})
    expect(validateDraft({ ...video, resourceLink: 'https://youtu.be/abc' })).toEqual({})
    expect(validateDraft({ ...video, resourceLink: 'https://m.youtube.com/watch?v=abc' })).toEqual({})
    expect(validateDraft({ ...video, resourceLink: 'https://notyoutube.com/x' }).resourceLink).toBeDefined()
  })
})

describe('toNewPackage', () => {
  it('trims fields and leaves out empty content and alt texts', () => {
    expect(toNewPackage({ ...valid, originalTitle: ' T ', content: '   ' }, ['', ' '])).toEqual({
      resourceType: 'text',
      originalTitle: 'T',
      author: 'Laura Mitchell',
      resourceLink: 'https://example.org/renewable',
    })
  })

  it('keeps alt texts in image order when any is given', () => {
    expect(toNewPackage({ ...valid, content: ' Text ' }, ['', ' Wind turbines ']).imageAltTexts).toEqual([
      '',
      'Wind turbines',
    ])
    expect(toNewPackage({ ...valid, content: ' Text ' }).content).toBe('Text')
  })
})

describe('imageProblem', () => {
  it('accepts small PNG, JPG and WebP', () => {
    for (const type of ['image/png', 'image/jpeg', 'image/webp']) {
      expect(imageProblem(new Blob([new Uint8Array(10)], { type }))).toBeNull()
    }
  })

  it('refuses other types and anything over 500 KB', () => {
    expect(imageProblem(new Blob([new Uint8Array(10)], { type: 'image/gif' }))).toMatch(/PNG, JPG, or WebP/)
    expect(imageProblem(new Blob([new Uint8Array(512_001)], { type: 'image/png' }))).toMatch(/500 KB/)
  })
})

describe('draft', () => {
  const store = new Map<string, string>()
  beforeEach(() => {
    store.clear()
    globalThis.localStorage = {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
      removeItem: (k: string) => void store.delete(k),
    } as Storage
  })
  afterEach(() => {
    // @ts-expect-error test cleanup
    delete globalThis.localStorage
  })

  it('round-trips and clears', () => {
    saveDraft({ ...valid, resourceType: 'video' })
    expect(loadDraft()).toEqual({ ...valid, resourceType: 'video' })
    clearDraft()
    expect(loadDraft()).toEqual(EMPTY_DRAFT)
  })

  it('does not store an empty draft', () => {
    saveDraft(EMPTY_DRAFT)
    expect(store.size).toBe(0)
  })

  it('survives broken or tampered storage', () => {
    store.set('bridgeaccess.createDraft', '{not json')
    expect(loadDraft()).toEqual(EMPTY_DRAFT)
    store.set('bridgeaccess.createDraft', JSON.stringify({ resourceType: 'audio', originalTitle: 5 }))
    expect(loadDraft()).toEqual(EMPTY_DRAFT)
  })

  it('works without storage at all', () => {
    // @ts-expect-error simulate private mode
    delete globalThis.localStorage
    expect(() => saveDraft(valid)).not.toThrow()
    expect(loadDraft()).toEqual(EMPTY_DRAFT)
  })
})

describe('pollDelay', () => {
  it('follows 3, 5, 8, then 10 seconds', () => {
    expect([0, 1, 2, 3, 4, 9].map((a) => pollDelay(a, 0))).toEqual([3000, 5000, 8000, 10000, 10000, 10000])
  })

  it('stops after about 3 minutes', () => {
    expect(pollDelay(5, POLL_GIVE_UP_MS - 1)).toBe(10000)
    expect(pollDelay(5, POLL_GIVE_UP_MS)).toBeNull()
  })
})

describe('describeCreateError', () => {
  const api = (init: Partial<ConstructorParameters<typeof ApiError>[0]>) =>
    new ApiError({ kind: 'http', message: 'msg', requestId: 'r', ...init })

  it('treats our own duplicate as success', () => {
    expect(
      describeCreateError(api({ status: 409, code: 'DUPLICATE_LINK', extras: { existingPackageId: 'p1', isOwn: true } })),
    ).toEqual({ kind: 'created', packageId: 'p1' })
  })

  it("points to someone else's package for the same link", () => {
    const outcome = describeCreateError(
      api({ status: 409, code: 'DUPLICATE_LINK', extras: { existingPackageId: 'p2', isOwn: false } }),
    )
    expect(outcome).toMatchObject({ kind: 'error', existingPackageId: 'p2', fieldErrors: { resourceLink: expect.any(String) } })
  })

  it('maps validation details to fields', () => {
    const outcome = describeCreateError(
      api({
        status: 400,
        code: 'VALIDATION_FAILED',
        details: [
          { field: 'resourceLink', code: 'NOT_YOUTUBE', message: 'Enter a YouTube link.' },
          { field: 'imageAltTexts', code: 'TOO_MANY', message: 'One alt text per image.' },
        ],
      }),
    )
    expect(outcome).toMatchObject({
      kind: 'error',
      fieldErrors: { resourceLink: 'Enter a YouTube link.', images: 'One alt text per image.' },
    })
  })

  it('explains the daily limit and keeps the draft', () => {
    const outcome = describeCreateError(
      api({ status: 429, code: 'DAILY_LIMIT_REACHED', extras: { limit: 5, resetsAt: '2026-11-14T00:00:00Z' } }),
    )
    expect(outcome.kind === 'error' && outcome.message).toMatch(/today's limit of 5 packages.*draft is saved/)
  })

  it('puts image errors on the images field', () => {
    expect(describeCreateError(api({ status: 413, code: 'IMAGE_TOO_LARGE', message: 'Too big.' }))).toMatchObject({
      fieldErrors: { images: 'Too big.' },
    })
  })

  it('reassures about the draft when offline or slow', () => {
    for (const kind of ['offline', 'timeout'] as const) {
      const outcome = describeCreateError(new ApiError({ kind, message: 'x', requestId: 'r' }))
      expect(outcome.kind === 'error' && outcome.message).toMatch(/draft is saved/i)
    }
  })
})

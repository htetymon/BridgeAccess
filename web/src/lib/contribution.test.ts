import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { ApiError } from './api/client'
import {
  clearDraft,
  contentCounter,
  describeCreateError,
  describeRetryError,
  EMPTY_DRAFT,
  imageProblem,
  isYouTubeLink,
  loadDraft,
  normalizeLink,
  pollDelay,
  POLL_GIVE_UP_MS,
  saveDraft,
  statusAnnouncement,
  toNewPackage,
  validateDraft,
  validateField,
  youTubeVideoId,
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

  it('requires title, author and link, ignoring spaces, in the words of each form', () => {
    expect(validateDraft({ ...valid, originalTitle: '  ', author: '', resourceLink: ' ' })).toEqual({
      originalTitle: 'Enter the original title.',
      author: 'Enter the author.',
      resourceLink: 'Enter the link to the resource.',
    })
    expect(
      validateDraft({ ...valid, resourceType: 'video', originalTitle: '', author: '', resourceLink: '' }),
    ).toEqual({
      originalTitle: 'Enter the video title.',
      author: 'Enter the channel name.',
      resourceLink: 'Enter the YouTube video link.',
    })
  })

  it('content is optional', () => {
    expect(validateField('content', { ...valid, content: '' })).toBeUndefined()
  })

  it('checks lengths with the server limits and says how much to remove', () => {
    const errors = validateDraft({
      ...valid,
      originalTitle: 'x'.repeat(301),
      author: 'y'.repeat(201),
      content: 'z'.repeat(10_523),
    })
    expect(errors.originalTitle).toBe('Use at most 300 characters.')
    expect(errors.author).toBe('Use at most 200 characters.')
    expect(errors.content).toBe('Use at most 10,000 characters. Remove 523.')
    expect(validateField('content', { ...valid, content: 'z'.repeat(10_000) })).toBeUndefined()
  })

  it('needs an http(s) link', () => {
    expect(validateDraft({ ...valid, resourceLink: 'example.org/page' }).resourceLink).toMatch(/http/)
    expect(validateDraft({ ...valid, resourceLink: 'ftp://example.org' }).resourceLink).toMatch(/http/)
  })

  it('needs a YouTube video link for a video', () => {
    const video = { ...valid, resourceType: 'video' as const }
    expect(validateDraft(video).resourceLink).toBe('Enter a YouTube link (youtube.com or youtu.be).')
    expect(validateDraft({ ...video, resourceLink: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ' })).toEqual({})
    expect(validateDraft({ ...video, resourceLink: 'https://notyoutube.com/x' }).resourceLink).toMatch(/YouTube link/)
    expect(validateDraft({ ...video, resourceLink: 'https://www.youtube.com/@channel' }).resourceLink).toBe(
      "This YouTube link isn't a video. Open the video and copy its link.",
    )
  })

  it('a text resource may use any web link, YouTube included', () => {
    expect(validateDraft({ ...valid, resourceLink: 'https://youtu.be/dQw4w9WgXcQ' })).toEqual({})
  })
})

describe('youTubeVideoId', () => {
  const id = 'dQw4w9WgXcQ'

  it.each([
    `https://www.youtube.com/watch?v=${id}`,
    `https://youtube.com/watch?feature=share&v=${id}&t=42`,
    `https://m.youtube.com/watch?v=${id}`,
    `https://music.youtube.com/watch?v=${id}`,
    `http://youtu.be/${id}`,
    `https://youtu.be/${id}?si=abc`,
    `https://www.youtube.com/shorts/${id}`,
    `https://www.youtube.com/embed/${id}`,
    `https://www.youtube.com/live/${id}`,
    `  https://WWW.YOUTUBE.COM/watch?v=${id}  `,
  ])('finds the video in %s', (link) => {
    expect(youTubeVideoId(link)).toBe(id)
  })

  it.each([
    'https://www.youtube.com/',
    'https://www.youtube.com/@SomeChannel',
    'https://www.youtube.com/playlist?list=PL123',
    'https://www.youtube.com/watch?v=short',
    'https://youtu.be/',
    `https://youtube.com.evil.example/watch?v=${id}`,
    `https://notyoutube.com/watch?v=${id}`,
    `ftp://youtube.com/watch?v=${id}`,
    'not a url',
  ])('finds no video in %s', (link) => {
    expect(youTubeVideoId(link)).toBeNull()
  })
})

describe('normalizeLink', () => {
  it('adds https:// to bare addresses', () => {
    expect(normalizeLink(' www.example.org/page ')).toBe('https://www.example.org/page')
    expect(normalizeLink('example.org')).toBe('https://example.org')
    expect(normalizeLink('youtu.be/dQw4w9WgXcQ')).toBe('https://youtu.be/dQw4w9WgXcQ')
    expect(normalizeLink('//example.org/a')).toBe('https://example.org/a')
  })

  it('leaves links with a scheme, and non-links, for validation to explain', () => {
    expect(normalizeLink('http://example.org')).toBe('http://example.org')
    expect(normalizeLink('ftp://example.org')).toBe('ftp://example.org')
    expect(normalizeLink('my notes')).toBe('my notes')
    expect(normalizeLink('mailto:a@b.org')).toBe('mailto:a@b.org')
    expect(normalizeLink('')).toBe('')
  })
})

describe('isYouTubeLink', () => {
  it('spots YouTube hosts only', () => {
    expect(isYouTubeLink('https://www.youtube.com/watch?v=x')).toBe(true)
    expect(isYouTubeLink('https://youtu.be/x')).toBe(true)
    expect(isYouTubeLink('https://example.org/youtube.com/')).toBe(false)
  })
})

describe('contentCounter', () => {
  it('counts quietly far from the limit', () => {
    expect(contentCounter(0)).toEqual({ text: '0/10,000', state: 'ok', announce: null })
    expect(contentCounter(8_999).state).toBe('ok')
  })

  it('warns from 90 % and says how much is left', () => {
    expect(contentCounter(9_000)).toEqual({ text: '9,000/10,000', state: 'near', announce: '1,000 characters left.' })
    expect(contentCounter(10_000).state).toBe('near')
  })

  it('goes red over the limit and says by how much', () => {
    expect(contentCounter(10_250)).toEqual({
      text: '10,250/10,000',
      state: 'over',
      announce: '250 characters over the limit.',
    })
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

describe('describeRetryError', () => {
  const api = (init: Partial<ConstructorParameters<typeof ApiError>[0]>) =>
    new ApiError({ kind: 'http', message: 'msg', requestId: 'r', ...init })

  it('stops offering Retry when the server refuses for good', () => {
    expect(
      describeRetryError(api({ status: 409, code: 'NOT_RETRYABLE', message: "This package can't be retried again." })),
    ).toEqual({ message: "This package can't be retried again.", canRetry: false })
  })

  it('points to the package that took the link', () => {
    expect(
      describeRetryError(api({ status: 409, code: 'DUPLICATE_LINK', extras: { existingPackageId: 'p9', isOwn: false } })),
    ).toMatchObject({ canRetry: false, existingPackageId: 'p9' })
  })

  it('drops a package deleted elsewhere', () => {
    expect(describeRetryError(api({ status: 404, code: 'NOT_FOUND' }))).toMatchObject({ gone: true, canRetry: false })
  })

  it('keeps Retry for passing trouble', () => {
    expect(describeRetryError(new ApiError({ kind: 'offline', message: 'x', requestId: 'r' }))).toEqual({
      message: "You're offline. Connect to the internet to retry.",
      canRetry: true,
    })
    expect(describeRetryError(api({ status: 503, message: 'Busy' })).canRetry).toBe(true)
  })
})

describe('statusAnnouncement', () => {
  const processing = { status: 'processing', title: null, originalTitle: 'Renewable Energy 101', failureReason: null }

  it('announces a finished package by its new title', () => {
    expect(statusAnnouncement(processing, { ...processing, status: 'published', title: 'Clean Energy Basics' })).toBe(
      '“Clean Energy Basics” is ready.',
    )
  })

  it('announces a failure with its reason', () => {
    expect(
      statusAnnouncement(processing, { ...processing, status: 'failed', failureReason: 'This took too long. Please try again.' }),
    ).toBe('“Renewable Energy 101” could not be built. This took too long. Please try again.')
  })

  it('says nothing when nothing changed', () => {
    expect(statusAnnouncement(processing, processing)).toBeNull()
  })
})

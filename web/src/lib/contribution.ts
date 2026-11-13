import { ApiError } from './api/client'
import type { NewPackage } from './api/contributions'
import type { ResourceType } from './api/packages'

/**
 * Create a Package rules and helpers (FR-5.2 to FR-5.7, API doc POST /packages). The limits
 * mirror the server's (CreatePackageValidator); the server checks again, so these exist to
 * give instant feedback and save a round trip on a slow connection.
 */

export const LIMITS = {
  titleChars: 300,
  authorChars: 200,
  contentChars: 10_000,
  linkChars: 2_048,
  maxImages: 3,
  maxImageBytes: 512_000,
} as const

export const ACCEPTED_IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp'] as const

const HTTP_RE = /^https?:\/\/\S+$/i
const YOUTUBE_RE = /^https?:\/\/([a-z0-9-]+\.)?(youtube\.com|youtu\.be)\//i

export type Draft = {
  resourceType: ResourceType
  originalTitle: string
  author: string
  content: string
  resourceLink: string
}

export type DraftField = keyof Draft | 'images'

export type FieldErrors = Partial<Record<DraftField, string>>

export const EMPTY_DRAFT: Draft = { resourceType: 'text', originalTitle: '', author: '', content: '', resourceLink: '' }

/** Labels follow the designs: a video asks for Video Title and Channel Name. */
export function fieldLabels(type: ResourceType) {
  return type === 'video'
    ? { title: 'Video Title', author: 'Channel Name', content: 'Transcript', link: 'Enter the video URL (e.g., YouTube)' }
    : {
        title: 'Original Title of the Article or Resource',
        author: 'Author',
        content: 'Content',
        link: 'Enter the URL (e.g., https://...)',
      }
}

/** Same messages as the server, so a field reads the same whichever side caught it. */
export function validateDraft(draft: Draft): FieldErrors {
  const errors: FieldErrors = {}
  const required = 'This field is required.'
  const title = draft.originalTitle.trim()
  const author = draft.author.trim()
  const link = draft.resourceLink.trim()

  if (!title) errors.originalTitle = required
  else if (title.length > LIMITS.titleChars) errors.originalTitle = tooLong(LIMITS.titleChars)

  if (!author) errors.author = required
  else if (author.length > LIMITS.authorChars) errors.author = tooLong(LIMITS.authorChars)

  if (draft.content.length > LIMITS.contentChars) errors.content = tooLong(LIMITS.contentChars)

  if (!link) errors.resourceLink = required
  else if (link.length > LIMITS.linkChars) errors.resourceLink = tooLong(LIMITS.linkChars)
  else if (!HTTP_RE.test(link)) errors.resourceLink = 'Enter a link that starts with http:// or https://.'
  else if (draft.resourceType === 'video' && !YOUTUBE_RE.test(link))
    errors.resourceLink = 'Enter a YouTube link (youtube.com or youtu.be).'

  return errors
}

/** Checks an image after on-device compression; null when it is fine. */
export function imageProblem(image: Blob): string | null {
  if (!(ACCEPTED_IMAGE_TYPES as readonly string[]).includes(image.type)) return 'Images must be PNG, JPG, or WebP.'
  if (image.size > LIMITS.maxImageBytes) return 'Images must be 500 KB or smaller.'
  return null
}

export function toNewPackage(draft: Draft, altTexts: string[] = []): NewPackage {
  const content = draft.content.trim()
  const alts = altTexts.map((a) => a.trim())
  return {
    resourceType: draft.resourceType,
    originalTitle: draft.originalTitle.trim(),
    author: draft.author.trim(),
    resourceLink: draft.resourceLink.trim(),
    ...(content ? { content } : {}),
    ...(alts.some(Boolean) ? { imageAltTexts: alts } : {}),
  }
}

// ------------------------------------------------------------------ draft

/**
 * FR-5.9: creating needs a connection, so the text fields are kept on the device and survive
 * going offline, closing the app or a failed upload. Images are not kept (too large for
 * localStorage); the form says so.
 */
const DRAFT_KEY = 'bridgeaccess.createDraft'

export function loadDraft(): Draft {
  try {
    const raw = localStorage.getItem(DRAFT_KEY)
    if (!raw) return EMPTY_DRAFT
    const parsed = JSON.parse(raw) as Partial<Draft>
    return {
      resourceType: parsed.resourceType === 'video' ? 'video' : 'text',
      originalTitle: str(parsed.originalTitle),
      author: str(parsed.author),
      content: str(parsed.content).slice(0, LIMITS.contentChars),
      resourceLink: str(parsed.resourceLink),
    }
  } catch {
    return EMPTY_DRAFT
  }
}

export function saveDraft(draft: Draft) {
  try {
    if (isEmptyDraft(draft)) localStorage.removeItem(DRAFT_KEY)
    else localStorage.setItem(DRAFT_KEY, JSON.stringify(draft))
  } catch {
    // Storage full or blocked (private mode): the draft just isn't kept.
  }
}

export function clearDraft() {
  try {
    localStorage.removeItem(DRAFT_KEY)
  } catch {
    // ignore
  }
}

export function isEmptyDraft(d: Draft) {
  return !d.originalTitle.trim() && !d.author.trim() && !d.content.trim() && !d.resourceLink.trim()
}

// ---------------------------------------------------------------- polling

/** API doc polling guidance: 3, 5, 8, then every 10 s; give up after about 3 minutes. */
export const POLL_DELAYS_MS = [3_000, 5_000, 8_000, 10_000] as const
export const POLL_GIVE_UP_MS = 180_000

/** Delay before poll number `attempt` (0-based), or null once `elapsedMs` passed the limit. */
export function pollDelay(attempt: number, elapsedMs: number): number | null {
  if (elapsedMs >= POLL_GIVE_UP_MS) return null
  return POLL_DELAYS_MS[Math.min(attempt, POLL_DELAYS_MS.length - 1)]
}

// ----------------------------------------------------------- create errors

export type CreateOutcome =
  /** Go to the package: created now, or this device's earlier attempt (isOwn). */
  | { kind: 'created'; packageId: string }
  | { kind: 'error'; message: string; fieldErrors: FieldErrors; existingPackageId?: string }

/** Turns a failed POST /packages into something the form can show (API doc, errors table). */
export function describeCreateError(error: unknown): CreateOutcome {
  if (!(error instanceof ApiError)) {
    return { kind: 'error', message: 'Something went wrong. Please try again.', fieldErrors: {} }
  }
  switch (error.code) {
    case 'DUPLICATE_LINK': {
      const existing = typeof error.extras.existingPackageId === 'string' ? error.extras.existingPackageId : undefined
      // Our own earlier request got through (e.g. it timed out on the way back): success.
      if (error.extras.isOwn === true && existing) return { kind: 'created', packageId: existing }
      return {
        kind: 'error',
        message: 'A package for this link already exists. You can learn from it instead.',
        fieldErrors: { resourceLink: 'This link is already in a package.' },
        existingPackageId: existing,
      }
    }
    case 'VALIDATION_FAILED': {
      const fieldErrors: FieldErrors = {}
      for (const d of error.details ?? []) {
        const field = (d.field === 'imageAltTexts' ? 'images' : d.field) as DraftField | undefined
        if (field && !(field in fieldErrors)) fieldErrors[field] = d.message
      }
      return { kind: 'error', message: 'Some fields need attention.', fieldErrors }
    }
    case 'DAILY_LIMIT_REACHED': {
      const resetsAt = typeof error.extras.resetsAt === 'string' ? new Date(error.extras.resetsAt) : null
      const when =
        resetsAt && !Number.isNaN(resetsAt.getTime())
          ? ` You can create more after ${resetsAt.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}.`
          : ' Try again tomorrow.'
      return {
        kind: 'error',
        message: `You have reached today's limit of ${error.extras.limit ?? 5} packages.${when} Your draft is saved.`,
        fieldErrors: {},
      }
    }
    case 'IMAGE_TOO_LARGE':
    case 'UNSUPPORTED_IMAGE_TYPE':
    case 'TOO_MANY_IMAGES':
      return { kind: 'error', message: error.message, fieldErrors: { images: error.message } }
    default:
      return {
        kind: 'error',
        message:
          error.kind === 'offline'
            ? "You're offline. Your draft is saved; create the package when you're connected."
            : error.kind === 'timeout'
              ? 'The connection is slow and the upload did not finish. Your draft is saved. Please try again.'
              : error.message,
        fieldErrors: {},
      }
  }
}

function tooLong(max: number) {
  return `Use at most ${max.toLocaleString('en-US')} characters.`
}

function str(v: unknown) {
  return typeof v === 'string' ? v : ''
}

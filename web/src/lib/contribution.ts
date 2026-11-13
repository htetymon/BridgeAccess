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

/** Fields checked as the contributor types (FR-5.5); images are checked when picked. */
export type TextField = Exclude<keyof Draft, 'resourceType'>

/**
 * One field's problem, or undefined. Messages say what to do, in the form's own words
 * (Video Title, Channel Name for a video), so they read well right under the field.
 */
export function validateField(field: TextField, draft: Draft): string | undefined {
  const video = draft.resourceType === 'video'
  switch (field) {
    case 'originalTitle': {
      const title = draft.originalTitle.trim()
      if (!title) return video ? 'Enter the video title.' : 'Enter the original title.'
      if (title.length > LIMITS.titleChars) return tooLong(LIMITS.titleChars)
      return undefined
    }
    case 'author': {
      const author = draft.author.trim()
      if (!author) return video ? 'Enter the channel name.' : 'Enter the author.'
      if (author.length > LIMITS.authorChars) return tooLong(LIMITS.authorChars)
      return undefined
    }
    case 'content': {
      const over = draft.content.length - LIMITS.contentChars
      if (over > 0) {
        return `Use at most ${LIMITS.contentChars.toLocaleString('en-US')} characters. Remove ${over.toLocaleString('en-US')}.`
      }
      return undefined
    }
    case 'resourceLink':
      return linkProblem(draft.resourceLink, draft.resourceType)
  }
}

export function validateDraft(draft: Draft): FieldErrors {
  const errors: FieldErrors = {}
  for (const field of ['originalTitle', 'author', 'content', 'resourceLink'] as const) {
    const problem = validateField(field, draft)
    if (problem) errors[field] = problem
  }
  return errors
}

function linkProblem(raw: string, type: ResourceType): string | undefined {
  const link = raw.trim()
  if (!link) return type === 'video' ? 'Enter the YouTube video link.' : 'Enter the link to the resource.'
  if (link.length > LIMITS.linkChars) return tooLong(LIMITS.linkChars)
  if (!HTTP_RE.test(link)) return 'Enter a link that starts with http:// or https://.'
  if (type === 'video') {
    if (!YOUTUBE_RE.test(link)) return 'Enter a YouTube link (youtube.com or youtu.be).'
    if (!youTubeVideoId(link)) return "This YouTube link isn't a video. Open the video and copy its link."
  }
  return undefined
}

const VIDEO_ID_RE = /^[A-Za-z0-9_-]{11}$/

/**
 * The video ID of a YouTube link, or null if the link is not a single video (a channel,
 * playlist or the home page has no transcript to learn from). Knows the usual forms:
 * youtube.com/watch?v=ID, youtu.be/ID, and /shorts/, /embed/, /live/ and /v/ paths, on any
 * youtube.com subdomain (www, m, music). Hosts follow the server's rule.
 */
export function youTubeVideoId(link: string): string | null {
  let url: URL
  try {
    url = new URL(link.trim())
  } catch {
    return null
  }
  if (!/^https?:$/.test(url.protocol)) return null
  const host = url.hostname.toLowerCase()
  let id: string | null | undefined
  if (host === 'youtu.be') {
    id = url.pathname.split('/')[1]
  } else if (host === 'youtube.com' || host.endsWith('.youtube.com')) {
    const [, first, second] = url.pathname.split('/')
    if (first === 'watch') id = url.searchParams.get('v')
    else if (['shorts', 'embed', 'live', 'v'].includes(first)) id = second
  }
  return id && VIDEO_ID_RE.test(id) ? id : null
}

/** For the "this looks like a video" hint on a Text Resource. */
export function isYouTubeLink(link: string): boolean {
  return YOUTUBE_RE.test(link.trim())
}

/**
 * Tidies a link when the field is left: trims it and adds https:// to a bare address such
 * as "www.example.org/page" or "youtu.be/abc", the most common slip on a phone keyboard.
 * Anything else is returned trimmed and left for validation to explain.
 */
export function normalizeLink(raw: string): string {
  const link = raw.trim()
  if (!link || /^[a-z][a-z0-9+.-]*:\/\//i.test(link)) return link
  if (link.startsWith('//')) return `https:${link}`
  if (/^[^\s/:@]+\.[a-z]{2,}([/?#:]|$)/i.test(link)) return `https://${link}`
  return link
}

export type CounterState = 'ok' | 'near' | 'over'

/**
 * Counter text and state: amber from 90 % of the limit, red when over. `announce` is what a
 * screen reader should hear; null while far from the limit, so typing is not narrated.
 */
export function contentCounter(length: number): { text: string; state: CounterState; announce: string | null } {
  const max = LIMITS.contentChars
  const fmt = (n: number) => n.toLocaleString('en-US')
  const text = `${fmt(length)}/${fmt(max)}`
  if (length > max) return { text, state: 'over', announce: `${fmt(length - max)} characters over the limit.` }
  if (length >= max * 0.9) return { text, state: 'near', announce: `${fmt(max - length)} characters left.` }
  return { text, state: 'ok', announce: null }
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
      // Not cut to the limit: over-limit text is shown and explained, never silently lost.
      content: str(parsed.content),
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

// ------------------------------------------------------------ retry errors

export type RetryProblem = {
  message: string
  /** False when tapping Retry again cannot help (budget used up, link taken, not failed). */
  canRetry: boolean
  /** Another package now uses this link: offer to open it. */
  existingPackageId?: string
  /** The package no longer exists (deleted elsewhere): drop it from the list. */
  gone?: boolean
}

/** Turns a failed POST /me/packages/{id}/retry into what the card shows. */
export function describeRetryError(error: unknown): RetryProblem {
  if (!(error instanceof ApiError)) return { message: 'Something went wrong. Please try again.', canRetry: true }
  switch (error.code) {
    case 'NOT_RETRYABLE':
      // The server's message says why: retries used up ("create it again"), or not failed.
      return { message: error.message, canRetry: false }
    case 'DUPLICATE_LINK': {
      const existing = typeof error.extras.existingPackageId === 'string' ? error.extras.existingPackageId : undefined
      return {
        message:
          error.extras.isOwn === true
            ? 'You already have a newer package for this link.'
            : 'Someone has since made a package for this link. You can learn from it instead.',
        canRetry: false,
        existingPackageId: existing,
      }
    }
    case 'NOT_FOUND':
      return { message: 'This package no longer exists.', canRetry: false, gone: true }
    default:
      return {
        message:
          error.kind === 'offline' ? "You're offline. Connect to the internet to retry." : error.message,
        canRetry: true,
      }
  }
}

/** What to read out when polling sees a package finish, or null if nothing changed. */
export function statusAnnouncement(
  before: { status: string; title: string | null; originalTitle: string },
  after: { status: string; title: string | null; originalTitle: string; failureReason: string | null },
): string | null {
  if (before.status === after.status) return null
  if (after.status === 'published') return `“${after.title ?? after.originalTitle}” is ready.`
  if (after.status === 'failed')
    return `“${after.originalTitle}” could not be built.${after.failureReason ? ` ${after.failureReason}` : ''}`
  return null
}

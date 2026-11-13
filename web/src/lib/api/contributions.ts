import { apiRequest } from './client'
import type { Page, PackageType, ResourceType, Subject } from './packages'

/** Types and calls for the contribution endpoints (API doc, section 8). */

export type PackageStatus = 'processing' | 'published' | 'failed'

/** One of this device's packages, in any status. */
export type MyPackage = {
  id: string
  /** AI title; null while processing (show originalTitle). */
  title: string | null
  originalTitle: string
  /** Null while processing. */
  subject: Subject | null
  resourceType: ResourceType
  packageType: PackageType
  status: PackageStatus
  /** Safe to show when failed. */
  failureReason: string | null
  sizeBytes: number
  createdAt: string
  publishedAt: string | null
  isLearnt: boolean
}

export type NewPackage = {
  resourceType: ResourceType
  originalTitle: string
  author: string
  resourceLink: string
  content?: string
  imageAltTexts?: string[]
}

export type CreatedPackage = { id: string; status: 'processing'; createdAt: string }

/**
 * POST /packages (multipart). Never retried automatically: it is not idempotent. A client
 * retry after a timeout is safe anyway, because the server answers DUPLICATE_LINK with
 * isOwn = true, which the form treats as success.
 */
export function createPackage(data: NewPackage, images: Blob[], signal?: AbortSignal) {
  const form = new FormData()
  form.append('data', new Blob([JSON.stringify(data)], { type: 'application/json' }))
  images.forEach((image, i) => form.append('images', image, fileName(image, i)))
  // Up to 1.5 MB of images on a slow connection: allow a long attempt.
  return apiRequest<CreatedPackage>('/packages', { method: 'POST', body: form, timeoutMs: 90_000, signal })
}

export function listMyPackages(
  params: { status?: PackageStatus; limit?: number; cursor?: string } = {},
  signal?: AbortSignal,
) {
  return apiRequest<Page<MyPackage>>('/me/packages', { query: params, signal })
}

export function getMyPackage(packageId: string, signal?: AbortSignal) {
  return apiRequest<MyPackage>(`/me/packages/${encodeURIComponent(packageId)}`, { signal })
}

/** Safe to repeat: a second retry of the same package answers NOT_RETRYABLE. */
export function retryPackage(packageId: string) {
  return apiRequest<{ id: string; status: 'processing' }>(`/me/packages/${encodeURIComponent(packageId)}/retry`, {
    method: 'POST',
  })
}

/** DELETE is idempotent, so the client retries it on network errors. */
export function deletePackage(packageId: string) {
  return apiRequest<void>(`/me/packages/${encodeURIComponent(packageId)}`, { method: 'DELETE' })
}

function fileName(image: Blob, index: number) {
  if (image instanceof File && image.name) return image.name
  const ext = image.type === 'image/png' ? 'png' : image.type === 'image/jpeg' ? 'jpg' : 'webp'
  return `image-${index + 1}.${ext}`
}

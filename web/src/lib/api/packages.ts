import { apiRequest } from './client'

/** Types and calls for the catalogue endpoints (API doc, section 5). */

export type Subject = 'Science' | 'Health' | 'Math' | 'Technology' | 'Language' | 'History' | 'Other'
export type ResourceType = 'text' | 'video'
export type PackageType = 'text_only' | 'with_visual'
export type LearningStep = 'summary' | 'key_concepts' | 'diagram' | 'quiz'

export type Page<T> = { items: T[]; nextCursor: string | null }

export type PackageSummary = {
  id: string
  title: string
  subject: Subject
  resourceType: ResourceType
  packageType: PackageType
  sizeBytes: number
  publishedAt: string
  isDownloaded: boolean
  isOwn: boolean
}

export type ListPackagesParams = {
  q?: string
  subject?: Subject
  resourceType?: ResourceType
  packageType?: PackageType
  excludeMine?: boolean
  limit?: number
  cursor?: string
}

export type PackageBundle = {
  schemaVersion: number
  package: {
    id: string
    title: string
    subject: Subject
    resourceType: ResourceType
    packageType: PackageType
    steps: LearningStep[]
    original: { title: string; author: string; link: string }
    sizeBytes: number
    publishedAt: string
  }
  content: {
    overview: string
    keyTakeaways: string[]
    summary: string
    keyConcepts: { term: string; explanation: string }[]
  }
  quiz: {
    questions: {
      id: string
      position: number
      question: string
      options: string[]
      correctIndex: number
      category: string | null
      explanation: string
    }[]
  }
  images: {
    id: string
    source: 'contributor' | 'ai_diagram'
    position: number
    mimeType: string
    sizeBytes: number
    altText: string | null
    url: string
  }[]
}

export function listPackages(params: ListPackagesParams = {}, signal?: AbortSignal) {
  return apiRequest<Page<PackageSummary>>('/packages', { query: params, signal })
}

export function getBundle(packageId: string, signal?: AbortSignal) {
  // Larger than a list page and part of a download, so allow a longer attempt.
  return apiRequest<PackageBundle>(`/packages/${encodeURIComponent(packageId)}/bundle`, {
    timeoutMs: 30_000,
    signal,
  })
}

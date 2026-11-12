import { Dexie, type EntityTable } from 'dexie'
import type { LearningStep, PackageBundle, PackageType, ResourceType, Subject } from './api/packages'

/**
 * On-device storage (IndexedDB via Dexie) for offline learning (PRD 6.8, API doc 11.1 and 11.3).
 *
 * Tables:
 * - packages:     a small metadata row per package the device knows about (downloaded,
 *                 downloading or bookmarked). Lists (Home, Learn, Bookmarks) read only this
 *                 table, so they never load bundle text or image blobs.
 * - bundles:      the downloaded text payload, exactly as GET /packages/{id}/bundle returned it.
 * - images:       downloaded image files as Blobs.
 * - progress:     learning progress per package. Kept when a download is deleted (FR-3.3).
 * - quizAttempts: finished quizzes, graded on the device (FR-7.5). Kept on delete too.
 * - outbox:       actions waiting to be sent to POST /sync (FR-8.2), oldest first.
 *
 * Timestamps are ISO 8601 UTC strings from `toISOString()`, the same form the API uses. That
 * fixed-width form sorts correctly as a string, so timestamp indexes order by time.
 *
 * Schema changes: never edit a released version's `stores()`. Add `db.version(n + 1)` with
 * the new definition and an `upgrade()` if existing rows need rewriting.
 */

// ------------------------------------------------------------------ tables

export type DownloadStatus = 'downloading' | 'downloaded'

export type LocalPackage = {
  /** Package id (server UUID). */
  id: string
  title: string
  subject: Subject
  resourceType: ResourceType
  packageType: PackageType
  sizeBytes: number
  publishedAt: string
  /**
   * Absent when the package is not on this device (bookmarked only, or deleted). It becomes
   * 'downloaded' in the same transaction that stores the bundle, so a package never counts as
   * downloaded with parts missing (FR-4.4).
   */
  downloadStatus?: DownloadStatus
  /** When the download finished. Sort key for Recent Downloads. */
  downloadedAt?: string
  /** Set while bookmarked (FR-7.10). Sort key for the Bookmarks tab. */
  bookmarkedAt?: string
}

export type LocalBundle = {
  packageId: string
  bundle: PackageBundle
  /** ETag of the bundle response, for conditional re-fetch. */
  etag?: string
  storedAt: string
}

export type LocalImage = {
  /** Image id (server UUID). */
  id: string
  packageId: string
  position: number
  source: PackageBundle['images'][number]['source']
  mimeType: string
  sizeBytes: number
  altText: string | null
  /** The file itself. Stored as soon as it arrives, so a retried download skips it (FR-4.3). */
  blob: Blob
}

export type LocalProgress = {
  packageId: string
  /** Only ever grows: steps are added, never removed (FR-8.3). */
  completedSteps: LearningStep[]
  lastStep: LearningStep | null
  /** Sort key for Continue Learning ("Latest opened"). */
  lastOpenedAt: string
  /** Set from a passing local quiz attempt; the server confirms it on sync (FR-7.6). */
  learntAt: string | null
}

export type QuizAnswer = { questionId: string; selectedIndex: number; isCorrect: boolean }

export type LocalQuizAttempt = {
  /** Client-generated UUID, also sent to the server so a replay is not duplicated. */
  id: string
  packageId: string
  attemptedAt: string
  answers: QuizAnswer[]
  score: number
  totalQuestions: number
  percentage: number
  /** The pass mark in force when the learner took the quiz (from GET /config). */
  passMarkPercent: number
  passed: boolean
}

// ------------------------------------------------------------------ outbox

/** Payloads of the POST /sync operation types (API doc section 9). */
export type SyncOperation =
  | {
      type: 'progress.upsert'
      payload: { packageId: string; completedSteps: LearningStep[]; lastStep: LearningStep | null; lastOpenedAt: string }
    }
  | {
      type: 'quiz.attempt.create'
      payload: {
        id: string
        packageId: string
        attemptedAt: string
        passMarkPercent: number
        answers: { questionId: string; selectedIndex: number }[]
      }
    }
  | { type: 'bookmark.add'; payload: { packageId: string } }
  | { type: 'bookmark.remove'; payload: { packageId: string } }
  | {
      type: 'download.record'
      payload: { packageId: string; status: 'started' | 'completed' | 'failed'; sizeBytes: number; downloadedAt?: string }
    }
  | { type: 'download.remove'; payload: { packageId: string } }

export type SyncOpType = SyncOperation['type']

export type OutboxEntry = SyncOperation & {
  /** Auto-increment key: the order actions happened in, which is the order /sync applies them. */
  seq: number
  /** Client UUID sent as `opId`, used to match a /sync result back to this row. */
  opId: string
  /** When the learner did it (the op's `clientTime`). */
  clientTime: string
  /** Failed send attempts so far, for backoff. */
  attempts: number
  lastError?: string
}

// ------------------------------------------------------------------ database

export const DB_NAME = 'bridgeaccess'

export type BridgeAccessDb = Dexie & {
  packages: EntityTable<LocalPackage, 'id'>
  bundles: EntityTable<LocalBundle, 'packageId'>
  images: EntityTable<LocalImage, 'id'>
  progress: EntityTable<LocalProgress, 'packageId'>
  quizAttempts: EntityTable<LocalQuizAttempt, 'id'>
  outbox: EntityTable<OutboxEntry, 'seq'>
}

/**
 * Index notes (the first entry of each schema string is the primary key):
 * - packages: downloadedAt and bookmarkedAt are only indexed on rows that have them, so
 *   "all downloads" and "all bookmarks" are index scans. subject / resourceType / packageType
 *   serve the Learn filters (FR-3.2).
 * - images: packageId, to load or delete one package's images.
 * - progress: lastOpenedAt for Continue Learning.
 * - quizAttempts: [packageId+attemptedAt] for a package's attempts in time order (latest
 *   result, Review Answers).
 * - outbox: &opId is unique, to find a row from a /sync result; type for coalescing.
 */
export function createDb(name = DB_NAME): BridgeAccessDb {
  const db = new Dexie(name) as BridgeAccessDb
  db.version(1).stores({
    packages: 'id, downloadedAt, bookmarkedAt, subject, resourceType, packageType',
    bundles: 'packageId',
    images: 'id, packageId',
    progress: 'packageId, lastOpenedAt',
    quizAttempts: 'id, packageId, [packageId+attemptedAt]',
    outbox: '++seq, &opId, type',
  })
  return db
}

/** The app's database. Opened lazily by Dexie on first use. */
export const db = createDb()

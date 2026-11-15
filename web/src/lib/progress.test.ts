import 'fake-indexeddb/auto'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { LearningStep } from './api/packages'
import { createDb, type BridgeAccessDb, type LocalProgress } from './db'
import { completeStep, openStep, resumeStep } from './progress'

const PKG = 'p'
const FOUR: LearningStep[] = ['summary', 'key_concepts', 'diagram', 'quiz']
const THREE: LearningStep[] = ['summary', 'key_concepts', 'quiz']

let db: BridgeAccessDb
let n = 0
beforeEach(async () => {
  db = createDb(`progress-test-${n++}`)
  await db.open()
})
afterEach(async () => {
  await db.delete()
})

const progress = (extra: Partial<LocalProgress>): LocalProgress => ({
  packageId: PKG,
  completedSteps: [],
  lastStep: null,
  lastOpenedAt: '2026-11-10T00:00:00.000Z',
  learntAt: null,
  ...extra,
})

describe('resumeStep (FR-3.4)', () => {
  it('opens a new package at its first step', () => {
    expect(resumeStep(FOUR, undefined)).toBe('summary')
  })

  it('returns to the last step opened', () => {
    expect(resumeStep(FOUR, progress({ lastStep: 'diagram', completedSteps: ['summary'] }))).toBe('diagram')
  })

  it('falls back to the first unfinished step when the last one is not in this package', () => {
    expect(resumeStep(THREE, progress({ lastStep: 'diagram', completedSteps: ['summary'] }))).toBe('key_concepts')
  })

  it('falls back to the first step when everything is done', () => {
    expect(resumeStep(THREE, progress({ completedSteps: ['summary', 'key_concepts', 'quiz'] }))).toBe('summary')
  })
})

describe('saving progress', () => {
  it('opening a step sets the resume point and the opened time', async () => {
    await openStep(PKG, 'key_concepts', db)

    const row = (await db.progress.get(PKG))!
    expect(row.lastStep).toBe('key_concepts')
    expect(row.completedSteps).toEqual([])
    expect(Date.parse(row.lastOpenedAt)).not.toBeNaN()
  })

  it('completing adds the step in step order and never removes one', async () => {
    await completeStep(PKG, 'key_concepts', db)
    await completeStep(PKG, 'summary', db)
    await completeStep(PKG, 'summary', db)
    await openStep(PKG, 'diagram', db)

    const row = (await db.progress.get(PKG))!
    expect(row.completedSteps).toEqual(['summary', 'key_concepts'])
    expect(row.lastStep).toBe('diagram')
  })

  it('keeps Learnt when progress is updated', async () => {
    await db.progress.add(progress({ learntAt: '2026-11-09T00:00:00.000Z' }))
    await completeStep(PKG, 'summary', db)
    expect((await db.progress.get(PKG))!.learntAt).toBe('2026-11-09T00:00:00.000Z')
  })

  it('queues one sync action per package, holding the full current state', async () => {
    await completeStep(PKG, 'summary', db)
    await openStep(PKG, 'key_concepts', db)
    await completeStep('other', 'summary', db)

    const queued = await db.outbox.toArray()
    expect(queued.map((q) => [q.type, q.payload])).toEqual([
      [
        'progress.upsert',
        expect.objectContaining({ packageId: PKG, completedSteps: ['summary'], lastStep: 'key_concepts' }),
      ],
      ['progress.upsert', expect.objectContaining({ packageId: 'other', completedSteps: ['summary'] })],
    ])
  })
})

import type { LearningStep } from './api/packages'
import { db as appDb, type BridgeAccessDb, type LocalProgress } from './db'
import { enqueue } from './outbox'

/**
 * Learning progress on the device (FR-3.4, FR-7.4, FR-8.3). Written locally first, so it
 * works offline and the screen updates at once, then queued for POST /sync as the full
 * current state. Steps are only ever added; the resume point is the last step opened.
 */

const ORDER: readonly LearningStep[] = ['summary', 'key_concepts', 'diagram', 'quiz']

function sortSteps(steps: Iterable<LearningStep>): LearningStep[] {
  return [...new Set(steps)].sort((a, b) => ORDER.indexOf(a) - ORDER.indexOf(b))
}

/**
 * Where to open a package (FR-3.4): the last step opened, if this package has it; otherwise
 * the first step not completed yet; otherwise the first step.
 */
export function resumeStep(steps: readonly LearningStep[], progress?: LocalProgress): LearningStep {
  if (progress?.lastStep && steps.includes(progress.lastStep)) return progress.lastStep
  return steps.find((s) => !progress?.completedSteps.includes(s)) ?? steps[0]
}

/** Records that the learner is on `step` now: the resume point and Continue Learning order. */
export function openStep(packageId: string, step: LearningStep, store: BridgeAccessDb = appDb) {
  return update(packageId, (p) => ({ ...p, lastStep: step }), store)
}

/** Mark Complete (FR-7.4). Also makes this step the resume point. */
export function completeStep(packageId: string, step: LearningStep, store: BridgeAccessDb = appDb) {
  return update(
    packageId,
    (p) => ({ ...p, completedSteps: sortSteps([...p.completedSteps, step]), lastStep: step }),
    store,
  )
}

async function update(
  packageId: string,
  change: (p: LocalProgress) => LocalProgress,
  store: BridgeAccessDb,
): Promise<LocalProgress> {
  const now = new Date().toISOString()
  const next = await store.transaction('rw', store.progress, async () => {
    const current = (await store.progress.get(packageId)) ?? {
      packageId,
      completedSteps: [],
      lastStep: null,
      lastOpenedAt: now,
      learntAt: null,
    }
    const updated = { ...change(current), lastOpenedAt: now }
    await store.progress.put(updated)
    return updated
  })
  await enqueue(
    {
      type: 'progress.upsert',
      payload: {
        packageId,
        completedSteps: next.completedSteps,
        lastStep: next.lastStep,
        lastOpenedAt: next.lastOpenedAt,
      },
    },
    store,
  )
  return next
}

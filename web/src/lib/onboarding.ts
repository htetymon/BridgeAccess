/**
 * First-launch onboarding flag (PRD FR-1.2). Once the learner finishes or skips the intro
 * slides, a flag is stored locally so they are not shown again. If storage is unavailable
 * (some private-browsing modes), the flag lives in memory, so the slides are shown once per
 * session instead of on every navigation.
 */

const STORAGE_KEY = 'bridgeaccess.onboardingDone'

let doneInMemory = false

export function hasCompletedOnboarding(): boolean {
  if (doneInMemory) return true
  try {
    return localStorage.getItem(STORAGE_KEY) === '1'
  } catch {
    return false
  }
}

export function markOnboardingComplete() {
  doneInMemory = true
  try {
    localStorage.setItem(STORAGE_KEY, '1')
  } catch {
    // Storage blocked or full: the in-memory flag covers this session.
  }
}

/** Test hook: forget the in-memory flag so the next call re-reads storage. */
export function resetOnboardingForTests() {
  doneInMemory = false
}

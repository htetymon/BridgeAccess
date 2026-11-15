import type { LearningStep, PackageBundle } from './api/packages'
import { db as appDb, type BridgeAccessDb, type LocalProgress, type LocalQuizAttempt, type QuizAnswer } from './db'
import { randomUuid } from './deviceId'
import { enqueue } from './outbox'

/**
 * The quiz, graded on the device with no connection (FR-7.5, FR-7.6). The bundle carries
 * the correct answers. The result is stored at once (with Learnt on a pass) and queued for
 * POST /sync; the server regrades it the same way, so both agree.
 */

/** Default pass mark (FR-7.6). Comes from GET /config once that endpoint exists. */
export const DEFAULT_PASS_MARK_PERCENT = 60

type Question = PackageBundle['quiz']['questions'][number]

export type GradedQuiz = {
  answers: QuizAnswer[]
  score: number
  totalQuestions: number
  /** Two decimals, like the server's numeric(5,2). */
  percentage: number
  passMarkPercent: number
  passed: boolean
}

/** The questions not answered yet, in quiz order (all must be answered, FR-7.5). */
export function unanswered(questions: readonly Question[], selected: Readonly<Record<string, number>>): Question[] {
  return questions.filter((q) => selected[q.id] === undefined)
}

/**
 * Grades every question. Passing is `score / total >= pass mark`, compared in whole numbers
 * so 3 of 5 at a 60 % mark passes exactly, as on the server.
 */
export function gradeQuiz(
  questions: readonly Question[],
  selected: Readonly<Record<string, number>>,
  passMarkPercent = DEFAULT_PASS_MARK_PERCENT,
): GradedQuiz {
  if (questions.length === 0) throw new Error('This package has no quiz.')
  const missing = unanswered(questions, selected)
  if (missing.length > 0) throw new Error(`Answer all ${questions.length} questions before submitting.`)
  const answers = questions.map((q) => ({
    questionId: q.id,
    selectedIndex: selected[q.id],
    isCorrect: selected[q.id] === q.correctIndex,
  }))
  const score = answers.filter((a) => a.isCorrect).length
  const totalQuestions = questions.length
  return {
    answers,
    score,
    totalQuestions,
    percentage: Math.round((score * 10_000) / totalQuestions) / 100,
    passMarkPercent,
    passed: score * 100 >= passMarkPercent * totalQuestions,
  }
}

/**
 * Stores a finished quiz and its effect in one transaction: the attempt, the Quiz step
 * completed, and Learnt on a pass (never removed by a later fail). Then queues the attempt
 * (its client ID makes a resend safe) and the progress for sync.
 */
export async function saveAttempt(
  packageId: string,
  graded: GradedQuiz,
  store: BridgeAccessDb = appDb,
): Promise<LocalQuizAttempt> {
  const attemptedAt = new Date().toISOString()
  const attempt: LocalQuizAttempt = { id: randomUuid(), packageId, attemptedAt, ...graded }
  let progress!: LocalProgress
  await store.transaction('rw', [store.quizAttempts, store.progress], async () => {
    await store.quizAttempts.add(attempt)
    const current = (await store.progress.get(packageId)) ?? {
      packageId,
      completedSteps: [],
      lastStep: null,
      lastOpenedAt: attemptedAt,
      learntAt: null,
    }
    const steps = new Set<LearningStep>([...current.completedSteps, 'quiz'])
    progress = {
      ...current,
      completedSteps: (['summary', 'key_concepts', 'diagram', 'quiz'] as const).filter((s) => steps.has(s)),
      lastStep: 'quiz',
      lastOpenedAt: attemptedAt,
      learntAt: current.learntAt ?? (graded.passed ? attemptedAt : null),
    }
    await store.progress.put(progress)
  })

  await enqueue(
    {
      type: 'quiz.attempt.create',
      payload: {
        id: attempt.id,
        packageId,
        attemptedAt,
        passMarkPercent: graded.passMarkPercent,
        answers: graded.answers.map(({ questionId, selectedIndex }) => ({ questionId, selectedIndex })),
      },
    },
    store,
  )
  await enqueue(
    {
      type: 'progress.upsert',
      payload: {
        packageId,
        completedSteps: progress.completedSteps,
        lastStep: progress.lastStep,
        lastOpenedAt: progress.lastOpenedAt,
      },
    },
    store,
  )
  return attempt
}

/** The package's most recent attempt, if any. */
export async function latestAttempt(packageId: string, store: BridgeAccessDb = appDb) {
  return store.quizAttempts
    .where('[packageId+attemptedAt]')
    .between([packageId, ''], [packageId, '￿'])
    .last()
}

export function resultMessage(passed: boolean, passMarkPercent: number): string {
  return passed
    ? "You've completed this package!"
    : `You need ${passMarkPercent}% to pass. Review the steps and try again.`
}

export type OptionState = 'chosen-correct' | 'chosen-wrong' | 'correct' | 'plain'

export type ReviewItem = {
  question: Question
  /** null when the stored attempt has no answer for it (the quiz changed since: not expected). */
  selectedIndex: number | null
  isCorrect: boolean
  options: { text: string; state: OptionState }[]
}

/**
 * Review Answers (FR-7.7): every question of the quiz with the learner's choice, the right
 * answer and whether it was correct. Correctness is recomputed from the answer key, so it
 * matches the options shown.
 */
export function buildReview(questions: readonly Question[], attempt: Pick<LocalQuizAttempt, 'answers'>): ReviewItem[] {
  const chosen = new Map(attempt.answers.map((a) => [a.questionId, a.selectedIndex]))
  return [...questions]
    .sort((a, b) => a.position - b.position)
    .map((question) => {
      const selectedIndex = chosen.get(question.id) ?? null
      return {
        question,
        selectedIndex,
        isCorrect: selectedIndex === question.correctIndex,
        options: question.options.map((text, i) => ({
          text,
          state:
            i === selectedIndex
              ? i === question.correctIndex
                ? 'chosen-correct'
                : 'chosen-wrong'
              : i === question.correctIndex
                ? 'correct'
                : 'plain',
        })),
      }
    })
}

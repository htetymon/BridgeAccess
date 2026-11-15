import 'fake-indexeddb/auto'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { PackageBundle } from './api/packages'
import { createDb, type BridgeAccessDb } from './db'
import { gradeQuiz, latestAttempt, resultMessage, saveAttempt, unanswered } from './quiz'

type Question = PackageBundle['quiz']['questions'][number]

/** Five questions; the correct answer of question i is i % 4. */
const questions: Question[] = Array.from({ length: 5 }, (_, i) => ({
  id: `q${i}`,
  position: i + 1,
  question: `Question ${i + 1}?`,
  options: ['A', 'B', 'C', 'D'],
  correctIndex: i % 4,
  category: null,
  explanation: 'Because.',
}))

/** Answers with `right` correct ones, the rest wrong. */
function answers(right: number): Record<string, number> {
  return Object.fromEntries(questions.map((q, i) => [q.id, i < right ? q.correctIndex : (q.correctIndex + 1) % 4]))
}

let db: BridgeAccessDb
let n = 0
beforeEach(async () => {
  db = createDb(`quiz-test-${n++}`)
  await db.open()
})
afterEach(async () => {
  await db.delete()
})

describe('gradeQuiz', () => {
  it('scores each answer against the answer key', () => {
    const graded = gradeQuiz(questions, answers(4))
    expect(graded).toMatchObject({ score: 4, totalQuestions: 5, percentage: 80, passMarkPercent: 60, passed: true })
    expect(graded.answers.map((a) => a.isCorrect)).toEqual([true, true, true, true, false])
  })

  it('passes at exactly the pass mark and fails below it', () => {
    expect(gradeQuiz(questions, answers(3)).passed).toBe(true) // 60 %
    expect(gradeQuiz(questions, answers(2)).passed).toBe(false) // 40 %
    expect(gradeQuiz(questions, answers(3), 80).passed).toBe(false)
  })

  it('needs every question answered', () => {
    const partial = answers(5)
    delete partial.q2
    expect(unanswered(questions, partial).map((q) => q.id)).toEqual(['q2'])
    expect(() => gradeQuiz(questions, partial)).toThrow('Answer all 5 questions')
  })

  it('keeps two decimals of the percentage', () => {
    const three = questions.slice(0, 3)
    expect(gradeQuiz(three, { q0: 0, q1: 1, q2: 0 }).percentage).toBe(66.67)
  })
})

describe('saveAttempt', () => {
  it('a pass stores the attempt, completes the Quiz step, grants Learnt and queues both for sync', async () => {
    await db.progress.add({
      packageId: 'p',
      completedSteps: ['key_concepts'],
      lastStep: 'key_concepts',
      lastOpenedAt: '2026-11-10T00:00:00.000Z',
      learntAt: null,
    })

    const attempt = await saveAttempt('p', gradeQuiz(questions, answers(4)), db)

    expect(await db.quizAttempts.get(attempt.id)).toMatchObject({ score: 4, passed: true, packageId: 'p' })
    const progress = (await db.progress.get('p'))!
    expect(progress.completedSteps).toEqual(['key_concepts', 'quiz'])
    expect(progress.learntAt).toBe(attempt.attemptedAt)

    const queued = await db.outbox.toArray()
    expect(queued.map((q) => q.type)).toEqual(['quiz.attempt.create', 'progress.upsert'])
    expect(queued[0].payload).toEqual({
      id: attempt.id,
      packageId: 'p',
      attemptedAt: attempt.attemptedAt,
      passMarkPercent: 60,
      answers: questions.map((q, i) => ({ questionId: q.id, selectedIndex: answers(4)[q.id] ?? i })),
    })
  })

  it('a fail does not grant Learnt, and a later fail never removes it', async () => {
    await saveAttempt('p', gradeQuiz(questions, answers(1)), db)
    expect((await db.progress.get('p'))!.learntAt).toBeNull()

    const pass = await saveAttempt('p', gradeQuiz(questions, answers(5)), db)
    await saveAttempt('p', gradeQuiz(questions, answers(0)), db)
    expect((await db.progress.get('p'))!.learntAt).toBe(pass.attemptedAt)

    // Every attempt is its own sync action (each has its own client ID).
    expect((await db.outbox.where('type').equals('quiz.attempt.create').count())).toBe(3)
  })

  it('latestAttempt is the most recent one for that package', async () => {
    await saveAttempt('p', gradeQuiz(questions, answers(1)), db)
    await new Promise((r) => setTimeout(r, 5))
    const second = await saveAttempt('p', gradeQuiz(questions, answers(5)), db)
    await saveAttempt('other', gradeQuiz(questions, answers(2)), db)

    expect((await latestAttempt('p', db))?.id).toBe(second.id)
    expect(await latestAttempt('none', db)).toBeUndefined()
  })
})

describe('resultMessage', () => {
  it('says what happened and what to do next', () => {
    expect(resultMessage(true, 60)).toBe("You've completed this package!")
    expect(resultMessage(false, 60)).toBe('You need 60% to pass. Review the steps and try again.')
  })
})

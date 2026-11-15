import { useId, useState } from 'react'
import { Link } from 'react-router'
import type { PackageBundle } from '../lib/api/packages'
import type { LocalQuizAttempt } from '../lib/db'
import { gradeQuiz, resultMessage, saveAttempt, unanswered } from '../lib/quiz'
import { AlertIcon, CheckIcon, ChevronLeftIcon, ChevronRightIcon, RefreshIcon } from './Icons'

type Question = PackageBundle['quiz']['questions'][number]

type Props = {
  packageId: string
  questions: Question[]
  /** The latest stored attempt: shown as the result until the learner retakes the quiz. */
  latest?: LocalQuizAttempt
  /** Answers in progress, kept by the viewer so they survive a visit to another step. */
  selected: Record<string, number>
  onSelect: (selected: Record<string, number>) => void
  index: number
  onIndex: (index: number) => void
  /** Previous from the first question goes back to the previous learning step. */
  onPreviousStep?: () => void
}

/**
 * Quiz step (FR-7.5, FR-7.6): one question at a time; Next needs an answer, Submit needs
 * every answer. Graded on the device, stored with Learnt on a pass, queued for sync. The
 * result shows score, percentage, pass or fail and a message; the learner can retake it.
 */
export default function QuizStep({
  packageId,
  questions,
  latest,
  selected,
  onSelect,
  index,
  onIndex,
  onPreviousStep,
}: Props) {
  const [retaking, setRetaking] = useState(false)
  // The attempt just submitted, shown at once (the stored "latest" catches up a moment later).
  const [saved, setSaved] = useState<LocalQuizAttempt | null>(null)
  const result = saved ?? latest
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const legendId = useId()

  if (questions.length === 0) {
    return <p className="empty-state">This package has no quiz.</p>
  }

  if (result && !retaking) {
    return (
      <>
        <QuizResult attempt={result} />
        <div className="viewer__footer">
          <button
            type="button"
            className="button button--secondary"
            onClick={() => {
              onSelect({})
              onIndex(0)
              setSaved(null)
              setRetaking(true)
            }}
          >
            <RefreshIcon size={18} /> Retake quiz
          </button>
          <Link to="/" replace className="button button--primary">
            Done
          </Link>
        </div>
      </>
    )
  }

  const question = questions[Math.min(index, questions.length - 1)]
  const last = index >= questions.length - 1
  const answered = selected[question.id] !== undefined
  const missing = unanswered(questions, selected)

  async function submit() {
    setError(null)
    setSubmitting(true)
    try {
      setSaved(await saveAttempt(packageId, gradeQuiz(questions, selected)))
      setRetaking(false)
      onSelect({})
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Your answers could not be saved. Try again.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <>
      <p className="quiz__intro">Answer all questions to complete this package.</p>

      <fieldset className="quiz-card" aria-describedby={legendId}>
        <p className="quiz-card__count" id={legendId}>
          Question {index + 1} of {questions.length}
        </p>
        <legend className="quiz-card__question">{question.question}</legend>
        <div className="quiz-options">
          {question.options.map((option, i) => (
            <label key={i} className={`quiz-option${selected[question.id] === i ? ' quiz-option--selected' : ''}`}>
              <input
                type="radio"
                name={`q-${question.id}`}
                checked={selected[question.id] === i}
                onChange={() => onSelect({ ...selected, [question.id]: i })}
              />
              <span>{option}</span>
            </label>
          ))}
        </div>
      </fieldset>

      {last && missing.length > 0 && (
        <p className="quiz__hint">
          Answer every question to submit ({missing.length} left: question{' '}
          {missing.map((q) => questions.indexOf(q) + 1).join(', ')}).
        </p>
      )}
      {error && (
        <p className="quiz__error" role="alert">
          {error}
        </p>
      )}

      <div className="viewer__footer">
        <button
          type="button"
          className="button button--secondary"
          onClick={() => (index > 0 ? onIndex(index - 1) : onPreviousStep?.())}
          disabled={index === 0 && !onPreviousStep}
        >
          <ChevronLeftIcon size={18} /> Previous
        </button>
        {last ? (
          <button
            type="button"
            className="button button--primary"
            onClick={submit}
            disabled={missing.length > 0 || submitting}
          >
            {submitting ? 'Submitting…' : 'Submit'}
          </button>
        ) : (
          <button
            type="button"
            className="button button--primary"
            onClick={() => onIndex(index + 1)}
            disabled={!answered}
          >
            Next <ChevronRightIcon size={18} />
          </button>
        )}
      </div>
    </>
  )
}

/** Score, percentage, pass or fail and the completion message (FR-7.6). Also used on the package screen. */
export function QuizResult({ attempt }: { attempt: LocalQuizAttempt }) {
  const reviewUrl = `/packages/${attempt.packageId}/review?attempt=${attempt.id}`
  const percent = Math.round(attempt.percentage)
  return (
    <section className={`quiz-result quiz-result--${attempt.passed ? 'pass' : 'fail'}`} aria-label="Quiz result">
      <div className="quiz-result__top">
        <span className="quiz-result__icon" aria-hidden="true">
          {attempt.passed ? <CheckIcon size={32} /> : <AlertIcon size={32} />}
        </span>
        <div>
          <p className="quiz-result__label">Quiz Result</p>
          <p className="quiz-result__score">
            {attempt.score} / {attempt.totalQuestions} <span aria-hidden="true">•</span>{' '}
            <span className="quiz-result__percent">{percent}%</span>
          </p>
          <span className={`chip ${attempt.passed ? 'chip--success' : 'chip--danger'}`}>
            {attempt.passed ? 'Passed' : 'Not passed'}
          </span>
        </div>
      </div>
      <p className="quiz-result__message" role="status">
        {resultMessage(attempt.passed, attempt.passMarkPercent)}
        {attempt.passed && ' You earned the Learnt badge.'}
      </p>
      <Link to={reviewUrl} className="button button--secondary button--small quiz-result__review">
        Review Answers
      </Link>
    </section>
  )
}

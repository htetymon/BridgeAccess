import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { AlertIcon, CheckIcon, ChevronDownIcon, ChevronLeftIcon, InfoIcon, XIcon } from '../components/Icons'
import { db } from '../lib/db'
import { buildReview, latestAttempt, type OptionState, type ReviewItem } from '../lib/quiz'
import { useLiveQuery } from '../lib/useLiveQuery'

async function readReview(packageId: string, attemptId: string | null) {
  const [stored, attempt] = await Promise.all([
    db.bundles.get(packageId),
    attemptId ? db.quizAttempts.get(attemptId) : latestAttempt(packageId),
  ])
  // An attempt ID from another package is treated as missing.
  return { bundle: stored?.bundle, attempt: attempt?.packageId === packageId ? attempt : undefined }
}

const OPTION_LABEL: Record<OptionState, string> = {
  'chosen-correct': 'Your answer, correct',
  'chosen-wrong': 'Your answer, incorrect',
  correct: 'Correct answer',
  plain: '',
}

/**
 * Review Answers (FR-7.7): every question of a quiz attempt with Correct or Incorrect, the
 * learner's choice, the right answer and the explanation. Reads the stored attempt and
 * bundle, so it works offline. Shows the latest attempt, or `?attempt=<id>`.
 */
export function Component() {
  const { packageId = '' } = useParams()
  const [params] = useSearchParams()
  const attemptId = params.get('attempt')
  const navigate = useNavigate()
  const data = useLiveQuery(() => readReview(packageId, attemptId), `${packageId}:${attemptId}`)
  const resultsUrl = `/packages/${packageId}/learn?step=quiz`

  const header = (
    <div className="review__bar">
      <button
        type="button"
        className="icon-button"
        aria-label="Back to results"
        onClick={() => navigate(resultsUrl, { replace: true })}
      >
        <ChevronLeftIcon />
      </button>
      <h1>Review Answers</h1>
    </div>
  )

  if (data.status === 'loading') {
    return <main className="screen review" aria-busy="true">{header}</main>
  }
  if (data.status === 'error' || !data.value.attempt || !data.value.bundle) {
    const noBundle = data.status === 'ready' && data.value.attempt && !data.value.bundle
    return (
      <main className="screen review">
        <title>Review Answers · BridgeAccess</title>
        {header}
        <div className="list-error" role="alert">
          <p>
            {noBundle
              ? 'Download this package again to review your answers.'
              : 'There is no quiz result to review yet. Take the quiz first.'}
          </p>
          <Link to={`/packages/${packageId}`} className="button button--primary">
            Go to the package
          </Link>
        </div>
      </main>
    )
  }

  const { attempt, bundle } = data.value
  const items = buildReview(bundle.quiz.questions, attempt)
  const percent = Math.round(attempt.percentage)

  return (
    <main className="screen review">
      <title>{`Review Answers · ${bundle.package.title} · BridgeAccess`}</title>
      {header}

      <section
        className={`review-summary review-summary--${attempt.passed ? 'pass' : 'fail'}`}
        aria-label="Quiz result"
      >
        <span className="review-summary__status">
          <span className="review-summary__icon" aria-hidden="true">
            {attempt.passed ? <CheckIcon size={20} /> : <AlertIcon size={20} />}
          </span>
          {attempt.passed ? 'Quiz Passed!' : 'Not passed'}
        </span>
        <span className="review-summary__score">
          {attempt.score} / {attempt.totalQuestions}
        </span>
        <span className="review-summary__percent">{percent}%</span>
      </section>

      <ol className="review-list">
        {items.map((item, i) => (
          <li key={item.question.id}>
            <ReviewCard item={item} number={i + 1} total={items.length} />
          </li>
        ))}
      </ol>

      <div className="viewer__footer">
        <Link to={resultsUrl} replace className="button button--secondary">
          Back to Results
        </Link>
        <Link to="/" replace className="button button--primary">
          Done
        </Link>
      </div>
    </main>
  )
}

/** One question. A native <details>: open by default, collapsible, no script needed. */
function ReviewCard({ item, number, total }: { item: ReviewItem; number: number; total: number }) {
  return (
    <details className="review-card" open>
      <summary className="review-card__head">
        <span className="review-card__count">
          Question {number} of {total}
        </span>
        <span className={`chip ${item.isCorrect ? 'chip--success' : 'chip--danger'}`}>
          {item.isCorrect ? <CheckIcon size={14} /> : <XIcon size={14} />}
          {item.isCorrect ? 'Correct' : 'Incorrect'}
        </span>
        <ChevronDownIcon size={18} />
      </summary>

      <h2 className="review-card__question">{item.question.question}</h2>
      {item.selectedIndex === null && <p className="review-card__note">Not answered.</p>}

      <ul className="review-options">
        {item.options.map((option, i) => (
          <li key={i} className={`review-option review-option--${option.state}`}>
            <span className="review-option__mark" aria-hidden="true" />
            <span>
              {option.text}
              {OPTION_LABEL[option.state] && <span className="visually-hidden"> ({OPTION_LABEL[option.state]})</span>}
            </span>
          </li>
        ))}
      </ul>

      <p className="review-card__explanation">
        <InfoIcon size={18} />
        <span>
          <strong>Explanation:</strong> {item.question.explanation}
        </span>
      </p>
    </details>
  )
}

import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import BlobImage from '../components/BlobImage'
import QuizStep from '../components/QuizStep'
import { CheckIcon, ChevronLeftIcon, ChevronRightIcon, InfoIcon } from '../components/Icons'
import type { LearningStep, PackageBundle } from '../lib/api/packages'
import { db, type LocalImage, type LocalProgress, type LocalQuizAttempt } from '../lib/db'
import { completeStep, openStep, resumeStep } from '../lib/progress'
import { latestAttempt } from '../lib/quiz'
import { useLiveQuery } from '../lib/useLiveQuery'

const STEP_LABEL: Record<LearningStep, string> = {
  summary: 'Details',
  key_concepts: 'Key Concepts',
  diagram: 'Diagram',
  quiz: 'Quiz',
}

type ViewerData = {
  bundle?: PackageBundle
  progress?: LocalProgress
  images: LocalImage[]
  downloaded: boolean
  latest?: LocalQuizAttempt
}

async function readViewer(packageId: string): Promise<ViewerData> {
  const [pkg, stored, progress, images, latest] = await Promise.all([
    db.packages.get(packageId),
    db.bundles.get(packageId),
    db.progress.get(packageId),
    db.images.where('packageId').equals(packageId).sortBy('position'),
    latestAttempt(packageId),
  ])
  return { bundle: stored?.bundle, progress, images, latest, downloaded: pkg?.downloadStatus === 'downloaded' }
}

/**
 * Package viewer (FR-3.4, FR-7.3, FR-7.4): the learning steps of a downloaded package, read
 * from IndexedDB only, so it works offline. Step indicator, N of M progress bar, Previous and
 * Next, Mark Complete with a Completed badge. The current step is in the URL (?step=); opening
 * without one resumes where the learner left off. Every step change and completion is saved
 * on the device at once and queued for sync.
 */
export function Component() {
  const { packageId = '' } = useParams()
  const data = useLiveQuery(() => readViewer(packageId), packageId)

  if (data.status === 'loading') {
    return (
      <main className="screen viewer" aria-busy="true">
        <div className="skeleton-line skeleton-line--title" />
      </main>
    )
  }
  if (data.status === 'error' || !data.value.bundle || !data.value.downloaded) {
    return (
      <main className="screen viewer">
        <title>Learning · BridgeAccess</title>
        <div className="list-error" role="alert">
          <p>Download this package to learn it, even offline.</p>
          <Link to={`/packages/${packageId}`} className="button button--primary">
            Go to the package
          </Link>
        </div>
      </main>
    )
  }
  return (
    <Viewer
      packageId={packageId}
      bundle={data.value.bundle}
      progress={data.value.progress}
      images={data.value.images}
      latest={data.value.latest}
    />
  )
}

function Viewer({
  packageId,
  bundle,
  progress,
  images,
  latest,
}: {
  packageId: string
  bundle: PackageBundle
  progress?: LocalProgress
  images: LocalImage[]
  latest?: LocalQuizAttempt
}) {
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const steps = bundle.package.steps
  const requested = params.get('step') as LearningStep | null
  const step: LearningStep = requested && steps.includes(requested) ? requested : resumeStep(steps, progress)
  const index = steps.indexOf(step)
  const completed = progress?.completedSteps.includes(step) ?? false
  const headingRef = useRef<HTMLHeadingElement>(null)
  const [saving, setSaving] = useState(false)
  const firstRender = useRef(true)
  // Quiz answers in progress live here, so visiting another step does not lose them.
  const [quizAnswers, setQuizAnswers] = useState<Record<string, number>>({})
  const [quizIndex, setQuizIndex] = useState(0)

  // Keep the resolved step in the URL (refresh and Back stay on it), and record it as the
  // resume point. Moving between steps replaces the entry: Back leaves the viewer.
  useEffect(() => {
    if (requested !== step) setParams({ step }, { replace: true })
    void openStep(packageId, step)
    // A new step: start at its top and move focus to its heading for screen readers.
    if (firstRender.current) {
      firstRender.current = false
      return
    }
    window.scrollTo({ top: 0 })
    headingRef.current?.focus()
  }, [packageId, step]) // eslint-disable-line react-hooks/exhaustive-deps

  function go(next: LearningStep) {
    setParams({ step: next }, { replace: true })
  }

  async function markComplete() {
    setSaving(true)
    try {
      await completeStep(packageId, step)
    } finally {
      setSaving(false)
    }
  }

  return (
    <main className="screen viewer">
      <title>{`${STEP_LABEL[step]} · ${bundle.package.title} · BridgeAccess`}</title>

      <div className="viewer__bar">
        <button
          type="button"
          className="icon-button"
          aria-label="Back to the package"
          onClick={() => navigate(`/packages/${packageId}`, { replace: true })}
        >
          <ChevronLeftIcon />
        </button>
      </div>

      <nav aria-label="Learning steps">
        <ol className="step-pills">
          {steps.map((s) => {
            const done = progress?.completedSteps.includes(s) ?? false
            const current = s === step
            return (
              <li key={s}>
                <button
                  type="button"
                  className={`step-pill${current ? ' step-pill--current' : ''}${done ? ' step-pill--done' : ''}`}
                  aria-current={current ? 'step' : undefined}
                  onClick={() => go(s)}
                >
                  <span className="step-pill__mark" aria-hidden="true">
                    {done && <CheckIcon size={14} />}
                  </span>
                  {STEP_LABEL[s]}
                  {done && <span className="visually-hidden"> (completed)</span>}
                </button>
              </li>
            )
          })}
        </ol>
      </nav>

      <div className="viewer__progress">
        <progress max={steps.length} value={index + 1} aria-label={`Step ${index + 1} of ${steps.length}`} />
        <span aria-hidden="true">
          {index + 1} of {steps.length}
        </span>
      </div>

      <p className="viewer__package">{bundle.package.title}</p>
      <div className="viewer__heading">
        <h1 ref={headingRef} tabIndex={-1}>
          {step === 'quiz' ? `Quiz (${bundle.quiz.questions.length} Questions)` : STEP_LABEL[step]}
        </h1>
        {completed && (
          <span className="chip chip--success">
            <CheckIcon size={14} /> Completed
          </span>
        )}
      </div>

      {step === 'quiz' ? (
        <QuizStep
          packageId={packageId}
          questions={[...bundle.quiz.questions].sort((a, b) => a.position - b.position)}
          latest={latest}
          selected={quizAnswers}
          onSelect={setQuizAnswers}
          index={quizIndex}
          onIndex={setQuizIndex}
          onPreviousStep={index > 0 ? () => go(steps[index - 1]) : undefined}
        />
      ) : (
        <StepContent step={step} bundle={bundle} images={images} />
      )}

      {step !== 'quiz' && (
        <div className="mark-complete">
          <InfoIcon size={20} />
          {completed ? (
            <p>You completed this step.</p>
          ) : (
            <>
              <p>Mark this step as complete when you've finished reading and understand the key ideas.</p>
              <button
                type="button"
                className="button button--secondary button--small"
                onClick={markComplete}
                disabled={saving}
              >
                <CheckIcon size={16} /> Mark Complete
              </button>
            </>
          )}
        </div>
      )}

      {step !== 'quiz' && (
        <div className="viewer__footer">
          <button
            type="button"
            className="button button--secondary"
            onClick={() => go(steps[index - 1])}
            disabled={index === 0}
          >
            <ChevronLeftIcon size={18} /> Previous
          </button>
          {index < steps.length - 1 && (
            <button type="button" className="button button--primary" onClick={() => go(steps[index + 1])}>
              Next <ChevronRightIcon size={18} />
            </button>
          )}
        </div>
      )}

      <p className="visually-hidden" role="status">
        {completed ? `${STEP_LABEL[step]} completed` : ''}
      </p>
    </main>
  )
}

function StepContent({ step, bundle, images }: { step: LearningStep; bundle: PackageBundle; images: LocalImage[] }) {
  if (step === 'summary') {
    return (
      <div className="step-body">
        {paragraphs(bundle.content.summary).map((p, i) => (
          <p key={i}>{p}</p>
        ))}
      </div>
    )
  }
  if (step === 'key_concepts') {
    return (
      <dl className="step-body concepts">
        {bundle.content.keyConcepts.map((c, i) => (
          <div key={i} className="concepts__item">
            <dt>{c.term}</dt>
            <dd>{c.explanation}</dd>
          </div>
        ))}
      </dl>
    )
  }
  if (step === 'diagram') {
    return (
      <div className="step-body diagrams">
        {images.length === 0 && (
          <p>The images of this package are not on this device. Download it again to see them.</p>
        )}
        {images.map((image) => (
          <figure key={image.id} className="diagrams__item">
            <BlobImage blob={image.blob} alt={image.altText ?? ''} className="diagrams__image" />
            {image.altText && <figcaption>{image.altText}</figcaption>}
          </figure>
        ))}
      </div>
    )
  }
  return null // the quiz is rendered by QuizStep
}

/** The Details text is plain text; blank lines separate paragraphs. */
function paragraphs(text: string): string[] {
  return text
    .split(/\n\s*\n/)
    .map((p) => p.trim())
    .filter(Boolean)
}

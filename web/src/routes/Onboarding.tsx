import { useRef, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { BookIcon, UploadIcon } from '../components/Icons'
import { markOnboardingComplete } from '../lib/onboarding'

type Slide = { title: string; body: ReactNode; art: ReactNode }

/** Copy follows the PRD slide titles (FR-1.1). Audio is deferred, so it is not mentioned. */
const SLIDES: Slide[] = [
  {
    title: 'Learn more, Use less',
    body: (
      <p className="onboarding__text">
        Study with lightweight learning packages. Download once, then learn anywhere, even without an
        internet connection.
      </p>
    ),
    art: <OfflineArt />,
  },
  {
    title: 'Create or Explore Learning Packages',
    body: (
      <p className="onboarding__text">
        Turn articles and YouTube videos into packages with a summary, key concepts and a quiz, or download
        packages other learners have made.
      </p>
    ),
    art: <PackageArt />,
  },
  {
    title: 'Two Ways to Use BridgeAccess',
    body: (
      <ul className="onboarding__ways">
        <li className="onboarding__way onboarding__way--learn">
          <BookIcon />
          <div>
            <strong>Learn</strong>
            <span>Download packages and study offline.</span>
          </div>
        </li>
        <li className="onboarding__way onboarding__way--contribute">
          <UploadIcon />
          <div>
            <strong>Contribute</strong>
            <span>Share what you know by creating packages.</span>
          </div>
        </li>
      </ul>
    ),
    art: <TwoWaysArt />,
  },
]

/** Swipes shorter than this (px) are treated as taps. */
const SWIPE_MIN = 50

/**
 * Three intro slides with Skip, Next and Get Started (FR-1.1). Finishing or skipping stores
 * the onboarding flag and replaces this screen with Home, so Back does not return here.
 */
export function Component() {
  const navigate = useNavigate()
  const [index, setIndex] = useState(0)
  const touchStartX = useRef<number | null>(null)
  const last = index === SLIDES.length - 1
  const slide = SLIDES[index]

  const finish = () => {
    markOnboardingComplete()
    navigate('/', { replace: true })
  }
  const go = (next: number) => setIndex(Math.min(Math.max(next, 0), SLIDES.length - 1))

  return (
    <main
      className="onboarding"
      onTouchStart={(e) => (touchStartX.current = e.touches[0].clientX)}
      onTouchEnd={(e) => {
        if (touchStartX.current === null) return
        const dx = e.changedTouches[0].clientX - touchStartX.current
        touchStartX.current = null
        if (Math.abs(dx) >= SWIPE_MIN) go(index + (dx < 0 ? 1 : -1))
      }}
    >
      <title>Welcome to BridgeAccess</title>

      <div className="onboarding__top">
        {!last && (
          <button type="button" className="onboarding__skip" onClick={finish}>
            Skip
          </button>
        )}
      </div>

      <section
        className="onboarding__slide"
        key={index}
        aria-roledescription="slide"
        aria-label={`${index + 1} of ${SLIDES.length}`}
        aria-live="polite"
      >
        <div className="onboarding__art">{slide.art}</div>
        <h1 className="onboarding__title">{slide.title}</h1>
        {slide.body}
      </section>

      <div className="onboarding__footer">
        <div className="onboarding__dots" aria-hidden="true">
          {SLIDES.map((s, i) => (
            <span key={s.title} className={i === index ? 'onboarding__dot onboarding__dot--active' : 'onboarding__dot'} />
          ))}
        </div>
        <button
          type="button"
          className="button button--primary button--block"
          onClick={last ? finish : () => go(index + 1)}
        >
          {last ? 'Get Started' : 'Next'}
        </button>
      </div>
    </main>
  )
}

/* Illustrations: inline SVG built from the design tokens, nothing extra to download. */

function ArtFrame({ children }: { children: ReactNode }) {
  return (
    <svg viewBox="0 0 200 160" width="200" height="160" aria-hidden="true" focusable="false">
      <circle cx="100" cy="80" r="72" fill="var(--color-primary-soft)" />
      {children}
    </svg>
  )
}

function OfflineArt() {
  return (
    <ArtFrame>
      {/* phone */}
      <rect x="70" y="30" width="60" height="100" rx="10" fill="var(--color-surface)" stroke="var(--color-ink)" strokeWidth="3" />
      <rect x="80" y="48" width="40" height="6" rx="3" fill="var(--color-primary)" />
      <rect x="80" y="60" width="32" height="5" rx="2.5" fill="var(--color-border)" />
      <rect x="80" y="70" width="36" height="5" rx="2.5" fill="var(--color-border)" />
      {/* download badge */}
      <circle cx="132" cy="108" r="18" fill="var(--color-accent)" />
      <path d="M132 99v16m-6-6 6 6 6-6" fill="none" stroke="#fff" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" />
    </ArtFrame>
  )
}

function PackageArt() {
  return (
    <ArtFrame>
      {/* box */}
      <path d="M60 62 100 44l40 18v46l-40 18-40-18z" fill="var(--color-surface)" stroke="var(--color-ink)" strokeWidth="3" strokeLinejoin="round" />
      <path d="M60 62l40 18 40-18M100 80v46" fill="none" stroke="var(--color-ink)" strokeWidth="3" strokeLinejoin="round" />
      {/* sparkles: AI summary and quiz */}
      <path d="M150 34v14m-7-7h14" stroke="var(--color-contribute)" strokeWidth="3" strokeLinecap="round" />
      <path d="M48 40v10m-5-5h10" stroke="var(--color-primary)" strokeWidth="3" strokeLinecap="round" />
    </ArtFrame>
  )
}

function TwoWaysArt() {
  return (
    <ArtFrame>
      <rect x="44" y="50" width="52" height="60" rx="12" fill="var(--color-surface)" stroke="var(--color-primary)" strokeWidth="3" />
      <path d="M70 70c-4-3-9-4-16-4v26c7 0 12 1 16 4 4-3 9-4 16-4V66c-7 0-12 1-16 4zm0 0v26" fill="none" stroke="var(--color-primary)" strokeWidth="2.5" strokeLinejoin="round" />
      <rect x="104" y="50" width="52" height="60" rx="12" fill="var(--color-surface)" stroke="var(--color-contribute)" strokeWidth="3" />
      <path d="M130 92V68m-8 8 8-8 8 8M118 90v4h24v-4" fill="none" stroke="var(--color-contribute)" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
    </ArtFrame>
  )
}

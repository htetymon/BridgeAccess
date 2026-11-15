import { useEffect, useId } from 'react'
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router'
import BlobImage from '../components/BlobImage'
import DownloadControl from '../components/DownloadControl'
import { QuizResult } from '../components/QuizStep'
import {
  BookIcon,
  CalendarIcon,
  CheckIcon,
  ChevronLeftIcon,
  ExternalLinkIcon,
  FileTextIcon,
  ImageIcon,
  InfoIcon,
  LayersIcon,
  LinkIcon,
  PlayIcon,
  SparkleIcon,
  StorageIcon,
  UserIcon,
} from '../components/Icons'
import { formatBytes, formatDate, PACKAGE_TYPE_LABEL, RESOURCE_TYPE_LABEL } from '../lib/format'
import { usePackageScreen, type Cover, type PackageView } from '../lib/packageScreen'
import { useOnline } from '../lib/useOnline'

type Tab = 'learn' | 'details'

/**
 * Package screen (screens 12 and 13, FR-7.1, FR-7.2, FR-7.9): header with Downloaded and
 * Learnt badges, cover image, and two tabs. Learn has the overview, key takeaways and Start
 * Learning (or Download, when the package is not on the device yet). Details has the
 * original title, author, a link to the original, category, type, date and size.
 * A downloaded package renders from IndexedDB only, so the screen works offline.
 */
export function Component() {
  const { packageId = '' } = useParams()
  const state = usePackageScreen(packageId)
  const online = useOnline()
  const [params, setParams] = useSearchParams()
  const tab: Tab = params.get('tab') === 'details' ? 'details' : 'learn'

  // Back online after an offline error: load again without a tap.
  const retry = state.status === 'error' ? state.retry : undefined
  useEffect(() => {
    if (online && retry) retry()
  }, [online]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <main className="screen package-screen">
      <BackBar />

      {state.status === 'loading' && (
        <div className="package-screen__loading" role="status" aria-label="Loading package">
          <div className="skeleton-line skeleton-line--title" />
          <div className="skeleton-line skeleton-line--short" />
        </div>
      )}

      {state.status === 'error' && (
        <div className="list-error" role="alert">
          <title>Package · BridgeAccess</title>
          <p>{state.message}</p>
          {state.retry && online && (
            <button type="button" className="button button--primary" onClick={state.retry}>
              Try again
            </button>
          )}
          <Link to="/learn" className="button button--secondary">
            Go to your packages
          </Link>
        </div>
      )}

      {state.status === 'ready' && (
        <PackageBody
          view={state.view}
          cover={state.cover}
          tab={tab}
          onTab={(next) => setParams(next === 'learn' ? {} : { tab: next }, { replace: true })}
        />
      )}
    </main>
  )
}

function PackageBody({
  view,
  cover,
  tab,
  onTab,
}: {
  view: PackageView
  cover: Cover
  tab: Tab
  onTab: (tab: Tab) => void
}) {
  const Icon = view.resourceType === 'video' ? PlayIcon : FileTextIcon
  const ids = useId()
  const tabId = (t: Tab) => `${ids}-tab-${t}`
  const panelId = (t: Tab) => `${ids}-panel-${t}`

  return (
    <>
      <title>{`${view.title} · BridgeAccess`}</title>

      <header className="package-hero">
        <div className={`package-hero__icon package-card__icon--${view.resourceType}`}>
          <Icon size={36} />
        </div>
        <div className="package-hero__text">
          <h1 className="package-hero__title">{view.title}</h1>
          <p className="package-hero__type">{RESOURCE_TYPE_LABEL[view.resourceType]} Resource</p>
          <ul className="chips" aria-label="Details and status">
            <li className="chip chip--subject">{view.subject}</li>
            <li className="chip">{PACKAGE_TYPE_LABEL[view.packageType]}</li>
            {view.downloaded && (
              <li className="chip chip--success">
                <CheckIcon size={14} /> Downloaded
              </li>
            )}
            {view.learnt && (
              <li className="chip chip--success">
                <CheckIcon size={14} /> Learnt
              </li>
            )}
          </ul>
        </div>
      </header>

      {cover && <CoverImage cover={cover} alt={view.coverAlt ?? ''} />}

      <div className="segmented" role="tablist" aria-label="Package">
        {(['learn', 'details'] as const).map((t) => (
          <button
            key={t}
            type="button"
            role="tab"
            id={tabId(t)}
            aria-selected={tab === t}
            aria-controls={panelId(t)}
            tabIndex={tab === t ? 0 : -1}
            className={`segmented__option${tab === t ? ' segmented__option--active' : ''}`}
            onClick={() => onTab(t)}
            onKeyDown={(e) => {
              // Arrow keys move between the two tabs (WAI-ARIA tabs pattern).
              if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
                const next = t === 'learn' ? 'details' : 'learn'
                onTab(next)
                document.getElementById(tabId(next))?.focus()
              }
            }}
          >
            {t === 'learn' ? <BookIcon size={20} /> : <InfoIcon size={20} />}
            {t === 'learn' ? 'Learn' : 'Details'}
          </button>
        ))}
      </div>

      {tab === 'learn' ? (
        <section role="tabpanel" id={panelId('learn')} aria-labelledby={tabId('learn')} className="package-panel">
          <h2>Overview</h2>
          <p>{view.overview}</p>

          {view.keyTakeaways.length > 0 && (
            <>
              <h2>Key Takeaways</h2>
              <ul className="takeaways">
                {view.keyTakeaways.map((t, i) => (
                  <li key={i}>
                    <span className="takeaways__check" aria-hidden="true">
                      <CheckIcon size={14} />
                    </span>
                    {t}
                  </li>
                ))}
              </ul>
            </>
          )}

          <p className="ai-note">
            <SparkleIcon size={16} />
            <span>
              Generated by AI from the original resource.{' '}
              <a href={view.original.link} target="_blank" rel="noopener noreferrer">
                Read the original
              </a>
              .
            </span>
          </p>

          {view.latest && <QuizResult attempt={view.latest} />}

          <div className="package-screen__action">
            {view.downloaded ? (
              <Link to={`/packages/${view.id}/learn`} className="button button--primary button--block">
                <BookIcon size={20} /> Start Learning
              </Link>
            ) : (
              <>
                <p className="package-screen__hint">Download this package to learn it, even offline.</p>
                <DownloadControl packageId={view.id} title={view.title} sizeBytes={view.sizeBytes} />
              </>
            )}
          </div>
        </section>
      ) : (
        <section role="tabpanel" id={panelId('details')} aria-labelledby={tabId('details')} className="package-panel">
          <dl className="details-list">
            <Detail icon={<BookIcon size={20} />} label="Original Resource Title" value={view.original.title} />
            <Detail icon={<UserIcon size={20} />} label="Author" value={view.original.author} />
            <div className="details-list__row">
              <span className="details-list__icon" aria-hidden="true">
                <LinkIcon size={20} />
              </span>
              <div>
                <dt>Resource Link</dt>
                <dd>
                  <a
                    className="details-list__link"
                    href={view.original.link}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    Open original resource <ExternalLinkIcon size={18} />
                    <span className="visually-hidden"> (opens in a new tab)</span>
                  </a>
                </dd>
              </div>
            </div>
            <Detail icon={<LayersIcon size={20} />} label="Category" value={view.subject} />
            <Detail icon={<ImageIcon size={20} />} label="Package Type" value={PACKAGE_TYPE_LABEL[view.packageType]} />
            {view.downloadedAt ? (
              <Detail icon={<CalendarIcon size={20} />} label="Date Downloaded" value={formatDate(view.downloadedAt)} />
            ) : (
              <Detail icon={<CalendarIcon size={20} />} label="Date Published" value={formatDate(view.publishedAt)} />
            )}
            <Detail icon={<StorageIcon size={20} />} label="Package Size" value={formatBytes(view.sizeBytes)} />
          </dl>
        </section>
      )}
    </>
  )
}

/** The cover: a server URL, or a stored image for a downloaded package. */
function CoverImage({ cover, alt }: { cover: NonNullable<Cover>; alt: string }) {
  return 'blob' in cover ? (
    <BlobImage blob={cover.blob} className="package-cover" alt={alt} />
  ) : (
    <img className="package-cover" src={cover.url} alt={alt} decoding="async" />
  )
}

function Detail({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="details-list__row">
      <span className="details-list__icon" aria-hidden="true">
        {icon}
      </span>
      <div>
        <dt>{label}</dt>
        <dd>{value}</dd>
      </div>
    </div>
  )
}

/** Back to wherever the learner came from; to Learn when the screen was opened directly. */
function BackBar() {
  const navigate = useNavigate()
  const location = useLocation()
  return (
    <div className="package-screen__bar">
      <button
        type="button"
        className="icon-button"
        aria-label="Back"
        onClick={() => (location.key === 'default' ? navigate('/learn', { replace: true }) : navigate(-1))}
      >
        <ChevronLeftIcon />
      </button>
    </div>
  )
}

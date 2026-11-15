import { Link } from 'react-router'
import { BookIcon, ChevronRightIcon, UploadIcon } from '../components/Icons'
import LocalPackageCard from '../components/LocalPackageCard'
import PackageCard, { PackageCardSkeleton } from '../components/PackageCard'
import Section from '../components/Section'
import { useExplorePreview } from '../lib/explorePreview'
import { continueLearning, recentDownloads } from '../lib/localLibrary'
import { useLiveQuery } from '../lib/useLiveQuery'
import { useOnline } from '../lib/useOnline'

/** Items shown per section; See All opens the full list. */
const CONTINUE_COUNT = 1
const RECENT_COUNT = 3

/**
 * Home (screen 4, FR-2.1 to FR-2.6). Continue Learning and Recent Downloads come from this
 * device's IndexedDB and update live (after a download, a delete or a finished quiz), so they
 * work with no connection. The Explore preview needs the server; offline it shows the list
 * from the last visit, and its Download buttons wait for a connection.
 */
export function Component() {
  const continuing = useLiveQuery(() => continueLearning(CONTINUE_COUNT))
  const downloads = useLiveQuery(() => recentDownloads(RECENT_COUNT))

  return (
    <>
      <title>BridgeAccess</title>
      <div className="brand">
        <BookIcon filled size={28} />
        <span>
          Bridge<span className="brand__accent">Access</span>
        </span>
      </div>

      <header className="page-header">
        <h1>What would you like to do today?</h1>
        <p className="page-header__subtitle">Downloaded lessons work without a connection.</p>
      </header>

      <div className="quick-actions">
        <Link to="/learn" className="quick-action quick-action--learn">
          <BookIcon /> <span>Learn</span> <ChevronRightIcon size={18} />
        </Link>
        <Link to="/contribute" className="quick-action quick-action--contribute">
          <UploadIcon /> <span>Contribute</span> <ChevronRightIcon size={18} />
        </Link>
      </div>

      <Section title="Continue Learning" seeAllTo="/learn/continue" empty="Packages you start will appear here.">
        {continuing.status === 'loading' ? (
          <Loading />
        ) : continuing.status === 'ready' && continuing.value.length > 0 ? (
          <ul className="package-list" aria-label="Continue Learning">
            {continuing.value.map(({ pkg, percent }) => (
              <li key={pkg.id}>
                <LocalPackageCard variant="continue" pkg={pkg} percent={percent} />
              </li>
            ))}
          </ul>
        ) : undefined}
      </Section>

      <Section title="Recent Downloads" seeAllTo="/learn/downloads" empty="Downloaded packages will appear here.">
        {downloads.status === 'loading' ? (
          <Loading />
        ) : downloads.status === 'ready' && downloads.value.length > 0 ? (
          <ul className="package-list" aria-label="Recent Downloads">
            {downloads.value.map(({ pkg, learnt }) => (
              <li key={pkg.id}>
                <LocalPackageCard variant="download" pkg={pkg} learnt={learnt} />
              </li>
            ))}
          </ul>
        ) : undefined}
      </Section>

      <ExplorePreviewSection />
    </>
  )
}

function ExplorePreviewSection() {
  const preview = useExplorePreview()
  const online = useOnline()

  return (
    <Section
      title="Explore and Download Packages"
      seeAllTo="/explore"
      empty={online ? 'No packages yet. Check back soon.' : 'Connect to the internet to explore packages.'}
    >
      {preview.status === 'loading' ? (
        <Loading />
      ) : preview.status === 'error' ? (
        <div className="empty-state section-error" role="alert">
          <p>{preview.message}</p>
          <button type="button" className="button button--secondary button--small" onClick={preview.retry}>
            Try again
          </button>
        </div>
      ) : preview.items.length > 0 ? (
        <>
          {preview.stale && (
            <p className="section__note">
              {online ? "Couldn't refresh. Showing packages from your last visit." : "You're offline. Showing packages from your last visit."}
            </p>
          )}
          <ul className="package-list" aria-label="Explore preview">
            {preview.items.map((pkg) => (
              <li key={pkg.id}>
                <PackageCard pkg={pkg} />
              </li>
            ))}
          </ul>
        </>
      ) : undefined}
    </Section>
  )
}

function Loading() {
  return (
    <div className="package-list" role="status" aria-label="Loading">
      <PackageCardSkeleton />
    </div>
  )
}

import { Link } from 'react-router'
import LocalPackageCard from '../components/LocalPackageCard'
import { PackageCardSkeleton } from '../components/PackageCard'
import PageHeader from '../components/PageHeader'
import Section from '../components/Section'
import { allDownloads, continueLearning, deleteDownload } from '../lib/localLibrary'
import { useLiveQuery } from '../lib/useLiveQuery'

/** Items shown per section on the tab; See All opens the full list with search and filters. */
const CONTINUE_COUNT = 1
const DOWNLOADS_COUNT = 5

/**
 * Learn tab (screen 5, FR-3.1, FR-3.3): Continue Learning and Downloaded Packages, from this
 * device's IndexedDB only, so it works offline and updates live. Deleting a download asks
 * first and keeps the learner's progress.
 */
export function Component() {
  const continuing = useLiveQuery(() => continueLearning(CONTINUE_COUNT))
  const downloads = useLiveQuery(allDownloads)

  return (
    <>
      <title>Learn · BridgeAccess</title>
      <PageHeader title="Learn" subtitle="Study and manage your learning packages." />

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

      <Section title="Downloaded Packages" seeAllTo="/learn/downloads">
        {downloads.status === 'loading' ? (
          <Loading />
        ) : downloads.status === 'ready' && downloads.value.length > 0 ? (
          <>
            <ul className="package-list" aria-label="Downloaded Packages">
              {downloads.value.slice(0, DOWNLOADS_COUNT).map(({ pkg, learnt }) => (
                <li key={pkg.id}>
                  <LocalPackageCard variant="download" pkg={pkg} learnt={learnt} onDelete={deleteDownload} />
                </li>
              ))}
            </ul>
            {downloads.value.length > DOWNLOADS_COUNT && (
              <Link to="/learn/downloads" className="button button--secondary button--block load-more">
                See all {downloads.value.length} downloads
              </Link>
            )}
          </>
        ) : (
          <div className="empty-state section-error">
            <p>No downloads yet. Explore packages to download one.</p>
            <Link to="/explore" className="button button--secondary button--small">
              Explore packages
            </Link>
          </div>
        )}
      </Section>
    </>
  )
}

function Loading() {
  return (
    <div className="package-list" role="status" aria-label="Loading">
      <PackageCardSkeleton />
    </div>
  )
}

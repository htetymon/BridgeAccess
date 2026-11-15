import { Link } from 'react-router'
import LocalPackageCard from '../components/LocalPackageCard'
import { PackageCardSkeleton } from '../components/PackageCard'
import PageHeader from '../components/PageHeader'
import { listBookmarks, toggleBookmark } from '../lib/bookmarks'
import { useLiveQuery } from '../lib/useLiveQuery'

/**
 * Bookmarks tab (FR-7.10): bookmarked packages, newest first, from this device's IndexedDB,
 * so it works offline. A bookmarked package that is not downloaded still opens its package
 * screen, which needs a connection (or a copy the app saw before) to show it.
 */
export function Component() {
  const bookmarks = useLiveQuery(listBookmarks)

  return (
    <>
      <title>Bookmarks · BridgeAccess</title>
      <PageHeader title="Bookmarks" subtitle="Packages you saved for later." />

      {bookmarks.status === 'loading' && (
        <div className="package-list" role="status" aria-label="Loading bookmarks">
          <PackageCardSkeleton />
        </div>
      )}

      {bookmarks.status === 'error' && (
        <p className="empty-state" role="alert">
          Your bookmarks could not be read from this device. Restart the app and try again.
        </p>
      )}

      {bookmarks.status === 'ready' &&
        (bookmarks.value.length === 0 ? (
          <div className="empty-state section-error">
            <p>Tap the bookmark on any package to save it here.</p>
            <Link to="/explore" className="button button--secondary button--small">
              Explore packages
            </Link>
          </div>
        ) : (
          <ul className="package-list" aria-label="Bookmarks">
            {bookmarks.value.map(({ pkg, learnt }) => (
              <li key={pkg.id}>
                <LocalPackageCard
                  variant="bookmark"
                  pkg={pkg}
                  learnt={learnt}
                  onUnbookmark={(p) => void toggleBookmark(p)}
                />
              </li>
            ))}
          </ul>
        ))}

      <p className="visually-hidden" role="status">
        {bookmarks.status === 'ready' ? `${bookmarks.value.length} bookmarked packages` : ''}
      </p>
    </>
  )
}

import { Link } from 'react-router'
import { UploadIcon } from '../components/Icons'
import { MyPackageList } from '../components/MyPackageCard'
import { PackageCardSkeleton } from '../components/PackageCard'
import PageHeader from '../components/PageHeader'
import { useMyPackages } from '../lib/api/useMyPackages'
import { PAGE_SIZE } from '../lib/api/usePackageList'

/**
 * My Contributions, See All: every package this device created, in every status except
 * deleted, newest first, 20 at a time (FR-4.5). Same cards and actions as the Contribute tab.
 */
export function Component() {
  const mine = useMyPackages(PAGE_SIZE)

  return (
    <main className="screen">
      <title>My Contributions · BridgeAccess</title>
      <PageHeader title="My Contributions" back={{ fallback: '/contribute' }} />

      {mine.status === 'loading' && (
        <div className="package-list" role="status" aria-label="Loading your packages">
          {Array.from({ length: 4 }, (_, i) => (
            <PackageCardSkeleton key={i} />
          ))}
        </div>
      )}

      {mine.status === 'error' && (
        <div className="list-error" role="alert">
          <p>{mine.error?.message}</p>
          <button type="button" className="button button--primary" onClick={mine.reload}>
            Try again
          </button>
        </div>
      )}

      {(mine.status === 'ready' || mine.status === 'loadingMore') &&
        (mine.items.length === 0 ? (
          <div className="empty-state section-error">
            <p>You haven't created any packages yet.</p>
            <Link to="/contribute/new" className="button button--primary button--small">
              <UploadIcon size={18} /> Create a Package
            </Link>
          </div>
        ) : (
          <>
            <MyPackageList items={mine.items} stalled={mine.stalled} onRetry={mine.retry} onDelete={mine.remove} />
            {mine.error && (
              <p className="list-more-error" role="alert">
                {mine.error.message}
              </p>
            )}
            {mine.hasMore && (
              <button
                type="button"
                className="button button--secondary button--block load-more"
                onClick={mine.loadMore}
                disabled={mine.status === 'loadingMore'}
              >
                {mine.status === 'loadingMore' ? 'Loading…' : mine.error ? 'Try again' : 'Load more'}
              </button>
            )}
          </>
        ))}
    </main>
  )
}

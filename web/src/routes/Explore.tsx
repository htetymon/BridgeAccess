import PageHeader from '../components/PageHeader'
import PackageCard, { PackageCardSkeleton } from '../components/PackageCard'
import { usePackageList } from '../lib/api/usePackageList'

/**
 * Explore and Download Packages (screen 8): the published catalogue from GET /packages,
 * newest first, 20 at a time with Load more (FR-4.5). Search, filters and the Download button
 * come next (FR-4.1 to FR-4.3).
 */
export function Component() {
  const list = usePackageList()

  return (
    <main className="screen">
      <title>Explore · BridgeAccess</title>
      <PageHeader title="Explore and Download Packages" back={{ fallback: '/' }} />

      {list.status === 'loading' && (
        <div className="package-list" role="status" aria-label="Loading packages">
          {Array.from({ length: 4 }, (_, i) => (
            <PackageCardSkeleton key={i} />
          ))}
        </div>
      )}

      {list.status === 'error' && (
        <div className="list-error" role="alert">
          <p>{list.error?.message}</p>
          <button type="button" className="button button--primary" onClick={list.retry}>
            Try again
          </button>
        </div>
      )}

      {(list.status === 'ready' || list.status === 'loadingMore') &&
        (list.items.length === 0 ? (
          <p className="empty-state">No packages yet. Check back soon, or contribute the first one.</p>
        ) : (
          <>
            <ul className="package-list" aria-label="Packages">
              {list.items.map((pkg) => (
                <li key={pkg.id}>
                  <PackageCard pkg={pkg} />
                </li>
              ))}
            </ul>

            <p className="visually-hidden" role="status">
              {list.status === 'loadingMore' ? 'Loading more packages' : `Showing ${list.items.length} packages`}
            </p>

            {list.error && (
              <p className="list-more-error" role="alert">
                {list.error.message}
              </p>
            )}

            {list.hasMore && (
              <button
                type="button"
                className="button button--secondary button--block load-more"
                onClick={list.loadMore}
                disabled={list.status === 'loadingMore'}
              >
                {list.status === 'loadingMore' ? 'Loading…' : list.error ? 'Try again' : 'Load more'}
              </button>
            )}
          </>
        ))}
    </main>
  )
}

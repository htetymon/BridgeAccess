import { useSearchParams } from 'react-router'
import { FilterSelect, SearchBox, SortSelect } from '../components/ListControls'
import PackageCard, { PackageCardSkeleton } from '../components/PackageCard'
import PageHeader from '../components/PageHeader'
import { usePackageList } from '../lib/api/usePackageList'
import {
  activeFilterCount,
  MAX_QUERY,
  MIN_QUERY,
  noResultsMessage,
  PACKAGE_TYPES,
  readView,
  RESOURCE_TYPES,
  SORTS,
  SUBJECTS,
  toListParams,
  writeView,
  type ExploreView,
} from '../lib/explore'

/**
 * Explore and Download Packages (screen 8, FR-4.1, FR-4.2, FR-4.5): the published catalogue
 * from GET /packages with search, Subject / Resource type / Package type filters and sort,
 * newest first, 20 at a time with Load more. The view lives in the URL, so Back, refresh and
 * shared links keep it. Download comes next (FR-4.3).
 */
export function Component() {
  const [params, setParams] = useSearchParams()
  const view = readView(params)
  const list = usePackageList(toListParams(view))

  function change(next: Partial<ExploreView>) {
    // replace: adjusting filters is not navigation; Back should leave Explore, not undo a filter.
    setParams(writeView({ ...view, ...next }), { replace: true })
  }

  const filtersActive = activeFilterCount(view) > 0

  return (
    <main className="screen">
      <title>Explore · BridgeAccess</title>
      <PageHeader title="Explore and Download Packages" back={{ fallback: '/' }} />

      <search className="explore-controls" aria-label="Search and filter packages">
        <SearchBox
          value={view.q}
          onSearch={(q) => change({ q })}
          placeholder="Search packages..."
          maxLength={MAX_QUERY}
          minLength={MIN_QUERY}
        />

        <div className="filter-row">
          <FilterSelect
            label="Subject"
            value={view.subject ?? ''}
            options={SUBJECTS.map((s) => ({ value: s, label: s }))}
            allLabel="All subjects"
            onChange={(v) => change({ subject: (v || undefined) as ExploreView['subject'] })}
          />
          <FilterSelect
            label="Resource type"
            value={view.resourceType ?? ''}
            options={RESOURCE_TYPES}
            allLabel="All resource types"
            onChange={(v) => change({ resourceType: (v || undefined) as ExploreView['resourceType'] })}
          />
          <FilterSelect
            label="Package type"
            value={view.packageType ?? ''}
            options={PACKAGE_TYPES}
            allLabel="All package types"
            onChange={(v) => change({ packageType: (v || undefined) as ExploreView['packageType'] })}
          />
        </div>

        <div className="sort-row">
          <SortSelect value="latest_published" options={SORTS} onChange={() => {}} />
          {filtersActive && (
            <button
              type="button"
              className="link-button"
              onClick={() => change({ subject: undefined, resourceType: undefined, packageType: undefined })}
            >
              Clear filters
            </button>
          )}
        </div>
      </search>

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
          <div className="empty-state section-error">
            <p>{noResultsMessage(view)}</p>
            {(filtersActive || view.q.trim()) && (
              <button
                type="button"
                className="button button--secondary button--small"
                onClick={() => setParams(new URLSearchParams(), { replace: true })}
              >
                Show all packages
              </button>
            )}
          </div>
        ) : (
          <>
            <ul className="package-list" aria-label="Packages">
              {list.items.map((pkg) => (
                <li key={pkg.id}>
                  <PackageCard pkg={pkg} />
                </li>
              ))}
            </ul>

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

      {/* One polite announcement per result change, instead of narrating the list. */}
      <p className="visually-hidden" role="status">
        {list.status === 'loading'
          ? ''
          : list.status === 'loadingMore'
            ? 'Loading more packages'
            : list.status === 'ready'
              ? list.items.length === 0
                ? noResultsMessage(view)
                : `Showing ${list.items.length}${list.hasMore ? ' or more' : ''} packages`
              : ''}
      </p>
    </main>
  )
}

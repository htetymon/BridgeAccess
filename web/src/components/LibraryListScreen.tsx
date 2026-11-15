import { useSearchParams } from 'react-router'
import { PACKAGE_TYPES, RESOURCE_TYPES, SUBJECTS } from '../lib/explore'
import {
  applyLibraryView,
  LIBRARY_SORTS,
  libraryFilterCount,
  MAX_LIBRARY_QUERY,
  readLibraryView,
  writeLibraryView,
  type LibraryItem,
  type LibrarySort,
  type LibraryView,
} from '../lib/libraryView'
import { deleteDownload } from '../lib/localLibrary'
import { useLiveQuery } from '../lib/useLiveQuery'
import { FilterSelect, SearchBox, SortSelect } from './ListControls'
import LocalPackageCard from './LocalPackageCard'
import { PackageCardSkeleton } from './PackageCard'
import PageHeader from './PageHeader'

type Props = {
  variant: 'continue' | 'download'
  title: string
  defaultSort: LibrarySort
  load: () => Promise<LibraryItem[]>
  /** Shown when the device has nothing for this list at all. */
  emptyMessage: string
}

/**
 * Continue Learning and Recent Downloads full lists (screens 6 and 7, FR-3.2): search,
 * Subject / Resource type / Package type filters and Latest opened / Latest downloaded sort,
 * all over local data, so the whole screen works offline. The list updates live (a delete,
 * a finished download). Search reacts quickly: no server round trip to wait for.
 */
export default function LibraryListScreen({ variant, title, defaultSort, load, emptyMessage }: Props) {
  const [params, setParams] = useSearchParams()
  const view = readLibraryView(params, defaultSort)
  const items = useLiveQuery(load)

  function change(next: Partial<LibraryView>) {
    setParams(writeLibraryView({ ...view, ...next }, defaultSort), { replace: true })
  }

  const filtersActive = libraryFilterCount(view) > 0
  const shown = items.status === 'ready' ? applyLibraryView(items.value, view) : []
  const narrowed = filtersActive || view.q.trim() !== ''

  return (
    <main className="screen">
      <title>{`${title} · BridgeAccess`}</title>
      <PageHeader title={title} back={{ fallback: '/learn' }} />

      <search className="explore-controls" aria-label={`Search and filter ${title}`}>
        <SearchBox
          value={view.q}
          onSearch={(q) => change({ q })}
          placeholder="Search your packages..."
          maxLength={MAX_LIBRARY_QUERY}
          delayMs={150}
        />
        <div className="filter-row">
          <FilterSelect
            label="Subject"
            value={view.subject ?? ''}
            options={SUBJECTS.map((s) => ({ value: s, label: s }))}
            allLabel="All subjects"
            onChange={(v) => change({ subject: (v || undefined) as LibraryView['subject'] })}
          />
          <FilterSelect
            label="Resource type"
            value={view.resourceType ?? ''}
            options={RESOURCE_TYPES}
            allLabel="All resource types"
            onChange={(v) => change({ resourceType: (v || undefined) as LibraryView['resourceType'] })}
          />
          <FilterSelect
            label="Package type"
            value={view.packageType ?? ''}
            options={PACKAGE_TYPES}
            allLabel="All package types"
            onChange={(v) => change({ packageType: (v || undefined) as LibraryView['packageType'] })}
          />
        </div>
        <div className="sort-row">
          <SortSelect value={view.sort} options={LIBRARY_SORTS} onChange={(sort) => change({ sort })} />
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

      {items.status === 'loading' && (
        <div className="package-list" role="status" aria-label="Loading packages">
          <PackageCardSkeleton />
          <PackageCardSkeleton />
        </div>
      )}

      {items.status === 'error' && (
        <p className="empty-state" role="alert">
          Your packages could not be read from this device. Restart the app and try again.
        </p>
      )}

      {items.status === 'ready' &&
        (shown.length === 0 ? (
          <div className="empty-state section-error">
            <p>{items.value.length === 0 ? emptyMessage : 'No packages match your search and filters.'}</p>
            {narrowed && items.value.length > 0 && (
              <button
                type="button"
                className="button button--secondary button--small"
                onClick={() => setParams(new URLSearchParams(), { replace: true })}
              >
                Show all
              </button>
            )}
          </div>
        ) : (
          <ul className="package-list" aria-label={title}>
            {shown.map((item) => (
              <li key={item.pkg.id}>
                {variant === 'continue' ? (
                  <LocalPackageCard variant="continue" pkg={item.pkg} percent={item.percent} />
                ) : (
                  <LocalPackageCard variant="download" pkg={item.pkg} learnt={item.learnt} onDelete={deleteDownload} />
                )}
              </li>
            ))}
          </ul>
        ))}

      <p className="visually-hidden" role="status">
        {items.status === 'ready' ? `Showing ${shown.length} of ${items.value.length} packages` : ''}
      </p>
    </main>
  )
}

import { useEffect, useId, useRef, useState } from 'react'
import { useSearchParams } from 'react-router'
import { ChevronDownIcon, SearchIcon, SortIcon, XIcon } from '../components/Icons'
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

/** Wait this long after the last key before searching: one request per pause, not per letter. */
const SEARCH_DELAY_MS = 400

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
        <SearchBox value={view.q} onSearch={(q) => change({ q })} />

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
          <label className="sort">
            <SortIcon size={18} />
            <span>Sort:</span>
            <select className="sort__select" value="latest_published" onChange={() => {}}>
              {SORTS.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
            <ChevronDownIcon size={16} />
          </label>
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

/**
 * Search field. Typing updates the field at once and searches after a short pause; Enter
 * searches straight away. One letter is not sent (the server needs two), with a hint.
 */
function SearchBox({ value, onSearch }: { value: string; onSearch: (q: string) => void }) {
  const [text, setText] = useState(value)
  const hintId = useId()
  const latest = useRef(onSearch)
  useEffect(() => {
    latest.current = onSearch
  })

  // Follow the URL when it changes from outside (Back, "Show all packages").
  const [shown, setShown] = useState(value)
  if (value !== shown) {
    setShown(value)
    if (value.trim() !== text.trim()) setText(value)
  }

  useEffect(() => {
    if (text.trim() === value.trim()) return
    const t = setTimeout(() => latest.current(text), SEARCH_DELAY_MS)
    return () => clearTimeout(t)
  }, [text, value])

  const tooShort = text.trim().length > 0 && text.trim().length < MIN_QUERY

  return (
    <form
      className="search-box"
      onSubmit={(e) => {
        e.preventDefault()
        onSearch(text)
        ;(document.activeElement as HTMLElement | null)?.blur() // close the phone keyboard
      }}
    >
      <SearchIcon size={20} />
      <input
        type="search"
        className="search-box__input"
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder="Search packages..."
        aria-label="Search packages"
        aria-describedby={tooShort ? hintId : undefined}
        enterKeyHint="search"
        maxLength={MAX_QUERY}
        autoComplete="off"
      />
      {text && (
        <button
          type="button"
          className="icon-button icon-button--small search-box__clear"
          onClick={() => {
            setText('')
            onSearch('')
          }}
          aria-label="Clear search"
        >
          <XIcon size={18} />
        </button>
      )}
      {tooShort && (
        <p id={hintId} className="search-box__hint">
          Type at least {MIN_QUERY} characters to search.
        </p>
      )}
    </form>
  )
}

/**
 * A filter chip. A native <select> underneath: the phone's own picker, keyboard and screen
 * reader support for free, and nothing extra to download.
 */
function FilterSelect({
  label,
  value,
  options,
  allLabel,
  onChange,
}: {
  label: string
  value: string
  options: readonly { value: string; label: string }[]
  allLabel: string
  onChange: (value: string) => void
}) {
  const selected = options.find((o) => o.value === value)
  return (
    <label className={`filter-chip${selected ? ' filter-chip--active' : ''}`}>
      <span className="filter-chip__text" aria-hidden="true">
        {selected ? selected.label : label}
      </span>
      <ChevronDownIcon size={16} />
      <select
        className="filter-chip__select"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-label={selected ? `${label}: ${selected.label}` : label}
      >
        <option value="">{allLabel}</option>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </label>
  )
}

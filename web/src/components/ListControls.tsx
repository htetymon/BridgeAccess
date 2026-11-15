import { useEffect, useId, useRef, useState } from 'react'
import { ChevronDownIcon, SearchIcon, SortIcon, XIcon } from './Icons'

/** Wait this long after the last key before searching: one search per pause, not per letter. */
const SEARCH_DELAY_MS = 400

/**
 * Search field shared by Explore and the Learn lists. Typing updates the field at once and
 * searches after a short pause; Enter searches straight away. With `minLength`, a shorter
 * term is not sent (Explore: the server needs two characters), with a hint.
 */
export function SearchBox({
  value,
  onSearch,
  placeholder,
  maxLength,
  minLength = 0,
  delayMs = SEARCH_DELAY_MS,
}: {
  value: string
  onSearch: (q: string) => void
  placeholder: string
  maxLength?: number
  minLength?: number
  delayMs?: number
}) {
  const [text, setText] = useState(value)
  const hintId = useId()
  const latest = useRef(onSearch)
  useEffect(() => {
    latest.current = onSearch
  })

  // Follow the URL when it changes from outside (Back, "Show all").
  const [shown, setShown] = useState(value)
  if (value !== shown) {
    setShown(value)
    if (value.trim() !== text.trim()) setText(value)
  }

  useEffect(() => {
    if (text.trim() === value.trim()) return
    const t = setTimeout(() => latest.current(text), delayMs)
    return () => clearTimeout(t)
  }, [text, value, delayMs])

  const tooShort = minLength > 0 && text.trim().length > 0 && text.trim().length < minLength

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
        placeholder={placeholder}
        aria-label={placeholder.replace(/\.+$/, '')}
        aria-describedby={tooShort ? hintId : undefined}
        enterKeyHint="search"
        maxLength={maxLength}
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
          Type at least {minLength} characters to search.
        </p>
      )}
    </form>
  )
}

/**
 * A filter chip. A native <select> underneath: the phone's own picker, keyboard and screen
 * reader support for free, and nothing extra to download.
 */
export function FilterSelect({
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

/** "Sort: …" control, also a native <select>. */
export function SortSelect<T extends string>({
  value,
  options,
  onChange,
}: {
  value: T
  options: readonly { value: T; label: string }[]
  onChange: (value: T) => void
}) {
  return (
    <label className="sort">
      <SortIcon size={18} />
      <span>Sort:</span>
      <select className="sort__select" value={value} onChange={(e) => onChange(e.target.value as T)}>
        {options.map((s) => (
          <option key={s.value} value={s.value}>
            {s.label}
          </option>
        ))}
      </select>
      <ChevronDownIcon size={16} />
    </label>
  )
}

/** Inline SVG icons: no icon library, nothing extra to download. */
export type IconProps = { filled?: boolean; size?: number }

function Svg({ filled, size = 24, children }: IconProps & { children: React.ReactNode }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill={filled ? 'currentColor' : 'none'}
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {children}
    </svg>
  )
}

export const HomeIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z" />
  </Svg>
)

export const BookIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M12 6.5C10.5 5 8 4.5 3 4.5V19c5 0 7.5.5 9 2 1.5-1.5 4-2 9-2V4.5c-5 0-7.5.5-9 2z" />
    <path d="M12 6.5V21" stroke={p.filled ? 'var(--color-surface)' : 'currentColor'} />
  </Svg>
)

/** Stroke-only in both states, as in the designs. */
export const UploadIcon = (p: IconProps) => (
  <Svg {...p} filled={false}>
    <path d="M12 15V4M7.5 8.5 12 4l4.5 4.5M4 14v5a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-5" />
  </Svg>
)

export const BookmarkIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M6 3h12v18l-6-4.5L6 21z" />
  </Svg>
)

export const ChevronLeftIcon = (p: IconProps) => (
  <Svg {...p} filled={false}>
    <path d="m15 18-6-6 6-6" />
  </Svg>
)

export const ChevronRightIcon = (p: IconProps) => (
  <Svg {...p} filled={false}>
    <path d="m9 18 6-6-6-6" />
  </Svg>
)

export const FileTextIcon = (p: IconProps) => (
  <Svg {...p} filled={false}>
    <path d="M14 3H6a1 1 0 0 0-1 1v16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8z" />
    <path d="M14 3v5h5M9 13h6M9 17h6" />
  </Svg>
)

export const PlayIcon = (p: IconProps) => (
  <Svg {...p} filled={false}>
    <rect x="3" y="5" width="18" height="14" rx="3" />
    <path d="m10 9 5 3-5 3z" fill="currentColor" />
  </Svg>
)

export const CheckIcon = (p: IconProps) => (
  <Svg {...p} filled={false}>
    <path d="m5 12.5 4.5 4.5L19 7.5" />
  </Svg>
)

export const TrashIcon = (p: IconProps) => (
  <Svg {...p} filled={false}>
    <path d="M4 7h16M9 7V4h6v3M6 7l1 13a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-13M10 11v6M14 11v6" />
  </Svg>
)

export const RefreshIcon = (p: IconProps) => (
  <Svg {...p} filled={false}>
    <path d="M20 12a8 8 0 1 1-2.34-5.66M20 4v5h-5" />
  </Svg>
)

export const LinkIcon = (p: IconProps) => (
  <Svg {...p} filled={false}>
    <path d="M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-5.66-5.66l-1 1M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 5.66 5.66l1-1" />
  </Svg>
)

export const InfoIcon = (p: IconProps) => (
  <Svg {...p} filled={false}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 11v5M12 8h.01" />
  </Svg>
)

export const ImageIcon = (p: IconProps) => (
  <Svg {...p} filled={false}>
    <rect x="3" y="4" width="18" height="16" rx="3" />
    <circle cx="16" cy="9" r="1.5" />
    <path d="m3 17 5-5 4 4 3-3 6 6" />
  </Svg>
)

export const XIcon = (p: IconProps) => (
  <Svg {...p} filled={false}>
    <path d="M6 6l12 12M18 6 6 18" />
  </Svg>
)

export const AlertIcon = (p: IconProps) => (
  <Svg {...p} filled={false}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 7v6M12 16h.01" />
  </Svg>
)

export const SearchIcon = (p: IconProps) => (
  <Svg {...p} filled={false}>
    <circle cx="11" cy="11" r="7" />
    <path d="m20 20-3.5-3.5" />
  </Svg>
)

export const ChevronDownIcon = (p: IconProps) => (
  <Svg {...p} filled={false}>
    <path d="m6 9 6 6 6-6" />
  </Svg>
)

export const SortIcon = (p: IconProps) => (
  <Svg {...p} filled={false}>
    <path d="M7 4v16M3.5 16.5 7 20l3.5-3.5M17 20V4M13.5 7.5 17 4l3.5 3.5" />
  </Svg>
)

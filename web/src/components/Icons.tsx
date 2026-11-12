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

import type { ReactNode } from 'react'
import { Link } from 'react-router'
import type { PackageType, ResourceType } from '../lib/api/packages'
import { formatBytes, PACKAGE_TYPE_LABEL, RESOURCE_TYPE_LABEL } from '../lib/format'
import { FileTextIcon, PlayIcon } from './Icons'

type Props = {
  resourceType: ResourceType
  title: string
  /** Opens the package page. Without it the title is plain text (e.g. still processing). */
  to?: string
  /** h2 where the card is the page's own list (Explore), h3 under a section heading. */
  headingLevel?: 2 | 3
  /** One-line details under the title: see {@link PackageMeta}. */
  meta?: ReactNode
  /** Status badges (Learnt, Processing, Failed); the row is left out when empty. */
  badges?: ReactNode[]
  /** One icon button (or icon) in the top-right corner, level with the title. */
  corner?: ReactNode
  /** The card's main action or state: Download, progress, a confirmation, Retry. */
  footer?: ReactNode
  busy?: boolean
  className?: string
}

/**
 * The one layout every package card uses, so lists read the same everywhere:
 *
 *   [icon]  Title of the package                         [corner]
 *           Subject · Text only · 7 KB
 *           (badges)
 *   ──────────────────────────────────────────────────────────────
 *   footer: the main action or state, full width
 *
 * The title link is stretched over the whole card (one big tap target); the corner and the
 * footer sit above it so their buttons stay tappable (a button inside a link is invalid).
 */
export default function PackageCardShell({
  resourceType,
  title,
  to,
  headingLevel = 3,
  meta,
  badges = [],
  corner,
  footer,
  busy,
  className = '',
}: Props) {
  const Icon = resourceType === 'video' ? PlayIcon : FileTextIcon
  const Heading = headingLevel === 2 ? 'h2' : 'h3'
  const shownBadges = badges.filter(Boolean)

  return (
    <article className={`package-card ${className}`.trim()} aria-busy={busy || undefined}>
      <div className="package-card__head">
        <div className={`package-card__icon package-card__icon--${resourceType}`}>
          <Icon size={22} />
          <span className="visually-hidden">{RESOURCE_TYPE_LABEL[resourceType]} resource</span>
        </div>

        <div className="package-card__body">
          <Heading className="package-card__title">
            {to ? (
              <Link to={to} className="package-card__link">
                {title}
              </Link>
            ) : (
              title
            )}
          </Heading>
          {meta}
          {shownBadges.length > 0 && (
            <ul className="package-card__badges" aria-label="Status">
              {shownBadges.map((badge, i) => (
                <li key={i}>{badge}</li>
              ))}
            </ul>
          )}
        </div>

        {corner && <div className="package-card__corner">{corner}</div>}
      </div>

      {footer && <div className="package-card__footer">{footer}</div>}
    </article>
  )
}

/** "Technology · Text only · 7 KB": a list, so screen readers hear three separate items. */
export function PackageMeta({
  subject,
  packageType,
  sizeBytes,
}: {
  subject?: string | null
  packageType: PackageType
  sizeBytes?: number
}) {
  return (
    <ul className="package-meta" aria-label="Details">
      {subject && <li className="package-meta__subject">{subject}</li>}
      <li>{PACKAGE_TYPE_LABEL[packageType]}</li>
      {sizeBytes !== undefined && sizeBytes > 0 && <li>{formatBytes(sizeBytes)}</li>}
    </ul>
  )
}

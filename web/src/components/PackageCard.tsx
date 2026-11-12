import { Link } from 'react-router'
import type { PackageSummary } from '../lib/api/packages'
import { formatBytes, PACKAGE_TYPE_LABEL, RESOURCE_TYPE_LABEL } from '../lib/format'
import { CheckIcon, FileTextIcon, PlayIcon } from './Icons'

/**
 * Catalogue card (FR-4.2): resource icon, title, subject and package-type chips, size.
 * The title link is stretched over the whole card, so the card is one tap target while
 * leaving room for a separate Download button later (a button inside a link is invalid).
 */
export default function PackageCard({ pkg }: { pkg: PackageSummary }) {
  const Icon = pkg.resourceType === 'video' ? PlayIcon : FileTextIcon

  return (
    <article className="package-card">
      <div className={`package-card__icon package-card__icon--${pkg.resourceType}`}>
        <Icon />
        <span className="visually-hidden">{RESOURCE_TYPE_LABEL[pkg.resourceType]} resource</span>
      </div>
      <div className="package-card__body">
        <h2 className="package-card__title">
          <Link to={`/packages/${pkg.id}`} className="package-card__link">
            {pkg.title}
          </Link>
        </h2>
        <ul className="chips" aria-label="Details">
          <li className="chip">{pkg.subject}</li>
          <li className="chip">{PACKAGE_TYPE_LABEL[pkg.packageType]}</li>
        </ul>
        <p className="package-card__meta">
          <span>{formatBytes(pkg.sizeBytes)}</span>
          {pkg.isDownloaded && (
            <span className="package-card__downloaded">
              <CheckIcon size={16} /> Downloaded
            </span>
          )}
        </p>
      </div>
    </article>
  )
}

/** Same footprint as a card, shown while the first page loads. */
export function PackageCardSkeleton() {
  return (
    <div className="package-card package-card--skeleton" aria-hidden="true">
      <div className="package-card__icon" />
      <div className="package-card__body">
        <div className="skeleton-line skeleton-line--title" />
        <div className="skeleton-line skeleton-line--short" />
      </div>
    </div>
  )
}

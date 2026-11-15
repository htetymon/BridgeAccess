import type { PackageSummary } from '../lib/api/packages'
import DownloadControl from './DownloadControl'
import PackageCardShell, { PackageMeta } from './PackageCardShell'

/**
 * Catalogue card (FR-4.2): resource icon, title, subject, package type and size, and the
 * download control in the footer (FR-4.3, FR-4.6). "Downloaded" comes from this device's
 * store, not the server's `isDownloaded`: what matters offline is what is on the phone.
 */
export default function PackageCard({ pkg }: { pkg: PackageSummary }) {
  return (
    <PackageCardShell
      resourceType={pkg.resourceType}
      title={pkg.title}
      to={`/packages/${pkg.id}`}
      headingLevel={2}
      meta={<PackageMeta subject={pkg.subject} packageType={pkg.packageType} sizeBytes={pkg.sizeBytes} />}
      footer={<DownloadControl packageId={pkg.id} title={pkg.title} sizeBytes={pkg.sizeBytes} />}
    />
  )
}

/** Same footprint as a card, shown while the first page loads. */
export function PackageCardSkeleton() {
  return (
    <div className="package-card package-card--skeleton" aria-hidden="true">
      <div className="package-card__head">
        <div className="package-card__icon" />
        <div className="package-card__body">
          <div className="skeleton-line skeleton-line--title" />
          <div className="skeleton-line skeleton-line--short" />
        </div>
      </div>
      <div className="package-card__footer">
        <div className="skeleton-line skeleton-line--button" />
      </div>
    </div>
  )
}

import DownloadControl from './DownloadControl'
import type { LocalPackage } from '../lib/db'
import { BookmarkIcon, CheckIcon } from './Icons'
import PackageCardShell, { PackageMeta } from './PackageCardShell'

type Props =
  | { variant: 'continue'; pkg: LocalPackage; percent: number }
  | { variant: 'download'; pkg: LocalPackage; learnt: boolean }
  | { variant: 'bookmark'; pkg: LocalPackage; learnt: boolean; onUnbookmark: (pkg: LocalPackage) => void }

/**
 * A package stored on this device (Home, Learn and Bookmarks). Rendered from IndexedDB only,
 * so it works offline (FR-2.6). Same layout as every package card (see PackageCardShell), and
 * the same footer as every other card: the download control, which for a downloaded package
 * is "Downloaded" with Delete (FR-3.3), or the Download button when it is not on the device.
 *
 * - continue: a progress bar with its percentage under the details (FR-2.2).
 * - download: a Learnt badge (FR-2.3).
 * - bookmark: a Learnt badge and Remove bookmark in the corner (FR-7.10). No confirmation to
 *   remove: bookmarking again undoes it.
 */
export default function LocalPackageCard(props: Props) {
  const { pkg } = props
  const learnt = props.variant !== 'continue' && props.learnt

  return (
    <PackageCardShell
      className="local-package"
      resourceType={pkg.resourceType}
      title={pkg.title}
      // Continue Learning opens the lesson itself; with no ?step the viewer resumes at the step
      // the learner was last on (progress.lastStep). The other lists open the package page.
      to={props.variant === 'continue' ? `/packages/${pkg.id}/learn` : `/packages/${pkg.id}`}
      meta={
        <>
          <PackageMeta subject={pkg.subject} packageType={pkg.packageType} sizeBytes={pkg.sizeBytes} />
          {props.variant === 'continue' && (
            <div className="package-card__progress">
              <progress max={100} value={props.percent} aria-label={`${props.percent}% complete`} />
              <span aria-hidden="true">{props.percent}%</span>
            </div>
          )}
        </>
      }
      badges={[
        learnt && (
          <span key="learnt" className="badge badge--success">
            <CheckIcon size={14} /> Learnt
          </span>
        ),
      ]}
      corner={
        props.variant === 'bookmark' && (
          <button
            type="button"
            className="icon-button package-card__action package-card__action--on"
            onClick={() => props.onUnbookmark(pkg)}
            aria-label={`Remove bookmark: ${pkg.title}`}
          >
            <BookmarkIcon filled />
          </button>
        )
      }
      footer={<DownloadControl packageId={pkg.id} title={pkg.title} sizeBytes={pkg.sizeBytes} />}
    />
  )
}

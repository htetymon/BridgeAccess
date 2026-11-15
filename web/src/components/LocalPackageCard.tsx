import { useState } from 'react'
import { Link } from 'react-router'
import type { LocalPackage } from '../lib/db'
import { formatBytes, PACKAGE_TYPE_LABEL, RESOURCE_TYPE_LABEL } from '../lib/format'
import { BookmarkIcon, CheckIcon, ChevronRightIcon, FileTextIcon, PlayIcon, TrashIcon } from './Icons'

type Props =
  | { variant: 'continue'; pkg: LocalPackage; percent: number }
  | { variant: 'download'; pkg: LocalPackage; learnt: boolean; onDelete: (id: string) => Promise<void> }
  | { variant: 'bookmark'; pkg: LocalPackage; learnt: boolean; onUnbookmark: (pkg: LocalPackage) => void }

/**
 * A package stored on this device (Home and Learn). Rendered from IndexedDB only, so it works
 * offline (FR-2.6). The title link covers the whole card; the delete button sits above it.
 *
 * - continue: chips, size, a progress bar and a chevron (FR-2.2).
 * - download: chips, a Learnt badge, size and Delete with an inline confirmation (FR-2.3, FR-3.3).
 * - bookmark: chips, Downloaded and Learnt badges, size and Remove bookmark (FR-7.10). No
 *   confirmation: bookmarking again from the package screen undoes it.
 */
export default function LocalPackageCard(props: Props) {
  const { pkg } = props
  const Icon = pkg.resourceType === 'video' ? PlayIcon : FileTextIcon
  const [confirming, setConfirming] = useState(false)
  const [deleting, setDeleting] = useState(false)

  async function remove() {
    if (props.variant !== 'download') return
    setDeleting(true)
    try {
      await props.onDelete(pkg.id)
    } finally {
      setDeleting(false) // the card usually disappears first; this covers a failed delete
      setConfirming(false)
    }
  }

  return (
    <article className="package-card local-package" aria-busy={deleting}>
      <div className={`package-card__icon package-card__icon--${pkg.resourceType}`}>
        <Icon />
        <span className="visually-hidden">{RESOURCE_TYPE_LABEL[pkg.resourceType]} resource</span>
      </div>

      <div className="package-card__body">
        <h3 className="package-card__title">
          <Link to={`/packages/${pkg.id}`} className="package-card__link">
            {pkg.title}
          </Link>
        </h3>
        <ul className="chips" aria-label="Details">
          <li className="chip">{pkg.subject}</li>
          <li className="chip">{PACKAGE_TYPE_LABEL[pkg.packageType]}</li>
          {props.variant === 'bookmark' && pkg.downloadStatus === 'downloaded' && (
            <li className="chip chip--success">
              <CheckIcon size={14} /> Downloaded
            </li>
          )}
          {props.variant !== 'continue' && props.learnt && (
            <li className="chip chip--success">
              <CheckIcon size={14} /> Learnt
            </li>
          )}
        </ul>

        {props.variant === 'continue' && (
          <div className="local-package__progress">
            <progress max={100} value={props.percent} aria-label={`${props.percent}% complete`} />
          </div>
        )}

        {confirming && (
          <div className="local-package__confirm" role="group" aria-label={`Delete ${pkg.title}?`}>
            <p>Delete this download? Your progress is kept, and you can download it again.</p>
            <div className="local-package__confirm-actions">
              <button type="button" className="button button--danger button--small" onClick={remove} disabled={deleting}>
                {deleting ? 'Deleting…' : 'Delete'}
              </button>
              <button
                type="button"
                className="button button--secondary button--small"
                onClick={() => setConfirming(false)}
                disabled={deleting}
              >
                Cancel
              </button>
            </div>
          </div>
        )}
      </div>

      <div className="local-package__side">
        <span className="local-package__size">{formatBytes(pkg.sizeBytes)}</span>
        {props.variant === 'continue' ? (
          <ChevronRightIcon size={20} />
        ) : props.variant === 'bookmark' ? (
          <button
            type="button"
            className="icon-button package-card__action local-package__bookmark"
            onClick={() => props.onUnbookmark(pkg)}
            aria-label={`Remove bookmark: ${pkg.title}`}
          >
            <BookmarkIcon filled />
          </button>
        ) : (
          !confirming && (
            <button
              type="button"
              className="icon-button package-card__action"
              onClick={() => setConfirming(true)}
              aria-label={`Delete ${pkg.title} from this device`}
            >
              <TrashIcon />
            </button>
          )
        )}
      </div>
    </article>
  )
}

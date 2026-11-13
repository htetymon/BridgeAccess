import { useState } from 'react'
import { Link } from 'react-router'
import type { MyPackage } from '../lib/api/contributions'
import { PACKAGE_TYPE_LABEL, RESOURCE_TYPE_LABEL } from '../lib/format'
import { AlertIcon, CheckIcon, FileTextIcon, PlayIcon, RefreshIcon, TrashIcon } from './Icons'

type Props = {
  pkg: MyPackage
  /** Still processing after about 3 minutes of polling. */
  stalled?: boolean
  /** Resolve to an error message, or null on success. */
  onRetry: (id: string) => Promise<string | null>
  onDelete: (id: string) => Promise<string | null>
}

/**
 * A package in My Contributions (FR-5.7, FR-5.8). Shows the status the contributor needs:
 * Processing (with the original title, since the AI title is not ready), Failed (with the
 * reason and a Retry button), or the published package with its chips and Learnt badge.
 * Only a published package links to its page; the others have nothing to open yet.
 */
export default function MyPackageCard({ pkg, stalled = false, onRetry, onDelete }: Props) {
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const Icon = pkg.resourceType === 'video' ? PlayIcon : FileTextIcon
  const title = pkg.title ?? pkg.originalTitle

  async function run(action: (id: string) => Promise<string | null>) {
    setBusy(true)
    setError(null)
    const message = await action(pkg.id)
    setBusy(false)
    setConfirming(false)
    if (message) setError(message)
  }

  return (
    <article className={`package-card my-package my-package--${pkg.status}`} aria-busy={busy}>
      <div className={`package-card__icon package-card__icon--${pkg.resourceType}`}>
        <Icon />
        <span className="visually-hidden">{RESOURCE_TYPE_LABEL[pkg.resourceType]} resource</span>
      </div>

      <div className="package-card__body">
        <h3 className="package-card__title">
          {pkg.status === 'published' ? (
            <Link to={`/packages/${pkg.id}`} className="package-card__link">
              {title}
            </Link>
          ) : (
            title
          )}
        </h3>

        {pkg.status === 'published' && (
          <ul className="chips" aria-label="Details">
            {pkg.subject && <li className="chip">{pkg.subject}</li>}
            <li className="chip">{PACKAGE_TYPE_LABEL[pkg.packageType]}</li>
            {pkg.isLearnt && (
              <li className="chip chip--success">
                <CheckIcon size={14} /> Learnt
              </li>
            )}
          </ul>
        )}

        {pkg.status === 'processing' && (
          <p className="my-package__status" role="status">
            <span className="chip chip--progress">
              <span className="spinner spinner--tiny" aria-hidden="true" /> Processing
            </span>
            <span className="my-package__hint">
              {stalled ? 'Still working, check back later.' : 'Building your lesson. This usually takes a minute.'}
            </span>
          </p>
        )}

        {pkg.status === 'failed' && (
          <div className="my-package__failed">
            <p className="my-package__status">
              <span className="chip chip--danger">
                <AlertIcon size={14} /> Failed
              </span>
            </p>
            {pkg.failureReason && <p className="my-package__reason">{pkg.failureReason}</p>}
            <button
              type="button"
              className="button button--secondary button--small"
              onClick={() => run(onRetry)}
              disabled={busy}
            >
              <RefreshIcon size={18} /> {busy ? 'Retrying…' : 'Retry'}
            </button>
          </div>
        )}

        {error && (
          <p className="my-package__error" role="alert">
            {error}
          </p>
        )}

        {confirming && (
          <div className="my-package__confirm" role="group" aria-label={`Delete ${title}?`}>
            <p>Delete this package? It will be removed for everyone.</p>
            <div className="my-package__confirm-actions">
              <button
                type="button"
                className="button button--danger button--small"
                onClick={() => run(onDelete)}
                disabled={busy}
              >
                {busy ? 'Deleting…' : 'Delete'}
              </button>
              <button
                type="button"
                className="button button--secondary button--small"
                onClick={() => setConfirming(false)}
                disabled={busy}
              >
                Cancel
              </button>
            </div>
          </div>
        )}
      </div>

      {!confirming && (
        <button
          type="button"
          className="icon-button package-card__action"
          onClick={() => setConfirming(true)}
          aria-label={`Delete ${title}`}
          disabled={busy}
        >
          <TrashIcon />
        </button>
      )}
    </article>
  )
}

/** List of my packages, shared by the Contribute tab and the See All screen. */
export function MyPackageList({
  items,
  stalled,
  onRetry,
  onDelete,
}: {
  items: MyPackage[]
  stalled: ReadonlySet<string>
  onRetry: Props['onRetry']
  onDelete: Props['onDelete']
}) {
  return (
    <ul className="package-list" aria-label="My contributions">
      {items.map((pkg) => (
        <li key={pkg.id}>
          <MyPackageCard pkg={pkg} stalled={stalled.has(pkg.id)} onRetry={onRetry} onDelete={onDelete} />
        </li>
      ))}
    </ul>
  )
}

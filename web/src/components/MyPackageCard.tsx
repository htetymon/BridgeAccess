import { useState } from 'react'
import { Link } from 'react-router'
import type { MyPackage } from '../lib/api/contributions'
import type { RetryProblem } from '../lib/contribution'
import { PACKAGE_TYPE_LABEL, RESOURCE_TYPE_LABEL } from '../lib/format'
import { AlertIcon, CheckIcon, FileTextIcon, PlayIcon, RefreshIcon, TrashIcon } from './Icons'

type Actions = {
  /** Resolves to null on success, or the problem to show. */
  onRetry: (id: string) => Promise<RetryProblem | null>
  /** Resolves to null on success, or an error message. */
  onDelete: (id: string) => Promise<string | null>
  /** Look again now for a package that is still working after the polling limit. */
  onCheckAgain: (id: string) => void
}

type Props = Actions & {
  pkg: MyPackage
  /** Still processing after about 3 minutes of polling. */
  stalled?: boolean
}

/**
 * A package in My Contributions (FR-5.7, FR-5.8). Shows the status the contributor needs:
 * Processing (with the original title, since the AI title is not ready), Failed (with the
 * reason and a Retry button), or the published package with its chips and Learnt badge.
 * Only a published package links to its page; the others have nothing to open yet.
 */
export default function MyPackageCard({ pkg, stalled = false, onRetry, onDelete, onCheckAgain }: Props) {
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState<'retry' | 'delete' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [retryProblem, setRetryProblem] = useState<RetryProblem | null>(null)
  const Icon = pkg.resourceType === 'video' ? PlayIcon : FileTextIcon
  const title = pkg.title ?? pkg.originalTitle
  // Once the server said Retry cannot help, offer the way forward instead of a dead button.
  const retryBlocked = retryProblem !== null && !retryProblem.canRetry

  async function retry() {
    setBusy('retry')
    setError(null)
    const problem = await onRetry(pkg.id)
    setBusy(null)
    setRetryProblem(problem)
  }

  async function remove() {
    setBusy('delete')
    setError(null)
    const message = await onDelete(pkg.id)
    setBusy(null)
    setConfirming(false)
    if (message) setError(message)
  }

  return (
    <article className={`package-card my-package my-package--${pkg.status}`} aria-busy={busy !== null}>
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
          <div className="my-package__processing">
            <p className="my-package__status">
              <span className="chip chip--progress">
                {!stalled && <span className="spinner spinner--tiny" aria-hidden="true" />} Processing
              </span>
              <span className="my-package__hint">
                {stalled ? 'Still working, check back later.' : 'Building your lesson. This usually takes a minute.'}
              </span>
            </p>
            {stalled && (
              <button type="button" className="button button--secondary button--small" onClick={() => onCheckAgain(pkg.id)}>
                <RefreshIcon size={18} /> Check again
              </button>
            )}
          </div>
        )}

        {pkg.status === 'failed' && (
          <div className="my-package__failed">
            <p className="my-package__status">
              <span className="chip chip--danger">
                <AlertIcon size={14} /> Failed
              </span>
            </p>
            {pkg.failureReason && <p className="my-package__reason">{pkg.failureReason}</p>}
            {retryBlocked ? (
              <div className="my-package__next">
                {retryProblem.existingPackageId && (
                  <Link to={`/packages/${retryProblem.existingPackageId}`} className="button button--secondary button--small">
                    Open that package
                  </Link>
                )}
                <Link to="/contribute/new" className="button button--secondary button--small">
                  Create it again
                </Link>
              </div>
            ) : (
              <button
                type="button"
                className="button button--secondary button--small"
                onClick={retry}
                disabled={busy !== null}
              >
                <RefreshIcon size={18} /> {busy === 'retry' ? 'Retrying…' : 'Retry'}
              </button>
            )}
          </div>
        )}

        {(error ?? retryProblem?.message) && (
          <p className="my-package__error" role="alert">
            {error ?? retryProblem?.message}
          </p>
        )}

        {confirming && (
          <div className="my-package__confirm" role="group" aria-label={`Delete ${title}?`}>
            <p>Delete this package? It will be removed for everyone. Devices that downloaded it keep their copy.</p>
            <div className="my-package__confirm-actions">
              <button type="button" className="button button--danger button--small" onClick={remove} disabled={busy !== null}>
                {busy === 'delete' ? 'Deleting…' : 'Delete'}
              </button>
              <button
                type="button"
                className="button button--secondary button--small"
                onClick={() => setConfirming(false)}
                disabled={busy !== null}
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
          disabled={busy !== null}
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
  announcement,
  ...actions
}: Actions & { items: MyPackage[]; stalled: ReadonlySet<string>; announcement: string }) {
  return (
    <>
      <ul className="package-list" aria-label="My contributions">
        {items.map((pkg) => (
          <li key={pkg.id}>
            <MyPackageCard pkg={pkg} stalled={stalled.has(pkg.id)} {...actions} />
          </li>
        ))}
      </ul>
      {/* Status changes found by polling ("… is ready"), read out without moving focus. */}
      <p className="visually-hidden" role="status" aria-live="polite">
        {announcement}
      </p>
    </>
  )
}

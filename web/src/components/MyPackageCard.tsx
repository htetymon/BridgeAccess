import { useState } from 'react'
import { Link } from 'react-router'
import type { MyPackage } from '../lib/api/contributions'
import type { RetryProblem } from '../lib/contribution'
import PackageCardShell, { PackageMeta } from './PackageCardShell'
import DownloadControl from './DownloadControl'
import { AlertIcon, EditIcon, CheckIcon, RefreshIcon, TrashIcon } from './Icons'

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
 * reason and a Retry button), or the published package, which looks and works like any other
 * package card (details, Learnt badge, Download or Downloaded + Delete). The corner trash deletes
 * the package itself, for everyone; the footer Delete only removes this device's copy.
 * Only a published package links to its page; the others have nothing to open yet.
 */
export default function MyPackageCard({ pkg, stalled = false, onRetry, onDelete, onCheckAgain }: Props) {
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState<'retry' | 'delete' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [retryProblem, setRetryProblem] = useState<RetryProblem | null>(null)
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

  const badges = [
    pkg.status === 'processing' && (
      <span key="processing" className="badge badge--progress">
        {!stalled && <span className="spinner spinner--tiny" aria-hidden="true" />} Processing
      </span>
    ),
    pkg.status === 'failed' && (
      <span key="failed" className="badge badge--danger">
        <AlertIcon size={14} /> Failed
      </span>
    ),
    pkg.status === 'published' && pkg.isLearnt && (
      <span key="learnt" className="badge badge--success">
        <CheckIcon size={14} /> Learnt
      </span>
    ),
  ]

  const message = error ?? retryProblem?.message
  let footer: React.ReactNode = null
  if (confirming) {
    footer = (
      <div className="card-confirm" role="group" aria-label={`Delete ${title}?`}>
        <p>Delete this package? It will be removed for everyone. Devices that downloaded it keep their copy.</p>
        <div className="card-actions">
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
    )
  } else if (pkg.status === 'processing') {
    footer = (
      <div className="card-status">
        <p className="card-status__text">
          {stalled ? 'Still working, check back later.' : 'Building your lesson. This usually takes a minute.'}
        </p>
        {stalled && (
          <div className="card-actions">
            <button type="button" className="button button--secondary button--small" onClick={() => onCheckAgain(pkg.id)}>
              <RefreshIcon size={18} /> Check again
            </button>
          </div>
        )}
      </div>
    )
  } else if (pkg.status === 'failed') {
    footer = (
      <div className="card-status">
        {pkg.failureReason && <p className="card-status__text">{pkg.failureReason}</p>}
        {retryBlocked ? (
          <div className="card-actions">
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
          <div className="card-actions">
            {/* Fix the cause first: paste the content, correct the link (FR-5.8). */}
            <Link
              to={`/contribute/edit/${pkg.id}`}
              className="button button--secondary button--small"
              aria-disabled={busy !== null}
            >
              <EditIcon size={18} /> Edit and retry
            </Link>
            <button type="button" className="button button--secondary button--small" onClick={retry} disabled={busy !== null}>
              <RefreshIcon size={18} /> {busy === 'retry' ? 'Retrying…' : 'Retry'}
            </button>
          </div>
        )}
      </div>
    )
  } else if (pkg.status === 'published') {
    // Published: the same footer as every other package card (Download, or Downloaded + Delete).
    footer = <DownloadControl packageId={pkg.id} title={title} sizeBytes={pkg.sizeBytes} />
  }

  return (
    <PackageCardShell
      className={`my-package my-package--${pkg.status}`}
      resourceType={pkg.resourceType}
      title={title}
      to={pkg.status === 'published' ? `/packages/${pkg.id}` : undefined}
      meta={
        pkg.status === 'published' && (
          <PackageMeta subject={pkg.subject} packageType={pkg.packageType} sizeBytes={pkg.sizeBytes} />
        )
      }
      badges={badges}
      corner={
        !confirming && (
          <button
            type="button"
            className="icon-button package-card__action"
            onClick={() => setConfirming(true)}
            aria-label={`Delete ${title} for everyone`}
            title="Delete for everyone"
            disabled={busy !== null}
          >
            <TrashIcon />
          </button>
        )
      }
      footer={
        (footer || message) && (
          <>
            {footer}
            {message && (
              <p className="card-error" role="alert">
                {message}
              </p>
            )}
          </>
        )
      }
      busy={busy !== null}
    />
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

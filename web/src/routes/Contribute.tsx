import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { CheckIcon, InfoIcon, UploadIcon, XIcon } from '../components/Icons'
import { MyPackageList } from '../components/MyPackageCard'
import PackageCard, { PackageCardSkeleton } from '../components/PackageCard'
import PageHeader from '../components/PageHeader'
import Section from '../components/Section'
import { useMyPackages } from '../lib/api/useMyPackages'
import { usePackageList } from '../lib/api/usePackageList'
import { useOnline } from '../lib/useOnline'

/** How many items each section previews; See All shows the rest. */
const PREVIEW = 3

/**
 * Contribute (screen 9, FR-5.1, FR-5.7, FR-5.8): Create a Package, the device's own packages
 * with their status (Processing, Failed with Retry, or published), and a preview of other
 * contributors' packages. Server data needs a connection; offline, each section says so.
 */
export function Component() {
  const online = useOnline()
  const location = useLocation()
  const navigate = useNavigate()
  const [justCreated, setJustCreated] = useState(Boolean((location.state as { created?: string } | null)?.created))

  useEffect(() => {
    // Show the confirmation once; a refresh or Back should not show it again.
    if (justCreated) navigate(location.pathname, { replace: true, state: null })
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const mine = useMyPackages(PREVIEW)
  const community = usePackageList({ excludeMine: true }, PREVIEW)

  // Back online after failing offline: load again without making the learner tap anything.
  const { reload: reloadMine, status: mineStatus } = mine
  const { retry: reloadCommunity, status: communityStatus } = community
  useEffect(() => {
    if (!online) return
    if (mineStatus === 'error') reloadMine()
    if (communityStatus === 'error') reloadCommunity()
  }, [online]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <>
      <title>Contribute · BridgeAccess</title>
      <PageHeader title="Contribute" subtitle="Share your learning materials with the community." />

      {/* Still opens offline: the form keeps a draft, and its Create button explains the wait (FR-5.9). */}
      <Link
        to="/contribute/new"
        className="button button--primary button--block"
        aria-describedby={online ? undefined : 'create-offline-note'}
      >
        <UploadIcon /> Create a Package
      </Link>
      {!online && (
        <p id="create-offline-note" className="notice" role="status">
          <InfoIcon size={20} />
          <span>
            You're offline. You can start a package now and it is saved on this device; sending it needs a connection.
          </span>
        </p>
      )}

      {justCreated && (
        <div className="notice notice--success" role="status">
          <CheckIcon size={20} />
          <p>Package submitted. We're building your lesson; it appears below and is ready in about a minute.</p>
          <button type="button" className="notice__close" onClick={() => setJustCreated(false)} aria-label="Dismiss">
            <XIcon size={18} />
          </button>
        </div>
      )}

      <Section title="My Contributions" seeAllTo={mine.items.length > 0 ? '/contribute/mine' : undefined}>
        {mine.status === 'loading' ? (
          <Skeletons count={2} label="Loading your packages" />
        ) : mine.status === 'error' ? (
          <SectionError message={online ? mine.error?.message : OFFLINE} onRetry={online ? mine.reload : undefined} />
        ) : mine.items.length === 0 ? (
          <p className="empty-state">Packages you create will appear here.</p>
        ) : (
          <MyPackageList
            items={mine.items}
            stalled={mine.stalled}
            announcement={mine.announcement}
            onRetry={mine.retry}
            onDelete={mine.remove}
            onCheckAgain={mine.checkAgain}
          />
        )}
      </Section>

      <Section title="Community Packages" seeAllTo="/explore">
        {community.status === 'loading' ? (
          <Skeletons count={2} label="Loading community packages" />
        ) : community.status === 'error' ? (
          <SectionError
            message={online ? community.error?.message : 'Connect to the internet to see community packages.'}
            onRetry={online ? community.retry : undefined}
          />
        ) : community.items.length === 0 ? (
          <p className="empty-state">No community packages yet. Yours could be the first.</p>
        ) : (
          <ul className="package-list" aria-label="Community packages">
            {community.items.map((pkg) => (
              <li key={pkg.id}>
                <PackageCard pkg={pkg} />
              </li>
            ))}
          </ul>
        )}
      </Section>
    </>
  )
}

const OFFLINE = "You're offline. Your packages will show when you're connected."

function Skeletons({ count, label }: { count: number; label: string }) {
  return (
    <div className="package-list" role="status" aria-label={label}>
      {Array.from({ length: count }, (_, i) => (
        <PackageCardSkeleton key={i} />
      ))}
    </div>
  )
}

function SectionError({ message, onRetry }: { message?: string; onRetry?: () => void }) {
  return (
    <div className="empty-state section-error" role="alert">
      <p>{message ?? 'Something went wrong. Please try again.'}</p>
      {onRetry && (
        <button type="button" className="button button--secondary button--small" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  )
}

import { db } from '../lib/db'
import { syncNow, useSyncStatus } from '../lib/sync'
import { useLiveQuery } from '../lib/useLiveQuery'
import { useOnline } from '../lib/useOnline'
import { RefreshIcon } from './Icons'

/**
 * Offline banner and "Waiting to sync" indicator (FR-8.4), at the top of every screen.
 *
 * - Offline: says so, says downloaded packages still work, and how many changes wait.
 * - Online with changes that could not be sent (the last sync failed): "Waiting to sync"
 *   with Sync now. Automatic retries continue in the background either way.
 * - Otherwise nothing. Changes made online are sent within seconds, so showing a banner for
 *   that short wait would only flash on every tap.
 */
export default function ConnectionBanner() {
  const online = useOnline()
  const sync = useSyncStatus()
  // Re-read when the service worker synced (its IndexedDB writes do not notify the page).
  const pending = useLiveQuery(() => db.outbox.count(), String(sync.version))
  const count = pending.status === 'ready' ? pending.value : 0
  const changes = `${count} ${count === 1 ? 'change' : 'changes'}`

  if (!online) {
    return (
      <div className="connection-banner connection-banner--offline" role="status">
        <span className="connection-banner__dot" aria-hidden="true" />
        <p>
          You're offline. Downloaded packages still work.
          {count > 0 && <> {changes} waiting to sync.</>}
        </p>
      </div>
    )
  }

  // lastError stays set from a failed sync until one succeeds, so a retry in progress keeps
  // the indicator (as "Syncing…") instead of making it blink.
  if (count > 0 && sync.lastError !== undefined) {
    const syncing = sync.state === 'syncing'
    return (
      <div className="connection-banner connection-banner--pending" role="status">
        <p>{syncing ? `Syncing ${changes}…` : `Waiting to sync: ${changes}.`}</p>
        {!syncing && (
          <button type="button" className="connection-banner__action" onClick={() => void syncNow()}>
            <RefreshIcon size={16} /> Sync now
          </button>
        )}
      </div>
    )
  }

  return null
}

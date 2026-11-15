import { useRegisterSW } from 'virtual:pwa-register/react'
import { startUpdateChecks } from '../lib/swUpdates'

/**
 * Registers the service worker and reports its state:
 * - first install finished: "ready to work offline" (dismissible);
 * - a new version is waiting: offer a reload, which activates it. Until then the old
 *   version keeps running, so nothing changes under a learner mid-lesson.
 * It also checks for a new version hourly and on returning to the app (startUpdateChecks),
 * so learners who keep the app open still get updates.
 */
export default function UpdatePrompt() {
  const {
    offlineReady: [offlineReady, setOfflineReady],
    needRefresh: [needRefresh, setNeedRefresh],
    updateServiceWorker,
  } = useRegisterSW({
    // Called once per page load, when the service worker is registered.
    onRegisteredSW: (_url, registration) => {
      if (registration) startUpdateChecks(registration)
    },
  })

  if (!offlineReady && !needRefresh) return null

  const close = () => {
    setOfflineReady(false)
    setNeedRefresh(false)
  }

  return (
    <div className="toast" role="status">
      <p>{needRefresh ? 'A new version of BridgeAccess is available.' : 'BridgeAccess is ready to work offline.'}</p>
      <div className="toast__actions">
        {needRefresh && (
          <button type="button" className="toast__button toast__button--primary" onClick={() => updateServiceWorker(true)}>
            Reload
          </button>
        )}
        <button type="button" className="toast__button" onClick={close}>
          {needRefresh ? 'Later' : 'OK'}
        </button>
      </div>
    </div>
  )
}

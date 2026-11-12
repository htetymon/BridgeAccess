import { useRegisterSW } from 'virtual:pwa-register/react'

/**
 * Registers the service worker and reports its state:
 * - first install finished: "ready to work offline" (dismissible);
 * - a new version is waiting: offer a reload, which activates it. Until then the old
 *   version keeps running, so nothing changes under a learner mid-lesson.
 */
export default function UpdatePrompt() {
  const {
    offlineReady: [offlineReady, setOfflineReady],
    needRefresh: [needRefresh, setNeedRefresh],
    updateServiceWorker,
  } = useRegisterSW()

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

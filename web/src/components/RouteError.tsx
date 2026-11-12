import { isRouteErrorResponse, useRouteError } from 'react-router'

/**
 * Catches render errors and failed chunk downloads. A screen's chunk can fail to load when
 * the device is offline before that screen was ever opened, or after a deploy replaced the
 * old chunk files; reloading fixes the second case and retries the first.
 */
export default function RouteError() {
  const error = useRouteError()
  const offline = typeof navigator !== 'undefined' && !navigator.onLine
  const notFound = isRouteErrorResponse(error) && error.status === 404

  return (
    <main className="screen route-error">
      <title>Something went wrong · BridgeAccess</title>
      <h1>{notFound ? 'Page not found' : offline ? "You're offline" : 'Something went wrong'}</h1>
      <p>
        {offline
          ? 'This screen has not been saved on your device yet. Connect to the internet and try again.'
          : 'Please try again.'}
      </p>
      <button type="button" className="button button--primary" onClick={() => window.location.reload()}>
        Try again
      </button>
    </main>
  )
}

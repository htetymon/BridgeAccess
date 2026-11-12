/** Shown on first load while the first screen's chunk downloads. */
export default function AppLoading() {
  return (
    <div className="app-loading" role="status">
      <span className="spinner" aria-hidden="true" />
      <span className="visually-hidden">Loading BridgeAccess</span>
    </div>
  )
}

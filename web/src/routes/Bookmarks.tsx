import PageHeader from '../components/PageHeader'

export function Component() {
  return (
    <>
      <title>Bookmarks · BridgeAccess</title>
      <PageHeader title="Bookmarks" subtitle="Packages you saved for later." />
      <p className="empty-state">Tap the bookmark on any package to save it here.</p>
    </>
  )
}

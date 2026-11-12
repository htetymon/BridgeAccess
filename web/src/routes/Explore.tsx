import PageHeader from '../components/PageHeader'

export function Component() {
  return (
    <main className="screen">
      <title>Explore · BridgeAccess</title>
      <PageHeader title="Explore and Download Packages" back={{ fallback: '/' }} />
      <p className="empty-state">The package catalogue will appear here.</p>
    </main>
  )
}

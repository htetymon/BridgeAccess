import PageHeader from '../components/PageHeader'

export function Component() {
  return (
    <main className="screen">
      <title>Create a Package · BridgeAccess</title>
      <PageHeader
        title="Create a Package"
        subtitle="Share a learning package with the community."
        back={{ fallback: '/contribute' }}
      />
      <p className="empty-state">The package form will appear here.</p>
    </main>
  )
}

import { useParams } from 'react-router'
import PageHeader from '../components/PageHeader'

export function Component() {
  const { packageId } = useParams()
  return (
    <main className="screen">
      <title>Package · BridgeAccess</title>
      <PageHeader title="Package" back={{ fallback: '/learn' }} />
      <p className="empty-state">Package {packageId} will appear here.</p>
    </main>
  )
}

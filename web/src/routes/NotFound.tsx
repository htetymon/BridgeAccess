import { Link } from 'react-router'
import PageHeader from '../components/PageHeader'

export function Component() {
  return (
    <main className="screen">
      <title>Page not found · BridgeAccess</title>
      <PageHeader title="Page not found" subtitle="This page does not exist." />
      <Link to="/" className="button button--primary">
        Go to Home
      </Link>
    </main>
  )
}

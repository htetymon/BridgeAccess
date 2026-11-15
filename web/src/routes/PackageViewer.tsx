import { useParams } from 'react-router'
import PageHeader from '../components/PageHeader'

/** Learning steps (FR-7.3 to FR-7.8). Placeholder until the step viewer is built. */
export function Component() {
  const { packageId } = useParams()
  return (
    <main className="screen">
      <title>Learning · BridgeAccess</title>
      <PageHeader title="Learning" back={{ fallback: `/packages/${packageId}` }} />
      <p className="empty-state">The learning steps (Summary, Key Concepts, Diagram, Quiz) will appear here.</p>
    </main>
  )
}

import PageHeader from '../components/PageHeader'
import Section from '../components/Section'

export function Component() {
  return (
    <>
      <title>Learn · BridgeAccess</title>
      <PageHeader title="Learn" subtitle="Study and manage your learning packages." />
      <Section title="Continue Learning" empty="Packages you start will appear here." />
      <Section
        title="Downloaded Packages"
        seeAllTo="/explore"
        empty="No downloads yet. Explore packages to download one."
      />
    </>
  )
}

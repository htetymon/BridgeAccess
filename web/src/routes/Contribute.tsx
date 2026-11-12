import { Link } from 'react-router'
import { UploadIcon } from '../components/Icons'
import PageHeader from '../components/PageHeader'
import Section from '../components/Section'

export function Component() {
  return (
    <>
      <title>Contribute · BridgeAccess</title>
      <PageHeader title="Contribute" subtitle="Share your learning materials with the community." />
      <Link to="/contribute/new" className="button button--primary button--block">
        <UploadIcon /> Create a Package
      </Link>
      <Section title="My Contributions" empty="Packages you create will appear here." />
      <Section title="Community Packages" seeAllTo="/explore" empty="Connect to the internet to see community packages." />
    </>
  )
}

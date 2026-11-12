import { Link } from 'react-router'
import { BookIcon, ChevronRightIcon, UploadIcon } from '../components/Icons'
import Section from '../components/Section'

export function Component() {
  return (
    <>
      <title>BridgeAccess</title>
      <div className="brand">
        <BookIcon filled size={28} />
        <span>
          Bridge<span className="brand__accent">Access</span>
        </span>
      </div>

      <header className="page-header">
        <h1>Welcome to BridgeAccess.</h1>
        <p className="page-header__subtitle">What would you like to do today?</p>
      </header>

      <div className="quick-actions">
        <Link to="/learn" className="quick-action quick-action--learn">
          <BookIcon /> <span>Learn</span> <ChevronRightIcon size={18} />
        </Link>
        <Link to="/contribute" className="quick-action quick-action--contribute">
          <UploadIcon /> <span>Contribute</span> <ChevronRightIcon size={18} />
        </Link>
      </div>

      <Section title="Continue Learning" seeAllTo="/learn" empty="Packages you start will appear here." />
      <Section title="Recent Downloads" seeAllTo="/learn" empty="Downloaded packages will appear here." />
      <Section
        title="Explore and Download Packages"
        seeAllTo="/explore"
        empty="Connect to the internet to explore packages."
      />
    </>
  )
}

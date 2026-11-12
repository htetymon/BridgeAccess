import { Link } from 'react-router'
import { ChevronRightIcon } from './Icons'

type Props = {
  title: string
  seeAllTo?: string
  /** Shown until the section is wired to data. */
  empty: string
}

/** A titled list section with an optional "See All" link, as used across the tab screens. */
export default function Section({ title, seeAllTo, empty }: Props) {
  return (
    <section className="section">
      <div className="section__head">
        <h2>{title}</h2>
        {seeAllTo && (
          <Link to={seeAllTo} className="section__see-all">
            See All <ChevronRightIcon size={16} />
          </Link>
        )}
      </div>
      <p className="empty-state">{empty}</p>
    </section>
  )
}

import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { ChevronRightIcon } from './Icons'

type Props = {
  title: string
  seeAllTo?: string
  /** Shown when there are no children (not wired to data yet, or nothing to list). */
  empty?: string
  children?: ReactNode
}

/** A titled list section with an optional "See All" link, as used across the tab screens. */
export default function Section({ title, seeAllTo, empty, children }: Props) {
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
      {children ?? (empty && <p className="empty-state">{empty}</p>)}
    </section>
  )
}

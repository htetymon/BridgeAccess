import { NavLink } from 'react-router'
import { BookIcon, BookmarkIcon, HomeIcon, UploadIcon, type IconProps } from './Icons'

type Tab = {
  to: string
  label: string
  Icon: (props: IconProps) => React.JSX.Element
  end?: boolean
}

const TABS: Tab[] = [
  { to: '/', label: 'Home', Icon: HomeIcon, end: true },
  { to: '/learn', label: 'Learn', Icon: BookIcon },
  { to: '/contribute', label: 'Contribute', Icon: UploadIcon },
  { to: '/bookmarks', label: 'Bookmarks', Icon: BookmarkIcon },
]

export default function BottomNav() {
  return (
    <nav className="bottom-nav" aria-label="Main">
      <ul>
        {TABS.map(({ to, label, Icon, end }) => (
          <li key={to}>
            {/* NavLink sets aria-current="page" on the active tab. */}
            <NavLink to={to} end={end} className="bottom-nav__link">
              {({ isActive }) => (
                <>
                  <Icon filled={isActive} />
                  <span>{label}</span>
                </>
              )}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  )
}

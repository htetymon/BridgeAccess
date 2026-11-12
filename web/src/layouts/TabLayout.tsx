import { Outlet } from 'react-router'
import BottomNav from '../components/BottomNav'

/** The four top-level tabs: screen content plus the fixed bottom navigation. */
export default function TabLayout() {
  return (
    <>
      <main className="screen screen--tabbed">
        <Outlet />
      </main>
      <BottomNav />
    </>
  )
}

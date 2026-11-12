import { Outlet, ScrollRestoration, useNavigation } from 'react-router'

/**
 * App frame for every screen. While a lazy route chunk downloads, React Router keeps the
 * current screen visible and `navigation.state` is "loading"; a thin top bar shows progress
 * (CSS delays it slightly so fast loads do not flash).
 */
export default function RootLayout() {
  const navigation = useNavigation()
  const loading = navigation.state === 'loading'

  return (
    <div className="app">
      {loading && <div className="route-progress" role="progressbar" aria-label="Loading page" />}
      <Outlet />
      <ScrollRestoration />
    </div>
  )
}

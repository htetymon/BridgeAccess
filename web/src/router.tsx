import { createBrowserRouter, redirect } from 'react-router'
import RootLayout from './layouts/RootLayout'
import TabLayout from './layouts/TabLayout'
import RouteError from './components/RouteError'
import AppLoading from './components/AppLoading'
import { hasCompletedOnboarding } from './lib/onboarding'

/**
 * Route-level code splitting: every screen is behind `lazy`, so Vite emits one chunk per
 * screen and the browser only downloads a screen the first time it is visited. Each route
 * module exports `Component` (React Router's lazy convention).
 *
 * The layouts stay in the main bundle: they are tiny and needed on every screen.
 * Tab screens sit under TabLayout, which renders the bottom navigation. Full-screen flows
 * (Explore, Create a Package, package detail) sit outside it, so they have no bottom nav,
 * matching the designs.
 *
 * Onboarding (FR-1.1, FR-1.2): Home is the launch screen (manifest start_url), so its loader
 * sends a first-time visitor to /welcome. The loader is inline rather than in the lazy module
 * so the redirect happens before the Home chunk is fetched. Deep links (a shared package) open
 * directly; the slides are shown the next time the learner lands on Home.
 */
export const router = createBrowserRouter([
  {
    path: '/',
    Component: RootLayout,
    ErrorBoundary: RouteError,
    HydrateFallback: AppLoading,
    children: [
      {
        Component: TabLayout,
        children: [
          {
            index: true,
            loader: () => (hasCompletedOnboarding() ? null : redirect('/welcome')),
            lazy: () => import('./routes/Home'),
          },
          { path: 'learn', lazy: () => import('./routes/Learn') },
          { path: 'contribute', lazy: () => import('./routes/Contribute') },
          { path: 'bookmarks', lazy: () => import('./routes/Bookmarks') },
        ],
      },
      { path: 'welcome', lazy: () => import('./routes/Onboarding') },
      { path: 'explore', lazy: () => import('./routes/Explore') },
      { path: 'contribute/new', lazy: () => import('./routes/CreatePackage') },
      { path: 'contribute/mine', lazy: () => import('./routes/MyContributions') },
      { path: 'packages/:packageId', lazy: () => import('./routes/PackageDetail') },
      { path: '*', lazy: () => import('./routes/NotFound') },
    ],
  },
])

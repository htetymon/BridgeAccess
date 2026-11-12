import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { VitePWA } from 'vite-plugin-pwa'

const THEME = '#f6f7fb'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      // Ask before activating a new version: an automatic reload could interrupt a quiz.
      registerType: 'prompt',
      // Icons are pre-generated into public/ (npm run generate-pwa-assets). The workbox glob
      // below already precaches them, so do not add them a second time.
      includeManifestIcons: false,
      manifest: {
        id: '/',
        name: 'BridgeAccess',
        short_name: 'BridgeAccess',
        description: 'Turn online educational content into lightweight learning packages for offline study.',
        lang: 'en',
        start_url: '/',
        scope: '/',
        display: 'standalone',
        orientation: 'portrait',
        theme_color: THEME,
        background_color: THEME,
        categories: ['education'],
        icons: [
          { src: 'pwa-64x64.png', sizes: '64x64', type: 'image/png' },
          { src: 'pwa-192x192.png', sizes: '192x192', type: 'image/png' },
          { src: 'pwa-512x512.png', sizes: '512x512', type: 'image/png' },
          { src: 'maskable-icon-512x512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        // Precache the whole app shell, including every lazy route chunk (each is < 1 KB
        // gzipped), so any screen opens offline, not only the ones already visited.
        // (manifest.webmanifest is added by the plugin itself.)
        globPatterns: ['**/*.{js,css,html,svg,png,ico}'],
        // Source image for icon generation only; never requested by the app.
        globIgnores: ['logo.svg'],
        // Client-side routes (/learn, /packages/:id, ...) are served the cached index.html.
        navigateFallback: '/index.html',
        // API calls are never answered by the service worker. Package data is stored for
        // offline use by the download flow (IndexedDB), not by an HTTP cache.
        navigateFallbackDenylist: [/^\/api\//],
        cleanupOutdatedCaches: true,
      },
    }),
  ],
  server: {
    // Forward API calls to the Spring Boot backend (server.port defaults to 5000).
    proxy: {
      '/api': 'http://localhost:5000',
    },
  },
})

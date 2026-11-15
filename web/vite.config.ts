import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { VitePWA } from 'vite-plugin-pwa'

const THEME = '#f6f6f3'

/**
 * public/sw-sync.js is imported by the service worker under a URL that carries its content
 * hash. A changed handler therefore changes sw.js itself, which is what makes browsers see an
 * update, and the new URL can never be answered from a stale HTTP cache.
 */
const SW_SYNC_VERSION = createHash('sha256')
  .update(readFileSync(new URL('./public/sw-sync.js', import.meta.url)))
  .digest('hex')
  .slice(0, 12)

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
        // logo.svg: source image for icon generation only. sw-sync.js: loaded by the service
        // worker itself (importScripts below), never by the page.
        globIgnores: ['logo.svg', 'sw-sync.js'],
        // Background Sync handler for the outbox (FR-8.2): sends queued changes when the
        // connection returns, even with the app closed.
        importScripts: [`sw-sync.js?v=${SW_SYNC_VERSION}`],
        // Client-side routes (/learn, /packages/:id, ...) are served the cached index.html.
        navigateFallback: '/index.html',
        navigateFallbackDenylist: [/^\/api\//],
        cleanupOutdatedCaches: true,
        // API caching. Downloaded packages live in IndexedDB (the download flow), so the
        // service worker only helps reads that are not part of a download:
        // - catalogue reads (Explore list, package detail): network first, so they are fresh
        //   when the connection works; the last copy when offline, or after 5 s on a stalled
        //   connection. Lets Explore and a package screen seen before open offline.
        // - images shown on screen (<img>, e.g. a package cover before download): cache
        //   first, since an image URL's bytes never change. Download fetches are not <img>
        //   requests, so downloaded images are not stored twice.
        // Everything else under /api (per-device /me data, /sync, manifests, bundles, any
        // write) is not matched and always goes to the network.
        // The urlPattern functions are copied into sw.js as source text: they must not use
        // anything defined outside them (constants, imports), or the service worker breaks.
        runtimeCaching: [
          {
            // GET /api/v1/packages (Explore list) and /api/v1/packages/{id} (detail).
            urlPattern: ({ url, request, sameOrigin }) =>
              sameOrigin && request.method === 'GET' && /^\/api\/v1\/packages(\/[0-9a-f-]{36})?$/.test(url.pathname),
            handler: 'NetworkFirst',
            options: {
              cacheName: 'api-catalogue',
              networkTimeoutSeconds: 5,
              expiration: { maxEntries: 60, maxAgeSeconds: 7 * 24 * 60 * 60 },
              cacheableResponse: { statuses: [200] },
            },
          },
          {
            // GET /api/v1/packages/{id}/images/{imageId}, from an <img> only.
            urlPattern: ({ url, request, sameOrigin }) =>
              sameOrigin &&
              request.destination === 'image' &&
              /^\/api\/v1\/packages\/[0-9a-f-]{36}\/images\/[0-9a-f-]{36}$/.test(url.pathname),
            handler: 'CacheFirst',
            options: {
              cacheName: 'api-images',
              expiration: { maxEntries: 60, maxAgeSeconds: 30 * 24 * 60 * 60, purgeOnQuotaError: true },
              cacheableResponse: { statuses: [200] },
            },
          },
        ],
      },
    }),
  ],
  build: {
    rollupOptions: {
      output: {
        // Keep shared code out of the entry chunk. The entry also holds the router's list of
        // screen file names, so it is renamed whenever any screen changes; every chunk that
        // imported shared code from it would then be renamed too, and an update would make
        // users download almost the whole app again. With libraries and src/lib in chunks of
        // their own, an update only renames what changed (checked by npm run test:sw).
        manualChunks(id) {
          const path = id.replaceAll('\\', '/')
          // Bundler helpers every lazy import uses (Vite's preload helper, the runtime) are
          // virtual modules ("\0..."): left alone, they would land in the entry too.
          if (path.includes('/node_modules/') || path.startsWith('\0')) return 'vendor'
          // Shared app code. Icons are used by the always-loaded layout and by most screens.
          if (path.includes('/src/lib/') || path.endsWith('/src/components/Icons.tsx')) return 'app'
        },
      },
    },
  },
  server: {
    // Forward API calls to the Spring Boot backend (server.port defaults to 5000).
    proxy: {
      '/api': 'http://localhost:5000',
    },
  },
})

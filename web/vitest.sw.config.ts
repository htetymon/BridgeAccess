import { defineConfig } from 'vitest/config'

/**
 * `npm run test:sw`: builds the app twice and checks the generated service worker
 * (scripts/swUpdate.check.ts). Slow (two production builds), so it is kept out of `npm test`.
 */
export default defineConfig({
  test: {
    include: ['scripts/**/*.check.ts'],
    environment: 'node',
    testTimeout: 180_000,
    hookTimeout: 180_000,
  },
})

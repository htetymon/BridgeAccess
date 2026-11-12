import { defineConfig, minimal2023Preset } from '@vite-pwa/assets-generator/config'

// Icons are generated from public/logo.svg into public/ by `npm run generate-pwa-assets`.
// The source is a full-bleed blue tile, so every variant uses a blue background:
// - "transparent" icons (manifest any-purpose, favicon.ico): no padding, the tile itself.
// - maskable: extra padding so the book stays inside Android's safe zone.
// - apple-touch-icon: iOS rounds the corners itself and disallows transparency.
const BRAND_BLUE = '#0a5cff'

export default defineConfig({
  headLinkOptions: { preset: '2023' },
  preset: {
    ...minimal2023Preset,
    transparent: { ...minimal2023Preset.transparent, padding: 0 },
    maskable: { ...minimal2023Preset.maskable, padding: 0.2, resizeOptions: { background: BRAND_BLUE } },
    apple: { ...minimal2023Preset.apple, padding: 0.2, resizeOptions: { background: BRAND_BLUE } },
  },
  images: ['public/logo.svg'],
})

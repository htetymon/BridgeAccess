import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { build, type Plugin } from 'vite'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'

/**
 * Does an app update reach users? Builds the app twice, the second time with one screen
 * (Home) changed, and checks the generated service worker the way a browser sees it:
 *
 * - the new sw.js is byte-different, so the browser detects an update and shows the prompt;
 * - the changed code and index.html get new precache entries, so the update is downloaded;
 * - unchanged screens keep their entries, so returning users fetch only what changed;
 * - the Background Sync script is imported under a content-hashed URL;
 * - the API routes inside sw.js work on their own (their matchers are copied into sw.js as
 *   source text, so anything they referenced from vite.config.ts would be missing there).
 *
 * It runs the built sw.js with a stand-in for Workbox that records what it is asked to do.
 * What it cannot check: a real browser installing and activating the worker.
 */

const root = fileURLToPath(new URL('..', import.meta.url))
const outDirs: string[] = []

type Entry = { url: string; revision: string | null }
type MatchContext = { url: URL; request: { method: string; destination: string }; sameOrigin: boolean }
type Route = { match: (ctx: MatchContext) => boolean; handler: string }
type Worker = { source: string; precache: Entry[]; routes: Route[]; imports: string[] }

/** Changes the Home screen's code in the build, like a code change between releases. */
function changeHome(mark: string): Plugin {
  return {
    name: 'change-home',
    transform(code, id) {
      if (id.replace(/\\/g, '/').endsWith('/src/routes/Home.tsx')) {
        return `${code}\nconsole.debug(${JSON.stringify(mark)})\n`
      }
    },
  }
}

async function buildApp(mark: string): Promise<Worker> {
  const outDir = mkdtempSync(join(tmpdir(), 'bridgeaccess-sw-'))
  outDirs.push(outDir)
  await build({ root, logLevel: 'silent', build: { outDir, emptyOutDir: true }, plugins: [changeHome(mark)] })
  return runWorker(readFileSync(join(outDir, 'sw.js'), 'utf8'))
}

/** Executes sw.js with a fake `define` and Workbox, recording precache entries and routes. */
function runWorker(source: string): Worker {
  const worker: Worker = { source, precache: [], routes: [], imports: [] }
  class NetworkFirst {}
  class CacheFirst {}
  // Called both as a function (cleanupOutdatedCaches()) and as a class (new ExpirationPlugin()).
  function NoOp() {}
  const known: Record<string, unknown> = {
    precacheAndRoute: (entries: Entry[]) => worker.precache.push(...entries),
    registerRoute: (match: unknown, handler: object) => {
      if (typeof match === 'function') {
        worker.routes.push({ match: match as Route['match'], handler: handler.constructor.name })
      }
    },
    NetworkFirst,
    CacheFirst,
  }
  // Anything else Workbox offers (plugins, NavigationRoute, cleanup, ...) is a harmless no-op.
  const workbox = new Proxy(known, {
    get: (target, key) => (typeof key === 'string' && key in target ? target[key] : key === 'then' ? undefined : NoOp),
  })
  const define = (_deps: string[], factory: (wb: unknown) => void) => factory(workbox)
  const self = { addEventListener: () => {}, skipWaiting: () => {}, define }
  const importScripts = (...urls: string[]) => worker.imports.push(...urls)
  // sw.js starts with a small module loader guarded by `if(!self.define)`; ours is set, so it is skipped.
  new Function('self', 'define', 'importScripts', source)(self, define, importScripts)
  return worker
}

let before: Worker
let after: Worker

beforeAll(async () => {
  before = await buildApp('release 1')
  after = await buildApp('release 2')
})

afterAll(() => {
  for (const dir of outDirs) rmSync(dir, { recursive: true, force: true })
})

const entry = (w: Worker, name: RegExp) => w.precache.find((e) => name.test(e.url))

describe('an app update reaches users', () => {
  it('the new sw.js differs, so browsers detect the update', () => {
    expect(after.source).not.toBe(before.source)
  })

  it('changed code and index.html get new precache entries', () => {
    expect(entry(after, /^assets\/Home-/)!.url).not.toBe(entry(before, /^assets\/Home-/)!.url)
    expect(entry(after, /^index\.html$/)!.revision).not.toBe(entry(before, /^index\.html$/)!.revision)
  })

  it('only what changed is downloaded again: the Home chunk and the entry', () => {
    // Every other script keeps its URL, so the browser reuses its precached copy. If shared
    // code moved back into the entry (see manualChunks in vite.config.ts), most chunks would
    // be renamed by any change and this would list them.
    const oldScripts = new Set(before.precache.filter((e) => e.url.endsWith('.js')).map((e) => e.url))
    const renamed = after.precache
      .filter((e) => e.url.endsWith('.js') && !oldScripts.has(e.url))
      .map((e) => e.url.replace(/-[\w-]{8}\.js$/, ''))
    expect(renamed.sort()).toEqual(['assets/Home', 'assets/index'])
  })

  it('precaches the shell: index.html, the manifest and every screen', () => {
    const urls = after.precache.map((e) => e.url)
    expect(urls).toContain('index.html')
    expect(urls).toContain('manifest.webmanifest')
    for (const screen of ['Home', 'Learn', 'Explore', 'PackageDetail', 'PackageViewer', 'PackageReview']) {
      expect(urls.some((u) => u.startsWith(`assets/${screen}-`))).toBe(true)
    }
    expect(urls).not.toContain('sw-sync.js')
  })

  it('imports the Background Sync script under a content-hashed URL', () => {
    expect(after.imports).toEqual([expect.stringMatching(/^sw-sync\.js\?v=[0-9a-f]{12}$/)])
    expect(after.imports).toEqual(before.imports) // unchanged script, unchanged URL
  })
})

describe('API caching in the built service worker', () => {
  const id = '0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10'
  const img = '8e2b7a64-1c50-4f3d-9d6a-0b7e5c3a2f19'
  const handlerFor = (path: string, method = 'GET', destination = '') => {
    const ctx: MatchContext = { url: new URL(path, 'https://app.example'), request: { method, destination }, sameOrigin: true }
    return after.routes.find((r) => r.match(ctx))?.handler ?? 'network only'
  }

  it('catalogue reads: network first, falling back to the cached copy', () => {
    expect(handlerFor('/api/v1/packages?subject=Science')).toBe('NetworkFirst')
    expect(handlerFor(`/api/v1/packages/${id}`)).toBe('NetworkFirst')
  })

  it('images shown on screen: cache first; download fetches of images: network only', () => {
    expect(handlerFor(`/api/v1/packages/${id}/images/${img}`, 'GET', 'image')).toBe('CacheFirst')
    expect(handlerFor(`/api/v1/packages/${id}/images/${img}`, 'GET', '')).toBe('network only')
  })

  it('per-device data, sync, downloads and writes always go to the network', () => {
    expect(handlerFor('/api/v1/me/packages')).toBe('network only')
    expect(handlerFor(`/api/v1/packages/${id}/bundle`)).toBe('network only')
    expect(handlerFor(`/api/v1/packages/${id}/manifest`)).toBe('network only')
    expect(handlerFor('/api/v1/sync', 'POST')).toBe('network only')
    expect(handlerFor('/api/v1/packages', 'POST')).toBe('network only')
  })
})

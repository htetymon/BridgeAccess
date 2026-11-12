/**
 * Anonymous device identity (PRD FR-1.3). A random UUID is created on first launch, stored
 * locally, and sent as X-Device-Id on every API request. It is a pseudonymous identifier,
 * not a credential, and contains no personal data.
 */

const STORAGE_KEY = 'bridgeaccess.deviceId'

/** Canonical 8-4-4-4-12 form; the backend rejects anything else with INVALID_DEVICE_ID. */
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

let cached: string | null = null

/**
 * Returns this device's ID, creating and storing one if needed. If storage is unavailable
 * (some private-browsing modes), the ID lives in memory for this session only.
 */
export function getDeviceId(): string {
  if (cached) return cached

  const stored = readStorage()
  if (stored && UUID_RE.test(stored)) {
    cached = stored.toLowerCase()
    return cached
  }

  cached = randomUuid()
  writeStorage(cached)
  return cached
}

/** Version-4 UUID. Falls back to getRandomValues where randomUUID is missing (plain-HTTP LAN testing). */
export function randomUuid(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()

  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40 // version 4
  bytes[8] = (bytes[8] & 0x3f) | 0x80 // RFC 4122 variant
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

function readStorage(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

function writeStorage(value: string) {
  try {
    localStorage.setItem(STORAGE_KEY, value)
  } catch {
    // Storage blocked or full: keep the in-memory ID for this session.
  }
}

/** Test hook: forget the cached value so the next call re-reads storage. */
export function resetDeviceIdCacheForTests() {
  cached = null
}

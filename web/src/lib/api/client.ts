import { getDeviceId, randomUuid } from '../deviceId'

/**
 * Fetch wrapper for the BridgeAccess API (/api/v1).
 *
 * - Every request carries X-Device-Id and an X-Request-Id (one per logical request, reused
 *   across retries so server logs group the attempts).
 * - Each attempt has a timeout (default 15 s, tuned for slow mobile networks).
 * - Transient failures are retried with exponential backoff and full jitter, honouring
 *   Retry-After. Only idempotent methods retry by default (API doc, section 9).
 * - Failures surface as ApiError with a `kind` the UI can branch on.
 *
 * ETag / 304 needs no code here: the browser HTTP cache revalidates GETs automatically.
 */

export const API_BASE = '/api/v1'

export type ApiErrorKind = 'offline' | 'timeout' | 'network' | 'http' | 'aborted'

export type ApiErrorDetail = { field?: string; code: string; message: string }

export class ApiError extends Error {
  readonly kind: ApiErrorKind
  /** HTTP status, when the server answered. */
  readonly status?: number
  /** Contract error code, e.g. NOT_FOUND or VALIDATION_FAILED (API doc, section 10). */
  readonly code?: string
  readonly details?: ApiErrorDetail[]
  readonly requestId: string

  constructor(init: {
    kind: ApiErrorKind
    message: string
    requestId: string
    status?: number
    code?: string
    details?: ApiErrorDetail[]
  }) {
    super(init.message)
    this.name = 'ApiError'
    this.kind = init.kind
    this.status = init.status
    this.code = init.code
    this.details = init.details
    this.requestId = init.requestId
  }
}

export type RetryOptions = {
  /** Total attempts including the first. 1 disables retry. */
  attempts: number
  /** First backoff ceiling in ms; doubles each retry. */
  baseDelayMs: number
  /** Upper bound for any single wait, including Retry-After. */
  maxDelayMs: number
}

export type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  /** Query parameters; undefined, null and '' are omitted. */
  query?: Record<string, string | number | boolean | null | undefined>
  /** JSON body (serialised) or FormData (multipart, e.g. POST /packages). */
  body?: unknown
  /** Per-attempt timeout in ms. */
  timeoutMs?: number
  /**
   * Retry policy. Defaults to DEFAULT_RETRY for GET/PUT/DELETE and to no retry for POST,
   * because POST /packages is not idempotent. Pass a policy to opt a POST in (for example
   * POST /me/sync, which is idempotent by design), or `false` to disable.
   */
  retry?: Partial<RetryOptions> | false
  /** Caller cancellation (for example when a screen unmounts). Never retried. */
  signal?: AbortSignal
  headers?: Record<string, string>
}

export const DEFAULT_TIMEOUT_MS = 15_000
export const DEFAULT_RETRY: RetryOptions = { attempts: 3, baseDelayMs: 500, maxDelayMs: 8_000 }

/** Server and gateway conditions that are worth another try. */
const RETRYABLE_STATUS = new Set([408, 429, 500, 502, 503, 504])

const OFFLINE_MESSAGE = "You're offline. Connect to the internet and try again."
const GENERIC_MESSAGE = 'Something went wrong. Please try again.'

/** Seam for tests; production uses global fetch and setTimeout-based sleep. */
export const transport = {
  fetch: (input: string, init: RequestInit) => fetch(input, init),
  sleep: (ms: number, signal?: AbortSignal) =>
    new Promise<void>((resolve, reject) => {
      if (signal?.aborted) return reject(signal.reason)
      const id = setTimeout(resolve, ms)
      signal?.addEventListener(
        'abort',
        () => {
          clearTimeout(id)
          reject(signal.reason)
        },
        { once: true },
      )
    }),
  // Only an explicit `false` means offline (onLine can be undefined outside browsers).
  isOnline: () => typeof navigator === 'undefined' || navigator.onLine !== false,
  random: () => Math.random(),
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? 'GET'
  const retry = resolveRetry(method, options.retry)
  const requestId = randomUuid()
  const url = buildUrl(path, options.query)

  for (let attempt = 1; ; attempt++) {
    if (!transport.isOnline()) {
      throw new ApiError({ kind: 'offline', message: OFFLINE_MESSAGE, requestId })
    }

    let error: ApiError
    let retryAfterMs: number | undefined
    try {
      const response = await attemptOnce(url, method, options, requestId)
      if (response.ok) return parseJson(response, requestId) as T
      error = httpError(response, requestId)
      retryAfterMs = parseRetryAfter(response.headers.get('Retry-After'))
    } catch (e) {
      if (e instanceof ApiError) error = e
      else throw e
    }

    const retryable =
      error.kind === 'timeout' ||
      error.kind === 'network' ||
      (error.kind === 'http' && RETRYABLE_STATUS.has(error.status!))
    if (!retryable || attempt >= retry.attempts) throw error

    const backoff = transport.random() * Math.min(retry.maxDelayMs, retry.baseDelayMs * 2 ** (attempt - 1))
    const wait = Math.min(retry.maxDelayMs, Math.max(backoff, retryAfterMs ?? 0))
    try {
      await transport.sleep(wait, options.signal)
    } catch {
      throw new ApiError({ kind: 'aborted', message: 'Request cancelled.', requestId })
    }
  }
}

type RawResponse = { ok: boolean; status: number; headers: Headers; text: string }

/**
 * One HTTP attempt with its own timeout, linked to the caller's signal. The body is read
 * inside the attempt so the timeout also covers a response that stalls mid-body.
 */
async function attemptOnce(
  url: string,
  method: string,
  options: RequestOptions,
  requestId: string,
): Promise<RawResponse> {
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const controller = new AbortController()
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)
  const onCallerAbort = () => controller.abort()
  if (options.signal?.aborted) controller.abort()
  options.signal?.addEventListener('abort', onCallerAbort, { once: true })

  const headers: Record<string, string> = {
    Accept: 'application/json',
    'X-Device-Id': getDeviceId(),
    'X-Request-Id': requestId,
    ...options.headers,
  }
  let body: BodyInit | undefined
  if (options.body instanceof FormData) {
    body = options.body // the browser sets the multipart boundary
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(options.body)
  }

  try {
    const response = await transport.fetch(url, { method, headers, body, signal: controller.signal })
    const text = await response.text()
    return { ok: response.ok, status: response.status, headers: response.headers, text }
  } catch {
    if (options.signal?.aborted) {
      throw new ApiError({ kind: 'aborted', message: 'Request cancelled.', requestId })
    }
    if (timedOut) {
      throw new ApiError({ kind: 'timeout', message: 'The connection is slow. Please try again.', requestId })
    }
    throw new ApiError({
      kind: transport.isOnline() ? 'network' : 'offline',
      message: transport.isOnline() ? GENERIC_MESSAGE : OFFLINE_MESSAGE,
      requestId,
    })
  } finally {
    clearTimeout(timer)
    options.signal?.removeEventListener('abort', onCallerAbort)
  }
}

function resolveRetry(method: string, retry: RequestOptions['retry']): RetryOptions {
  if (retry === false) return { ...DEFAULT_RETRY, attempts: 1 }
  if (retry) return { ...DEFAULT_RETRY, ...retry }
  return method === 'POST' ? { ...DEFAULT_RETRY, attempts: 1 } : DEFAULT_RETRY
}

function buildUrl(path: string, query: RequestOptions['query']): string {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value !== undefined && value !== null && value !== '') params.set(key, String(value))
  }
  const qs = params.toString()
  return `${API_BASE}${path}${qs ? `?${qs}` : ''}`
}

function parseJson(response: RawResponse, requestId: string): unknown {
  if (!response.text) return undefined // 204 No Content, or an empty 200
  try {
    return JSON.parse(response.text)
  } catch {
    throw new ApiError({ kind: 'http', status: response.status, message: GENERIC_MESSAGE, requestId })
  }
}

/** Maps the API error envelope ({ error: { code, message, details, requestId } }). */
function httpError(response: RawResponse, requestId: string): ApiError {
  let envelope: { error?: { code?: string; message?: string; details?: ApiErrorDetail[]; requestId?: string } } = {}
  try {
    envelope = JSON.parse(response.text)
  } catch {
    // Not our envelope (proxy or gateway error page).
  }
  const e = envelope.error ?? {}
  return new ApiError({
    kind: 'http',
    status: response.status,
    code: e.code,
    message: e.message ?? GENERIC_MESSAGE,
    details: e.details,
    requestId: e.requestId ?? requestId,
  })
}

/** Retry-After is either delay-seconds or an HTTP date. */
export function parseRetryAfter(value: string | null): number | undefined {
  if (!value) return undefined
  const seconds = Number(value)
  if (Number.isFinite(seconds)) return Math.max(0, seconds * 1000)
  const date = Date.parse(value)
  return Number.isNaN(date) ? undefined : Math.max(0, date - Date.now())
}

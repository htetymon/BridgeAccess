# BridgeAccess API Documentation

Version 1.0 (draft) · Base path `/api/v1` · Companion to the BridgeAccess PRD and `bridgeaccess_schema_full.sql`

BridgeAccess turns articles and YouTube videos into small, offline-ready **learning packages**. This API serves the PWA: it lists and delivers packages, accepts contributions, runs the AI generation job, and stores learner data (progress, quiz attempts, bookmarks, downloads) keyed by an anonymous device ID.

> **Status of this document.** The PRD fixes the stack (Spring Boot, Supabase Postgres, Gemini) and the data model, but not the exact endpoints. The routes, payloads, and error codes below are a proposed contract derived from the PRD and the schema. Anything that is a judgment call is listed in [section 12](#12-assumptions-and-open-decisions).

---

## Contents

1. [Conventions](#1-conventions)
2. [Identity](#2-identity)
3. [Enums and constants](#3-enums-and-constants)
4. [Meta endpoints](#4-meta-endpoints)
5. [Catalogue endpoints (Explore and Download)](#5-catalogue-endpoints)
6. [Download endpoints](#6-download-endpoints)
7. [Learner data endpoints](#7-learner-data-endpoints)
8. [Contribution endpoints](#8-contribution-endpoints)
9. [Batch sync endpoint](#9-batch-sync-endpoint)
10. [Error codes](#10-error-codes)
11. [Key flows](#11-key-flows)
12. [Assumptions and open decisions](#12-assumptions-and-open-decisions)
13. [Appendix: screen to endpoint map](#13-appendix-screen-to-endpoint-map)
14. [Appendix: database rules the API relies on](#14-appendix-database-rules-the-api-relies-on)

---

## 1. Conventions

| Topic | Rule |
|---|---|
| Transport | HTTPS only. |
| Format | JSON (`application/json; charset=utf-8`), except image upload (`multipart/form-data`) and image download (binary). |
| Field naming | `camelCase` in JSON. The database uses `snake_case`; the API maps between them. |
| IDs | UUID v4 strings. |
| Timestamps | ISO 8601 UTC, for example `2026-11-10T08:30:00Z`. |
| Compression | Responses are gzipped when the client sends `Accept-Encoding: gzip`. |
| Caching | `GET` responses for catalogue data send `ETag`. Clients send `If-None-Match` and receive `304 Not Modified` with no body when unchanged. |
| Pagination | Keyset (cursor) pagination. `limit` defaults to 20, maximum 50. |
| Unknown fields | Ignored on input. Never returned on output. |
| Size discipline | List responses carry no images and no long text. A text bundle targets 30 KB or less. |

### Request headers

| Header | Required | Description |
|---|---|---|
| `X-Device-Id` | Yes, on every endpoint except `GET /health` | Anonymous device UUID. See [section 2](#2-identity). |
| `Accept-Encoding` | Recommended | `gzip`. |
| `If-None-Match` | Optional | ETag from a previous response. |
| `X-Request-Id` | Optional | Client-generated UUID for log correlation. Echoed in the response. |

### Pagination envelope

```json
{
  "items": [ ],
  "nextCursor": "eyJwIjoiMjAyNi0xMS0xMFQwODozMDowMFoiLCJpIjoiLi4uIn0"
}
```

`nextCursor` is an opaque string. It is `null` when there are no more items. Send it back as `?cursor=`. The Explore feed orders by `(published_at desc, id desc)`, which is what the keyset cursor encodes.

### Error envelope

Every non-2xx response has this shape:

```json
{
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "Some fields need attention.",
    "details": [
      { "field": "resourceLink", "code": "INVALID_URL", "message": "Enter a link that starts with https://" }
    ],
    "requestId": "7f0f4c1e-5b6e-4a87-9b8f-1a2d3c4e5f60"
  }
}
```

`message` is safe to show to a learner. `details` is present only for validation errors. The full code list is in [section 10](#10-error-codes).

### Rate limits

| Scope | Limit (proposed) | Failure |
|---|---|---|
| All endpoints, per device and IP | 120 requests per minute | `429 RATE_LIMITED` with `Retry-After` |
| `POST /packages`, per device | 5 per day (server-configurable, FR-5.10) | `429 DAILY_LIMIT_REACHED` |

---

## 2. Identity

The MVP has no accounts. On first launch the PWA generates a random UUID (`crypto.randomUUID()`), stores it locally, and sends it in `X-Device-Id` on every request (FR-1.3). No personal data is collected (FR-1.4).

- A malformed or missing header returns `400 INVALID_DEVICE_ID`.
- A device can only read and write its own learner data and its own contributions.
- Acting on someone else's package (delete, retry) returns `404 NOT_FOUND`, not `403`, so ownership is not revealed.
- The UUID is unguessable but it is not a credential. Treat it as a pseudonymous identifier and plan real authentication after the MVP.

---

## 3. Enums and constants

| Name | Values |
|---|---|
| `resourceType` | `text`, `video` |
| `packageType` | `text_only`, `with_visual` |
| `status` (package) | `processing`, `published`, `failed` |
| `imageSource` | `contributor`, `ai_diagram` |
| `subject` | `Science`, `Health`, `Math`, `Technology`, `Language`, `History`, `Other` |
| Learning `step` | `summary`, `key_concepts`, `diagram`, `quiz` |
| `downloadStatus` | `started`, `completed`, `failed` |
| Sort | `latest_published` (Explore). Learn lists sort locally. |

**Package type rule.** `with_visual` when the contributor attached at least one image, otherwise `text_only`. The database sets this automatically.

**Learning steps.** `text_only` packages have 3 steps (`summary`, `key_concepts`, `quiz`). `with_visual` packages have 4 (adds `diagram`). The server returns the exact list in `steps`, and the client renders from it.

**Image rules.** Up to 3 contributor images, 500 KB each, PNG, JPG, or WebP. The server re-encodes every image to WebP at about 80 KB or less. The AI diagram, when present, is position 0.

**Quiz rules.** Exactly 5 questions, 4 options each, one correct option (`correctIndex` 0 to 3), one explanation each.

---

## 4. Meta endpoints

### GET /health

Liveness check and keep-warm ping. No `X-Device-Id` needed.

**200**

```json
{ "status": "ok", "time": "2026-11-11T05:00:00Z" }
```

### GET /config

Runtime settings so the client never hard-codes values that the PRD marks as configurable. Cache it locally and refresh when online.

**200**

```json
{
  "passMarkPercent": 60,
  "dailyCreateLimit": 5,
  "maxImages": 3,
  "maxImageBytes": 512000,
  "maxContentChars": 10000,
  "pageSize": 20,
  "largeDownloadBytes": 1048576,
  "subjects": ["Science", "Health", "Math", "Technology", "Language", "History", "Other"]
}
```

---

## 5. Catalogue endpoints

These serve Explore and Download, the Contribute screen's Community Packages, and package detail. They return only `published`, non-deleted packages.

### GET /packages

List published packages. Screens: Home (Explore preview), Explore and Download, Contribute (Community Packages).

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `q` | string | none | Case-insensitive substring search on AI title, original title, and author. Minimum 2 characters. |
| `subject` | enum | none | Filter by subject. |
| `resourceType` | enum | none | `text` or `video`. |
| `packageType` | enum | none | `text_only` or `with_visual`. |
| `sort` | enum | `latest_published` | Only `latest_published` in the MVP. |
| `excludeMine` | boolean | `false` | Hide packages contributed by this device. |
| `limit` | integer | 20 | 1 to 50. |
| `cursor` | string | none | From `nextCursor`. |

**200**

```json
{
  "items": [
    {
      "id": "0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10",
      "title": "Renewable Energy Basics",
      "subject": "Science",
      "resourceType": "video",
      "packageType": "with_visual",
      "sizeBytes": 850000,
      "publishedAt": "2026-11-08T10:15:00Z",
      "isDownloaded": false,
      "isOwn": false
    }
  ],
  "nextCursor": null
}
```

`isDownloaded` reflects this device's server-side download record (`completed`, not removed). The client's local store is the source of truth offline.

**Errors:** `400 VALIDATION_FAILED` (bad enum, `q` too short, bad cursor).

### GET /packages/{packageId}

Full metadata for one published package. Used by the package screen before download, and for the Learn and Details tabs.

**200**

```json
{
  "id": "0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10",
  "title": "Renewable Energy Basics",
  "overview": "A beginner-friendly introduction to renewable energy, including its main types, benefits, and role in a sustainable future.",
  "keyTakeaways": [
    "Understand what renewable energy is",
    "Explore common types (solar, wind, hydro, etc.)",
    "Learn its key benefits",
    "See why it matters for a sustainable future"
  ],
  "subject": "Science",
  "resourceType": "text",
  "packageType": "with_visual",
  "steps": ["summary", "key_concepts", "diagram", "quiz"],
  "original": {
    "title": "Renewable Energy 101: A Beginner's Guide",
    "author": "Laura Mitchell",
    "link": "https://example.org/renewable-energy-101"
  },
  "sizeBytes": 850000,
  "imageCount": 2,
  "publishedAt": "2026-11-08T10:15:00Z",
  "isAiGenerated": true
}
```

`isAiGenerated` is always `true` and drives the AI label (FR-7.9). Supports `ETag` and `304`.

**Errors:** `404 NOT_FOUND` (unknown, unpublished, or deleted).

### GET /packages/{packageId}/bundle

The complete text payload for offline use. One small JSON file, gzipped, target 30 KB or less. Includes the correct answers and explanations, because grading happens on the device with no connection (FR-7.5).

**200**

```json
{
  "schemaVersion": 1,
  "package": {
    "id": "0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10",
    "title": "Renewable Energy Basics",
    "subject": "Science",
    "resourceType": "text",
    "packageType": "with_visual",
    "steps": ["summary", "key_concepts", "diagram", "quiz"],
    "original": {
      "title": "Renewable Energy 101: A Beginner's Guide",
      "author": "Laura Mitchell",
      "link": "https://example.org/renewable-energy-101"
    },
    "sizeBytes": 850000,
    "publishedAt": "2026-11-08T10:15:00Z"
  },
  "content": {
    "overview": "A beginner-friendly introduction ...",
    "keyTakeaways": ["...", "...", "..."],
    "summary": "Renewable energy comes from sources that replenish naturally ...",
    "keyConcepts": [
      { "term": "Solar power", "explanation": "Energy captured from sunlight ..." }
    ]
  },
  "quiz": {
    "questions": [
      {
        "id": "5d1c0f0a-7a43-4b77-9a0e-2f6f4a8e9c11",
        "position": 1,
        "question": "What is renewable energy?",
        "options": ["Option A", "Option B", "Option C", "Option D"],
        "correctIndex": 0,
        "category": "Basics",
        "explanation": "Renewable energy comes from sources that naturally replenish."
      }
    ]
  },
  "images": [
    {
      "id": "8e2b7a64-1c50-4f3d-9d6a-0b7e5c3a2f19",
      "source": "contributor",
      "position": 1,
      "mimeType": "image/webp",
      "sizeBytes": 61240,
      "altText": "Wind turbines and solar panels on green hills",
      "url": "/api/v1/packages/0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10/images/8e2b7a64-1c50-4f3d-9d6a-0b7e5c3a2f19"
    }
  ]
}
```

- `keyConcepts` has 4 to 6 items. `quiz.questions` has exactly 5, ordered by `position`.
- `images` is empty for `text_only` packages. The AI diagram, when it exists, has `source: "ai_diagram"` and `position: 0`.
- `sizeBytes` in `package` is the total for bundle plus images, and drives download progress.
- Supports `ETag` and `304`.

**Errors:** `404 NOT_FOUND`.

### GET /packages/{packageId}/images/{imageId}

Binary image (WebP in almost all cases). The Storage bucket is private, so the backend streams the file or redirects to a short-lived signed URL.

| Response header | Value |
|---|---|
| `Content-Type` | `image/webp`, `image/png`, or `image/jpeg` |
| `Content-Length` | Size in bytes |
| `ETag` | Stable per image |
| `Cache-Control` | `private, max-age=31536000, immutable` |
| `Accept-Ranges` | `bytes` |

Supports `Range` requests (`206 Partial Content`) so an interrupted download resumes without restarting (FR-4.3).

**Errors:** `404 NOT_FOUND`, `416 RANGE_NOT_SATISFIABLE`.

---

## 6. Download endpoints

Downloading is performed by the client (bundle plus images), then **recorded** here. A package counts as downloaded only after every part is stored locally (FR-4.4), so the client calls `PUT` with `completed` only at the end.

### PUT /me/downloads/{packageId}

Create or update this device's download record. Idempotent.

**Request**

```json
{
  "status": "completed",
  "sizeBytes": 850000,
  "downloadedAt": "2026-11-10T09:00:00Z"
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `status` | `started` / `completed` / `failed` | Yes | `completed` is sticky: a later `started` for the same package does not downgrade it unless the download was removed first. |
| `sizeBytes` | integer, 0 or more | Yes | Actual stored size. |
| `downloadedAt` | timestamp | No | Client time, may be offline. Defaults to server time. |

Re-downloading a previously removed package clears `removedAt`.

**200**

```json
{
  "packageId": "0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10",
  "status": "completed",
  "sizeBytes": 850000,
  "downloadedAt": "2026-11-10T09:00:00Z"
}
```

**Errors:** `400 VALIDATION_FAILED`, `404 NOT_FOUND` (package not published).

### DELETE /me/downloads/{packageId}

Mark the package as removed from this device (sets `removedAt`). The learner's progress, quiz attempts, and bookmark are kept (FR-3.3). The client deletes the local copy itself.

**204** No body. Deleting something already removed also returns 204.

### GET /me/downloads

This device's active downloads, newest first. Used to restore state, for example after clearing local storage.

Query: `limit`, `cursor`.

**200**

```json
{
  "items": [
    { "packageId": "0b9d...", "status": "completed", "sizeBytes": 850000, "downloadedAt": "2026-11-10T09:00:00Z" }
  ],
  "nextCursor": null
}
```

---

## 7. Learner data endpoints

All keyed by `X-Device-Id`. Reads are for restore and reporting. The app itself reads local data first and works offline (FR-8.1).

### Progress

#### PUT /me/progress/{packageId}

Merge learning progress. Idempotent and monotonic (FR-8.3):

- `completedSteps` is **unioned** with what is stored. A step never becomes uncompleted.
- `lastStep` and `lastOpenedAt` move forward only. A stale event (older `lastOpenedAt`) cannot rewind the resume point.
- The client cannot set Learnt. Learnt is granted only by a passing quiz attempt.

**Request**

```json
{
  "completedSteps": ["summary", "key_concepts"],
  "lastStep": "diagram",
  "lastOpenedAt": "2026-11-10T09:12:00Z"
}
```

**200**

```json
{
  "packageId": "0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10",
  "completedSteps": ["key_concepts", "summary"],
  "lastStep": "diagram",
  "lastOpenedAt": "2026-11-10T09:12:00Z",
  "isLearnt": false,
  "learntAt": null
}
```

**Errors:** `400 VALIDATION_FAILED` (a step outside the four allowed values), `404 NOT_FOUND`.

#### GET /me/progress

All progress rows for this device, newest opened first. Query: `limit`, `cursor`. Items have the same shape as the `PUT` response.

#### GET /me/progress/{packageId}

One row. `404 NOT_FOUND` if the learner has not opened the package.

### Quiz attempts

#### POST /me/quiz-attempts

Record a finished quiz. The **client generates the attempt `id`**, so replaying the same request after a dropped connection never creates a duplicate (FR-8.3).

The server does not trust the client's score. It grades each answer against `correctIndex`, recomputes `score` and `passed`, and stores the result. A passing attempt grants Learnt (FR-7.6).

**Request**

```json
{
  "id": "c3f5b2a1-6d4e-4f08-8a7b-9e1d2c3b4a5f",
  "packageId": "0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10",
  "attemptedAt": "2026-11-10T09:20:00Z",
  "passMarkPercent": 60,
  "answers": [
    { "questionId": "5d1c0f0a-7a43-4b77-9a0e-2f6f4a8e9c11", "selectedIndex": 0 },
    { "questionId": "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d", "selectedIndex": 2 }
  ]
}
```

| Field | Rule |
|---|---|
| `id` | UUID, required. |
| `answers` | Exactly one answer per question of the package. For the MVP that is 5 (all questions must be answered, FR-7.5). |
| `selectedIndex` | 0 to 3. |
| `passMarkPercent` | 0 to 100. Use the value from `GET /config` so an offline result matches what the learner saw. |

**201 Created** (first time) or **200 OK** (same `id` replayed, returns the stored result)

```json
{
  "id": "c3f5b2a1-6d4e-4f08-8a7b-9e1d2c3b4a5f",
  "packageId": "0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10",
  "score": 4,
  "totalQuestions": 5,
  "percentage": 80.0,
  "passMarkPercent": 60,
  "passed": true,
  "attemptedAt": "2026-11-10T09:20:00Z",
  "isLearnt": true,
  "answers": [
    { "questionId": "5d1c0f0a-7a43-4b77-9a0e-2f6f4a8e9c11", "selectedIndex": 0, "isCorrect": true }
  ]
}
```

**Errors:** `400 VALIDATION_FAILED` (wrong number of answers, question not in package, index out of range), `404 NOT_FOUND` (package), `409 ATTEMPT_ID_CONFLICT` (that `id` already belongs to another device).

#### GET /me/quiz-attempts

Query: `packageId` (optional), `limit`, `cursor`. Newest first. Items have the shape of the `POST` response.

### Bookmarks

#### PUT /me/bookmarks/{packageId}

Add a bookmark. Idempotent. **204** (no body). `404 NOT_FOUND` if the package is not published.

#### DELETE /me/bookmarks/{packageId}

Remove a bookmark. Idempotent. **204**.

#### GET /me/bookmarks

Bookmarked packages, newest bookmark first. Query: `limit`, `cursor`.

**200**

```json
{
  "items": [
    {
      "bookmarkedAt": "2026-11-10T09:30:00Z",
      "package": {
        "id": "0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10",
        "title": "Renewable Energy Basics",
        "subject": "Science",
        "resourceType": "video",
        "packageType": "with_visual",
        "sizeBytes": 850000,
        "publishedAt": "2026-11-08T10:15:00Z",
        "isDownloaded": true,
        "isOwn": false
      }
    }
  ],
  "nextCursor": null
}
```

Bookmarks of packages that were later unpublished or deleted are omitted from this list.

---

## 8. Contribution endpoints

Creating needs a connection (FR-5.9). The package is created in `processing` and an asynchronous job calls Gemini. The client polls for the result.

### POST /packages

Create a package from a text or video resource. Responds immediately with `202 Accepted`.

**Content-Type:** `multipart/form-data`

| Part | Type | Required | Description |
|---|---|---|---|
| `data` | JSON (`application/json`) | Yes | Metadata, see below. |
| `images` | file, repeatable, up to 3 | No | PNG, JPG, or WebP, 500 KB each. Compress on the device first (FR-5.6). |

**`data` fields**

| Field | Type | Required | Rules |
|---|---|---|---|
| `resourceType` | `text` / `video` | Yes | |
| `originalTitle` | string | Yes | 1 to 300 characters after trimming. For video, the Video Title. |
| `author` | string | Yes | 1 to 200 characters after trimming. For video, the Channel Name. |
| `resourceLink` | string | Yes | Must start with `http://` or `https://`. For `video`, must be a `youtube.com` or `youtu.be` link. |
| `content` | string | No | Up to 10,000 characters. Pasted article text, or the transcript for video. |
| `imageAltTexts` | string[] | No | Same order as `images`. Optional alt text per image. |

**Example `data` part**

```json
{
  "resourceType": "text",
  "originalTitle": "Renewable Energy 101: A Beginner's Guide",
  "author": "Laura Mitchell",
  "resourceLink": "https://example.org/renewable-energy-101",
  "content": "Renewable energy comes from sources that ...",
  "imageAltTexts": ["Wind turbines and solar panels"]
}
```

**202 Accepted**

`Location: /api/v1/me/packages/{id}`

```json
{
  "id": "0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10",
  "status": "processing",
  "createdAt": "2026-11-10T08:30:00Z"
}
```

**Errors**

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | A rule above failed. `details` lists each field. |
| 409 | `DUPLICATE_LINK` | A live package with the same normalised link exists. Body adds `existingPackageId` and `isOwn`. If `isOwn` is true (for example a retry after a timeout), treat it as success and follow that package. |
| 413 | `IMAGE_TOO_LARGE` | An image exceeds 500 KB. |
| 415 | `UNSUPPORTED_IMAGE_TYPE` | Not PNG, JPG, or WebP. |
| 422 | `TOO_MANY_IMAGES` | More than 3 images. |
| 429 | `DAILY_LIMIT_REACHED` | The device reached its daily creation limit. Body adds `limit` and `resetsAt`. |

**What the job does** (server side, for reference)

1. Use `content` if present. Otherwise fetch the link, with SSRF protection. If the page cannot be read, fail with a message asking for pasted content. The AI never invents content (FR-6.3).
2. Ask Gemini for: learner-friendly title, subject, overview, 3 to 5 key takeaways, a 100 to 150 word summary, 4 to 6 key concepts, and exactly 5 quiz questions with explanations. Write in the AI's own words, use only facts from the source (FR-6.1, FR-6.7).
3. Validate (question count, 4 options each, answer index, subject). On failure retry once, then mark `failed` (FR-6.4).
4. Optimise images to WebP at about 80 KB or less (FR-6.5). If the package has images, generate one diagram. A diagram failure does not block publishing (FR-6.2, FR-6.6).
5. Set `status = published`. The database refuses to publish unless content exists and the quiz has exactly 5 questions.

Jobs stuck in `processing` beyond a timeout are marked `failed` with a reason.

### GET /me/packages

My Contributions. This device's packages in every status except deleted, newest first.

Query: `status` (optional, `processing`, `published`, or `failed`), `limit`, `cursor`.

**200**

```json
{
  "items": [
    {
      "id": "0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10",
      "title": "Healthy Living Habits",
      "originalTitle": "10 Habits for a Healthier Life",
      "subject": "Health",
      "resourceType": "text",
      "packageType": "text_only",
      "status": "published",
      "failureReason": null,
      "sizeBytes": 892000,
      "createdAt": "2026-11-09T07:00:00Z",
      "publishedAt": "2026-11-09T07:01:10Z",
      "isLearnt": true
    }
  ],
  "nextCursor": null
}
```

While `processing`, `title` and `subject` are `null`: show `originalTitle`. When `failed`, `failureReason` holds a message safe to show.

### GET /me/packages/{packageId}

One of this device's packages in any status. Same item shape as the list. Poll this endpoint after creating or retrying.

**Polling guidance:** first poll after 3 seconds, then back off (3, 5, 8, 10 seconds), stop at `published` or `failed`, and stop after about 3 minutes with a "Still working, check back later" state. Polling continues from My Contributions when the learner returns.

**Errors:** `404 NOT_FOUND` (not found or not yours).

### POST /me/packages/{packageId}/retry

Re-run the AI job for a `failed` package you own. The stored content and images are reused. No body.

**202 Accepted**

```json
{ "id": "0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10", "status": "processing" }
```

**Errors:** `404 NOT_FOUND`, `409 NOT_RETRYABLE` (status is not `failed`).

### DELETE /me/packages/{packageId}

Soft-delete a package you contributed (FR-5.8). It disappears from Explore and from other devices' lists. Devices that already downloaded it keep their local copy.

**204** No body. **Errors:** `404 NOT_FOUND` (not found or not yours).

---

## 9. Batch sync endpoint

When a connection returns, the client sends everything waiting in its **outbox** in one request (FR-8.2). Each operation is safe to repeat (FR-8.3), so a retry after a lost response never causes duplicates.

### POST /sync

**Request**

```json
{
  "ops": [
    {
      "opId": "11111111-aaaa-4bbb-8ccc-222222222222",
      "type": "progress.upsert",
      "clientTime": "2026-11-10T09:12:00Z",
      "payload": {
        "packageId": "0b9d6a8e-3c1f-4d52-8e75-6a2f1c9b4d10",
        "completedSteps": ["summary"],
        "lastStep": "key_concepts",
        "lastOpenedAt": "2026-11-10T09:12:00Z"
      }
    }
  ]
}
```

- Maximum 50 operations per request. Send more in further requests.
- Operations are applied **in array order**.
- `opId` is a client UUID used to match results to outbox rows. It is not stored. Idempotency comes from the natural keys below.

**Operation types**

| `type` | `payload` | Equivalent call | Idempotent because |
|---|---|---|---|
| `progress.upsert` | `packageId` plus the body of `PUT /me/progress/{id}` | `PUT /me/progress/{packageId}` | Steps are unioned, position only moves forward. |
| `quiz.attempt.create` | The body of `POST /me/quiz-attempts` | `POST /me/quiz-attempts` | Attempt `id` is client-supplied. |
| `bookmark.add` | `{ "packageId" }` | `PUT /me/bookmarks/{id}` | Primary key is device plus package. |
| `bookmark.remove` | `{ "packageId" }` | `DELETE /me/bookmarks/{id}` | Deleting nothing is a no-op. |
| `download.record` | `packageId`, `status`, `sizeBytes`, `downloadedAt` | `PUT /me/downloads/{id}` | One row per device and package. |
| `download.remove` | `{ "packageId" }` | `DELETE /me/downloads/{id}` | Sets `removedAt`. |

**200**

```json
{
  "results": [
    { "opId": "11111111-aaaa-4bbb-8ccc-222222222222", "status": "ok" },
    {
      "opId": "33333333-aaaa-4bbb-8ccc-444444444444",
      "status": "rejected",
      "error": { "code": "NOT_FOUND", "message": "This package is no longer available." }
    }
  ]
}
```

| Result | Client action |
|---|---|
| `ok` | Remove the operation from the outbox. A replay that changed nothing is also `ok`. |
| `rejected` | Permanent failure for that operation. Remove it from the outbox, and keep the local data. Do not retry. |
| HTTP `5xx`, `429`, timeout, or no response | Keep every operation. Retry the whole request with exponential backoff. |

The request itself returns `400` only if the body is malformed or over 50 operations.

---

## 10. Error codes

| HTTP | Code | Meaning |
|---|---|---|
| 400 | `INVALID_DEVICE_ID` | `X-Device-Id` is missing or not a UUID. |
| 400 | `VALIDATION_FAILED` | One or more fields are invalid. See `details`. |
| 404 | `NOT_FOUND` | The resource does not exist, is not published, or is not yours. |
| 409 | `DUPLICATE_LINK` | A live package already uses this link. |
| 409 | `ATTEMPT_ID_CONFLICT` | The quiz attempt `id` belongs to another device. |
| 409 | `NOT_RETRYABLE` | Retry requested for a package that is not `failed`. |
| 413 | `IMAGE_TOO_LARGE` | Image over 500 KB. |
| 415 | `UNSUPPORTED_IMAGE_TYPE` | Image is not PNG, JPG, or WebP. |
| 416 | `RANGE_NOT_SATISFIABLE` | Bad `Range` header on an image. |
| 422 | `TOO_MANY_IMAGES` | More than 3 images. |
| 429 | `DAILY_LIMIT_REACHED` | Daily creation limit reached. |
| 429 | `RATE_LIMITED` | Too many requests. See `Retry-After`. |
| 500 | `INTERNAL_ERROR` | Unexpected server error. Safe to retry reads. |
| 503 | `SERVICE_UNAVAILABLE` | Server or AI service temporarily unavailable. Retry with backoff. |

**Failure messages for `failureReason`** (shown to the contributor on a failed package):

| Situation | Message |
|---|---|
| Link cannot be read, no content pasted | "We couldn't read this link. Please paste the content and try again." |
| AI output invalid after one retry | "We couldn't build a reliable lesson from this. Please try again." |
| Job timed out | "This took too long. Please try again." |
| Video with no transcript and no readable page | "We couldn't read this video. Please paste the transcript and try again." |

---

## 11. Key flows

### 11.1 Download a package

```
Explore: tap Download
  1. If sizeBytes > largeDownloadBytes or Data Saver is on: ask the learner to confirm.
  2. GET /packages/{id}/bundle          (ETag aware)
  3. GET /packages/{id}/images/{imageId} for each image, with Range to resume
  4. Store bundle + images in IndexedDB in one transaction.
     Only now does the package count as downloaded.
  5. PUT /me/downloads/{id} {status: "completed", sizeBytes}
     If offline or it fails, queue download.record in the outbox.
```

On failure at steps 2 or 3, retry automatically with backoff. Parts already stored are not fetched again.

### 11.2 Contribute a package

```
Create a Package: tap Create Package
  1. Validate locally. Compress images on the device.
  2. POST /packages (multipart)  ->  202 {id, status: "processing"}
  3. Show the item in My Contributions as Processing.
  4. Poll GET /me/packages/{id}.
       published -> item becomes available; learner can download it
       failed    -> show failureReason and a Retry button
  5. Retry: POST /me/packages/{id}/retry -> back to step 4.
```

Offline: keep a draft locally and disable Create with an explanation (FR-5.9).

### 11.3 Learn offline and sync later

```
Open package -> read the bundle from IndexedDB (no network)
Complete a step -> write progress locally, queue progress.upsert
Finish quiz -> grade locally using correctIndex, store attempt + answers locally,
               queue quiz.attempt.create, show result and Learnt immediately
Connection returns -> POST /sync with the outbox (<= 50 ops per request)
```

The learner sees Learnt immediately from the local grade. The server recomputes the grade on sync. They agree unless the bundle was changed, which published packages are not.

---

## 12. Assumptions and open decisions

These are the points where the PRD, the schema, and the designs leave room. The API takes the default shown. Change the default in one place if you decide otherwise.

| # | Topic | Default taken here |
|---|---|---|
| 1 | Pass mark | 60 percent, served by `GET /config`. The designs show 80 percent as a pass (PRD open question 1). |
| 2 | Diagram step | Present only when the package has at least one image (`steps` includes `diagram`). The Water Cycle design is Text Only yet shows a Diagram step (open question 2). |
| 3 | Learnt | Granted only by a passing quiz attempt. The client cannot set it through progress. |
| 4 | Client score | The server regrades from `correctIndex` and ignores any client score. |
| 5 | Outbox idempotency | Relies on natural keys and the client attempt `id`. There is no operations table, because the schema has none. |
| 6 | Sizes | `sizeBytes` always comes from the server. The designs show inconsistent sizes (open question 3). |
| 7 | Audio resources | Not supported. `resourceType` is `text` or `video` only (open question 4). |
| 8 | Language | English only. The AI is not asked to match the source language (open question 5). |
| 9 | Subjects | Fixed list of seven, enforced by a database constraint (open question 6). |
| 10 | Unpublishing | A deleted package stays on devices that already downloaded it. It cannot be re-downloaded. |
| 11 | Idempotent create | There is no `Idempotency-Key`. The unique live-link index plus `DUPLICATE_LINK` with `isOwn` covers a timed-out retry. |
| 12 | Failure codes | The schema stores only a `failureReason` text, so the API exposes the message, not a machine code. |

---

## 13. Appendix: screen to endpoint map

| Screen | Reads | Writes | Works offline |
|---|---|---|---|
| Onboarding (3 slides) | none | none (device ID created locally) | Yes |
| Home | Local store. `GET /packages?limit=3` for the Explore preview when online. | `PUT/DELETE /me/downloads`, via outbox | Yes, except the Explore preview and Download |
| Learn | Local store | `DELETE /me/downloads` via outbox | Yes |
| Continue Learning (See All) | Local store, filters and sort run locally | none | Yes |
| Recent Downloads (See All) | Local store, filters and sort run locally | `DELETE /me/downloads` via outbox | Yes |
| Explore and Download | `GET /packages` | Download flow, `PUT /me/downloads` | Browsing from cache only. Download needs a connection. |
| Contribute | `GET /me/packages`, `GET /packages?excludeMine=true&limit=3` | `DELETE /me/packages/{id}`, `POST .../retry` | Partly. Needs a connection for server data. |
| Create a Package (Text) | `GET /config` (cached) | `POST /packages` | No. Draft saved locally. |
| Create a Package (Video) | `GET /config` (cached) | `POST /packages` | No. Draft saved locally. |
| Package, Learn tab | Local bundle, or `GET /packages/{id}` before download | Bookmark via outbox | Yes once downloaded |
| Package, Details tab | Local bundle | none | Yes once downloaded |
| Viewer (Summary, Key Concepts, Diagram) | Local bundle and images | `progress.upsert` via outbox | Yes |
| Quiz | Local bundle | none until submit | Yes |
| Quiz result | Local attempt | `quiz.attempt.create` via outbox | Yes |
| Review Answers | Local bundle and local attempt | none | Yes |
| Bookmarks (no design provided) | Local store. `GET /me/bookmarks` to restore. | `bookmark.add` and `bookmark.remove` via outbox | Yes |

---

## 14. Appendix: database rules the API relies on

The database enforces these, so the API does not need to duplicate them (and must surface the violations as clean errors).

| Rule | Mechanism |
|---|---|
| Only published, non-deleted packages are visible to browsers | RLS policies. The backend uses the service role and enforces device ownership itself. |
| A package publishes only with content and exactly 5 questions | Trigger `enforce_publishable` |
| `package_type` follows the images | Trigger `sync_package_type` (With Visual means at least one contributor image) |
| Progress never goes backwards | Trigger `merge_learning_progress` |
| A passing attempt grants Learnt | Trigger `grant_learnt_on_pass` |
| One live package per normalised link | Unique partial index `uq_lp_live_link` on `resource_link_key` |
| Image caps: 500 KB, 3 contributor images plus 1 diagram, PNG/JPG/WebP | Check constraints and unique `(package_id, position)` |
| Text and transcript up to 10,000 characters | Check constraint `lp_source_len` |
| Video links must be YouTube | Check constraint `lp_video_is_youtube` |
| One download row per device and package | Unique `(device_id, package_id)` |
| Search | Generated `search_text` column with a trigram index |

# Platform Development — Final Project

## Part A: Problem Understanding and Analysis

**Project:** BridgeAccess — *Learn more. Use less. Learn anywhere.*
**Document type:** Problem analysis, stakeholder identification, and current workflow (as-is)
**Status:** Analysis only — no technical solution is proposed in this part.

---

## 1. Problem Analysis

### 1.1 The real problem

The problem is **not a shortage of educational content**. Free articles, open course material, and YouTube lessons are abundant. The real problem is that **the content cannot be reliably turned into something a learner can actually study under real-world constraints**:

- connections are unstable, slow, or expensive;
- devices are old, low-memory, low-storage, and often shared;
- existing content is heavy (video, ads, large pages, autoplay);
- there is no simple way for an ordinary person to reduce a resource into a small, structured, teachable unit;
- and there is no offline way to confirm that learning happened.

In one sentence:

> **Learners with constrained connectivity and devices cannot depend on online educational content, and the people who find good content have no low-effort way to turn it into a small, offline, structured learning experience that others can complete.**

The platform is therefore solving a **conversion and access problem**, not a content-creation problem: converting an existing article or video into a lightweight, offline-ready **learning package** (details, key concepts, optional diagram, and a short quiz).

### 1.2 Why this is a problem worth solving

- **Access inequality:** the learners who most need free educational material are the least able to stream or browse it.
- **Cost of data:** every unnecessary kilobyte has a direct financial cost to the learner.
- **Device reality:** heavy pages and video are unusable on low-end or shared phones.
- **Content waste:** useful material is discovered, shared as a raw link, and then lost because it cannot be studied later.
- **No feedback loop:** even when content is read, there is no lightweight way to check understanding or record progress offline.
- **Community potential:** people already share links; the missing step is a simple, trustworthy way to repackage them.

### 1.3 Problem framing

The analysis can be framed as a design question:

> *How might we reduce an article or YouTube video into a small, self-contained, offline learning package that a learner on an unreliable, costly connection and an old shared phone can download once and finish — including a quiz — without a network?*

Three sub-problems follow from this framing:

1. **Reduction** — compress a long resource into its essentials without distorting it.
2. **Delivery** — get the result onto the device once, efficiently, and keep it usable offline.
3. **Outcome** — let the learner study, test themselves, and resume later with measurable progress.

### 1.4 Root causes

| # | Root cause | How it produces the problem |
|---|---|---|
| R1 | Educational content is designed for broadband and modern devices | Video, ads, large pages, and scripts make content expensive to load and impossible to use offline. |
| R2 | Connectivity is intermittent and metered | Requests fail or time out; re-downloading wastes money and time. |
| R3 | Devices are constrained and shared | Low memory/storage and slow processors make heavy apps and pages impractical; multiple users share one device. |
| R4 | No low-effort transformation tool exists | Contributors must manually summarize, structure, and create quizzes — too much effort, so it rarely happens. |
| R5 | Content is unstructured for learning | Raw articles/videos are not organized into steps, key concepts, or assessment. |
| R6 | No offline-first learner record | Progress, quiz results, and bookmarks live only online or not at all. |
| R7 | Discovery and sharing are informal | Links are shared through messaging and lost; there is no durable, reusable package. |
| R8 | Trust and attribution are weak | Summaries and reposts may be inaccurate or unattributed, creating quality and copyright risk. |

---

## 2. Stakeholder Identification

### 2.1 Primary and secondary stakeholders

| Stakeholder | Role | Primary needs | Pain today | Influence on success |
|---|---|---|---|---|
| **Learner** (primary) | Student or self-learner with unstable, slow, or costly internet, often on an older or shared phone | Small downloads, offline study, clear progress, quick quizzes, ability to resume | Cannot depend on streaming/browsing; loses content when connection drops; no offline assessment | Highest — the product succeeds only if learners can finish packages offline |
| **Contributor** (secondary, often the same person) | Someone who finds a useful article or video and wants to share it | A short form, no technical skill required, confidence the result will be good | Manual summarizing is slow; shared links are not reusable; no structure or quiz | High — supply of packages depends on low-effort contribution |
| **Shared-device household / co-learners** | Several people using one phone | Separate progress or at least clear data control; privacy | Mixed progress and data on one device | Medium — affects usability and trust on shared devices |

### 2.2 Enabling and indirect stakeholders

| Stakeholder | Interest | Needs from the system | Risk if ignored |
|---|---|---|---|
| **Platform operator / administrator** | A working, safe, affordable service | Moderation basics, cost control, reliability, abuse prevention | Unsafe or unaffordable content; service outage |
| **Product and engineering team** | Deliver within a fixed, short timeline | Clear scope, testable requirements, realistic constraints | Scope creep, missed milestones |
| **AI provider (Gemini)** | Used server-side for summarization, quiz, and optional diagram | Valid, well-formed prompts; rate and cost control | Cost overruns, malformed output, outages |
| **Database and storage provider (Supabase)** | Hosts content, learner records, and images | Secure access, RLS, storage limits | Data exposure or service limits |
| **Original content authors / publishers** | Attribution and fair use of their material | Visible attribution, links to the original, own-words summaries | Copyright complaints, reputational and legal risk |
| **Educators / community programs** (indirect) | Reach learners with relevant material | Trustworthy, curriculum-aligned packages | Low adoption or misaligned content |

### 2.3 Stakeholder tensions to manage

- **Learner vs. contributor:** learners want small, verified material; contributors want speed and low effort.
- **Openness vs. safety:** easy contribution increases supply but also risk of inaccurate or infringing content.
- **Richness vs. size:** diagrams and images improve learning but increase download size and data cost.
- **Privacy vs. personalization:** no accounts protect privacy but limit cross-device continuity and real identity.

---

## 3. Current Workflow (As-Is)

### 3.1 How a learner studies today

1. **Discover** a link via search, social media, messaging, or a friend.
2. **Open** the article or YouTube video on a phone.
3. **Wait / buffer / retry** as the connection drops; consume data on ads and page weight.
4. **Try to save** the content — browser tab, screenshot, or copied notes.
5. **Return later** and find the tab lost, the page changed, or the video unavailable.
6. **Study without structure** — no key concepts, no diagram, no quiz.
7. **Abandon or forget**, with no record of progress or understanding.

**Characteristics:** ad hoc, connection-dependent, unstructured, non-resumable, and with no measurable outcome.

### 3.2 How a contributor shares content today

1. **Find** a useful article or video.
2. **Decide to share** with a person or group.
3. **Send the raw link** (or a screenshot / retyped notes).
4. **Recipient repeats** the learner workflow above.
5. **No package is created**, no structure is added, no quiz exists, and attribution may be lost.

**Characteristics:** high friction for anyone who wants to add structure; no reuse; duplicate effort across people sharing the same resource.

### 3.3 How the current "system" is assembled

| Need | Current workaround | Limitation |
|---|---|---|
| Save content | Browser tabs, bookmarks, "Watch Later" | Requires connection; content may disappear or change |
| Study offline | Screenshots, copied text, downloaded PDFs | Unstructured, large, no quiz, hard to update |
| Check understanding | Nothing, or self-made questions | No feedback, no record |
| Track progress | Memory, notes app | No resume point, no cross-session continuity |
| Share knowledge | Messaging links, screenshots | No reusable package, no attribution discipline |

### 3.4 As-is vs. desired outcome

| Stage | As-is | Desired outcome |
|---|---|---|
| Discover | Link found in feed/chat | Package found in a catalogue |
| Acquire | Stream/browse live | Download once, small and resumable |
| Study | Unstructured page/video | Guided steps: Details → Key Concepts → Diagram → Quiz |
| Check learning | None | Instant offline quiz with score and review |
| Return | Lost tab or memory | Resume at last section; progress stored |
| Share | Raw link | Structured, attributed package |

---

## 4. Main Pain Points and Limitations

| # | Pain point | Affected stakeholder | Consequence | Evidence / requirement link |
|---|---|---|---|---|
| P1 | Content is too heavy for the connection | Learner | Cannot load or afford to study | PRD §2, G1/G2; data-usage targets |
| P2 | Downloads fail or restart from zero | Learner | Wasted data and time; abandonment | FR-4.3/4.4 (resumable, atomic downloads) |
| P3 | Old/low-memory phones struggle | Learner | Poor or unusable experience | G3, device-support requirement |
| P4 | No offline study or quiz | Learner | Learning stops when connection does | FR-7.5, FR-8.1 |
| P5 | No progress continuity | Learner | Starts over; loses motivation | FR-3.4, FR-8.2/8.3 |
| P6 | No easy way to repackage content | Contributor | Good material is not turned into packages | G4, FR-5.2/5.3 |
| P7 | Sharing loses structure and attribution | Contributor, original author | Low quality, copyright risk | FR-6.7, FR-7.9 |
| P8 | AI output may be wrong or malformed | Learner, operator | Misleading lessons, broken quizzes | FR-6.3/6.4, risk table |
| P9 | Shared devices mix learner data | Household | Confusing progress; privacy concerns | FR-1.5, FR-8.5 |
| P10 | No lightweight moderation | Operator, learner | Unsafe or infringing content | Non-goal (basic safeguards only) |

**Core limitation of the current process:** it optimizes for *access to content*, not for *completion of learning under constraints*. There is no unit that is simultaneously small, structured, offline-capable, attributable, and measurable.

---

## 5. Assumptions to Verify

These assumptions are unproven and must be tested before or during solution design.

| # | Assumption | Why it matters | How to verify |
|---|---|---|---|
| A1 | Learners have at least occasional access to a "good enough" connection to download a package | The whole offline model depends on a successful first download | Field interviews; test downloads on real networks |
| A2 | A package in the low hundreds of kilobytes (text bundle ≈ 30 KB, images ≤ 80 KB) is small enough to be acceptable | Defines the data-cost value proposition | Measure real sizes; compare with learner data budgets |
| A3 | A 300–400-word lesson, 4–6 key concepts, and 5 quiz questions are sufficient for meaningful learning | Scope and quality of the core unit | Pilot with learners; quiz pass/retention data |
| A4 | AI can produce faithful, own-words summaries from articles and YouTube transcripts | Trust and legal safety | Validate output samples; human review; retry/validation rules |
| A5 | Source links (articles and YouTube) can usually be read server-side | Otherwise contribution fails and contributors must paste content | Test against paywalls, anti-bot pages, region locks |
| A6 | Target phones support PWA installation, service workers, and IndexedDB | Offline behavior depends on these capabilities | Test the oldest supported device/browser |
| A7 | Readers will accept a 60% pass mark as "Learnt" (designs show 80%) | Affects learner perception and success metric | Confirm with product/design and learners |
| A8 | An anonymous device ID is acceptable and adequately protects privacy | Identity model shapes all learner data | Privacy review; confirm no personal-data need |
| A9 | Contributors will contribute without accounts or payment | Supply of packages depends on it | Usability test of the contribution form; contribution rate |
| A10 | English-only UI and AI output are acceptable for the first release | Language scope affects reach and content fit | Confirm target market and language needs |
| A11 | Basic safeguards are enough for moderation in the MVP | Legal and safety exposure | Define reporting/terms; monitor early contributions |
| A12 | A fixed subject list (Science, Health, Math, Technology, Language, History, Other) fits the content | Classification and filtering depend on it | Review with contributors and sample content |

---

## 6. What Happens If the Problem Remains Unsolved

### For learners

- They continue to **pay for data they cannot fully use** and abandon content when the connection drops.
- They lose **study continuity** and motivation because there is no resume point or feedback.
- They remain **excluded from free educational material** despite it being publicly available, widening the learning gap.
- They cannot demonstrate even informal progress (no quiz score, no "Learnt" status).

### For contributors and communities

- Useful resources stay trapped as **raw links** that are reshared but never structured or reused.
- Effort is duplicated: each person re-summarizes and re-explains the same material.
- Attribution and quality remain inconsistent, increasing **copyright and trust risk**.

### For the platform and project

- The core value proposition — *learn offline on a constrained device* — is never validated.
- Success metrics (activation, completion, quiz pass rate, data efficiency, contribution) remain at zero or unmeasurable.
- The project fails its stated goals (G1–G5) and the 9-day MVP has no defensible outcome.
- Without a working offline model, later features (accounts, dashboards, social features) have no foundation.

### Broader impact

- The digital learning divide persists: content abundance does not translate into **learning access**.
- Communities with the least infrastructure continue to be served worst by otherwise free educational material.

---

## 7. Problem Statement (Summary)

> Learners in low-bandwidth, high-data-cost environments, often using old or shared phones, cannot reliably study the educational content they find online because that content is heavy, connection-dependent, and unstructured. Contributors who want to help have no low-effort way to reduce an article or video into a small, offline, attributed learning unit with a way to check understanding. The result is wasted data, lost content, no measurable learning, and a widening access gap. BridgeAccess addresses this by converting a resource into a compact learning package that can be downloaded once and completed fully offline, including a quiz and resumable progress.

---

## 8. Scope Boundary of This Analysis

**In scope for the problem analysis:** the learner and contributor experience, connectivity and device constraints, the as-is study workflow, trust/attribution, and the assumptions that must be validated before design.

**Out of scope for this part:** architecture, API design, data model, technology selection, and implementation planning. Those belong to later parts of the final project.

---

## 9. Open Questions to Resolve Before Solution Design

1. Who is the **oldest target device and browser** that must work?
2. Is the pass mark **60% or 80%**, and should every package include a diagram?
3. Should AI output match the **source language**, and is English-only acceptable?
4. How will **moderation, reporting, and terms** work in the MVP?
5. Which **hosting and Gemini budget/limits** apply in production?
6. How will **shared-device privacy** be handled beyond anonymous IDs and "Clear my data"?
7. Are **audio resources** in scope later, or should onboarding copy be changed now?

---

## 10. References

- `BridgeAccess_Product_Requirements_Document.md` — problem statement, users, goals, requirements, risks, assumptions.
- `BridgeAccess_API_Documentation.md` — identity model, package lifecycle, offline/sync behavior.
- `bridgeaccess_schema_full.sql` — data entities that support learner records, packages, and metrics.
- `success_metrics.sql` — how activation, completion, outcomes, data efficiency, and contribution are measured.

---

# Part B: Platform Concept and Requirements

## 11. Platform Concept Overview

BridgeAccess is a **community-powered offline learning platform**. It converts an ordinary article or YouTube video into a small, structured, offline-ready **learning package** — an AI-written Details lesson, 3–5 key takeaways, 4–6 key concepts, an optional diagram, and a 5-question quiz — and delivers it to learners who cannot depend on a steady connection or a powerful device.

The platform has three connected sides:

1. **Supply side — Contributors** turn a resource they already found into a package through a short form.
2. **Conversion core — AI generation pipeline** reduces the resource to essentials, validates the output, and publishes a package.
3. **Demand side — Learners** discover, download once, study offline, take the quiz, and build a progress record.

A fourth side, **operations**, keeps the catalogue safe, affordable, and reliable.

### 11.1 Why this should be a platform, not a single app

A standalone offline reader would solve one learner's problem but would not scale: someone would still have to create every package by hand. BridgeAccess is a platform because it coordinates **independent participants** and makes each one more valuable to the others:

- Contributors create value without needing technical skill.
- Learners get a growing catalogue without creating content.
- The AI conversion engine lowers the cost of supply.
- The offline runtime removes the connectivity barrier on the demand side.
- Learner activity (downloads, completion, quiz results) feeds quality and prioritization.
- Attribution and reporting keep the exchange trustworthy.

This is a **two-sided value loop**, not a one-off feature:

```
more contributors → more packages → more learners → more usage data
        ↑                                              │
        └──────── better targeting, quality, trust ←───┘
```

### 11.2 How the components create value

| Component | What it does | Value created | Who benefits |
|---|---|---|---|
| **Contribution form** | Collects title, author, link, optional content, up to 3 images | Removes the technical barrier to repackaging content | Contributor |
| **Source resolver** | Reads an article or YouTube transcript when content is not pasted | Lets a contributor create a package from a link alone | Contributor |
| **AI generation pipeline** | Produces the lesson, takeaways, key concepts, quiz, and optional diagram | Converts raw content into a teachable unit with near-zero manual effort | Contributor, learner |
| **Validation and retry** | Checks structure, counts, answer indexes, and categories; retries once | Protects learners from broken or misleading packages | Learner, operator |
| **Catalogue / Explore** | Search, filters, sort, pagination, size and type chips | Makes a growing library findable at low data cost | Learner |
| **Download manager** | Resumable, atomic package download; retries and size warnings | Makes acquisition survive weak networks | Learner |
| **Offline package store** | Local content, images, progress, bookmarks, quiz state | Enables full study with no connection | Learner |
| **Viewer and quiz** | Guided steps, mark complete, instant grading, review answers | Adds structure and measurable learning | Learner |
| **Learner record and sync** | Progress, attempts, bookmarks, downloads; idempotent outbox sync | Gives continuity across sessions and devices | Learner |
| **Attribution layer** | Original title, author, link, AI label | Builds trust and reduces copyright risk | Learner, original author, operator |
| **Operations and metrics** | Rate limits, stuck-job detection, success metrics | Keeps the platform safe, affordable, and improvable | Operator, team |

### 11.3 Value proposition

**For learners:**
> Turn any article or YouTube video into a tiny learning package you download once and finish anywhere — key ideas, a quiz, and progress that stays on your phone, even with no connection.

**For contributors:**
> Turn a useful link into a structured, shareable lesson in under two minutes — no account, no technical skill, no manual summarizing.

**For the ecosystem:**
> Free educational content becomes reusable, attributable, and measurable for the people who need it most.

### 11.4 Differentiators

| Alternative today | What it does | Why BridgeAccess is different |
|---|---|---|
| Browser tabs / bookmarks | Saves a link | Needs connection; content changes or disappears; no structure or quiz |
| YouTube "Watch Later" | Saves a video | Still streams; heavy on data; no offline study unit |
| Screenshots / copied notes | Saves fragments | Unstructured, large, no assessment, no progress |
| Downloaded PDFs | Offline reading | Large, no key concepts, no quiz, no resume or sync |
| MOOC apps | Structured courses | Heavy apps, account-bound, not built for low-end/offline use |
| Generic AI chat | Summarizes on demand | Needs connection and a paid model; no offline package, quiz, catalogue, or progress |

BridgeAccess combines **conversion + small delivery + offline learning + assessment + attribution** in one unit. That combination is the platform.

---

## 12. User Roles and Responsibilities

> **Framing note.** The product has **two end users: the Learner and the Contributor** (PRD §4), and they are often the same person on the same device. The other participants listed below are **supporting actors** that enable or operate the platform; they are not end users and do not use the learner/contributor experience.

### 12.1 End-user roles

| Role | Description | Identity | Responsibilities | Permissions |
|---|---|---|---|---|
| **Learner** (primary) | Student or self-learner on an unstable, costly connection and/or an older or shared phone | Anonymous device ID | Download packages, study offline, complete steps, take quizzes, bookmark, manage local data | Read catalogue and own learner data; download; create progress, attempts, bookmarks; delete local downloads |
| **Contributor** (secondary, often the same person) | Anyone who turns a resource into a package | Same anonymous device ID | Provide accurate title, author, link, and optional content/images; respect attribution and terms | Create packages (rate-limited); view and delete own contributions; retry failed generation |

**A shared device does not create a new user role.** Multiple people may use one phone; the MVP keeps one anonymous identity, and optional local profiles (P2) simply scope one learner's data. "Shared-device co-learner" is a usage context, not a user type.

### 12.2 Supporting actors (not end users)

| Actor | Role | Why it is not a user role |
|---|---|---|
| **Platform operator / moderator** | Runs and safeguards the service: monitors jobs, handles reports, tunes limits, seeds packages | Internal operational role; no learner or contributor experience |
| **AI provider (Gemini)** | Generates lesson content and the optional diagram | External service called by the backend, never by the client |
| **Generation worker** | Asynchronous job runner: generate, validate, retry, publish or fail, detect stuck jobs | Internal system process |
| **Original author / publisher** | Owns the source article or video | External rights-holder; receives attribution and a link back, but has no account in the MVP |

### 12.3 Responsibility boundaries

- **The contributor is responsible for the source and its accuracy**, but the platform must never invent missing content.
- **The platform is responsible for structure, validation, attribution, and offline delivery.**
- **The learner owns local study data** and can clear it from the device.
- **The operator owns safety, cost, and reliability**, not the truth of every source.
- **The original author keeps ownership**; the platform summarizes in its own words and links back.

### 12.4 Role–capability matrix

| Capability | Learner | Contributor |
|---|---|---|
| Browse / search catalogue | Yes | Yes |
| Download a package | Yes | Yes |
| Study offline / take a quiz | Yes | Yes |
| Bookmark / unbookmark | Yes | Yes |
| Create a package | — | Yes |
| View own contributions | — | Yes |
| Retry failed generation | — | Yes (own) |
| Delete own package | — | Yes |
| Clear local data | Yes | Yes |
| Report content (future) | Later | Later |

---

## 13. Core Capabilities and Functional Requirements

### 13.1 Capability map

| ID | Capability | Description | Key requirements | Priority |
|---|---|---|---|---|
| C1 | **Onboarding and identity** | Intro slides, anonymous device ID, no personal data, optional local profiles | FR-1.1–1.5 | P0/P1 |
| C2 | **Home and library** | Welcome, continue learning, recent downloads, explore preview, bottom navigation, fully offline | FR-2.1–2.6, FR-3.1–3.4 | P0 |
| C3 | **Explore and download** | Search, filter, sort, paginate; resumable atomic downloads; size warnings | FR-4.1–4.6 | P0/P1 |
| C4 | **Contribution and creation** | Text/video forms, validation, image limits, processing state, retry, delete, rate limit | FR-5.1–5.11 | P0/P1 |
| C5 | **AI generation** | Lesson, takeaways, concepts, 5-question quiz, optional diagram, validation, retry, attribution | FR-6.1–6.7 | P0/P1 |
| C6 | **Learning experience** | Package tabs, guided steps, mark complete, quiz grading, review answers, restart, bookmarks | FR-7.1–7.10 | P0/P1 |
| C7 | **Offline and sync** | Offline study, queued actions, idempotent sync, banners, storage controls, installable PWA | FR-8.1–8.6 | P0/P1 |
| C8 | **Trust and safety** | AI label, attribution, validation, rate limits, SSRF protection, ownership checks, basic safeguards | FR-6.4, 6.7, 7.9; NFR security | P0 |
| C9 | **Operations and measurement** | Job sweeping, stuck-job detection, success metrics from existing tables | NFR reliability; success metrics | P0/P1 |

### 13.2 Functional requirements summary

| Area | Core functional requirements |
|---|---|
| **Identity** | Anonymous device UUID on every request; no personal data; optional local profiles |
| **Catalogue** | Search by title; filter by subject, resource type, package type; sort by latest published; 20 per page |
| **Download** | Progress, automatic retry, resume without re-downloading finished parts, atomic completion, large-download confirmation |
| **Create — text** | Original title, author, content (≤10,000 chars), up to 3 images, resource link |
| **Create — video** | Video title, channel, transcript (≤10,000 chars), up to 3 images, YouTube URL |
| **Generation** | 300–400-word Details in four paragraphs, 3–5 takeaways, 4–6 key concepts, exactly 5 MCQs with explanations, optional diagram |
| **Learning** | Details, Key Concepts, Diagram (if visuals), Quiz; step indicator and progress; mark complete; resume |
| **Assessment** | 5 questions, one at a time, all answered, instant offline grading, score and percentage, pass/fail, 60% default, Learnt badge, review answers |
| **Bookmarks** | Bookmark/unbookmark; offline Bookmarks tab |
| **Sync** | Queue offline actions; replay idempotently; completed steps never un-complete; offline and waiting-to-sync indicators |
| **Contribution management** | Processing → published/failed; failure reason and retry; delete own package; 5 per rolling hour |
| **Installation** | Installable PWA with offline app shell |

---

## 14. Key User Journeys

### J1 — First-time learner: discover, download, learn offline

| Step | Action | System response | Success signal |
|---|---|---|---|
| 1 | Opens the PWA for the first time | Three intro slides; Skip / Next / Get Started | Reaches Home |
| 2 | Device ID created silently | UUID stored locally and sent with requests | No personal data collected |
| 3 | Opens Explore | Paginated catalogue with filters and sizes | List loads on weak connection |
| 4 | Taps Download on a package | Progress shown; retries on failure; resumes parts | Package marked downloaded only when all parts stored |
| 5 | Goes offline and opens the package | Local content and images load | Full study with no network |
| 6 | Moves through Details → Key Concepts → (Diagram) → Quiz | Step indicator, progress, mark complete | Each step completes and persists |
| 7 | Answers 5 questions | Instant grading without a connection | Score, percentage, pass/fail |
| 8 | Passes at 60% or more | Learnt badge and completion message | Package appears as Learnt |

**Failure/edge cases:** download interrupted (resume), large package or Data Saver (confirm), quiz left incomplete (progress kept), app closed mid-study (resume at last section).

### J2 — Returning learner: continue and sync

| Step | Action | System response |
|---|---|---|
| 1 | Opens Home while offline | Renders from local data; offline banner |
| 2 | Taps Continue Learning | Resumes most recent in-progress package at last section |
| 3 | Completes a step or quiz offline | Action queued in the local outbox |
| 4 | Connection returns | Queued actions sync in a batch; waiting-to-sync indicator clears |
| 5 | Reopens later | Progress is reconciled; completed steps remain complete |

**Business rule:** sync is idempotent — no duplicates, and a completed step never un-completes.

### J3 — Contributor: create a package and publish

| Step | Action | System response |
|---|---|---|
| 1 | Opens Contribute | Create, My Contributions, Community Packages |
| 2 | Chooses Text or Video | Form with inline validation and character counter |
| 3 | Pastes content or leaves it blank | If blank, the server reads the link or YouTube transcript |
| 4 | Adds up to 3 images (optional) | Images compressed on device; package type determined |
| 5 | Submits | Package shows Processing; AI job runs asynchronously |
| 6 | AI produces and validates output | Package publishes; learner-facing title and subject set |
| 7 | Contributor checks My Contributions | Package is visible and shareable |
| 8 | Contributor can delete their package | Soft delete; catalogue removes it |

**Failure/edge cases:** unreadable link → clear message asking for pasted content (never invent content); invalid AI output → one retry; failed diagram → publishing continues; rate limit → show when creation is available again.

### J4 — Contributor: recover from failure

| Step | Action | System response |
|---|---|---|
| 1 | Opens My Contributions | Failed package with reason |
| 2 | Taps Retry | Job re-queued; status returns to Processing |
| 3 | If failure was unreadable source | Contributor pastes the content and retries |
| 4 | Job succeeds | Package publishes |

**Business rules:** ownership checked on retry/delete; another device's package returns not-found; stuck jobs are detected and marked failed.

### J5 — Learner on a shared device: separate or clear data

| Step | Action | System response |
|---|---|---|
| 1 | Second person opens the app | Optional local profile (P2) or continues on the same device |
| 2 | Uses learner features | Progress is scoped to the profile/device |
| 3 | Chooses Clear my data | Storage used is shown; local data is cleared after confirmation |

### J6 — Supporting actor: operator keeps the platform healthy

| Step | Action | System response |
|---|---|---|
| 1 | Checks failed or stuck generation jobs | Job status and failure reasons visible |
| 2 | Checks metrics | Activation, completion, quiz outcomes, package size, contributions, reliability |
| 3 | Reviews abuse or reports | Basic safeguards and removal path |
| 4 | Adjusts limits | Rate limits and pass mark are configurable |

---

## 15. Main Workflows and Business Rules

### 15.1 Workflow W1 — Contribution to published package

```
Submit form
   │
   ▼
Validate input (title, author, link, length, images, rate limit)
   │ valid
   ▼
Create package (status = processing) ──► show Processing to contributor
   │
   ▼
Resolve source (pasted content → link reader → YouTube transcript)
   │ unreadable
   ├──────────────► fail: ask contributor to paste content (never invent)
   ▼ readable
Generate lesson with Gemini
   │
   ▼
Validate output (counts, options, answer index, category)
   │ invalid
   ├──────────────► retry once ──► still invalid ──► status = failed + reason
   ▼ valid
Process images (optimize to ~80 KB WebP; diagram failure is non-blocking)
   │
   ▼
Persist content, quiz, images, size ──► status = published + published_at
```

### 15.2 Workflow W2 — Download

```
Request manifest (metadata + part list)
   │
   ▼
Download each part with progress
   │ failure
   ├──────────────► retry automatically; resume only missing parts
   ▼ all parts stored
Verify every part ──► mark downloaded ──► record download
   │
   ▼
Learner can study fully offline
```

### 15.3 Workflow W3 — Learning and assessment

```
Open package → resume last section
   │
   ▼
Details → Key Concepts → Diagram (if with_visual) → Quiz
   │ each step
   ▼
Mark Complete → persist progress
   │
   ▼
Answer 5 questions (all required) → grade instantly offline
   │
   ▼
Score + percentage + pass/fail (pass ≥ 60%)
   │ pass
   ▼
Learnt badge → Review Answers → optional Review Learning (restart)
```

### 15.4 Workflow W4 — Offline sync

```
Offline action (progress, quiz attempt, bookmark, download)
   │
   ▼
Append to local outbox
   │ connection returns (or background sync)
   ▼
Send batch to sync endpoint
   │
   ▼
Server applies idempotently (no duplicates; completed never un-completes)
   │
   ▼
Clear outbox items → update local state → hide waiting-to-sync indicator
```

### 15.5 Business rules

| ID | Rule |
|---|---|
| BR-1 | Package type is **with_visual** if the contributor attached at least one image; otherwise **text_only**. |
| BR-2 | Learning steps: 3 for text_only (Details, Key Concepts, Quiz); 4 for with_visual (adds Diagram). |
| BR-3 | The quiz has exactly 5 multiple-choice questions, 4 options each, one correct answer, and an explanation. |
| BR-4 | Pass mark is 60% by default and configurable; a pass awards the Learnt badge. |
| BR-5 | A package counts as downloaded only after every part is stored; downloads are resumable and atomic. |
| BR-6 | Sync is idempotent: no duplicate records and completed steps never revert. |
| BR-7 | A device may create up to 5 packages in any rolling hour; the next available time is shown. There is no lifetime cap. |
| BR-8 | No accounts and no personal data; identity is an anonymous device UUID. |
| BR-9 | AI content is written in its own words, uses only source facts, is labelled AI-generated, and keeps original title, author, and link. |
| BR-10 | If source content cannot be read, the package fails with a clear message; the system never invents content. |
| BR-11 | A failed diagram does not block publishing. |
| BR-12 | Creating requires a connection; offline, keep a draft and explain why Create is disabled. |
| BR-13 | Images: up to 3, 500 KB each, PNG/JPG/WebP; optimized to about 80 KB WebP. |
| BR-14 | Pasted content/transcript is capped at 10,000 characters. |
| BR-15 | Large downloads (>1 MB) or Data Saver require confirmation. |
| BR-16 | Catalogue lists load 20 at a time with Load more; responses are gzipped and ETag-enabled. |
| BR-17 | Contributors can delete only their own packages; ownership failures return not-found, not forbidden. |
| BR-18 | Stuck AI jobs are detected and marked failed. |
| BR-19 | Offline learning, quiz, review, bookmarks, and Home work after the first load. |
| BR-20 | The app is installable to the home screen. |

---

## 16. Non-Functional Requirements

| Area | Requirement | Target / rule |
|---|---|---|
| **Performance** | Fast on low-end devices | Initial JS ≤ 150 KB gzipped; route-level code splitting; first useful screen from cache after first visit |
| **Data usage** | Every kilobyte counts | Text bundle ≈ 30 KB or less; images target 80 KB (hard cap 500 KB); gzip, ETag, 304 |
| **Reliability** | Weak networks and retries | Resumable, atomic downloads; idempotent sync; stuck-job detection; clear failure states |
| **Device support** | Old, low-memory phones | Recent mobile browsers from roughly the last five years on low-end Android; confirm oldest device |
| **Accessibility** | Inclusive by default | 44 px tap targets, WCAG AA contrast, labelled controls, screen-reader support, scalable text |
| **Security** | Protect keys and data | Gemini and database service keys server-side only; RLS enabled; ownership checks on delete/retry; SSRF protection; rate limiting; HTTPS only |
| **Privacy** | No personal data | Anonymous device ID; no third-party trackers; local data can be cleared |
| **Cost** | Sustainable AI and hosting | Rate limits, job queue, duplicate-link detection, monitoring, configurable limits |
| **Scalability** | Grow the catalogue | Stateless API, asynchronous generation, paginated catalogue, storage abstraction (local or Supabase) |
| **Maintainability** | Small team, short timeline | Modular capabilities, validated contracts, automated tests, clear error codes |
| **Observability** | Know what is failing | Request IDs, structured logs, job status, success metrics from existing tables |
| **Compatibility** | Offline-first web | Service worker, IndexedDB, installable PWA; graceful degradation when storage is unavailable |

---

## 17. MVP Scope and Future Enhancements

### 17.1 MVP definition

The MVP is **one complete loop**: a contributor turns a link into a package, a learner discovers and downloads it, studies it fully offline, passes a quiz, and progress survives reconnection. If that loop works on a weak network and an old phone, the MVP is successful.

**In scope (P0):**

| Capability | Included |
|---|---|
| Onboarding and anonymous device identity | Yes — identity P0, slides P1 |
| Home, Learn, Continue Learning, Recent Downloads | Yes |
| Explore with search/filter/sort/pagination | Yes |
| Resumable, atomic download with progress | Yes |
| Contribute: text and video forms with validation | Yes |
| AI generation: lesson, takeaways, concepts, 5-question quiz | Yes |
| Optional AI diagram | P1 |
| Package viewer with guided steps, mark complete, resume | Yes |
| Quiz, instant offline grading, result, Learnt badge, Review Answers | Yes |
| Bookmarks | P1 |
| Offline storage and idempotent sync | Yes |
| PWA installation | Yes |
| Basic safeguards, attribution, AI label | Yes |
| Success metrics from existing tables | Yes |

**Cut order if time runs short:** AI diagrams and contributor images → bookmarks → Review Answers → local profiles. Offline download, quiz, and sync must stay.

### 17.2 Out of scope for the MVP

- User accounts and login
- Hosting or streaming video
- Audio resources
- Native iOS/Android apps
- Teacher or school dashboards
- Payments and social features
- Advanced moderation tooling
- Real authentication and cross-device identity

### 17.3 Future enhancements

| Horizon | Enhancement | Why it matters |
|---|---|---|
| **Next** | Real accounts and cross-device sync | Continuity beyond one browser profile |
| **Next** | Audio packages and text-to-speech | Reach low-literacy and feature-phone-adjacent users |
| **Next** | Better moderation, reporting, and contributor reputation | Safer catalogue and higher quality at scale |
| **Next** | Multi-language UI and AI output | Wider reach and source-language fidelity |
| **Later** | Offline contributions queue and draft sync | Create on intermittent connections |
| **Later** | Recommendations and personalized feed | Better discovery as the catalogue grows |
| **Later** | Teacher/school dashboards and cohorts | Classroom adoption and outcome tracking |
| **Later** | Versioning and updates for published packages | Keep lessons current without losing progress |
| **Later** | Native apps and deeper device integration | Performance and installability on constrained platforms |
| **Later** | Peer review and community quality signals | Trust at scale without heavy central moderation |

### 17.4 MVP success criteria

| Criterion | Measure |
|---|---|
| Learner can complete the full loop offline | Download on a weak network, then study and pass the quiz with no connection |
| Package is genuinely small | Median text package in the low hundreds of KB or less |
| Old device is usable | Smooth use on a real low-end phone |
| Contribution is fast | A package can be created in under two minutes of contributor effort |
| Learning is measurable | Progress, quiz score, and Learnt status recorded |
| Sync is safe | Replayed syncs produce no duplicates and never un-complete steps |

---

## 18. Platform Blueprint

### 18.1 Component blueprint

| Layer | Component | Responsibility | Value |
|---|---|---|---|
| **Client** | React + TypeScript PWA | Onboarding, Home, Learn, Explore, Contribute, viewer, quiz | Small, installable, offline-capable experience |
| **Client offline layer** | Service worker + IndexedDB (Dexie) | App shell, package store, progress, bookmarks, outbox | Removes the connectivity requirement |
| **API** | Spring Boot REST API `/api/v1` | Catalogue, download, contributions, learner data, sync | Single contract for all clients |
| **Identity and safety** | Device-ID filter, rate limiter, validation, SSRF guard | Anonymous identity, abuse and cost control, safe link reading | Trust and affordability |
| **Generation** | Asynchronous AI job + validator + sweeper | Convert source to lesson, quiz, diagram; validate and retry | Low-cost, scalable supply |
| **Source ingestion** | Link reader + YouTube transcript + text normalizer | Read the resource or ask for pasted content | Coverage and honesty about failures |
| **Data** | Supabase Postgres (`app` schema) | Packages, content, images, quizzes, attempts, progress, downloads, bookmarks | Durable, queryable learner record |
| **Storage** | Private image bucket or local disk | Contributor images and AI diagrams | Portable media without bloating the client |
| **External** | Gemini API | Text generation and optional diagram | Core conversion capability |
| **Operations** | Logs, metrics, job status, success queries | Reliability, cost, and quality visibility | Continuous improvement |

### 18.2 End-to-end value flow

```
Contributor ──► Contribution form ──► API ──► Generation job ──► Gemini
                                                    │
                                                    ▼
                                          Validated package + images
                                                    │
                                                    ▼
                                            Catalogue + storage
                                                    │
Learner ──► Explore ──► Download (resumable) ──► Offline store
                                                    │
                                                    ▼
                                      Study → Quiz → Progress → Sync
                                                    │
                                                    ▼
                                            Success metrics
```

### 18.3 Diagram placeholders (AI prompts)

> **Architecture diagram prompt:**
> "Draw a clean layered architecture diagram for a mobile-first offline learning PWA called BridgeAccess. Show, left to right: (1) Client layer — React/TypeScript PWA with service worker, IndexedDB offline store, and sync outbox; (2) Edge/API layer — Spring Boot REST API `/api/v1` with device-ID filter, rate limiter, validation, and SSRF-protected link reader; (3) Async generation layer — job queue, Gemini text generation, lesson validator, image optimizer, retry and stuck-job sweeper; (4) Data layer — Supabase Postgres schema `app` with nine tables and a private image storage bucket; (5) External services — Gemini API, article sources, YouTube. Use labeled arrows for the core flows: contributor submits source, AI generates package, learner downloads bundle, learner syncs offline actions. Style: minimal, high-contrast, accessible colors, clear legend, no decorative clutter."

> **User journey swimlane prompt:**
> "Draw a swimlane user-journey diagram for BridgeAccess with four lanes: Contributor, Platform/AI, Learner, and Offline device. Show the happy path: contributor submits a link or pasted content → platform validates and generates an AI lesson, key concepts, and a 5-question quiz → package is published to the catalogue → learner explores and downloads it once → learner studies Details, Key Concepts, optional Diagram, and Quiz fully offline → learner gets a score and Learnt badge → offline progress syncs when connection returns. Mark failure branches: unreadable source asks for pasted content; invalid AI output retries once then fails; interrupted download resumes; failed sync retries idempotently. Style: clean swimlanes, numbered steps, color-coded happy path versus failure path, readable at A4 landscape."

> **Package lifecycle state machine prompt:**
> "Draw a state machine diagram for a BridgeAccess learning package. States: Draft/Submitted → Processing → Published → Learnt (learner-level status), plus Failed and Deleted (soft delete). Transitions: submit creates Processing; successful AI generation and validation moves to Published; unreadable source or invalid output after one retry moves to Failed; contributor retry moves Failed back to Processing; learner download does not change package status; passing the quiz marks the learner's copy Learnt; contributor delete moves to Deleted and removes it from the catalogue. Include guards: rate limit, ownership check, stuck-job timeout. Style: minimal nodes, labeled transitions, clear legend."

> **Offline sync data-flow prompt:**
> "Draw a data-flow diagram for offline sync in BridgeAccess. Left: device with IndexedDB tables for packages, progress, quiz attempts, bookmarks, downloads, and an outbox queue. Middle: service worker/background sync and the Spring Boot sync endpoint. Right: Postgres tables for learning_progress, quiz_attempts, quiz_answers, bookmarks, and downloads. Show actions queued while offline, a batched replay when connectivity returns, idempotent upserts with no duplicates, completed steps never un-completing, and the outbox clearing only after acknowledgement. Include an offline banner and waiting-to-sync indicator. Style: clean flow diagram, directional arrows, numbered sequence, accessible palette."

### 18.4 How the blueprint creates platform value

- **Reduces transaction cost on both sides:** contributors spend minutes, learners spend kilobytes.
- **Creates a reusable unit:** the package can be listed, downloaded, studied, assessed, synced, and measured.
- **Separates concerns cleanly:** the client owns offline experience; the API owns contracts; the worker owns generation; the database owns truth.
- **Scales supply without scaling headcount:** AI does the conversion; validation and retries protect quality.
- **Improves with use:** downloads, completion, and quiz data reveal which subjects and packages matter.
- **Stays affordable:** rate limits, small payloads, caching, and duplicate detection control cost.
- **Remains extensible:** accounts, audio, moderation, and dashboards can be added without changing the core package model.

---

## 19. Requirements Traceability

| Goal (Part A/PRD) | Capability | MVP evidence |
|---|---|---|
| G1 — Download on weak connection, finish offline | C3, C7 | Resumable download; offline viewer and quiz |
| G2 — Small packages | C3, C5 | Text bundle ≈ 30 KB; images optimized |
| G3 — Runs on old phones | C7, NFR performance | JS budget, no heavy effects, real-device test |
| G4 — Create in under two minutes | C4, C5 | Short form; AI generation; link reading |
| G5 — Measurable feedback | C6, C9 | Progress, quiz score, Learnt badge, metrics |

---

## 20. Part B Summary

BridgeAccess is a platform because it connects contributors, an AI conversion core, and constrained learners through a reusable offline package, and because each component lowers a different barrier: effort of creation, cost of discovery, cost of data, cost of connectivity, and lack of feedback. The MVP proves one complete loop — **create → publish → download → study offline → pass → sync** — on real devices and real networks. Everything else (accounts, audio, moderation, dashboards, recommendations) extends that loop rather than replacing it.

---

# Part D: AI-Assisted Design Review

## 21. Review Approach

AI was used as a **design reviewer and alternative generator**, not as the decision-maker. Every recommendation was treated as a hypothesis and checked against four things:

1. **The stated problem and MVP constraints** — offline-first, low-end shared phones, costly data, no accounts, 9-day delivery.
2. **The PRD requirements and API/schema contracts** — a recommendation that breaks a requirement is rejected even if it is technically elegant.
3. **Security, performance, and scalability impact** — especially server-side fetches, identity, payload size, and AI latency.
4. **Reversibility and testability** — prefer a simple choice we can validate on a real device and change later.

**Evaluation rubric used for every AI suggestion**

| Criterion | Question we asked |
|---|---|
| Requirement fit | Does it satisfy the PRD and the offline loop, or add scope? |
| Evidence | Is the claim backed by the schema, API, device constraints, or a test? |
| Cost | What does it add in data, CPU, memory, money, or operational effort? |
| Risk | What can fail, and is the failure visible and recoverable? |
| Reversibility | Can we change our mind later without rewriting the product? |
| Simplicity | Is it the smallest approach that meets the requirement? |

**Decision outcomes.** We accepted some recommendations with changes, rejected some outright, and deferred others. The table below summarizes the six decisions documented in the log.

| ID | Decision | AI's initial recommendation | Our evaluation | Final choice |
|---|---|---|---|---|
| D1 | Client offline storage | SQLite compiled to WebAssembly | Partially agree; rejected on size and CPU cost | IndexedDB (Dexie) + service worker |
| D2 | Download format | Single ZIP bundle | Disagree; no resume, decompression cost | Manifest + resumable parts |
| D3 | AI generation execution | Synchronous call in the request | Disagree; timeouts and duplicate work | Async job + status + sweeper |
| D4 | Identity and authorization | Accounts with JWT/OAuth | Partially agree; outside MVP scope | Anonymous device UUID + server-side hardening |
| D5 | Offline sync | CRDT-based merge | Disagree; overkill for one writer | Idempotent batch outbox |
| D6 | Link reading | Standard HTTP fetch + timeout | Disagree; SSRF risk | Hardened reader with address policy |

---

## 22. AI Decision Log

### D1 — Offline storage: IndexedDB vs. SQLite WASM vs. Cache API

| Item | Description |
|---|---|
| **Question to AI** | Which client-side storage should hold downloaded packages, images, progress, quiz attempts, bookmarks, and the sync outbox so the app works fully offline on low-end Android phones? Compare IndexedDB, SQLite compiled to WebAssembly, and the Cache API. |
| **AI Recommendation** | Use SQLite compiled to WebAssembly (for example `sql.js` or `wa-sqlite`) as a single relational store, so the client can run SQL queries and transactions, and use a service worker for the app shell. |
| **Your Evaluation** | **Partially agree, but reject for the MVP.** The relational model is attractive, but a SQLite WASM build adds a significant runtime download and uses more memory and CPU on old phones — directly against the low-end-device and data-cost goals. It also duplicates the server's relational model on a client that only needs simple local lookups. The Cache API is for request/response caching, not structured learner state such as "which steps are complete." IndexedDB is built in, has no download cost, supports indexes and transactions, and is sufficient for this data. |
| **Final Decision** | Use **IndexedDB via Dexie** for packages, content, images, progress, quiz attempts, bookmarks, downloads, and the outbox. Use the service worker for the app shell and static assets. |
| **Justification** | No extra runtime payload; native browser support; transactional and indexed; works with the existing React live-query approach; aligns with the PRD stack and the low-end-device goal. Trade-off: less expressive querying than SQL, which is acceptable because filtering and sorting of the library happen on small local collections. Revisit only if the client needs complex reporting. |

### D2 — Download format: single ZIP vs. resumable manifest parts

| Item | Description |
|---|---|
| **Question to AI** | Should a learning package download as one ZIP file containing JSON and images, or as a manifest plus separate parts? Consider weak, intermittent connections and old devices. |
| **AI Recommendation** | Serve one ZIP bundle. It is atomic, simple to reason about, and one request reduces per-request overhead; the client unzips after download. |
| **Your Evaluation** | **Disagree for this context.** A ZIP is atomic only after the whole archive arrives; a dropped connection forces a restart unless HTTP range requests are implemented. Decompression adds CPU and memory pressure on old phones, and the learner cannot start reading until the entire archive is ready. The PRD requires progress, automatic retry, resume without re-downloading finished parts, and a package that counts as downloaded only when every part is stored. A manifest with independent parts meets those rules directly; a ZIP does not. |
| **Final Decision** | Download a **manifest** (metadata plus a part list), fetch each part with progress and automatic retry, store parts locally, and mark the package downloaded only after all parts are verified. |
| **Justification** | Satisfies FR-4.3 and FR-4.4, survives weak networks, avoids ZIP CPU and memory cost, and makes retries cheap because only missing parts are refetched. Trade-off: more requests and bookkeeping, which the download manager already owns. |

### D3 — AI generation: synchronous request vs. asynchronous job

| Item | Description |
|---|---|
| **Question to AI** | Should package creation call Gemini synchronously inside the `POST /packages` request and return the finished package, or create the package immediately and run generation asynchronously? |
| **AI Recommendation** | Call Gemini synchronously and return the completed package in the same request. It is simpler, avoids a queue, and gives the contributor an immediate result. |
| **Your Evaluation** | **Disagree.** Gemini latency and failures are unpredictable. A long request can time out, and client retries can duplicate work and even duplicate packages. The UI needs a Processing state and a Retry action, and the platform must detect stuck jobs. Synchronous calls also make rate limiting and cost control harder because every retry is a fresh generation. An asynchronous job with explicit states is the better fit. |
| **Final Decision** | Create the package with status **Processing**, run generation asynchronously, expose status to the contributor, validate the output, retry once, then publish or fail with a reason. A sweeper detects stuck jobs. |
| **Justification** | Satisfies FR-5.7, FR-6.4, and the reliability requirement that stuck AI jobs are detected. It decouples user latency from AI latency and makes retries and rate limits manageable. Trade-off: more states and status polling, which the API and data model already represent. |

### D4 — Identity and authorization: accounts vs. anonymous device ID

| Item | Description |
|---|---|
| **Question to AI** | Should the MVP use real accounts with JWT/OAuth, or an anonymous device ID? Consider learner privacy, abuse control, cross-device continuity, and the 9-day scope. |
| **AI Recommendation** | Use accounts with JWT/OAuth from the start. Anonymous IDs are insecure, cannot support cross-device sync, and make abuse control and ownership harder. |
| **Your Evaluation** | **Partially agree on the risk, disagree on scope.** The security concern is valid: a device UUID is not a credential. But accounts add sign-up friction, personal data, password/identity handling, and recovery flows — all outside the MVP non-goals, which explicitly defer accounts and require no personal data. An unguessable UUID is adequate to scope learner data if it is combined with server-side rate limits, ownership checks on delete and retry, and the rule that another device's package returns not-found rather than forbidden. The limitation must be documented, not hidden. |
| **Final Decision** | Use an **anonymous device UUID** on every request, collect no personal data, enforce per-device and per-IP rate limits and ownership checks, and accept no cross-device continuity in the MVP. Plan real authentication as the top post-MVP enhancement. |
| **Justification** | Meets FR-1.3 and FR-1.4, avoids friction and PII, and keeps the MVP deliverable. Residual risk — one device impersonating another — is accepted and documented in the risk table. The decision is reversible because learner data is already keyed by an identifier that can later map to an account. |

### D5 — Offline sync: CRDT vs. idempotent batch outbox

| Item | Description |
|---|---|
| **Question to AI** | How should offline progress, quiz attempts, bookmarks, and downloads be reconciled when the connection returns? Compare a CRDT-based sync layer with a queued batch sync. |
| **AI Recommendation** | Use a CRDT-based sync so concurrent edits merge automatically and conflicts never occur. |
| **Your Evaluation** | **Disagree for the MVP.** CRDTs solve multi-writer concurrency, but the MVP has a single anonymous device writing its own learner data. A CRDT library adds client size and conceptual complexity, and the learning domain already has simple rules: progress is a completion flag, quiz attempts are immutable records, bookmarks are add/remove, and completed steps must never un-complete. A batched outbox with idempotency keys and monotonic completion flags is sufficient, smaller, and easier to test. |
| **Final Decision** | Queue actions in a **local outbox**, replay them in a batch, and apply them idempotently on the server. Unique constraints prevent duplicates; completed steps never revert; the client clears items only after acknowledgement. |
| **Justification** | Meets FR-8.2 and FR-8.3, keeps the client small, and is straightforward to verify by replaying the same batch. Trade-off: no true multi-device merge, which is acceptable until accounts exist. |

### D6 — Link reading: standard fetch vs. hardened SSRF-safe reader

| Item | Description |
|---|---|
| **Question to AI** | How should the server fetch a contributor-provided URL to extract article text, given that the URL is untrusted input? |
| **AI Recommendation** | Use a standard HTTP client with a timeout and follow redirects, then extract the main text with an HTML parser. |
| **Your Evaluation** | **Disagree with the naive version.** A server-side fetch of an arbitrary URL is a server-side request forgery (SSRF) risk: it can reach localhost, private networks, cloud metadata endpoints, and internal services, and redirects can bypass a one-time check. A timeout is a reliability control, not a security control. The fetcher must resolve the host, reject private/loopback/link-local/metadata addresses, re-check after every redirect, cap response size and time, and allow only HTTP(S). |
| **Final Decision** | Use a **hardened link reader**: address policy plus guarded DNS resolution, redirect re-validation, timeouts, response-size caps, HTML text extraction, and clear failure messages. YouTube is handled separately. If the source cannot be read safely, ask the contributor to paste the content. |
| **Justification** | Addresses the security NFR and closes a classic SSRF path while preserving the contributor experience; the system never invents content when a link is unreadable. Trade-off: some legitimate URLs may be rejected, which is an acceptable safety cost for an MVP that fetches user-supplied links. |

---

## 23. AI-Discovered Gaps, Assumptions, and Risks

The review also surfaced issues that were not obvious from the requirements alone. Each was evaluated before being accepted or deferred.

| # | Finding | AI suggestion | Our evaluation | Action |
|---|---|---|---|---|
| F1 | Activation metric can only count devices that wrote something; a device that only browsed leaves no row | Add a first-seen device table | Valid, but adds a table and a write on every first visit; not required for the MVP loop | Keep the activity-derived denominator and document the limitation in `success_metrics.sql`; revisit with accounts |
| F2 | Download success rate is not measurable because only completed downloads are stored | Record started and failed downloads | Valid for reliability measurement, but adds writes and state for every attempt | Defer the metric; log failures, and add status tracking when reliability reporting matters |
| F3 | Sync outcomes are logged but not persisted | Add a sync-run table | Useful for operations, but duplicates the log for a small MVP | Keep structured logs for now; add a table if sync volume grows |
| F4 | Pass mark is ambiguous (60% in the PRD, 80% in the designs) | Use 80% because it is stricter | The PRD explicitly says 60% default and configurable; designs are inconsistent | Keep 60% configurable and confirm with product before launch |
| F5 | Diagram step rule is ambiguous (text-only package shows a diagram in one design) | Give every package a diagram | Adds size, cost, and AI failure surface; contradicts the package-type rule | Keep the diagram for **with_visual** packages only; failed diagrams do not block publishing |
| F6 | Stuck AI jobs need a timeout, but Gemini latency varies | Use a fixed 5-minute timeout | Reasonable starting point, but the right value depends on the model and load | Make the timeout configurable and pair it with a sweeper |
| F7 | Copyright and moderation are under-specified | Add automated plagiarism detection | Stronger, but costly and complex for the MVP; own-words summaries and attribution already reduce risk | Keep own-words output, visible attribution, AI label, and a future report path |
| F8 | Per-IP rate limiting alone can punish a school or carrier NAT | Limit only by IP | Both limits are needed: per-device for fairness, per-IP for abuse | Implement both, with server-configurable values |
| F9 | Offline creation is impossible when AI and the source need a connection | Queue package creation offline | Tempting, but the AI job cannot run offline and the source may be unreadable | Keep an offline draft and explain why Create is disabled (FR-5.9) |
| F10 | Image content-type headers can be spoofed | Trust the declared MIME type | Not safe; a malicious file could be stored or served | Validate file signatures/magic bytes and re-encode images during optimization |

**Assumptions explicitly re-checked after the review**

- The first download can happen on a "good enough" connection (A1) — otherwise the offline loop never starts.
- A text bundle in the low tens of KB and images near 80 KB are acceptable (A2) — this is the core data-cost promise.
- IndexedDB and service workers are available on the oldest target browser — to be verified on a real device.
- Anonymous identity is acceptable for the MVP (A8) — accepted with documented risk, not treated as secure authentication.

---

## 24. Alternatives Considered and Rejected

| Alternative | Why AI suggested it | Why we rejected or deferred it | Revisit trigger |
|---|---|---|---|
| **Native iOS/Android app** | Better performance and deeper offline integration | Two codebases, store review, install friction, outside MVP scope | If PWA performance fails on target devices |
| **Accounts with JWT/OAuth** | Stronger identity and cross-device sync | Adds PII, recovery flows, and friction; PRD defers accounts | Top post-MVP enhancement |
| **SQLite WASM on the client** | Relational queries and transactions | Large runtime download and CPU/memory cost on old phones | If local relational reporting becomes necessary |
| **Single ZIP download** | Atomic and simple | No cheap resume; decompression cost; cannot start until complete | If the platform moves to reliable broadband |
| **CRDT sync** | Conflict-free multi-writer merge | Overkill for one anonymous writer; adds size and complexity | When real multi-device accounts arrive |
| **Elasticsearch / Algolia search** | Rich search and ranking at scale | Extra infrastructure and cost for a small catalogue | When catalogue size or search relevance demands it |
| **GraphQL or microservices** | Flexible queries and independent scaling | Operational overhead too high for a 9-day MVP | When team size and traffic justify it |
| **Client-side AI generation** | Offline generation and privacy | Model size, cost, and quality cannot match server-side Gemini on old phones | Not planned |
| **Always-on AI diagrams** | Richer lessons | Increases package size, cost, and failure surface | If learner research shows diagrams are essential |
| **Trusting MIME types for image uploads** | Simple validation | Spoofable; unsafe | Never — replaced by signature validation and re-encoding |

---

## 25. Security, Performance, and Scalability Review

| Area | Concern found in review | Mitigation in the design | Residual risk |
|---|---|---|---|
| **Security** | SSRF via contributor-supplied links | Address policy, guarded DNS resolution, redirect re-validation, timeouts, size caps | Some legitimate URLs rejected |
| **Security** | Anonymous device ID is not a credential | Unguessable UUID, per-device and per-IP limits, ownership checks, not-found on foreign resources | Device impersonation; documented and accepted for MVP |
| **Security** | AI output could contain unsafe HTML or scripts | Treat AI output as text; sanitize before rendering; no raw HTML injection | Model could still produce misleading content |
| **Security** | Prompt injection through source content | Constrain prompts, validate output structure, use only source facts, label AI content | Determined adversarial sources may still degrade quality |
| **Security** | Secret leakage | Gemini and database keys stay server-side; only the API calls them | Operational misconfiguration |
| **Security** | Image upload abuse | Limit count and size; validate signatures; re-encode and optimize | Storage cost from allowed uploads |
| **Performance** | Large JavaScript bundle on old phones | 150 KB gzipped budget, route-level code splitting, no heavy effects | Browser and device variance |
| **Performance** | Slow downloads on weak networks | Resumable parts, retries, small payloads, gzip/ETag, 20-item pagination | Carrier-level throttling |
| **Performance** | Image weight | Optimize to about 80 KB WebP, hard cap 500 KB, lazy/local loading | Some images still cost data |
| **Scalability** | AI latency and quota | Async jobs, rate limits, job queue, duplicate-link detection, configurable timeout | Provider quota or outage |
| **Scalability** | Growing catalogue | Cursor pagination, indexed queries, small list payloads | Very large catalogue may need dedicated search |
| **Scalability** | Storage growth | Private bucket or local disk abstraction, size limits, soft delete | Cleanup of deleted images |
| **Reliability** | Interrupted downloads | Manifest parts, retry, resume, atomic completion | Persistent network loss |
| **Reliability** | Duplicate offline actions | Idempotent sync, unique constraints, monotonic completion | Clock or client bugs |
| **Reliability** | Stuck generation jobs | Timeout plus sweeper marks failed jobs | False timeout under provider slowness |

---

## 26. What We Accepted, Changed, and Rejected

| Outcome | Decisions |
|---|---|
| **Accepted with modification** | IndexedDB instead of SQLite WASM (D1); anonymous identity instead of accounts for the MVP, with hardening (D4) |
| **Rejected** | Single ZIP download (D2); synchronous AI generation (D3); CRDT sync (D5); naive link fetching (D6); per-IP-only rate limiting (F8); trusting MIME types (F10) |
| **Deferred** | Accounts and cross-device sync; first-seen device table; download success-rate tracking; sync-run table; automated plagiarism detection; always-on diagrams |
| **Confirmed by evidence** | Offline-first PWA; manifest + resumable parts; async generation with validation and retry; attribution and AI labeling; 60% configurable pass mark; 20-item pagination; image optimization |

**Why these decisions are defensible**

- They are **traceable to requirements** (FR-4.3/4.4, FR-5.7, FR-6.4, FR-8.2/8.3, FR-1.3/1.4, security NFR).
- They favor the **smallest approach that meets the constraint**, which matters for a MVP and low-end devices.
- They are **reversible**: the identity model can map to accounts, storage can move from local disk to Supabase, and sync can evolve beyond a single writer.
- They **surface their own limitations** (anonymous identity, no multi-device merge, possible URL rejections) rather than hiding them.

---

## 27. Part D Summary

AI was useful for generating alternatives, exposing edge cases, and pressure-testing security and offline assumptions. It was not useful as an authority: several of its recommendations — SQLite WASM, ZIP bundles, synchronous generation, CRDT sync, and naive link fetching — were rejected because they conflicted with low-end-device constraints, resumability, reliability, or SSRF protection. The final design keeps the AI-assisted improvements that survived evaluation: **IndexedDB offline storage, resumable manifest downloads, asynchronous validated generation, anonymous identity with server-side hardening, idempotent batch sync, and a hardened link reader.**

---

# Part E: Final Design Decisions and Defense

## 28. Final MVP Scope

### 28.1 MVP statement

The MVP proves **one complete platform loop** on a weak network and a real low-end phone:

> **A contributor turns a link into a package → the package is published → a learner discovers and downloads it once → the learner studies every step and passes a quiz fully offline → progress syncs when the connection returns.**

Anything that does not directly prove this loop is deferred.

### 28.2 Final in-scope capabilities

| Capability | MVP scope | Priority |
|---|---|---|
| **Anonymous identity** | Device UUID on every request; no personal data; ownership scoping | P0 |
| **Onboarding** | Three intro slides, Skip/Next/Get Started; first launch only | P1 |
| **Home and Learn** | Continue Learning, Recent Downloads, full lists with offline search/filter/sort | P0 |
| **Explore and download** | Search, filters, sort, 20-item pagination; progress, retry, resume, atomic completion; large-download confirmation | P0 |
| **Contribute and create** | Text and video forms, inline validation, 10,000-character limit, up to 3 images, link required, rate limit, delete own package | P0 |
| **AI generation** | AI title, subject, overview, 3–5 takeaways, 300–400-word Details, 4–6 key concepts, exactly 5 MCQs with explanations; validate and retry once | P0 |
| **Optional diagram** | One AI diagram for packages with visuals; failure does not block publishing | P1 |
| **Viewer and quiz** | Details → Key Concepts → Diagram (if visuals) → Quiz; step indicator, mark complete, resume; instant offline grading; score, pass/fail, Learnt badge; Review Answers | P0 |
| **Bookmarks** | Bookmark/unbookmark; offline Bookmarks tab | P1 |
| **Offline and sync** | Full offline study, queued actions, idempotent batch sync, offline banner, storage usage, Clear my data, installable PWA | P0 |
| **Trust and safety** | AI label, attribution, validation, rate limits, SSRF protection, ownership checks, basic safeguards | P0 |
| **Measurement** | Success metrics from existing tables | P0 |

### 28.3 Explicitly out of scope

- User accounts, passwords, and cross-device identity
- Video hosting or streaming (packages link to YouTube)
- Audio packages
- Native iOS/Android apps
- Teacher or school dashboards
- Payments and social features
- Advanced moderation tooling
- Real multi-device merge and conflict resolution

### 28.4 Definition of done

The MVP is done only when all of the following are true:

1. A package can be created from a pasted article or YouTube link, published, and found in Explore.
2. A learner can download it on a throttled connection, then study every step and pass the quiz with the network disabled.
3. A dropped download resumes without re-downloading finished parts.
4. Offline progress, quiz attempts, and bookmarks replay without duplicates and without un-completing steps.
5. The app runs acceptably on the oldest target device and within the JavaScript and data budgets.
6. Security checks pass: keys are server-side, ownership is enforced, link fetching is SSRF-safe, and rate limits hold.
7. Activation, completion, quiz outcome, package size, contribution, and reliability are measurable from the stored data or structured logs.

---

## 29. Major Design Decisions

| ID | Decision | Why | Alternative rejected | Requirement link |
|---|---|---|---|---|
| **FD-1** | Offline-first PWA with **IndexedDB (Dexie)** and a service worker | No extra runtime download; native structured storage on old phones; works with React live queries | SQLite WASM (too large and CPU-heavy) | G1, G3, FR-8.1, FR-8.6 |
| **FD-2** | **Manifest + resumable parts** download, atomic completion | Survives weak networks; cheap retries; no decompression cost | Single ZIP bundle (cannot resume cheaply) | FR-4.3, FR-4.4 |
| **FD-3** | **Asynchronous AI generation** with validation, one retry, and a stuck-job sweeper | Decouples user latency from AI latency; enables retry, rate limits, and clear failure states | Synchronous generation (timeouts, duplicate work) | FR-5.7, FR-6.4 |
| **FD-4** | **Anonymous device UUID** with server-side hardening, no accounts | Meets privacy and scope constraints; no sign-up friction | JWT/OAuth accounts (PII, recovery, out of scope) | FR-1.3, FR-1.4 |
| **FD-5** | **Idempotent batch outbox** for offline sync | Simple, small, testable; matches single-writer data model | CRDT merge (overkill and larger client) | FR-8.2, FR-8.3 |
| **FD-6** | **Server-side Gemini only**, structured JSON, own-words output, attribution | Keeps keys safe, enforces validation, preserves trust | Client-side AI (size, cost, quality) | FR-6.1–6.7 |
| **FD-7** | **Hardened link reader** with address policy, guarded DNS, redirect re-checks, size/time caps; pasted content fallback | Prevents SSRF while keeping link-based contribution usable | Naive HTTP fetch (SSRF risk) | Security NFR, FR-6.3 |
| **FD-8** | **Modular monolith** (Spring Boot REST API) | One deployable, simple operations, fits the 9-day team and traffic | Microservices (operational overhead) | NFR maintainability, cost |
| **FD-9** | **REST `/api/v1`** with cursor pagination, gzip, ETag, small list payloads | Simple contracts, cacheable, low data cost | GraphQL (flexibility we do not need) | NFR performance, data usage |
| **FD-10** | **Postgres + private storage**; RLS enabled; storage abstraction for local/Supabase | Durable truth, secure images, environment flexibility | Direct client database access (unsafe) | Security NFR, data model |
| **FD-11** | **Image optimization** to about 80 KB WebP, hard cap 500 KB, max 3 images | Protects the data-cost promise and package size | Store originals (large, expensive) | FR-5.4, FR-6.5, G2 |
| **FD-12** | **PWA over native** | One codebase, no store friction, installable, updateable | Native apps (scope and time) | G3, FR-8.6 |

---

## 30. Important Trade-offs

| Trade-off | Side chosen | What we give up | Why acceptable | Mitigation |
|---|---|---|---|---|
| **Offline vs. freshness** | Offline-first | Content can be stale after the source changes | The package is a study unit, not a live feed; the Details tab links to the original | Attribution link; republish/versioning later |
| **Small size vs. richness** | Small packages | Few images, optional diagrams, no video | Data cost is the core constraint | Images ≤80 KB; diagrams P1; text-first lessons |
| **No accounts vs. continuity** | Anonymous device ID | No cross-device sync or real identity | Privacy and zero friction are explicit MVP goals | Documented risk; local profiles later; accounts post-MVP |
| **AI speed vs. quality** | Generate asynchronously, validate and retry | Contributor waits briefly; some jobs fail | Quality and honesty matter more than instant output | Processing state, clear failure reason, retry, AI label |
| **Resumable parts vs. request overhead** | Many small requests | More HTTP overhead than one ZIP | Retries become cheap and downloads survive drops | Parallelism limits, gzip, caching, progress UI |
| **Postgres search vs. dedicated search** | `pg_trgm` + indexed queries | Less powerful ranking than Elasticsearch | Catalogue is small; cost and ops are lower | Revisit at catalogue scale |
| **Anonymous ID vs. abuse control** | Anonymous, rate-limited | Device impersonation is possible | MVP has no PII and low-value data; risk is documented | Per-device + per-IP limits, ownership checks, real auth later |
| **Modular monolith vs. microservices** | Monolith | Independent scaling per component | One team, one deploy, low traffic | Stateless API; split only when justified |
| **PWA vs. native** | PWA | Some device APIs and peak performance | Reach, installability, one codebase | JS budget, real-device test, code splitting |
| **Cut scope vs. completeness** | Ship the offline loop | Bookmarks, diagrams, profiles can slip | The loop is the product; extras are enhancements | Explicit cut order and feature flags |

---

## 31. Technical and Operational Risks

| ID | Risk | Likelihood / impact | Mitigation | Contingency |
|---|---|---|---|---|
| **R-1** | Gemini unavailable, rate-limited, or too costly | Medium / High | Async queue, rate limits, duplicate-link detection, configurable model, cost monitoring | Queue jobs; keep existing packages fully usable; secondary provider behind an interface |
| **R-2** | Unreadable source (paywall, anti-bot, region lock) | High / Medium | Link reader with clear failure; ask contributor to paste content; never invent | Package stays failed with a reason; contributor retries with pasted text |
| **R-3** | AI output invalid or misleading | Medium / High | Strict validation, one retry, own-words prompt, AI label, attribution, source link | Mark failed and ask for retry; human spot-check early packages |
| **R-4** | SSRF or link-fetch abuse | Medium / High | Address policy, guarded DNS, redirect re-validation, timeouts, size caps, HTTP(S) only | Disable link reading and require pasted content if an exploit is found |
| **R-5** | Anonymous device impersonation | Low / Medium | Unguessable UUID, rate limits, ownership checks, not-found responses, no PII | Tighten limits; accelerate accounts post-MVP |
| **R-6** | Download failures on weak networks | High / Medium | Resumable parts, automatic retry, atomic completion, size warnings | Retry later; show progress and reason; support manual resume |
| **R-7** | Sync duplicates or lost progress | Low / High | Idempotency keys, unique constraints, monotonic completion, replay tests | Re-run sync; server state is authoritative; never un-complete |
| **R-8** | Old devices cannot cope | Medium / High | JS budget, code splitting, no heavy effects, real-device testing | Reduce features behind flags; drop optional images first |
| **R-9** | Free hosting sleeps or pauses | Medium / High | Longer client timeouts, keep-warm ping, check plans before demo | Use a paid tier for the demo window |
| **R-10** | Storage and image cost growth | Medium / Medium | Image caps, optimization, soft delete, cleanup job | Lower caps; move to cheaper storage |
| **R-11** | Copyright or moderation complaint | Medium / High | Own-words summaries, visible attribution, AI label, terms, basic safeguards | Remove package; add report path; legal review |
| **R-12** | Timeline overrun | High / High | P0 first, seed packages day 1, cut order, feature flags | Ship the offline loop; defer P1/P2 |
| **R-13** | Shared-device data mixing | Medium / Medium | One identity per browser profile; Clear my data; local profiles later | Clear and re-onboard; document limitation |

---

## 32. Development Priorities and Implementation Roadmap

### 32.1 Priority principles

1. **Protect the core loop:** offline download, study, quiz, and sync are never cut.
2. **Build the spine first:** identity → package creation → generation → catalogue → download → learner data → sync.
3. **Seed content on day 1** so frontend and testing never wait for AI.
4. **Test on the worst realistic conditions:** throttled network and an old real phone, not a desktop.
5. **Ship P0 before P1**, and use feature flags for anything uncertain.

### 32.2 Implementation roadmap

| Phase | Days | Deliverables | Exit criteria |
|---|---|---|---|
| **0. Foundations** | 0 | Scope freeze, schema migration, seeded sample packages, target device and network baseline, metric definitions | Team can run the API and load a seed package |
| **1. Backend spine** | 1–2 | Schema, device-ID filter, error envelope, rate limits, package creation, async Gemini text generation, validator, retry | A submitted package reaches Published or Failed with a reason |
| **2. Delivery** | 3–4 | Image optimization, manifest and bundle, resumable download, learner-data APIs, idempotent sync endpoint | Download resumes correctly; replayed sync creates no duplicates |
| **3. Frontend foundation** | 5–6 | App shell, onboarding, Home, Learn, Explore, download manager, Contribute and Create forms | A learner can create, find, and download a package |
| **4. Learning experience** | 7 | Package tabs, viewer steps, mark complete, quiz, result, Review Answers, bookmarks (P1) | A learner can finish a package and earn Learnt offline |
| **5. Hardening** | 8 | Offline sync, accessibility, security review, performance budget, stuck-job handling, storage controls | All P0 tests pass; budgets met; security checklist signed off |
| **6. Validation and release** | 9 | Throttled-network test, real old-phone test, fixes, deploy, metric dashboards | Definition of done met; demo loop works end to end |

### 32.3 Cut order if time runs short

1. AI diagrams and contributor images
2. Bookmarks
3. Review Answers (note: P0 in FR-7.7 but listed as cuttable in the PRD — resolve with product before cutting)
4. Local profiles

**Never cut:** offline download, offline quiz, sync, attribution, security controls.

### 32.4 Quality gates

| Gate | Check |
|---|---|
| **Unit and integration** | Validation, generation, download, sync replay, ownership, rate limits |
| **Contract** | API error envelope, pagination, ETag, status codes |
| **Offline** | Airplane mode: study, quiz, bookmark, resume, then sync |
| **Network** | Throttled 3G and intermittent drops: download resume, retries |
| **Device** | Oldest target phone: install, scroll, study, quiz, storage |
| **Security** | Keys server-side, SSRF cases, ownership, rate limits, input validation, dependency scan |
| **Accessibility** | 44 px targets, contrast, labels, screen reader, text scaling |
| **Data** | Bundle and image sizes within budget; gzip/ETag verified |

---

## 33. Measurable Success Criteria

Targets are **proposed** and must be confirmed before launch. Every metric comes from the existing tables or structured logs; no third-party tracker is required.

| Metric | Definition | Proposed target | Data source |
|---|---|---|---|
| **Activation** | Share of new devices that complete at least one download within their first 24 hours | ≥ 40% | `downloads` plus activity-derived first-seen |
| **Completion** | Share of downloaded packages with every learning step complete | ≥ 50% | `learning_progress` |
| **Learning outcome** | Quiz pass rate (≥60%) and average score | Pass ≥ 60%; average ≥ 70% | `quiz_attempts` |
| **Data efficiency** | Median downloaded package size | Text-only ≤ 100 KB; with visual ≤ 300 KB | `learning_packages.package_size_bytes` |
| **Contribution** | Packages created per active device; share that reach Published | ≥ 0.2 per device per month; ≥ 90% published | `learning_packages` |
| **Generation reliability** | Share of creation jobs that publish after at most one retry | ≥ 95% | `learning_packages.status`, logs |
| **Download reliability** | Share of started downloads that complete | ≥ 95% (requires started/failed tracking; proposed) | `downloads` plus new status logging |
| **Sync reliability** | Share of sync batches applied without rejection | ≥ 99% | `event=sync` logs; proposed sync-run table later |
| **Offline completion** | Share of quizzes completed with no network | ≥ 90% (requires an offline flag in the sync payload) | Sync payload metadata |
| **Performance** | First useful screen on throttled 3G; initial JS | < 3 s; ≤ 150 KB gzipped | Lighthouse, bundle report |
| **Accessibility** | Automated WCAG AA checks and manual screen-reader pass | 0 critical issues | Automated plus manual audit |
| **Cost** | Gemini and storage cost per published package | Within agreed monthly budget | Provider dashboards |

**Measurement guardrails**

- Count server timestamps, not device clocks.
- Document denominators (for example, activation counts devices that wrote something, not every visitor).
- Do not claim a metric the data cannot support; add logging or a table first.

---

## 34. Change Request Impact Analysis

No single change request was assigned, so the final design is stress-tested against **all five scenarios** in the brief. The same method applies if one is issued: protect the core loop, degrade gracefully, keep decisions reversible, and re-measure.

### 34.1 Change-control principles

1. **Protect the offline loop first** — download, study, quiz, sync.
2. **Degrade quality before availability** — fewer diagrams and images before fewer packages.
3. **Prefer reversible changes** — feature flags, configurable limits, provider interfaces.
4. **Re-baseline the metrics** — a changed constraint changes what "success" means.
5. **Make the trade-off explicit** — document what gets worse, not just what gets cut.

### 34.2 Scenario matrix

| Change request | Impact on the design | What we would modify | What we protect | Trade-off / risk |
|---|---|---|---|---|
| **CR-1: Budget reduced by 50%** | Gemini, storage, and hosting costs dominate; image size and diagram generation are the largest variable costs | Stricter per-device and per-IP limits; duplicate-link caching; cheaper/smaller model; disable AI diagrams; lower image target (e.g., 60–80 KB); cap packages per device; local/cheap storage; pre-seed curated packages; aggressive caching and ETag | Offline loop, quiz, sync, attribution, security | Less rich packages, slower contribution, lower limits, fewer new packages; cost per package becomes a first-class metric |
| **CR-2: Support 10× users** | API throughput, DB connections, AI quota, storage, and download bandwidth become bottlenecks; anonymous rate limits must scale | Run multiple stateless API instances behind a load balancer; pool DB connections; add read caching/CDN for catalogue and bundles; move generation to a worker pool with a durable queue; per-IP and per-device limits; storage + CDN for images; load test | Same product behavior and offline experience | Higher infrastructure cost and operational complexity; AI quota is the binding constraint; may need provider capacity planning |
| **CR-3: External API (Gemini) unavailable** | New package creation stops; existing packages, downloads, study, and quizzes are unaffected because they are local | Queue jobs with backoff and a circuit breaker; show Processing or a clear unavailable message; add a provider abstraction for a secondary model; ship pre-generated seed packages; allow pasted content; degrade diagrams first | Learner path and existing catalogue | Contributions are delayed or lower quality; retry backlog; possible cost spike on recovery |
| **CR-4: New privacy/security requirement** | Anonymous IDs become a pseudonymous identifier subject to deletion, retention, and audit obligations; more endpoints and controls | Data export and delete-by-device endpoints; cascade deletes across all learner tables; retention job; encryption at rest/in transit; security headers and CSP; admin audit log; stricter input validation; privacy notice; possibly real authentication if identity is required | Data minimization, offline experience, ownership checks | Schema and ops work; deletion must cover images and derived content; real auth would be a larger scope change |
| **CR-5: Deadline shortened** | Only the core loop can ship; P1/P2 work must be deferred immediately | Freeze scope to P0; text packages only (video optional); no AI diagrams or contributor images; no bookmarks or local profiles; seed packages; keep Review Answers only if the loop needs it; feature-flag the rest | Offline download, quiz, sync, attribution, security | Less rich learning experience and fewer contribution options; demo depends on seeded content |

### 34.3 How we would decide if a real change request arrives

1. **Classify the change:** cost, capacity, availability, compliance, or time.
2. **Model the impact** on the core loop, the metrics, and the risk register.
3. **Choose the least reversible-risk option** — configuration and feature flags first, schema and architecture changes second.
4. **Re-run the quality gates** on the worst realistic device and network.
5. **Update this document and the success targets** so the defense matches the design that actually ships.

---

## 35. Final Defense

The design is defensible because every major decision is traceable to a constraint, a requirement, or a verified risk:

- **Offline-first** is the product, not a feature; it drives storage, download, sync, and size decisions.
- **Small payloads and images** follow directly from the data-cost problem.
- **Anonymous identity** is the correct MVP trade-off between privacy, friction, and scope, with its residual risk documented.
- **Asynchronous, validated generation** keeps AI failure from becoming learner failure.
- **Server-side keys and a hardened link reader** treat the input and the provider as untrusted.
- **A modular monolith, REST, and Postgres** are the simplest choices that meet the requirements and can scale later.
- **The roadmap** protects the core loop and tests on the real constraints rather than a desktop.

**What would change our mind**

- Real-device testing shows the PWA cannot meet the performance budget → revisit native or a lighter client.
- User research shows learners need multi-device continuity → prioritize accounts earlier.
- AI cost or accuracy misses the target → shrink scope to curated packages or add a second provider.
- Security review finds an SSRF or identity flaw → disable link reading or tighten identity before launch.
- Contribution volume stays near zero → seed more content and reduce creation friction before adding features.

---

## 36. AI Prompt Placeholders for Diagrams

> **Final MVP architecture prompt:**
> "Draw the final MVP architecture for BridgeAccess, a low-bandwidth offline learning PWA. Layers: (1) Client — React/TypeScript PWA with service worker app shell, IndexedDB (Dexie) packages/progress/outbox, resumable download manager, viewer and offline quiz; (2) API — Spring Boot REST `/api/v1` modular monolith with device-ID filter, rate limiting, validation, ownership checks, sync endpoint, and SSRF-safe link reader; (3) Async — durable generation job, Gemini structured JSON, lesson validator, one retry, image optimizer, stuck-job sweeper; (4) Data — Supabase Postgres schema `app` with nine tables, private image bucket or local disk; (5) External — Gemini API, article sources, YouTube. Show numbered arrows for: contributor submits source; AI generates and validates; package published to catalogue; learner downloads manifest parts; learner studies offline; outbox syncs on reconnect. Label the trust boundaries and where secrets live (server only). Style: clean, minimal, accessible colors, legend, suitable for A4 landscape."

> **Implementation roadmap prompt:**
> "Draw a 9-day implementation roadmap timeline for BridgeAccess. Swimlanes or phases: Foundations (day 0), Backend spine (days 1–2), Delivery and download (days 3–4), Frontend foundation (days 5–6), Learning experience (day 7), Hardening (day 8), Validation and release (day 9). For each phase show the deliverables and the exit criterion. Mark the critical path and the cut order: AI diagrams and images, bookmarks, Review Answers, local profiles. Highlight the non-negotiable core loop: create → publish → download → study offline → quiz → sync. Style: clean Gantt-style timeline, readable labels, accessible palette, A4 landscape."

> **Change-impact map prompt:**
> "Draw a decision map showing how five change requests affect BridgeAccess: budget −50%, 10× users, Gemini unavailable, new privacy/security requirement, and a shortened deadline. For each, show the primary impact, the design modification, and what stays protected (offline loop, quiz, sync, attribution, security). Use a central 'Core offline loop' node that remains unchanged, with branches for cost, capacity, availability, compliance, and time. Style: simple radial or hub-and-spoke diagram, color-coded by change type, short labels, legend, A4 portrait."

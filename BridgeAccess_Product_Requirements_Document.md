# BridgeAccess: Product Requirements Document

Version 1.0 (draft) · 10 November 2026 · Scope: 9-day MVP · Companion to the BridgeAccess API Documentation and Implementation Guide

## 1. Summary

BridgeAccess is a mobile-first progressive web app (PWA) that turns online educational content (articles and YouTube videos) into lightweight, offline-ready **learning packages**. Each package holds an AI-generated summary, key concepts, an optional diagram, and a 5-question quiz. A learner downloads a package once on a good connection and studies it anywhere with no connection, on an older or shared phone.

Tagline: **Learn more. Use less. Learn anywhere.**

## 2. Problem and opportunity

- Most online learning content is heavy (video, large pages, ads) and needs a steady connection.

- Learners with unstable or expensive data, and with older or shared devices, cannot depend on it.

- People who find good material have no simple way to shrink it into something others can study offline.

**Opportunity:** compress content to its essentials with AI, let the community share the result, and make the whole learning experience work without a network.

## 3. Goals and non-goals

### 3.1 Goals

| ID  | Goal                                                                                                 |
|-----|------------------------------------------------------------------------------------------------------|
| G1  | A learner can download a package on a weak connection and finish learning it fully offline.          |
| G2  | Packages are small: typical text-only packages in the low hundreds of kilobytes or less.             |
| G3  | The app runs smoothly on old, low-memory phones.                                                     |
| G4  | Anyone can turn an article or YouTube video into a package in under two minutes of their own effort. |
| G5  | Learners get measurable feedback: progress, a quiz score, and a Learnt status.                       |

### 3.2 Non-goals for the MVP

- User accounts and login (the MVP uses an anonymous device ID).

- Hosting or streaming video. Video packages link to YouTube and carry only text and images.

- Audio resources. The onboarding copy mentions audio, but it is deferred.

- Native iOS or Android apps.

- Teacher or school dashboards, payments, and social features.

- Advanced moderation tooling. The MVP has basic safeguards only.

## 4. Users

| User                                               | Description                                                                                          | Needs                                                                 |
|----------------------------------------------------|------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------|
| **Learner** (primary)                              | A student or self-learner with unstable, slow or costly internet, often on an older or shared phone. | Small downloads, offline study, clear progress, quick quizzes.        |
| **Contributor** (secondary, often the same person) | Someone who finds a useful article or video and wants to share it as a package.                      | A short form, no technical skill, confidence the result will be good. |

**Context constraints that shape every requirement**

- Connectivity is intermittent. Requests may fail or time out at any moment.

- Data costs money, so every kilobyte counts.

- Devices have little memory and storage, and a slow processor.

- A phone may be shared by several people.

## 5. MVP scope

**In scope:** onboarding, Home, Learn, Explore and Download, Contribute and Create (text and video resources), AI generation (summary, key concepts, quiz, optional diagram), package viewer and quiz with Review Answers, bookmarks, offline storage and background sync, and PWA installation.

**Out of scope:** everything under non-goals above.

## 6. Functional requirements

Priority: **P0** must ship, **P1** should ship, **P2** nice to have.

### 6.1 Onboarding and identity

| ID     | Requirement                                                                                                                                        | Priority |
|--------|----------------------------------------------------------------------------------------------------------------------------------------------------|----------|
| FR-1.1 | Show three intro slides (Learn more, Use less; Create or Explore Learning Packages; Two Ways to Use BridgeAccess) with Skip, Next and Get Started. | P1       |
| FR-1.2 | Show onboarding only on first launch.                                                                                                              | P1       |
| FR-1.3 | Create an anonymous device ID on first launch, silently, and send it with every request.                                                           | P0       |
| FR-1.4 | Collect no personal data.                                                                                                                          | P0       |
| FR-1.5 | Optional local profiles so several people can share one device.                                                                                    | P2       |

### 6.2 Home

| ID     | Requirement                                                                                                                                       | Priority |
|--------|---------------------------------------------------------------------------------------------------------------------------------------------------|----------|
| FR-2.1 | Welcome message with two shortcuts: Learn and Contribute.                                                                                         | P0       |
| FR-2.2 | Continue Learning: the most recent in-progress package with subject and package-type chips, size and a progress bar. See All opens the full list. | P0       |
| FR-2.3 | Recent Downloads: packages with a Learnt badge and a delete action. See All opens the full list.                                                  | P0       |
| FR-2.4 | Explore and Download Packages preview with a download action (needs a connection). See All opens Explore.                                         | P0       |
| FR-2.5 | Bottom navigation: Home, Learn, Contribute, Bookmarks.                                                                                            | P0       |
| FR-2.6 | Home renders fully offline from locally stored data.                                                                                              | P0       |

### 6.3 Learn

| ID     | Requirement                                                                                                                                                                  | Priority |
|--------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|
| FR-3.1 | Learn tab shows Continue Learning and Downloaded Packages.                                                                                                                   | P0       |
| FR-3.2 | Continue Learning and Recent Downloads full lists have search, filters (Subject, Resource type, Package type) and sort (Latest opened, Latest downloaded). All work offline. | P0       |
| FR-3.3 | Delete a downloaded package after confirmation. Learning progress is kept.                                                                                                   | P0       |
| FR-3.4 | Opening a package resumes at the last section.                                                                                                                               | P0       |

### 6.4 Explore and Download

| ID     | Requirement                                                                                                   | Priority |
|--------|---------------------------------------------------------------------------------------------------------------|----------|
| FR-4.1 | Search packages, filter by Subject, Resource type and Package type, and sort by Latest published.             | P0       |
| FR-4.2 | Each card shows the resource icon, title, chips, size and a Download button.                                  | P0       |
| FR-4.3 | Download shows progress, retries automatically on failure, and resumes without re-downloading finished parts. | P0       |
| FR-4.4 | A package counts as downloaded only after every part is stored.                                               | P0       |
| FR-4.5 | Lists load 20 at a time with a Load more button.                                                              | P0       |
| FR-4.6 | Ask for confirmation before a large download (over 1 MB) or when the browser Data Saver setting is on.        | P1       |

### 6.5 Contribute and Create a Package

| ID      | Requirement                                                                                                                                                                     | Priority |
|---------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|
| FR-5.1  | Contribute screen with Create a Package, My Contributions and Community Packages.                                                                                               | P0       |
| FR-5.2  | **Text Resource form:** Original Title (required), Author (required), Content (optional, up to 10,000 characters with a counter), Visuals (optional), Resource Link (required). | P0       |
| FR-5.3  | **Video Resource form:** Video Title (required), Channel Name (required), Transcript (optional, up to 10,000 characters), Visuals (optional), Video URL (required, YouTube).    | P0       |
| FR-5.4  | Visuals: up to 3 images, 500 KB each, PNG, JPG or WebP.                                                                                                                         | P0       |
| FR-5.5  | Inline validation with clear messages.                                                                                                                                          | P0       |
| FR-5.6  | Compress images on the device before upload.                                                                                                                                    | P1       |
| FR-5.7  | After submitting, the package shows as Processing and becomes available when ready. If it fails, show the reason and a Retry button.                                            | P0       |
| FR-5.8  | A contributor can delete their own package.                                                                                                                                     | P0       |
| FR-5.9  | Creating needs a connection. Offline, keep a draft and explain why Create is disabled.                                                                                          | P1       |
| FR-5.10 | Limit creations to 5 per device per day (configurable).                                                                                                                         | P0       |
| FR-5.11 | Info tooltip explaining what visuals are for.                                                                                                                                   | P2       |

### 6.6 AI generation (Gemini, server side)

| ID     | Requirement                                                                                                                                                                                      | Priority |
|--------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|
| FR-6.1 | Generate a learner-friendly title, a subject, an overview, 3 to 5 key takeaways, a 100 to 150 word summary, 4 to 6 key concepts, and exactly 5 multiple-choice quiz questions with explanations. | P0       |
| FR-6.2 | For packages with visuals, generate one simple diagram.                                                                                                                                          | P1       |
| FR-6.3 | If no content was pasted, read the link. If it cannot be read, fail with a clear message asking the contributor to paste the content. Never invent content.                                      | P0       |
| FR-6.4 | Validate AI output (question count, options, answer index, category) and retry once before failing.                                                                                              | P0       |
| FR-6.5 | Optimise every image to about 80 KB or less (WebP).                                                                                                                                              | P0       |
| FR-6.6 | A failed diagram must not block publishing.                                                                                                                                                      | P1       |
| FR-6.7 | Write in the AI's own words, use only facts from the source, and keep attribution (original title, author, link).                                                                                | P0       |

### 6.7 Package details, viewer and quiz

| ID      | Requirement                                                                                                                                                                                               | Priority |
|---------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|
| FR-7.1  | Package screen with two tabs. **Learn:** overview, key takeaways, Start Learning. **Details:** original title, author, resource link (opens the original), category, package type, date downloaded, size. | P0       |
| FR-7.2  | Status badges: Downloaded and Learnt.                                                                                                                                                                     | P0       |
| FR-7.3  | Learning steps: Summary, Key Concepts, Diagram (only if the package has images), Quiz. Show a step indicator, an N of M progress bar, and Previous and Next buttons.                                      | P0       |
| FR-7.4  | Mark Complete on each step, with a Completed badge.                                                                                                                                                       | P0       |
| FR-7.5  | Quiz: 5 multiple-choice questions, one at a time, all must be answered. Grade instantly with no connection.                                                                                               | P0       |
| FR-7.6  | Result: score, percentage, pass or fail, and a completion message. Default pass mark is 60 percent (configurable). A pass gives the Learnt badge.                                                         | P0       |
| FR-7.7  | Review Answers: every question with Correct or Incorrect, the learner's choice, the right answer and an explanation.                                                                                      | P0       |
| FR-7.8  | Review Learning restarts the package.                                                                                                                                                                     | P1       |
| FR-7.9  | Label content as AI-generated and link to the original resource.                                                                                                                                          | P1       |
| FR-7.10 | Bookmark or un-bookmark from the package screen. The Bookmarks tab lists bookmarked packages and works offline.                                                                                           | P1       |

### 6.8 Offline and sync

| ID     | Requirement                                                                                                  | Priority |
|--------|--------------------------------------------------------------------------------------------------------------|----------|
| FR-8.1 | Learning, quiz, review, bookmarks and Home work with no connection after the first load.                     | P0       |
| FR-8.2 | Progress, quiz attempts, bookmarks and downloads made offline are queued and sent when a connection returns. | P0       |
| FR-8.3 | Sync is safe to repeat: no duplicates, and completed steps never un-complete.                                | P0       |
| FR-8.4 | Show an offline banner and a Waiting to sync indicator.                                                      | P1       |
| FR-8.5 | Show storage used and offer Clear my data on this device.                                                    | P1       |
| FR-8.6 | The app is installable to the home screen.                                                                   | P0       |

## 7. Non-functional requirements

Numbers are **proposed targets**. Confirm them against your real devices and networks.

| Area           | Requirement                                                                                                                                                                              |
|----------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Performance    | Initial JavaScript of 150 KB gzipped or less. Route-level code splitting. After the first visit, the first useful screen loads from cache.                                               |
| Data usage     | List responses are small (no images, no long text). A text bundle is about 30 KB or less. Images target 80 KB with a hard cap of 500 KB. Responses are gzipped and support ETag and 304. |
| Reliability    | Downloads are resumable and atomic. Sync is idempotent. Stuck AI jobs are detected and marked failed.                                                                                    |
| Device support | Recent mobile browsers from roughly the last five years on low-end Android phones. Confirm the oldest device you must support.                                                           |
| Accessibility  | 44 px tap targets, WCAG AA contrast, labelled controls, screen reader support, and text that scales.                                                                                     |
| Security       | Gemini and Supabase service keys stay on the server. RLS stays enabled. Ownership checks on delete and retry. SSRF protection when fetching links. Rate limiting. HTTPS only.            |
| Privacy        | No personal data. No third-party trackers.                                                                                                                                               |
| Cost           | Gemini usage is limited by rate limits, a job queue, and duplicate-link detection.                                                                                                       |

## 8. Package specification

| Part              | Details                                                                                                                                                    |
|-------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Metadata          | Title (AI), original title, author or channel, resource link, resource type (text or video), subject, package type (text only or with visual), size, dates |
| Text content      | Overview, key takeaways, summary, key concepts (term and explanation)                                                                                      |
| Quiz              | Exactly 5 questions, 4 options each, one correct answer, an explanation each                                                                               |
| Images            | 0 to 3 contributor images, plus 0 or 1 AI diagram. Stored as WebP or the original allowed type.                                                            |
| Package type rule | **With Visual** if the contributor attached at least one image. Otherwise **Text Only**.                                                                   |
| Learning steps    | Text Only: 3 steps. With Visual: 4 steps (adds Diagram).                                                                                                   |
| Status            | processing, published, failed                                                                                                                              |

## 9. Data summary

Supabase Postgres, schema app, nine tables: learning_packages, package_content, package_images, quiz_questions, quiz_attempts, quiz_answers, downloads, bookmarks, learning_progress. A small migration (original title, key takeaways, image source, published date, unique constraints) is described in section 3.1 of the Implementation Guide. Learner data is keyed by an anonymous device ID. Only the backend reads and writes these tables.

## 10. Screen inventory

| #   | Screen                                          | Requirements             |
|-----|-------------------------------------------------|--------------------------|
| 1   | Onboarding: Learn more, Use less                | FR-1.1, FR-1.2           |
| 2   | Onboarding: Create or Explore Learning Packages | FR-1.1                   |
| 3   | Onboarding: Two Ways to Use BridgeAccess        | FR-1.1                   |
| 4   | Home                                            | FR-2.1 to FR-2.6         |
| 5   | Learn                                           | FR-3.1, FR-3.3           |
| 6   | Continue Learning (See All)                     | FR-3.2                   |
| 7   | Recent Downloads (See All)                      | FR-3.2                   |
| 8   | Explore and Download Packages                   | FR-4.1 to FR-4.6         |
| 9   | Contribute                                      | FR-5.1, FR-5.8           |
| 10  | Create a Package: Text Resource                 | FR-5.2, FR-5.4 to FR-5.7 |
| 11  | Create a Package: Video Resource                | FR-5.3 to FR-5.7         |
| 12  | Package, Learn tab                              | FR-7.1, FR-7.2           |
| 13  | Package, Details tab                            | FR-7.1                   |
| 14  | Viewer: Summary (also Key Concepts and Diagram) | FR-7.3, FR-7.4           |
| 15  | Quiz                                            | FR-7.5                   |
| 16  | Quiz result and Learnt                          | FR-7.6, FR-7.8           |
| 17  | Review Answers                                  | FR-7.7                   |

## 11. Technical approach

- **Frontend:** React and TypeScript PWA with a service worker, IndexedDB (Dexie) for offline storage, and a background sync queue.

- **Backend:** Spring Boot REST API at /api/v1, with an asynchronous job that calls Gemini.

- **Database and storage:** Supabase Postgres (schema app) and a private Storage bucket for images.

- **AI:** Gemini on the server only, returning structured JSON for text and an image for the diagram.

- Full API reference, data flows and code are in the **BridgeAccess: API Documentation and Implementation Guide**.

## 12. Success metrics (proposed)

All metrics can come from the existing tables, so no tracking software is needed.

| Metric           | How measured                                                                                  |
|------------------|-----------------------------------------------------------------------------------------------|
| Activation       | Share of new devices that download at least one package in their first session (downloads)    |
| Completion       | Share of downloaded packages with every step complete (learning_progress)                     |
| Learning outcome | Quiz pass rate and average score (quiz_attempts)                                              |
| Data efficiency  | Median package size downloaded (package_size_bytes)                                           |
| Contribution     | Packages created per active device, and share that reach published (learning_packages.status) |
| Reliability      | Download success rate and share of sync operations that succeed                               |

Agree numeric targets for each before launch.

## 13. Release plan

| Days   | Milestone                                                       |
|--------|-----------------------------------------------------------------|
| 1 to 2 | Backend foundation, package creation and Gemini text generation |
| 3 to 4 | Images, bundle and download, learner data APIs, sync endpoint   |
| 5 to 6 | Frontend foundation, Explore, Download, Contribute and Create   |
| 7      | Learning experience: viewer, quiz, review, bookmarks            |
| 8      | Offline sync, hardening, accessibility                          |
| 9      | Testing on throttled network and a real old phone, deploy       |

**If time runs short, cut in this order:** AI diagrams and contributor images, bookmarks, the Review Answers screen, local profiles. Offline download, quiz and sync must stay.

## 14. Risks and mitigations

| Risk                              | Impact                             | Mitigation                                                                            |
|-----------------------------------|------------------------------------|---------------------------------------------------------------------------------------|
| Only 9 days                       | Scope not finished                 | Ship P0 first, follow the cut order, seed sample packages on day 1                    |
| AI output wrong or malformed      | Misleading lessons, broken quizzes | Strict validation, one retry, AI label, link to the source                            |
| Gemini cost or quota              | Service stops or costs rise        | Rate limits, job queue, duplicate-link detection, monitoring                          |
| Link cannot be read               | Package fails                      | Ask the contributor to paste the content                                              |
| Third-party content and copyright | Legal exposure                     | Own-words summaries, visible attribution, terms line on the form, Report action later |
| Anonymous identity can be misused | One device acting as another       | Unguessable UUIDs, no personal data, plan real auth later                             |
| Shared devices mix progress       | Confusing data                     | Local profiles, Clear my data                                                         |
| Old devices are slow              | Poor experience                    | JavaScript budget, no heavy effects, test on a real old phone                         |
| Free hosting sleeps or pauses     | Failed demo                        | Longer client timeouts, keep-warm ping, check plans before the demo                   |

## 15. Assumptions and open questions

1.  **Pass mark:** assumed 60 percent. The designs show 80 percent as a pass. Confirm.

2.  **Diagram rule:** the Diagram step is tied to With Visual packages. The Water Cycle design is Text Only yet shows a Diagram step, so decide whether every package should get a diagram.

3.  **Sizes and icons in the designs** are inconsistent (for example 4.8 MB versus 850 KB). Real sizes come from the server.

4.  **Audio resources:** remove the onboarding wording or plan them for later.

5.  **Languages:** the UI is assumed English only. Is another language needed, and should AI output match the source language?

6.  **Subjects:** the fixed list is assumed to be Science, Health, Math, Technology, Language, History, Other.

7.  **Target devices:** name the oldest phone and browser that must work.

8.  **Moderation and terms:** who reviews reported content, and what terms do contributors accept?

9.  **Hosting and Gemini budget:** which providers and limits will be used in production?

## 16. Glossary

| Term                    | Meaning                                                                                   |
|-------------------------|-------------------------------------------------------------------------------------------|
| Learning package        | The small offline unit: summary, key concepts, optional diagram and quiz for one resource |
| Resource                | The original article or YouTube video a package is made from                              |
| Text Only / With Visual | Package types without or with images                                                      |
| Learnt                  | A package whose quiz has been passed                                                      |
| Device ID               | An anonymous UUID that identifies one browser profile                                     |
| Bundle                  | The text content of a package, delivered as one small JSON file                           |
| Outbox                  | The local queue of actions waiting to sync                                                |

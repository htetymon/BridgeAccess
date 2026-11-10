-- BridgeAccess migration 1/4: schema, enums, tables
-- Target: Supabase Postgres, schema "app"

create schema if not exists app;
create extension if not exists pg_trgm with schema extensions;

-- ---------------------------------------------------------------- enums
create type app.resource_type  as enum ('text', 'video');
create type app.package_type   as enum ('text_only', 'with_visual');
create type app.package_status as enum ('processing', 'published', 'failed');
create type app.image_source   as enum ('contributor', 'ai_diagram');

-- ------------------------------------------------------ learning_packages
create table app.learning_packages (
  id                  uuid primary key default gen_random_uuid(),
  device_id           uuid not null,                       -- contributor's anonymous device ID
  title               text,                                -- AI-generated, filled when ready
  original_title      text not null,
  author              text not null,                       -- author or channel name
  resource_link       text not null,
  resource_link_key   text generated always as (lower(rtrim(btrim(resource_link), '/'))) stored,
  resource_type       app.resource_type not null,
  subject             text,
  package_type        app.package_type not null default 'text_only',
  status              app.package_status not null default 'processing',
  failure_reason      text,
  source_content      text,                                -- pasted content / transcript (<= 10,000 chars)
  package_size_bytes  integer not null default 0,
  attempt_count       smallint not null default 0,         -- AI job attempts (validate + 1 retry)
  processing_started_at timestamptz,                       -- used to detect stuck jobs
  published_at        timestamptz,
  deleted_at          timestamptz,                         -- soft delete by contributor
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),

  constraint lp_original_title_len check (char_length(btrim(original_title)) between 1 and 300),
  constraint lp_author_len         check (char_length(btrim(author)) between 1 and 200),
  constraint lp_link_http          check (resource_link ~* '^https?://'),
  constraint lp_video_is_youtube   check (
    resource_type <> 'video'
    or resource_link ~* '^https?://([a-z0-9-]+\.)?(youtube\.com|youtu\.be)/'
  ),
  constraint lp_source_len         check (source_content is null or char_length(source_content) <= 10000),
  constraint lp_subject_allowed    check (
    subject is null
    or subject in ('Science','Health','Math','Technology','Language','History','Other')
  ),
  constraint lp_size_nonneg        check (package_size_bytes >= 0),
  constraint lp_published_complete check (
    status <> 'published'
    or (title is not null and subject is not null and published_at is not null)
  ),
  constraint lp_failed_has_reason  check (status <> 'failed' or failure_reason is not null)
);
comment on table app.learning_packages is 'One offline-ready learning package per submitted resource.';

-- Generated, lowercase search text for ILIKE / trigram search (FR-3.2, FR-4.1).
alter table app.learning_packages
  add column search_text text generated always as (
    lower(coalesce(title, '') || ' ' || original_title || ' ' || author)
  ) stored;

-- ------------------------------------------------------- package_content
create table app.package_content (
  package_id     uuid primary key references app.learning_packages(id) on delete cascade,
  overview       text not null,
  key_takeaways  text[] not null,
  summary        text not null,
  key_concepts   jsonb not null,                           -- [{"term": "...", "explanation": "..."}]
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),

  constraint pc_takeaways_count check (cardinality(key_takeaways) between 3 and 5),
  constraint pc_concepts_array  check (jsonb_typeof(key_concepts) = 'array'),
  constraint pc_concepts_count  check (
    jsonb_typeof(key_concepts) <> 'array' or jsonb_array_length(key_concepts) between 4 and 6
  )
);

-- --------------------------------------------------------- package_images
create table app.package_images (
  id            uuid primary key default gen_random_uuid(),
  package_id    uuid not null references app.learning_packages(id) on delete cascade,
  source        app.image_source not null,
  position      smallint not null,                         -- 0 = AI diagram, 1..3 = contributor images
  storage_path  text not null,                             -- path in the private Storage bucket
  mime_type     text not null,
  size_bytes    integer not null,
  alt_text      text,
  created_at    timestamptz not null default now(),

  constraint pi_mime_allowed    check (mime_type in ('image/png','image/jpeg','image/webp')),
  constraint pi_size_cap        check (size_bytes > 0 and size_bytes <= 512000),   -- 500 KB hard cap
  constraint pi_position_by_src check (
    (source = 'ai_diagram'  and position = 0) or
    (source = 'contributor' and position between 1 and 3)
  ),
  constraint pi_unique_position unique (package_id, position)                       -- max 3 + 1 diagram
);

-- -------------------------------------------------------- quiz_questions
create table app.quiz_questions (
  id            uuid primary key default gen_random_uuid(),
  package_id    uuid not null references app.learning_packages(id) on delete cascade,
  position      smallint not null,
  question      text not null,
  options       text[] not null,
  correct_index smallint not null,
  category      text,
  explanation   text not null,
  created_at    timestamptz not null default now(),

  constraint qq_position_range check (position between 1 and 5),
  constraint qq_four_options   check (cardinality(options) = 4),
  constraint qq_correct_range  check (correct_index between 0 and 3),
  constraint qq_unique_pos     unique (package_id, position)
);

-- ---------------------------------------------------------- quiz_attempts
-- id is supplied by the client outbox so a replayed sync cannot create duplicates.
create table app.quiz_attempts (
  id                 uuid primary key default gen_random_uuid(),
  device_id          uuid not null,
  package_id         uuid not null references app.learning_packages(id) on delete cascade,
  score              smallint not null,
  total_questions    smallint not null default 5,
  percentage         numeric(5,2) generated always as (round(score * 100.0 / total_questions, 2)) stored,
  pass_mark_percent  smallint not null default 60,
  passed             boolean not null,
  attempted_at       timestamptz not null default now(),   -- client time (may be offline)
  created_at         timestamptz not null default now(),

  constraint qa_total_pos   check (total_questions > 0),
  constraint qa_score_range check (score between 0 and total_questions),
  constraint qa_pass_range  check (pass_mark_percent between 0 and 100)
);

-- ----------------------------------------------------------- quiz_answers
create table app.quiz_answers (
  id              uuid primary key default gen_random_uuid(),
  attempt_id      uuid not null references app.quiz_attempts(id) on delete cascade,
  question_id     uuid not null references app.quiz_questions(id) on delete cascade,
  selected_index  smallint not null,
  is_correct      boolean not null,
  created_at      timestamptz not null default now(),

  constraint qans_selected_range check (selected_index between 0 and 3),
  constraint qans_one_per_q      unique (attempt_id, question_id)
);

-- -------------------------------------------------------------- downloads
create table app.downloads (
  id             uuid primary key default gen_random_uuid(),
  device_id      uuid not null,
  package_id     uuid not null references app.learning_packages(id) on delete cascade,
  status         text not null default 'completed',
  size_bytes     integer not null default 0,
  downloaded_at  timestamptz not null default now(),
  removed_at     timestamptz,                              -- learner deleted it from the device
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),

  constraint dl_status_allowed check (status in ('started','completed','failed')),
  constraint dl_size_nonneg    check (size_bytes >= 0),
  constraint dl_one_per_device unique (device_id, package_id)
);

-- --------------------------------------------------------------- bookmarks
create table app.bookmarks (
  device_id   uuid not null,
  package_id  uuid not null references app.learning_packages(id) on delete cascade,
  created_at  timestamptz not null default now(),
  primary key (device_id, package_id)
);

-- ------------------------------------------------------- learning_progress
create table app.learning_progress (
  device_id        uuid not null,
  package_id       uuid not null references app.learning_packages(id) on delete cascade,
  completed_steps  text[] not null default '{}',
  last_step        text,                                    -- resume point (FR-3.4)
  last_opened_at   timestamptz not null default now(),
  is_learnt        boolean not null default false,
  learnt_at        timestamptz,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  primary key (device_id, package_id),

  constraint lprog_steps_valid check (completed_steps <@ array['summary','key_concepts','diagram','quiz']),
  constraint lprog_last_step   check (last_step is null or last_step in ('summary','key_concepts','diagram','quiz'))
);
-- BridgeAccess migration 2/4: indexes
-- (primary keys and UNIQUE constraints already create their own indexes)

-- ---- learning_packages
-- Explore feed: published, newest first, keyset pagination (published_at, id). FR-4.1, FR-4.5
create index idx_lp_explore
  on app.learning_packages (published_at desc, id desc)
  where status = 'published' and deleted_at is null;

-- Explore feed filtered by subject.
create index idx_lp_explore_subject
  on app.learning_packages (subject, published_at desc, id desc)
  where status = 'published' and deleted_at is null;

-- Explore feed filtered by resource / package type.
create index idx_lp_explore_types
  on app.learning_packages (resource_type, package_type, published_at desc)
  where status = 'published' and deleted_at is null;

-- Substring search on title / original title / author.
create index idx_lp_search_trgm
  on app.learning_packages using gin (search_text extensions.gin_trgm_ops)
  where status = 'published' and deleted_at is null;

-- My Contributions + daily creation rate limit (5 per device per day). FR-5.1, FR-5.10
create index idx_lp_device_created
  on app.learning_packages (device_id, created_at desc);

-- Stuck AI job detector.
create index idx_lp_processing
  on app.learning_packages (processing_started_at)
  where status = 'processing';

-- Duplicate-link detection (cost control). Only live packages count.
create unique index uq_lp_live_link
  on app.learning_packages (resource_link_key)
  where status in ('processing','published') and deleted_at is null;

-- ---- quiz_attempts
create index idx_qa_device_package
  on app.quiz_attempts (device_id, package_id, attempted_at desc);
create index idx_qa_package on app.quiz_attempts (package_id);

-- ---- quiz_answers (attempt_id is covered by the unique constraint)
create index idx_qans_question on app.quiz_answers (question_id);

-- ---- downloads
create index idx_dl_device_recent
  on app.downloads (device_id, downloaded_at desc)
  where removed_at is null and status = 'completed';
create index idx_dl_package on app.downloads (package_id);

-- ---- bookmarks
create index idx_bm_device_recent on app.bookmarks (device_id, created_at desc);
create index idx_bm_package on app.bookmarks (package_id);

-- ---- learning_progress
create index idx_lprog_device_opened on app.learning_progress (device_id, last_opened_at desc);
create index idx_lprog_device_learnt
  on app.learning_progress (device_id) where is_learnt;
create index idx_lprog_package on app.learning_progress (package_id);
-- BridgeAccess migration 3/4: integrity functions and triggers

-- 1. Keep updated_at current -------------------------------------------------
create or replace function app.set_updated_at()
returns trigger language plpgsql set search_path = '' as $$
begin
  new.updated_at := now();
  return new;
end $$;

create trigger trg_lp_updated  before update on app.learning_packages
  for each row execute function app.set_updated_at();
create trigger trg_pc_updated  before update on app.package_content
  for each row execute function app.set_updated_at();
create trigger trg_dl_updated  before update on app.downloads
  for each row execute function app.set_updated_at();

-- 2. A package can only be published when it is complete ---------------------
create or replace function app.enforce_publishable()
returns trigger language plpgsql set search_path = '' as $$
begin
  if not exists (select 1 from app.package_content where package_id = new.id) then
    raise exception 'package % cannot be published: no content', new.id using errcode = 'check_violation';
  end if;
  if (select count(*) from app.quiz_questions where package_id = new.id) <> 5 then
    raise exception 'package % cannot be published: quiz must have exactly 5 questions', new.id
      using errcode = 'check_violation';
  end if;
  new.published_at := coalesce(new.published_at, now());
  new.failure_reason := null;
  return new;
end $$;

create trigger trg_lp_publish_guard
  before update of status on app.learning_packages
  for each row
  when (new.status = 'published' and old.status is distinct from 'published')
  execute function app.enforce_publishable();

-- 3. package_type follows the images (With Visual = >= 1 contributor image) ---
create or replace function app.sync_package_type()
returns trigger language plpgsql set search_path = '' as $$
declare pid uuid := coalesce(new.package_id, old.package_id);
begin
  update app.learning_packages
     set package_type = case when exists (
           select 1 from app.package_images
            where package_id = pid and source = 'contributor')
         then 'with_visual'::app.package_type else 'text_only'::app.package_type end
   where id = pid;
  return null;
end $$;

create trigger trg_pi_sync_type
  after insert or delete on app.package_images
  for each row execute function app.sync_package_type();

-- 4. Idempotent, monotonic progress merge (FR-8.3) ---------------------------
-- Replayed or out-of-order sync can never un-complete a step, drop Learnt,
-- or rewind the resume point.
create or replace function app.merge_learning_progress()
returns trigger language plpgsql set search_path = '' as $$
begin
  new.completed_steps := coalesce((
    select array_agg(distinct s order by s)
      from unnest(old.completed_steps || new.completed_steps) as s
  ), '{}');
  new.is_learnt := old.is_learnt or new.is_learnt;
  new.learnt_at := coalesce(old.learnt_at, new.learnt_at);
  if new.last_opened_at < old.last_opened_at then       -- stale event: keep newer position
    new.last_opened_at := old.last_opened_at;
    new.last_step      := old.last_step;
  end if;
  new.updated_at := now();
  return new;
end $$;

create trigger trg_lprog_merge
  before update on app.learning_progress
  for each row execute function app.merge_learning_progress();

-- 5. Passing a quiz grants Learnt (FR-7.6) -----------------------------------
create or replace function app.grant_learnt_on_pass()
returns trigger language plpgsql set search_path = '' as $$
begin
  if new.passed then
    insert into app.learning_progress (device_id, package_id, is_learnt, learnt_at, last_opened_at)
    values (new.device_id, new.package_id, true, new.attempted_at, new.attempted_at)
    on conflict (device_id, package_id) do update
      set is_learnt = true,
          learnt_at = coalesce(app.learning_progress.learnt_at, excluded.learnt_at);
  end if;
  return null;
end $$;

create trigger trg_qa_grant_learnt
  after insert on app.quiz_attempts
  for each row execute function app.grant_learnt_on_pass();
-- BridgeAccess migration 4/4: privileges and Row Level Security
-- Model: the Spring Boot backend uses the service role (bypasses RLS) and enforces
-- device ownership. Browser roles (anon / authenticated) get read-only access to
-- the PUBLISHED catalogue and nothing else.

-- Enable RLS on all nine tables --------------------------------------------
alter table app.learning_packages enable row level security;
alter table app.package_content   enable row level security;
alter table app.package_images    enable row level security;
alter table app.quiz_questions    enable row level security;
alter table app.quiz_attempts     enable row level security;
alter table app.quiz_answers      enable row level security;
alter table app.downloads         enable row level security;
alter table app.bookmarks         enable row level security;
alter table app.learning_progress enable row level security;

-- Privileges: start from nothing for browser roles ---------------------------
revoke all on all tables    in schema app from anon, authenticated;
revoke all on all sequences in schema app from anon, authenticated;
revoke all on all functions in schema app from anon, authenticated, public;

grant usage on schema app to anon, authenticated, service_role;
grant all on all tables in schema app to service_role;
grant select on app.learning_packages, app.package_content,
                app.package_images,   app.quiz_questions
  to anon, authenticated;

-- Public catalogue: published, not deleted -----------------------------------
create policy catalogue_read on app.learning_packages
  for select to anon, authenticated
  using (status = 'published' and deleted_at is null);

create policy content_read on app.package_content
  for select to anon, authenticated
  using (exists (select 1 from app.learning_packages p
                  where p.id = package_id and p.status = 'published' and p.deleted_at is null));

create policy images_read on app.package_images
  for select to anon, authenticated
  using (exists (select 1 from app.learning_packages p
                  where p.id = package_id and p.status = 'published' and p.deleted_at is null));

create policy questions_read on app.quiz_questions
  for select to anon, authenticated
  using (exists (select 1 from app.learning_packages p
                  where p.id = package_id and p.status = 'published' and p.deleted_at is null));

-- Learner data (keyed by device ID): explicit deny for browser roles ---------
create policy deny_all on app.quiz_attempts     for all to anon, authenticated using (false) with check (false);
create policy deny_all on app.quiz_answers      for all to anon, authenticated using (false) with check (false);
create policy deny_all on app.downloads         for all to anon, authenticated using (false) with check (false);
create policy deny_all on app.bookmarks         for all to anon, authenticated using (false) with check (false);
create policy deny_all on app.learning_progress for all to anon, authenticated using (false) with check (false);

-- Writes to catalogue tables by browser roles: no policy = denied by default.

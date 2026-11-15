-- BridgeAccess success metrics (PRD section 12), from the existing tables.
--
-- Each query stands alone: run one at a time (Supabase SQL editor or psql). The reporting
-- period is set in its `params` line, by default the last 30 days; edit it there.
-- Timestamps used are the server's (created_at), not device clocks.
--
-- Each block starts with "-- metric: <name>"; MetricsQueriesIntegrationTest runs every block
-- against the real schema with known data and checks the numbers.
--
-- Not measurable from the tables (so not here):
-- - Sync reliability: the outcome of each sync is not stored. It is logged instead: one
--   "event=sync" line per request with sync.ops / sync.ok / sync.rejected (SyncService).
-- - Download success rate: the app records only completed downloads (the download flow does
--   not report started or failed), so there is nothing to divide by yet.
-- - Activation's denominator only includes devices that wrote something (a download,
--   bookmark, progress, quiz or contribution). A device that only browsed Explore leaves no
--   row, so activation here is "of devices that did anything, the share that downloaded in
--   their first day". Counting every new device would need a first-seen record per device.


-- metric: activation
-- Share of new devices that downloaded at least one package within their first session,
-- taken as the first 24 hours after the device was first seen.
with params as (
  select now() - interval '30 days' as period_start, now() as period_end, interval '24 hours' as first_session
),
activity as (
  select device_id, created_at from app.downloads
  union all select device_id, created_at from app.bookmarks
  union all select device_id, created_at from app.learning_progress
  union all select device_id, created_at from app.quiz_attempts
  union all select device_id, created_at from app.learning_packages
),
first_seen as (
  select device_id, min(created_at) as first_seen from activity group by device_id
),
new_devices as (
  select f.* from first_seen f, params p where f.first_seen >= p.period_start and f.first_seen < p.period_end
)
select count(*) as new_devices,
       count(*) filter (where exists (
         select 1 from app.downloads d, params p
          where d.device_id = n.device_id and d.status = 'completed'
            and d.created_at < n.first_seen + p.first_session)) as activated_devices,
       round(count(*) filter (where exists (
         select 1 from app.downloads d, params p
          where d.device_id = n.device_id and d.status = 'completed'
            and d.created_at < n.first_seen + p.first_session))::numeric / nullif(count(*), 0), 3) as activation_rate
  from new_devices n;


-- metric: completion
-- Share of downloaded packages (one per device and package, downloaded in the period) whose
-- every learning step is complete: summary, key concepts and quiz, plus the diagram for
-- packages with visuals.
with params as (
  select now() - interval '30 days' as period_start, now() as period_end
),
downloaded as (
  select d.device_id, d.package_id, lp.package_type
    from app.downloads d
    join app.learning_packages lp on lp.id = d.package_id, params p
   where d.status = 'completed' and d.created_at >= p.period_start and d.created_at < p.period_end
),
checked as (
  select dl.*,
         coalesce(pr.completed_steps @> case dl.package_type
                    when 'with_visual' then array['summary','key_concepts','diagram','quiz']
                    else array['summary','key_concepts','quiz'] end, false) as complete
    from downloaded dl
    left join app.learning_progress pr on pr.device_id = dl.device_id and pr.package_id = dl.package_id
)
select count(*) as downloaded_packages,
       count(*) filter (where complete) as completed_packages,
       round(count(*) filter (where complete)::numeric / nullif(count(*), 0), 3) as completion_rate
  from checked;


-- metric: learning_outcome
-- Quiz pass rate and average score over attempts in the period, plus the first-attempt pass
-- rate (each device's first attempt at each package), which retakes cannot inflate.
with params as (
  select now() - interval '30 days' as period_start, now() as period_end
),
attempts as (
  select qa.*,
         row_number() over (partition by qa.device_id, qa.package_id order by qa.attempted_at, qa.id) as nth
    from app.quiz_attempts qa, params p
   where qa.created_at >= p.period_start and qa.created_at < p.period_end
)
select count(*) as attempts,
       round(avg(passed::int), 3) as pass_rate,
       round(avg(percentage), 1) as average_score_percent,
       count(*) filter (where nth = 1) as first_attempts,
       round(avg(passed::int) filter (where nth = 1), 3) as first_attempt_pass_rate
  from attempts;


-- metric: data_efficiency
-- Median (and 90th percentile) size of what learners downloaded in the period, as recorded by
-- the app, and the median size of the packages published in the period.
with params as (
  select now() - interval '30 days' as period_start, now() as period_end
)
select (select percentile_cont(0.5) within group (order by d.size_bytes)
          from app.downloads d, params p
         where d.status = 'completed' and d.created_at >= p.period_start and d.created_at < p.period_end
       )::bigint as median_downloaded_bytes,
       (select percentile_cont(0.9) within group (order by d.size_bytes)
          from app.downloads d, params p
         where d.status = 'completed' and d.created_at >= p.period_start and d.created_at < p.period_end
       )::bigint as p90_downloaded_bytes,
       (select percentile_cont(0.5) within group (order by lp.package_size_bytes)
          from app.learning_packages lp, params p
         where lp.status = 'published' and lp.published_at >= p.period_start and lp.published_at < p.period_end
       )::bigint as median_published_package_bytes;


-- metric: contribution
-- Packages created per contributing device, and the share of finished packages (published or
-- failed, not still processing) that reached published.
with params as (
  select now() - interval '30 days' as period_start, now() as period_end
),
created as (
  select lp.* from app.learning_packages lp, params p
   where lp.created_at >= p.period_start and lp.created_at < p.period_end
)
select count(*) as packages_created,
       count(distinct device_id) as contributing_devices,
       round(count(*)::numeric / nullif(count(distinct device_id), 0), 2) as packages_per_contributor,
       round(count(*) filter (where status = 'published')::numeric
             / nullif(count(*) filter (where status in ('published', 'failed')), 0), 3) as publish_rate
  from created;

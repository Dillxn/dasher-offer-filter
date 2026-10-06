-- Hardening of the accountless feedback service (see ../../README.md). Apply it before deploying the improved
-- function. The function deployed today keeps working meanwhile: its submit function is left as it was, and the
-- improved function calls the new one (submit_offer_filter_feedback_v2).
--
-- * Rate-limit buckets are keyed with a random salt that changes every UTC day (no longer the service-role key);
--   the salt is deleted once its day is over, and rate rows are kept at most an hour.
-- * Only a submission's first part counts toward the per-network limit (10 submissions per 10 minutes).
-- * A submission (one report token) has at most 4 parts and 500,000 bytes of text, the same kind and part count on
--   every part, its first part stored before any other, and one reference: its first part's id.
-- * A global daily cap: 2,000 submissions and 50,000,000 bytes of text per UTC day.
-- * Diagnostics are stored exactly as sent (the app frames each part; blank diagnostics are refused).
-- Re-running this file changes nothing further.

-- ---- A random salt per UTC day ----

create table if not exists public.offer_filter_feedback_salt (
  day date primary key,
  salt text not null check (salt ~ '^[0-9a-f]{64}$')
);
alter table public.offer_filter_feedback_salt enable row level security;
revoke all on table public.offer_filter_feedback_salt from anon, authenticated;
grant select, insert, delete on table public.offer_filter_feedback_salt to service_role;

create or replace function public.offer_filter_feedback_daily_salt()
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_day date := (now() at time zone 'utc')::date;
  v_salt text;
begin
  select salt into v_salt from public.offer_filter_feedback_salt where day = v_day;
  if v_salt is not null then
    return v_salt;
  end if;
  -- A day's salt goes once the day is over: no bucket can be recomputed from an address afterwards.
  delete from public.offer_filter_feedback_salt where day < v_day;
  -- 244 random bits (gen_random_uuid uses the server's strong random source).
  insert into public.offer_filter_feedback_salt(day, salt)
  values (v_day, replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', ''))
  on conflict (day) do nothing;
  select salt into v_salt from public.offer_filter_feedback_salt where day = v_day;
  return v_salt;
end;
$$;

revoke all on function public.offer_filter_feedback_daily_salt() from public, anon, authenticated;
grant execute on function public.offer_filter_feedback_daily_salt() to service_role;

-- ---- The global daily cap ----

create table if not exists public.offer_filter_feedback_daily (
  day date primary key,
  submissions integer not null default 0 check (submissions >= 0),
  bytes bigint not null default 0 check (bytes >= 0)
);
alter table public.offer_filter_feedback_daily enable row level security;
revoke all on table public.offer_filter_feedback_daily from anon, authenticated;
grant select, insert, update, delete on table public.offer_filter_feedback_daily to service_role;

-- ---- Rate rows: at most an hour ----

create index if not exists offer_filter_feedback_rate_limit_window_idx
  on public.offer_filter_feedback_rate_limit(window_start);
-- Rows keyed with the service-role key go now.
delete from public.offer_filter_feedback_rate_limit where window_start < now() - interval '1 hour';

-- ---- Storing a part ----

create or replace function public.submit_offer_filter_feedback_v2(
  p_rate_bucket text,
  p_kind text,
  p_category text,
  p_message text,
  p_diagnostics text,
  p_app_version text,
  p_app_version_code integer,
  p_source text,
  p_diagnostics_consented boolean,
  p_report_token text,
  p_part_index integer,
  p_part_count integer
)
returns table (outcome text, reference uuid)
language plpgsql
security definer
set search_path = ''
as $$
declare
  c_window_limit constant integer := 10;
  c_max_parts constant integer := 4;
  -- The app's parts are at most 120,000 bytes each: four fit.
  c_max_submission_bytes constant bigint := 500000;
  c_daily_submissions constant integer := 2000;
  c_daily_bytes constant bigint := 50000000;
  v_day date := (now() at time zone 'utc')::date;
  v_window timestamptz := date_trunc('hour', now())
    + floor(extract(minute from now()) / 10) * interval '10 minutes';
  v_bytes bigint := coalesce(octet_length(p_diagnostics), 0) + coalesce(octet_length(p_message), 0);
  -- A submission's first part (or a submission of one part without a token) is a new submission.
  v_first boolean := p_report_token is null or p_part_index = 0;
  v_id uuid;
  v_first_id uuid;
  v_token_bytes bigint;
  v_count integer;
  v_daily_submissions integer;
  v_daily_bytes bigint;
begin
  -- Rate rows live an hour at most, whatever this request turns out to be.
  delete from public.offer_filter_feedback_rate_limit where window_start < now() - interval '1 hour';

  -- The function in front of this one checks all of this too.
  if p_rate_bucket is null or p_rate_bucket !~ '^[0-9a-f]{64}$'
     or p_kind is null or p_kind not in ('feedback', 'problem', 'diagnostics')
     or p_source is null or p_source not in ('android', 'web')
     or (p_source = 'web' and p_kind <> 'feedback')
     or (p_category is not null
         and p_category not in ('general', 'bug', 'feature', 'ux', 'privacy', 'update', 'other'))
     or (p_message is not null and char_length(btrim(p_message)) > 4000)
     or (p_diagnostics is not null
         and (char_length(p_diagnostics) > 60000 or btrim(p_diagnostics, E' \t\r\n') = ''))
     or (p_diagnostics is not null and not coalesce(p_diagnostics_consented, false))
     or (coalesce(char_length(btrim(p_message)), 0) = 0 and p_diagnostics is null)
     or (p_kind = 'feedback' and coalesce(char_length(btrim(p_message)), 0) = 0)
     or (p_kind = 'diagnostics' and p_diagnostics is null)
     or (p_app_version is not null and char_length(p_app_version) > 32)
     or (p_app_version_code is not null and (p_app_version_code < 1 or p_app_version_code > 10000000))
     or (p_report_token is not null and p_report_token !~ '^[a-f0-9]{32}$')
     or p_part_index is null or p_part_count is null
     or p_part_index < 0 or p_part_count < 1 or p_part_count > c_max_parts or p_part_index >= p_part_count
     or (p_report_token is null and p_part_count <> 1) then
    return query select 'invalid'::text, null::uuid;
    return;
  end if;

  if p_report_token is not null then
    -- One part of a submission at a time.
    perform pg_advisory_xact_lock(hashtextextended('offer-filter-feedback:' || p_report_token, 0));
    select f.id into v_first_id
    from public.offer_filter_feedback f
    where f.report_token = p_report_token and f.part_index = 0;
    -- A part stored before (its reply was lost) is stored once: the same reference again.
    select f.id into v_id
    from public.offer_filter_feedback f
    where f.report_token = p_report_token and f.part_index = p_part_index;
    if v_id is not null then
      return query select 'duplicate'::text, coalesce(v_first_id, v_id);
      return;
    end if;
    if exists (
      select 1 from public.offer_filter_feedback f
      where f.report_token = p_report_token and (f.part_count <> p_part_count or f.kind <> p_kind)
    ) then
      return query select 'part_mismatch'::text, null::uuid;
      return;
    end if;
    if p_part_index > 0 and v_first_id is null then
      return query select 'part_out_of_order'::text, null::uuid;
      return;
    end if;
    select coalesce(sum(coalesce(octet_length(f.diagnostics), 0) + coalesce(octet_length(f.message), 0)), 0)
    into v_token_bytes
    from public.offer_filter_feedback f
    where f.report_token = p_report_token;
    if v_token_bytes + v_bytes > c_max_submission_bytes then
      return query select 'too_large'::text, null::uuid;
      return;
    end if;
  end if;

  -- Everyone's submissions today, together.
  insert into public.offer_filter_feedback_daily(day) values (v_day) on conflict (day) do nothing;
  select d.submissions, d.bytes into v_daily_submissions, v_daily_bytes
  from public.offer_filter_feedback_daily d
  where d.day = v_day
  for update;
  if (v_first and v_daily_submissions >= c_daily_submissions) or v_daily_bytes + v_bytes > c_daily_bytes then
    return query select 'daily_cap'::text, null::uuid;
    return;
  end if;

  -- Only a new submission counts toward its network's limit; the parts after its first do not.
  if v_first then
    insert into public.offer_filter_feedback_rate_limit as r (bucket, window_start, submission_count)
    values (p_rate_bucket, v_window, 1)
    on conflict (bucket, window_start)
    do update set submission_count = r.submission_count + 1
      where r.submission_count < c_window_limit
    returning r.submission_count into v_count;
    if v_count is null then
      return query select 'rate_limited'::text, null::uuid;
      return;
    end if;
  end if;

  insert into public.offer_filter_feedback(
    kind, category, message, diagnostics, app_version, app_version_code, source, diagnostics_consented,
    report_token, part_index, part_count
  ) values (
    p_kind, p_category, nullif(btrim(p_message), ''), p_diagnostics,
    nullif(btrim(p_app_version), ''), p_app_version_code, p_source, coalesce(p_diagnostics_consented, false),
    p_report_token, p_part_index, p_part_count
  )
  on conflict (report_token, part_index) where report_token is not null do nothing
  returning id into v_id;
  if v_id is null then
    select f.id into v_id
    from public.offer_filter_feedback f
    where f.report_token = p_report_token and f.part_index = p_part_index;
    return query select 'duplicate'::text, coalesce(v_first_id, v_id);
    return;
  end if;

  update public.offer_filter_feedback_daily d
  set submissions = d.submissions + case when v_first then 1 else 0 end,
      bytes = d.bytes + v_bytes
  where d.day = v_day;

  return query select 'stored'::text, coalesce(v_first_id, v_id);
end;
$$;

revoke all on function public.submit_offer_filter_feedback_v2(
  text,text,text,text,text,text,integer,text,boolean,text,integer,integer
) from public, anon, authenticated;
grant execute on function public.submit_offer_filter_feedback_v2(
  text,text,text,text,text,text,integer,text,boolean,text,integer,integer
) to service_role;

-- ---- Reading a submission whole (service role only) ----

-- Each submission once, its parts' diagnostics joined in order with the app's frame lines removed.
create or replace view public.offer_filter_feedback_submissions
with (security_invoker = true) as
select
  coalesce(f.report_token, f.id::text) as submission,
  min(f.received_at) as received_at,
  min(f.kind) as kind,
  min(f.category) as category,
  max(f.message) filter (where f.part_index = 0) as message,
  max(f.app_version) as app_version,
  max(f.source) as source,
  count(*) as parts_received,
  max(f.part_count) as part_count,
  string_agg(
    case
      when f.source = 'android' and f.diagnostics ~ '^\[[^\n]*\]\n' then
        regexp_replace(regexp_replace(f.diagnostics, '^\[[^\n]*\]\n', ''), '\n\[end part [0-9]+/[0-9]+\]$', '')
      else f.diagnostics
    end,
    '' order by f.part_index
  ) as diagnostics,
  max(f.expires_at) as expires_at
from public.offer_filter_feedback f
group by coalesce(f.report_token, f.id::text);

revoke all on table public.offer_filter_feedback_submissions from anon, authenticated;
grant select on table public.offer_filter_feedback_submissions to service_role;

-- ---- Short-lived rows, even with no traffic ----

create extension if not exists pg_cron with schema extensions;
select cron.schedule(
  'offer-filter-feedback-short-lived',
  '*/10 * * * *',
  $$
  delete from public.offer_filter_feedback_rate_limit where window_start < now() - interval '1 hour';
  delete from public.offer_filter_feedback_salt where day < (now() at time zone 'utc')::date;
  delete from public.offer_filter_feedback_daily where day < (now() at time zone 'utc')::date - 7;
  $$
);

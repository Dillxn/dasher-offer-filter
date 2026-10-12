-- Counts of the accountless feedback service (see ../../README.md): how many installs of the app check in each day,
-- and how many times the install page's Download button is pressed. Needs 20261006120000_feedback_hardening.sql
-- applied first: the function sends the same network hash as for feedback, keyed with its daily salt
-- (offer_filter_feedback_daily_salt). Apply this before deploying the function that counts. The function deployed
-- today keeps working meanwhile: it answers 400 to a count and touches nothing here.
--
-- * Daily totals, the only thing kept beyond a day or two: per UTC day, event, app version and whether it was an
--   install's first count. No address, user agent, token, network or identifier goes with a total. Totals are
--   deleted after 400 days.
-- * A count's random token is kept at most two days, only so that a count retried is counted once. It is kept with
--   its day and nothing else.
-- * Per network (the daily-keyed hash the feedback rate limit uses) and UTC day: whether that network's download was
--   counted (one a day) and how many daily counts came from it (at most 50 a day: a carrier's NAT puts many phones
--   behind one address). Deleted once the day is over, as the day's salt is.
-- * A global cap: 20,000 counts per UTC day.
-- Re-running this file changes nothing further.

do $$
begin
  if to_regprocedure('public.offer_filter_feedback_daily_salt()') is null then
    raise exception 'apply 20261006120000_feedback_hardening.sql first';
  end if;
end
$$;

-- ---- Daily totals: what is read ----

create table if not exists public.offer_filter_counts (
  day date not null,
  event text not null check (event in ('daily', 'download')),
  -- The app's version name; '' for a download.
  app_version text not null default '' check (char_length(app_version) <= 32),
  -- An install's first count accepted by the service: a new install counted.
  first boolean not null default false,
  total integer not null default 0 check (total >= 0),
  primary key (day, event, app_version, first)
);
alter table public.offer_filter_counts enable row level security;
revoke all on table public.offer_filter_counts from anon, authenticated;
grant select, insert, update, delete on table public.offer_filter_counts to service_role;

-- ---- Tokens seen: a retried count is counted once (at most two days) ----

create table if not exists public.offer_filter_count_seen (
  token text primary key check (token ~ '^[a-f0-9]{32}$'),
  day date not null
);
create index if not exists offer_filter_count_seen_day_idx on public.offer_filter_count_seen(day);
alter table public.offer_filter_count_seen enable row level security;
revoke all on table public.offer_filter_count_seen from anon, authenticated;
grant select, insert, delete on table public.offer_filter_count_seen to service_role;

-- ---- Per network and day: one download, at most 50 daily counts (until the day is over) ----

create table if not exists public.offer_filter_count_network (
  bucket text not null check (bucket ~ '^[0-9a-f]{64}$'),
  day date not null,
  event text not null check (event in ('daily', 'download')),
  n integer not null check (n >= 1),
  primary key (bucket, day, event)
);
create index if not exists offer_filter_count_network_day_idx on public.offer_filter_count_network(day);
alter table public.offer_filter_count_network enable row level security;
revoke all on table public.offer_filter_count_network from anon, authenticated;
grant select, insert, update, delete on table public.offer_filter_count_network to service_role;

-- ---- The global daily cap: today's number of counts, no network or content (kept 7 days) ----

create table if not exists public.offer_filter_count_daily (
  day date primary key,
  counts integer not null default 0 check (counts >= 0)
);
alter table public.offer_filter_count_daily enable row level security;
revoke all on table public.offer_filter_count_daily from anon, authenticated;
grant select, insert, update, delete on table public.offer_filter_count_daily to service_role;

-- ---- Counting once ----

-- Outcomes: 'counted'; 'duplicate' (the token was counted before, or this network's download today was); and
-- refusals that leave nothing behind: 'network_limit' (this network's 50 daily counts today), 'daily_cap' (everyone's
-- 20,000 today) and 'invalid'. A refused count's token is not kept, so it can be counted when it is tried again.
create or replace function public.offer_filter_count_v1(
  p_rate_bucket text,
  p_event text,
  p_app_version text,
  p_first boolean,
  p_token text
)
returns table (outcome text)
language plpgsql
security definer
set search_path = ''
as $$
declare
  c_network_daily constant integer := 50;
  c_daily_counts constant integer := 20000;
  v_day date := (now() at time zone 'utc')::date;
  v_app_version text := coalesce(p_app_version, '');
  v_first boolean := coalesce(p_first, false);
  v_n integer;
  v_counts integer;
begin
  -- Tokens live two days at most, and network rows only their own day, whatever this request turns out to be.
  delete from public.offer_filter_count_seen where day < v_day - 1;
  delete from public.offer_filter_count_network where day < v_day;

  -- The function in front of this one checks all of this too.
  if p_rate_bucket is null or p_rate_bucket !~ '^[0-9a-f]{64}$'
     or p_event is null or p_event not in ('daily', 'download')
     or (p_token is not null and p_token !~ '^[a-f0-9]{32}$')
     or (p_event = 'daily' and p_token is null)
     or (p_event = 'download' and (v_app_version <> '' or v_first))
     or (v_app_version <> '' and v_app_version !~ '^[0-9A-Za-z][0-9A-Za-z._+-]{0,31}$') then
    return query select 'invalid'::text;
    return;
  end if;

  if p_token is not null then
    -- One request of a token at a time: the second of two at once waits, then finds the first's token.
    perform pg_advisory_xact_lock(hashtextextended('offer-filter-count:' || p_token, 0));
    if exists (select 1 from public.offer_filter_count_seen s where s.token = p_token) then
      return query select 'duplicate'::text;
      return;
    end if;
  end if;

  -- Everyone's counts today, together (the row is held until this count is done).
  insert into public.offer_filter_count_daily(day) values (v_day) on conflict (day) do nothing;
  select d.counts into v_counts
  from public.offer_filter_count_daily d
  where d.day = v_day
  for update;
  if v_counts >= c_daily_counts then
    return query select 'daily_cap'::text;
    return;
  end if;

  -- This network today: one download; daily counts up to the limit. A refusal writes nothing.
  if p_event = 'download' then
    insert into public.offer_filter_count_network as r (bucket, day, event, n)
    values (p_rate_bucket, v_day, 'download', 1)
    on conflict (bucket, day, event) do nothing
    returning r.n into v_n;
    if v_n is null then
      return query select 'duplicate'::text;
      return;
    end if;
  else
    insert into public.offer_filter_count_network as r (bucket, day, event, n)
    values (p_rate_bucket, v_day, 'daily', 1)
    on conflict (bucket, day, event)
    do update set n = r.n + 1
      where r.n < c_network_daily
    returning r.n into v_n;
    if v_n is null then
      return query select 'network_limit'::text;
      return;
    end if;
  end if;

  if p_token is not null then
    insert into public.offer_filter_count_seen(token, day) values (p_token, v_day)
    on conflict (token) do nothing;
    if not found then
      -- Held by the lock above, so never expected: undo the network's count with everything else, and let it retry.
      raise exception 'count token seen meanwhile';
    end if;
  end if;

  update public.offer_filter_count_daily d set counts = d.counts + 1 where d.day = v_day;

  insert into public.offer_filter_counts as c (day, event, app_version, first, total)
  values (v_day, p_event, v_app_version, v_first, 1)
  on conflict (day, event, app_version, first)
  do update set total = c.total + 1;

  return query select 'counted'::text;
end;
$$;

revoke all on function public.offer_filter_count_v1(text,text,text,boolean,text) from public, anon, authenticated;
grant execute on function public.offer_filter_count_v1(text,text,text,boolean,text) to service_role;

-- ---- Reading the counts (service role only) ----

-- Per UTC day and event: every count, and those that were an install's first (new installs counted; always 0 for a
-- download).
create or replace view public.offer_filter_counts_daily
with (security_invoker = true) as
select
  c.day,
  c.event,
  sum(c.total)::bigint as total,
  coalesce(sum(c.total) filter (where c.first), 0)::bigint as first_total
from public.offer_filter_counts c
group by c.day, c.event;

revoke all on table public.offer_filter_counts_daily from anon, authenticated;
grant select on table public.offer_filter_counts_daily to service_role;

-- Per UTC day and app version: the installs that checked in, and those counted for the first time.
create or replace view public.offer_filter_counts_by_version
with (security_invoker = true) as
select
  c.day,
  c.app_version,
  sum(c.total)::bigint as total,
  coalesce(sum(c.total) filter (where c.first), 0)::bigint as first_total
from public.offer_filter_counts c
where c.event = 'daily'
group by c.day, c.app_version;

revoke all on table public.offer_filter_counts_by_version from anon, authenticated;
grant select on table public.offer_filter_counts_by_version to service_role;

-- ---- Retention, even with no traffic ----

create extension if not exists pg_cron with schema extensions;
select cron.schedule(
  'offer-filter-counts-retention',
  '7 0 * * *',
  $$
  delete from public.offer_filter_counts where day < (now() at time zone 'utc')::date - 400;
  delete from public.offer_filter_count_seen where day < (now() at time zone 'utc')::date - 1;
  delete from public.offer_filter_count_network where day < (now() at time zone 'utc')::date;
  delete from public.offer_filter_count_daily where day < (now() at time zone 'utc')::date - 7;
  $$
);

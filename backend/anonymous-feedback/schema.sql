-- Production schema of the accountless Offer Filter feedback service as first deployed (reference only; do not
-- re-run). Supabase project: offer-filter-feedback (zlnfvqyyjsltmkmmpgzp). Changes since are migrations in
-- supabase/migrations/, applied in order (README.md).
create table public.offer_filter_feedback (
  id uuid primary key default gen_random_uuid(),
  received_at timestamptz not null default now(),
  kind text not null check (kind in ('feedback','problem','diagnostics')),
  category text check (category is null or (char_length(category) between 1 and 64)),
  message text check (message is null or char_length(message) <= 4000),
  diagnostics text check (diagnostics is null or char_length(diagnostics) <= 60000),
  app_version text check (app_version is null or char_length(app_version) <= 32),
  app_version_code integer check (app_version_code is null or app_version_code between 1 and 10000000),
  source text not null check (source in ('android','web')),
  diagnostics_consented boolean not null default false,
  expires_at timestamptz not null default (now() + interval '90 days'),
  report_token text check (report_token is null or report_token ~ '^[a-f0-9]{32}$'),
  part_index integer not null default 0,
  part_count integer not null default 1,
  constraint offer_filter_feedback_has_content
    check (coalesce(char_length(btrim(message)),0) > 0 or coalesce(char_length(diagnostics),0) > 0),
  constraint offer_filter_feedback_diagnostics_consent
    check (diagnostics is null or diagnostics_consented),
  constraint offer_filter_feedback_part_shape
    check (part_index >= 0 and part_count between 1 and 32 and part_index < part_count)
);

create unique index offer_filter_feedback_report_part_uidx
  on public.offer_filter_feedback(report_token, part_index)
  where report_token is not null;
create index offer_filter_feedback_expires_at_idx
  on public.offer_filter_feedback(expires_at);

alter table public.offer_filter_feedback enable row level security;
revoke all on table public.offer_filter_feedback from anon, authenticated;
grant select, insert, update, delete on table public.offer_filter_feedback to service_role;

create table public.offer_filter_feedback_rate_limit (
  bucket text not null,
  window_start timestamptz not null,
  submission_count integer not null check (submission_count >= 1),
  primary key (bucket, window_start)
);
alter table public.offer_filter_feedback_rate_limit enable row level security;
revoke all on table public.offer_filter_feedback_rate_limit from anon, authenticated;
grant select, insert, update, delete on table public.offer_filter_feedback_rate_limit to service_role;

create or replace function public.submit_offer_filter_feedback(
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
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_window timestamptz;
  v_count integer;
  v_id uuid;
begin
  if p_rate_bucket is null or char_length(p_rate_bucket) < 16 or char_length(p_rate_bucket) > 128 then
    raise exception 'invalid rate bucket';
  end if;
  if p_kind not in ('feedback', 'problem', 'diagnostics') then raise exception 'invalid kind'; end if;
  if p_source not in ('android', 'web') then raise exception 'invalid source'; end if;
  if p_category is not null and (char_length(p_category) < 1 or char_length(p_category) > 64) then
    raise exception 'invalid category';
  end if;
  if p_message is not null and char_length(p_message) > 4000 then raise exception 'message too long'; end if;
  if p_diagnostics is not null and char_length(p_diagnostics) > 60000 then raise exception 'diagnostics too long'; end if;
  if p_diagnostics is not null and not coalesce(p_diagnostics_consented, false) then
    raise exception 'diagnostics require consent';
  end if;
  if coalesce(char_length(btrim(p_message)), 0) = 0 and coalesce(char_length(p_diagnostics), 0) = 0 then
    raise exception 'empty feedback';
  end if;
  if p_report_token is not null and p_report_token !~ '^[a-f0-9]{32}$' then
    raise exception 'invalid report token';
  end if;
  if p_part_index is null or p_part_count is null or p_part_index < 0
     or p_part_count < 1 or p_part_count > 32 or p_part_index >= p_part_count then
    raise exception 'invalid part';
  end if;

  if p_report_token is not null then
    select id into v_id
    from public.offer_filter_feedback
    where report_token = p_report_token and part_index = p_part_index;
    if v_id is not null then return v_id; end if;
  end if;

  v_window := date_trunc('hour', now())
    + floor(extract(minute from now()) / 10) * interval '10 minutes';

  insert into public.offer_filter_feedback_rate_limit(bucket, window_start, submission_count)
  values (p_rate_bucket, v_window, 1)
  on conflict (bucket, window_start)
  do update set submission_count = public.offer_filter_feedback_rate_limit.submission_count + 1
  returning submission_count into v_count;

  if v_count > 10 then raise exception 'rate limit exceeded'; end if;

  insert into public.offer_filter_feedback(
    kind, category, message, diagnostics, app_version, app_version_code, source, diagnostics_consented,
    report_token, part_index, part_count
  ) values (
    p_kind, nullif(btrim(p_category), ''), nullif(btrim(p_message), ''), p_diagnostics,
    nullif(btrim(p_app_version), ''), p_app_version_code, p_source, coalesce(p_diagnostics_consented, false),
    p_report_token, p_part_index, p_part_count
  )
  on conflict (report_token, part_index) where report_token is not null do nothing
  returning id into v_id;

  if v_id is null and p_report_token is not null then
    select id into v_id
    from public.offer_filter_feedback
    where report_token = p_report_token and part_index = p_part_index;
  end if;

  delete from public.offer_filter_feedback_rate_limit
  where window_start < now() - interval '2 days';

  return v_id;
end;
$$;

revoke all on function public.submit_offer_filter_feedback(
  text,text,text,text,text,text,integer,text,boolean,text,integer,integer
) from public, anon, authenticated;
grant execute on function public.submit_offer_filter_feedback(
  text,text,text,text,text,text,integer,text,boolean,text,integer,integer
) to service_role;

create extension if not exists pg_cron with schema extensions;
select cron.schedule(
  'offer-filter-feedback-retention',
  '17 4 * * *',
  $$delete from public.offer_filter_feedback where expires_at <= now();$$
);

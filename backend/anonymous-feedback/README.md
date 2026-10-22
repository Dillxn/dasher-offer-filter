# Accountless feedback service

The Supabase Edge Function `offer-filter-feedback` (project `offer-filter-feedback`, ref `nwglojlrzfbsibtoqjzc`)
receives Offer Filter's anonymous feedback, offer reports and opt-in diagnostics, and the website's feedback form.
It also counts: the app's anonymous daily check-in, and downloads through the counted download link (the install
page's and the update server's Download links, `…/offer-filter-feedback/download`), kept as daily totals (and, briefly, a count's token and its network's day-keyed value, so each counts once). The app posts to `https://nwglojlrzfbsibtoqjzc.supabase.co/functions/v1/offer-filter-feedback`.

Nothing here is deployed by CI. Deploy by hand, in the order below.

| File | What it is |
| --- | --- |
| `index.ts` | The function. |
| `lib.ts` | Its checks (feedback and counts) and network bucketing. |
| `schema.sql` | The production schema as first deployed (for reference; do not re-run it). |
| `supabase/migrations/20261006120000_feedback_hardening.sql` | The hardening the improved function needs. |
| `supabase/migrations/20261012160000_offer_filter_counts.sql` | The counts' tables, function, views and retention. |
| `supabase/config.toml` | The CLI settings: `verify_jwt = false`, entrypoint `../index.ts`. |

## What a request is

One JSON POST per part, `Content-Type: application/json`, at most 150,000 bytes. The app sends no `Origin`; the
website's form sends its own (`https://offerfilter.org` or `https://www.offerfilter.org`) and may send only feedback.

| Field | Rule |
| --- | --- |
| `kind` | `feedback`, `problem` or `diagnostics` |
| `category` | `general`, `bug`, `feature`, `ux`, `privacy`, `update` or `other` |
| `message` | at most 4,000 characters, trimmed; required for `feedback` |
| `diagnostics` | at most 60,000 characters, stored exactly as sent; needs `diagnosticsConsented: true` |
| `reportToken` | 32 lowercase hex digits, random per submission; ties its parts together |
| `partIndex`, `partCount` | at most 4 parts; the same `partCount` (and `kind`) on every part; part 0 first |
| `appVersion`, `appVersionCode` | the app's version |

The app frames each part of its diagnostics with a first line `[Offer Filter <kind> <token8> part i/n]` and a last
line `[end part i/n]`; the text between them, part after part, is the whole. The view
`offer_filter_feedback_submissions` joins a submission's parts with the frames removed.

### A count

One POST to the same address, `kind` `count`. A key the event does not list is refused (400), and so is a key sent
as `null`; nothing is trimmed.

| Event | Sent by | Keys |
| --- | --- | --- |
| `daily` | The app, at most once a UTC day, with no `Origin` and `Content-Type: application/json` | `kind`, `event`, `countToken` (required), `first`, `appVersion`, `appVersionCode` |
| `download` | Install pages loaded before 0.6.15's counted link: their Download button, by `navigator.sendBeacon` from `https://offerfilter.org` or `https://www.offerfilter.org`, as `text/plain;charset=UTF-8` (no CORS preflight) or `application/json` | `kind`, `event`, `countToken` (optional) |

| Field | Rule |
| --- | --- |
| `countToken` | 32 lowercase hex digits, random. The app makes one for each UTC day and sends it again only to retry that day's count: a token seen before is a duplicate, counted once. |
| `first` | `true` while the service has never accepted a count from this install, else `false` (absent: `false`) |
| `appVersion` | the app's version name, as for feedback (at most 32 characters: letters, digits, `.`, `_`, `+`, `-`) |
| `appVersionCode` | a whole number from 1 to 10,000,000; checked, not stored |

So the app's count is exactly
`{"kind":"count","event":"daily","countToken":"<hex32>","first":false,"appVersion":"0.6.3","appVersionCode":95}`,
and the website's `{"kind":"count","event":"download"}`. Neither carries a message, a device, an Android version, a
maker, a locale, a time zone or any identifier. `text/plain` is taken only from the website's origins and only for a
count (anything else sent so: 415).

### The counted download link

`GET` or `HEAD` `…/offer-filter-feedback/download` answers `302` to the APK on the update server (`lib.ts`'s
`APK_URL`), `Cache-Control: no-store`, `Referrer-Policy: no-referrer`. A browser's GET (a User-Agent beginning
`Mozilla/` that names no robot or link previewer, and no `Sec-Purpose` or `Purpose` naming a prefetch or prerender)
is first counted as a `download`, as a POSTed one is: once per network a UTC day, within the daily cap. The count
runs after the answer (`EdgeRuntime.waitUntil`; elsewhere the redirect waits for it at most 1.5 s), and a failure is
only logged. The User-Agent and purpose are read only to decide, never stored. Any other method there: 405.
`WEBSITE_DOWNLOAD_COUNT.md` says why and which links use it.

## Answers

| Status | Meaning | The app |
| --- | --- | --- |
| 201 `{"ok":true,"reference":"1a2b3c4d"}` | Stored (or stored before: same reference). One reference per submission. | Shows it; done |
| 400 | Never storable (bad field, mismatched parts, part 0 missing) | Drops it, says "Couldn't be accepted" |
| 413 | Too large (request over 150,000 bytes, or a submission over 500,000 bytes) | Drops it |
| 429 + `Retry-After: 600` | This network's 10 new submissions in 10 minutes | Waits at least 10 minutes |
| 503 + `Retry-After` | Storage unreachable (60 s), or today's global cap (until midnight UTC, at most an hour) | Retries with backoff |

The app works with the function deployed before the hardening too, with two differences that matter:

- It counts every part toward the rate limit (the app waits out the 429 and retries).
- It answers 502 when storage refuses a request, which the app retries, but its catch-all answers **400** when
  storage could not be reached at all or answered unreadably. The app takes a 400 as final and drops the submission:
  typed words come back to the open dialog (or to the next one, as the draft), but an automatic summary after a dash
  is lost. So deploy this function before 0.5.0 ships (PUBLIC_BETA_PHONE_GATE.md lists it as a launch gate).

It also keys its network value with the service-role key (long-lived) rather than a daily random salt, and deletes
rate rows older than two days only during later submissions; PRIVACY.md describes only what both functions do.

A count is answered so:

| Status | Meaning | The app |
| --- | --- | --- |
| 201 `{"ok":true}` | Counted, or a duplicate (its token was counted before, or this network's download today was) | Its day's count is done; the install has been counted (`first` is `false` from then on) |
| 400 | An invalid count (and the function deployed before counts answers 400 to every count) | Gives up for the UTC day, no retry; `first` stays as it was |
| 415 | `text/plain` without the website's `Origin`, or for something other than a count | Gives up for the day |
| 429 + `Retry-After` | This network's 50 daily counts today (seconds until midnight UTC) | Gives up for the day |
| 503 + `Retry-After` | Storage unreachable (60 s), or today's global cap of 20,000 counts (until midnight UTC) | Tries again later that day with the same token |

The website does not look at the answer.

## What is stored, and for how long

- `offer_filter_feedback`: each part as sent, with its kind, category, app version, source (`android` or `web`)
  and receipt time. No IP address, user agent, account or device identifier. Deleted after **90 days**
  (`offer-filter-feedback-retention`, daily).
- `offer_filter_feedback_rate_limit`: per network and 10-minute window, a count of new submissions. The network (an
  IPv4 address, or an IPv6 address's /64, from Cloudflare's `cf-connecting-ip` only) is stored only as an
  HMAC-SHA-256 keyed with a random salt for the UTC day. Rows are deleted after **an hour**
  (`offer-filter-feedback-short-lived`, every 10 minutes, and on every request).
- `offer_filter_feedback_salt`: the day's random salt, deleted once the day is over.
- `offer_filter_feedback_daily`: today's totals for the global cap (2,000 submissions, 50,000,000 bytes), no
  network or content; kept 7 days.
- `offer_filter_counts`: the counts, as totals only: per UTC day, event (`daily` or `download`), app version (`''`
  for a download) and `first`, how many. No address, user agent, token, network or identifier. Deleted after
  **400 days** (`offer-filter-counts-retention`, daily at 00:07 UTC).
- `offer_filter_count_seen`: each counted token with its UTC day and nothing else, only so a retried count is counted
  once. Kept at most **two days** (deleted on every count and by the daily job).
- `offer_filter_count_network`: per network and UTC day, how many daily counts it sent (at most 50: a carrier's NAT
  puts many phones behind one address) and whether its download was counted (once a day). The network is the same
  daily-keyed HMAC as the feedback rate limit, whose salt is deleted once the day is over; the rows go then too (on
  the next count, and by the daily job). Nothing ties a token to a network.
- `offer_filter_count_daily`: how many counts were made each day, for the global cap (20,000 a day); kept 7 days.
- The views `offer_filter_counts_daily` (per day and event: `total`, and `first_total`, the counts that were an
  install's first) and `offer_filter_counts_by_version` (per day and app version, daily counts only) read the totals.
- Cloudflare and Supabase keep their own connection logs under their own terms.

Only the service role can read or write any of this. Public clients have no database access.

## Deploying

Requirements: the [Supabase CLI](https://supabase.com/docs/guides/cli) logged in with access to the project. Run
from this folder (`backend/anonymous-feedback`).

1. **Link the project** (once): `supabase link --project-ref nwglojlrzfbsibtoqjzc`
2. **Apply the hardening migration first, if it has not been applied yet** (`select
   to_regprocedure('public.offer_filter_feedback_daily_salt()') is not null;` says whether it has). Either
   `supabase db push` (applies everything in `supabase/migrations/` not applied yet, in order, so it also does step 3),
   or paste `supabase/migrations/20261006120000_feedback_hardening.sql` into the dashboard's SQL editor and run it. It
   is safe while the old function is live (the old `submit_offer_filter_feedback` is left as it was) and safe to run
   twice.
3. **Apply the counts migration**: `supabase/migrations/20261012160000_offer_filter_counts.sql`, by `supabase db push`
   or the SQL editor. It stops at once, changing nothing, when the hardening migration is missing. It is safe while
   the function deployed today is live (that one answers 400 to a count and touches none of it) and safe to run twice.
4. **Check the migrations**: in the SQL editor, `select public.offer_filter_feedback_daily_salt() is not null;`
   returns true; `select jobname, schedule from cron.job where jobname like 'offer-filter-%';` lists
   `offer-filter-feedback-retention`, `offer-filter-feedback-short-lived` and `offer-filter-counts-retention`; and
   `select * from public.offer_filter_counts_daily;` runs (empty).
5. **Deploy the function**: `supabase functions deploy offer-filter-feedback --no-verify-jwt`.
   `supabase/config.toml` sets `verify_jwt = false` and the entrypoint `../index.ts`; with a CLI too old for
   `entrypoint`, copy `index.ts` and `lib.ts` to `supabase/functions/offer-filter-feedback/` and deploy from there.
   The function needs no secret beyond Supabase's own `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY`.
6. **Smoke test** (with made-up text and a made-up version only). First note today's downloads so far:
   `select coalesce(sum(total), 0) from public.offer_filter_counts where day = (now() at time zone 'utc')::date and
   event = 'download';`. Then:

   ```sh
   URL=https://nwglojlrzfbsibtoqjzc.supabase.co/functions/v1/offer-filter-feedback
   curl -si -X OPTIONS "$URL" -H 'Origin: https://offerfilter.org' | grep -i -E '^HTTP|max-age'
   curl -si -X POST "$URL" -H 'Content-Type: application/json' \
     -d '{"kind":"feedback","category":"general","message":"Deployment smoke test","appVersion":"0.5.0"}'
   TOKEN=$(openssl rand -hex 16)
   curl -si -X POST "$URL" -H 'Content-Type: application/json' \
     -d '{"kind":"count","event":"daily","countToken":"'"$TOKEN"'","first":false,"appVersion":"smoke-test","appVersionCode":1}'
   curl -si -X POST "$URL" -H 'Content-Type: application/json' \
     -d '{"kind":"count","event":"daily","countToken":"'"$TOKEN"'","first":false,"appVersion":"smoke-test","appVersionCode":1}'
   curl -si -X POST "$URL" -H 'Origin: https://offerfilter.org' -H 'Content-Type: text/plain;charset=UTF-8' \
     -d '{"kind":"count","event":"download"}'
   ```

   Expect `204` with `Access-Control-Max-Age: 86400`, then `201` with a reference, then `201 {"ok":true}` three times
   (the second daily count is the first one's duplicate). In the SQL editor,
   `select * from public.offer_filter_counts where day = (now() at time zone 'utc')::date;` shows the `smoke-test`
   row with a total of 1, and today's downloads one more than before (or the same, when this network's download had
   already been counted today: the smoke test's was then a duplicate). Delete the test rows:

   ```sql
   delete from public.offer_filter_feedback where message = 'Deployment smoke test';
   delete from public.offer_filter_counts where app_version = 'smoke-test';
   -- Only when today's downloads rose by one with the smoke test:
   update public.offer_filter_counts set total = total - 1
   where day = (now() at time zone 'utc')::date and event = 'download' and app_version = '' and not first;
   delete from public.offer_filter_counts where total = 0;
   ```

   The smoke test's token and network rows go by themselves within two days. The download's curl sets its Origin by
   hand; that a real browser's beacon carries one (a page whose referrer policy is `no-referrer` or `same-origin`
   makes it `Origin: null`, answered 403) is checked from the install page itself, as WEBSITE_DOWNLOAD_COUNT.md's
   "Checking it" says.
7. **After a week with no errors**, drop the old submit function:

   ```sql
   drop function public.submit_offer_filter_feedback(
     text,text,text,text,text,text,integer,text,boolean,text,integer,integer);
   ```

**Rolling back the function**: deploy the previous `index.ts` (`git show c9e1f69:backend/anonymous-feedback/index.ts`);
it still finds its old submit function until step 7. To roll back only the counts, deploy `index.ts` and `lib.ts` as
they were before them (`git show 6295e19:backend/anonymous-feedback/index.ts`, and the same for `lib.ts`): it answers
400 to every count, which the app takes as its day's count given up. The counts' tables can stay.

## Reading the counts

In the dashboard's SQL editor, which runs as the database's owner. (Only it and the service role can read them: the
app and the website have no database access.)

The last 30 days, one row a day:

```sql
select day,
       coalesce(sum(total) filter (where event = 'daily'), 0) as installs_checked_in,
       coalesce(sum(first_total) filter (where event = 'daily'), 0) as new_installs_counted,
       coalesce(sum(total) filter (where event = 'download'), 0) as downloads
from public.offer_filter_counts_daily
where day >= (now() at time zone 'utc')::date - 30
group by day
order by day desc;
```

By app version (which versions are in use):

```sql
select day, app_version, total, first_total
from public.offer_filter_counts_by_version
where day >= (now() at time zone 'utc')::date - 7
order by day desc, app_version desc;
```

How to read them:

- `installs_checked_in` is how many installs sent their count that UTC day: about the installs in use that day (each
  sends at most one a day; installs with the count off, the notice not accepted yet, or no connection that day are
  not in it). Your own phone is one of them while its count is on.
- `new_installs_counted` (`first`) is how many of those had never been counted before: an install's first count
  accepted by the service. A reinstall, or clearing the app's data, makes a new install, counted as new again. Summed
  over a period, it is the installs first counted in that period.
- `downloads` is downloads through the counted link (the install page's and the update server's Download links;
  before 0.6.15, presses of the install page's button), each network counted once a UTC day: a download is not an
  install, and two people on one network on one day count once.
- No row says who: the totals carry no address, device or identifier, so they cannot tell your phone from anyone
  else's. Anything above your own installs is someone else's.

## Testing

The checks' unit tests (`lib.test.ts`) were removed with the repository's other unit tests on 22 October 2026. The
migrations were checked on a local PostgreSQL 16 with Supabase's roles, its
default grants to them and `cron.schedule` stubbed: the counts migration run twice; every outcome of
`offer_filter_count_v1`; concurrent identical counts and downloads counted once, and a network's 50 daily counts
held under concurrency; the cap, the retention and the grants. Never run checks that write against the production
project (the smoke test above writes only made-up rows, deleted after).

# Accountless feedback service

The Supabase Edge Function `offer-filter-feedback` (project `offer-filter-feedback`, ref `zlnfvqyyjsltmkmmpgzp`)
receives Offer Filter's anonymous feedback, offer reports and opt-in diagnostics, and the website's feedback form.
The app posts to `https://zlnfvqyyjsltmkmmpgzp.supabase.co/functions/v1/offer-filter-feedback`.

Nothing here is deployed by CI. Deploy by hand, in the order below.

| File | What it is |
| --- | --- |
| `index.ts` | The function. |
| `lib.ts`, `lib.test.ts` | Its checks and network bucketing, and their tests. |
| `schema.sql` | The production schema as first deployed (for reference; do not re-run it). |
| `supabase/migrations/20261006120000_feedback_hardening.sql` | The hardening the improved function needs. |
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

## Answers

| Status | Meaning | The app |
| --- | --- | --- |
| 201 `{"ok":true,"reference":"1a2b3c4d"}` | Stored (or stored before: same reference). One reference per submission. | Shows it; done |
| 400 | Never storable (bad field, mismatched parts, part 0 missing) | Drops it, says "Couldn't be accepted" |
| 413 | Too large (request over 150,000 bytes, or a submission over 500,000 bytes) | Drops it |
| 429 + `Retry-After: 600` | This network's 10 new submissions in 10 minutes | Waits at least 10 minutes |
| 503 + `Retry-After` | Storage unreachable (60 s), or today's global cap (until midnight UTC, at most an hour) | Retries with backoff |

The app works with the function deployed before this change too, with two differences that matter:

- It counts every part toward the rate limit (the app waits out the 429 and retries).
- It answers 502 when storage refuses a request, which the app retries, but its catch-all answers **400** when
  storage could not be reached at all or answered unreadably. The app takes a 400 as final and drops the submission:
  typed words come back to the open dialog (or to the next one, as the draft), but an automatic summary after a dash
  is lost. So deploy this function before 0.5.0 ships (PUBLIC_BETA_PHONE_GATE.md lists it as a launch gate).

It also keys its network value with the service-role key (long-lived) rather than a daily random salt, and deletes
rate rows older than two days only during later submissions; PRIVACY.md describes only what both functions do.

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
- Cloudflare and Supabase keep their own connection logs under their own terms.

Only the service role can read or write any of this. Public clients have no database access.

## Deploying

Requirements: the [Supabase CLI](https://supabase.com/docs/guides/cli) logged in with access to the project. Run
from this folder (`backend/anonymous-feedback`).

1. **Link the project** (once): `supabase link --project-ref zlnfvqyyjsltmkmmpgzp`
2. **Apply the migration first.** Either `supabase db push` (applies `supabase/migrations/`), or paste
   `supabase/migrations/20261006120000_feedback_hardening.sql` into the dashboard's SQL editor and run it. It is
   safe while the old function is live (the old `submit_offer_filter_feedback` is left as it was) and safe to run
   twice.
3. **Check the migration**: in the SQL editor, `select public.offer_filter_feedback_daily_salt() is not null;`
   returns true, and `select jobname, schedule from cron.job where jobname like 'offer-filter-feedback%';` lists
   both jobs.
4. **Deploy the function**: `supabase functions deploy offer-filter-feedback --no-verify-jwt`.
   `supabase/config.toml` sets `verify_jwt = false` and the entrypoint `../index.ts`; with a CLI too old for
   `entrypoint`, copy `index.ts` and `lib.ts` to `supabase/functions/offer-filter-feedback/` and deploy from there.
   The function needs no secret beyond Supabase's own `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY`.
5. **Smoke test** (with made-up text only):

   ```sh
   URL=https://zlnfvqyyjsltmkmmpgzp.supabase.co/functions/v1/offer-filter-feedback
   curl -si -X OPTIONS "$URL" -H 'Origin: https://offerfilter.org' | grep -i -E '^HTTP|max-age'
   curl -si -X POST "$URL" -H 'Content-Type: application/json' \
     -d '{"kind":"feedback","category":"general","message":"Deployment smoke test","appVersion":"0.5.0"}'
   ```

   Expect `204` with `Access-Control-Max-Age: 86400`, then `201` with a reference. Delete the test row:
   `delete from public.offer_filter_feedback where message = 'Deployment smoke test';`
6. **After a week with no errors**, drop the old submit function:

   ```sql
   drop function public.submit_offer_filter_feedback(
     text,text,text,text,text,text,integer,text,boolean,text,integer,integer);
   ```

**Rolling back the function**: deploy the previous `index.ts` (`git show c9e1f69:backend/anonymous-feedback/index.ts`);
it still finds its old submit function until step 6.

## Testing

`node --experimental-strip-types --test lib.test.ts` (or `deno test lib.test.ts`) tests the checks and the
network bucketing. The migration was checked on a local PostgreSQL 16 with Supabase's roles and `cron.schedule`
stubbed; never run checks that write against the production project.

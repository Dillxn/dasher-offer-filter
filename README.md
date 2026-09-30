# Offer Filter

A local Android assistant for reviewing and declining DoorDash delivery offers. Not an official DoorDash app. Android 8+; release build targets Android 15.

## What it actually does

When a real offer screen is visible in Dasher, Accessibility reads the displayed payout, mileage, duration and stop count. A known enabled-rule failure can request Decline immediately and handle the subsequent confirmation. This is a UI request, not proof of server acceptance.

The confirmation step belongs only to the offer that was declined. A confirmation needs a prompt ("Are you sure…", "Decline offer?") or a way back ("Cancel", "Go back"); a lone Decline button is how a new offer looks while it is still being drawn. A screen whose pay, distance, time or stops contradict the declined offer is a different offer and is judged on its own. Once the tapped confirmation closes, the authority ends.

In the background, a notification containing sufficient explicit offer data can be evaluated. Passing offers receive a selective Offer Filter alert. Unknown offers receive a separate **silent, unclassified review card** that the user can tap to open Dasher. The app does not automatically foreground Dasher. A background notification with only a merchant name cannot establish payout, distance, or whether the order passes.

If a known-failing background offer exposes a safe DoorDash-owned non-activity Decline action, the app can request it. Otherwise it can hide the notification only. **Hiding a notification does not decline the order**; it may remain pending until acted on or expired.

## Sound, vibration and missed-offer tradeoffs

Keep DoorDash notifications allowed and set its offer channel to Silent. Offer Filter cannot change another app's notification settings.

Dasher also rings and vibrates for an offer with its own sound, outside its notifications, until the offer closes. **Mute Dasher's ring while declining** (Setup & help; on by default) turns down the media and alarm streams while a filtered offer is being declined on screen.

- **What it touches.** Only the media and alarm streams, only while that declined offer or its own confirmation is showing, and only when something other than navigation or a call is playing on them.
  - The alarm stream can't be muted, so it goes to its lowest level instead.
  - The ringer, notification and system streams are never touched, because muting them switches the whole phone to vibrate.
- **When it gives way.** Nothing is touched while a call rings or is in progress, and a call that starts mid-decline brings everything back at once. A next offer on screen, a new offer notification, or a passing offer's bell does the same.
- **Restoring.** Everything comes back when the decline ends, after 20 seconds at most, or at the next start if the app died in between.

Navigation voice shares the media stream, so if Dasher's ring plays there, directions are quiet for those seconds too. If Dasher rings on the ringer or notification stream, this setting can't help. The decision's diagnostics log which stream was playing. Android gives an app no way to stop another app's vibration, so the buzz lasts until Dasher closes the declined offer. Declines that don't finish within 5 seconds are reported.

Touching the screen while a decline is in progress hands the offer back. The confirmation is not tapped, that offer is not declined again (from the screen or its notification), the sound comes back, and a toast says so. The takeover holds through partly drawn frames of that offer. It ends when Dasher goes idle or to a delivery, when a clearly different offer appears, or after two minutes. A tiny invisible accessibility overlay notices the touch without receiving it. The app's own taps are accessibility actions, not touches, so they never count. A decline whose confirmation was already tapped can't be undone. A channel's name does not establish its actual configuration; opt-in diagnostics record actual channel sound, vibration, importance, and presence of a full-screen intent when Android exposes these.

Only proven passing offers request an audible Offer Filter alert. Unknown offers and disabled-filter offers use the silent review channel. Consequently a qualifying order with no background price/distance evidence will not produce a passing bell until adequate evidence is available. This is a deliberate uncertainty boundary, not fully automatic background classification. Silent review can be missed; inspect the review cards while parked.

The app retains original passing/unknown notifications rather than deleting them merely because it tried to post a replacement. Alert permission denial, blocked channels, reconnects, duplicate updates, expiration, and stale removals are handled separately.

## Rules and add-ons

Stops count pickups plus drop-offs, as DoorDash shows them: a single order is 2 stops and a double is 4. A "(N stops)" breakdown beside pickups or drop-offs is not the total. Zero disables a rule. Required pay is `max(flat, miles × per-mile rate, minutes × per-minute rate) + extra-stop fee × max(0, stops - 2)`. Maximum stops is inclusive.

The optional **Adaptive minimum** rises with the standalone offers you accept:

- **Payout:** an offer must pay strictly more than the last accepted payout.
- **Rates:** it must also at least match the best pay per minute, per mile and per stop among accepted offers. For example, if your best is $14.20 for 24 minutes, a 30-minute offer needs $17.75.
- **How the floors combine:** each is a floor of its own, compared with the saved rules' total, never added to it.
- **Unknowns:** if an offer doesn't show the minutes, miles or stops a floor needs, it goes to review, never keep or decline.
- **When it learns:** bests are learned only while auto-decline and the adaptive minimum are both on. Turning the minimum on never applies highs gathered while it was off.
- **What doesn't count:**
  - Add-ons are never judged by these floors and don't set them.
  - An offer with any misread-looking figure (under 5 minutes, under half a mile, or 1 stop) sets no best at all.
  - Short trips pay mostly base pay, so only trips of at least 2 miles set the per-mile best, and only trips of at least 10 minutes the per-minute best. A $7.50 hop of 0.6 miles would otherwise make every 5-mile offer need $62.50.
- **Starting over:** **Reset** clears the payout and every best rate.

Known failures may decline despite another missing field. Conflicting, oversized, malformed, negative, ranged, and ambiguous numeric inputs do not become exact facts. Costs use overflow-safe long arithmetic and decimal ceiling for mileage rates.

Add-on offers are not fresh standalone orders. They need explicitly added pay/distance/time/stops or explicitly labeled totals. The flat minimum and stop ceiling apply to the combined route; explicitly added pay is required whenever a per-mile, per-minute, extra-stop, or rising-payout rule is enabled. Bare numbers do not establish incremental meaning. Old accepted-route travel totals are not subtracted from new totals to invent added miles/time: the route may already be partly completed. A missing active route is not permission to apply the standalone flat minimum to the add-on. Confirmed pickup/completion progress invalidates old travel estimates, and recognized idle screens clear route context. Route context can also be cleared manually.

## Setup and recovery

Install the cloud-signed APK over an existing cloud-signed 0.4.x installation. Do not uninstall for a normal update. The original 0.3.1 signer is a separate retired installation chain. Enable Accessibility, notification access, and Offer Filter's notification permission. Configure the observed DoorDash offer channel using the app's shortcut. Some Android devices require allowing restricted settings for sideloaded Accessibility services.

The screen is kept short and mostly drawn rather than written.

- **Top card.** Offer tickets flow into a funnel and out to passed, filtered and review piles, with the last 24 hours' counts. "Filtered" counts only offers the app acted on. A failing offer left to you (paused, refused or taken over) counts as review. The funnel has its sieve when on, is open, dashed and amber when paused, and grey when off.
- **Opened offer.** Tapping a chart bar or a history row draws the offer: its pay as a bar against the needed-pay tick, and its route from pickup bag to drop-off house with a dot per stop.
- **Rules.** A meter shows what an offer like your latest one needs, as one bar per rule with icons: minimum pay, per mile, per minute, extra stops, and each adaptive floor. The rule that sets the bar is highlighted. It follows the fields as you type, before you save.
- **Large fonts.** The drawings size themselves from the system font setting. Labels shrink, or move to their own line, rather than overlap or get cut. With a large font, rule fields stack in one column.

Under the picture, the top card shows whether auto-decline is on, paused or off, the rules in one line (for example `$7 min · $1.50/mi · ≤3 stops`), and one button: **Pause auto-decline** takes effect at once, and **Resume auto-decline** saves the rules and turns auto-decline back on (it refuses when no rule is set). A row with a Fix button appears only while screen reading, background offers or alerts are off. **Rules** and **Setup & help** fold away until tapped; **Save rules** keeps the current on/paused state. While paused nothing is declined and every offer gets a silent review card. No real-world acceptance is claimed merely from a KEEP decision or an attempted click.

## Decision history and diagnostics

Every offer the app evaluates is recorded on the phone, always on and bounded to the latest 200: time, source (screen or notification), the pay/miles/minutes/stops read, the pay the rules required, the result and reason, what the app did (for example "Decline tapped", "Notification hidden; order NOT declined", "Passing alert rang"), and whether auto-decline was paused. It also keeps only the lines that carried a figure or a pay label (such as `$7.90`, `Guaranteed (incl. tips)`, `2 stops (7.2 mi) • 21 min`), never names or addresses, so a misread can be spotted. **Recent offers** charts the latest 14 as bars (pay) against ticks (needed pay), each with a ✓/✕/? badge, and lists the latest five as one line each (`$7.90 · needed $10.80`, reason in plain words). A bar ending below its tick was declined by the rules as written; tap it, or a row, to see what was read and what the app did. Clear history removes it.

**Email report** (under Setup & help) opens the mail app addressed to the user's own saved address, with the report as the body. Nothing is sent until the user presses Send. The subject always starts with `Offer Filter diagnostics`, so Claude can find the latest report through the user's Gmail connection when asked. **Share report** offers any app.

## Automatic problem reports and the fixer

Under Setup & help, **Automatic reports** takes a GitHub fine-grained token that can only write issues in this private repository. Saving it is the opt-in. **Turn off reports** removes it and discards anything not yet sent. With a token saved:

- An offer screen the rules could not judge (pay, miles, time or stops not readable) is reported once per reading gap per app version.
- A declined offer, or its confirmation, still on screen 5 seconds after the Decline tap is reported once per offer. Dasher keeps ringing while it shows.
- A screen-read or notification-handler crash is reported once per error site per version.
- **Report this offer** appears when a history row or chart column is opened. It asks what went wrong (optional) and files the offer with what was read.
- **Send test** checks the whole path.

At most 10 automatic and 20 user reports go out a day, by the phone's clock.

Each report is an issue titled `[offer-report] …`, holding the rules, the decision, the screen lines and recent decisions as JSON. Before a report leaves the phone, every word that isn't offer vocabulary (pay, guaranteed, stops, mi, accept, decline…) is masked to its shape. "Order for Jane D. $7.90" becomes "Order for Xxxx X. $7.90", so store names, customer names and streets never leave. Runs of four or more digits (ZIP codes, house and phone numbers) become `#`, and so do error messages. Offer figures stay, so a misread can be reproduced. The issue is read by Claude in the fixer workflow, so a report goes to Anthropic as well as to GitHub.

Reports queue on the phone and send when any network is available. An outage or rate limit is retried later with backoff. A rejected token keeps them until a new one is saved. Only a report GitHub refuses as invalid is dropped.

Opening such an issue starts `.github/workflows/offer-report-fixer.yml`. It is event-driven, never scheduled. Each run handles every report still waiting, oldest first, one at a time, and closes exact resends as duplicates. Each report goes through `offer-report-fix-one.yml`, in three jobs with separate permissions:

1. **Diagnose** (read-only token, no build caches). Claude follows `tools/fixer/PLAYBOOK.md`: explain a decision the rules made, or reproduce a misread or crash with a failing test, fix it, bump the version and commit. The commit leaves the job only as a git bundle. A session that runs out of turns or time ships nothing.
2. **Gate** (read-only, fresh machine). The commit is applied on top of `main`, and `tools/fixer/gate.sh` runs as it was on `main` before the fix. It requires:
   - exactly one commit, touching only app code, tests, the version lines and notes (never the updater, and moving a protected file counts);
   - changed tests that compile against the old code and fail there;
   - no skipped or removed tests;
   - the full suite and lint passing;
   - an APK that packages.
3. **Ship** (the only job that can write, and it runs no code from the fix):
   - pushes exactly the gated commit to `main`, where Render builds, signs and publishes as usual;
   - waits for the live feed and runs `tools/verify_channel.py`;
   - comments on the issue and closes it (shipped, or explained). Anything else stays open, labeled `fixer:needs-human`, with the reason.

Set the repository variable `OFFER_FIXER_ENABLED` to `false` to stop it.

Capture is local, explicit, bounded and expires after 30 minutes. Raw screen text can contain customer/store/address information; review before sharing. Reports include every saved rule (including the rising-payout baseline), notification-access and service readiness, the decision history, updater status, the latest advertised version and last check timestamps even when raw capture is off; the newest 48,000 characters of raw log are added only while capture has been on. Arbitrary notification-extra values are not interpreted as numeric evidence or dumped to logs. Clear diagnostics removes the local log.

## Build and release

The private source repository feeds the existing Render static build. `render-build.sh` installs the SDK/JDK as needed, verifies the current public channel using the production Java downloader, runs the JUnit and Robolectric suite, builds/signs the APK with the existing Render-held key, checks embedded package/version, and publishes only APK, feed, receipt and install-page assets.

`app/build.gradle` is the version source of truth. A changed source commit cannot reuse the live version code. A deliberate same-source redeploy reuses the already published APK/feed bytes after checking them, preventing mutable content under one version. `verification.json` reports test totals and the exact live version reached by the production-transport probe. The app trusts only the Render release host for the feed, the APK, and every redirect hop; the retired GitHub feed is neither trusted by the app nor published to by any script in this repository. The manual-tests workflow and the offer-report fixer never sign or publish: the fixer only pushes a gated source commit to `main`, and Render signs with the existing key.

Run `./gradlew --no-daemon testDebugUnitTest`, then `./build-local.sh` with the existing signing environment. The network probe is `python3 tools/verify_channel.py`. The app has minSdk 26 and no core-library desugaring, so Java APIs newer than Android 8 (for example `List.of`) crash on older phones even though the JVM-hosted tests pass; `./gradlew lintDebug` reports these as `NewApi`. Local pure-Java checks are not handset installation tests; Robolectric adapter tests are not a physical phone or the real DoorDash client.

## Current limitations

No official DoorDash API integration, hidden-background UI access, global audio muting, or device-level vibration suppression is implemented. Notification identifiers are not guaranteed unique order IDs. Acceptance tracking depends on observing the user tap Accept and the delivery transition, so external acceptance or incomplete screen evidence may leave route context unknown. Android may defer periodic/one-shot work or require an update-installation confirmation. Changing these limits needs additional evidence or capabilities, not more optimistic labels in the UI.

See `AUDIT.md` for the adversarial findings and test boundaries.

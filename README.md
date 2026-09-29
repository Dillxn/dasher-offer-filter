# Offer Filter

A local Android assistant for reviewing and declining DoorDash delivery offers. Not an official DoorDash app. Android 8+; release build targets Android 15.

## What it actually does

When a real offer screen is visible in Dasher, Accessibility reads the displayed payout, mileage, duration and stop count. A known enabled-rule failure can request Decline immediately and handle the subsequent confirmation. This is a UI request, not proof of server acceptance.

The confirmation step belongs only to the offer that was declined. A confirmation needs a prompt ("Are you sure…", "Decline offer?") or a way back ("Cancel", "Go back"); a lone Decline button is how a new offer looks while it is still being drawn. A screen whose pay, distance, time or stops contradict the declined offer is a different offer and is judged on its own. Once the tapped confirmation closes, the authority ends.

In the background, a notification containing sufficient explicit offer data can be evaluated. Passing offers receive a selective Offer Filter alert. Unknown offers receive a separate **silent, unclassified review card** that the user can tap to open Dasher. The app does not automatically foreground Dasher. A background notification with only a merchant name cannot establish payout, distance, or whether the order passes.

If a known-failing background offer exposes a safe DoorDash-owned non-activity Decline action, the app can request it. Otherwise it can hide the notification only. **Hiding a notification does not decline the order**; it may remain pending until acted on or expired.

## Sound, vibration and missed-offer tradeoffs

Keep DoorDash notifications allowed and set its offer channel to Silent. Offer Filter cannot change another app's notification settings or guarantee suppression of sound/vibration generated inside Dasher. A channel's name does not establish its actual configuration; opt-in diagnostics record actual channel sound, vibration, importance, and presence of a full-screen intent when Android exposes these.

Only proven passing offers request an audible Offer Filter alert. Unknown offers and disabled-filter offers use the silent review channel. Consequently a qualifying order with no background price/distance evidence will not produce a passing bell until adequate evidence is available. This is a deliberate uncertainty boundary, not fully automatic background classification. Silent review can be missed; inspect the review cards while parked.

The app retains original passing/unknown notifications rather than deleting them merely because it tried to post a replacement. Alert permission denial, blocked channels, reconnects, duplicate updates, expiration, and stale removals are handled separately.

## Rules and add-ons

Stops count pickups plus drop-offs, as DoorDash shows them: a single order is 2 stops and a double is 4. A "(N stops)" breakdown beside pickups or drop-offs is not the total. Zero disables a rule. Required pay is `max(flat, miles × per-mile rate, minutes × per-minute rate) + extra-stop fee × max(0, stops - 2)`. Maximum stops is inclusive. The optional standalone rising-payout baseline requires strictly more than the last observed accepted standalone payout.

Known failures may decline despite another missing field. Conflicting, oversized, malformed, negative, ranged, and ambiguous numeric inputs do not become exact facts. Costs use overflow-safe long arithmetic and decimal ceiling for mileage rates.

Add-on offers are not fresh standalone orders. They need explicitly added pay/distance/time/stops or explicitly labeled totals. The flat minimum and stop ceiling apply to the combined route; explicitly added pay is required whenever a per-mile, per-minute, extra-stop, or rising-payout rule is enabled. Bare numbers do not establish incremental meaning. Old accepted-route travel totals are not subtracted from new totals to invent added miles/time: the route may already be partly completed. A missing active route is not permission to apply the standalone flat minimum to the add-on. Confirmed pickup/completion progress invalidates old travel estimates, and recognized idle screens clear route context. Route context can also be cleared manually.

## Setup and recovery

Install the cloud-signed APK over an existing cloud-signed 0.4.x installation. Do not uninstall for a normal update. The original 0.3.1 signer is a separate retired installation chain. Enable Accessibility, notification access, and Offer Filter's notification permission. Configure the observed DoorDash offer channel using the app's shortcut. Some Android devices require allowing restricted settings for sideloaded Accessibility services.

The screen is kept short. The top card shows whether auto-decline is on, paused or off, the rules in one line (for example `$7 min · $1.50/mi · ≤3 stops`), and one button: **Pause auto-decline** takes effect at once, and **Resume auto-decline** saves the rules and turns auto-decline back on (it refuses when no rule is set). A row with a Fix button appears only while screen reading, background offers or alerts are off. **Rules** and **Setup & help** fold away until tapped; **Save rules** keeps the current on/paused state. While paused nothing is declined and every offer gets a silent review card. No real-world acceptance is claimed merely from a KEEP decision or an attempted click.

## Decision history and diagnostics

Every offer the app evaluates is recorded on the phone, always on and bounded to the latest 200: time, source (screen or notification), the pay/miles/minutes/stops read, the pay the rules required, the result and reason, what the app did (for example "Decline tapped", "Notification hidden; order NOT declined", "Passing alert rang"), and whether auto-decline was paused. It also keeps only the lines that carried a figure or a pay label (such as `$7.90`, `Guaranteed (incl. tips)`, `2 stops (7.2 mi) • 21 min`), never names or addresses, so a misread can be spotted. **Recent offers** charts the latest 14 as bars (pay) against ticks (needed pay), each with a ✓/✕/? badge, and lists the latest five as one line each (`$7.90 · needed $10.80`, reason in plain words). A bar ending below its tick was declined by the rules as written; tap it, or a row, to see what was read and what the app did. Clear history removes it.

**Email report** (under Setup & help) opens the mail app addressed to the user's own saved address, with the report as the body. Nothing is sent until the user presses Send. The subject always starts with `Offer Filter diagnostics`, so Claude can find the latest report through the user's Gmail connection when asked. **Share report** offers any app.

Capture is local, explicit, bounded and expires after 30 minutes. Raw screen text can contain customer/store/address information; review before sharing. Reports include every saved rule (including the rising-payout baseline), notification-access and service readiness, the decision history, updater status, the latest advertised version and last check timestamps even when raw capture is off; the newest 48,000 characters of raw log are added only while capture has been on. Arbitrary notification-extra values are not interpreted as numeric evidence or dumped to logs. Clear diagnostics removes the local log.

## Build and release

The private source repository feeds the existing Render static build. `render-build.sh` installs the SDK/JDK as needed, verifies the current public channel using the production Java downloader, runs the JUnit and Robolectric suite, builds/signs the APK with the existing Render-held key, checks embedded package/version, and publishes only APK, feed, receipt and install-page assets.

`app/build.gradle` is the version source of truth. A changed source commit cannot reuse the live version code. A deliberate same-source redeploy reuses the already published APK/feed bytes after checking them, preventing mutable content under one version. `verification.json` reports test totals and the exact live version reached by the production-transport probe. The app trusts only the Render release host for the feed, the APK, and every redirect hop; the retired GitHub feed is neither trusted by the app nor published to by any script in this repository. The GitHub workflow is manual tests only and cannot create another signer.

Run `./gradlew --no-daemon testDebugUnitTest`, then `./build-local.sh` with the existing signing environment. The network probe is `python3 tools/verify_channel.py`. The app has minSdk 26 and no core-library desugaring, so Java APIs newer than Android 8 (for example `List.of`) crash on older phones even though the JVM-hosted tests pass; `./gradlew lintDebug` reports these as `NewApi`. Local pure-Java checks are not handset installation tests; Robolectric adapter tests are not a physical phone or the real DoorDash client.

## Current limitations

No official DoorDash API integration, hidden-background UI access, global audio muting, or device-level vibration suppression is implemented. Notification identifiers are not guaranteed unique order IDs. Acceptance tracking depends on observing the user tap Accept and the delivery transition, so external acceptance or incomplete screen evidence may leave route context unknown. Android may defer periodic/one-shot work or require an update-installation confirmation. Changing these limits needs additional evidence or capabilities, not more optimistic labels in the UI.

See `AUDIT.md` for the adversarial findings and test boundaries.

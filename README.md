# Dash Buddy

A local Android assistant for reviewing and declining DoorDash delivery offers. Not an official DoorDash app. The name puts it beside DoorDash's Dasher app in alphabetical app lists. It was called Offer Filter before 0.4.29. The package (com.local.dasherfilter) and signer are unchanged, so it updates over Offer Filter as before. The update file (OfferFilter.apk), this repository (dasher-offer-filter), the "Offer Filter Updates" GitHub App and the report fixer's comments keep their old names. Android 8+; release build targets Android 15.

## What it actually does

When a real offer screen is visible in Dasher, Accessibility reads the displayed payout, mileage, duration and stop count. A known enabled-rule failure can request Decline immediately and handle the subsequent confirmation. This is a UI request, not proof of server acceptance.

The confirmation step belongs only to the offer that was declined. A confirmation needs a prompt ("Are you sure…", "Decline offer?") or a way back ("Cancel", "Go back"); a lone Decline button is how a new offer looks while it is still being drawn. A screen whose pay, distance, time or stops contradict the declined offer is a different offer and is judged on its own. Once the tapped confirmation closes, the authority ends.

In the background, a notification containing sufficient explicit offer data can be evaluated. Passing offers receive a selective Dash Buddy alert. An offer that cannot be judged gets a card on **Offers to check** that rings once while Dasher is in the background, so it is not missed, and opens Dasher when tapped. The app does not automatically foreground Dasher. A background notification with only a merchant name cannot establish payout, distance, or whether the order passes.

If a known-failing background offer exposes a safe DoorDash-owned non-activity Decline action, the app can request it. Otherwise it can hide the notification only. **Hiding a notification does not decline the order**; it may remain pending until acted on or expired.

## Sound, vibration and missed-offer tradeoffs

Keep DoorDash notifications allowed and set its offer channel to Silent. Dash Buddy cannot change another app's notification settings.

Dasher also rings and vibrates for an offer with its own sound, outside its notifications, until the offer closes. **Mute Dasher's ring while declining** (Settings; on by default) turns down the media and alarm streams while a filtered offer is being declined on screen.

- **What it touches.** Only the media and alarm streams, only while that declined offer or its own confirmation is showing, and only when something other than navigation or a call is playing on them.
  - The alarm stream can't be muted, so it goes to its lowest level instead.
  - The ringer, notification and system streams are never touched, because muting them switches the whole phone to vibrate.
- **When it gives way.** Nothing is touched while a call rings or is in progress, and a call that starts mid-decline brings everything back at once. A next offer on screen, a new offer notification, or a passing offer's bell does the same.
- **Restoring.** Everything comes back when the decline ends, after 20 seconds at most, or at the next start if the app died in between.

Navigation voice shares the media stream, so if Dasher's ring plays there, directions are quiet for those seconds too. If Dasher rings on the ringer or notification stream, this setting can't help. The decision's diagnostics log which stream was playing. Android gives an app no way to stop another app's vibration, so the buzz lasts until Dasher closes the declined offer. Declines that don't finish within 5 seconds are reported.

Touching the screen while a decline is in progress hands the offer back. The confirmation is not tapped, that offer is not declined again (from the screen or its notification), the sound comes back, and a toast says so. The takeover holds through partly drawn frames of that offer. It ends when Dasher goes idle or to a delivery, when a clearly different offer appears, or after two minutes. A tiny invisible accessibility overlay notices the touch without receiving it. The app's own taps are accessibility actions, not touches, so they never count. A decline whose confirmation was already tapped can't be undone. A channel's name does not establish its actual configuration; opt-in diagnostics record actual channel sound, vibration, importance, and presence of a full-screen intent when Android exposes these.

Two alerts can ring, each at most once per offer and only while Dasher is not on screen. A proven passing offer rings on **Offers that pass**. An offer that cannot be judged rings on **Offers to check**: DoorDash's background notification shows no pay, or auto-decline is paused. Nothing rings on a replay, a reconnect or an update of the same offer, or for a known failure. An offer that could not be judged is never declined or opened automatically; the alert only says to look. Before 0.4.16 these offers got a silent card, and with DoorDash's own offer channel silenced, background offers went unnoticed.

The app retains original passing/unknown notifications rather than deleting them merely because it tried to post a replacement. Alert permission denial, blocked channels, reconnects, duplicate updates, expiration, and stale removals are handled separately.

## Rules and add-ons

Stops count pickups plus drop-offs, as DoorDash shows them: a single order is 2 stops and a double is 4. A "(N stops)" breakdown beside pickups or drop-offs is not the total. Zero disables a rule. Required pay is `max(flat, miles × per-mile rate, minutes × per-minute rate) + extra-stop fee × max(0, stops - 2)`. Maximum stops is inclusive.

The optional **Adaptive minimum** rises with the standalone offers you accept, and with ones you decline by hand:

- **Payout:** an offer must pay strictly more than the highest standalone payout you have accepted.
- **Rates:** it must also at least match the best pay per minute, per mile and per stop among accepted offers. For example, if your best is $14.20 for 24 minutes, a 30-minute offer needs $17.75.
- **How the floors combine:** each is a floor of its own, compared with the saved rules' total, never added to it.
- **Unknowns:** if an offer doesn't show the minutes, miles or stops a floor needs, it goes to review, never keep or decline.
- **When it learns:** the payout and the bests are learned only while auto-decline and the adaptive minimum are both on. Turning the minimum on never applies highs gathered while it was off.
- **What doesn't count:**
  - Add-ons are never judged by these floors and don't set them.
  - An offer with any misread-looking figure (under 5 minutes, under half a mile, or 1 stop) sets no best at all.
  - Short trips pay mostly base pay, so only trips of at least 2 miles set the per-mile best, and only trips of at least 10 minutes the per-minute best. A $7.50 hop of 0.6 miles would otherwise make every 5-mile offer need $62.50.
- **Declines by hand:** tapping Decline yourself on an offer the rules let through means the rules were too lenient. Only the rule that came closest to catching that offer is raised, just past it (its payout, or its pay per mile, minute or stop), so later offers must beat it. Raising every rule would decline offers like ones you accept. The same guards apply: short trips set no mile or minute floor, and a misread-looking offer teaches nothing. The decline counts only once the next different offer arrives, which shows the dash went on. A decline just before the dash ends or pauses teaches nothing, nor does one of an offer you then accept. Declines the app taps itself, or made while auto-decline or the adaptive minimum is off, never count.
- **It only rises:** a lower offer accepted later never lowers anything. What was learned stays through pauses, turning the minimum off and on, clearing the history and updates.
- **Starting over:** only **Reset** clears the payout, every best rate, and what declines taught.

Known failures may decline despite another missing field. Conflicting, oversized, malformed, negative, ranged, and ambiguous numeric inputs do not become exact facts. Costs use overflow-safe long arithmetic and decimal ceiling for mileage rates.

Add-on offers are not fresh standalone orders. They need explicitly added pay/distance/time/stops or explicitly labeled totals. The flat minimum and stop ceiling apply to the combined route; explicitly added pay is required whenever a per-mile, per-minute, extra-stop, or rising-payout rule is enabled. Bare numbers do not establish incremental meaning. Old accepted-route travel totals are not subtracted from new totals to invent added miles/time: the route may already be partly completed. A missing active route is not permission to apply the standalone flat minimum to the add-on. Confirmed pickup/completion progress invalidates old travel estimates, and recognized idle screens clear route context. Route context can also be cleared manually.

## The filter button in Dasher

While Dasher fills the screen, a small tab with the mascot's face sits fixed on its left edge, a little above the middle, clear of the offer card and its Accept and Decline buttons. In split screen there is no tab: Dash Buddy's own half has the mascot. It cannot be dragged or dismissed. Blue and awake means auto-decline is on, amber and asleep means paused. A tap pauses or resumes, keeping every rule; with no rule saved yet, it opens Dash Buddy. A tap during an automatic decline counts as your touch, so that offer is handed back to you. It needs no extra permission (it is part of the screen-reading service) and leaves when Dasher does.

Beside the tab (in split screen, near the top of Dasher's half) a small pill points toward the best offer area: an arrow and "3.6 mi NE · $3.00/mi". North is up, as on Dasher's map while you wait for offers, and the compass point says the same in words. It shows only while no offer or confirmation is on screen and no delivery is under way, and touches pass through it to Dasher.

**Split with Dasher.** The round button with a split phone at the top of the main page (shown while Dasher is installed and the screen is not split yet) puts Dash Buddy in the top half and Dasher in the bottom half, so Dasher stays on screen with its offers in view. Android has no way for an app to split the screen by itself, so Dash Buddy asks through its screen reading (the same as Android's own Split screen accessibility shortcut), then opens Dasher in the other half. Many newer phones ignore that request, because split screen now starts from recent apps: then Dash Buddy opens recent apps for you with a hint (tap Dash Buddy's icon above its card and choose split screen), and once the screen splits within a minute, Dasher opens in the other half by itself. It happens only when you tap it. In split screen, an offer in Dasher's half is read and declined at once even while you are touching Dash Buddy's half; under the notification shade or recent apps, Dasher is left alone, as when it is off screen.

## Setup and recovery

Install the cloud-signed APK over an existing cloud-signed 0.4.x installation. Do not uninstall for a normal update. The original 0.3.1 signer is a separate retired installation chain. Enable Accessibility, notification access, and Dash Buddy's notification permission. Configure the observed DoorDash offer channel using the app's shortcut. Some Android devices require allowing restricted settings for sideloaded Accessibility services.

The app's icon is a white funnel on blue with a green check. It is an adaptive icon with a themed (one-colour) layer, and offer alerts use the same funnel in the status bar.

The app has two quiet pages. The main page is one picture that fits one screen without scrolling, read top to bottom: a sky with the mascot and the minimums as a constellation, the offers as a skyline on the horizon, and the map on the ground, down to the road. It carries as few words as it can: the picture says what the app is doing, words appear where something needs you, and screen readers hear everything the picture shows. In a short window (half of a split screen) the constellation rises into the sky at the top, beside the sun or moon, and the page keeps the mascot, its counts, anything needing a fix and the map, which gets most of the room; the skyline and the road wait for a whole screen. **Settings** (the round button at the top right) holds everything set once. Back from Settings returns to the main page.

- **The mascot.** A funnel with a face, standing in a brush-drawn ring (an ensō), with this dash's counts beside it (under it on taller screens) (or the last dash's, between dashes): how many offers, then ✓ passed, ✕ filtered and ? review, each with its all-time total underneath. "Filtered" counts only offers the app acted on; a failing offer left to you (paused, refused or taken over) counts as review. A dash starts at the first offer, "finding offers" or delivery screen after the last one ended or went quiet for half an hour. The totals count every offer since the history was last cleared.
  - **On:** the ring goes nearly all the way round, the mascot is cheerful, its rim is a sieve, and an offer ticket drifts down into it.
  - **Paused:** the ring stops at two-thirds, amber, and the mascot sleeps (dashed and amber).
  - **Off:** the ring is a faint dotted circle and the mascot is grey and still.
  - The ring draws itself when the state changes.

  While on, there are no words; paused, one line says **Paused**, and with no rules yet, **Tap to set up rules**.
- **Day and night.** Tap the sun to turn the app to night, and the moon to turn it back to day. Until then it follows the phone's dark mode.
- **Live while dashing.** While a dash is on (an offer, Dasher's "finding offers" screen or a delivery seen in the last half hour, or a route under way, and no "dash ended" or "dash paused" screen since) and screen reading or background offers are on, two searchlights sweep the sky from behind the hills (screen readers hear "watching for offers" from the mascot). Otherwise the sky is still. This only shows what the app is doing; nothing is decided from it.
- **The mascot is the button.** Tapping it pauses at once, or resumes: that saves the rules as typed and turns auto-decline back on. With no rule saved, a tap opens Settings. It squishes a little while pressed, and screen readers hear it as a button named for what it does ("Pause auto-decline"). A quiet line with **Fix** appears only while screen reading, background offers or alerts are off. An active route shows with a Forget button.
- **Offers.** The latest 14 offers form a small skyline. Each building is as tall as the pay read, a rope marks the pay the rules needed, and a flag on the roof says ✓ passed, ✕ declined or ? review. Passed offers have their windows lit, and an offer whose pay was not read is a signpost. A building ending below its rope was declined by the rules as written. Tap a building to slide its ticket up over the page: the outcome rubber-stamped on the stub, the pay against the needed pay, the route from pickup bag to drop-off house, what the app did, and the lines read. With reports on, the ticket has **Report this offer**. The newest building is the chosen one until you tap an older one.
- **Minimums.** Constellations in the sky (ink on the morning sky, shining at night), with a spoke each for pay, per mile, per minute and per stop, each marked by the same icon as its field in Settings (a coin, a road, a clock and a pin); there are no words or key. The minimums you set are the solid shape with round stars. The adaptive minimums are the dashed shape with sparkles, muted while the adaptive minimum is off. Distance from the middle is what each minimum asks of one example offer (the latest fully read one, or a typical one), on one dollar scale. Recent offers are small marks on each spoke (● passed, ✕ declined, ○ review) at what their own pay, pay per mile, per minute and per stop would pay for that example. A mark outside a minimum beat it. Screen readers hear first what the example needs. The extra-stop fee has no spoke: it is added on top of the other minimums rather than being one. Screen readers hear every value. Tap the star to change the minimums.
- **The ground is the map** (see Offer areas below). Below the skyline, the squares where offers came in lie on the land around you (fading out toward every edge rather than stopping at a hard line; the compass and scale stay crisp), gilded deeper where offers pay more per mile, with coins on the best three and a dotted trail from you to the best. It is centred on you, with squares big enough to read and tap; far ones fall outside it. The best areas, and a signpost on the hills for where you are, carry neighbourhood names from the phone's own place lookup; the blue dot is you. One line under it names the chosen area, its distance and its pay per mile ("Treasure Island · 3.6 mi NE · $3.00/mi ›"; its rank is on its coin), and a tap opens it in your maps app. Before location is allowed, a tap on the ground asks for it.
- **Settings.** The rules on soft cards, with a live line saying what an example offer needs as you type. **Save rules** keeps the current on/paused state. Switches have the mascot's face for a knob: awake when on, asleep when off. Then come sound, the Android shortcuts as plain rows, the offer map, reports (**Share report** and **Clear history** as rows, raw capture, automatic reports), updates (with **Connect GitHub**) and, once the author's names are filled in, **Support** with one-tap tips.
- **Scenery and motion.** On the main page the sky runs from a soft morning blue to a warm horizon with a sun and drifting clouds (in dark mode deep blue, with a moon and stars across the whole sky), two ranges of hills stand behind the skyline, and the ground runs down to hills, houses and a road with a little car. Settings keeps a strip of sky behind its title and the same road at the end. Each region stays light (or dark) enough for the page's text. Things move gently: stars twinkle, clouds and birds drift, the car drives, the mascot breathes and blinks, its ring breathes, buildings rise as offers arrive, the star's shapes glide to new values, and the stamp thumps down. Tilting the phone slides the far layers against the near ones. The tilt comes from the phone's gyroscope (or gravity sensor), read only while the app is on screen, never kept or sent. With Android's **Remove animations** setting on, everything rests and no sensor is read.
- **Fonts.** The drawings size themselves from the system font setting. Labels shrink, or move to their own line, rather than overlap or get cut. With a large font, the rule cards stack in one column.

While paused nothing is declined, and every background offer gets an "Offers to check" alert. No real-world acceptance is claimed merely from a KEEP decision or an attempted click.

## Offer areas

On from the start (the homepage's ground is the map), and nothing is kept until approximate location is allowed; **Remember where offers come in** in Settings turns it off. While on, each new standalone offer with a known pay is added to the square of about 2 km the phone was in when it appeared.

- **Location.** Only approximate location is asked for, never precise. The position is the newest fix the phone already has (no GPS is switched on for this), used only if it is under ten minutes old, and rounded to its square before it is kept.
- **Counting.** An offer seen from its notification and then on screen counts once. Add-ons and offers without a pay are left out. An offer with no fresh location is only counted as "without a location". If that happens, Settings asks for **Allow all the time**, since Android may share location only while Dash Buddy is in front.
- **Ranking.** A square is ranked by pooled pay per mile (all pay over all miles, so one short trip cannot dominate) once three offers with miles came in there.
- **Privacy.** A square is where you were when an offer came in, not where its pickup is. Squares are not in reports (the diagnostic report says only how many), and the map is drawn without map tiles. Neighbourhood names come from the phone's own place lookup (Android's geocoder, which on most phones asks Google's servers): only positions rounded to about half a kilometre are asked about, each once, and the names are kept on the phone; nothing about offers goes with them. **Open in Maps** hands one square's center to your maps app only when you tap it. **Forget areas** removes them all.

## Decision history and diagnostics

Every offer the app evaluates is recorded on the phone, always on and bounded to the latest 200: time, source (screen or notification), the pay/miles/minutes/stops read, the pay the rules required, the result and reason, what the app did (for example "Decline tapped", "Notification hidden; order NOT declined", "Passing alert rang"), and whether auto-decline was paused. It also keeps only the lines that carried a figure or a pay label (such as `$7.90`, `Guaranteed (incl. tips)`, `2 stops (7.2 mi) • 21 min`), never names or addresses, so a misread can be spotted. The skyline on the main page shows the latest 14 as buildings (pay) against ropes (needed pay), each with a ✓/✕/? flag. A building ending below its rope was declined by the rules as written; tap it to see its ticket with what was read and what the app did. Clear history removes it.

**Share report** (in Settings) offers any app for the report. Nothing is sent until the user sends it. The subject starts with `Dash Buddy diagnostics` (`Offer Filter diagnostics` before 0.4.29), so a report mailed to yourself can be found through the user's Gmail connection when asked.

## Automatic problem reports and the fixer

In Settings, **Automatic reports** takes a GitHub fine-grained token that can only write issues in this private repository. Saving it is the opt-in. Once GitHub is connected for updates, **Send reports through my GitHub connection** is the other way in, with no token to paste: turning it on is the opt-in, and the "Offer Filter Updates" GitHub App (it keeps its old name) then also needs **Issues: Read and write**. Adding the permission to the app is only half of it: the installation must accept it too (github.com → Settings → Applications → Installed GitHub Apps → **Configure** next to the app → accept the new permissions; GitHub also emails a request to review them). Until then GitHub answers 403, the status line shows GitHub's own words and what to do, and reports wait; each time Dash Buddy opens, it tries them again with a freshly renewed token. Connecting GitHub alone never turns reports on. **Turn off reports**, turning that switch off, or disconnecting GitHub stops reports and discards anything not yet sent. With reports on:

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

`app/build.gradle` is the version source of truth. A changed source commit cannot reuse the live version code. A deliberate same-source redeploy reuses the already published APK/feed bytes after checking them, preventing mutable content under one version. `verification.json` reports test totals and the exact live version reached by the production-transport probe. The app trusts two release origins, each with one exact feed and one exact APK address, and checks every redirect hop against them: the Render host, and this repository's `release/` folder on `main`, read through the GitHub API. Whichever advertises the higher version code is used (a tie goes to Render), and one being unreachable does not stop the other. The retired GitHub feed is neither trusted by the app nor published to by any script in this repository. The manual-tests workflow and the offer-report fixer never sign or publish: the fixer only pushes a gated source commit to `main`, and Render signs with the existing key.

When Render cannot build (for example when the workspace is out of build minutes), `tools/sign-local.sh` builds and signs the same release in a Claude session. The same cloud key reaches the session only through the environment variables `OFFER_FILTER_KEYSTORE_B64` and `OFFER_FILTER_SIGNING_PASSWORD`, which the user sets in the environment settings. The script refuses any other certificate, runs the tests, and leaves `dist/OfferFilter-<version>.apk`, which installs by hand over the current app with settings kept.

To ship that APK to phones without Render, commit the source and run `tools/publish-repo-feed.py`. It refuses an uncommitted source, any other signer, a package or embedded version that disagrees with `app/build.gradle`, and a version code not above the one already in `release/`, and checks the feed with the app's own policy code. It then writes `release/OfferFilter.apk` and `release/latest.json`, naming the source commit. Commit `release/` on its own and push both commits to `main`. Nothing is signed on GitHub, and no workflow or paid service is involved.

### Updates from the repository (Connect GitHub)

The repository is private, so a phone reads `release/` with its own GitHub sign-in:

1. **Once, on GitHub:** create a GitHub App owned by your account (Settings → Developer settings → GitHub Apps). Tick **Enable Device Flow**, turn the webhook off, and give it only **Repository permissions → Contents: Read-only**. Install it on this repository only, and put its **Client ID** (public, not a secret) in `GitHubConnect.CLIENT_ID`. Builds without one hide the feature.
2. **On the phone:** Settings → Updates → **Connect GitHub**. The app asks GitHub for a short code, copies it, and opens github.com/login/device. Paste the code and approve.

The token can only read this repository's files, is limited to this repository, and refreshes itself with the client ID alone (no client secret, no server). It is kept in the app's private storage and sent only to GitHub. **Disconnect GitHub** forgets it. A refused refresh forgets it too and says to connect again.

Run `./gradlew --no-daemon testDebugUnitTest`, then `./build-local.sh` with the existing signing environment. The network probe is `python3 tools/verify_channel.py`. The app has minSdk 26 and no core-library desugaring, so Java APIs newer than Android 8 (for example `List.of`) crash on older phones even though the JVM-hosted tests pass; `./gradlew lintDebug` reports these as `NewApi`. Local pure-Java checks are not handset installation tests; Robolectric adapter tests are not a physical phone or the real DoorDash client.

## Current limitations

No official DoorDash API integration, hidden-background UI access, global audio muting, or device-level vibration suppression is implemented. Notification identifiers are not guaranteed unique order IDs. Acceptance tracking depends on observing the user tap Accept and the delivery transition, so external acceptance or incomplete screen evidence may leave route context unknown. Android may defer periodic/one-shot work or require an update-installation confirmation. Changing these limits needs additional evidence or capabilities, not more optimistic labels in the UI.

See `AUDIT.md` for the adversarial findings and test boundaries.

## 0.4.9 — touch to take over, a quieter decline, and an automatic fix loop

- **Touch to take over.** Touching the screen while a filtered offer is being declined stops the automation for that offer, and a toast says "Offer Filter stopped tapping this offer".
  - The confirmation isn't tapped, and the offer isn't declined again, either on screen (even while Dasher redraws it) or from its notification.
  - A tiny invisible overlay notices the touch without receiving it, and only while a decline is in progress.
  - A confirmation already tapped can't be undone.
- **Dasher's ring is turned down while declining.** Dasher rings for an offer with its own sound, not its notifications, until the offer closes. While the declined offer or its confirmation is on screen, the media and alarm streams are turned down if something other than navigation is playing on them.
  - The alarm stream goes to its lowest level, since Android can't mute it.
  - The ringer, notification and system streams are never touched: muting them would flip the phone to vibrate.
  - Sound comes back when the decline ends, a next offer or a passing bell arrives, a call starts, after 20 seconds, or at the next start after a crash.
  - Turn it off with **Mute Dasher's ring while declining** under Setup & help.
  - Android doesn't let one app stop another's vibration, so the buzz still lasts until Dasher closes the offer.
- **Automatic reports (opt-in).** Under Setup & help, paste a GitHub fine-grained token limited to this repository's issues. The app then files an issue when:
  - an offer can't be judged (once per reading gap per version);
  - a decline is still on screen 5 seconds after the tap (once per offer);
  - the screen reader or notification handler crashes (once per error site per version).

  **Report this offer** on an opened history row or chart column files one with your note, and **Send test** checks the path. At most 10 automatic reports go out a day. Every word that isn't offer vocabulary, and every run of four or more digits, is masked before sending. Store names, customer names, streets, ZIP codes and phone numbers never leave the phone. Outages and rate limits are retried. **Turn off reports** removes the token and discards unsent reports.
- **The fixer.** Each report issue starts a GitHub Actions run, not a schedule.
  - **Diagnose:** Claude, with a read-only token and no shared build caches, explains a decision the rules made, or reproduces a misread or crash with a failing test, fixes it and bumps the version. A session that runs out of turns or time ships nothing.
  - **Gate:** a separate read-only job applies that one commit to main and requires tests that compile and fail on the old code, then the full suite, lint and a packaged APK.
  - **Ship:** only this job can push, and it runs none of the fix's code. It waits for Render and verifies the live APK before it comments and closes the issue. A cancelled run pushes nothing. Anything unfinished stays open, labeled `fixer:needs-human`.

Two independent adversarial reviews ran before release, and everything they confirmed is fixed:
- the model's session could reach a write token;
- gate bypasses (renames; tests that merely fail to compile);
- dropped reports on outages;
- unmasked screen text;
- a takeover forgotten on a half-drawn frame;
- muting that outlasted the declined offer;
- ringer-mode side effects of muting.

Evidence boundaries:
- **Simulated Android 8 and 15 (JUnit and Robolectric):**
  - touch takeover: confirmation withheld, no re-decline even through half-drawn frames or the notification path, a different offer still judged, the overlay only during a decline;
  - ring handling: media and alarm turned down only while the declined offer shows; restored after the decline, a touch, a next offer, a call, a passing bell or a crash; ringer streams never touched; navigation alone never triggers it; the setting honored;
  - stuck-decline reports;
  - report contents, masking, deduplication, daily caps and the network job;
  - sending to a local fake GitHub (created, bad token, invalid, outage, rate limit, no connection).
- **The gate** was dry-run on simulated fixer commits. It rejected:
  - an edit to a protected file;
  - a move of a protected file;
  - an updater change;
  - a test that only fails because it doesn't compile on the old code;
  - a test that passes without the fix.

  A real fix passed.
- **Not tested:**
  - The workflow runs only on GitHub, so its first real run is its first end-to-end test.
  - Whether Dasher's ring actually stops, and which stream it uses, can only be heard on the phone.
  - Detecting touches through the overlay relies on Android behavior that Robolectric can only simulate.

## 0.4.8 — fixes from the first real diagnostics report

Built from the first real report sent from the phone:
- **Stops on double orders.** DoorDash shows "4 stops (2.6 mi) • 30 min" and also "Multiple dropoffs (2 stops)". The second line counts drop-offs only, but was read as a conflicting total, so stops became unknown. A "(N stops)" breakdown next to pickups or drop-offs no longer counts as the total. The real screen text is now a regression test.
- **"Max stops" says what it counts.** The label reads "Max stops (1 order = 2)". DoorDash counts pickups plus drop-offs, so a double order is 4 stops and a max of 2 or 3 declines every double.
- **Screen fixes.** Content no longer scrolls behind the status bar. The tapped chart column gets a small marker under the axis instead of a grey column that looked like a bar. Time labels stay inside the chart. An unread-pay offer reads "Pay unknown / Notification showed no pay" instead of repeating itself.

The report also showed that the two declines it captured followed the rules as written. $9.75 for 30 minutes needed $18.00 at $0.60/min, and $14.40 for 41 minutes needed $24.60, with 4 stops over a maximum of 2. Neither was a misread.

## 0.4.7 — false-decline fixes, decision history, clear Pause/Resume, redesigned screen

After a report that a valid offer was declined, code review found three ways 0.4.6 could decline an offer that should have been kept. Each was reproduced by a failing test against the live 0.4.6 code before being fixed:

- **A rate next to the pay label was read as pay.** With "$30.00", then "Guaranteed", then "$12.00/hr" on screen, the label took the hourly rate as the offer's pay, so a $30 offer looked like $12 and was declined. A rate is never pay now.
- **A bonus was read as pay.** "+$2.00 Peak Pay" under "Guaranteed" made a $9.90 offer read as $2.00, and the bonus could also stand in for pay when the main amount wasn't readable yet. A "+$" amount is never pay now. When one appears next to a total that isn't explicitly labeled, it's unclear whether the total includes it, so the offer needs review rather than being declined.
- **One offer's decline confirmation could tap Decline on the next offer.** The confirmation step stayed authorized for 10 seconds, and it treated any screen with a Decline button but no readable Accept as the confirmation dialog. That describes a new offer that is still being drawn, or one with a Back button. Now:
  - A confirmation needs a prompt or a way back out (Cancel, Go back, Keep order, Never mind…).
  - A screen whose figures contradict the declined offer is judged on its own.
  - A screen that still shows an Accept button counts as the dialog only if it visibly repeats the declined offer.
  - Reaching a delivery screen ends the authority.
  - The authority also ends about a second after the tapped confirmation closes. That delay lets a swallowed tap be retried.
  - Where both the offer and the dialog have a Decline button, the dialog's is tapped.
  - Offer screens with a Back button are now judged normally. Before, they were never evaluated.

The "Notification access" shortcut now passes the component name the way Android 11+ expects, so it opens Offer Filter's own access page.

New:
- **Decision history and chart.** Every evaluated offer is recorded on the phone: what was read, the pay needed, the result, the reason and what the app did. The Recent offers card charts the latest 14 as pay bars against needed-pay ticks, with ✓/✕/? badges and a legend, so a status is never shown by color alone. Tap a bar or row to see the exact lines that were read. Only figure and pay-label lines are kept, never names or addresses. The history is bounded to 200 entries.
- **Pause/Resume.** One button on the status card pauses at once, or resumes with the rules on screen. Save keeps the on/paused state. The card also shows setup problems with a Fix button each.
- **Email report.** Under Setup & help, this opens the mail app addressed to your own saved address, with the full report (rules, setup, decision history, updater state) as the body. Nothing is sent until you press Send. The subject always starts with "Offer Filter diagnostics", so Claude can find the latest report through your Gmail connection.
- **A much shorter screen.** It shows the status, one Pause/Resume button, any setup problem, the chart and the last five offers, with reasons in plain words ("Below your per-mile rate"). Rules and Setup & help fold away until tapped. The long explanations are gone. Light and dark themes, with themed system bars on Android 8–15.

An independent adversarial review of this release found the remaining bonus-as-pay and next-offer paths above before launch. Each finding got a failing test first.

Evidence boundaries: JUnit and Robolectric (simulated Android 8 and 15) tests pass. The screen was rendered and reviewed in light and dark themes with Robolectric's native graphics. Android Lint reports no API-level issues for minSdk 26. These do not establish physical handset behavior, real DoorDash screen layouts, or real sound and vibration. The false decline that was reported could not be traced from logs, because no report existed; the decision history is there so the next one can be.

## 0.4.6 — code-quality review release

A full review and readability rewrite of the 0.4.5 sources, fixing four defects that were each reproduced by a failing test against unmodified 0.4.5 before being fixed.

- **Fresh offers are no longer dropped after deep sleep.** The per-notification expiry callback runs on uptime, which stops while the phone sleeps. When DoorDash reused a notification key for a new offer after the old one's 90-second lifetime but before that callback ran, 0.4.5 removed the stale entry and ignored the new offer: no passing bell and no review card. Expiry is now checked against elapsed time on every post, so the new offer starts a new incarnation, matching the awake-phone behavior.
- **Reconnect clears leftover cards.** 0.4.5 cleared only an untagged alert on listener connect, but every card is tagged, so after a process restart old cards stayed next to the replayed ones until they timed out. All Offer Filter offer cards are now cleared on connect; the update-confirmation notice is left alone.
- **Android 15 layout.** With targetSdk 35, Android 15 draws the settings screen edge to edge. The page now keeps clear of the status bar, navigation bar, display cutout and keyboard, and uses dark system-bar icons on its light background. Earlier Android versions are unchanged.
- **Add-ons ask only for evidence an enabled rule uses.** With only a flat minimum and/or a stop ceiling, an add-on is judged on the combined route; missing *added* pay no longer forces REVIEW. Added pay is still required whenever a per-mile, per-minute, extra-stop or rising-payout rule is enabled.
- The updater trusts only the Render release host. The retired GitHub/base64 feed paths, which could only ever serve the old signer, were removed from the app, along with the two scripts that published to that feed.
- Review cards read "Not classified: pay not found. Tap to open Dasher…", and diagnostics reports now include the rising-payout rule and baseline, notification-access state and the latest advertised version.
- Every source file was reformatted into conventional one-statement-per-line Java with named constants and helpers. Parsing expressions are byte-identical. Previous tests keep their exact assertions, except `UpdatePolicyTest`, which now asserts that the retired GitHub URLs are rejected. Seven new adapter tests (each run on Android 8 and 15), plus two rule tests and one policy test, raise the suite from 111 to 128 test runs, and the release gate now requires that coverage.

Evidence boundaries: JUnit and Robolectric (simulated Android 8 and 15) tests pass, Android Lint reports no API-level errors for minSdk 26, and the unsigned APK packages at 0.4.6. The signed build, publication and live-channel verification of 0.4.6 happen on Render. Physical handset installation and real DoorDash sound/vibration behavior are not established by these checks.

## 0.4.5 — completed adversarial audit release

Includes the 0.4.4 audit repairs plus expanded Android Accessibility adapter coverage on API 26 and 35, a legacy-Android guard for notification action type inspection, and protection against stopped update jobs completing a replacement job with the same ID.

- Passing alerts and silent unclassified review cards are separate. No automatic Dasher foreground takeover.
- Original passing/unknown notifications are retained if replacement alerts are blocked or unavailable.
- Bounded per-notification state, expiry cleanup, duplicate/reconnect bell prevention and stale-removal isolation replace global pending state.
- Only displayed notification text is numeric evidence. Arbitrary extras are not promoted into pay/distance; customer-message offer wording is rejected.
- Add-ons require explicit added values. Missing active context and conflicting figures require review instead of standalone or incremental guesses.
- Whole-token money, duration-range guards and overflow-safe math prevent malformed fields from becoming valid rule inputs.
- Actual Finding offers screens clear stale context. Pickup/completion progress invalidates old travel estimates.
- Immediate screen decline is retained; pause, newly passing screens and newer notification generations revoke stale confirmation authority. Requests and hidden notifications are not labeled completed declines.
- Pause persists immediately. Raw logs are local, bounded and expire after 30 minutes. Reports always include updater status.
- Failed checks schedule real one-shot jobs; duplicate checks coalesce; every redirect and embedded APK version is validated. Automatic installation waits during active offers/deliveries.
- Release tests include both notification/UI and real Accessibility-service adapter flows on simulated Android 8 and Android 15. Production HTTP code verifies the public feed, APK bytes/hash, signer and embedded version.
- Same cloud signer: no uninstall or key reset for existing cloud-signed 0.4.x installations.

The code does not claim to mute Dasher's internally generated audio/haptics or classify a background notification that provides no payout/distance. Physical handset installation and live DoorDash behavior are not established by the simulated tests. See AUDIT.md and the published verification.json for evidence boundaries.

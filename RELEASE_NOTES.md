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

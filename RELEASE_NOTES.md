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

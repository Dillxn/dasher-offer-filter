# Adversarial audit — Offer Filter 0.5.0

Audited baseline: main commit 4efa90d (0.4.5 / versionCode 11). Two independent auditors (pure decision core; Android adapters and release scripts) reproduced findings with javac probes and Robolectric; each medium/high finding was then checked by a separate skeptic. Public DoorDash Help Center / Dasher Central screenshots were used to build realistic offer-card fixtures. Nine findings were confirmed, one was rejected (below), and the implementation went through two further rounds of independent adversarial review with repairs.

## Confirmed and fixed
1. **Pay taken from the wrong line (high).** A money-less "Guaranteed" caption adopted the next line's amount, so a Flash boost, Peak Pay, `$1.21/mi` or `$17.50/hr` became the payout and good offers were declined. Pay is now attributed per amount (free, component, increment, rate, included); a label takes only a bare amount beside it; conflicts are unknown.
2. **Earn by Time declined (high).** `$15/active hr + tips` parsed as a $15 offer. Hourly cards are now always REVIEW, whatever other rule fails.
3. **Add-on misread as a decline confirmation (high).** "Add to route" was not an accept control; the card could be confirm-declined without evaluation. It is now the add-on accept control, and a surface with offer evidence confirms only when bound to the declined offer.
4. **Confirmation carried over to the next offer (high).** Ten-second, time-only authority could click Decline on a different passing offer. Authority is now bound to the declined offer's facts and ends once its sheet closes.
5. **Signer check failed on Android 9–10 (high).** Archive certificates were only collected with `GET_SIGNATURES` there; the archive parse now requests both flags. The installed-package check and signer comparison are unchanged.
6. **Wrong minutes (medium).** "1 h 5 min" → 5; pickup/ready/ETA times read as trip time. Fixed with full hour+minute parsing and context guards.
7. **"+$" increment treated as standalone pay (medium).** Increments never become standalone pay and never count alone as add-on evidence.
8. **Accept tracking credited failed or stale accepts (medium).** Delivery labels must newly appear after the tap; "no longer available" cancels; ambiguous accepts clear route context; add-on accepts that cannot be recorded clear route context.
9. **Notification-access shortcut crashed Settings on Android 11+ (medium).** The component extra is now a flattened String.

Lower-severity fixes: countdown labels resetting the 4-tap cap, one-cent double-sum rounding on add-on miles, "$7.50+" as exact pay, split "21 min" nodes ignored, road names/fractions as mileage, offer notifications dropped by merchant names containing exclusion words, re-ring of an identical notification after the 90 s entry lifetime, orphan review cards after process death, a failing offer with a "Back" label never getting its first decline, update deferral re-polling every minute, a dropped manual update tap, the POST_NOTIFICATIONS dead end, comma decimals, Android 15 edge-to-edge overlap, and the retired GitHub publisher still being runnable (removed).

## Rejected
- *Re-ring a same-key notification update as a new offer.* The mechanism is real, but without an order ID a pay correction is indistinguishable from a new offer and `Notification.when` changes on every rebuild, so the proposed fix would reintroduce the repeated-ring defect fixed in 0.4.4. One passing bell per notification incarnation remains the rule.

## New surfaces and their safety boundaries
- Store avoid list: a known failure; never creates KEEP. On notifications it acts only when the notification is classified as a real offer and the store comes from DoorDash's own "Go to …" phrase.
- Maximum miles: add-ons use only an explicitly displayed route total.
- Rising baseline: expires after 8 hours; undated 0.4.x baselines do not apply.
- Decline limit per hour: opt-in; only converts declines into review.
- History: local, bounded, no raw screen text; decisions never learn from it.

## Evidence boundaries
JVM tests (including fixed-seed fuzzing of 12,000 random and 6,000 realistic offer cards) and Robolectric Android API 26/35 adapter tests. The live-channel probe verifies the published APK. None of this is a physical handset, the real DoorDash client, or real audio/haptics.

---

# Adversarial audit — Offer Filter 0.4.4

Audited baseline: main commit 5e30520683e90393d969548c6a3642d914da26c6 (0.4.3 / versionCode 9). User-supplied 0.4.2 phone diagnostics were used as a regression scenario, not as evidence that all 0.4.3 behavior was observed on-device.

## Critical findings

1. **Architecture promised more evidence than it possessed.** The observed new-order notification supplied a merchant, not payout/distance. Launching Dasher before classification necessarily exposed filtered orders. Removing all launches then made unknown offers easy to miss. Repair: explicit silent review cards, no foreground takeover, candid capability status. Remaining: no-price notifications still cannot be classified without additional evidence.
2. **Notification suppression was called decline.** A successful UI click or PendingIntent send is only a request. Canceling a notification does not reject the order at DoorDash. Repair: distinct requested/hidden/observed-idle language; safe notification decline actions only; no blind global-pending-key cancellation from screen handlers.
3. **Unlabeled add-on numbers were guessed to be increments.** Conflicting and absent values shared a null fallback; old trip totals were subtracted from new totals despite route progress. Repair: explicit increments only; conflict remains unknown; no old-travel subtraction; missing active context stays an add-on, not a standalone-flat-minimum failure.
4. **Release checks missed the real update path.** 0.4.2's feed host and actual transport allowlist diverged. 0.4.3 repaired the host, but still only stored backoff deadlines without scheduling a retry. Repair: shared production HTTP implementation, live transport probe, actual one-shot JobScheduler retry, detailed state and error reporting.

## High-severity defects and repairs

- Replacement attempts could remove the only usable original even when notification permission/channel prevented posting. Original passing/unknown notifications are retained; replacement returns success/failure.
- One global pending notification key could be overwritten and later canceled by an unrelated screen action. Per-key, bounded lifecycle state replaces it; source removals are timestamp checked.
- Cancel/repost and changed payloads could repeatedly ring. Passing-announcement budget is consumed once, including foreground/replay delivery. Unknown review cards are never deliberately audible.
- Parsing a monetary prefix could turn `$10000.00` into `$1000`, `$7.901` into `$7.90`, and negative money into a positive value. Strict whole-token guards reject these as unknown. Thousands-style comma ambiguity is not guessed.
- Duration ranges and fractional fragments could become an exact integer, and rate multiplication could overflow 32-bit arithmetic. Range/sign/fraction guards and overflow-safe long arithmetic replace these paths.
- Generic arbitrary extra values could carry unrelated numbers into decisions. Only standard displayed notification text is used; arbitrary extra keys can be logged, not their values or imagined semantics.
- Real idle text `Finding offers` was not clearing route state; animation drove repeated screen-log noise. Unicode normalization, recognized idle clearing, progress invalidation and meaningful-change diagnostics reduce this risk.
- A competing GitHub workflow could generate an independent signing key, and multi-file edits could auto-publish partial versions. The GitHub workflow is tests-only; the complete change is committed atomically; packaged version derives from Gradle; changed source cannot reuse a live version code.

## Local repro evidence

A matching-logic probe reproduced these baseline outputs: `$7.901 → 7.90`, `$10000.00 → 1000`, `$1,234.56 → 1,23`, `-$7.90 → 7.90`, `Estimated 20-30 min → 30`, and `100000 × 99999 → 1409965408` using int arithmetic. A dependency-free probe of the revised production core passed 37 assertions before Android integration. These are logic checks, not device tests.

## Verification design

The release gate runs the retained standalone rule/control/acceptance/confirmation cases, updated explicit-evidence add-on cases, new adversarial parser/state/transport tests, and Robolectric Android API 35 adapter tests. Adapter cases cover the real user notification shape, quiet review configuration, denied permissions, independent replacement lifecycles, real retry-job creation, immediate pause and diagnostic expiration/reporting.

`tools/verify_channel.py` compiles the actual production UpdateTransport/UpdatePolicy code, downloads the unauthenticated live feed/APK, checks exact bytes/SHA, and invokes Android SDK tools to verify signer and embedded package/version. `verification.json` states the live version this probe reached. A same-source post-publication redeploy can verify the just-published version while preserving identical APK/feed bytes.

Do not claim physical installation, real vibration/sound behavior, actual background scheduler timing, server-confirmed rejection, or all DoorDash screen variants from these tests. The public test receipt explicitly marks device-install and physical-sound verification false.

## Residual risks

- Complete quiet background filtering is unavailable when notification evidence lacks the enabled-rule fields. Quiet review cards can be missed.
- The app cannot cancel media playback/vibration generated directly inside Dasher, and a channel name is not proof of silence. Actual channel properties are logged; audio-source attribution remains unverified.
- No authoritative remote order ID exists in the observed payload. Conservative per-notification state reduces, but cannot prove away, every same-key/reordered-offer race.
- Accessibility is observational and may miss external acceptance, transitions, or changed screen layouts. Unknown route facts remain unknown. Manual route reset is available.
- The original historical signing experiments remain in private git history even if paths were deleted. This audit does not rewrite history or change the current signer; current signing material is not added to source or public artifacts. Restrict repository/Render access and maintain controlled signer backup.
- Android controls installation confirmation, notification settings, and scheduling. A unit-test pass is not proof that a user's device has granted permissions or applied an APK.

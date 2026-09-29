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

## Follow-up review — 0.4.6

Reviewed baseline: 4efa90d (0.4.5 / versionCode 11), which the live-channel probe confirmed is the published build. Every finding below was first reproduced by a failing Robolectric or JUnit test against unmodified 0.4.5.

1. **Deep sleep could drop a new offer (high).** Tracked notifications expire through a Handler callback on uptime, which pauses in deep sleep. A fresh post on a reused key whose incarnation had expired by elapsed time, but whose callback had not yet run, hit `expired → remove → return`. The new offer got no bell and no review card, and DoorDash's own offer channel is expected to be Silent. Repair: purge expired entries by elapsed time before every lookup, so the post starts a new incarnation exactly as it does on an awake phone.
2. **Reconnect cleared nothing (medium).** `OfferAlerts.clear(Context)` cancelled `(null tag, 8241)`, but every card is tagged. Cards from a killed process stayed beside replayed duplicates for up to 90 s. Repair: cancel every active notification with the alert ID, whatever its tag.
3. **Android 15 edge-to-edge (medium, UI).** targetSdk 35 makes the window edge to edge on Android 15, and the settings page did not apply insets, so its title sat under the status bar and focused fields could sit under the keyboard. Repair: pad for system bars, cutout and IME on API 35+, with light system-bar appearance.
4. **Add-on over-review (low).** Explicit added pay was required even when no enabled rule priced the add-on's own travel (flat and/or stop-ceiling rules only), which contradicts "zero disables a rule". Repair: require it only when a per-mile, per-minute, extra-stop or rising-payout rule is enabled. An independent differential fuzz of old against new classes (500k inputs) found this to be the only decision change. It also caught that an earlier draft let a rising-only add-on with unknown pay pass; that case is REVIEW and has a test. Unknown values that an enabled rule needs still yield REVIEW.

Hygiene: the app no longer trusts GitHub download hosts, and the retired publishing scripts are gone. Dead code (`hasRecentPendingWake`, `screenResolved`, `cancelPendingFiltered`, `isIdleScreen`, the unused `sourceWhen` and `shown` parameters, and write-only preferences) was removed, and `hasAccess` now feeds the diagnostics report. All sources were rewritten for readability. A literal-by-literal comparison confirmed that every parsing expression is unchanged, and test assertion counts per file were compared before and after the rewrite.

Residual: the first update to a still-present DoorDash notification 90 s or more after it was first seen starts a new incarnation and may ring once more if it passes. An awake 0.4.5 phone behaved the same way, and live offers expire well before 90 s.

Checks run for this review: `testDebugUnitTest` (128 tests, 0 failures/skips, including Robolectric API 26 and 35 adapters); `lintDebug` (no NewApi or error-severity issues against minSdk 26); unsigned aapt2/javac/d8/zipalign packaging reporting `versionCode='12' versionName='0.4.6'`; and `tools/verify_channel.py` against the live 0.4.5 feed using the new `UpdatePolicy`/`UpdateTransport`. Not established: the signed 0.4.6 build, which requires the Render-held key; installation on a physical handset; and real DoorDash audio/haptic behavior.

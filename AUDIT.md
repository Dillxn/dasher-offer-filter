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

## False-decline review — 0.4.7

Trigger: the user saw a valid offer declined on 0.4.6. No diagnostics report existed, and the app kept no decision history, so the cause could not be read from evidence. The review therefore looked for every code path that could decline a keepable offer, and reproduced each one against 0.4.6 with a failing test before fixing it:

1. **Label lookahead read a rate as pay (high).** In `OfferParser.parsePay`, a label line without an amount took the next line's sole amount without checking whether that line was a rate. `$30.00 / Guaranteed / $12.00/hr` parsed as $12.00 pay. Repair: the lookahead skips rate and `+$` lines.
2. **Label lookahead read an increment as pay (high).** `$9.90 / Guaranteed (incl. tips) / +$2.00 Peak Pay` parsed as $2.00. Repair: the lookahead skips increments, so two unreconciled figures mean pay is unknown (REVIEW).
3. **Confirmation authority reached the next offer (high).**
   - **Defect:** after a decline tap, any Dasher screen within 10 s that had a Decline button and either a Back/Cancel label or no readable Accept was treated as the confirmation dialog, and its Decline was tapped. A next offer mid-render, or one with a Back button, matches that shape.
   - **Repair:** a surface needs a prompt or decline+cancel; a surface whose parsed facts contradict the declined offer revokes the authority and is evaluated as an offer; the authority ends when the tapped confirmation disappears.
   - **Still tapped:** the legitimate confirmation, whether in its own window or overlaying the declined offer (both covered by tests).
4. **Notification-access shortcut (low).** `EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME` was passed as a `ComponentName` rather than the flattened string the settings page reads. Repair: pass `flattenToString()`.

Second pass (independent adversarial review of the draft, each finding reproduced first):
- **Increment standing in for pay (high).** "+$2.00" was still counted as a pay figure when no other amount was read. Increments are now never pay. An unlabeled total shown next to one is ambiguous, so the offer is REVIEW.
- **Next offer with Accept but no pay drawn (medium).** A surface that has an Accept control must positively agree with the declined offer to be its dialog. Delivery screens end the authority. Revocation now ends only the confirmation window, and keeps the 4-attempt cap on re-tapping the same offer.
- **Offer screens with Back/Cancel never evaluated (medium, pre-existing).** Without a pending confirmation, distinct Accept and Decline targets with no prompt make an offer screen.
- **Legitimate confirmation missed (medium).** There are more back-out and prompt wordings. The dialog's button is the last Decline in tree order, not the one with the "best" label. The authority ends one second after the confirmation tap, not after a single missed read.
- **Privacy, size, threading.**
  - Evidence no longer matches phone numbers or bare hours.
  - The report is capped at 60,000 characters, within intent limits.
  - History JSON is built on the writer thread.
  - The status button is restyled only when the state changes.
- **Add-on acceptance.** An add-on's visible pay for acceptance tracking is its explicit increment, so accepting an add-on still updates the route.

Observability: an always-on local decision history now records each decision's figures, requirement, reason, action and paused state. It also keeps only the evidence lines that carried a figure or a pay label, and it goes into every diagnostics report. The report is capped at 60,000 characters and can be emailed to the user's own address in two taps (the user presses Send), which satisfies "unless the user explicitly shares a diagnostic report".

Residual: all three false-decline repairs depend on DoorDash wording and layout that no real-screen capture has confirmed. Pause/Resume and the new screen were exercised in Robolectric and rendered for review, not on a handset.


## Follow-up review — 0.4.9 (reports, fixer, takeover, ring)

Scope: a new outbound data path, an automated release path, and two changes to the decline flow. An independent adversarial review of the first draft found the issues marked *(review)*; each was fixed and, where marked, reproduced first.

- **Consent and scope.** Nothing leaves the phone until the user saves a token. The only destination is this private repository's issues endpoint, over HTTPS without redirects, and the response is capped at 256 KB. Turning reports off stops sending before the next report, discards queued reports and cancels the job. A rejected token keeps the queue and says so.
  - An outage (5xx, 408) or a rate limit (429, or 403 with rate-limit headers) keeps everything and retries with backoff. *(review: 5xx and 429 used to drop reports; reproduced against a fake server.)*
  - Only a refused report such as 422 is dropped.
- **Masking.** Every screen line in a report has each word that is not offer vocabulary masked to its shape, and so does every run of four or more digits (ZIP codes, house and phone numbers). Offer figures and money stay. Exception messages are masked too, and name-like words ("will", "may") were taken out of the vocabulary. *(Second review.)* This covers the labels, the decision's evidence and the recent decisions. Reports are read by Claude in the fixer, so they reach Anthropic as well as GitHub, and the app and README say so. *(review: automatic reports used to carry full screen text.)*
- **Volume.** Automatic reports are deduplicated by reading gap or error site plus app version, the record survives restarts, and a repeat costs one lookup before any report is built. Caps: 10 automatic and 20 user reports a day by the phone's local day, and 30 queued. The fixer closes exact resends (the same body after a lost reply) as duplicates. A deliberate "oversized notification" refusal is not reported. *(review)*
- **Queue names.** The first Render build of 0.4.9 failed two report tests. Queued reports were named by millisecond plus problem signature, so two reports of one problem in the same clock tick overwrote each other. Render's build machine has a coarse clock; so may some phones. Reproduced with a pinned clock (5 reports became 1); names now add a sequence number, and the whole suite passes under a frozen clock.
- **Robustness.** A failure while queuing on the background thread is caught, where it used to be able to crash the app, screen reader included. The report job always calls `jobFinished`. *(review)*
- **Unknown stays REVIEW.** Filing a report never changes a decision or taps anything.
- **Fixer containment.** *(review: in the first draft Claude could reach a write token through Gradle, and the gate could be bypassed by renames or by tests that merely failed to compile; both bypasses were reproduced.)*
  - **Diagnose job:** only a read-only token (no `id-token`, so the action cannot mint its App token). Claude's tools are limited to read/edit, Gradle and local git, and its commit leaves only as a git bundle. Neither this job nor the gate saves build caches that later runs would restore, and a session that did not finish ships nothing. *(Second review.)*
  - **Gate job:** read-only, on a fresh machine. It applies that one commit to main and runs the gate as it was on main. The gate diffs with `--no-renames`, forbids the updater, requires the changed test classes to compile and fail on the old code, then the full suite, lint and packaging.
  - **Ship job:** checks that the bundle is exactly the gated commit, pushes it, and verifies the live APK with the base's own code. It never checks out or runs the fix.
  - **Runs:** serialized; cancelling a run pushes nothing. A report left marked `fixer:working` by a run that died is picked up again.
  - **The gate script** is read in full before it runs, so the fix's tests cannot rewrite its remaining checks. The checks still run on the same machine as that code, so they guard against mistakes, not against a deliberately hostile fix. Render's own build and tests are the backstop.
- **Touch takeover.** A 1×1 `TYPE_ACCESSIBILITY_OVERLAY` watching outside touches exists only while a decline is pending; the touch is handled after the event, since handling it removes that window.
  - A touch revokes confirmation authority and restores sound.
  - It blocks declining any frame that does not contradict the taken-over offer, on screen or through its notification, until idle, a delivery screen, a contradicting offer, or two minutes. *(Second review: the first version forgot the takeover on a half-drawn frame and declined again; reproduced.)*
  - Accessibility clicks are not touches, so the app's own taps cannot trigger it.
  - Per AOSP input dispatch, ACTION_OUTSIDE reaches watch-outside windows above the touched one on the first finger down. Manufacturer builds are unverified.
- **Ring handling.** Only the media stream (ADJUST_MUTE) and the alarm stream (to its minimum) are turned down.
  - **When:** only while the declined offer or its own confirmation is the latest read and no newer offer notification has arrived, and only when a player other than navigation, calls or the assistant runs on them. *(Second review: sound used to stay down over an unreadable next offer; reproduced. The ringer, notification and system streams were dropped entirely, since muting them flips ringer mode.)*
  - **Calls:** a mode other than `MODE_NORMAL` restores everything at once.
  - **Restoring:** previous levels, and for the alarm the level actually set, are committed before any change. They are restored when the decline ends, on a touch, on a passing alert, after 20 s, when the service stops, and at the next start or app open after a crash. An alarm level the user changed meanwhile is left alone.
  - **Side effect:** the player's app is not visible to other apps, so music playing then is quieted too.
- **Stuck declines.** A declined offer or its confirmation still showing 5 s after the first tap is reported once, with its masked screen lines.
- **Residual risk.**
  - Report text reaches the model; screen text crafted to steer it is contained by the read-only token and the gate, not prevented.
  - The diagnose job does hold the Claude credential, so a steered session could leak it through Gradle's network access.
  - Vibration cannot be stopped by another app.
  - Whether ACTION_OUTSIDE reaches the overlay for every touch, and which stream Dasher rings on, are device behavior the simulated tests cannot establish.
  - Actions minutes and Claude usage are spent per report, bounded by the caps.

## Follow-up — 0.4.10 (adaptive rates, drawn screen)

- **Adaptive rates.** Best accepted pay per minute, per mile and per stop are kept as the (pay, amount) pair that set them, so comparisons and requirements use exact integer or BigDecimal arithmetic with ceiling rounding.
  - They apply only while the adaptive minimum is on, each as its own floor. They never apply to add-ons.
  - A missing amount a floor needs gives REVIEW unless another known floor already fails.
  - Bests are learned only while auto-decline and the adaptive minimum are both on.
  - An offer with any misread-looking figure (under 5 min, 0.5 mi or 2 stops) sets none.
  - Only trips of at least 2 mi and 10 min set the per-mile and per-minute bests.
  - Saving rules never touches them; Reset clears them. Corrupt stored values read as none.
  - *(Review: the first draft let a $7.50, 0.6 mi trip set $12.50/mi, so a normal $15, 5 mi offer "needed" $62.50. It also learned while off, and set a per-stop best from partial misreads. All were reproduced by tests and fixed.)*
- **Drawn views.** The drawn views reuse OfferRule for totals: the meter evaluates the example offer with a very large pay to read the requirement. Layouts come from font metrics, with fitting, stacking and wrapping at large font scales, and were checked at 1.3× and 2×. The hero's middle pile counts only offers the app acted on. They redraw only when what they show changes. Each has a content description for screen readers, and no meaning rests on color alone.

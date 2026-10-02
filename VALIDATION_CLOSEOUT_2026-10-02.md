# Eleven-study closeout: Offer Filter 0.4.54

Starting source: `bc7c0bc1a928ed09ca846007c5627195ea0fa1b0` (published 0.4.53, code 59). Released: **0.4.54, code 60**, package `com.local.dasherfilter`. Tested source: `f507d61bbcb63e9837d62322d5e74699ea296f1b`. Published with the original signing identity in release commit `9637e4efa94b215c02d65c26f44cc53496e1cc9a`. The recovered studies were research returns, not evidence that their interrupted implementations shipped.

## What changed

- Paused/quiet shifts hold automatic installation until a positive dash end. The current screen may go idle without granting install permission. Prepared updates avoid repeated downloads while held; normal feed/APK verification runs again before installation. Android's pending confirmation no longer leaves a blocking updating cover.
- Screen-off ends old tap authority and polling. Restart recovery can only suppress taps on the previous numeric offer, never restore automation authority. Scanner callback exceptions fail closed; Peek runtime failures pause Peek. Optional overlays become inert during window transitions. Actual launcher components are resolved explicitly.
- A proven fresh countdown creates a separate history instance even when all offer figures match. Takeover, confirmation, outcome counts and notification folding remain distinct. Paused/menu/end-dash questions cannot prematurely file a completed-dash report.
- Reports are built off the UI thread. Place names are capped at 300 rounded positions and Clear history invalidates in-flight names and queued old log writes. Atomic history writes recover a prior complete file. Timing floods yield to decision evidence; numeric loss counters expose missing evidence.
- Every issue/comment POST has a durable opaque receipt, including ordinary problem reports. Ambiguous replies reconcile receipts and remain pending instead of blindly posting again. Opt-out, queue admission and job cancellation are serialized around requests.
- Older-Android split views keep refreshing while visible. In-bounds app-dialog/legal touches use the own-half guard. Known floating/PiP windows avoid adjacent launches. Notification setup uses actual alert evidence, handles blocked channels and permanent permission denial, and sound restoration keeps failed recovery receipts.
- Selected tickets show learning steps and distinguish a score reference from strict-rule decisions. Fresh outcome evidence can correct the mascot. Calm drawings share frame boundaries; no new persistent dashboard controls were added.
- Both release paths reject conflicting version/source/byte identities. The retired full builder requires current remote main before trusting its checkout's feed; the active Render path remains a mirror of the signed GitHub APK.

The original learning policy and area compensation are preserved. The fifth spoke's scoring/control exists, but automatic final-stop-to-hotspot measurement remains unavailable. No guessed coordinates or hotspot substitute was added.

## Validation record

The first integration run compiled and ran 1,541 tests across 88 suites, exposing regressions that were corrected; it is not the release pass. A subsequent compile/lint pass identified two test-fixture compilation errors and one API30 compatibility error. A further 1,579-test pass found twelve remaining behavior/fixture failures. All were corrected before the final release gate.

**Final release gate: PASS.** `testDebugUnitTest -PallSdks lintDebug` completed with **1,579 tests across 90 suites, zero failures, errors or skips**, covering simulated Android 8/API26 and Android15/API35 (plus explicitly configured older split-lifecycle cases). Android lint completed with **zero errors and 34 warnings**. All 199 frozen build/test input hashes were unchanged through the run, aggregate SHA-256 `fe852e872be818964459d7410d199d3563bbab76cc8d6c9e242d7431271aa175`. The warnings include intentional durable preference commits and existing localization/style findings; they are not a claim of zero warnings.

The [machine-readable test receipt](validation/0.4.54-test-result.json) and [frozen input hashes](validation/0.4.54-test-inputs.json) are retained with the source.

The source gate above and the publication evidence below are separate checks; neither implies a handset install.

Four Python release-identity regressions passed, covering version advance, same-code source/byte conflicts, malformed/downgrade feeds and stale remote-main checkouts. A pre-release production `UpdateTransport` probe verified the existing live **0.4.53 / code 59** APK: 382,035 bytes, SHA-256 `548c08eb0504d390b2a03e971a2b0416c3618d7cf8f3b6c31a12aba32dd58f58`, original signer `553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703`.

## Publication and live update verification

The original-key APK was published in GitHub release commit `9637e4efa94b215c02d65c26f44cc53496e1cc9a`, built from tested source `f507d61bbcb63e9837d62322d5e74699ea296f1b`. Render deployment `dep-davvjh5g1s2s73c1h8ng` became live at `2026-10-02T18:37:26.916552Z` and mirrors that same APK.

A fresh download through the app's production `UpdateTransport.java` passed at `2026-10-02T18:38:25.834199+00:00`. The [machine-readable live receipt](validation/0.4.54-live-channel.json) records:

| Field | Verified value |
|---|---|
| Package/version | `com.local.dasherfilter`, 0.4.54 / code 60 |
| APK size | 398,419 bytes |
| APK SHA-256 | `821a8763576f9fe620b6ef17dc0e8651a9fbb43ec19ae0928e83c21c174baf12` |
| Original signer SHA-256 | `553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703` |
| Public update feed | `https://dash-offer-filter-build.onrender.com/latest.json` |

GitHub and Render now serve the signed 0.4.54 update. This verifies published metadata, downloaded bytes, package/version and signing identity; it does not establish installation on the user's phone or physical Dasher behavior. Subsequent fixes are tracked under their own release version.

## Boundaries of the evidence

`INVESTIGATION_CLOSEOUT.md` records all 277 unique findings from eleven original reports. Optional policy/telemetry proposals are explicitly deferred with reasons; they are not represented as implemented or as user decisions. Real Samsung/Android16 installation, physical touch/audio behavior, slow Dasher confirmation timing, map/task return, floating-window variants, filesystem power loss and real network interruption remain field checks. Simulated Android tests do not establish them.

This release does not delete previously sent diagnostics. Containment of the old issues and any payment-card action remain unverified external work. No old raw diagnostic fixture or payment credential was recovered into the repository.

Privacy wording reflects the bounded place cache, temporary numeric restart suppression, exception category and report receipts/counters. Consent notice version advances to **6**; the app must be opened once to accept it after updating. Automatic installation now waits through a paused/uncertain shift. It never wakes or unlocks the phone and never automatically accepts an offer.

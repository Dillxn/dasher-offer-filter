# Decline recovery hardening — 0.4.56

The disconnected continuation was recovered from the working source and reconciled with the original thread's published 0.4.55 at `6795dbae2519977e1cb62fe3bed519a355d717c0`. That release's evidence is preserved in [its validation record](VALIDATION_DECLINE_ERROR_2026-10-02.md). Additional adversarial regressions identified the timing and identity cases addressed here.

## Behavior

- Back recovery remains limited to a fresh exact Dasher error from this service's successful standalone first-step decline, a stable complete non-actionable empty/map screen, sole active focused foreground Dasher, and the original attempt/deadline budgets. No new control, automatic Accept or general loading-screen Back is introduced.
- Every recovered offer, including later retries, must match the original complete offer key and have a known continuing countdown. Changed stable labels, missing metrics and missing countdowns hand back without replenishing authority. Contradictory/fresh offers are evaluated separately.
- Exact error evidence is latched before scanner dispatch. Slow traversal/window reads cannot erase an already observed error. Incomplete reads reset blank-screen stability; error freshness and event/window counters are checked again after final metadata reads.
- Explicit failed actions stay unconfirmed through passive rereads and later idle evidence, without being relabeled as user touches. Confirmation errors invalidate optimistic completion, including error evidence observed before a deadline but consumed after it.
- Once the owned confirmation question appears, recovery lineage is cleared so the normal confirmation settling lifecycle resumes; the request's error latch remains available.
- Notice version remains 7, as introduced in 0.4.55. No additional notice or data flow is added; raw toast text is never retained.

## Verification

**Final release source gate: PASS.** `testDebugUnitTest -PallSdks lintDebug --offline --no-configuration-cache` passed **1,669 tests across 92 suites**, with **zero failures, errors or skips**. Android lint completed with **zero errors and 34 warnings**. All **207 frozen inputs** were unchanged. [Frozen input hashes](validation/0.4.56-test-inputs.json) and [test/lint receipt](validation/0.4.56-test-result.json) retain the exact evidence.

The suite includes simulated Android 8/API 26 and Android 15/API 35 adapters, including original deadline/Back/attempt bounds, partial facts and missing countdowns, changed stable labels, touch/app switches, errors during slow traversal/window reads, deadline-crossing failure evidence, late content/window changes before Back, and normal confirmation settling after recovery.

## Published release

Released **0.4.56 / code 62** through the existing GitHub and Render update feeds. Source commit: `97a4918d92adb1406cba17370e769556cdda8669`; release commit: `f9c9694f97ca179f42f28bb8b22d20f075097ebc`.

- Package: `com.local.dasherfilter`; signed APK: **402,515 bytes**.
- SHA-256: `d4148cb7eb895fdae1a93ed295a4eae90f8610ba7107c7fa65131079c94d8219`.
- Original signer: `553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703`.
- The original `tools/sign-local.sh` and `tools/publish-repo-feed.py` performed signing and publication checks. All 207 frozen source inputs remained unchanged after signing.
- GitHub main's feed matched the local signed release. Render deploy `dep-db00hlu7bikc73fneq9g` went live at `2026-10-02T19:41:45.423414Z`.
- A fresh production `UpdateTransport.java` download verified **0.4.56/code 62**, exact APK size/hash, embedded package/version and original signer. A rollout-time probe still saw 0.4.55; the final successful probe was after the new deployment became live.

Evidence: [signed-artifact receipt](validation/0.4.56-signed-artifact.json), [live-channel receipt](validation/0.4.56-live-channel.json). These receipts establish publication and downloaded APK identity, not handset installation.

## Limits

These are synthetic Java/Android adapter checks and release transport checks. They do not establish installation or recovery behavior on the owner's Samsung/Android 16 handset, real DoorDash server behavior, or OEM sound/haptic behavior. Clickable map containers, unrecognized toast implementations, and ambiguous idle/navigation evidence remain unconfirmed and are left to the user. Automatic final-stop-to-hotspot measurement and historical report containment remain outside this result.

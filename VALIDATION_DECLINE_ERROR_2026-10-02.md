# Decline error recovery — 0.4.55

The owner reported that tapping Decline can produce an error toast and leave Dasher on a dimmed map with no options. Manually pressing Back and retrying can recover it. This follow-up adds bounded recovery to the existing auto-decline path; it adds no persistent UI control and never accepts offers.

## Behavior under test

- A normalized exact `error` toast must come from Dasher's accessibility toast event and belong to a successful automatic decline request. Only a fixed error category is kept; raw toast text is not logged.
- Only an automatic standalone first-step decline qualifies for Back; manual and add-on declines do not. A full read must show a stable empty/map-labelled screen with no enabled click target. Dasher must be the sole, active, focused and uncovered foreground app. A confirmation, unknown controls, split mode, keyboard, navigation or ambiguous idle labels cannot authorize Back.
- Back is requested at most once per failed request and twice per original offer. The original deadline and decline-attempt budget remain in force. A fresh read must identify the same offer before the normal retry can proceed.
- Touches, changed minimum scale, a new offer/countdown, lock, interruption or another foreground app revoke recovery. A blank screen without the matching error event never authorizes Back.
- An error following a confirmation request leaves that decline unconfirmed, cancels Peek return and prevents optimistic late-completion correction. Back itself never counts as a completed decline.

The notice is version 7 because Back recovery changes the described automation. Existing installs must accept the updated in-app notice once before filtering resumes.

## Verification

The final full gate, `./gradlew -q testDebugUnitTest -PallSdks lintDebug --continue`, passed: **1,631 tests in 92 suites; zero failures, errors or skips; zero lint errors and 34 warnings**. It includes simulated Android 8/API 26 and Android 15/API 35 adapters; selected existing split tests also specify API 28. The focused run passed 169 tests in 10 suites. Four release-identity Python regressions also passed.

All 202 frozen build/test inputs remained unchanged throughout the final gate. Aggregate SHA-256: `8350e7d252bdd32d10fcdb39b11f789e044b550ab36c22034c77b7931ceda1c9`. Evidence: [input hashes](validation/0.4.55-inputs.json), [full result](validation/0.4.55-tests.json), [focused result](validation/0.4.55-focused-tests.json). The earlier full run was canceled for the discovered retry-budget issue and is not the release gate.

## Published release

Released **0.4.55 / code 61** through the existing auto-update feeds. Source commit: `74ce3111963dc8daa2b140d844c62b40695fb08a`; release commit: `108393ef901291fa28dda18aa2fe0d189c4d63cc`.

- Package: `com.local.dasherfilter`; APK: **402,515 bytes**.
- SHA-256: `edd0a73266e68c301239f07f6b9f326230295ea834575a74059988481dec9328`.
- Original signer: `553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703`.
- GitHub's published manifest matches the signed release. Render deploy `dep-db003j67bikc73fm06b0` went live at `2026-10-02T19:11:41.128742Z`.
- Fresh production `UpdateTransport.java` download verification passed at `2026-10-02T19:12:03.129175+00:00`: exact size/hash, embedded package/version and original signer. [Machine-readable live receipt](validation/0.4.55-live-channel.json).

This verifies publication and the downloaded APK, not installation or real Dasher behavior on the owner's handset.

The focused review also checked two failure paths: passive rereads after a refused Back must preserve the unconfirmed result instead of claiming a user touch, and changing display labels must not reset a recovered offer’s deadline or retry budget. These are regression requirements, not evidence of a successful real-phone decline.

## Limits

The exact physical Samsung/Android 16 failure has not been reproduced here. Synthetic Android adapters exercise the service's toast delivery and global-action paths, not the real DoorDash server or OEM UI. Recovery requires the accessibility representation described above: a clickable map container, a different toast implementation, or ambiguous idle/navigation labels remain unconfirmed and are left to the user. No claim is made that every DoorDash error is recoverable.

The [eleven-study closeout](INVESTIGATION_CLOSEOUT.md) and [live 0.4.54 receipt](validation/0.4.54-live-channel.json) remain separate evidence. Automatic final-stop-to-hotspot measurement, real-phone acceptance checks and historical report containment are still outside the verified result.

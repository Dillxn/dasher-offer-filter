# Passing-offer chime — 0.4.58 candidate

Validated October 3, 2026 UTC from the continuation branch based on `0cf0a32c5bb85e424fdcf2899c07bb8327df8137`.

## Behavior boundary

- A proven passing offer posts on `qualifying_offers_v2`, whose sound is the bundled `offer_pass_chime.ogg`.
- The old `qualifying_offers` channel is retired because Android does not replace the sound of a channel already created on the phone.
- Offer Filter requests no sound for a rejected offer. REVIEW retains the distinct `offers_to_check_v2` channel and its existing one-alert rules.
- This does not claim to suppress DoorDash's native first alert. A notification listener can observe the post only after Android may already have sounded it.
- Failed-decline recovery was not broadened: the exact observed toast remains in the request-bound path, and global Back remains prohibited in split screen.

## Evidence

- Exact-source `testDebugUnitTest -PallSdks`: **1,774 tests in 98 suites**, zero failures, errors or skips, simulating Android 8/API 26 and Android 15/API 35.
- Android lint: **zero errors and 34 warnings**.
- Focused alert/readiness and six-spoke shopping tests passed before the full run. The adapter asserts that the passing channel resolves the bundled resource, differs from the ordinary notification sound and deletes the retired channel.
- `offer_pass_chime.ogg`: Ogg/Vorbis, mono, 48 kHz, 1.4 seconds, 8,669 bytes; full decode passed; SHA-256 `c439a56c18f1b0cf3b3709e4114c0fe1ea132bd75ffa8cd84e786126dcd29712`.
- Unsigned release assembly completed. `aapt` identifies package `com.local.dasherfilter`, versionName `0.4.58`, versionCode `64`, minimum API 26 and target API 35.

The assembled APK is intentionally unsigned and fails signature verification. It was not installed, uploaded or published. Original-signer signing, release-feed publication, production updater verification and physical-phone listening remain required before a shipped claim.

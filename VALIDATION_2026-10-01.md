# October 1 continuation validation

Candidate: **0.4.50 / versionCode 56**, package `com.local.dasherfilter`. This is tested source, **not a shipped upgrade**.

## Provenance

Migration `dasher-handoff-20261001` supplied the recovered Claude conversation, decisions, interrupted edits and visual draft. The archive opened and its supplied checksums verified. GitHub main was freshly checked at `5d76ec631a9f8002f409feab8c5afc64de6997a8`; its complete source tree was recovered with matching Git object hashes. Unfinished drafts were reconciled in privacy, core decline, then Peek order. Raw diagnostic reports were not retrieved or imported as fixtures.

The user's existing learning floors, area-score tradeoff, Offer Filter name, default-on Peek choice and no-accept behavior remain. Consent version 4 describes the combined privacy and Peek changes; bundled legal text matches the source documents.

## Checks completed

| Check | Result |
| --- | --- |
| Java/JUnit and Robolectric, Android API 26 and 35 | **1,253 tests, 68 suites; 0 failures, errors or skips** |
| Android lint | **0 errors, 32 warnings** |
| Unsigned release packaging | Correct package, versionCode 56, versionName 0.4.50 |
| Live update transport | Existing **0.4.49 / code 55** APK verified using production `UpdateTransport`, exact size/hash, package/version and cloud signer |

Final tests ran with `testDebugUnitTest -PallSdks`. Lint and `assembleRelease` ran separately. The constrained execution workspace used one Gradle worker and a 1 GiB daemon heap; no product build settings or release gates were weakened. An earlier combined runner was killed before completing tests and is not counted as a pass.

The tested `app/` Git tree is `8161b438d2146dc1f22d74e99b08a61827f94f2c`. The unsigned APK is 341,084 bytes, SHA-256 `e51d087a843fa71854c7c36b5cd0ecab9a82ab8adcdaab8b3f5e3ff16636a5c8`. It is not offered for installation.

The verified live 0.4.49 APK is 324,691 bytes, SHA-256 `c78a77bb0fcb4f3035e79ea7481fc67a734921d6803cff738cf7a30af259a9e1`, built from source `4859d7c908f0b677b33a39eff588c5353b7191d4`. It is the older release, not evidence that these fixes are installed.

## Remaining release gates

- Build with the existing cloud key through `tools/sign-local.sh`. That key was not transferred into this environment. No replacement key was generated and no update verification was bypassed.
- Publish through `tools/publish-repo-feed.py`, keep the separate release commit, push the completed source/release together, and verify the resulting live channel. `release/` and the live feed remain unchanged in this candidate.
- Verify an upgrade on the real Samsung/Android 16 phone: notice once, old-log cleanup, late confirmation at 7–10 seconds, same-offer notifications, fresh-countdown takeover, add-on handling, and sound restoration.
- Verify Peek on-device: quiet wait and typing/touch cancellation; Maps/Waze return and observed-figure cards; lock, calls, split/freeform/pinned/unknown-app exclusions; return without resetting the previous task. Android/vendor behavior is not established by Robolectric.
- Historical reports already sent to GitHub are not removed by client cleanup. Their containment remains unverified. Keep automatic diagnostics off on the older installed build until the fixed upgrade is installed.

Durable receipts prevent repeating an uncertain issue-creation request; uncertainty can leave a report pending. A lost response to a comment can still repeat a part within the same issue.

## Separate film and site delivery

The recovered animatic was developed into newly rendered 18.5-second, 30 fps films with original sound: portrait, square and landscape in MP4 and WebM. All six exports fully decoded; selected frames were visually inspected. They are marketing drafts using invented offers, not recordings of verified device behavior.

The illustrated [private site preview](https://offer-filter-illustrated.jesuslovesyou0808.chatgpt.site) hosts the playable film and the user's Cash App/Venmo tip links. Its deployment succeeded. Static assets and script syntax were checked; a full live-browser/device review is not claimed. The APK download remains gated pending the signed upgrade. Public/open-source launch is still conditional on release readiness.

# Offer Filter 0.4.53 validation

Version 0.4.53 / versionCode 59, package `com.local.dasherfilter`. This release responds to the request for a less visually busy interface.

## Changes

- The constellation displays only the latest or explicitly selected offer. History remains in the skyline. Drawing, touch hit tests, scores and accessibility nodes share the same selected identity; an unplottable latest offer cannot silently substitute an older one.
- The grid and offer fill are quieter; the central duplicate score is removed. The existing percentage control says Mins; axis meanings, saved/learned floors, learning controls and threshold adjustment remain.
- Skyline buildings use muted colors and small outcome symbols; the selected offer retains a full badge. Payout heights, fitness trees and both threshold lines remain. Screen-reader Previous/Next actions browse history, and click opens the selected ticket.
- The atlas uses one short heading and its existing info popup for the complete key. The You label avoids badges and place names; a consistent data gutter prevents plotted cells from landing under the info control. Selected areas show a place or bearing, rate and eligible sample count.
- Scoring, learning, privacy, decline automation, Peek, consent and updater behavior are unchanged.

## Verification

- `testDebugUnitTest -PallSdks`: **1,431 tests across 80 suites, 0 failures, errors or skips**, covering Java/JUnit and simulated Android 8/API 26 and Android 15/API 35.
- `lintDebug`: **0 errors, 32 warnings**.
- Focused interaction checks cover hidden history hit targets, latest unplottable offers, skyline screen-reader history, selected versus quiet outcome symbols, and map data/help separation.
- `tools/sign-local.sh` signed the exact app source with the existing cloud key; `tools/publish-repo-feed.py` verified the APK and wrote the repository feed. Source commit: `b88940191ed669e936a07b5489e7bba565638e75`; separate release commit: `cba91b2af00e2a8ab26a9b10327ca6b93a050897`.
- GitHub and Render now serve **0.4.53 / 59**, **382,035 bytes**, SHA-256 `548c08eb0504d390b2a03e971a2b0416c3618d7cf8f3b6c31a12aba32dd58f58`. Render deployment `dep-daviu88u01pc73eut4g0` became live on October 2 UTC.
- A fresh `tools/verify_channel.py` download using production `UpdateTransport.java` passed package, embedded version, size, SHA-256 and original signer checks. Signer: `553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703`. This verifies the published **0.4.53** APK, not an actual handset installation.

Native Android graphics renders were inspected in day, night and short layouts using synthetic data. The 97% boundary example comes from the actual rule evaluator. These are simulated Android views, not physical-phone screenshots. Setup warnings in the fixture reflect its disabled accessibility/notification permissions.

## Remaining limits

No physical handset installation or real Dasher interaction is claimed by these UI checks. The fifth-spoke measurement source is still unavailable and defaults off; no hotspot geometry was inferred. Earlier unresolved historical-report containment and physical-device decline/Peek questions remain unchanged.

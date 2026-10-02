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
- Source is ready for the existing signing and publication protocol. The signed APK and live-feed result are recorded in the separate release commit; these test results do not by themselves claim publication.

Native Android graphics renders were inspected in day, night and short layouts using synthetic data. The 97% boundary example comes from the actual rule evaluator. These are simulated Android views, not physical-phone screenshots. Setup warnings in the fixture reflect its disabled accessibility/notification permissions.

## Remaining limits

No physical handset installation or real Dasher interaction is claimed by these UI checks. The fifth-spoke measurement source is still unavailable and defaults off; no hotspot geometry was inferred. Earlier unresolved historical-report containment and physical-device decline/Peek questions remain unchanged.

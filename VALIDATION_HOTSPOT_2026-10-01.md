# Fifth-spoke candidate validation

Candidate: **0.4.51 / versionCode 57**, package `com.local.dasherfilter`. This is tested source, **not a shipped upgrade or a working automatic hotspot reader**.

## Scope

The new top spoke is `1 / miles from the offer's final stop to the nearest actual Dasher hotspot`. A saved minimum of 0.50/mi means no farther than 2 miles in strict mode. Area mode uses this independent ratio in the compensated shape. It does not turn proximity into a pay floor or alter the user's four existing learned floors.

Current production source has no verified final-stop/hotspot geometry. The rule defaults off; enabling it explains that the distance is unavailable. Unknown remains unplotted and requires review when needed. All known distances in the new tests and visual previews are synthetic. See [the measurement contract and remaining acquisition work](HOTSPOT_SPOKE.md).

## Checks completed

| Check | Result |
| --- | --- |
| Full Java/JUnit and simulated Android API 26/35 suite | **1,310 tests, 73 suites; 0 failures, errors or skips** |
| Android lint | **0 errors, 32 warnings** |
| Unsigned release packaging | Correct package, versionCode 57, versionName 0.4.51 |
| Native Android API 35 graphics previews | Phone and short split-screen layouts rendered and visually inspected |

The final full run used `testDebugUnitTest -PallSdks`, one worker and a 1 GiB Gradle daemon heap, and completed in 88.71 seconds. No product release gate was relaxed. Tests cover all 31 active-spoke subsets, exact strict boundaries, area compensation and required pay, known zero versus unknown, add-on endpoint isolation, settings/history persistence, accessibility adjustment, vertical drag ownership, old four-spoke results, and hotspot-direction exclusions from offer mileage/time parsing.

The new upright spoke makes available height constrain short windows. Legacy layout expectations were updated to require the full top spoke and clear controls inside that height; existing text, mascot, icon, tap and drag checks remain. Native previews use invented offer facts and do not establish real-phone behavior.

An intermediate full run caught the badge/icon separation and one unrelated timeout in an updater test's setup. The placement was fixed; the isolated updater test passed all 14 cases, and the final full run above passed without skips or extended timeouts.

The tested `app/` Git tree is `ad7f5e6d7b1a82f2707cceb2f36160ae28171ea7`. The unsigned APK is 347,260 bytes, SHA-256 `227a5f50fe07f597d157cf461f00379b89e993de91dd29653c223b2941ae9d8d`. It is not offered for installation.

## Remaining gates

- Establish a privacy-safe, current Dasher offer/map observation that identifies the final stop, actual hotspots, distance geometry and freshness. Do not substitute phone location, route mileage, store position or learned offer-area squares. Update same-offer context/history handling before connecting a live measurement producer.
- Use the existing cloud signing credentials and the separate source/release commit procedure in `AGENTS.md`. The credentials are unavailable in this environment; no replacement signer was generated. `release/` and main remain unchanged.
- Verify the real Samsung/Android 16 upgrade and all prior privacy, decline and Peek release gates in [the earlier continuation validation](VALIDATION_2026-10-01.md). Historical diagnostic-issue containment is still unverified.

No live feed check is claimed for 0.4.51. The earlier record's production transport check concerned the published 0.4.49 release. The separate film/site assets were not changed by this fifth-spoke work.

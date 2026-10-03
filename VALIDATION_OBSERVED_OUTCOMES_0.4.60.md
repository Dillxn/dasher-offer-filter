# Offer Filter 0.4.60 — local release validation

Source `140cf0ed2be3844bbe8005754d6ff79e249b7d94` (tree `184ff82bb117ac9cadb922bb70c5731d2e5fc7e5`) was built, tested, packaged and signed locally. Release-only commit `10b90c5b6344c3cb1ce98a1cfb7731ada29a311b` published its signed APK through the existing automatic updater.

- 1,945 tests / 107 suites passed, with zero failures/errors/skips. Android adapter cases cover simulated API 26 and 35. Lint: zero errors, 34 warnings.
- 298 inputs frozen for the successful full gate; SHA-256 aggregate `5d911dcad9459d99f759f1f220a748580c83bbcf1c0c87aaa4a42095769c89b9`. Inputs remained unchanged through the gate. After signing/publication only the two publisher-generated release inputs differed. Matching path/hash manifest and structured result are in `validation/0.4.60-local-signed-*.json`.
- APK: 435,357 bytes; SHA-256 `e52b4e857fa9130c9f91c27378fb2c1de4ca256175d150d1b1fdcfef03d0e562`. Original signer `553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703` verified independently by the signing script, publisher and live probe. Temporary private exports were removed; no signer changed or entered source.
- Render deployment `dep-db0n0qlg1s2s73eq70cg` copied finished GitHub artifacts and became live October 3, 21:15:56 UTC. A fresh production UpdateTransport/UpdatePolicy download verified .60/code66, exact bytes/hash, package/version and certificate. Fresh private GitHub manifest matched too. No Android build/signing ran on Render or GitHub Actions.

## Concrete regression evidence

Four automatic-Accept scenarios failed on the earlier service and passed after the fix: generic Directions after a request; idle followed by unrelated delivery; a delivery marker behind remaining offer controls; and Android refusing the request followed by a generic route. Timeout and repeated same-offer reads cannot restore manual inference or issue another click. Actual automatic confirmation does not claim a human tapped Accept. Automatic requests still do not train adaptive floors.

Quieting regressions establish that a previous offer's pass/review sound no longer blocks a different decline for 20 seconds. A newer alert still ends protection for the older decline appropriately; its original tap time persists across decline retries.

Wait coverage tests include clipping at the 24-hour boundary, censored intervals, restart/clear, clock discontinuity, partial offers later identified as add-ons, unreadable item/hotspot facts and reevaluation against current learned floors/strict/area/buffer. No historical gaps are backfilled as waiting. Theme tests cover sunrise/night/polar/date-line cases, current permitted approximate location, clock fallback, system configuration and preserved explicit day/night choices.

The initial integration gate caught a large-font overflow and an ETA touch mask covering the shopping-item point in split screen. The final ETA owns its own row above the skyline and yields to setup warnings. Native split checks use both a ready 12-minute estimate and the full unreadable message; all six labels/controls and the selected item point remain available. Day/night rendering fixtures now select their palette explicitly; wait-store fixtures advance monotonic and wall clocks coherently. No assertions were weakened. The saved [split preview](validation/0.4.60-split-wait.png) uses invented test numbers, not a phone screenshot.

## Remaining limits

This is simulated Android and published-byte evidence, not physical-phone proof. The user's actual failed automatic tap, historical .56 in-app map problem, real sound timing and estimator coverage remain field questions. No new time-aligned issue accompanied those reports through the October 3, 20:54 UTC audit. The map code audit found no established cause or existing fix; the small delivery edge tab is only an unproven touch-interception surface. Issue #38 remains unresolved; #39 lacks authority/time alignment. No global Back is allowed in split screen.

Hotspot measurement is still unavailable. Pay/item cannot infer a missing total from a shopping label. The selected readout no longer borrows an older payout. Existing scoring and upward learning remain unchanged; see [the model review](MODEL_REVIEW_0.4.60.md) for adjacency, correlated metrics and unbounded reciprocal-distance concerns.

Consent 11 requires the updated notice once before filtering resumes, retaining acceptance-rate/own-risk and separate default-off auto-accept confirmation. It discloses bounded local wait records and offline solar theme. Settings still ends Jesus Loves You.

## Public site

Separate public `Dillxn/offer-filter-site`: source `f4e9c160cceda4a25ae72870aa1d4b9b6d8cc16d`, checkpoint `393e5b1f9199fd7a3249d9d2a901d4e882cd6444`, successful Pages run `37152526587`. Header mascot, one wide hero film with visible poster, page signature and all four film closing signatures are live. Original clean music packet bytes were preserved; no breathing audio returned. Live landscape playback reached 18.5 seconds, ended, no error. Current 520px browser viewport had no overflow/overlap; exact400/1280 verification was unavailable and is not claimed. Public SITE_VALIDATION.md and film receipt preserve evidence. This remains an original marketing draft, not final pdoom-level polish. Private reports remain private.

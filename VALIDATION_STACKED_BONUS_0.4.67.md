# Offer Filter 0.4.67 validation

## Findings

Private .66 reports #57–58 retain a readable total beside a bare +$1 badge, three route stops, two pickup orders and two dropoff stops. The two-stop-only ceiling guard and blanket breakdown rejection prevented a decision. The screenshot's distinct $13 / 7 miles / 33 minutes offer shares that shape but is not itself present in those reports.

The new ceiling allows the bonus on each explicitly observed order, requiring corroborating dropoff and route totals. The screenshot ceiling is $15; report ceilings are $13 and $13.85. Each fails the reports' fixed compensating-area rules even at that favorable ceiling. Exact pay remains unknown, with no plotted hypothetical score, Auto-accept authority, notification ceiling or learning. A changed or malformed read cannot reuse a smaller ceiling or erase observed uncertainty.

The mascot now has a maximum 36 dp ring radius and sits 6 dp below the header/counts and 6 dp from the left edge. Native-render checks cover a Samsung-like split, full phone, narrow width and 2× font, plus the pause action.

## Local checks

- New parser regression: old source failed 5 of 7 tests; updated parser/rule/acceptance suite passed 102 tests.
- Old service failed the fresh-larger-bonus and qualifier regressions. Updated coverage also checks changing stack counts, disappearing labels, lost route figures and persistent uncertainty.
- Focused mascot/scene interaction checks: 65 tests passed.
- Combined gate: 2105 tests in 114 suites; zero failures, errors or skips; Android API 26 and 35. Lint zero errors, 34 warnings.
- Original-signer frozen-source build and live updater verification are recorded separately after publication. No physical handset test is claimed.

## Outstanding arrival request

The user also requested return from a map after arrival. The particular navigation surface and a reliable current-trip arrival signal have not been established. Existing code does not observe external-navigation arrival; no speculative app switch, global Back, route clearing, added screen capture or new data flow was introduced.

## Published release

- Exact frozen source `4e27eecf081d17e1d708e84e5dd4c21b41c84bdd`; release commit `2edab42467aaec90dfd0d54e3bedfc1c6ae65d95`.
- Original local signer: `553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703`. APK 1,213,691 bytes, SHA-256 `dd39e9b5442ab186475ab36944513ad462614b5d91939517fd9c3a9af607bc2d`.
- Frozen-source signing reran the dual-SDK gate and lint; all 374 tracked inputs unchanged, aggregate `097b8cc2604ae785c2ce65bdfb8ca3986b3fb9b71bed5218d2672fe1fa915f94`.
- Render static mirror deployment `dep-db0uvc6gekts73bdfqcg` went live at 06:18:56 UTC October 4. Production UpdateTransport downloaded and verified the exact .67 package/version/bytes/hash/original certificate; GitHub feed matches. Signing inputs were removed from both temporary workspaces.
- No physical handset installation or actual Dasher acceptance/arrival result is claimed. The arrival-return request remains open pending the specific map surface and reliable arrival evidence.

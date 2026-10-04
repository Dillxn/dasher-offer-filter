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

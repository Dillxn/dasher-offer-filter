# Pay per item and shared minimum calculations — 0.4.57

This candidate starts from main `98d410ceb29341928d7605d464ac4aee051ecf39` (0.4.56), preserving its decline-recovery identity/countdown guards and the original-signer auto-update channels.

## Behavior

- Pay / item is an optional sixth, downward spoke, off on existing installs. The fixed rule is payout divided by an explicitly observed positive total item count. It applies when an offer declares items or shopping. Missing declared counts require review when needed; no item evidence makes this scoped rule inapplicable, not proof of an ordinary delivery. An unread payout still requires review.
- Repeated equivalent accessibility labels do not add quantities. Unique-only counts, progress, ranges, contradictory totals, per-order components without a total, and rate labels do not become exact total quantities. Add-ons require explicit incremental/total meaning; old shopping quantities are not subtracted to invent an increment.
- `AreaScore.Floors` resolves exact fixed costs and the existing accepted/declined learned costs. Strict decisions, area scoring, chart baselines and closest-floor learning share those components. Fixed decimal costs scale before final cent rounding; existing learned matching/beating remains unchanged. Pay/item adds no adaptive floor.
- Strict mode takes the maximum monetary requirement. Area mode retains the user's compensating polygon rule: fitness is the square root of normalized area. The global minimums scale applies once. Existing zero-route validity policies remain unchanged in this refactor.
- Observed item count/applicability survives bounded history, route and suppression records; legacy missing fields remain unknown. Changed item settings stop stale tap authority. An item count alone never grants positive offer identity or tap authority. Historical scores remain recorded values; a differing constellation score under current rules is labeled Now.
- The new axis uses the existing control surface. Phone, short split-screen and large-text renderings are checked with native Robolectric graphics. No extra Settings row is added. Consent version 8 covers retained numeric item facts.

## Verification

The first integration run exposed short-window layout and shopping-list recognition regressions. They were corrected before the final candidate gate. The focused shopping/Peek/decline rerun passed 211 tests on simulated API 26/35.

A separate deterministic comparison against the 0.4.56 math/model covered 2,500 scenarios and 15,000 result records with pay/item disabled, with no mismatches in decisions, reasons, required pay, scores, add-ons or learned summaries. It included missing/zero routes, fractional mileage, extremes, hotspot values and scales from 1% through 200%.

The final gate passed 1,756 tests across 98 suites on simulated API 26/35 with zero failures, errors or skips. Lint completed with zero errors and 34 warnings. All 214 frozen inputs and both execution helpers were unchanged. Native phone, short split and large-font previews were inspected.

The repeatable differential probe, exact source snapshots and original matching outputs are preserved in `validation/0.4.57-math-comparison.zip` (SHA-256 `8401a979662d0652c1d08f8ba56d87c9dce51b7b52c0582968bd977d7286dcc7`).

Final frozen test/lint evidence is recorded in `validation/0.4.57-test-inputs.json` and `validation/0.4.57-test-result.json`. The release is not considered verified unless the result is PASS, all frozen inputs match, and the original-signer APK and live updater receipts are present.

## Release boundary

Candidate source only until signing/publication is recorded here. The existing Render browser session is signed out, and original signing credentials are not configured in this workspace; secure reauthentication is the remaining release blocker. No physical handset installation, live shopping-screen parsing, or Samsung/DoorDash decline/audio behavior was verified by these tests. The hotspot axis still has no verified automatic measurement source. No new signing key, payment setup or alternate update mechanism is introduced.

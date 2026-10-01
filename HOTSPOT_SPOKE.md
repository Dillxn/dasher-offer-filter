# Fifth spoke: hotspot proximity

Requested October 1, 2026: `1 / distance of last stop on offer from nearest hotspot`.
The earlier handoff request at sequence 2307 identifies actual Dasher hotspot honeycombs, not the app's learned offer areas.

## Implemented contract

- `OfferSnapshot.finalStopHotspotMiles` is an optional nonnegative, finite distance. Null is unknown. Zero must be an established exact zero, never a rounded display of `0.0 mi`.
- `FilterSettings.hotspotProximityHundredths` is a fixed minimum reciprocal distance, in hundredths per mile. Zero turns the rule off. A value of 100 means 1/mi, equivalently within 1 mile; 50 means within 2 miles.
- The value is independent of payout. Its normalized ratio is `100 / (hotspotProximityHundredths × finalStopHotspotMiles)`. Changing pay cannot change that ratio.
- Strict mode requires this minimum alongside the other active rules. Area mode includes it in the compensated shape; it is not an extra hard limit. Max stops retains its existing hard-limit behavior.
- Missing distance while the rule is active requires review. A separate known strict-rule failure or max-stops failure can still decline. An inactive fifth rule does not change old results.
- The fifth setting survives save/load, other rule edits, adaptive toggles, adoption and undo. The existing four adaptive floors are unchanged; no fifth learned floor is invented.
- Offer identity excludes the hotspot distance because a changing hotspot observation does not create a new offer. The optional numeric fact can be stored in decision history; no destination or hotspot coordinates are stored by this change.

## Geometry and numerical behavior

Keep the original four angles and add the fifth at the top, −90°. The drawn order is pay, hotspot, per mile, per minute, per stop. Close an empty sector of at least 180° through the center in both scoring and drawing. This avoids a sparse triangle whose area decreases as a desirable value increases. Single-axis scoring remains its ratio; two axes use the geometric mean.

The fifth ratio does not scale with pay. Mixed area therefore has monetary–monetary terms proportional to pay squared and hotspot–monetary terms proportional to pay. Required pay must be found from this expression, not by multiplying every ratio by pay. With only the fifth active, more pay cannot repair a proximity failure.

Exact zero distance uses the reciprocal limit. The visual may clip the mark at the display boundary and say “At hotspot”; the scoring model must not silently substitute an arbitrary epsilon or cap. An unknown distance never becomes this limit.

## Live-data gap

This candidate implements the control, numeric model and rules, **not automatic measurement**. The current accessibility reader exposes offer labels and content descriptions; no verified fixture supplies the final stop's geographic position or actual hotspot geometry. `AreaMap` records approximate phone positions when offers arrive. It cannot answer this question.

A future reader must establish all of the following before attaching a distance to an offer:

- The final stop of that exact offer, including the resulting route order for an add-on.
- Current actual Dasher hotspots, with stale observations discarded.
- Whether distance is straight-line or driving distance, and whether the target is the hotspot region's boundary or a marker. These choices are not settled by existing observations.
- A valid common geographic reference, explicit units, and enough precision to distinguish a real zero from rounding.
- Freshness and offer binding; no carryover from a preceding offer, completed route or later hotspot update into historical decisions.

The history merger currently keeps the first matching decision's facts. Before connecting a live reader, it must distinguish contextual observation updates from offer identity: a changed distance or a loss of freshness must update the context without counting a new offer or silently retaining a stale score.

No route-mileage, driver-GPS, merchant-location or learned-area proxy is permitted. No new screenshots, geocoding of customer addresses, third-party hotspot calls or map interaction is introduced here. Hotspot-labelled directions are excluded from parsing route mileage/duration; that exclusion does not establish final-stop evidence.

The live acquisition path needs a current, privacy-safe observation from the user's Dasher offer/map UI before implementation can be verified. Existing synthetic tests establish model behavior only.

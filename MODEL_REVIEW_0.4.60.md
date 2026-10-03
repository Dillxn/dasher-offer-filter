# Metrics and automation review — October 3, 2026

This review preserves the user's chosen strict rules, compensating polygon-area rules and upward adaptive learning. It does not claim that a fitness score predicts profit, acceptance probability or DoorDash allocation.

## Changes supported by evidence

- Automatic Accept was entering the manual acceptance observer. A generic Directions screen could then reach its weaker inferred-acceptance path, creating a false accepted result and stored route. Automatic requests now require their own bounded observation of explicit pickup/delivery progress. A successful Android click is only a request; a refused, interrupted or unconfirmed request does not count as acceptance. The user's failed physical tap itself is not explained without a matching field trace.
- A prior passing/review alert protected sound for 20 seconds globally, delaying quieting of a different declining offer. Protection now belongs to the current decline's original tap time. Actual Android sound timing still needs phone verification.
- Hotspot distance has a model and a saved rule but no reliable automatic reader. The constellation now reports that limitation instead of offering an apparently operational adjustment. An existing enabled rule is preserved until the user explicitly turns it off. The Atlas is historical offer geography, not a source of current Dasher hotspots.
- Pay/item needs a positive, unambiguous total item count on an applicable offer. Reports #40/#41 contain no count in their captured labels; they are one target and cannot establish whether a visual count was accessible. The selected-offer display also incorrectly combined a prior example payout with a newer count when the current payout was unread. It now uses the actual selected facts and marks missing or inapplicable figures honestly.
- Old offer timestamps contain no waiting exposure. The new estimate collects prospective numeric observations only during verified eligible waiting, then applies today's rules to them. It excludes delivery and unobserved time, includes measured unfinished waits, clips exposure to its 24-hour window, and withholds a numerical answer when facts or samples are insufficient.

## Model limitations to consider before another scoring redesign

1. Polygon area depends on which metrics are neighbors. For four evenly spaced ratios, `[3, .2, 3, .2]` scores about 77.46%, while `[3, 3, .2, .2]` scores 160%. A drawing-order choice therefore affects compensation. This is the approved preference rule, not a universal definition of fitness.
2. Payout, pay/mile, pay/minute, pay/stop and pay/item share payout and are correlated. They answer different cost questions but are not independent evidence of profitability. Shared effective floors correctly choose the strongest required payout instead of adding overlapping minimums.
3. Raw reciprocal hotspot distance is unbounded near zero. If measurement becomes available, it can dominate an area score even when a monetary axis is poor. Before enabling that reader, consider a bounded proximity contribution or a separate destination constraint; do not silently change the currently approved formula.
4. Adaptive minimums are the user's chosen upward high-water marks, not a statistical estimate of typical preferences. A floor above every historically accepted payout can leave no historical offer qualifying under today's rules. The wait estimate must withhold a prediction in that case rather than lowering floors or inventing matches.
5. Atlas rankings pool historical pay/mile. They do not measure eligible waiting, time-of-day effects or profit after expenses. Keep those meanings separate from the new wait estimate.

## Historical Dasher map problem

The user reported that 0.4.56 sometimes prevented Dasher's in-app map from opening, with current status unknown. The relevant overlay, touch, Peek and launcher code is unchanged from the .56 signed source through .59. Ordinary navigation does not issue a map launch/reset/cancel. Peek cannot begin while Dasher is already visible; its return is canceled by user interaction. Automatic Back remains exclusive to bounded failed-decline recovery, with route, touch, navigation and split guards.

The delivery-time edge tab can consume touches in its 16 × 56 dp strip. A coincident map control is a possible interference surface, not an established cause. No time-aligned report identifies an intercepted tap, an automatic return, a slow read or a Dasher failure. The map symptom remains unresolved; no speculative navigation behavior was changed.

## Evidence boundary

Local regression tests and code inspection can establish these paths and arithmetic. They do not certify physical-phone acceptance, audio timing, navigation, eligible-wait coverage or accessibility behavior. Live release checks separately verify published APK bytes and original signing identity.

## Later field evidence — October 3, 21:25 UTC

Diagnostic #42 from .59 clarifies the selected automatic-accept incident: verification canceled at the final guard before any Android click, and history said PASSED (matched rules), not ACCEPTED. The log does not identify the failed predicate. This is distinct from the source-proven false-acceptance paths fixed in .60. The next narrow change should distinguish an unsent automatic choice from a rules match and record fixed, privacy-safe guard reasons without weakening authority. No .61 result is claimed here.

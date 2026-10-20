# Independent Autopilot working minimums — 0.6.29

## Requested behavior

The owner's request on 19 October 2026 is that an offer accepted with Autopilot on should lower the Autopilot minimum on each lower measure and raise it on each higher measure. This applies to Autopilot's working requirements. Saved knobs and the separate best-ever learner are not overwritten.

A positively confirmed personal standalone acceptance replaces every readable, currently active working rate with the accepted offer's actual rate. It does not average observations, require a new best, or clamp the result to a saved or learned minimum. Each measure can move in a different direction on the same acceptance.

| Measure | Rate learned by Autopilot | A valid observation requires |
| --- | --- | --- |
| Pay per stop | Actual payout ÷ shown stops | Positive readable payout and stop count; pickup included |
| Pay per mile | Actual payout ÷ shown miles | Positive readable payout and distance |
| Pay per hour | Actual payout ÷ shown minutes × 60 | Positive readable payout and trip minutes |

An active measure has a saved minimum or an enabled learned best. An Autopilot adjustment alone never activates a disabled measure. An unread or zero denominator leaves that measure's prior adjustment unchanged. A known implausible offer rejects the entire adjustment; max stops remains a hard limit. This uses positively confirmed personal standalone acceptances while Autopilot is on, including the established manual outcome paths. Automatic acceptances, add-ons, skipped stores, ambiguous or expired offers, declines and reconstructed history are excluded.

## Exact rates and the shared bar

For an observed rate `r` and shared bar `b`, Autopilot stores the exact reference `r × 100 / b`. The effective working rate at a later bar `c` is `reference × c / 100`. Therefore the acceptance sets the actual rate to `r` immediately without moving the displayed bar or applying it twice. Rational arithmetic retains fractional cents and exact decimal mileage; hourly text converts the internal per-minute rate only for display.

With a shared bar of 82%, these two consecutive accepted offers produce independent changes:

| Accepted offer | Working pay per stop | Working pay per mile | Working pay per hour |
| --- | ---: | ---: | ---: |
| $6.60, 2 stops, 6 miles, 12 minutes | $3.30 | $1.10 | $33.00 |
| $9.00, 3 stops, 3 miles, 18 minutes | $3.00 ↓ | $3.00 ↑ | $30.00 ↓ |

A fully readable accepted offer lies at the existing bar under its newly normalized shape. Strict mode checks every active working measure; Area mode combines them with the existing area calculation. Required payout receives one final cent ceiling. The details may round a displayed unit rate up to cents, marked ≈, but that display rounding never feeds back into decisions. For example, $10 divided over 3 stops is shown as approximately $3.34/stop while that same three-stop offer still asks exactly $10.

The newest acceptance also supplies the shared bar's next-24-counted-offers hold. It replaces the old accepted-score maximum, so a historical high cannot undo a newer lower acceptance. A newer eligible acceptance restarts this hold at its own anchor bar. After 24 counted offers the hold ends, but the working references remain and normal bar planning continues. Legacy accepted-score records remain bounded and supply the prior hold only before independent working adjustments are active.

## Lifecycle and boundaries

A confirmed update is staged and committed only at the screen reader's established safe point. An offer or action in progress cannot have its rule changed midway. The pending record identifies the consent and policy context; stale unconfirmed observation authority cannot create a later update. When no offer can be up off a dash, the established fast safe point can apply the update at once. The on-demand Auto details show a pending explanation while the current working amounts remain in force.

Pause and same-goal Autopilot off/on retain committed adjustments and already confirmed pending updates. While off, filtering uses the saved and enabled learned requirements at 100%, and new acceptances do not create Autopilot adjustments. Resuming Autopilot restores its retained bar and working shape. Changing the goal, saved minimums, max stops, score mode or learning setting clears the adjustments and hold. Adopt retains them. Clear history removes active and pending adjustments as well as decision history; uninstall removes the local state.

Acceptance learning remains independent: with Learn on, it keeps only new bests for each measure and retains its pay margin and common plausibility guards. The owner's 19 October clarification supersedes the extra two-mile/ten-minute learning exclusions: every known plausible rate from the same confirmed personal evidence can establish a new high. The calculated learned triangle uses the higher saved or enabled learned requirement on each active axis, before Autopilot's working adjustment and shared bar. An acceptance can update that learner and Autopilot separately, and a later learner best cannot silently replace an active Autopilot reference. With Learn off, stored learned bests are excluded and Autopilot can still follow eligible personal acceptances on the remaining active measures.

Auto-accept requires a complete standalone offer that satisfies the strict saved and enabled learned requirements at 100% or any higher shared bar, and the independent working requirements wherever they ask more. An offer permitted below the saved or learned requirements remains for the user, with the existing review treatment and no passing chime. Add-ons retain strict saved-rule evaluation, including their combined-route and incremental checks; independent standalone adjustments do not apply to them. The narrow +$ pay-ceiling path remains conservative and does not use the new independent rates.

## Explanation and privacy

The Auto details show actual working amounts and explain the latest acceptance, shared bar and any pending update. The constellation's dashed purple shape represents the current working asks, including at 100%; saved and learned shapes stay separate. The current constellation and offer clock use current rules. Tickets and skyline trees use their recorded rule snapshot and identify an adjusted result as **Autopilot score**. New working values cannot relabel a historical score.

Exact references, their latest acceptance time and anchor bar, and one confirmed pending copy remain on the phone. Each retained decision may also keep the exact rule snapshot it used, under the existing 200-decision limit and folded-notification limits. The active references have no age expiry; decision copies leave through ordinary eviction or Clear history. Exported reports strip local rule snapshots, including folded notifications and queued reports remasked before sending. The score text and a fixed `scoreReference: autopilot` category identify the adjusted score basis; no exact-rate report fields or new recipients are added. Notice 30, Terms, Privacy and bundled release notes describe the change.

## Verification targets

The tests cover independent raises, independent lowers, mixed movement, latest acceptance replacement, exact fractional rates at non-100 bars, learned and saved separation, disabled or unknown measures, prospective personal evidence, deferred safe-point commits, persistence and invalidation. Presentation checks cover displayed rate rounding without changing required pay, purple working shapes at 100%, calibrated score labels from historical snapshots, unchanged older labels, pending explanations and report redaction. Existing area, strict, add-on and auto-accept regressions remain part of the local gate.

No real-phone run is claimed. On the owner's supported phone, accept a readable offer that moves measures in different directions with Autopilot on; inspect Auto's working amounts and the next offer's ticket. Repeat with Learn off, then a later lower acceptance. Confirm that an offer still on screen retains its original decision, and that Auto off uses the saved and enabled learned requirements. Build and test evidence belongs in the task's final verification report; this note does not imply that a build was installed or published.

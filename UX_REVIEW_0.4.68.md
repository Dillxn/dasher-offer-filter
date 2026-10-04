# Offer Filter UX review — October 4, 2026

The main problem is that important actions and outcomes are hard to find or distinguish. The constellation carries useful detail, but a driver also needs to answer four immediate questions: is filtering on, what happened to this offer, did it teach the minimums, and how do I navigate from here?

This review follows waiting, receiving an offer, automatic handling, manual acceptance, learning, navigation and arrival. It includes full-screen, short split-screen, narrow windows and enlarged text. Source and simulated Android results are evidence about the implementation, not proof of actual Samsung, Dasher or Maps behavior. Release validation is recorded separately.

## Changes in this update

| Problem | Change | What it means |
| --- | --- | --- |
| Navigation disappears when the atlas hides beside Dasher | A header Navigate action opens best recorded area, nearby gas and price comparison | The action remains available in split screen. The chosen map app supplies station results and prices. |
| Green geometry can look like confirmed acceptance or a learned lesson | A stable, tappable latest/selected offer caption shows its actual recorded outcome; the existing ticket leads with its learning or automatic-accept result | Passing the rule, requesting Accept, confirmed acceptance and learning remain separate facts. |
| Tapping the yellow unknown counter pauses filtering | Separate native touch/accessibility controls over the existing mascot and count columns | Only the mascot pauses/resumes. Counts open the newest matching retained offer ticket. |
| Unavailable wait estimates occupy delivery space | Show a number only with sufficient samples and positively observed waiting | No waiting promise appears merely because the services are enabled. |
| Area versus strict mode is encoded in styling | Existing minimums control says Area or Each above its percentage | The actual rule is visible without adding a legend or changing the math. |
| A harmless content update can cancel Auto-accept before it sends anything | At most two complete fresh reads before persistence, tied to the same candidate and original deadline | A stale node is never clicked. Changed identity, rules, countdown, user interaction or phone state still stops the attempt; after persistence there is no retry. |
| Native and generic background notifications both remain | Skip a redundant generic card when a current actionable native alert is verified and no new app bell is needed; preserve the audible fallback for silent native alerts | The original alert stays available. An enriched Peek card can still add figures actually read from the screen. |
| Launcher artwork ignores appearance | Stable day/night launcher aliases follow the existing Day/Night/System/Auto resolution | MainActivity and explicit consent/update entry points remain enabled. No new timer, wakeup or location request. |

The mascot, landscape, six spokes, skyline and existing settings remain the design foundation. The update favors clearer actions and truthful status over a second control cluster or a permanent explanatory legend. Enlarged text can use available space formerly occupied by the decorative road.

## The purple minimums complaint

The matching recorded offer was $18.35 for 16.3 miles, 35 minutes, two stops and one item. It passed its historical rule, but its recorded outcome was left to the user. Automatic Accept was canceled before dispatch with `content_changed`; later delivery progress appeared with retained pay/item facts, so the conservative observer continued treating it as an unfinished offer and recorded no lesson. The learned payout remained $9.40 and the learned item rate stayed empty. A green shape by itself does not prove that an acceptance reached the learning store.

The new acceptance-only recognition handles a narrow reproducible case: known delivery progress retains exactly the watched offer's bare payout and consistent observed item count, without remaining offer controls, countdown, additional offer facts or conflicting screen evidence. It teaches only the original eligible manual offer. Global offer classification used for other actions is unchanged; automatic choices still do not teach. The old history is not backfilled.

The original report does not retain the exact dollar label on the progress screen. Therefore this is a verified repair of that conservative failure pattern, not a claim that every detail of the reported incident has been reproduced. Fixed categorical evidence in future diagnostics distinguishes matching, different and ambiguous retained pay without retaining new raw text.

## Navigation and arrival

The best recorded area is the existing historical offer-arrival ranking by pooled observed pay per mile, with at least three readable-mile offers. Its sample count is shown. It is an approximate area, not a live Dasher hotspot or predicted earnings.

Nearby gas opens a station search. Compare gas prices opens the provider's search/comparison flow. Offer Filter has no station-price feed and cannot independently identify the nearest or cheapest station. The user chooses the result in Maps or Waze. No destination history or additional location query is introduced.

Automatic return after arrival remains unimplemented because the app has no verified arrival signal or current trip destination. `ActiveRouteStore` holds numeric accepted-offer context for add-on rules; it is not navigation state. Time elapsed, stopped movement, a generic Directions button, or Maps being foreground cannot safely establish arrival. Closing a map must not clear the active delivery context. A later arrival feature needs a time-aligned, identifiable arrival state and a clearly defined return destination.

Android controls task placement. Three apps cannot simultaneously occupy two ordinary split panes, and an adjacent launch is a best-effort request. Actual route preservation, pane placement and spoken navigation need parked handset verification.

## Verification priorities

| Journey | Required behavior |
| --- | --- |
| Offer Filter beside Dasher | Navigate is reachable; counts inspect without pausing; latest/selected identity stays correct as an outcome updates. |
| Eligible Auto-accept, content refresh | Fresh full verification; at most one Android Accept request; no accepted outcome until observed progress. |
| Changed offer, partial content, touch, lock or exhausted verification | No stale tap, no reset of the original deadline, no restoration after cancellation/restart. |
| Manual acceptance with retained pay | Exact original lesson only; unrelated/ambiguous money, conflicting items, route history or automatic provenance cannot teach. |
| Background offer and native update | Omit a redundant generic card only when native coverage is verified and no new app bell is needed; preserve silent-native fallback and share sound evidence. |
| Notification removed, listener reconnects or rules change | No lost last alert, stale pending Peek, or new offer inheriting an earlier notification's authority. |
| Theme choice, clock/system transition, update and Peek return | One enabled launcher after successful sync; MainActivity always enabled; correct alias/task lookup. |
| Short/narrow/enlarged-text layouts | At least 48 dp primary action targets, readable wrapping and usable scrolling where a dialog cannot fit. |

Launcher caches and pinned shortcut refresh depend on the phone. Auto is refreshed by existing activity/lifecycle/service work; a sleeping process is not woken just to change an icon. Old pinned shortcuts may retain cached appearance. Actual home-screen placement and TalkBack interaction remain device checks.

## Platform references

- Android accessibility guidance: minimum 48 × 48 dp touch targets and action/result descriptions; small text contrast of at least 4.5:1. https://developer.android.com/guide/topics/ui/accessibility/apps
- Google Maps intents distinguish search/display from navigation. A free-text navigation query selects the first result; it does not establish nearest or cheapest. https://developer.android.com/guide/components/google-maps-intents
- Multi-window placement and lifecycle vary by Android version and manufacturer. https://developer.android.com/develop/ui/views/layout/support-multi-window-mode

These references guide the interaction design. They do not certify third-party app behavior on the user's phone.

## Open-source wording and supplied narration

The earlier user direction explicitly called for a free and open-source app. The previous proprietary license and the unilateral cut of “and open source” contradicted that direction. Version 0.4.68 aligns LICENSE to the standard MIT License, updates the bundled Terms, and raises the notice version for the substantive Terms change. The website credits revision restores the complete original spoken line.

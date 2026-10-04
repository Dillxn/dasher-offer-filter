# Three-app journey review — October 4, 2026 UTC

The user asked to investigate Offer Filter, Dasher and a separate Maps/Waze app together, including getting gas and traveling to a historically better offer-arrival area. This review uses source at `ea52147fdd86838794216cfd3231254549b7d189` plus the bounded card-placement correction below. It is not a physical Samsung navigation test or a promise to preserve arbitrary Android split layouts.

## Immediate correction in this candidate

The split button already used fresh window evidence and excluded known floating/PiP windows. A notification-card tap still selected its launch using the twenty-second `dasherBeside()` UI hint and raw `isInMultiWindowMode()`. Consequently, an old Dasher pairing could describe a newer Maps pairing, while a floating card Activity could be treated as a split pane. This was a source inconsistency, not proof that it caused the user's GPS-voice/visual-map failure.

Card placement now requires fresh metadata identifying two non-overlapping, unobscured app panes and a divider. It uses already-known window identities without fetching any app root or text. A present Dasher uses the ordinary launcher request; a positively known pair without Dasher may use the existing user-requested adjacent launch. Unknown identities, unavailable metadata, keyboard/system obstruction, PiP and known floating geometry use an ordinary launcher request. The cached UI sighting never chooses which task to replace. No reset/clear-task flags, speculative Back, automatic route restart, new reading permission or action authority is added. Existing card cancellation, Dasher-notification fallback and one user-requested launch remain.

The focused validation record lists the actual regression and guard checks. Integration, full release gates, signing and publication are recorded separately by the coordinating release; this document alone is not shipment evidence.

## What the existing flow actually does

| Situation | Implemented behavior | Limit or friction |
|---|---|---|
| Offer Filter beside Dasher | Reads visible Dasher, shows the constellation/history, hides the duplicate atlas and redundant tab. Split setup/restoration is user-requested. | Android owns task placement and split persistence. A launch request does not prove the intended pane or Dasher screen appeared. |
| Maps/Waze full screen for gas, repositioning or an order | Eligible fresh background offers may trigger the existing quiet, bounded Peek. A completed decline returns to the previously observed app. Passing/unclear offers return with their observed-fact card under the user's existing navigation policy. | Only the navigation app's identity is known. The destination and purpose of that trip are not read. A foreground Maps screen is not proof that directions resumed. |
| Maps beside Dasher | Visible Dasher can be read normally; no Peek or extra tab. | Touches in Maps cannot be positively distinguished from touches in Dasher, so they conservatively end automatic offer handling. Only Offer Filter's own half has the explicit touch timestamp signal. |
| Offer Filter beside Maps, Dasher hidden | Peek refuses multi-window interruption. The user gets the ordinary card and can choose to open Dasher. | Three apps cannot occupy two ordinary split panes. A store-only notification does not supply enough figures to filter without displaying Dasher. |
| Tap a historical atlas area | Sends a user-requested `geo:` display/search intent for that approximate cell center. | This is not a measured Dasher hotspot, guaranteed high-earning destination, or start-navigation command. No return trip or gas detour is saved. |
| A user touches, switches apps, locks, starts a call or opens a chooser during Peek | The existing return/action guards cancel or refuse the interruption; no forced return. | Preserving the user's current choice is preferable to trying to reconstruct it from a stale app name. |
| Automatic acceptance enabled | One guarded request for a complete eligible standalone offer; explicit later delivery progress is required to confirm it. | A matching offer is not permission to replace a map's current destination. Automatic accepts never teach the learned minimums. |

`ActiveRouteStore` is bounded numeric accepted-offer context for add-on evaluation. It is not a trip manager: it stores no destination, gas detour, remaining navigation state or map-app intent. Its presence also makes Peek use the existing navigation return policy, even when the previous foreground app was something other than Maps. Logs correctly name only a category, never the other app's package or contents.

## Recommended product direction, not silently implemented

Offer Filter should coordinate brief decisions while preserving the user's current navigation, rather than require its atlas to stay visible during every drive. Use Dasher or the chosen map as the primary driving surface; use the existing passing/review card for an offer and the app's existing explicit split control for planning. The atlas remains an on-demand historical view. Do not add another permanently visible navigation control cluster to the crowded scene.

An eventual small, explicit “Return to map” action could make the user's return predictable after manually inspecting an offer. It should reopen only a currently authorized, short-lived map-app target, without recreating a route or guessing a destination; unavailable/expired state should say so. Today's Peek-only memory is deliberately cleared, and must not quietly become a persistent travel history. Any new retention or outside-app reading needs an explicit design/privacy review.

If an app-initiated trip concept is introduced later, distinguish **area preview/repositioning**, **gas search/detour** and **delivery navigation**. Destination changes must be the user's choice in the map. A new offer must not automatically overwrite a gas detour or route to a customer. Never infer delivery completion from Maps being frontmost, nor restore a customer route from approximate atlas coordinates. No such trip model or routing policy is included in .64.

## Real-device matrix still required

Run these while parked, preserving only fixed categories/timing in reports. Verify the actual visible map/destination and spoken navigation directly, because synthetic intents cannot certify them.

| Journey | What to observe |
|---|---|
| Maps and Waze, each navigating to gas; fresh fail/pass/review offer | Whether Peek opens, completes or leaves the offer correctly, returns once, preserves the original gas route and does not mute navigation audio. |
| Atlas area preview → explicit map navigation → offer → return | Cell preview versus directions start, chooser handling, no destination replacement, and return to the actual prior map state. |
| Active Dasher order using internal navigation, then an external map detour | Existing route context persists through app switches; no inferred completion, forced Back or automatic destination change. |
| Maps + Dasher split; move focus and touch each half during a decline | Conservative takeover, no unwanted tab, actual divider/coverage behavior and no tap after a Maps touch. |
| Offer Filter + Maps split; fresh card tap, with and without a recent Dasher pairing | Card opens Dasher once; no stale pairing or floating-window adjacent request. Verify which pane Samsung actually changes. |
| Offer Filter + Dasher split → reopen each app → rotation/theme recreation → return | Which pair survives, no duplicate Dasher launch when freshly visible, and explicit restoration only when requested. |
| Card/Peek with keyboard, shade, chooser, floating window, PiP, call or lock | No automatic navigation jump, no stale return authority, no unlock/wake and no action from incomplete metadata. |
| Dasher visually says waiting while GPS speech continues | Time-aligned visible screen, app/window category and fixed route lifecycle reasons; do not infer hidden Dasher state from audio alone. |

## Platform evidence checked

- Android documents `FLAG_ACTIVITY_LAUNCH_ADJACENT` as placement **if possible**, in conjunction with `NEW_TASK`; it does not promise arbitrary split restoration: https://developer.android.com/reference/android/content/Intent#FLAG_ACTIVITY_LAUNCH_ADJACENT .
- Android 12L+ can request entry into split using launch-adjacent from full screen; older devices and OEM behavior still differ. This is a future test opportunity, not a reason to add unverified launch attempts: https://developer.android.com/develop/ui/views/layout/support-multi-window-mode .
- Google distinguishes `geo:` map display/search from `google.navigation:` directions. The current atlas uses the former: https://developer.android.com/guide/components/google-maps-intents .

These are public API contracts. Neither an API contract, simulated window test nor a successful `startActivity` proves Dasher or Maps restored a particular internal screen or route.

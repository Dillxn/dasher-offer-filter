# Phone regression repair — local, unreleased candidate

This is the recovered specification and historical evidence summary for the `631eaa8c` core-repair checkpoint. Version and test statements below describe that checkpoint, not the current build. Current cumulative integration, new corrections and remaining release/phone gates are tracked in [the 0.5.11 ledger](docs/cumulative-successor-0.5.11.md).

Parent: dispatch-study candidate `18fa283b7ff0f879bf10c94e3aa0b1bbe191a0df`, tree `e149c9e232b75dbc39e55cfb5db57f00ff0cea3a`. Its 216 focused passing tests and release-source checkpoints remain unchanged. This incremental candidate changes no scoring, minimums, payout parsing, app version/code, signing, updater channel or recruitment status. Version 0.5.8/code 88 remains provisional source metadata, not evidence that this patch is installed. Recruitment remains **held**. Everyone uses the same app/update channel; a pilot limits recruitment/support, not product behavior.

## Field evidence and reproduced mechanisms

Owner-reported on **10 October 2026 EDT**: 20:53 ding then manual Dasher entry with no offer; 20:54 divider resize appeared to leave navigation and Peek was unreliable; 20:56 Report Offer Send unreachable in split; 21:01 Offer Filter responsive while Dasher was unresponsive, working when opened directly, with unreliable reads. The coordinator's 20:59:46 screenshot review described Offer Filter above showing “Review pay unread · 10.3 mi” and Dasher below on its red startup/splash. The installed version was not reconfirmed. The screenshot supplies no ground-truth payout or duration and does not establish that a Back action occurred.

Local baseline fixtures reproduced:

- A pending split request issued another launcher intent after Android had paired Dasher and then temporarily supplied no window metadata. Another fixture replaced a map pane during a dash. These demonstrate possible source mechanisms, not the cause of the owner's exact incident.
- Report Offer's separate native message panel placed Send at `Rect(268, 228 - 332, 289)` in a 240-pixel-high resized viewport. A logical `performClick` test had not checked this clipping.
- A background ding followed by manual icon/recents entry lacked correlation to the later normal visible read. This gap is now observed conservatively; it does not justify automatic recovery after a canceled Peek.

## Repair

**Pending split completion:** fresh metadata must identify an unobscured two-pane pair including Offer Filter, with no Dasher already present. Unknown/missing metadata, an existing dash, or a known Maps/Maps Go/Waze pane leaves the tasks untouched and consumes the pending request. A short message says “Keeping this split. Open Dasher from its icon if needed.” No-pending resize/resume opens nothing. The existing first, explicit split/open/swap actions remain available. Completion never relies on the cached twenty-second UI grace to authorize a launch.

Map identity comes only from package/window identity reads already made by the service. A bounded in-memory window/category map adds no node traversal, persisted app identity or destination. Map visibility is a reason to preserve that pane, **not proof of active navigation**. Existing Peek return ownership, touch/split cancellation and explicit handoffs remain intact. Unknown ownership grants no new automatic route. This change cannot stop another app's navigation or guarantee an OEM will restore an external task's exact screen; it introduces no such API or promise.

**Later visible observation:** one fresh background notification can be correlated for at most forty seconds to normal, eligible scanner reads. Only fixed categories enter existing masked diagnostics: `later visible: unknown screen|waiting|offer evidence|route screen; observation only`. A splash remains unknown, with at most one unknown line and one recognized observation; it is never called an empty offer. Withdrawn, replaced, expired or superseded notifications are rejected. Pause/consent/lock/lifecycle guards remain. This path creates no read poll, launch, native notification tap, Back, Peek authority or payout estimate. It does not guarantee Dasher will draw an offer. Existing foreground/card recovery guards are unchanged.

**Report Offer:** the complete unchanged disclosure, choices, optional note, privacy reminder, Preview and status share one scrolling body. Native Send/Cancel stay outside it, and the window explicitly requests IME resize. Send still constitutes consent, the note stays optional, and preparation/transmission still uses the existing background reporting path. No backend operation was performed here.

## Focused evidence

Final result: **186 tests across 11 suites passed, zero failures/errors/skips, exit 0**, with all recorded application/test inputs unchanged during the run. Exact commands, source-input hashes, exit codes and fresh JUnit receipts are under `../receipts/phone-regressions/`; the final source/patch/file inventory is in `verification.json`. The profile is the existing warm, offline API35 development profile, not full dual-SDK/lint/package/release validation. Existing dispatch-study tests were not rerun.

Fixtures cover pending/unknown/paired/no-pending split journeys, preserving known map and other-app panes, explicit user handoff, later manual entry including splash and incomplete pay, canceled Peek with no return/tap/Back, expiry/withdrawal, existing Peek/card/touch/read-budget/paused behavior, and reporting. The viewport fixture measures actual dialog/button rectangles with nonzero insets and a keyboard request in a reduced available viewport, checks an unclipped at-least-48dp hit region, and dispatches coordinate DOWN/UP events to submit exactly once through the fake transport. It also resizes and scrolls with Send outside the body. This is simulated Android layout/input evidence, not a real IME or handset PASS.

## Compact stationary recheck, after root's review and separately authorized delivery

1. Park safely. Confirm installed Offer Filter version/code against the exact delivery receipt; record Android/device and Dasher version. Keep Autopilot and auto-accept off. Confirm only the intended navigation app/route is active; end any unwanted route manually.
2. With the current route/pane visible, enter split once, resize the divider in both directions and resume Offer Filter. Expect no second Dasher launch, splash, task replacement, Back or competing map route. Try an explicit Open/Swap only by choice. Record which action preceded any failure.
3. Wait for a naturally occurring offer; do not accept an unwanted delivery to test. Compare a ding, manual icon/recents entry and Peek separately. Touch or enter split to cancel Peek; it must not later switch apps or retry its actions. Splash/unread payout or duration stays unknown, with no auto-action justified by it.
4. Open Report this offer in a short pane. Focus the optional note to open the keyboard, scroll the entire unchanged disclosure and Preview, and confirm Send/Cancel remain visible and tappable before and after resize. Send only if voluntarily intended after preview and root's intake readiness; otherwise Cancel. Local tests used only a fake service.
5. Stop and pause filtering for a wrong tap, ignored touch, freeze, obscured navigation or privacy leak. Report privately by the already-agreed channel: installed versions/device, timestamp plus timezone, preceding action, expected/actual, reference if voluntarily submitted, and masked preview only. No raw customer screenshots or public diagnostics.

Remaining: exact installed-version confirmation, real-device task/split/IME/touch behavior and Dasher responsiveness, root review and full release gates, and existing private backend triage/retention readiness. No new backend access, blocked-network retry, signing, publication or phone validation occurred.

## Queued, deliberately not included

- Stable minimums vertices: fixed rules/viewport followed by a worse inner offer must not shrink the reference triangle or mutate saved rules. The newest-offer reference/rescaling path remains a separate UI follow-up.
- Latest offer/verdict primary; mode, minima, pause and report clear; map/chart secondary. Automatic map-favoring divider ratios remain capability-dependent and unimplemented; no guessed coordinates, task restarts or override of the user's divider choice.

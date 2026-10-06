> October4,01:49UTC: .64/code70 is locally tested/signed and live-verified (2,077 tests/111 suites; source0aed95a, releasef5b62b3). It ships bounded outcome observations, proven-unsent provenance rollback, approved adaptive Pay/item, smaller upper-left mascot and fresh notification-card split placement. See VALIDATION_LEARNING_FLOW_0.4.64.md. Actual Samsung three-app route restoration and historical #45 tree provenance remain open.

> October 4 .64 follow-up: direct user request extends accepted-rate learning to observed Pay/item and reduces/moves the mascot. Bounded outcome rereads address a reproduced no-event loading-to-pickup transition while preserving evidence/action guards. The three-app audit identified notification-card placement using stale split state; see E2E_FLOW_REVIEW_0.4.64.md. These source fixes do not close the physical Dasher visual-navigation/split persistence questions or establish #45's exact inaccessible tree/event cause. Release validation is recorded separately.

> October 3, 21:53 UTC: .61/code67 is locally tested/signed and live-verified on both update feeds. It closes the #42 pre-dispatch status/diagnostic gap without changing automatic-action guards. The historical cause of that cancellation and physical acceptance/map/audio questions remain open. New #43 retains one item but unknown pay; REVIEW is correct and no parser defect is established. See VALIDATION_ACCEPT_NOT_SENT_0.4.61.md and the 21:41 diagnostics audit.

> October 3 continuation: the owner explicitly requested optional auto-accept, superseding the earlier no-auto-accept product boundary. See VALIDATION_AUTO_ACCEPT_0.4.59.md for guarded behavior and adaptive verification. Physical-device gaps in this closeout remain open; simulated tests do not close them.

# Original eleven investigations: finding-by-finding closeout

Audit date: 2026-10-02. Starting published repository: `bc7c0bc1a928ed09ca846007c5627195ea0fa1b0`, release 0.4.53 / code 59. The closeout is now **implemented, tested and published as 0.4.54 / code 60**, from source `f507d61bbcb63e9837d62322d5e74699ea296f1b` and release commit `9637e4efa94b215c02d65c26f44cc53496e1cc9a`. The final integrated gate passed 1,579 tests in 90 suites on API 26/35, with zero failures, errors or skips; lint passed with zero errors and 34 warnings. All 199 frozen inputs remained unchanged (aggregate SHA-256 `fe852e872be818964459d7410d199d3563bbab76cc8d6c9e242d7431271aa175`). Original-signer publication through GitHub and Render and a fresh production-updater download are recorded in `VALIDATION_CLOSEOUT_2026-10-02.md` and `validation/0.4.54-live-channel.json`. These checks do not establish a completed handset installation or scenario test.

## Scope and evidence

All eleven returned studies are present in the recovered `RESEARCH_AND_TASK_RETURNS.txt`: sequences **27986, 27988, 28075, 28077, 28162, 28164, 28166, 28560, 28562, 28564, 28580**. The source explicitly said all eleven study parts were in. They are completed research, not eleven completed implementations. Their observations largely concern one Samsung/Android 16 handset on older releases. Duplicate diagnostics are not independent samples. No raw diagnostic issue, payment credential, customer text, or old raw fixture was retrieved for this audit.

This ledger records **277 unique findings**. Each report retains its original finding identifiers where supplied. Additional identifiers distinguish unnumbered recommendations and field questions. Overlapping findings deliberately remain in their original reports, with shared evidence references. Suggested tests and field checklists are attached to the finding they exercise rather than counted again as separate defects. Research recommendations do not themselves constitute user instructions.

Statuses:

| Status | Meaning |
|---|---|
| already fixed | Source remedy is present in the published 0.4.53 baseline; listed tests/documents support the bounded claim. This does not prove handset behavior. |
| implemented in 0.4.54 — owner | Remedy is in the source that passed the final integrated gate and was published with the original signer. Real-phone behavior remains separate evidence. |
| intentional user decision | A later explicit user decision controls the behavior. It is not an unimplemented bug. |
| needs field | The original uncertainty requires observed phone/Dasher behavior or measurement; simulation cannot close it. |
| external blocked | Requires an external account action, owner evidence, unavailable platform/source data, or a publishing/legal decision. |
| still actionable | An open concrete defect, or an explicitly **deferred recommendation**. Optional new features/telemetry remain possible follow-up work, not unassigned release blockers or implicit user approvals. Each deferral states why it was not added. |

Owners: **atlas** = MainActivity/Places; **decline** = accessibility/decline/Peek state; **diagnostic** = logs/history/outbox/GitHubIssues; **fifth** = updates/receiver/notification/silencer; **root** = integration, consent/privacy/legal and remaining coordination.

Published evidence is in `VALIDATION_2026-10-01.md`, `VALIDATION_2026-10-02.md`, `VALIDATION_HOTSPOT_2026-10-01.md`, `VALIDATION_CALM_2026-10-02.md`, `VALIDATION_CLOSEOUT_2026-10-02.md`, `RELEASE_NOTES.md`, and `release/latest.json`. The closeout validation and live-channel receipt verify published 0.4.54; the 0.4.53 validation records 1,431 tests across API 26/35, lint, original signer, signed artifacts and live update transport verification. It explicitly does not establish a fresh Samsung installation, real touch delivery, navigation audio/task restoration, or historical report containment.

### Shared evidence references

| Ref | Current source and relevant tests |
|---|---|
| PRIV | `PersonalText`, `DiagnosticLog`, `DecisionLog`, `ProblemReport` (since 0.5.0 `OfferReport` and `DashSummary`), `OfferFilterService`, `Consent`; `PersonalTextTest`, `PrivacyBoundaryTest`, `MaskedCaptureAdapterTest`, `ConsentGateTest`. Default-deny unknown/partial pages and discard payment/account/earnings pages, mask recognized private text, purge old logs/unsent reports once. |
| SEND | `ReportOutbox`, `ReportJobService`, `DashDiagnostics`, `GitHubIssues`; `ReportOutboxTest`, `DashDiagnosticsTest`. Consent/opt-in gates and durable diagnostic issue-create receipt exist; further comment/problem receipt and overflow repairs are implemented and tested in 0.4.54. Retired in 0.5.0 with the GitHub path: the accountless path is `Feedback`, `FeedbackOutbox`, `FeedbackJobService`, `DashSummary`; `FeedbackOutboxTest`, `FeedbackDialogTest`, `DashSummaryTest`. |
| CONF | `DeclineState`, `DeclineEpisode`, `OfferFilterService`; `DeclineStateTest`, `ConfirmationLeftToYouTest`, `DeclineHandBackTest`, `ScannerThreadTest`, `StalledDeclineTest`. Countdown-bounded authority starts at tap, survives unreadable/figureless frames, patient retries, late outcome correction and reasoned endings. |
| PAIR | `OfferFilterService.sameForegroundNotice`, `OfferNotificationService`, `OfferPairing`; `SameOfferAdapterTest`, `OfferPairingTest`, `ConfirmationLeftToYouTest`. Same-offer notices preserve authority; explicit merchant/numeric contradiction revokes it. |
| TAKE | `OfferFilterService.Takeover`, `DecisionLog`; `DeclineHandBackTest`, `SameOfferAdapterTest`, `OfferOutcomeTest`. Fresh countdown re-offer, takeover read/card folding, bounded standalone display-only accepted observation. |
| SCAN | `OfferFilterService`, `DasherScene`, `SplitWindows`; `ScannerThreadTest`, `SplitTouchTest`, `DasherSceneTest`. Prefetch, metadata-only listener look, cached other-window IDs, quiet navigation, own-overlay filtering. |
| PEEK | `Peek`, `OfferFilterService`, `OfferNotificationService`, `OpenDasherActivity`, manifest; `PeekTest`, `PeekStateTest`, `PeekSettingsTest`. Quiet arming, eligibility guards, positive return, navigation card, chaining, elapsed timers and return verification. |
| AUDIO | `OfferSilencer`, `UpdateReceiver`; `OfferSilencerRecoveryTest`, `PeekTest`. Save-before-change, readback, boot/update restore, alarm-only Peek. Recovery-failure receipt retention is implemented and tested in 0.4.54. |
| LEARN | `AcceptedOfferTracker`, `FilterStore`, `AcceptedBest`; `AcceptedOfferCloseTest`, `AcceptanceEvidenceTest`, `AreaDeclineLessonTest`, `AdaptiveShapeByAreaTest`. Conservative evidence, no stale waiting inheritance, honest no-effective-change result. |
| VIEW | `MinimumsStarView`, `DecisionChartView`, `AreaMapView`, `MainActivity`, `OfferCardView`; `AxisClarityTest`, `AtlasClarityTest`, `OfferOutcomeTest`, `AndroidAdapterChartTest`, `SkylineFitnessTest`. Calm latest/selected geometry, accessible history, accurate historical atlas and distinct outcomes. |
| FEED | `tools/mirror_repo_feed.py`, `render-build.sh`, `UpdateTransport`, `UpdatePolicy`, `Updater`; `UpdatePolicyTest`, `UpdateDuringDashTest`, `UpdateCadenceTest`, release validation documents. Render mirrors the same original-signer GitHub APK; package/hash/size/version/signer checks preserved. |

The passing 0.4.54 integrated gate includes: `RobustStorageTest` for budget/drop/AtomicFile/receipt/queue cases; `ScannerRobustnessTest` and `RestartSuppressionTest` for fail-closed scanner, screen state, restart suppression, pause, launcher and transient overlay; `UpdaterHeldReadyTest`, `OfferAlertsReadinessTest`, expanded `UpdateDuringDashTest`/`OfferSilencerRecoveryTest`; expanded `PlacesTest` and `MainActivityRobustnessTest` for cache/clear/report worker/setup/visible split. `ReportShare` builds off the UI thread and suppresses stale chooser delivery after leaving, clearing history or destruction. The aggregate gate receipt above applies to these tests; their presence alone is not the evidence.

## 1. Evidence for Peek — sequence 27986

| ID | Original finding/recommendation | Disposition and evidence |
|---|---|---|
| 1.01 | Card/account screens retained and uploaded; own-name and ZIP-before-state masking gaps | **already fixed** in source (PRIV); **external blocked** for old issue deletion/card containment. New code cannot erase old remote history. |
| 1.02 | One dash uploaded two or three times | **already fixed** diagnostic issue create protection (SEND); comment/problem retry details are **implemented in 0.4.54 — diagnostic**. |
| 1.03 | Launcher reaches offer; contentIntent previously reached wrong screen | **already fixed** launcher-first user-card and launcher-only Peek (PEEK). Actual hot/cold/subpage behavior **needs field**. |
| 1.04 | Opening needs its own observed deadline, rather than a successful startActivity return | **already fixed** `Peek.OPEN_MS` = 6 seconds and window observation. Original 3-second suggestion was an estimate; 6 seconds is the implemented bounded contract. |
| 1.05 | Blank/loading/menu/partial frames must not trigger no-offer return | **already fixed** recognized empty-screen clock and positive proof (PEEK). Unknown screens remain up. |
| 1.06 | Suggested return from errors, wallet or end-dash question | **already fixed** conservative alternative: never act on or retain sensitive/unknown screens; no automatic Back. New screen-specific return wording **needs field**. Error/end question alone is not positive completed-decline proof. |
| 1.07 | Whole Peek 20-second limit may interrupt very slow decline; suggested countdown-based Peek limit | **needs field**: core confirmation remains countdown-bounded (CONF), Peek return window remains a documented 20 seconds (PEEK/AGENTS). Do not equate Peek ending with confirmed decline. |
| 1.08 | Late 7–10-second confirmation ignored, including rootless looks | **already fixed** (CONF). Slow Samsung execution still **needs field**. |
| 1.09 | Fast retries label completed decline as not confirmed | **already fixed** patient retry/late observation (CONF). |
| 1.10 | Takeover survives clearly re-offered same order | **intentional user decision**, implemented: a fresh countdown ends earlier takeover (TAKE). |
| 1.11 | KEEP/REVIEW would cover Maps | **intentional user decision**, implemented: navigating returns to previous app with observed-figure card; otherwise stays in Dasher (PEEK). |
| 1.12 | Stray touch during launch | **intentional user decision**, implemented: 700 ms quiet with watch already up, no keyboard, no touch shield (PEEK). Actual touch delivery **needs field**. |
| 1.13 | Notification action/category/flags/timeout/group/update metadata absent | **still actionable** optional diagnostic expansion. Capture only safe structural metadata; do not introduce arbitrary action text or extra personal data. `OfferNotificationService` currently records channel/extras-key state, not the complete proposed schema. |
| 1.14 | Removal age/reason and stale-post context absent | **already fixed** repeated stale log aggregation; detailed removal age/reason/offer-shape counters remain **still actionable** optional diagnostics (`OfferNotificationService`). |
| 1.15 | Missing complete post→launch→read→tap→return timing chain | **already fixed** core read/tap/Peek timing and return verification (CONF/SCAN/PEEK); exact first-nonempty/first-control milestone coverage is **still actionable** optional telemetry, not evidence of measured improvement. |
| 1.16 | Capture first three screens and prior scene for startup study | **intentional user decision** privacy boundary supersedes broad capture: unknown/payment/partial pages are not retained (PRIV). Use safe scene categories and timing; new startup wording **needs field**. |
| 1.17 | Hidden eligibility, ring duration/double ring, notification lifetime and key reuse unknown | **needs field**. One-phone 35% hidden sample does not establish general Peek eligibility or delivery/earnings effects. `OfferAlerts.CARD_MS` is now 60 seconds; notification state lifetime remains bounded separately. |
| 1.18 | Unknown add-on wording, cold starts, wallet/subpage launch, error/login/update/shop/camera screens | **needs field**. Synthetic `AddOnOfferTest` does not verify real Dasher labels. Unknowns remain REVIEW/no action; no invented labels. |
| 1.19 | Transient +$ label loses uncertainty on next read | **already fixed** carried same-offer bound and one history line (`OfferFilterService`, `SameOfferAdapterTest`). |
| 1.20 | Screen/lock/front/call/recent-input context proposed for hidden alerts | **already fixed** screen/lock and categorical window context; extra secure-lock/call/input telemetry is **still actionable** optional diagnostics. Previous app identity remains memory-only (PEEK/PRIV). |

## 2. Peek experience and interaction — sequence 27988

Original 24 recommendations are retained below; alternate numeric suggestions are not independent shipping requirements.

| ID | Original recommendation | Disposition and evidence |
|---|---|---|
| 2.01 | Quiet interval, no typing/call/system interaction before opening | **intentional user decision**, implemented at 700 ms / 3-second wait (PEEK). |
| 2.02 | Watch raised and verified before launch; failure means card | **already fixed** (PEEK). |
| 2.03 | Return only after own completed decline/positive empty or prior route; recheck ownership | **already fixed** including post-cleanup main-thread recheck (PEEK). |
| 2.04 | Navigation-aware KEEP/REVIEW return and figures on card | **intentional user decision**, implemented (PEEK). |
| 2.05 | Peek must not mute navigation/media | **already fixed** alarm-only Peek (AUDIO). Physical routing **needs field**. |
| 2.06 | No repeat Peek over user takeover/same offer | **already fixed** request freshness, paired read, held-key, takeover/acceptance guards (PEEK/TAKE). |
| 2.07 | Foreground identity only in memory; categorical log wording | **already fixed** (PEEK/PRIV). |
| 2.08 | Consent/docs must disclose automatic opening and return | **already fixed** current notice, `PRIVACY.md`, `TERMS.md`, `LegalTexts`, `ConsentGateTest`. |
| 2.09 | Temporary touch shield | **intentional user decision**: no shield; quiet watch selected. |
| 2.10 | Ignore pre-draw touches aimed at previous app | **already fixed** conservative alternative: any action while opening cancels return/automation; no assumption about touch destination (`peekSaw`, `clickDuringPeek`). This does not prevent an accidental Android-delivered Accept; **needs field**. |
| 2.11 | Hold initial ring during eligible Peek; fallback rings once | **already fixed** main-thread eligibility before silent card, fallback only once (`OfferNotificationService`, `PeekTest`). |
| 2.12 | Fail early if Dasher never appears | **already fixed** 6-second opening deadline; repeated failures disable Peek (PEEK). |
| 2.13 | Rate-limit interrupts and repeated failures | **already fixed** 5 seconds after ending, six starts/follows per ten minutes, two failed/three empty auto-disable (PEEK). Suggested alternative 10-second/three-per-two-minute values were not user decisions. |
| 2.14 | Temporary return status/turn-off affordance and first-use explanation | **already fixed** notice plus Settings toggle and categorical status/log; additional transient action surface is **still actionable** optional UX, subject to user's compact-interface direction. |
| 2.15 | Early accidental Accept/manual Decline must not teach | **already fixed** first-second learning suppression (PEEK/LEARN). |
| 2.16 | Preserve music as well as navigation | **already fixed** alarm-only Peek (AUDIO). |
| 2.17 | Default on only after safeguards | **intentional user decision**, implemented default-on toggle (PEEK/PeekSettingsTest). Phone verification remains open. |
| 2.18 | Checking indicator/accessibility state | **still actionable** optional UX; no new persistent overlay should be added merely to satisfy a speculative recommendation. Existing Settings/notice/status describe Peek. |
| 2.19 | Return immediately when question window closes | **already fixed** safer positive-screen completion (PEEK); closure alone is insufficient while glitch/next offer may redraw. |
| 2.20 | Proximity-near skip | **still actionable** optional platform guard; no proximity sensor is currently used. Not required to claim existing screen/call guards. |
| 2.21 | Shrink shield near Accept | **intentional user decision**: no shield. |
| 2.22 | Skip games/fullscreen video | **still actionable** optional interruption policy; camera/microphone/call/typing are guarded, arbitrary games/video are not classified. |
| 2.23 | Split navigation/status action | **intentional user decision**: use existing split workflow, avoid redundant tab/map when Offer Filter is beside Dasher (`DasherSplit`, AGENTS). |
| 2.24 | Verify return landed | **already fixed** 250 ms checks for 1.5 seconds, categorical logs and forgetting identity (PEEK). Task restoration itself **needs field**. |
| 2.F01 | Seventeen scenarios: parked media/game, Maps/Waze, add-on, social/typing, in-flight tap, bursts, pass/review, lock/car, split/own app, call, battery and surprise opening | **needs field** for OEM task/touch/audio behavior. Eligibility and rule simulations are covered by `PeekTest`/`PeekStateTest`; they are not seventeen physical scenario passes. No safety/hands-free or passive-earnings claim. |

## 3. Learning, rules and clarity — sequence 28075

| ID | Original finding | Disposition and evidence |
|---|---|---|
| 3.01 | Privacy/masking/capture/notice gaps | **already fixed** source (PRIV); old remote containment **external blocked**. |
| 3.02 | Aggressive learned maxima can reject all historical offers | **intentional user decision**: user explicitly chose “Keep it as is.” No alternative floor model is implemented (LEARN). |
| 3.03a | No learned offers in sample: no passing cases, unidentified clicks, unknown +$ and paused periods | **needs field** for actual inputs; conservative learning is intentional. Absence of learning in one sample is not proof the mechanism never works (`AcceptedOfferTrackerTest`). |
| 3.03b | Takeovers not learned without proper acceptance evidence | **intentional user decision** preserve existing learning; display-only outcome may change without teaching (TAKE/LEARN). |
| 3.03c | Actual “This dash / Dash Preferences / Safety tools” idle wording missing | **already fixed**, guarded against partial offers (`OfferEvidence`, `DasherSceneTest`). |
| 3.04 | Duplicate dash issue creation | **already fixed** diagnostic create (SEND); ambiguous comments/problem issues **implemented in 0.4.54 — diagnostic**. |
| 3.05 | Area decline below effective floor falsely reports TAUGHT | **already fixed** effective-shape comparison, without changing stored learned values (`FilterStore`, `AreaDeclineLessonTest`). |
| 3.06a | Failed/first-step declines counted as filtered instead of left to user | **already fixed** outcome/tally derivation (`DecisionLog`, `OfferOutcomeTest`). |
| 3.06b | Mascot samples outcome once after 1.2 seconds and can miss later completion | **implemented in 0.4.54 — root** late outcome correction in existing mascot animation, with regression; no manufactured confirmed outcome. |
| 3.07 | Expired unconfirmed question retains DECLINED stamp | **already fixed** timeout/left-to-user outcome (CONF/OfferOutcomeTest). |
| 3.08a | Learned values invisible/unavailable | **already fixed** labeled shapes, pressed numeric readout, accessible saved/learned values and adopt/reset (VIEW, `AdoptAdaptiveTest`). Persistent extra labels are constrained by latest calm-interface request. |
| 3.08b | Ticket omits learning steps and why nothing learned | **implemented in 0.4.54 — root** existing ticket renders learning steps; regression checks useful evidence without adding homepage text. |
| 3.08c | No floor-raised notice or per-learning Undo | **still actionable** optional UX; no change to learning rule or extra home clutter without reconciling user direction. Adopt already has Undo. |
| 3.09a | Area permits compensation / geometry can weight axes unequally | **intentional user decision**: understood and explicitly wanted area flexibility; strict mode remains (`AreaScore`, `AreaScoreTest`). |
| 3.09b | Accidental area/adaptive toggles lack feedback | **already fixed** state, spoken feedback and semantics (`MinimumsStarView`, `ConstellationControlsTest`); new visual Undo for every mode toggle is **still actionable** optional UX. |
| 3.10 | Stale waiting evidence survives an unclear screen without watched offer | **already fixed** (`AcceptedOfferTracker`, `AcceptedOfferCloseTest`). |
| 3.11 | Numeric knob values/normalization hard to understand | **already fixed** five explicit meanings, effective references and readouts (VIEW/AxisClarityTest); at-rest minimal text is latest user direction. |
| 3.12 | First max-stops tap changes unlimited to 2 | **still actionable** optional UX proposal, not a parsing error. Existing documented cycle is off, 2…10, off; drag/a11y adjust (`MinimumsStarView`, AGENTS). No recorded user choice selects a replacement cycle. |
| 3.13a | Report learned precision/current-state vs historical rules; outcome omitted | **implemented in 0.4.54 — diagnostic** `DiagnosticLog` labels current rules rather than inventing historical baselines, records learning-state times with milliseconds/timezone; `DecisionLog.format` includes actual outcome. `RobustStorageTest` covers the report context. |
| 3.13b | “baseline updated” despite no effective learning | **already fixed** no-effective-shape-change lesson status (LEARN); **implemented in 0.4.54 — decline** acceptance status claims an updated baseline only for actual `ACCEPTED_LEARNED`. |
| 3.13c | Problem report omits manual-decline learned floors | **implemented in 0.4.54 — diagnostic** `ProblemReport` includes current manual-decline floors and minimums scale, preserving synthetic/redacted evidence and existing privacy boundary; `RobustStorageTest`. |
| 3.14 | Strict ticket presents Score >100% beside DECLINE without explaining reference-only | **implemented in 0.4.54 — root** reference-only ticket wording plus regression, retaining actual verdict and score. |
| 3.15 | Own confirmation echo can masquerade as manual learning | **already fixed** own action identity/echo handling and tests (`ClickEvidenceTest`, `ScannerThreadTest`, LEARN). Real OEM echoes **needs field**. |
| 3.16 | Review and left-to-you visually conflated | **already fixed** distinct outcome symbols/selected badge/ticket reasons (`DecisionLog.Outcome`, VIEW). |
| 3.17 | Area suggestions lack distance/recency/time weighting; capture mid-route cells and stale location | **still actionable** optional alternate ranking policy. Existing atlas explicitly shows historical offer-arrival pay-per-mile, minimum three readable offers, not actual hotspots (`AreaMap`, `AtlasClarityTest`, AGENTS); that bounded representation is implemented. Freshness/route interpretation **needs field**. |
| 3.18a | End-dash question/menu can file diagnostics before actual end | **implemented in 0.4.54 — root** `DashDiagnostics.endOf` no longer ended a dash on the menu action or cancellable end question; explicit completed-end labels remain. Since 0.5.0 (DashDiagnostics retired) the summary after a dash ends at `OfferEvidence.isDashOver` (`Dashing.ended`), with the same labels: `DashSummaryTest.dashersEndScreensEndADashButAPauseOrTheDashsOwnScreenDoNot` and `RestartSuppressionTest.pauseIsNotADashEnd`; decline feeds explicit pause state. |
| 3.18b | Reports send before current notice accepted | **already fixed** before every outgoing part (SEND/ConsentGateTest). |
| 3.19 | Accepted unknown-pay +$ offer stays REVIEW | **already fixed** outcome evidence separate from verdict; unknown facts still never teach. Display-only takeover observation is bounded to standalone/non-prior-route cases (TAKE/LEARN). |

## 4. Setup, permissions and updates — sequence 28077

| ID | Original finding | Disposition and evidence |
|---|---|---|
| 4.B1a | Render stuck at 0.4.13; non-GitHub phones cannot update | **already fixed** user-approved mirror of signed GitHub release; live 0.4.53 transport verified (FEED). |
| 4.B1b | First-install page only explains upgrading | **already fixed** active `tools/mirror_repo_feed.py` page gives ordered browser install permission, notice and restricted-settings steps, followed by a separate preserve-data upgrade paragraph; no Play Protect safety claim. |
| 4.B2 | Restricted-settings dead end; direct accessibility component settings absent | **implemented in 0.4.54 — atlas** existing setup guidance/direct-details fallback; physical OEM paths **needs field**. |
| 4.M1a | Enabled-but-disconnected screen reader indistinguishable from off | **implemented in 0.4.54 — atlas** existing setup state; decline owner handles scanner failure breadcrumb. |
| 4.M1b | New outage card, battery/OEM diagnostics and fix row | **still actionable** optional new alert/report policy; existing offer cards must remain truthful. Current user authorization to fix bugs does not establish physical OEM kill behavior. |
| 4.M2 | Fresh-install auto-install permission missing; old nonexistent row names | **implemented in 0.4.54 — fifth** accurate current Updates/Fix wording; **atlas** existing setup links. Extra homepage update row is optional and constrained by compact UI. |
| 4.M3a | Pause/30-minute silence lifts update hold mid-dash | **implemented in 0.4.54 — root** explicit dash continuity for automatic installs; manual Updates remains allowed subject to offer visibility. |
| 4.M3b | Once-per-notice reminder spent at reconnect; notice update pauses next dash | **still actionable** policy recommendation: feed notice version/foreground-only notice-changing install or altered reminder. Existing once-per-version consent contract is explicit; do not claim changed behavior without docs/tests. |
| 4.M3c | Notice bumped for minor wording only | **intentional user decision** existing rule: bump when notice/terms/privacy changes in substance. Current privacy/Peek changes were substantive; no new bump just for editorial cleanup. |
| 4.M4 | Sideload developer verification/geographic rollout | **external blocked** requires current official policy/account/package-certificate status; source report's dates are not independently reverified by this code audit. No replacement signer. |
| 4.m1 | Cancelled/failed install disables automatic updates indefinitely; blocking cover during confirmation | **implemented in 0.4.54 — fifth** nonblocking pending confirmation. Automatic cancellation retry is **still actionable** optional engineering policy; current manual retry deliberately respects cancellation and avoids prompt loops. This is not represented as an explicit user choice. |
| 4.m2 | First-rule toast says “Resume it” with no Resume button | **implemented in 0.4.54 — atlas** mascot wording; auto-decline stays paused per user contract. |
| 4.m3 | Repeated notification permission denial dead end | **implemented in 0.4.54 — atlas** notification-settings fallback. |
| 4.m4 | Readiness checks only passing channel | **implemented in 0.4.54 — fifth** review/reminder readiness too; atlas integration. |
| 4.m5 | Silent-channel Fix uses settings instead of observed sounded post | **implemented in 0.4.54 — fifth** `FilterStore.doorDashChannelAlerts`/`OfferNotificationService`; ring decision already uses actual audible evidence. |
| 4.m6 | Notification-access loss only visible on homepage | **still actionable** optional tab/outage alert policy; new tab behavior conflicts with existing minimal overlay contract unless deliberately adopted. |
| 4.m7 | GitHub expiry only Settings, combined with stale Render stops updates | **already fixed** stale-Render dependency removed (FEED); connection failure remains honest Settings/report state (`GitHubConnect`). New alert optional. |
| 4.m8 | Non-owner GitHub connection appears useful without repo-access check | **still actionable** `GitHubConnect` access-probe/clear failure wording review; public updates already work through Render. |
| 4.m9 | Outbox/diagnostics send while new notice pending | **already fixed** (SEND/ConsentGateTest). |
| 4.m10 | Two signing scripts can reuse a code from different source | **already fixed** active Render path has one GitHub authority (FEED); **implemented in 0.4.54 — root** shared `tools/release_identity.py` checks both publish/finalize paths. Publisher freshly verifies live Render with production transport; retired finalizer requires HEAD to equal freshly queried GitHub main before trusting its checkout feed. `tools/test_release_identity.py` four regressions passed. The integrated gate passed; 0.4.54 was signed with the original key and published through both feeds, then verified by a fresh production-updater download. |
| 4.m11 | Relaunch call reported as success though Android may silently block | **already fixed** method contract says requested, foreground acknowledgement clears receipt (`Updater.relaunchAfterUpdate`/`relaunched`); actual relaunch **needs field**. Avoid stronger success wording. |
| 4.F01 | Browser first install, restricted settings, Play Protect/Auto Blocker, Pixel and Android8–11 update confirmation/services | **needs field**. Live transport and signed artifact are not installation evidence. |
| 4.F02 | OEM force-stop/cleaners, 24-hour accessibility warning, Advanced Protection, denied permissions, background location, device flow | **needs field**. Never label app an accessibility tool to bypass platform protections. |
| 4.F03 | Real channel silent behavior, vibration and postSounded | **needs field**; preserve current once-per-offer rule until evidence supports a deliberate change. |

## 5. Peek integration — sequence 28162

| ID | Original hazard | Disposition and evidence |
|---|---|---|
| 5.01 | Add-on one-tap decline never returns | **already fixed** positive prior-route completion (PEEK); real add-on labels **needs field**. |
| 5.02 | Any nonempty no-controls read triggers return; partial next offer/glitch/error hidden | **already fixed** positive recognized screen, no facts, no question/glitch hold (PEEK/CONF). |
| 5.03 | Card rings before Peek and yields silencer; misleading “not opened” wording | **already fixed** silent eligible card/once-only fallback and accurate text (`OfferNotificationService`, `PeekTest`). |
| 5.04 | Watch after launch/stray touch and early tap learning | **intentional user decision**, implemented quiet watch before launch and first-second no-learning (PEEK). |
| 5.05 | Updated/stale/same-key viewed offer reopens | **already fixed** fresh incarnation, ≤10-second post, recent-screen pairing and key hold (PEEK). |
| 5.06 | Pre-hide waiting/manual-decline evidence leaks into Peek learning | **already fixed** `AcceptedOfferTracker.peekBegan`, Peek learning tests. |
| 5.07 | Media mute suppresses Maps directions | **already fixed** alarm-only Peek (AUDIO); physical audio **needs field**. |
| 5.08 | Return leaves authority/watch/silencer/overlay active | **already fixed** cleanup before main-thread return (PEEK). |
| 5.09 | RESET_TASK_IF_NEEDED resets prior task; guest/PiP task identity | **already fixed** no reset/clear flags and guest/fullscreen guards (PEEK); exact Maps/Waze/custom-tab task restoration **needs field**. |
| 5.10 | Prior app packages written to logs/reports | **already fixed** memory-only identity and categorical logs (PEEK/PRIV). |
| 5.11 | Stale worktree/version/docs | **already fixed** reconciled into original-signer 0.4.52/0.4.53, validation docs. Recovered worktree was not a release. |
| 5.12 | Peek starts while install in progress | **already fixed** `Peek.refusal` and arming recheck. |
| 5.13 | No opening timeout; repeatedly fetches front roots while blocked | **already fixed** 6-second deadline/new-window look, failed-launch disable (PEEK). |
| 5.14 | Bare start/tick/return RuntimeException kills process | **already fixed** local guarded steps; persistent crash-pause **implemented in 0.4.54 — decline** (see 6.D03). |
| 5.15 | User card tap races return | **already fixed** `OpenDasherActivity.cardTapped` ownership cancellation (PEEK). |
| 5.16 | Keyboard/recents/pending split awkward starts | **already fixed** IME, unknown/system front and pending split guards; launcher-recents identity behavior **needs field**. |
| 5.17 | Same offer cancel/repost on foreground revokes confirmation | **already fixed** positive same-offer pairing (PAIR); real repost semantics **needs field**. |
| 5.18 | “Peek” also means slim tab / Motorola display | **still actionable** optional terminology cleanup; separate classes/log categories exist. No need rename product. |
| 5.19 | Optional 150 ms content gap during opening | **needs field** before changing existing scanner cadence. Source keeps existing documented gap and adds no delay (SCAN/AGENTS); original recommendation was optional. |
| 5.T01 | Twenty-three proposed regression groups | **already fixed** substantial coverage in `PeekTest`, `PeekStateTest`, `PeekSettingsTest`, PAIR/CONF/AUDIO; the crash-pause regression is included in the passing 0.4.54 gate. This is not a claim every suggested test name exists verbatim. |
| 5.F01 | BAL Android10–16, actual offer launch, navigation return/PiP, directions, notification repost | **needs field**; listed explicitly in validation documents. |

## 6. Peek adversarial scenarios — sequence 28164

The six headline problems and eleven prioritized fixes repeat the scenario findings below: headline 1→6.B06; 2→6.C03; 3→6.C08; 4→6.A01; 5→6.A02; 6→6.B04. Priority fixes 1–11 map respectively to B06, C08, C03, A02, A01, B04, B05, A05, D04, C06, and the remaining C/D guard rows.

| ID | Original failure mode | Disposition and evidence |
|---|---|---|
| 6.A01 | Cold start/splash/login/update mistaken for no offer | **already fixed** recognized-screen clock; unknowns stay up; three empty peeks disable (PEEK). New wording **needs field**. |
| 6.A02 | OEM silently blocks start, 20-second wait, misleading touch/key hold | **already fixed** separate 6-second opening timeout, two-failure disable, no unseen-offer hold (PEEK). OEM delay **needs field**. |
| 6.A03 | Dasher PiP/pop-up not excluded | **already fixed** fullscreen/bounds/PiP guards (PEEK). |
| 6.A04 | User already opening Dasher at same time | **already fixed** quiet interval, final recheck, ownership cancellation (PEEK); actual transition race **needs field**. |
| 6.A05 | Lagging notification after screen read/acceptance causes reopening | **already fixed** paired 12-second recent read and 60-second acceptance guard (PEEK/PAIR). |
| 6.A06 | Assigned/already accepted “New Delivery” triggers flash | **already fixed** conservative recent acceptance guard; indistinguishable unseen assigned posts **needs field**. No invented notification semantic. |
| 6.A07 | Non-English notification ignored silently | **still actionable** optional unsupported-language/channel-only status, not broad translation/parsing. Unknown text must not become an actionable offer. |
| 6.A08 | Same-store repost or cancel/repost on foreground looks new | **already fixed** PAIR; explicit differing merchant still revokes. Real key lifecycle **needs field**. |
| 6.A09 | Dasher removes notification before delayed offer appears | **already fixed** positive no-offer proof prevents early return; observed removal/draw timing **needs field**. |
| 6.B01 | Dialog covers offer and triggers empty return | **already fixed** unknown screen does not count as empty (PEEK). |
| 6.B02 | System permission prompt during Peek | **already fixed** interruption/foreground guards leave user in control (PEEK). |
| 6.B03 | Expired offer dialog vs recognized finding-offers screen | **already fixed** known idle can return; unknown dialog stays. New expired wording **needs field**. |
| 6.B04 | Burst/next offer strands user; spacing measured from start | **already fixed** bounded chaining and 5 seconds after end, six per ten minutes (PEEK). |
| 6.B05 | Return before settled idle skips glitch recovery and same-key end | **already fixed** positive completion/episode hold, seen-end notification lifecycle (PEEK/CONF/PAIR). |
| 6.B06 | Add-on decline no question never returns | **already fixed** add-on one-step completion plus prior-route proof (PEEK). Actual labels **needs field**. |
| 6.B07 | User takeover must end Peek | **already fixed** touch/click/card authority cancellation (PEEK/TAKE). |
| 6.C01 | No launcher/guest system front cannot be restored | **already fixed** refuse unknown/unreturnable/known guest packages (PEEK). |
| 6.C02 | Custom tab/caller-owned camera task returns wrong task | **needs field** and platform limitation: arbitrary other-app task IDs unavailable; guest exclusions reduce risk, launcher return is not exact task restoration. |
| 6.C03 | Settings FallbackHome mistaken for Home | **already fixed** resolve default HOME only (`OfferFilterService.defaultHome`, `PeekTest`). |
| 6.C04 | Voice recording/video call/camera interruption | **already fixed** mic/camera/audio-mode guards (PEEK); platform indicator completeness **needs field**. |
| 6.C05 | Bank/game/video restarts; landscape rotates | **already fixed** no RESET_TASK flag; voluntary category/landscape skip remains **still actionable** optional policy. App behavior **needs field**. |
| 6.C06 | Own notification-launched task duplicates MainActivity/loses Settings | **already fixed** own `AppTask.moveToFront` with fallback and regression (PEEK). |
| 6.C07 | User switches apps or goes Home before return | **already fixed** touch/action/window checks immediately before return (PEEK); gesture delivery **needs field**. |
| 6.C08 | Keyboard or pre-launch touch causes accidental Accept | **intentional user decision**, implemented quiet/no-IME/watch guard, no shield (PEEK). Actual accidental taps remain **needs field**. |
| 6.C09 | Switch or auto-decline off before return ignored | **already fixed** return rechecks including main-thread runnable (PEEK). |
| 6.D01 | Screen off/lock during Peek | **already fixed** Peek stops with no return; broader scanner window watch **implemented in 0.4.54 — decline**. |
| 6.D02 | Service disabled during Peek | **already fixed** stop cleanup/restoration/cancelled timers (PEEK/AUDIO). |
| 6.D03 | RuntimeException recurring every offer; no persisted crash pause | **implemented in 0.4.54 — decline** `peekFailed` persists switch off; `ScannerFailure` guards callback failures. Whole-process crash capture is a separate 8.M5 item. |
| 6.D04 | Uptime timers stay paused through sleep | **already fixed** `Peek.clock` uses elapsed realtime, explicit clock tests (`PeekStateTest`). |
| 6.D05 | Lifetime key hold blocks unseen new offers | **already fixed** hold only offer actually left with user; screen-ended clears hold (PEEK). |
| 6.D06 | Consent pending and privacy of foreground packages | **already fixed** gates plus memory-only identity (PEEK/PRIV). |
| 6.F01 | Fourteen phone scenarios in original checklist | **needs field**: Maps/Waze/add-on, bursts, notification cancel/repost, Settings/custom tab/own task, typing/calls/Home/lock, cold start, OEM blocked start, landscape/video. Simulation alone does not check those platform deliveries. |

## 7. Core offer-to-outcome flow — sequence 28166

| ID | Original finding | Disposition and evidence |
|---|---|---|
| 7.U1 | Payment data captured and remote copies | **already fixed** PRIV source; remote containment **external blocked**. |
| 7.U2 | Own name without completed-orders marker | **already fixed** PRIV. |
| 7.U3 | Duplicate dash reports | **already fixed** diagnostic issue-create receipt; remaining delivery robustness **implemented in 0.4.54 — diagnostic** (SEND). |
| 7.B1 | Late confirmation silently missed because authority starts at read start | **already fixed** actual-tap countdown authority, logged endings/late question (CONF). |
| 7.M1 | Fast confirmation retries mislabel completed decline | **already fixed** patient accepted-try interval, honest refusal wording, late correction (CONF). |
| 7.M2 | Own offer notification revokes confirmation | **already fixed** PAIR. |
| 7.M3a | Expensive remote node/root fetches | **already fixed** API33 prefetch/remote count and metadata-only pretap visibility (SCAN); improvement magnitude **needs field**. |
| 7.M3b | New offer waits behind non-offer traversal | **already fixed** cut-short/priority scheduling (SCAN); duty-cycle regression **needs field**. |
| 7.M3c | Drop include-not-important-views / early-stop traversal experiment | **needs field** before changing completeness-sensitive traversal. Do not drop labels merely to improve synthetic speed. |
| 7.M4a | Notification callback fetches roots on main thread | **already fixed** metadata-only visibility (SCAN/SameOfferAdapterTest). |
| 7.M4b | Click.of event source lookup may block main thread | **needs field** timing measurement; no demonstrated source-cache delay in current build. |
| 7.M5 | +$ uncertainty vanishes on next frame, duplicate history | **already fixed** persistent same-offer bound and merge (PAIR/SameOfferAdapterTest). |
| 7.M6 | Same order freshly reoffered remains under 2-minute takeover | **intentional user decision**, implemented countdown proof (TAKE). |
| 7.M7 | Navigation/unchanged takeover causes urgent back-to-back reads | **intentional user decision**, implemented calm cadence (SCAN). |
| 7.m1a | Tab/guide remains stale and touchable until slow read classifies new window | **implemented in 0.4.54 — decline** immediate hide on relevant transition; only fresh matching-generation read restores it (`ScannerRobustnessTest`). |
| 7.m1b | Possible tab overlap with Accept/Decline | **needs field** bounds/screenshot check. No evidence current draggable slim noninteractive tab covers an actionable control. |
| 7.m2 | Compact “1 hr” without minutes unparsed | **already fixed** `OfferParser`, `OfferParserTest` including malformed-duration guard. |
| 7.m3 | Unsupported locale/km silently misses offers | **still actionable** bounded unsupported-language feedback; full localization is separate scope. Do not claim Spanish/km support. |
| 7.m4 | Alarm volume set before recovery receipt saved | **already fixed** save-before-change/≤floor restore (AUDIO); retaining failed restore receipt **implemented in 0.4.54 — fifth**. |
| 7.m5 | Fixed 2-second glitch allowance shorter than slow close | **already fixed** adaptive 2×read duration bounded 2–10 seconds (`DeclineEpisode`, `DeclineEpisodeTest`). |
| 7.m6 | Partial flicker arms “went back to offer” handback | **already fixed** positive settled-screen evidence; sparse frames preserve authority (CONF/ScannerThreadTest). |
| 7.m7 | Long performAction echo window can swallow real touch | **needs field** measured call duration/echo behavior. Existing own-call echo policy retained; no arbitrary cap added that could misclassify app taps. |
| 7.m8 | Node target may be seconds old at tap | **needs field** race/performance measurement before another blocking remote fetch. Current metadata/window/generation/touch rechecks protect ownership (SCAN/CONF). |
| 7.F01 | Eleven original field checks: slow/later questions, details takeover, split own-half touch, tab, +$, reoffer, alarm restore, navigation duty, one report and truthful outcomes | **needs field**, in addition to unit/Robolectric regressions. No field completion inferred from 1,431 passing tests. |

## 8. Whole-dash and multi-day robustness — sequence 28560

| ID | Original finding | Disposition and evidence |
|---|---|---|
| 8.M1a | Pause or long quiet period permits automatic update mid-shift | **implemented in 0.4.54 — root** explicit dash continuity for update gate (`Dashing`, `UpdateDuringDashTest`); decline feeds pause state. |
| 8.M1b | New-notice reminder used up off-dash | **still actionable** optional reminder/install policy; see 4.M3b. Once-per-version behavior is explicit current contract. |
| 8.M2a | Fullscreen slow reads dominate battery/UI | **already fixed** source optimizations (SCAN); duty cycle/battery magnitude **needs field**. |
| 8.M2b | No content-only read while Dasher last hidden | **still actionable** optional scheduling policy requiring assurance a newly shown offer is never missed. Not silently adopted as a performance shortcut. |
| 8.M3 | Own overlay events invalidate reads | **already fixed** verified own-overlay IDs only; unknown other service overlays remain visible to scanner (SCAN/ScannerThreadTest). |
| 8.M4 | Silent authority drops and unlogged late question | **already fixed** CONF. |
| 8.M5a | Unguarded scanner callbacks kill reading without breadcrumb | **implemented in 0.4.54 — decline** fail-closed handler plus class-only `ScannerFailure`; reconnect discards old tap authority. |
| 8.M5b | Whole-process/Application uncaught exception and historical process-exit records absent | **still actionable** optional bounded crash diagnostics beyond scanner containment. Never retain exception messages or private screen data. No crash/restart behavior claimed from simulation. |
| 8.M5c | Screen reading stopped not distinguished/announced | **implemented in 0.4.54 — atlas** setup distinction; extra one-per-dash outage notification is **still actionable** new alert policy. |
| 8.M6 | Lost create response, job cancellation/replacement causes duplicate reports/comments | **already fixed** diagnostics issue-create marker/job lifecycle; **implemented in 0.4.54 — diagnostic** comment/problem reconciliation and queue durability (SEND). |
| 8.M7 | Scan timings evict decision/confirmation history within minutes | **implemented in 0.4.54 — diagnostic** separate timing eviction priority within unchanged retention budgets (`DiagnosticLog`, targeted log tests). |
| 8.m1 | Place cache unbounded and survives Clear history | **implemented in 0.4.54 — atlas** bounded 300-entry recent-use cache, clear and in-flight generation invalidation (`Places`, `PlacesTest`, `MainActivity`). |
| 8.m2 | Window watch runs while screen off | **implemented in 0.4.54 — decline** phone-readable guards and stopped timers (`ScannerRobustnessTest`). |
| 8.m3 | Re-fetch other apps' roots in split every look | **already fixed** cached other window IDs (SCAN); one Peek identity lookup remains necessary and never traverses text. |
| 8.m4 | Listener main-thread root reads | **already fixed** SCAN. |
| 8.m5 | Independent calm-view invalidate loops may exceed aggregate 10 fps | **implemented in 0.4.54 — root** common100ms animation tick (`MotionAdapterTest`). Actual aggregate frame/battery measurements still **needs field**; source report's50fps was a hypothesis. |
| 8.m6 | Full log queue/outbox drops uncounted; says queued after failure | **implemented in 0.4.54 — diagnostic** numeric counters and durable-write success reporting. |
| 8.m7 | Decision history rename not fsynced; corrupt file drops all entries | **implemented in 0.4.54 — diagnostic** atomic fsynced write and per-entry recovery (`DecisionLog`, new robustness tests). |
| 8.m8 | Sound restore after boot/update requires service/activity | **already fixed** `UpdateReceiver` restore and `OfferSilencerRecoveryTest`; retry receipt retention **implemented in 0.4.54 — fifth**. |
| 8.m9 | Held update downloads/rechecks feeds/hash every five minutes | **implemented in 0.4.54 — fifth** process-local verified-ready hold, fresh verification on release/manual/restart/cache change (`UpdaterHeldReadyTest`). |
| 8.m10 | Main refresh lists outbox folder every second even hidden Settings | **implemented in 0.4.54 — atlas + diagnostic** Settings-visible status gate and cached outbox count. |
| 8.m11a | Stale notification replay log spam | **already fixed** count/interval aggregation (`OfferNotificationService`, `QuietLogAdapterTest`). |
| 8.m11b | Unavailable touch watch logged repeatedly | **implemented in 0.4.54 — decline** coalesced `TouchWatch` failure logging; actual decline authority remains unaffected. |
| 8.m12 | No distinct idle/pause trace; ambiguous finding-offers status | **implemented in 0.4.54 — decline** safe recognized idle category capture and truthful pause state; default-deny privacy retained. |
| 8.m13 | Restart loses takeover and pending authority | **implemented in 0.4.54 — decline** `RestartSuppression` persists only bounded numeric takeover evidence, never resurrects tap authority; tests cover time/boot/fresh offer. |
| 8.m14 | Share report builds/masks/waits on main thread | **implemented in 0.4.54 — atlas** worker construction with duplicate/lifecycle guards. |
| 8.F01 | Scanner duty/cache-vs-remote timings, overlay identity, gfx frame rate, battery, process exits, storage and outbox traces | **needs field** measurements; current scanner timing and categorical logs do not establish battery or latency gains. Optional telemetry proposals remain bounded by PRIV. |

## 9. Evidence replay of earlier reports — sequence 28562

The research replay itself completed on old main: 122 screen entries were replayed, 117 unchanged, three improved, two differed with historical rule changes. Those are historical research results, not a new replay of private data in this continuation.

| ID | Original ranked problem | Disposition and evidence |
|---|---|---|
| 9.P1 | Payment data retention/upload | **already fixed** source PRIV; historical containment **external blocked**. |
| 9.P2 | Late question silently ignored | **already fixed** CONF. |
| 9.P3 | Too-fast retries / false “all refused” and false YOURS | **already fixed** CONF. |
| 9.P4 | Name, ZIP-before-state, route street, customer-dropoff masks | **already fixed** PRIV, including default-deny unknown text rather than recovering old fixtures. |
| 9.P5 | Duplicate diagnostic creates | **already fixed** issue marker; remaining comment/problem retry **implemented in 0.4.54 — diagnostic**. |
| 9.P6 | Reports sent before consent | **already fixed** SEND. |
| 9.P7 | Reoffer inherits takeover | **intentional user decision**, implemented TAKE. |
| 9.P8 | Full controls/no figures causes REVIEW/report/reset | **already fixed** sparse animation frame preserves state without invented result/report (CONF/ScannerThreadTest). |
| 9.P9 | Multi-second reads and queued wait | **already fixed** source optimization SCAN; real speed **needs field**. Proposed traversal experiments remain 7.M3c. |
| 9.P10 | Public Render feed stale | **already fixed** FEED and live0.4.53 verification. |
| 9.P11 | First Decline counts as completed | **already fixed** `DecisionLog.outcome`/`OfferOutcomeTest`. |
| 9.P12 | Navigation/dash panel false “offer up” | **already fixed** quiet navigation (SCAN); **implemented in 0.4.54 — decline** positively recognized summary figures use `AcceptedOfferTracker.offerFacts` consistently. `ScannerRobustnessTest.explainedDashTotalsCoalesceButNewOfferControlsStayUrgent` retains urgent controls/new-offer/partial evidence. |
| 9.P13 | Actual dash idle wording missing | **already fixed** `OfferEvidence`/`DasherSceneTest`. |
| 9.P14 | User takeover then acceptance remains YOURS | **already fixed** bounded display-only acceptance for standalone prior-wait evidence; add-on/prior-route resumption cannot falsely prove acceptance (TAKE/OfferOutcomeTest). Learning unchanged. |
| 9.P15 | Repeated stale/future lines | **already fixed** `OfferNotificationService` aggregation. |
| 9.H01 | Close historical fixed/test issues; preserve unclear cases | **external blocked** not performed by this code audit, and raw diagnostic issues deliberately not reopened. Distinguish cleanup authorization/evidence from code fixes. |
| 9.E01 | Earlier +$ ceiling, extra-stop-fee retirement, own tap echo/details takeover/card launch and mid-offer updater fixes | **already fixed** historical source/tests preserved (`OfferRuleTest`, `ScannerThreadTest`, `DeclineHandBackTest`, `CardTapAdapterTest`, `UpdateDuringDashTest`). |
| 9.E02 | First Decline refused with no useful reason | **already fixed** current service records refusal/readability/timing; root/decline must retain safe fixed-category wording, never raw node exception messages. |
| 9.E03 | Missing old raw logs: old takeover intent, hidden reason, unexplained pause, half-draw/late-offer cause | **needs field** on a new privacy-safe build. Do not reconstruct missing evidence or infer intent. |
| 9.E04 | Unknown-route add-on reason says pay missing | **already fixed** `OfferRule.evaluateAddOn` distinguishes missing/ambiguous incremental or route evidence; service uses that path. Root added an explicit historical-shaped synthetic no-route `+$5.50`, `+2 stops` assertion to `OfferRuleTest` included in the passing 0.4.54 gate. |
| 9.E05 | End-dash question filed before confirmation | **implemented in 0.4.54 — root** dash-end classification audit; see 3.18a. |
| 9.L01 | Authority/end/question timing, read queue cost, takeover end, late correction lines | **already fixed** bounded structured diagnostics (CONF/SCAN/TAKE); exhaustive timeline measurement **needs field**. |
| 9.L02 | Safe incomplete-control diagnostics | **already fixed** generic/default-deny category for incomplete/unknown pages (PRIV), deliberately not raw proposed label dump. |
| 9.L03 | Pause/resume/knob changes absent from log | **still actionable** optional fixed-value rule-change audit trail; no private labels required (`FilterStore`, `MainActivity`). |
| 9.L04 | Screen/lock on captures; job/create lifecycle; sensitive skip and consent hold counters | **already fixed** bounded existing markers partly present; richer consolidated coverage **implemented in 0.4.54 — diagnostic/decline** where owned, otherwise optional. Do not claim every original suggested log line exists. |

## 10. Peek Android platform mechanics — sequence 28564

Platform-source research completed in the original session. Historical claims about future Android versions, developer verification and Advanced Protection are not restated here as newly verified current facts.

| ID | Original finding/recommendation | Disposition and evidence |
|---|---|---|
| 10.01 | Bound accessibility service background launch, not listener-only authority | **already fixed** connected service requirement/start path (PEEK). OEM/Android-version exemption behavior **needs field**; new platform policy requires current primary-source verification. |
| 10.02 | startActivity can be silently blocked; verify window appeared | **already fixed** opening observation/deadline (PEEK). |
| 10.03 | Android8–11 Home delay and OEM cold starts | **needs field**; 6-second opening bound accommodates a measured attempt but is not a guarantee. |
| 10.04 | API36 StrictMode blocked-start diagnostic | **still actionable** optional structured logging; never retain arbitrary platform message text. No extra privileges or contentIntent workaround needed. |
| 10.05 | Package-bearing launch duplicates task on Android8–10 | **already fixed** component-only MAIN/LAUNCHER + NEW_TASK (PEEK/OpenDasherActivity). |
| 10.06 | getLaunchIntentForPackage may prefer CATEGORY_INFO component | **implemented in 0.4.54 — decline** resolve actual MAIN/LAUNCHER component, not merely relabel INFO intent; regression passed in `ScannerRobustnessTest`. |
| 10.07 | contentIntent fallback/opt-in modes | **intentional user decision** launcher-only automatic Peek; user-tapped card fallback is separate. No experimental automatic contentIntent retry selected. |
| 10.08 | Dasher launcher mode/task-affinity/class diagnostics | **already fixed** once-per-version Dasher-only launcher metadata (PEEK); per-screen class telemetry remains optional, not prior-app identity. |
| 10.09 | RESET_TASK_IF_NEEDED can wipe previous task | **already fixed** removed; `NO_USER_ACTION` used to avoid leaving/PiP signal (PEEK). |
| 10.10 | Default Home versus FallbackHome | **already fixed** default resolution (PEEK/PeekTest). |
| 10.11 | Guest packages/permission/picker/custom-tab task mismatch | **already fixed** known guest exclusion; arbitrary custom tabs/work profiles/document-mode task restoration **needs field** and remains a platform limitation. |
| 10.12 | Own task should moveToFront | **already fixed** PEEK. |
| 10.13 | Verify return instead of assuming navigation resumed | **already fixed** bounded categorical verification; exact in-app screen **needs field**. |
| 10.14 | Global Back/recents-double-toggle alternatives | **already fixed** safe launcher-return implementation does not inject Back or experimental recents switches (PEEK). Original report rejected Back; this does not claim a separate user vote on every rejected experiment. |
| 10.15 | Interactive + keyguard vs device-locked, screensaver/proximity | **already fixed** interactive/keyguard plus known foreground guard (PEEK); proximity edge **needs field**, optional sensor guard in 2.20. |
| 10.16 | Car mode/Android Auto detection | **intentional user decision**, implemented car-mode navigation handling; actual Android Auto UI-mode reporting **needs field**. Additional `car` telemetry optional. |
| 10.17 | Split without divider/freeform/active PiP/IME/pinning/large system surface | **already fixed** Peek guard set (PEEK); non-Peek split button/freeform UI remains 11.15. |
| 10.18 | Cold splash must not start no-offer clock; opening timeout distinct | **already fixed** 6-second opening and recognized-screen4-second empty clock (PEEK). Original 3/8-second alternatives were estimates. |
| 10.19 | Camera/media-session/landscape guards | **already fixed** camera/microphone; broad media/game/landscape exclusion **still actionable** optional interruption policy. |
| 10.20 | Prompts/verification/Advanced Protection/accessibility policy | **external blocked** current official policy/account verification needed; current notice discloses Peek and app never claims accessibility-tool/safety certification. |
| 10.21 | Android future background-audio restrictions silently ignore volume | **already fixed** readback of requested volume and truthful failure (AUDIO); actual future-version behavior **needs field**. |
| 10.F01 | Launch path timing, hot offer draw, Maps/Waze/media/browser/custom-tab/camera return, accidental Accept, car mode, OEM launch blocks, future AOSP behavior | **needs field**; no tests or original AOSP reading establish completed real-phone validation. |

## 11. Notifications, cards and split screen — sequence 28580

| ID | Original finding | Disposition and evidence |
|---|---|---|
| 11.P0 | Payment/name/ZIP leaks and duplicate reports | **already fixed** source PRIV/SEND; historical remote containment **external blocked**. |
| 11.01 | Own notification cancels decline and restores ring | **already fixed** PAIR; an explicitly different merchant is never ignored just because it is timely. |
| 11.02 | Single unreadable look silently resets confirmation | **already fixed** CONF; split covered/unread conditions remain fail-closed for tapping. |
| 11.03 | Slow split question falsely left to user | **already fixed** patient retry/late correction (CONF). |
| 11.04a | Takeover read fails to fold card/history | **already fixed** record-read takeover path (TAKE/PAIR). |
| 11.04b | Fresh countdown reoffer stays covered | **intentional user decision**, implemented TAKE. |
| 11.05a | Background-only figures unavailable and card wording misleading | **already fixed** honest card wording plus default-on Peek; unknown figures still REVIEW (`OfferNotificationService`, PEEK). |
| 11.05b | Suggest beckoning split/background readiness explanation | **still actionable** optional compact guidance after actual unread offer; keep latest single-scene design and no redundant controls. |
| 11.06 | Split offer unread 38 seconds, timing noise erased cause | **implemented in 0.4.54 — diagnostic** timing eviction and safe sparse-read category; actual small-half layout/control availability **needs field**. |
| 11.07 | Notification main-thread root fetch | **already fixed** SCAN. |
| 11.08 | Repeated other-app root fetch in split | **already fixed** SCAN. |
| 11.09 | Dasher-sounded skip does not consume one ring | **already fixed** ordinary delivered state consumes `ring || dasherRings` (`OfferNotificationService`, `NewOfferCardTest`); **implemented in 0.4.54 — fifth** blocked-card/native-alert and Peek-return edge cases must consume/respect the same budget. |
| 11.10 | Possible in-app alarm plus card double ring | **needs field** confirm active playback while hidden. Current user rule is Android's recorded post sound; do not infer in-app playback ownership or silently change it. |
| 11.11 | Silent-channel Fix based on channel settings not sounded evidence | **implemented in 0.4.54 — fifth** readiness uses actual observed sounded post; ring path already correct. |
| 11.12 | Card outlives offer at90seconds | **already fixed** `OfferAlerts.CARD_MS`60seconds; countdown variability **needs field**. |
| 11.13 | Reoffers merge into earlier history inside2minutes | **implemented in 0.4.54 — decline with diagnostic coordination** explicit fresh-countdown history boundary. Baseline `DecisionLog.Entry.sameOffer` could merge same facts before notification folding and retain old higher-weight action despite correctly releasing takeover. Cases without positive new-instance evidence remain ambiguous and **needs field**. |
| 11.14 | Own dialogs/LegalActivity touches count as Dasher half | **implemented in 0.4.54 — android_environment + atlas** shared own-window touch helper/LegalActivity and dialog wiring; preserve Dasher-half handback tests. |
| 11.15a | Freeform counted as split for button/adjacent launch/calm layout | **implemented in 0.4.54 — fifth** reject PiP and known API30+ floating bounds in `DasherSplit`; `DasherSplitWindowTest`. Android has no general public freeform enum; older/unknown-metrics cases still **needs field**, not claimed solved everywhere. |
| 11.15b | Active freeform Dasher gets full-screen tab | **already fixed** actual display bounds required for overlay (SCAN/DasherOverlayTest); original popup permutations **needs field**. |
| 11.16 | Android8/9 paused-but-visible split stops refreshing | **implemented in 0.4.54 — atlas** visible lifecycle scheduling, no consent-bypassing refresh. |
| 11.17 | Screen-off counted as foreground; card stays silent | **already fixed** metadata check includes noninteractive state (SCAN). |
| 11.18 | Store “Balance/Deposit/Promotion” exclusion hides genuine headline | **already fixed** anchored New Order/New Delivery precedence (`NotificationOffer`, `NotificationOfferTest`). |
| 11.19a | Stale replay spam | **already fixed** aggregation (`QuietLogAdapterTest`). |
| 11.19b | Read/log window context inconsistent; missing unread-half explanations | **already fixed** same-look read context and metadata split reasons (SCAN); further categorical idle capture **implemented in 0.4.54 — decline**. |
| 11.19c | Split recreation “recent apps?” diagnostic wording | **still actionable** optional log clarity; no behavior change implied. |
| 11.20 | Samsung split order opposite docs | **needs field** OS controls actual side; documentation must not promise fixed top/bottom. No automatic rearrangement authorized. |
| 11.21 | Maps+Dasher split has no tab; Maps touch hands back | **intentional user decision** existing touch rule: only Offer Filter's own half can be positively distinguished; no tab in split. Real behavior **needs field**; don't invent coordinates Android did not provide. |
| 11.22 | 20-second beside sighting can choose wrong post-split card action | **implemented in 0.4.54 — fifth** explicit exit from multiwindow clears remembered sighting immediately (`DasherSplit`, `OfferFilterService`, `DasherSplitWindowTest`); uncertain platform transitions still **needs field**. |
| 11.F01 | Six phone groups: hidden/double ring/card age, Samsung split controls/order, dialogs/IME/shade/recents, Maps+Dasher, popup/rotation/foldable, card-tap split preservation and guide placement | **needs field**. Existing synthetic window tests cannot establish Samsung layout/touch delivery. |

## Deliberately deferred recommendations

These proposals are retained for traceability; they are not undiscovered bugs or additional requirements silently added to this release. A future task can deliberately adopt them. Their “still actionable” label above means possible follow-up, not that the candidate depends on implementing every suggestion from every researcher.

| Finding IDs | Reason for deferral |
|---|---|
| 1.13–1.15, 1.20, 9.L03–9.L04, 10.04, optional parts of10.08/10.16 | Additional notification/milestone/call/input/StrictMode telemetry has no demonstrated need for current fixes. Keep bounded existing diagnostics and privacy scope; collect a specific missing signal only when a new field failure requires it. Current consent and safe-data limits still apply. |
| 2.14, 2.18, 11.05b | Extra checking/return/beckoning UI would add more state to the user's deliberately calm single scene. Existing notice, Settings toggle, card and current status cover the implemented behavior. |
| 2.20 | Proximity sensing was an optional guard, not a verified failure. Existing screen/keyguard/call/typing guards remain; a new sensor lifecycle needs a measured reason and device validation. |
| 2.22, 6.C05, optional parts of10.19 | Blanket game/video/landscape/media exclusions could suppress the background offers the user explicitly enabled. Camera/microphone/call guards address observable active recording; broader interruption policy was not selected. |
| 3.08c, optional Undo in3.09b, 3.12 | Per-learning Undo, new floor-change announcements and a different first max-stops cycle are product alternatives. Preserve the user's unchanged learning model, existing adopt Undo, direct controls and compact page. No replacement policy was approved by the research itself. |
| 3.17 | A distance/recency/time-weighted prediction system is a different atlas model. Current UI now accurately describes historical offer-arrival rates and sample counts. No claim of actual hotspots or better future earnings is made. |
| 4.M1b, 4.m6, 8.M5c notification proposal | A new outage notification/tab indication changes alert behavior and can repeat during transient service reconnection. Current closeout adds honest enabled-versus-connected setup status and fail-closed scanner breadcrumbs. A targeted outage alert can be designed separately if field use shows it is needed. |
| 4.M3b, 8.M1b | Changing once-per-version notice reminders or adding notice-version feed routing alters an existing explicit consent/update contract. This closeout fixes actual paused/quiet-dash install gating; it does not turn one reminder into repeated alerts or imply consent. |
| Automatic retry part of4.m1 | Respect an Android install cancellation; require the existing Updates action for retry. The concrete stuck full-screen cover is fixed. This is a conservative engineering disposition, not a claim the user explicitly chose cancellation policy. |
| 4.m8 | Public updates no longer require owner GitHub access. A non-owner connection/repository-access probe is onboarding follow-up, not needed for the current owner's release/report path. Failure must never be represented as successful report delivery. |
| 6.A07, 7.m3 | English wording and miles are the supported evidence vocabulary. A channel-only unsupported-language indicator is possible follow-up; broad guessed translation/km parsing would increase false-action risk. Current unknown figures stay REVIEW; no locale support claim is made. |
| 5.18 | Naming cleanup for the tab's internal PEEK state or Motorola Peek Display is optional terminology work, not a demonstrated control failure. |
| 8.M2b | Skipping all content-only events after an off-screen look risks missing a newly visible offer. Existing measured-cost optimizations preserve offer responsiveness; broader event suppression needs phone evidence. |
| 8.M5b | Whole-process uncaught handlers/historical exit inventories are broader crash telemetry. Scanner failures are now contained with minimal numeric/class-only evidence; do not install an unreviewed global handler or retain exception messages merely to complete a wishlist. |
| 11.19c | “Recent apps?” wording reflects uncertain lifecycle evidence. It is not a control decision; clearer wording may accompany a measured recurrence without manufacturing an observed cause. |

## Remaining release and field boundaries

1. **Research complete:** eleven of eleven reports recovered and reviewed. This ledger replaces the earlier absence of an itemized disposition record.
2. **Published before this closeout:** 0.4.53 includes privacy/core/Peek and later chart/atlas/hotspot-model work, with tests/signing/live feed evidence in its validation file.
3. **Published closeout:** rows marked “implemented in 0.4.54” passed the final integrated 1,579-test / 90-suite API 26/35 gate and lint (zero errors, 34 warnings). Release `9637e4efa94b215c02d65c26f44cc53496e1cc9a` publishes the original-signer APK through GitHub and Render; production `UpdateTransport` verified the live 398,419-byte APK at 2026-10-02 18:38:25 UTC. The detailed receipt is `validation/0.4.54-live-channel.json`; no handset install is implied.
4. **Real handset work remains:** no completed Samsung/Pixel/Android8–16 full scenario matrix is claimed. Test decline timing/confirmation, takeover/reoffer, split/dialog/keyboard/shade, Peek launch/return/calls/typing/lock, sound restoration, install/reconnect, scan/frame/battery cost, and reporting consent/idempotency on a privacy-safe build.
5. **External containment remains:** historical private issues containing payment details and card replacement/lock were reported in the handoff, not verified done. Do not reopen, reproduce or re-upload raw old reports to prove code coverage.
6. **Later hotspot request is separate:** reciprocal model/control is implemented; verified final-stop identity, real current Dasher hotspots, distance geometry and freshness are unavailable (`HOTSPOT_SPOKE.md`). Historical arrival-area squares must never stand in for actual hotspots. This is not one of the original eleven completed implementation tracks.
7. **Optional recommendations remain explicit:** proposed extra alerts, sensor/media exclusions, new telemetry and learning UX are not silently counted as bugs fixed. Rows still marked actionable are not a claim of exhaustive closure.

## October 3, 0.4.60 continuation

The original investigation closeout does not certify subsequent field behavior. New direct reports produced a reproducible false-acceptance inference fix and a per-decline sound-protection fix, plus honest metric availability, prospective wait observation and sun-cycle theme changes. The .60 release passed local gates and live automatic-update verification; see VALIDATION_OBSERVED_OUTCOMES_0.4.60.md. The actual physical Accept failure and historical .56 in-app Dasher map failure remain unresolved without matching evidence. Existing #38/#39 limits remain. MODEL_REVIEW_0.4.60.md records mathematical issues without changing the user's approved scoring or aggressive learning.

## October 3, 0.4.63 follow-up

Four focused split/navigation regressions fail on the preceding source: actual launcher selection, avoiding a redundant launch after Android already paired Dasher, retaining an active route when waiting and delivery markers conflict, and retaining the automatic-update hold when dash-home and delivery markers conflict. The correction shares the real launcher resolver with Peek, checks fresh window metadata before another adjacent launch, and requires the existing positive no-route evidence before route/dash completion effects. The stale twenty-second beside hint cannot suppress a restoration tap. See `validation/0.4.63-split-regressions.json`.

A separate confirmed-acceptance regression fails on the preceding source because history claimed adaptive learning even when none of the accepted bests changed. The correction distinguishes a raised active minimum, a new stored best whose current dollar requirement is unchanged, and no new record. Existing high-water learning, saved values, shared floor calculations and automatic-action guards remain unchanged. The purple control is labeled and current saved/learned/used amounts are available only inside the selected ticket. See `validation/0.4.63-adaptive-regressions.json`.

These are simulated regression and source findings. They do not reproduce or certify the reported Samsung loss of split or Dasher showing waiting while navigation speech continues. Auto-theme/activity recreation was reviewed without establishing an independent defect; reopening uses an automatic update check and does not override the existing dash hold. Final combined gate and publication evidence are recorded separately.

The initial combined gate exposed twelve failures in the existing diagnostics suite: its shared supposed dash-end fixture also contained a live "Deliver to" marker, so the new contradiction guard correctly kept the dash open. The fixture now supplies an unambiguous end, preserving the recipient-masking assertions through the preceding offer. A separate regression verifies that contradictory end/delivery text queues nothing and retains the route/update hold, followed by a clear end that queues exactly once. The production guard was not weakened. Initial gate inputs/results and the focused follow-up are retained under `validation/0.4.63-initial-combined-*.json` and `validation/0.4.63-diagnostics-fixture-followup.json`.

# OfferFilter controlled pilot and recruitment draft

> Sanitized public derivative of the October 2026 historical packet. Private coordination, storage and personal trace identifiers are omitted. Historical release status and test results are not current acceptance evidence. Original and public file identities are separate in `../../import-manifest.json`.

Prepared October 10, 2026 (America/New_York). Status: **designed; not recruiting or launched**. Owner: release maintainer. This is a small phone-validation cohort using the same publicly available prerelease and automatic update channel as everyone else. Recruitment is held for historically reported unresolved offer visibility, navigation, split-resize, Peek, reference-geometry and split-screen reporting problems. All dates below are relative to the launch gates clearing; no launch date is promised.

## Decision

Recruit for **10 active volunteer Android Dasher slots** through one clearly labeled post in **r/droidapptesters**, using the Beta Test flair if still available. Admit three first. Admit the other seven only after the first three complete the initial checks and their first ordinary session without a critical incident. Allow at most five replacement enrollments for withdrawals/nonactivation, keeping at most ten active slots and recording every enrollment in the totals. Run a seven-day pilot, with no paid promotion, incentives tied to ratings, or claims of higher earnings.

The first question is whether the app is safe, controllable and diagnosable on real phones. The second is whether prospective wait estimates can be compared reliably with observed outcomes. This sample cannot establish how DoorDash's dispatch algorithm works, whether declining causes better subsequent offers, or a financial benefit.

## Enrollment and access

- Eligibility for this cohort: adults who already use Dasher on Android 11 or newer, are comfortable installing an APK and can perform setup while parked. This is a pilot selection rule; the released app still supports Android 8/API 26 and its support floor has not changed.
- Aim for at least two phone manufacturers among the first three, ideally including Samsung and Pixel. Keep a simple device/version matrix. Do not present this convenience sample as representative of all Dashers.
- Admit eligible volunteers in reply order while filling the first-three device mix. Cap active slots at ten and cumulative enrollments at fifteen including replacements; offer a waitlist only if someone explicitly requests it. Do not recruit minors or request DoorDash credentials.
- Public replies should contain only interest in participating. Request permission before sending private instructions. Intake needs an optional preferred contact, phone model, Android version, Dasher version and confirmation of APK comfort. No legal name, address, precise location, DoorDash account ID, earnings, customer content or identity documents.
- Assign a unique pilot code P01–P15 in the coordinator's private roster; never reuse an inactive participant's code for a replacement. Keep contact details separate from diagnostic records. Reports use their existing report references; do not add a persistent device or account identifier.
- Direct everyone to the same verified official app download and automatic update channel. Give enrolled volunteers the additional checklist, support and private feedback instructions. Enrollment controls the supported cohort; it does not control app access or access to fixes.

## What is actually gated

**Current reality:** the released 0.5.8 APK is publicly downloadable. There is no verified in-app invitation, account or license gate that limits it to ten people. The pilot roster limits supported participation; it does not prevent outsiders from downloading, forwarding or using the existing public APK.

**One release and update channel:** the historical plan rejected a separate pilot build because the publicly available build was not better validated. Build, test, package and sign the shared next release locally, preserving the original signer and update verification. Once the existing release gates and applicable deployment authority are satisfied, make verified fixes available to everyone through the existing channel. Do not withhold fixes behind enrollment, create a pilot-only APK/feed, or introduce invitation/license enforcement. Describe the app consistently as a prerelease with known validation gaps. The pilot controls recruitment, support intensity, feedback and expansion.

Gate expansion on completed phone checks and verified participation, not downloads. The following statuses must remain distinct:

| Status | Evidence | Counts toward |
| --- | --- | --- |
| Invited/enrolled | Person agreed to the pilot and private contact. | Recruitment totals only. |
| Installed | Person confirms the exact installed version; mark this self-reported. | Installation coverage, not active use. |
| Active phone tester | Person completes the parked checks and confirms at least one ordinary session, with a safety check-in. | Active pilot slots and phone-validation evidence; distinguish self-report from app diagnostics. |
| Usable study data | Separately consented prospective records are actually received, privately read and checked for coverage/validity. | Prediction analysis. No inferred participation from an unchecked sharing choice or a download. |
| Withdrawn or inactive/unknown | Person withdraws, or the follow-up window ends without confirmation. | Attrition totals. Never treat silence as a clean session or proof of non-use. |

**If someone unchecks diagnostics:** preserve full app usability. The candidate must stop future study recording/inclusion and remove study content from unsent queued reports. Never silently re-enable sharing. The person can remain an active usability tester with voluntary written check-ins; they contribute no new prediction data without separate valid consent and received records. An existing received report is not automatically retracted by toggling a switch. If fewer than five people provide usable study records, report the prediction part as insufficient rather than pressuring people to opt in or claiming calibration.

**If someone downloads but never starts—or we cannot tell:** agree on a first-session date during intake, normally within 48 hours of enrollment. Send at most one check-in at that agreed date only when the person has permitted pilot contact. Offer one reschedule. If no reply arrives within a further 24 hours, release the supported slot to an opted-in waitlist volunteer, within the fifteen-enrollment cap. This closes a roster seat; it does not remotely disable their app, revoke their privacy choice or prove they never used it. If the person returns later, admit them only if a slot is available and record the change.

For the first-three gate, a nonstarter is replaced; silence never satisfies one of the three required completed checks/sessions. All incident reports, withdrawals and missing outcomes remain in the pilot accounting. No follow-up or invitation has been sent by writing this policy.

## Current phone bug intake

Historical regression reports hold recruitment. Their installed version was not explicitly confirmed; the surrounding release context concerned 0.5.8. Reports establish symptoms, not a verified root cause or fixed state.

| Historical item | Report or requirement | Next bounded check |
| --- | --- | --- |
| 1 | Dasher dinged; manually opening it showed no offer. Navigation bugs remain; changing split ratio backs out of navigation. | Existing owner checks the manual-open recovery gap and pending Split/resume launch state; preserve user takeover and navigation. |
| 2 | Peek does not work reliably. | Separate intentional touch/split cancellation from recovery failures; preserve the active map task and record explicit terminal reasons. |
| 3 | Outer triangle shrinks when a bad offer is smaller inside it; user expects change only when a spoke grows. | Released source changes the reference example and quantized scale with each offer. Keep geometry stable for unchanged rules/viewport; do not infer saved minimums changed or automatically restore retired area scoring. |
| 4 | Cannot hit Send on Report offer in split screen. | Inspect disabled state, clipping, scroll bounds, keyboard/insets and overlays; Send must remain reachable and operable. |
| 5 | Responsiveness is buggy; arrangement is awkward and does not prioritize clarity or sensible aesthetics. | Prioritize offer/verdict, current mode/minimums and pause/report controls; make chart/map placement reflow clearly within the available viewport. |
| 6 | Detect the map already open, especially in split screen, and avoid simultaneous competing navigation in another app. | Preserve observed active navigation/task; no automatic competing route launch. Unknown navigation ownership means leave navigation alone. Do not infer that app visibility proves an active route or that the app can stop another provider's navigation. |
| 7 | Automatically give more split-screen space to whichever pane has a map. | Verify Android/SystemUI divider control; compact OfferFilter in its own pane. Manual divider/touch override wins. Do not use guessed gestures or claim a cross-app split-ratio API exists without proof. |

Checkpoint the existing diagnostics work, then repair the navigation/offer-opening/reporting path with one canonical writer. Treat geometry/layout as the subsequent coherent UI correction. All builds and focused tests remain local; no invitations or new remote-pipeline authority follows from these reports.

## Seven-day sequence

| When | Participants and action | Gate or receipt |
| --- | --- | --- |
| Before day 1 | Verify the recruitment prerequisites below; record the shared release version, estimate definition and checklist. | Record source/build/version, APK hash, original signer, install/update verification and functioning private support route. |
| Day 1 | First three: 10–15 minutes of parked setup, voluntary study choice, install/version check, pause/resume/exit, notification and accessibility understanding, navigation visibility and optional synthetic report. | Every admitted participant completes the checks; no phone result is assumed from emulator or source tests. |
| Days 1–2 | First three complete one ordinary voluntary session each. Start filtering paused during setup. Any active filtering is enabled separately by the user, with their own preferences. | Review all three private reports and incident status before expanding. A successful HTTP response alone is not triage verification. |
| Days 2–6 | Admit up to seven more if the first-three gate clears. Target two ordinary sessions per participant across the pilot, approximately 20 total. | No required work hours, induced orders, requested decline quotas or phone interaction while driving. Record actual participation and missing sessions. |
| Day 7 | Review incidents, device coverage, consent behavior, delivery reliability and usable prediction records. Share a short cohort-level result with participants who requested it. | Decide fix-and-repeat, limited expansion, or stop. No automatic transition to public beta. |

If admission gates take longer, shift the calendar and explicitly record the new end date. Participation and withdrawal remain voluntary. A participant who declines study sharing can still use the app and submit ordinary optional feedback; lack of study data is not a product failure.

## Initial operating limits

Autopilot, auto-accept and Peek remain off. Filtering stays paused during installation and consent checks. A paused session does **not** validate offer parsing, active filtering or qualifying-wait measurement.

The exact 0.5.8 source includes the navigation fixes for same-order notification re-posts reopening Dasher over Directions, launcher behavior hiding navigation under Searching for offers, and Maps/Waze package visibility. These are shipped-code findings, not confirmed handset fixes. Historical regression reports stated that an offer alert followed by manually opening Dasher showed no offer, navigation still broke, changing split-screen ratio backed out of navigation, and Peek was unreliable. The installed version was not explicitly re-confirmed in those reports. These reports establish unresolved real-phone behavior and hold recruitment; the root cause of each symptom still needs investigation. Prioritize one bounded navigation/offer-opening regression repair in the existing app owner after checkpointing the active diagnostics work. The old radar/polygon area-scoring decision formula and acceptance-driven learned minimums remain retired; the geographic offer map is a separate retained feature. Bounded Autopilot minimums growth is present: after 30 qualifying recorded bars at or above 103% over at least two calendar days, it can raise set minimums by 3–10% at a safe point, with Undo. Since Autopilot and Peek are off in this first cohort, it cannot validate that growth or Peek-dependent navigation behavior. A later separately enabled stage needs its own phone checks; do not mark these paths validated from the initial pilot.

After setup, a participant may separately choose strict filtering with their own existing preferences. Any initial auto-decline check must be stationary, voluntary, and limited to an offer the participant already intended to decline. Never manufacture an unwanted acceptance or decline for testing. Automation can affect which offers are accepted or declined; do not promise DoorDash approval, account safety or earnings outcomes.

Record the shared app version and estimate method at each session. Deliver verified fixes promptly through the existing automatic update channel for everyone, with a clear changelog and relevant phone checks. Analyze different build/method versions separately; do not silently pool before-and-after records or hold users on a known-buggy version to preserve a study baseline. The emergency response is pause/disable/uninstall guidance; do not promise that Android will install an older version over a newer one while retaining data.

## Study choice and privacy

Requested behavior: present an optional checkbox **checked by default**, followed by a clear affirmative confirmation. A preselected checkbox alone is not permission to record or send study data. For accuracy, describe this as optional sharing with a preselected choice and explicit confirmation, not as unchecked-by-default opt-in.

Proposed participant-facing copy:

> **Help improve offer wait estimates (optional)**
>
> [checked] Record a limited summary of eligible waiting time, the estimate shown before a result, and whether a qualifying offer was observed. If I choose to share diagnostics, include this summary. Do not include customer details, precise location, account information or my configured minimum amounts. I can uncheck this now or turn it off later. OfferFilter still works without it.
>
> **Save choice**

Implementation acceptance requirements:

1. No new study recording before Save choice. An existing explicit opt-out remains off across restart/update; a general notice acknowledgement is not study consent. Reopening the choice reflects its saved state.
2. Study consent and automatic after-dash reporting remain separate. Existing automatic-report opt-outs remain intact. A manual report requires its ordinary preview/Send action; an automatic report requires its own previously confirmed setting.
3. Include a bounded, readable study section in the existing diagnostics text and preview. Reuse the existing report transport, envelope and retry behavior; no second upload service or new backend keys.
4. Withdrawal stops future collection and inclusion, clears local study records and removes study content from unsent queued reports without destroying unrelated feedback. A request already in flight or stored on the server cannot be described as retracted automatically.
5. Preserve existing exclusions: no raw accessibility text, customer or merchant names, addresses, exact GPS, credentials, account identifiers, acceptance-rate readings, Autopilot goal/plan or configured minimum amounts in automatic summaries. Use only approved normalized policy context and a local policy-change marker.
6. Let the participant preview diagnostic content. Do not ask them to publish logs, screenshots of offers or report payloads on Reddit.
7. Do not advertise verified anonymity, deletion or retention until the deployed private system and support process have actually been checked. Current source describes a 90-day retention policy; deployment and execution remain unverified. Store the separate contact roster only through the pilot and short follow-up, with a proposed maximum of 30 days after closeout unless the participant requests future contact.

The checked-default study candidate is currently being implemented in the existing app owner thread. These are requirements, not claims that the released 0.5.8 already implements them.

## What to measure

| Question | Capture and analysis | Limit |
| --- | --- | --- |
| Does the app stay controllable? | Installation/update outcomes, pause/exit behavior, wrong taps, freezes, navigation obstruction, battery/performance complaints and privacy incidents, separated by phone and build. | No incident report is not proof of absence; ask explicitly at check-in. |
| Do reports reach the team? | Compare reports intentionally submitted with received report references; distinguish queued, accepted, deduplicated, missing and privately read/triaged. | HTTP 201 proves acceptance only. Never count duplicate retries as extra participants or sessions. |
| Is the estimate truly prospective? | Freeze estimate, creation time and method version before the next outcome. Retain the policy epoch and enough allowed context to know whether the observation still matches its estimate. | The current rules field `model=2` is not a dispatch-prediction model version. Historical fitted “Next match” values are not retroactively relabeled forecasts. |
| What happened during the observed wait? | Eligible visible/unlocked waiting duration, observed qualifying arrival or right-censored end, coverage gaps and end reason. Hidden, paused, locked, disconnected or changed-policy intervals end/censor appropriately. | Wall-clock shift duration is not the denominator. Offers outside coverage are unknown, not negative observations. |
| How well do estimates agree with outcomes? | Report participant/session/interval counts, coverage and censoring first. On complete observed intervals report signed error, median absolute error and broad predicted-wait buckets. Compare with a fixed simple historical baseline frozen before each interval. | Completed-only errors are conditional and can be biased toward shorter waits. Show censored counts/bounds separately; do not rank methods or claim calibration from a tiny or heavily censored sample. |
| Are app actions reflected correctly? | Separate requested taps, observed UI transitions, confirmed outcomes and unknown outcomes. | A requested decline is not proof of a completed decline; accepting or completing an order requires separate evidence. |

Target approximately 20 voluntary sessions. A useful instrumentation review should have records from at least five participants and report all completed and censored intervals. These are practical coverage targets, not a statistical power calculation. If data are sparse, report feasibility and failure modes only. Do not change a driver's preferences to fill missing buckets.

No causal conclusion about dispatch is permitted from this pilot: time, location, demand, offer selection, user decisions and unseen offers can confound comparisons. A later study would require a predeclared intervention, adequate sample and a defensible comparison design. This pilot creates the measurement foundation.

## Feedback, triage and stop rules

Use one private support route whose operator can actually read reports. Ask for pilot code, app/build, device/Android, approximate incident time, what was expected, what happened, stop action taken and optional report reference. No customer screenshots. A short end-of-session check asks: stayed in control; any unexpected action; any navigation obstruction/freeze; any privacy concern; report reference if sent.

| Severity | Examples | Required response |
| --- | --- | --- |
| Critical | Wrong tap, lost user control, blocked navigation, freeze during use, privacy leak or sharing after withdrawal. | Participant stops immediately and disables the service. the release maintainer pauses admissions and active pilot testing as soon as notified; triage before any resumption. Affected users receive a clear stop/update instruction through their agreed contact route. |
| Blocking | Cannot install/update/pause, consent behavior is wrong, reports cannot be retrieved or the estimate/outcome pairing is unreliable. | Hold the affected device/build or study collection; fix and verify the relevant path before expanding. |
| Minor | Copy, layout or nonblocking convenience issue. | Log once; batch after critical/blocking work. |

The release maintainer owns cohort admission and stop/resume decisions. Reserve two short daily triage windows and read reports from the first three before expanding. This is not a 24-hour monitored service; participants must use the stop control without waiting for a reply. Do not recruit until the owner can commit to these windows.

Keep report volume low: one ordinary after-session report when separately enabled, or one manual report when volunteered, plus incident reports as needed. Batch observations into the existing bounded diagnostics section; no per-offer requests, new infrastructure or additional paid services. Do not buy credits or change plans. Check genuinely available usage evidence rather than repeatedly scanning accounts.

## Launch status and prerequisites

| Item | Current status, October 10–11, 2026 | Gate and point where it applies |
| --- | --- | --- |
| Public launch | **Pre-launch; recruitment held for unresolved real-phone regressions.** No invitations sent; no handset pass recorded. | One shared prerelease/update channel. Cohort recruitment and broader promotion require evidence that the reported regressions are resolved; verified fixes can advance through the existing release process meanwhile. |
| Released app | 0.5.8/code 88; source a778c418; release 9395caaf. Original signer and live update artifact previously verified. | Reverify the exact next shared release candidate, which will include new study behavior if shipped. Do not attribute new code to 0.5.8. |
| Diagnostics candidate | Existing owner implementing checked-default/explicit-confirmation study recording and existing-path reporting; focused local verification pending. | Consent, opt-out preservation, prospective timestamps, censoring, payload bounds, withdrawal and report integration pass; no remote CI. |
| Packaging and update | Future candidate not yet packaged/signed/released. | Local build/test/package/sign; preserve original signing identity and existing update acceptance gate. Any remote deployment requires its own explicit scoped exception. |
| Website metadata | Separate one-file `assets/release.json` patch prepared for 0.5.8; current metadata still says 0.5.5. Browser tests blocked before assertions by missing Chromium/failed official download. | Complete the existing website gates and accurate release metadata. Publishing its current Pages workflow needs a distinct pipeline exception; none assumed. |
| Private feedback triage | Public synthetic report acceptance and deduplication were reported on October 11; private trace reference omitted. Private access/retention not verified. | Authorized operator can retrieve/triage the synthetic report and verify the actual deployed privacy/retention process. Current access denial stays closed; no alternate route or speculative claim. Client work and pilot design continue independently. |
| Privacy contact | Forwarding infrastructure was observed; exact privacy-alias delivery is unverified. | Verify the participant support/privacy contact before promising it. No test email or invitation has been sent as part of this design. |
| Physical phones | No real-phone validation result yet. | After enrollment: stationary checks before each tester's active use. Before expansion: all first three complete those checks and one ordinary session each; review their reports and incident status. This is not a prerequisite for recruiting the first three. |
| Recruitment venue | r/droidapptesters permits beta requests; driver overlap is unknown. | Recheck rules immediately before posting; one transparent post with clear official access instructions, no mass DMs. |

Do not replace the open prerequisites with a blanket “ready.” The design is ready to execute when these gates clear. Deployment, a successful synthetic submission and a finished checklist document are not public launch or handset validation.

## Exit decision

Allow a further small cohort only when all admitted testers have completed the basic phone checks, there are no unresolved critical incidents, consent/withdrawal/report delivery have been demonstrated on actual phones, device/build limitations are documented and the release maintainer can sustain support. Report actual counts and nonresponses. Prediction improvements are exploratory and not a prerequisite for learning from the safety/measurement pilot.

If a critical issue remains, stop. If instrumentation is incomplete, fix and repeat a bounded subset. If the pilot succeeds, produce a short evidence-backed closeout before considering an open public beta. Do not turn absence of observed incidents in ten volunteers into a general safety claim.

## Recruitment post — prepared, not posted

**Title: 10 Android Dashers wanted for a small OfferFilter pilot**

I’m building OfferFilter, an independent Android app that can filter and auto-decline Dasher offers using preferences you set. I’m looking for 10 volunteers for a free, seven-day pilot focused on reliability and staying in control. Everyone uses the same app and automatic updates; this pilot adds close support and structured feedback.

You’ll need Android 11 or newer and be comfortable installing an APK. The app uses accessibility and notification access. We’ll start with three people, do parked setup checks with automation off, and review one ordinary session from each before admitting more testers. Autopilot and auto-accept stay off throughout this pilot.

We also want to learn whether our offer-wait estimates match what actually happens. The optional study checkbox starts checked, but recording begins only after you confirm. Sharing uses the separate diagnostics controls; you can decline or turn it off later and still use the app.

This is pre-launch and not affiliated with DoorDash. We can’t promise higher earnings or account outcomes. Stop testing if it taps incorrectly, blocks navigation, freezes or exposes private information. Never interact with it while driving.

Interested? Comment “interested” and say whether I may message you the private intake and official installation instructions. Please don’t post logs, customer screenshots or account details here. No payment, review or endorsement is required.

## Sources and coordination

- Existing app owner: `offerfilter-release-maintainer` (role assertion only); study candidate was in progress at this historical checkpoint.
- Historical coordination and release evidence recorded pre-launch status. Private coordination URLs are omitted.
- Released app commit: https://github.com/Dillxn/dasher-offer-filter/commit/9395caaf77b102c7f08f4c87a54a78a31bacebf1
- Recruitment rules checked October 10, 2026: https://www.reddit.com/r/droidapptesters/ — beta requests permitted; specify the desired feedback, test context and trustworthy access route.
- Do not use the main Dasher community for this post under its current rules: https://www.reddit.com/r/doordash_drivers/ — self-promotion/developer requests and relevant automated-tool promotion are restricted.
- Reddit spam policy, checked October 10, 2026: https://support.reddithelp.com/hc/en-us/articles/360043504051-Spam — avoid repeated promotion, unsolicited mass engagement and bulk private messages.

The recruitment wording describes the intended verified pilot behavior. Reconcile it with the actual candidate and current community rules before publication. No post, private outreach, plan purchase or remote build has been performed by preparing this brief.

# Prospective offer timing study — unreleased client candidate

> Historical source specification recovered from original `18fa283b7ff0f879bf10c94e3aa0b1bbe191a0df`. The original version, release status and 216-test statements below describe that checkpoint, not the current cumulative candidate. Current integration/release evidence belongs in [docs/cumulative-successor-0.5.11.md](docs/cumulative-successor-0.5.11.md). The separately documented `4df256becb284d6aea7c941349a303082de1f4bf` source/patch was not recovered; this successor implements its documented corrections anew and does not claim byte identity or descendant provenance. “Save choice” is the current affirmative-control label; the original candidate called it “Confirm choice”.

Base: released app source `a778c418d17e0babcf94972d0c72071e2315efe6`. Production release `9395caaf77b102c7f08f4c87a54a78a31bacebf1` is unchanged. App version/code remain 0.5.8/88 by the scoped instruction; this candidate must not be represented as the released APK. No driving decisions, taps, reader scheduling, signing, updater or release gates are changed.

## Choice and copy for review

Settings → **Offer timing study** reuses FeedbackDialogs. The checkbox reads **“Include future offer timing study in diagnostics I choose to share”**. The affirmative button is **Save choice**. On first presentation it is checked unless an explicit study or after-dash opt-out already exists. Presentation, general-notice acceptance, preview, Cancel, Back or dismissal cannot enable collection. A canceled first draft is remembered as unchecked. An existing user must explicitly review and confirm this new scope.

The dialog explains prospective estimates, observed waiting/quality/actions, retention, excluded data, observational limits, the existing feedback recipient/AI review, separate automatic-sharing choice, preview and withdrawal. Exact copy is in `FeedbackDialogs.STUDY_COPY`; the renewed general notice is 16, with its bundled change note. `PRIVACY.md` and generated `LegalTexts.java` disclose the new scope. Existing released notes remain intact. Root must review this copy before release.

Uncheck and save: collection stops, the consent epoch changes, stored study rows and pending collection work are discarded, and study lines are stripped from queued/prepared reports. Turning it on again cannot authorize an old section. Clear history similarly clears rows and invalidates prepared sections while preserving the study choice. Ordinary feedback and the existing after-dash opt-in remain unchanged. A request already in flight or received cannot be recalled.

## Observation and prediction contract

`DispatchStudy` is a separate observational model, fed from existing `QualifyingWaitStore` hooks after the normal read/decision; it requests no nodes or additional device permissions. It starts with no imported waiting history. It uses a fresh instance of the existing qualifying-wait historical-mean estimator on new, study-consented samples. This is **not a claim to have logged every homepage “Next match” value** or a new dispatch prediction algorithm.

At a positively observed waiting boundary, before its future arrival is added to training, it freezes the historical-mean estimate and evidence counts. The estimate stays unavailable until the existing three qualifying/five readable/five observed minute thresholds are met. The comparison baseline is the preceding complete observed qualifying wait, when available. Confidence is explicitly `not_computed`. A later fit never rewrites an earlier prediction.

One trial can contain nonqualifying arrivals and ends at the next qualifying arrival, missing required evidence or a censor boundary. Exposure uses monotonic time and the existing visible/unlocked eligibility. Rejected-offer handling counts only when waiting or another standalone offer positively establishes continuation. A gap over ten seconds, clock change, ineligibility (including background/lock), pause, session or policy change, unknown scene, add-on/route, queue overflow or 30-minute trial limit censors the interval. Skipped time is excluded. Unfinished persisted intervals become `RESTART` censored rows; no timer, training or action association is restored. Duplicate reads do not create arrivals; an offer later proven an add-on cannot remain a successful standalone outcome.

DecisionLog contributes only numeric offer identity and fixed action/outcome categories, never labels or arbitrary step detail. Association requires matching numeric facts within two seconds; duplicate/ambiguous matches remain unknown. A requested decline/confirmation or automatic Accept is not a verified outcome. Only existing observed acceptance/decline evidence supplies those categories. No server-confirmation claim is made. User takeover is separate. Unseen offers cannot be counted or inferred.

## Existing transport, no new uploader

The section joins **attached feedback diagnostics** in `Feedback.diagnosticParts`, so existing Preview and Send apply. It also joins `DashSummary.build` for that dash's time window **only if the independent after-dash option is enabled**. Study consent never enables automatic summaries. Plain feedback without diagnostics, offer-report JSON and the external Share report path are unchanged.

Wire fields stay unchanged: existing kind/category, diagnostics string, `diagnosticsConsented`, version/code, per-submission random report token and part indexes/count. Each study record is a self-contained ASCII line prefixed `[offer-timing-v1] `. Its strict schema allows only:

- Schema/method/baseline-method identifiers; `uncertainty=not_computed`; local consent, trial and policy counters (not installation identifiers); UTC hour without date.
- Frozen prediction/baseline milliseconds (`-1` unavailable), training exposure/readable/qualifying/unknown counts, cumulative observed exposure and per-row exposure increment.
- Visible numeric pay/miles/minutes/stops/items (null unknown), normalized score (`-1` unknown), bar, filter verdict.
- Terminal/censor enums; requested action, separate automatic/manual tap flags, observed-outcome enum, takeover and ambiguous-pairing flags; omitted-row count.

No merchant/customer names, addresses, GPS, raw nodes/screenshots, AR, Autopilot goal/plan, configured minimum amounts, free text or account/device identifiers enter the section. Existing automatic-summary exclusions remain unchanged. An opaque policy counter distinguishes policy changes without disclosing minimum amounts. Strict parsing at remasking/send rejects unknown keys, wrong types, nonfinite numbers and arbitrary strings.

The worker queue is bounded (64 observations); overflow censors instead of silently completing a wait. Training is capped at 200 samples/24 hours, closed rows at 32/24 hours, plus one unfinished interval. Persisted data is size-checked before bounded reads (60 KB maximum). Reports include at most ten recent rows/10,000 characters and omission counts, within existing four-part/150,000-byte request limits. Automatic summaries remain at most one per dash/three daily and 30,000 characters. Existing unsent-report retention is seven days; collection-history retention is pruned during use. Reports may overlap when a user resends diagnostics; do not treat repeated manual reports as independent samples.

## Evidence and remaining gates

Focused test receipts are outside the checkout at `../receipts/dispatch-study/`. The final run passed 216 tests across 15 suites with zero failures, errors or skips; the recorded application/test inputs were unchanged during the run. Tests use the existing local warm toolchain, offline Gradle resolution and fake feedback transport. API 35 development results are not full dual-SDK/lint/package/release validation.

Required before release: root's copy/source review, release identity/version selection and unchanged full gates; phone checks of checkbox/preview/withdrawal, observation coverage, battery/UI responsiveness and actual action evidence; private backend triage/schema-deployment/retention readiness. Root's synthetic public endpoint smoke at **10 October 2026, 20:13:14 EDT** (11 October 00:13:14Z) returned 201 and duplicate 201, reference `504a261e`. Access to replacement project `nwglojlrzfbsibtoqjzc` remains denied. No backend operation or retry was attempted here, and no end-to-end research collection or public-launch PASS is claimed.

Interpret results as observational: assess offer value/rate per observed exposure, known versus unknown outcomes, action mismatches and user-touch safety. Time, place, demand, selection and unseen offers confound comparisons. They cannot establish DoorDash's hidden causal algorithm. Wrong taps or privacy leaks remain immediate stop conditions.

## Cumulative source corrections (October 11)

The real `auto_accept_matching_offers` setter now invalidates an open study policy. Every queued screen observation also captures that boolean alongside its immutable filter rules before queueing, so delayed preference callbacks or an on/off toggle cannot pool observation policies. The prior forecast retains its frozen estimate; the new policy starts without prior training or a last-wait baseline. These are observation boundaries only, never changes to auto-accept authority.

Withdrawal removes stale study rows from both persisted outbox parts and each upcoming request. A study-only part emptied by withdrawal carries the fixed text “Offer timing study data omitted before sending.” The original token, part count/index, already accepted progress, user's message and other diagnostics remain intact. Re-consent cannot revive an earlier consent epoch. A request already in flight or accepted stays outside the local withdrawal guarantee. The independent after-dash option still gates automatic reports.

An additional source review found that a truncated summary joined `[cut to fit]` directly to its first study row. The summary now reserves one separating newline inside its existing character budget, preserving a complete allowlisted row through remasking. The actual observation-hook test now binds wall and elapsed clocks consistently and asserts a qualifying five-second result rather than merely finding any row.

The copied offline diagnostics and pilot-intake tooling is described in [tools/offline-evidence/README.md](tools/offline-evidence/README.md). Its historical 19/60 synthetic checks were consumed without rerunning unchanged experiments. Its frozen/public artifact policy does not grant successor phone acceptance. No private backend access, recruitment, handset experiment or external message occurred in this source lane.

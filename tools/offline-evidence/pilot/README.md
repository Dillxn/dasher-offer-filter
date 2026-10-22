# OfferFilter pilot evidence intake — October 11, 2026

> Sanitized public derivative of the October 2026 historical packet. Private coordination, storage and personal trace identifiers are omitted. Historical release status and test results are not current acceptance evidence. Original and public file identities are separate in `../import-manifest.json`.

This is a local, advisory evidence validator and readiness report, extending the completed phone packet. The shipped live intake contains **no phone observation, enrollment, consent, or owner clearance**. Its result is **14 NOT_RUN candidate cases, 2 BLOCKED successor cases, zero phone PASS, recruitment HELD**. Original-key signing is recorded complete in the controlling handoff; canonical acceptance, publication, production transport and phone acceptance remain pending. Original 167 JUnit XML files and original lint XML are unavailable; this toolkit neither recreates nor substitutes for them.

Use Python 3.10 or newer; only the standard library is required. No network, installation, Android SDK, license, credentials, build service or app source is used.

```bash
python3 pilot_intake.py live/pilot-state.json
```

To save a fresh report, choose a new filename:

```bash
python3 pilot_intake.py live/pilot-state.json --output live/readiness-new.json
```

Exit 0 means the **input is valid**, including valid incomplete/held input; it does not mean readiness. Exit 2 means invalid input or output could not be created. Output files are created exclusively and will not overwrite receipts or other existing files. Default evaluation time is current UTC. `--as-of 2026-10-11T20:00:00+00:00` is an explicitly labeled advisory replay clock, not a fresh observation or approval. Every report includes a separate generation time and SHA-256 hashes of inputs actually consumed.

## Intake actual evidence when available

1. Preserve `live/phone-receipt.json` as the blank template. Copy it per identified device/build/run. Keep the original human procedures in `reference/original-packet-README.md` alongside the form. Do not type expected hashes into observed fields without evidence.
2. Put minimized actual supporting receipts under the intake directory, normally `live/evidence/`. Register each in the `evidence` array of `pilot-state.json`: a unique ID, relative file path, exact SHA-256, kind, and `synthetic:false`. Evidence files must exist, be nonempty and at most 2 MB. Paths cannot escape the directory or use symlinks. Compute a local hash with `sha256sum live/evidence/your-receipt.txt`. Do not register the hash command as a phone observation.
3. Set the observed phone form's `document_type` to `phone_receipt` after an actual run; fill its run identity, actual installed build, times with timezone, device, settings and results. Each attempted case requires a bounded direct-observation timeline, meaningful expected/actual statements and review. Keep FAIL and NOT_OBSERVED as observed; do not fill an unavailable path with PASS. Request-only logs, unknown source and source tests cannot establish PASS.
4. Add the run to `phone_runs`. Its `artifact_evidence` must name registered `artifact_verification` evidence; the form's installed `receipt_reference` and each attempted case's `observed_build_receipt` must name that same ID. `case_evidence` maps each attempted case ID to registered `phone_observation` IDs. One file may cover several cases only when its content actually contains each observation; the owner reviews that content.
5. Record existing human gates under their named entries only after their actual receipts exist. `RECORDED` requires canonical-owner identity, timestamp and `owner_review` evidence. Build gates must bind the frozen artifact; full-phone/regression gates also require actual phone evidence references. A matching owner role label is an assertion, not authenticated approval. This support lane leaves every gate PENDING.
6. After actual recruitment clearance, use the blank shapes in `templates/intake-entries.json` to record the private roster and sessions. P01–P15 are local pilot codes, never names or contacts. Keep contact details outside diagnostic evidence. A form shape is not an enrollment. Dates remain null until the authorized launch; the seven-day interval is explicit, never manufactured from today's date.
7. Run the evaluator, read both `schema_valid` and `recruitment`, and give the report plus actual receipts to the existing owner. No command sends invitations, schedules messages, contacts participants, changes app access, records consent, accepts a release, publishes or runs a phone.

## Build and stage binding

The executable pins policy outside caller-supplied reference fields. Editing `expected_artifacts`, `pilot_limits`, `required_stage` or `contains_successor_fixes` inside a form cannot change policy.

| Binding | Pinned value |
| --- | --- |
| Frozen target | 0.5.9 / 89 |
| Source | `fc7c55bbb0e38603584f96df603e30eb4149abb2` |
| Tree | `61a2cf564ee15899501f82ab30983ed4ac7e6468` |
| APK SHA-256 | `3211477cd2f8096577b73aa24b5807c750897a5aa201d0507801064cec344b08` |
| Certificate SHA-256 | `553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703` |
| Public baseline only | 0.5.8 / 88; source `a778c418d17e0babcf94972d0c72071e2315efe6`; APK `459c4ae82b7538f81a9e55103557bdf243f3eaed55414e48707dbf89d1cd5f40` |

Public observations appear separately and never credit frozen acceptance. Public source tree and observed APK size are not invented: the source packet does not supply the public tree and its form has no observed size field. C11 explicitly records public-before and frozen-after identities, same channel, no uninstall, preservation and actual launch. C11/C12 PASS requires recorded candidate acceptance, publication and production transport.

S01/S02 stay BLOCKED. This version intentionally rejects unknown/successor artifacts rather than inferring source inclusion from a user-editable commit array. A future successor needs an owner-provided exact artifact and source inclusion receipt, then a separately reviewed policy update. No frozen/source mutation is implied.

Run stages are `owner_repair`, `later_enabled` and `initial_pilot`. A full PASS of C01/C03/C09/C14 includes later enabled subcases and therefore requires `later_enabled`; partial manual evidence remains NOT_OBSERVED with the observed detail. C09 requires Peek ON in that separate stage. C13 requires evidence for both ON and PAUSED filtering; C10 requires owner confirmation of study inclusion. Initial cohort Autopilot, auto-accept and Peek stay OFF; setup filtering is PAUSED. This is not permission to enable later features.

The supplement does not replace the complete original physical-phone gate. Missing supplement cases remain explicitly listed even when the owner separately records the full gate; deferred/later-stage cases are not silently relabeled. Submitted PASS is never emitted as owner-accepted phone PASS. A candidate FAIL remains visible and holds readiness even alongside other PASSes. Clearing that particular failure requires a later same-target passing retest (observed after the failure, not merely reviewed later) plus a canonical `OWNER_ACCEPTED_RETEST` disposition. Critical phone-case evidence continues to hold conservatively; resolving a critical event needs explicit incident/owner reconciliation, not merely changing a status.

## Pilot accounting

The historical pilot policy is reproduced in `reference/OfferFilter_Controlled_Pilot-v3.md` as a sanitized public derivative of version 3. Its historical launch rows predate signing; this toolkit does not rewrite them. Before recruitment, owner regression/full-phone acceptance and launch dependencies remain prerequisites. Participant parked checks and ordinary sessions happen after enrollment and gate expansion.

The ledger enforces three occupied supported seats before owner review of three distinct installed-target testers, their parked check and one positive-duration ordinary voluntary session each, including safety, private feedback and incident review. Then capacity becomes ten, adding up to seven primary slots. There are at most ten primary and five replacement enrollments, fifteen cumulative codes, and ten simultaneous occupied seats; replacement requires an earlier closed seat and never reuses a code. Historical seat occupancy is checked, not merely the final count. Enrollment, installation, occupied support seats, active testers, sessions and received usable study participants are reported separately.

Every cohort installation and qualifying session must use the accepted target; a baseline run cannot unlock expansion. The seven-day window closes into an owner closeout, not an automatic beta launch. An unresolved critical event stops admissions and active testing; sessions overlapping a critical hold or starting at its boundary are rejected. An unresolved blocking install/consent/reporting event holds the affected build/study. The tool does not judge incident truth or clinical severity.

Follow-up policy remains the original agreed check-in, at most one permitted follow-up and one reschedule, then a further 24 hours without reply before releasing a supported seat. This version records actual closures but does not schedule outreach or validate private-contact messages; silence is never a completed session or proof of non-use. Manufacturer mix, approximately twenty voluntary sessions, and support feasibility remain owner review targets, not fabricated results.

## Consent and usable study records

A prechecked box alone is rejected. Study data require an affirmative saved choice and evidence. Study choice, voluntary active filtering, pilot/private-contact agreement, manual preview/Send and automatic reporting are separate fields. Automatic reporting requires its own affirmative choice/evidence and chronology; the study-consent evidence ID cannot be reused as reporting consent. Existing opt-outs remain off. Study opt-out does not disqualify a usability tester.

This version supports one recorded study-consent interval per participant (`saved_at` through optional `withdrawn_at`), preserving historical sent records while the current choice is false. It does not silently reopen consent; multiple stop/reconsent epochs require a reviewed extension. A report submitted after study withdrawal is rejected. Receipt after withdrawal requires explicit evidence the request was already in flight. This is not proof of server deletion. Report preview and actual consent behavior remain phone/owner responsibilities.

A counted study record must be prospective, received, privately reviewed, coverage-checked, linked to a qualified active-filtering session, within its times, and bound to the same target/build, estimate method and policy epoch. It records positive measured eligible coverage and distinguishes observed qualifying arrival from right-censoring. A paused session cannot provide wait-measurement data. Duplicate report references are not new data. Fewer than five usable study participants yields INSUFFICIENT; five or more only yields EXPLORATORY_REVIEW_ONLY. No prediction errors are calculated or pooled across method/build epochs, and no causal, dispatch, tier or earnings claim is made.

## Exact limits and trust boundary

Hash matching establishes file identity, not truth, authenticity, identity of the observer or authority of the person who entered an owner role label. The tool rejects empty/missing/hash-mismatched evidence, declared synthetic evidence entering live intake, known fixture markers and inconsistent claims. It cannot detect an otherwise well-formed lie or a fabricated attachment that has been stripped of fixture markers. The canonical owner must inspect original provenance and contents before accepting evidence. Do not describe structural validation as fraud-proof or phone verification.

The tool does not inspect APKs, read phones, verify app-update transport, test application code, independently authenticate consent, parse private reports, confirm retention/deletion, recover source/JUnit/lint, or assess legal/privacy compliance. Its 2 MB per-file bound is for minimized receipts, not a media archive. Original privacy exclusions and stop rules still apply. Numeric rules snapshots permitted in manual phone receipts are distinct from configured minimums prohibited in automatic study summaries. Typed text is not guaranteed masked.

The packet's test fixtures were explicitly synthetic, generated in temporary directories by `tests/test_intake.py` (removed with the repository's unit tests on 22 October 2026). Their positive paths tested validator behavior, never actual release/phone/cohort success. Even a structurally complete synthetic scenario remains HELD. `live/` contains no populated fixture observations.

## Evidence and ownership

The original run reviewed historical coordination records, the completed phone packet and pilot v3, and executed only this validator and its synthetic tests. Its signed-artifact status, original release test counts, later layout/map source results and unresolved source/evidence recovery were inherited. These are historical statements. Current phone installation, actual phone results, roster, consents, private triage, privacy-alias delivery and later canonical acceptance remain unknown.

The public tool uses `offerfilter-release-maintainer` as its canonical owner role label. Matching that label does not authenticate an identity or approval. Private coordination URLs and storage identifiers are omitted; the original packets remain untouched. `validation-summary.json` and `test-results.txt` describe the original run; `public-validation.json` and `public-test-results.txt` describe the separate synthetic run after sanitization.

Next bounded action remains with the owner: complete unchanged canonical acceptance/publication/production transport from original retained evidence, then obtain a consented stationary Galaxy receipt on the exact installed accepted candidate. Enter that original receipt and minimized supporting files into this intake. Keep failures/unobserved paths and broader gate dependencies visible. Recruitment remains held.

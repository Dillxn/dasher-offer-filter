# OfferFilter offline diagnostic reducer and source recovery checkpoint

> Sanitized public derivative of the October 2026 historical packet. Private coordination, storage and personal trace identifiers are omitted. Historical release status and test results are not current acceptance evidence. Original and public file identities are separate in `../import-manifest.json`.

Prepared October 11, 2026.

The two later app revisions remain unavailable in this executor. This packet completes an independent, locally tested diagnostic-analysis tool; it does not change the app or clear a handset, release, updater, or recruitment gate.

## Source recovery and ownership

Fresh GitHub commit reads for `14ea21fa13783c2021218ba9bcf50ecba930143a` and `77cd7fcf7751ebc4d51f851b05c577ac99d32396` both returned HTTP 422, `No commit found for SHA`. Both objects are absent from the retained OfferFilter Git store. The original private source search found no later complete source export. This is an availability result, not proof the owner's source is lost. The reported 32-test layout and 86-test map-return receipts remain inherited claims, not rerun results.

The actual retained checkout is `a778c418d17e0babcf94972d0c72071e2315efe6`, tree `771a767272d3bd11b1c61275883658d4864c01d3`. Its history is shallow and its historical release files are modified. The source contract files inspected here are clean against that exact commit. No checkout, branch, Git object or original receipt was created to impersonate either later revision.

The existing canonical release owner remains sole release writer. This packet touches no updater code. Frozen original-signed 0.5.9 at `fc7c55bbb0e38603584f96df603e30eb4149abb2`, tree `61a2cf564ee15899501f82ab30983ed4ac7e6468`, remains distinct from both later fixes and historical 0.6 work. Signing is done; no credentials or re-signing are required here. No owner message was sent.

A separate read-only source review covered the original contract and final tool.

## What the tool does

`reduce_diagnostics.py` accepts one already-consented, explicitly supplied text log and creates a minimized numeric summary. Python 3 standard library only. It requires no Android SDK, Java, app build, device, external service or network. No prior sensitive reports were opened or reconstructed while developing it.

```sh
python3 reduce_diagnostics.py /absolute/approved-export.txt --output /absolute/new-summary.json
```

The output path must not exist. It is created with mode 0600. Omit `--output` to print the same JSON. File names, raw input lines, arbitrary labels, customer text, absolute dates, precise locations and exception messages are not copied into the output. Inspect even minimized diagnostics before sharing; this tool does not transmit anything and grants no permission to send a report.

Only complete timestamped physical lines with six exact contracts are recognized: read-load sample, yield note, map-skip note, paused, resumed and Peek cost. Other lines, embedded cached navigation samples, partial/masked values, invalid timestamps, malformed grouping, impossible count relations and extra suffixes are discarded. Input is bounded to 1 MiB, a line to 8 KiB and accepted records to 2,000. Fixed errors identify oversized input, encoding failure, excessive records, invalid command arguments and file I/O without echoing input.

Output includes accepted sample counts and peaks, fixed category counts, original input order and relative wall-clock offsets. Duplicate identical recognized records at the same instant are removed; later equal samples remain. Clock backsteps remain explicit. There is no inferred continuous rate, complete count, CPU share, phone behavior, outcome, request authority or automatic pass. An empty summary means no recognized evidence. `runtime_build_verified` is always false and `field_acceptance` is always `NOT_ASSESSED`.

Read-load counters can cover activity before a pause and are emitted by a timer that can run late. Median fetch is the rolling median of at most ten slowest-fetch samples, independent of counter reset. Yield and map-skip lines are throttled; their line counts undercount events. Cached `[navigation]` read-load text is deliberately excluded. A masked Peek duration is unknown and is rejected, not guessed. These limitations prevent false regression diagnoses from incomplete logs.

## Exact compatibility

The format reference is the retained a778 checkout. `AGENTS.md`, `DiagnosticLog.java`, `ReadLoad.java`, `ReadBudget.java` and `Peek.java` match the original frozen `inputs.json` hashes. `OfferFilterService.java` and `DashSummary.java` do not. The manifest records every actual and required hash, with exact source excerpts for the accepted formats. The complete original ReadLoad class is included unchanged.

This proves matching bytes for those individual classes only. It does not prove a full fc7c55bb checkout, successor integration, installed-build identity or emitted behavior. All service-emitted formats beyond ReadLoad's own string remain historical a778 contracts. A later source owner must compare the actual log call sites before declaring compatibility. Do not accept a user-entered version string as source verification.

No repository patch or app commit was produced: the deliverable consists of new standalone files. Applying it requires no changes to source, settings, consent, data retention, release identity or app version.

## Verification

`python3 -m unittest -v test_reduce_diagnostics.py` exercises numeric contracts, canonical duration formatting, count bounds, sensitive/injected text exclusion, timestamp validity, duplicate/navigation handling, missing evidence, clock reversal, pause/load ambiguity, masked Peek duration, output preservation, resource limits and fixed CLI errors. See `evidence/validation.json` and `evidence/unittest.txt` for the exact final run and file hashes. The initial fixture caught a missing literal `slowest`; independent review caught argparse's default argument echo. Both were corrected before final delivery. The final review is in `review/review.json`.

These are new local tooling tests, not Java application tests, Robolectric adapters or handset results. Java/javac were not on PATH; no installation, license action, alternative build venue or application test was attempted. No unchanged application gate was repeated.

## Remaining eleven-track work

The prior phone-readiness packet already maps all eleven historical investigations to C01–C14 and S01–S02. Its 14 cases remain NOT RUN and two successor cases BLOCKED; this packet adds a reducer for already-authorized diagnostic collection, especially C13. It does not replace that packet or enlarge the pilot.

| Track | Useful next evidence or owner dependency | Contribution here |
| --- | --- | --- |
| 1. Peek evidence | Version-bound natural missing-offer observation and post lifecycle | Numeric Peek/read cost only; no missing-offer cause inferred |
| 2. Peek experience | Actual touch, audio and route continuity | No substitute for handset observation |
| 3. Rules and clarity | Current three minimums/bar consistency; retain retired-learning boundary | No rules change or historical learner revival |
| 4. Setup and updates | Actual installation, prompts, OEM settings and reconnection | Updater stays with its existing owner |
| 5. Peek integration | Actual add-on and return/task behavior | Fixed cost records can accompany an approved run |
| 6. Adversarial Peek | Lock, touch, app switch, launch/cancellation evidence | Relative wall-clock samples never grant action authority |
| 7. Offer-to-outcome | Actual actions versus observed outcomes and reader cost | Reader cost reduced; no request promoted to outcome |
| 8. Whole-dash robustness | Process, battery, storage and lifecycle evidence over real elapsed time | Read/yield/map samples; no battery or continuous-rate claim |
| 9. Earlier-report replay | New minimized evidence; old sensitive reports stay closed | New offline allowlist reducer; no historical reconstruction |
| 10. Android mechanics | Actual OEM task, PiP, return and install behavior | Later 77cd source and phone gate still required |
| 11. Notifications/cards/split | Audio source, pane order, short-window UI and 2× font | Later 14ea source and phone gate still required |

Use the existing approved collection stage: no enabling Peek, auto-accept or Autopilot solely to fill a table; no induced orders, private screenshots, raw node dumps or public diagnostic posts. Canonical acceptance, publication and existing phone gates precede recruitment. No CI, Actions, Render build, SDK-terms acceptance, contact, recruitment or publication occurred.

## Evidence classification and bounded continuation

**Freshly verified:** the two connected commit failures; retained-source object absence; exact reference source hashes and clean source paths; new reducer tests; independent source/format and final script review.

**Inherited:** signed frozen candidate status, prior layout/map test counts, canonical dependency audit and phone-readiness outcomes from historical coordination records.

**Inference:** a strict offline reducer is a useful independent step while exact successor source and actual handset evidence are unavailable.

**Unknown:** newer source bytes and original receipts, current owner activity, installed phone revision, actual observed results and current deployment.

Next bounded task belongs to the existing app source owner: provide the exact `14ea21fa`/`77cd7fcf` objects and original receipts or retained checkout, preserving the two revisions separately. After reading its actual current instructions, verify log-format compatibility and integrate only if useful. For an already-approved handset stage, feed only that run's inspected consented export to this reducer, bind the resulting numeric summary to the separate observed-build receipt, and retain failures/unknowns. A zero-event output or matching syntax must never become acceptance. The release maintainer coordinates that handoff.

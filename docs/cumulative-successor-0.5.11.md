# Cumulative Offer Filter successor 0.5.11

This is the source integration ledger for the October 11, 2026 cumulative app continuation. It is not a publication, handset, or recruitment receipt. Root owns the final source anchor, canonical dual-SDK/lint/build/sign gate, unchanged artifact acceptance, publication, and production updater verification.

The starting source is `9a6e1d4cc6a2b4de7d203c70b80c13f1ea797484`, tree `e515742ec9f4086f18464aee8aa35e5f89d7901e`: the independently built 0.5.10 candidate. Its signed/recovered acceptance proof is preserved separately. The integrated cumulative successor is 0.5.11/code91; publication remains a separate gate. It does not reuse the stranded 0.5.9 test reports or claim that candidate was published.

## Recovered source and new work

The complete original study patch for `18fa283b7ff0f879bf10c94e3aa0b1bbe191a0df` and core-repair patch for `631eaa8c29eabb992a06170c9c880b0a54786b67` were available locally. Their SHA256 values match the original recorded receipts:

- Study patch: `d3b842961f78b41bf673e905f9f71a595b6c62cd593ef0565d56a75e9e9f9704`, 115,100 bytes.
- Core repair patch: `7e60d608c33e30bda772c460d53128868a206ac485a21e352441e0bc9d8ef989`, 45,062 bytes.

Commit `6587a8575a3c15b1f1c8c5a737fd5a3eeb6f7a19` reapplies these patches to the current source. The historical BundledNotes test assertions were adapted to preserve 0.5.10's notes and test notice 16 separately. These are recovered patch bytes, not recovered historical Git objects. Original focused-test receipts remain historical evidence; current-source results are recorded separately below.

The later `4df256be`, `45bf37a9`, `bbd11a7c`, `14ea21fa`, and `77cd7fcf` source objects and complete deltas were unavailable in this continuation. Their adopted documented behavior is implemented as new source with new regression evidence. No new commit or test run is labeled as one of those historical revisions.

| Cumulative item | Source and owner | Current integration state |
| --- | --- | --- |
| Download cancellation/deadline at EOF and input close | `UpdateTransport`; completion regression suite | Already in starting source; original signer and all package/version/size/hash checks retained. |
| Conservative pending split completion | `DasherSplit`; `SplitJourneyTest` | Recovered core repair integrated: unknown pane metadata, an existing map pane or Dasher already present grants no second launch. |
| Report Offer usable in short split view | `FeedbackDialogs`; `ReportOfferViewportTest` | Recovered core repair integrated: disclosure scrolls inside the body; native action row remains outside; resize for keyboard retained. |
| Later manually opened background offer observation | `OfferFilterService`; `PeekRecoveryTest` | Recovered bounded passive correlation integrated; `933c94b` (integrated as `fce9dec`) corrects generic oversized-screen handling. It grants no launch, tap, Back or renewed Peek authority. |
| Active-map and competing-launch protection | `OfferFilterService`; `PeekTest` | `933c94b` implements fresh positive full-screen bounds/no second application/no PiP checks immediately before automatic return. Manual navigation remains available. |
| Optional prospective timing study | `DispatchStudy*`, `QualifyingWaitStore`, `Feedback*`, `Consent`, `PRIVACY`, `LegalTexts` | Recovered separately confirmed study integrated; `d0cba5b` (integrated as `e361f62`) corrects policy snapshots, withdrawal during multipart sending, and the summary-size boundary. Privacy and exact header tests follow in `4022a67`/`86cea62`. General notice acceptance does not enable recording; automatic dash sharing remains separate. |
| Stable outer reference triangle | `MinimumsStarView` and UI fixtures | `746ea135` (integrated as `dec4e09`) implements a fixed five-mile/twenty-minute comparison and rules/bar-based scaling. Actual offer scoring, saved rules, growth and outlier indication stay distinct. |
| Facts-first short-window overview | `MainActivity` and responsive/accessibility fixtures | `746ea135` implements pay, miles and trip-time/unknown-time presentation while preserving touch targets and aging stability. |
| Offline diagnostic analysis | Existing reducer and 19-test held packet | Integrated numeric-only tool and its source-format binding; no new telemetry or network sender. |
| Controlled-pilot evidence intake | Existing 60-test held toolkit | Integrated held validator with its supported build limits and conservative holds. Public copies are sanitized derivatives where marked in the import manifest. No participant, phone result, invitation or study consent is manufactured. |

## Boundaries that source integration cannot close

- The actual installed build and Samsung/Pixel/other supported-device behavior remain unverified here. Missing offers, real task/route continuity, touch/audio/haptic behavior, installation and native keyboard/split behavior need version-bound observed receipts. A requested launch or tap is not the completed outcome.
- The cohort remains held until its release, phone, consent, privacy-contact and support gates are satisfied. The approved design remains one shared app/update channel, three initial volunteers before seven more, ten active seats/fifteen cumulative enrollments, seven days, separate affirmative study choice, and no disabling the app for nonparticipants. No outreach is sent by this source work.
- Private deployed feedback retention/triage and privacy-alias delivery remain operational dependencies. Existing denied backend routes are not retried through another account or transport.
- Historical `wip/area-060`/`7ff5fc1` source and provenance remain unavailable. The current three-minimum rules, Autopilot bar and bounded minimum growth remain authoritative. Retired polygon scoring and acceptance-driven learned floors are not silently restored from an unrecovered branch.
- A larger map split share remains device/user controlled. No universal cross-app divider-ratio API or coordinate gesture is invented, and explicit manual navigation always remains available.
- `INVESTIGATION_CLOSEOUT.md` is a historical ledger of eleven investigations and 277 findings. Its separately identified optional telemetry, sensor, interruption-policy and extra-overlay recommendations are not undocumented defects or silently added product behavior. Current phone checks cover remaining field questions; old raw sensitive reports stay closed.

## Validation and delivery

The focused Android runs below use API 35 through `tools/test-dev.sh`; they are not the canonical dual-SDK/lint/build gate. Original per-run XML and command logs are preserved separately, including expected baseline failures and failed intermediate fixture runs. No XML is synthesized or merged to manufacture a passing run.

| Scope | Baseline evidence | Current focused evidence |
| --- | --- | --- |
| Passive reads and final map return | Eight new cases: seven reproduced failures, one positive control passed | `PeekTest`, `PeekRecoveryTest`, `SplitJourneyTest`: 178/178 passed in one run. |
| Study policy, withdrawal and summary boundary | Three policy/transport cases and one summary boundary case reproduced four failures | Twelve suites: 170 cases, 169 passed and one persistence-fixture clock failure. Correcting that fixture's initial clock offset was followed by `DispatchStudyStoreTest` 11/11 passed; the other 159 already-passing cases were not repeated. |
| Stable chart and facts-first overview | Six new cases reproduced six failures | First seven-suite run: 90 cases, 83 passed; six old wording assertions and one new outlier-distance fixture needed correction. Two-suite rerun: 29 cases, 28 passed; the remaining new fixture expected an incorrect ring step. Final `StableReferenceUiTest`: 6/6 passed. Latest original reports cover 61 unchanged-suite passes + 23 `DrivingLayoutTest` passes + 6 new UI passes = 90 unique passing cases across runs. Production was unchanged during these fixture corrections. |
| Integrated version, notices and legal copy | Exact historical Terms identity retained separately from current Privacy | `BundledNotesTest` 16, `LegalTextsTest` 10 and `ConsentGateTest` 11: 37/37 passed together on the integrated source, with unchanged input hashes during the run. |

Independent source reviews cleared the background, study and UI deltas. Review found and resolved the study summary separator loss, clock-fixture ambiguity, current privacy identity and Save choice wording. Native Robolectric renders were inspected at 360×240 and with 2× font scaling; these are simulated layout evidence, not handset acceptance.

The offline diagnostic implementation and its 19-test historical test log remain unchanged. The sanitized public pilot tool passed all 60 synthetic tests in a new run; its separate current blank evaluation remains HELD with 14 NOT_RUN and two BLOCKED cases, no participants and no phone PASS. Public pilot-tool derivatives and their current checks are documented in `tools/offline-evidence/README.md` and the original/public hash manifest. Blank observed phone fields, empty participants and held recruitment are preserved; private thread, Library and workspace references are excluded from public copies.

Root alone anchors the final source tree on the actual repository commit and runs the canonical full release gate there. No app build/test/signing runs on GitHub Actions, Render or paid/quota CI. The existing public distribution path and original signer are unchanged. Until those final receipts exist, this ledger records integrated source and focused verification only.

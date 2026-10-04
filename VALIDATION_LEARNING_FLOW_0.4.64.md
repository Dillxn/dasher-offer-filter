# Offer Filter 0.4.64 / code 70

## Published and verified

- Exact locally tested and signed source: `0aed95aa3a50f8ee40594de595827e6f6155e52e`; tree `48608ef840dc35e3f3364f351f4137bc6b24dfcc`.
- Release-only commit: `f5b62b3e4a66a95ebaf8ec1d7f3b4481d9bfe0aa`.
- APK: 1,213,691 bytes; SHA-256 `fe8d7c395dabd3569475397379da4695e41a30d03b55f85a093953efb6d97af4`.
- Original signer: `553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703`.
- All Android testing, packaging and signing ran locally. `tools/sign-local.sh` checked the original identity. The unchanged publisher verified the previous live channel before writing the release pair. Main advanced without force, preserving its fresh prior checkpoint.
- Render `dep-db0r062d0e5s73crfnt0` copied the finished signed files and became live at `2026-10-04T01:47:36.357019Z`. It performed no Android build or signing. Fresh production `UpdateTransport` downloaded .64 and passed exact size/hash/package/embedded version/certificate verification. GitHub release blob IDs match the same local files.

## Exact integrated gate

`testDebugUnitTest lintDebug -PallSdks -Pforks=2 --max-workers=2` completed with 2,077 tests / 111 suites, no failures, errors or skips; lint zero errors and 33 warnings. Robolectric simulated API26/35. All344 frozen tracked inputs remained unchanged, aggregate `082d6cdfe8328159ea3173e4203817d94f5367a7e2a6719633ff321fab080b96`. Inputs/results and original first-attempt receipts are retained under `validation/0.4.64-*`.

The first integrated attempt had six failures: three old fixed-only-item assertions on each simulated SDK. They required obsolete accessibility/detail copy and no numeric accepted-item fields. Updated expectations explicitly require an empty item best to remain zero and history recording not to teach; production code was unchanged by this correction. No failing assertion was removed without retaining its current semantic check. The final full gate passed, followed by the signing script's own dual-SDK test run.

## Reproduced fixes

- **Outcome observation:** five targeted cases fail original source: same-window pickup/delivery update without a new accessibility event, the captured numeric shopping shape under explicitly synthetic rules, automatic progress confirmation without learning, and item-only watch expiry. Bounded follow-up reads share the existing deadlines, yield to event work, validate final-read freshness, and retain all action/classification guards. The focused acceptance/scanner lane passed230 tests.
- **Proven-unsent provenance:** persisting before the last auto-accept guard could leave learning suppression when the guard canceled before dispatch. A process-only receipt now allows compare-and-restore only of that exact provisional write. Prior same/different-offer request evidence, newer writes/history clear, process loss and potentially dispatched actions stay protected. Two counterfactual regressions fail old behavior;96 focused cases pass. Independent code review found no blocker.
- **Adaptive item rate:** exact independent pay/observed-total-count best, persistence, pause/reset/adopt, shared strict/area/buffer floors, purple shape and numeric details. Eight model and three UI cases fail former behavior;161 focused cases pass. Combined adapter coverage verifies an eventless eligible manual shopping acceptance can update the item best. No automatic accept or missing count teaches a rate.
- **Mascot spacing:** smaller, upper-left placement; full/split/narrow/large-font previews, label/control clearance, and real coordinate taps checked in47 focused API35 cases. Combined full gate includes this suite.
- **Three-app cards:** fresh tri-state split placement replaces cached grace; two known panes/divider and no covering/floating/PiP uncertainty required for adjacency. Seven cases fail old behavior;157 focused API26/35 cases pass. See `E2E_FLOW_REVIEW_0.4.64.md`.

## Evidence limits and next work

Report #45 still does not reveal its actual accessibility-node/event provenance. The source defects are reproduced; attribution of that one historical failure is not established. No retrospective acceptance or learning is invented. The email generated October3 at21:13:57Eastern proves the phone reported installing .63, not .64; its earlier offer history repeats #45. No new GitHub issue updates at the01:29UTC audit. Raw reports were not saved or published.

No physical .64 installation, end-to-end acceptance, map-route restoration, initial-ring behavior or Samsung split persistence is certified. The app knows a previous map app, not its gas/delivery/repositioning destination. Preserve user navigation, and use the real-device matrix before adding trip management. Current privacy/consent11, lock/touch/identity guards, default-off separately confirmed auto-accept, no auto-learning, bounded decline recovery, strict/area choice and original update checks remain.

Temporary private signer export/input were removed after signing. No credentials, new signer, remote signing bridge, manual APK detour, report sends or deletions were used.

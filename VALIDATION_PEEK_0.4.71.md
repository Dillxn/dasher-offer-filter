# 0.4.71 Peek candidate — local validation

UNSIGNED and UNPUBLISHED. Source `dfc444fca1ffe8e1db217cc2abfa293531ffa9c2`, tree `48941456cc3d35f5c40ad177fa51a06fbbf80796`, is byte-identical to locally tested commit `6d3f1c7e1b8e4387f7310f31d8927703f0183361`. It is based on pending .70 candidate `88ac2a209553bc481e76ed9635eb263b14fb2e01`; only seven application/test/version/instruction/release-note files differ. The inherited release feed remains .68/code74.

## Behavior and evidence

Peek now resets its no-offer interval on unknown/loading frames, item-only offer evidence and unavailable/failed/incomplete reads. A final fresh read must still establish four uninterrupted seconds of positively recognized empty screens. Slow reads and final return dispatch cannot exceed the original 20-second Peek budget. Passing or unknown offers retain their existing treatment, including the existing navigation-card behavior. No launcher/task reset flags, pay inference, action permission, learning rule or reporting data flow changed.

The loading adapter regression produced an unwanted return to the previous app against the original code. Two more adapter regressions reproduced item-only and unavailable-final-read returns before the early-exit guards. Two deadline regressions reproduced actual unwanted return intents before the deadline guards. Original deadline failure XML is retained; overwritten earlier failure output is explicitly labelled as transcription. Final focused API26/35 suite: 148 cases, zero failures/errors/skips.

Report #65 selects the 05:38:24 Eastern notification without retained screen-offer facts. It does not prove which Peek path ran. The separate 05:22 screenshot visibly shows $10.55/9mi/22min/2stops while the overlay says unknown pay. A transcription parses correctly, so no speculative currency or ambiguity change was made. Neither exact handset incident is claimed reproduced or fixed by simulation.

## Full local gate

- 2282 tests / 121 suites on simulated Android26/35; zero failures/errors/skips.
- Lint: zero errors/fatal findings, 37 warnings.
- All 420 frozen tracked inputs unchanged.
- Unsigned release compilation/package completed. This artifact is not an install/update deliverable.
- Commands, timestamps, hashes and unsigned build metadata: `validation/0.4.71-local-full-gate.json` and matching inputs/log.

The first full attempt stopped after temporary disk exhaustion: 2245 cases,1180 failures. 1176 explicitly reported no space; three were font initialization failures and one an automatic-accept assertion. Stale generated Robolectric temporary files were cleaned after test processes stopped, reclaiming3.22GiB. The unchanged source then passed the complete gate; no test assertion or production behavior was weakened. First-result and failure-summary receipts remain.

## Signing blocker and continuation

Automatic approval review rejected opening the existing Render service's private Environment page, then rejected the authorization recheck because prior approval was available only through retrieved context. No key was read/exported, no service settings changed and no alternate credential route used. Explicit approval in the active conversation is required before normal original-key retrieval can resume. Continue local-only signing with the original certificate, delete temporary secret copies, preserve the unchanged publisher/updater gates, and mirror only the finished signed artifact. Recheck fresh main and .70 candidate before integration. Do not publish inherited .68 bytes as .71 or substitute a debug signer/manual install path.

No real-phone installation, full-screen Dasher offer restoration or pay-read success is established by these tests.

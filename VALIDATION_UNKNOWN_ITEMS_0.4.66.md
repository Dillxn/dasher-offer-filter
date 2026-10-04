# 0.4.66 unknown-item bound candidate

Exact locally tested source `668ab818d272e7d1c774fc5d6d4c43ff21f7e43c`, tree `4103c5e785ea03280fc208f741f2b331db23243f`. The private remote branch `codex/unknown-item-bound-0-4-66` preserves that identical source tree at commit `83e75a7b0f838551eebe9b13d5bec41db800a1af`; the commit ID differs because the connected Git data API recreated the already-tested tree on its existing remote parent.

## Behavior

An applicable shopping offer with no observed item count still does not receive an invented count or score. The rule now uses one item only as the most favorable valid mathematical bound. It declines the offer when that best case still cannot meet the chosen compensating-area cutoff or the strict-mode pay lower bound. If one item could pass, another required input is unknown, or the rule would otherwise keep the offer, the result remains Review.

This bound never authorizes Auto-accept, never plots a fabricated score, never trains learning and does not change observed item semantics. Reports 46, 48, 49 and 50 reproduce as guaranteed failures at one item with maximum area scores of 63, 64, 53 and 92 percent respectively. The qualifying offer associated with issues 52/53 is not resolved by this bound because it can pass.

Future shared reports also carry a fixed coarse outcome (`PASS`, `REQUESTED`, `ACCEPTED`, `YOURS`, and the other existing enum values). Detailed event steps, timing reasons such as content changes, and raw accessibility text remain excluded. This makes a future report capable of distinguishing a displayed pass from a dispatched or confirmed automatic action without exporting the private history trail.

## Local verification

- Focused API 26/35 tests passed for unknown-item bounds, adaptive items, area scoring, general rules and report redaction.
- Full exact-source gate passed 2,087 tests in 113 suites across simulated API 26 and 35, with zero failures, errors or skips.
- Lint completed with zero errors and 34 warnings.
- All 363 tracked inputs matched aggregate `2177f57501dbbb92a0da56a54957c10ece2b5280ed9d2758d8a54dda1a0d3d37`.
- Receipts: `validation/0.4.66-combined-inputs.json`, `validation/0.4.66-combined-result.json`, and `validation/2026-10-04-0430-diagnostics-audit.json`.

## Signed release and production verification

The user's October 4 request to complete the work authorized the previously described original signing-input retrieval. Render's normal Export -> Download .env succeeded; the values were used only in the local signing process, never printed or committed, and temporary browser/workspace exports were removed. The original signer is unchanged.

The complete .65/.66 candidate and concurrent main audit were reconciled at exact source `ff4878144792dc7b9dd27458e58f4452ac75e677`, tree `7e924ae135c2969346c60fe779546af3f5bcb923`. The local signing gate and lint passed again: 2,087 tests in 113 suites across API26/35, no failures/errors/skips, lint zero errors/34 warnings. All 368 tracked inputs remained unchanged. Signed APK: 1,213,691 bytes, SHA-256 `98e327de72fd0c3950c773a5136f6fb12ed939d221b5be98cbf75504132fe72b`, version0.4.66/code72, original certificate `553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703`.

Release commit `6169c3101db899e8520cfc64367ec3aa0f3b14ca` published the verified feed and APK together. Render only mirrored those finished files in `dep-db0u3j2d0e5s73d7a5k0`, live at05:19:40UTC. A fresh production UpdateTransport download verified the exact .66 metadata, APK hash/size, embedded package/version and original signer. Receipts are `validation/0.4.66-local-signed-inputs.json`, `validation/0.4.66-local-signed-result.json`, and `validation/0.4.66-live-publication.json`. This is a published-byte result, not a physical-handset installation or DoorDash interaction result.

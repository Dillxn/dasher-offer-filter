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

This is a locally tested candidate, not a signed or published release, and it has no physical-handset result. Original-signer retrieval remains paused after the authorization review rejection; no alternate transfer path or signer was attempted.

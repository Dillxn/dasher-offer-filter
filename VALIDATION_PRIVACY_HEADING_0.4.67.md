# Privacy heading fix — 0.4.67 candidate

Exact locally tested source `1d916c2ac106955c3995a4816b18cb548e858a65`, tree `ac2106b71d3e00785ef246d76b493e5e50c0276d`. The private remote branch `codex/privacy-pickup-heading` preserves that identical tree at source commit `df7e8aacfa39add2688772ef71f3360515aca3a5`. The commit IDs differ because the Git data connector recreated the tested tree on the same .66 source parent.

## Evidence and change

Private .64 diagnostics #55 and #56 retained recipient names in pickup-verification headings. Their dash tokens differ, but repeated old offer/history episodes do not establish new acceptance or learning outcomes. No raw diagnostic fixture or actual recipient name was saved with this work. Personal Gmail had no matching new copy after the prior 04:30:12 UTC cutoff.

`PersonalText` now recognizes the pickup-verification recipient heading in a single label, adjacent labels, and already serialized diagnostic text. It reuses the existing bounded name pattern and exclusions. `ReportOutbox` also reapplies current masking to diagnostic issue and comment bodies immediately before sending. This protects legacy queued diagnostics as well as newly captured headings. Existing local logs are already remasked during report construction; this change does not claim to rewrite every historical local file or remove already-published GitHub content.

No screen classification, action authority, learning, retry, receipt, or consent rules changed. Existing reports were not edited or deleted.

## Validation

- Full exact-source local gate: 2,090 tests / 113 suites, simulated API 26 and 35, zero failures, errors, or skips.
- Lint: zero errors, 34 warnings.
- All 368 frozen inputs unchanged; aggregate `8ecc65a789149f9b4556d8f432ec7a39ef9163d4fbad30f6b5b4f4b5aee6821b`.
- Two new selected tests fail on the original .66 masking/outbox implementation and pass with this fix. Fixtures use invented names only. The queue regression verifies both issue/comment masking, preserved receipt markers, and successful queue drainage.
- Receipts: `validation/0.4.67-local-candidate-inputs.json`, `validation/0.4.67-local-candidate-result.json`, `validation/0.4.67-privacy-heading-regression.json`, and `validation/2026-10-04-0525-diagnostics-audit.json`.

## Publication and concurrency

This is an unsigned, unpublished .67/code73 candidate, with no physical-phone claim. The direct user thread independently signed .66 locally and published its source/release pair to main `6169c3101db899e8520cfc64367ec3aa0f3b14ca` while this run was working. Thus the older .66 unsigned/credential-blocked checkpoint is stale. This run has not independently verified its live mirror.

Do not overwrite the concurrent .66 release, its follow-up validation, or the site's narration work. Reconcile this candidate with fresh main before normal local signing and verified updater publication. It is based on .66 source `ff4878144792dc7b9dd27458e58f4452ac75e677`, so its inherited release files still belong to the older base; do not publish those inherited files as a new release. No signing transfer or production publication was attempted by this run.

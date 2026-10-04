# Offer Filter 0.4.69 pickup-heading privacy validation

Validated candidate: `0.4.69` / code `75`, exact source `c6a4a3d0a02c9b81c3d6a45aff6d53083264944d`, tree `1f857230a7b401ccb47d671b85681baa14549abc`.

## Change

- `PersonalText` now recognizes `Verify items for <recipient>` as a customer-name heading in same-label, comma-serialized and split-label forms.
- `ReportOutbox` reapplies the current deterministic mask immediately before sending a queued diagnostics issue body or comment. This protects reports queued by an older release without changing report receipts or retry behavior.
- No classifier, filtering rule, learning rule, consent gate, action authority, report destination or stored raw-screen input changed. Already-filed issues were not edited or deleted.

## Evidence

- The two focused test classes passed first on the combined 0.4.68 source.
- The complete local gate passed **2,263 tests in 121 suites** across simulated API 26 and 35, with zero failures, errors or skips.
- Lint completed with zero errors and 37 warnings.
- All **403** frozen tracked inputs remained unchanged during the gate; aggregate `923accd36a01823b11f86fdb18df11b13150b080996c39486212b8c8e3c1a06b`.
- Structured receipts: `validation/0.4.69-local-full-gate-inputs.json` and `validation/0.4.69-local-full-gate.json`.

## Limits

This is local simulated regression evidence. It is not physical-phone proof, signing evidence, publication evidence or evidence that old remote issue history was removed. Signing and automatic-update publication remain separate gates.

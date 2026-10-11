# Offline diagnostics and pilot evidence

These public copies derive from the October 11, 2026 reviewed support packets. Private coordination URLs, storage identifiers, local paths and personal trace details have been removed. They are optional local maintenance tools, outside the Android app. Neither contacts a service, changes an app setting, sends a message, enrolls a tester, nor establishes a phone PASS. `import-manifest.json` records original and public SHA-256 hashes and sizes separately; files marked sanitized make no unchanged-byte claim. The untouched originals remain separate. Included historical READMEs and receipts describe their original checkpoint, not current release gates.

## Numeric diagnostic reducer

`diagnostics/reduce_diagnostics.py` accepts an explicitly supplied, already-consented text export and reduces only six complete fixed/numeric log forms. It excludes arbitrary text and keeps field acceptance `NOT_ASSESSED` and runtime-build verification false. It does not collect a report or authorize sharing. Read the included limits before use.

```sh
python3 tools/offline-evidence/diagnostics/reduce_diagnostics.py /absolute/consented-export.txt --output /absolute/new-summary.json
```

The original reviewed source and tests have unchanged hashes, with **19 historical synthetic tests passed**. That experiment was consumed, not repeated. The syntax reference is a778c418; successor integration does not prove that an installed phone emitted any of it. Bind any new minimized output to a separately observed build and reviewed source-format evidence. A zero-event output proves nothing about phone behavior.

## Historical pilot intake

`pilot/pilot_intake.py` is an advisory receipt validator. Its pinned policy supports the public 0.5.8 and frozen 0.5.9 artifacts only. It deliberately does **not** accept the cumulative successor by version-name substitution. The owner comparison now uses the public role label `offerfilter-release-maintainer`, with the same exact-equality policy; this is an assertion, not authentication. The original **60 historical synthetic passes** remain in `pilot/validation-summary.json` and `pilot/test-results.txt`. The separate **60 current synthetic passes** against the sanitized tool are recorded in `pilot/public-validation.json` and `pilot/public-test-results.txt`. Test source is unchanged. No phone observations, consent, participants or gate approvals were added; the phone form remains blank, with sanitized provenance metadata.

```sh
python3 tools/offline-evidence/pilot/pilot_intake.py tools/offline-evidence/pilot/live/pilot-state.json
```

Exit zero means a valid input, including an incomplete/held one. The included historical report remains **HELD**, with 14 NOT_RUN cases, two BLOCKED successor cases, zero phone PASS and zero participants. It is explicitly labeled a sanitized historical derivative: its input hashes now identify the public copies, while its original result and timestamps remain historical. A separate current structural evaluation of those blank inputs is recorded in `pilot/public-readiness-report.json`; that result is also HELD and establishes no phone outcome. Do not enter successor evidence into this old policy or overwrite these receipts. Successor intake requires a separately reviewed policy update after exact artifact/source inclusion evidence is available. Hashes establish file identity, not observer truth or authenticated owner approval.

The pilot direction remains one shared app/update channel: three initial testers' stationary checks and ordinary sessions reviewed before seven more, at most ten active/fifteen cumulative over seven days. Initial pilot Autopilot, auto-accept and Peek remain off; setup filtering is paused. Study choice requires an affirmative saved choice, separate from both general consent and automatic reporting. Existing opt-outs persist. Wrong taps, touch loss, freezing, obstructed navigation or privacy leaks stop testing. Recruitment, actual-phone acceptance, private deployed retention/triage, and privacy-alias delivery remain unverified in this source lane. No backend permission workaround or outreach is authorized here.

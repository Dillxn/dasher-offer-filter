# Offer Filter 0.4.72 candidate: core safety audit

Date: October 4, 2026.

**Status: source-review candidate; core-tested but NOT signed, installed, or release-ready.** The original audit stopped after a reported tool safety-review block. During the October 4 follow-up, the same authorized GitHub connector successfully accepted the tested production source, test files, and candidate trees. No reconnection, credential change, alternate authentication route, or relaxed protection was required. This candidate is for an isolated `codex/core-safety-0-4-72` branch, not a live release. Main, release artifacts, updater feeds, and deployed services remain unchanged.

## Source and scope

Repository: `Dillxn/dasher-offer-filter`.

Baseline main read: `41c2d169fe6bc48d99465d37daa12e068586f2ab`.
Candidate integration base: `afb9cbdbb8e42fc5692eff58f70d605191ef6249`, branch `codex/peek-loading-0-4-71`, tree `41b90b24074356ce7aca05763af170a4853f47b7`.

The integration base retains the unshipped .69/.70 privacy and .71 Peek corrections. Comparison established that the parser classes used in this audit were unchanged by that pending work. All ten selected baseline production files and the baseline build configuration were checked against their Git blob identities before editing. The patch advances versionCode 77 to 78 and versionName 0.4.71 to 0.4.72 only in the candidate.

Source review covered offer evidence, item parsing, add-on composition, offer identity, decline confirmation/error-recovery policy, update metadata/transport, and the existing .71 Peek patch. This is not an exhaustive audit of all application, website, advertising, Android lifecycle, or device paths.

## Confirmed defects and fixes

| Area | Reproduced defect | Implemented repair |
| --- | --- | --- |
| Payout rates | Rates such as `$2 / mi`, `$18 / hr`, abbreviated/per-unit/plural forms became exact pay or add-on increments. A rate beside Guaranteed could displace the actual payout. | Shared whole-unit rate detection handles whitespace, common abbreviations, plurals and item/order/delivery units. Standalone pay, add-on pay and decline-only ceilings cannot use those rate figures. |
| Fragmented items | Separate accessibility labels such as `at most`, `12`, `items` lost the qualification when joined; increments, estimates, signs and product/progress qualifiers could become exact totals. | Sibling joining retains uncertainty. Only unqualified item-unit pairs become exact counts; neighboring travel metrics retain their own units, conflicting item pairs remain contradictory, and existing distance/stop joins are preserved. |
| Item meaning | `Estimated 12 items`, `Unique total items 8`, qualified totals and mixed total/increment wording could become reliable basket counts. | Qualifiers are checked after the total/additional wrapper is removed; unique-product counts remain distinct from units; incompatible meanings remain unknown. Exact explicit counts, repeated labels and separately identified order components retain their existing semantics. |
| Parser robustness | Malformed number tokens within the existing 4,096-character per-label bound caused a host-JVM StackOverflowError in the item regex. | Possessive numeric groups prevent the reproduced stack overflow without increasing accepted numeric grammar or input limits. |
| Range evidence | `3 to 5 stops` became exactly five; ranged add-on metrics or new totals could leave a misleading exact increment/composed route. | Shared distance/time/stop range checks reject both dashed and worded intervals. Ranged evidence invalidates the affected add-on dimension. Cash ranges also handle case-insensitive `to`. |
| Update validation | A leading `/../` survived URI normalization and passed the canonical-address check; missing channel/address validation was inconsistent. | Explicit unresolved-parent-segment rejection and fail-closed missing-input checks. Existing origin, package, version, checksum, byte-limit and signer requirements are not weakened. This does not demonstrate a working exploit or compromised update. |

## Reproduction and local results

The new cases were executed against the unmodified selected production sources first. Rates, fragmented items, item meaning, ranges, update metadata and update address validation failed; existing-evidence and decline-recovery checks passed. The final 256 KiB baseline run also reproduces StackOverflowError in malformed item tokens. Overflow behavior at 1 MiB varied as the case set and JIT warm-up changed, so both final baseline receipts are preserved rather than claiming every baseline run overflowed. The patched code passes all groups at both stack sizes. Output is retained under `validation/`.

After the fixes, **2,008 assertion checks in nine groups passed**, independently at both `-Xss1m` and `-Xss256k`:

| Group | Passing checks |
| --- | ---: |
| Rate/payout separation | 970 |
| Fragmented item evidence | 472 |
| Item-count meaning | 231 |
| Malformed/bounded item inputs | 113 |
| Range handling | 108 |
| Existing exact evidence/guards | 19 |
| Decline recovery identity, freshness and retry caps | 30 |
| Update metadata and retry policy | 42 |
| Update address boundaries | 23 |
| Total | 2,008 |

These are assertion checks, not 2,008 independent JUnit test methods or distinct bugs. The four added test-source files include nine JUnit bridge methods to run these same groups in the regular test gate; the JUnit bridge execution itself was not performed here.

Runtime: OpenJDK 21.0.11. Compilation target: Java 17 (`javac --release 17 -encoding UTF-8 -implicit:none`). All inputs are synthetic; no captured customer text or credentials were added.

Re-run the supplemental check from a checkout with this patch:

```sh
python3 tools/check-core-safety.py --stack-size 1m
python3 tools/check-core-safety.py --stack-size 256k
```

The check compiles the real selected production files. It supplies a temporary, throwing DasherScene boundary solely to resolve OfferEvidence's unrelated scene-method references. Any call to that boundary fails the run. Accordingly, these runs do not validate Android scene classification, service adapters, notification delivery, accessibility actions, or the full app. The production scene class is never replaced in the repository or patch.

## Unchanged safeguards

No change to automatic-accept authority/defaults, manual takeover, consent or disclosure, learning/outcome confirmation, user settings, service lifecycle, notification launching, the existing two-Back recovery cap, Peek return logic, update signer checks, release workflow gates, or remote reporting flows. Decline recovery was tested as a pure state machine, not on a phone. Prior owners' .71 validation is separate evidence and is not represented as validation of .72.

## Release and handset gaps

A complete Android SDK/Gradle environment was not available in this workspace. No full `testDebugUnitTest -PallSdks`, Android 26/35 adapter run, lint, release packaging, signature verification, or physical-phone test was completed for this patch. No APK was built or offered, and no Actions/Render build was started. The follow-up reran the same 2,008 checks successfully at both stack sizes and used the existing GitHub connector for source-only publication. All three repository workflows were inspected: they have manual-dispatch or reusable-workflow triggers, not push or pull-request build triggers. No workflow was dispatched.

Before merging/publishing: apply or integrate onto the fresh .71 lineage after checking concurrent changes; run the existing full local Android test/lint/package gates; complete original-certificate local signing and the unchanged publisher/update verification; then validate real-device offer reads, decline/error recovery, lock/background behavior, Peek, notifications, learning and auto-update. The exact recent screenshot, item-count learning observation, and handset hang are not claimed reproduced or fixed by these synthetic core checks.

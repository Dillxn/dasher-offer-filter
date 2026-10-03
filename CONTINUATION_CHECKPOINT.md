# Offer Filter continuation — October 3, 2026

Sanitized working checkpoint. This private app repository must remain private while its old issue history may contain payment details. The public website is a separate clean repository. Do not copy reports, credentials or this checkpoint into the website repository.

## App source and release boundary

- Fresh main inspected: `98d410ceb29341928d7605d464ac4aee051ecf39`, serving 0.4.56/code 62. No main or release-feed mutation was made in this continuation.
- Continued the exact pay/item candidate `88f8c2a5bc209e9e179c98d622a65f2e38ce852c` on `codex/pay-per-item-0-4-57`, rather than reimplementing it. Its 263 Git blobs and tree were restored and hash-verified.
- Added the requested last Settings text, "Jesus Loves You". Added an acceptance-rate warning as the first opening-notice point, an explicit "I understand and accept" action and own-risk agreement. Notice version 9 requires existing installs to acknowledge it before filtering can resume; Not now/Back store no consent. Updated and bundled the matching terms.
- Added the exact observed long Dasher error-toast wording while retaining every request/time/identity/touch/lock guard and the existing prohibition on split-screen global Back.
- Read the final `validation/0.4.57-test-inputs.json` and `validation/0.4.57-test-result.json` together with `VALIDATION_ITEMS_0.4.57.md`. Earlier counts certify only their corresponding frozen source. A test pass does not establish signing, publication or physical-phone behavior.
- Final combined candidate gate completed October 3 at 16:51:04 UTC: 1,774 tests across 98 suites, no failures/errors/skips; lint zero errors and 34 warnings. All 214 frozen inputs and both execution helpers were unchanged. Source aggregate: `865e0d70a6ca9cf4fdb6065ce97e8deb80fcaf2c02083b44e8ba40e7cf10a747`. Focused notice/footer tests passed 56 cases; focused error recognition/recovery passed 102. Current candidate branch commit is recorded below after publication.
- Signing remains blocked by a concrete runtime transfer failure, not a missing Render sign-in: authorized original-signer values were visible in the existing service, but the documented browser shared-file mechanism did not persist or transfer private files to the build workspace. All temporary private values were cleared. Do not replace the key, copy secrets through chat/source, bypass the updater, or suggest a manual-install detour.
- A fresh run of `tools/verify_channel.py` using production UpdateTransport downloaded and verified the existing live 0.4.56/code 62 APK: 402,515 bytes, SHA-256 `d4148cb7eb895fdae1a93ed295a4eae90f8610ba7107c7fa65131079c94d8219`, source `97a4918d92adb1406cba17370e769556cdda8669`, original signer `553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703`.
- Render `srv-datkrtid0e5s73ch9egg` in workspace `tea-d6v27g6uk2gs738gvi80` remains a mirror of signed GitHub release files, auto-deploy off, not suspended. The Dashboard showed a payment-failed banner; no billing changes were made and a new deployment was not attempted.

## Diagnostics inspected and deduplicated

Audit completed October 3, 16:43 UTC, with a cutoff carried forward from the prior release of October 2, 19:43 UTC. Personal Gmail had no matching Offer Filter reports after that cutoff. The older email `1a0fda13b60e94c3` / thread `1a0fda0d85c6f784` (October 2, 17:19:55 UTC, 0.4.53) was identified by metadata only and excluded. No new email/issue duplicates were found. Subsequent audits should include issues updated after this checkpoint and deduplicate these report IDs.

GitHub issues updated since that cutoff: #34–39, all 0.4.56 offer reports and each with zero comments; no new diagnostics-label issue. They represent four selected-offer targets:

| Report IDs | Disposition |
| --- | --- |
| #34, #35 | Same ambiguous-pay reading; user says they accepted it. REVIEW correctly avoided guessing pay. No demonstrated automatic-decline defect. |
| #36, #37 | Same unread-offer target; user reports the phone was locked. The lock guard is intentional and must remain. No evidence of a separate unlocked failure. |
| #38 | Alert heard but offer never appeared; notification-only unknown figures. Unresolved Peek/notification timing. Selected-offer history does not establish who sounded or whether Peek was eligible. |
| #39 | User reports dim map/no controls after Decline; retained action USER_TOOK_OVER. No runtime sequence establishes initial tap ownership, toast event, split focus or completion. |

Issue #39 was created October 3, 01:57:39 UTC. The separately supplied screenshot at 12:38 Eastern (16:38 UTC) is a later incident, not a time-aligned diagnostic for that report. It shows "Something went wrong. Please try again." in Dasher's lower split half. The prior candidate recognized only the short word "error"; the exact-message patch fixes that recognition gap, not the split-screen recovery limitation.

No raw report bodies, payment information, precise locations or old fixtures were saved. Issues #10–15 were not reopened. No email or issue was sent, changed, closed or deleted.

## Public website and film

- User chose GitHub Pages and public visibility. Created **only** `Dillxn/offer-filter-site` as public; the app/diagnostic repository stays private.
- Recovered the existing site source from its saved private Sites version, source commit `2071c00fdaeb624014b5f0541e140c489327309e`. The historical animatic has since become actual exported assets; do not restart from a storyboard or claim no film exists.
- Published static site commit `57eb08772bef9ece4d11cf9bc43c47f29282d428`, tree `793111584dc76f488c21dab2ffb5ba31a063cac5`, on public `main`. GitHub Pages build/deployment `37137255001` completed successfully.
- Verified live URL: https://dillxn.github.io/offer-filter-site/ . Browser playback reached 18.5 seconds/ended with no video error, 1080×1920 and readyState 4. Local MP4 and WebM full decoding passed before upload. Square and landscape MP4 cutdowns are present too.
- Local fonts, font licences and English captions are included. All 17 deployed files use project-path-safe relative references; no private configuration, reports or signing files were published.
- The download panel links to the existing signed Render distribution/setup page, preserving automatic updates. It also discloses the acceptance-rate risk. Cash App `$JesusLovesYou0808` and Venmo `@dillonriecke` links were inspected in the live dialog; no payment was initiated.
- The film is a playable original marketing **draft**, with invented offers and original sound—not a certified phone demonstration or a claim that the requested pdoom-level final polish is finished. Public deployment does not complete that remaining creative work.

## Next safe work

1. Re-read fresh main and the candidate branch before editing. Preserve concurrent work; do not treat restored filesystem paths, browser sign-in or credentials as durable.
2. When the authorized original signer can reach the build environment through the normal documented mechanism, rerun required release checks, sign through `tools/sign-local.sh`, publish through `tools/publish-repo-feed.py`, push the complete source/release pair, mirror on Render and verify the exact new live APK through `tools/verify_channel.py`.
3. Keep #38 open as an unresolved display/Peek symptom. For the decline error, need privacy-safe, time-aligned event/request/countdown/window evidence and actual phone verification. Do not infer safe global Back merely from a screenshot of an inactive split half, or weaken privacy retention for convenience.
4. Continue visual refinement of the public film/site without claiming the current draft meets the final ambition. Preserve no-auto-accept, accurate metrics, no fabricated automatic hotspot data and optional goodwill-only tips.

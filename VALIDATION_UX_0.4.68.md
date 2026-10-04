# Offer Filter 0.4.68 validation

Version 0.4.68 / code 74 is the combined UX, acceptance, launcher and background-alert update. The development base was the verified 0.4.67 checkpoint. Final release receipts will identify the exact committed inputs, APK, original signing certificate, and both live update channels.

## Verified live release

- Exact source: `7cfd79acadd8456d9ef58d564b4cc51b027755f3`; release commit: `66b27eb11f99bca416c101cae85bf1b67120c732`.
- Local gate and exact-source signer gate: **2,260 tests in 121 suites**, simulated API 26 and 35, no failures/errors/skips. Lint: no errors, 37 warnings. No tracked source inputs changed while signing.
- APK: 1,226,254 bytes, SHA-256 `e60228dd5bbed190add3d9ac97283229fbd13ad1cfb3aad9b0809b5a56e687be`; original certificate `553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703`.
- Both update feeds advertise 0.4.68/code 74 and the exact source above. Render deploy `dep-db10dmegekts73bjv6mg` became live at 2026-10-04 07:57:47 UTC and only mirrored finished artifacts. A fresh download through production `UpdateTransport` verified the live package, version, bytes, hash and signer.
- The authorized temporary signing export was removed after local signing. No key was generated, logged, committed or published. No Android build, test, signing or video rendering ran remotely.

## Public source

The MIT-licensed `app-source` branch is `ebba04f28f617b8512b6128e92f555c6a65cd8cd`, tree `4a7ada6b41338271696631b3540b8aed19239897`. All 127 reviewed paths/modes/blob hashes match the prepared snapshot; 125 upstream files match the frozen app source exactly. Anonymous LICENSE, README and build configuration downloads are byte-exact. Private reports, diagnostics, history, credentials, signing inputs and workflows remain excluded. The exported source compiles and lints locally with no errors.

## Scope and evidence

- Auto-accept: reproduced a pre-dispatch content-event cancellation and an omitted learned-item rule identity on the old implementation. The final code permits at most two fresh complete reads before persistence, preserving the same offer, rules, generation, quiet interval and original deadline. Post-persistence cancellation, restart suppression, user takeover and automatic-learning exclusions remain intact.
- Adaptive minimums: reproduced missed eligible manual acceptance when explicit delivery progress retains exactly the original bare pay and consistent observed items. The old implementation fails that positive regression. Ambiguous, conflicting, partial, new-offer, expired, route-history, add-on and automatic cases remain excluded. Report #60 lacks the exact money label at the relevant moment, so the repair covers the verified pattern rather than claiming exact field reproduction. Historical lessons are not backfilled.
- Background notifications: keeps passing/review native alerts and omits redundant generic cards only when an actionable native alert covers the offer and no new app bell is needed. Native-sound evidence shares the once-per-offer budget. Silent native alerts, failed quiet Peek and unavailable native listings retain necessary fallback cards/bells. Reverse transitions and protection of the sole queued app bell have direct regressions. No native dismissal callbacks or key-only cancellation were added.
- UX: counts inspect their newest matching retained history without pausing; only the mascot pauses. Older count tickets preserve the underlying current chart/caption, including new arrivals and skyline rollover. The latest/selected caption names recorded outcomes; learning and automatic-request status are prominent in the ticket. Numerical wait estimates require sufficient samples and positively observed waiting.
- Navigation: header access remains available in split screen. Historical best-area ranking is unchanged and disclosed; nearby gas and price comparison open provider searches. Neither nearest nor cheapest gas is independently claimed. Native short/narrow/enlarged-text checks retain 48 dp targets and verify the last navigation action can be reached by scrolling on both simulated Android versions.
- Launcher: day/night aliases follow the existing Day/Night/System/Auto appearance resolution. MainActivity remains enabled, explicit consent/updater entry points work, and effective alias resolution and Peek restoration are tested. Existing lifecycle/visible/service work performs refresh; no wakeup, new location request or timer was introduced.
- Licensing: the user's explicit free/open-source direction is aligned to the MIT License. Terms and bundled LegalTexts agree byte-for-byte; Consent.VERSION is 12 for the substantive Terms change. Existing notice acceptance pauses filtering until the current notice is accepted. Privacy and data flows are unchanged.

## Validation status

Final combined dual-SDK tests and lint are run locally before freezing the source. The local signer repeats the release test gate from that exact clean commit and verifies the original certificate. The publisher checks embedded identity, version monotonicity and the other live channel before producing the feed. Render only mirrors those finished signed files.

Focused corrections include 103 background-alert/Peek/sound tests and 90 combined count, consent and settings cases. The prior broad run exposed stale license/button assertions and an API 26 test-scroll issue; those were corrected without weakening actual consent, label or 48 dp reachability checks. Final authoritative totals are in the release receipts.

Native simulated renders cover phone, split, narrow, large text and compact map layouts. These are distinct from a physical handset install or live Dasher behavior. Android/OEM task placement, notification sound, launcher cache/pinned icons, TalkBack operation and real offer handling remain handset checks.

## Explicit remaining limitation

Automatic return from Maps after arrival remains open: there is no verified arrival signal or active navigation destination. Elapsed time, stopped movement, Maps foreground and generic Directions controls do not establish arrival. No speculative automatic Back or route clearing was added.

## Matching website release

Website product commit `e9d95ee34a9d0fc04f54dc24090c5391d73dca53` is live through successful static Pages run `37187542659` (checkout/upload/deploy only). All 28 checked public files match, including four 24-second exports, posters, full restored captions, MIT notices and existing favicon assets. Actual unmuted live playback reached the end without errors; all nine caption cues and 1280/400 layouts passed. The original full “Free and open source.” speech remains intact; only its speech-free musical tail was extended for the credits.

Website verification checkpoint: `09c181e563e75be754d998b7baccbe9f75f9b635` (documentation/receipts only, skip CI). Live film product remains `e9d95ee34a9d0fc04f54dc24090c5391d73dca53`.

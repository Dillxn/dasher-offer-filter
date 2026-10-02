# Offer Filter 0.4.52 candidate validation

Release addendum (October 2 UTC): this candidate was subsequently signed with the existing cloud key and published in commit `353222a84675c7462132f1137edeca077bdf696c`. GitHub and Render served the same 0.4.52 / 58 APK (377,939 bytes, SHA-256 `aadc3f6c7eb57e39cf2ef472f30b5301380a22fc25217cffd5094dcbd7e15fd0`); a fresh production-updater download verified package, version, size, hash and original signer. The candidate-time record below is retained as historical evidence. Physical-phone behavior and automatic hotspot acquisition remain unverified.

Candidate: **0.4.52 / versionCode 58**, package `com.local.dasherfilter`. Work checked on October 1 in New York / October 2 UTC. This is a source candidate, **not a shipped upgrade**.

## Changes

- Skyline buildings retain payout; adjacent evergreen trees show each offer’s recorded baseline fitness. Independent dollar and percent scales have labeled current reference lines. Unknown scores remain unknown; scores above the 400% display ceiling show an overflow chevron. Historical requirement ticks, outcomes and offer selection remain.
- One persisted minimums scale (1–200%, default 100%) applies to every active fixed and applicable learned floor. At 97%, exact baseline fitness of 97% passes area mode; strict mode checks each floor at 97%. Fixed values and learned history are not rewritten. Add-ons remain strict; max stops, unknown-data review and manual takeover remain.
- The existing constellation area button shows the percentage: a horizontal drag changes it in one-percent steps, a tap still switches area/strict mode, and screen readers can set it directly. Saved/learned stars remain at their original values while the effective boundary and skyline references move. Near-cutoff rounding never displays a failing score as meeting its cutoff.
- Expanded axes have plain labels. The atlas key and optional explanation distinguish historical offer-arrival areas from final stops and actual Dasher hotspots. Ranking remains pooled offered pay divided by total known offer miles, with at least three eligible offers. Selected rates show their sample counts.
- Active, visible filtering on an already interactive, unlocked phone renews a short screen-on lease, gated by current consent, enabled rules, an active dash, call state and visible Dasher. The app's own foreground window uses its Activity flag under matching guards. Hiding, pausing, locking, interruption or service teardown releases the claim. No wake-up flags, unlocking or system-setting edits were added.
- Successful first-step Decline requests retry at 2–3 second intervals, at most four times within the original deadline. Timers re-read the current offer even without a new event; bounded quiet confirmation reads can find a late prompt. Fresh countdowns end the old episode, and touches still take control away. Diagnostics identify the stalled stage and request count. Changing the minimums scale cancels old decline authority, including pending confirmations and retries; a scale change during a slow read prevents a stale first tap. An already requested action cannot be recalled.

## Verification

- **1,421 passing tests across 80 suites**, including Java/JUnit and simulated Android 8/API 26 and Android 15/API 35; **0 failures, errors or skips**. Final full run finished 2026-10-02 00:45:49 UTC. The earlier run exposed one legacy rapid-retry fixture on both APIs; its timing now preserves the patient interval, original deadline, four-request cap and single-report assertion.
- `lintDebug`: **0 errors, 32 warnings**. `assembleRelease` completed without signing. Embedded package `com.local.dasherfilter`, version **0.4.52 / 58**.
- Unsigned APK: **359,252 bytes**, SHA-256 `830a11dce9715c4093b7ea5140a41dafc2d63a9f0c2345174c190176d8b17fd0`. This is packaging evidence, not an installable upgrade for the existing signer.
- Tested app tree: `1201726db4c8d44683b3ffa0d2a12f24288b50a4`. Gradle 8.13, Temurin 17.0.20.1, compile SDK 36/build tools 35.0.0, one worker/test fork.
- Native Android graphics previews rendered and inspected for phone/day/night and short layouts, including the 52dp skyline and 84dp atlas. The 97% example derives its observed score and passing outcome from the real rule evaluator. Labels, threshold captions, per-offer markers, overflow chevrons, sample counts and atlas keys were checked. A simulated phone preview was saved for review.
- A fresh `tools/verify_channel.py` run using production `UpdateTransport.java` verified the existing live **0.4.49 / 55** feed: 324,691 bytes; SHA-256 `c78a77bb0fcb4f3035e79ea7481fc67a734921d6803cff738cf7a30af259a9e1`; source `4859d7c908f0b677b33a39eff588c5353b7191d4`; cloud signer `553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703`. This verifies 0.4.49, not the candidate.

The fixtures contain synthetic offer values and coordinates, never recovered raw diagnostic screens. Java tests and simulated Android adapters are separate from real handset behavior.

## Platform references

The screen-on implementation follows Android's [Activity screen-on guidance](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on), [PowerManager screen wake-lock contract](https://developer.android.com/reference/android/os/PowerManager#SCREEN_BRIGHT_WAKE_LOCK) and [timed acquisition API](https://developer.android.com/reference/android/os/PowerManager.WakeLock#acquire(long)). The service uses the deprecated screen wake-lock API because it does not own Dasher's Activity. Its normal `WAKE_LOCK` permission needs no runtime permission prompt. Timed release depends on Android scheduling; the tests do not prove release during an actual stalled system or handler.

## Remaining release gates

- Existing cloud signing credentials are unavailable. Follow `AGENTS.md`'s existing-signer and separate source/release commit protocol. No replacement key, signed 0.4.52 APK or change to the published feed is claimed.
- Check Samsung Android 16 screen-timeout/manual-lock behavior, slow or hung Dasher confirmations, touches, backgrounding and battery behavior on the physical phone. A UI tap remains a request, not server-confirmed acceptance of a decline.
- Automatic Back is not implemented for an unidentified loading screen. The app cannot safely attribute that screen to its prior decline; the user's manual Back/touch continues to end automation for that offer. Some Dasher hangs can therefore still require the user.
- The fifth-spoke control and math remain implemented, but actual final-stop/hotspot acquisition is still unavailable; unknown measurements remain unplotted and the rule defaults off.
- Earlier privacy, historical-report containment and physical-device Peek/decline release checks remain in [the continuation record](VALIDATION_2026-10-01.md) and [fifth-spoke record](VALIDATION_HOTSPOT_2026-10-01.md). This change does not establish that past diagnostic issues were removed.

The freshly verified published feed remains 0.4.49. The existing illustrated film/site delivery is unchanged.

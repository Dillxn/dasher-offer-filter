# Public beta: physical-phone acceptance gate

Updated 6 October 2026. This checklist is not a test receipt. No connected physical device was available for this review; `adb devices -l` returned no devices. Simulated API 26/35 tests and a verified APK download do not establish installation or real Dasher behavior.

Use consenting testers while stationary. Do not create an unwanted delivery commitment for testing. Record the actual phone/Android version, app version, expected result, observed result and pass/fail for each scenario. Never attach customer, address, payment or account data to public issues.

| Gate | Required observation |
| --- | --- |
| Independent installation | A tester with no account of any kind downloads from the normal public page and installs; record actual security/installer warnings without disabling protections. |
| Consent | Before acceptance there is no screen reading and nothing is sent: no feedback, offer report or diagnostics. Terms/Privacy are readable offline; Not now and Back leave consent unaccepted; accepting persists. |
| Android setup | Each missing-access row reaches the right setting. Denial is recoverable. Confirm any Android restricted-settings flow on the actual OEM after explaining its authority. |
| Safe defaults | Fresh app begins paused with no rules; mascot toggles filtering; Auto-accept remains off unless separately confirmed. Optional location denial does not block filtering. |
| Alerts | Denied app notification permission and quiet native alerts do not silently remove the only actionable offer. Test actual sound/vibration and volume restoration around decline, navigation and calls. |
| Existing-install update | A legitimately original-signed older version upgrades through the existing automatic-update channel, without uninstalling; rules and history survive. “Up to date” is not an upgrade test. |
| Installer recovery | Separate Offer Filter installation permission, cancellation and manual retry remain reachable. Dash hold survives pause, lock and reconnect until a positively observed end. |
| Real actions | Distinguish request from completed acceptance/decline. Verify confirmation, manual takeover, lock, partial/unknown offer and restart suppression. Never auto-accept only to manufacture test evidence. |
| Learning | Record a genuinely confirmed eligible manual acceptance and its observed numeric facts; inspect Saved/Learned/Used values before and after. Automatic acceptance must not train. |
| Three-app flow | Exercise Offer Filter, Dasher and Maps/Waze in full and split layouts, with keyboard/shade obstruction and notification entry. Verify actual route UI, not merely map-app launch or navigation audio. |
| Privacy | Use invented recipient headings only. Inspect Preview and shared reports for masking. Send anonymous feedback with and without Attach masked diagnostics, and Report this offer, offline and online: each waits ("Saved; it will send when you're online") and then shows its reference. Turn Share anonymous diagnostics after each dash on and off around a real dash end; confirm one summary per dash and none after it is off. After a forced stop, check the homepage's "stopped unexpectedly" line sends nothing until Send. Confirm an updated phone shows no GitHub row and no old queued report is sent. No public raw diagnostics. |

Broad Android compatibility claims need physical coverage beyond one handset, including the minimum-supported generation and current target OEMs. The reported Samsung split/navigation mismatch and map-arrival return remain unresolved unless observed with aligned evidence.

## Other launch gates

- Ship the tested pickup-heading privacy fix with the original signing identity and all publication checks.
- Publish a working private privacy/security contact. Public issues are not that private channel.
- Before 0.5.0 ships, deploy the feedback service's hardening migration and then its function (backend/anonymous-feedback/README.md), run its smoke test and confirm its 90-day deletion job runs. The function deployed today answers 400, which the app drops, when its storage cannot be reached, so until then a storage outage loses automatic summaries.
- Before merging 0.5.0 to main (Render rebuilds the download page from it), have the website serve https://offerfilter.org/terms/, /privacy/ and /license/. Until it does, each Render build links the missing ones to the texts in the public source instead (tools/mirror_repo_feed.py prints LEGAL_PAGE_NOT_SERVED for each); check the build log.
- Get the user's explicit confirmation of the two flows this release adds beyond what users send themselves: the opt-in "Share anonymous diagnostics after each dash" summary (DashSummary, hooked into both services) and the stop line's feedback dialog that opens with Attach masked diagnostics on (the user still taps Send). Without it, remove the DashSummary/StopReports hooks and open the stop line's dialog with Bug only.
- Have the draft legal notices reviewed; no legal clearance is claimed.
- Verify OfferFilter.org DNS, HTTPS and links after the separate domain owner completes setup. This lane did not modify DNS or hosting domain settings.
- Keep the initial rollout labeled beta; do not present incomplete field validation as a stable broad launch.

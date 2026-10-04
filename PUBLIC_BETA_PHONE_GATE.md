# Public beta: physical-phone acceptance gate

Updated 4 October 2026. This checklist is not a test receipt. No connected physical device was available for this review; `adb devices -l` returned no devices. Simulated API 26/35 tests and a verified APK download do not establish installation or real Dasher behavior.

Use consenting testers while stationary. Do not create an unwanted delivery commitment for testing. Record the actual phone/Android version, app version, expected result, observed result and pass/fail for each scenario. Never attach customer, address, payment or account data to public issues.

| Gate | Required observation |
| --- | --- |
| Independent installation | A tester without private GitHub access downloads from the normal public page and installs; record actual security/installer warnings without disabling protections. |
| Consent | Before acceptance there is no screen reading or reporting. Terms/Privacy are readable offline; Not now and Back leave consent unaccepted; accepting persists. |
| Android setup | Each missing-access row reaches the right setting. Denial is recoverable. Confirm any Android restricted-settings flow on the actual OEM after explaining its authority. |
| Safe defaults | Fresh app begins paused with no rules; mascot toggles filtering; Auto-accept remains off unless separately confirmed. Optional location denial does not block filtering. |
| Alerts | Denied app notification permission and quiet native alerts do not silently remove the only actionable offer. Test actual sound/vibration and volume restoration around decline, navigation and calls. |
| Existing-install update | A legitimately original-signed older version upgrades through the existing automatic-update channel, without uninstalling; rules and history survive. “Up to date” is not an upgrade test. |
| Installer recovery | Separate Offer Filter installation permission, cancellation and manual retry remain reachable. Dash hold survives pause, lock and reconnect until a positively observed end. |
| Real actions | Distinguish request from completed acceptance/decline. Verify confirmation, manual takeover, lock, partial/unknown offer and restart suppression. Never auto-accept only to manufacture test evidence. |
| Learning | Record a genuinely confirmed eligible manual acceptance and its observed numeric facts; inspect Saved/Learned/Used values before and after. Automatic acceptance must not train. |
| Three-app flow | Exercise Offer Filter, Dasher and Maps/Waze in full and split layouts, with keyboard/shade obstruction and notification entry. Verify actual route UI, not merely map-app launch or navigation audio. |
| Privacy | Use invented recipient headings only. Inspect retained/shared reports for masking and verify opt-in/opt-out and unsent-queue behavior. No public raw diagnostics. |

Broad Android compatibility claims need physical coverage beyond one handset, including the minimum-supported generation and current target OEMs. The reported Samsung split/navigation mismatch and map-arrival return remain unresolved unless observed with aligned evidence.

## Other launch gates

- Ship the tested pickup-heading privacy fix with the original signing identity and all publication checks.
- Publish a working private privacy/security contact and confirm actual sent-report retention/deletion practice. Public issues are not that private channel.
- Have the draft legal notices reviewed; no legal clearance is claimed.
- Verify OfferFilter.org DNS, HTTPS and links after the separate domain owner completes setup. This lane did not modify DNS or hosting domain settings.
- Keep the initial rollout labeled beta; do not present incomplete field validation as a stable broad launch.

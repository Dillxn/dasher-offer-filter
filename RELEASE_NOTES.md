## 0.4.4 — adversarial reliability audit

- Separate passing alerts from silent unclassified review cards; never automatically open Dasher to gather missing evidence.
- Retain original passing/unknown notifications when replacement alerts are blocked or unavailable.
- Bound notification state, cancel expiry callbacks on removal, suppress duplicate/reconnect bells, isolate stale-removal events, and ignore group summaries/other profiles.
- Remove arbitrary notification-extra values from decision inputs. Customer messages cannot impersonate offers by containing new-order wording.
- Require explicit add-on increments. Missing route context and contradictory figures remain REVIEW rather than silently becoming standalone orders.
- Reject malformed/partial/negative payout values and ranged/fractional durations. Use overflow-safe cost calculations.
- Recognize Finding offers and animated Unicode variants; clear/invalidate stale route estimates.
- Preserve immediate first screen decline; revoke confirmation authority on changed notification generation or a newly nonfailing screen. Do not equate click requests or notification cancellation with completed decline.
- Add a persisted immediate pause, clearer readiness/limitations, local 30-minute raw diagnostic sessions, and updater state in reports.
- Schedule real one-shot retries, coalesce duplicate update checks, validate every redirect and the APK's embedded version, and defer automatic installation during active offers/deliveries.
- Add simulated Android adapter tests and adversarial regressions. The release probe uses the actual app downloader against the public feed and signed APK.
- Use one version source and one existing Render-held signer. No additional key reset is required for cloud-signed 0.4.x installs.

Physical handset installation, live DoorDash screen variations, actual sound/vibration suppression, and Android background scheduling remain separate verification steps. This release does not claim to mute Dasher's internally generated audio/haptics.

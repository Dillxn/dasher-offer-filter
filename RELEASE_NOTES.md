## 0.5.0 — safer parsing, new rules, on-device history

### Money-losing fixes (each reproduced first, now covered by regression tests)
- Pay beside its label: DoorDash's redesigned card puts the amount above "Guaranteed". Flash offers no longer read the boost ("Includes a $15.00 Flash offer boost") as the pay, and Peak Pay, tip, rate (`$1.21/mi`, `/hr`) and `+$` lines are never taken as the payout.
- Earn by Time cards (`$15/active hr + tips`) are never auto-declined. DoorDash ends the dash after more than one decline per hour.
- "Add to route" is recognized as the add-on accept button, so an add-on card is evaluated as an add-on and is never mistaken for a decline confirmation.
- Decline-confirmation authority is tied to the offer that was declined. A different offer shown within the confirmation window is evaluated fresh, and a navigation "Back" label no longer turns an offer card into a "confirmation".
- "1 h 5 min" is no longer read as 5 minutes; pickup/ready/ETA times are not trip time; "$7.50+" and "+ tips" are lower bounds (review); road names like "14 Mile Rd" are not mileage; countdown text can't reset the 4-tap retry limit; combined add-on miles are summed exactly.
- Accept tracking counts an accept only when delivery screens newly appear after the tap. "No longer available" cancels it, and an unrecordable add-on accept clears route context instead of leaving a stale route.
- Self-update signer check works on Android 9–10 (archive certificates were never collected there).
- The notification-access shortcut no longer opens a Settings page that closes or crashes on Android 11+. Alert permission denied twice now opens the app's notification settings. Rule amounts accept a comma decimal ("2,50"). Content stays clear of the status bar on Android 15.

### New rules
- Minimum $/hour replaces $/minute. A saved per-minute rate converts exactly (e.g. $0.50/min = $30.00/hr). It applies only when Dasher shows a duration; "Deliver by 7:45 PM" is a deadline, not minutes.
- Maximum miles. For add-ons only an explicitly shown route total counts.
- Stores to avoid. A match is a known failure, so it can act on DoorDash's merchant-only "New Order: Go to …" notification: the safe Decline action when DoorDash provides one, otherwise the notification is hidden and the order is NOT declined. Customer messages and promotions never trigger it, and store rules alone never mark an offer as passing.
- The rising-payout baseline expires 8 hours after the observed accept (an undated baseline from 0.4.x is not applied).
- Optional safety limit on decline requests per hour: past the limit, failing offers become review (no tap, no hide).
- Every decision carries a reason code and a line-by-line breakdown.

### On-device history
- Offers the app evaluates are recorded locally (at most 1,000 entries / 14 days) with pay, miles, minutes, stops, verdict, reason and what the app actually did ("Decline requested", "Notification hidden — order NOT declined", "Accept observed"). History stays on the phone.

Same cloud signer: install over any cloud-signed 0.4.x build without uninstalling. The redesigned screen, history view, readiness checklist and Quick Settings tile follow in 0.5.1.

Evidence boundaries: JVM unit tests and simulated Android (Robolectric API 26/35) adapter tests pass; the published APK is verified by the release probe. Physical handset installation, live DoorDash screens and audio/haptic behavior are not established by these tests.

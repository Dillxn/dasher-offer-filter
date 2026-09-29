## Selective offer bell and background filtering

- Keep DoorDash offer notifications enabled but set the DoorDash offer channel to Silent.
- Detect the real DoorDash offer notification in the background.
- Decline and remove filtered offers without emitting an Offer Filter alert.
- For offers that pass, emit an audible high-priority Offer Filter notification and attempt to refocus Dasher.
- For incomplete notification payloads, refocus Dasher silently, evaluate the real offer screen, then alert only if it passes or still needs manual review.
- Remember the actual DoorDash offer notification channel ID and deep-link directly to that channel's settings after the first observed offer.
- If auto-decline is off, relay every DoorDash offer through Offer Filter so silencing DoorDash does not cause missed offers.
- Preserve background-offer handling, add-on route economics, diagnostics, and the tightened automatic update loop.

DoorDash must remain allowed to post its offer notifications; only its offer channel sound should be silenced. In-app sounds produced directly by Dasher are outside Android notification-channel control.

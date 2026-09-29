## Background offers and faster automatic updates

- Detect DoorDash offer notifications while Dasher is in the background.
- Track the active accepted route and evaluate add-on offers using both marginal economics and combined-route economics.
- Bring Dasher forward for qualifying or screen-only decisions when Android permits the notification handoff.
- Use a DoorDash notification Decline action directly when one is exposed; otherwise defer to the Accessibility screen flow.
- Cancel the matching filtered-offer notification after a real Decline request succeeds.
- Add on-device notification/screen diagnostics with explicit Share diagnostics and Clear diagnostics actions.
- Request periodic update checks every 15 minutes with a 5-minute flex window.
- Check again when Offer Filter opens and when Accessibility or notification access reconnects.
- Retry failed checks after 1, 2, 4, 8, then 15 minutes and cache-bust feed reads.
- Verify both release assets plus the unauthenticated public update feed and APK before publication reports success.

Phone verification is still required for the exact DoorDash notification payload and Android background-foreground behavior.

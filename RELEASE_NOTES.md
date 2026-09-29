## 0.4.3 — no premature foregrounding + updater repair

- Fix automatic updates: the updater now allows the exact Render feed/APK host it already trusted in metadata validation.
- Log update-check start, advertised version, download URL, and failures in diagnostics.
- Never automatically foreground Dasher for an offer that cannot yet be classified from its background notification.
- Never automatically foreground Dasher for a qualifying offer either; the selective Offer Filter alert is now the user-controlled way to open it.
- If a filtered notification can be classified but exposes no Decline action, suppress its notification without launching Dasher.
- Parse all textual notification extras, including nested bundles, so richer hidden DoorDash payloads can be evaluated without opening the app.
- Log sanitized notification metadata for incomplete offer notifications to identify hidden payout/distance fields.
- Parse DoorDash compact metrics such as `2 stops (7.2 mi) • 21 min` correctly.
- Keep the observed DoorDash channel ID in diagnostics. The current live channel name itself indicates no notification sound/haptics, which helps distinguish Android notification behavior from Dasher's own in-app alert behavior.

Because 0.4.2's downloader rejects the Render host before connecting, 0.4.3 requires one manual install. After 0.4.3, the repaired automatic update path uses the Render feed directly.

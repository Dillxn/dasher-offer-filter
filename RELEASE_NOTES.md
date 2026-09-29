## 0.4.5 — completed adversarial audit release

Includes the 0.4.4 audit repairs plus expanded Android Accessibility adapter coverage on API 26 and 35, a legacy-Android guard for notification action type inspection, and protection against stopped update jobs completing a replacement job with the same ID.

- Passing alerts and silent unclassified review cards are separate. No automatic Dasher foreground takeover.
- Original passing/unknown notifications are retained if replacement alerts are blocked or unavailable.
- Bounded per-notification state, expiry cleanup, duplicate/reconnect bell prevention and stale-removal isolation replace global pending state.
- Only displayed notification text is numeric evidence. Arbitrary extras are not promoted into pay/distance; customer-message offer wording is rejected.
- Add-ons require explicit added values. Missing active context and conflicting figures require review instead of standalone or incremental guesses.
- Whole-token money, duration-range guards and overflow-safe math prevent malformed fields from becoming valid rule inputs.
- Actual Finding offers screens clear stale context. Pickup/completion progress invalidates old travel estimates.
- Immediate screen decline is retained; pause, newly passing screens and newer notification generations revoke stale confirmation authority. Requests and hidden notifications are not labeled completed declines.
- Pause persists immediately. Raw logs are local, bounded and expire after 30 minutes. Reports always include updater status.
- Failed checks schedule real one-shot jobs; duplicate checks coalesce; every redirect and embedded APK version is validated. Automatic installation waits during active offers/deliveries.
- Release tests include both notification/UI and real Accessibility-service adapter flows on simulated Android 8 and Android 15. Production HTTP code verifies the public feed, APK bytes/hash, signer and embedded version.
- Same cloud signer: no uninstall or key reset for existing cloud-signed 0.4.x installations.

The code does not claim to mute Dasher's internally generated audio/haptics or classify a background notification that provides no payout/distance. Physical handset installation and live DoorDash behavior are not established by the simulated tests. See AUDIT.md and the published verification.json for evidence boundaries.

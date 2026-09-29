# Offer Filter development

- Stay on main; no branches or worktrees. Stage a complete, tested/versioned change in one commit rather than publishing partially edited main states.
- Network access is for updates only. Offer data, rules, and logs stay on-device unless the user explicitly shares a diagnostic report.
- Never infer missing pay, travel, or incremental add-on semantics. Unknown is REVIEW, not KEEP or DECLINE. A separate known failure may still decline.
- Preserve immediate first decline attempts on a readable foreground offer. Never automatically open Dasher to inspect an unclassified offer.
- A successful click or PendingIntent send means requested, not server-confirmed. Hiding a notification is not declining an order.
- Notification updates/reconnects must not ring repeatedly. Verify replacement posting/permissions and never remove an original passing/unknown notification merely on an attempted replacement.
- Keep the existing cloud signer. No automatic key generation, key material in source, signing material in public, or independent GitHub signing path.
- Increase versionCode/versionName in app/build.gradle for changed app sources. build-local.sh derives the packaged version from this single source.
- Render is the authoritative release path: render-build.sh, public/latest.json, public/OfferFilter.apk. Do not publish to the retired old-signer GitHub feed.
- Run testDebugUnitTest (including Robolectric adapters), the signed APK build, and tools/verify_channel.py. The probe uses actual production UpdateTransport, exact size/SHA, APK package/version, and signer verification. Report which live version it tested.
- Final release checks must distinguish Java/unit tests, simulated Android adapter tests, published APK verification, real handset installation, and real DoorDash audio/haptic behavior. Do not conflate them.

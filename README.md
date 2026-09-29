# Offer Filter

A local Android companion for the DoorDash Dasher app. It reads the visible offer screen through Android Accessibility and can tap **Decline** when the shown guaranteed payout is below your saved rule or the total stop count exceeds your limit. It never signs in to DoorDash, sends offer data to a server, or accepts an offer.

Offers are evaluated immediately when Android reports a screen change, with no waiting timer before the first decline attempt. The app rechecks the current screen every 200 ms for up to 3 seconds after a Dasher event so an early, incomplete screen or missed tap can recover without another event. The same failing offer can receive up to four decline requests, at least 250 ms apart; a different offer is eligible immediately. Every retry reloads saved rules and checks the current foreground screen. The app makes no sound or vibration. Android and Dasher still determine screen/event delivery time. The **Dasher notification sound settings** button opens Android's controls for Dasher's own alerts.

After an automatic first decline request, the app watches for the confirmation for up to 10 seconds, including blank screen transitions. It searches Dasher's topmost interactive application windows for the popup and selects an actionable **Decline offer** button even when the background Accept button remains exposed. It can request that confirmation up to four times, at least 250 ms apart. It leaves manually opened confirmations alone unless its own recent automatic decline is pending. A tap request still needs Dasher to actually dismiss the screen.

### Background offers

Version 0.4.1 includes the 0.4 background-offer work and adds a tighter update loop. It adds a DoorDash notification listener so Offer Filter can react while Dasher is in the background. Enable **Allow background offer access** once in Android's Notification access settings. When a notification contains enough offer data, the same saved rules are evaluated immediately. A qualifying offer is opened in Dasher. A filtered offer uses a notification **Decline** action when DoorDash exposes one; otherwise Offer Filter opens Dasher so Accessibility can read the real offer screen and perform the normal decline flow. If the notification does not contain enough data to decide, Dasher is opened for screen evaluation.

Android 14+ restricts background activity launches. Offer Filter first sends DoorDash's own notification `contentIntent` with the background-start opt-in and falls back to the package launch intent. The Diagnostics log records whether the wake was actually observed on the phone. Filtered notifications are cancelled after a real Decline request succeeds rather than being hidden before the app has acted.

### Dasher ringing

The filter cannot guarantee stopping a sound that Dasher starts before Android exposes the offer for evaluation. Completing the second decline step may stop continued ringing, but this has not been checked on the phone. For Android notification sounds, use **Dasher notification sound settings** and set the relevant notification category to **Silent**. This silences all notifications in that category, including qualifying offers. On Samsung, missing categories can be enabled under **Settings → Notifications → Advanced settings → Manage notification categories for each app** ([Samsung instructions](https://www.samsung.com/us/support/answer/ANS10002521/)). Sounds played inside Dasher are separate from Android notification controls. Offer Filter does not mute the phone's calls or media.

## Rules

Set any unused value to zero:

- Flat minimum payout
- Minimum dollars per mile
- Minimum dollars per minute, only when an explicit estimated duration is shown
- Fee for every stop after the usual pickup and drop-off
- Maximum total stops (whole number; pickup + drop-off = 2 stops)
- Optional rising payout: every offer must pay more than the last offer you accepted

The required payout is `max(flat, miles × mile rate, minutes × minute rate) + extra stops × fee`. If an enabled value is missing, the app may still decline when a known lower bound already fails; otherwise it leaves the offer for manual review. Auto-decline is off by default.

With only a $20 flat minimum enabled, missing mileage and stops do not prevent a decision: below $20 declines, and $20 or more stays available for you to choose. A qualifying offer is never accepted automatically.

The stop limit is independent of payout: a maximum of 2 allows up to 2 stops and declines 3 or more, even on a high-paying offer. Set all price rules to zero to use only maximum stops. Missing or conflicting stop counts cannot trigger the stop limit; a known payout failure can still trigger a price rule. Updates preserve existing price settings and auto-decline, and start the new stop limit at 0 (off).

**Only offers above my last accepted payout** is off by default. For example, after accepting $25, the next offer needs at least $25.01 plus compliance with your other rules. Passing offers never raise the baseline. Acceptance tracking requires a visible offer with readable pay, an observed Accept click, and a recognizable delivery action within 15 seconds. Missing click events, unreadable pay, or unfamiliar delivery layouts leave the previous baseline unchanged. The baseline persists across restarts and updates; **Reset last accepted payout** clears it. Until the first acceptance is detected, the usual rules apply. A rising threshold can eventually exclude most offers.

**Refresh status** shows whether Accessibility is connected, whether saved auto-decline is ON, and the saved minimum payout and stop limit. It reports missing controls, unreadable pay, or failed click attempts. A requested tap is not reported as a completed decline. If an offer is missed, this status is the first diagnostic to check.

## Diagnostics

Turn on **Capture notification and screen diagnostics** while troubleshooting. The app keeps a bounded on-device text log containing DoorDash notification labels, the Accessibility labels visible on changed screens, parsed pay/miles/time/stops, rule decisions, foreground-wake results, and decline outcomes. Nothing is uploaded automatically. **Share diagnostics** sends the text through Android's share sheet so it can be attached to a ChatGPT conversation; **Clear diagnostics** deletes the local log. Because raw screen labels can include store, customer, or location text, leave capture off when it is not needed.

### Add-on offers

When an active delivery has been observed after an Accept action, Offer Filter keeps a short-lived on-device snapshot of that route. A later DoorDash offer explicitly presented as an add-on is evaluated in context rather than as a fresh standalone order. The combined route must still satisfy the flat minimum, maximum stops, and enabled route economics. Separately, the add-on payout must cover its incremental miles/time and the incremental increase in extra-stop fees. This prevents a strong first order from subsidizing a bad add-on while avoiding charging the flat minimum twice.

If DoorDash presents explicit new route totals, Offer Filter subtracts the saved active route to recover the marginal add-on. If it presents ordinary add-on figures, those are treated as incremental. Conflicting or missing total/incremental values remain unknown and fall back to manual review unless a known rule failure is already enough to decline. The active-route snapshot is cleared when Dasher returns to a recognizable idle/searching screen and expires after three hours.

## Automatic updates

Install the latest version from the [permanent APK download](https://github.com/Dillxn/dasher-offer-filter-updates/releases/latest/download/OfferFilter.apk). In Offer Filter, tap **Allow automatic installs** and enable Android's **Allow from this source**. Update notifications are optional and silent.

Automatic updates are on by default and can be switched off. Offer Filter requests a network-backed JobScheduler check every 15 minutes with a 5-minute flex window, which is Android's minimum periodic interval; Android may still defer background work. Opening Offer Filter, connecting Accessibility, and connecting the background notification listener also trigger a check. Successful foreground/event checks are coalesced for 60 seconds, failures retry after 1, 2, 4, 8, then 15 minutes, and feed requests are cache-busted. New versions download automatically, verify package, version, exact size, SHA-256, and the installed app's signing certificate, then request self-installation. Installation waits while Dasher is the foreground app. Saved rules and the accepted-offer baseline stay in place.

[Android permits self-updates without user action under specified conditions](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)); the installer still handles confirmation when required. A silent notification or the **Check / install update** button completes that confirmation. No accessibility automation clicks Android permission or installer screens.

Releases and APKs appear in the [original repository's Releases](https://github.com/Dillxn/dasher-offer-filter/releases), with matching APKs and the feed in the public [update repository](https://github.com/Dillxn/dasher-offer-filter-updates). The source repository is private. The phone needs no GitHub token or login for automatic updates. Its only network requests fetch update metadata and APKs; offer information and settings stay on the device.

## Setup

1. Install the debug APK from `app/build/outputs/apk/debug/` on the Android phone.
2. Open Offer Filter and enter your rules. Leave auto-decline off initially.
3. Enable Offer Filter under Android Accessibility settings. On newer Android versions, you may need to allow restricted settings from Offer Filter's App info menu before its accessibility service can be enabled.
4. Tap **Allow background offer access** and grant Offer Filter notification access so DoorDash offers can be detected while Dasher is backgrounded.
5. Check **Last offer check** against an actual offer while parked. Turn on auto-decline only after pay and distance are being read correctly.

The app reads only the Dasher package (`com.doordash.driverapp`). Android Accessibility needs broad screen access, so review the permission before enabling it. [DoorDash's deactivation policy](https://help.doordash.com/en-us/dashers/article/deactivation-policy-us-english-dx) lists automated monitoring or scraping; using this app may put Dasher account access at risk.

## Build

Use JDK 17, Android SDK Platform 36, and SDK Build Tools 35.0.0. Run `./build-local.sh`. The APK stays local. Gradle 8.13 is also supported with `./gradlew testDebugUnitTest assembleDebug` when its dependencies are available.

To ship a new update, increase `versionCode` and `versionName` in both `app/build.gradle` and `build-local.sh`, update `RELEASE_NOTES.md`, and push source to `main`. GitHub builds the signed APK in the cloud with the private-repo signing key, verifies it, and stores the signed artifact. The public update repo can then receive the verified APK as Base64 plus an updated `latest.json`; phones decode and re-verify the APK before installation.

### Signing-key reset and cloud builds

The original 0.3.1 private signing key is no longer available. Android requires self-managed APK updates to preserve signing identity, so 0.3.1 cannot be upgraded in place with a newly generated key. The recovery path is a one-time uninstall/reinstall onto a new signing baseline.

The private source repo now owns that replacement signer through the **Build signed Offer Filter** GitHub workflow. The workflow restores the signer from a private release asset named `signing-key-v1`; if it does not exist yet, it creates the key once and stores the PKCS12 file only in the private source repository's Releases. The public update repository never receives the signing key.

The workflow runs the JUnit suite, builds and signs the APK, verifies the certificate, records SHA-256/size/version evidence, uploads a signed workflow artifact, and creates a private `reset-v<version>` baseline release. This removes any dependency on a particular Mac or Chromebook.

The new updater also supports a public Base64 APK channel under `dasher-offer-filter-updates/apks/`. Decoded APKs still undergo exact package, version, size, SHA-256, and signing-certificate validation before installation. The existing public `latest.json` remains on 0.3.1 until the replacement baseline has been installed, so the old app is not repeatedly offered an APK it cannot authenticate.

After the one-time reinstall, subsequent versions can use the new cloud-held signer and the normal automatic update loop.

## Current limit

Offer layouts change. The parser requires both Accept and Decline controls and a readable value that fails an enabled rule before the initial decline. Mileage numbers and units split between neighboring text nodes are supported, as are explicit stop counts. Item counts and unnumbered pickup/drop-off rows are not assumed to be stop totals. Conflicting or missing values remain unknown. Payout reading and declining were confirmed on the phone with 0.1.0. A missed $3 offer under a $22 minimum was reported after 0.2.0; its exact cause is not confirmed without phone status. Version 0.2.1 fixes permanent suppression after a tap request, adds bounded retries and clearer diagnostics, and supports more Accept countdown labels. Version 0.3.0 adds accepted-offer tracking and self-updates. Version 0.3.1 fixes the confirmation guard and selection of the second decline action. Its signed APK build passed all 45 JUnit tests. Version 0.4.1 adds background DoorDash notification handling, filtered-notification cancellation, foreground wake diagnostics, an on-device shareable screen/notification log, active-route-aware add-on evaluation, 15-minute periodic update checks, event-triggered checks, bounded fast retry, feed cache-busting, and publisher-side public-feed/APK verification. The 0.4.1 source has not been APK-built in this execution environment because the existing signing key and Android SDK are not available here. Background wake behavior, notification payload shapes, confirmation reliability, ringing behavior, live accepted-offer detection, and installation still need confirmation on the phone.

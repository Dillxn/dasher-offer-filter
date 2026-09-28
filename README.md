# Offer Filter

A local Android companion for the DoorDash Dasher app. It reads the visible offer screen through Android Accessibility and can tap **Decline** when the shown guaranteed payout is below your saved rule or the total stop count exceeds your limit. It never signs in to DoorDash, sends offer data to a server, or accepts an offer.

Offers are evaluated immediately when Android reports a screen change, with no waiting timer before the first decline attempt. The app rechecks the current screen every 200 ms for up to 3 seconds after a Dasher event so an early, incomplete screen or missed tap can recover without another event. The same failing offer can receive up to four decline requests, at least 250 ms apart; a different offer is eligible immediately. Every retry reloads saved rules and checks the current foreground screen. The app makes no sound or vibration. Android and Dasher still determine screen/event delivery time. The **Dasher notification sound settings** button opens Android's controls for Dasher's own alerts.

## Rules

Set any unused value to zero:

- Flat minimum payout
- Minimum dollars per mile
- Minimum dollars per minute, only when an explicit estimated duration is shown
- Fee for every stop after the usual pickup and drop-off
- Maximum total stops (whole number; pickup + drop-off = 2 stops)

The required payout is `max(flat, miles × mile rate, minutes × minute rate) + extra stops × fee`. If an enabled value is missing, the app may still decline when a known lower bound already fails; otherwise it leaves the offer for manual review. Auto-decline is off by default.

With only a $20 flat minimum enabled, missing mileage and stops do not prevent a decision: below $20 declines, and $20 or more stays available for you to choose. A qualifying offer is never accepted automatically.

The stop limit is independent of payout: a maximum of 2 allows up to 2 stops and declines 3 or more, even on a high-paying offer. Set all price rules to zero to use only maximum stops. Missing or conflicting stop counts cannot trigger the stop limit; a known payout failure can still trigger a price rule. Updates preserve existing price settings and auto-decline, and start the new stop limit at 0 (off).

**Refresh status** shows whether Accessibility is connected, whether saved auto-decline is ON, and the saved minimum payout and stop limit. It reports missing controls, unreadable pay, or failed click attempts. A requested tap is not reported as a completed decline. If an offer is missed, this status is the first diagnostic to check.

## Setup

1. Install the debug APK from `app/build/outputs/apk/debug/` on the Android phone.
2. Open Offer Filter and enter your rules. Leave auto-decline off initially.
3. Enable Offer Filter under Android Accessibility settings. On newer Android versions, you may need to allow restricted settings from Offer Filter's App info menu before its accessibility service can be enabled.
4. Check **Last offer check** against an actual offer while parked. Turn on auto-decline only after pay and distance are being read correctly.

The app reads only the Dasher package (`com.doordash.driverapp`). Android Accessibility needs broad screen access, so review the permission before enabling it. [DoorDash's deactivation policy](https://help.doordash.com/en-us/dashers/article/deactivation-policy-us-english-dx) lists automated monitoring or scraping; using this app may put Dasher account access at risk.

## Build

Use JDK 17, Android SDK Platform 36, and SDK Build Tools 35.0.0. Run `./build-local.sh`. The APK stays local. Gradle 8.13 is also supported with `./gradlew testDebugUnitTest assembleDebug` when its dependencies are available.

## Current limit

Offer layouts change. The parser requires both Accept and Decline controls and a readable value that fails an enabled rule before it acts. Mileage numbers and units split between neighboring text nodes are supported, as are explicit stop counts. Item counts and unnumbered pickup/drop-off rows are not assumed to be stop totals. Conflicting or missing values remain unknown. Payout reading and declining were confirmed on the phone with 0.1.0. A missed $3 offer under a $22 minimum was reported after 0.2.0; its exact cause is not confirmed without phone status. Version 0.2.1 fixes permanent suppression after a tap request, adds bounded retries and clearer diagnostics, and supports more Accept countdown labels. These changes still need a live offer check.

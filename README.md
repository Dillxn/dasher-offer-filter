# Offer Filter

A local Android companion for the DoorDash Dasher app. It reads the visible offer screen through Android Accessibility and can tap **Decline** when the shown guaranteed payout is below your saved rule. It never signs in to DoorDash, sends offer data to a server, or accepts an offer.

## Rules

Set any unused value to zero:

- Flat minimum payout
- Minimum dollars per mile
- Minimum dollars per minute, only when an explicit estimated duration is shown
- Fee for every stop after the usual pickup and drop-off

The required payout is `max(flat, miles × mile rate, minutes × minute rate) + extra stops × fee`. If an enabled value is missing, the app may still decline when a known lower bound already fails; otherwise it leaves the offer for manual review. Auto-decline is off by default.

## Setup

1. Install the debug APK from `app/build/outputs/apk/debug/` on the Android phone.
2. Open Offer Filter and enter your rules. Leave auto-decline off initially.
3. Enable Offer Filter under Android Accessibility settings. On newer Android versions, you may need to allow restricted settings from Offer Filter's App info menu before its accessibility service can be enabled.
4. Check **Last offer check** against an actual offer while parked. Turn on auto-decline only after pay and distance are being read correctly.

The app reads only the Dasher package (`com.doordash.driverapp`). Android Accessibility needs broad screen access, so review the permission before enabling it. [DoorDash's deactivation policy](https://help.doordash.com/en-us/dashers/article/deactivation-policy-us-english-dx) lists automated monitoring or scraping; using this app may put Dasher account access at risk.

## Build

Use JDK 17, Android SDK Platform 36, and SDK Build Tools 35.0.0. Run `./build-local.sh`. The APK stays local. Gradle 8.13 is also supported with `./gradlew testDebugUnitTest assembleDebug` when its dependencies are available.

## Current limit

Offer layouts change. The parser requires a clear payout and both Accept and Decline controls before it acts. The on-device offer labels have not yet been verified against a live offer on this phone; keep auto-decline off until that check is done.

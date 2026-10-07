# Public beta: physical-phone acceptance gate (0.5.0)

Updated 7 October 2026. This checklist is not a test receipt: no box below has been checked on a real phone. Plain Java tests, simulated Android 8 and 15 (Robolectric) and a verified APK download do not establish installation or real Dasher behavior. Never report a check as passed on a real phone until a tester has recorded it.

## How to run it

- **Phones:** a Samsung Galaxy on One UI 8 (Android 16; the owner's phone, Dasher 8.100.x) and a Google Pixel on Android 15 or 16. Where a check says so, also a Pixel on Android 14, another Galaxy on One UI 6–7, and one phone on Android 8–12 (it has no prefetch choice, so Android picks how much each root fetch brings).
- **Testers:** consenting testers, stationary. Don't create an unwanted delivery commitment, and never turn on auto-accept only to produce evidence.
- **Record for each check:** phone and build, Dasher's version, Offer Filter's version, what you did, what you expected, what you saw, pass or fail, and the log lines named. Get the logs from Settings → Share report, or from Send anonymous feedback with masked diagnostics attached. Use invented names and addresses only. Never attach customer, address, payment or account data to public issues.
- **A tap is only a request.** Check what really happened in Dasher (its own history and screens), not only what Offer Filter logged.

## 1. Install, notice and the update path

- [ ] **Independent install:** a tester with no account of any kind downloads from the normal public page and installs. Record every security or installer warning, without turning any protection off.
- [ ] **Notice first:** before I understand and accept, nothing reads Dasher, no tab shows over it, and nothing is sent (no feedback, offer report or diagnostics). Terms, Privacy and License open offline. Not now and Back leave the notice unaccepted; accepting is kept.
- [ ] **Safe defaults:** a fresh app starts paused with no rules. The mascot alone turns filtering on and off. Auto-accept stays off until its separate Settings confirmation. Denying location doesn't stop filtering.
- [ ] **Update from 0.4.72:** an original-signed 0.4.72 updates through the app's automatic update, without uninstalling, and keeps its history. "Up to date" is not an upgrade test.
- [ ] **0.4.72 held by a dash it never saw end:** on 0.4.72, turn Offer Filter's Accessibility off (as testers did to get through 0.4.72's frozen Dasher) and let a DoorDash offer notification arrive, so 0.4.72 never reads the dash's end. Once Render serves 0.5.0, 0.4.72's Settings → Updates keeps saying "Update ready: installs after your dash" (0.4.72 has no homepage Install now and no 8-hour limit). A tap on Updates, with Dasher not on screen, installs 0.5.0 and keeps rules and history; so does installing the downloaded 0.5.0 over it. Check that the tester announcement and offerfilter.org/install step 10 say so ("Already on 0.4.72?").
- [ ] **0.5.0 rules after the update:** with 0.4.72 rules that used per stop, score by area, the adaptive minimum or a percentage other than 100%, the "Your rules are simpler now" note shows once after the notice and lists exactly what changed. The new minimums match the note (per stop folded into minimum pay, the old percentage built in and rounded up). Auto-accept is off if it was on. Autopilot is off. With plain rules at 100% and nothing learned, no note shows.
- [ ] **Launcher icon:** update from 0.4.72 with the night icon enabled and pinned, on Pixel Launcher, One UI Home and one third-party launcher. The pinned icon survives with no duplicate drawer entry, and Day, Night and Auto no longer touch it.

## 2. Restricted settings and setup steps

- [ ] **Pixel (Android 14, 15 and 16), fresh install from the browser,** and again from a file manager: the homepage's first step is Allow restricted settings. Fix → Try the switch → Android's "Restricted setting" dialog (Accessibility shows "Controlled by Restricted Setting"; record the exact words) → App info → ⋮ → Allow restricted settings. The ⋮ item appears only after that first try. Come back within 10 minutes: the Accessibility list opens (not the service's own page) with one "Downloaded apps → Offer Filter" toast, the step goes away, and the log says "restricted settings allowed". The log also shows "not open to apps here (SecurityException)" for the service's own page. Repeat for notification access.
- [ ] **Galaxy on One UI 8** (and One UI 6–7 if available): the same flow. Samsung's dialog says "App was denied access" (record the exact words); the way out is still App info → ⋮ → Allow restricted settings. Note App info's ⋮ wording, the "Installed apps" list name, whether notification access is restricted too, and whether the listener has its own page (if not, the list opens naming "Offer Filter background offers").
- [ ] **Android 15 and 16:** can the app read its own access_restricted_settings record, and does it move from errored to ignored to allowed as assumed (Enhanced Confirmation Mode)? Note what the step decided (log and screen). An adb or app-store install must show no step.
- [ ] **After the first self-update** of a browser install: what does Android report as the install source and installer, and does the restriction stay lifted?
- [ ] **Accessibility details page:** on Pixel, Samsung and one other maker, does Fix open Offer Filter's own Accessibility page, or the list with the "Downloaded apps (or Installed apps) → Offer Filter" toast?
- [ ] **A granted access that stopped working** (after a force stop or an update): "Reconnect notification access" asks Android to reconnect the listener, and a second tap within a minute opens its page. "Turn Offer Filter off and on in Accessibility" opens the right page.
- [ ] **Battery:** on a Pixel, the "Restricted" battery setting shows Settings' "Battery limits background work" row, and its Fix opens the app's battery page (App info as the fallback). On One UI, note whether "Put unused apps to sleep" or "Deep sleeping apps" puts Offer Filter in the restricted standby bucket (45), and whether choosing Unrestricted lifts it.
- [ ] **Small phone, large fonts:** on a 360×800-class phone at font 1.0, 1.3 and 2.0, a fresh sideload shows the first step plus "N more to set up", setup lines keep to two lines (their words shrink at most 25% at 2.0), the constellation keeps its size, and the start line covers no knob.

## 3. Dasher stays responsive with Offer Filter on

Do every check here with auto-decline on and again with it paused, on the Galaxy and the Pixel (Android 13 or later), then once more on an Android 8–12 phone.

- [ ] **Navigate:** during a delivery, with Dasher's map up, tap Navigate. The maps app opens at once, and no Offer Filter tab appears over it. Back in Dasher, the tab returns within about a second.
- [ ] **Complete delivery:** complete a delivery. Its pay shows, and Dasher answers every tap.
- [ ] **End dash:** End dash, then End dash on "End your current dash?". The dash ends, and the log shows "[dash] ended". Pressing Home right after End dash also releases the screen ("Dasher left the screen after your End dash"); Go back on the question keeps it held.
- [ ] **Offer details render without split screen:** a background offer's details draw in Dasher full screen, with Offer Filter on (the 0.4.72 report: they appeared only after split screen).
- [ ] **Paused means not reading:** pause from the tab mid-dash. The status reads "Paused: Offer Filter is not reading Dasher", the tab stays as it was until Dasher's screen changes and then becomes the slim edge that takes no touches, and no screen-read history lines appear. A user with no rule yet sees only the slim edge. Resume, and the screen is read at once.
- [ ] **A decline whose question never comes,** followed by Dasher's own map: the read-load line shows about one timed read a second, and Dasher stays responsive.

## 4. Read load and Dasher's map

- [ ] **Map node dump:** on the Galaxy (Android 16, Dasher 8.100.x), capture a node dump of the waiting, pickup, delivery and navigation screens. Record the map node's class name and content description: does Dasher keep "Google Map", or use Mapbox with no description?
- [ ] **Map pruning:** the log shows "[screen] map subtree skipped (N children) on Dasher's <kind> screen" for the waiting, delivery and navigation screens, with a small N (markers only). If it never appears, record the map node's class name and description before changing MapNodes. If N is large, or the kind is "offer", check that no offer controls sit inside the map.
- [ ] **Read-load lines** ("[screen] read load: …", once a minute) on each screen: about 240 or fewer reads a minute, all fact-free, on the waiting map; about 60 or fewer during a delivery or navigation; truncated 0 on map screens; and note "slowest" and "median fetch" for each phone.
- [ ] **Offers still read at once:** get offers while sitting on the waiting map. The first-step decline line says "read after change (waited 0 ms)", or 150 ms or less for an offer drawn by a content change ("read after content burst (waited N ms)"). During any "[screen] yielding to Dasher" period, no offer is missed: a window change or the offer's notification still reads at once.
- [ ] **Android 16 cost:** do the interruptible prefetch on routine reads and Android's node cache make reads cheaper than the test model assumes?
- [ ] **Window-list-only windows:** how often is a Dasher window first announced only by Android's list of windows (each costs one extra root fetch for a read taken at once)?
- [ ] **No watch fetches:** with Dasher full screen for several minutes, the "[peek] cost" and slow-read lines show no window-watch root fetches, and the tab and the screen hold still follow an app switch within about half a second.

## 5. Dasher's decline question and the acceptance rate

- [ ] **Exactly one percent:** decline an offer by hand and let Offer Filter decline one. On Dasher's "Are you sure you want to decline this offer?" question, is there exactly one percent next to acceptance-rate wording, drawn as one "9%" label (not two nodes)? The log shows "[autopilot] ar reading N% (exempt yes|no)" once per reading. If the percent is split across nodes, or there are several, Autopilot reads nothing and counts from the app's own history.
- [ ] **Before or after the decline counts:** compare the percent on two questions in a row (and with Dasher's own acceptance-rate screen, read by you, not the app). Is it the rate from before this decline counts (what Autopilot assumes) or after it? If after, Autopilot over-counts one offer since the reading.
- [ ] **"Does not lower acceptance rate":** when Dasher says so on its question, is that wording for that one offer, or does it appear on most declines? The offer's history line gets "Dasher said declining it does not lower your acceptance rate". The owner should confirm what the phrase means.
- [ ] **Autopilot at connect:** turning screen reading on starts Autopilot's thread (an "[autopilot]" plan line follows soon after), and the question's confirmation tap is never slower with Autopilot on.

## 6. Declines, takeovers and sound

- [ ] **Confirmation, takeover, lock and restart:** an automatic decline taps Dasher's question once. Your touch or tap hands the offer back (USER_TOOK_OVER). Locking the phone stops it. A partly drawn or unknown offer is left to you. After a restart, a decline in flight is never resumed.
- [ ] **Takeover kept:** touch an offer during its automatic decline, switch to Maps, wait for DoorDash to re-post its notification, then come back within the countdown. The offer is not declined again, and the log says "[takeover] kept".
- [ ] **Sound:** the ring is turned down while an offer is declined and comes back exactly, around navigation voice and calls. During Peek only the alarm stream is touched. Dasher's vibration still runs (Android allows no app to stop it).
- [ ] **Decline error recovery:** if Dasher shows "Error" or "Something went wrong. Please try again." after a decline, record what the screen does; at most two Back recoveries follow, never in split screen.

## 7. Peek

- [ ] **Background offer:** with the phone unlocked and Google Maps in front, a background offer opens Dasher, its details draw and are read, and the decline and return (or Dasher staying up) behave as the rules say. The log shows "[peek] Dasher up", "[peek] cost" with no extra root fetches, and "[split] Dasher resized" when the layout changed.
- [ ] **Dasher's own notification tap** (Android 14 and 15 on both phones; Android 16): when Dasher comes up without the offer, the log shows "[peek] Dasher's own notification tap: requested", then "[peek] Dasher window: <Class>" lines, with no "refused (…)" and no background-start block in logcat. The key question: does that tap show the offer the launcher did not ("[peek] offer facts read N s after Dasher came up (X s after Dasher's notification tap)" versus "offer never showed")? Compare the window class names after the launcher and after the tap.
- [ ] **Dasher's own notification while the offer shows only its buttons:** does Dasher redraw the offer with its figures, or reset or lose it? Does Dasher remove its own offer notification when that tap opens its offer screen? (If it does while still showing "Finding offers", the peek ends as withdrawn.)
- [ ] **"Dasher didn't show this offer" card** (Android 16): its tap opens Dasher's own offer screen. If Android blocks that silently, the log shows "[alert] card: nothing of Dasher's came up 1.5 s after its own notification's screen was asked for; Dasher's launcher: requested" and Dasher still opens, without a second LauncherActivity stacked. An ordinary card's tap still opens Dasher's launcher.
- [ ] **Unlock catch-up:** press the power button, let an offer ding, then unlock within 40 s with fingerprint, PIN, swipe, and Smart Lock or no keyguard. Expect "[peek] offer arrived while locked; waiting for unlock", then "[peek] unlocked in time: checking it (N s after the post)", and Dasher opening after a quiet moment. After 40 s the log says "unlocked too late". Also let DoorDash update the offer while locked: it still opens. Record how USER_PRESENT and SCREEN_ON timing differs between the phones. A card tapped from the lock screen, or Dasher's own notification tapped there, never leads to a catch-up peek.
- [ ] **Lock mid-peek:** lock with the power button while a peek has Dasher up, then unlock within 60 s. Expect "[peek] resumed after unlock", with the decline and return only after about 0.7 s without touches. A touch or a tap on Dasher right after unlocking leaves Dasher up. Do this once during a delivery too (the read budget active).
- [ ] **A re-post after accepting never peeks over Maps:** accept an offer (from the notification's Accept, and in Dasher), tap Navigate, and wait for DoorDash to re-post. Note whether it uses the same key. No peek opens over Maps. Also: tap Navigate with an offer up (or just declined by you), go to Maps and wait for the re-post. The log shows "[notification] the same store re-posted: a new offer (…)" then "[peek] skipped: a re-post of the offer you had in Dasher". Record DoorDash's real re-post timing and wording.
- [ ] **Pickup return:** during a delivery, let Peek decline an offer while Maps is in front. When Dasher returns to the pickup screen with its own amount, Peek goes back to the map; a tap on Dasher before that keeps Dasher up.
- [ ] **Gestures:** pan or pinch the map while a background offer arrives. Dasher never opens mid-gesture. Count how often peeks are skipped as "you were using the phone", and how often Peek still opens mid-pan when the gesture was already under way as the notification arrived.
- [ ] **A touch on Maps while Dasher opens:** after the automatic decline it still returns to Maps; a touch after Dasher appears leaves Dasher up. Compare accessibility event times with touch times.
- [ ] **Return:** another app's heads-up during the return doesn't cancel it; pulling the shade down does.
- [ ] **A same-offer re-post while Peek has Dasher up** keeps the peek waiting: no 4 s return and no "offer withdrawn" line.
- [ ] **Too big to read:** a peek over a screen too big to read ends at its own deadline and doesn't count as an empty peek.
- [ ] **Peek paused:** after an induced pause, the homepage shows "Peek paused: <why>" with Resume, the Settings switch stays on, and the pause ends at the next dash or after 15 minutes. Pausing auto-decline while a peek has Dasher up logs "[peek] left Dasher up because auto-decline was paused"; nothing goes back to the map, and Back to map is gone.

## 8. The screen kept on during a dash

- [ ] **The hold:** through a long dash with Maps in front, the screen never dims or times out. One press of the power button turns it off and it stays off; there are no wake-ups in a pocket. Battery saver, or 20% or less unplugged, lets it time out again; plugging in holds it again. Measure the battery cost.
- [ ] **Its release:** keep Maps or another app in front for more than 15 minutes with no offers. The log shows "[awake] screen timeout released: nothing of the dash seen for 15 minutes" (Google Maps and Waze keep the screen on themselves while navigating). With Dasher in front on its waiting screen, the screen stays on. After End dash it times out again at once (section 3).

## 9. Offer cards

- [ ] **No payless duplicate:** on a real dash with DoorDash's notification listed and tappable, no "Offers to check" card appears for an offer Offer Filter cannot judge, whether or not Android says DoorDash's alert sounded.
- [ ] **Silenced DoorDash channel:** set DoorDash's offer channel to Silent, then let a background offer come with the phone locked. Offer Filter's own card rings once. With DoorDash's default channel (high importance, no sound): no payless card. Check Samsung with pop-ups turned off for all apps too.
- [ ] **Offer details channel:** a card that carries what Peek read pops up silently on "Offer details" (no sound or vibration) on Android 8–16, with Do Not Disturb off and on. A card re-posted from a quiet group child pops up exactly once. Its countdown shows, and the card goes when the offer's countdown ends; check the lock screen and the shade.
- [ ] **Alerts denied:** with Offer Filter's notification permission denied, or Dasher's alert quiet, every offer that passes or can't be judged still has a way in: Offer Filter never removes Dasher's own notification of it.
- [ ] **Old settings with auto-decline on but no rule:** no pass chime and no Offer Filter card beside Dasher's own notification.
- [ ] **Accepted offers:** if DoorDash's offer notification ever carries figures with a Decline action (the owner says it doesn't), an accepted offer's post is left alone ("an offer you may have accepted; notification left alone").

## 10. Back to map and the tab

- [ ] **Back to map:** after a real peek left Dasher up from Google Maps (and from Waze, and Maps Go), and the offer showed and ended without an acceptance, the chip shows under the tab over Dasher's waiting screen. Its tap reopens navigation with the route intact, it hides after 12 s, and it never shows over an offer or a delivery. It still takes taps often enough over Dasher's live waiting map (it ignores taps while Dasher's screen changes). After a card tapped from Maps, the window list taken as you tapped still shows Maps, not Dasher.
- [ ] **The tab never over the map:** tap Navigate in Dasher. The tab disappears at once and never shows over Maps, and tapping its old spot in Maps doesn't pause auto-decline. During deliveries, the tab's half-second absence after each tap is not distracting.
- [ ] **Map shortcuts:** Navigate shortcuts and the offer map open the map in its own task: they don't stack inside Offer Filter's task, also from a split half.

## 11. Split layouts and the driving strip

- [ ] **Maps and Dasher split:** with Maps on top and Dasher below, over several minutes with no reads, the tab stays over Dasher's half (top and bottom placement, its saved split position). Over an offer it is the untouchable slim bar, green when the offer passes and amber when it needs review; away from offers it drags and taps. Maps' half never has a touch taken. With Offer Filter's own half beside Dasher, no tab shows, whichever half is active, paused or not. Dasher's half is read on the read budget.
- [ ] **The driving strip:** drag the divider so Offer Filter has about a third (screen height under 340 dp; note phones that snap only to 50/50; tablets and foldables keep the page). The strip shows the mascot (pause and resume work), the latest verdict, Autopilot's chip and the status. With notification access off, it says "Allow notification access" and its tap opens Android's page. Drag the divider back: the page returns, and a pending 0.5.0 note appears and the constellation stays in the header.
- [ ] **The divider hint:** beside Dasher at 50/50, "Drag the divider toward Offer Filter to give Dasher's map more room" shows once, over the bottom of the page for 10 s, takes no taps, and covers nothing important at large fonts.
- [ ] **Swap in Dasher:** with Offer Filter split beside Google Maps during a dash, the page says background offers need a tap in this layout. Swap in Dasher puts Dasher in Offer Filter's half and Maps stays. Which half a launch uses differs by phone; record it. The readiness line's Swap and the header button do the same.
- [ ] **Open Dasher:** in a narrow header at 2× font, one tap on Open Dasher brings Dasher full screen with the tab.
- [ ] **Three-app flow:** dash with Offer Filter, Dasher and Google Maps or Waze in each driving layout (Dasher full screen with the tab, Maps full screen with Peek, Maps and Dasher split with the tab), entering through notifications too, with the keyboard and the shade over Dasher at times. Check the route actually shown in the map, not only that a map app launched or that navigation voice plays.
- [ ] **Split setup:** the split button splits the screen, or opens recent apps with words for the phone (Pixel: Offer Filter's icon, Split screen, then Dasher; Samsung: Open in split screen view). Keyboard, pop-up and shade over Dasher's half stop reads there.

## 12. Autopilot and the 0.5.0 rules

- [ ] **Typical minimums:** on a fresh install, "Tap to start with typical minimums" → Use these sets $4.00 · $1.00/mi · $15/hr, leaves auto-decline paused and opens "What matters more?" with Keep a top tier (70%) preselected. Set my own makes the knobs beckon (in the strip it says to drag the divider first).
- [ ] **The goal chooser:** a tap on the Auto button while off opens the chooser; one tap applies ("Autopilot on · goal: acceptance rate 70% or more"); a long press changes the goal; a tap while on turns it off ("Autopilot off · back to exactly your minimums"). With no pay, per-mile or hourly minimum, the tap only says to set one. Someone never asked (who set their own knobs, or an updated install) sees the chooser once, the first time the mascot turns auto-decline on with a money minimum.
- [ ] **Status, chip and details:** the status line replaces Next match ("Autopilot 100% · learning (12 of 20 offers)" at first); a short window and the strip show the "Auto …" chip; a tap opens the details, which match the status. TalkBack reads the button as a switch with its bar and goal, and automatic bar changes are never announced.
- [ ] **Bar changes:** during a real dash, every change is one "[autopilot] commit A% -> B% (reason)" line, never while an offer, decline or peek is under way ("commit deferred: offer or decline in flight" at most once a minute instead). Turning Autopilot off puts the bar back at 100% at once.
- [ ] **Below your minimums:** with the bar below 100%, an offer between the bar and your minimums is left to you. Its card is on Offers to check ("…; below your minimums (85%), passed by Autopilot's 82% bar. Yours to accept.") without the pass chime, the slim tab is amber, and with auto-accept on it is never accepted.
- [ ] **Auto-accept:** with auto-accept confirmed in Settings, only a complete standalone offer at 100% of your minimums (or the bar, when higher) gets an Accept request, after about 0.7 s without touches. Record whether Dasher actually accepted it.
- [ ] **Outcomes, not lessons:** accept an offer by hand: its history line says Accepted and its skyline flag is the green bag. Decline one by hand: it is counted on its line. Neither changes any minimum (nothing is learned in 0.5.0).
- [ ] **Clear history:** removes Autopilot's acceptance-rate reading and its last change (the details then say the rate was not seen yet); the rules and Autopilot's settings stay.

## 13. Updates

- [ ] **Allow updates:** Android's "Allow from this source" opens from the homepage's Allow updates step, from Settings' Updates row, and from the update notification. The notification is silent, low-priority, once per version, and not posted while notifications are off.
- [ ] **First self-update of a browser install:** Android asks to confirm. Dismiss it: the homepage shows "Update ready · Install now", and its tap shows the confirmation again. The confirmation's notification appears only with notifications allowed.
- [ ] **Install now on a cold Render:** tap Install now right after opening the app. The tap's check runs after the opening check (log "check start manual=true"), and Android's confirmation or the install follows.
- [ ] **The 8-hour hold:** start a dash, force-stop Dasher so the dash's end is never seen, and leave the screen off for 8 hours or more (Doze). The 5-minute retry still runs in maintenance windows, the held update installs, and the log says "dash hold reached its ceiling". Nothing installs while the screen is on or Dasher is in front. Setting the date forward must not trigger it early. Note whether Android asks to confirm a background self-update.
- [ ] **Dash hold:** an update an automatic check found mid-dash waits ("Update ready: installs after your dash") through a pause, the lock and a reconnect, until Dasher shows the dash ended (or the ceiling above). A tap on Updates still installs it, unless Dasher is on screen.
- [ ] **Reopen after an update:** start an update from Settings → Updates. Staying on the updating cover, Offer Filter reopens over the home screen. Switching to Maps or Dasher during the install, it doesn't reopen, and the log says "[update] reopen after update: dropped (…)".
- [ ] **Updating cover:** an install that never reports back shows "Still updating? Continue" after 60 s; continuing doesn't stop the new version from opening when the install finishes.
- [ ] **Auto Blocker** (Galaxy, with Auto Blocker on): record what a self-update returns (STATUS_FAILURE_BLOCKED or something else), what Settings → Updates says, and whether Auto Blocker also locks Offer Filter's Install unknown apps switch.

## 14. Feedback, reports and the summary after a dash

- [ ] **Send anonymous feedback** with and without Attach masked diagnostics, and **Report this offer**, both offline and online. Each waits ("Saved; it will send when you're online") and then shows its reference. Preview shows exactly what goes, masked; use invented recipient headings and check that they are masked.
- [ ] **Report this offer's** dialog names your current rules and Autopilot's state, with the acceptance rate it counts with, and the sent report carries exactly that.
- [ ] **Diagnostics after each dash:** turn the option on around a real dash end and confirm one summary for that dash, none after it is turned off, and none of your acceptance rate in it.
- [ ] **After a forced stop,** the homepage's "Offer Filter stopped unexpectedly last time · Send report" sends nothing until Send.
- [ ] **An updated phone** shows no GitHub row, and no old queued report is ever sent.
- [ ] **Help** in Settings opens https://offerfilter.org/install/ once the site serves it (it answered 404 on 6 October 2026).

## 15. Other screens

- [ ] **Terms, Privacy and License** in light and dark: headings, bullets, numbered lists and links render, links can be tapped, text can be selected, and TalkBack reads them in order.
- [ ] **Contrast:** muted text and Fix words are readable on the day and night skies.
- [ ] **Background location:** the one line of why, then Android 11+'s settings page for Allow all the time; Not now asks nothing. Deny it twice, then Continue: App info opens with the toast "Permissions → Location → Allow all the time".
- [ ] **One-time cards:** Peek's introduction appears the first time filtering is on, its link opens Settings, and OK closes it for good. What is new appears only after an update, and only once its lines are written. Both survive the process being killed.

Broad Android compatibility claims need more than these two phones, including the oldest supported generation. The reported Samsung split/navigation mismatch and returning from a map on arrival remain unresolved unless observed with aligned evidence.

## Other launch gates

- **Sign and publish only from the tested source.** Bump to 0.5.0 (code 80), run the full tests on Android 8 and 15, then `tools/sign-local.sh` (it checks the cloud certificate, runs the tests with `-PallSdks` and leaves the APK in `dist/`) and `tools/publish-repo-feed.py` from the exact committed source. Commit `release/` on its own and push both commits to main; Render mirrors it. `tools/verify_channel.py` then reports the live version it tested.
- **Deploy the feedback backend's hardening first.** Before 0.5.0 ships, apply the hardening migration and then deploy the function (`backend/anonymous-feedback/README.md`), run its smoke test and confirm its 90-day deletion job is scheduled. The function deployed today answers 400, which the app drops, when its storage cannot be reached, so until then a storage outage loses automatic summaries.
- **Deploy the site.** Before merging 0.5.0 to main (Render rebuilds the download page from it), have the website serve https://offerfilter.org/terms/, /privacy/ and /license/. Until it does, each Render build links the missing ones to the texts in the public source instead (`tools/mirror_repo_feed.py` prints LEGAL_PAGE_NOT_SERVED for each); check the build log. Before 0.5.0 ships, have it serve https://offerfilter.org/install/: the app's Help row opens it with no fallback, and `tools/publish-repo-feed.py` refuses to publish while it does not answer (`tools/help_page.py`). On 6 October 2026 it answered 404 (the page is on the site's unmerged branch). The site's own release gate (`tools/build-legal.py`, `test-launch-help.cjs`) must pass once the 0.5.0 legal texts land.
- **Create the privacy@offerfilter.org forward.** Terms and Privacy name it as the private contact for privacy, data deletion and security (the owner's decision, 6 October 2026); offerfilter.org has no MX record yet. Send a test message and confirm it arrives. Public issues are not that private channel.
- **Legal texts:** Terms and Privacy are dated beta terms for 0.5.0. Never claim a lawyer reviewed them, and keep the rollout labelled beta: do not present incomplete field validation as a stable broad launch.
- Get the user's explicit confirmation of the two flows this release adds beyond what users send themselves: the opt-in "Share anonymous diagnostics after each dash" summary (DashSummary, hooked into both services) and the stop line's feedback dialog that opens with Attach masked diagnostics on (the user still taps Send). Without it, remove the DashSummary/StopReports hooks and open the stop line's dialog with Bug only.
- Verify offerfilter.org DNS, HTTPS and links after the separate domain owner completes setup. This lane did not change DNS or hosting settings.

## 0.4.35 — the log says why the adaptive minimum did or didn't learn

- The adaptive minimum learns from an offer only when:
  - auto-decline and **Adaptive minimum** are on;
  - the offer's pay was read on screen;
  - your **Accept** tap was seen;
  - Dasher showed a delivery screen within 15 seconds.

  Each step now goes in the log, so a shared report shows where it stops. The log lines are:
  - "Accept tap seen on …";
  - "Learned from accepted …";
  - "not learned: auto-decline or Adaptive minimum was off";
  - "Not learned: no delivery screen recognized within 15 s after Accept on …", with the screen Dasher showed instead;
  - "no offer with readable pay was on screen".
- Nothing about what is learned changed: guessing Dasher's wording could teach the minimum from offers you never accepted.

Evidence boundaries:
- **Simulated Android 8 and 15 tests** cover each of those log lines.
- **Not verified on a real phone:** which step stops on your dashes. A report after your next dash will show it.

## 0.4.34 — shopping screens never push offers out of a report

- **Two logs instead of one.** Offers, decisions and status go in one small log, Dasher's other screens (a shopping list, an item) in another, so a few minutes of shopping can no longer push the offers out of a report you share. Every report now has both: the newest offers log, then "Dasher's other screens (newest)", each starting at a whole line.
- **Kept only as long as a report can carry**, never older than a day: less customer text sits on the phone.
- **A ticking clock is not a new screen.** A screen whose only change is a number (a countdown, an ETA) is kept at most once a minute.
- The line under **Share report** now says exactly that.

Evidence boundaries:
- **Simulated Android 8 and 15 tests** cover:
  - an offer line still in the report after 40 large shopping screens;
  - the newest screen kept;
  - each section starting at a whole line;
  - ten minutes of a ticking delivery clock kept once;
  - a new item screen kept at once.
- **Not verified on a real phone:** a full shopping order's screens.

## 0.4.33 — screen text capture is automatic

- **No switch to remember.** What the screen reader sees, including Dasher's shopping screens, is always captured into a small rolling log on this phone: the last 24 hours, the newest 128 KB. **Share report** includes it; nothing else sends it, and automatic GitHub reports still carry only masked offer labels. **Clear history** now clears it too. The "Capture full screen text (30 min)" switch is gone; one line under **Share report** says what is kept.

Evidence boundaries:
- **Simulated Android 8 and 15 tests** cover:
  - capture on from the start;
  - an entry two days old dropped while today's is kept;
  - the report saying capture is automatic;
  - Clear history removing the captured text;
  - no capture switch in Settings;
  - a shopping-like screen kept while capture is on, and not while off.
- **Not verified on a real phone:** the log over a whole day of dashing.

## 0.4.32 — capture shopping screens

- **Capture full screen text** (Settings → Reports, 30 minutes) now also keeps Dasher's other screens (a shopping list, an item, a delivery step), once per distinct screen and at most once a second, so their wording can be learned from a report you share. It is the first step toward a store sketch for shopping orders. Nothing is decided from those screens, nothing is kept while capture is off, and nothing leaves the phone unless you share the report.

Evidence boundaries:
- **Simulated Android 8 and 15 tests** cover:
  - another Dasher screen's words kept in the report while capture is on;
  - nothing kept while it is off;
  - nothing tapped.
- **Not verified on a real phone:** what Dasher's shopping screens actually say.

## 0.4.31 — no second map beside Dasher

- **Split screen with Dasher shows one map: Dasher's.** Our map and its line step aside while Dasher is in the other half, since its own map is right there and the pointer over it shows the way to the best area. The room goes to the sky: the constellation is back at full size (about twice as large as in 0.4.30's header), above the skyline. Our map comes back if Dasher leaves the other half. A moment under the shade or in recent apps does not count as leaving (20 seconds do).
- **Why no squares on Dasher's map:** Android does not tell other apps where Dasher's map is centred or how far it is zoomed, so squares drawn over it would land in the wrong places. Direction and distance from you are safe (Dasher keeps you in the middle, north up), which is what the pointer shows.
- The road waits for a whole screen; in split screen Dasher's half is the ground below.

Evidence boundaries:
- **Simulated Android 8 and 15 tests** cover:
  - half a split screen beside Dasher (411×410 dp): no map, the constellation in the page at 110 dp or more, the skyline, no scrolling;
  - the map coming back and the constellation moving to the header when Dasher leaves;
  - Dasher's half remembered for a moment under the shade, not for 20 s of absence;
  - Dasher filling the screen never counting as beside.
- **Renders** of the page beside Dasher were reviewed, light and dark.
- **Not verified on a real phone:**
  - the switch as Dasher comes and goes in the other half;
  - the page at your exact split size.

## 0.4.30 — the whole picture in split screen

- **The skyline is back in split screen.** Its street is the horizon again, so the hills and ground no longer cut a hard line across the map.
- **The constellation fits the header.** In split screen it stands at the header's left with its icons beside the circle: a bigger circle in less height, clear of the split screen's handle. On a small circle only the outer dollar label shows, and the stars and marks scale down.
- **The page fits half a screen** (about 411×410 dp): the constellation, mascot and counts, one line needing a fix, the skyline, the map and its line, with no scrolling.
- **The map:**
  - its top and bottom fade less;
  - when it has to zoom in, it keeps you and the best area's coin in the clear middle rather than in the faded edge;
  - a place name that would overlap another is left out, the best area's first.
- **Over Dasher:** the pointer (and the tab) are placed in screen coordinates. The pointer now sits just under Dasher's "This dash" pill instead of a status bar's height lower, over the hotspots.
- Screen readers reach the page's name in split screen again.

Evidence boundaries:
- **Pixel tests** (simulated Android 8 and 15, native graphics) cover:
  - the map's edges fading while its compass and scale stay solid (the test fails with the fade off);
  - side-by-side names not drawn over each other.
- **Simulated Android 8 and 15 tests** cover:
  - half of a split screen at 411×410 dp fitting without scrolling, with the constellation in the header, the skyline, the map at 84 dp or more and the horizon above the map;
  - the constellation in the page body on a whole screen;
  - the overlay windows placed in screen coordinates.
- **Renders** of the split and full pages were reviewed, light and dark.
- **Not verified on a real phone:**
  - where the pointer lands over Dasher;
  - the page at your exact split size.

## 0.4.29 — Offer Filter is now Dash Buddy

- **New name: Dash Buddy**, so it sits right beside Dasher in alphabetical app lists such as the app drawer and Settings → Apps. The new name shows everywhere the app names itself:
  - the launcher and recent apps;
  - its screen-reading and background-offers entries in Accessibility and Notification access;
  - the tab over Dasher, toasts and the updating screen;
  - the update notification and install prompt;
  - the diagnostic report ("Dash Buddy diagnostics");
  - the note on a Venmo tip.
- **The passing-offer alert** now reads "DoorDash offer meets your rules".
- **Nothing to redo.** The package, signer and update feed are unchanged, so this installs over Offer Filter like any update. Screen reading and notification access stay on: Android remembers them by the app's package and service, not the name it shows. This one update is installed by 0.4.28, so if it asks for a tap, that prompt still says Offer Filter; the new name shows from the next update on.
- **What keeps the old name:** the update file (OfferFilter.apk), this repository, the "Offer Filter Updates" GitHub App and the report fixer's comments.

Evidence boundaries:
- **Java and simulated Android 8 and 15 tests** cover the renamed texts:
  - the diagnostic report's subject;
  - the tab's description over Dasher;
  - the takeover toast;
  - the updating screen;
  - the split-screen hint;
  - the Venmo tip note.
- **Not verified on a real phone:**
  - the new name in the launcher and settings lists;
  - that accessibility and notification access stay on across this update.

## 0.4.28 — split screen on phones that won't split for us, a softer map, and the constellation in split screen

- **Split with Dasher works on phones that ignore the split request.** Newer Android starts split screen from recent apps, so when the phone does not split the screen for us, Offer Filter opens recent apps with a hint (tap Offer Filter's icon above its card and choose split screen). Once the screen splits within a minute, Dasher opens in the other half by itself; a split much later opens nothing.
- **The map fades out at its edges.** Squares, coins, the trail and the grid dissolve into the ground over the last 22 dp instead of being cut off; the compass and scale stay crisp.
- **The constellation stays in split screen.** In a short window it rises into the sky at the top, beside the sun or moon, where the title used to be; a tap still opens the minimums.

Evidence boundaries:
- **Simulated Android 8 and 15 tests** cover:
  - a refused split opening recent apps with the hint;
  - Dasher opening beside Offer Filter after a split made there 30 s later, and not after one long after;
  - the constellation in the header of a short window, above the mascot, still opening the minimums.
- **Renders** of the map's fade and the short-window page were reviewed, light and dark.
- **Not verified on a real phone:**
  - recent apps opening from the button;
  - Dasher opening after a split made there;
  - how the fade looks on a real screen.

## 0.4.27 — fewer words, and split screen with Dasher

- **The homepage says less.** The picture carries it; words appear only where something needs you. Screen readers still hear all of it. Gone from the page:
  - the title;
  - "Auto-decline is on" and "Tap me to…" (paused shows **Paused**; no rules shows **Tap to set up rules**);
  - "Watching for offers" (the searchlights show it);
  - the words under the counts (✓ ✕ ? say which is which);
  - the constellation's names, key and caption (its spokes carry the same icons as in Settings);
  - the line under the skyline (tap a building for its ticket);
  - "You" on the map (the blue dot; the signpost names where you are).
  - The line under the map is now "Treasure Island · 3.6 mi NE · $3.00/mi ›".
- **Split with Dasher.** A new round button at the top puts Offer Filter above and Dasher below, at your tap only. In half a screen the page keeps the mascot, its counts, anything needing a fix and the map, which gets most of the room.
- **Dasher's half is still watched.** In split screen, an offer in Dasher's half is read and declined at once even while you are using Offer Filter's half. Under the shade or recent apps Dasher is left alone, as before.
- **Over Dasher:**
  - The filter tab shows only while Dasher fills the screen; in split screen, Offer Filter's own half has the mascot.
  - A small pill points toward the best offer area ("3.6 mi NE · $3.00/mi"). It sits beside the tab, or at the top of Dasher's half when split, and never covers an offer or takes a touch.

Evidence boundaries:
- **Simulated Android 8 and 15 tests** cover:
  - an offer in Dasher's half declined while Offer Filter's half is active;
  - a passing one left alone;
  - Dasher left alone behind another app or under the shade;
  - the tab only when Dasher fills the screen;
  - the guide inside Dasher's half, letting touches through, and gone while an offer shows;
  - the Split button asking Android through screen reading, then opening Dasher beside it once;
  - the short-window page.
- **Renders** of the full and half-height pages were reviewed, light and dark.
- **Not verified on a real phone:**
  - the split action and Dasher opening in the other half;
  - reading and declining in Dasher's half of a real split screen;
  - where the guide lands over Dasher's real map.

## 0.4.26 — reports refused by GitHub try again by themselves

- **When GitHub refuses a report** sent through your GitHub connection (403), the status line now shows:
  - GitHub's own words, such as "Resource not accessible by integration";
  - the step that is easy to miss: adding **Issues: Read and write** to the GitHub App is not enough by itself. The installation must accept it: github.com → Settings → Applications → Installed GitHub Apps → Configure → accept the new permissions.
- **Refused reports are sent again each time Offer Filter opens**, with a freshly renewed token, so approving the permission on github.com is all it takes. They are still kept only while reports are on.

Evidence boundaries:
- **Java tests with a local fake of GitHub** cover:
  - the message and steps shown for a 403;
  - the token renewed before the next attempt;
  - a refused report sent once GitHub allows it, and nothing sent when nothing was refused;
  - GitHub's message kept to one short plain line.
- **Not verified:** a real report through a real GitHub App after its permissions were accepted.

## 0.4.25 — the map is the ground, and updates just happen

- **The map is part of the homepage.**
  - Below the skyline, the land around you shows where offers came in, gilded deeper where they pay more per mile, with coins on the best three and a dotted trail to the best.
  - It is always there and centred on you, with squares big enough to read and tap.
  - One line under it names the chosen area, with Open in Maps.
  - Before location is allowed, a tap on the ground asks for it (approximate only).
  - Mapping is now on from the start; nothing is kept without location permission.
- **Neighbourhood names.** You, the best areas, and a signpost on the hills are named from the phone's own place lookup. Only rounded positions (about half a kilometre) are asked about, each once, and the names stay on the phone.
- **The mascot stands beside its counts** on phone screens, leaving room for the map.
- **Updates install by themselves**, even with Offer Filter open: the updating screen shows and the app reopens. They wait only while you are in Settings or reading a ticket, and still never during an offer, a delivery, or with Dasher on screen.
- Fewer options: the switch for the button in Dasher is gone; it shows over Dasher.

Evidence boundaries: the homepage was rendered and reviewed with and without mapped areas, in light and dark, paused and dashing.

- **Java tests** cover mapping being on from the start, while keeping nothing without location or once turned off.
- **Simulated place lookups** cover: positions rounded to about half a kilometre before asking, each asked once, names kept on the phone, and nothing asked while off.
- **Simulated Android 8 and 15 tests** cover:
  - the map always on the page, asking for approximate location only on a tap, and turning back on with a tap;
  - the best area's line opening Maps;
  - an update waiting only while mid-task.
- **Not verified:**
  - real place names from a phone's geocoder;
  - a real automatic install with the app open.

## 0.4.24 — reports through GitHub, and a calm update

- **Reports can use your GitHub connection.**
  - Once GitHub is connected for updates, Settings → Reports has **Send reports through my GitHub connection**, with no token to paste.
  - Turning it on is the opt-in; connecting GitHub alone never turns reports on.
  - Turning it off, turning reports off, or disconnecting GitHub stops reports and discards anything not yet sent.
  - The GitHub App also needs **Issues: Read and write**. Until that change is approved on GitHub, the status line says what to change and reports wait.
- **Updating is visible, and the app comes back.**
  - When an update starts installing with Offer Filter on screen, the screen shows the mascot waiting in a sweeping ring, "Updating Offer Filter…". It takes no taps and Back doesn't interrupt it.
  - If Android's install fails, the screen comes back as it was.
  - Once the new version is in, Offer Filter opens again by itself, but only if it was on screen when the update began, and never over Dasher.

Evidence boundaries:

- **Simulated Android 15 tests against a local fake GitHub** cover:
  - reports going through the connection only once turned on, sent with its token;
  - turning it off, or disconnecting, discarding what waited;
  - a refusal for missing Issues permission saying what to change.
- **Simulated Android 8 and 15 tests** cover:
  - the updating screen taking every touch and Back, and giving the screen back after a failed install;
  - the reopening happening only after an update begun on screen, only once the new version runs, and not again once open.
- **Not verified:** a real update on a real phone, including whether Android lets the app reopen itself there.

## 0.4.23 — a filter button inside Dasher, day and night, and counts per dash

- **A filter button inside Dasher.**
  - While Dasher is on screen, a small tab with the mascot's face sits on its left edge, a little above the middle, clear of the offer card and its Accept and Decline buttons.
  - It cannot be moved or dismissed. Blue and awake means auto-decline is on; amber and asleep means paused.
  - A tap pauses or resumes, keeping every rule. With no rule saved yet, it opens Offer Filter.
  - A tap during an automatic decline counts as your touch, so that offer is handed back to you.
  - It leaves when Dasher does. Settings → Filter button in Dasher turns it off.
- **Tap the sun or the moon** to switch between day and night. Until the first tap, the app follows the phone's dark mode.
- **Counts per dash.** Under the mascot: this dash's offers, passed, filtered and review (or the last dash's, between dashes), each with its all-time total underneath. The totals count every offer since the history was last cleared, beyond the 200 kept in the history. Clear history starts them again.

Evidence boundaries: the main page was rendered and reviewed in light and dark, while dashing, and before any dash.

- **Simulated Android 8 and 15 tests** cover:
  - the tab showing over Dasher only with the service connected: fixed on the left edge, never focusable, pausing and resuming with rules kept, leaving with Dasher and the service, and never showing when turned off;
  - the sun switching to night and the moon back to day, for the whole screen;
  - this dash's counts against earlier offers, and the all-time totals.
- **Java tests** cover a declined offer moving from review to filtered once, and Clear history resetting the totals.
- **Not verified:**
  - where the tab lands over a real Dasher screen;
  - tapping it during a real dash.

## 0.4.22 — one screen, a new icon, and adaptive minimums that stay

- **The main page fits one screen.** No scrolling:
  - the mascot, the constellation and the skyline share the height, and shrink to fit on shorter phones;
  - the passed, filtered and review counts keep their size;
  - the chosen offer's ticket slides up over the page instead of lengthening it, and Back or a tap outside puts it away;
  - with mapping on, one line on the ground names the best area; tap it for the map.

  Only a very large font on a small phone still scrolls, so nothing is ever cut off.
- **A new app icon** in the style of the pictures: the mascot in its brush-drawn ring over a morning sky with the sun, a cloud, hills and the road. The themed (one-colour) icon keeps the ring and the funnel's face.
- **Adaptive minimums stay until you reset them.**
  - The pay minimum is now the highest pay you have accepted, not the last. Accepting a lower offer later no longer lowers it.
  - Like the best rates, it is learned only while auto-decline and the adaptive minimum are both on.
  - Pausing, switching the minimum off and on, clearing the history and updating all keep what was learned. Only **Reset** clears it.
  - Declines now read "Not above your highest accepted $X".

Evidence boundaries: the main page was rendered and reviewed on 360×640, 360×780 and 412×915 screens, in light and dark, while dashing, empty and with the map, with the ticket and the map sheets open, and at 2× font on a 320×640 screen (which scrolls). The icon was rendered under round, rounded-square and square masks, and as the themed icon.

- **Java tests** cover a declined offer naming the highest accepted pay.
- **Simulated Android 8 and 15 tests** cover:
  - the page fitting a 360×740 screen, and the ticket sliding up and folding again with Back;
  - the map opening from the ground line and closing with Back;
  - accepted offers only raising the adaptive minimums, nothing being learned while off or paused, and all of it surviving pauses and toggling.
- **Not verified:** anything on a real phone.

## 0.4.21 — one picture, live while dashing

- **The main page is one illustration.** Instead of separate pictures stacked like magazine sections, the whole page is one scene:
  - a sky from the top down to the horizon: a soft morning blue warming towards the horizon with a sun and drifting clouds, or at night deep blue with a moon and stars;
  - the mascot and its state up in the sky, and the minimums drawn right in it as a constellation (no more porthole);
  - the offers' skyline standing on the horizon in front of two ranges of hills;
  - the chosen offer, its ticket and the map on the ground, down to the road with the little car.

  The section headings are gone; everything is still tappable and read out the same way.
- **Live while dashing.** While a dash is on, two searchlights sweep the sky from behind the hills, and a line with a pulsing green dot says "Watching for offers · last one 3 min ago". A dash counts as on after an offer, Dasher's "finding offers" screen or a delivery in the last half hour (or while a route is under way), until Dasher shows the dash ended or paused, and only while screen reading or background offers are on. It only shows what the app is doing; nothing is decided from it.

Evidence boundaries: the main page was rendered and reviewed in light and dark, while dashing, empty, with the map, at 320 dp and at 2× font.

- **Simulated Android 8 and 15 tests** cover:
  - offers and delivery screens meaning a dash is on, and "Dash ended" or the start screen ending it;
  - the live line and searchlights showing only while dashing with a service connected;
  - the horizon sitting at the skyline's street, with the constellation above it;
  - the scene drawing in both themes.
- **Not verified:** anything on a real phone, including which Dasher screens appear during a real dash.

## 0.4.20 — the adaptive minimums show as soon as they change

- **The star shows what was just learned.** Accepting an order (or declining a passing one by hand) raises the adaptive minimums. Until now the star on the main page redrew only when the next offer arrived, so right after accepting it still showed the old sparkles, or none. It now redraws within a second of anything being learned, as the Adaptive minimum line in Settings already did.

Evidence boundaries:

- **Simulated Android 8 and 15 tests** cover an order accepted while the page is open showing on the star without another offer. The test fails on 0.4.19.
- **Not verified:** that Dasher's screens after Accept are recognized on a real phone. If Settings → Adaptive minimum still says "None yet" after accepting orders, acceptances are not being seen, which is a separate problem.

## 0.4.19 — updates from GitHub without Render, and tips

- **Updates no longer depend on Render's build minutes.** Settings → Updates → **Connect GitHub**:
  - one tap asks GitHub for a short code, copies it, and opens github.com/login/device;
  - paste it and approve, and the app says whom it is connected as;
  - from then on it also reads releases from this repository and installs whichever of Render and the repository is newer, with the same size, SHA-256, package, version and signer checks;
  - if one source is unreachable, the other still works.

  The sign-in can only read this repository's files, refreshes itself, and is kept on the phone. **Disconnect GitHub** forgets it. It is separate from the report token and never turns reports on.
- **Tips instead of a price.** Settings → **Support** has one-tap tips through Cash App or Venmo. A tip only opens that app ready to pay; Offer Filter never charges or counts anything.
- Releases can be published to `release/` on `main` from a Claude session (`tools/publish-repo-feed.py`), replacing the manual pre-release workflow, which could not run.

Evidence boundaries:

- **Java tests** cover:
  - each channel accepting only its own exact feed and APK addresses;
  - the GitHub token going only to this repository's release folder;
  - the newer release winning, with a tie going to Render;
  - tip links, and names that could change the address being refused.
- **Simulated Android 15 tests against a local fake GitHub** cover:
  - asking for a code, waiting, and slowing down when asked;
  - approval, with the token limited to this repository and no client secret sent;
  - refresh, a refused refresh, and GitHub being unreachable;
  - a denied sign-in, device flow being off, and an expired code;
  - cancelling while GitHub answers.
- **Simulated Android 8 and 15 tests** cover the Connect GitHub and Support sections of Settings.
- **Not verified:**
  - signing in against real GitHub;
  - reading the real repository feed through the GitHub API (the published files were checked through git instead);
  - installing on a real phone.

## 0.4.18 — the mascot is the button

- **Tap the mascot to pause or resume.** The separate button is gone.
  - A small line under the state says what a tap does ("Tap me to pause").
  - With no rule yet, a tap opens the rules.
  - The mascot squishes a little while pressed.
  - Screen readers hear a button named for what it does, with the state and the day's counts.
- **A brush-drawn ring (an ensō) around the mascot** replaces the plain disc and shows the state:
  - nearly closed while on;
  - two-thirds and amber while paused;
  - a faint dotted circle while off.

  It draws itself when the state changes, breathes gently while on, and slides with the tilt like the rest of the sky.
- **Calmer where it helps.**
  - Setup problems are plain lines with **Fix**, without the tinted cards.
  - In Settings, the Android shortcuts, Share report, Clear history, Check for update and Allow installs are plain rows with a chevron instead of pairs of big buttons.
- The illustrations, motion and parallax stay as they were.

Evidence boundaries: the main page (on, paused, off, mapping) and Settings were rendered and reviewed in light and dark, at 320 dp, and at 2× font.

- **Simulated Android 8 and 15 tests** cover:
  - tapping the mascot to pause, resume, or open the rules;
  - its spoken label;
  - no other pause or resume control;
  - the ring drawing itself, resting with animations off, and drawing in every state.
- **Not verified:** anything on a real phone.

## 0.4.17 — zen, gentle motion, offers on the star, and manual declines teach

- **Quieter main page.** The speech bubble, the machine with its basket, bin and crate, the legend, the long notes and the map invitation are gone.
  - The mascot keeps the day's three counts, with one line of state and one soft button under it.
  - Headings are small captions.
  - The chosen offer is one line under the skyline; tap it, or a building, to unfold its ticket.
  - The skyline has no axis or times.
  - The star shows the spoke names only; screen readers still hear every value.
  - The map appears only while mapping is on, which is now turned on in Settings.
  - Buttons are soft pills, setup problems are quiet rows with **Fix**, and the rule fields are soft cards.
- **Gentle motion.**
  - Stars twinkle in the sky, over the skyline, around the mascot and in the star's porthole.
  - Clouds and birds drift, the car drives the road, and the mascot breathes and blinks.
  - While auto-decline is on, a ticket drifts into the mascot. While paused, a "z" floats up.
  - Buildings rise as offers arrive, the star's shapes glide to new values, the stamp thumps down, and the map's "You" halo breathes.
  - **Tilt parallax:** tilting the phone slides the sky's layers, the hills and the mascot's halo against one another. The gyroscope (or gravity sensor) is read only while the app is on screen. The readings are never kept or sent.
  - With Android's **Remove animations** setting on, nothing moves and no sensor is read.
- **Recent offers on the star.** Each of the last 14 standalone offers with a pay is marked on every spoke it can be placed on (● passed, ✕ declined, ○ review), at what its own pay, pay per mile, per minute and per stop would pay for the example offer. A mark outside a minimum beat it.
- **Manual declines teach the adaptive minimum.** Tapping Decline yourself on an offer the rules let through raises only the rule that came closest to catching it, just past that offer. The decline counts once the next different offer arrives, so a decline just before ending or pausing the dash teaches nothing. Also ignored:
  - an offer you then accept
  - the app's own taps
  - declines while auto-decline or the adaptive minimum is off
  - short trips (no mile or minute floor)
  - misread-looking offers

  The star and the Settings note show what declines taught, and **Reset** clears it.

Evidence boundaries: both pages were rendered and reviewed in light and dark, paused and off, at 320 dp, and at 2× font.

- **Java unit tests** cover choosing the closest rule, the "beat it" arithmetic, and the short-trip and misread guards.
- **Simulated Android 8 and 15 tests** cover:
  - a manual decline teaching only after the next offer, and nothing when the dash ends, for the app's own taps, or while learning is off
  - the folded ticket and its line
  - offer marks and decline floors on the star
  - mapping being turned on from Settings
  - tilt following the rotation sensor and gravity, recentring, and stopping when the app leaves the screen
  - no sensor and no motion with animations off
- **Not verified:**
  - Real gyroscope feel, frame rate and battery use on a phone.
  - Whether DoorDash's own Decline button reports its taps the way these tests model.

## 0.4.16 — background offers are no longer missed

- **Fix: offers were missed while Dasher was in the background.** DoorDash's background notification names the store but not the pay, so Offer Filter cannot judge it. Such offers got a silent card, and with DoorDash's own offer channel silenced, nothing made a sound. They now ring once on a new **Offers to check** channel while Dasher is not on screen. Tap the card to open Dasher. They are still never declined or opened for you. Nothing rings again on updates, replays or reconnects, or for offers the rules decline. Offers while auto-decline is paused alert the same way.
- The old silent "Unclassified offers" channel is removed; Android cannot make an existing channel louder, so the new one replaces it.

Evidence boundaries: simulated Android 8 and 15 tests cover the ring once, the quiet update and the absence of any launch; the ring test fails on 0.4.15. Not verified: DoorDash's real background notifications and sound on a phone.

## 0.4.15 — the map keeps showing the best area

- **Fix:** the map's details are meant to show the best area until you tap another. In 0.4.14, the first automatic choice counted as your tap, so when a better area turned up while the page was open, the details stayed on the old one. They now follow the best area until you pick one, and your pick then stays put as the ranking changes.

Evidence boundaries: simulated Android 8 and 15 tests cover both. The first test fails on 0.4.14. Not seen on a real phone.

## 0.4.14 — two drawn pages, and a map of where offers pay best

- **Pruned to two pages.** The tab bar is gone. The main page is one scene you scroll through. Everything set once moves to **Settings** (the round button at the top right). The history list, the "Latest offer" card, the rules meter and the email-address field are gone. The ticket, the star and **Share report** now do their jobs.
- **Drawn, not boxed.**
  - The mascot stands up in a little machine: offer tickets parachute into it and drop into a basket, a bin and a crate with the day's counts, and a speech bubble says the state.
  - Offers are a skyline with lit windows, needed-pay ropes and roof flags.
  - The chosen offer is a ticket with its outcome rubber-stamped on the stub. It follows each new offer until you pick an older one.
  - Minimums are constellations in a night-sky porthole, with the line "An offer like … needs $X".
  - Warning signs mark setup problems.
- **Creative controls.** The rules are price tags, and switches have the mascot's face for a knob (awake on, asleep off). Buttons are chunky keys that press down.
- **Where offers pay best** (opt-in). Tap **Start mapping** and each new offer is added to the square of about 2 km you were in, using only approximate location from fixes the phone already has. A treasure map gilds squares by pay per mile, with coins on the best three and a trail from you to the best. It all stays on the phone: squares never go into reports, and the map uses no map service. Settings can turn it off or **Forget areas**.
- An email address saved by older versions is removed, since nothing uses it now.

Evidence boundaries: both pages were rendered and reviewed in light and dark, at 320 dp, and at 2× font.

- **Simulated Android 8 and 15 tests** cover:
  - the settings button, Back, and keeping the page through recreation
  - the ticket following new offers, and tapping an older building
  - the rules preview and the star as typed
  - Share report and Clear history
  - Start mapping asking only for approximate location
  - the map's ranking and default area
  - the area store (opt-in, one count per offer, stale or missing locations, add-ons, pooled ranking, no position in reports, Forget)
- **Not verified:**
  - Real location delivery while Dasher is in front. The app counts offers that arrive without a location, rather than assuming.
  - Anything on a real phone.

## 0.4.13 — a star of your minimums, a mascot, and drawn scenery

- **Minimums as a star.** At the top of Rules, a radar chart has a spoke each for pay, per mile, per minute and per stop. Your set minimums are the solid shape with round points. The adaptive minimums learned from offers you accepted are the dashed shape with diamond points. The corners give the exact values (for example `$1.50` set and `$3.13` adaptive per mile). Distance from the middle is what each minimum asks of the same example offer the meter uses, so every spoke shares one dollar scale. The rings are labeled in dollars. The extra-stop fee gets no spoke, because it is added on top of the other minimums rather than being one. It follows the fields as you type. With a large font, the values move under the star.
- **A mascot.** The funnel has a face. It is cheerful while on, asleep ("z z") while paused, and blank while off. It waves from the empty Offers page and from the bottom of More.
- **Drawn scenery.** A sun and clouds sit behind the page title (a moon and stars in dark mode). Hills, houses, trees and a road with a little car sit along the bottom. Each page leaves room at its end, so the last card scrolls clear of them. All colors stay close to the page color, so text keeps its contrast.

Evidence boundaries: the star, the mascot in every state, and the scenery were rendered and reviewed in light and dark, at 320 dp, and at 1.3× and 2× font. Simulated Android 8 and 15 tests check the star's values as typed and with the adaptive minimum off, and the mascot's mood in each state. Not seen on a real phone.

## 0.4.12 — one screen with tabs

- **A tab bar instead of one long page.** Home, Offers, Rules and More sit along the bottom. A tap swaps the page in place, and each page keeps its scroll position. Nothing folds away any more.
- **Home is the glance.** It shows the filter picture, on/paused/off with its one button, any setup problem with its Fix button, and the latest offer. Tap the offer to open Offers. An active route also shows here.
- **Offers** has the chart, the selected offer and the last ten decisions. **Rules** has the meter above the fields. **More** has setup, reports, updates and the version.
- **No rule yet?** The main button reads **Set up rules** and opens Rules. Saving rules while paused now says so.
- Back from another tab returns to Home. While the keyboard is up, the tab bar steps aside.

Evidence boundaries: every tab was rendered and reviewed in light and dark, at 320 dp wide, and at 1.3× and 2× font. Simulated Android 8 and 15 tests cover switching tabs, Back, keeping the tab through recreation, the latest offer opening Offers, and the keyboard and system-bar spacing. It was not seen on a real phone.

## 0.4.11 — an app icon

- **A launcher icon.** The app had none. It now shows a white funnel on blue with a green check, the same filter as the status card. It is an adaptive icon, so it fits any launcher's shape, and it has a one-colour layer for Android 13+ themed icons.
- **Offer alerts in the status bar show the funnel** instead of a generic system symbol.

Evidence boundaries: the icon was rendered and reviewed under round, squircle and square masks, as a themed icon, and at launcher size. Simulated Android 8 and 15 tests check that the app declares it, that it has the themed layer, and that alerts use the funnel. How your phone's launcher shows it was not seen here.


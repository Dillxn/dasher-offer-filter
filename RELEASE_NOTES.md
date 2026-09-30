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


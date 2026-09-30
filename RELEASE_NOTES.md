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


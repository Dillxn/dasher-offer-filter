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


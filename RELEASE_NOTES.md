## 0.4.49 — the mascot shows only real offers

- **No more drip, and no ticket falling forever.** While no offer comes, the mascot just breathes, blinks and now and then waves.
- **Each offer decided while the page is open plays out a moment later, as it went.** Its ticket drops in under a little parachute and then:
  - **passed:** it slips through the sieve and out of the spout, with ✓;
  - **declined:** it bounces off the sieve, with ✕;
  - **review:** it rests on the sieve, with ?;
  - **left to you:** it rests on the sieve, with a person.
- It waits until the offer's line settles (a confirmed decline, a takeover), so it shows what really happened. Offers from before the page opened, and any while paused, play nothing. With animations turned off, nothing moves.
- The skyline's flags and the mascot now draw the same badges.

Evidence boundaries:
- **Simulated Android 8 and 15 tests:**
  - a new offer plays as what became of it, e.g. taken over after its first Decline plays as left to you;
  - old offers, offers already there and paused offers play nothing;
  - the whole suite passes.
- **Renders:** each outcome as a filmstrip, light and dark.
- **Not verified on a real phone.**

## 0.4.48 — tap off an offer to go back to the newest

- **With an older offer chosen** (from its dot, its polygon or its building), a tap anywhere on the constellation away from every offer, knob and button chooses the newest offer again: its polygon and score stand out, and the skyline's spotlight moves back. No ticket opens.
- Once the newest is chosen, such a tap does what it did before.

Evidence boundaries:
- **Simulated Android 8 and 15 tests:** choose an older offer, close its ticket, tap empty sky, and the newest is chosen again without a ticket. This fails on 0.4.47. The whole suite passes.
- **Not verified on a real phone.**

## 0.4.47 — the mascot, with a little more love

- **The funnel looks glazed:** soft shading lit from the upper left, a shine down its side, a glint on the rim, and a darker inside under the sieve.
- **Little touches:**
  - round mitts on its arms;
  - a collar and a rounded spout;
  - a soft shadow underneath;
  - rosier, softer cheeks, and a second sparkle in each eye.
- **While it's on:**
  - the drip falls to the ground and ripples;
  - every 11 seconds or so it waves at you, beaming.
- Paused, it still sleeps. Off, it stays grey and still. With Android's animations turned off, nothing moves.

Evidence boundaries:
- **Simulated Android 8 and 15 tests** (the whole suite) still pass. The drawing itself was checked in renders, light and dark, at large and phone size.
- **Not verified on a real phone:** how smooth the wave and the ripple look.

## 0.4.46 — Settings in one place; split screen done well; tickets say what happened

- **Every rule now lives on the chart.** Settings has no minimums fields and no Save button.
  - Drag a knob to set a minimum. With no rules yet, all four knobs are hollow and the line under the mascot says "Drag a knob to start".
  - Max stops is the "≤3" badge by the per-stop icon. Tap it to step through off, 2 … 10, or drag across it.
  - The sparkle button turns the adaptive minimum on or off and keeps what it learned. Long-press it to reset.
- **Settings is one row per item:**
  - fix rows, only while setup needs one;
  - mute while declining, and the offer map;
  - updates (tap to check now) and GitHub;
  - share report, problem reports and diagnostics;
  - clear history (it also clears the offer areas now), tip, and the legal links.
- **Gone:**
  - the pasted report token: reports go only through your GitHub connection;
  - the automatic-updates switch: checks are always automatic;
  - the explanation paragraphs.
- **Split screen:**
  - If Android doesn't actually split after you tap the split button, recent apps open, with directions for your phone.
  - Split without Dasher beside you? The button reads "Put Dasher beside".
  - In split screen, only a touch on Dasher's half hands an offer back. Touching Offer Filter's half no longer stops a decline.
  - Dasher's half isn't read while the keyboard, a pop-up or recent apps covers it.
  - The best-area pill sits below the status bar when Dasher is on top.
- **An update found mid-dash waits until your dash ends** ("Update ready: installs after your dash"), because installing closes Offer Filter's half of the split. Tapping Updates still installs it right away.
- **Tickets say what actually happened.** The stamp and the skyline flag now show:
  - **DECLINED** only when Offer Filter's decline went through;
  - **ACCEPTED** (a green bag) when you accepted it;
  - **YOURS** (a grey person) when it was left to you: you took it over, auto-decline was paused, the decline didn't finish, or the notification was only hidden.

  The building keeps the rules' colour. When the stamp and the rules differ, the ticket says so ("Rules: decline — too many stops (4, max 3)"). Your $26.30 offer would now read YOURS.
- **When Dasher's "are you sure" can't be confirmed,** the history now says "Dasher's question not confirmed; left to you" and counts it as review, not declined.
- **Score by area shows your adaptive minimums again.** The set minimums are the solid shape through the knobs, and what it learned is the dashed shape with sparkles. With adaptive on and nothing learned yet, a faint dashed outline shows it's on.
- **The notice shows once more**, because the privacy text changed: no report token, no updates switch, and shared reports and diagnostics now name your Android version and phone maker. Offer Filter waits until you tap I understand again.

Evidence boundaries:
- **No new plain Java tests** this time; everything below runs on simulated Android.
- **Simulated Android 8 and 15 tests** cover:
  - the outcome behind each stamp (accepted, yours, declined);
  - "confirmation not tapped" leaving the offer to you;
  - the knobs, badge, sparkle and reset;
  - Settings having no rule fields;
  - the token and updates-switch retirement on upgrade;
  - the split check and its fallback;
  - touches by half;
  - covered-half reads;
  - the update waiting for the dash to end;
  - the stamp, the Rules line and the skyline flags;
  - the learned dashed shape in score by area, and the nothing-learned outline.
- **Not verified on a real phone:**
  - Android's split action on your phone, and the recent-apps fallback;
  - which half a real touch lands on;
  - the notice showing again after the update.

## 0.4.45 — a one-time notice; learning knows Dasher's delivery screens

- **A one-time notice before Offer Filter starts.** It says plainly:
  - it's not a DoorDash app;
  - what it reads, and that it only ever taps Decline;
  - that using it may break DoorDash's terms and risk your account;
  - that it comes with no warranty;
  - not to handle your phone while driving;
  - how screen text is kept.

  **After this update Offer Filter does nothing until you open it and tap I understand.** One notification, "Offer Filter is paused until you open it", tells you so. It sounds once, never again.
- **Terms, Privacy and License** texts are in the app, under the Settings footer, readable offline. They are drafts for a lawyer to review before any public release.
- **Learning knows Dasher's real delivery screens** from your report: "Deliver to …", "Leave it at the door", "Verify correct order", "Handed order to customer", "Confirm to complete delivery", "Arriving at …". The dash summary ("This dash so far", "Continue dashing") and the zone screens count as waiting for offers. Turn-by-turn navigation alone counts as neither.
- **The screens log keeps your pickup screens.** Navigation screens are kept at most once a minute, and never push the last 30 minutes of other screens out of a report.
- "End your current dash?" counts as the dash ending only after you tap End dash.

Evidence boundaries:
- **Java tests** cover:
  - the delivery and waiting wording;
  - navigation;
  - the end-dash question.
- **Simulated Android 8 and 15 tests** cover:
  - the notice blocking every read, tap and card until accepted;
  - the reminder posting once and cancelling on accept;
  - the bundled texts matching the files;
  - pickup screens surviving 100 navigation updates.
- **Not verified on a real phone:**
  - Dasher's pickup-leg wording, which your next report should finally show;
  - the reminder's sound.

## 0.4.44 — tap an offer on the chart to see it

- **Tap an offer's dot, or inside its polygon, on the chart to open its ticket.** It's the same ticket a skyline building opens, and both show that offer as chosen.
  - When dots overlap, the nearest wins. Inside overlapping polygons, the chosen or newest offer wins.
  - Knobs and the chart's buttons keep their taps, and a drag never opens anything.
  - A tap on empty sky works as before.
- **While a ticket is open,** its offer's shape and dots stand out on the chart. A light highlight shows under your finger as you press.
- **Screen readers** reach each plotted offer after the chart's controls ("Offer $9.75, 3.3 mi, 18 min, 2 stops, declined"), and a double tap shows its details.
- The chart now marks only the offers shown on the skyline.

Evidence boundaries:
- **Simulated Android 8 and 15 tests** cover:
  - a dot tap opening its ticket;
  - the newest of two overlapping shapes;
  - score mode;
  - knob drags opening nothing;
  - empty sky;
  - screen-reader clicks.
- **Not verified on a real phone:** how easy dots are to hit on a busy chart.

## 0.4.43 — going back to an offer hands it to you; diagnostics after each dash

- **Going back to an offer is yours.** On 0.4.42, Offer Filter tapped Decline, couldn't tap "Decline offer" on Dasher's question, and when you tapped "View offer details" it declined the same offer again. Now:
  - The same offer showing again after its question, with no tap on the question's Decline, is handed back to you: nothing more is tapped on it, the sound comes back, and you see "Offer Filter stopped tapping this offer".
  - Any tap of yours on Dasher during a decline hands it back too: "View offer details", "Go back", "Cancel", the offer card, any button. Only a tap on the very button Offer Filter tapped counts as its own.
  - Once the question has shown, that offer's first Decline is never tapped again. One exception: Dasher closed a question Offer Filter confirmed, nothing of yours was seen, and the offer still shows 2 seconds later. That is logged as a Dasher glitch and gets one more Decline.
- **Your touch is never mistaken for Offer Filter's own.** A tap Android refused used to be retried on every read, every 0.1 s. Each retry restarted the 0.15 s window in which a touch counts as an echo of Offer Filter's tap, so any touch could be ignored. Each tap now has its own window, which no later tap or read extends.
- **Tapping Dasher's question.** A refused tap, or one Dasher doesn't act on, is retried at most twice, 0.3 s apart. Then Offer Filter stops, the log says "confirmation not tapped" and the offer is left to you. The question is also found in a window of its own beside a large offer screen; 0.4.42's quick check gave up after 200 items.
- **Diagnostics after each dash (optional, off by default).** Turn on **Share diagnostics after each dash** under Reports in Settings. It's shown while GitHub is connected and works while reports go through it. After each dash, the same report Share report builds is filed to your private repository's issues, so you don't have to email it.
- **Customer details are masked before anything is stored.** Names, addresses, phone numbers, emails and drop-off notes in captured screen text become [name], [address], [phone] and [instructions], both on the phone and in every report. Stores, pay, miles, minutes, buttons and shopping items stay readable. Offers are still judged from the real screen text.
- **The automatic report fixer is off until it is repaired.** It had been failing and emailing you "Run failed".
- **The log shows every try at the question:** where it was found (or why not), whether Android took the tap, and why a try was skipped.

Evidence boundaries:
- **Java tests** cover:
  - each tap's own echo window;
  - which clicks are Offer Filter's own;
  - the question's tries and the 2-second glitch rule.
- **Simulated Android 8 and 15 tests**, with Dasher's question as your report shows it, cover:
  - going back with no touch or click reported;
  - clicks on "View offer details", "Go back", "Cancel", the card and another button, including during a read;
  - a touch 180 ms after a refused retry counting as yours;
  - three tries 300 ms apart, then "confirmation not tapped";
  - the question found beside a 250-item offer screen.

  Each of these failed on 0.4.42.
- **Not verified on a real phone:**
  - why Dasher's "Decline offer" was not tapped on yours (the new log lines will show it);
  - whether Dasher reports your tap on "View offer details".

## 0.4.42 — declines are quick again; "+$1" offers decline; score by area

- **Declines are quick again.** Since 0.4.39 every read of Dasher's screen waited in one line, and new background reads (for learning, logging and taps) sat in it ahead of the offer. Reads on your phone often take 1–4 seconds, so Dasher's confirmation question was tapped 2–4 seconds after the first Decline, and once you beat Offer Filter to it.
  - Offer and confirmation reads now jump the line, and background reads stop for them.
  - After the first Decline tap, Offer Filter watches for Dasher's question every 0.1 s and taps it as soon as it shows. It no longer re-reads the offer's whole screen.
  - Its own taps no longer count as your touch.
- **"+$1" offers decline when they should.** Dasher draws "Very busy · +$1 · Decline · $5.75". Offer Filter now sees that "+$1" too, so an offer that misses your minimum even with it is declined; otherwise it's left for you.
- **Score by area (optional, off by default).** A small button by the chart switches the minimums from "every axis at least its minimum" to "the offer's shape covers at least your minimums' area".
  - Every offer's dots form a faint polygon. In score mode your minimum area sits in the middle and each offer's area is its score ("Score 121%", also on its ticket).
  - Max stops stays a hard limit. A missing number makes it Review. Add-ons keep the strict rules.
- **The log shows where read time goes:** every slow read near an offer, with its window, root and node times, and how long the confirmation took.

Evidence boundaries:
- **Java tests** cover:
  - the score;
  - "+$1" with Dasher's real label order.
- **Simulated Android 8 and 15 tests**, on the real reading thread, cover:
  - an offer read starting within about 10 ms while a slow background read is running (about 1 s before);
  - Dasher's question tapped about 0.1 s after it appears (about 2.5 s before);
  - echoes of the app's own taps ignored;
  - the score toggle and chart.
- **Not verified on a real phone:** real decline and confirmation times. Your next report shows them.

## 0.4.41 — the adaptive minimum learns from what you do

- **Your accepts teach it,** even when Dasher doesn't report your tap. An offer counts as accepted only when all of this holds:
  - Dasher's wait for offers was on screen before it, so no delivery was under way;
  - it closed with more than 3 seconds left on its countdown;
  - Dasher asked no decline question and Offer Filter requested no decline;
  - within a minute, Dasher moved on to a delivery screen.

  When any of that is unclear, nothing is learned and the history says why.
- **Your own declines teach it.** Your Decline tap, or Dasher's "Are you sure you want to decline this offer?", on an offer Offer Filter let through counts only once Dasher goes back to the wait for offers or the next offer comes. Going back to the offer or accepting it after all cancels it.
- **Every step is in the offer's history:** what was learned, or why not ("Not learned: it may have run out…"). Dasher's first screen after an offer goes to the screens log, so a report shows its wording.
- **The alert card names the store:** "Taco Bell offer: open Dasher to check it".
- **The GitHub connection is sturdier.** Updates and reports renew it one at a time. If it ends anyway, the log says why, and reports through it turn off until you turn them back on.
- **A quieter log.** Automatic update checks wait 5 minutes between tries (opening the app or tapping Check still checks at once), and are logged only when the result changes. Alert-channel details are logged once per change.

Once it learns from an accepted offer, the adaptive minimum rises to that offer's pay and rates. Reset clears it.

Evidence boundaries:
- **Java tests** cover:
  - each acceptance and decline path;
  - every case where nothing should be learned;
  - restarts;
  - taps found on Dasher's newer screens, including a tap on the card body not counting.
- **Simulated Android 8 and 15 tests** cover:
  - the history lines;
  - the store-named card;
  - the GitHub renewal;
  - the update pacing.
- **Not verified on a real phone:**
  - Dasher's real wording after an Accept;
  - whether Android reports your taps on Dasher.

  A report after your next accepted offer will show both.

## 0.4.40 — the tab and pointer over Dasher get out of the way

- **The pointer** (distance and pay per mile to the best area) shows only while Dasher is waiting for offers. During a delivery, shopping, an offer, or any screen it doesn't recognise, it stays hidden.
- **The tab** is smaller and mostly tucked past the screen edge.
  - Drag it up or down either edge, or across to the other side. It stays where you put it, separately in portrait and landscape. A drag never counts as a tap.
  - During a delivery, and on unrecognised screens while dashing, it shrinks to a thin bar. Tap the bar to bring the tab out for 6 seconds.
  - Over an offer or its confirmation it is a thin bar that takes no touches, so every tap goes to Dasher.
  - Screen readers get "Move up", "Move down" and "Move to other side".

Evidence boundaries:
- **Simulated Android 8 and 15 tests** cover:
  - the pointer shown only on Dasher's waiting screens;
  - dragging and remembering the tab;
  - the thin bar during a delivery and over an offer;
  - screen-reader moves.
- **Not verified on a real phone:**
  - whether a drag starting at the very edge is mistaken for Back;
  - how the thin bar sits against Dasher's real offer card.

## 0.4.39 — Offer Filter stays responsive beside Dasher

- **No more freezing in split screen.** Reading Dasher's screen used to run on the same thread that draws Offer Filter, once for every change on Dasher's screen. Its map and countdown change many times a second, and on a slow phone a read takes up to half a second, so Offer Filter's half froze. Reading now runs on its own thread: Offer Filter's screen never waits for it.
- **Fewer reads while nothing is happening.** The first change after a quiet moment is read at once; the rest of a burst of map updates is read together, at most 150 ms later. Anything that looks like an offer is read at once, and a decline is still tapped on the first read that sees the offer.
- **Calmer in split screen.** No tilt sensor, and slower animation, beside another app.
- **Safer timing around declines.**
  - An offer arriving just after you leave Dasher rings at once; it no longer waits for the next window check.
  - Your touch is watched before the confirmation is tapped.
  - Nothing is tapped after the service stops.
  - Sound turned down is put back at once when a next offer arrives.
- **Reports show the real timings:** a slow read is logged (at most once a minute), and each decline says what triggered the read and how long it waited.

Evidence boundaries:
- **Simulated Android 8 and 15 tests** run the real reading thread and cover:
  - a decline and its confirmation;
  - a slow read not holding up the screen (0 ms per event, against about 200 ms before);
  - a touch during a read;
  - stopping mid-read;
  - sound restored when the next offer arrives;
  - the 150 ms burst rule.
- **Not verified on a real phone:** how responsive the split screen feels, and real read timings. Your next report will show them.

## 0.4.38 — one offer, one line; the alert card opens Dasher; Offer Filter again

- **The name is Offer Filter again** (it showed as Dash Buddy from 0.4.29). It updates over the installed app as always.
- **One offer counts once.** Dasher's offer notification carries no pay, so it used to add its own "?" line beside the screen's decision. Now it folds into the offer the screen reads next to it, and the counts, history and status show only the screen's decision. Stored history is folded once on update; the all-time totals lose the duplicates still in the history.
- **The alert card keeps in step with Dasher.**
  - It clears as soon as the screen reads its offer, even after it rang.
  - It no longer appears for an offer the screen just read.
  - A second offer from the same store gets its own card.
- **Tapping the card opens Dasher** the way its app icon does, bringing up the offer already showing, or opens it beside Offer Filter in split screen. It used to reuse DoorDash's own notification link, which could open the wrong screen.
- **No double ring.** When Dasher's own offer alert already makes a sound, the card arrives silently instead of ringing too.
- **"+$" offers** (a "+$1" beside the pay, seen on a 2-stop offer): declined when even the pay plus that amount misses your minimums, using your set minimums only. Otherwise left for you, never passed on their own.

Evidence boundaries:
- **Java tests** cover:
  - which notification folds into which offer;
  - the one-time fold of stored history;
  - the "+$" ceiling.
- **Simulated Android 8 and 15 tests** cover:
  - a card cleared after the screen reads its offer, including after it rang;
  - no card for an offer just read;
  - same-store re-posts;
  - the silent card when Dasher's channel sounds;
  - the card tap opening Dasher, full screen and beside.
- **Not verified on a real phone:**
  - DoorDash's real notification timing;
  - where Android puts Dasher after the card tap in split screen.

## 0.4.37 — drag your minimums; a bigger sky; per stop is a minimum

- **Knobs on the constellation.** Each minimum you set is a knob on its spoke. Drag it along the spoke to change it:
  - It moves in steps: $0.50 of pay, $0.05 a mile, $0.01 a minute, $0.25 a stop. Each step gives a light tick, and a small readout shows the value while it moves.
  - Letting go saves it, exactly like typing it in Settings and tapping **Save rules**. Every other rule and the on/paused state stay as they were.
  - Dragging it into the middle turns that rule off.
  - Only a finger moving along the spoke takes a knob. A scroll, or a slide across the spoke, sets nothing.
  - Screen readers get each knob as an adjustable control.
- **Make the learned minimums yours.** While a learned (dashed) minimum asks more than your set one, a round button beside the chart copies the learned minimums into your set ones.
  - It never lowers a minimum. It rounds rates up, so the set rule is never looser than the learned one.
  - Undo is offered for 8 seconds.
  - Set minimums also judge add-ons, which learned ones never do, so adopting can decline add-ons that used to pass.
- **A bigger, looser sky.** The constellation runs past the page edges, a little right of centre. Lines like "Paused" or a Fix row cross its lower part instead of shrinking it.
  - At night, stars and clouds stay out from under words.
  - The ring labels are bolder.
  - With a very large font, the page scrolls a little rather than squeezing the skyline and map.
- **Per stop is a minimum now,** like per mile and per minute: required pay is at least stops × rate.
  - The old extra-stop fee meant something else, so updating removes it.
  - If you had one, Settings says so once beside **Per stop**. If it was your only rule, auto-decline is paused rather than filtering nothing.
- **Safer stop counts.** A "1 stop" reading is treated as a misread: a per-stop minimum sends that offer to review instead of halving the minimum. An add-on whose "New total N stops" disagrees with its own counts also goes to review. A separate known failure still declines.

Evidence boundaries:
- **Java tests** cover:
  - adopting: the rounding, never lowering, and spokes with nothing learned left alone;
  - the "1 stop" misread;
  - stop totals that disagree.
- **Simulated Android 8 and 15 tests** cover:
  - a knob drag saving the stepped value and showing it in Settings;
  - a drag into the middle turning the rule off;
  - a wobble or a scroll saving nothing;
  - screen-reader steps;
  - adopt and Undo;
  - the fee's removal: silent for $0, paused with a one-time notice when it was the only rule;
  - the constellation's size beside Dasher.
- **Not verified on a real phone:**
  - how dragging feels;
  - the haptic tick;
  - TalkBack;
  - the look on your screen.

## 0.4.36 — drag the ticket down to close it

- **The offer ticket is a drawer.** Pull it down to close it: from its handle any time, or from anywhere on it once its content is scrolled to the top.
  - It follows your finger, and the dimmed page behind it fades as you pull.
  - Let go past a quarter of its height, or flick it down, and it closes. Otherwise it springs back.
  - A tap on the dimmed page and Back still close it, and screen readers get a Close action.

Evidence boundaries:
- **Simulated Android 8 and 15 tests** cover:
  - a short, slow pull springing back;
  - a pull past a quarter closing it;
  - the Close action.
- **Not verified on a real phone:** how the pull feels.

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


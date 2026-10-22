package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Short notes bundled with each release, written by the docs package, read with no connection:
 * <ul>
 * <li>what changed in the notice, by the {@link Consent#VERSION} that brought it: a "What changed" box at the top of
 * the notice for someone who accepted an older one (never for a new install, which reads the whole notice anyway);</li>
 * <li>what is new in a version, by its version name: a one-time card on the homepage after an update to it.</li>
 * </ul>
 * Until the docs package writes the final words, the lines are marked placeholders and nothing is shown:
 * {@link #WRITTEN} stays false. The docs package replaces every placeholder and sets WRITTEN to true in the same
 * change; BundledNotesTest then fails while any placeholder remains, while the current notice version or app version
 * has no entry, or while every line is written but WRITTEN is still false. A few short lines each; no paragraph.
 */
final class BundledNotes {
    /** The docs package's one switch: true once every line below is final (and none is a placeholder). */
    static final boolean WRITTEN = true;
    /** How a line still to be written starts. */
    static final String PLACEHOLDER = "[DOCS PACKAGE:";

    private static final Map<Integer, List<String>> NOTICE = new LinkedHashMap<>();
    private static final Map<String, List<String>> RELEASES = new LinkedHashMap<>();

    /** Notice 24's lines, also 0.6.14's card: pay per stop counts the pickup. */
    private static final String[] PICKUP_COUNTED = {
            "Pay per stop now counts every stop Dasher shows, the pickup included: $5 a stop asks $10 of a single order "
                    + "and $15 of three stops.",
            "Your number wasn't changed, so a single order now asks twice what it did; set it to what you want per "
                    + "stop.",
            "Learning's best pay per stop counts the pickup too.",
            "If Dasher shows Finding offers without an offer it announced, the app taps Dasher's ⚙ once to bring it "
                    + "up, as you would."};

    /** Notice 25's lines, also 0.6.17's card: the account warning says plainly what DoorDash's terms ban. */
    private static final String[] TERMS_PLAIN = {
            "The account warning is plainer: DoorDash's terms ban automated tools that read or collect data from "
                    + "its apps, and this app is one.",
            "DoorDash could limit or deactivate your account. Nothing about how the app works has changed."};

    /**
     * Notice 27's lines, also 0.6.19's card: turning Autopilot off and on keeps the best accept's hold, which lasts its
     * full 7 days, and with pay first the update counts the accepts of the last 7 days again.
     */
    private static final String[] FLOOR_HOLDS = {
            "Turning Autopilot off and on no longer lets go of your best accept: it keeps holding the bar for its 7 "
                    + "days.",
            "It holds even after 200 newer offers: the app keeps the figures of offers you accept yourself for 7 days, "
                    + "on this phone only.",
            "With pay first, this update also counts your accepts of the last 7 days again: the bar rises to your best "
                    + "one as soon as no offer is up."};

    /**
     * Notice 26's lines, also 0.6.18's card: pay first by default, the best accept of 7 days holds the bar, and a
     * decline is confirmed only on Dasher's usual question.
     */
    private static final String[] EARNINGS_FIRST = {
            "Autopilot puts pay first by default now. The goals that protect your acceptance rate are a choice in its "
                    + "goal question.",
            "Your goal moved to pay first unless you had picked 50% or pay first; a long press on Autopilot's chip "
                    + "brings 70% back.",
            "The best score you accepted yourself in the last 7 days now holds Autopilot's bar across dashes, until "
                    + "you change your goal or minimums.",
            "A decline is confirmed only on Dasher's usual question; any other question is left to you and pauses "
                    + "auto-decline.",
            "This notice ends with a switch to share anonymous diagnostics; it starts off and Settings can change it "
                    + "later.",
            "If screen reading stops on a fault it restarts once, then a notification says offers aren't filtered; "
                    + "update checks wait for this notice."};

    /**
     * Notice 28's lines, also 0.6.21's card: an accept holds the bar for the next 24 offers, and the minimums no longer
     * grow on their own (learning raises what offers must meet; Autopilot is the buffer). Its third line, a map's half
     * getting 70% of a split, went with split screen in 0.6.33; 0.6.21's card keeps it ({@link #MAP_SEVENTY}).
     */
    private static final String[] HOLD_GROWTH = {
            "An offer you accept yourself now holds Autopilot's bar for your next " + Autopilot.FLOOR_OFFERS
                    + " offers, instead of 7 days.",
            "Your minimums no longer grow on their own. Learning raises the minimums offers must meet when you accept "
                    + "one that beats your best; Autopilot only moves its bar."};
    /** 0.6.21's card only: a map's half of a split screen got 70% (retired with split screen in 0.6.33). */
    private static final String MAP_SEVENTY = "In split screen, a map in either half now gets 70% of the screen, not "
            + "only during a delivery. Move the divider yourself and it stops for that dash.";

    /** Notice 29's lines, also 0.6.27's card: offers are scored by area by default, Strict a pick in the radar's middle. */
    private static final String[] AREA_BY_DEFAULT = {
            "Offers are scored by Area by default now: stronger pay or rates can make up for a weaker one.",
            "If yours was Strict, it moved to Area with this update. Tap the word in the radar's middle to choose "
                    + "Strict, which checks every minimum."};

    /** Notice 30 and 0.6.29: each accepted offer sets independent working minimums in either direction. */
    private static final String[] ACCEPTED_AXES = {
            "With Autopilot on, your newest confirmed personal acceptance sets each readable active minimum up or "
                    + "down. Tap Auto for its working minimums.",
            "With Learn on, the dashed green triangle covers the higher saved or learned value on every active axis. Readable short "
                    + "trips can teach new highs.",
            "Saved minimums and learned bests stay separate. The bar still moves working minimums together; unread "
                    + "and disabled measures stay as they are."};

    /** Notice 31 and 0.6.31: a quarter of each Autopilot Area score is total pay against the latest acceptance's. */
    private static final String[] TOTAL_PAY = {
            "With Autopilot on, a quarter of each Area score is now an offer's total pay against your latest "
                    + "acceptance's.",
            "So a bigger offer a little under that acceptance's rates can pass, and a smaller one at its rates can be "
                    + "declined.",
            "The chart's pay line now shows that acceptance's total pay at the bar."};

    /** Notice 32 and 0.6.32: Autopilot's bar can come down to 10% of its working minimums, not only to 50%. */
    private static final String[] LOW_BAR = {
            "Autopilot's bar can now come down to 10% of its working minimums instead of stopping at 50%, so a slow "
                    + "dash doesn't decline nearly everything.",
            "With pay first it keeps about one offer in five coming. Offers below your saved or learned minimums "
                    + "are left to you, never auto-accepted.",
            "An offer you accept still sets its working minimums and holds the bar there for the 24 offers after "
                    + "it."};

    /**
     * Notice 33 and 0.6.33: split screen is gone, the divider's moves with it; the tab over Dasher comes back here, and
     * a touch held on it pauses or resumes.
     */
    private static final String[] NO_SPLIT = {
            "Split screen is gone: the app no longer splits the screen with Dasher or moves its divider for a map.",
            "To come back here from Dasher, tap the tab on the screen's edge.",
            "To pause or resume from Dasher, touch and hold the tab. Tapping the mascot here still does it too."};

    /**
     * Notice 34 and 0.6.34: one Share anonymous diagnostics switch for every diagnostic, off for everyone until turned
     * on; and the release's other change, the same tab on this screen, opening Dasher.
     */
    private static final String[] ONE_SWITCH = {
            "Share anonymous diagnostics is now one switch for the summary after each dash, masked diagnostics with "
                    + "feedback you send and the offer timing study.",
            "It covers more than each earlier choice did, so it is off for everyone until you turn it on."};
    private static final String HOME_TAB = "To go to Dasher, tap the tab on this screen's edge: the same tab as over "
            + "Dasher, in the same spot, with Dasher's icon. Moving one moves both.";

    /**
     * Notice 35 and 0.6.35: paused, the app reads and scores each offer on Dasher's screen and taps nothing (it read
     * nothing of the screen before); the Accessibility switch is the full stop.
     */
    private static final String[] PAUSED_SCORES = {
            "Paused, the app now still reads each offer on Dasher's screen and shows its score beside the tab, "
                    + "outlined when it would decline, but taps nothing.",
            "Offers read while paused don't teach learning or move Autopilot. To stop all reading, turn the app off in "
                    + "Android's Accessibility settings."};
    /** 0.6.35's other change: Settings' Decline after 10 seconds. */
    private static final String[] DECLINE_WAIT = {
            "New in Settings: Decline after 10 seconds waits that long before declining an offer on Dasher's screen; a "
                    + "touch in that time keeps it yours.",
            "Offers that come while you're in another app are still declined at once."};

    private static final String SKYLINE_BACK =
            "The chart's buildings and trees are back: each building is the offer's pay, its tree the score, with a "
                    + "pay line and the 100% line.";

    static {
        // What changed in the notice since the one before it. Two or three short lines, plain words. Notice 14 was
        // first published with 0.5.0; its reader last accepted notice 13. Notice 15 comes with 0.5.1.
        // Notice 13 and earlier texts called the repository older versions filed reports in "private"; it is public
        // (the owner's decision, 6 October 2026), and the first line corrects that for everyone who read them.
        notice(14,
                "Feedback needs no account and GitHub is gone. Reports older versions sent went to the project's "
                        + "public GitHub repository, not a private one.",
                "Optional Autopilot moves your cutoff up or down and may pass offers below your minimums to you. "
                        + "Dasher's last acceptance rate is kept, used 7 days at most.",
                "Mid-dash your unlocked screen won't time out, and Peek can check an offer that came while locked. "
                        + "Dash diagnostics are opt-in. The Terms name Ohio law.");
        notice(15,
                "Autopilot now raises your minimums after offers have paid above them for a while: at most 10% at a "
                        + "time, with Undo.",
                "Higher minimums can mean more declines. \"Let my minimums grow\" in Autopilot's details turns it "
                        + "off.",
                "Autopilot keeps a note of its last growth: when, how much, and your minimums and bar before and "
                        + "after.");
        // What is new in this version, shown once after the update. Two or three short lines, plain words. Keyed by
        // the versionName that ships (BundledNotesTest requires the current versionName's entry). What the code does,
        // never an outcome no phone has shown yet.
        notice(16,
                "An optional offer timing study records estimates before outcomes and keeps numeric observations "
                        + "for up to 24 hours. It changes no driving controls.",
                "Its checked draft in Settings enables nothing until you tap Save choice. Existing opt-outs "
                        + "and automatic-sharing choices stay unchanged.",
                "Uncheck and save to discard study data and remove it from unsent reports. Preview before sharing.");
        notice(17,
                "Area mode and acceptance learning are separate, initially off choices. Strict remains the default; "
                        + "Autopilot keeps its own bar and controls.",
                "Learning uses new confirmed manual standalone acceptances. Reset clears active bests and adoption "
                        + "Undo; adopted saved minimums stay.",
                "Clear history removes active learning and earlier decision snapshots. Reset alone keeps those "
                        + "snapshots.",
                "Automatic acceptance stays strict. The optional timing study still needs its own saved choice; "
                        + "this notice enables neither learning nor sharing.");
        notice(18,
                "Learning is on by default: an accept of yours that beats your best pay, pay per mile or pay per hour "
                        + "raises the learned minimums.",
                "Offers must meet the learned minimums too, which can mean more declines. Your own minimums stay as "
                        + "you set them.",
                "The homepage says what rose, with Adopt to make them yours; the Learn button on the radar turns "
                        + "learning off.");
        // 0.6.2 (the owner, 12 October 2026): Autopilot on by default. A reader of notice 18 sees no What's-new card
        // with this notice, so its last line names the offer clock too.
        notice(19,
                "Autopilot is on by default: this update turns it on once, with the goal you chose before or else "
                        + "70% acceptance rate. Its chip on the homepage turns it off.",
                "It moves your cutoff between 50% and 150% of your minimums: raised, it declines offers that meet "
                        + "them; lowered, offers below them are yours to decide.",
                "It also raises your minimums after offers have paid above them for a while, at most 10% at a time, "
                        + "with Undo.",
                "New over the skyline: your offers by time of day, as a percent of your minimums now. Tap it for "
                        + "what it means.");
        // 0.6.3 (the owner, 12 October 2026: "i want to keep track of if anyone besides me downloads the app"): the
        // anonymous daily count, on by default. A reader of notice 19 sees no What's-new card with this notice, so its
        // last line names 0.6.3's homepage and split changes too.
        notice(20,
                "Once a day the app sends one anonymous count, so the developer can see how many people use it: its "
                        + "version and whether this install was counted before.",
                "Nothing about your offers, phone or account goes with it; the service keeps daily totals for 400 "
                        + "days. Settings → Send an anonymous daily count turns it off.",
                "Also new: Strict or Area is a chip beside Autopilot's, and the latest offer is a short label.");
        // 0.6.10 (the owner, 13 October 2026, of the #DeclineNow study: "yeah I want all its recommendations"): the
        // store skip list and the driver's cost per mile, both changing what gets declined, so one notice for both.
        notice(21,
                "New: a skip list. \"Skip <store> from now on\" on an offer's details declines its offers, whatever "
                        + "they pay. Settings → Skipped stores removes one.",
                "An offer from two stores is declined if either is on the list; an offer whose store can't be read is "
                        + "judged by your rules as before.",
                "Also new, off unless you set it: your own cost per mile for gas and wear, in Autopilot's details. "
                        + "Autopilot then weighs pay after that cost.");
        notice(22,
                "When you accept an offer yourself above Autopilot's bar, the bar rises to that score for the rest of "
                        + "the dash, even past what your acceptance goal allows.",
                "It starts over at your next dash or when you change your goal or minimums.");
        // 0.6.12 (the owner, 13 October 2026: "swap overall payout for pay per stop"): the pay minimum is per stop. Its
        // rule C counting (each stop but the pickup) was superseded by 0.6.14, so its lines no longer describe it.
        notice(23,
                "Minimum pay is now pay per stop, so an offer with more stops must pay more; your number didn't "
                        + "change.",
                "Learning keeps your best pay per stop.");
        // 0.6.14 (the owner, 14 October 2026: "i don't want the pay per stop to not count the pickup"): every stop
        // counts, the pickup included; the saved number is unchanged ("it doesn't need to do any halving").
        notice(24, PICKUP_COUNTED);
        // 0.6.17 (the owner chose "Say it plainly", 14 October 2026): the account point says DoorDash's terms ban
        // automated tools that read or collect data from its apps, and that this is one, where it said "may break".
        notice(25, TERMS_PLAIN);
        // 0.6.18 (the owner chose "Earnings first", 14 October 2026): pay first is the default goal, a 70% goal moves
        // to it once, and the best hand-accepted score of the last 7 days holds Autopilot's bar across dashes; and
        // (option A, 19:36 UTC) only Dasher's usual decline question is confirmed, any other pauses auto-decline.
        notice(26, EARNINGS_FIRST);
        // 0.6.19 (the owner, 14 October 2026: "it dropped the auto score below my recent high again on 6.18"): turning
        // Autopilot off and on no longer restarts the accepted floor, and the accepts it counts are kept 7 days.
        notice(27, FLOOR_HOLDS);
        // 0.6.21 (the owner, 15 October 2026: "We may have to do it by like two dozen orders", "mins should grow as we
        // accept offers with higher mins when adaptive / learned mins is on. autopilot is the buffer", "whenever one
        // half has nav open make it 70% height"). Notice 15's growth (0.5.1) is retired by it.
        notice(28, HOLD_GROWTH);
        // 0.6.27 (the owner, 17 October 2026: "it should be on area score mode by default").
        notice(29, AREA_BY_DEFAULT);
        notice(30, ACCEPTED_AXES);
        // 0.6.31 (the owner, 22 October 2026: "we must factor in overall pay", and "we had some issue before with
        // overall pay compounding weird with pay per stop"): total against total, never pay per stop times stops.
        notice(31, TOTAL_PAY);
        // 0.6.32 (the owner, 22 October 2026: "i think auto needs to be able to go below 50%, like maybe even 10%?").
        notice(32, LOW_BAR);
        // 0.6.33 (the owner, 22 October 2026: "i say let's get rid of split screen mode. we haven't been able to get the
        // auto resizing to work and it's so buggy. instead can we have an easy way to swap back to the offerfilter
        // app?"). Notices 20, 22 and 28 no longer say what split screen did.
        notice(33, NO_SPLIT);
        // 0.6.34 (the owner, 22 October 2026: "we should combine all diagnostics opt in into one").
        notice(34, ONE_SWITCH);
        // 0.6.35 (the owner, 22 October 2026: "we still show what we would recommend and it's score even if the user
        // toggles off actual decline"), reversing his "Read nothing" of 6 October.
        notice(35, PAUSED_SCORES);
        release("0.5.0",
                "New Autopilot (the Auto button) adjusts how much of your minimums an offer must pay, toward a goal "
                        + "you pick: acceptance rate 70%, 50%, or pay first.",
                "Peek now taps Dasher's own notification when an offer won't show and checks an offer that came "
                        + "while locked; mid-dash your unlocked screen won't time out.",
                "Lighter on Dasher: recognized maps are skipped and screens with no offer are read less often. "
                        + "Feedback needs no account: Settings → Send anonymous feedback.");
        release("0.5.1",
                "Your minimums grow: when Autopilot's bar stays at 103% or more for 30 offers over 2 days, that gain "
                        + "moves into your minimums, at most 10% at a time.",
                "A note says what grew and why, with Undo. All the minimums you set grow together; max stops "
                        + "never changes.",
                "Autopilot can still lower its bar to protect your acceptance rate. Turn growth off in Autopilot's "
                        + "details.");
        release("0.5.2",
                "An offer that rings while Dasher is on screen but never shows now gets Dasher's own notification "
                        + "tapped once after 5 s, in split screen too.",
                "The app's own Decline tap, reported late by a busy phone, no longer counts as your touch. A split "
                        + "left unmade no longer holds back Peek.",
                "Offers are read sooner after their notification, Dasher's question is tapped as soon as it shows "
                        + "in split screen, and directions keep talking.");
        release("0.5.3",
                "Directions work during a delivery: DoorDash's re-post of the order you are delivering no longer "
                        + "pulls Dasher back over your map.",
                "The split button now splits the screen in one tap, Dasher beside this app; recent apps open only "
                        + "if your phone doesn't split for it.");
        release("0.5.4",
                "Dasher opens as its own icon opens it: its navigation comes back as it was, never hidden under "
                        + "\"Searching for offers\".",
                "That covers the split button, Peek and taps on this app's cards.");
        release("0.5.5",
                "One homepage at every window size: the radar and the offer map stand together, and everything "
                        + "resizes gradually as you drag the divider.",
                "The latest offer's verdict is always at the top, with why and how long ago; tap it for the "
                        + "ticket.");
        release("0.5.6",
                "Navigate can find installed Google Maps and Waze on recent Android versions before using the "
                        + "browser fallback.",
                "Anonymous feedback now goes to the replacement feedback service. No account is needed.");
        release("0.5.7",
                "Diagnostic logs mask more combined street and route labels, and unquoted delivery instructions.",
                "Pay, miles, minutes and stop counts stay available for troubleshooting. Review diagnostics before sharing.");
        release("0.5.8",
                "Diagnostics drop malformed account-screen prefixes and mask more delivery-instruction continuations.",
                "Unclear instruction boundaries may hide extra text. Review diagnostics before sharing.");
        release("0.5.10",
                "Update checks now recheck cancellation when a download finishes.",
                "A download that finishes after its time limit is rejected.");
        release("0.5.11",
                "Short windows show pay, miles and trip time first. The outer comparison triangle stays steady "
                        + "while your minimums and Autopilot bar stay the same.",
                "Peek needs a confirmed full-screen view before returning to a map. Report Offer keeps Send in "
                        + "reach as its disclosure scrolls.",
                "The optional offer timing study needs its own saved choice. Turning it off and saving removes "
                        + "study data from unsent reports; preview before sharing.");
        release("0.6.0",
                "Settings → Mode: Strict or Area; separate Learned control. Area permits compensation; max stops "
                        + "stays hard and add-ons stay strict.",
                "Learning uses confirmed manual acceptances. Inspect bests, adopt, undo adoption or reset. "
                        + "Teal shows learned bests; purple shows Autopilot's adjustment.",
                "Learning starts empty. Autopilot, its growth Undo and 0.5.11 repairs remain. Permitted by the "
                        + "filter means left for you to decide, not accepted.");
        release("0.6.1",
                "A new high you accept raises the learned minimums, with Adopt on the homepage; your own stay as you "
                        + "set them. Accepts on a new dash's first pickup count again.",
                "Moving a knob leaves the offer on screen to you. An offer you take back from a decline before its "
                        + "confirmation teaches when you accept it.",
                "Learning is on by default: the radar's Learn button, long press for what it learned. Autopilot is "
                        + "the chip at the top. Split mid-delivery opens recent apps.");
        release("0.6.2",
                "Autopilot is on by default: this update turns it on once, with the goal you chose before or else "
                        + "70% acceptance rate. Its chip at the top turns it off.",
                "Offers by time of day, over the skyline: each bar is the middle score of your offers in those three "
                        + "hours, as a percent of your minimums now.",
                "Tap it for what it means. It is history, not a promise.");
        release("0.6.3",
                "The latest offer is a label now: its badge, pay, age and trip. A small split window keeps the radar, "
                        + "skyline, offer map and counts.",
                "Split opens Dasher full screen first, then puts this app beside it. Strict or Area is a chip beside "
                        + "Autopilot's; Settings has no Mode row.",
                "A take-back from a peek's decline teaches, the green or amber tab shows beside this app too, and a "
                        + "daily anonymous count has a Settings switch.");
        release("0.6.4",
                "Split works again: this app opens a small pane beside the screen, which opens Dasher in its half. "
                        + "Where a phone won't split, recent apps open at once.",
                "If Android stops this app reading Dasher mid-dash, one notification says so; notification access "
                        + "reconnects itself when it stays down.");
        release("0.6.5",
                "In Earn by Time, when Dasher says another decline would end your dash, the offer is left to you and "
                        + "auto-decline pauses until you resume it.",
                "It pauses too if Dasher ends the dash right after a decline.");
        release("0.6.6",
                "A calmer homepage that says each thing once: one bar per offer, in % of your minimums, coloured by "
                        + "what happened, with the dash's counts under it.",
                "Each minimum stands at its knob, the mode sits in the radar's middle, Autopilot shows a small meter, "
                        + "and the map names the best area.");
        release("0.6.7",
                "Split is back to how 0.6.2 did it: one tap opens Dasher beside this app straight away, with no small "
                        + "pane in between.",
                "If the screen doesn't split, recent apps open so you can pick Dasher's card.");
        release("0.6.8",
                "The homepage now looks like the mockup: the radar and the chart side by side over a wide map, the "
                        + "buttons beside the latest offer.",
                "The latest offer's pay is bigger, your acceptance rate shows as a bar even with Autopilot off, and "
                        + "each minimum sits by its knob.");
        release("0.6.9",
                "When an offer comes from the same store as one declined in the last half hour, both tickets say so, "
                        + "with how much later and for how much.",
                "Each minimum's value now stays readable over the offer's outline, and the chart says which end is latest.");
        release("0.6.10",
                "Skip a store: on an offer's details, \"Skip <store> from now on\" declines its offers from then on. "
                        + "Settings → Skipped stores lists them.",
                "Your own cost per mile, off unless you set it in Autopilot's details, lets Autopilot weigh pay after "
                        + "gas and wear.");
        release("0.6.11",
                "Autopilot follows your accepts: accept an offer above its bar and the bar rises to that score for the "
                        + "rest of the dash.",
                "In split screen, the navigating app gets more room during a delivery.",
                "The acceptance meter stays on one line at every width.");
        release("0.6.12",
                "Minimum pay is now pay per stop, so an offer with more stops must pay more; your number didn't "
                        + "change.",
                "Learning keeps your best pay per stop.",
                "Fewer empty gaps on the homepage, and the time-of-day chart comes back after each new offer.",
                "No second card of ours beside Dasher's own offer notification: Dasher rings for offers itself.");
        release("0.6.13",
                "A customer's name shown alone on a pickup screen is now masked in reports.");
        release("0.6.14", PICKUP_COUNTED[0], PICKUP_COUNTED[1], PICKUP_COUNTED[2], PICKUP_COUNTED[3],
                SKYLINE_BACK);
        release("0.6.15", "Downloads from the website are now counted at the download link itself, so a long press or a "
                + "shared link counts too. Privacy says how.");
        release("0.6.16", "The homepage's pictures are the website's 3D art now: the mascot, the sun, moon and clouds, "
                + "the chart's buildings and trees, and the road's houses and car.");
        release("0.6.17", TERMS_PLAIN[0], TERMS_PLAIN[1]);
        release("0.6.18", EARNINGS_FIRST);
        release("0.6.19", FLOOR_HOLDS);
        release("0.6.20",
                "Off a dash, Autopilot's bar now moves at once instead of waiting for Dasher, so after an update it no "
                        + "longer sits below your best accept.",
                "The notice is a short summary now, the details left to Terms and Privacy, and its diagnostics switch "
                        + "shows your choice.");
        release("0.6.21", HOLD_GROWTH[0], HOLD_GROWTH[1], MAP_SEVENTY,
                "Beside the tab over Dasher, each offer's score shows at a glance: ✕ 52% while it's declined, for "
                        + DasherOverlay.SCORE_STAYS_MS / 1000 + " seconds after.",
                "The offer map's grid reaches its edges and fades out, with no outline.");
        release("0.6.22", "Drag the offer map and it follows your finger, then springs back into place when you let go.");
        // 0.6.23 (the owner, 15 October 2026, with every update held beside Dasher's Dash button: "in app won't update
        // even though no ongoing dash").
        release("0.6.23", "Updates install when you're not dashing, even with Dasher's home screen in the other half.");
        // 0.6.24 (the owner, 15 October 2026: "since I have learn on shouldn't a 109% offer raise my mins, not just
        // autopilot?"; his 4:56 AM accept, right after two of the app's declines, was never counted).
        release("0.6.24", "An offer you accept yourself right after the app declines others now counts as accepted, and "
                + "raises your learned minimums when it beats your best.");
        // 0.6.25 (the owner, 16 October 2026, with Samsung's "App was denied access" over Accessibility: "we need to
        // streamline this process and make it user friendly").
        release("0.6.25", "Setup now opens each of Android's screens for you in turn and says what to tap there, Allow "
                + "restricted settings included.");
        // 0.6.26 (the owner, 16 October 2026: "shouldn't have this limbo screen. if the app is unable to work for
        // permissions or whathaveyou then we shepherd the user directly to resolution. all ui on screen needs to be
        // justified by what is currently functional").
        release("0.6.26", "Setup now goes one step at a time on a page of its own, and the home screen shows each part "
                + "only once it works.");
        // 0.6.27 (the owner, 17 October 2026: "it should be on area score mode by default").
        release("0.6.27", AREA_BY_DEFAULT);
        // 0.6.28 (the owner's two reports of 16 October 2026: "was +1 didnt read it", of Dasher's add-on card, and a
        // "+$3" offer it "didnt read" through several showings, a lifetime "N orders completed" drawn under it).
        release("0.6.28", "Offers with a +$ amount are read again when Dasher shows \"orders completed\" under "
                + "them: if even pay plus the +$ misses your minimums, they're declined.",
                "Dasher's add-on card, such as +$9.40 for +1 stop and +7 min, is read as an add-on to your route; one "
                + "paying too little for its minutes is declined.");
        release("0.6.29", ACCEPTED_AXES);
        // 0.6.30 (the owner's reports of 20 October 2026, "seems like the app isn't reading them", and his choice of
        // 21 October that Dasher's total already includes its +$ amount).
        release("0.6.30", "On busy offers with a +$ amount, Dasher's total is now read as the whole pay, the +$ "
                + "included, so they're judged like any other offer.",
                "Reports and your history now keep a full-screen offer's own lines; nothing of Dasher's screen below "
                + "the offer is kept.");
        // 0.6.31 (the owner, 22 October 2026: "we must factor in overall pay").
        release("0.6.31", TOTAL_PAY);
        // 0.6.32 (the owner, 22 October 2026: "i think auto needs to be able to go below 50%, like maybe even 10%?").
        release("0.6.32", LOW_BAR);
        // 0.6.33 (the owner, 22 October 2026: "i say let's get rid of split screen mode").
        release("0.6.33", NO_SPLIT);
        // 0.6.34 (the owner, 22 October 2026: "we should combine all diagnostics opt in into one", and of the tab,
        // "door dash swap button should be same as offer filter swap button on its screen").
        release("0.6.34", ONE_SWITCH[0], ONE_SWITCH[1], HOME_TAB);
        // 0.6.35 (the owner, 22 October 2026: the score while paused, and "we could also make auto decline timing
        // configurable like immediate vs 10s").
        release("0.6.35", PAUSED_SCORES[0], PAUSED_SCORES[1], DECLINE_WAIT[0], DECLINE_WAIT[1]);
        // 0.4.73 never shipped and nothing builds under its name any more: it has no entry (and so no card).
    }

    private BundledNotes() {}

    private static void notice(int version, String... lines) {
        NOTICE.put(version, Collections.unmodifiableList(Arrays.asList(lines)));
    }

    private static void release(String versionName, String... lines) {
        RELEASES.put(versionName, Collections.unmodifiableList(Arrays.asList(lines)));
    }

    /** What changed since the notice {@code accepted} was accepted; nothing for a new install or while unwritten. */
    static List<String> noticeChanges(int accepted) {
        return noticeChanges(accepted, WRITTEN);
    }

    /**
     * As {@link #noticeChanges(int)}, with whether the words are final given: the lines of every notice after
     * {@code accepted} up to the current one, oldest first. {@code accepted} 0 is a new install: nothing.
     */
    static List<String> noticeChanges(int accepted, boolean written) {
        List<String> lines = new ArrayList<>();
        if (!written || accepted <= 0 || accepted >= Consent.VERSION) return lines;
        for (Map.Entry<Integer, List<String>> notice : NOTICE.entrySet()) {
            if (notice.getKey() > accepted && notice.getKey() <= Consent.VERSION) lines.addAll(notice.getValue());
        }
        return lines;
    }

    /** What is new in {@code versionName}; nothing while unwritten or with no entry. */
    static List<String> whatsNew(String versionName) {
        return whatsNew(versionName, WRITTEN);
    }

    static List<String> whatsNew(String versionName, boolean written) {
        List<String> lines = written ? RELEASES.get(versionName) : null;
        return lines == null ? Collections.<String>emptyList() : lines;
    }

    /** Every line, notices then releases, for the docs package's checks. */
    static List<String> allLines() {
        List<String> all = new ArrayList<>();
        for (List<String> lines : NOTICE.values()) all.addAll(lines);
        for (List<String> lines : RELEASES.values()) all.addAll(lines);
        return all;
    }

    static boolean hasNotice(int version) {
        return NOTICE.containsKey(version);
    }

    static boolean hasRelease(String versionName) {
        return RELEASES.containsKey(versionName);
    }
}

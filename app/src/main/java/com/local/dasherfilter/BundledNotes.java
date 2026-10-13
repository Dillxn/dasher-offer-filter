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
                "Also new: Strict or Area is a chip beside Autopilot's, the latest offer is a short label, and Split "
                        + "opens Dasher first, then puts this app beside it.");
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
                "It starts over at your next dash or when you change your goal or minimums.",
                "In split screen during a delivery, the app moves the divider to give the map more room, and puts it "
                        + "back afterward. Move it yourself and it stops for that dash.");
        // 0.6.12 (the owner, 13 October 2026: "swap overall payout for pay per stop"): the pay minimum is per stop.
        notice(23,
                "Minimum pay is now pay per stop: each stop after the first pickup counts, so a 3-stop offer asks "
                        + "twice your number.",
                "Single orders are judged exactly as before; your number didn't change.",
                "Learning keeps your best pay per stop.");
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
                "Minimum pay is now pay per stop: each stop after the first pickup counts, so a 3-stop offer asks "
                        + "twice your number.",
                "Single orders are judged exactly as before; your number didn't change.",
                "Learning keeps your best pay per stop.",
                "Fewer empty gaps on the homepage, and the time-of-day chart comes back after each new offer.",
                "No second card of ours beside Dasher's own offer notification: Dasher rings for offers itself.");
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

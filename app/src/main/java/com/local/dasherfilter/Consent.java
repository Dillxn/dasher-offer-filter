package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The first-run notice and the user's consent to it. Everyone sees it once, existing installs included, and again
 * only when {@link #VERSION} is raised. Until the current version is accepted, the screen reader reads, decides and
 * taps nothing, the notification path posts, hides and sends nothing (but for the one reminder that the app is
 * paused, {@link ConsentReminder}), no feedback, diagnostics or daily count ({@link DailyCount}) leave the phone, and
 * the homepage shows only the notice.
 * "Not now" closes the app and stores nothing. Nothing here ever turns consent off again.
 */
final class Consent {
    /**
     * Raised when the notice, the terms or the privacy text change in substance, so everyone sees them once more. 14
     * (published with 0.5.0; 13 was the one before) covers accountless feedback, Autopilot and the acceptance-rate
     * reading it keeps, the screen held on during a dash, Peek's unlock catch-up and the dated beta terms and privacy
     * policy. 15 (0.5.1) covers Autopilot raising the minimums after offers have paid above them for a while (at most
     * 10% at a time, with Undo, and the switch that stops it) and the note of the last growth it keeps.
     */
    // 16: separate prospective study scope; accepting this notice never enables that optional choice.
    // 17: separate area mode and local acceptance learning; study confirmation remains independent.
    // 18 (0.6.1): learning on by default, so a new high you accept raises the learned minimums offers must meet.
    // 19 (0.6.2): Autopilot on by default, for new installs and once for installs updating, with goal 70%.
    // 20 (0.6.3): the anonymous daily count (DailyCount), on by default with its switch in Settings, and in Privacy
    // the install page's Download count; both kept by the feedback service only as daily totals.
    // 21 (0.6.10): the store skip list, a reason the app declines (any pay), and the driver's own cost per mile, one
    // of Autopilot's inputs (off by default); both kept on the phone like the rules.
    // 22 (0.6.11): Autopilot's bar rises to the score of an offer you accept yourself, for the rest of the dash; and in
    // split screen during a delivery the divider moves, by its own controls, to give the navigating app more room.
    // 23 (0.6.12): the pay minimum is per stop (rule C, max(1, stops − 1)), so stacked offers need more;
    // learning's pay best is per stop.
    // 24 (0.6.14): pay per stop counts every stop Dasher shows, the pickup included; the saved number is unchanged, so
    // a single order asks twice what it did.
    // 25 (0.6.17): the account point says plainly that DoorDash's terms ban automated tools that read or collect
    // data from its apps, and that this is one (the owner chose "Say it plainly", 14 October 2026).
    // 26 (0.6.18): Autopilot puts pay first by default (a 70% goal moves to pay first once; the goals that protect the
    // acceptance rate are opt-in), and the best score you accepted yourself in the last 7 days holds its bar across
    // dashes (the owner chose "Earnings first", 14 October 2026).
    static final int VERSION = 26;
    static final String PREFS = "consent";
    static final String ACCEPTED_VERSION = "accepted_version";
    static final String ACCEPTED_AT = "accepted_at";

    static final String TITLE = "Before you start";

    /** The notice: a few short points, each a bold lead and a line or two after it. */
    static final String[][] POINTS = {
        {"Acceptance rate.", "Automatic declines may dramatically lower your DoorDash acceptance rate. "
                + "Only continue if you understand and accept that risk."},
        {"Not a DoorDash app.", AppName.NAME + " is not made by, endorsed by or affiliated with DoorDash."},
        {"What it does.", "It reads Dasher's screen and notifications on this phone. It taps Decline and its confirmation "
                + "on offers that fail your chosen rules. It confirms only Dasher's usual question (\"Are you sure "
                + "you want to decline this offer?\"); any other question it leaves to you and pauses auto-decline. "
                + "It also declines offers from stores you put on your skip list "
                + "(from an offer's details), whatever they pay. Your pay minimum is per stop, the pickup included: an offer "
                + "must pay it for each stop Dasher shows, so $5 a stop asks $10 of a single order and $15 of three "
                + "stops. Your number wasn't changed, so a single order now asks twice what it did; set it to what you "
                + "want per stop. Strict mode checks every active minimum; optional area mode "
                + "allows stronger pay or rates to compensate for weaker ones. Max stops stays a hard limit; "
                + "unread required facts stay for review and add-ons stay strict. Permitted by the filter does not "
                + "mean accepted. Autopilot is on by default, with pay first as its goal, and moves that cutoff up or "
                + "down by itself (50% to 150% of your minimums), using your recent offers, how often they come and the acceptance rate Dasher shows when "
                + "you decline, and your own cost per mile if you set one in its details (off by default): raised, "
                + "it declines offers that meet your minimums; if you choose a goal that protects your acceptance "
                + "rate instead, it can let offers below your minimums through for you to decide. When you accept an "
                + "offer yourself that scores above that cutoff, it raises the cutoff to that offer's score and keeps "
                + "it at least at the best score you accepted in the last 7 days, across dashes, even above what an "
                + "acceptance goal allows, until you change your goal or minimums. Autopilot also raises your minimums after "
                + "offers have paid above them for a while, at most 10% at a time, with Undo, which can mean more "
                + "declines. Its chip on the homepage turns Autopilot off. Auto-accept is off by default; if "
                + "you separately enable it in Settings, it can accept standalone offers that meet your minimums and commit "
                + "you to a delivery. If Dasher shows an error and gets stuck during a decline, it may go Back and retry, at "
                + "most twice. Your touch stops it. If Dasher shows Finding offers without an offer it announced, it "
                + "may tap Dasher's ⚙ button once to bring the offer up, never within 2 seconds of your touch. In split screen during a delivery it moves the divider, using the "
                + "divider's own controls, to give the navigating app more room, and puts it back afterward; move it "
                + "yourself and it stops for that dash. It can briefly turn offer sound down. During a dash it keeps your "
                + "unlocked screen from timing out; it never wakes or unlocks it."},
        {"Peek is on by default.", "While your phone is unlocked and quiet, it can briefly open Dasher to read a fresh "
                + "background offer (also just after you unlock, for one that came while it was locked), then return to "
                + "your previous app. Turn Peek off in Settings."},
        {"Learning is on by default.", "When you accept an offer yourself and it beats your best pay per stop, pay per "
                + "mile (trips of 2 miles or more) or pay per hour (10 minutes or more), the learned minimums rise to match "
                + "it at once. Offers must meet them as well as your own minimums, which can mean more declines. Your "
                + "own minimums stay as you set them; the homepage says what rose, with Adopt to make them yours. "
                + "The bests stay on this phone. Automatic accepts, declines, add-ons and unclear acceptances "
                + "do not teach it; any known implausible figure rejects the lesson. The Learn button on the homepage "
                + "turns it off; off keeps the bests without learning. Reset clears the bests and Clear history clears them "
                + "too; your minimums stay. Area mode starts off. Autopilot growth is separate."},
        {"Your Dasher account.", "DoorDash's terms ban automated tools that read or collect data from its apps, "
                + "and this is one. DoorDash could limit or deactivate your account."},
        {"At your own risk.", "It can misread, accept or decline an offer you did not want it to. It comes with no warranty."},
        {"Not while driving.", "Don't handle your phone while driving. Pull over to look at offers."},
        {"Your data.", "Diagnostic logs use a rolling 24-hour window, pruned during app use. The latest 200 offer decisions "
                + "have no age-based expiry. The wait estimate and Autopilot use up to 200 numeric offer and observed-wait "
                + "records in a rolling 24-hour window, which are not shared, and the app keeps the latest acceptance rate "
                + "Dasher showed, with the offer it was shown for (used up to 7 days, then deleted when the app next looks), "
                + "even with Autopilot off; the rate leaves only in reports you share or send yourself, never in the "
                + "summary after a dash. "
                + "Auto theme uses an existing allowed approximate location, or a local-clock fallback. "
                + "Recognized payment, account and earnings screens are discarded. Feedback and offer reports are accountless and leave "
                + "only when you tap Send; masked diagnostics go only when you attach them, or after each dash if you turn that on in "
                + "Settings or with the switch at the end of this notice (off by default). What you type is sent as written, not masked. No feedback or diagnostics leave before "
                + "you accept this notice. Unsent submissions wait on this phone up to 7 days. The feedback service keeps them 90 days, "
                + "and the developer may review them with AI tools (Anthropic's Claude or OpenAI's ChatGPT/Codex); clearing this phone "
                + "does not erase sent copies. Network providers still receive normal connection metadata, and text masking can miss "
                + "details. Privacy explains updates, feedback, the daily count, place lookups and map links."},
        {"A daily count is on by default.", "Once a day it sends the feedback service one anonymous count, so the "
                + "developer can see how many people use the app: only its version, whether this install was counted "
                + "before and a random code made new each day, so a retry counts once. Nothing about your offers, "
                + "phone or account goes with it. The service keeps daily totals for 400 days; it keeps the code up "
                + "to 2 days, and a one-way value made from your network's address only for that day, so each counts "
                + "once. Turn it off with Send an anonymous daily count in Settings."},
        {"Optional offer timing study.", "Settings offers a checked draft choice to include new numeric offer-timing "
                + "observations in diagnostics you choose to share. Nothing starts until you separately confirm it with Save choice. "
                + "It compares estimates recorded before outcomes with observed waits, offer quality and actions; "
                + "gaps stay unknown. No customer text, location, account/device ID, acceptance rate, Autopilot plan "
                + "or minimum amounts. Unchecking and saving discards local study data and study sections in "
                + "unsent reports. Automatic after-dash sharing stays your separate choice."},
    };
    static final String ACCEPT = "I understand and accept";
    static final String AGREEMENT = "By tapping " + ACCEPT + ", you acknowledge this acceptance-rate risk, "
            + "choose to use " + AppName.NAME + " at your own risk and accept the Terms.";
    static final String NOT_NOW = "Not now";
    /**
     * The notice's last control (0.6.18; the owner chose "The install page and the notice ask pilot Dashers to turn
     * diagnostics on, and it stays opt-in", 14 October 2026): one switch, unchecked every time the notice shows.
     * Accepting the notice with it on turns Settings' "Share anonymous diagnostics after each dash" on
     * ({@link Feedback#setAfterDash}); with it off nothing changes (an already-on switch stays on). The same summaries,
     * masking and limits as that switch.
     */
    static final String DIAGNOSTICS_ASK = "Share anonymous diagnostics after each dash (helps fix it for your phone)";

    /** Whether the current notice was accepted. Cheap enough for every read: Android keeps the prefs in memory. */
    static boolean accepted(Context context) {
        return prefs(context).getInt(ACCEPTED_VERSION, 0) >= VERSION;
    }

    /**
     * The user explicitly accepted the notice: kept at once, before anything that waited for it runs, and the reminder
     * that the app is paused ({@link ConsentReminder}) goes.
     */
    static void accept(Context context) {
        FilterStore.invalidateLearningEvidence(context);
        prefs(context).edit().putInt(ACCEPTED_VERSION, VERSION).putLong(ACCEPTED_AT, System.currentTimeMillis())
                .commit();
        ConsentReminder.cancel(context);
        DiagnosticLog.log(context, "consent", "notice " + VERSION + " accepted; screen reading and background offers "
                + "may act");
        // Feedback the user sent before an updated notice waited for it.
        FeedbackOutbox.consented(context);
        // So did the anonymous daily count (on unless turned off in Settings); it goes off this thread.
        DailyCount.trigger(context);
        // How the app last stopped is looked at from now on: before the notice, Android's record of it is not read.
        StopReports.install(context);
        StopReports.checkSoon(context);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private Consent() {}
}

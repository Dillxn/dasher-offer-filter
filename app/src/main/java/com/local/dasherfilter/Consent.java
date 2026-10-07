package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The first-run notice and the user's consent to it. Everyone sees it once, existing installs included, and again
 * only when {@link #VERSION} is raised. Until the current version is accepted, the screen reader reads, decides and
 * taps nothing, the notification path posts, hides and sends nothing (but for the one reminder that the app is
 * paused, {@link ConsentReminder}), no feedback or diagnostics leave the phone, and the homepage shows only the notice.
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
    static final int VERSION = 15;
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
                + "on offers below your minimums. Optional Autopilot moves that cutoff up or down by itself (50% to 150% of "
                + "your minimums), using your recent offers, how often they come and the acceptance rate Dasher shows when "
                + "you decline: raised, it declines offers that meet your minimums; to protect your acceptance goal it can "
                + "let offers below your minimums through for you to decide. Autopilot also raises your minimums after "
                + "offers have paid above them for a while, at most 10% at a time, with Undo, which can mean more "
                + "declines. Auto-accept is off by default; if "
                + "you separately enable it in Settings, it can accept standalone offers that meet your minimums and commit "
                + "you to a delivery. If Dasher shows an error and gets stuck during a decline, it may go Back and retry, at "
                + "most twice. Your touch stops it. It can briefly turn offer sound down. During a dash it keeps your "
                + "unlocked screen from timing out; it never wakes or unlocks it."},
        {"Peek is on by default.", "While your phone is unlocked and quiet, it can briefly open Dasher to read a fresh "
                + "background offer (also just after you unlock, for one that came while it was locked), then return to "
                + "your previous app. Turn Peek off in Settings."},
        {"Your Dasher account.", "Using it may break DoorDash's terms. DoorDash could limit or deactivate your "
                + "account."},
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
                + "Settings (off by default). What you type is sent as written, not masked. No feedback or diagnostics leave before "
                + "you accept this notice. Unsent submissions wait on this phone up to 7 days. The feedback service keeps them 90 days, "
                + "and the developer may review them with AI tools (Anthropic's Claude or OpenAI's ChatGPT/Codex); clearing this phone "
                + "does not erase sent copies. Network providers still receive normal connection metadata, and text masking can miss "
                + "details. Privacy explains updates, feedback, place lookups and map links."},
    };
    static final String ACCEPT = "I understand and accept";
    static final String AGREEMENT = "By tapping " + ACCEPT + ", you acknowledge this acceptance-rate risk, "
            + "choose to use " + AppName.NAME + " at your own risk and accept the Terms.";
    static final String NOT_NOW = "Not now";

    /** Whether the current notice was accepted. Cheap enough for every read: Android keeps the prefs in memory. */
    static boolean accepted(Context context) {
        return prefs(context).getInt(ACCEPTED_VERSION, 0) >= VERSION;
    }

    /**
     * The user explicitly accepted the notice: kept at once, before anything that waited for it runs, and the reminder
     * that the app is paused ({@link ConsentReminder}) goes.
     */
    static void accept(Context context) {
        prefs(context).edit().putInt(ACCEPTED_VERSION, VERSION).putLong(ACCEPTED_AT, System.currentTimeMillis())
                .commit();
        ConsentReminder.cancel(context);
        DiagnosticLog.log(context, "consent", "notice " + VERSION + " accepted; screen reading and background offers "
                + "may act");
        // Feedback the user sent before an updated notice waited for it.
        FeedbackOutbox.consented(context);
        // How the app last stopped is looked at from now on: before the notice, Android's record of it is not read.
        StopReports.install(context);
        StopReports.checkSoon(context);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private Consent() {}
}

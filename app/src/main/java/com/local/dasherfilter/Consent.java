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
    /** Raised when the notice, the terms or the privacy text change in substance, so everyone sees them once more. */
    static final int VERSION = 14;
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
                + "on offers below your minimums. Auto-accept is off by default; if you separately enable it in Settings, "
                + "it can accept matching offers and commit you to a delivery. If Dasher shows an error and gets stuck during a decline, "
                + "it may go Back and retry, at most twice. Your touch stops it. It can briefly turn offer sound down."},
        {"Peek is on by default.", "While your phone is unlocked and quiet, it can briefly open Dasher to read a fresh "
                + "background offer, then return to your previous app. Turn Peek off in Settings."},
        {"Your Dasher account.", "Using it may break DoorDash's terms. DoorDash could limit or deactivate your "
                + "account."},
        {"At your own risk.", "It can misread, accept or decline an offer you did not want it to. It comes with no warranty."},
        {"Not while driving.", "Don't handle your phone while driving. Pull over to look at offers."},
        {"Your data.", "Diagnostic logs use a rolling 24-hour window, pruned during app use. The latest 200 offer decisions "
                + "have no age-based expiry. A local wait estimate uses up to 200 numeric offer and observed-wait records in a rolling "
                + "24-hour window; these are not shared. Auto theme uses an existing allowed approximate location, or a local-clock fallback. "
                + "Recognized payment, account and earnings screens are discarded. End-user GitHub sign-in and automatic report sharing are "
                + "retired. Feedback is accountless and leaves only when you tap Send; masked diagnostics are attached only when you explicitly "
                + "choose them. The feedback service keeps submitted feedback for up to 90 days. Network providers still receive normal "
                + "connection metadata, and text masking can miss details. Privacy explains updates, feedback, place lookups and map links."},
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
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private Consent() {}
}

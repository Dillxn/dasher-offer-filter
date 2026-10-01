package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The first-run notice and the user's consent to it. Everyone sees it once, existing installs included, and again
 * only when {@link #VERSION} is raised. Until the current version is accepted, the screen reader reads, decides and
 * taps nothing, the notification path posts, hides and sends nothing (but for the one reminder that the app is
 * paused, {@link ConsentReminder}), and the homepage shows only the notice. "Not now" closes the app and stores
 * nothing. Nothing here ever turns consent off again.
 */
final class Consent {
    /** Raised when the notice, the terms or the privacy text change in substance, so everyone sees them once more. */
    static final int VERSION = 1;
    static final String PREFS = "consent";
    static final String ACCEPTED_VERSION = "accepted_version";
    static final String ACCEPTED_AT = "accepted_at";

    static final String TITLE = "Before you start";

    /** The notice: a few short points, each a bold lead and a line or two after it. */
    static final String[][] POINTS = {
        {"Not a DoorDash app.", AppName.NAME + " is not made by, endorsed by or affiliated with DoorDash."},
        {"What it does.", "It reads Dasher's screen and notifications on this phone. It only ever taps Decline (and "
                + "Dasher's \"are you sure\") on offers below your minimums, never Accept. While it declines, it "
                + "turns media and alarm sound down for a moment."},
        {"Your Dasher account.", "Using it may break DoorDash's terms. DoorDash could limit or deactivate your "
                + "account."},
        {"At your own risk.", "It can misread an offer or decline one you wanted. It comes with no warranty."},
        {"Not while driving.", "Don't handle your phone while driving. Pull over to look at offers."},
        {"Your data.", "Screen text stays on this phone, masked, for up to 24 hours. It leaves only in reports you "
                + "turn on or share."},
    };
    static final String AGREEMENT = "Tapping I understand means you accept the Terms.";
    static final String ACCEPT = "I understand";
    static final String NOT_NOW = "Not now";

    /** Whether the current notice was accepted. Cheap enough for every read: Android keeps the prefs in memory. */
    static boolean accepted(Context context) {
        return prefs(context).getInt(ACCEPTED_VERSION, 0) >= VERSION;
    }

    /**
     * The user tapped I understand: kept at once, before anything that waited for it runs, and the reminder that the
     * app is paused ({@link ConsentReminder}) goes.
     */
    static void accept(Context context) {
        prefs(context).edit().putInt(ACCEPTED_VERSION, VERSION).putLong(ACCEPTED_AT, System.currentTimeMillis())
                .commit();
        ConsentReminder.cancel(context);
        DiagnosticLog.log(context, "consent", "notice " + VERSION + " accepted; screen reading and background offers "
                + "may act");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private Consent() {}
}

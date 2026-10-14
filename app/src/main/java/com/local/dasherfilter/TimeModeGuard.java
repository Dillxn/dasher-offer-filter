package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.List;

/**
 * Earn by Time (0.6.5, from #DeclineNow; the owner chose it on 13 October 2026): in Dasher's time mode "You can decline
 * or unassign up to one offer every hour", and a second decline, or a timeout, ends the dash
 * (help.doordash.com/en-us/dashers/article/time-earnings-mode). From 0.6.18 only a decline question whose wording the
 * owner's reports have shown is confirmed ({@link DeclineConfirmation#knownPrompt}), and this guard stays as a second
 * check: its phrases still pause even when the question's own line is a known one.
 *
 * <p>So, removing taps only: Dasher's question whose words say the dash will end, or that declines are limited per
 * hour, is never confirmed; the offer is handed back and auto-decline paused (as tapping the mascot pauses it), with
 * one homepage line, until the user resumes. The same when Dasher's own words end the dash within
 * {@link #ENDED_AFTER_DECLINE_MS} of the app's confirmed decline.
 *
 * <p>The wording below is a guess: no report from a time-mode dash has shown Dasher's real question yet. It comes from
 * DoorDash's help page and common phrasings, and the tests use made-up wording too. A report from a time-mode dash
 * should replace it. The mode is never detected from Dasher's dash-start chooser, which names both modes.
 */
final class TimeModeGuard {
    /** How soon after the app's confirmed decline Dasher ending the dash counts as caused by it. */
    static final long ENDED_AFTER_DECLINE_MS = 10_000;
    static final String WOULD_END_LINE = "Paused: Dasher said another decline would end this dash";
    static final String ENDED_LINE = "Paused: Dasher ended the dash right after a decline";
    /**
     * 0.6.18: Dasher's decline question was not one whose wording the owner's reports have shown
     * ({@link DeclineConfirmation#knownPrompt}): never confirmed, the offer handed back, auto-decline paused. This guard's
     * own phrases still pause first, even when the question is a known one.
     */
    static final String UNKNOWN_QUESTION_LINE = "Paused: Dasher asked something new before a decline";
    static final String PREFS = "time_mode_guard";
    static final String LINE = "line";

    /** Guessed phrasings (see the class comment): the dash ending, or a limit on declines per hour. */
    private static final String[] ENDS_DASH = {
            "end your dash", "end this dash", "end the dash", "dash will end", "dash will be ended",
            "ends your dash", "ends this dash", "end your time", "your dash will stop",
    };
    private static final String[] PER_HOUR = {
            "every hour", "per hour", "an hour", "each hour", "1 hour", "one hour",
    };

    /** Whether Dasher's question says another decline ends the dash, or that declines are limited per hour. */
    static boolean endsDash(List<String> labels) {
        for (String label : labels) {
            String value = OfferControls.normalize(label);
            for (String phrase : ENDS_DASH) if (value.contains(phrase)) return true;
            if (value.contains("decline") || value.contains("unassign")) {
                for (String phrase : PER_HOUR) if (value.contains(phrase)) return true;
            }
        }
        return false;
    }

    /** Pauses auto-decline, keeping every saved rule, and keeps {@code line} for the homepage until resumed. */
    static void pause(Context context, String line) {
        FilterSettings saved = FilterStore.load(context);
        if (saved.enabled) FilterStore.save(context, saved, saved.withEnabled(false));
        prefs(context).edit().putString(LINE, line).apply();
        DiagnosticLog.log(context, "rules", "auto-decline paused: " + line);
        OfferNotificationService.rulesChanged();
        OfferFilterService.requestCheckForRules();
    }

    /** The homepage's paused line, while auto-decline is still paused by this guard; else null. */
    static String line(Context context) {
        if (FilterStore.load(context).enabled) return null;
        return prefs(context).getString(LINE, null);
    }

    /** Auto-decline resumed by the user: the line goes. */
    static void resumed(Context context) {
        prefs(context).edit().remove(LINE).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private TimeModeGuard() {}
}

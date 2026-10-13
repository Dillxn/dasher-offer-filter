package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Locale;

/**
 * The driver's own cost per mile for gas and wear (0.6.10; the #DeclineNow study's "cost per mile", the owner: "yeah I
 * want all its recommendations"). Off (0) by default, set in Autopilot's details in steps of {@link #STEP_CENTS}, and
 * never suggested: no IRS rate, which counts depreciation and would read as advice. With it set, Autopilot's value rule
 * weighs each offer's pay less this × its miles ({@link Autopilot#mix}); the acceptance-rate limit still comes first,
 * and nothing else changes. Kept like the rules: Clear history leaves it.
 */
final class CostPerMile {
    private CostPerMile() {}

    private static final String PREFS = "cost_per_mile";
    private static final String CENTS = "cents";
    static final int STEP_CENTS = 5;
    /** $2.00 a mile at most. */
    static final int MOST_CENTS = 200;
    static final String TITLE = "Your cost per mile";
    static final String ABOUT = "Your own gas and wear, if you want Autopilot to weigh pay after it. Off unless you "
            + "set it.";

    static int cents(Context context) {
        return clamp(prefs(context).getInt(CENTS, 0));
    }

    /** Sets it (0 turns it off), logs it in fixed words, and asks Autopilot for a plan. */
    static void set(Context context, int cents) {
        int next = clamp(cents);
        if (next == cents(context)) return;
        prefs(context).edit().putInt(CENTS, next).apply();
        DiagnosticLog.log(context, "autopilot", "cost per mile " + (next == 0 ? "off" : next + "¢"));
        AutopilotRuntime.requestPlan(context, AutopilotRuntime.Trigger.USER);
    }

    /** "35¢ a mile", or "Off". */
    static String words(int cents) {
        return cents <= 0 ? "Off" : cents >= 100 ? String.format(Locale.US, "$%.2f a mile", cents / 100.0)
                : cents + "¢ a mile";
    }

    static int clamp(int cents) {
        return Math.max(0, Math.min(MOST_CENTS, cents));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

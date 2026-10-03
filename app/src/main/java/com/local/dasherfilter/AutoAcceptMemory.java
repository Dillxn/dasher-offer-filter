package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

/** Numeric-only request provenance. It can suppress a personal lesson, never establish acceptance or tap authority. */
final class AutoAcceptMemory {
    static final String PREFS = "auto_accept_request";
    static boolean remember(Context context, OfferSnapshot offer) {
        return prefs(context).edit().clear().putString("facts", offer.fingerprint())
                .putLong("elapsed", SystemClock.elapsedRealtime()).putInt("boot", boot(context)).commit();
    }
    static boolean covers(Context context, OfferSnapshot offer) {
        SharedPreferences p = prefs(context);
        if (!p.contains("elapsed")) return false;
        try {
            long age = SystemClock.elapsedRealtime() - p.getLong("elapsed", 0);
            int before = p.getInt("boot", -1), now = boot(context);
            if (age >= AutoAccept.SUPPRESS_MS || before >= 0 && now >= 0 && before != now) {
                clear(context);
                return false;
            }
            if (age < 0) {
                // A clock from an unknown earlier boot can only suppress learning for one bounded new interval.
                // Resetting this numeric age once avoids keeping a future elapsed timestamp indefinitely.
                p.edit().putLong("elapsed", SystemClock.elapsedRealtime()).putInt("boot", now).commit();
            }
            return offer.fingerprint().equals(p.getString("facts", ""));
        } catch (RuntimeException corrupt) {
            return true; // Uncertain provenance may only suppress a lesson, never raise a floor.
        }
    }
    static void clear(Context context) { prefs(context).edit().clear().apply(); }
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    private static int boot(Context context) {
        try { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
        catch (RuntimeException unknown) { return -1; }
    }
    private AutoAcceptMemory() {}
}

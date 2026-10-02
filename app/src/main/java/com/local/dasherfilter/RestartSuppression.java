package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

/** One short-lived local refusal to tap after a restart. Never stores click or confirmation authority. */
final class RestartSuppression {
    static final String PREFS = "restart_suppression";
    static final long MAX_MS = 120_000;

    static final class Saved {
        final OfferSnapshot offer;
        final int countdown;
        final long ageMs;
        Saved(OfferSnapshot offer, int countdown, long ageMs) {
            this.offer = offer;
            this.countdown = countdown;
            this.ageMs = ageMs;
        }
    }

    /** Persist before the first request: a process dying during the Android call cannot restore tap permission. */
    static boolean remember(Context context, OfferSnapshot offer, int countdown) {
        SharedPreferences.Editor edit = prefs(context).edit().clear()
                .putLong("elapsed", SystemClock.elapsedRealtime()).putInt("boot", boot(context))
                .putInt("countdown", countdown);
        if (offer.payCents != null) edit.putInt("pay", offer.payCents);
        if (offer.miles != null) edit.putLong("miles", Double.doubleToLongBits(offer.miles));
        if (offer.minutes != null) edit.putInt("minutes", offer.minutes);
        if (offer.stops != null) edit.putInt("stops", offer.stops);
        return edit.commit();
    }

    static Saved load(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.contains("elapsed")) return null;
        try {
            int savedBoot = prefs.getInt("boot", -1), currentBoot = boot(context);
            long age = SystemClock.elapsedRealtime() - prefs.getLong("elapsed", 0);
            if ((savedBoot >= 0 && currentBoot >= 0 && savedBoot != currentBoot) || age >= MAX_MS) {
                clear(context);
                return null;
            }
            // An unknown boot or malformed elapsed clock cannot grant a tap; hold conservatively for at most 2 min.
            age = Math.max(0, age);
            OfferSnapshot offer = new OfferSnapshot(
                    prefs.contains("pay") ? prefs.getInt("pay", 0) : null,
                    prefs.contains("miles") ? Double.longBitsToDouble(prefs.getLong("miles", 0)) : null,
                    prefs.contains("minutes") ? prefs.getInt("minutes", 0) : null,
                    prefs.contains("stops") ? prefs.getInt("stops", 0) : null);
            return new Saved(offer, prefs.getInt("countdown", -1), age);
        } catch (RuntimeException corrupt) {
            // Corrupt suppression data is never a reason to act on a possibly taken-over offer.
            return new Saved(OfferSnapshot.UNKNOWN, -1, 0);
        }
    }

    static void clear(Context context) { prefs(context).edit().clear().apply(); }
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    private static int boot(Context context) {
        try { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
        catch (RuntimeException unavailable) { return -1; }
    }
    private RestartSuppression() {}
}

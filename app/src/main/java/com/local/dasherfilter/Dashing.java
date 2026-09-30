package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Whether the user is dashing, as far as the app has seen: an offer, Dasher's "finding offers" screen or a delivery
 * screen within the last half hour, or a route still under way, and no "dash ended" or "dash paused" screen since.
 * It drives the homepage's live monitoring and its per-dash counts; nothing about offers is decided from it. A dash
 * starts at the first such sight after the last one ended (or went quiet for half an hour).
 */
final class Dashing {
    static final long WINDOW_MS = 1_800_000L;
    /** Screens arrive many times a second; the time is written at most this often. */
    private static final long WRITE_EVERY_MS = 30_000L;
    private static final String SEEN_AT = "seen_at";
    private static final String ENDED_AT = "ended_at";
    private static final String STARTED_AT = "started_at";

    private static volatile long lastWrite;

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("dashing", Context.MODE_PRIVATE);
    }

    /** Something only seen during a dash: an offer, the search for one, or a delivery. */
    static void seen(Context context) {
        long now = System.currentTimeMillis();
        SharedPreferences prefs = prefs(context);
        boolean onDash = onDash(prefs, now);
        if (onDash && now - lastWrite < WRITE_EVERY_MS && now >= lastWrite) return;
        lastWrite = now;
        SharedPreferences.Editor edit = prefs.edit().putLong(SEEN_AT, now);
        if (!onDash) edit.putLong(STARTED_AT, now);
        edit.apply();
    }

    private static boolean onDash(SharedPreferences prefs, long now) {
        long seen = prefs.getLong(SEEN_AT, 0);
        long age = now - seen;
        return seen > prefs.getLong(ENDED_AT, 0) && age >= 0 && age < WINDOW_MS;
    }

    /**
     * The current dash, or the last one, as {start, end}; the end is {@link Long#MAX_VALUE} while it is still on.
     * Null before the first dash the app saw.
     */
    static long[] lastDash(Context context) {
        SharedPreferences prefs = prefs(context);
        long started = prefs.getLong(STARTED_AT, 0);
        if (started <= 0) return null;
        if (onDash(prefs, System.currentTimeMillis())) return new long[] {started, Long.MAX_VALUE};
        long ended = prefs.getLong(ENDED_AT, 0);
        long end = ended >= started ? ended : prefs.getLong(SEEN_AT, started) + WINDOW_MS;
        return new long[] {started, end};
    }

    /** Dasher said the dash ended or paused. */
    static void ended(Context context) {
        lastWrite = 0;
        prefs(context).edit().putLong(ENDED_AT, System.currentTimeMillis()).apply();
    }

    /** Whether the homepage should show the app watching: a dash is on and something can watch it. */
    static boolean now(Context context) {
        if (!OfferFilterService.isConnected() && !OfferNotificationService.isConnected()) return false;
        SharedPreferences prefs = prefs(context);
        long seen = prefs.getLong(SEEN_AT, 0);
        long ended = prefs.getLong(ENDED_AT, 0);
        long age = System.currentTimeMillis() - seen;
        boolean recent = seen > ended && age >= 0 && age < WINDOW_MS;
        return recent || (seen > ended && ActiveRouteStore.load(context) != null);
    }

    /** For tests: forget the write throttle. */
    static void forgetCache() {
        lastWrite = 0;
    }

    private Dashing() {}
}

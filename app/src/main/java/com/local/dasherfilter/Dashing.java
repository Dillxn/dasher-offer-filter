package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Whether the user is dashing, as far as the app has seen: an offer, Dasher's "finding offers" screen or a delivery
 * screen within the last half hour, or a route still under way, and no "dash ended" or "dash paused" screen since.
 * It only drives the homepage's live monitoring; nothing about offers is decided from it.
 */
final class Dashing {
    static final long WINDOW_MS = 1_800_000L;
    /** Screens arrive many times a second; the time is written at most this often. */
    private static final long WRITE_EVERY_MS = 30_000L;
    private static final String SEEN_AT = "seen_at";
    private static final String ENDED_AT = "ended_at";

    private static volatile long lastWrite;

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("dashing", Context.MODE_PRIVATE);
    }

    /** Something only seen during a dash: an offer, the search for one, or a delivery. */
    static void seen(Context context) {
        long now = System.currentTimeMillis();
        if (now - lastWrite < WRITE_EVERY_MS && now >= lastWrite) return;
        lastWrite = now;
        prefs(context).edit().putLong(SEEN_AT, now).apply();
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

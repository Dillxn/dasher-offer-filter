package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Whether the user is dashing, as far as the app has seen: an offer, Dasher's "finding offers" screen or a delivery
 * screen within the last half hour, or a route still under way, and no positive dash-end screen since.
 * It drives the homepage's live monitoring and its per-dash counts; nothing about offers is decided from it. A dash
 * starts at the first such sight after the last one ended (or went quiet for half an hour).
 */
final class Dashing {
    static final long WINDOW_MS = 1_800_000L;
    /** Screens arrive many times a second; the time is written at most this often. */
    private static final long WRITE_EVERY_MS = 30_000L;
    private static final String SEEN_AT = "seen_at";
    /** The same sighting on the boot's own clock (elapsedRealtime), and which boot it was (Settings.Global). */
    private static final String SEEN_ELAPSED = "seen_elapsed";
    private static final String SEEN_BOOT = "seen_boot";
    private static final String ENDED_AT = "ended_at";
    private static final String STARTED_AT = "started_at";
    private static final String PAUSED = "paused";
    private static final String OPEN = "open";

    private static volatile long lastWrite;

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("dashing", Context.MODE_PRIVATE);
    }

    /** Something only seen during a dash: an offer, the search for one, or a delivery. */
    static void seen(Context context) {
        long now = System.currentTimeMillis();
        SharedPreferences prefs = prefs(context);
        boolean onDash = onDash(prefs, now);
        if (onDash && !prefs.getBoolean(PAUSED, false)
                && now - lastWrite < WRITE_EVERY_MS && now >= lastWrite) return;
        lastWrite = now;
        long previousStart = prefs.getLong(STARTED_AT, 0);
        long previousSeen = prefs.getLong(SEEN_AT, 0);
        boolean previousOpen = open(prefs);
        SharedPreferences.Editor edit = prefs.edit().putLong(SEEN_AT, now).putBoolean(OPEN, true).remove(PAUSED)
                .putLong(SEEN_ELAPSED, SystemClock.elapsedRealtime()).putInt(SEEN_BOOT, boot(context));
        if (!onDash) edit.putLong(STARTED_AT, now);
        edit.apply();
        // A dash still open but quiet for half an hour ended then: its opt-in summary goes now (cheap when off).
        if (!onDash && previousOpen && previousStart > 0) {
            DashSummary.dashStarting(context, previousStart, previousSeen);
        }
    }

    /** The dash under way's start (wall clock), or 0 when none is on. */
    static long currentStart(Context context) {
        return on(context) ? prefs(context).getLong(STARTED_AT, 0) : 0;
    }

    /**
     * A dash still open whose last sighting is half an hour old (the quiet end), as {start, last seen}; else null.
     * Only for the opt-in summary after each dash: it never ends the hold on automatic installation.
     */
    static long[] quietlyEnded(Context context) {
        SharedPreferences prefs = prefs(context);
        long started = prefs.getLong(STARTED_AT, 0);
        long seen = prefs.getLong(SEEN_AT, 0);
        long now = System.currentTimeMillis();
        if (started <= 0 || seen <= 0 || !open(prefs) || now - seen < WINDOW_MS) return null;
        return new long[] {started, seen};
    }

    private static boolean onDash(SharedPreferences prefs, long now) {
        long seen = prefs.getLong(SEEN_AT, 0);
        long age = now - seen;
        return open(prefs) && age >= 0 && age < WINDOW_MS;
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

    /** Dasher positively said the dash ended. A pause is still the same shift. */
    static void ended(Context context) {
        lastWrite = 0;
        SharedPreferences prefs = prefs(context);
        boolean wasOpen = open(prefs);
        long started = prefs.getLong(STARTED_AT, 0);
        long now = System.currentTimeMillis();
        prefs.edit().putLong(ENDED_AT, now).putBoolean(OPEN, false).remove(PAUSED).apply();
        // Once per dash (its end screen is read many times): the opt-in summary after each dash (cheap when off).
        if (wasOpen) DashSummary.dashEnded(context, started, now);
    }

    /** A pause keeps the shift open but must release the screen-on lease. */
    static void paused(Context context) {
        if (!prefs(context).getBoolean(PAUSED, false)) {
            seen(context);
            prefs(context).edit().putBoolean(PAUSED, true).apply();
        }
    }

    static boolean isPaused(Context context) {
        return prefs(context).getBoolean(PAUSED, false);
    }

    /** Whether the homepage should show the app watching: a dash is on and something can watch it. */
    static boolean now(Context context) {
        if (!OfferFilterService.isConnected() && !OfferNotificationService.isConnected()) return false;
        return on(context);
    }

    /**
     * An observed shift has not positively ended. Used only to hold automatic installation: a quiet or locked
     * phone, an interrupted service, and the homepage's half-hour freshness limit are not evidence that a shift
     * is over. This conservative hold survives restart; the user's Updates tap can still install explicitly.
     */
    static boolean awaitingEnd(Context context) {
        return open(prefs(context));
    }

    private static boolean open(SharedPreferences prefs) {
        long seen = prefs.getLong(SEEN_AT, 0);
        return prefs.getBoolean(OPEN, seen > 0 && seen > prefs.getLong(ENDED_AT, 0));
    }

    /**
     * Whether a dash is on, as {@link #now} says to anything already watching it (the screen reader itself): what it
     * saw of a dash in the last half hour, or a route still under way, and no end since. Dasher's other screens are
     * kept only then, or soon after an offer.
     */
    static boolean on(Context context) {
        SharedPreferences prefs = prefs(context);
        long seen = prefs.getLong(SEEN_AT, 0);
        long age = System.currentTimeMillis() - seen;
        boolean recent = open(prefs) && age >= 0 && age < WINDOW_MS;
        return recent || (open(prefs) && ActiveRouteStore.load(context) != null);
    }

    /**
     * When anything of a dash was last seen (an offer, Dasher's wait for offers or a delivery screen; wall clock), 0
     * for never. Written at most every half minute.
     */
    static long lastSeen(Context context) {
        return prefs(context).getLong(SEEN_AT, 0);
    }

    /**
     * How long nothing of a dash has been seen, or -1 for never (or a sighting in the future): on the boot's own clock
     * when the last sighting was in this boot, so a wall clock set forward (by hand, or a network correction) cannot
     * shorten it; by the wall clock across a reboot, or when the boot cannot be told. Only {@link UpdateHold}'s
     * ceiling reads it.
     */
    static long quietFor(Context context) {
        SharedPreferences prefs = prefs(context);
        long seen = prefs.getLong(SEEN_AT, 0);
        if (seen <= 0) return -1;
        int then = prefs.getInt(SEEN_BOOT, -1);
        int now = boot(context);
        if (then >= 0 && now >= 0 && then == now && prefs.contains(SEEN_ELAPSED)) {
            long quiet = SystemClock.elapsedRealtime() - prefs.getLong(SEEN_ELAPSED, 0);
            return quiet >= 0 ? quiet : -1;
        }
        long quiet = System.currentTimeMillis() - seen;
        return quiet >= 0 ? quiet : -1;
    }

    /** Android's count of boots, or -1 when it cannot be read. */
    private static int boot(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }

    /** For tests: forget the write throttle. */
    static void forgetCache() {
        lastWrite = 0;
    }

    private Dashing() {}
}

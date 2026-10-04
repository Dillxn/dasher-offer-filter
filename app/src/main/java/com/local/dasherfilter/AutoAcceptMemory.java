package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

/** Numeric-only request provenance. It can suppress a personal lesson, never establish acceptance or tap authority. */
final class AutoAcceptMemory {
    static final String PREFS = "auto_accept_request";
    /** Process-local write revision: a proven-unsent rollback cannot erase a newer request or a history clear. */
    private static long revision;

    static final class Prepared {
        final long revision, elapsed, previousElapsed;
        final int boot, previousBoot;
        final String facts, previousFacts;
        final boolean hadPrevious;
        Prepared(long revision, String facts, long elapsed, int boot, boolean hadPrevious,
                 String previousFacts, long previousElapsed, int previousBoot) {
            this.revision = revision; this.facts = facts; this.elapsed = elapsed; this.boot = boot;
            this.hadPrevious = hadPrevious; this.previousFacts = previousFacts;
            this.previousElapsed = previousElapsed; this.previousBoot = previousBoot;
        }
    }

    static boolean remember(Context context, OfferSnapshot offer) {
        return prepare(context, offer) != null;
    }

    /** Persist uncertain request provenance before dispatch, retaining its predecessor only in this process. */
    static synchronized Prepared prepare(Context context, OfferSnapshot offer) {
        SharedPreferences p = prefs(context);
        try {
            boolean hadPrevious = p.contains("elapsed");
            String previousFacts = p.getString("facts", "");
            long previousElapsed = p.getLong("elapsed", 0);
            int previousBoot = p.getInt("boot", -1);
            long elapsed = SystemClock.elapsedRealtime();
            int boot = boot(context);
            String facts = offer.fingerprint();
            long written = ++revision;
            if (!p.edit().clear().putString("facts", facts).putLong("elapsed", elapsed).putInt("boot", boot).commit()) {
                return null; // Ambiguous persistence remains suppressive; no dispatch is permitted.
            }
            return new Prepared(written, facts, elapsed, boot, hadPrevious,
                    previousFacts, previousElapsed, previousBoot);
        } catch (RuntimeException unavailable) { return null; }
    }

    /**
     * Only a final guard that proves dispatch was never reached may roll back its own prepared record. A process
     * death loses this receipt and leaves uncertain provenance suppressive. A prior request (including the same
     * offer), a newer write and a history clear are never erased by this cleanup.
     */
    static synchronized void discardUnsent(Context context, Prepared prepared) {
        if (prepared == null || revision != prepared.revision) return;
        SharedPreferences p = prefs(context);
        try {
            if (!prepared.facts.equals(p.getString("facts", ""))
                    || prepared.elapsed != p.getLong("elapsed", -1) || prepared.boot != p.getInt("boot", -1)) return;
            SharedPreferences.Editor edit = p.edit().clear();
            if (prepared.hadPrevious) {
                edit.putString("facts", prepared.previousFacts).putLong("elapsed", prepared.previousElapsed)
                        .putInt("boot", prepared.previousBoot);
            }
            ++revision;
            edit.commit();
        } catch (RuntimeException unavailable) { /* Keep uncertain provenance suppressive. */ }
    }

    static synchronized boolean covers(Context context, OfferSnapshot offer) {
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
    static synchronized void clear(Context context) { ++revision; prefs(context).edit().clear().apply(); }
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    private static int boot(Context context) {
        try { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
        catch (RuntimeException unknown) { return -1; }
    }
    private AutoAcceptMemory() {}
}

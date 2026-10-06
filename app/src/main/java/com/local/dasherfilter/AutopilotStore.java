package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Autopilot's own state, in prefs "autopilot", apart from the rules (Autopilot's switch, goal and bar are rules, in
 * {@link FilterStore}). Numbers and fixed words only, never screen text:
 * <ul>
 *   <li>the latest acceptance rate Dasher showed on its decline question: a whole percent, when it was shown (wall
 *       clock) and the numeric fingerprint of the offer it was shown for; only the latest is kept, and a reading older
 *       than {@link #AR_MAX_AGE_MS} is discarded when read;</li>
 *   <li>the planner's acceptance-rate state: recovering, the stall correction ({@code extra}, 0–{@link #EXTRA_MAX})
 *       and its checkpoint;</li>
 *   <li>a pending user-change cause (the "jump") for the next commit;</li>
 *   <li>a note of the last bar change (time, old and new bar, a fixed reason name), when the bar last rose, and when
 *       the exemption valve was last logged.</li>
 * </ul>
 * Clear history removes all of it ({@link #clear}). Callers on several threads share it: every read-modify-write is
 * under this class's lock, and every write bumps {@link #version}.
 */
final class AutopilotStore {
    static final String PREFS = "autopilot";
    /** A reading lasts this long (7 days). */
    static final long AR_MAX_AGE_MS = 7L * 24 * 60 * 60_000;
    /** The same offer's reading again within this long only updates when it was seen. */
    static final long READING_DEDUP_MS = 120_000;
    /** A reading stamped this far ahead of the clock asked about (another thread's clock, a small adjustment) counts. */
    static final long READING_CLOCK_SLACK_MS = 60_000;
    /** The stall correction stays within 0–10 points. */
    static final int EXTRA_MAX = 10;

    private static final String AR_PERCENT = "ar_percent";
    private static final String AR_AT = "ar_at";
    private static final String AR_FP = "ar_fp";
    private static final String RECOVERING = "recovering";
    private static final String EXTRA = "extra";
    private static final String CP_AT = "cp_at";
    private static final String CP_READING = "cp_reading";
    private static final String JUMP = "jump";
    private static final String CHANGED_AT = "changed_at";
    private static final String CHANGED_FROM = "changed_from";
    private static final String CHANGED_TO = "changed_to";
    private static final String CHANGED_WHY = "changed_why";
    private static final String RAISED_AT = "raised_at";
    private static final String EXEMPT_LOGGED_AT = "exempt_logged_at";

    /** Changes whenever anything here is written or cleared, so a screen can skip redrawing unchanged state. */
    static volatile long version;

    static long version() {
        return version;
    }

    /** Dasher's acceptance rate as its decline question showed it. */
    static final class Reading {
        /** Whole percent, 0–100. */
        final int percent;
        /** When it was (last) seen, wall clock. */
        final long at;
        /** The numeric fingerprint of the offer it was shown for ({@link OfferSnapshot#fingerprint}); "" if unknown. */
        final String fingerprint;

        Reading(int percent, long at, String fingerprint) {
            this.percent = percent;
            this.at = at;
            this.fingerprint = fingerprint == null ? "" : fingerprint;
        }
    }

    /**
     * The latest reading, or null when there is none, it is older than {@link #AR_MAX_AGE_MS} (it is then discarded)
     * or it is stamped later than {@code wallNow} by more than {@link #READING_CLOCK_SLACK_MS}.
     */
    static Reading reading(Context context, long wallNow) {
        synchronized (AutopilotStore.class) {
            SharedPreferences prefs = prefs(context);
            if (!prefs.contains(AR_AT)) return null;
            long at = prefs.getLong(AR_AT, 0);
            int percent = prefs.getInt(AR_PERCENT, -1);
            if (at <= 0 || percent < 0 || percent > 100 || wallNow - at > AR_MAX_AGE_MS) {
                prefs.edit().remove(AR_PERCENT).remove(AR_AT).remove(AR_FP).apply();
                version++;
                return null;
            }
            if (at - wallNow > READING_CLOCK_SLACK_MS) return null;
            return new Reading(percent, at, prefs.getString(AR_FP, ""));
        }
    }

    /**
     * Keeps a reading of Dasher's acceptance rate ({@code percent}, 0–100) for the offer with this fingerprint, seen at
     * {@code wallNow}. The same offer and percent again within {@link #READING_DEDUP_MS} of the last sighting only
     * updates when it was seen.
     *
     * @return whether this is a new reading (another percent, another offer, or the same one after the dedupe window);
     *     false for a repeat and for a percent outside 0–100, which is not kept
     */
    static boolean recordReading(Context context, int percent, String fingerprint, long wallNow) {
        if (percent < 0 || percent > 100) return false;
        String fp = fingerprint == null ? "" : fingerprint;
        synchronized (AutopilotStore.class) {
            SharedPreferences prefs = prefs(context);
            if (prefs.contains(AR_AT) && prefs.getInt(AR_PERCENT, -1) == percent
                    && fp.equals(prefs.getString(AR_FP, null))) {
                long at = prefs.getLong(AR_AT, 0);
                if (Math.abs(wallNow - at) <= READING_DEDUP_MS) {
                    if (wallNow > at) {
                        prefs.edit().putLong(AR_AT, wallNow).apply();
                        version++;
                    }
                    return false;
                }
            }
            prefs.edit().putInt(AR_PERCENT, percent).putLong(AR_AT, wallNow).putString(AR_FP, fp).apply();
            version++;
            return true;
        }
    }

    // ---- The planner's acceptance-rate state ----

    /** Below the goal (or not yet 2 points above it) when last planned. */
    static boolean recovering(Context context) {
        return prefs(context).getBoolean(RECOVERING, false);
    }

    static void setRecovering(Context context, boolean recovering) {
        synchronized (AutopilotStore.class) {
            prefs(context).edit().putBoolean(RECOVERING, recovering).apply();
            version++;
        }
    }

    /** The stall correction, 0–{@link #EXTRA_MAX} points. */
    static int extra(Context context) {
        return Math.max(0, Math.min(EXTRA_MAX, prefs(context).getInt(EXTRA, 0)));
    }

    /** When the stall correction's checkpoint was set (wall clock), 0 when there is none. */
    static long checkpointAt(Context context) {
        return prefs(context).getLong(CP_AT, 0);
    }

    /** Dasher's reading at the checkpoint (whole percent), -1 when there is none. */
    static int checkpointReading(Context context) {
        return prefs(context).getInt(CP_READING, -1);
    }

    /**
     * The stall correction and its checkpoint; {@code checkpointAt} 0 (or a {@code checkpointReading} below 0) clears
     * the checkpoint. {@code extra} is held to 0–{@link #EXTRA_MAX}.
     */
    static void setCorrection(Context context, int extra, long checkpointAt, int checkpointReading) {
        synchronized (AutopilotStore.class) {
            correction(prefs(context).edit(), extra, checkpointAt, checkpointReading).apply();
            version++;
        }
    }

    /** What one plan changed of the acceptance-rate state, in one write. */
    static void savePlanState(Context context, boolean recovering, int extra, long checkpointAt,
                              int checkpointReading) {
        synchronized (AutopilotStore.class) {
            correction(prefs(context).edit().putBoolean(RECOVERING, recovering), extra, checkpointAt,
                    checkpointReading).apply();
            version++;
        }
    }

    private static SharedPreferences.Editor correction(SharedPreferences.Editor edit, int extra, long checkpointAt,
                                                       int checkpointReading) {
        edit.putInt(EXTRA, Math.max(0, Math.min(EXTRA_MAX, extra)));
        if (checkpointAt <= 0 || checkpointReading < 0) return edit.remove(CP_AT).remove(CP_READING);
        return edit.putLong(CP_AT, checkpointAt).putInt(CP_READING, Math.min(100, checkpointReading));
    }

    /**
     * Forgets the goal-relative state: recovering, the stall correction and its checkpoint. For a goal change,
     * Autopilot on or off, and Clear history.
     */
    static void resetGoalState(Context context) {
        synchronized (AutopilotStore.class) {
            prefs(context).edit().remove(RECOVERING).remove(EXTRA).remove(CP_AT).remove(CP_READING).apply();
            version++;
        }
    }

    // ---- The next commit's cause and the last change ----

    /** A pending user-change cause (a fixed name such as "TURNED_ON"), or null when none. */
    static String jump(Context context) {
        return prefs(context).getString(JUMP, null);
    }

    /** Sets the pending cause; null clears it. */
    static void setJump(Context context, String cause) {
        synchronized (AutopilotStore.class) {
            SharedPreferences.Editor edit = prefs(context).edit();
            if (cause == null || cause.isEmpty()) edit.remove(JUMP); else edit.putString(JUMP, cause);
            edit.apply();
            version++;
        }
    }

    static void clearJump(Context context) {
        setJump(context, null);
    }

    /** The last bar change: when (wall clock), from and to which bar, and its fixed reason name. */
    static final class Change {
        final long at;
        final int from;
        final int to;
        final String why;

        Change(long at, int from, int to, String why) {
            this.at = at;
            this.from = from;
            this.to = to;
            this.why = why == null ? "" : why;
        }
    }

    /** The last bar change, or null when none is kept. */
    static Change lastChange(Context context) {
        synchronized (AutopilotStore.class) {
            SharedPreferences prefs = prefs(context);
            if (!prefs.contains(CHANGED_AT)) return null;
            return new Change(prefs.getLong(CHANGED_AT, 0), prefs.getInt(CHANGED_FROM, 0),
                    prefs.getInt(CHANGED_TO, 0), prefs.getString(CHANGED_WHY, ""));
        }
    }

    /**
     * A committed bar change from {@code from} to {@code to} at {@code wall}, for the fixed reason name {@code why};
     * {@code raised} also keeps {@code wall} as when the bar last rose. It replaces the note before it.
     */
    static void recordChange(Context context, int from, int to, String why, long wall, boolean raised) {
        synchronized (AutopilotStore.class) {
            SharedPreferences.Editor edit = prefs(context).edit().putLong(CHANGED_AT, wall)
                    .putInt(CHANGED_FROM, from).putInt(CHANGED_TO, to).putString(CHANGED_WHY, why == null ? "" : why);
            if (raised) edit.putLong(RAISED_AT, wall);
            edit.apply();
            version++;
        }
    }

    /** When the bar last rose (wall clock), 0 when never. */
    static long raisedAt(Context context) {
        return prefs(context).getLong(RAISED_AT, 0);
    }

    /** When the exemption valve was last logged (wall clock), 0 when never: it is logged at most once a day. */
    static long exemptLoggedAt(Context context) {
        return prefs(context).getLong(EXEMPT_LOGGED_AT, 0);
    }

    static void setExemptLoggedAt(Context context, long wall) {
        synchronized (AutopilotStore.class) {
            prefs(context).edit().putLong(EXEMPT_LOGGED_AT, wall).apply();
            version++;
        }
    }

    /** Clear history: the reading, the acceptance-rate state, the jump and the change note all go. */
    static void clear(Context context) {
        synchronized (AutopilotStore.class) {
            prefs(context).edit().clear().apply();
            version++;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private AutopilotStore() {}
}

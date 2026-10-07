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
 *       and its checkpoint (when, and the acceptance rate carried forward from Dasher's reading then, in hundredths of
 *       a percent, when there was one);</li>
 *   <li>a pending user-change cause (the "jump") for the next commit;</li>
 *   <li>a note of the last bar change (time, old and new bar, a fixed reason name), when the bar last rose, and when
 *       the exemption valve was last logged;</li>
 *   <li>the last growth of the minimums ({@link Growth}, 0.5.1): when, how much, how many offers on how many days, the
 *       minimums and the bar before and after, and whether its homepage note (with Undo) still waits for a tap.</li>
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
    /** The carried-forward acceptance rate at the checkpoint, hundredths of a percent (0.5.0 never kept a raw one). */
    private static final String CP_AR = "cp_ar";
    /** An earlier development build's raw-percent checkpoint; removed with the checkpoint, never read. */
    private static final String RETIRED_CP_READING = "cp_reading";
    private static final String JUMP = "jump";
    private static final String CHANGED_AT = "changed_at";
    private static final String CHANGED_FROM = "changed_from";
    private static final String CHANGED_TO = "changed_to";
    private static final String CHANGED_WHY = "changed_why";
    private static final String RAISED_AT = "raised_at";
    private static final String EXEMPT_LOGGED_AT = "exempt_logged_at";
    /** The last growth: when (also the minimums' "since" it wrote), points, offers and days, before and after. */
    private static final String GREW_AT = "grew_at";
    private static final String GREW_PERCENT = "grew_percent";
    private static final String GREW_OFFERS = "grew_offers";
    private static final String GREW_DAYS = "grew_days";
    private static final String GREW_FLAT_FROM = "grew_flat_from";
    private static final String GREW_FLAT_TO = "grew_flat_to";
    private static final String GREW_MILE_FROM = "grew_mile_from";
    private static final String GREW_MILE_TO = "grew_mile_to";
    private static final String GREW_MINUTE_FROM = "grew_minute_from";
    private static final String GREW_MINUTE_TO = "grew_minute_to";
    private static final String GREW_BAR_FROM = "grew_bar_from";
    private static final String GREW_BAR_TO = "grew_bar_to";
    /** The homepage's note of it (with Undo) waits for a tap. */
    private static final String GREW_NOTE = "grew_note";
    private static final String[] GREW_KEYS = {GREW_AT, GREW_PERCENT, GREW_OFFERS, GREW_DAYS, GREW_FLAT_FROM,
        GREW_FLAT_TO, GREW_MILE_FROM, GREW_MILE_TO, GREW_MINUTE_FROM, GREW_MINUTE_TO, GREW_BAR_FROM, GREW_BAR_TO,
        GREW_NOTE};

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

    /**
     * The acceptance rate carried forward from Dasher's reading at the checkpoint, in hundredths of a percent; -1 when
     * there is no checkpoint, or its rate was not from a Dasher reading (the app's own estimate, or none).
     */
    static int checkpointAr(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.getLong(CP_AT, 0) <= 0) return -1;
        int ar = prefs.getInt(CP_AR, -1);
        return ar < 0 || ar > 10_000 ? -1 : ar;
    }

    /**
     * The stall correction and its checkpoint; {@code checkpointAt} 0 clears the checkpoint, and a
     * {@code checkpointAr} below 0 keeps a checkpoint without a Dasher rate. {@code extra} is held to
     * 0–{@link #EXTRA_MAX}.
     */
    static void setCorrection(Context context, int extra, long checkpointAt, int checkpointAr) {
        synchronized (AutopilotStore.class) {
            correction(prefs(context).edit(), extra, checkpointAt, checkpointAr).apply();
            version++;
        }
    }

    /** What one plan changed of the acceptance-rate state, in one write. */
    static void savePlanState(Context context, boolean recovering, int extra, long checkpointAt, int checkpointAr) {
        synchronized (AutopilotStore.class) {
            correction(prefs(context).edit().putBoolean(RECOVERING, recovering), extra, checkpointAt, checkpointAr)
                    .apply();
            version++;
        }
    }

    private static SharedPreferences.Editor correction(SharedPreferences.Editor edit, int extra, long checkpointAt,
                                                       int checkpointAr) {
        edit.putInt(EXTRA, Math.max(0, Math.min(EXTRA_MAX, extra))).remove(RETIRED_CP_READING);
        if (checkpointAt <= 0) return edit.remove(CP_AT).remove(CP_AR);
        edit.putLong(CP_AT, checkpointAt);
        return checkpointAr < 0 ? edit.remove(CP_AR) : edit.putInt(CP_AR, Math.min(10_000, checkpointAr));
    }

    /**
     * Forgets the goal-relative state: recovering, the stall correction and its checkpoint. For a goal change,
     * Autopilot on or off, and Clear history.
     */
    static void resetGoalState(Context context) {
        synchronized (AutopilotStore.class) {
            prefs(context).edit().remove(RECOVERING).remove(EXTRA).remove(CP_AT).remove(CP_AR)
                    .remove(RETIRED_CP_READING).apply();
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

    /**
     * Clears the pending cause only while it is still {@code cause} (compare-and-clear), so a commit that used one
     * cause never swallows another the user set meanwhile; null clears only when none is pending (nothing to do).
     *
     * @return whether no cause other than {@code cause} was pending (and none is now)
     */
    static boolean clearJumpIf(Context context, String cause) {
        synchronized (AutopilotStore.class) {
            SharedPreferences prefs = prefs(context);
            String pending = prefs.getString(JUMP, null);
            if (pending == null || pending.isEmpty()) return true;
            if (cause == null || !cause.equals(pending)) return false;
            prefs.edit().remove(JUMP).apply();
            version++;
            return true;
        }
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

    /**
     * Forgets the last bar change's note (when the bar last rose stays): turning Autopilot off puts the bar back at
     * exactly 100, so a note of how Autopilot last moved it would no longer say why the bar is where it is, in the
     * details, the shared report or an offer report, then or after Autopilot is turned on again.
     */
    static void forgetChange(Context context) {
        synchronized (AutopilotStore.class) {
            prefs(context).edit().remove(CHANGED_AT).remove(CHANGED_FROM).remove(CHANGED_TO).remove(CHANGED_WHY)
                    .apply();
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

    // ---- The last growth of the minimums ----

    /** The last growth of the minimums, as kept. */
    static final class Grew {
        /** When (wall clock): also the "minimums since" the growth wrote ({@link FilterStore#minimumsSince}). */
        final long at;
        final int offers;
        final int days;
        final Growth.Grown grown;
        /** Its homepage note (with Undo) still waits for a tap. */
        final boolean note;

        Grew(long at, int offers, int days, Growth.Grown grown, boolean note) {
            this.at = at;
            this.offers = offers;
            this.days = days;
            this.grown = grown;
            this.note = note;
        }
    }

    /** The minimums grew as {@code evidence} said, at {@code wall}: kept, replacing any before, with its note. */
    static void recordGrowth(Context context, Growth.Evidence evidence, long wall) {
        Growth.Grown grown = evidence.grown;
        synchronized (AutopilotStore.class) {
            prefs(context).edit().putLong(GREW_AT, wall).putInt(GREW_PERCENT, grown.percent)
                    .putInt(GREW_OFFERS, evidence.offers).putInt(GREW_DAYS, evidence.days)
                    .putInt(GREW_FLAT_FROM, grown.flatBefore).putInt(GREW_FLAT_TO, grown.flatAfter)
                    .putInt(GREW_MILE_FROM, grown.mileBefore).putInt(GREW_MILE_TO, grown.mileAfter)
                    .putInt(GREW_MINUTE_FROM, grown.minuteBefore).putInt(GREW_MINUTE_TO, grown.minuteAfter)
                    .putInt(GREW_BAR_FROM, grown.barBefore).putInt(GREW_BAR_TO, grown.barAfter)
                    .putBoolean(GREW_NOTE, true).apply();
            version++;
        }
    }

    /** The last growth, or null when none is kept. */
    static Grew lastGrowth(Context context) {
        synchronized (AutopilotStore.class) {
            SharedPreferences prefs = prefs(context);
            if (!prefs.contains(GREW_AT)) return null;
            Growth.Grown grown = new Growth.Grown(prefs.getInt(GREW_PERCENT, 0), prefs.getInt(GREW_FLAT_FROM, 0),
                    prefs.getInt(GREW_MILE_FROM, 0), prefs.getInt(GREW_MINUTE_FROM, 0),
                    prefs.getInt(GREW_BAR_FROM, FilterSettings.BAR_AT_MINIMUMS), prefs.getInt(GREW_FLAT_TO, 0),
                    prefs.getInt(GREW_MILE_TO, 0), prefs.getInt(GREW_MINUTE_TO, 0),
                    prefs.getInt(GREW_BAR_TO, FilterSettings.BAR_AT_MINIMUMS));
            return new Grew(prefs.getLong(GREW_AT, 0), prefs.getInt(GREW_OFFERS, 0), prefs.getInt(GREW_DAYS, 0),
                    grown, prefs.getBoolean(GREW_NOTE, false));
        }
    }

    /**
     * The homepage's note of the growth made at {@code at} is answered (OK) or no longer applies: it and its Undo go.
     * Compare-and-clear: the note of a later growth is never taken for it.
     */
    static void forgetGrowthNote(Context context, long at) {
        synchronized (AutopilotStore.class) {
            SharedPreferences prefs = prefs(context);
            if (!prefs.getBoolean(GREW_NOTE, false) || prefs.getLong(GREW_AT, 0) != at) return;
            prefs.edit().remove(GREW_NOTE).apply();
            version++;
        }
    }

    /** The last growth was undone: nothing of it is kept. */
    static void forgetGrowth(Context context) {
        synchronized (AutopilotStore.class) {
            SharedPreferences.Editor edit = prefs(context).edit();
            for (String key : GREW_KEYS) edit.remove(key);
            edit.apply();
            version++;
        }
    }

    /**
     * Clear history: the reading, the acceptance-rate state, the jump, the change note and the last growth (with its
     * note and Undo) all go.
     */
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

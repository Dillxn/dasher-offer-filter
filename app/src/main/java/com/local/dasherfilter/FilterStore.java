package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Saved rules (0.5.0, rules model 2), Autopilot's switch, goal and bar, and the last user-visible status line.
 *
 * <p>Two writers share prefs "offer_filter" on disjoint keys. {@link #save} (the user's rules, main thread) writes only
 * enabled, the three money minimums and max stops. The bar is written only by {@link #commitAutopilotBar} (Autopilot,
 * compare-and-set at a safe point on the scanner thread) and reset to exactly 100 by {@link #setAutopilot} turning
 * Autopilot off; {@link #save} never writes it, so a rule saved from a stale copy can never restore an old bar.
 *
 * <p>The first {@link #load} after the update runs {@link #migrateToModel2} once: the retired rules (score by area,
 * per stop, per item, hotspot, the minimums scale) and every learned minimum leave, per stop is folded into minimum pay
 * and the old scale is built into the minimums, rounded up.
 */
final class FilterStore {
    private static final String PREFS = "offer_filter";
    private static final String ENABLED = "enabled";
    private static final String FLAT = "flat";
    private static final String PER_MILE = "mile";
    private static final String PER_MINUTE = "minute";
    private static final String MAX_STOPS = "max_stops";

    /** Autopilot on: only Autopilot moves the bar, between offers. Off unless turned on. */
    private static final String AUTOPILOT_ON = "autopilot_on";
    /** Autopilot's acceptance-rate goal: 70, 50 or 0 (pay first). */
    private static final String AUTOPILOT_GOAL = "autopilot_goal";
    /** The goal chooser was shown and answered (or Autopilot was set) at least once. */
    private static final String AUTOPILOT_GOAL_ASKED = "autopilot_goal_asked";
    /** The bar while Autopilot is on, in percent of the minimums; ignored (exactly 100) while it is off. */
    private static final String AUTOPILOT_BAR = "autopilot_bar_percent";
    /** The lowest and highest bar Autopilot may set. */
    static final int BAR_MIN = 50;
    static final int BAR_MAX = 150;
    /** Which rules model the stored values are in: absent (1) before 0.5.0, {@link #MODEL} after. */
    private static final String RULES_MODEL = "rules_model";
    static final int MODEL = 2;
    /** What the 0.5.0 migration changed, as JSON, until the homepage's notice is dismissed. */
    private static final String MODEL_NOTICE = "model_notice";

    // ---- Retired by the 0.5.0 migration: removed, never read as rules again, never reused. ----
    private static final String PER_STOP = "per_stop";
    private static final String PER_ITEM = "per_item";
    private static final String HOTSPOT_PROXIMITY = "hotspot_proximity_hundredths";
    private static final String MINIMUM_SCALE = "minimum_scale_percent";
    private static final String RISING_OFFERS = "rising_offers";
    private static final String SCORE_BY_AREA = "score_by_area";
    private static final List<String> LEARNED_KEYS = Collections.unmodifiableList(Arrays.asList(
            "last_accepted",
            "best_minute_pay", "best_minutes", "best_mile_pay", "best_miles_bits", "best_stop_pay", "best_stops",
            "best_item_pay", "best_items",
            "declined_pay", "declined_minute_pay", "declined_minutes", "declined_mile_pay", "declined_miles_bits",
            "declined_stop_pay", "declined_stops"));
    private static final List<String> LEARNING_TIMES = Collections.unmodifiableList(Arrays.asList(
            "learning_on_since", "learning_off_at", "adaptive_reset_at"));
    /** The 25 keys 0.5.0 retires: six rules, sixteen learned values and three learning times. */
    static final List<String> RETIRED_KEYS;

    static {
        List<String> retired = new ArrayList<>(Arrays.asList(PER_STOP, PER_ITEM, HOTSPOT_PROXIMITY, MINIMUM_SCALE,
                RISING_OFFERS, SCORE_BY_AREA));
        retired.addAll(LEARNED_KEYS);
        retired.addAll(LEARNING_TIMES);
        RETIRED_KEYS = Collections.unmodifiableList(retired);
    }

    /** Rules saved by any version: an install that has none of these (nor a retired key) is fresh. */
    private static final List<String> RULE_KEYS = Collections.unmodifiableList(Arrays.asList(
            ENABLED, FLAT, PER_MILE, PER_MINUTE, MAX_STOPS));
    /** The held hand-decline record of older versions, retired with the learning it fed. */
    static final String RETIRED_MANUAL_DECLINES = "offer_filter_manual_declines";

    /**
     * Where older versions kept the extra-stop fee, added on top of the other minimums for each stop after two. It is
     * retired, never read as a rule: per stop, which replaced it, is itself retired in 0.5.0, and reading the fee as
     * anything would silently change which offers are declined. Its handling below is unchanged; only its words say
     * what 0.5.0 offers instead (max stops).
     */
    private static final String RETIRED_EXTRA_STOP_FEE = "stop";
    /** What the homepage says once when a set extra-stop fee was retired. */
    private static final String STOP_FEE_NOTICE = "stop_fee_notice";
    private static final String DOORDASH_OFFER_CHANNEL = "doordash_offer_channel";
    /** Evidence from a post, separate from older versions' channel-configuration-only flag. */
    private static final String DOORDASH_POST_SOUNDED = "doordash_post_sounded";
    /**
     * Whether Dasher's offer channel alerts (sound or vibration at default importance or above), as Android's ranking
     * of its last offer notification said; absent until one was seen with its channel.
     */
    private static final String DOORDASH_CHANNEL_ALERTS = "doordash_channel_alerts";
    private static final String LAST_STATUS = "last_status";
    /** Where 0.4.13 and earlier kept an address for emailing reports; Share replaced that, so it is removed. */
    private static final String RETIRED_REPORT_EMAIL = "report_email";
    private static final String SILENCE_WHILE_DECLINING = "silence_while_declining";
    /** Peek at background offers ({@link Peek}); on unless turned off. */
    private static final String PEEK = "peek_background_offers";
    /** Accepting a delivery is a separate, explicit opt-in; upgrades never enable it. */
    private static final String AUTO_ACCEPT = "auto_accept_matching_offers";

    /**
     * Test seam: runs inside the migration after its edit is built and before it is applied, so a test can stop it
     * there (by throwing) as a crash would. Null in the app.
     */
    static volatile Runnable migrationInterruptForTests;

    static FilterSettings load(Context context) {
        SharedPreferences prefs = prefs(context);
        retireExtraStopFee(context, prefs);
        migrateToModel2(context, prefs);
        return read(prefs);
    }

    /**
     * The stored rules. Autopilot's bar is {@link #BAR_MIN}–{@link #BAR_MAX} while it is on and exactly 100 while it
     * is off; the goal is 70, 50 or 0.
     */
    private static FilterSettings read(SharedPreferences prefs) {
        boolean autopilot = prefs.getBoolean(AUTOPILOT_ON, false);
        int bar = autopilot ? bar(prefs) : FilterSettings.BAR_AT_MINIMUMS;
        return new FilterSettings(prefs.getBoolean(ENABLED, false), prefs.getInt(FLAT, 0), prefs.getInt(PER_MILE, 0),
                prefs.getInt(PER_MINUTE, 0), prefs.getInt(MAX_STOPS, 0), autopilot,
                FilterSettings.sanitizedGoal(prefs.getInt(AUTOPILOT_GOAL, FilterSettings.GOAL_TOP_TIER)), bar);
    }

    /** The stored bar held to {@link #BAR_MIN}–{@link #BAR_MAX}; 100 when none is stored. */
    private static int bar(SharedPreferences prefs) {
        return clamp(prefs.getInt(AUTOPILOT_BAR, FilterSettings.BAR_AT_MINIMUMS), BAR_MIN, BAR_MAX);
    }

    /**
     * Removes an extra-stop fee saved by an older version, once; nothing takes its value, which meant something no
     * rule of 0.5.0 means (max stops is what limits stacked orders now). A fee that was set is noted in the diagnostic
     * log and the status, and the homepage says so once; if it was the only rule, auto-decline is paused in the same
     * edit, so the page never shows an active filter that filters nothing. A fee of 0 was never a rule and goes
     * silently.
     */
    private static void retireExtraStopFee(Context context, SharedPreferences prefs) {
        if (!prefs.contains(RETIRED_EXTRA_STOP_FEE)) return;
        Object saved;
        boolean paused = false;
        synchronized (FilterStore.class) {
            if (!prefs.contains(RETIRED_EXTRA_STOP_FEE)) return;
            saved = prefs.getAll().get(RETIRED_EXTRA_STOP_FEE);
            SharedPreferences.Editor edit = prefs.edit().remove(RETIRED_EXTRA_STOP_FEE);
            if (saved instanceof Integer && (Integer) saved > 0) {
                FilterSettings left = read(prefs);
                paused = left.enabled && !left.hasAnyRule();
                if (paused) edit.putBoolean(ENABLED, false);
                String notice = "Your " + DecisionLog.money((Integer) saved) + " extra-stop fee was removed; it is "
                        + "not a rule any more. Use Max stops to limit stacked orders."
                        + (paused ? " It was your only rule, so auto-decline is paused." : "");
                edit.putString(STOP_FEE_NOTICE, notice).putString(LAST_STATUS, stamped(notice));
            }
            edit.apply();
        }
        if (!(saved instanceof Integer) || (Integer) saved <= 0) return;
        DiagnosticLog.log(context, "rules", "the old extra-stop fee of " + DecisionLog.money((Integer) saved)
                + " was retired; it is not a rule any more (max stops limits stacked orders)"
                + (paused ? "; no rule was left, so auto-decline was paused" : ""));
    }

    /**
     * The note about a retired extra-stop fee, once: it is forgotten as it is taken, so the homepage shows it the first
     * time it opens after the update and never again. Null when there is none.
     */
    static String takeStopFeeNotice(Context context) {
        SharedPreferences prefs = prefs(context);
        synchronized (FilterStore.class) {
            String notice = prefs.getString(STOP_FEE_NOTICE, null);
            if (notice != null) prefs.edit().remove(STOP_FEE_NOTICE).apply();
            return notice;
        }
    }

    // ---- The 0.5.0 migration (rules model 2) ----

    /**
     * Moves stored rules to model 2, once; again only if an older version wrote a retired key since (a downgrade and
     * upgrade). Idempotent, and crash-safe: everything it changes in prefs "offer_filter" is one edit applied at once,
     * so a crash leaves either the old rules (and this runs again from them) or the new ones. The retired hand-decline
     * record is deleted before that edit, so no crash can leave it behind.
     *
     * <ul>
     *   <li>Per stop is folded into minimum pay as {@code min(100000, max(flat, 2 × per stop))}: a single order (two
     *       stops) is judged exactly as before.</li>
     *   <li>The old minimums scale is built into each money minimum, rounded up so nothing gets looser:
     *       {@code ⌈x × scale ÷ 100⌉}, at most 100000; 0 stays off.</li>
     *   <li>The 25 {@link #RETIRED_KEYS} are removed.</li>
     *   <li>Auto-decline is paused when it was on and no rule is left; auto-accept is turned off once when a retired
     *       rule or learned value was in use, the scale was not 100, or only max stops is left.</li>
     *   <li>Autopilot starts off, goal 70, bar 100 (each only if absent).</li>
     *   <li>An install that had rules gets one "rules" log line, and a notice ({@link #peekModelNotice}) and status
     *       when the meaning of its rules changed.</li>
     * </ul>
     */
    static void migrateToModel2(Context context, SharedPreferences prefs) {
        if (!needsModel2(prefs)) return;
        Migration done;
        synchronized (FilterStore.class) {
            if (!needsModel2(prefs)) return;
            done = Migration.of(prefs.getAll());
            try {
                context.deleteSharedPreferences(RETIRED_MANUAL_DECLINES);
            } catch (RuntimeException unavailable) {
                // Nothing reads it any more; a file that cannot be deleted now is harmless.
            }
            SharedPreferences.Editor edit = prefs.edit();
            done.writeTo(edit);
            Runnable interrupt = migrationInterruptForTests;
            if (interrupt != null) interrupt.run();
            edit.apply();
        }
        if (!done.fresh) DiagnosticLog.log(context, "rules", done.logLine());
    }

    /** Stored values are not yet model 2, or an older version wrote a retired key since. */
    private static boolean needsModel2(SharedPreferences prefs) {
        if (prefs.getInt(RULES_MODEL, 1) < MODEL) return true;
        for (String key : RETIRED_KEYS) {
            if (prefs.contains(key)) return true;
        }
        return false;
    }

    /** What the 0.5.0 migration finds in stored values and makes of them: exact integer arithmetic only. */
    static final class Migration {
        /** Nothing about rules was ever stored: nothing to tell the user and nothing to log. */
        final boolean fresh;
        final boolean enabled;
        final int flat;
        final int mile;
        final int minute;
        final int maxStops;
        final int perStop;
        final int perItem;
        final int hotspot;
        /** The old minimums scale, 1–200 (100 when none was stored). */
        final int scale;
        final boolean rising;
        final boolean area;
        /** A learned value of the adaptive minimum was stored. */
        final boolean learned;
        final boolean autoAcceptWasOn;
        /** Minimum pay with per stop folded in, before the scale is built in. */
        final int foldedFlat;
        final int newFlat;
        final int newMile;
        final int newMinute;
        /** A retired rule or learned value was in use, or the scale was not 100: the rules' meaning changed. */
        final boolean retiredInUse;
        final boolean autoAcceptOff;
        final boolean paused;
        /** Autopilot settings already stored (a downgrade and upgrade): kept as they are. */
        private final boolean storedAutopilot;
        private final boolean storedGoal;
        private final boolean storedBar;

        private Migration(Map<String, ?> stored) {
            boolean anyRule = false;
            for (String key : RULE_KEYS) anyRule |= stored.containsKey(key);
            boolean anyRetired = false;
            for (String key : RETIRED_KEYS) anyRetired |= stored.containsKey(key);
            boolean anyLearned = false;
            for (String key : LEARNED_KEYS) anyLearned |= stored.containsKey(key);
            fresh = !anyRule && !anyRetired;
            enabled = bool(stored.get(ENABLED));
            flat = money(stored.get(FLAT));
            mile = money(stored.get(PER_MILE));
            minute = money(stored.get(PER_MINUTE));
            maxStops = Math.max(0, integer(stored.get(MAX_STOPS), 0));
            perStop = money(stored.get(PER_STOP));
            perItem = money(stored.get(PER_ITEM));
            hotspot = Math.max(0, integer(stored.get(HOTSPOT_PROXIMITY), 0));
            // A scale that is missing or not a number was never a scale: exactly the minimums.
            scale = clamp(integer(stored.get(MINIMUM_SCALE), FilterSettings.BAR_AT_MINIMUMS), 1, 200);
            rising = bool(stored.get(RISING_OFFERS));
            area = bool(stored.get(SCORE_BY_AREA));
            learned = anyLearned;
            autoAcceptWasOn = bool(stored.get(AUTO_ACCEPT));
            foldedFlat = perStop > 0 ? (int) Math.min(FilterSettings.MOST_CENTS, Math.max(flat, 2L * perStop)) : flat;
            newFlat = bake(foldedFlat, scale);
            newMile = bake(mile, scale);
            newMinute = bake(minute, scale);
            retiredInUse = perStop > 0 || perItem > 0 || hotspot > 0 || rising || area || learned
                    || scale != FilterSettings.BAR_AT_MINIMUMS;
            boolean moneyLeft = newFlat > 0 || newMile > 0 || newMinute > 0;
            autoAcceptOff = autoAcceptWasOn && (retiredInUse || (!moneyLeft && maxStops > 0));
            paused = enabled && !moneyLeft && maxStops == 0;
            storedAutopilot = stored.containsKey(AUTOPILOT_ON);
            storedGoal = stored.containsKey(AUTOPILOT_GOAL);
            storedBar = stored.containsKey(AUTOPILOT_BAR);
        }

        /** The migration of these stored values (prefs "offer_filter", as {@code getAll()} returns them). */
        static Migration of(Map<String, ?> stored) {
            return new Migration(stored);
        }

        /** The scale built into a minimum, rounded up to a whole cent: {@code ⌈x × scale ÷ 100⌉}, at most 100000. */
        static int bake(int cents, int scale) {
            if (cents <= 0) return 0;
            return (int) Math.min(FilterSettings.MOST_CENTS, (cents * (long) scale + 99) / 100);
        }

        /** The learning was on or left values behind: the adaptive minimum is what retires. */
        boolean adaptive() {
            return rising || learned;
        }

        /** The user is told what changed (and the status says so). */
        boolean notice() {
            return !fresh && (retiredInUse || autoAcceptOff || paused);
        }

        private void writeTo(SharedPreferences.Editor edit) {
            if (!fresh) edit.putInt(FLAT, newFlat).putInt(PER_MILE, newMile).putInt(PER_MINUTE, newMinute);
            for (String key : RETIRED_KEYS) edit.remove(key);
            if (paused) edit.putBoolean(ENABLED, false);
            if (autoAcceptOff) edit.putBoolean(AUTO_ACCEPT, false);
            // Only when absent: a downgrade and upgrade keeps the Autopilot settings it had.
            if (!storedAutopilot) edit.putBoolean(AUTOPILOT_ON, false);
            if (!storedGoal) edit.putInt(AUTOPILOT_GOAL, FilterSettings.GOAL_TOP_TIER);
            if (!storedBar) edit.putInt(AUTOPILOT_BAR, FilterSettings.BAR_AT_MINIMUMS);
            edit.putInt(RULES_MODEL, MODEL);
            if (notice()) {
                edit.putString(MODEL_NOTICE, noticeJson())
                        .putString(LAST_STATUS, stamped("Rules simplified for " + RELEASE));
            }
        }

        /**
         * The notice's facts: {area, adaptive, perStop, foldedFlat, flatBefore, buffer (the old scale), newFlat,
         * newMile, newMinute, perItem, hotspot, autoAcceptOff, paused}, money in cents.
         */
        String noticeJson() {
            try {
                return new JSONObject().put("area", area).put("adaptive", adaptive()).put("perStop", perStop)
                        .put("foldedFlat", foldedFlat).put("flatBefore", flat).put("buffer", scale)
                        .put("newFlat", newFlat).put("newMile", newMile).put("newMinute", newMinute)
                        .put("perItem", perItem).put("hotspot", hotspot).put("autoAcceptOff", autoAcceptOff)
                        .put("paused", paused).toString();
            } catch (JSONException impossible) {
                return "{}";
            }
        }

        /**
         * One fixed-words line for the diagnostic log, e.g. "rules model 2: flat 1950 -> 2040 (per stop 1275 folded,
         * buffer 80% built in); mile 500 -> 400; minute 60 -> 48; retired: per item 1790, score by area, adaptive
         * (learned values cleared), hotspot no; auto-accept turned off; paused no".
         */
        String logLine() {
            List<String> why = new ArrayList<>();
            if (perStop > 0) why.add("per stop " + perStop + " folded");
            if (scale != FilterSettings.BAR_AT_MINIMUMS) why.add("buffer " + scale + "% built in");
            return "rules model " + MODEL + ": flat " + flat + " -> " + newFlat
                    + (why.isEmpty() ? "" : " (" + String.join(", ", why) + ")")
                    + "; mile " + mile + " -> " + newMile + "; minute " + minute + " -> " + newMinute
                    + "; retired: per item " + (perItem > 0 ? String.valueOf(perItem) : "no")
                    + ", score by area" + (area ? "" : " no")
                    + ", adaptive" + (adaptive() ? " (learned values cleared)" : " no")
                    + ", hotspot " + (hotspot > 0 ? String.valueOf(hotspot) : "no")
                    + "; auto-accept " + (autoAcceptOff ? "turned off" : "unchanged")
                    + "; paused " + (paused ? "yes" : "no");
        }

        private static int money(Object value) {
            return clamp(integer(value, 0), 0, FilterSettings.MOST_CENTS);
        }

        /** A stored whole number; anything else (absent, or a type no version wrote here) is {@code otherwise}. */
        private static int integer(Object value, int otherwise) {
            if (value instanceof Integer) return (Integer) value;
            if (value instanceof Long) {
                return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, (Long) value));
            }
            return otherwise;
        }

        private static boolean bool(Object value) {
            return Boolean.TRUE.equals(value);
        }
    }

    /** The release whose migration this is, as its notice and status say. */
    private static final String RELEASE = "0.5.0";

    /**
     * What the 0.5.0 migration changed, as the JSON {@link Migration#noticeJson} describes, until
     * {@link #dismissModelNotice}; null when there is nothing to tell. It survives the homepage being recreated.
     */
    static String peekModelNotice(Context context) {
        SharedPreferences prefs = prefs(context);
        migrateToModel2(context, prefs);
        return prefs.getString(MODEL_NOTICE, null);
    }

    /** The user answered the notice: it is not shown again. */
    static void dismissModelNotice(Context context) {
        SharedPreferences prefs = prefs(context);
        synchronized (FilterStore.class) {
            if (prefs.contains(MODEL_NOTICE)) prefs.edit().remove(MODEL_NOTICE).apply();
        }
    }

    // ---- The user's rules ----

    /**
     * Saves the user's rules: auto-decline on or off, the three money minimums and max stops, nothing else. Autopilot's
     * switch, goal and bar are never written here ({@link #setAutopilot}, {@link #commitAutopilotBar}), so this can
     * never put back a bar Autopilot has since moved. Values saved here are already model 2: a pending migration runs
     * first, so they are never folded or scaled again.
     */
    static void save(Context context, FilterSettings settings) {
        SharedPreferences prefs = prefs(context);
        migrateToModel2(context, prefs);
        synchronized (FilterStore.class) {
            prefs.edit().putBoolean(ENABLED, settings.enabled)
                    .putInt(FLAT, settings.flatCents)
                    .putInt(PER_MILE, settings.perMileCents)
                    .putInt(PER_MINUTE, settings.perMinuteCents)
                    .putInt(MAX_STOPS, settings.maxStops)
                    .apply();
        }
    }

    // ---- Autopilot's switch, goal and bar ----

    /**
     * Turns Autopilot on or off with {@code goal} (70, 50 or 0; anything else is 70), the user's choice; the goal now
     * counts as asked. Turning it off puts the bar back at exactly the minimums at once; turning it on from off starts
     * the bar there too, and with Autopilot already on (a new goal) the bar stays until Autopilot moves it.
     */
    static void setAutopilot(Context context, boolean on, int goal) {
        SharedPreferences prefs = prefs(context);
        migrateToModel2(context, prefs);
        synchronized (FilterStore.class) {
            boolean wasOn = prefs.getBoolean(AUTOPILOT_ON, false);
            SharedPreferences.Editor edit = prefs.edit().putBoolean(AUTOPILOT_ON, on)
                    .putInt(AUTOPILOT_GOAL, FilterSettings.sanitizedGoal(goal))
                    .putBoolean(AUTOPILOT_GOAL_ASKED, true);
            if (!on || !wasOn) edit.putInt(AUTOPILOT_BAR, FilterSettings.BAR_AT_MINIMUMS);
            edit.apply();
        }
    }

    /** Whether the goal chooser was answered (or Autopilot set) at least once. */
    static boolean goalAsked(Context context) {
        return prefs(context).getBoolean(AUTOPILOT_GOAL_ASKED, false);
    }

    static void setGoalAsked(Context context, boolean asked) {
        prefs(context).edit().putBoolean(AUTOPILOT_GOAL_ASKED, asked).apply();
    }

    /**
     * Autopilot moves the bar from {@code expected} to {@code next}: compare-and-set, under the same lock as every
     * other rules write, with one apply. Nothing happens (false) when Autopilot is off, the stored bar is no longer
     * {@code expected} (as {@link #load} reads it), or {@code next} is outside {@link #BAR_MIN}–{@link #BAR_MAX}. It
     * changes nothing else: no rule save, no replay, no rescan; the next read simply loads the new bar.
     */
    static boolean commitAutopilotBar(Context context, int expected, int next) {
        if (next < BAR_MIN || next > BAR_MAX) return false;
        SharedPreferences prefs = prefs(context);
        migrateToModel2(context, prefs);
        synchronized (FilterStore.class) {
            if (!prefs.getBoolean(AUTOPILOT_ON, false) || bar(prefs) != expected) return false;
            prefs.edit().putInt(AUTOPILOT_BAR, next).apply();
            return true;
        }
    }

    // ---- Retired learning: inert until its last callers are removed ----

    /** Retired with the adaptive minimum: what a decline by hand taught. Nothing is taught any more. */
    @Deprecated
    enum DeclineLesson {
        /** A floor rose. */
        TAUGHT,
        /** Auto-decline or the adaptive minimum was off: nothing is learned then. */
        SWITCHES_OFF,
        /** The minimums already asked more than that offer (or it looked misread): nothing rose. */
        NOTHING_NEW
    }

    /** Retired: declines by hand teach nothing in 0.5.0. Always {@link DeclineLesson#SWITCHES_OFF}; stores nothing. */
    @Deprecated
    static DeclineLesson learnFromDecline(Context context, OfferSnapshot declinedOffer) {
        return DeclineLesson.SWITCHES_OFF;
    }

    /** Retired with the adaptive minimum: what a confirmed manual acceptance changed. */
    @Deprecated
    enum AcceptedLesson {
        RAISED("at least one active minimum rose"),
        RECORDED("a new accepted best was saved; this offer’s current dollar requirements did not change"),
        NOTHING_NEW("no new accepted best; your existing minimums stay unchanged"),
        SWITCHES_OFF("auto-decline or Adaptive minimum was off"),
        PAY_UNKNOWN("its pay was not read");

        final String reason;
        AcceptedLesson(String reason) { this.reason = reason; }
        boolean considered() { return this != SWITCHES_OFF && this != PAY_UNKNOWN; }
    }

    /** Retired: acceptances teach nothing in 0.5.0. Always false; stores nothing. */
    @Deprecated
    static boolean recordAccepted(Context context, OfferSnapshot accepted) {
        return false;
    }

    /** Retired: acceptances teach nothing in 0.5.0. Always {@link AcceptedLesson#SWITCHES_OFF}; stores nothing. */
    @Deprecated
    static AcceptedLesson recordAcceptedLesson(Context context, OfferSnapshot accepted) {
        return AcceptedLesson.SWITCHES_OFF;
    }

    /** Retired: there are no learned minimums to reset. Does nothing. */
    @Deprecated
    static void resetAccepted(Context context) {
    }

    /** Retired: learning times are no longer kept (0.5.0 removed them). Always three zeros, "not recorded". */
    @Deprecated
    static long[] learningTimes(Context context) {
        return new long[] {0, 0, 0};
    }

    // ---- Dasher's offer channel, status and switches ----

    static void recordDoorDashOfferChannel(Context context, String channelId) {
        if (channelId == null || channelId.trim().isEmpty()) return;
        SharedPreferences prefs = prefs(context);
        if (!channelId.equals(prefs.getString(DOORDASH_OFFER_CHANNEL, ""))) {
            prefs.edit().putString(DOORDASH_OFFER_CHANNEL, channelId).remove(DOORDASH_POST_SOUNDED).apply();
        }
    }

    static String doorDashOfferChannel(Context context) {
        return prefs(context).getString(DOORDASH_OFFER_CHANNEL, "");
    }

    /**
     * Channel settings alone never create a mandatory Fix. A known Silent setting can clear a previously observed
     * alert; a channel Android does not describe changes nothing.
     */
    static void recordDoorDashChannel(Context context, android.app.NotificationChannel channel) {
        recordDoorDashChannel(context, channel, false);
    }

    /** A fresh post Android indicated sounded, under the same evidence rule used to avoid a second ring. */
    static void recordDoorDashChannel(Context context, android.app.NotificationChannel channel, boolean postSounded) {
        SharedPreferences prefs = prefs(context);
        boolean configuredToAlert = channel != null
                && channel.getImportance() >= android.app.NotificationManager.IMPORTANCE_DEFAULT
                && (channel.getSound() != null || channel.shouldVibrate());
        if (postSounded) {
            prefs.edit().putBoolean(DOORDASH_POST_SOUNDED, true).remove(DOORDASH_CHANNEL_ALERTS).apply();
        } else if (channel != null && !configuredToAlert) {
            prefs.edit().remove(DOORDASH_POST_SOUNDED).remove(DOORDASH_CHANNEL_ALERTS).apply();
        }
    }

    /**
     * Whether Android indicated an offer post sounded and its channel has not since been seen Silent. Unknown
     * ranking and replays cannot create this evidence; old channel-configuration-only flags are not evidence.
     */
    static boolean doorDashChannelAlerts(Context context) {
        return prefs(context).getBoolean(DOORDASH_POST_SOUNDED, false);
    }

    static void setLastStatus(Context context, String status) {
        prefs(context).edit().putString(LAST_STATUS, stamped(status)).apply();
    }

    private static String stamped(String status) {
        String timestamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date());
        return timestamp + "\n" + status;
    }

    static String lastStatus(Context context) {
        return prefs(context).getString(LAST_STATUS, "No offer evaluated yet.");
    }

    /** Removes the email address older versions kept, now that nothing uses it. */
    static void forgetRetiredEmail(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.contains(RETIRED_REPORT_EMAIL)) prefs.edit().remove(RETIRED_REPORT_EMAIL).apply();
    }

    /** Whether Dasher's own offer ring is turned down while a filtered offer is declined. On unless turned off. */
    static boolean silenceWhileDeclining(Context context) {
        return prefs(context).getBoolean(SILENCE_WHILE_DECLINING, true);
    }

    static void setSilenceWhileDeclining(Context context, boolean on) {
        prefs(context).edit().putBoolean(SILENCE_WHILE_DECLINING, on).apply();
    }

    /**
     * Whether Peek may bring Dasher up for a moment to read a background offer its notification cannot judge
     * ({@link Peek}). On unless turned off (the user's choice), so also on for an install that never stored it.
     */
    static boolean peek(Context context) {
        return prefs(context).getBoolean(PEEK, true);
    }

    static void setPeek(Context context, boolean on) {
        prefs(context).edit().putBoolean(PEEK, on).apply();
    }

    static boolean autoAcceptEnabled(Context context) {
        return prefs(context).getBoolean(AUTO_ACCEPT, false);
    }

    static void setAutoAcceptEnabled(Context context, boolean on) {
        prefs(context).edit().putBoolean(AUTO_ACCEPT, on).apply();
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private FilterStore() {}
}

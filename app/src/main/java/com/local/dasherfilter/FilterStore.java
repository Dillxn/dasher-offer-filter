package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Saved rules (0.5.0, rules model 2), Autopilot's switch, goal and bar, and the last user-visible status line.
 *
 * <p>Two writers share prefs "offer_filter". {@link #save} (the user's rules, main thread) writes only enabled, the
 * three money minimums and max stops, and, given the rules its change was made from, only what that change changed. The
 * bar is written only by {@link #commitAutopilotBar} (Autopilot, compare-and-set at a safe point on the scanner thread)
 * and reset to exactly 100 by {@link #setAutopilot} turning Autopilot off; {@link #save} never writes it, so a rule
 * saved from a stale copy can never restore an old bar. Autopilot's one other write, {@link #growMinimums} (0.5.1, the
 * minimums grow), is a compare-and-set of the minimums and the bar at that same safe point, which a change saved from a
 * copy made before it never undoes: such a save writes only the fields the user changed ({@link #save(Context,
 * FilterSettings, FilterSettings)}).
 *
 * <p>"Minimums since" ({@link #minimumsSince}) is when the current minimums took effect: set whenever the minimum pay,
 * per mile or per minute changes (the user's save, a growth, its Undo, the 0.5.0 migration), never by the bar.
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

    /**
     * Autopilot on: only Autopilot moves the bar, between offers. On by default from 0.6.2 ({@link #AUTOPILOT_SCHEMA});
     * off once the user turns it off.
     */
    private static final String AUTOPILOT_ON = "autopilot_on";
    /** Autopilot's acceptance-rate goal: 70, 50 or 0 (pay first). */
    private static final String AUTOPILOT_GOAL = "autopilot_goal";
    /** The goal chooser was shown and answered (or Autopilot was set) at least once. */
    private static final String AUTOPILOT_GOAL_ASKED = "autopilot_goal_asked";
    /**
     * The user picked the stored goal themselves (0.6.18): written when a choice in the goal chooser applies it
     * ({@link #setAutopilot} with Autopilot on), and by {@link #initializeGoalDefault} for a stored 50% or pay first,
     * which only the chooser could ever have set. Absent for the suggested goal, kept by Not now, Back or turning
     * Autopilot off. Before 0.6.18 nothing kept this, so a stored 70% could not be told from the suggested one.
     */
    private static final String AUTOPILOT_GOAL_CHOSEN = "autopilot_goal_chosen";
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
    /**
     * When the current minimums took effect (wall clock): written with every change of minimum pay, per mile or per
     * minute, never by the bar; an install from before 0.5.1 gets it at its first load (its older lines say nothing of
     * which minimums judged them).
     */
    private static final String MINIMUMS_SINCE = "minimums_since";
    /** "Let my minimums grow", one of Autopilot's settings: on unless turned off (0.5.0 installs too). */
    private static final String AUTOPILOT_GROW = "autopilot_grow";
    // Fresh restoration state. The model-2 migration's deleted evidence is never read into these keys.
    private static final String LEARNER_SCHEMA = "learner_v3_schema";
    /** 1: 0.6.0's fresh state, learning off; 2: learning on by default (0.6.1). */
    private static final int LEARNER_SCHEMA_NOW = 2;
    /**
     * Autopilot on by default (0.6.2; the owner, 12 October 2026): 1 once {@link #initializeAutopilot} has turned it
     * on, for a new install and, once, for an install updating from an earlier version. From then on the user's own
     * switch decides.
     */
    private static final String AUTOPILOT_SCHEMA = "autopilot_default_schema";
    private static final int AUTOPILOT_SCHEMA_NOW = 1;
    /**
     * Pay first is the default goal (0.6.18; the owner chose "Earnings first", 14 October 2026): 1 once
     * {@link #initializeGoalDefault} has moved a goal the user never chose to pay first, once only. From then on the
     * user's own choice decides.
     */
    static final String GOAL_DEFAULT_SCHEMA = "autopilot_goal_default_schema";
    private static final int GOAL_DEFAULT_SCHEMA_NOW = 1;
    /**
     * The pay minimum is pay per stop (0.6.12; the owner, 13 October 2026: "swap overall payout for pay per stop"): 1
     * once {@link #initializePayPerStop}'s first step has run (0.6.12's rule C, max(1, stops − 1), the saved number
     * unchanged); 2 once its second step has run (0.6.14; the owner, 14 October 2026: "i don't want the pay per stop to
     * not count the pickup"): every stop counts, the pickup included; the saved number is unchanged.
     */
    static final String PAY_PER_STOP_SCHEMA = "pay_per_stop_schema";
    private static final int PAY_PER_STOP_SCHEMA_RULE_C = 1;
    private static final int PAY_PER_STOP_SCHEMA_NOW = 2;
    /**
     * Test seam: whether {@link #initializeAutopilot} turns Autopilot on. On in the app. Tests written before 0.6.2 run
     * as an install whose user keeps Autopilot off (ConsentedTestApp turns this off); AutopilotDefaultTest turns it
     * back on to test the default itself.
     */
    static volatile boolean autopilotOnByDefault = true;
    static final String AREA_MODE = "area_mode_v3";
    static final String ACCEPTANCE_LEARNING = "acceptance_learning_v3";
    static final String ACCEPTED_PAY = "accepted_v3_pay";
    /** The pay best's stops, the pickup included ({@link AreaScore#payUnits}; 0.6.14); 1 when absent. */
    static final String ACCEPTED_PAY_UNITS = "accepted_v3_pay_units";
    static final String ACCEPTED_MILE_PAY = "accepted_v3_mile_pay";
    static final String ACCEPTED_MILES = "accepted_v3_miles";
    static final String ACCEPTED_MINUTE_PAY = "accepted_v3_minute_pay";
    static final String ACCEPTED_MINUTES = "accepted_v3_minutes";
    private static final String LEARNING_EPOCH = "learner_v3_epoch";
    private static final String MINIMA_REVISION = "learner_v3_minima_revision";
    private static final String ADOPTION = "learner_v3_adoption";
    /** The homepage's note of the last learned raise ({@link LearnedRaise}). */
    private static final String LEARNED_RAISE = "learner_v3_raise_note";

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
    /** The wall clock "minimums since" is stamped with (tests set their own). */
    static volatile LongSupplier wallClock = System::currentTimeMillis;

    static FilterSettings load(Context context) {
        SharedPreferences prefs = prefs(context);
        retireExtraStopFee(context, prefs);
        migrateToModel2(context, prefs);
        initializePayPerStop(context, prefs);
        initializeAutopilot(context, prefs);
        initializeLearning(prefs);
        stampMinimumsSince(prefs);
        return read(prefs);
    }

    /**
     * Pay per stop's first step (0.6.12's rule C, max(1, stops − 1)), once: the saved pay minimum keeps its number and
     * becomes a per-stop minimum. A stored learned pay best counts as one unit. Always followed at once by
     * {@link #initializePickupCounted}, which makes every stop count (0.6.14). With a money
     * minimum or a learned pay best, "minimums since" moves to now: lines judged by the minimum pay say nothing about
     * pay per stop ({@link Growth}). Adoption, the learned raise's note, growth records, auto-accept, auto-decline and
     * Autopilot stay as they are. One fixed line in the diagnostic log, outside the lock; never Autopilot's runtime
     * (whose lock is taken before this class's).
     */
    private static void initializeRuleC(Context context, SharedPreferences prefs) {
        if (prefs.getInt(PAY_PER_STOP_SCHEMA, 0) >= PAY_PER_STOP_SCHEMA_RULE_C) return;
        int flat;
        boolean stored = false;
        synchronized (FilterStore.class) {
            if (prefs.getInt(PAY_PER_STOP_SCHEMA, 0) >= PAY_PER_STOP_SCHEMA_RULE_C) return;
            for (String key : RULE_KEYS) stored |= prefs.contains(key);
            SharedPreferences.Editor edit = prefs.edit().putInt(PAY_PER_STOP_SCHEMA, PAY_PER_STOP_SCHEMA_RULE_C);
            flat = prefs.getInt(FLAT, 0);
            int learnedPay = prefs.getInt(ACCEPTED_PAY, 0);
            if (learnedPay > 0 && !prefs.contains(ACCEPTED_PAY_UNITS)) edit.putInt(ACCEPTED_PAY_UNITS, 1);
            if (flat > 0 || prefs.getInt(PER_MILE, 0) > 0 || prefs.getInt(PER_MINUTE, 0) > 0 || learnedPay > 0) {
                policyChangedSince(prefs, edit);
            }
            edit.commit();
        }
        // A new install has nothing to reinterpret: no line.
        if (stored && context != null) DiagnosticLog.log(context, "rules", payPerStopLog(flat));
    }

    /**
     * Pay per stop (0.6.12, the pickup counted from 0.6.14), once each, in order: rule C's step, then the pickup's.
     */
    private static void initializePayPerStop(Context context, SharedPreferences prefs) {
        initializeRuleC(context, prefs);
        initializePickupCounted(context, prefs);
    }

    /**
     * Rule C's one line: "pay per stop: minimum pay $13.00 is now a per-stop minimum". From 0.6.14 the pickup step's
     * line ({@link #PICKUP_COUNTED_LOG}) always follows it and says how stops are counted.
     */
    static String payPerStopLog(int flatCents) {
        return "pay per stop: " + (flatCents > 0 ? "minimum pay " + DecisionLog.money(flatCents)
                + " is now a per-stop minimum" : "no pay minimum set");
    }

    /**
     * Pay per stop counts the pickup (0.6.14), once, after rule C's step: every stop Dasher shows counts, the pickup
     * included, so a single order (Dasher's two stops) asks the number twice (the owner, 14 October 2026: "if I say I
     * want $5 per stop then a single pickup and single dropoff dash would require $10"). The saved number is untouched.
     * A stored learned pay best's units become units + 1 (its stop count rises by one under the new count, so its floor
     * stays the accepted pay ÷ that offer's own stops + 1¢), in the learned raise's note too, so the note still matches.
     * With a money minimum or a learned pay best, "minimums since" moves to now ({@link Growth}). One fixed line in the
     * diagnostic log, outside the lock, none on a new install; never Autopilot's runtime.
     */
    private static void initializePickupCounted(Context context, SharedPreferences prefs) {
        if (prefs.getInt(PAY_PER_STOP_SCHEMA, 0) >= PAY_PER_STOP_SCHEMA_NOW) return;
        boolean stored = false;
        synchronized (FilterStore.class) {
            if (prefs.getInt(PAY_PER_STOP_SCHEMA, 0) >= PAY_PER_STOP_SCHEMA_NOW) return;
            for (String key : RULE_KEYS) stored |= prefs.contains(key);
            SharedPreferences.Editor edit = prefs.edit().putInt(PAY_PER_STOP_SCHEMA, PAY_PER_STOP_SCHEMA_NOW);
            int flat = prefs.getInt(FLAT, 0);
            int learnedPay = prefs.getInt(ACCEPTED_PAY, 0);
            if (learnedPay > 0) {
                edit.putInt(ACCEPTED_PAY_UNITS, Math.max(1, prefs.getInt(ACCEPTED_PAY_UNITS, 1)) + 1);
                String note = pickupCountedRaise(prefs.getString(LEARNED_RAISE, null));
                if (note != null) edit.putString(LEARNED_RAISE, note);
                else edit.remove(LEARNED_RAISE);
            }
            if (flat > 0 || prefs.getInt(PER_MILE, 0) > 0 || prefs.getInt(PER_MINUTE, 0) > 0 || learnedPay > 0) {
                policyChangedSince(prefs, edit);
            }
            edit.commit();
        }
        if (stored && context != null) DiagnosticLog.log(context, "rules", PICKUP_COUNTED_LOG);
    }

    /** The learned raise's note with its pay best's units + 1, or null when there is no note or it is unreadable. */
    private static String pickupCountedRaise(String raw) {
        if (raw == null) return null;
        try {
            JSONObject json = new JSONObject(raw);
            if (json.optInt("pay", 0) > 0) json.put("payUnits", Math.max(1, json.optInt("payUnits", 1)) + 1);
            return json.toString();
        } catch (JSONException malformed) { return null; }
    }

    /** The pickup step's one line (0.6.14). */
    static final String PICKUP_COUNTED_LOG = "pay per stop now counts every stop, the pickup included (number "
            + "unchanged, so a single order asks twice what it did)";

    /** "Minimums since" for an install that has none yet (one from before 0.5.1, or a new one): now. */
    private static void stampMinimumsSince(SharedPreferences prefs) {
        if (prefs.contains(MINIMUMS_SINCE)) return;
        synchronized (FilterStore.class) {
            if (!prefs.contains(MINIMUMS_SINCE)) prefs.edit().putLong(MINIMUMS_SINCE, wallClock.getAsLong()).apply();
        }
    }

    /**
     * When the current minimums took effect (wall clock): lines Autopilot decided before it say nothing about them
     * ({@link Growth}).
     */
    static long minimumsSince(Context context) {
        SharedPreferences prefs = prefs(context);
        migrateToModel2(context, prefs);
        initializePayPerStop(context, prefs);
        stampMinimumsSince(prefs);
        return prefs.getLong(MINIMUMS_SINCE, 0);
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
                FilterSettings.sanitizedGoal(prefs.getInt(AUTOPILOT_GOAL, FilterSettings.GOAL_DEFAULT)), bar,
                prefs.getBoolean(AREA_MODE, false), prefs.getBoolean(ACCEPTANCE_LEARNING, true), accepted(prefs));
    }

    /**
     * The learner's state: schema 1 (0.6.0) starts it empty and off; schema 2 (0.6.1, the owner, 11 October 2026:
     * learning "should be on by default") turns learning on, once, for 0.6.0 installs too, keeping their bests. From
     * then on the user's own switch decides.
     */
    private static void initializeLearning(SharedPreferences prefs) {
        synchronized (FilterStore.class) {
            int schema = prefs.getInt(LEARNER_SCHEMA, 0);
            if (schema >= LEARNER_SCHEMA_NOW) return;
            SharedPreferences.Editor edit = prefs.edit().putInt(LEARNER_SCHEMA, LEARNER_SCHEMA_NOW)
                    .putBoolean(ACCEPTANCE_LEARNING, true);
            if (schema < 1) {
                edit.putBoolean(AREA_MODE, false).putLong(LEARNING_EPOCH, 1).putLong(MINIMA_REVISION, 0)
                        .remove(ADOPTION);
                writeAccepted(edit, AcceptedFloors.NONE);
            } else {
                // Learning starts now: nothing seen while it was off may teach it.
                advanceEpoch(prefs, edit);
            }
            edit.apply();
        }
    }

    /**
     * Autopilot on by default (0.6.2; the owner, 12 October 2026: Autopilot "should be on by default"): a new install,
     * and once an install updating from an earlier version whatever it had, get Autopilot on with its stored goal (the
     * suggested one when none was chosen: 70% then, pay first from 0.6.18, {@link #initializeGoalDefault}) and the bar
     * at exactly the minimums. Nothing else changes: not the
     * minimums, "minimums since", "Let my minimums grow" or whether the goal was asked (the goal chooser still asks
     * once, with Autopilot already on). It makes no jump: Autopilot moves the bar from 100% only by its own commits, at
     * its safe points, and plans nothing before the current notice is accepted. Runs after the model-2 migration
     * (which writes it off) and before every read of the switch. It writes prefs, forgets any goal state left from an
     * earlier time Autopilot was on, and logs one fixed line, never calling Autopilot's runtime (whose lock is taken
     * before this class's); the app was just installed or updated, so the runtime holds no plan of its own yet.
     */
    private static void initializeAutopilot(Context context, SharedPreferences prefs) {
        initializeAutopilotOn(context, prefs);
        initializeGoalDefault(context, prefs);
    }

    private static void initializeAutopilotOn(Context context, SharedPreferences prefs) {
        if (prefs.getInt(AUTOPILOT_SCHEMA, 0) >= AUTOPILOT_SCHEMA_NOW) return;
        boolean turnedOn = false;
        synchronized (FilterStore.class) {
            if (prefs.getInt(AUTOPILOT_SCHEMA, 0) >= AUTOPILOT_SCHEMA_NOW) return;
            SharedPreferences.Editor edit = prefs.edit().putInt(AUTOPILOT_SCHEMA, AUTOPILOT_SCHEMA_NOW);
            if (autopilotOnByDefault && !prefs.getBoolean(AUTOPILOT_ON, false)) {
                edit.putBoolean(AUTOPILOT_ON, true).putInt(AUTOPILOT_BAR, FilterSettings.BAR_AT_MINIMUMS)
                        .putInt(AUTOPILOT_GOAL, FilterSettings.sanitizedGoal(
                                prefs.getInt(AUTOPILOT_GOAL, FilterSettings.GOAL_DEFAULT)));
                turnedOn = true;
            }
            edit.commit();
        }
        if (!turnedOn || context == null) return;
        // As turning it on does: no recovery or stall correction left from an earlier time it was on (outside this
        // class's lock; Autopilot's store takes no other).
        AutopilotStore.resetGoalState(context);
        DiagnosticLog.log(context, "autopilot", AutopilotText.LOG_ON_BY_DEFAULT);
    }

    /**
     * Pay first is the default goal (0.6.18; the owner, 14 October 2026: "no reason auto pilot should have dropped to
     * %50 when the latest order is above that... it's goal is to arm wrestle with the dispatch algorithm to ratchet up
     * earnings", then "Earnings first"). Once only, after the model-2 migration and the Autopilot default (which write a
     * goal where none is stored): a goal the user picked stays; any other becomes pay first.
     * <p>Which goal the user picked (best judgment, as nothing before 0.6.18 kept it): one marked chosen
     * ({@link #AUTOPILOT_GOAL_CHOSEN}), and a stored 50% or pay first, which only the goal chooser could ever set (they
     * are marked chosen now; a new install's pay first is written with this step done, by the model-2 migration, and is
     * not). A stored 70% is the suggested goal: 0.5.0's migration wrote it into every install, 0.6.2
     * turned Autopilot on with it, the chooser came with it checked and Not now or Back kept it ("asked" is set by those
     * as much as by a pick, and by turning Autopilot off), so a 70% the user tapped cannot be told from one they only
     * kept; it moves to pay first, which the owner's decision is for, and notice 26 says so (one tap in the chooser
     * brings 70% back, and from then on it is marked chosen). A new install simply has pay first.
     * <p>The goal-relative state (recovering, the stall correction) goes with the old goal; the accepted floor stays (it
     * restarts only at the user's own change) and Autopilot's runtime is never called (its lock is taken before this
     * class's): the stored goal is in the rules key, so a plan for the old goal is stale. One fixed line in the log when
     * a goal moved.
     */
    private static void initializeGoalDefault(Context context, SharedPreferences prefs) {
        if (prefs.getInt(GOAL_DEFAULT_SCHEMA, 0) >= GOAL_DEFAULT_SCHEMA_NOW) return;
        int moved = -1;
        synchronized (FilterStore.class) {
            if (prefs.getInt(GOAL_DEFAULT_SCHEMA, 0) >= GOAL_DEFAULT_SCHEMA_NOW) return;
            SharedPreferences.Editor edit = prefs.edit().putInt(GOAL_DEFAULT_SCHEMA, GOAL_DEFAULT_SCHEMA_NOW);
            boolean stored = prefs.contains(AUTOPILOT_GOAL);
            int goal = stored ? prefs.getInt(AUTOPILOT_GOAL, FilterSettings.GOAL_DEFAULT) : FilterSettings.GOAL_DEFAULT;
            if (prefs.getBoolean(AUTOPILOT_GOAL_CHOSEN, false)) {
                // The user's pick stands.
            } else if (stored && (goal == FilterSettings.GOAL_TIER || goal == FilterSettings.GOAL_PAY_FIRST)) {
                edit.putBoolean(AUTOPILOT_GOAL_CHOSEN, true);
            } else {
                edit.putInt(AUTOPILOT_GOAL, FilterSettings.GOAL_DEFAULT);
                int was = FilterSettings.sanitizedGoal(goal);
                if (stored && was != FilterSettings.GOAL_DEFAULT) moved = was;
            }
            edit.commit();
        }
        if (moved < 0 || context == null) return;
        // As a change of goal does: no recovery or stall correction toward the old goal (outside this class's lock).
        AutopilotStore.resetGoalState(context);
        DiagnosticLog.log(context, "autopilot", AutopilotText.logGoalDefault(moved));
    }

    /** Whether the user picked the stored goal themselves (0.6.18, {@link #AUTOPILOT_GOAL_CHOSEN}). */
    static boolean goalChosen(Context context) {
        SharedPreferences prefs = prefs(context);
        migrateToModel2(context, prefs);
        initializePayPerStop(context, prefs);
        initializeAutopilot(context, prefs);
        return prefs.getBoolean(AUTOPILOT_GOAL_CHOSEN, false);
    }

    private static AcceptedFloors accepted(SharedPreferences prefs) {
        if (prefs.getInt(LEARNER_SCHEMA, 0) < 1) return AcceptedFloors.NONE;
        return new AcceptedFloors(prefs.getInt(ACCEPTED_PAY, 0), prefs.getInt(ACCEPTED_PAY_UNITS, 1),
                prefs.getInt(ACCEPTED_MILE_PAY, 0),
                Double.longBitsToDouble(prefs.getLong(ACCEPTED_MILES, 0)),
                prefs.getInt(ACCEPTED_MINUTE_PAY, 0), prefs.getInt(ACCEPTED_MINUTES, 0));
    }

    private static void writeAccepted(SharedPreferences.Editor edit, AcceptedFloors accepted) {
        edit.putInt(ACCEPTED_PAY, accepted.highestPayCents).putInt(ACCEPTED_PAY_UNITS, accepted.payUnits)
                .putInt(ACCEPTED_MILE_PAY, accepted.milePay)
                .putLong(ACCEPTED_MILES, Double.doubleToLongBits(accepted.miles))
                .putInt(ACCEPTED_MINUTE_PAY, accepted.minutePay).putInt(ACCEPTED_MINUTES, accepted.minutes);
    }

    private static long advanceEpoch(SharedPreferences prefs, SharedPreferences.Editor edit) {
        long next = prefs.getLong(LEARNING_EPOCH, 0) + 1;
        edit.putLong(LEARNING_EPOCH, next);
        return next;
    }

    private static long minimaChanged(SharedPreferences prefs, SharedPreferences.Editor edit) {
        long next = minimaRevised(prefs, edit);
        advanceEpoch(prefs, edit);
        return next;
    }

    /**
     * The user's own knob or badge: the revision moves (so an older Adopt can no longer be undone over it), but an
     * offer on screen can still teach. Its lesson is that offer's own figures, whatever the minimums now say (the
     * owner's 11 October report: a knob stepped over a $14.40 offer he then accepted, and it taught nothing).
     */
    private static long minimaRevised(SharedPreferences prefs, SharedPreferences.Editor edit) {
        long next = prefs.getLong(MINIMA_REVISION, 0) + 1;
        edit.putLong(MINIMA_REVISION, next).remove(ADOPTION);
        return next;
    }

    private static void policyChangedSince(SharedPreferences prefs, SharedPreferences.Editor edit) {
        edit.putLong(MINIMUMS_SINCE, Math.max(wallClock.getAsLong(), prefs.getLong(MINIMUMS_SINCE, 0) + 1));
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
     *   <li>Autopilot starts off, goal 70, bar 100 (each only if absent); from 0.6.2 {@link #initializeAutopilot}
     *       then turns it on, once.</li>
     *   <li>An install that had rules gets one "rules" log line, and a notice ({@link #peekModelNotice}) and status
     *       when the meaning of its rules changed; its minimums take effect now ({@link #minimumsSince}).</li>
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
            if (!done.fresh) edit.putLong(MINIMUMS_SINCE, wallClock.getAsLong());
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
        /** The pay-per-stop step already stored (a downgrade and upgrade): never moved back. */
        private final int storedPayPerStop;

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
            storedPayPerStop = integer(stored.get(PAY_PER_STOP_SCHEMA), 0);
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
            // A goal written here is the default (pay first from 0.6.18), never the user's pick: nothing for the goal
            // step to move or keep.
            if (!storedGoal) {
                edit.putInt(AUTOPILOT_GOAL, FilterSettings.GOAL_DEFAULT)
                        .putInt(GOAL_DEFAULT_SCHEMA, GOAL_DEFAULT_SCHEMA_NOW);
            }
            if (!storedBar) edit.putInt(AUTOPILOT_BAR, FilterSettings.BAR_AT_MINIMUMS);
            edit.putInt(RULES_MODEL, MODEL);
            // Rules moved to model 2 here are judged per stop from the start (0.6.12): their notice and line say what
            // changed, and the minimums' time is stamped with them; rule C's step has nothing left to say. The pickup
            // step (0.6.14) still runs after and says that every stop now counts.
            // A phone that went back to an older version and forward again keeps the step it had: the pickup step
            // runs once only.
            edit.putInt(PAY_PER_STOP_SCHEMA, Math.max(storedPayPerStop, PAY_PER_STOP_SCHEMA_RULE_C));
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
        initializePayPerStop(context, prefs);
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
     * first, so they are never folded or scaled again. A change of minimum pay, per mile or per minute moves "minimums
     * since" ({@link #minimumsSince}).
     */
    static void save(Context context, FilterSettings settings) {
        save(context, null, settings);
    }

    /**
     * Saves the user's change from {@code base} (the rules as loaded when the change was made) to {@code next}: only
     * the fields it changed are written, so a change made from a copy loaded before Autopilot grew the minimums
     * ({@link #growMinimums}) never puts the old ones back (pausing writes only the switch; one knob only its own
     * minimum). A null {@code base} writes every field, as {@link #save(Context, FilterSettings)} does.
     */
    static void save(Context context, FilterSettings base, FilterSettings next) {
        SharedPreferences prefs = prefs(context);
        migrateToModel2(context, prefs);
        initializePayPerStop(context, prefs);
        initializeAutopilot(context, prefs);
        initializeLearning(prefs);
        synchronized (FilterStore.class) {
            FilterSettings stored = read(prefs);
            SharedPreferences.Editor edit = prefs.edit();
            if (base == null || next.enabled != base.enabled) edit.putBoolean(ENABLED, next.enabled);
            boolean learning = base == null || next.acceptanceLearning != base.acceptanceLearning
                    ? next.acceptanceLearning : stored.acceptanceLearning;
            boolean area = base == null || next.scoreByArea != base.scoreByArea ? next.scoreByArea : stored.scoreByArea;
            edit.putBoolean(ACCEPTANCE_LEARNING, learning).putBoolean(AREA_MODE, area);
            boolean enabled = base == null || next.enabled != base.enabled ? next.enabled : stored.enabled;
            if (enabled != stored.enabled || learning != stored.acceptanceLearning || area != stored.scoreByArea) {
                advanceEpoch(prefs, edit);
            }
            if (learning != stored.acceptanceLearning || area != stored.scoreByArea) {
                policyChangedSince(prefs, edit);
            }
            int flat = base == null || next.flatCents != base.flatCents ? next.flatCents : stored.flatCents;
            int mile = base == null || next.perMileCents != base.perMileCents ? next.perMileCents : stored.perMileCents;
            int minute = base == null || next.perMinuteCents != base.perMinuteCents ? next.perMinuteCents
                    : stored.perMinuteCents;
            edit.putInt(FLAT, flat).putInt(PER_MILE, mile).putInt(PER_MINUTE, minute);
            if (base == null || next.maxStops != base.maxStops) edit.putInt(MAX_STOPS, next.maxStops);
            if (flat != stored.flatCents || mile != stored.perMileCents || minute != stored.perMinuteCents
                    || !prefs.contains(MINIMUMS_SINCE)) {
                edit.putLong(MINIMUMS_SINCE, wallClock.getAsLong());
                minimaRevised(prefs, edit);
            }
            edit.apply();
        }
    }

    // ---- Autopilot's switch, goal and bar ----

    /**
     * Turns Autopilot on or off with {@code goal} (70, 50 or 0; anything else is pay first), the user's choice; the goal
     * now counts as asked. Turning it on (only a pick in the goal chooser does) also marks the goal chosen (0.6.18);
     * turning it off keeps whatever was. Turning it off puts the bar back at exactly the minimums at once; turning it on
     * from off starts the bar there too, and with Autopilot already on (a new goal) the bar stays until Autopilot moves
     * it.
     */
    static void setAutopilot(Context context, boolean on, int goal) {
        SharedPreferences prefs = prefs(context);
        migrateToModel2(context, prefs);
        initializePayPerStop(context, prefs);
        // The default first, so a choice made before the rules were ever loaded is the one that stands.
        initializeAutopilot(context, prefs);
        synchronized (FilterStore.class) {
            boolean wasOn = prefs.getBoolean(AUTOPILOT_ON, false);
            SharedPreferences.Editor edit = prefs.edit().putBoolean(AUTOPILOT_ON, on)
                    .putInt(AUTOPILOT_GOAL, FilterSettings.sanitizedGoal(goal))
                    .putBoolean(AUTOPILOT_GOAL_ASKED, true);
            if (on) edit.putBoolean(AUTOPILOT_GOAL_CHOSEN, true);
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
        initializePayPerStop(context, prefs);
        initializeAutopilot(context, prefs);
        synchronized (FilterStore.class) {
            if (!prefs.getBoolean(AUTOPILOT_ON, false) || bar(prefs) != expected) return false;
            prefs.edit().putInt(AUTOPILOT_BAR, next).apply();
            return true;
        }
    }

    // ---- The minimums grow (0.5.1) ----

    /**
     * "Let my minimums grow", kept with Autopilot's settings (its switch is in Settings from 0.6.21): on unless turned
     * off, so on for 0.5.0 installs too.
     */
    static boolean minimumsGrow(Context context) {
        return prefs(context).getBoolean(AUTOPILOT_GROW, true);
    }

    static void setMinimumsGrow(Context context, boolean on) {
        synchronized (FilterStore.class) {
            prefs(context).edit().putBoolean(AUTOPILOT_GROW, on).apply();
        }
    }

    /**
     * The minimums grow ({@link Growth}): compare-and-set, under the same lock as every other rules write, with one
     * apply. Nothing happens (false) unless "Let my minimums grow" is on (Autopilot on or off, 0.6.21), and the stored
     * minimums, bar and "minimums since" are exactly those {@code evidence} was worked out for. Then every set money
     * minimum and the bar become {@code evidence}'s grown ones and the minimums take effect at {@code wall}; max stops,
     * auto-decline and auto-accept never change.
     */
    static boolean growMinimums(Context context, Growth.Evidence evidence, long wall) {
        if (evidence == null) return false;
        Growth.Grown grown = evidence.grown;
        if (grown.barAfter < BAR_MIN || grown.barAfter > BAR_MAX) return false;
        SharedPreferences prefs = prefs(context);
        migrateToModel2(context, prefs);
        initializePayPerStop(context, prefs);
        initializeAutopilot(context, prefs);
        synchronized (FilterStore.class) {
            // Autopilot on or off (0.6.21): only the switch.
            if (!prefs.getBoolean(AUTOPILOT_GROW, true)) return false;
            FilterSettings stored = read(prefs);
            if (stored.flatCents != grown.flatBefore || stored.perMileCents != grown.mileBefore
                    || stored.perMinuteCents != grown.minuteBefore || bar(prefs) != grown.barBefore
                    || !prefs.contains(MINIMUMS_SINCE) || prefs.getLong(MINIMUMS_SINCE, 0) != evidence.since) {
                return false;
            }
            SharedPreferences.Editor edit = prefs.edit().putInt(FLAT, grown.flatAfter).putInt(PER_MILE, grown.mileAfter)
                    .putInt(PER_MINUTE, grown.minuteAfter).putInt(AUTOPILOT_BAR, grown.barAfter)
                    .putLong(MINIMUMS_SINCE, wall);
            minimaChanged(prefs, edit);
            edit.apply();
            return true;
        }
    }

    /**
     * Undo of a growth: the minimums from before it come back, by compare-and-set, only while they are still exactly
     * the grown ones, in effect since the growth ({@code grewAt}). It counts as the user's change: the minimums take
     * effect at {@code wall}. The bar is Autopilot's: it is not written here.
     */
    static boolean undoGrowth(Context context, Growth.Grown grown, long grewAt, long wall) {
        if (grown == null) return false;
        SharedPreferences prefs = prefs(context);
        migrateToModel2(context, prefs);
        initializePayPerStop(context, prefs);
        synchronized (FilterStore.class) {
            FilterSettings stored = read(prefs);
            if (stored.flatCents != grown.flatAfter || stored.perMileCents != grown.mileAfter
                    || stored.perMinuteCents != grown.minuteAfter || prefs.getLong(MINIMUMS_SINCE, 0) != grewAt) {
                return false;
            }
            SharedPreferences.Editor edit = prefs.edit().putInt(FLAT, grown.flatBefore).putInt(PER_MILE, grown.mileBefore)
                    .putInt(PER_MINUTE, grown.minuteBefore).putLong(MINIMUMS_SINCE, wall);
            minimaChanged(prefs, edit);
            edit.apply();
            return true;
        }
    }

    // ---- Confirmed manual acceptance learning (fresh 0.6.0 state) ----

    static void setScoreByArea(Context context, boolean on) {
        FilterSettings before = load(context);
        save(context, before, before.withScoreByArea(on));
        if (before.scoreByArea != on) AutopilotRuntime.rulesChanged(context);
    }

    static void setAcceptanceLearning(Context context, boolean on) {
        FilterSettings before = load(context);
        save(context, before, before.withAcceptanceLearning(on));
        if (before.acceptanceLearning != on) AutopilotRuntime.rulesChanged(context);
    }

    /** Ephemeral, single-use authority to learn from one observed offer; never persisted or reconstructed. */
    static final class LearningPermit {
        final long epoch, consentAt, observedAt;
        final int consentVersion;
        final OfferSnapshot observed;
        private LearningPermit(long epoch, int consentVersion, long consentAt, OfferSnapshot observed) {
            this.epoch = epoch; this.consentVersion = consentVersion; this.consentAt = consentAt;
            this.observed = observed; this.observedAt = SystemClock.elapsedRealtime();
        }
    }

    /** Called at an offer's first observation, not when its eventual acceptance arrives. */
    static LearningPermit captureLearning(Context context, OfferSnapshot observed) {
        if (observed == null) return null;
        load(context);
        synchronized (FilterStore.class) {
            SharedPreferences prefs = prefs(context);
            FilterSettings rules = read(prefs);
            if (!Consent.accepted(context) || !rules.enabled || !rules.acceptanceLearning) return null;
            SharedPreferences consent = context.getSharedPreferences(Consent.PREFS, Context.MODE_PRIVATE);
            return new LearningPermit(prefs.getLong(LEARNING_EPOCH, 0),
                    consent.getInt(Consent.ACCEPTED_VERSION, 0), consent.getLong(Consent.ACCEPTED_AT, 0), observed);
        }
    }

    enum AcceptedLesson { RAISED, SAVED, UNCHANGED, OFF, STALE, UNKNOWN }

    /** Only the existing outcome detector's confirmed manual standalone branch may consume this permit. */
    static AcceptedLesson recordConfirmedManualAcceptance(Context context, LearningPermit permit, OfferSnapshot offer) {
        AcceptedLesson result;
        synchronized (FilterStore.class) {
            SharedPreferences prefs = prefs(context);
            FilterSettings before = read(prefs);
            if (!Consent.accepted(context) || !before.enabled || !before.acceptanceLearning) return AcceptedLesson.OFF;
            SharedPreferences consent = context.getSharedPreferences(Consent.PREFS, Context.MODE_PRIVATE);
            long elapsed = SystemClock.elapsedRealtime();
            if (permit == null || permit.epoch != prefs.getLong(LEARNING_EPOCH, 0)
                    || permit.consentVersion != consent.getInt(Consent.ACCEPTED_VERSION, 0)
                    || permit.consentAt != consent.getLong(Consent.ACCEPTED_AT, 0)
                    || elapsed < permit.observedAt || elapsed - permit.observedAt > 5 * 60_000L
                    || offer == null || offer.contradicts(permit.observed)) return AcceptedLesson.STALE;
            SharedPreferences.Editor edit = prefs.edit();
            // Consumed even if no best changes: duplicate outcomes cannot replay it.
            long lesson = advanceEpoch(prefs, edit);
            if (offer.payCents == null || offer.payCents <= 0) {
                edit.apply();
                return AcceptedLesson.UNKNOWN;
            }
            AcceptedFloors next = before.accepted.raisedBy(offer);
            result = next.equals(before.accepted) ? AcceptedLesson.UNCHANGED
                    : AreaScore.effectiveFloorsRaised(before, before.withAccepted(next), offer)
                    ? AcceptedLesson.RAISED : AcceptedLesson.SAVED;
            if (result != AcceptedLesson.UNCHANGED) {
                writeAccepted(edit, next);
                policyChangedSince(prefs, edit);
            }
            // A new high raised the learned minimums in use: the homepage notes it (with Adopt). The saved minimums
            // stay as the user set them (the owner, 12 October 2026).
            if (result == AcceptedLesson.RAISED) edit.putString(LEARNED_RAISE, LearnedRaise.encode(lesson, next));
            edit.apply();
        }
        // A lesson's jump is its own, not the user's: it keeps Autopilot's accepted floor and recovery state (0.6.11).
        if (result == AcceptedLesson.RAISED || result == AcceptedLesson.SAVED) AutopilotRuntime.learned(context);
        return result;
    }

    /** A consent transition invalidates pending evidence while preserving already stored bests. */
    static void invalidateLearningEvidence(Context context) {
        SharedPreferences prefs = prefs(context);
        initializeLearning(prefs);
        synchronized (FilterStore.class) {
            SharedPreferences.Editor edit = prefs.edit();
            advanceEpoch(prefs, edit);
            edit.apply();
        }
    }

    /** Caller confirms Reset. Clear history uses the same derived-data boundary; adopted settings stay. */
    static void resetAccepted(Context context) {
        clearAccepted(context);
        AutopilotRuntime.rulesChanged(context);
    }

    private static void clearAccepted(Context context) {
        SharedPreferences prefs = prefs(context);
        initializeLearning(prefs);
        synchronized (FilterStore.class) {
            SharedPreferences.Editor edit = prefs.edit().remove(ADOPTION).remove(LEARNED_RAISE);
            policyChangedSince(prefs, edit);
            writeAccepted(edit, AcceptedFloors.NONE);
            advanceEpoch(prefs, edit);
            edit.apply();
        }
    }

    // DecisionLog.clear owns the accompanying Autopilot history invalidation; avoid calling back under its lock.
    static void clearLearningHistory(Context context) { clearAccepted(context); }

    static final class Adoption {
        final long id, at, revision;
        final int flatBefore, mileBefore, minuteBefore, flatAfter, mileAfter, minuteAfter;
        Adoption(long id, long at, long revision, FilterSettings before, int flat, int mile, int minute) {
            this.id = id; this.at = at; this.revision = revision;
            flatBefore = before.flatCents; mileBefore = before.perMileCents; minuteBefore = before.perMinuteCents;
            flatAfter = flat; mileAfter = mile; minuteAfter = minute;
        }
        private Adoption(JSONObject json) throws JSONException {
            id = json.getLong("id"); at = json.getLong("at"); revision = json.getLong("revision");
            flatBefore = json.getInt("flatBefore"); mileBefore = json.getInt("mileBefore");
            minuteBefore = json.getInt("minuteBefore"); flatAfter = json.getInt("flatAfter");
            mileAfter = json.getInt("mileAfter"); minuteAfter = json.getInt("minuteAfter");
        }
        String encode() {
            try {
                return new JSONObject().put("id", id).put("at", at).put("revision", revision)
                        .put("flatBefore", flatBefore).put("mileBefore", mileBefore).put("minuteBefore", minuteBefore)
                        .put("flatAfter", flatAfter).put("mileAfter", mileAfter).put("minuteAfter", minuteAfter).toString();
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
        boolean matches(SharedPreferences prefs, FilterSettings current) {
            return id > 0 && revision > 0 && validRange(flatBefore, flatAfter) && validRange(mileBefore, mileAfter)
                    && validRange(minuteBefore, minuteAfter) && revision == prefs.getLong(MINIMA_REVISION, 0)
                    && current.flatCents == flatAfter && current.perMileCents == mileAfter
                    && current.perMinuteCents == minuteAfter;
        }
        private static boolean validRange(int before, int after) {
            return before >= 0 && after >= before && after <= FilterSettings.MOST_CENTS;
        }
    }

    static boolean adoptAccepted(Context context) {
        load(context);
        synchronized (FilterStore.class) {
            SharedPreferences prefs = prefs(context);
            FilterSettings before = read(prefs);
            int[] asks = adoptedAsks(before.accepted);
            int flat = Math.max(before.flatCents, asks[0]);
            int mile = Math.max(before.perMileCents, asks[1]);
            int minute = Math.max(before.perMinuteCents, asks[2]);
            if (flat == before.flatCents && mile == before.perMileCents && minute == before.perMinuteCents) return false;
            long wall = wallClock.getAsLong();
            SharedPreferences.Editor edit = prefs.edit().putInt(FLAT, flat).putInt(PER_MILE, mile)
                    .putInt(PER_MINUTE, minute).putLong(MINIMUMS_SINCE, wall);
            long revision = minimaChanged(prefs, edit);
            Adoption adoption = new Adoption(prefs.getLong(LEARNING_EPOCH, 0) + 1, wall, revision,
                    before, flat, mile, minute);
            // The learned minimums are now the user's own: no learned raise is left to note.
            edit.putString(ADOPTION, adoption.encode()).remove(LEARNED_RAISE).apply();
        }
        // Adopt only raises the minimums: Autopilot's accepted floor stays (0.6.11).
        AutopilotRuntime.adopted(context);
        return true;
    }

    private static int adoptedRate(int pay, double amount) {
        return BigDecimal.valueOf(pay).divide(BigDecimal.valueOf(amount), 0, RoundingMode.CEILING)
                .min(BigDecimal.valueOf(FilterSettings.MOST_CENTS)).intValue();
    }

    static Adoption pendingAdoption(Context context) {
        load(context);
        synchronized (FilterStore.class) {
            SharedPreferences prefs = prefs(context);
            String raw = prefs.getString(ADOPTION, null);
            if (raw == null) return null;
            try {
                Adoption adoption = new Adoption(new JSONObject(raw));
                if (adoption.matches(prefs, read(prefs))) return adoption;
            } catch (JSONException malformed) { /* Corrupt data is never restore authority. */ }
            prefs.edit().remove(ADOPTION).apply();
            return null;
        }
    }

    static boolean undoAdoption(Context context, long id) {
        synchronized (FilterStore.class) {
            Adoption a = pendingAdoption(context);
            if (a == null || a.id != id) return false;
            SharedPreferences prefs = prefs(context);
            SharedPreferences.Editor edit = prefs.edit().putLong(MINIMUMS_SINCE, wallClock.getAsLong());
            if (a.flatBefore != a.flatAfter) edit.putInt(FLAT, a.flatBefore);
            if (a.mileBefore != a.mileAfter) edit.putInt(PER_MILE, a.mileBefore);
            if (a.minuteBefore != a.minuteAfter) edit.putInt(PER_MINUTE, a.minuteBefore);
            minimaChanged(prefs, edit);
            edit.apply();
        }
        AutopilotRuntime.rulesChanged(context);
        return true;
    }

    // ---- The homepage's note of a learned raise ----

    /**
     * The homepage's note of a learned raise (0.6.1; the owner, 11 October 2026): a new high the user accepted made
     * a learned minimum ask more than the saved one. Learning raises only the learned minimums, which apply on top of
     * the saved ones; the saved minimums never move by themselves ("this shouldn't happen ... we have the adopt mins
     * feature for that"). The note says what the learned minimums now ask above the saved ones, with Adopt and OK.
     * It shows only while the bests are still exactly the ones that lesson stored, learning is on and a learned
     * minimum still asks more than its saved one; OK, Adopt, a later lesson, Reset and Clear history end it.
     */
    static final class LearnedRaise {
        /** The lesson's learning generation, unique to it. */
        final long id;
        /** The learned bests that lesson stored. */
        final AcceptedFloors bests;
        /** What the learned bests ask in the knobs' own units (Adopt's arithmetic), or 0 on an axis where they ask no more than the saved minimum. */
        final int flat, mile, minute;
        /** The saved minimums then. */
        final int savedFlat, savedMile, savedMinute;

        private LearnedRaise(long id, AcceptedFloors bests, FilterSettings saved) {
            this.id = id;
            this.bests = bests;
            int[] asks = adoptedAsks(bests);
            savedFlat = saved.flatCents; savedMile = saved.perMileCents; savedMinute = saved.perMinuteCents;
            flat = asks[0] > savedFlat ? asks[0] : 0;
            mile = asks[1] > savedMile ? asks[1] : 0;
            minute = asks[2] > savedMinute ? asks[2] : 0;
        }

        /** Some learned minimum asks more than its saved one. */
        boolean above() {
            return flat > 0 || mile > 0 || minute > 0;
        }

        private static String encode(long id, AcceptedFloors bests) {
            try {
                return new JSONObject().put("id", id).put("pay", bests.highestPayCents)
                        .put("payUnits", bests.payUnits).put("milePay", bests.milePay)
                        .put("miles", bests.miles).put("minutePay", bests.minutePay).put("minutes", bests.minutes)
                        .toString();
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
    }

    /** The learned raise whose note waits, or null (and a note that can no longer show is forgotten). */
    static LearnedRaise learnedRaise(Context context) {
        load(context);
        synchronized (FilterStore.class) {
            SharedPreferences prefs = prefs(context);
            String raw = prefs.getString(LEARNED_RAISE, null);
            if (raw == null) return null;
            FilterSettings current = read(prefs);
            try {
                JSONObject json = new JSONObject(raw);
                AcceptedFloors bests = new AcceptedFloors(json.getInt("pay"), json.optInt("payUnits", 1),
                        json.getInt("milePay"),
                        json.getDouble("miles"), json.getInt("minutePay"), json.getInt("minutes"));
                if (bests.equals(current.accepted)) {
                    LearnedRaise raise = new LearnedRaise(json.getLong("id"), bests, current);
                    // Learning switched off hides it without forgetting it: the bests still stand.
                    if (!current.acceptanceLearning) return null;
                    if (raise.above()) return raise;
                }
            } catch (JSONException malformed) { /* A corrupt note is simply not shown. */ }
            prefs.edit().remove(LEARNED_RAISE).apply();
            return null;
        }
    }

    /** OK (or Adopt) on the note of the learned raise {@code id}: the note goes. */
    static void closeLearnedRaise(Context context, long id) {
        synchronized (FilterStore.class) {
            SharedPreferences prefs = prefs(context);
            String raw = prefs.getString(LEARNED_RAISE, null);
            if (raw == null) return;
            try {
                if (new JSONObject(raw).getLong("id") != id) return;
            } catch (JSONException malformed) { /* Removed below. */ }
            prefs.edit().remove(LEARNED_RAISE).apply();
        }
    }

    /**
     * What {@code bests} ask in the knobs' units, Adopt's arithmetic: {⌈(pay + 1¢) ÷ its stops, the pickup
     * included⌉, ⌈pay ÷ miles⌉, ⌈pay ÷ minutes⌉}.
     */
    private static int[] adoptedAsks(AcceptedFloors a) {
        return new int[] {
                (int) Math.min(FilterSettings.MOST_CENTS,
                        a.highestPayCents > 0 ? (a.highestPayCents + 1L + a.payUnits - 1) / a.payUnits : 0),
                a.hasPerMile() ? adoptedRate(a.milePay, a.miles) : 0,
                a.hasPerMinute() ? adoptedRate(a.minutePay, a.minutes) : 0};
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

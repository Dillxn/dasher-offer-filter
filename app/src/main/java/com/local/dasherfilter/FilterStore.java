package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.DateFormat;
import java.util.Date;
import java.util.function.BooleanSupplier;

/** Saved rules, the standalone payout baseline, and the last user-visible status line. */
final class FilterStore {
    private static final String PREFS = "offer_filter";
    private static final String ENABLED = "enabled";
    private static final String FLAT = "flat";
    private static final String PER_MILE = "mile";
    private static final String PER_MINUTE = "minute";
    /** Minimum pay per stop. */
    private static final String PER_STOP = "per_stop";
    private static final String PER_ITEM = "per_item";
    /** Fixed minimum reciprocal distance from the final stop to the nearest current Dasher hotspot. */
    private static final String HOTSPOT_PROXIMITY = "hotspot_proximity_hundredths";
    /** Common scale; absent on older installs means exactly the original rules. */
    private static final String MINIMUM_SCALE = "minimum_scale_percent";
    /**
     * Where older versions kept the extra-stop fee, added on top of the other minimums for each stop after two. Per
     * stop is now a minimum of its own, so the old fee is retired, never read as one: that would silently change
     * which offers are declined.
     */
    private static final String RETIRED_EXTRA_STOP_FEE = "stop";
    /** What the homepage says once when a set extra-stop fee was retired. */
    private static final String STOP_FEE_NOTICE = "stop_fee_notice";
    private static final String MAX_STOPS = "max_stops";
    private static final String RISING_OFFERS = "rising_offers";
    /** Score by area (the user's choice) rather than every minimum; off unless turned on. */
    private static final String SCORE_BY_AREA = "score_by_area";
    private static final String LAST_ACCEPTED = "last_accepted";
    private static final String BEST_MINUTE_PAY = "best_minute_pay";
    private static final String BEST_MINUTES = "best_minutes";
    private static final String BEST_MILE_PAY = "best_mile_pay";
    private static final String BEST_MILES = "best_miles_bits";
    private static final String BEST_STOP_PAY = "best_stop_pay";
    private static final String BEST_STOPS = "best_stops";
    private static final String BEST_ITEM_PAY = "best_item_pay";
    private static final String BEST_ITEMS = "best_items";
    private static final String DECLINED_PAY = "declined_pay";
    private static final String DECLINED_MINUTE_PAY = "declined_minute_pay";
    private static final String DECLINED_MINUTES = "declined_minutes";
    private static final String DECLINED_MILE_PAY = "declined_mile_pay";
    private static final String DECLINED_MILES = "declined_miles_bits";
    private static final String DECLINED_STOP_PAY = "declined_stop_pay";
    private static final String DECLINED_STOPS = "declined_stops";
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
    /** When learning last turned on and off, and when the adaptive minimums were last reset (wall clock). */
    private static final String LEARNING_ON_SINCE = "learning_on_since";
    private static final String LEARNING_OFF_AT = "learning_off_at";
    private static final String ADAPTIVE_RESET_AT = "adaptive_reset_at";

    /** Process-local invalidation only: no recommendation or action authority is restored after restart. */
    private static long rulesGeneration;

    static final class RuleSnapshot {
        final FilterSettings settings;
        final String key;
        final long generation;
        private RuleSnapshot(FilterSettings settings, long generation) {
            this.settings = settings;
            this.key = AutoAccept.rulesKey(settings);
            this.generation = generation;
        }
    }

    static synchronized RuleSnapshot snapshot(Context context) {
        return new RuleSnapshot(load(context), rulesGeneration);
    }

    /**
     * The optimizer changes only the scale, through the ordinary save path. Manual edits (even change-back),
     * learned floors and opt-in changes invalidate the captured rules. The last safety check runs under this lock.
     */
    static synchronized boolean saveScaleIfUnchanged(Context context, RuleSnapshot expected, int percent,
                                                     BooleanSupplier stillEligible) {
        FilterSettings current = load(context);
        if (expected == null || expected.generation != rulesGeneration
                || !expected.key.equals(AutoAccept.rulesKey(current)) || !current.enabled
                || percent < 1 || percent > 200 || percent == current.minimumScalePercent
                || Math.abs(percent - current.minimumScalePercent) > 5 || !stillEligible.getAsBoolean()) return false;
        // Reentrant platform callbacks must not let a manual edit be overwritten with the earlier settings object.
        current = load(context);
        if (expected.generation != rulesGeneration || !expected.key.equals(AutoAccept.rulesKey(current))) return false;
        save(context, current.withMinimumScalePercent(percent));
        return true;
    }

    static synchronized FilterSettings load(Context context) {
        SharedPreferences prefs = prefs(context);
        retireExtraStopFee(context, prefs);
        return read(prefs);
    }

    private static FilterSettings read(SharedPreferences prefs) {
        return new FilterSettings(prefs.getBoolean(ENABLED, false),
                prefs.getInt(FLAT, 0), prefs.getInt(PER_MILE, 0),
                prefs.getInt(PER_MINUTE, 0), prefs.getInt(PER_STOP, 0), prefs.getInt(MAX_STOPS, 0),
                prefs.getBoolean(RISING_OFFERS, false), prefs.getInt(LAST_ACCEPTED, 0), best(prefs), declined(prefs),
                prefs.getBoolean(SCORE_BY_AREA, false), prefs.getInt(HOTSPOT_PROXIMITY, 0),
                prefs.getInt(MINIMUM_SCALE, 100), prefs.getInt(PER_ITEM, 0));
    }

    /**
     * Removes an extra-stop fee saved by an older version, once. Per stop is left off (0) rather than taking the
     * fee's value, which meant something else. A fee that was set is noted in the diagnostic log and the status, and
     * the homepage says so once; if it was the only rule, auto-decline is paused in the same edit, so the page never
     * shows an active filter that filters nothing. A fee of 0 was never a rule and goes silently.
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
                String notice = "Your " + DecisionLog.money((Integer) saved) + " extra-stop fee was removed: Per stop "
                        + "is now a minimum. Set one if you want it."
                        + (paused ? " It was your only rule, so auto-decline is paused." : "");
                edit.putString(STOP_FEE_NOTICE, notice).putString(LAST_STATUS, stamped(notice));
            }
            rulesGeneration++;
            edit.apply();
        }
        if (!(saved instanceof Integer) || (Integer) saved <= 0) return;
        DiagnosticLog.log(context, "rules", "the old extra-stop fee of " + DecisionLog.money((Integer) saved)
                + " was retired; per stop is now a minimum like per mile and per minute, and starts off"
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

    /** The stored bests; anything that is not a positive, finite number reads as "none" rather than crashing a rule. */
    private static AcceptedBest best(SharedPreferences prefs) {
        double miles = Double.longBitsToDouble(prefs.getLong(BEST_MILES, 0));
        return new AcceptedBest(positive(prefs.getInt(BEST_MINUTE_PAY, 0)), positive(prefs.getInt(BEST_MINUTES, 0)),
                positive(prefs.getInt(BEST_MILE_PAY, 0)), Double.isFinite(miles) && miles > 0 ? miles : 0,
                positive(prefs.getInt(BEST_STOP_PAY, 0)), positive(prefs.getInt(BEST_STOPS, 0)),
                positive(prefs.getInt(BEST_ITEM_PAY, 0)), positive(prefs.getInt(BEST_ITEMS, 0)));
    }

    /** The decline floors, sanitized like the bests. */
    private static DeclinedFloor declined(SharedPreferences prefs) {
        double miles = Double.longBitsToDouble(prefs.getLong(DECLINED_MILES, 0));
        return new DeclinedFloor(positive(prefs.getInt(DECLINED_PAY, 0)), new AcceptedBest(
                positive(prefs.getInt(DECLINED_MINUTE_PAY, 0)), positive(prefs.getInt(DECLINED_MINUTES, 0)),
                positive(prefs.getInt(DECLINED_MILE_PAY, 0)), Double.isFinite(miles) && miles > 0 ? miles : 0,
                positive(prefs.getInt(DECLINED_STOP_PAY, 0)), positive(prefs.getInt(DECLINED_STOPS, 0))));
    }

    /** What a decline by hand taught. */
    enum DeclineLesson {
        /** A floor rose. */
        TAUGHT,
        /** Auto-decline or the adaptive minimum was off: nothing is learned then. */
        SWITCHES_OFF,
        /** The minimums already asked more than that offer (or it looked misread): nothing rose. */
        NOTHING_NEW
    }

    /**
     * An offer the user declined by hand, after the dash went on: the rule that came closest to catching it is
     * raised just past it. Learned only while auto-decline and the adaptive minimum are both on, like acceptances.
     */
    static synchronized DeclineLesson learnFromDecline(Context context, OfferSnapshot declinedOffer) {
        FilterSettings settings = load(context);
        if (!settings.enabled || !settings.risingOffers) return DeclineLesson.SWITCHES_OFF;
        DeclinedFloor floor = DeclinedFloor.raisedBy(settings, declinedOffer);
        if (floor == settings.declined) return DeclineLesson.NOTHING_NEW;
        // Area compensation or a percentage buffer can pass an offer below a fixed floor. Do not claim
        // a floor rose when the learned value still leaves this offer's effective shape unchanged. Keep the learned
        // values exactly as before (including their persistence if the user later changes the set minimum).
        FilterSettings learned = new FilterSettings(settings.enabled, settings.flatCents, settings.perMileCents,
                settings.perMinuteCents, settings.perStopCents, settings.maxStops, settings.risingOffers,
                settings.lastAcceptedCents, settings.best, floor, settings.scoreByArea, settings.hotspotProximityHundredths,
                settings.minimumScalePercent, settings.perItemCents);
        AreaScore.Floors before = AreaScore.floors(settings, declinedOffer);
        AreaScore.Floors after = AreaScore.floors(learned, declinedOffer);
        boolean changed = false;
        for (int axis = 0; axis < AreaScore.AXES; axis++) {
            if (before.active[axis] != after.active[axis]
                    || (before.cents[axis] == null) != (after.cents[axis] == null)
                    || (before.cents[axis] != null && after.cents[axis] != null
                        && before.cents[axis].compareTo(after.cents[axis]) != 0)) {
                changed = true;
                break;
            }
        }
        rulesGeneration++;
        prefs(context).edit()
                .putInt(DECLINED_PAY, floor.payCents)
                .putInt(DECLINED_MINUTE_PAY, floor.rates.minutePay).putInt(DECLINED_MINUTES, floor.rates.minutes)
                .putInt(DECLINED_MILE_PAY, floor.rates.milePay)
                .putLong(DECLINED_MILES, Double.doubleToLongBits(floor.rates.miles))
                .putInt(DECLINED_STOP_PAY, floor.rates.stopPay).putInt(DECLINED_STOPS, floor.rates.stops)
                .apply();
        return changed ? DeclineLesson.TAUGHT : DeclineLesson.NOTHING_NEW;
    }

    private static int positive(int value) {
        return Math.max(0, value);
    }

    /**
     * Saves rules, score by area among them. The accepted baselines are owned by {@link #recordAccepted} and are not
     * overwritten. When learning
     * (auto-decline and the adaptive minimum both on) starts or stops, the time is kept for a shared report.
     */
    static synchronized void save(Context context, FilterSettings settings) {
        SharedPreferences prefs = prefs(context);
        boolean before = prefs.getBoolean(ENABLED, false) && prefs.getBoolean(RISING_OFFERS, false);
        boolean after = settings.enabled && settings.risingOffers;
        SharedPreferences.Editor edit = prefs.edit();
        if (after && !before) edit.putLong(LEARNING_ON_SINCE, System.currentTimeMillis());
        if (before && !after) edit.putLong(LEARNING_OFF_AT, System.currentTimeMillis());
        rulesGeneration++;
        edit.putBoolean(ENABLED, settings.enabled)
                .putInt(FLAT, settings.flatCents)
                .putInt(PER_MILE, settings.perMileCents)
                .putInt(PER_MINUTE, settings.perMinuteCents)
                .putInt(PER_STOP, settings.perStopCents)
                .putInt(PER_ITEM, settings.perItemCents)
                .putInt(HOTSPOT_PROXIMITY, settings.hotspotProximityHundredths)
                .putInt(MINIMUM_SCALE, settings.minimumScalePercent)
                .putInt(MAX_STOPS, settings.maxStops)
                .putBoolean(RISING_OFFERS, settings.risingOffers)
                .putBoolean(SCORE_BY_AREA, settings.scoreByArea)
                .apply();
    }

    /**
     * An accepted standalone offer, only while auto-decline and the adaptive minimum are both on: a new highest
     * accepted pay, and a new best for any rate it beats. The adaptive minimums only ever rise; nothing lowers or
     * forgets them but Reset. Nothing is learned while either is off, so turning it on never applies highs gathered
     * meanwhile. (The stored key keeps its old name, "last accepted".)
     */
    /** What a confirmed manual acceptance changed, without changing the stored learning model. */
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

    /** Compatibility result: whether this acceptance could be considered, not whether a minimum rose. */
    static boolean recordAccepted(Context context, OfferSnapshot accepted) {
        return recordAcceptedLesson(context, accepted).considered();
    }

    static synchronized AcceptedLesson recordAcceptedLesson(Context context, OfferSnapshot accepted) {
        if (accepted.payCents == null) return AcceptedLesson.PAY_UNKNOWN;
        SharedPreferences prefs = prefs(context);
        boolean learning = prefs.getBoolean(ENABLED, false) && prefs.getBoolean(RISING_OFFERS, false);
        if (!learning) return AcceptedLesson.SWITCHES_OFF;
        FilterSettings before = load(context);
        AcceptedBest previous = best(prefs);
        AcceptedBest best = previous.raisedBy(accepted);
        int highest = Math.max(prefs.getInt(LAST_ACCEPTED, 0), accepted.payCents);
        boolean recorded = highest != before.lastAcceptedCents
                || best.minutePay != previous.minutePay || best.minutes != previous.minutes
                || best.milePay != previous.milePay || Double.compare(best.miles, previous.miles) != 0
                || best.stopPay != previous.stopPay || best.stops != previous.stops
                || best.itemPay != previous.itemPay || best.items != previous.items;
        if (recorded) rulesGeneration++;
        prefs.edit()
                .putInt(LAST_ACCEPTED, highest)
                .putInt(BEST_MINUTE_PAY, best.minutePay).putInt(BEST_MINUTES, best.minutes)
                .putInt(BEST_MILE_PAY, best.milePay).putLong(BEST_MILES, Double.doubleToLongBits(best.miles))
                .putInt(BEST_STOP_PAY, best.stopPay).putInt(BEST_STOPS, best.stops)
                .putInt(BEST_ITEM_PAY, best.itemPay).putInt(BEST_ITEMS, best.items)
                .apply();
        if (!recorded) return AcceptedLesson.NOTHING_NEW;
        // Report the same exact resolved costs the rules use. A new personal best may still sit below
        // a stronger saved or manually-declined floor, and must not be described as raising that floor.
        AreaScore.Floors oldFloors = AreaScore.floors(before, accepted);
        AreaScore.Floors newFloors = AreaScore.floors(load(context), accepted);
        for (int axis = 0; axis < AreaScore.AXES; axis++) {
            if (oldFloors.active[axis] != newFloors.active[axis]
                    || (oldFloors.cents[axis] == null) != (newFloors.cents[axis] == null)
                    || (oldFloors.cents[axis] != null && newFloors.cents[axis] != null
                        && newFloors.cents[axis].compareTo(oldFloors.cents[axis]) > 0)) {
                return AcceptedLesson.RAISED;
            }
        }
        return AcceptedLesson.RECORDED;
    }

    /**
     * Forgets the payout baseline, every best rate and everything declines taught: the adaptive minimum starts over
     * from the saved rules.
     */
    static synchronized void resetAccepted(Context context) {
        rulesGeneration++;
        prefs(context).edit().putLong(ADAPTIVE_RESET_AT, System.currentTimeMillis())
                .remove(LAST_ACCEPTED).remove(BEST_MINUTE_PAY).remove(BEST_MINUTES)
                .remove(BEST_MILE_PAY).remove(BEST_MILES).remove(BEST_STOP_PAY).remove(BEST_STOPS)
                .remove(BEST_ITEM_PAY).remove(BEST_ITEMS)
                .remove(DECLINED_PAY).remove(DECLINED_MINUTE_PAY).remove(DECLINED_MINUTES)
                .remove(DECLINED_MILE_PAY).remove(DECLINED_MILES).remove(DECLINED_STOP_PAY).remove(DECLINED_STOPS)
                .apply();
        ManualDeclines.forget(context);
    }

    /**
     * For a shared report: when learning (auto-decline and the adaptive minimum both on) last turned on and off, and
     * when the adaptive minimums were last reset, as wall-clock times; 0 when not recorded (older versions kept
     * none).
     */
    static long[] learningTimes(Context context) {
        SharedPreferences prefs = prefs(context);
        return new long[] {prefs.getLong(LEARNING_ON_SINCE, 0), prefs.getLong(LEARNING_OFF_AT, 0),
                prefs.getLong(ADAPTIVE_RESET_AT, 0)};
    }

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

    static synchronized void setPeek(Context context, boolean on) {
        rulesGeneration++;
        prefs(context).edit().putBoolean(PEEK, on).apply();
    }

    static boolean autoAcceptEnabled(Context context) {
        return prefs(context).getBoolean(AUTO_ACCEPT, false);
    }

    static synchronized void setAutoAcceptEnabled(Context context, boolean on) {
        rulesGeneration++;
        prefs(context).edit().putBoolean(AUTO_ACCEPT, on).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private FilterStore() {}
}

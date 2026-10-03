package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * An offer the user declined by hand, held until it is clear the decline was about the offer. The next different
 * offer shows the dash went on, so the decline then teaches the adaptive minimum. If the dash ends or pauses first,
 * the decline was about stopping, not about the offer, and is dropped. An acceptance of the same offer, or an hour
 * without another offer, drops it too.
 */
final class ManualDeclines {
    static final long STALE_MS = 60 * 60_000L;
    private static final String PREFS = "offer_filter_manual_declines";
    private static final String PAY = "pay";
    private static final String MILES = "miles_bits";
    private static final String MINUTES = "minutes";
    private static final String STOPS = "stops";
    private static final String ITEMS = "items";
    private static final String ITEM_APPLICABLE = "item_count_applicable";
    private static final String AT = "at";

    private ManualDeclines() {}

    /** How long after a decline its offer's history line still takes the steps of what it taught. */
    private static final long STEP_WINDOW_MS = STALE_MS + 10 * 60_000L;

    /** The user declined {@code offer} by hand, which the rules let through: held until the dash goes on. */
    static void declined(Context context, OfferSnapshot offer, long now) {
        if (offer.payCents == null) return;
        // A second manual decline is itself proof the dash went on after the first.
        OfferSnapshot earlier = pending(context, now);
        if (earlier != null && !sameOfferFacts(earlier, offer)) learn(context, earlier);
        SharedPreferences.Editor edit = prefs(context).edit()
                .putInt(PAY, offer.payCents)
                .putLong(MILES, offer.miles == null ? -1 : Double.doubleToLongBits(offer.miles))
                .putInt(MINUTES, offer.minutes == null ? -1 : offer.minutes)
                .putInt(STOPS, offer.stops == null ? -1 : offer.stops)
                .putLong(AT, now);
        if (offer.items != null) edit.putInt(ITEMS, offer.items); else edit.remove(ITEMS);
        if (offer.itemCountApplicable) edit.putBoolean(ITEM_APPLICABLE, true); else edit.remove(ITEM_APPLICABLE);
        edit.apply();
    }

    /** A new offer was recorded: if it is a different offer, a held decline now counts. */
    static void offerSeen(Context context, OfferSnapshot offer, long now) {
        OfferSnapshot held = pending(context, now);
        if (held == null || sameOfferFacts(held, offer)) return;
        forget(context);
        learn(context, held);
    }

    /**
     * Keep the established pay/route identity. A newly unread item count is not evidence that the dash continued
     * to a different offer; only two observed, differing counts can establish an item-only change here. This
     * learning guard does not change the scanner's exact full-key recovery or authorize a tap.
     */
    private static boolean sameOfferFacts(OfferSnapshot left, OfferSnapshot right) {
        return java.util.Objects.equals(left.payCents, right.payCents)
                && java.util.Objects.equals(left.miles, right.miles)
                && java.util.Objects.equals(left.minutes, right.minutes)
                && java.util.Objects.equals(left.stops, right.stops)
                && (left.items == null || right.items == null || left.items.equals(right.items));
    }

    /** The dash ended or paused: a held decline was about stopping, not the offer. */
    static void dashEnded(Context context) {
        dropped(context, "the dash ended or paused before another offer came");
    }

    /** A held decline no longer counts, for the reason given: its offer's history line says so. */
    static void dropped(Context context, String why) {
        OfferSnapshot held = pending(context, System.currentTimeMillis());
        forget(context);
        if (held == null) return;
        DecisionLog.markStep(context, held, DecisionLog.StepKind.DECLINE_DROPPED, why, STEP_WINDOW_MS);
        DiagnosticLog.log(context, "learn", held.summary() + ": not counted as your Decline: " + why);
    }

    static void forget(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.contains(AT)) prefs.edit().clear().apply();
    }

    /** The held decline, or null when there is none or it went stale (and is dropped). */
    static OfferSnapshot pending(Context context, long now) {
        SharedPreferences prefs = prefs(context);
        long at = prefs.getLong(AT, 0);
        if (at <= 0) return null;
        if (now - at > STALE_MS || now < at - 60_000L) {
            forget(context);
            return null;
        }
        long miles = prefs.getLong(MILES, -1);
        int minutes = prefs.getInt(MINUTES, -1);
        int stops = prefs.getInt(STOPS, -1);
        double mileValue = miles == -1 ? Double.NaN : Double.longBitsToDouble(miles);
        return new OfferSnapshot(prefs.getInt(PAY, 0),
                Double.isFinite(mileValue) && mileValue > 0 ? mileValue : null,
                minutes > 0 ? minutes : null, stops > 0 ? stops : null)
                .withItems(prefs.contains(ITEMS) ? prefs.getInt(ITEMS, 0) : null,
                        prefs.getBoolean(ITEM_APPLICABLE, false));
    }

    /** The held decline teaches now; its offer's history line, and the log, say what it taught. */
    private static void learn(Context context, OfferSnapshot declined) {
        FilterStore.DeclineLesson lesson = FilterStore.learnFromDecline(context, declined);
        String detail;
        switch (lesson) {
            case TAUGHT:
                detail = "learned from declines by hand: " + FilterStore.load(context).declined.summary();
                break;
            case SWITCHES_OFF:
                detail = "auto-decline or the adaptive minimum was off";
                break;
            default:
                detail = "the minimums already ask more than it paid, or it looked misread";
                break;
        }
        DecisionLog.StepKind kind = lesson == FilterStore.DeclineLesson.TAUGHT
                ? DecisionLog.StepKind.DECLINE_TAUGHT : DecisionLog.StepKind.DECLINE_NOT_TAUGHT;
        DecisionLog.markStep(context, declined, kind, detail, STEP_WINDOW_MS);
        DiagnosticLog.log(context, "learn", declined.summary() + ": " + kind.label + ": " + detail);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

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
    private static final String AT = "at";

    private ManualDeclines() {}

    /** The user tapped Decline on {@code offer}, which the rules let through. */
    static void declined(Context context, OfferSnapshot offer, long now) {
        if (offer.payCents == null) return;
        // A second manual decline is itself proof the dash went on after the first.
        OfferSnapshot earlier = pending(context, now);
        if (earlier != null && !earlier.fingerprint().equals(offer.fingerprint())) learn(context, earlier);
        prefs(context).edit()
                .putInt(PAY, offer.payCents)
                .putLong(MILES, offer.miles == null ? -1 : Double.doubleToLongBits(offer.miles))
                .putInt(MINUTES, offer.minutes == null ? -1 : offer.minutes)
                .putInt(STOPS, offer.stops == null ? -1 : offer.stops)
                .putLong(AT, now)
                .apply();
    }

    /** A new offer was recorded: if it is a different offer, a held decline now counts. */
    static void offerSeen(Context context, OfferSnapshot offer, long now) {
        OfferSnapshot held = pending(context, now);
        if (held == null || held.fingerprint().equals(offer.fingerprint())) return;
        forget(context);
        learn(context, held);
    }

    /** The dash ended or paused: a held decline was about stopping, not the offer. */
    static void dashEnded(Context context) {
        forget(context);
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
                minutes > 0 ? minutes : null, stops > 0 ? stops : null);
    }

    private static void learn(Context context, OfferSnapshot declined) {
        FilterStore.learnFromDecline(context, declined);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

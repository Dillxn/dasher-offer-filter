package com.local.dasherfilter;

import java.util.Locale;

final class OfferRule {
    enum Result { DECLINE, KEEP, REVIEW }

    static final class Decision {
        final Result result;
        final int requiredCents;
        final String reason;

        Decision(Result result, int requiredCents, String reason) {
            this.result = result;
            this.requiredCents = requiredCents;
            this.reason = reason;
        }

        String summary() {
            return String.format(Locale.US, "%s: required at least $%.2f (%s)",
                    result, requiredCents / 100.0, reason);
        }
    }

    static Decision evaluate(OfferSnapshot offer, FilterSettings settings) {
        if (offer.payCents == null) {
            return new Decision(Result.REVIEW, 0, "pay not found");
        }
        int required = settings.flatCents;
        boolean missing = false;
        String reason = "flat minimum";

        if (settings.perMileCents > 0) {
            if (offer.miles == null) {
                missing = true;
            } else {
                int byMiles = (int) Math.ceil(settings.perMileCents * offer.miles - 0.00001);
                if (byMiles > required) {
                    required = byMiles;
                    reason = "dollars per mile";
                }
            }
        }
        if (settings.perMinuteCents > 0) {
            if (offer.minutes == null) {
                missing = true;
            } else {
                int byMinutes = settings.perMinuteCents * offer.minutes;
                if (byMinutes > required) {
                    required = byMinutes;
                    reason = "dollars per minute";
                }
            }
        }
        if (settings.extraStopCents > 0) {
            if (offer.stops == null) {
                missing = true;
            } else {
                int extraStops = Math.max(0, offer.stops - 2);
                required += settings.extraStopCents * extraStops;
                if (extraStops > 0) reason += " and extra stops";
            }
        }

        if (offer.payCents < required) {
            return new Decision(Result.DECLINE, required, reason);
        }
        if (missing) {
            return new Decision(Result.REVIEW, required, "an enabled value was not found");
        }
        return new Decision(Result.KEEP, required, "meets enabled rules");
    }
}


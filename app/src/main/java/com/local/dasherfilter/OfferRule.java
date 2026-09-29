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
            if (requiredCents == 0) return result + ": " + reason;
            return String.format(Locale.US, "%s: required at least $%.2f (%s)",
                    result, requiredCents / 100.0, reason);
        }
    }

    static Decision evaluate(OfferSnapshot offer, FilterSettings settings) {
        boolean missing = settings.maxStops > 0 && offer.stops == null;
        if (settings.maxStops > 0 && offer.stops != null && offer.stops > settings.maxStops) {
            return new Decision(Result.DECLINE, 0,
                    offer.stops + " stops exceeds maximum " + settings.maxStops);
        }
        boolean needsPay = settings.flatCents > 0 || settings.perMileCents > 0 ||
                settings.perMinuteCents > 0 || settings.extraStopCents > 0 || settings.risingOffers;
        if (needsPay && offer.payCents == null) {
            return new Decision(Result.REVIEW, 0, "pay not found");
        }
        int required = settings.flatCents;
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

        if (settings.risingOffers && settings.lastAcceptedCents > 0 &&
                settings.lastAcceptedCents + 1 > required) {
            required = settings.lastAcceptedCents + 1;
            reason = String.format(Locale.US, "must beat last accepted payout $%.2f",
                    settings.lastAcceptedCents / 100.0);
        }

        if (offer.payCents != null && offer.payCents < required) {
            return new Decision(Result.DECLINE, required, reason);
        }
        if (missing) {
            return new Decision(Result.REVIEW, required, "an enabled value was not found");
        }
        return new Decision(Result.KEEP, required, "meets enabled rules");
    }

    static Decision evaluateAddOn(AddOnOffer addOn, FilterSettings settings) {
        // Rising-offer logic is route-level for add-ons: the combined route replaces the
        // standalone offer as the accepted baseline. Do not require the marginal payout
        // itself to exceed the previous full-order payout.
        FilterSettings routeSettings = new FilterSettings(settings.enabled, settings.flatCents,
                settings.perMileCents, settings.perMinuteCents, settings.extraStopCents,
                settings.maxStops, false, settings.lastAcceptedCents);
        Decision combined = evaluate(addOn.combined, routeSettings);
        if (combined.result == Result.DECLINE) {
            return new Decision(Result.DECLINE, combined.requiredCents,
                    "combined route fails: " + combined.reason);
        }

        OfferSnapshot delta = addOn.incremental;
        boolean missing = false;
        int variableRequired = 0;
        String reason = "add-on marginal economics";

        if (delta.payCents == null &&
                (settings.perMileCents > 0 || settings.perMinuteCents > 0 ||
                        settings.extraStopCents > 0)) {
            return new Decision(Result.REVIEW, 0, "add-on payout not found");
        }

        int travelRequired = 0;
        if (settings.perMileCents > 0) {
            if (delta.miles == null) {
                missing = true;
            } else {
                travelRequired = Math.max(travelRequired,
                        (int) Math.ceil(settings.perMileCents * delta.miles - 0.00001));
            }
        }
        if (settings.perMinuteCents > 0) {
            if (delta.minutes == null) {
                missing = true;
            } else {
                travelRequired = Math.max(travelRequired,
                        settings.perMinuteCents * delta.minutes);
            }
        }
        variableRequired = travelRequired;

        if (settings.extraStopCents > 0) {
            if (addOn.active.stops == null || addOn.combined.stops == null) {
                missing = true;
            } else {
                int before = Math.max(0, addOn.active.stops - 2);
                int after = Math.max(0, addOn.combined.stops - 2);
                variableRequired += settings.extraStopCents * Math.max(0, after - before);
            }
        }

        if (delta.payCents != null && delta.payCents < variableRequired) {
            return new Decision(Result.DECLINE, variableRequired, reason);
        }
        if (combined.result == Result.REVIEW || missing) {
            return new Decision(Result.REVIEW, variableRequired,
                    "add-on or combined route is missing an enabled value");
        }
        return new Decision(Result.KEEP, variableRequired,
                variableRequired == 0
                        ? "combined route meets rules; no marginal variable minimum is enabled"
                        : "add-on marginal economics and combined route both meet rules");
    }
}

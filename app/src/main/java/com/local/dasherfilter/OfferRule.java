package com.local.dasherfilter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Applies saved rules to offer facts. A known failure declines even when other values are missing; otherwise a
 * missing value that an enabled rule needs yields REVIEW, never KEEP or DECLINE.
 */
final class OfferRule {
    enum Result { DECLINE, KEEP, REVIEW }

    static final class Decision {
        final Result result;
        final long requiredCents;
        final String reason;
        /** The facts the requirement was compared with: the offer, an add-on's increment, or its combined route. */
        final OfferSnapshot basis;

        Decision(Result result, long requiredCents, String reason) {
            this(result, requiredCents, reason, OfferSnapshot.UNKNOWN);
        }

        Decision(Result result, long requiredCents, String reason, OfferSnapshot basis) {
            this.result = result;
            this.requiredCents = requiredCents;
            this.reason = reason;
            this.basis = basis;
        }

        String summary() {
            if (requiredCents == 0) return result + ": " + reason;
            return String.format(Locale.US, "%s: required at least $%.2f (%s)", result, requiredCents / 100.0, reason);
        }
    }

    /**
     * Required pay is {@code max(flat, miles × rate, minutes × rate) + fee × max(0, stops - 2)}. The adaptive
     * minimum raises it to one cent above the last accepted standalone payout, and to at least the best accepted
     * pay per minute, per mile and per stop applied to this offer; each is a floor of its own, never added on top.
     */
    static Decision evaluate(OfferSnapshot offer, FilterSettings settings) {
        if (settings.maxStops > 0 && offer.stops != null && offer.stops > settings.maxStops) {
            String reason = offer.stops + " stops exceeds maximum " + settings.maxStops;
            return new Decision(Result.DECLINE, 0, reason, offer);
        }
        boolean needsPay = settings.flatCents > 0 || settings.hasMarginalRule() || settings.risingOffers;
        if (needsPay && offer.payCents == null) return new Decision(Result.REVIEW, 0, "pay not found", offer);

        boolean missing = settings.maxStops > 0 && offer.stops == null;
        long required = Math.max(0, settings.flatCents);
        String reason = "flat minimum";
        if (settings.perMileCents > 0) {
            if (offer.miles == null) {
                missing = true;
            } else {
                long byMiles = mileageCost(settings.perMileCents, offer.miles);
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
                long byMinutes = (long) settings.perMinuteCents * offer.minutes;
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
                required = saturatingAdd(required, (long) settings.extraStopCents * extraStops);
                if (extraStops > 0) reason += " and extra stops";
            }
        }
        if (settings.risingOffers && settings.lastAcceptedCents > 0 && settings.lastAcceptedCents + 1L > required) {
            required = settings.lastAcceptedCents + 1L;
            reason = String.format(Locale.US, "must beat last accepted payout $%.2f",
                    settings.lastAcceptedCents / 100.0);
        }
        if (settings.risingOffers) {
            AcceptedBest best = settings.best;
            if (best.hasPerMinute()) {
                if (offer.minutes == null) {
                    missing = true;
                } else if (best.forMinutes(offer.minutes) > required) {
                    required = best.forMinutes(offer.minutes);
                    reason = "must match best accepted " + best.perMinuteLabel();
                }
            }
            if (best.hasPerMile()) {
                if (offer.miles == null) {
                    missing = true;
                } else if (best.forMiles(offer.miles) > required) {
                    required = best.forMiles(offer.miles);
                    reason = "must match best accepted " + best.perMileLabel();
                }
            }
            if (best.hasPerStop()) {
                if (offer.stops == null) {
                    missing = true;
                } else if (best.forStops(offer.stops) > required) {
                    required = best.forStops(offer.stops);
                    reason = "must match best accepted " + best.perStopLabel();
                }
            }
            // What offers declined by hand taught: each is a floor of its own, beaten by at least a cent.
            DeclinedFloor declined = settings.declined;
            if (declined.payCents > 0 && declined.beatPay() > required) {
                required = declined.beatPay();
                reason = "must beat declined payout " + DecisionLog.money(declined.payCents);
            }
            if (declined.rates.hasPerMinute()) {
                if (offer.minutes == null) {
                    missing = true;
                } else if (declined.beatMinutes(offer.minutes) > required) {
                    required = declined.beatMinutes(offer.minutes);
                    reason = "must beat declined " + declined.rates.perMinuteLabel();
                }
            }
            if (declined.rates.hasPerMile()) {
                if (offer.miles == null) {
                    missing = true;
                } else if (declined.beatMiles(offer.miles) > required) {
                    required = declined.beatMiles(offer.miles);
                    reason = "must beat declined " + declined.rates.perMileLabel();
                }
            }
            if (declined.rates.hasPerStop()) {
                if (offer.stops == null) {
                    missing = true;
                } else if (declined.beatStops(offer.stops) > required) {
                    required = declined.beatStops(offer.stops);
                    reason = "must beat declined " + declined.rates.perStopLabel();
                }
            }
        }

        // Missing values can only raise the requirement, so a shortfall against the known part is already final.
        if (offer.payCents != null && offer.payCents < required) {
            return new Decision(Result.DECLINE, required, reason, offer);
        }
        if (missing) return new Decision(Result.REVIEW, required, "an enabled value was not found", offer);
        return new Decision(Result.KEEP, required, "meets enabled rules", offer);
    }

    /**
     * An add-on must keep the combined route within the flat, rate, and stop limits, and its own explicit added
     * pay must cover the marginal per-mile, per-minute, and extra-stop cost it introduces.
     */
    static Decision evaluateAddOn(AddOnOffer addOn, FilterSettings settings) {
        Decision combined = evaluate(addOn.combined, settings.withoutRisingBaseline());
        if (combined.result == Result.DECLINE) {
            return new Decision(Result.DECLINE, combined.requiredCents, "combined route fails: " + combined.reason,
                    addOn.combined);
        }

        OfferSnapshot added = addOn.incremental;
        // Any pay rule other than the flat minimum (already applied to the combined route) needs the added pay.
        // The rising baseline never judges an add-on, but it must not let an add-on with unknown pay pass either.
        boolean missing = (settings.hasMarginalRule() || settings.risingOffers) && added.payCents == null;
        long marginalCost = 0;
        if (settings.perMileCents > 0) {
            if (added.miles == null) missing = true;
            else marginalCost = Math.max(marginalCost, mileageCost(settings.perMileCents, added.miles));
        }
        if (settings.perMinuteCents > 0) {
            if (added.minutes == null) missing = true;
            else marginalCost = Math.max(marginalCost, (long) settings.perMinuteCents * added.minutes);
        }
        if (settings.extraStopCents > 0) {
            if (added.stops == null || addOn.active.stops == null) {
                missing = true;
            } else {
                long extraBefore = Math.max(0L, (long) addOn.active.stops - 2);
                long extraAfter = Math.max(0L, (long) addOn.active.stops + added.stops - 2);
                marginalCost = saturatingAdd(marginalCost, settings.extraStopCents * (extraAfter - extraBefore));
            }
        }

        if (added.payCents != null && added.payCents < marginalCost) {
            return new Decision(Result.DECLINE, marginalCost, "add-on marginal economics", added);
        }
        if (combined.result == Result.REVIEW || missing) {
            return new Decision(Result.REVIEW, marginalCost,
                    "add-on has missing or ambiguous incremental/route evidence", added);
        }
        return new Decision(Result.KEEP, marginalCost, "combined route and add-on meet enabled rules", added);
    }

    /** Cents for {@code miles × rate}, rounded up; saturates rather than overflowing. */
    static long mileageCost(int rateCents, double miles) {
        try {
            return BigDecimal.valueOf(miles).multiply(BigDecimal.valueOf(rateCents))
                    .setScale(0, RoundingMode.CEILING).longValueExact();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatingAdd(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    private OfferRule() {}
}

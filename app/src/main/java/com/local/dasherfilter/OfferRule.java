package com.local.dasherfilter;

import java.math.BigDecimal;

/**
 * Applies the rules to offer facts (0.5.0): one strict rule. A standalone offer needs at most {@code maxStops} stops
 * (never scaled) and pay of at least {@code ⌈bar × R100 ÷ 100⌉} whole cents, where R100 is the highest of the set money
 * minimums' asks ({@link AreaScore#asks}): minimum pay, per mile × miles and per minute × minutes, worked exactly.
 *
 * <p>A known failure declines even when other values are missing; otherwise a missing value that a set rule needs is
 * REVIEW, never KEEP or DECLINE. A known ceiling on unknown pay (a "+$" amount beside a total) declines an offer only
 * when even that ceiling misses the user's own minimums ({@code min(bar, 100)}), the user's own rule for that shape,
 * and never passes one. With the bar below 100 a passing offer that misses 100% of the minimums is marked
 * {@link Decision#belowMinimums}: it is left to the user and never auto-accepted.
 *
 * <p>Add-ons are judged in two parts at the same bar: the combined route as a standalone offer, and the explicit added
 * pay against {@code ⌈bar × max(per mile × added miles, per minute × added minutes) ÷ 100⌉}.
 */
final class OfferRule {
    enum Result { DECLINE, KEEP, REVIEW }

    /** The reason of a KEEP that meets 100% of the minimums (with the bar at or below 100). */
    static final String MEETS_MINIMUMS = "meets your minimums";
    static final String PAY_NOT_FOUND = "pay not found";
    static final String VALUE_NOT_FOUND = "an enabled value was not found";
    static final String PAY_UNCLEAR = "pay unclear beside a +$ amount";
    static final String ADD_ON_UNCLEAR = "add-on has missing or ambiguous incremental/route evidence";
    static final String ADD_ON_ECONOMICS = "add-on marginal economics";
    static final String COMBINED_FAILS = "combined route fails: ";

    static final class Decision {
        final Result result;
        final long requiredCents;
        final String reason;
        /** The facts the requirement was compared with: the offer, an add-on's increment, or its combined route. */
        final OfferSnapshot basis;
        /** The score ({@link AreaScore#scorePercent}): pay as a percent of what the minimums ask; -1 when none. */
        final int scorePercent;
        /** The bar this decision used. */
        final int minimumScalePercent;
        /**
         * KEEP only because the bar is below 100: the offer misses 100% of the minimums. Left to the user, never
         * auto-accepted, never the pass chime.
         */
        final boolean belowMinimums;
        /** Autopilot was on (it set the bar). */
        final boolean autopilot;

        Decision(Result result, long requiredCents, String reason) {
            this(result, requiredCents, reason, OfferSnapshot.UNKNOWN);
        }

        Decision(Result result, long requiredCents, String reason, OfferSnapshot basis) {
            this(result, requiredCents, reason, basis, -1);
        }

        Decision(Result result, long requiredCents, String reason, OfferSnapshot basis, int scorePercent) {
            this(result, requiredCents, reason, basis, scorePercent, FilterSettings.BAR_AT_MINIMUMS);
        }

        Decision(Result result, long requiredCents, String reason, OfferSnapshot basis, int scorePercent,
                 int minimumScalePercent) {
            this(result, requiredCents, reason, basis, scorePercent, minimumScalePercent, false, false);
        }

        Decision(Result result, long requiredCents, String reason, OfferSnapshot basis, int scorePercent,
                 int minimumScalePercent, boolean belowMinimums, boolean autopilot) {
            this.result = result;
            this.requiredCents = requiredCents;
            this.reason = reason;
            this.basis = basis;
            this.scorePercent = scorePercent < 0 ? -1 : scorePercent;
            this.minimumScalePercent = minimumScalePercent;
            this.belowMinimums = belowMinimums;
            this.autopilot = autopilot;
        }

        /** "DECLINE: required at least $6.25 (dollars per hour)"; "KEEP: meets your minimums" when nothing is asked. */
        String summary() {
            if (requiredCents == 0) return result + ": " + reason;
            return result + ": required at least $" + BigDecimal.valueOf(requiredCents, 2).toPlainString()
                    + " (" + reason + ")";
        }
    }

    /** The rules applied to a standalone offer. */
    static Decision evaluate(OfferSnapshot offer, FilterSettings settings) {
        int bar = settings.minimumScalePercent;
        boolean autopilot = settings.autopilot;
        AreaScore.Asks asks = AreaScore.asks(settings, offer);
        int score = asks.score(offer.payCents);
        if (settings.maxStops > 0 && offer.stops != null && offer.stops > settings.maxStops) {
            return new Decision(Result.DECLINE, 0, offer.stops + " stops exceeds maximum " + settings.maxStops,
                    offer, score, bar, false, autopilot);
        }
        if (!settings.hasMonetaryRule()) {
            if (settings.maxStops > 0 && offer.stops == null) {
                return new Decision(Result.REVIEW, 0, VALUE_NOT_FOUND, offer, score, bar, false, autopilot);
            }
            return new Decision(Result.KEEP, 0, MEETS_MINIMUMS, offer, score, bar, false, autopilot);
        }
        if (offer.payCents == null && offer.payAtMostCents == null) {
            return new Decision(Result.REVIEW, 0, PAY_NOT_FOUND, offer, score, bar, false, autopilot);
        }
        long required = asks.known == null ? 0 : AreaScore.roundedCents(asks.known, bar);
        // Missing values can only raise the requirement, so a shortfall against the known part is already final.
        if (offer.payCents != null && offer.payCents < required) {
            return new Decision(Result.DECLINE, required, atBar(bar, asks.axis), offer, score, bar, false, autopilot);
        }
        if (offer.payCents == null) {
            // Only the most it can pay is known. The user's own rule for this one shape: decline when the offer as a
            // whole, Y + X, misses the user's own minimums, never anything stricter than them.
            int ceilingBar = Math.min(bar, FilterSettings.BAR_AT_MINIMUMS);
            if (asks.known != null) {
                long ceilingRequired = AreaScore.roundedCents(asks.known, ceilingBar);
                if (offer.payAtMostCents < ceilingRequired) {
                    return new Decision(Result.DECLINE, ceilingRequired, "pay at most "
                            + DecisionLog.money(offer.payAtMostCents) + " with its +$ amount; "
                            + atBar(ceilingBar, asks.axis), offer, score, bar, false, autopilot);
                }
            }
            return new Decision(Result.REVIEW, required, PAY_UNCLEAR, offer, score, bar, false, autopilot);
        }
        if (asks.missing || (settings.maxStops > 0 && offer.stops == null)) {
            return new Decision(Result.REVIEW, required, VALUE_NOT_FOUND, offer, score, bar, false, autopilot);
        }
        boolean below = bar < FilterSettings.BAR_AT_MINIMUMS && score >= 0 && score < FilterSettings.BAR_AT_MINIMUMS;
        String reason = bar > FilterSettings.BAR_AT_MINIMUMS ? "meets the " + bar + "% bar"
                : below ? "below your minimums; passes the " + bar + "% bar" : MEETS_MINIMUMS;
        return new Decision(Result.KEEP, required, reason, offer, score, bar, below, autopilot);
    }

    /**
     * An add-on keeps the combined route within the rules at the current bar, and its own explicit added pay must cover
     * {@code ⌈bar × max(added miles × per mile, added minutes × per minute) ÷ 100⌉}. Only the amounts the add-on
     * explicitly adds count; minimum pay is not an increment floor. Unknown added pay is REVIEW only when a rate
     * minimum is set. With the bar below 100, a KEEP that would not pass at 100 is marked below the minimums.
     */
    static Decision evaluateAddOn(AddOnOffer addOn, FilterSettings settings) {
        Decision decision = addOnAt(addOn, settings);
        int bar = settings.minimumScalePercent;
        if (decision.result != Result.KEEP || bar >= FilterSettings.BAR_AT_MINIMUMS) return decision;
        if (addOnAt(addOn, settings.withMinimumScalePercent(FilterSettings.BAR_AT_MINIMUMS)).result == Result.KEEP) {
            return decision;
        }
        return new Decision(Result.KEEP, decision.requiredCents,
                "combined route and add-on below your minimums; pass the " + bar + "% bar", decision.basis, -1, bar,
                true, settings.autopilot);
    }

    private static Decision addOnAt(AddOnOffer addOn, FilterSettings settings) {
        int bar = settings.minimumScalePercent;
        boolean autopilot = settings.autopilot;
        Decision combined = evaluate(addOn.combined, settings);
        if (combined.result == Result.DECLINE) {
            return new Decision(Result.DECLINE, combined.requiredCents, COMBINED_FAILS + combined.reason,
                    addOn.combined, -1, bar, false, autopilot);
        }
        OfferSnapshot added = addOn.incremental;
        boolean missing = settings.hasMarginalRule() && added.payCents == null;
        BigDecimal marginal = null;
        for (int axis : new int[] {AreaScore.MILE, AreaScore.MINUTE}) {
            int rate = axis == AreaScore.MILE ? settings.perMileCents : settings.perMinuteCents;
            if (rate <= 0) continue;
            BigDecimal ask = AreaScore.fixedFloor(axis, rate, added);
            if (ask == null) missing = true;
            else if (marginal == null || ask.compareTo(marginal) > 0) marginal = ask;
        }
        long marginalCost = marginal == null ? 0 : AreaScore.roundedCents(marginal, bar);
        if (added.payCents != null && added.payCents < marginalCost) {
            return new Decision(Result.DECLINE, marginalCost, barPrefix(bar) + ADD_ON_ECONOMICS, added, -1, bar,
                    false, autopilot);
        }
        if (combined.result == Result.REVIEW || missing) {
            return new Decision(Result.REVIEW, marginalCost, ADD_ON_UNCLEAR, added, -1, bar, false, autopilot);
        }
        return new Decision(Result.KEEP, marginalCost, bar > FilterSettings.BAR_AT_MINIMUMS
                ? "combined route and add-on meet the " + bar + "% bar"
                : "combined route and add-on meet your minimums", added, -1, bar, false, autopilot);
    }

    /** "dollars per mile", prefixed "82% bar: " when the bar is not 100. */
    private static String atBar(int bar, int axis) {
        return barPrefix(bar) + axisReason(axis);
    }

    private static String barPrefix(int bar) {
        return bar == FilterSettings.BAR_AT_MINIMUMS ? "" : bar + "% bar: ";
    }

    /** The binding minimum's words: the first highest of pay, per mile and per hour. */
    private static String axisReason(int axis) {
        switch (axis) {
            case AreaScore.MILE: return "dollars per mile";
            case AreaScore.MINUTE: return "dollars per hour";
            default: return "flat minimum";
        }
    }

    /** Cents for {@code miles × rate}, rounded up; saturates rather than overflowing. */
    static long mileageCost(int rateCents, double miles) {
        return AreaScore.roundedCents(BigDecimal.valueOf(miles).multiply(BigDecimal.valueOf(rateCents)), 100);
    }

    /** Scale whole cents by a bar, rounded up; Long.MAX_VALUE stays the unreachable/saturated sentinel. */
    static long scaledCost(long cents, int minimumScalePercent) {
        if (minimumScalePercent == FilterSettings.BAR_AT_MINIMUMS || cents == Long.MAX_VALUE) return cents;
        return AreaScore.roundedCents(BigDecimal.valueOf(cents), minimumScalePercent);
    }

    private OfferRule() {}
}

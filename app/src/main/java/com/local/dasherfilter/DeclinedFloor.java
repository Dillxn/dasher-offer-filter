package com.local.dasherfilter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * What offers declined by hand taught the adaptive minimum: a payout, and pay per minute, mile and stop, that later
 * offers must beat. A manual decline of an offer the rules let through means the rules were too lenient, so only
 * the rule that came closest to catching that offer is raised, just past it. Raising every rule would decline
 * offers like ones the user accepts. Rates are kept as the declined pay and the amount it covered, so "beats" stays
 * exact: an offer needs {@code floor(pay × itsAmount ÷ amount) + 1} cent.
 */
final class DeclinedFloor {
    static final DeclinedFloor NONE = new DeclinedFloor(0, AcceptedBest.NONE);

    enum Rule {
        PAY(AreaScore.PAY), MILE(AreaScore.MILE), MINUTE(AreaScore.MINUTE), STOP(AreaScore.STOP);
        final int axis;
        Rule(int axis) { this.axis = axis; }
    }

    final int payCents;
    final AcceptedBest rates;

    DeclinedFloor(int payCents, AcceptedBest rates) {
        this.payCents = Math.max(0, payCents);
        this.rates = rates == null ? AcceptedBest.NONE : rates;
    }

    boolean isEmpty() {
        return payCents <= 0 && rates.isEmpty();
    }

    long beatPay() {
        return payCents + 1L;
    }

    long beatMinutes(int offerMinutes) {
        return (long) rates.minutePay * offerMinutes / rates.minutes + 1;
    }

    long beatMiles(double offerMiles) {
        try {
            return BigDecimal.valueOf(rates.milePay).multiply(BigDecimal.valueOf(offerMiles))
                    .divide(BigDecimal.valueOf(rates.miles), 0, RoundingMode.FLOOR).longValueExact() + 1;
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    long beatStops(int offerStops) {
        return (long) rates.stopPay * offerStops / rates.stops + 1;
    }

    /** "$25.00, $2.50/mi", or empty when nothing was learned. */
    String summary() {
        List<String> parts = new ArrayList<>();
        if (payCents > 0) parts.add(DecisionLog.money(payCents));
        if (!rates.isEmpty()) parts.add(rates.summary());
        return String.join(", ", parts);
    }

    /**
     * The floor after the user declined {@code declined} by hand while {@code rules} let it through. Unknown
     * amounts are never guessed; a misread-looking offer teaches nothing; short trips set no distance or time floor.
     */
    static DeclinedFloor raisedBy(FilterSettings rules, OfferSnapshot declined) {
        DeclinedFloor current = rules.declined;
        Integer pay = declined.payCents;
        if (pay == null || pay <= 0 || AcceptedBest.looksMisread(declined)) return current;
        Rule closest = Rule.PAY;
        AreaScore.Floors floors = AreaScore.floors(rules, declined);
        double closeness = floors.wholeCents(AreaScore.PAY) / (double) pay;
        for (Rule rule : new Rule[] {Rule.MILE, Rule.MINUTE, Rule.STOP}) {
            if (!usable(declined, rule)) continue;
            double share = floors.wholeCents(rule.axis) / (double) pay;
            if (share > closeness) {
                closeness = share;
                closest = rule;
            }
        }
        return current.raise(closest, declined);
    }

    /** Whether the declined offer shows this rule's amount, long enough to set a floor on it. */
    private static boolean usable(OfferSnapshot offer, Rule rule) {
        switch (rule) {
            case MILE: return offer.miles != null && offer.miles >= AcceptedBest.RATE_SETTING_MILES;
            case MINUTE: return offer.minutes != null && offer.minutes >= AcceptedBest.RATE_SETTING_MINUTES;
            case STOP: return offer.stops != null && offer.stops >= AcceptedBest.PLAUSIBLE_STOPS;
            default: return true;
        }
    }

    /** This floor with {@code rule} raised to the declined offer's value, if that is higher. */
    private DeclinedFloor raise(Rule rule, OfferSnapshot offer) {
        int pay = offer.payCents;
        AcceptedBest r = rates;
        switch (rule) {
            case MILE:
                if (r.hasPerMile() && BigDecimal.valueOf(pay).multiply(BigDecimal.valueOf(r.miles))
                        .compareTo(BigDecimal.valueOf(r.milePay).multiply(BigDecimal.valueOf(offer.miles))) <= 0) {
                    return this;
                }
                return new DeclinedFloor(payCents, new AcceptedBest(r.minutePay, r.minutes, pay, offer.miles,
                        r.stopPay, r.stops));
            case MINUTE:
                if (r.hasPerMinute() && (long) pay * r.minutes <= (long) r.minutePay * offer.minutes) return this;
                return new DeclinedFloor(payCents, new AcceptedBest(pay, offer.minutes, r.milePay, r.miles,
                        r.stopPay, r.stops));
            case STOP:
                if (r.hasPerStop() && (long) pay * r.stops <= (long) r.stopPay * offer.stops) return this;
                return new DeclinedFloor(payCents, new AcceptedBest(r.minutePay, r.minutes, r.milePay, r.miles,
                        pay, offer.stops));
            default:
                return pay > payCents ? new DeclinedFloor(pay, r) : this;
        }
    }
}

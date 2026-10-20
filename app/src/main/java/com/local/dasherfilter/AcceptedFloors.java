package com.local.dasherfilter;

import java.math.BigDecimal;
import java.util.Objects;

/** Independent numeric bests from confirmed manual standalone offers; no projected or rounded rates are stored.
 * The pay best is pay per stop (0.6.12): the pay of the accepted offer with the best pay for each stop, the pickup
 * included (0.6.14), kept with that offer's units ({@link AreaScore#payUnits}), never as a rounded rate. */
final class AcceptedFloors {
    static final AcceptedFloors NONE = new AcceptedFloors(0, 0, 0, 0, 0);
    /** The pay of the best pay-per-stop offer accepted, in cents; 0 when none. */
    final int highestPayCents;
    /** That offer's stops, the pickup included ({@link AreaScore#payUnits}); 1 when none or from before 0.6.12. */
    final int payUnits;
    final int milePay;
    final double miles;
    final int minutePay;
    final int minutes;

    AcceptedFloors(int highestPayCents, int milePay, double miles, int minutePay, int minutes) {
        this(highestPayCents, 1, milePay, miles, minutePay, minutes);
    }

    AcceptedFloors(int highestPayCents, int payUnits, int milePay, double miles, int minutePay, int minutes) {
        this.highestPayCents = Math.max(0, highestPayCents);
        this.payUnits = this.highestPayCents > 0 ? Math.max(1, payUnits) : 1;
        boolean validMile = milePay > 0 && Double.isFinite(miles) && miles >= OfferSanity.PLAUSIBLE_MILES;
        this.milePay = validMile ? milePay : 0;
        this.miles = validMile ? miles : 0;
        boolean validMinute = minutePay > 0 && minutes >= OfferSanity.PLAUSIBLE_MINUTES;
        this.minutePay = validMinute ? minutePay : 0;
        this.minutes = validMinute ? minutes : 0;
    }

    boolean hasPerMile() { return milePay > 0; }
    boolean hasPerMinute() { return minutePay > 0; }
    boolean isEmpty() { return highestPayCents == 0 && !hasPerMile() && !hasPerMinute(); }

    /**
     * Caller supplies confirmed manual standalone evidence. Unknown amounts set no corresponding best; unread stops
     * teach nothing on pay, and the pay best moves only for a better pay per stop.
     */
    AcceptedFloors raisedBy(OfferSnapshot offer) {
        if (offer == null || offer.payCents == null || offer.payCents <= 0) return this;
        int pay = offer.payCents;
        int nextMilePay = milePay, nextMinutePay = minutePay, nextMinutes = minutes;
        double nextMiles = miles;
        // Known implausible figures reject the whole lesson; missing optional measures are not invented.
        boolean ratePlausible = !OfferSanity.looksMisread(offer)
                && (offer.miles == null || Double.isFinite(offer.miles));
        if (!ratePlausible) return this;
        // Every readable plausible rate can establish a high; a short trip is not a separate exclusion.
        if (offer.miles != null
                && (!hasPerMile() || BigDecimal.valueOf(pay).multiply(BigDecimal.valueOf(miles))
                .compareTo(BigDecimal.valueOf(milePay).multiply(BigDecimal.valueOf(offer.miles))) > 0)) {
            nextMilePay = pay; nextMiles = offer.miles;
        }
        if (offer.minutes != null
                && (!hasPerMinute() || (long) pay * minutes > (long) minutePay * offer.minutes)) {
            nextMinutePay = pay; nextMinutes = offer.minutes;
        }
        int nextPay = highestPayCents, nextUnits = payUnits;
        Integer units = AreaScore.payUnits(offer.stops);
        if (units != null && (highestPayCents == 0 || (long) pay * payUnits > (long) highestPayCents * units)) {
            nextPay = pay; nextUnits = units;
        }
        AcceptedFloors next = new AcceptedFloors(nextPay, nextUnits, nextMilePay, nextMiles,
                nextMinutePay, nextMinutes);
        return equals(next) ? this : next;
    }

    /** Local policy identity only; never add it to an automatic report or study payload. */
    String stableKey() {
        return highestPayCents + ":" + milePay + ":" + Double.toHexString(miles) + ":" + minutePay + ":" + minutes
                + ":" + payUnits;
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof AcceptedFloors)) return false;
        AcceptedFloors b = (AcceptedFloors) other;
        return highestPayCents == b.highestPayCents && payUnits == b.payUnits && milePay == b.milePay
                && Double.compare(miles, b.miles) == 0 && minutePay == b.minutePay && minutes == b.minutes;
    }

    @Override public int hashCode() { return Objects.hash(highestPayCents, payUnits, milePay, miles, minutePay, minutes); }
}

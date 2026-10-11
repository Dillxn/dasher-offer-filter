package com.local.dasherfilter;

import java.math.BigDecimal;
import java.util.Objects;

/** Independent numeric bests from confirmed manual standalone offers; no projected or rounded rates are stored. */
final class AcceptedFloors {
    static final AcceptedFloors NONE = new AcceptedFloors(0, 0, 0, 0, 0);
    final int highestPayCents;
    final int milePay;
    final double miles;
    final int minutePay;
    final int minutes;

    AcceptedFloors(int highestPayCents, int milePay, double miles, int minutePay, int minutes) {
        this.highestPayCents = Math.max(0, highestPayCents);
        boolean validMile = milePay > 0 && Double.isFinite(miles) && miles >= 2;
        this.milePay = validMile ? milePay : 0;
        this.miles = validMile ? miles : 0;
        boolean validMinute = minutePay > 0 && minutes >= 10;
        this.minutePay = validMinute ? minutePay : 0;
        this.minutes = validMinute ? minutes : 0;
    }

    boolean hasPerMile() { return milePay > 0; }
    boolean hasPerMinute() { return minutePay > 0; }
    boolean isEmpty() { return highestPayCents == 0 && !hasPerMile() && !hasPerMinute(); }

    /** Caller supplies confirmed manual standalone evidence. Unknown amounts set no corresponding best. */
    AcceptedFloors raisedBy(OfferSnapshot offer) {
        if (offer == null || offer.payCents == null || offer.payCents <= 0) return this;
        int pay = offer.payCents;
        int nextMilePay = milePay, nextMinutePay = minutePay, nextMinutes = minutes;
        double nextMiles = miles;
        // Known implausible figures reject the whole lesson; missing optional measures are not invented.
        boolean ratePlausible = (offer.miles == null || Double.isFinite(offer.miles) && offer.miles >= .5)
                && (offer.minutes == null || offer.minutes >= 5) && (offer.stops == null || offer.stops >= 2);
        if (!ratePlausible) return this;
        if (ratePlausible && offer.miles != null && offer.miles >= 2
                && (!hasPerMile() || BigDecimal.valueOf(pay).multiply(BigDecimal.valueOf(miles))
                .compareTo(BigDecimal.valueOf(milePay).multiply(BigDecimal.valueOf(offer.miles))) > 0)) {
            nextMilePay = pay; nextMiles = offer.miles;
        }
        if (ratePlausible && offer.minutes != null && offer.minutes >= 10
                && (!hasPerMinute() || (long) pay * minutes > (long) minutePay * offer.minutes)) {
            nextMinutePay = pay; nextMinutes = offer.minutes;
        }
        AcceptedFloors next = new AcceptedFloors(Math.max(highestPayCents, pay), nextMilePay, nextMiles,
                nextMinutePay, nextMinutes);
        return equals(next) ? this : next;
    }

    /** Local policy identity only; never add it to an automatic report or study payload. */
    String stableKey() {
        return highestPayCents + ":" + milePay + ":" + Double.toHexString(miles) + ":" + minutePay + ":" + minutes;
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof AcceptedFloors)) return false;
        AcceptedFloors b = (AcceptedFloors) other;
        return highestPayCents == b.highestPayCents && milePay == b.milePay
                && Double.compare(miles, b.miles) == 0 && minutePay == b.minutePay && minutes == b.minutes;
    }

    @Override public int hashCode() { return Objects.hash(highestPayCents, milePay, miles, minutePay, minutes); }
}

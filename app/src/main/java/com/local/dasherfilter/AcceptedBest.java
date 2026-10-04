package com.local.dasherfilter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The best pay per minute, per mile, per stop and per observed total item among standalone offers the user accepted.
 * Each is kept as the accepted pay and the amount it covered, so "at least as good as your best" stays exact: an offer needs
 * {@code bestPay × itsAmount ÷ bestAmount}, rounded up to the cent.
 */
final class AcceptedBest {
    static final AcceptedBest NONE = new AcceptedBest(0, 0, 0, 0, 0, 0);
    /**
     * Readings below these are misreads ("1 min", "0.1 mi", "1 stop" for an order that is always two), and an offer
     * with any of them sets no best at all: one wrong figure would otherwise lock later offers out until Reset.
     */
    static final int PLAUSIBLE_MINUTES = 5;
    static final double PLAUSIBLE_MILES = 0.5;
    static final int PLAUSIBLE_STOPS = 2;
    /**
     * Short trips pay mostly base pay, so their pay per mile and per minute run far above any longer trip's: a
     * $7.50 trip of 0.6 mi is $12.50/mi, which would make a normal 5 mi offer need $62.50. Only trips at least this
     * long set those two bests.
     */
    static final double RATE_SETTING_MILES = 2.0;
    static final int RATE_SETTING_MINUTES = 10;

    final int minutePay;
    final int minutes;
    final int milePay;
    final double miles;
    final int stopPay;
    final int stops;
    final int itemPay;
    final int items;

    AcceptedBest(int minutePay, int minutes, int milePay, double miles, int stopPay, int stops) {
        this(minutePay, minutes, milePay, miles, stopPay, stops, 0, 0);
    }

    AcceptedBest(int minutePay, int minutes, int milePay, double miles, int stopPay, int stops,
                 int itemPay, int items) {
        this.minutePay = minutePay;
        this.minutes = minutes;
        this.milePay = milePay;
        this.miles = miles;
        this.stopPay = stopPay;
        this.stops = stops;
        this.itemPay = itemPay;
        this.items = items;
    }

    boolean hasPerMinute() {
        return minutePay > 0 && minutes > 0;
    }

    boolean hasPerMile() {
        return milePay > 0 && miles > 0;
    }

    boolean hasPerStop() {
        return stopPay > 0 && stops > 0;
    }

    boolean hasPerItem() {
        return itemPay > 0 && items > 0;
    }

    boolean isEmpty() {
        return !hasPerMinute() && !hasPerMile() && !hasPerStop() && !hasPerItem();
    }

    /**
     * This record with any new best an accepted standalone offer sets. Unknown amounts set nothing, trips too short
     * to compare set no distance or time best, and an offer with any misread-looking figure sets nothing at all.
     */
    AcceptedBest raisedBy(OfferSnapshot accepted) {
        Integer pay = accepted.payCents;
        if (pay == null || pay <= 0 || looksMisread(accepted)) return this;
        int newMinutePay = minutePay;
        int newMinutes = minutes;
        int newMilePay = milePay;
        double newMiles = miles;
        int newStopPay = stopPay;
        int newStops = stops;
        int newItemPay = itemPay;
        int newItems = items;
        // a/b beats c/d exactly when a×d > c×b; no division, so no rounding decides a tie.
        if (accepted.minutes != null && accepted.minutes >= RATE_SETTING_MINUTES
                && (!hasPerMinute() || (long) pay * minutes > (long) minutePay * accepted.minutes)) {
            newMinutePay = pay;
            newMinutes = accepted.minutes;
        }
        if (accepted.miles != null && accepted.miles >= RATE_SETTING_MILES
                && (!hasPerMile() || BigDecimal.valueOf(pay).multiply(BigDecimal.valueOf(miles))
                        .compareTo(BigDecimal.valueOf(milePay).multiply(BigDecimal.valueOf(accepted.miles))) > 0)) {
            newMilePay = pay;
            newMiles = accepted.miles;
        }
        if (accepted.stops != null
                && (!hasPerStop() || (long) pay * stops > (long) stopPay * accepted.stops)) {
            newStopPay = pay;
            newStops = accepted.stops;
        }
        // A total item count is independent of route length. Unknown/inapplicable counts retain the old best.
        if (accepted.itemCountApplicable && accepted.items != null
                && (!hasPerItem() || (long) pay * items > (long) itemPay * accepted.items)) {
            newItemPay = pay;
            newItems = accepted.items;
        }
        return new AcceptedBest(newMinutePay, newMinutes, newMilePay, newMiles, newStopPay, newStops,
                newItemPay, newItems);
    }

    static boolean looksMisread(OfferSnapshot offer) {
        return (offer.minutes != null && offer.minutes < PLAUSIBLE_MINUTES)
                || (offer.miles != null && offer.miles < PLAUSIBLE_MILES)
                || (offer.stops != null && offer.stops < PLAUSIBLE_STOPS);
    }

    /** Pay that matches the best accepted pay per minute over {@code offerMinutes}, rounded up. */
    long forMinutes(int offerMinutes) {
        return ceilDivide((long) minutePay * offerMinutes, minutes);
    }

    /** Pay that matches the best accepted pay per mile over {@code offerMiles}, rounded up; saturates. */
    long forMiles(double offerMiles) {
        try {
            return BigDecimal.valueOf(milePay).multiply(BigDecimal.valueOf(offerMiles))
                    .divide(BigDecimal.valueOf(miles), 0, RoundingMode.CEILING).longValueExact();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /** Pay that matches the best accepted pay per stop over {@code offerStops}, rounded up. */
    long forStops(int offerStops) {
        return ceilDivide((long) stopPay * offerStops, stops);
    }

    /** Pay matching the exact accepted total-item rate, rounded up like the existing accepted rates. */
    long forItems(int offerItems) {
        return ceilDivide((long) itemPay * offerItems, items);
    }

    /** "$0.59/min" */
    String perMinuteLabel() {
        return perMinute() + "/min";
    }

    /** "$2.37/mi" */
    String perMileLabel() {
        return perMile() + "/mi";
    }

    /** "$7.10/stop" */
    String perStopLabel() {
        return perStop() + "/stop";
    }

    String perItemLabel() {
        return perItem() + "/item";
    }

    /** "$0.59": the best pay per minute without its unit, rounded as in the labels. */
    String perMinute() {
        return rate(minutePay, BigDecimal.valueOf(minutes));
    }

    String perMile() {
        return rate(milePay, BigDecimal.valueOf(miles));
    }

    String perStop() {
        return rate(stopPay, BigDecimal.valueOf(stops));
    }

    String perItem() {
        return rate(itemPay, BigDecimal.valueOf(items));
    }

    /** The best pay per minute in cents, for drawing only; decisions use the exact pay and amount. */
    double perMinuteCents() {
        return (double) minutePay / minutes;
    }

    double perMileCents() {
        return milePay / miles;
    }

    double perStopCents() {
        return (double) stopPay / stops;
    }

    double perItemCents() {
        return (double) itemPay / items;
    }

    /** "$0.59/min, $2.37/mi, $7.10/stop, $1.42/item", or empty when nothing has been accepted yet. */
    String summary() {
        List<String> rates = new ArrayList<>();
        if (hasPerMinute()) rates.add(perMinuteLabel());
        if (hasPerMile()) rates.add(perMileLabel());
        if (hasPerStop()) rates.add(perStopLabel());
        if (hasPerItem()) rates.add(perItemLabel());
        return String.join(", ", rates);
    }

    private static String rate(int cents, BigDecimal amount) {
        BigDecimal dollars = BigDecimal.valueOf(cents).divide(amount, 4, RoundingMode.HALF_UP)
                .movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
        return String.format(Locale.US, "$%s", dollars.toPlainString());
    }

    private static long ceilDivide(long dividend, long divisor) {
        return (dividend + divisor - 1) / divisor;
    }
}

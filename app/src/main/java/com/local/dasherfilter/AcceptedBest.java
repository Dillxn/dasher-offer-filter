package com.local.dasherfilter;

/**
 * Retired (0.5.0): the best accepted rates the adaptive minimum once learned. Nothing is learned from what the user
 * accepts any more, and no rule reads these values. This deprecated shell keeps older callers compiling until their
 * own work packages remove them: it holds the old record, says whether it is empty, and computes nothing. Every
 * learned-rate method answers "nothing learned"; the plausibility limits live in {@link OfferSanity}.
 */
@Deprecated
final class AcceptedBest {
    static final AcceptedBest NONE = new AcceptedBest(0, 0, 0, 0, 0, 0);
    static final int PLAUSIBLE_MINUTES = OfferSanity.PLAUSIBLE_MINUTES;
    static final double PLAUSIBLE_MILES = OfferSanity.PLAUSIBLE_MILES;
    static final int PLAUSIBLE_STOPS = OfferSanity.PLAUSIBLE_STOPS;
    /** The old shortest trip that could set a per-mile or per-minute best; kept only as a constant. */
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

    /** Nothing is learned from an acceptance any more: always this record, unchanged. */
    AcceptedBest raisedBy(OfferSnapshot accepted) {
        return this;
    }

    /** See {@link OfferSanity#looksMisread}. */
    static boolean looksMisread(OfferSnapshot offer) {
        return OfferSanity.looksMisread(offer);
    }

    /** No learned rate asks anything: 0. */
    long forMinutes(int offerMinutes) {
        return 0;
    }

    /** No learned rate asks anything: 0. */
    long forMiles(double offerMiles) {
        return 0;
    }

    /** No learned rate asks anything: 0. */
    long forStops(int offerStops) {
        return 0;
    }

    /** No learned rate asks anything: 0. */
    long forItems(int offerItems) {
        return 0;
    }

    /** No learned rate to show: empty. */
    String perMinuteLabel() {
        return "";
    }

    String perMileLabel() {
        return "";
    }

    String perStopLabel() {
        return "";
    }

    String perItemLabel() {
        return "";
    }

    String perMinute() {
        return "";
    }

    String perMile() {
        return "";
    }

    String perStop() {
        return "";
    }

    String perItem() {
        return "";
    }

    /** Nothing learned to list: empty. */
    String summary() {
        return "";
    }
}

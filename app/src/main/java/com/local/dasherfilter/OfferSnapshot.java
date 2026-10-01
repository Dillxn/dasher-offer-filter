package com.local.dasherfilter;

import java.util.Locale;

/** Immutable offer facts. A null field is unknown; negative or non-finite inputs are coerced to unknown. */
final class OfferSnapshot {
    static final OfferSnapshot UNKNOWN = new OfferSnapshot(null, null, null, null);

    final Integer payCents;
    final Double miles;
    final Integer minutes;
    final Integer stops;
    /**
     * Set only while pay is unknown because a total sits beside one bare "+$" amount: their sum, the most the offer
     * can pay under any reading of that amount. Never pay; never learned, stored or compared.
     */
    final Integer payAtMostCents;

    OfferSnapshot(Integer payCents, Double miles, Integer minutes, Integer stops) {
        this(payCents, miles, minutes, stops, null);
    }

    OfferSnapshot(Integer payCents, Double miles, Integer minutes, Integer stops, Integer payAtMostCents) {
        this.payCents = nonNegative(payCents);
        this.miles = miles != null && Double.isFinite(miles) && miles >= 0 ? miles : null;
        this.minutes = nonNegative(minutes);
        this.stops = nonNegative(stops);
        this.payAtMostCents = this.payCents == null ? nonNegative(payAtMostCents) : null;
    }

    /** These facts without the bound on unknown pay: what a path that must never use it sees. */
    OfferSnapshot withoutPayBound() {
        return payAtMostCents == null ? this : new OfferSnapshot(payCents, miles, minutes, stops);
    }

    private static Integer nonNegative(Integer value) {
        return value != null && value >= 0 ? value : null;
    }

    /** True when some fact is known in both snapshots and differs, so they cannot describe the same offer. */
    boolean contradicts(OfferSnapshot other) {
        return differ(payCents, other.payCents) || differ(miles, other.miles)
                || differ(minutes, other.minutes) || differ(stops, other.stops);
    }

    /** True when no fact contradicts and at least one fact is known in both and equal. */
    boolean agreesWith(OfferSnapshot other) {
        return !contradicts(other) && (same(payCents, other.payCents) || same(miles, other.miles)
                || same(minutes, other.minutes) || same(stops, other.stops));
    }

    private static boolean same(Object a, Object b) {
        return a != null && a.equals(b);
    }

    private static boolean differ(Object a, Object b) {
        return a != null && b != null && !a.equals(b);
    }

    /** Identity of the facts only; used to tell one offer screen from the next. */
    String fingerprint() {
        return payCents + ":" + miles + ":" + minutes + ":" + stops;
    }

    String summary() {
        return "Pay " + (payCents == null ? "?" : String.format(Locale.US, "$%.2f", payCents / 100.0))
                + (payAtMostCents == null ? "" : String.format(Locale.US, " (at most $%.2f)", payAtMostCents / 100.0))
                + ", miles " + (miles == null ? "?" : miles)
                + ", minutes " + (minutes == null ? "?" : minutes)
                + ", stops " + (stops == null ? "?" : stops);
    }
}

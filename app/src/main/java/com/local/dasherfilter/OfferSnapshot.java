package com.local.dasherfilter;

import java.util.Locale;

/** Immutable offer facts. A null field is unknown; negative or non-finite inputs are coerced to unknown. */
final class OfferSnapshot {
    static final OfferSnapshot UNKNOWN = new OfferSnapshot(null, null, null, null);

    final Integer payCents;
    final Double miles;
    final Integer minutes;
    final Integer stops;

    OfferSnapshot(Integer payCents, Double miles, Integer minutes, Integer stops) {
        this.payCents = nonNegative(payCents);
        this.miles = miles != null && Double.isFinite(miles) && miles >= 0 ? miles : null;
        this.minutes = nonNegative(minutes);
        this.stops = nonNegative(stops);
    }

    private static Integer nonNegative(Integer value) {
        return value != null && value >= 0 ? value : null;
    }

    /** Identity of the facts only; used to tell one offer screen from the next. */
    String fingerprint() {
        return payCents + ":" + miles + ":" + minutes + ":" + stops;
    }

    String summary() {
        return "Pay " + (payCents == null ? "?" : String.format(Locale.US, "$%.2f", payCents / 100.0))
                + ", miles " + (miles == null ? "?" : miles)
                + ", minutes " + (minutes == null ? "?" : minutes)
                + ", stops " + (stops == null ? "?" : stops);
    }
}

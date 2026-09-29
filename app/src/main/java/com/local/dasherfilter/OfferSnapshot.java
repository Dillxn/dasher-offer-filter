package com.local.dasherfilter;

import java.util.Locale;

final class OfferSnapshot {
    final Integer payCents;
    final Double miles;
    final Integer minutes;
    final Integer stops;
    OfferSnapshot(Integer payCents, Double miles, Integer minutes, Integer stops) {
        this.payCents = valid(payCents);
        this.miles = miles != null && Double.isFinite(miles) && miles >= 0 ? miles : null;
        this.minutes = valid(minutes); this.stops = valid(stops);
    }
    private static Integer valid(Integer n) { return n != null && n >= 0 ? n : null; }
    String fingerprint() { return payCents + ":" + miles + ":" + minutes + ":" + stops; }
    String summary() {
        return "Pay " + (payCents == null ? "?" : String.format(Locale.US, "$%.2f", payCents / 100.0)) +
                ", miles " + (miles == null ? "?" : miles) + ", minutes " + (minutes == null ? "?" : minutes) +
                ", stops " + (stops == null ? "?" : stops);
    }
}

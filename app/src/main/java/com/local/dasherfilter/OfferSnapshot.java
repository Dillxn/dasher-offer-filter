package com.local.dasherfilter;

import java.util.Locale;

final class OfferSnapshot {
    final Integer payCents;
    final Double miles;
    final Integer minutes;
    final Integer stops;
    /**
     * Parsed from an Earn by Time (active-hour) offer. Every OfferRule entry point returns REVIEW for it, so a caller that
     * forgets to pass the labels still cannot auto-decline or pass an hourly offer. Not part of the fingerprint.
     */
    final boolean hourly;
    OfferSnapshot(Integer payCents, Double miles, Integer minutes, Integer stops) { this(payCents, miles, minutes, stops, false); }
    OfferSnapshot(Integer payCents, Double miles, Integer minutes, Integer stops, boolean hourly) {
        this.payCents = hourly ? null : valid(payCents);
        this.miles = miles != null && Double.isFinite(miles) && miles >= 0 ? miles : null;
        this.minutes = valid(minutes); this.stops = valid(stops); this.hourly = hourly;
    }
    private static Integer valid(Integer n) { return n != null && n >= 0 ? n : null; }
    /** At least one explicit offer fact (pay, miles, minutes or stops) is known. */
    boolean hasFacts() { return payCents != null || miles != null || minutes != null || stops != null; }
    String fingerprint() { return payCents + ":" + miles + ":" + minutes + ":" + stops; }
    String summary() {
        return "Pay " + (payCents == null ? "?" : String.format(Locale.US, "$%.2f", payCents / 100.0)) +
                ", miles " + (miles == null ? "?" : miles) + ", minutes " + (minutes == null ? "?" : minutes) +
                ", stops " + (stops == null ? "?" : stops) + (hourly ? " (Earn by Time)" : "");
    }
}

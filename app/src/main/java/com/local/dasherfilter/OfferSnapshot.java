package com.local.dasherfilter;

import java.util.Locale;

final class OfferSnapshot {
    final Integer payCents;
    final Double miles;
    final Integer minutes;
    final Integer stops;

    OfferSnapshot(Integer payCents, Double miles, Integer minutes, Integer stops) {
        this.payCents = payCents;
        this.miles = miles;
        this.minutes = minutes;
        this.stops = stops;
    }

    String fingerprint() {
        return payCents + ":" + miles + ":" + minutes + ":" + stops;
    }

    String summary() {
        return String.format(Locale.US, "Pay %s, miles %s, minutes %s, stops %s",
                payCents == null ? "?" : String.format(Locale.US, "$%.2f", payCents / 100.0),
                miles == null ? "?" : miles, minutes == null ? "?" : minutes,
                stops == null ? "?" : stops);
    }
}


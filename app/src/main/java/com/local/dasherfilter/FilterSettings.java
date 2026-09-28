package com.local.dasherfilter;

final class FilterSettings {
    final boolean enabled;
    final int flatCents;
    final int perMileCents;
    final int perMinuteCents;
    final int extraStopCents;
    final int maxStops;
    final boolean risingOffers;
    final int lastAcceptedCents;

    FilterSettings(boolean enabled, int flatCents, int perMileCents,
                   int perMinuteCents, int extraStopCents, int maxStops) {
        this(enabled, flatCents, perMileCents, perMinuteCents, extraStopCents, maxStops, false, 0);
    }

    FilterSettings(boolean enabled, int flatCents, int perMileCents,
                   int perMinuteCents, int extraStopCents, int maxStops,
                   boolean risingOffers, int lastAcceptedCents) {
        this.enabled = enabled;
        this.flatCents = flatCents;
        this.perMileCents = perMileCents;
        this.perMinuteCents = perMinuteCents;
        this.extraStopCents = extraStopCents;
        this.maxStops = maxStops;
        this.risingOffers = risingOffers;
        this.lastAcceptedCents = lastAcceptedCents;
    }

}

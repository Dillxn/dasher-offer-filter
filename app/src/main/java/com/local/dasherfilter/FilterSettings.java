package com.local.dasherfilter;

/** Saved rules. Every threshold uses zero to mean "disabled". */
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

    /** True when at least one rule can reject or require review of an offer. */
    boolean hasAnyRule() {
        return flatCents > 0 || perMileCents > 0 || perMinuteCents > 0 || extraStopCents > 0
                || maxStops > 0 || risingOffers;
    }

    /** Rules whose cost scales with an add-on's own miles, minutes, or stops. */
    boolean hasMarginalRule() {
        return perMileCents > 0 || perMinuteCents > 0 || extraStopCents > 0;
    }

    FilterSettings withEnabled(boolean value) {
        return new FilterSettings(value, flatCents, perMileCents, perMinuteCents, extraStopCents, maxStops,
                risingOffers, lastAcceptedCents);
    }

    /** Add-on routes are judged without the standalone rising-payout baseline. */
    FilterSettings withoutRisingBaseline() {
        return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, extraStopCents, maxStops,
                false, lastAcceptedCents);
    }
}

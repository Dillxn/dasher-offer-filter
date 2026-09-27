package com.local.dasherfilter;

final class FilterSettings {
    final boolean enabled;
    final int flatCents;
    final int perMileCents;
    final int perMinuteCents;
    final int extraStopCents;

    FilterSettings(boolean enabled, int flatCents, int perMileCents,
                   int perMinuteCents, int extraStopCents) {
        this.enabled = enabled;
        this.flatCents = flatCents;
        this.perMileCents = perMileCents;
        this.perMinuteCents = perMinuteCents;
        this.extraStopCents = extraStopCents;
    }

}

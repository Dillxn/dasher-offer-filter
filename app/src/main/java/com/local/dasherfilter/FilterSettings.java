package com.local.dasherfilter;

import java.util.List;
import java.util.Objects;

/**
 * Immutable saved rules. Zero disables a numeric rule. Rebuild settings only through the with* copy helpers so a
 * pause, tile tap, or save never drops a field it does not edit.
 */
final class FilterSettings {
    /** A rising baseline applies only while the observed accept is at most 8 hours old and not more than 5 minutes in the future. */
    static final long BASELINE_MAX_AGE_MS = 8L * 60 * 60 * 1000;
    static final long BASELINE_FUTURE_SKEW_MS = 5L * 60 * 1000;
    final boolean enabled;
    final int flatCents;
    final int perMileCents;
    final int perMinuteCents;
    final int perHourCents;
    final int extraStopCents;
    final int maxStops;
    /** Maximum miles in hundredths (600 = 6.00 mi); 0 disables. */
    final int maxMilesHundredths;
    /** Normalized avoid-list terms (see StoreMatcher); unmodifiable, at most 50. */
    final List<String> avoidStores;
    final boolean risingOffers;
    final int lastAcceptedCents;
    /** Wall-clock ms of the observed accept behind lastAcceptedCents; 0 = unknown (a legacy baseline never applies). */
    final long lastAcceptedAt;
    /** Opt-in runaway guard; 0 disables. */
    final int maxDeclinesPerHour;

    FilterSettings(boolean enabled, int flatCents, int perMileCents,
                   int perMinuteCents, int extraStopCents, int maxStops) {
        this(enabled, flatCents, perMileCents, perMinuteCents, extraStopCents, maxStops, false, 0);
    }

    FilterSettings(boolean enabled, int flatCents, int perMileCents,
                   int perMinuteCents, int extraStopCents, int maxStops,
                   boolean risingOffers, int lastAcceptedCents) {
        this(enabled, flatCents, perMileCents, perMinuteCents, 0, extraStopCents, maxStops, 0, null, risingOffers, lastAcceptedCents, 0L, 0);
    }

    FilterSettings(boolean enabled, int flatCents, int perMileCents, int perMinuteCents, int perHourCents,
                   int extraStopCents, int maxStops, int maxMilesHundredths, List<String> avoidStores,
                   boolean risingOffers, int lastAcceptedCents, long lastAcceptedAt, int maxDeclinesPerHour) {
        this.enabled = enabled;
        this.flatCents = flatCents;
        this.perMileCents = perMileCents;
        this.perMinuteCents = perMinuteCents;
        this.perHourCents = perHourCents;
        this.extraStopCents = extraStopCents;
        this.maxStops = maxStops;
        this.maxMilesHundredths = maxMilesHundredths;
        this.avoidStores = StoreMatcher.sanitize(avoidStores);
        this.risingOffers = risingOffers;
        this.lastAcceptedCents = lastAcceptedCents;
        this.lastAcceptedAt = lastAcceptedAt;
        this.maxDeclinesPerHour = maxDeclinesPerHour;
    }

    FilterSettings withEnabled(boolean v) { return new FilterSettings(v, flatCents, perMileCents, perMinuteCents, perHourCents, extraStopCents, maxStops, maxMilesHundredths, avoidStores, risingOffers, lastAcceptedCents, lastAcceptedAt, maxDeclinesPerHour); }
    FilterSettings withFlatCents(int v) { return new FilterSettings(enabled, v, perMileCents, perMinuteCents, perHourCents, extraStopCents, maxStops, maxMilesHundredths, avoidStores, risingOffers, lastAcceptedCents, lastAcceptedAt, maxDeclinesPerHour); }
    FilterSettings withPerMileCents(int v) { return new FilterSettings(enabled, flatCents, v, perMinuteCents, perHourCents, extraStopCents, maxStops, maxMilesHundredths, avoidStores, risingOffers, lastAcceptedCents, lastAcceptedAt, maxDeclinesPerHour); }
    FilterSettings withPerMinuteCents(int v) { return new FilterSettings(enabled, flatCents, perMileCents, v, perHourCents, extraStopCents, maxStops, maxMilesHundredths, avoidStores, risingOffers, lastAcceptedCents, lastAcceptedAt, maxDeclinesPerHour); }
    FilterSettings withPerHourCents(int v) { return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, v, extraStopCents, maxStops, maxMilesHundredths, avoidStores, risingOffers, lastAcceptedCents, lastAcceptedAt, maxDeclinesPerHour); }
    FilterSettings withExtraStopCents(int v) { return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, perHourCents, v, maxStops, maxMilesHundredths, avoidStores, risingOffers, lastAcceptedCents, lastAcceptedAt, maxDeclinesPerHour); }
    FilterSettings withMaxStops(int v) { return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, perHourCents, extraStopCents, v, maxMilesHundredths, avoidStores, risingOffers, lastAcceptedCents, lastAcceptedAt, maxDeclinesPerHour); }
    FilterSettings withMaxMilesHundredths(int v) { return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, perHourCents, extraStopCents, maxStops, v, avoidStores, risingOffers, lastAcceptedCents, lastAcceptedAt, maxDeclinesPerHour); }
    FilterSettings withAvoidStores(List<String> v) { return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, perHourCents, extraStopCents, maxStops, maxMilesHundredths, v, risingOffers, lastAcceptedCents, lastAcceptedAt, maxDeclinesPerHour); }
    FilterSettings withRisingOffers(boolean v) { return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, perHourCents, extraStopCents, maxStops, maxMilesHundredths, avoidStores, v, lastAcceptedCents, lastAcceptedAt, maxDeclinesPerHour); }
    FilterSettings withLastAccepted(int cents, long at) { return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, perHourCents, extraStopCents, maxStops, maxMilesHundredths, avoidStores, risingOffers, cents, at, maxDeclinesPerHour); }
    FilterSettings withMaxDeclinesPerHour(int v) { return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, perHourCents, extraStopCents, maxStops, maxMilesHundredths, avoidStores, risingOffers, lastAcceptedCents, lastAcceptedAt, v); }

    /** Any rule that sets a pay requirement: flat, per mile/minute/hour, extra-stop fee, or rising. */
    boolean hasPayRule() { return flatCents > 0 || perMileCents > 0 || perMinuteCents > 0 || perHourCents > 0 || extraStopCents > 0 || risingOffers; }
    /** Any rule other than the avoid list. Store rules alone never produce a passing verdict. */
    boolean hasNonStoreRule() { return hasPayRule() || maxStops > 0 || maxMilesHundredths > 0; }
    /** At least one rule, so auto-decline may be enabled. */
    boolean hasAnyRule() { return hasNonStoreRule() || !avoidStores.isEmpty(); }
    /** The saved baseline exists, is dated (not legacy), at most 8 h old, and not more than 5 min in the future. */
    boolean baselineFresh(long nowWallMs) {
        return lastAcceptedCents > 0 && lastAcceptedAt > 0 && nowWallMs > 0 &&
                nowWallMs - lastAcceptedAt <= BASELINE_MAX_AGE_MS && lastAcceptedAt - nowWallMs <= BASELINE_FUTURE_SKEW_MS;
    }
    /** The rising rule currently requires beating lastAcceptedCents. */
    boolean baselineActive(long nowWallMs) { return risingOffers && baselineFresh(nowWallMs); }

    @Override public boolean equals(Object o) {
        if (!(o instanceof FilterSettings)) return false;
        FilterSettings s = (FilterSettings) o;
        return enabled == s.enabled && flatCents == s.flatCents && perMileCents == s.perMileCents && perMinuteCents == s.perMinuteCents &&
                perHourCents == s.perHourCents && extraStopCents == s.extraStopCents && maxStops == s.maxStops &&
                maxMilesHundredths == s.maxMilesHundredths && avoidStores.equals(s.avoidStores) && risingOffers == s.risingOffers &&
                lastAcceptedCents == s.lastAcceptedCents && lastAcceptedAt == s.lastAcceptedAt && maxDeclinesPerHour == s.maxDeclinesPerHour;
    }
    @Override public int hashCode() { return Objects.hash(enabled, flatCents, perMileCents, perMinuteCents, perHourCents, extraStopCents, maxStops, maxMilesHundredths, avoidStores, risingOffers, lastAcceptedCents, lastAcceptedAt, maxDeclinesPerHour); }
    @Override public String toString() {
        return "enabled=" + enabled + " flat=" + flatCents + " perMile=" + perMileCents + " perMinute=" + perMinuteCents + " perHour=" + perHourCents +
                " extraStop=" + extraStopCents + " maxStops=" + maxStops + " maxMiles(1/100)=" + maxMilesHundredths + " avoidStores=" + avoidStores.size() +
                " rising=" + risingOffers + " lastAccepted=" + lastAcceptedCents + "@" + lastAcceptedAt + " maxDeclinesPerHour=" + maxDeclinesPerHour;
    }
}

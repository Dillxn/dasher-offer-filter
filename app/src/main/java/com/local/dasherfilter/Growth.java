package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * Your minimums grow (0.5.1, the owner's decision of 7 October 2026): when Autopilot's bar has stayed above 100% of the
 * minimums for a while, offers have paid above them, and that gain moves into the minimums. Pure and deterministic, with
 * no Android class: it reads only Autopilot's own recorded bars ({@link Autopilot.OfferRecord#bar}), never what the
 * user accepted or declined, and its arithmetic is exact whole numbers ({@code long}), no floating point.
 *
 * <ul>
 *   <li>Evidence ({@link #evidence}): of Autopilot's counted lines ({@link Autopilot#countedLines}, newest first), only
 *   the rules-model-2 lines decided with Autopilot on ({@link Autopilot.OfferRecord#autopilot}) at or after the time
 *   the current minimums took effect. Growth is due when the newest {@value #OFFERS} of those all have a bar of at
 *   least {@value #LEAST_BAR}% and fall on at least {@value #LEAST_DAYS} calendar days in the phone's time zone; it is
 *   {@code g = min(10, lowest bar − 100)} points, so 3 ≤ g ≤ 10. It waits while the bar now is below {@code 100 + g}
 *   (Autopilot lowered it since those offers): grown then, the bar could not come down with the minimums, so offers
 *   right after it would be asked more, not the same.</li>
 *   <li>Effect ({@link #grown}): every set money minimum m becomes {@code ⌈m × (100 + g) ÷ 100⌉} in its own stored unit
 *   (cents; cents a mile; cents a minute, shown ×60 an hour); an unset one stays unset; max stops never changes. The
 *   bar becomes {@code max(100, round(bar × 100 ÷ (100 + g)))}, so an offer judged right after it meets the same
 *   requirement, within rounding. A minimum that would pass the knob's top ({@link FilterSettings#MOST_CENTS}) cannot
 *   grow, and then nothing grows: no growth is due.</li>
 * </ul>
 *
 * When and where it is applied (only at Autopilot's safe point, by compare-and-set) is {@link AutopilotRuntime}'s and
 * {@link FilterStore#growMinimums}'s.
 */
final class Growth {
    /** The newest lines the evidence looks at. */
    static final int OFFERS = 30;
    /** Each of them had a bar of at least this, in percent of the minimums. */
    static final int LEAST_BAR = 103;
    /** They fall on at least this many calendar days. */
    static final int LEAST_DAYS = 2;
    /** One growth is at most this many points. */
    static final int MOST_PERCENT = 10;
    private static final long DAY_MS = 24L * 60 * 60_000;

    /** The minimums and the bar before and after one growth of {@link #percent} points (money in stored units). */
    static final class Grown {
        final int percent;
        final int flatBefore;
        final int mileBefore;
        final int minuteBefore;
        final int barBefore;
        final int flatAfter;
        final int mileAfter;
        final int minuteAfter;
        final int barAfter;

        Grown(int percent, int flatBefore, int mileBefore, int minuteBefore, int barBefore, int flatAfter,
              int mileAfter, int minuteAfter, int barAfter) {
            this.percent = percent;
            this.flatBefore = flatBefore;
            this.mileBefore = mileBefore;
            this.minuteBefore = minuteBefore;
            this.barBefore = barBefore;
            this.flatAfter = flatAfter;
            this.mileAfter = mileAfter;
            this.minuteAfter = minuteAfter;
            this.barAfter = barAfter;
        }
    }

    /**
     * Why growth is due, and what it does: the newest {@link #offers} lines Autopilot judged under these minimums, on
     * {@link #days} calendar days, the lowest bar among them, and the growth worked out for exactly the minimums, bar
     * and "minimums since" ({@link #since}) it was computed for, which a compare-and-set must find unchanged.
     */
    static final class Evidence {
        final int offers;
        final int days;
        final int lowestBar;
        /** When the minimums it was computed for took effect (wall clock). */
        final long since;
        final Grown grown;

        Evidence(int offers, int days, int lowestBar, long since, Grown grown) {
            this.offers = offers;
            this.days = days;
            this.lowestBar = lowestBar;
            this.since = since;
            this.grown = grown;
        }

        /** {@code g}, the growth in points (3–10). */
        int percent() {
            return grown.percent;
        }
    }

    /**
     * The growth due for {@code rules} from {@code counted} (Autopilot's counted lines, {@link Autopilot#countedLines},
     * newest first), with the minimums in effect since {@code since} (wall clock) and calendar days in {@code zone};
     * null when none is due: no money minimum, fewer than {@value #OFFERS} lines of Autopilot's under these minimums,
     * any of the newest {@value #OFFERS} below {@value #LEAST_BAR}%, all of them on one day, the bar now below
     * {@code 100 + g}, or a minimum that cannot grow ({@link #grown}).
     */
    static Evidence evidence(FilterSettings rules, List<Autopilot.OfferRecord> counted, long since, TimeZone zone) {
        if (rules == null || !rules.hasMonetaryRule() || counted == null || zone == null) return null;
        List<Autopilot.OfferRecord> lines = new ArrayList<>(OFFERS);
        for (Autopilot.OfferRecord record : counted) {
            if (lines.size() == OFFERS) break;
            if (record == null || record.model < DecisionLog.MODEL || !record.autopilot || record.at < since) continue;
            lines.add(record);
        }
        if (lines.size() < OFFERS) return null;
        int lowest = Integer.MAX_VALUE;
        Set<Long> days = new HashSet<>();
        for (Autopilot.OfferRecord record : lines) {
            lowest = Math.min(lowest, record.bar);
            days.add(day(record.at, zone));
        }
        if (lowest < LEAST_BAR || days.size() < LEAST_DAYS) return null;
        int percent = Math.min(MOST_PERCENT, lowest - FilterSettings.BAR_AT_MINIMUMS);
        // The bar comes down with the minimums only from 100 + g: below that, growing would ask offers for more.
        if (rules.minimumScalePercent < FilterSettings.BAR_AT_MINIMUMS + percent) return null;
        Grown grown = grown(rules, percent);
        return grown == null ? null : new Evidence(lines.size(), days.size(), lowest, since, grown);
    }

    /** The calendar day of {@code at} (wall clock) in {@code zone}, as a day number. */
    static long day(long at, TimeZone zone) {
        return Math.floorDiv(at + zone.getOffset(at), DAY_MS);
    }

    /**
     * {@code rules}' minimums and bar grown by {@code percent} points (3–10); null when a set minimum would pass the
     * knob's top, {@link FilterSettings#MOST_CENTS}, or the percent is outside 3–10.
     */
    static Grown grown(FilterSettings rules, int percent) {
        if (percent < LEAST_BAR - FilterSettings.BAR_AT_MINIMUMS || percent > MOST_PERCENT) return null;
        int flat = grownCents(rules.flatCents, percent);
        int mile = grownCents(rules.perMileCents, percent);
        int minute = grownCents(rules.perMinuteCents, percent);
        if (flat > FilterSettings.MOST_CENTS || mile > FilterSettings.MOST_CENTS
                || minute > FilterSettings.MOST_CENTS) return null;
        return new Grown(percent, rules.flatCents, rules.perMileCents, rules.perMinuteCents, rules.minimumScalePercent,
                flat, mile, minute, barAfter(rules.minimumScalePercent, percent));
    }

    /** A minimum grown by {@code percent} points, rounded up to a whole unit: {@code ⌈m × (100 + g) ÷ 100⌉}; 0 stays 0. */
    static int grownCents(int cents, int percent) {
        if (cents <= 0) return 0;
        long scaled = (long) cents * (100 + percent);
        long grown = (scaled + 99) / 100;
        return grown > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) grown;
    }

    /**
     * The bar after a growth of {@code percent} points: {@code max(100, round(bar × 100 ÷ (100 + g)))}, rounded half
     * up, so the bar times the grown minimums asks what the bar asked of the minimums before, within rounding.
     */
    static int barAfter(int bar, int percent) {
        long grown = 100L + percent;
        long rounded = (200L * bar + grown) / (2 * grown);
        return (int) Math.max(FilterSettings.BAR_AT_MINIMUMS, rounded);
    }

    private Growth() {}
}

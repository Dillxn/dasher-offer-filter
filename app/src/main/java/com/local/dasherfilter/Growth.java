package com.local.dasherfilter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * Your minimums grow (0.5.1, the owner's decision of 7 October 2026): when offers have paid above the minimums for a
 * while, that gain moves into them. From 0.6.21 it follows the offers themselves, Autopilot on or off (the owner, 15
 * October 2026: "mins growing should not be narried to autopilot"; before, it read Autopilot's recorded bars, so it
 * never grew while Autopilot was off). Pure and deterministic, with no Android class: it reads what the offers paid,
 * never what the user accepted or declined, and its arithmetic is exact whole numbers ({@code long}), no floating point.
 *
 * <ul>
 *   <li>Evidence ({@link #evidence}): of the counted lines ({@link Autopilot#countedLines}, newest first), those
 *   scored under exactly these rules ({@link #samePolicy}) at or after the time the current minimums took effect.
 *   Growth is due when the newest {@value #OFFERS} of those fall on at least {@value #LEAST_DAYS} calendar days in the
 *   phone's time zone and 1 in 5 of them ({@value #SHARE}%) paid at least {@value #LEAST_BAR}% of the minimums: their
 *   share bar ({@link Autopilot#shareBar}, as pay first's bar is worked out, on each offer's score
 *   {@link AreaScore#passThreshold}; pay not read counts as nothing above) is {@value #LEAST_BAR} or more. It is
 *   {@code g = min(10, share bar − 100)} points, so 3 ≤ g ≤ 10.</li>
 *   <li>Effect ({@link #grown}): every set money minimum m becomes {@code ⌈m × (100 + g) ÷ 100⌉} in its own stored unit
 *   (cents; cents a mile; cents a minute, shown ×60 an hour); an unset one stays unset; max stops never changes. A bar
 *   of Autopilot's above 100 becomes {@code max(100, round(bar × 100 ÷ (100 + g)))}, so an offer judged right after it
 *   meets the same requirement, within rounding, as far as the bar can come down; a bar at or below 100 (exactly 100
 *   while Autopilot is off) stays, so the minimums' growth asks more of the next offers. A minimum that would pass the
 *   knob's top ({@link FilterSettings#MOST_CENTS}) cannot grow, and then nothing grows: no growth is due.</li>
 * </ul>
 *
 * When and where it is applied (only at Autopilot's safe point, by compare-and-set, Autopilot on or off) is
 * {@link AutopilotRuntime}'s and {@link FilterStore#growMinimums}'s.
 */
final class Growth {
    /** The newest lines the evidence looks at. */
    static final int OFFERS = 30;
    /** 1 in 5 of them paid at least this, in percent of the minimums. */
    static final int LEAST_BAR = 103;
    /** The share of them, in percent, that paid at least the growth's mark: pay first's 1 in 5. */
    static final int SHARE = Autopilot.PAY_FIRST_FLOOR;
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
     * Why growth is due, and what it does: the newest {@link #offers} lines scored under these minimums, on
     * {@link #days} calendar days, the share bar 1 in 5 of them met ({@link #shareBar}), and the growth worked out for
     * exactly the minimums, bar and "minimums since" ({@link #since}) it was computed for, which a compare-and-set must
     * find unchanged.
     */
    static final class Evidence {
        final int offers;
        final int days;
        /** The highest score, in percent of the minimums, that 1 in 5 of them met (at most 150). */
        final int shareBar;
        /** When the minimums it was computed for took effect (wall clock). */
        final long since;
        final Grown grown;

        Evidence(int offers, int days, int shareBar, long since, Grown grown) {
            this.offers = offers;
            this.days = days;
            this.shareBar = shareBar;
            this.since = since;
            this.grown = grown;
        }

        /** {@code g}, the growth in points (3–10). */
        int percent() {
            return grown.percent;
        }
    }

    /**
     * The growth due for {@code rules} from {@code counted} (the counted lines, {@link Autopilot#countedLines}, newest
     * first), with the minimums in effect since {@code since} (wall clock) and calendar days in {@code zone}; null when
     * none is due: no money minimum, fewer than {@value #OFFERS} lines scored under these minimums, all of the newest
     * {@value #OFFERS} on one day, fewer than 1 in 5 of them at {@value #LEAST_BAR}% or more, or a minimum that cannot
     * grow ({@link #grown}). Whether Autopilot was on for them, or is now, does not matter (0.6.21).
     */
    static Evidence evidence(FilterSettings rules, List<Autopilot.OfferRecord> counted, long since, TimeZone zone) {
        if (rules == null || !rules.hasMonetaryRule() || learningDominates(rules) || counted == null || zone == null) return null;
        List<Autopilot.OfferRecord> lines = new ArrayList<>(OFFERS);
        for (Autopilot.OfferRecord record : counted) {
            if (lines.size() == OFFERS) break;
            if (record == null || record.at < since || !samePolicy(record, rules)) continue;
            lines.add(record);
        }
        if (lines.size() < OFFERS) return null;
        int[] scores = new int[lines.size()];
        Set<Long> days = new HashSet<>();
        for (int i = 0; i < scores.length; i++) {
            scores[i] = score(rules, lines.get(i));
            days.add(day(lines.get(i).at, zone));
        }
        if (days.size() < LEAST_DAYS) return null;
        int bar = Autopilot.shareBar(scores, SHARE).bar;
        if (bar < LEAST_BAR) return null;
        Grown grown = grown(rules, Math.min(MOST_PERCENT, bar - FilterSettings.BAR_AT_MINIMUMS));
        return grown == null ? null : new Evidence(lines.size(), days.size(), bar, since, grown);
    }

    /**
     * What a line's offer paid, in percent of {@code rules}' minimums ({@link AreaScore#passThreshold}): a skipped
     * store's offer, one with more stops than allowed, and one whose pay was not read count as 0, nothing above them.
     */
    private static int score(FilterSettings rules, Autopilot.OfferRecord line) {
        if (line.skipped || line.facts == null) return 0;
        int score = AreaScore.passThreshold(rules, line.facts);
        return score == Integer.MAX_VALUE ? 0 : score;
    }

    /** Growing saved knobs cannot compensate a lower bar on stronger learned floors; wait for Adopt or learner off. */
    static boolean learningDominates(FilterSettings rules) {
        if (rules == null || !rules.acceptanceLearning) return false;
        AcceptedFloors proof = rules.accepted;
        // The learned pay ask for one stop (the pickup counts as one), best pay ÷ its units + 1¢, beats pay per stop.
        return proof.highestPayCents > 0
                && (long) proof.highestPayCents + proof.payUnits > (long) rules.flatCents * proof.payUnits
                || proof.hasPerMile() && BigDecimal.valueOf(rules.perMileCents)
                        .multiply(BigDecimal.valueOf(proof.miles)).compareTo(BigDecimal.valueOf(proof.milePay)) < 0
                || proof.hasPerMinute() && (long) rules.perMinuteCents * proof.minutes < proof.minutePay;
    }

    /** Model 2 proof applies only to the unchanged strict, learner-off regime. New proof names its exact policy. */
    private static boolean samePolicy(Autopilot.OfferRecord record, FilterSettings rules) {
        if (record.model == DecisionLog.STRICT_MODEL) return !rules.scoreByArea && !rules.acceptanceLearning;
        return record.model == DecisionLog.MODEL && record.scoringRules != null
                && record.scoringRules.scoringKey().equals(rules.scoringKey());
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
        if (learningDominates(rules)) return null;
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
     * up, so the bar times the grown minimums asks what the bar asked of the minimums before, within rounding; a bar at
     * or below 100 stays (0.6.21: before, growth waited for a bar of {@code 100 + g}).
     */
    static int barAfter(int bar, int percent) {
        if (bar <= FilterSettings.BAR_AT_MINIMUMS) return bar;
        long grown = 100L + percent;
        long rounded = (200L * bar + grown) / (2 * grown);
        return (int) Math.max(FilterSettings.BAR_AT_MINIMUMS, rounded);
    }

    private Growth() {}
}

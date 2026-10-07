package com.local.dasherfilter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Your minimums grow (0.5.1, the owner's decision of 7 October 2026), the evidence and the arithmetic, exactly: of
 * Autopilot's counted lines, only those it decided (model 2, "auto") since the minimums took effect count; the newest
 * 30 must all be at a bar of 103% or more and fall on two calendar days in the phone's time zone; the growth is
 * {@code min(10, lowest − 100)} points; each set minimum is rounded up in its own unit, an unset one stays unset, and
 * the bar comes down so an offer is asked the same, within rounding.
 */
public final class GrowthTest {
    /** Cincinnati's time zone, where the phone is. */
    private static final TimeZone EASTERN = TimeZone.getTimeZone("America/New_York");
    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;
    /** Noon on Tuesday 6 October 2026 in Cincinnati (16:00 UTC). */
    private static final long TUESDAY_NOON = 1_791_302_400_000L;
    /** Typical minimums, $4.00 · $1.00/mi · $15/hr, with Autopilot at 112%. */
    private static final FilterSettings TYPICAL = rules(400, 100, 25, 112);

    private static FilterSettings rules(int flat, int mile, int minute, int bar) {
        return new FilterSettings(true, flat, mile, minute, 0, true, FilterSettings.GOAL_TOP_TIER, bar);
    }

    /** A line Autopilot decided at {@code bar} at {@code at}, each a distinct offer (no two are deduplicated). */
    private static Autopilot.OfferRecord line(long at, int bar) {
        return line(at, bar, true, DecisionLog.MODEL);
    }

    private static Autopilot.OfferRecord line(long at, int bar, boolean auto, int model) {
        int pay = 900 + (int) Math.floorMod(at / MIN, 5_000L);
        return new Autopilot.OfferRecord(new OfferSnapshot(pay, 6.6, 27, 2), at, false, false, false, false, false,
                bar, auto, model);
    }

    /**
     * {@code count} lines at {@code bar}, newest first: half on Tuesday afternoon, half on Wednesday afternoon (or all
     * on Tuesday when {@code oneDay}), five minutes apart.
     */
    private static List<Autopilot.OfferRecord> lines(int count, int bar, boolean oneDay) {
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        int half = (count + 1) / 2;
        for (int i = 0; i < count; i++) {
            boolean wednesday = !oneDay && i < half;
            int step = oneDay || wednesday ? i : i - half;
            lines.add(line(TUESDAY_NOON + (wednesday ? 24 * HOUR : 0) + 4 * HOUR - 5 * MIN * step, bar));
        }
        Collections.sort(lines, (a, b) -> Long.compare(b.at, a.at));
        return lines;
    }

    private static Growth.Evidence evidence(FilterSettings rules, List<Autopilot.OfferRecord> newestFirst, long since) {
        return Growth.evidence(rules, Autopilot.countedLines(newestFirst), since, EASTERN);
    }

    // ---- The evidence ----

    @Test public void thirtyOffersAboveYourMinimumsOnTwoDaysGrowThemByTheLowestBarsGain() {
        List<Autopilot.OfferRecord> history = lines(30, 115, false);
        // One of them at 108%: the lowest bar sets the growth, 8 points.
        history.set(7, line(history.get(7).at, 108));
        Growth.Evidence due = evidence(TYPICAL, history, TUESDAY_NOON - HOUR);
        assertNotNull(due);
        assertEquals(30, due.offers);
        assertEquals(2, due.days);
        assertEquals(108, due.lowestBar);
        assertEquals(8, due.percent());
        assertEquals(TUESDAY_NOON - HOUR, due.since);
        // The spec's example: $4.00 · $1.00/mi · $15/hr grow 8% to $4.32 · $1.08/mi · $16.20/hr, and the bar 112% → 104%.
        Growth.Grown grown = due.grown;
        assertEquals(400, grown.flatBefore);
        assertEquals(100, grown.mileBefore);
        assertEquals(25, grown.minuteBefore);
        assertEquals(112, grown.barBefore);
        assertEquals(432, grown.flatAfter);
        assertEquals(108, grown.mileAfter);
        assertEquals(27, grown.minuteAfter);
        assertEquals(1620, grown.minuteAfter * 60);
        assertEquals(104, grown.barAfter);
    }

    @Test public void twentyNineOffersAreNotEnough() {
        assertNull(evidence(TYPICAL, lines(29, 120, false), TUESDAY_NOON - HOUR));
        assertNotNull(evidence(TYPICAL, lines(30, 120, false), TUESDAY_NOON - HOUR));
    }

    @Test public void oneOfTheNewestThirtyAtOneHundredAndTwoHoldsTheMinimums() {
        List<Autopilot.OfferRecord> history = lines(30, 120, false);
        history.set(29, line(history.get(29).at, 102));
        assertNull("one offer at 102% among the newest 30", evidence(TYPICAL, history, TUESDAY_NOON - HOUR));
        history.set(29, line(history.get(29).at, 103));
        Growth.Evidence at103 = evidence(TYPICAL, history, TUESDAY_NOON - HOUR);
        assertNotNull("103% is enough", at103);
        assertEquals("and grows them 3 points", 3, at103.percent());
        // An older line at 102%, past the newest 30, says nothing.
        List<Autopilot.OfferRecord> longer = new ArrayList<>(lines(30, 120, false));
        longer.add(line(longer.get(29).at - 5 * MIN, 102));
        assertNotNull(evidence(TYPICAL, longer, TUESDAY_NOON - 2 * HOUR));
    }

    @Test public void oneCalendarDayInThePhonesTimeZoneIsNotEnough() {
        assertNull("one afternoon", evidence(TYPICAL, lines(30, 120, true), TUESDAY_NOON - HOUR));
        // An evening dash past midnight in Cincinnati (20:49 to 01:10): offers before and after midnight are two days
        // there, though all 30 fall on one day in UTC (00:49 to 05:10 Wednesday).
        long midnight = TUESDAY_NOON + 12 * HOUR;
        List<Autopilot.OfferRecord> evening = new ArrayList<>();
        for (int i = 0; i < 30; i++) evening.add(line(midnight + 70 * MIN - 9 * MIN * i, 120));
        Growth.Evidence due = evidence(TYPICAL, evening, TUESDAY_NOON);
        assertNotNull(due);
        assertEquals(2, due.days);
        assertNull("the same instants in UTC are one day",
                Growth.evidence(TYPICAL, Autopilot.countedLines(evening), TUESDAY_NOON, TimeZone.getTimeZone("UTC")));
        assertEquals(Growth.day(midnight - MIN, EASTERN) + 1, Growth.day(midnight, EASTERN));
    }

    @Test public void onlyLinesDecidedSinceTheMinimumsTookEffectCount() {
        List<Autopilot.OfferRecord> history = lines(30, 120, false);
        long oldest = history.get(29).at;
        assertNotNull("decided at the very moment they took effect counts", evidence(TYPICAL, history, oldest));
        assertNull("one decided a moment before does not", evidence(TYPICAL, history, oldest + 1));
        // Forty lines, the ten oldest before the minimums changed: still due on the thirty since.
        List<Autopilot.OfferRecord> forty = new ArrayList<>(history);
        for (int i = 1; i <= 10; i++) forty.add(line(oldest - 5 * MIN * i, 102));
        Growth.Evidence due = evidence(TYPICAL, forty, oldest);
        assertNotNull(due);
        assertEquals(10, due.percent());
        assertNull("and not on the older ones", evidence(TYPICAL, forty, oldest + 1));
    }

    @Test public void onlyLinesAutopilotDecidedUnderTheseRulesCountAndOthersAreSkipped() {
        List<Autopilot.OfferRecord> history = new ArrayList<>(lines(30, 120, false));
        // Lines decided with Autopilot off (its bar was not Autopilot's), and a line an older version wrote, sit
        // among them: they are not evidence, either way.
        history.add(3, line(history.get(3).at + MIN, 100, false, DecisionLog.MODEL));
        history.add(9, line(history.get(9).at + MIN, 140, false, DecisionLog.MODEL));
        history.add(12, line(history.get(12).at + MIN, 100, true, DecisionLog.LEGACY_MODEL));
        Growth.Evidence due = evidence(TYPICAL, history, TUESDAY_NOON - HOUR);
        assertNotNull("skipped, they hold nothing back", due);
        assertEquals(30, due.offers);
        // With one of Autopilot's own lines gone, 29 are left: not enough, however many others there are.
        history.remove(0);
        assertNull(evidence(TYPICAL, history, TUESDAY_NOON - HOUR));
        // Add-ons, replays and an offer read twice within two minutes are not counted lines at all.
        List<Autopilot.OfferRecord> counted = new ArrayList<>(lines(29, 120, false));
        Autopilot.OfferRecord newest = counted.get(0);
        counted.add(0, new Autopilot.OfferRecord(newest.facts, newest.at + MIN, false, false, false, false, false, 130,
                true, DecisionLog.MODEL));
        counted.add(0, new Autopilot.OfferRecord(new OfferSnapshot(1999, 4.0, 20, 2), newest.at + 3 * MIN, true,
                false, false, false, false, 130, true, DecisionLog.MODEL));
        counted.add(0, new Autopilot.OfferRecord(new OfferSnapshot(1998, 4.0, 20, 2), newest.at + 4 * MIN, false,
                true, false, false, false, 130, true, DecisionLog.MODEL));
        assertNull(evidence(TYPICAL, counted, TUESDAY_NOON - HOUR));
    }

    @Test public void theGrowthIsAtMostTenPointsAndAtLeastThree() {
        assertEquals(10, evidence(TYPICAL.withMinimumScalePercent(150), lines(30, 150, false), 0).percent());
        assertEquals(10, evidence(TYPICAL, lines(30, 125, false), 0).percent());
        assertEquals(10, evidence(TYPICAL, lines(30, 110, false), 0).percent());
        assertEquals(9, evidence(TYPICAL, lines(30, 109, false), 0).percent());
        assertEquals(3, evidence(TYPICAL, lines(30, 103, false), 0).percent());
        assertNull(evidence(TYPICAL, lines(30, 102, false), 0));
        assertNull(evidence(TYPICAL, lines(30, 100, false), 0));
        assertNull("outside 3 to 10 points is no growth", Growth.grown(TYPICAL, 2));
        assertNull(Growth.grown(TYPICAL, 11));
    }

    @Test public void itWaitsWhileTheBarIsBelowWhereItCouldComeDownWithTheMinimums() {
        List<Autopilot.OfferRecord> history = lines(30, 115, false);
        // Ten points are due, but Autopilot has since lowered the bar to 109%: grown now, the bar could not come down
        // with the minimums (it would have to be 99%), and offers would be asked 1% more. It waits.
        assertNull(evidence(TYPICAL.withMinimumScalePercent(109), history, 0));
        assertNull(evidence(TYPICAL.withMinimumScalePercent(82), history, 0));
        Growth.Evidence due = evidence(TYPICAL.withMinimumScalePercent(110), history, 0);
        assertNotNull(due);
        assertEquals(100, due.grown.barAfter);
    }

    @Test public void noMoneyMinimumNoGrowth() {
        FilterSettings stopsOnly = new FilterSettings(true, 0, 0, 0, 3, true, FilterSettings.GOAL_TOP_TIER, 120);
        assertNull(evidence(stopsOnly, lines(30, 120, false), 0));
    }

    // ---- The arithmetic ----

    @Test public void eachSetMinimumIsRoundedUpInItsOwnUnit() {
        // ⌈m × (100 + g) ÷ 100⌉ cents, cents a mile, cents a minute.
        assertEquals(432, Growth.grownCents(400, 8));
        assertEquals(2, Growth.grownCents(1, 3));
        assertEquals(357, Growth.grownCents(333, 7));
        assertEquals(162, Growth.grownCents(151, 7));
        assertEquals("23¢ a minute ($13.80/hr) at 7% asks 24.61¢: 25¢, $15/hr", 25, Growth.grownCents(23, 7));
        assertEquals(110, Growth.grownCents(100, 10));
        assertEquals(0, Growth.grownCents(0, 10));
        Growth.Grown grown = Growth.grown(rules(333, 151, 23, 120), 7);
        assertEquals(357, grown.flatAfter);
        assertEquals(162, grown.mileAfter);
        assertEquals(25, grown.minuteAfter);
        assertEquals(112, grown.barAfter);
    }

    @Test public void anUnsetMinimumStaysUnsetAndNothingElseIsTouched() {
        FilterSettings perMileOnly = new FilterSettings(false, 0, 150, 0, 3, true, FilterSettings.GOAL_TIER, 118);
        Growth.Evidence due = evidence(perMileOnly, lines(30, 118, false), 0);
        assertNotNull(due);
        assertEquals(10, due.percent());
        assertEquals(0, due.grown.flatAfter);
        assertEquals(165, due.grown.mileAfter);
        assertEquals(0, due.grown.minuteAfter);
        assertEquals(107, due.grown.barAfter);
        // Max stops, the switch and the goal are not the growth's: it carries only the minimums and the bar.
        assertEquals(3, perMileOnly.maxStops);
    }

    @Test public void aMinimumAtTheKnobsTopCannotGrowSoNothingDoes() {
        assertNull(Growth.grown(rules(FilterSettings.MOST_CENTS, 100, 25, 120), 3));
        assertNull(evidence(rules(400, 100, 92_000, 120), lines(30, 120, false), 0));
        assertEquals(FilterSettings.MOST_CENTS, Growth.grownCents(90_909, 10));
        assertNotNull("up to the top it can", evidence(rules(400, 100, 90_909, 120), lines(30, 120, false), 0));
        assertEquals(FilterSettings.MOST_CENTS + 1, Growth.grownCents(90_910, 10));
        assertNull(evidence(rules(400, 100, 90_910, 120), lines(30, 120, false), 0));
    }

    @Test public void theBarComesDownToAtLeastOneHundredAndAsksTheSameWithinRounding() {
        // round(bar × 100 ÷ (100 + g)), half up, never below 100.
        assertEquals(104, Growth.barAfter(112, 8));
        assertEquals(136, Growth.barAfter(150, 10));
        assertEquals(100, Growth.barAfter(103, 3));
        assertEquals(100, Growth.barAfter(110, 10));
        assertEquals(100, Growth.barAfter(100, 10));
        assertEquals("half up: 107 ÷ 1.07 = 100, 108 ÷ 1.07 = 100.93", 101, Growth.barAfter(108, 7));
        assertEquals("103 × 100 ÷ 103 = 100", 100, Growth.barAfter(103, 3));
        assertEquals("105 ÷ 1.04 = 100.96", 101, Growth.barAfter(105, 4));
        assertEquals("111 ÷ 1.08 = 102.78", 103, Growth.barAfter(111, 8));
        assertEquals("117 ÷ 1.04 = 112.5, half up", 113, Growth.barAfter(117, 4));
        for (int bar = 103; bar <= Autopilot.BAR_MAX; bar++) {
            for (int g = 3; g <= Math.min(10, bar - 100); g++) {
                int after = Growth.barAfter(bar, g);
                assertTrue(after >= FilterSettings.BAR_AT_MINIMUMS);
                assertTrue(after <= bar);
                // The bar times the growth is the bar before, within half a step of the bar.
                assertTrue(bar + "/" + g, Math.abs(after * (100L + g) - 100L * bar) * 2 <= 100L + g);
            }
        }
        // An offer judged right after the growth is asked the same pay as just before it, within rounding: the bar's
        // half point (at most 0.55% of the pay) and each minimum's rounding up to a whole unit.
        int[][] minimums = {{400, 100, 25}, {333, 151, 23}, {0, 150, 0}, {2040, 400, 48}, {1300, 385, 41}, {0, 0, 37}};
        for (int[] m : minimums) {
            for (int bar = 103; bar <= Autopilot.BAR_MAX; bar += 1) {
                for (int g = 3; g <= Math.min(10, bar - 100); g++) {
                    FilterSettings before = rules(m[0], m[1], m[2], bar);
                    Growth.Grown grown = Growth.grown(before, g);
                    FilterSettings after = rules(grown.flatAfter, grown.mileAfter, grown.minuteAfter, grown.barAfter);
                    for (double miles : new double[] {0.8, 2.2, 6.6, 12.6}) {
                        for (int minutes : new int[] {9, 27, 44}) {
                            assertSameRequirement(before, after, g, new OfferSnapshot(1000, miles, minutes, 2));
                        }
                    }
                }
            }
        }
    }

    /** {@code after} asks {@code facts} what {@code before} asked, within the rounding the growth allows. */
    private static void assertSameRequirement(FilterSettings before, FilterSettings after, int g, OfferSnapshot facts) {
        BigDecimal asked = AreaScore.required100(before, facts);
        BigDecimal grownAsk = AreaScore.required100(after, facts);
        long was = AreaScore.roundedCents(asked, before.minimumScalePercent);
        long now = AreaScore.roundedCents(grownAsk, after.minimumScalePercent);
        // What rounding each minimum up to a whole unit added to the highest ask, at the bar after.
        BigDecimal exactGrown = asked.multiply(BigDecimal.valueOf(100 + g)).divide(BigDecimal.valueOf(100));
        BigDecimal unitRounding = grownAsk.subtract(exactGrown).max(BigDecimal.ZERO)
                .multiply(BigDecimal.valueOf(after.minimumScalePercent)).divide(BigDecimal.valueOf(100));
        BigDecimal allowed = BigDecimal.valueOf(was).multiply(new BigDecimal("0.0055")).add(unitRounding)
                .add(BigDecimal.ONE).setScale(0, RoundingMode.CEILING);
        String what = before.describe() + " → " + after.describe() + " for " + facts.summary() + ": " + was + " → "
                + now + " (allowed " + allowed + ")";
        assertTrue(what, BigDecimal.valueOf(Math.abs(now - was)).compareTo(allowed) <= 0);
    }

    // ---- The plan carries it ----

    @Test public void aPlanSaysWhetherTheMinimumsAreDueAndOnlyWithTheMinimumsSince() {
        List<Autopilot.OfferRecord> history = lines(30, 112, false);
        long now = history.get(0).at + 10 * MIN;
        Autopilot.State state = new Autopilot.State(false, 0, Autopilot.NEVER, -1, Autopilot.NEVER, 112, null);
        Autopilot.Plan plan = Autopilot.plan(new Autopilot.Inputs(TYPICAL, history, null, null, state, now, 0,
                TUESDAY_NOON - HOUR, EASTERN));
        assertNotNull(plan.growth);
        assertEquals(10, plan.growth.percent());
        assertEquals(TUESDAY_NOON - HOUR, plan.growth.since);
        assertNull("inputs without the minimums' time give no evidence",
                Autopilot.plan(new Autopilot.Inputs(TYPICAL, history, null, null, state, now, 0)).growth);
        assertNull("nor do minimums that took effect after those offers",
                Autopilot.plan(new Autopilot.Inputs(TYPICAL, history, null, null, state, now, 0, now - MIN, EASTERN))
                        .growth);
    }

    @Test public void growthReadsOnlyAutopilotsOwnBarsNeverWhatTheUserAcceptedOrDeclined() {
        List<Autopilot.OfferRecord> plain = lines(30, 112, false);
        List<Autopilot.OfferRecord> accepted = new ArrayList<>();
        List<Autopilot.OfferRecord> declined = new ArrayList<>();
        for (Autopilot.OfferRecord line : plain) {
            accepted.add(new Autopilot.OfferRecord(line.facts, line.at, false, false, true, false, false, line.bar,
                    line.autopilot, line.model));
            declined.add(new Autopilot.OfferRecord(line.facts, line.at, false, false, false, true, true, line.bar,
                    line.autopilot, line.model));
        }
        Growth.Evidence base = evidence(TYPICAL, plain, 0);
        for (List<Autopilot.OfferRecord> outcomes : java.util.Arrays.asList(accepted, declined)) {
            Growth.Evidence same = evidence(TYPICAL, outcomes, 0);
            assertEquals(base.percent(), same.percent());
            assertEquals(base.days, same.days);
            assertEquals(base.grown.flatAfter, same.grown.flatAfter);
            assertEquals(base.grown.barAfter, same.grown.barAfter);
        }
    }
}

package com.local.dasherfilter;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Autopilot's exact arithmetic against the spec's worked example (finalSpec.autopilot.worked): the recency weights and
 * offer rates of a busy and a slow market, the exact value fractions, every plan target, the acceptance-rate
 * carry-forward and accepts still needed, the need's hysteresis, the stall correction and the exemption valve. Every
 * expected number is the spec's; values are compared as reduced fractions, never as decimals.
 */
public final class AutopilotEngineTest {
    private static final long NOW = 1_760_000_000_000L;
    private static final long MIN = 60_000L;

    /** The worked example's 20 counted offers, newest first: pay in cents, miles, minutes; two stops each. */
    private static final int[] PAY = {1625, 975, 700, 1350, 575, 800, 2250, 600, 1100, 350, 925, 1490, 400, 1050,
            725, 1700, 875, 500, 1225, 650};
    private static final double[] MILES = {6.2, 11.8, 2.2, 7.0, 6.6, 3.0, 12.6, 7.9, 5.1, 4.8, 6.3, 9.9, 2.6, 4.4,
            5.5, 8.8, 7.4, 3.2, 6.0, 4.1};
    private static final int[] MINUTES = {26, 41, 14, 28, 27, 16, 44, 29, 23, 19, 25, 38, 15, 20, 24, 33, 31, 18, 26,
            22};
    /** θ of those offers under the starter minimums: their scores. */
    private static final int[] STARTER_THETAS = {250, 82, 175, 192, 85, 200, 178, 75, 191, 72, 146, 150, 100, 210,
            120, 193, 112, 111, 188, 118};

    // ---- Fixtures ----

    private static FilterSettings rules(int flat, int mile, int minute, int goal, int bar) {
        return new FilterSettings(true, flat, mile, minute, 0, true, goal, bar);
    }

    private static FilterSettings starters(int goal) {
        return rules(400, 100, 25, goal, 100);
    }

    private static OfferSnapshot offer(Integer pay, Double miles, Integer minutes) {
        return new OfferSnapshot(pay, miles, minutes, 2);
    }

    /** A line decided {@code minutesAgo} minutes before now, with no outcome. */
    private static Autopilot.OfferRecord line(OfferSnapshot facts, long minutesAgo) {
        return new Autopilot.OfferRecord(facts, NOW - minutesAgo * MIN, false, false, false, false, false);
    }

    private static Autopilot.OfferRecord outcome(long minutesAgo, boolean accepted, boolean declined, boolean exempt) {
        return new Autopilot.OfferRecord(offer(1000 + (int) minutesAgo, 5.0, 20), NOW - minutesAgo * MIN, false,
                false, accepted, declined, exempt);
    }

    /** The worked example's window: the newest line five minutes old, three minutes apart. */
    private static List<Autopilot.OfferRecord> window() {
        return window(PAY.length);
    }

    private static List<Autopilot.OfferRecord> window(int count) {
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) lines.add(line(offer(PAY[i], MILES[i], MINUTES[i]), 5 + 3L * i));
        return lines;
    }

    private static QualifyingWait.Sample sample(long minutesAgo, long waitMinutes, OfferSnapshot arrival) {
        return new QualifyingWait.Sample(NOW - minutesAgo * MIN, waitMinutes * MIN, arrival);
    }

    /** Busy: 9 arrivals aged 38 … 3 minutes after waits of 2 or 3 minutes, plus a 2-minute open wait. */
    private static List<QualifyingWait.Sample> busy() {
        return Arrays.asList(sample(38, 2, offer(900, 5.0, 24)), sample(34, 3, offer(525, 3.9, 19)),
                sample(29, 2, offer(1150, 6.8, 27)), sample(25, 3, offer(700, 5.2, 22)),
                sample(19, 2, offer(1400, 9.1, 34)), sample(15, 3, offer(450, 2.9, 16)),
                sample(11, 2, offer(1025, 4.6, 21)), sample(7, 3, offer(800, 7.7, 30)),
                sample(3, 2, offer(1275, 6.4, 25)), sample(0, 2, null));
    }

    /** Slow: 3 arrivals aged 110, 64 and 23 minutes after waits of 21, 17 and 14 minutes, plus a 9-minute open wait. */
    private static List<QualifyingWait.Sample> slow() {
        return Arrays.asList(sample(110, 21, offer(780, 8.1, 29)), sample(64, 17, offer(615, 3.9, 21)),
                sample(23, 14, offer(1150, 10.4, 37)), sample(0, 9, null));
    }

    /** Dasher's question showed {@code percent} a minute ago, after every line of the window. */
    private static Autopilot.Reading dasher(int percent) {
        return new Autopilot.Reading(percent, NOW - MIN);
    }

    private static Autopilot.Plan plan(FilterSettings rules, List<Autopilot.OfferRecord> lines,
                                       List<QualifyingWait.Sample> waits, Autopilot.Reading reading,
                                       boolean recovering, int current) {
        Autopilot.State state = new Autopilot.State(recovering, 0, Autopilot.NEVER, -1, Autopilot.NEVER, current, null);
        return Autopilot.plan(new Autopilot.Inputs(rules, lines, waits, reading, state, NOW, 7));
    }

    private static int[] thetas(FilterSettings rules) {
        int[] thetas = new int[PAY.length];
        for (int i = 0; i < PAY.length; i++) {
            thetas[i] = AreaScore.passThreshold(rules, offer(PAY[i], MILES[i], MINUTES[i]));
        }
        return thetas;
    }

    private static int[] sortedDescending(int[] values) {
        int[] sorted = values.clone();
        Arrays.sort(sorted);
        for (int i = 0, j = sorted.length - 1; i < j; i++, j--) {
            int swap = sorted[i];
            sorted[i] = sorted[j];
            sorted[j] = swap;
        }
        return sorted;
    }

    /** {@code numerator ÷ denominator} reduced: what the spec writes as an exact fraction. */
    private static void assertFraction(String what, long numerator, long denominator, BigInteger n, BigInteger d) {
        BigInteger gcd = n.gcd(d);
        assertEquals(what + " numerator", BigInteger.valueOf(numerator), n.divide(gcd));
        assertEquals(what + " denominator", BigInteger.valueOf(denominator), d.divide(gcd));
    }

    private static Autopilot.Values values(FilterSettings rules, List<QualifyingWait.Sample> waits, int hi) {
        List<Autopilot.OfferRecord> lines = Autopilot.countedWindow(window());
        int[] thetas = new int[lines.size()];
        for (int i = 0; i < thetas.length; i++) thetas[i] = AreaScore.passThreshold(rules, lines.get(i).facts);
        Autopilot.Rate rate = Autopilot.lambda(waits, NOW);
        return Autopilot.values(Autopilot.mix(rules, lines, thetas), rate.numerator, rate.denominator, hi);
    }

    private static void assertValue(Autopilot.Plan plan, Autopilot.Mode mode, int target, int need, int barShare,
                                    int acceptableLow, int acceptableHigh) {
        assertEquals("mode", mode, plan.mode);
        assertEquals("target", target, plan.target);
        assertEquals("need", need, plan.need);
        assertEquals("share bar", barShare, plan.barShare);
        assertEquals("least acceptable bar", acceptableLow, plan.acceptableLow);
        assertEquals("greatest acceptable bar", acceptableHigh, plan.acceptableHigh);
        assertFalse(plan.pinned);
    }

    // ---- Recency weights and offer rates ----

    @Test
    public void recencyWeightsAreTheSpecsWholeNumbers() {
        long[] busyAges = {38, 34, 29, 25, 19, 15, 11, 7, 3, 0};
        int[] busyWeights = {822, 841, 866, 886, 918, 939, 961, 983, 1006, 1024};
        for (int i = 0; i < busyAges.length; i++) {
            assertEquals("aged " + busyAges[i] + " min", busyWeights[i], Autopilot.weight(busyAges[i] * MIN));
        }
        long[] slowAges = {110, 64, 23, 0};
        int[] slowWeights = {542, 708, 897, 1024};
        for (int i = 0; i < slowAges.length; i++) {
            assertEquals("aged " + slowAges[i] + " min", slowWeights[i], Autopilot.weight(slowAges[i] * MIN));
        }
        assertEquals("a future sample has full weight", 1024, Autopilot.weight(-5 * MIN));
        assertEquals("one half-life", 512, Autopilot.weight(Autopilot.HALF_LIFE_MS));
        assertEquals("long gone", 0, Autopilot.weight(48 * 60 * MIN));
    }

    @Test
    public void offerRatesAreExactFractionsBehindTheSixAnHourPrior() {
        Autopilot.Rate busy = Autopilot.lambda(busy(), NOW);
        assertEquals(8_222, busy.arrivalWeight);
        assertEquals(1_328_460_000L, busy.exposureWeight);
        assertEquals("60,000 × (8,222 + 1,024)", 554_760_000L, busy.numerator);
        assertEquals("1,328,460,000 + 614,400,000", 1_942_860_000L, busy.denominator);
        assertFraction("busy λ per minute", 9_246, 32_381, BigInteger.valueOf(busy.numerator),
                BigInteger.valueOf(busy.denominator));

        Autopilot.Rate slow = Autopilot.lambda(slow(), NOW);
        assertEquals(2_147, slow.arrivalWeight);
        assertEquals(2_711_520_000L, slow.exposureWeight);
        assertFraction("slow λ per minute", 3_171, 55_432, BigInteger.valueOf(slow.numerator),
                BigInteger.valueOf(slow.denominator));

        Autopilot.Rate none = Autopilot.lambda(new ArrayList<>(), NOW);
        assertFraction("with no watched waiting, the prior: 6 an hour", 1, 10, BigInteger.valueOf(none.numerator),
                BigInteger.valueOf(none.denominator));
    }

    // ---- The counted window, θ and the share bar ----

    @Test
    public void thetaIsEachOffersScoreAndThePassCountsMatchTheTable() {
        assertArrayEquals(STARTER_THETAS, thetas(starters(70)));
        int[][] table = {{72, 20}, {75, 19}, {82, 18}, {86, 16}, {100, 16}, {111, 15}, {112, 14}, {146, 11},
                {150, 10}};
        for (int[] row : table) {
            assertEquals("passing at " + row[0], row[1], Autopilot.passing(STARTER_THETAS, row[0]));
        }
        int[][] shareBars = {{75, 111}, {80, 100}, {55, 146}, {95, 75}, {20, 150}};
        for (int[] row : shareBars) {
            Autopilot.ShareBar share = Autopilot.shareBar(STARTER_THETAS, row[0]);
            assertEquals("need " + row[0], row[1], share.bar);
            assertFalse(share.pinned);
        }
    }

    @Test
    public void theCountedWindowSkipsAddOnsReplaysMisreadsAndRepeatsAndStopsAtOneHundred() {
        OfferSnapshot facts = offer(900, 5.0, 24);
        List<Autopilot.OfferRecord> lines = Arrays.asList(
                line(facts, 0),
                new Autopilot.OfferRecord(offer(800, 4.0, 20), NOW, true, false, false, false, false),
                new Autopilot.OfferRecord(offer(700, 4.0, 20), NOW, false, true, false, false, false),
                line(offer(600, 4.0, 3), 1),
                line(offer(600, 0.3, 20), 1),
                line(new OfferSnapshot(600, 4.0, 20, 1), 1),
                new Autopilot.OfferRecord(facts, NOW - 120_000L, false, false, true, false, false),
                new Autopilot.OfferRecord(facts, NOW - 120_001L, false, false, true, false, false),
                line(OfferSnapshot.UNKNOWN, 3));
        List<Autopilot.OfferRecord> window = Autopilot.countedWindow(lines);
        assertEquals("the newest line, the same facts 120,001 ms before it, and the unread line", 3, window.size());
        assertSame(lines.get(0), window.get(0));
        assertSame("the same facts 120,001 ms earlier are another offer", lines.get(7), window.get(1));
        assertSame(lines.get(8), window.get(2));

        List<Autopilot.OfferRecord> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) many.add(line(offer(500 + i, 5.0, 20), i));
        List<Autopilot.OfferRecord> hundred = Autopilot.countedWindow(many);
        assertEquals(100, hundred.size());
        assertSame("newest first", many.get(0), hundred.get(0));
        assertSame(many.get(99), hundred.get(99));
    }

    // ---- Exact values ----

    @Test
    public void valuesAreTheSpecsExactFractions() {
        Autopilot.Values busy = values(starters(0), busy(), 150);
        assertFraction("busy V(100)", 4_816_703_700L, 2_186_879L, busy.numerator(100), busy.denominator(100));
        assertFraction("busy V(101)", 2_352_875_850L, 1_058_767L, busy.numerator(101), busy.denominator(101));
        assertFraction("busy V(121)", 3_942_956_700L, 1_678_349L, busy.numerator(121), busy.denominator(121));
        assertFraction("busy V(150)", 1_843_190_100L, 781_387L, busy.numerator(150), busy.denominator(150));
        assertFraction("busy V(50)", 5_510_153_700L, 2_723_147L, busy.numerator(50), busy.denominator(50));
        for (int bar = 86; bar <= 100; bar++) {
            assertFraction("busy V(" + bar + ")", 4_816_703_700L, 2_186_879L, busy.numerator(bar),
                    busy.denominator(bar));
        }
        for (int bar = 121; bar <= 146; bar++) {
            assertFraction("busy V(" + bar + ")", 3_942_956_700L, 1_678_349L, busy.numerator(bar),
                    busy.denominator(bar));
        }
        assertEquals("$22.0255 an hour", 2202, busy.centsPerHour(100));
        assertEquals("$23.5887 an hour", 2358, busy.centsPerHour(150));

        Autopilot.Values slow = values(starters(0), slow(), 150);
        assertFraction("slow V(100)", 3_303_864_900L, 2_386_553L, slow.numerator(100), slow.denominator(100));
        assertEquals("$13.8437 an hour", 1384, slow.centsPerHour(100));
        assertEquals("$12.9109 an hour", 1291, slow.centsPerHour(150));
    }

    @Test
    public void theBestBarIsTheLowestOfEqualValuesAndTheSetIsWithinTwoPercent() {
        Autopilot.Acceptable busy = Autopilot.acceptable(values(starters(0), busy(), 150));
        assertEquals("V is flat from 147 to 150: the lowest", 147, busy.best);
        assertEquals(119, busy.lo);
        assertEquals(150, busy.hi);
        assertFalse(busy.contains(118));
        assertTrue(busy.contains(119));
        assertEquals(119, busy.closestTo(100));
        assertEquals(150, busy.closestTo(170));
        assertEquals(130, busy.closestTo(130));

        Autopilot.Acceptable limited = Autopilot.acceptable(values(starters(0), busy(), 111));
        assertEquals(101, limited.best);
        assertEquals("98% of $22.2228 is $21.78", 86, limited.lo);
        assertEquals(111, limited.hi);
        assertEquals("the nearest member", 86, limited.closestTo(0));
    }

    // ---- Plans: busy market ----

    @Test
    public void busyGoalSeventyWithDasherShowingSeventyFourHoldsAtOneHundred() {
        Autopilot.Plan plan = plan(starters(70), window(), busy(), dasher(74), false, 100);
        assertValue(plan, Autopilot.Mode.VALUE, 100, 75, 111, 86, 111);
        assertEquals(101, plan.bestBar);
        assertEquals(7400, plan.arHundredths);
        assertEquals(Autopilot.ArSource.DASHER, plan.arSource);
        assertEquals(0, plan.arOffersSince);
        assertEquals(0, plan.acceptsNeeded);
        assertFalse(plan.recovering);
        assertEquals(20, plan.counted);
        assertEquals(16, plan.passAtTarget);
        assertEquals(20, plan.mixSize);
        assertEquals("from 90 the closest acceptable bar is 90 itself", 90,
                plan(starters(70), window(), busy(), dasher(74), false, 90).target);
    }

    @Test
    public void busyPayFirstRaisesTowardOneHundredNineteen() {
        Autopilot.Plan plan = plan(starters(0), window(), busy(), null, false, 100);
        assertValue(plan, Autopilot.Mode.VALUE, 119, 20, 150, 119, 150);
        assertEquals(147, plan.bestBar);
        assertEquals(Autopilot.Reason.VALUE_UP, Autopilot.commitReason(plan, 100, 105, null));
        assertEquals("offers are coming often", Autopilot.commitReason(plan, 100, 105, null).words);
    }

    @Test
    public void busyGoalFiftyAtSixtyTwoAlsoGoesToOneHundredNineteen() {
        Autopilot.Plan plan = plan(starters(50), window(), busy(), dasher(62), false, 100);
        assertValue(plan, Autopilot.Mode.VALUE, 119, 55, 146, 119, 146);
        assertEquals(121, plan.bestBar);
    }

    @Test
    public void busyRecoveryAtFiftyFiveHoldsAtOneHundredUnderTheAcceptanceRateLimit() {
        Autopilot.Plan plan = plan(starters(70), window(), busy(), dasher(55), false, 100);
        assertValue(plan, Autopilot.Mode.RECOVERY, 100, 80, 100, 86, 100);
        assertTrue(plan.recovering);
        assertEquals("16 of 20 pass at 100", 16, plan.passAtTarget);
        assertEquals("AR 55% → 70%: about 15 more accepts", 15, plan.acceptsNeeded);
        assertTrue(plan.belowGoal());
        assertEquals(Autopilot.Reason.RECOVERY, Autopilot.commitReason(plan, 100, 100, null));
        assertEquals("from 84 while still recovering at 71%: the closest acceptable bar", 86,
                plan(starters(70), window(), busy(), dasher(71), true, 84).target);
    }

    /**
     * Recovering lasts until the rate reaches the goal + 2 (72% for a 70% goal), but from 70% on the rate is not below
     * the goal: a move then keeps enough offers for the goal, and never says the rate is below it (the status line
     * says "At or above your goal" at the same moment).
     */
    @Test
    public void aMoveWhileStillRecoveringAtOrAboveTheGoalSaysItKeepsEnoughOffersForIt() {
        FilterSettings high = rules(500, 125, 35, 70, 100);
        Autopilot.Plan below = plan(high, window(), busy(), dasher(69), true, 100);
        assertEquals(Autopilot.Mode.RECOVERY, below.mode);
        assertEquals("acceptance rate below your goal",
                Autopilot.commitReason(below, 100, below.target, null).words);
        for (int percent : new int[] {70, 71}) {
            Autopilot.Plan band = plan(high, window(), busy(), dasher(percent), true, 100);
            assertTrue(percent + "%: still recovering", band.recovering);
            assertEquals(percent + "%", Autopilot.Mode.RECOVERY, band.mode);
            assertFalse(band.belowGoal());
            assertEquals(percent + "%", Autopilot.Reason.GOAL, Autopilot.commitReason(band, 100, band.target, null));
        }
        Autopilot.Plan out = plan(high, window(), busy(), dasher(72), true, 100);
        assertFalse("72%: recovery is over", out.recovering);
        assertEquals(Autopilot.Reason.GOAL, Autopilot.commitReason(out, 100, out.target, null));
    }

    // ---- Plans: slow market ----

    @Test
    public void aSlowMarketNeverRaisesTheBar() {
        Autopilot.Plan goal = plan(starters(70), window(), slow(), dasher(74), false, 100);
        assertValue(goal, Autopilot.Mode.VALUE, 100, 75, 111, 50, 111);
        assertEquals("V is flat from 86 to 100: the lowest is best", 86, goal.bestBar);
        Autopilot.Plan payFirst = plan(starters(0), window(), slow(), null, false, 100);
        assertValue(payFirst, Autopilot.Mode.VALUE, 100, 20, 150, 50, 118);
    }

    // ---- Plans: higher minimums, the same offers ----

    @Test
    public void higherMinimumsOnTheSameOffers() {
        FilterSettings high = rules(500, 125, 35, 70, 100);
        assertArrayEquals(new int[] {178, 150, 147, 142, 142, 140, 137, 136, 134, 112, 105, 86, 84, 80, 79, 76, 66,
                60, 59, 52}, sortedDescending(thetas(high)));
        for (List<QualifyingWait.Sample> market : Arrays.asList(busy(), slow())) {
            Autopilot.Plan goal = plan(high, window(), market, dasher(74), false, 100);
            assertEquals(Autopilot.Mode.GOAL, goal.mode);
            assertEquals(79, goal.target);
            assertEquals(79, goal.barShare);
            assertEquals("keeping enough offers for your goal", Autopilot.commitReason(goal, 100, 79, null).words);
            Autopilot.Plan recovery = plan(high, window(), market, dasher(55), false, 100);
            assertEquals(Autopilot.Mode.RECOVERY, recovery.mode);
            assertEquals(76, recovery.target);
        }
        assertEquals(67, plan(high, window(), busy(), dasher(74), false, 100).acceptableLow);

        FilterSettings payFirst = rules(500, 125, 35, 0, 100);
        Autopilot.Plan busyPay = plan(payFirst, window(), busy(), null, false, 100);
        assertValue(busyPay, Autopilot.Mode.VALUE, 100, 20, 142, 85, 136);
        Autopilot.Plan slowPay = plan(payFirst, window(), slow(), null, false, 100);
        assertValue(slowPay, Autopilot.Mode.VALUE, 80, 20, 142, 50, 80);
        assertEquals(61, slowPay.bestBar);
        assertEquals(Autopilot.Reason.VALUE_DOWN, Autopilot.commitReason(slowPay, 100, 80, null));
        assertEquals("offers are slower", Autopilot.commitReason(slowPay, 100, 80, null).words);

        FilterSettings goalFifty = rules(500, 125, 35, 50, 100);
        assertEquals(100, plan(goalFifty, window(), busy(), dasher(62), false, 100).target);
        assertEquals(80, plan(goalFifty, window(), slow(), dasher(62), false, 100).target);

        // $8.75 / 7.4 mi / 31 min scores 80: at 79 it needs ⌈0.79 × $10.85⌉ = $8.58 and passes below the minimums.
        OfferSnapshot eightSeventyFive = offer(875, 7.4, 31);
        assertEquals(80, AreaScore.scorePercent(high, eightSeventyFive));
        OfferRule.Decision at79 = OfferRule.evaluate(eightSeventyFive, high.withMinimumScalePercent(79));
        assertEquals(OfferRule.Result.KEEP, at79.result);
        assertEquals(858, at79.requiredCents);
        assertTrue(at79.belowMinimums);
        assertEquals(1085, OfferRule.evaluate(eightSeventyFive, high).requiredCents);
    }

    // ---- Plans: the owner after migration, learning, gates ----

    @Test
    public void theOwnersMigratedMinimumsArePinnedAtFifty() {
        FilterSettings owner = rules(2040, 400, 48, 70, 100);
        int[] thetas = sortedDescending(thetas(owner));
        assertEquals("θ max", 65, thetas[0]);
        assertEquals(0, Autopilot.passing(thetas, 100));
        assertEquals(4, Autopilot.passing(thetas, 50));
        Autopilot.Plan plan = plan(owner, window(), busy(), dasher(9), false, 100);
        assertEquals(Autopilot.Mode.PINNED, plan.mode);
        assertEquals(50, plan.target);
        assertTrue(plan.pinned);
        assertEquals(80, plan.need);
        assertEquals("4 of 20 pass at 50", 4, plan.passAtTarget);
        assertEquals("AR 9% → 70%: about 61 more accepts", 61, plan.acceptsNeeded);
        assertEquals(Autopilot.Reason.PINNED, Autopilot.commitReason(plan, 100, 80, null));

        // After "Use typical minimums" (with the bar back at 100): need 80, 16 of 20 pass at 100.
        Autopilot.Plan typical = plan(starters(70), window(), busy(), dasher(9), false, 100);
        assertEquals(Autopilot.Mode.RECOVERY, typical.mode);
        assertEquals(100, typical.target);
        assertEquals(16, typical.passAtTarget);
        assertEquals(61, typical.acceptsNeeded);
    }

    @Test
    public void nineteenCountedOffersAreStillLearningAtOneHundred() {
        Autopilot.Plan plan = plan(starters(70), window(19), busy(), dasher(9), true, 82);
        assertEquals(Autopilot.Mode.LEARNING, plan.mode);
        assertEquals(100, plan.target);
        assertEquals(19, plan.counted);
        assertEquals("not planned", -1, plan.need);
        assertTrue("the state is kept while learning", plan.recovering);
        assertEquals(Autopilot.Reason.LEARNING, Autopilot.commitReason(plan, 82, 100, null));
        assertEquals("twenty is enough", Autopilot.Mode.RECOVERY,
                plan(starters(70), window(20), busy(), dasher(9), true, 100).mode);
    }

    @Test
    public void autopilotOffOrNoMoneyMinimumHoldsExactlyTheMinimums() {
        FilterSettings off = FilterSettings.of(true, 400, 100, 25, 0);
        Autopilot.Plan offPlan = plan(off, window(), busy(), dasher(9), false, 100);
        assertEquals(Autopilot.Mode.OFF, offPlan.mode);
        assertEquals(100, offPlan.target);
        FilterSettings stopsOnly = new FilterSettings(true, 0, 0, 0, 3, true, 70, 100);
        Autopilot.Plan noRules = plan(stopsOnly, window(), busy(), dasher(9), false, 82);
        assertEquals(Autopilot.Mode.NO_RULES, noRules.mode);
        assertEquals(100, noRules.target);
        assertEquals(Autopilot.Reason.AT_MINIMUMS, Autopilot.commitReason(noRules, 82, 100, null));
    }

    @Test
    public void maxStopsCountsAsDeclinedAtEveryBar() {
        FilterSettings threeStops = new FilterSettings(true, 400, 100, 25, 3, true, 70, 100);
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            lines.add(line(new OfferSnapshot(PAY[i], MILES[i], MINUTES[i], i < 10 ? 4 : 2), 5 + 3L * i));
        }
        Autopilot.Plan plan = plan(threeStops, lines, busy(), dasher(74), false, 100);
        assertEquals("half the offers fail at any bar: 75% is out of reach", Autopilot.Mode.PINNED, plan.mode);
        assertEquals(50, plan.target);
        assertEquals(10, plan.passAt(50));
    }

    @Test
    public void withTooFewReadableOffersTheBarIsTheShareBarHeldToOneHundred() {
        FilterSettings high = rules(500, 125, 35, 70, 100);
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            // The five newest lines have no minutes read: θ from the asks that were read, out of the mix.
            lines.add(line(offer(PAY[i], MILES[i], i < 5 ? null : MINUTES[i]), 5 + 3L * i));
        }
        Autopilot.Plan plan = plan(high, lines, busy(), dasher(74), false, 100);
        assertEquals(15, plan.mixSize);
        assertEquals(Autopilot.Mode.GOAL, plan.mode);
        assertEquals("θ 209, 66, 140, 154, 69 for the five: the 15th highest θ is 79", 79, plan.target);
        assertEquals(-1, plan.bestBar);

        List<Autopilot.OfferRecord> unread = new ArrayList<>(window());
        unread.set(0, line(offer(null, 6.2, 26), 5));
        Autopilot.Plan noValue = plan(starters(70), unread, busy(), dasher(74), false, 90);
        assertEquals(19, noValue.mixSize);
        assertEquals("unread pay passes at every bar; the share bar is above 100", Autopilot.Mode.AT_MINIMUMS,
                noValue.mode);
        assertEquals(100, noValue.target);
        assertEquals(Autopilot.Reason.AT_MINIMUMS, Autopilot.commitReason(noValue, 90, 100, null));
    }

    @Test
    public void outliersAndUnreadQuantitiesStayOutOfTheMix() {
        FilterSettings rules = starters(70);
        List<Autopilot.OfferRecord> lines = Arrays.asList(
                line(offer(1000, 5.0, 20), 1),
                line(offer(5001, 5.0, 20), 2),
                line(offer(5000, 5.0, 20), 3),
                line(offer(1000, null, 20), 4),
                line(offer(1000, 5.0, null), 5),
                line(offer(null, 5.0, 20), 6));
        List<Autopilot.OfferRecord> window = Autopilot.countedWindow(lines);
        int[] thetas = new int[window.size()];
        for (int i = 0; i < thetas.length; i++) thetas[i] = AreaScore.passThreshold(rules, window.get(i).facts);
        List<Autopilot.MixLine> mix = Autopilot.mix(rules, window, thetas);
        assertEquals("$150.00 an hour is in, $150.03 is out, unread miles, minutes and pay are out", 2, mix.size());
        assertEquals(1000, mix.get(0).payCents);
        assertEquals(5000, mix.get(1).payCents);
    }

    // ---- Acceptance rate ----

    @Test
    public void dashersReadingIsCarriedForwardOverTheOffersSince() {
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        for (int i = 0; i < 10; i++) lines.add(outcome(1 + i, i < 8, i >= 8, false));
        for (int i = 0; i < 30; i++) lines.add(outcome(20 + i, false, true, false));
        Autopilot.Reading nine = new Autopilot.Reading(9, NOW - 15 * MIN);
        Autopilot.ArNow carried = Autopilot.arNow(nine, Autopilot.countedWindow(lines), false, NOW);
        assertEquals("900 + 800 − 90", 1_610, carried.hundredths);
        assertEquals(Autopilot.ArSource.DASHER, carried.source);
        assertEquals(10, carried.offersSince);
        assertEquals("AR 16% → 70%: about 54 more accepts", 54, Autopilot.acceptsNeeded(70, carried.hundredths));

        List<Autopilot.OfferRecord> three = new ArrayList<>();
        for (int i = 0; i < 3; i++) three.add(outcome(1 + i, i < 2, i == 2, false));
        Autopilot.Reading seventyFour = new Autopilot.Reading(74, NOW - 10 * MIN);
        assertEquals("7400 + 200 − 222", 7_378, Autopilot.arNow(seventyFour, three, false, NOW).hundredths);

        assertEquals(61, Autopilot.acceptsNeeded(70, 900));
        assertEquals(15, Autopilot.acceptsNeeded(70, 5_500));
        assertEquals(1, Autopilot.acceptsNeeded(70, 6_999));
        assertEquals(0, Autopilot.acceptsNeeded(70, 7_000));
        assertEquals("pay first has no goal", 0, Autopilot.acceptsNeeded(0, 900));
        assertEquals("unknown", 0, Autopilot.acceptsNeeded(70, -1));
    }

    @Test
    public void aReadingIsFreshForSevenDaysAndUntilDashersWindowTurnsOver() {
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        for (int i = 0; i < 99; i++) lines.add(outcome(1 + i, true, false, false));
        Autopilot.ArNow ninetyNine = Autopilot.arNow(new Autopilot.Reading(40, NOW - 200 * MIN),
                Autopilot.countedWindow(lines), false, NOW);
        assertEquals(Autopilot.ArSource.DASHER, ninetyNine.source);
        assertEquals("4000 + 9900 − 3960", 9_940, ninetyNine.hundredths);
        lines.add(outcome(100, true, false, false));
        Autopilot.ArNow hundred = Autopilot.arNow(new Autopilot.Reading(40, NOW - 200 * MIN),
                Autopilot.countedWindow(lines), false, NOW);
        assertEquals("100 offers since: the app's own count instead", Autopilot.ArSource.ESTIMATE, hundred.source);
        assertEquals(10_000, hundred.hundredths);

        List<Autopilot.OfferRecord> few = Arrays.asList(outcome(1, true, false, false));
        long sevenDays = 7L * 24 * 60 * MIN;
        assertEquals("seven days old: 6000 + 100 − 60", 6_040,
                Autopilot.arNow(new Autopilot.Reading(60, NOW - sevenDays), few, false, NOW).hundredths);
        assertEquals(Autopilot.ArSource.UNKNOWN,
                Autopilot.arNow(new Autopilot.Reading(60, NOW - sevenDays - 1), few, false, NOW).source);
        assertEquals(-1, Autopilot.arNow(null, few, false, NOW).hundredths);
    }

    @Test
    public void theOfferWhoseQuestionShowedTheRateCountsAmongTheOffersSince() {
        // Dasher's question shows the rate before its own offer's decline counts: that offer is one offer since.
        OfferSnapshot declined = offer(1100, 5.1, 23);
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        for (int i = 0; i < 10; i++) lines.add(outcome(1 + i, i < 8, i >= 8, false));
        lines.add(new Autopilot.OfferRecord(declined, NOW - 15 * MIN - 2_000, false, false, false, true, false));
        for (int i = 0; i < 30; i++) lines.add(outcome(20 + i, false, true, false));
        List<Autopilot.OfferRecord> window = Autopilot.countedWindow(lines);
        Autopilot.Reading seventy = new Autopilot.Reading(70, NOW - 15 * MIN, declined.fingerprint());
        Autopilot.ArNow ar = Autopilot.arNow(seventy, window, false, NOW);
        assertEquals("7,000 + 800 − 70 × 11", 7_030, ar.hundredths);
        assertEquals(11, ar.offersSince);
        assertEquals("without the offer named, as before", 7_100,
                Autopilot.arNow(new Autopilot.Reading(70, NOW - 15 * MIN), window, false, NOW).hundredths);

        // The user went back to it from the question and took it: it counts as accepted.
        List<Autopilot.OfferRecord> taken = new ArrayList<>(lines);
        taken.set(10, new Autopilot.OfferRecord(declined, NOW - 15 * MIN - 2_000, false, false, true, false, false));
        assertEquals("7,000 + 900 − 70 × 11", 7_130,
                Autopilot.arNow(seventy, Autopilot.countedWindow(taken), false, NOW).hundredths);

        // Stamped after the question (the history took it a moment late): already among the offers since, once.
        List<Autopilot.OfferRecord> late = new ArrayList<>(lines);
        late.set(10, new Autopilot.OfferRecord(declined, NOW - 15 * MIN + 500, false, false, false, true, false));
        assertEquals(11, Autopilot.arNow(seventy, Autopilot.countedWindow(late), false, NOW).offersSince);

        // The same facts more than two minutes before the question are another offer.
        List<Autopilot.OfferRecord> older = new ArrayList<>(lines);
        older.set(10, new Autopilot.OfferRecord(declined, NOW - 15 * MIN - Autopilot.DEDUP_MS - 1, false, false, false,
                true, false));
        assertEquals(10, Autopilot.arNow(seventy, Autopilot.countedWindow(older), false, NOW).offersSince);

        // Dasher said declining it does not lower the rate: it is not in the accounting, so not counted either.
        List<Autopilot.OfferRecord> free = new ArrayList<>(lines);
        free.set(10, new Autopilot.OfferRecord(declined, NOW - 15 * MIN - 2_000, false, false, false, true, true));
        assertEquals(10, Autopilot.arNow(seventy, Autopilot.countedWindow(free), false, NOW).offersSince);
    }

    @Test
    public void aReadingIsStaleOnceDashersWindowTurnedOverEvenWithExemptLinesInTheCountedWindow() {
        // 130 offers since Dasher showed 9%, three of the newest declines marked free: DoorDash's last 100 offers are
        // all after the reading, so it is stale, though the counted window's 100 lines hold only 97 accounted ones.
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        for (int i = 0; i < 130; i++) {
            boolean accepted = i % 5 != 0;
            lines.add(new Autopilot.OfferRecord(offer(1500 + i, 5.0, 20), NOW - (i + 1) * 2 * MIN, false, false,
                    accepted, !accepted, i == 0 || i == 5 || i == 10));
        }
        Autopilot.Reading nine = new Autopilot.Reading(9, NOW - 131 * 2 * MIN);
        Autopilot.ArNow ar = Autopilot.arNow(nine, Autopilot.countedLines(lines), false, NOW);
        assertEquals(Autopilot.ArSource.ESTIMATE, ar.source);
        assertEquals("DoorDash's last 100 offers without the 3 free declines: 82 accepted", 8_200, ar.hundredths);
        assertEquals(100, ar.counted);
        assertEquals("the accounting reaches 3 lines past the counted window", 100,
                Autopilot.accounting(Autopilot.countedLines(lines), false).size());
        Autopilot.Plan plan = plan(starters(70), lines, busy(), nine, true, 100);
        assertEquals(Autopilot.ArSource.ESTIMATE, plan.arSource);
        assertEquals(8_200, plan.arHundredths);
        assertEquals("the pass share is still the counted window", 100, plan.counted);
    }

    @Test
    public void aPlusAmountsCeilingOnALineCountsAsUnreadPayBeforeAndAfterARestart() {
        // A "+$" ceiling is never stored on a history line: in memory it must not count either, or the same history
        // would plan otherwise before and after a restart. Unread pay passes at every bar.
        FilterSettings rules = starters(70);
        OfferSnapshot ceiling = new OfferSnapshot(null, 6.6, 27, 2, 559);
        assertEquals("the rule itself still uses it", 82, AreaScore.passThreshold(rules, ceiling));
        Autopilot.OfferRecord record = new Autopilot.OfferRecord(ceiling, NOW - MIN, false, false, false, false,
                false);
        assertEquals(null, record.facts.payAtMostCents);
        assertEquals(Integer.MAX_VALUE, AreaScore.passThreshold(rules, record.facts));
        Autopilot.OfferRecord stored = new Autopilot.OfferRecord(new OfferSnapshot(null, 6.6, 27, 2), NOW - MIN,
                false, false, false, false, false);
        List<Autopilot.OfferRecord> inMemory = new ArrayList<>(window());
        inMemory.set(0, record);
        List<Autopilot.OfferRecord> reloaded = new ArrayList<>(window());
        reloaded.set(0, stored);
        Autopilot.Plan before = plan(rules, inMemory, busy(), dasher(74), false, 100);
        Autopilot.Plan after = plan(rules, reloaded, busy(), dasher(74), false, 100);
        assertEquals(after.passAt(150), before.passAt(150));
        assertEquals(after.target, before.target);
        assertEquals(after.barShare, before.barShare);
    }

    @Test
    public void withoutAFreshReadingTheAppCountsTwentyKnownOutcomes() {
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        for (int i = 0; i < 31; i++) lines.add(outcome(1 + i, i < 7, i >= 7 && i < 19, false));
        Autopilot.ArNow estimate = Autopilot.arNow(null, Autopilot.countedWindow(lines), false, NOW);
        assertEquals("7 accepted of 19 known: unknown outcomes are left out", Autopilot.ArSource.UNKNOWN,
                estimate.source);
        lines.add(outcome(40, false, true, false));
        estimate = Autopilot.arNow(null, Autopilot.countedWindow(lines), false, NOW);
        assertEquals(Autopilot.ArSource.ESTIMATE, estimate.source);
        assertEquals("⌊10000 × 7 ÷ 20⌋", 3_500, estimate.hundredths);
        assertEquals(20, estimate.counted);

        List<Autopilot.OfferRecord> both = new ArrayList<>(lines);
        both.set(0, new Autopilot.OfferRecord(offer(1001, 5.0, 20), NOW - MIN, false, false, true, true, false));
        assertEquals("a line both accepted and declined counts once, as accepted", 3_500,
                Autopilot.arNow(null, Autopilot.countedWindow(both), false, NOW).hundredths);
    }

    @Test
    public void exemptOffersLeaveTheAccountingButStayInThePassShare() {
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        for (int i = 0; i < 15; i++) lines.add(outcome(1 + i, true, false, false));
        for (int i = 0; i < 15; i++) lines.add(outcome(20 + i, false, true, i < 5));
        List<Autopilot.OfferRecord> window = Autopilot.countedWindow(lines);
        assertFalse("5 of 15 declines marked: honoured", Autopilot.exemptionsIgnored(window));
        Autopilot.ArNow ar = Autopilot.arNow(null, window, false, NOW);
        assertEquals("15 accepted of 25 accounted", 6_000, ar.hundredths);
        assertEquals(25, ar.counted);
        Autopilot.Plan plan = plan(starters(70), lines, busy(), null, false, 100);
        assertEquals("every counted offer is in the pass share", 30, plan.counted);
        assertEquals(30, plan.passAt(50));
        assertEquals(6_000, plan.arHundredths);
        assertEquals(15, plan.declinedInWindow);
        assertEquals(5, plan.exemptInWindow);
        assertFalse(plan.exemptionsIgnored);
    }

    @Test
    public void theValveTreatsAMarkOnMostDeclinesAsDashersGeneralWording() {
        List<Autopilot.OfferRecord> six = new ArrayList<>();
        for (int i = 0; i < 10; i++) six.add(outcome(1 + i, false, true, i < 6));
        assertTrue("Dc 10, X 6: ignored", Autopilot.exemptionsIgnored(six));
        List<Autopilot.OfferRecord> five = new ArrayList<>();
        for (int i = 0; i < 10; i++) five.add(outcome(1 + i, false, true, i < 5));
        assertFalse("Dc 10, X 5: honoured", Autopilot.exemptionsIgnored(five));
        List<Autopilot.OfferRecord> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) nine.add(outcome(1 + i, false, true, true));
        assertFalse("fewer than 10 declines: honoured", Autopilot.exemptionsIgnored(nine));

        List<Autopilot.OfferRecord> lines = new ArrayList<>(six);
        for (int i = 0; i < 10; i++) lines.add(outcome(20 + i, true, false, false));
        assertEquals("ignored: 10 accepted of all 20", 5_000,
                Autopilot.arNow(null, Autopilot.countedWindow(lines), true, NOW).hundredths);
        Autopilot.Plan plan = plan(starters(70), lines, busy(), null, false, 100);
        assertTrue(plan.exemptionsIgnored);
        assertEquals(5_000, plan.arHundredths);
    }

    // ---- The need ----

    @Test
    public void theNeedKeepsRecoveringUntilTheGoalPlusTwo() {
        assertNeed(80, true, Autopilot.need(70, 6_900, false, 0));
        assertNeed(80, true, Autopilot.need(70, 7_100, true, 0));
        assertNeed(75, false, Autopilot.need(70, 7_200, true, 0));
        assertNeed("not recovering before: steady", 75, false, Autopilot.need(70, 7_100, false, 0));
        assertNeed("unknown rate", 75, false, Autopilot.need(70, -1, true, 0));
        assertNeed("unknown rate with the correction", 79, false, Autopilot.need(70, -1, false, 4));
        assertNeed("goal 50, steady", 55, false, Autopilot.need(50, 6_200, false, 0));
        assertNeed("pay first", 20, false, Autopilot.need(0, 900, true, 10));
        assertNeed("recovering with the most correction", 90, true, Autopilot.need(70, 900, true, 10));
        assertNeed("never above 95", 95, true, Autopilot.need(85, 900, true, 10));
    }

    private static void assertNeed(int need, boolean recovering, Autopilot.Need actual) {
        assertNeed("", need, recovering, actual);
    }

    private static void assertNeed(String what, int need, boolean recovering, Autopilot.Need actual) {
        assertEquals(what + " need", need, actual.need);
        assertEquals(what + " recovering", recovering, actual.recovering);
    }

    // ---- The stall correction ----

    private static List<Autopilot.OfferRecord> accounted(int count, long newestMinutesAgo) {
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) lines.add(outcome(newestMinutesAgo + i, i % 2 == 0, i % 2 == 1, false));
        return lines;
    }

    /** A checkpoint {@code minutesAgo} old with {@code extra} points and the carried-forward rate {@code ar} then. */
    private static Autopilot.State checkpoint(int extra, long minutesAgo, int ar) {
        return new Autopilot.State(true, extra, NOW - minutesAgo * MIN, ar, Autopilot.NEVER, 100, null);
    }

    /** The rate carried forward from a fresh Dasher reading, in hundredths. */
    private static Autopilot.ArNow carried(int hundredths) {
        return new Autopilot.ArNow(hundredths, Autopilot.ArSource.DASHER, 3, -1);
    }

    /** The app's own estimate, in hundredths, with no fresh Dasher reading. */
    private static Autopilot.ArNow estimated(int hundredths) {
        return new Autopilot.ArNow(hundredths, Autopilot.ArSource.ESTIMATE, -1, 25);
    }

    @Test
    public void theFirstCheckpointWaitsForTwentyFiveAccountedOffersAndChangesNothing() {
        Autopilot.Correction few = Autopilot.correction(70, Autopilot.State.INITIAL, carried(6_000), accounted(24, 1),
                NOW);
        assertEquals(Autopilot.NEVER, few.checkpointAt);
        Autopilot.Correction set = Autopilot.correction(70, Autopilot.State.INITIAL, carried(6_000), accounted(25, 1),
                NOW);
        assertEquals(NOW, set.checkpointAt);
        assertEquals("the carried-forward rate, in hundredths", 6_000, set.checkpointAr);
        assertEquals(0, set.extra);
        Autopilot.Correction estimate = Autopilot.correction(70, Autopilot.State.INITIAL, estimated(6_000),
                accounted(25, 1), NOW);
        assertEquals("a checkpoint is set without a Dasher reading too", NOW, estimate.checkpointAt);
        assertEquals("but keeps no rate to compare with", -1, estimate.checkpointAr);
    }

    @Test
    public void aStalledRateAddsTwoAndARisingOneNeverDoes() {
        List<Autopilot.OfferRecord> since = accounted(25, 1);
        Autopilot.Correction stalled = Autopilot.correction(70, checkpoint(0, 100, 6_800), carried(6_800), since, NOW);
        assertEquals("6,800 ≤ checkpoint 6,800, below the goal", 2, stalled.extra);
        assertEquals(NOW, stalled.checkpointAt);
        assertEquals(6_800, stalled.checkpointAr);
        Autopilot.Correction fell = Autopilot.correction(70, checkpoint(2, 100, 6_800), carried(6_100), since, NOW);
        assertEquals(4, fell.extra);
        Autopilot.Correction rising = Autopilot.correction(70, checkpoint(0, 100, 6_000), carried(6_001), since, NOW);
        assertEquals("a recovery raises the rate: nothing added", 0, rising.extra);
        assertEquals("the checkpoint still moves", 6_001, rising.checkpointAr);
        Autopilot.Correction capped = Autopilot.correction(70, checkpoint(10, 100, 5_000), carried(4_000), since, NOW);
        assertEquals(10, capped.extra);
        Autopilot.Correction notYet = Autopilot.correction(70, checkpoint(0, 100, 6_800), carried(6_800),
                accounted(24, 1), NOW);
        assertEquals("only 24 accounted offers since the checkpoint", 0, notYet.extra);
        assertEquals(NOW - 100 * MIN, notYet.checkpointAt);
        assertEquals(6_800, notYet.checkpointAr);

        // Only a rate carried forward from Dasher's reading, against one that was too, can say the rate stalled.
        Autopilot.Correction estimate = Autopilot.correction(70, checkpoint(0, 100, 6_800), estimated(6_000), since,
                NOW);
        assertEquals("the app's own estimate never adds", 0, estimate.extra);
        assertEquals(NOW, estimate.checkpointAt);
        assertEquals(-1, estimate.checkpointAr);
        Autopilot.Correction noRateThen = Autopilot.correction(70, checkpoint(0, 100, -1), carried(6_000), since, NOW);
        assertEquals("nothing to compare with at the checkpoint", 0, noRateThen.extra);
        assertEquals(6_000, noRateThen.checkpointAr);
        Autopilot.Correction unknown = Autopilot.correction(70, checkpoint(4, 100, 6_800), Autopilot.ArNow.UNKNOWN,
                since, NOW);
        assertEquals("an unknown rate changes nothing", 4, unknown.extra);
        assertEquals(-1, unknown.checkpointAr);
    }

    @Test
    public void aRateAtTheGoalPlusFiveReleasesTwoFromAnySource() {
        List<Autopilot.OfferRecord> since = accounted(25, 1);
        assertEquals(2, Autopilot.correction(70, checkpoint(4, 100, 7_000), carried(7_500), since, NOW).extra);
        assertEquals(0, Autopilot.correction(70, checkpoint(0, 100, 7_000), carried(8_000), since, NOW).extra);
        assertEquals("between the goal and the goal + 5: unchanged", 4,
                Autopilot.correction(70, checkpoint(4, 100, 7_000), carried(7_499), since, NOW).extra);
        // With no fresh reading (the user declines through Dasher's notification, which asks nothing), the app's own
        // count still releases it: the correction never stays latched at a rate well above the goal.
        assertEquals(2, Autopilot.correction(70, checkpoint(4, 100, -1), estimated(8_500), since, NOW).extra);
        assertEquals(4, Autopilot.correction(70, checkpoint(4, 100, -1), estimated(7_400), since, NOW).extra);
    }

    @Test
    public void theCorrectionNeedsAGoal() {
        List<Autopilot.OfferRecord> since = accounted(30, 1);
        Autopilot.State state = checkpoint(4, 100, 6_800);
        Autopilot.Correction payFirst = Autopilot.correction(0, state, carried(5_000), since, NOW);
        assertEquals("pay first", 4, payFirst.extra);
        assertEquals("pay first moves no checkpoint", NOW - 100 * MIN, payFirst.checkpointAt);
    }

    @Test
    public void anOldReadingWithAcceptsSinceIsARecoveryNotAStall() {
        // Dasher showed 40% five hours ago, before the checkpoint, and nothing was declined since: 30 accepted offers.
        // The reading never moved, but the rate it carries forward did: 4,000 + 3,000 − 1,200 = 5,800.
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        for (int i = 0; i < 30; i++) lines.add(new Autopilot.OfferRecord(offer(1500, 5.0, 20), NOW - (i + 1) * 5 * MIN,
                false, false, true, false, false));
        Autopilot.Reading forty = new Autopilot.Reading(40, NOW - 300 * MIN);
        for (int extra : new int[] {0, 2, 8}) {
            Autopilot.State state = new Autopilot.State(true, extra, NOW - 200 * MIN, 4_000, Autopilot.NEVER, 100,
                    null);
            Autopilot.Plan plan = Autopilot.plan(new Autopilot.Inputs(starters(70), lines, busy(), forty, state, NOW,
                    1));
            assertEquals(5_800, plan.arHundredths);
            assertEquals(Autopilot.ArSource.DASHER, plan.arSource);
            assertEquals("extra " + extra + " stays: the rate rose from 4,000 to 5,800", extra, plan.extra);
            assertEquals(NOW, plan.checkpointAt);
            assertEquals(5_800, plan.checkpointAr);
        }
    }

    @Test
    public void withoutAFreshReadingTheAppsOwnCountReleasesTheCorrection() {
        // Recovering from 9% with every decline sent through Dasher's notification: no question, no reading. The
        // window turned over long ago; the app's own count shows 85% and the correction comes off, two at a time.
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        for (int i = 0; i < 100; i++) lines.add(outcome(1 + i, i % 20 >= 3, i % 20 < 3, false));
        Autopilot.Reading nine = new Autopilot.Reading(9, NOW - 300 * MIN);
        Autopilot.State latched = new Autopilot.State(false, 4, NOW - 150 * MIN, -1, Autopilot.NEVER, 100, null);
        Autopilot.Plan plan = Autopilot.plan(new Autopilot.Inputs(starters(70), lines, busy(), nine, latched, NOW, 1));
        assertEquals(Autopilot.ArSource.ESTIMATE, plan.arSource);
        assertEquals(8_500, plan.arHundredths);
        assertEquals(2, plan.extra);
        Autopilot.State next = new Autopilot.State(false, plan.extra, NOW - 150 * MIN, plan.checkpointAr,
                Autopilot.NEVER, 100, null);
        assertEquals(0, Autopilot.plan(new Autopilot.Inputs(starters(70), lines, busy(), nine, next, NOW, 1)).extra);
    }

    @Test
    public void aPlanCarriesTheCorrectionIntoItsNeed() {
        List<Autopilot.OfferRecord> lines = accounted(30, 5);
        Autopilot.State stalled = new Autopilot.State(false, 4, NOW - 200 * MIN, 7_400, Autopilot.NEVER, 100, null);
        Autopilot.Plan plan = Autopilot.plan(new Autopilot.Inputs(starters(70), lines, busy(), dasher(74), stalled,
                NOW, 3));
        assertEquals("74 is not below the goal: unchanged, checkpoint moved", 4, plan.extra);
        assertEquals(NOW, plan.checkpointAt);
        assertEquals(7_400, plan.checkpointAr);
        assertEquals("75 + 4", 79, plan.need);
        Autopilot.Plan twenty = Autopilot.plan(new Autopilot.Inputs(starters(70), accounted(20, 5), busy(),
                dasher(74), stalled, NOW, 3));
        assertEquals("20 accounted offers since the checkpoint: it stays", NOW - 200 * MIN, twenty.checkpointAt);
        assertEquals(79, twenty.need);
        assertEquals(3, plan.generation);
        assertEquals(NOW, plan.computedAt);
        assertEquals("true:400:100:25:0:true:70", plan.rulesKey);
    }

    // ---- Commit reasons and the plan's other figures ----

    @Test
    public void aUsersChangeIsTheReasonForItsJump() {
        Autopilot.Plan plan = plan(starters(0), window(), busy(), null, false, 100);
        for (Autopilot.Reason jump : Arrays.asList(Autopilot.Reason.TURNED_ON, Autopilot.Reason.GOAL_CHANGED,
                Autopilot.Reason.RULES_CHANGED, Autopilot.Reason.CLEARED)) {
            assertTrue(jump.jump);
            assertSame(jump, Autopilot.commitReason(plan, 100, 119, jump));
        }
        try {
            Autopilot.commitReason(plan, 100, 119, Autopilot.Reason.VALUE_UP);
            fail("only a user's change is a jump");
        } catch (IllegalArgumentException expected) {
            // A plan's own reason is never a pending jump.
        }
        String[] words = {"Autopilot turned on", "you changed your goal", "you changed your minimums",
                "history cleared", "learning from your offers", "your minimums are high for these offers",
                "acceptance rate below your goal", "keeping enough offers for your goal",
                "keeping at least 1 in 5 offers coming to you", "holding at your minimums", "offers are coming often",
                "offers are slower"};
        Autopilot.Reason[] reasons = Autopilot.Reason.values();
        assertEquals(words.length, reasons.length);
        for (int i = 0; i < words.length; i++) assertEquals(reasons[i].name(), words[i], reasons[i].words);
    }

    @Test
    public void thePlanCarriesCountsSinceTheLastRaiseAndDisplayFigures() {
        Autopilot.State raised = new Autopilot.State(false, 0, Autopilot.NEVER, -1, NOW - 15 * MIN, 105, null);
        Autopilot.Plan plan = Autopilot.plan(new Autopilot.Inputs(starters(0), window(), busy(), null, raised, NOW,
                1));
        assertEquals("lines 5, 8, 11 and 14 minutes old came after the raise 15 minutes ago", 4,
                plan.offersSinceRaise);
        assertEquals(105, plan.current);
        assertEquals(119, plan.target);
        assertEquals("24 minutes of watched waiting over 9 arrivals in the last two hours", 160_000L,
                plan.offerIntervalMs());
        assertEquals("17.1 offers an hour", 171, plan.offersPerHourTenths());
        Autopilot.Paid paid = plan.paidAt(100);
        assertEquals(16, paid.count);
        assertEquals(17_365, paid.payCents);
        assertEquals(403, paid.minutes);
        assertEquals("$25.85 an hour of Dasher's minutes", 2_585, paid.centsPerHour());
        assertEquals(18, plan.passAt(82));

        Autopilot.Plan slowPlan = plan(starters(70), window(), slow(), dasher(74), false, 100);
        assertEquals("61 minutes over 3 arrivals", 1_220_000L, slowPlan.offerIntervalMs());
        assertEquals("3.4 offers an hour", 34, slowPlan.offersPerHourTenths());
        List<QualifyingWait.Sample> two = Arrays.asList(sample(10, 3, offer(500, 3.0, 15)),
                sample(5, 3, offer(500, 3.0, 15)), sample(200, 3, offer(500, 3.0, 15)));
        assertEquals("fewer than 3 arrivals in two hours", -1,
                plan(starters(70), window(), two, dasher(74), false, 100).offerIntervalMs());
    }

    @Test
    public void theEngineAndTheParserUseNoAndroidClass() throws IOException {
        File root = new File("../app").isDirectory() ? new File("..") : new File(".");
        for (String name : Arrays.asList("Autopilot.java", "AcceptanceRate.java")) {
            File source = new File(root, "app/src/main/java/com/local/dasherfilter/" + name);
            String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            assertTrue(name + " was read", text.contains("final class " + name.replace(".java", "")));
            assertFalse(name + " imports no android class", text.contains("import android"));
            assertFalse(name + " names no android class", text.contains("android."));
        }
    }

    @Test
    public void theSameInputsAlwaysGiveTheSamePlan() {
        Autopilot.Inputs inputs = new Autopilot.Inputs(starters(50), window(), busy(), dasher(62),
                Autopilot.State.INITIAL, NOW, 9);
        Autopilot.Plan first = Autopilot.plan(inputs);
        Autopilot.Plan second = Autopilot.plan(inputs);
        assertEquals(first.target, second.target);
        assertEquals(first.mode, second.mode);
        assertEquals(first.bestBar, second.bestBar);
        assertEquals(first.acceptableLow, second.acceptableLow);
        assertEquals(first.acceptableHigh, second.acceptableHigh);
        assertEquals(first.lambdaNumerator, second.lambdaNumerator);
        assertEquals(first.lambdaDenominator, second.lambdaDenominator);
        Autopilot.Plan minuteLater = Autopilot.plan(new Autopilot.Inputs(starters(50), window(), busy(), dasher(62),
                Autopilot.State.INITIAL, NOW + MIN, 9));
        assertEquals("a minute's decay of the weights moves nothing", first.target, minuteLater.target);
    }
}

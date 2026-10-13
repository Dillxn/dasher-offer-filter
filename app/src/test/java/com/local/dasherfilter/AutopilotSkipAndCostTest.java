package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 0.6.10's two inputs to Autopilot: a skipped store's offers fail at every bar, and the driver's own cost per mile
 * (off by default) weighs each offer's pay less that cost × its miles in the value rule only.
 */
public final class AutopilotSkipAndCostTest {
    private static final long NOW = 1_760_000_000_000L;
    private static final long MIN = 60_000L;

    private static FilterSettings rules(int goal) {
        return new FilterSettings(true, 400, 100, 25, 0, true, goal, 100);
    }

    private static Autopilot.OfferRecord line(int pay, double miles, int minutes, long minutesAgo, boolean skipped) {
        return new Autopilot.OfferRecord(new OfferSnapshot(pay, miles, minutes, 2), NOW - minutesAgo * MIN, false,
                false, false, false, false, 100, true, DecisionLog.MODEL, null, skipped);
    }

    private static List<QualifyingWait.Sample> busy() {
        List<QualifyingWait.Sample> waits = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            waits.add(new QualifyingWait.Sample(NOW - (40 - 4 * i) * MIN, 2 * MIN, new OfferSnapshot(900, 5.0, 24, 2)));
        }
        return waits;
    }

    private static Autopilot.Plan plan(List<Autopilot.OfferRecord> lines, int goal, int cost) {
        Autopilot.State state = new Autopilot.State(false, 0, Autopilot.NEVER, -1, Autopilot.NEVER, 100, null);
        return Autopilot.plan(new Autopilot.Inputs(rules(goal), lines, busy(), new Autopilot.Reading(72, NOW - MIN),
                state, NOW, 7, Long.MAX_VALUE, java.util.TimeZone.getTimeZone("UTC"), cost));
    }

    private static List<Autopilot.OfferRecord> window(int skippedCount) {
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            // Every offer pays well above the minimums ($4, $1/mi, $15/hr): 150% or more.
            lines.add(line(1500 + 10 * i, 5.0, 20, 5 + 3L * i, i < skippedCount));
        }
        return lines;
    }

    @Test public void skippedStoresFailAtEveryBarSoTheBarComesDownForTheGoal() {
        Autopilot.Plan none = plan(window(0), 70, 0);
        Autopilot.Plan some = plan(window(12), 70, 0);
        assertEquals(30, some.counted);
        assertTrue("a skipped offer passes at no bar: " + some.passAtTarget, some.passAtTarget <= 18);
        assertTrue("the bar never rises for skipped offers: " + some.target + " vs " + none.target,
                some.target <= none.target);
    }

    @Test public void costPerMileWeighsPayAfterItInTheValueRuleOnly() {
        FilterSettings rules = rules(70);
        List<Autopilot.OfferRecord> lines = Arrays.asList(line(1000, 10.0, 30, 5, false), line(800, 2.0, 20, 8, false),
                new Autopilot.OfferRecord(new OfferSnapshot(900, null, 20, 2), NOW - 9 * MIN, false, false, false,
                        false, false));
        int[] thetas = {120, 110, 100};
        List<Autopilot.MixLine> off = Autopilot.mix(rules, lines, thetas);
        List<Autopilot.MixLine> zero = Autopilot.mix(rules, lines, thetas, 0);
        assertEquals(off.size(), zero.size());
        List<Autopilot.MixLine> costed = Autopilot.mix(rules, lines, thetas, 35);
        // $10.00 less 10 mi × 35¢ = $6.50; $8.00 less 2 mi × 35¢ = $7.30; the offer with unread miles is left out.
        assertEquals(2, costed.size());
        assertEquals(650, costed.get(0).payCents);
        assertEquals(730, costed.get(1).payCents);
        // Never below nothing.
        assertEquals(0, Autopilot.mix(rules, Arrays.asList(line(500, 10.0, 20, 5, false)), new int[] {100}, 200)
                .get(0).payCents);
    }

    @Test public void costPerMileIsHeldToItsRangeAndSaidPlainly() {
        assertEquals(0, CostPerMile.clamp(-5));
        assertEquals(CostPerMile.MOST_CENTS, CostPerMile.clamp(10_000));
        assertEquals("Off", CostPerMile.words(0));
        assertEquals("35¢ a mile", CostPerMile.words(35));
        assertEquals("$1.25 a mile", CostPerMile.words(125));
        assertTrue(!CostPerMile.ABOUT.contains("IRS") && !CostPerMile.ABOUT.contains("72"));
    }
}

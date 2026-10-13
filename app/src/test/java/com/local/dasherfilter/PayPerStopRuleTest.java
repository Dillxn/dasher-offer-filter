package com.local.dasherfilter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pay per stop (0.6.12; the owner, 13 October 2026: "swap overall payout for pay per stop"), rule C: the pay minimum
 * is asked for each stop after the first pickup, n = max(1, stops − 1). Single orders (Dasher's 2 stops) are judged as
 * before; unread stops decline only on the known lower bound (one stop) and otherwise stay for review.
 */
public final class PayPerStopRuleTest {
    private static final FilterSettings PER_STOP = FilterSettings.of(true, 1300, 0, 0, 0);

    private static OfferSnapshot offer(Integer pay, Double miles, Integer minutes, Integer stops) {
        return new OfferSnapshot(pay, miles, minutes, stops);
    }

    private static OfferRule.Decision judge(OfferSnapshot offer, FilterSettings rules) {
        return OfferRule.evaluate(offer, rules);
    }

    @Test public void unitsAreEachStopAfterTheFirstPickup() {
        assertNull(AreaScore.payUnits(null));
        assertNull("a read of 0 counts as unread", AreaScore.payUnits(0));
        assertEquals(Integer.valueOf(1), AreaScore.payUnits(1));
        assertEquals(Integer.valueOf(1), AreaScore.payUnits(2));
        assertEquals(Integer.valueOf(2), AreaScore.payUnits(3));
        assertEquals(Integer.valueOf(3), AreaScore.payUnits(4));
    }

    @Test public void theAskScalesWithStops() {
        int[][] cases = {{1, 1300}, {2, 1300}, {3, 2600}, {4, 3900}};
        for (int[] c : cases) {
            OfferRule.Decision short1 = judge(offer(c[1] - 1, 3.0, 15, c[0]), PER_STOP);
            assertEquals(c[0] + " stops", OfferRule.Result.DECLINE, short1.result);
            assertEquals(c[1], short1.requiredCents);
            assertEquals(OfferRule.PAY_PER_STOP, short1.reason);
            assertEquals(c[0] + " stops", OfferRule.Result.KEEP, judge(offer(c[1], 3.0, 15, c[0]), PER_STOP).result);
        }
    }

    @Test public void unreadStopsDeclineOnlyBelowOneStopAndOtherwiseReview() {
        OfferRule.Decision low = judge(offer(1200, 3.0, 15, null), PER_STOP);
        assertEquals(OfferRule.Result.DECLINE, low.result);
        assertEquals(OfferRule.PAY_PER_STOP, low.reason);
        assertEquals(1300, low.requiredCents);
        OfferRule.Decision high = judge(offer(2000, 3.0, 15, null), PER_STOP);
        assertEquals(OfferRule.Result.REVIEW, high.result);
        assertEquals(OfferRule.VALUE_NOT_FOUND, high.reason);
        assertEquals(-1, high.scorePercent);
        assertEquals(-1, AreaScore.requiredPay(PER_STOP, offer(2000, 3.0, 15, null), 100));
        // The +$ ceiling's own shape and Autopilot's threshold use the same lower bound.
        assertEquals(92, AreaScore.passThreshold(PER_STOP, offer(1200, 3.0, 15, null)));
    }

    @Test public void perMileBindsEitherWay() {
        FilterSettings rules = FilterSettings.of(true, 1300, 200, 0, 0);
        OfferRule.Decision stops = judge(offer(2500, 10.0, 30, 3), rules);
        assertEquals(2600, stops.requiredCents);
        assertEquals(OfferRule.PAY_PER_STOP, stops.reason);
        OfferRule.Decision miles = judge(offer(3900, 20.0, 30, 3), rules);
        assertEquals(4000, miles.requiredCents);
        assertEquals("dollars per mile", miles.reason);
        // Unread stops: miles bind above the one-stop lower bound, and still decline.
        OfferRule.Decision unread = judge(offer(1900, 10.0, 30, null), rules);
        assertEquals(OfferRule.Result.DECLINE, unread.result);
        assertEquals("dollars per mile", unread.reason);
    }

    @Test public void theBarRoundsUpTheScaledAsk() {
        OfferRule.Decision at82 = judge(offer(2131, 3.0, 15, 3), PER_STOP.withMinimumScalePercent(82));
        assertEquals(OfferRule.Result.DECLINE, at82.result);
        assertEquals(2132, at82.requiredCents);
        assertEquals("82% bar: " + OfferRule.PAY_PER_STOP, at82.reason);
        assertEquals(OfferRule.Result.KEEP, judge(offer(2132, 3.0, 15, 3), PER_STOP.withMinimumScalePercent(82)).result);
        assertEquals(3094, judge(offer(3000, 3.0, 15, 3), PER_STOP.withMinimumScalePercent(119)).requiredCents);
    }

    @Test public void maxStopsComesFirst() {
        OfferRule.Decision decision = judge(offer(9000, 3.0, 15, 4), PER_STOP.withMaxStops(3));
        assertEquals(OfferRule.Result.DECLINE, decision.result);
        assertEquals("4 stops exceeds maximum 3", decision.reason);
    }

    @Test public void theScoreIsPayOverTheAsk() {
        assertEquals(75, AreaScore.scorePercent(PER_STOP, offer(1950, 3.0, 15, 3)));
        assertEquals(150, AreaScore.scorePercent(PER_STOP, offer(1950, 3.0, 15, 2)));
    }

    @Test public void moreStopsNeverTurnADeclineIntoAKeep() {
        FilterSettings[] all = {PER_STOP, FilterSettings.of(true, 900, 150, 20, 0),
                FilterSettings.of(true, 1300, 0, 0, 0).withMinimumScalePercent(82),
                FilterSettings.of(true, 1300, 100, 0, 0).withScoreByArea(true)};
        for (FilterSettings rules : all) {
            for (int pay = 500; pay <= 6000; pay += 37) {
                for (int stops = 1; stops < 7; stops++) {
                    if (judge(offer(pay, 5.0, 20, stops), rules).result != OfferRule.Result.DECLINE) continue;
                    assertEquals(rules.describe() + " $" + pay + " " + stops, OfferRule.Result.DECLINE,
                            judge(offer(pay, 5.0, 20, stops + 1), rules).result);
                }
                // Raising pay per stop never turns a decline into a keep either.
                for (int stops = 1; stops < 6; stops++) {
                    if (judge(offer(pay, 5.0, 20, stops), rules).result != OfferRule.Result.DECLINE) continue;
                    FilterSettings higher = rules.withMinimums(rules.flatCents + 100, rules.perMileCents,
                            rules.perMinuteCents);
                    assertEquals(OfferRule.Result.DECLINE, judge(offer(pay, 5.0, 20, stops), higher).result);
                }
            }
        }
    }

    @Test public void areaModeAsksPerStopAndNeedsTheStops() {
        FilterSettings area = PER_STOP.withScoreByArea(true);
        assertEquals(2600, AreaScore.requiredPay(area, offer(2000, 3.0, 15, 3), 100));
        assertEquals(OfferRule.Result.DECLINE, judge(offer(2599, 3.0, 15, 3), area).result);
        assertEquals(OfferRule.Result.KEEP, judge(offer(2600, 3.0, 15, 3), area).result);
        assertEquals(OfferRule.Result.REVIEW, judge(offer(9000, 3.0, 15, null), area).result);
    }

    @Test public void thePlusCeilingIsSingleOrderAsBeforeAndStackedPerStop() {
        OfferRule.Decision single = judge(new OfferSnapshot(null, 3.0, 15, 2, 1200), PER_STOP);
        assertEquals(OfferRule.Result.DECLINE, single.result);
        assertEquals(1300, single.requiredCents);
        OfferRule.Decision stacked = judge(new OfferSnapshot(null, 3.0, 15, 3, 2000), PER_STOP);
        assertEquals(OfferRule.Result.DECLINE, stacked.result);
        assertEquals(2600, stacked.requiredCents);
        assertTrue(stacked.reason, stacked.reason.endsWith(OfferRule.PAY_PER_STOP));
        assertEquals(OfferRule.Result.REVIEW, judge(new OfferSnapshot(null, 3.0, 15, 3, 2700), PER_STOP).result);
    }

    @Test public void theLearnerKeepsTheBestPayPerStop() {
        AcceptedFloors first = AcceptedFloors.NONE.raisedBy(offer(2600, 5.0, 20, 3));
        assertEquals(2600, first.highestPayCents);
        assertEquals(2, first.payUnits);
        AcceptedFloors lower = first.raisedBy(offer(2500, 5.0, 20, 2));
        assertEquals("$25 a stop beats $13 a stop", 2500, lower.highestPayCents);
        assertEquals(1, lower.payUnits);
        AcceptedFloors less = lower.raisedBy(offer(4800, 5.0, 20, 3));
        assertEquals("a higher pay that is less per stop teaches nothing on pay", 2500, less.highestPayCents);
        assertEquals(1, less.payUnits);
        AcceptedFloors unread = first.raisedBy(offer(9000, 5.0, 20, null));
        assertEquals("unread stops teach nothing on pay", 2600, unread.highestPayCents);
        assertEquals(2, unread.payUnits);
        assertTrue("its rates still teach", unread.milePay == 9000);
    }

    @Test public void theLearnedAskScalesWithStopsAndHasALowerBound() {
        FilterSettings learning = FilterSettings.of(true, 0, 0, 0, 0).withAcceptanceLearning(true)
                .withAccepted(new AcceptedFloors(2600, 2, 0, 0, 0, 0));
        assertEquals(1301, AreaScore.asks(learning, offer(1, 5.0, 20, 2)).required(100));
        assertEquals(2601, AreaScore.asks(learning, offer(1, 5.0, 20, 3)).required(100));
        assertEquals(3901, AreaScore.asks(learning, offer(1, 5.0, 20, 4)).required(100));
        assertEquals(OfferRule.Result.DECLINE, judge(offer(1300, 5.0, 20, null), learning).result);
        assertEquals(OfferRule.Result.REVIEW, judge(offer(1400, 5.0, 20, null), learning).result);
        assertEquals("one best from before 0.6.12 counts as one stop", 1,
                new AcceptedFloors(2600, 0, 0, 0, 0).payUnits);
    }

    @Test public void learningDominatesComparesPerStop() {
        AcceptedFloors best = new AcceptedFloors(2600, 2, 0, 0, 0, 0);
        assertTrue(Growth.learningDominates(FilterSettings.of(true, 1300, 0, 0, 0).withAcceptanceLearning(true)
                .withAccepted(best)));
        assertFalse(Growth.learningDominates(FilterSettings.of(true, 1301, 0, 0, 0).withAcceptanceLearning(true)
                .withAccepted(best)));
    }

    @Test public void theRulesSayPerStop() {
        FilterSettings rules = FilterSettings.of(true, 400, 100, 25, 0);
        assertEquals("$4.00 per stop · $1.00 per mile · $15.00 per hour", rules.describe());
        assertEquals("$4/stop · $1/mi · $15/hr", rules.brief());
        assertTrue(rules.scoringKey().endsWith(":ps"));
    }
}

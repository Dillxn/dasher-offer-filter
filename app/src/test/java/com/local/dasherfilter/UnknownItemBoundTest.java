package com.local.dasherfilter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** An unread positive item count may prove failure from one item, but can never prove a pass. */
public final class UnknownItemBoundTest {
    private static OfferSnapshot shopping(int pay, Double miles, Integer minutes, Integer stops, Integer items) {
        return new OfferSnapshot(pay, miles, minutes, stops).withItems(items, true);
    }

    private static FilterSettings reportedRules() {
        return new FilterSettings(true, 1950, 409, 59, 750, 3)
                .withPerItem(735).withScoreByArea(true);
    }

    @Test public void theUnreadReportsFailEvenAtTheirBestPossibleOneItemScore() {
        Object[][] cases = {
                {945, 7.5, 34, 2, 63},
                {945, 7.5, 33, 2, 64},
                {640, 4.6, 17, 2, 53},
                {1675, 12.3, 46, 3, 92}
        };
        for (Object[] row : cases) {
            OfferSnapshot unknown = shopping((Integer) row[0], (Double) row[1],
                    (Integer) row[2], (Integer) row[3], null);
            OfferRule.Decision decision = OfferRule.evaluate(unknown, reportedRules());
            assertEquals(OfferRule.Result.DECLINE, decision.result);
            assertEquals("an unknown count has no plotted score", -1, decision.scorePercent);
            assertTrue(decision.reason, decision.reason.contains(
                    "even 1 item scores " + row[4] + "% (needs 100%)"));
            for (int count : new int[] {1, 2, 10, 100}) {
                int score = AreaScore.percent(reportedRules(), unknown.withItems(count, true));
                assertTrue("larger real counts cannot exceed the one-item bound", score <= (Integer) row[4]);
            }
        }
    }

    @Test public void aPossibleOneItemPassRemainsReviewAndNeverBecomesKeep() {
        FilterSettings area = new FilterSettings(true, 500, 0, 0, 0, 0)
                .withPerItem(100).withScoreByArea(true);
        OfferRule.Decision unknown = OfferRule.evaluate(shopping(600, null, null, null, null), area);
        assertEquals(OfferRule.Result.REVIEW, unknown.result);
        assertEquals("item count not found", unknown.reason);
        assertEquals(OfferRule.Result.KEEP,
                OfferRule.evaluate(shopping(600, null, null, null, 1), area).result);
        assertEquals(OfferRule.Result.DECLINE,
                OfferRule.evaluate(shopping(600, null, null, null, 10), area).result);
    }

    @Test public void anotherUnreadAxisKeepsAreaModeInReview() {
        OfferRule.Decision decision = OfferRule.evaluate(
                shopping(100, null, 30, 2, null), reportedRules());
        assertEquals(OfferRule.Result.REVIEW, decision.result);
        assertEquals("item count not found", decision.reason);
    }

    @Test public void strictModeUsesOneItemOnlyAsALowerBound() {
        FilterSettings strict = new FilterSettings(true, 0, 0, 0, 0, 0).withPerItem(735);
        OfferRule.Decision failure = OfferRule.evaluate(shopping(734, null, null, null, null), strict);
        assertEquals(OfferRule.Result.DECLINE, failure.result);
        assertEquals(735, failure.requiredCents);
        assertEquals("dollars per item (at least 1 item)", failure.reason);
        assertEquals(OfferRule.Result.REVIEW,
                OfferRule.evaluate(shopping(735, null, null, null, null), strict).result);
    }

    @Test public void learnedItemLowerBoundAlsoDeclinesButDoesNotInventTheCount() {
        AcceptedBest best = AcceptedBest.NONE.raisedBy(shopping(1000, null, null, null, 2));
        FilterSettings strict = new FilterSettings(true, 0, 0, 0, 0, 0, true, 0, best);
        OfferRule.Decision failure = OfferRule.evaluate(shopping(499, null, null, null, null), strict);
        assertEquals(OfferRule.Result.DECLINE, failure.result);
        assertEquals(500, failure.requiredCents);
        assertTrue(failure.reason, failure.reason.contains("for at least 1 item"));
        assertEquals(OfferRule.Result.REVIEW,
                OfferRule.evaluate(shopping(500, null, null, null, null), strict).result);
    }
}

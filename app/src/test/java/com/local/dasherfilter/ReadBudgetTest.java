package com.local.dasherfilter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The read budget's arithmetic on a fake clock: the token bucket, backoff from cost, delivery calm and the watchdog. */
public class ReadBudgetTest {
    private static final long QUIET_PAST = Long.MIN_VALUE / 8;

    @Test
    public void aFullBucketAllowsTwoReadsAtOnceThenFourASecond() {
        ReadBudget budget = new ReadBudget();
        long now = 10_000;
        assertTrue(budget.dueAt(QUIET_PAST, false) <= now);
        budget.take(now, false);
        assertTrue("the burst's second read", budget.dueAt(QUIET_PAST, false) <= now);
        budget.take(now, false);
        assertEquals("then one every 250 ms", now + 250, budget.dueAt(QUIET_PAST, false));
        budget.take(now + 250, false);
        assertEquals(now + 500, budget.dueAt(QUIET_PAST, false));

        // Over any long run: at most four reads a second, plus the burst.
        ReadBudget sustained = new ReadBudget();
        int reads = 0;
        for (long t = 0; t < 60_000; t++) {
            if (sustained.dueAt(QUIET_PAST, false) <= t) {
                sustained.take(t, false);
                reads++;
            }
        }
        assertTrue("reads in a minute: " + reads, reads <= 4 * 60 + ReadBudget.BURST);
        assertTrue(reads >= 4 * 60);
    }

    @Test
    public void duringADeliveryOneReadASecondAtMost() {
        ReadBudget budget = new ReadBudget();
        int reads = 0;
        long last = Long.MIN_VALUE / 2;
        for (long t = 0; t < 60_000; t++) {
            if (budget.dueAt(QUIET_PAST, true) <= t) {
                assertTrue("a second apart", t - last >= 1_000);
                budget.take(t, true);
                last = t;
                reads++;
            }
        }
        assertTrue("reads in a minute: " + reads, reads <= 60);
    }

    @Test
    public void theQuietGapStillRulesACheapScreen() {
        ReadBudget budget = new ReadBudget();
        budget.factFree(1_000, 1_020);
        assertEquals("a cheap read leaves the 150 ms rule alone", 1_170, budget.dueAt(1_170, false));
        assertEquals(1_000, budget.dueAt(1_000, false));
    }

    @Test
    public void aCostlyReadIsFollowedNoSoonerThanThreeTimesItsCostAndSlowOnesInARowBackOffToTwoSeconds() {
        ReadBudget budget = new ReadBudget();
        budget.factFree(0, 100);
        assertEquals("three times 100 ms after it ended", 400, budget.dueAt(0, false));
        budget.factFree(1_000, 1_300);
        assertEquals("a slow read: three times its cost", 1_300 + 900, budget.dueAt(0, false));
        budget.factFree(2_000, 2_300);
        assertEquals("slow twice in a row: the gap doubles", 2_300 + 1_800, budget.dueAt(0, false));
        budget.factFree(4_000, 4_300);
        assertEquals("doubling stops at 2 s", 4_300 + 2_000, budget.dueAt(0, false));
        budget.factFree(7_000, 9_000);
        assertEquals("a 2 s read rests three times as long: never more than a quarter of Dasher's time", 9_000 + 6_000,
                budget.dueAt(0, false));
        // A fast read ends the streak: the caller's own quiet gap rules again.
        budget.factFree(16_000, 16_040);
        assertEquals(0, budget.dueAt(0, false));
        budget.factFree(17_000, 17_300);
        assertEquals("a slow read after a fast one: three times its cost, no doubling", 18_200, budget.dueAt(0, false));
    }

    @Test
    public void readsHeldBackOnTheBudgetTakeAQuarterOfDashersTimeAtMostHoweverLongEachTakes() {
        for (long took : new long[] {20, 60, 100, 300, 1_000, 2_700, 7_500}) {
            ReadBudget budget = new ReadBudget();
            long busy = 0;
            long t = 0;
            while (t < 600_000) {
                long start = Math.max(t, budget.dueAt(QUIET_PAST, false));
                budget.take(start, false);
                budget.factFree(start, start + took);
                busy += took;
                t = start + took;
            }
            // A quarter of the time at most (the last read may end just after the span).
            assertTrue(took + " ms reads: " + busy + " of " + t + " ms", busy <= t / 4 + took);
        }
    }

    @Test
    public void anOffersSignRefillsTheBucketAndForgetsTheCost() {
        ReadBudget budget = new ReadBudget();
        budget.take(0, false);
        budget.take(0, false);
        budget.factFree(0, 900);
        assertTrue(budget.dueAt(0, false) > 1_000);
        budget.evidence();
        assertTrue("an offer's sign: the bucket is full, the cost forgotten", budget.dueAt(0, false) <= 0);
    }

    @Test
    public void dasherSlowToAnswerPausesReadsThreeSecondsThenLongerWhileItLasts() {
        ReadBudget budget = new ReadBudget();
        long now = 0;
        assertEquals("one slow read is not a pattern", -1, budget.fetched(300, true, now));
        assertEquals(-1, budget.fetched(300, true, now += 2_000));
        long median = budget.fetched(300, true, now += 2_000);
        assertEquals("three slow reads: their median, and a pause", 300, median);
        assertTrue(budget.yielding(now));
        assertEquals(now + ReadBudget.FIRST_YIELD_MS, budget.yieldUntil());
        assertEquals(now + ReadBudget.FIRST_YIELD_MS, budget.dueAt(0, false));
        assertFalse(budget.yielding(now + ReadBudget.FIRST_YIELD_MS));

        // The first read after it is a probe: still slow, a pause twice as long, then capped at 10 s.
        now += ReadBudget.FIRST_YIELD_MS;
        assertTrue(budget.fetched(320, true, now) > 0);
        assertEquals(now + 6_000, budget.yieldUntil());
        now += 6_000;
        assertTrue(budget.fetched(320, true, now) > 0);
        assertEquals(now + ReadBudget.MAX_YIELD_MS, budget.yieldUntil());
        now += ReadBudget.MAX_YIELD_MS;
        assertTrue(budget.fetched(320, true, now) > 0);
        assertEquals("never more than 10 s", now + ReadBudget.MAX_YIELD_MS, budget.yieldUntil());

        // Dasher answers in time again: no pause, and Dasher's slowness is judged afresh from that read on.
        now += ReadBudget.MAX_YIELD_MS;
        assertEquals(-1, budget.fetched(20, true, now));
        assertFalse(budget.yielding(now));
        assertEquals(20, budget.median());
        assertEquals(-1, budget.fetched(300, true, now += 1_000));
        assertEquals("the median of 20, 300, 300 is over 200 ms: a 3 s pause again", 300,
                budget.fetched(300, true, now += 1_000));
        assertEquals(now + ReadBudget.FIRST_YIELD_MS, budget.yieldUntil());
    }

    @Test
    public void onlyAReadWithNothingOfAnOfferStartsAPauseAndOnlyAFreshJudgementEndsOne() {
        ReadBudget budget = new ReadBudget();
        budget.fetched(400, false, 0);
        budget.fetched(400, false, 0);
        assertEquals("slow offer reads are never paused", -1, budget.fetched(400, false, 0));
        assertEquals("the next read with nothing of an offer is", 400, budget.fetched(400, true, 0));
        assertTrue(budget.yielding(1));
        // An offer's sign does not end it (an offer is read at once whatever the budget says): a figure flickering on
        // a slow screen cannot keep the watchdog from pausing.
        budget.evidence();
        assertTrue(budget.yielding(1));
        // An offer's notification judges Dasher afresh.
        budget.fresh();
        assertFalse(budget.yielding(1));
        assertEquals(0, budget.median());
    }

    @Test
    public void theMedianOfTheLastTenReads() {
        ReadBudget budget = new ReadBudget();
        assertEquals(0, budget.median());
        for (int i = 0; i < 9; i++) budget.fetched(10, false, 0);
        budget.fetched(1_000, false, 0);
        assertEquals(10, budget.median());
        for (int i = 0; i < 6; i++) budget.fetched(500, false, 0);
        assertEquals("six of the last ten slow", 500, budget.median());
        budget.fresh();
        assertEquals("judged afresh after an offer's notification", 0, budget.median());
    }
}

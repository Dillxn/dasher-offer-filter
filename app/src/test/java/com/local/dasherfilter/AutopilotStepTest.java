package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * How Autopilot moves the bar between offers (finalSpec.autopilot.decisionRule, Commit): a user's change jumps; one
 * commit a minute at most; a 3-point deadband with a snap back to exactly 100; down at most 20 at a time; up at most 5,
 * and only after five minutes and three counted offers since the last raise; always within 50–150.
 */
public final class AutopilotStepTest {
    private static final long MIN = 60_000L;
    /** Long enough since the last commit and the last raise, with offers enough, that only the rules below hold. */
    private static final long LONG_AGO = 60 * MIN;
    private static final int MANY = 100;

    private static Autopilot.Step free(int current, int target) {
        return Autopilot.step(current, target, false, LONG_AGO, LONG_AGO, MANY);
    }

    private static void assertStep(String what, int next, Autopilot.StepKind kind, Autopilot.Step step) {
        assertEquals(what + ": next", next, step.next);
        assertEquals(what + ": kind", kind, step.kind);
    }

    @Test
    public void commitsAreAtLeastAMinuteApart() {
        assertStep("59.999 s after the last commit", 100, Autopilot.StepKind.SPACING,
                Autopilot.step(100, 80, false, 59_999, LONG_AGO, MANY));
        assertStep("a minute after", 80, Autopilot.StepKind.LOWER, Autopilot.step(100, 80, false, 60_000, LONG_AGO,
                MANY));
        assertStep("the spacing holds a snap too", 98, Autopilot.StepKind.SPACING,
                Autopilot.step(98, 100, false, 30_000, LONG_AGO, MANY));
        assertStep("already there", 82, Autopilot.StepKind.HOLD, Autopilot.step(82, 82, false, 0, 0, 0));
    }

    @Test
    public void withinThreeOfOneHundredATargetOfOneHundredSnapsBackExactly() {
        for (int current : new int[] {98, 99, 101, 102}) {
            assertStep("from " + current, 100, Autopilot.StepKind.SNAP, free(current, 100));
            assertStep("from " + current + ", even right after a raise", 100, Autopilot.StepKind.SNAP,
                    Autopilot.step(current, 100, false, MIN, 0, 0));
        }
        assertStep("from 97 it is an ordinary raise", 100, Autopilot.StepKind.RAISE, free(97, 100));
        assertStep("from 103 an ordinary lowering", 100, Autopilot.StepKind.LOWER, free(103, 100));
    }

    @Test
    public void aMoveOfLessThanThreePointsHolds() {
        assertStep("100 → 102", 100, Autopilot.StepKind.DEADBAND, free(100, 102));
        assertStep("100 → 98: no snap away from 100", 100, Autopilot.StepKind.DEADBAND, free(100, 98));
        assertStep("85 → 87", 85, Autopilot.StepKind.DEADBAND, free(85, 87));
        assertStep("85 → 83", 85, Autopilot.StepKind.DEADBAND, free(85, 83));
        assertStep("121 vs 120", 120, Autopilot.StepKind.DEADBAND, free(120, 121));
        assertStep("100 → 103 raises to 103", 103, Autopilot.StepKind.RAISE, free(100, 103));
        assertStep("100 → 97 lowers to 97", 97, Autopilot.StepKind.LOWER, free(100, 97));
    }

    @Test
    public void loweringGoesAtMostTwentyAtATime() {
        assertStep("100 → 82 in one commit", 82, Autopilot.StepKind.LOWER, free(100, 82));
        List<Integer> path = new ArrayList<>();
        int bar = 100;
        for (int minute = 0; minute < 5 && bar != 50; minute++) {
            bar = Autopilot.step(bar, 50, false, MIN, LONG_AGO, MANY).next;
            path.add(bar);
        }
        assertEquals("100 → 50: 80, 60, 50, a minute apart", Arrays.asList(80, 60, 50), path);
    }

    @Test
    public void raisingWaitsFiveMinutesAndThreeOffersAndGoesFiveAtATime() {
        assertStep("4:59 since the last raise", 100, Autopilot.StepKind.RAISE_WAIT,
                Autopilot.step(100, 121, false, LONG_AGO, 299_999, MANY));
        assertStep("two offers since", 100, Autopilot.StepKind.RAISE_WAIT,
                Autopilot.step(100, 121, false, LONG_AGO, LONG_AGO, 2));
        assertStep("five minutes and three offers", 105, Autopilot.StepKind.RAISE,
                Autopilot.step(100, 121, false, LONG_AGO, 300_000, 3));
    }

    @Test
    public void oneHundredToOneHundredTwentyOneAtAnOfferEveryTwoMinutes() {
        // A tick a minute, an offer every two minutes; the first raise finds nothing recent.
        int bar = 100;
        long sinceCommit = LONG_AGO;
        long sinceRaise = LONG_AGO;
        int offersSinceRaise = MANY;
        List<String> commits = new ArrayList<>();
        for (int minute = 0; minute <= 30; minute++) {
            if (minute > 0 && minute % 2 == 0) offersSinceRaise++;
            Autopilot.Step step = Autopilot.step(bar, 121, false, sinceCommit, sinceRaise, offersSinceRaise);
            if (step.next != bar) {
                commits.add(step.next + " at " + minute + " min");
                if (step.next > bar) {
                    sinceRaise = 0;
                    offersSinceRaise = 0;
                }
                sinceCommit = 0;
                bar = step.next;
            }
            sinceCommit += MIN;
            sinceRaise += MIN;
        }
        assertEquals("105, then 110 (about 6 min), 115 (about 12), 120 (about 18); then the deadband holds 120",
                Arrays.asList("105 at 0 min", "110 at 6 min", "115 at 12 min", "120 at 18 min"), commits);
    }

    @Test
    public void aUsersChangeJumpsStraightToTheTarget() {
        assertStep("past the spacing and the down step", 50, Autopilot.StepKind.JUMP,
                Autopilot.step(100, 50, true, 0, 0, 0));
        assertStep("past the raise limits", 150, Autopilot.StepKind.JUMP, Autopilot.step(60, 150, true, 0, 0, 0));
        assertStep("past the deadband", 101, Autopilot.StepKind.JUMP, Autopilot.step(100, 101, true, 0, 0, 0));
        assertStep("to where it already is", 82, Autopilot.StepKind.JUMP, Autopilot.step(82, 82, true, 0, 0, 0));
    }

    @Test
    public void everyResultStaysWithinFiftyToOneHundredFifty() {
        assertEquals(50, Autopilot.step(100, 30, true, 0, 0, 0).next);
        assertEquals(150, Autopilot.step(100, 180, true, 0, 0, 0).next);
        assertEquals("a stored bar above the range comes down to it", 150, free(170, 160).next);
        assertEquals("a stored bar below the range comes up to it", 50, free(40, 45).next);
        assertEquals(50, free(55, 20).next);
        assertEquals(150, free(148, 200).next);
        for (int current = 50; current <= 150; current++) {
            for (int target = 50; target <= 150; target++) {
                Autopilot.Step step = free(current, target);
                int moved = step.next - current;
                assertEquals("never past the target", true, target >= current ? moved <= target - current
                        : moved >= target - current);
                assertEquals("at most 20 down, 5 up", true, moved >= -20 && moved <= 5);
            }
        }
    }
}

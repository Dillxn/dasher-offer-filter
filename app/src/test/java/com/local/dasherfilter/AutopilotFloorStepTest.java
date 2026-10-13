package com.local.dasherfilter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * The accepted floor's commit step (0.6.11, the owner: "if an accepted offer's score beats the autopilot ask, the ask
 * needs to jump to the new higher score"): up to the floor at once, past the commit spacing, the raise wait, the
 * deadband and the 5-point limit, at most 150, never down for it; a bar the floor put goes straight back to the plan's
 * own target once no floor holds it. Without a floor, exactly the old step.
 */
public final class AutopilotFloorStepTest {
    private static final long MIN = 60_000L;

    private static Autopilot.Step floor(int current, int target, long sinceCommitMs, long sinceRaiseMs,
                                        int offersSinceRaise, int floor) {
        return Autopilot.step(current, target, false, sinceCommitMs, sinceRaiseMs, offersSinceRaise, 50, floor, false);
    }

    private static void assertStep(int next, Autopilot.StepKind kind, Autopilot.Step step) {
        assertEquals("next", next, step.next);
        assertSame("kind", kind, step.kind);
    }

    @Test
    public void theFloorJumpsStraightUpPastEveryLimit() {
        assertStep(63, Autopilot.StepKind.FLOOR, floor(50, 63, 0, 0, 0, 63));
        // Long since the last commit and raise, but a 1-point gap: the deadband alone would hold.
        assertStep(63, Autopilot.StepKind.FLOOR, floor(62, 63, 10 * MIN, 10 * MIN, 5, 63));
        // A raise would wait for five minutes and three offers.
        assertStep(80, Autopilot.StepKind.FLOOR, floor(60, 80, 2 * MIN, MIN, 1, 80));
        // A raise would be at most 5 points.
        assertStep(95, Autopilot.StepKind.FLOOR, floor(50, 95, 10 * MIN, 10 * MIN, 5, 95));
        assertEquals("the old step would have raised 5", 55,
                Autopilot.step(50, 95, false, 10 * MIN, 10 * MIN, 5, 50).next);
    }

    @Test
    public void theFloorIsCappedAtOneHundredFifty() {
        assertStep(150, Autopilot.StepKind.FLOOR, floor(50, 150, 0, 0, 0, 170));
        assertStep(150, Autopilot.StepKind.FLOOR, floor(149, 150, 0, 0, 0, 150));
    }

    @Test
    public void theFloorNeverMovesTheBarDownAndWithoutOneTheStepIsTheOldOne() {
        long[] sinceCommit = {0, 30_000, MIN, 10 * MIN};
        long[] sinceRaise = {0, MIN, 5 * MIN, 10 * MIN};
        int[] offers = {0, 2, 3, 10};
        int[] limits = {-1, 50, 80, 120};
        for (int current = 50; current <= 150; current += 3) {
            for (int target = 50; target <= 150; target += 4) {
                for (long commit : sinceCommit) {
                    for (long raise : sinceRaise) {
                        for (int offer : offers) {
                            for (int limit : limits) {
                                Autopilot.Step old = Autopilot.step(current, target, false, commit, raise, offer,
                                        limit);
                                Autopilot.Step none = Autopilot.step(current, target, false, commit, raise, offer,
                                        limit, -1, false);
                                assertEquals(old.next, none.next);
                                assertSame(old.kind, none.kind);
                                // A floor at or below the old step's bar changes nothing: it never pulls the bar down.
                                for (int at : new int[] {Autopilot.BAR_MIN, old.next - 1, old.next}) {
                                    if (at < Autopilot.BAR_MIN) continue;
                                    Autopilot.Step low = Autopilot.step(current, target, false, commit, raise, offer,
                                            limit, at, false);
                                    assertEquals(current + "→" + target + " floor " + at, old.next, low.next);
                                    assertSame(old.kind, low.kind);
                                }
                                // A floor below 50 is no floor.
                                Autopilot.Step below = Autopilot.step(current, target, false, commit, raise, offer,
                                        limit, 49, false);
                                assertEquals(old.next, below.next);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void aJumpsTargetAlreadyCarriesTheFloor() {
        // The plan's target is max(own, floor): a user's change jumps straight there, as any jump does.
        assertStep(63, Autopilot.StepKind.JUMP, Autopilot.step(100, 63, true, 0, 0, 0, 50, 63, false));
        assertStep(63, Autopilot.StepKind.JUMP, Autopilot.step(50, 63, true, 0, 0, 0, 50, 63, true));
    }

    @Test
    public void releaseOnlyWhenTheFloorHeldTheBarAndTheTargetIsBelowIt() {
        // The bar the floor put (63), no floor any more (a new dash): straight back, even 0 s after the last commit.
        assertStep(50, Autopilot.StepKind.RELEASE, Autopilot.step(63, 50, false, 0, 0, 0, 50, -1, true));
        // 150 comes down in one commit, not five 20-point ones.
        assertStep(50, Autopilot.StepKind.RELEASE, Autopilot.step(150, 50, false, 10 * MIN, 0, 0, 50, -1, true));
        assertEquals("the old step comes down 20 at a time", 130,
                Autopilot.step(150, 50, false, 10 * MIN, 0, 0, 50).next);
        // A lower floor still stands: straight down to it, the plan's target.
        assertStep(60, Autopilot.StepKind.RELEASE, Autopilot.step(63, 60, false, 0, 0, 0, 50, 60, true));
        // Not held by the floor: the ordinary step.
        assertStep(63, Autopilot.StepKind.SPACING, Autopilot.step(63, 50, false, 0, 0, 0, 50, -1, false));
        // Held, but the floor still binds there: hold.
        assertStep(63, Autopilot.StepKind.HOLD, Autopilot.step(63, 63, false, 0, 0, 0, 50, 63, true));
        // Held, the target above the bar: the ordinary raise rules.
        assertStep(63, Autopilot.StepKind.RAISE_WAIT, Autopilot.step(63, 70, false, 10 * MIN, 0, 0, 80, -1, true));
        // A jump is a jump.
        assertStep(50, Autopilot.StepKind.JUMP, Autopilot.step(63, 50, true, 0, 0, 0, 50, -1, true));
    }
}

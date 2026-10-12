package com.local.dasherfilter;

import com.local.dasherfilter.AutopilotClosedLoopTest.Dash;
import org.junit.Test;

import static com.local.dasherfilter.AutopilotClosedLoopTest.BUSY;
import static com.local.dasherfilter.AutopilotClosedLoopTest.MODERATE;
import static com.local.dasherfilter.AutopilotClosedLoopTest.OFFERS;
import static com.local.dasherfilter.AutopilotClosedLoopTest.SLOW;
import static com.local.dasherfilter.AutopilotClosedLoopTest.WARMUP;
import static com.local.dasherfilter.AutopilotClosedLoopTest.assertCalm;
import static com.local.dasherfilter.AutopilotClosedLoopTest.dash;
import static com.local.dasherfilter.AutopilotClosedLoopTest.market;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The two longest closed-loop runs of {@link AutopilotClosedLoopTest}, on its market and dash simulation: kept in a
 * class of their own so the parallel test JVMs, which never split a class, finish together.
 */
public final class AutopilotGoalHoldsTest {
    @Test
    public void goalSeventyWithEveryPassingOfferTakenHoldsSeventyPercentInEveryMarket() {
        for (double offersPerHour : new double[] {BUSY, MODERATE, SLOW}) {
            for (long seed = 1; seed <= 2; seed++) {
                Dash dash = dash(FilterSettings.GOAL_TOP_TIER, offersPerHour, 1.0, 70, true, seed, OFFERS, WARMUP);
                String what = market(offersPerHour) + ", seed " + seed + ": " + dash;
                assertTrue(what + ": AR over the last 100 offers at least 70%", dash.finalAr >= 70);
                assertTrue(what + ": mean AR at least the goal + 5", dash.meanAr >= 75);
                assertTrue(what + ": dips stay small", dash.minAr >= 65);
                assertCalm(what, dash);
            }
        }
    }

    @Test
    public void readingOnlySomeQuestionsNeverWindsTheCorrectionUp() {
        // Recovering from the owner's 9% while Dasher's question is read for all, a fifth, a tenth or none of the
        // declines (the rest through Dasher's notification): the correction stays off on the way, and is off once the
        // rate is well above the goal, whatever the app's own count or Dasher's last reading says.
        for (double share : new double[] {1.0, 0.2, 0.1, 0.0}) {
            for (long seed = 1; seed <= 2; seed++) {
                Dash dash = dash(FilterSettings.GOAL_TOP_TIER, MODERATE, 1.0, 9, true, share, true, seed, 1_200, 200);
                String what = "questions read " + share + ", seed " + seed + ": " + dash;
                assertTrue(what + ": the goal is reached", dash.offersToGoal <= 150);
                assertEquals(what + ": nothing added on the way", 0, dash.maxExtraBeforeGoal);
                assertEquals(what + ": nothing left at the end", 0, dash.finalExtra);
                assertTrue(what + ": the goal holds", dash.meanAr >= 70);
                assertCalm(what, dash);
            }
        }
    }
}

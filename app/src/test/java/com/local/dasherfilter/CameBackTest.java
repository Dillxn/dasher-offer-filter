package com.local.dasherfilter;

import android.content.Context;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * "Came back?" (0.6.9): a declined offer's store, kept in memory for 30 minutes, notes the same store's next standalone
 * offer on both tickets, in minutes and pay only, and decides nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CameBackTest {
    private static final long MIN = 60_000L;
    private Context context;

    @Before public void fresh() {
        context = RuntimeEnvironment.getApplication();
        DecisionLog.clear(context);
        CameBack.clear();
    }

    @After public void forget() {
        CameBack.clear();
    }

    private static OfferSnapshot offer(int pay, double miles) {
        return new OfferSnapshot(pay, miles, 14, 2);
    }

    @Test public void theSameStoresNextOfferSaysHowMuchLaterAndForWhat() {
        assertNull(CameBack.note("DiBella's Subs", true, false, true, offer(870, 4.2), 0));
        CameBack.Match match = CameBack.note("dibella's  subs", true, false, false, offer(1080, 4.2), 9 * MIN);
        assertNotNull(match);
        assertEquals(9, match.minutes);
        assertEquals("9 min later, $10.80 (+$2.10)", match.laterDetail());
        assertEquals("declined 9 min earlier at $8.70", match.earlierDetail());
        assertEquals("3 min later, $7.50 (-$1.20)", new CameBack.Match(offer(870, 4.2), offer(750, 3.0), 3)
                .laterDetail());
        assertEquals("1 min later, $8.70 (same pay)", new CameBack.Match(offer(870, 4.2), offer(870, 3.0), 1)
                .laterDetail());
    }

    @Test public void onlyWithinThirtyMinutesOfTheSameStoresDecline() {
        CameBack.note("Taco Bell", true, false, true, offer(500, 3.0), 0);
        assertNull(CameBack.note("Chipotle", true, false, false, offer(900, 3.0), 5 * MIN));
        assertNull(CameBack.note("Taco Bell", true, false, false, offer(900, 3.1), 31 * MIN));
        // An offer the rules kept is not a store's decline.
        CameBack.note("Wendy's", true, false, false, offer(500, 3.0), 40 * MIN);
        assertNull(CameBack.note("Wendy's", true, false, false, offer(600, 3.0), 45 * MIN));
    }

    @Test public void stackedAddOnsUnreadStoresAndUnreadPayAreLeftOut() {
        CameBack.note("Taco Bell", true, false, true, offer(500, 3.0), 0);
        assertNull(CameBack.note("Taco Bell", false, false, false, offer(900, 3.0), MIN));
        assertNull(CameBack.note("Taco Bell", true, true, false, offer(901, 3.0), MIN));
        assertNull(CameBack.note("", true, false, false, offer(902, 3.0), MIN));
        assertNull(CameBack.note("Taco Bell", true, false, false, new OfferSnapshot(null, 3.0, 14, 2), MIN));
    }

    @Test public void anotherReadingOfTheSameOfferIsNotItComingBack() {
        CameBack.note("Taco Bell", true, false, true, offer(500, 3.0), 0);
        assertNull(CameBack.note("Taco Bell", true, false, true, offer(500, 3.0), MIN));
        assertNotNull(CameBack.note("Taco Bell", true, false, false, offer(700, 3.0), 2 * MIN));
        assertNull(CameBack.note("Taco Bell", true, false, false, offer(700, 3.0), 3 * MIN));
    }

    @Test public void bothLinesTakeTheNoteInNumbersOnlyAndKeepTheirOutcome() {
        List<String> first = Arrays.asList("$8.70", "2 stops (4.2 mi) • 14 min", "DiBella's Subs", "12 Oak Ave");
        List<String> second = Arrays.asList("$10.80", "2 stops (4.6 mi) • 16 min", "DiBella's Subs", "12 Oak Ave");
        DecisionLog.Entry declined = new DecisionLog.Entry(System.currentTimeMillis(), DecisionLog.Source.SCREEN,
                false, offer(870, 4.2), 1000, OfferRule.Result.DECLINE, "pay below minimum",
                DecisionLog.Action.CONFIRMATION_TAPPED, true, Collections.<String>emptyList());
        DecisionLog.record(context, declined);
        CameBack.note(context, declined, first);
        DecisionLog.Entry later = new DecisionLog.Entry(System.currentTimeMillis() + 1, DecisionLog.Source.SCREEN,
                false, new OfferSnapshot(1080, 4.6, 16, 2), 1000, OfferRule.Result.KEEP, "passes",
                DecisionLog.Action.BELL, true, Collections.<String>emptyList());
        DecisionLog.Outcome before = DecisionLog.outcome(declined);
        DecisionLog.record(context, later);
        CameBack.note(context, later, second);

        List<DecisionLog.Entry> recent = DecisionLog.recent(context, 2);
        DecisionLog.Entry newer = recent.get(0), older = recent.get(1);
        assertTrue(DecisionLog.hasStep(older, DecisionLog.StepKind.SAME_STORE_LATER));
        assertTrue(DecisionLog.hasStep(newer, DecisionLog.StepKind.SAME_STORE_EARLIER));
        assertEquals("Same store: 1 min later, $10.80 (+$2.10)", older.steps.get(0).text());
        assertEquals("Same store: declined 1 min earlier at $8.70", newer.steps.get(0).text());
        assertEquals(before, DecisionLog.outcome(older));
        for (DecisionLog.Entry entry : recent) {
            for (DecisionLog.Step step : entry.steps) assertFalse(step.text(), step.text().contains("DiBella"));
        }
    }
}

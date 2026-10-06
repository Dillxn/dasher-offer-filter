package com.local.dasherfilter;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
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
 * Autopilot's own state in prefs "autopilot": the acceptance-rate reading (one number, at most 7 days, one reading per
 * offer within 2 minutes), the planner's goal-relative state, the next commit's cause and the last change, all cleared
 * at once by Clear history. Numbers and fixed names only.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AutopilotStoreTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = 24 * 60 * 60_000L;
    private static final String OFFER = new OfferSnapshot(575, 6.6, 27, 2).fingerprint();
    private static final String OTHER = new OfferSnapshot(925, 6.3, 25, 2).fingerprint();
    private Application app;

    @Before public void setUp() {
        app = RuntimeEnvironment.getApplication();
        prefs().edit().clear().commit();
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences(AutopilotStore.PREFS, Context.MODE_PRIVATE);
    }

    @Test public void aReadingLastsSevenDaysAndIsThenDiscarded() {
        assertNull(AutopilotStore.reading(app, NOW));
        assertTrue(AutopilotStore.recordReading(app, 55, OFFER, NOW));
        AutopilotStore.Reading reading = AutopilotStore.reading(app, NOW + 12 * 60_000L);
        assertNotNull(reading);
        assertEquals(55, reading.percent);
        assertEquals(NOW, reading.at);
        assertEquals(OFFER, reading.fingerprint);
        assertNotNull("seven days to the millisecond", AutopilotStore.reading(app, NOW + 7 * DAY));
        assertNull(AutopilotStore.reading(app, NOW + 7 * DAY + 1));
        assertNull("discarded, not just hidden", AutopilotStore.reading(app, NOW));
        assertFalse(prefs().contains("ar_percent"));
        assertFalse(prefs().contains("ar_at"));
        assertFalse(prefs().contains("ar_fp"));
    }

    @Test public void theSameOffersReadingWithinTwoMinutesOnlyUpdatesWhenItWasSeen() {
        assertTrue(AutopilotStore.recordReading(app, 9, OFFER, NOW));
        assertFalse("the question polled again", AutopilotStore.recordReading(app, 9, OFFER, NOW + 60_000));
        assertEquals(NOW + 60_000, AutopilotStore.reading(app, NOW + 60_000).at);
        assertFalse(AutopilotStore.recordReading(app, 9, OFFER, NOW + 180_000));
        assertEquals("measured from when it was last seen", NOW + 180_000,
                AutopilotStore.reading(app, NOW + 180_000).at);
        assertTrue("the same offer again later is a new reading",
                AutopilotStore.recordReading(app, 9, OFFER, NOW + 300_001));
        assertTrue("another percent is a new reading", AutopilotStore.recordReading(app, 10, OFFER, NOW + 300_002));
        assertTrue("another offer is a new reading", AutopilotStore.recordReading(app, 10, OTHER, NOW + 300_003));
        AutopilotStore.Reading reading = AutopilotStore.reading(app, NOW + 300_003);
        assertEquals(10, reading.percent);
        assertEquals(OTHER, reading.fingerprint);
        // An offer whose facts were not known still keeps a reading, without an identity.
        assertTrue(AutopilotStore.recordReading(app, 74, null, NOW + 400_000));
        assertFalse(AutopilotStore.recordReading(app, 74, "", NOW + 401_000));
        assertEquals("", AutopilotStore.reading(app, NOW + 401_000).fingerprint);
        // A late thread's slightly older clock never moves the time back.
        assertFalse(AutopilotStore.recordReading(app, 74, "", NOW + 400_500));
        assertEquals(NOW + 401_000, AutopilotStore.reading(app, NOW + 401_000).at);
    }

    @Test public void onlyAWholePercentIsKept() {
        assertFalse(AutopilotStore.recordReading(app, -1, OFFER, NOW));
        assertFalse(AutopilotStore.recordReading(app, 101, OFFER, NOW));
        assertNull(AutopilotStore.reading(app, NOW));
        assertTrue(AutopilotStore.recordReading(app, 0, OFFER, NOW));
        assertEquals(0, AutopilotStore.reading(app, NOW).percent);
        assertTrue(AutopilotStore.recordReading(app, 100, OFFER, NOW));
        assertEquals(100, AutopilotStore.reading(app, NOW).percent);
        // Whatever else is stored there is not a reading.
        prefs().edit().putInt("ar_percent", 250).commit();
        assertNull(AutopilotStore.reading(app, NOW));
    }

    @Test public void aReadingStampedAheadOfTheClockAskedAboutIsNotUsedYetButKept() {
        assertTrue(AutopilotStore.recordReading(app, 55, OFFER, NOW));
        assertNotNull("a moment ahead is another thread's clock", AutopilotStore.reading(app, NOW - 30_000));
        assertNull(AutopilotStore.reading(app, NOW - AutopilotStore.READING_CLOCK_SLACK_MS - 1));
        assertNotNull(AutopilotStore.reading(app, NOW));
    }

    @Test public void thePlannersStateIsBoundedAndTheGoalStateResetsTogether() {
        assertFalse(AutopilotStore.recovering(app));
        assertEquals(0, AutopilotStore.extra(app));
        assertEquals(0, AutopilotStore.checkpointAt(app));
        assertEquals(-1, AutopilotStore.checkpointReading(app));

        AutopilotStore.savePlanState(app, true, 14, NOW, 68);
        assertTrue(AutopilotStore.recovering(app));
        assertEquals("held to 10", AutopilotStore.EXTRA_MAX, AutopilotStore.extra(app));
        assertEquals(NOW, AutopilotStore.checkpointAt(app));
        assertEquals(68, AutopilotStore.checkpointReading(app));
        AutopilotStore.setCorrection(app, -3, 0, 68);
        assertEquals(0, AutopilotStore.extra(app));
        assertEquals("no time is no checkpoint", 0, AutopilotStore.checkpointAt(app));
        assertEquals(-1, AutopilotStore.checkpointReading(app));
        AutopilotStore.setCorrection(app, 2, NOW + 1, 70);
        AutopilotStore.setRecovering(app, false);
        assertFalse(AutopilotStore.recovering(app));
        assertEquals(2, AutopilotStore.extra(app));

        AutopilotStore.recordReading(app, 70, OFFER, NOW);
        AutopilotStore.setJump(app, "GOAL_CHANGED");
        AutopilotStore.recordChange(app, 100, 82, "RECOVERY", NOW, false);
        AutopilotStore.setRecovering(app, true);
        AutopilotStore.resetGoalState(app);
        assertFalse(AutopilotStore.recovering(app));
        assertEquals(0, AutopilotStore.extra(app));
        assertEquals(0, AutopilotStore.checkpointAt(app));
        assertEquals(-1, AutopilotStore.checkpointReading(app));
        assertNotNull("the reading is not goal state", AutopilotStore.reading(app, NOW));
        assertEquals("GOAL_CHANGED", AutopilotStore.jump(app));
        assertNotNull(AutopilotStore.lastChange(app));
    }

    @Test public void theNextCommitsCauseAndTheLastChangeAreKept() {
        assertNull(AutopilotStore.jump(app));
        AutopilotStore.setJump(app, "TURNED_ON");
        assertEquals("TURNED_ON", AutopilotStore.jump(app));
        AutopilotStore.clearJump(app);
        assertNull(AutopilotStore.jump(app));
        AutopilotStore.setJump(app, "CLEARED");
        AutopilotStore.setJump(app, null);
        assertNull(AutopilotStore.jump(app));

        assertNull(AutopilotStore.lastChange(app));
        assertEquals(0, AutopilotStore.raisedAt(app));
        AutopilotStore.recordChange(app, 100, 82, "RECOVERY", NOW, false);
        AutopilotStore.Change change = AutopilotStore.lastChange(app);
        assertEquals(NOW, change.at);
        assertEquals(100, change.from);
        assertEquals(82, change.to);
        assertEquals("RECOVERY", change.why);
        assertEquals("a lowered bar is not a raise", 0, AutopilotStore.raisedAt(app));
        AutopilotStore.recordChange(app, 82, 87, "VALUE_UP", NOW + 400_000, true);
        assertEquals(NOW + 400_000, AutopilotStore.raisedAt(app));
        assertEquals("VALUE_UP", AutopilotStore.lastChange(app).why);
        assertEquals("it replaces the note before it", 82, AutopilotStore.lastChange(app).from);

        assertEquals(0, AutopilotStore.exemptLoggedAt(app));
        AutopilotStore.setExemptLoggedAt(app, NOW);
        assertEquals(NOW, AutopilotStore.exemptLoggedAt(app));
    }

    @Test public void clearEmptiesTheWholeStoreAndEveryWriteMovesTheVersion() {
        long version = AutopilotStore.version();
        AutopilotStore.recordReading(app, 55, OFFER, NOW);
        assertTrue(AutopilotStore.version() > version);
        version = AutopilotStore.version;
        AutopilotStore.savePlanState(app, true, 4, NOW, 50);
        AutopilotStore.setJump(app, "RULES_CHANGED");
        AutopilotStore.recordChange(app, 100, 95, "GOAL", NOW, false);
        AutopilotStore.recordChange(app, 95, 100, "VALUE_UP", NOW, true);
        AutopilotStore.setExemptLoggedAt(app, NOW);
        assertTrue("five writes, five new versions", AutopilotStore.version() >= version + 5);
        assertFalse(prefs().getAll().isEmpty());

        version = AutopilotStore.version();
        AutopilotStore.clear(app);
        assertTrue(AutopilotStore.version() > version);
        assertTrue(prefs().getAll().isEmpty());
        assertNull(AutopilotStore.reading(app, NOW));
        assertNull(AutopilotStore.jump(app));
        assertNull(AutopilotStore.lastChange(app));
        assertEquals(0, AutopilotStore.raisedAt(app));
        assertFalse(AutopilotStore.recovering(app));
    }

    @Test public void nothingButNumbersAndFixedNamesIsStored() {
        AutopilotStore.recordReading(app, 9, OFFER, NOW);
        AutopilotStore.savePlanState(app, true, 2, NOW, 9);
        AutopilotStore.setJump(app, "TURNED_ON");
        AutopilotStore.recordChange(app, 100, 80, "RECOVERY", NOW, false);
        for (Object value : prefs().getAll().values()) {
            if (!(value instanceof String)) continue;
            String text = (String) value;
            assertTrue(text, text.matches("[A-Z_]+|[0-9.:=a-z]*"));
        }
        assertEquals(OFFER, prefs().getString("ar_fp", null));
    }
}

package com.local.dasherfilter;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
 * offer within 2 minutes), the planner's goal-relative state, the next commit's cause and the last change, and the
 * hand accepts kept for the accepted floor (0.6.19), all cleared at once by Clear history. Numbers and fixed names
 * only.
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
        assertEquals(-1, AutopilotStore.checkpointAr(app));

        AutopilotStore.savePlanState(app, true, 14, NOW, 6_850);
        assertTrue(AutopilotStore.recovering(app));
        assertEquals("held to 10", AutopilotStore.EXTRA_MAX, AutopilotStore.extra(app));
        assertEquals(NOW, AutopilotStore.checkpointAt(app));
        assertEquals("the carried-forward rate, in hundredths", 6_850, AutopilotStore.checkpointAr(app));
        AutopilotStore.setCorrection(app, -3, 0, 6_850);
        assertEquals(0, AutopilotStore.extra(app));
        assertEquals("no time is no checkpoint", 0, AutopilotStore.checkpointAt(app));
        assertEquals(-1, AutopilotStore.checkpointAr(app));
        AutopilotStore.setCorrection(app, 2, NOW, -1);
        assertEquals("a checkpoint without a Dasher rate is still a checkpoint", NOW, AutopilotStore.checkpointAt(app));
        assertEquals(-1, AutopilotStore.checkpointAr(app));
        AutopilotStore.setCorrection(app, 2, NOW, 12_000);
        assertEquals("held to 100%", 10_000, AutopilotStore.checkpointAr(app));
        prefs().edit().putInt("cp_reading", 68).commit();
        AutopilotStore.setCorrection(app, 2, NOW + 1, 7_000);
        assertFalse("an earlier build's raw-percent checkpoint goes", prefs().contains("cp_reading"));
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
        assertEquals(-1, AutopilotStore.checkpointAr(app));
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

        // Compare-and-clear: a commit clears only the cause it used, never one set meanwhile.
        assertTrue("nothing pending: nothing to clear", AutopilotStore.clearJumpIf(app, "TURNED_ON"));
        AutopilotStore.setJump(app, "RULES_CHANGED");
        assertFalse(AutopilotStore.clearJumpIf(app, "TURNED_ON"));
        assertFalse(AutopilotStore.clearJumpIf(app, null));
        assertEquals("another cause stays", "RULES_CHANGED", AutopilotStore.jump(app));
        assertTrue(AutopilotStore.clearJumpIf(app, "RULES_CHANGED"));
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
        AutopilotStore.savePlanState(app, true, 2, NOW, 891);
        AutopilotStore.setJump(app, "TURNED_ON");
        AutopilotStore.recordChange(app, 100, 80, "RECOVERY", NOW, false);
        AutopilotStore.keepAccepts(app, Collections.singletonList(
                hand(new OfferSnapshot(1445, 7.2, 27, 2, null, null, 3, true), NOW - MIN)), NOW - DAY);
        for (java.util.Map.Entry<String, ?> entry : prefs().getAll().entrySet()) {
            if (!(entry.getValue() instanceof String)) continue;
            String text = (String) entry.getValue();
            if (entry.getKey().equals("kept_accepts")) {
                // Numbers under fixed keys, as JSON.
                String field = "\"(at|pay|mi|min|stops|items|itemsApply)\":(-?[0-9.E]+|true)";
                assertTrue(text, text.matches("\\[(\\{(" + field + ",?)+},?)*]"));
            } else {
                assertTrue(text, text.matches("[A-Z_]+|[0-9.:=a-z]*"));
            }
        }
        assertEquals(OFFER, prefs().getString("ar_fp", null));
        assertNotNull(prefs().getString("kept_accepts", null));
    }

    // ---- The hand accepts kept for the accepted floor (0.6.19) ----

    private static final long MIN = 60_000L;

    /** An offer the user accepted by hand at {@code at}. */
    private static Autopilot.OfferRecord hand(OfferSnapshot facts, long at) {
        return line(facts, at, false, false, false, at);
    }

    private static Autopilot.OfferRecord line(OfferSnapshot facts, long at, boolean addOn, boolean replay,
                                              boolean skipped, long handAt) {
        return new Autopilot.OfferRecord(facts, at - 30_000L, addOn, replay, true, false, false,
                FilterSettings.BAR_AT_MINIMUMS, true, DecisionLog.MODEL, null, skipped, handAt);
    }

    @Test public void handAcceptsAreKeptPastTheLinesWithTheirFigures() {
        OfferSnapshot his = new OfferSnapshot(1445, 7.2, 27, 2, null, null, 3, true);
        List<Autopilot.OfferRecord> lines = Arrays.asList(
                hand(his, NOW - MIN),
                line(new OfferSnapshot(700, 3.0, 15, 2), NOW - 2 * MIN, true, false, false, NOW - 2 * MIN),
                line(new OfferSnapshot(710, 3.0, 15, 2), NOW - 3 * MIN, false, true, false, NOW - 3 * MIN),
                line(new OfferSnapshot(720, 3.0, 15, 2), NOW - 4 * MIN, false, false, true, NOW - 4 * MIN),
                hand(new OfferSnapshot(null, 3.0, 15, 2), NOW - 5 * MIN),
                line(new OfferSnapshot(730, 3.0, 15, 2), NOW - 6 * MIN, false, false, false, Autopilot.NEVER),
                hand(new OfferSnapshot(740, 3.0, 15, 2), NOW - 8 * DAY));
        List<Autopilot.OfferRecord> kept = AutopilotStore.keepAccepts(app, lines, NOW - 7 * DAY);
        assertEquals("not an add-on, a replay, a skipped store's, unread pay, no hand accept or too old", 1,
                kept.size());
        Autopilot.OfferRecord one = kept.get(0);
        assertEquals(NOW - MIN, one.acceptedByHandAt);
        assertEquals(NOW - MIN, one.at);
        assertEquals(Integer.valueOf(1445), one.facts.payCents);
        assertEquals(7.2, one.facts.miles, 0);
        assertEquals(Integer.valueOf(27), one.facts.minutes);
        assertEquals(Integer.valueOf(2), one.facts.stops);
        assertEquals(Integer.valueOf(3), one.facts.items);
        assertTrue(one.facts.itemCountApplicable);
        assertTrue(one.accepted);
        assertFalse(one.addOn || one.replay || one.skipped || one.declined);
        // Its line gone from the history, it is still kept; the same accept again is kept once.
        assertEquals(1, AutopilotStore.keepAccepts(app, null, NOW - 7 * DAY).size());
        assertEquals(1, AutopilotStore.keepAccepts(app, Collections.singletonList(hand(his, NOW - MIN)),
                NOW - 7 * DAY).size());
        // Once the floor's start passes it (its 7 days over, or a restart): forgotten.
        long version = AutopilotStore.version();
        assertTrue(AutopilotStore.keepAccepts(app, null, NOW - MIN + 1).isEmpty());
        assertFalse(prefs().contains("kept_accepts"));
        assertTrue(AutopilotStore.version() > version);
        version = AutopilotStore.version();
        assertTrue(AutopilotStore.keepAccepts(app, null, NOW).isEmpty());
        assertEquals("nothing changed, nothing written", version, AutopilotStore.version());
    }

    @Test public void theNewest100AreKeptNewestFirst() {
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        for (int i = 0; i < 150; i++) lines.add(hand(new OfferSnapshot(1000 + i, 5.0, 20, 2), NOW - i * MIN));
        Collections.reverse(lines);
        List<Autopilot.OfferRecord> kept = AutopilotStore.keepAccepts(app, lines, NOW - DAY);
        assertEquals(AutopilotStore.KEPT_ACCEPTS_MAX, kept.size());
        assertEquals(NOW, kept.get(0).acceptedByHandAt);
        assertEquals(NOW - 99 * MIN, kept.get(99).acceptedByHandAt);
        assertEquals(Integer.valueOf(1099), kept.get(99).facts.payCents);
    }

    @Test public void aRestartOfTheFloorForgetsThemAndKeepsNoneBeforeIt() {
        AutopilotStore.keepAccepts(app, Collections.singletonList(hand(new OfferSnapshot(1200, 5.0, 20, 2),
                NOW - 2 * MIN)), NOW - DAY);
        assertTrue(prefs().contains("kept_accepts"));
        AutopilotStore.restartFloor(app, NOW - MIN);
        assertFalse(prefs().contains("kept_accepts"));
        assertTrue(AutopilotStore.keepAccepts(app, Collections.singletonList(hand(new OfferSnapshot(1200, 5.0, 20, 2),
                NOW - 2 * MIN)), NOW - DAY).isEmpty());
        assertEquals("from the restart on", 1, AutopilotStore.keepAccepts(app, Collections.singletonList(
                hand(new OfferSnapshot(1300, 5.0, 20, 2), NOW - MIN)), NOW - DAY).size());
        AutopilotStore.clear(app);
        assertFalse(prefs().contains("kept_accepts"));
    }

    @Test public void unreadableKeptAcceptsReadAsNone() {
        prefs().edit().putString("kept_accepts", "not json").commit();
        assertTrue(AutopilotStore.keepAccepts(app, null, NOW - DAY).isEmpty());
        assertFalse(prefs().contains("kept_accepts"));
        prefs().edit().putString("kept_accepts", "[{\"at\":" + (NOW - MIN) + "},{\"at\":" + (NOW - 2 * MIN)
                + ",\"pay\":900,\"mi\":5.0}]").commit();
        List<Autopilot.OfferRecord> kept = AutopilotStore.keepAccepts(app, null, NOW - DAY);
        assertEquals("one with no pay is left out", 1, kept.size());
        assertEquals(Integer.valueOf(900), kept.get(0).facts.payCents);
        assertNull(kept.get(0).facts.minutes);
    }

    /**
     * The update to 0.6.19 (once): with pay first, a floor that restarted (turning Autopilot on or off may have done
     * it) starts over from nothing; a goal that protects the rate keeps the restart; a restart under 0.6.19 is never
     * undone.
     */
    @Test public void theUpdateRepairsARestartedFloorOnceWithPayFirst() {
        prefs().edit().putLong("floor_since", NOW).commit();
        assertFalse(AutopilotStore.undoToggleRestartOnce(app, false));
        assertEquals(NOW, AutopilotStore.floorSince(app));
        assertFalse("once only", AutopilotStore.undoToggleRestartOnce(app, true));
        assertEquals(NOW, AutopilotStore.floorSince(app));

        prefs().edit().clear().putLong("floor_since", NOW).commit();
        assertTrue(AutopilotStore.undoToggleRestartOnce(app, true));
        assertEquals(0, AutopilotStore.floorSince(app));
        assertFalse(AutopilotStore.undoToggleRestartOnce(app, true));

        prefs().edit().clear().commit();
        AutopilotStore.restartFloor(app, NOW);
        assertFalse(AutopilotStore.undoToggleRestartOnce(app, true));
        assertEquals(NOW, AutopilotStore.floorSince(app));

        prefs().edit().clear().commit();
        assertFalse("nothing restarted, nothing to do", AutopilotStore.undoToggleRestartOnce(app, true));
    }
}

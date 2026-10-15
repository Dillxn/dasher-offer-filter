package com.local.dasherfilter;

import android.app.Application;
import android.os.SystemClock;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The minimums never grow through Autopilot's runtime (0.6.21, the owner: "mins should grow as we accept offers with
 * higher mins when adaptive / learned mins is on. autopilot is the buffer"; from 0.5.1 to 0.6.20 a commit could grow
 * them): offers paying well above them, Autopilot on or off, move only Autopilot's bar. A growth an earlier version made
 * keeps its note and Undo while the minimums are still the grown ones: Undo is the user's change; Clear history removes
 * the note and its Undo. Plans run on the calling thread; the clocks are the test's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public class MinimumsGrowthRuntimeTest {
    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;
    private static final int[] TYPICAL = {400, 100, 25, 0, 0, 0};
    private static final int[] GROWN = {440, 110, 28, 0, 0, 0};

    private Application app;
    private long wall;
    private long elapsed;
    private long since;
    private int requests;

    @Before public void setUp() {
        app = RuntimeEnvironment.getApplication();
        wall = System.currentTimeMillis();
        elapsed = 90_000_000L;
        since = wall - 3 * 24 * HOUR;
        AutopilotRuntime.wallClock = () -> wall;
        AutopilotRuntime.elapsedClock = () -> elapsed;
        AutopilotRuntime.executorForTests = Runnable::run;
        AutopilotRuntime.beforeWriteForTests = null;
        AutopilotRuntime.forgetCache();
        FilterStore.wallClock = () -> since;
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        DiagnosticLog.clear(app);
        AutopilotStore.clear(app);
        Dashing.forgetCache();
        requests = 0;
        AutopilotRuntime.setCommitRequester(() -> requests++);
        // Typical minimums, set three days ago; Autopilot on, its bar at 112% (offers have paid above them).
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 3));
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertTrue(FilterStore.commitAutopilotBar(app, 100, 112));
        assertEquals(since, FilterStore.minimumsSince(app));
        FilterStore.wallClock = () -> wall;
    }

    @After public void tearDown() {
        AutopilotRuntime.forgetCache();
        AutopilotRuntime.beforeWriteForTests = null;
        AutopilotRuntime.wallClock = System::currentTimeMillis;
        AutopilotRuntime.elapsedClock = SystemClock::elapsedRealtime;
        FilterStore.wallClock = System::currentTimeMillis;
        DecisionLog.forgetCache();
    }

    // ---- Fixtures ----

    /**
     * 30 offers Autopilot judged at {@code bar} since the minimums took effect: 15 yesterday and 15 in the last two
     * hours, each passed and left to the user. Each paid 187% of the typical minimums or more ($15.00 or more for 2
     * stops, 6.6 mi and 27 min): until 0.6.20, enough to grow the minimums the most, 10 points.
     */
    private void history(int bar) {
        FilterSettings scoringRules = rules().withMinimumScalePercent(bar);
        for (int i = 29; i >= 0; i--) {
            long at = wall - 5 * MIN - 7 * MIN * (i % 15) - (i >= 15 ? 24 * HOUR : 0);
            OfferSnapshot facts = new OfferSnapshot(1500 + i, 6.6, 27, 2);
            DecisionLog.record(app, new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false, facts, 756,
                    OfferRule.Result.KEEP, "passes your rules", DecisionLog.Action.PASSES, true,
                    Collections.<String>emptyList()).withBar(bar, true).withScoringRules(scoringRules));
        }
    }

    /**
     * A growth as 0.6.20 made it at {@code at}: the typical minimums grown 10%, in effect since then, Autopilot's bar
     * 112% → 102%, and its record kept with its note.
     */
    private void grewBefore(long at) {
        FilterStore.wallClock = () -> at;
        FilterStore.save(app, rules().withMinimums(440, 110, 28));
        FilterStore.wallClock = () -> wall;
        assertTrue(FilterStore.commitAutopilotBar(app, 112, 102));
        AutopilotStore.recordGrowth(app, new Growth.Grown(10, 400, 100, 25, 112, 440, 110, 28, 102), 30, 2, at);
        assertEquals(at, FilterStore.minimumsSince(app));
    }

    private FilterSettings rules() {
        return FilterStore.load(app);
    }

    private List<String> logged() {
        List<String> lines = new java.util.ArrayList<>();
        for (String line : DiagnosticLog.read(app).split("\n")) {
            int at = line.indexOf("[autopilot] ");
            if (at >= 0) lines.add(line.substring(at + "[autopilot] ".length()));
        }
        return lines;
    }

    private int count(String line) {
        int count = 0;
        for (String logged : logged()) if (logged.equals(line)) count++;
        return count;
    }

    private void assertNotGrown() {
        assertArrayEquals(TYPICAL, rules().minimums());
        assertEquals("they took effect three days ago, and nothing since", since, FilterStore.minimumsSince(app));
        assertNull(AutopilotStore.lastGrowth(app));
        assertNull(AutopilotRuntime.growthNote(app));
        for (String line : logged()) assertFalse(line, line.startsWith("minimums grew"));
    }

    // ---- Nothing grows ----

    @Test public void theMinimumsNeverGrowWithAutopilotOnOrOff() {
        history(108);
        // On: the plan and its commits move only the bar, however long offers keep paying well above the minimums.
        for (int i = 0; i < 5; i++) {
            AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.USER);
            assertNotNull(AutopilotRuntime.latest());
            AutopilotRuntime.Commit commit = AutopilotRuntime.commitIfDue(app);
            assertTrue(commit.toString(), commit == AutopilotRuntime.Commit.HELD
                    || commit == AutopilotRuntime.Commit.COMMITTED);
            assertNotGrown();
            elapsed += 2 * MIN;
            wall += 2 * MIN;
        }
        assertTrue("Autopilot stays on", rules().autopilot);
        assertEquals("max stops never changes", 3, rules().maxStops);

        // Off: nothing is planned at all, the bar is exactly 100, and the minimums stay.
        AutopilotRuntime.setAutopilot(app, false, FilterSettings.GOAL_TOP_TIER);
        AutopilotRuntime.forgetCache();
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.USER);
        assertNull(AutopilotRuntime.latest());
        assertEquals(AutopilotRuntime.Commit.NO_PLAN, AutopilotRuntime.commitIfDue(app));
        assertFalse(AutopilotRuntime.commitWanted(app));
        assertEquals(100, rules().minimumScalePercent);
        assertNotGrown();
    }

    // ---- An earlier version's growth: Undo, the note and Clear history ----

    @Test public void undoPutsTheMinimumsBackAsTheUsersChangeAndTheBarIsAutopilotsToMove() {
        long grewAt = wall - HOUR;
        grewBefore(grewAt);
        assertNotNull(AutopilotRuntime.growthNote(app));
        // Autopilot's details still say when it was.
        List<String> details = AutopilotText.detailsLines(AutopilotRuntime.status(app, wall, true));
        assertTrue(details.toString(), details.contains("Your minimums last grew 10%, 1 h ago: "
                + "$4.00/stop → $4.40/stop · $1.00/mi → $1.10/mi · $15/hr → $16.80/hr."));
        assertFalse("only the growth shown", AutopilotRuntime.undoGrowth(app, grewAt - 1));
        assertTrue(AutopilotRuntime.undoGrowth(app, grewAt));
        FilterSettings undone = rules();
        assertArrayEquals(TYPICAL, undone.minimums());
        assertEquals(wall, FilterStore.minimumsSince(app));
        assertEquals("the bar waits for Autopilot", 102, undone.minimumScalePercent);
        assertEquals("other minimums: a jump at the next safe point", "RULES_CHANGED", AutopilotStore.jump(app));
        assertNull("the note and the growth's record go", AutopilotStore.lastGrowth(app));
        assertNull(AutopilotRuntime.growthNote(app));
        assertEquals(1, count("minimums growth undone: $4.40/stop -> $4.00/stop, $1.10/mi -> $1.00/mi, $16.80/hr -> "
                + "$15.00/hr"));
        assertFalse("once", AutopilotRuntime.undoGrowth(app, grewAt));
        // The jump is the next commit's, as for any change of minimums; nothing grows them again.
        history(102);
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.USER);
        AutopilotRuntime.Commit next = AutopilotRuntime.commitIfDue(app);
        assertTrue(next.toString(), next == AutopilotRuntime.Commit.HELD || next == AutopilotRuntime.Commit.COMMITTED);
        assertNull(AutopilotStore.jump(app));
        assertArrayEquals(TYPICAL, rules().minimums());
        assertNull(AutopilotStore.lastGrowth(app));
    }

    @Test public void theNoteGoesWithItsUndoOnceTheMinimumsChangeAnyOtherWayAndOkKeepsTheGrowth() {
        long grewAt = wall - HOUR;
        grewBefore(grewAt);
        assertNotNull(AutopilotRuntime.growthNote(app));
        // OK: the note goes, the growth stays (the details still say it).
        AutopilotRuntime.growthNoted(app, grewAt);
        assertNull(AutopilotRuntime.growthNote(app));
        assertNotNull(AutopilotStore.lastGrowth(app));
        assertFalse("no Undo without its note", AutopilotRuntime.undoGrowth(app, grewAt));

        // The note again, then a knob set by hand: the note and its Undo go for good.
        AutopilotStore.recordGrowth(app, AutopilotStore.lastGrowth(app).grown, 30, 2, grewAt);
        assertNotNull(AutopilotRuntime.growthNote(app));
        wall += MIN;
        FilterStore.save(app, rules().withMinimums(440, 110, 30));
        assertNull(AutopilotRuntime.growthNote(app));
        FilterStore.save(app, rules().withMinimums(440, 110, 28));
        assertNull("changed back, the note stays gone", AutopilotRuntime.growthNote(app));
        assertFalse(AutopilotRuntime.undoGrowth(app, grewAt));
        assertArrayEquals(GROWN, rules().minimums());
    }

    @Test public void clearHistoryRemovesTheNoteAndItsUndoAndKeepsTheRulesAndSettings() {
        long grewAt = wall - HOUR;
        grewBefore(grewAt);
        DecisionLog.clear(app);
        AutopilotRuntime.cleared(app);
        assertNull(AutopilotStore.lastGrowth(app));
        assertNull(AutopilotRuntime.growthNote(app));
        assertFalse(AutopilotRuntime.undoGrowth(app, grewAt));
        FilterSettings kept = rules();
        assertArrayEquals("the rules stay", GROWN, kept.minimums());
        assertTrue("and Autopilot's settings", kept.autopilot);
        List<String> details = AutopilotText.detailsLines(AutopilotRuntime.status(app, wall, true));
        for (String line : details) assertFalse(line, line.startsWith("Your minimums last grew"));
    }
}

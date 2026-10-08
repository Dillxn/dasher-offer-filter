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
 * The minimums grow through Autopilot's runtime (0.5.1): a plan finds the evidence, and the commit at a safe point grows
 * them in place of a bar move, at most once, logged in one fixed line, with the bar's change note "your minimums grew"
 * and the growth kept for its note; never while Autopilot recovers toward its goal, with "Let my minimums grow" off,
 * with Autopilot off, before the notice is accepted, while a change of the user's waits, or when anything changed
 * since the evidence. Undo is the user's change; Clear history removes the note and its Undo. Plans run on the
 * calling thread; the clocks are the test's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class MinimumsGrowthRuntimeTest {
    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;
    /** What the spec's example logs. */
    private static final String GREW = "minimums grew 8% after 30 offers above 103% on 2 days: $4.00 -> $4.32, "
            + "$1.00/mi -> $1.08/mi, $15.00/hr -> $16.20/hr (bar 112% -> 104%)";

    private Application app;
    private long wall;
    /** Midday now, whatever hour the suite runs at, so the offers' days are the same days ({@link MiddayZone}). */
    private MiddayZone zone;
    private long elapsed;
    private long since;
    private int requests;

    @Before public void setUp() {
        app = RuntimeEnvironment.getApplication();
        wall = System.currentTimeMillis();
        zone = new MiddayZone(wall);
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
        zone.restore();
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
     * hours, each passed and left to the user.
     */
    private void history(int bar) {
        for (int i = 29; i >= 0; i--) {
            long at = wall - 5 * MIN - 7 * MIN * (i % 15) - (i >= 15 ? 24 * HOUR : 0);
            OfferSnapshot facts = new OfferSnapshot(1500 + i, 6.6, 27, 2);
            DecisionLog.record(app, new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false, facts, 756,
                    OfferRule.Result.KEEP, "passes your rules", DecisionLog.Action.PASSES, true,
                    Collections.<String>emptyList()).withBar(bar, true));
        }
    }

    private Autopilot.Plan plan() {
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.USER);
        Autopilot.Plan plan = AutopilotRuntime.latest();
        assertNotNull(plan);
        return plan;
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
        assertArrayEquals(new int[] {400, 100, 25, 0, 0, 0}, rules().minimums());
        assertNull(AutopilotStore.lastGrowth(app));
        assertEquals(0, count(GREW));
    }

    // ---- Growth ----

    @Test public void theMinimumsGrowAtACommitInPlaceOfABarMoveOnceLoggedAndNoted() {
        history(108);
        Autopilot.Plan plan = plan();
        assertNotNull("due: 30 offers at 108% over two days", plan.growth);
        assertEquals(8, plan.growth.percent());
        assertTrue("the plan asks the screen reader for a commit", requests > 0);
        assertTrue(AutopilotRuntime.commitWanted(app));

        assertEquals(AutopilotRuntime.Commit.GREW, AutopilotRuntime.commitIfDue(app));
        FilterSettings grown = rules();
        assertArrayEquals(new int[] {432, 108, 27, 0, 0, 0}, grown.minimums());
        assertEquals(104, grown.minimumScalePercent);
        assertEquals("max stops never grows", 3, grown.maxStops);
        assertEquals(wall, FilterStore.minimumsSince(app));
        assertEquals(1, count(GREW));
        // The bar's change note says why it came down.
        AutopilotStore.Change change = AutopilotStore.lastChange(app);
        assertEquals(112, change.from);
        assertEquals(104, change.to);
        assertEquals("MINIMUMS_GREW", change.why);
        // The growth is kept, with its note.
        AutopilotStore.Grew grew = AutopilotStore.lastGrowth(app);
        assertEquals(wall, grew.at);
        assertEquals(8, grew.grown.percent);
        assertEquals(30, grew.offers);
        assertEquals(2, grew.days);
        assertEquals(400, grew.grown.flatBefore);
        assertEquals(432, grew.grown.flatAfter);
        assertEquals(112, grew.grown.barBefore);
        assertEquals(104, grew.grown.barAfter);
        assertTrue(grew.note);
        assertNotNull(AutopilotRuntime.growthNote(app));
        // Autopilot's details say when, and why the bar moved.
        List<String> details = AutopilotText.detailsLines(AutopilotRuntime.status(app, wall, true));
        assertTrue(details.toString(), details.contains("Last change less than a minute ago: 112% → 104% (your "
                + "minimums grew)."));
        assertTrue(details.toString(), details.contains("Your minimums last grew 8%, less than a minute ago: $4.00 → "
                + "$4.32 · $1.00/mi → $1.08/mi · $15/hr → $16.20/hr."));

        // At most once: the evidence starts again from the grown minimums, and these 30 offers are from before them.
        elapsed += 2 * MIN;
        wall += 2 * MIN;
        Autopilot.Plan next = plan();
        assertNull(next.growth);
        assertTrue(AutopilotRuntime.commitIfDue(app) != AutopilotRuntime.Commit.GREW);
        assertArrayEquals(new int[] {432, 108, 27, 0, 0, 0}, rules().minimums());
        assertEquals(1, count(GREW));
    }

    @Test public void notWhileRecoveringTowardTheGoal() {
        history(108);
        // Dasher showed 40% a minute ago, below the 70% goal: Autopilot is recovering.
        assertTrue(AutopilotStore.recordReading(app, 40, "", wall - MIN));
        Autopilot.Plan plan = plan();
        assertTrue(plan.recovering);
        assertNotNull("the evidence holds", plan.growth);
        assertFalse(AutopilotRuntime.commitIfDue(app) == AutopilotRuntime.Commit.GREW);
        assertNotGrown();
    }

    @Test public void notWithLetMyMinimumsGrowOffAndNothingElseChanges() {
        history(108);
        AutopilotRuntime.setMinimumsGrow(app, false);
        assertEquals(1, count("minimums growth off"));
        assertNotNull("the evidence holds", plan().growth);
        assertFalse("nothing is wanted: these offers pass at every bar, so the bar holds", AutopilotRuntime
                .commitWanted(app));
        assertEquals(AutopilotRuntime.Commit.HELD, AutopilotRuntime.commitIfDue(app));
        assertNotGrown();
        assertEquals(112, rules().minimumScalePercent);
        assertTrue("Autopilot stays on", rules().autopilot);
        // On again: due at the next commit.
        AutopilotRuntime.setMinimumsGrow(app, true);
        assertEquals(1, count("minimums growth on"));
        assertEquals(AutopilotRuntime.Commit.GREW, AutopilotRuntime.commitIfDue(app));
        assertArrayEquals(new int[] {432, 108, 27, 0, 0, 0}, rules().minimums());
    }

    @Test public void notWithAutopilotOffNorBeforeTheNoticeIsAccepted() {
        history(108);
        plan();
        AutopilotRuntime.setAutopilot(app, false, FilterSettings.GOAL_TOP_TIER);
        assertEquals(AutopilotRuntime.Commit.NO_PLAN, AutopilotRuntime.commitIfDue(app));
        assertNotGrown();
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        ConsentedTestApp.forget(app);
        assertEquals(AutopilotRuntime.Commit.NO_PLAN, AutopilotRuntime.commitIfDue(app));
        assertNotGrown();
        ConsentedTestApp.accept(app);
    }

    @Test public void neverForAUserWithNoMoneyMinimum() {
        // Max stops only, since three days ago, and 30 offers at 120% since.
        FilterStore.wallClock = () -> since;
        FilterStore.save(app, FilterSettings.of(true, 0, 0, 0, 3));
        assertEquals(since, FilterStore.minimumsSince(app));
        history(120);
        assertNull(plan().growth);
        assertFalse(AutopilotRuntime.commitIfDue(app) == AutopilotRuntime.Commit.GREW);
        assertArrayEquals(new int[] {0, 0, 0, 0, 0, 0}, rules().minimums());
        assertEquals(3, rules().maxStops);
        assertNull(AutopilotStore.lastGrowth(app));
    }

    @Test public void aChangeOfTheUsersWaitingForItsCommitGoesFirst() {
        history(108);
        plan();
        // The user chose another goal: the next commit is that change's jump, not the growth.
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TIER);
        assertEquals("GOAL_CHANGED", AutopilotStore.jump(app));
        AutopilotRuntime.Commit first = AutopilotRuntime.commitIfDue(app);
        assertTrue(first.toString(), first == AutopilotRuntime.Commit.HELD
                || first == AutopilotRuntime.Commit.COMMITTED);
        assertNull("the user's change is done", AutopilotStore.jump(app));
        assertArrayEquals(new int[] {400, 100, 25, 0, 0, 0}, rules().minimums());
        assertNull(AutopilotStore.lastGrowth(app));
        // Then the growth, at the next commit.
        assertEquals(AutopilotRuntime.Commit.GREW, AutopilotRuntime.commitIfDue(app));
        assertArrayEquals(new int[] {432, 108, 27, 0, 0, 0}, rules().minimums());
    }

    @Test public void aChangeBetweenTheEvidenceAndTheWriteWritesNothing() {
        history(108);
        plan();
        // The user sets a minimum just as the commit is about to write.
        AutopilotRuntime.beforeWriteForTests = () -> FilterStore.save(app, rules().withMinimums(450, 100, 25));
        AutopilotRuntime.Commit outcome = AutopilotRuntime.commitIfDue(app);
        assertTrue(outcome.toString(), outcome == AutopilotRuntime.Commit.DISCARDED
                || outcome == AutopilotRuntime.Commit.CONFLICT);
        assertArrayEquals("the user's change stands, nothing grown", new int[] {450, 100, 25, 0, 0, 0},
                rules().minimums());
        assertEquals(112, rules().minimumScalePercent);
        assertNull(AutopilotStore.lastGrowth(app));
        assertEquals(0, count(GREW));
        assertEquals("its minimums took effect now: the evidence starts again", wall,
                FilterStore.minimumsSince(app));
        AutopilotRuntime.beforeWriteForTests = null;
        assertNull(plan().growth);
    }

    // ---- Undo, the note and Clear history ----

    @Test public void undoPutsTheMinimumsBackAsTheUsersChangeAndTheBarIsAutopilotsToMove() {
        history(108);
        plan();
        assertEquals(AutopilotRuntime.Commit.GREW, AutopilotRuntime.commitIfDue(app));
        long grewAt = wall;
        wall += 5 * MIN;
        elapsed += 5 * MIN;
        assertFalse("only the growth shown", AutopilotRuntime.undoGrowth(app, grewAt - 1));
        assertTrue(AutopilotRuntime.undoGrowth(app, grewAt));
        FilterSettings undone = rules();
        assertArrayEquals(new int[] {400, 100, 25, 0, 0, 0}, undone.minimums());
        assertEquals(wall, FilterStore.minimumsSince(app));
        assertEquals("the bar waits for Autopilot", 104, undone.minimumScalePercent);
        assertEquals("other minimums: a jump at the next safe point", "RULES_CHANGED", AutopilotStore.jump(app));
        assertNull("the note and the growth's record go", AutopilotStore.lastGrowth(app));
        assertNull(AutopilotRuntime.growthNote(app));
        assertEquals(1, count("minimums growth undone: $4.32 -> $4.00, $1.08/mi -> $1.00/mi, $16.20/hr -> $15.00/hr"));
        assertFalse("once", AutopilotRuntime.undoGrowth(app, grewAt));
        // The jump is the next commit's, as for any change of minimums; no growth comes back on the same evidence.
        AutopilotRuntime.Commit next = AutopilotRuntime.commitIfDue(app);
        assertTrue(next.toString(), next == AutopilotRuntime.Commit.HELD || next == AutopilotRuntime.Commit.COMMITTED);
        assertNull(AutopilotStore.jump(app));
        assertArrayEquals(new int[] {400, 100, 25, 0, 0, 0}, rules().minimums());
        assertNull(plan().growth);
        assertNull(AutopilotStore.lastGrowth(app));
    }

    @Test public void theNoteGoesWithItsUndoOnceTheMinimumsChangeAnyOtherWayAndOkKeepsTheGrowth() {
        history(108);
        plan();
        assertEquals(AutopilotRuntime.Commit.GREW, AutopilotRuntime.commitIfDue(app));
        long grewAt = wall;
        assertNotNull(AutopilotRuntime.growthNote(app));
        // OK: the note goes, the growth stays (the details still say it).
        AutopilotRuntime.growthNoted(app, grewAt);
        assertNull(AutopilotRuntime.growthNote(app));
        assertNotNull(AutopilotStore.lastGrowth(app));
        assertFalse("no Undo without its note", AutopilotRuntime.undoGrowth(app, grewAt));

        // Another growth's note, then a knob set by hand: the note and its Undo go for good.
        AutopilotStore.recordGrowth(app, growthAgain(), wall);
        assertNotNull(AutopilotRuntime.growthNote(app));
        wall += MIN;
        FilterStore.save(app, rules().withMinimums(432, 108, 30));
        assertNull(AutopilotRuntime.growthNote(app));
        FilterStore.save(app, rules().withMinimums(432, 108, 27));
        assertNull("changed back, the note stays gone", AutopilotRuntime.growthNote(app));
        assertFalse(AutopilotRuntime.undoGrowth(app, grewAt));
        assertArrayEquals(new int[] {432, 108, 27, 0, 0, 0}, rules().minimums());
    }

    /** The growth just made, as its record kept it, to note again. */
    private Growth.Evidence growthAgain() {
        AutopilotStore.Grew grew = AutopilotStore.lastGrowth(app);
        return new Growth.Evidence(grew.offers, grew.days, 108, grew.at, grew.grown);
    }

    @Test public void clearHistoryRemovesTheNoteAndItsUndoAndKeepsTheRulesAndSettings() {
        history(108);
        plan();
        assertEquals(AutopilotRuntime.Commit.GREW, AutopilotRuntime.commitIfDue(app));
        long grewAt = wall;
        AutopilotRuntime.setMinimumsGrow(app, false);
        DecisionLog.clear(app);
        AutopilotRuntime.cleared(app);
        assertNull(AutopilotStore.lastGrowth(app));
        assertNull(AutopilotRuntime.growthNote(app));
        assertFalse(AutopilotRuntime.undoGrowth(app, grewAt));
        FilterSettings kept = rules();
        assertArrayEquals("the rules stay", new int[] {432, 108, 27, 0, 0, 0}, kept.minimums());
        assertTrue(kept.autopilot);
        assertFalse("and Autopilot's settings", FilterStore.minimumsGrow(app));
        List<String> details = AutopilotText.detailsLines(AutopilotRuntime.status(app, wall, true));
        for (String line : details) assertFalse(line, line.startsWith("Your minimums last grew"));
    }
}

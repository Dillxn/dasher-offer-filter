package com.local.dasherfilter;

import android.app.Application;
import android.os.SystemClock;
import java.util.ArrayList;
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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The accepted floor through Autopilot's runtime (0.6.11, the owner: "if an accepted offer's score beats the autopilot
 * ask, the ask needs to jump to the new higher score"): the owner pinned at 50% accepts his $14.45 offer (7.2 mi,
 * 27 min, 63% of his minimums) by hand, and the next commit lifts the bar to 63% at once, with its own words; it holds
 * there for the dash. A new dash and the user's own changes (a knob, the goal, Autopilot off and on) let it go, each
 * straight back at the next commit; a lesson and Adopt keep it. Automatic, add-on and observed accepts, an accept
 * before the user's last change, Autopilot off and the notice not accepted change nothing; Clear history forgets it.
 * Bars the floor holds up never grow the minimums. Plans run on the calling thread; Autopilot's clocks are the test's,
 * starting at the real one (the decision lines' steps and the dash go by the real clock).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class AutopilotFloorRuntimeTest {
    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;
    /** His accepted offer: 63% of a $3.18-a-mile minimum's $22.90. */
    private static final OfferSnapshot HIS = new OfferSnapshot(1445, 7.2, 27, 2);
    private static final String COMMIT_63 = "commit 50% -> 63% (you accepted an offer at this score)";

    private Application app;
    private long wall;
    private long elapsed;
    private MiddayZone zone;

    @Before public void setUp() {
        app = RuntimeEnvironment.getApplication();
        wall = System.currentTimeMillis();
        elapsed = 50_000_000L;
        AutopilotRuntime.wallClock = () -> wall;
        AutopilotRuntime.elapsedClock = () -> elapsed;
        AutopilotRuntime.executorForTests = Runnable::run;
        AutopilotRuntime.beforeWriteForTests = null;
        AutopilotRuntime.forgetCache();
        AutopilotRuntime.setCommitRequester(null);
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        DiagnosticLog.clear(app);
        AutopilotStore.clear(app);
        Dashing.forgetCache();
    }

    @After public void tearDown() {
        if (zone != null) zone.restore();
        AutopilotRuntime.forgetCache();
        AutopilotRuntime.executorForTests = null;
        AutopilotRuntime.wallClock = System::currentTimeMillis;
        AutopilotRuntime.elapsedClock = SystemClock::elapsedRealtime;
        FilterStore.wallClock = System::currentTimeMillis;
        DecisionLog.forgetCache();
    }

    // ---- Fixtures ----

    /**
     * The owner's case: $5.00 and $3.18 a mile, 99 offers paying about a fifth of that and his offer, decided at 50%;
     * Dasher showed 10%; a dash under way; Autopilot turned on with goal 70%, and its first commit pins the bar at
     * 50%.
     */
    private void pinned() {
        FilterStore.save(app, FilterSettings.of(true, 250, 318, 0, 0));
        FilterSettings decided = FilterStore.load(app).withMinimumScalePercent(50);
        for (int i = 98; i >= 0; i--) {
            line(new OfferSnapshot(500 + i, 7.2, 27, 2), wall - (5 + 3L * i) * MIN, decided);
        }
        line(HIS, wall - 2 * MIN, decided);
        assertTrue(AutopilotStore.recordReading(app, 10, "", wall - MIN));
        Dashing.seen(app);
        assertTrue(Dashing.on(app));
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        assertEquals(50, bar());
        Autopilot.Plan plan = AutopilotRuntime.latest();
        assertSame(Autopilot.Mode.PINNED, plan.mode);
        assertEquals(-1, plan.floor);
    }

    private void line(OfferSnapshot facts, long at, FilterSettings rules) {
        OfferRule.Decision decision = OfferRule.evaluate(facts, rules);
        DecisionLog.Action action = decision.result == OfferRule.Result.DECLINE
                ? DecisionLog.Action.CONFIRMATION_TAPPED : DecisionLog.Action.PASSES;
        DecisionLog.record(app, new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false, facts,
                decision.requiredCents, decision.result, decision.reason, action, true,
                Collections.<String>emptyList()).withBar(rules.minimumScalePercent, true).withScoringRules(rules));
    }

    /** The service's step on his line ({@code kind}), then, for a hand accept, its plan (the service's order). */
    private void accepted(DecisionLog.StepKind kind) {
        assertTrue(DecisionLog.markStep(app, HIS, kind, "", 10 * MIN));
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.ACCEPTED);
    }

    /** His accept lifts the bar to 63% at the next commit. */
    private void held() {
        accepted(DecisionLog.StepKind.ACCEPTED);
        Autopilot.Plan plan = AutopilotRuntime.latest();
        assertEquals(63, plan.floor);
        assertEquals(50, plan.ownTarget);
        assertEquals(63, plan.target);
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        assertEquals(63, bar());
    }

    /** The user's change comes after his accept: Autopilot's clock (its "floor since") moves past the step's. */
    private void userLater() {
        wall = System.currentTimeMillis() + 1_000L;
    }

    /** The real clock moves past every time stamped so far (a new dash starts after his accept). */
    private static void realLater() {
        long now = System.currentTimeMillis();
        while (System.currentTimeMillis() <= now) Thread.yield();
    }

    /** At the next commit, straight back to 50%, with these words. */
    private void releasedWith(String words) {
        Autopilot.Plan plan = AutopilotRuntime.latest();
        assertNotNull(plan);
        assertEquals(-1, plan.floor);
        assertEquals(50, plan.target);
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        assertEquals(50, bar());
        assertEquals("commit " + (words.startsWith("Autopilot") ? "100" : "63") + "% -> 50% (" + words + ")",
                lastCommit());
    }

    private int bar() {
        return FilterStore.load(app).minimumScalePercent;
    }

    private List<String> logged() {
        List<String> lines = new ArrayList<>();
        for (String line : DiagnosticLog.read(app).split("\n")) {
            int at = line.indexOf("[autopilot] ");
            if (at >= 0) lines.add(line.substring(at + "[autopilot] ".length()));
        }
        return lines;
    }

    private String lastCommit() {
        String last = null;
        for (String line : logged()) if (line.startsWith("commit ") && line.contains("% -> ")) last = line;
        return last;
    }

    // ---- His accept ----

    @Test public void hisAcceptLiftsTheBarAtTheNextCommitAndHoldsItThere() {
        pinned();
        accepted(DecisionLog.StepKind.ACCEPTED);
        Autopilot.Plan plan = AutopilotRuntime.latest();
        assertSame(Autopilot.Mode.PINNED, plan.mode);
        assertEquals(63, plan.target);
        // The commit right after the last one (0 s spacing): straight up, past every limit of a raise.
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        assertEquals(63, bar());
        assertEquals(COMMIT_63, lastCommit());
        AutopilotStore.Change change = AutopilotStore.lastChange(app);
        assertEquals(Autopilot.Reason.ACCEPTED.name(), change.why);
        assertEquals(50, change.from);
        assertEquals(63, change.to);
        assertTrue(lastPlan(), lastPlan().startsWith("plan 63% PINNED: "));
        assertTrue(lastPlan(), lastPlan().endsWith(", floor 63%, own 50%"));
        // The next tick plans the same and holds.
        elapsed += 2 * MIN;
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.TICK);
        assertEquals(63, AutopilotRuntime.latest().target);
        assertEquals(AutopilotRuntime.Commit.HELD, AutopilotRuntime.commitIfDue(app));
        assertEquals(63, bar());
    }

    private String lastPlan() {
        String last = "";
        for (String line : logged()) if (line.startsWith("plan ")) last = line;
        return last;
    }

    // ---- What lets it go ----

    @Test public void aNewDashLetsItGo() {
        pinned();
        held();
        Dashing.ended(app);
        realLater();
        Dashing.seen(app);
        assertTrue(Dashing.on(app));
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.TICK);
        // 0 s after the floor's commit, and 13 points: in one commit, not a wait and a 20-point step.
        releasedWith("your minimums are high for these offers");
    }

    @Test public void aKnobLetsItGo() {
        pinned();
        held();
        userLater();
        FilterSettings before = FilterStore.load(app);
        // $5.10 a ride asks nothing more of 7.2 mi: his offer still scores 63, but the change is his.
        FilterStore.save(app, before, before.withMinimums(255, 318, 0));
        AutopilotRuntime.rulesChanged(app);
        releasedWith("you changed your minimums");
    }

    @Test public void aGoalChangeLetsItGo() {
        pinned();
        held();
        userLater();
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TIER);
        releasedWith("you changed your goal");
    }

    @Test public void autopilotOffThenOnLetsItGo() {
        pinned();
        held();
        userLater();
        AutopilotRuntime.setAutopilot(app, false, FilterSettings.GOAL_TOP_TIER);
        assertEquals(100, bar());
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        releasedWith("Autopilot turned on");
    }

    // ---- What keeps it ----

    @Test public void aLessonKeepsItAndTheRecoveryState() {
        pinned();
        held();
        userLater();
        AutopilotStore.savePlanState(app, true, 4, wall - HOUR, 1_000);
        AutopilotRuntime.learned(app);
        assertEquals(Autopilot.Reason.LEARNED.name(), AutopilotStore.jump(app));
        assertTrue("a lesson keeps the recovery state", AutopilotStore.recovering(app));
        assertEquals(4, AutopilotStore.extra(app));
        Autopilot.Plan plan = AutopilotRuntime.latest();
        assertEquals("the jump goes to max(own, the floor recomputed)", 63, plan.target);
        assertEquals(63, plan.floor);
        assertEquals(AutopilotRuntime.Commit.HELD, AutopilotRuntime.commitIfDue(app));
        assertEquals(63, bar());
        assertNull("the jump is done", AutopilotStore.jump(app));
        assertTrue(AutopilotStore.recovering(app));
        assertTrue("the floor did not start over", AutopilotStore.floorSince(app) < wall);
    }

    @Test public void adoptKeepsIt() {
        pinned();
        held();
        userLater();
        long since = AutopilotStore.floorSince(app);
        AutopilotRuntime.adopted(app);
        assertEquals("Adopt's jump is still a change of the minimums", Autopilot.Reason.RULES_CHANGED.name(),
                AutopilotStore.jump(app));
        assertEquals(since, AutopilotStore.floorSince(app));
        assertEquals(63, AutopilotRuntime.latest().target);
        assertEquals(AutopilotRuntime.Commit.HELD, AutopilotRuntime.commitIfDue(app));
        assertEquals(63, bar());
    }

    // ---- What never sets it ----

    @Test public void automaticAddOnAndObservedAcceptsSetNothing() {
        pinned();
        accepted(DecisionLog.StepKind.ACCEPTED_AUTOMATIC);
        accepted(DecisionLog.StepKind.ACCEPTED_ADD_ON);
        accepted(DecisionLog.StepKind.ACCEPTED_OBSERVED);
        Autopilot.Plan plan = AutopilotRuntime.latest();
        assertEquals(-1, plan.floor);
        assertEquals(50, plan.target);
        assertEquals(AutopilotRuntime.Commit.HELD, AutopilotRuntime.commitIfDue(app));
        assertEquals(50, bar());
    }

    @Test public void anAcceptBeforeTheUsersLastChangeSetsNothing() {
        pinned();
        accepted(DecisionLog.StepKind.ACCEPTED);
        userLater();
        AutopilotRuntime.rulesChanged(app);
        assertEquals(-1, AutopilotRuntime.latest().floor);
        assertEquals(AutopilotRuntime.Commit.HELD, AutopilotRuntime.commitIfDue(app));
        assertEquals(50, bar());
    }

    @Test public void nothingWhileAutopilotIsOff() {
        pinned();
        AutopilotRuntime.setAutopilot(app, false, FilterSettings.GOAL_TOP_TIER);
        accepted(DecisionLog.StepKind.ACCEPTED);
        assertNull(AutopilotRuntime.latest());
        assertEquals(AutopilotRuntime.Commit.NO_PLAN, AutopilotRuntime.commitIfDue(app));
        assertEquals(100, bar());
    }

    @Test public void nothingBeforeTheNoticeIsAccepted() {
        pinned();
        ConsentedTestApp.forget(app);
        accepted(DecisionLog.StepKind.ACCEPTED);
        assertEquals("no new plan", -1, AutopilotRuntime.latest().floor);
        assertEquals(AutopilotRuntime.Commit.NO_PLAN, AutopilotRuntime.commitIfDue(app));
        assertEquals(50, bar());
    }

    @Test public void clearHistoryForgetsIt() {
        pinned();
        held();
        assertTrue(AutopilotStore.floorSince(app) > 0);
        DecisionLog.clear(app);
        AutopilotRuntime.cleared(app);
        assertEquals(0, AutopilotStore.floorSince(app));
        Autopilot.Plan plan = AutopilotRuntime.latest();
        assertSame(Autopilot.Mode.LEARNING, plan.mode);
        assertEquals(-1, plan.floor);
        assertEquals(100, plan.target);
    }

    // ---- Growth ----

    /**
     * Typical minimums Autopilot has held at 108% over two days (growth is due), then a hand accept scoring past 150%
     * this dash: while the floor binds, its commit comes in place of the growth, and nothing grows.
     */
    @Test public void noGrowthWhileTheFloorHoldsTheBar() {
        zone = new MiddayZone(wall);
        long since = wall - 3 * 24 * HOUR;
        FilterStore.wallClock = () -> since;
        FilterStore.save(app, FilterSettings.of(true, 200, 100, 25, 3));
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertTrue(FilterStore.commitAutopilotBar(app, 100, 112));
        FilterStore.wallClock = () -> wall;
        FilterSettings scoring = FilterStore.load(app).withMinimumScalePercent(108);
        for (int i = 29; i >= 0; i--) {
            long at = wall - 5 * MIN - 7 * MIN * (i % 15) - (i >= 15 ? 24 * HOUR : 0);
            OfferSnapshot facts = new OfferSnapshot(1500 + i, 6.6, 27, 2);
            DecisionLog.record(app, new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false, facts, 729,
                    OfferRule.Result.KEEP, "passes your rules", DecisionLog.Action.PASSES, true,
                    Collections.<String>emptyList()).withBar(108, true).withScoringRules(scoring));
        }
        // $11.00 for 6.6 mi and 27 min: 162% of the $6.75 asked, so 150%.
        OfferSnapshot rich = new OfferSnapshot(1100, 6.6, 27, 2);
        DecisionLog.record(app, new DecisionLog.Entry(wall - 2 * MIN, DecisionLog.Source.SCREEN, false, rich, 729,
                OfferRule.Result.KEEP, "passes your rules", DecisionLog.Action.PASSES, true,
                Collections.<String>emptyList()).withBar(108, true).withScoringRules(scoring));
        Dashing.seen(app);
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.USER);
        Autopilot.Plan due = AutopilotRuntime.latest();
        assertNotNull("growth is due", due.growth);
        assertFalse(due.floorBinds());
        assertTrue(DecisionLog.markStep(app, rich, DecisionLog.StepKind.ACCEPTED, "", 10 * MIN));
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.ACCEPTED);
        Autopilot.Plan plan = AutopilotRuntime.latest();
        assertNotNull("the 30 lines came before the accept: still evidence", plan.growth);
        assertEquals(150, plan.floor);
        assertTrue(plan.floorBinds());
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        assertEquals(150, bar());
        assertEquals("commit 112% -> 150% (you accepted an offer at this score)", lastCommit());
        assertArrayEquals(new int[] {200, 100, 25, 0, 0, 0}, FilterStore.load(app).minimums());
        assertNull(AutopilotStore.lastGrowth(app));
    }
}

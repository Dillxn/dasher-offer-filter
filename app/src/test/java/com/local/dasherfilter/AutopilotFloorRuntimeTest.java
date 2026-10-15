package com.local.dasherfilter;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
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
 * there across dashes (0.6.18; the owner chose "Earnings first", 14 October 2026: until then a new dash let it go) for
 * the 24 offers after it, however long they take (0.6.21, the owner: "24h may b too aggressive. We may have to do it
 * by like two dozen orders or something"; the last 7 days from 0.6.18). The 24th offer after the accept and the
 * user's own changes (a knob, the goal, turning Autopilot on with another goal) let it go, each straight back at the
 * next commit; its figures stay kept for 7 days after 200 newer offers have pushed its line out of the history
 * (0.6.19); turning Autopilot off and on (0.6.19), a lesson and Adopt keep it. Automatic, add-on and
 * observed accepts, an accept before the user's last change, Autopilot off and the notice not accepted change nothing;
 * Clear history forgets it. The update to 0.6.19 brings back, once and with pay first, an accept that turning
 * Autopilot on let go. The minimums still grow while the floor holds the bar (0.6.21: growth reads what offers paid,
 * not the bars). Plans run on the calling thread; Autopilot's
 * clocks are the test's, starting at the real one (the decision lines' steps and the dash go by the real clock).
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

    private SharedPreferences autopilotPrefs() {
        return app.getSharedPreferences(AutopilotStore.PREFS, Context.MODE_PRIVATE);
    }

    /** The hand accepts kept for the floor, as stored (0.6.19); null when none is. */
    private String keptAccepts() {
        return autopilotPrefs().getString("kept_accepts", null);
    }

    private int repairs() {
        return Collections.frequency(logged(), AutopilotText.LOG_FLOOR_PAST_TOGGLES);
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

    /** A new dash after his accept (0.6.18: it no longer lets it go). */
    private void nextDash() {
        Dashing.ended(app);
        realLater();
        Dashing.seen(app);
        assertTrue(Dashing.on(app));
    }

    @Test public void aNewDashKeepsIt() {
        pinned();
        held();
        nextDash();
        elapsed += 2 * MIN;
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.TICK);
        Autopilot.Plan plan = AutopilotRuntime.latest();
        assertSame(Autopilot.Mode.PINNED, plan.mode);
        assertEquals(63, plan.floor);
        assertEquals(63, plan.target);
        assertEquals(AutopilotRuntime.Commit.HELD, AutopilotRuntime.commitIfDue(app));
        assertEquals(63, bar());
        assertEquals("no other commit since the floor's", COMMIT_63, lastCommit());
    }

    /**
     * The owner's 14 October 2026 case (0.6.18): his hand accept at 6:45 AM scored 67%; by 2:56 PM, a dash later,
     * Autopilot pinned at 50% read "67% → 50% (your minimums are high for these offers)". Now the earlier dash's accept
     * holds the bar at 67% in the later dash, pinned or not, with the accepted words.
     */
    @Test public void anAcceptAtSixtySevenInAnEarlierDashHoldsTheBarInALaterOneWhilePinned() {
        pinned();
        // $15.40 for 7.2 mi: 67% of the $22.90 his $3.18 a mile asks.
        OfferSnapshot morning = new OfferSnapshot(1540, 7.2, 27, 2);
        FilterSettings decided = FilterStore.load(app);
        assertEquals(67, AreaScore.scorePercent(decided, morning));
        line(morning, wall - MIN, decided);
        assertTrue(DecisionLog.markStep(app, morning, DecisionLog.StepKind.ACCEPTED, "", 10 * MIN));
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.ACCEPTED);
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        assertEquals(67, bar());

        // Eight hours later, in a later dash.
        nextDash();
        wall += 8 * HOUR;
        elapsed += 8 * HOUR;
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.TICK);
        Autopilot.Plan plan = AutopilotRuntime.latest();
        assertSame("the goal still can't be met", Autopilot.Mode.PINNED, plan.mode);
        assertEquals(50, plan.ownTarget);
        assertEquals(67, plan.floor);
        assertEquals("never below the floor, pinned included", 67, plan.target);
        assertTrue(plan.floorBinds());
        assertEquals(AutopilotRuntime.Commit.HELD, AutopilotRuntime.commitIfDue(app));
        assertEquals(67, bar());
        assertEquals("commit 50% -> 67% (you accepted an offer at this score)", lastCommit());
        assertEquals(Autopilot.Reason.ACCEPTED.name(), AutopilotStore.lastChange(app).why);
    }

    /**
     * 0.6.21: days with no offers keep it; it lets go at the 24th offer after the accept, straight back to the plan's
     * own target, and the plan counts down the offers it has left.
     */
    @Test public void itLetsGoAtThe24thOfferAfterTheAcceptHoweverLongTheyTake() {
        pinned();
        held();
        nextDash();
        // Eight days on with no offer: still held, for all 24.
        wall += 8 * 24 * HOUR;
        elapsed += 8 * 24 * HOUR;
        assertTrue(AutopilotStore.recordReading(app, 10, "", wall - MIN));
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.TICK);
        Autopilot.Plan plan = AutopilotRuntime.latest();
        assertEquals(63, plan.floor);
        assertEquals(Autopilot.FLOOR_OFFERS, plan.floorOffersLeft);
        assertEquals(AutopilotRuntime.Commit.HELD, AutopilotRuntime.commitIfDue(app));
        assertEquals(63, bar());
        // 23 offers after the accept, all declined: one left.
        FilterSettings decided = FilterStore.load(app);
        long after = System.currentTimeMillis() + 1_000L;
        for (int i = 0; i < Autopilot.FLOOR_OFFERS - 1; i++) {
            line(new OfferSnapshot(600 + i, 7.2, 27, 2), after + i * 1_000L, decided);
        }
        elapsed += 2 * MIN;
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.TICK);
        plan = AutopilotRuntime.latest();
        assertEquals(63, plan.floor);
        assertEquals(1, plan.floorOffersLeft);
        assertEquals(AutopilotRuntime.Commit.HELD, AutopilotRuntime.commitIfDue(app));
        assertEquals(63, bar());
        // The 24th: no floor, straight back to the plan's own target.
        line(new OfferSnapshot(700, 7.2, 27, 2), after + Autopilot.FLOOR_OFFERS * 1_000L, decided);
        elapsed += 2 * MIN;
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.TICK);
        releasedWith("your minimums are high for these offers");
    }

    @Test public void theUsersChangeInALaterDashStillLetsItGo() {
        pinned();
        held();
        nextDash();
        userLater();
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TIER);
        releasedWith("you changed your goal");
    }

    @Test public void aKnobInALaterDashStillLetsItGo() {
        pinned();
        held();
        nextDash();
        userLater();
        FilterSettings before = FilterStore.load(app);
        FilterStore.save(app, before, before.withMinimums(255, 318, 0));
        AutopilotRuntime.rulesChanged(app);
        releasedWith("you changed your minimums");
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
        assertNotNull(keptAccepts());
        userLater();
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TIER);
        releasedWith("you changed your goal");
        assertNull("the accepts kept for it are forgotten", keptAccepts());
    }

    /** Turning Autopilot on with another goal than it had is a change of goal too (0.6.19). */
    @Test public void turningItOnWithAnotherGoalLetsItGo() {
        pinned();
        held();
        userLater();
        AutopilotRuntime.setAutopilot(app, false, FilterSettings.GOAL_TOP_TIER);
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TIER);
        releasedWith("Autopilot turned on");
        assertNull(keptAccepts());
    }

    // ---- What keeps it ----

    /**
     * The owner's 14 October 2026 report on 0.6.18 ("it dropped the auto score below my recent high again on 6.18"):
     * he turned Autopilot off and back on, and its first commit put the bar at 52%, under his accept. From 0.6.19 off
     * and on keep it: back on, the bar goes straight to the accept's score, in its words.
     */
    @Test public void autopilotOffThenOnKeepsIt() {
        pinned();
        held();
        userLater();
        long since = AutopilotStore.floorSince(app);
        AutopilotRuntime.setAutopilot(app, false, FilterSettings.GOAL_TOP_TIER);
        assertEquals(100, bar());
        assertEquals(since, AutopilotStore.floorSince(app));
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertEquals("the floor did not start over", since, AutopilotStore.floorSince(app));
        Autopilot.Plan plan = AutopilotRuntime.latest();
        assertEquals(50, plan.ownTarget);
        assertEquals(63, plan.floor);
        assertEquals(63, plan.target);
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        assertEquals(63, bar());
        assertEquals("commit 100% -> 63% (you accepted an offer at this score)", lastCommit());
        assertEquals(Autopilot.Reason.ACCEPTED.name(), AutopilotStore.lastChange(app).why);
        assertNull("the jump is used up", AutopilotStore.jump(app));
    }

    /**
     * 0.6.19 kept his accept's figures, so it held the bar for its 7 days once 200 newer offers had pushed its line out
     * of the history. From 0.6.21 those 200 are far more than its 24, so it lets go; its figures are still kept for the
     * 7 days and forgotten then.
     */
    @Test public void twoHundredNewerOffersLetItGoAndItsFiguresGoAfterSevenDays() {
        pinned();
        held();
        FilterSettings decided = FilterStore.load(app);
        long after = System.currentTimeMillis() + 1_000L;
        for (int i = 0; i < DecisionLog.MAX_ENTRIES; i++) {
            line(new OfferSnapshot(600 + i, 7.2, 27, 2), after + i * 1_000L, decided);
        }
        for (DecisionLog.Entry entry : DecisionLog.recent(app, DecisionLog.MAX_ENTRIES)) {
            assertFalse("his line left the history", entry.facts.fingerprint().equals(HIS.fingerprint()));
        }
        wall += 10 * MIN;
        elapsed += 10 * MIN;
        assertTrue(AutopilotStore.recordReading(app, 10, "", wall - MIN));
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.TICK);
        releasedWith("your minimums are high for these offers");
        assertNotNull("kept for its 7 days", keptAccepts());
        // Past 7 days from the accept: forgotten.
        wall += 7 * 24 * HOUR;
        elapsed += 7 * 24 * HOUR;
        assertTrue(AutopilotStore.recordReading(app, 10, "", wall - MIN));
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.TICK);
        assertNull(keptAccepts());
    }

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
        // Plans are still worked out for the minimums' growth (0.6.21), Autopilot off: they move no bar.
        Autopilot.Plan plan = AutopilotRuntime.latest();
        assertTrue(plan == null || plan.mode == Autopilot.Mode.OFF);
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
        assertNotNull(keptAccepts());
        DecisionLog.clear(app);
        AutopilotRuntime.cleared(app);
        assertEquals(0, AutopilotStore.floorSince(app));
        assertNull(keptAccepts());
        Autopilot.Plan plan = AutopilotRuntime.latest();
        assertSame(Autopilot.Mode.LEARNING, plan.mode);
        assertEquals(-1, plan.floor);
        assertEquals(100, plan.target);
    }

    // ---- The update to 0.6.19 ----

    /** As 0.6.18 left it after turning Autopilot back on: the floor restarted after his accept, not yet repaired. */
    private void restartedBy0618() {
        assertTrue(autopilotPrefs().edit().putLong("floor_since", wall).remove("floor_schema")
                .remove("kept_accepts").commit());
    }

    /**
     * The owner's case on updating: 0.6.18 had restarted the floor when he turned Autopilot back on, after his accept.
     * With pay first, the update's first plan counts the accepts again (from 0.6.21 each for the 24 offers after it),
     * once, with its log line.
     */
    @Test public void theUpdateBringsBackAnAcceptThatTurningItOnLetGo() {
        pinned();
        held();
        userLater();
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_PAY_FIRST);
        assertEquals(-1, AutopilotRuntime.latest().floor);
        restartedBy0618();
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.TICK);
        Autopilot.Plan plan = AutopilotRuntime.latest();
        assertEquals(63, plan.floor);
        assertEquals(Math.max(63, plan.ownTarget), plan.target);
        assertEquals(0, AutopilotStore.floorSince(app));
        assertNotNull("kept from now on", keptAccepts());
        assertEquals(1, repairs());
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.TICK);
        assertEquals(63, AutopilotRuntime.latest().floor);
        assertEquals("once only", 1, repairs());
    }

    /**
     * The owner opening the app after the update (14 October 2026: "when I opened the app it said 50% which was already
     * low"): nothing is planned before the new notice is accepted, and accepting it now plans at once (0.6.19), so the
     * homepage says where the bar is going until the commit, at the next safe point, puts it there.
     */
    @Test public void acceptingTheUpdatesNoticePlansAtOnceAndShowsTheRise() {
        pinned();
        held();
        userLater();
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_PAY_FIRST);
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        int left = bar();
        assertTrue(left + "%", left < 63);
        restartedBy0618();
        // The update: a new process with no plan, and the new notice not accepted yet.
        AutopilotRuntime.forgetCache();
        ConsentedTestApp.forget(app);
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.CONNECT);
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.RESUME);
        assertNull("nothing planned before the notice", AutopilotRuntime.latest());
        AutopilotText.Status opened = homepage();
        assertEquals("Auto " + left + "%", AutopilotText.chip(opened));
        assertEquals("Checking offers", AutopilotText.statusLine(opened));

        ConsentedTestApp.accept(app);
        AutopilotRuntime.noticeAccepted(app);
        Autopilot.Plan plan = AutopilotRuntime.latest();
        assertNotNull("planned at once", plan);
        assertTrue(plan.floorBinds());
        assertEquals(63, plan.target);
        AutopilotText.Status waiting = homepage();
        assertEquals("the bar deciding offers until the commit", "Auto " + left + "%", AutopilotText.chip(waiting));
        assertEquals("Rising to 63%", AutopilotText.statusLine(waiting));

        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        assertEquals(63, bar());
        assertEquals("commit " + left + "% -> 63% (you accepted an offer at this score)", lastCommit());
        assertEquals(-1, AutopilotText.risingTo(homepage()));
    }

    /** The homepage's status as it shows it: the stored rules and last change, and Autopilot's latest plan. */
    private AutopilotText.Status homepage() {
        return new AutopilotText.Status(FilterStore.load(app), true, AutopilotRuntime.latest(), null,
                AutopilotStore.lastChange(app), wall);
    }

    /** A goal that protects the acceptance rate keeps 0.6.18's restart. */
    @Test public void theUpdateKeepsTheRestartForAGoalThatProtectsTheRate() {
        pinned();
        held();
        userLater();
        restartedBy0618();
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.TICK);
        assertEquals(-1, AutopilotRuntime.latest().floor);
        assertEquals(wall, AutopilotStore.floorSince(app));
        assertEquals(0, repairs());
    }

    /** The user's own change under 0.6.19, before any plan: the update never undoes it. */
    @Test public void theUpdateNeverUndoesARestartMadeUnderIt() {
        pinned();
        held();
        userLater();
        assertTrue(autopilotPrefs().edit().remove("floor_schema").commit());
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_PAY_FIRST);
        assertEquals(-1, AutopilotRuntime.latest().floor);
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.TICK);
        assertEquals(-1, AutopilotRuntime.latest().floor);
        assertEquals(0, repairs());
    }

    // ---- Growth ----

    /**
     * Minimums Autopilot has held at 112% over two days, under offers paying well above them (growth is due), then a
     * hand accept scoring past 150% this dash: the growth still comes first (0.6.21: it follows what offers paid, so a
     * bar the floor holds up no longer stops it; before, nothing grew while the floor bound), and at the next commit the
     * accept holds the bar at its score under the grown minimums.
     */
    @Test public void theMinimumsGrowWhileTheFloorHoldsTheBarAndTheAcceptHoldsItAfter() {
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
        assertEquals(AutopilotRuntime.Commit.GREW, AutopilotRuntime.commitIfDue(app));
        assertArrayEquals("10%: every offer paid 50% or more above them", new int[] {220, 110, 28, 0, 0, 0},
                FilterStore.load(app).minimums());
        assertEquals("the bar comes down by the same share", 102, bar());
        assertNotNull(AutopilotStore.lastGrowth(app));
        int score = AreaScore.scorePercent(FilterStore.load(app), rich);
        assertTrue("under the grown minimums it scores less, below 150%: " + score, score < 150);
        assertEquals(score, AutopilotRuntime.latest().floor);
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        assertEquals(score, bar());
        assertEquals("commit 102% -> " + score + "% (you accepted an offer at this score)", lastCommit());
    }
}

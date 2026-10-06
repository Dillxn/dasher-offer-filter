package com.local.dasherfilter;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Autopilot's runtime (finalSpec.autopilot.updateTiming and arGuard): nothing before the notice is accepted or while
 * Autopilot is off; a plan is published and the commit requester asked; stale plans are discarded (other rules, 15
 * minutes, Clear history) and never write their state back; a commit is one limited step by compare-and-set; a user's
 * change jumps past the limits; Dasher's decline question keeps one reading, marks the offer's own line and logs no
 * text. Plans and readings run on the calling thread here ({@code executorForTests}); the clocks are the test's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class AutopilotRuntimeTest {
    private static final long MIN = 60_000L;
    private static final String QUESTION = "Are you sure you want to decline this offer?";

    /** The worked example's 20 offers, newest first: pay in cents, miles, minutes; two stops each. */
    private static final int[] PAY = {1625, 975, 700, 1350, 575, 800, 2250, 600, 1100, 350, 925, 1490, 400, 1050,
            725, 1700, 875, 500, 1225, 650};
    private static final double[] MILES = {6.2, 11.8, 2.2, 7.0, 6.6, 3.0, 12.6, 7.9, 5.1, 4.8, 6.3, 9.9, 2.6, 4.4,
            5.5, 8.8, 7.4, 3.2, 6.0, 4.1};
    private static final int[] MINUTES = {26, 41, 14, 28, 27, 16, 44, 29, 23, 19, 25, 38, 15, 20, 24, 33, 31, 18, 26,
            22};

    private Application app;
    private long wall;
    private long elapsed;
    private int requests;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        // The wall clock starts at the real one: DecisionLog.markStep looks for lines by the real clock.
        wall = System.currentTimeMillis();
        elapsed = 50_000_000L;
        AutopilotRuntime.wallClock = () -> wall;
        AutopilotRuntime.elapsedClock = () -> elapsed;
        AutopilotRuntime.executorForTests = Runnable::run;
        AutopilotRuntime.afterInputsForTests = null;
        AutopilotRuntime.beforeWriteForTests = null;
        AutopilotRuntime.forgetCache();
        QualifyingWaitStore.wallClock = () -> wall;
        QualifyingWaitStore.forgetCache();
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        DiagnosticLog.clear(app);
        AutopilotStore.clear(app);
        Dashing.forgetCache();
        requests = 0;
        AutopilotRuntime.setCommitRequester(() -> requests++);
    }

    @After
    public void tearDown() {
        AutopilotRuntime.forgetCache();
        AutopilotRuntime.executorForTests = null;
        AutopilotRuntime.afterInputsForTests = null;
        AutopilotRuntime.beforeWriteForTests = null;
        AutopilotRuntime.wallClock = System::currentTimeMillis;
        AutopilotRuntime.elapsedClock = SystemClock::elapsedRealtime;
        QualifyingWaitStore.wallClock = System::currentTimeMillis;
        QualifyingWaitStore.forgetCache();
        DecisionLog.forgetCache();
    }

    // ---- Fixtures ----

    /** The user's rules: auto-decline on, these minimums, no max stops. */
    private void minimums(int flat, int mile, int minute) {
        FilterStore.save(app, FilterSettings.of(true, flat, mile, minute, 0));
    }

    /** The worked example's first {@code count} offers in the history, decided by the saved rules. */
    private void history(int count) {
        FilterSettings rules = FilterStore.load(app);
        for (int i = count - 1; i >= 0; i--) {
            line(new OfferSnapshot(PAY[i], MILES[i], MINUTES[i], 2), wall - (2 + 3L * i) * MIN, rules);
        }
    }

    /** A screen line for {@code facts} at {@code at}: declined (its confirmation tapped), passed or left to review. */
    private void line(OfferSnapshot facts, long at, FilterSettings rules) {
        DecisionLog.record(app, entry(facts, at, OfferRule.evaluate(facts, rules)));
    }

    private static DecisionLog.Entry entry(OfferSnapshot facts, long at, OfferRule.Decision decision) {
        DecisionLog.Action action = decision.result == OfferRule.Result.DECLINE
                ? DecisionLog.Action.CONFIRMATION_TAPPED
                : decision.result == OfferRule.Result.KEEP ? DecisionLog.Action.PASSES
                : DecisionLog.Action.NEEDS_REVIEW;
        return new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false, facts, decision.requiredCents,
                decision.result, decision.reason, action, true, Collections.<String>emptyList());
    }

    /** Dasher's question showed {@code percent} that many minutes ago. */
    private void reading(int percent, long minutesAgo) {
        assertTrue(AutopilotStore.recordReading(app, percent, "", wall - minutesAgo * MIN));
    }

    /** Busy watched waiting (17.1 offers an hour): nine arrivals in the last 38 minutes and a 2-minute open wait. */
    private void busyWaits() {
        long[][] waits = {{38, 2}, {34, 3}, {29, 2}, {25, 3}, {19, 2}, {15, 3}, {11, 2}, {7, 3}, {3, 2}};
        List<QualifyingWait.Sample> samples = new ArrayList<>();
        for (int i = 0; i < waits.length; i++) {
            samples.add(new QualifyingWait.Sample(wall - waits[i][0] * MIN, waits[i][1] * MIN,
                    new OfferSnapshot(700 + 50 * i, 5.0, 22, 2)));
        }
        samples.add(new QualifyingWait.Sample(wall, 2 * MIN, null));
        // QualifyingWaitStore's own file: what it would have written while the user waited with Dasher on screen.
        app.getSharedPreferences("qualifying-wait", Context.MODE_PRIVATE).edit()
                .putString("numeric-history-v1", QualifyingWaitStore.encode(samples)).commit();
        QualifyingWaitStore.forgetCache();
        assertEquals(10, QualifyingWaitStore.snapshot(app).size());
    }

    private int bar() {
        return FilterStore.load(app).minimumScalePercent;
    }

    /** The "autopilot" log lines, oldest first, without their time and category. */
    private List<String> autopilotLog() {
        List<String> lines = new ArrayList<>();
        for (String line : DiagnosticLog.read(app).split("\n")) {
            int at = line.indexOf("[autopilot] ");
            if (at >= 0) lines.add(line.substring(at + "[autopilot] ".length()));
        }
        return lines;
    }

    private int logged(String prefix) {
        int count = 0;
        for (String line : autopilotLog()) if (line.startsWith(prefix)) count++;
        return count;
    }

    private static String last(List<String> lines) {
        return lines.isEmpty() ? null : lines.get(lines.size() - 1);
    }

    private static void idleMain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private int exemptMarks(OfferSnapshot facts) {
        int marks = 0;
        for (DecisionLog.Entry entry : DecisionLog.recent(app, DecisionLog.MAX_ENTRIES)) {
            if (!entry.facts.fingerprint().equals(facts.fingerprint())) continue;
            for (DecisionLog.Step step : entry.steps) if (step.kind == DecisionLog.StepKind.AR_EXEMPT) marks++;
        }
        return marks;
    }

    // ---- Consent and the switch ----

    @Test
    public void noPlanBeforeTheNoticeIsAcceptedOrWhileAutopilotIsOff() {
        minimums(2040, 400, 48);
        history(20);
        reading(9, 1);
        ConsentedTestApp.forget(app);
        FilterStore.setAutopilot(app, true, 70);
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.USER);
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.CONNECT);
        assertNull("before the notice is accepted", AutopilotRuntime.latest());
        assertEquals(AutopilotRuntime.Commit.NO_PLAN, AutopilotRuntime.commitIfDue(app));
        AutopilotRuntime.confirmationSeen(app, Arrays.asList(QUESTION, "Your acceptance rate", "40%"), null);
        assertEquals("no reading is kept before the notice is accepted", 9,
                AutopilotStore.reading(app, wall).percent);

        ConsentedTestApp.accept(app);
        FilterStore.setAutopilot(app, false, 70);
        for (AutopilotRuntime.Trigger trigger : AutopilotRuntime.Trigger.values()) {
            AutopilotRuntime.requestPlan(app, trigger);
        }
        assertNull("while Autopilot is off", AutopilotRuntime.latest());
        assertEquals(AutopilotRuntime.Commit.NO_PLAN, AutopilotRuntime.commitIfDue(app));
        assertFalse(AutopilotRuntime.commitWanted(app));
        assertEquals(0, requests);
        assertTrue(autopilotLog().isEmpty());
        assertEquals(100, bar());

        FilterStore.setAutopilot(app, true, 70);
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.USER);
        assertNotNull(AutopilotRuntime.latest());
    }

    @Test
    public void turningOnPublishesAPlanAsksForACommitAndJumpsAtTheNextSafePoint() {
        minimums(2040, 400, 48);
        history(20);
        reading(9, 1);
        int[] told = {0};
        AutopilotRuntime.Listener listener = () -> told[0]++;
        AutopilotRuntime.listen(listener);

        AutopilotRuntime.setAutopilot(app, true, 70);

        Autopilot.Plan plan = AutopilotRuntime.latest();
        assertNotNull(plan);
        assertEquals(Autopilot.Mode.PINNED, plan.mode);
        assertEquals(50, plan.target);
        assertEquals(100, plan.current);
        assertEquals(AutopilotRuntime.generation(), plan.generation);
        assertEquals(wall, plan.computedAt);
        assertEquals(FilterStore.load(app).rulesKey(), plan.rulesKey);
        assertEquals("the pending user change asks for a commit", 1, requests);
        assertTrue(AutopilotRuntime.commitWanted(app));
        assertEquals("TURNED_ON", AutopilotStore.jump(app));
        assertTrue("the state the plan changed is kept", AutopilotStore.recovering(app));
        assertEquals("nothing moves until a commit", 100, bar());
        idleMain();
        assertTrue("the homepage is told", told[0] >= 1);
        assertEquals(Arrays.asList("on; goal 70%", "plan 50% PINNED: need 80%, pass 4/20 at 50%, share bar 50%, "
                + "ar 9% dasher (1 min ago, 0 since), recovering yes, extra 0, lambda 6.0/h, mix 20"), autopilotLog());

        // The next safe point jumps straight there: past the 20-point step and within the process's first minute.
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        assertEquals(50, bar());
        AutopilotStore.Change change = AutopilotStore.lastChange(app);
        assertEquals(100, change.from);
        assertEquals(50, change.to);
        assertEquals("TURNED_ON", change.why);
        assertEquals(wall, change.at);
        assertNull("the jump is used up", AutopilotStore.jump(app));
        assertEquals("commit 100% -> 50% (Autopilot turned on)", last(autopilotLog()));
        assertEquals("a new plan, made at the new bar", 50, AutopilotRuntime.latest().current);
        assertFalse(AutopilotRuntime.commitWanted(app));
        assertEquals(AutopilotRuntime.Commit.HELD, AutopilotRuntime.commitIfDue(app));
        assertEquals(1, requests);
        int before = told[0];
        idleMain();
        assertTrue("told of the commit", told[0] > before);

        AutopilotText.Status status = AutopilotRuntime.status(app, wall + 4 * MIN, true);
        assertEquals("Autopilot 50% (lowest) · AR 9% → 70%: about 61 more accepts", AutopilotText.statusLine(status));
        assertEquals("Last change 4 min ago: 100% → 50% (Autopilot turned on).",
                AutopilotText.detailsLastChange(status));

        AutopilotRuntime.unlisten(listener);
        int quiet = told[0];
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.RESUME);
        idleMain();
        assertEquals("no longer told", quiet, told[0]);
    }

    @Test
    public void theTickPlansOnlyDuringADashWithAutoDeclineOn() {
        minimums(400, 100, 25);
        history(20);
        FilterStore.setAutopilot(app, true, 70);
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.TICK);
        assertNull("no dash seen", AutopilotRuntime.latest());
        Dashing.seen(app);
        Dashing.paused(app);
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.TICK);
        assertNull("the dash is paused", AutopilotRuntime.latest());
        Dashing.seen(app);
        FilterStore.save(app, FilterSettings.of(false, 400, 100, 25, 0));
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.TICK);
        assertNull("auto-decline is paused", AutopilotRuntime.latest());
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 0));
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.TICK);
        assertNotNull(AutopilotRuntime.latest());
    }

    @Test
    public void requestsAreCoalescedUntilThePlanStarts() {
        minimums(400, 100, 25);
        FilterStore.setAutopilot(app, true, 70);
        List<Runnable> queued = new ArrayList<>();
        AutopilotRuntime.executorForTests = queued::add;
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.USER);
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.RESUME);
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.CONNECT);
        assertEquals("one plan for three requests", 1, queued.size());
        assertNull("planning happens on the planner, not the caller", AutopilotRuntime.latest());
        queued.remove(0).run();
        assertNotNull(AutopilotRuntime.latest());
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.RESUME);
        assertEquals("a request after a plan started is a plan of its own", 1, queued.size());
    }

    // ---- Stale plans ----

    @Test
    public void aPlanForOtherRulesOrOlderThanFifteenMinutesIsDiscardedAndReplaced() {
        minimums(400, 100, 25);
        history(20);
        reading(74, 1);
        FilterStore.setAutopilot(app, true, 70);
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.CONNECT);
        assertNotNull(AutopilotRuntime.latest());

        // Other rules, saved without telling Autopilot: the plan is for rules no longer in force.
        FilterStore.save(app, FilterSettings.of(true, 500, 100, 25, 0));
        assertFalse("a plan for other rules wants nothing", AutopilotRuntime.commitWanted(app));
        assertEquals(AutopilotRuntime.Commit.DISCARDED, AutopilotRuntime.commitIfDue(app));
        assertEquals(1, logged("plan discarded: rules changed"));
        Autopilot.Plan replaced = AutopilotRuntime.latest();
        assertNotNull("a new plan is asked for at once", replaced);
        assertEquals(FilterStore.load(app).rulesKey(), replaced.rulesKey);
        assertEquals(AutopilotRuntime.Commit.HELD, AutopilotRuntime.commitIfDue(app));

        // Fifteen minutes and a millisecond later it is too old to commit from.
        wall += Autopilot.PLAN_MAX_AGE_MS + 1;
        assertEquals(AutopilotRuntime.Commit.DISCARDED, AutopilotRuntime.commitIfDue(app));
        assertEquals(1, logged("plan discarded: older than 15 min"));
        assertEquals(wall, AutopilotRuntime.latest().computedAt);
        assertEquals("exactly fifteen minutes is still fresh", AutopilotRuntime.Commit.HELD,
                commitAt(wall + Autopilot.PLAN_MAX_AGE_MS));
        assertEquals(100, bar());
    }

    private AutopilotRuntime.Commit commitAt(long when) {
        long now = wall;
        wall = when;
        try {
            return AutopilotRuntime.commitIfDue(app);
        } finally {
            wall = now;
        }
    }

    @Test
    public void aPlanWorkedOutAcrossClearHistoryIsDroppedAndTheBarGoesBackToOneHundred() {
        minimums(2040, 400, 48);
        history(20);
        reading(9, 1);
        AutopilotRuntime.setAutopilot(app, true, 70);
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        assertEquals(50, bar());
        long generation = AutopilotRuntime.generation();

        // Clear history lands while a plan is being worked out from the history as it was.
        AutopilotRuntime.afterInputsForTests = () -> {
            AutopilotRuntime.afterInputsForTests = null;
            DecisionLog.clear(app);
            AutopilotRuntime.cleared(app);
        };
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.RESUME);

        assertEquals(generation + 1, AutopilotRuntime.generation());
        assertEquals(1, logged("plan discarded: history cleared"));
        Autopilot.Plan plan = AutopilotRuntime.latest();
        assertEquals("the plan made after Clear history stands", generation + 1, plan.generation);
        assertEquals(Autopilot.Mode.LEARNING, plan.mode);
        assertEquals(100, plan.target);
        assertNull("the reading went with the history", AutopilotStore.reading(app, wall));
        assertNull("so did the change note", AutopilotStore.lastChange(app));
        assertFalse("and the acceptance-rate state, which the old plan did not write back",
                AutopilotStore.recovering(app));
        assertEquals("CLEARED", AutopilotStore.jump(app));

        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        assertEquals(100, bar());
        assertEquals("commit 50% -> 100% (history cleared)", last(autopilotLog()));
        assertEquals("Autopilot 100% · learning (0 of 20 offers)",
                AutopilotText.statusLine(AutopilotRuntime.status(app, wall, true)));
    }

    @Test
    public void aPlanForTheOldGoalNeverWritesItsStateOverTheNewGoals() {
        minimums(400, 100, 25);
        history(20);
        reading(9, 1);
        AutopilotRuntime.setAutopilot(app, true, 70);
        assertTrue(AutopilotStore.recovering(app));

        // The user chooses pay first while a plan for 70% is being worked out.
        AutopilotRuntime.afterInputsForTests = () -> {
            AutopilotRuntime.afterInputsForTests = null;
            AutopilotRuntime.setAutopilot(app, true, 0);
        };
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.RESUME);

        assertEquals(1, logged("plan discarded: rules changed"));
        assertEquals(0, AutopilotRuntime.latest().goal);
        assertFalse("pay first is never recovering, and the 70% plan did not write that it was",
                AutopilotStore.recovering(app));
        assertEquals("GOAL_CHANGED", AutopilotStore.jump(app));
        assertTrue(autopilotLog().contains("pay first (was 70%)"));
    }

    // ---- Commits ----

    @Test
    public void withoutAJumpEachCommitIsOneLimitedStepAMinuteApart() {
        minimums(2040, 400, 48);
        history(20);
        reading(9, 1);
        // On before this process started: no change pending.
        FilterStore.setAutopilot(app, true, 70);
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.CONNECT);
        assertEquals(50, AutopilotRuntime.latest().target);
        assertEquals("within the process's first minute nothing is due, so nothing is asked", 0, requests);
        assertFalse(AutopilotRuntime.commitWanted(app));
        assertEquals(AutopilotRuntime.Commit.HELD, AutopilotRuntime.commitIfDue(app));
        assertEquals(100, bar());

        elapsed += Autopilot.MIN_COMMIT_SPACING_MS;
        assertTrue(AutopilotRuntime.commitWanted(app));
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        assertEquals("at most 20 points down", 80, bar());
        assertEquals("commit 100% -> 80% (your minimums are high for these offers)", last(autopilotLog()));
        elapsed += Autopilot.MIN_COMMIT_SPACING_MS - 1;
        assertEquals(AutopilotRuntime.Commit.HELD, AutopilotRuntime.commitIfDue(app));
        assertEquals(80, bar());
        elapsed += 1;
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        assertEquals(60, bar());
        elapsed += Autopilot.MIN_COMMIT_SPACING_MS;
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        assertEquals("the last step stops at the target", 50, bar());
        elapsed += Autopilot.MIN_COMMIT_SPACING_MS;
        assertEquals(AutopilotRuntime.Commit.HELD, AutopilotRuntime.commitIfDue(app));
        assertEquals(50, bar());
        assertNull(AutopilotStore.jump(app));
        AutopilotStore.Change change = AutopilotStore.lastChange(app);
        assertEquals(60, change.from);
        assertEquals(50, change.to);
        assertEquals("PINNED", change.why);
        assertEquals(0, AutopilotStore.raisedAt(app));
        assertEquals(3, logged("commit "));
    }

    @Test
    public void raisesAreFivePointsAtLeastFiveMinutesAndThreeOffersApart() {
        minimums(400, 100, 25);
        history(20);
        busyWaits();
        FilterStore.setAutopilot(app, true, 0);
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.CONNECT);
        Autopilot.Plan plan = AutopilotRuntime.latest();
        assertEquals(Autopilot.Mode.VALUE, plan.mode);
        assertEquals(119, plan.target);

        elapsed += Autopilot.MIN_COMMIT_SPACING_MS;
        assertEquals("five minutes from process start before a raise", AutopilotRuntime.Commit.HELD,
                AutopilotRuntime.commitIfDue(app));
        elapsed += Autopilot.MIN_RAISE_SPACING_MS - Autopilot.MIN_COMMIT_SPACING_MS;
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        assertEquals("at most 5 points up", 105, bar());
        assertEquals("commit 100% -> 105% (offers are coming often)", last(autopilotLog()));
        assertEquals(wall, AutopilotStore.raisedAt(app));

        elapsed += Autopilot.MIN_RAISE_SPACING_MS;
        assertEquals("five more minutes, but no offer since the raise", AutopilotRuntime.Commit.HELD,
                AutopilotRuntime.commitIfDue(app));
        assertEquals(105, bar());
        FilterSettings rules = FilterStore.load(app);
        for (int k = 1; k <= 3; k++) line(new OfferSnapshot(2000 + k, 5.0 + k, 22, 2), wall + k * 1_000L, rules);
        wall += 5_000;
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.CONNECT);
        assertEquals(3, AutopilotRuntime.latest().offersSinceRaise);
        assertTrue(AutopilotRuntime.latest().target >= 110);
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        assertEquals(110, bar());
    }

    @Test
    public void aCommitIsCompareAndSetAndNeverWritesOverAnotherBar() {
        minimums(2040, 400, 48);
        history(20);
        reading(9, 1);
        FilterStore.setAutopilot(app, true, 70);
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.CONNECT);
        elapsed += Autopilot.MIN_COMMIT_SPACING_MS;

        // Something else moves the bar between the commit's check and its write.
        AutopilotRuntime.beforeWriteForTests = () -> assertTrue(FilterStore.commitAutopilotBar(app, 100, 90));
        assertEquals(AutopilotRuntime.Commit.CONFLICT, AutopilotRuntime.commitIfDue(app));
        assertEquals("the other write stands", 90, bar());
        assertNull("no change is recorded", AutopilotStore.lastChange(app));
        assertEquals(0, logged("commit "));
        assertEquals("a new plan is made at the bar as it is", 90, AutopilotRuntime.latest().current);

        // A bar moved since the plan was made is never written over from that plan either.
        AutopilotRuntime.beforeWriteForTests = null;
        assertTrue(FilterStore.commitAutopilotBar(app, 90, 95));
        assertEquals(AutopilotRuntime.Commit.DISCARDED, AutopilotRuntime.commitIfDue(app));
        assertEquals(95, bar());
        assertEquals(95, AutopilotRuntime.latest().current);
        assertEquals("that is no news: not logged", 0, logged("plan discarded"));

        // Turned off by the user: the bar is 100 at once and no plan writes anything.
        AutopilotRuntime.setAutopilot(app, false, 70);
        assertEquals(100, bar());
        assertEquals(AutopilotRuntime.Commit.NO_PLAN, AutopilotRuntime.commitIfDue(app));
        assertEquals(100, bar());
    }

    @Test
    public void theUsersChangesJumpPastTheLimits() {
        minimums(2040, 400, 48);
        history(20);
        reading(9, 1);
        AutopilotRuntime.setAutopilot(app, true, 70);
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        assertEquals(50, bar());

        // "Use typical minimums" moments later. Under the cautious 6-an-hour prior 83–100% are within 2% of the best
        // value (V(86–100) = 1,041,900/603 ¢/h; V(83–85) = 1,076,400/630 is within it, V(76–82) is not), and 83% is
        // the closest of them to 50%: one commit goes there at once, a raise of 33 within the minute.
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 0));
        AutopilotRuntime.rulesChanged(app);
        assertEquals("RULES_CHANGED", AutopilotStore.jump(app));
        Autopilot.Plan typical = AutopilotRuntime.latest();
        assertEquals(83, typical.acceptableLow);
        assertEquals(100, typical.acceptableHigh);
        assertEquals(83, typical.target);
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        assertEquals(83, bar());
        assertEquals("commit 50% -> 83% (you changed your minimums)", last(autopilotLog()));
        assertTrue(AutopilotStore.recovering(app));

        // Another goal forgets the goal-relative state, then plans for the new goal; its jump needs no move here.
        AutopilotStore.setCorrection(app, 4, wall - MIN, 9);
        AutopilotRuntime.setAutopilot(app, true, 50);
        assertTrue(autopilotLog().contains("goal 50% (was 70%)"));
        assertEquals(0, AutopilotStore.extra(app));
        assertEquals(0, AutopilotStore.checkpointAt(app));
        assertEquals(50, AutopilotRuntime.latest().goal);
        assertTrue("9% is below 50% too", AutopilotStore.recovering(app));
        assertEquals("GOAL_CHANGED", AutopilotStore.jump(app));
        assertEquals(AutopilotRuntime.Commit.HELD, AutopilotRuntime.commitIfDue(app));
        assertNull("a change that needs no move is done with", AutopilotStore.jump(app));

        // Turning off: the bar is exactly 100 at once, no plan, nothing pending, the goal state forgotten.
        AutopilotRuntime.setAutopilot(app, false, 50);
        assertNull(AutopilotRuntime.latest());
        assertNull(AutopilotStore.jump(app));
        assertFalse(AutopilotStore.recovering(app));
        assertEquals(100, bar());
        assertEquals("off; bar back to 100%", last(autopilotLog()));
        // With Autopilot off, changing a minimum is no jump.
        AutopilotRuntime.rulesChanged(app);
        assertNull(AutopilotStore.jump(app));
    }

    @Test
    public void aDeferredCommitIsLoggedAtMostOnceAMinute() {
        AutopilotRuntime.commitDeferred(app);
        AutopilotRuntime.commitDeferred(app);
        elapsed += 59_999;
        AutopilotRuntime.commitDeferred(app);
        assertEquals(1, logged("commit deferred: offer or decline in flight"));
        elapsed += 1;
        AutopilotRuntime.commitDeferred(app);
        assertEquals(2, logged("commit deferred: offer or decline in flight"));
    }

    // ---- Logging ----

    @Test
    public void anUnchangedPlanIsLoggedAtMostEveryTenMinutes() {
        minimums(400, 100, 25);
        history(20);
        reading(74, 1);
        FilterStore.setAutopilot(app, true, 70);
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.RESUME);
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.RESUME);
        assertEquals(1, logged("plan "));
        elapsed += AutopilotRuntime.PLAN_LOG_EVERY_MS - 1;
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.RESUME);
        assertEquals(1, logged("plan "));
        elapsed += 1;
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.RESUME);
        assertEquals(2, logged("plan "));
        // Higher minimums: the goal's limit binds below 100, a new target, logged at once.
        FilterStore.save(app, FilterSettings.of(true, 500, 125, 35, 0));
        AutopilotRuntime.rulesChanged(app);
        Autopilot.Plan plan = AutopilotRuntime.latest();
        assertEquals(Autopilot.Mode.GOAL, plan.mode);
        assertEquals(79, plan.target);
        assertEquals(3, logged("plan "));
        assertTrue(last(autopilotLog()).startsWith("plan 79% GOAL: need 75%, pass 15/20 at 79%, share bar 79%, ar 74% "
                + "dasher (1 min ago, 0 since), "));
    }

    @Test
    public void theExemptionValveIsLoggedAtMostOnceADay() {
        FilterSettings rules = FilterSettings.of(true, 2040, 400, 48, 0);
        FilterStore.save(app, rules);
        // Twenty declined lines, twelve of them marked free to decline: the mark reads as Dasher's general wording.
        for (int i = 19; i >= 0; i--) {
            OfferSnapshot facts = new OfferSnapshot(PAY[i], MILES[i], MINUTES[i], 2);
            DecisionLog.Entry line = entry(facts, wall - (2 + 3L * i) * MIN, OfferRule.evaluate(facts, rules));
            if (i < 12) line = line.withStep(new DecisionLog.Step(DecisionLog.StepKind.AR_EXEMPT, line.at, ""));
            DecisionLog.record(app, line);
        }
        FilterStore.setAutopilot(app, true, 70);
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.RESUME);
        assertTrue(AutopilotRuntime.latest().exemptionsIgnored);
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.RESUME);
        assertEquals(Arrays.asList("ar exemptions ignored: 12 of 20 declines flagged"),
                linesStartingWith("ar exemptions"));
        wall += AutopilotRuntime.EXEMPT_LOG_EVERY_MS;
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.RESUME);
        assertEquals(2, logged("ar exemptions ignored: 12 of 20 declines flagged"));
    }

    private List<String> linesStartingWith(String prefix) {
        List<String> lines = new ArrayList<>();
        for (String line : autopilotLog()) if (line.startsWith(prefix)) lines.add(line);
        return lines;
    }

    @Test
    public void aFailingPlanIsLoggedByItsClassAndChangesNothing() {
        minimums(400, 100, 25);
        history(20);
        FilterStore.setAutopilot(app, true, 70);
        AutopilotRuntime.afterInputsForTests = () -> {
            throw new IllegalStateException("Deliver to Sam Smith, 12 Elm St");
        };
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.RESUME);
        assertNull(AutopilotRuntime.latest());
        assertEquals(Arrays.asList("plan failed: IllegalStateException"), autopilotLog());
        assertFalse(DiagnosticLog.read(app).contains("Sam"));
        assertEquals(0, requests);
        AutopilotRuntime.afterInputsForTests = null;
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.RESUME);
        assertNotNull("the next request plans again", AutopilotRuntime.latest());
    }

    // ---- Dasher's acceptance rate ----

    @Test
    public void aDeclineQuestionKeepsOneReadingMarksItsOwnLineAndLogsNoText() {
        FilterSettings rules = FilterSettings.of(true, 400, 100, 25, 0);
        FilterStore.save(app, rules);
        long now = System.currentTimeMillis();
        OfferSnapshot declined = new OfferSnapshot(575, 6.6, 27, 2);
        OfferSnapshot newer = new OfferSnapshot(925, 6.3, 25, 2);
        line(declined, now - 20_000, rules);
        line(newer, now - 5_000, rules);
        List<String> labels = Arrays.asList(QUESTION, "Does not lower acceptance rate", "9%", "Maintaining a hi…",
                "Sam's order", "Decline", "$5.75");

        // Read on the planner's thread: nothing is parsed or kept on the caller's.
        List<Runnable> queued = new ArrayList<>();
        AutopilotRuntime.executorForTests = queued::add;
        AutopilotRuntime.confirmationSeen(app, labels, declined);
        assertEquals(1, queued.size());
        assertNull(AutopilotStore.reading(app, wall));
        AutopilotRuntime.executorForTests = Runnable::run;
        queued.remove(0).run();

        AutopilotStore.Reading reading = AutopilotStore.reading(app, wall);
        assertEquals(9, reading.percent);
        assertEquals(declined.fingerprint(), reading.fingerprint);
        assertEquals(0, exemptMarks(declined));
        idleMain();
        assertEquals("the mark goes on the declined offer's line, on the main thread", 1, exemptMarks(declined));
        assertEquals("never on another line", 0, exemptMarks(newer));
        DecisionLog.Entry marked = DecisionLog.recent(app, 2).get(1);
        assertEquals(declined.fingerprint(), marked.facts.fingerprint());
        assertTrue(DecisionLog.hasStep(marked, DecisionLog.StepKind.AR_EXEMPT));
        assertEquals("a mark changes no outcome", DecisionLog.Outcome.DECLINED, DecisionLog.outcome(marked));

        // Dasher's question is polled again and again: still one reading, one log line, one mark.
        for (int i = 0; i < 5; i++) {
            elapsed += 100;
            wall += 100;
            AutopilotRuntime.confirmationSeen(app, labels, declined);
        }
        idleMain();
        assertEquals("only when it was last seen moved", wall, AutopilotStore.reading(app, wall).at);
        assertEquals(1, exemptMarks(declined));
        assertEquals(Arrays.asList("ar reading 9% (exempt yes)"), autopilotLog());

        // Another offer's question with two percents: no reading kept, logged once as none.
        List<String> twoPercents = Arrays.asList(QUESTION, "Your acceptance rate", "50%", "9%");
        AutopilotRuntime.confirmationSeen(app, twoPercents, newer);
        AutopilotRuntime.confirmationSeen(app, twoPercents, newer);
        assertEquals(9, AutopilotStore.reading(app, wall).percent);
        assertEquals(Arrays.asList("ar reading 9% (exempt yes)", "ar reading none (exempt no)"), autopilotLog());

        // A question whose offer is not known keeps the reading and marks no line.
        AutopilotRuntime.confirmationSeen(app, Arrays.asList(QUESTION, "Does not lower acceptance rate", "12%"), null);
        idleMain();
        assertEquals(12, AutopilotStore.reading(app, wall).percent);
        assertEquals("", AutopilotStore.reading(app, wall).fingerprint);
        assertEquals(0, exemptMarks(newer));

        // Not a decline question, or an account screen: nothing at all.
        AutopilotRuntime.confirmationSeen(app, Arrays.asList("Does not lower acceptance rate", "40%"), newer);
        AutopilotRuntime.confirmationSeen(app, Arrays.asList(QUESTION, "Available balance", "Your acceptance rate",
                "40%"), newer);
        assertEquals(12, AutopilotStore.reading(app, wall).percent);
        assertEquals(3, autopilotLog().size());

        String log = DiagnosticLog.read(app);
        for (String text : new String[] {"Are you sure", "Does not lower", "Maintaining", "Sam", "$5.75",
                "Available balance", "Your acceptance rate"}) {
            assertFalse("no label text in the log: " + text, log.contains(text));
        }
    }

    @Test
    public void aNewReadingReplansWhileAutopilotIsOn() {
        minimums(400, 100, 25);
        history(20);
        reading(55, 1);
        AutopilotRuntime.setAutopilot(app, true, 70);
        assertEquals(5_500, AutopilotRuntime.latest().arHundredths);
        assertEquals(Autopilot.Mode.RECOVERY, AutopilotRuntime.latest().mode);
        AutopilotRuntime.confirmationSeen(app, Arrays.asList(QUESTION, "Your acceptance rate", "74%"),
                new OfferSnapshot(PAY[0], MILES[0], MINUTES[0], 2));
        assertEquals(7_400, AutopilotRuntime.latest().arHundredths);
        assertEquals(Autopilot.Mode.VALUE, AutopilotRuntime.latest().mode);
        assertEquals(1, logged("ar reading 74% (exempt no)"));
    }

    @Test
    public void aQuestionSeenBeforeClearHistoryIsNotKeptAfterIt() {
        List<Runnable> queued = new ArrayList<>();
        AutopilotRuntime.executorForTests = queued::add;
        AutopilotRuntime.confirmationSeen(app, Arrays.asList(QUESTION, "Your acceptance rate", "40%"), null);
        AutopilotRuntime.cleared(app);
        assertNull("Autopilot is off: no jump to return to 100", AutopilotStore.jump(app));
        for (Runnable task : new ArrayList<>(queued)) task.run();
        assertNull(AutopilotStore.reading(app, wall));
        assertTrue(autopilotLog().isEmpty());
    }

    // ---- Status and the history as Autopilot counts it ----

    @Test
    public void theStatusAssemblesTheRulesThePlanTheReadingAndTheLastChange() {
        minimums(400, 100, 25);
        history(20);
        reading(55, 12);
        AutopilotText.Status off = AutopilotRuntime.status(app, wall, true);
        assertEquals(AutopilotText.Status.Kind.OFF, off.kind);
        assertEquals("Acceptance rate: 55%, shown by Dasher 12 min ago.", AutopilotText.detailsAcceptanceRate(off));

        FilterStore.setAutopilot(app, true, 70);
        assertEquals("Autopilot 100% · checking your offers",
                AutopilotText.statusLine(AutopilotRuntime.status(app, wall, true)));
        assertEquals("Autopilot waits for screen reading",
                AutopilotText.statusLine(AutopilotRuntime.status(app, wall, false)));
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.RESUME);
        AutopilotText.Status s = AutopilotRuntime.status(app, wall, true);
        // Four offers came after Dasher's 55% and none was accepted: 5,500 − 4 × 55 = 5,280.
        assertEquals(5_280, s.arHundredths);
        assertEquals("Autopilot 100% · AR 52% → 70%: about 18 more accepts", AutopilotText.statusLine(s));
        assertEquals("Acceptance rate: about 52% (Dasher showed 55% 12 min ago; 4 offers since).",
                AutopilotText.detailsAcceptanceRate(s));
        assertTrue(s.recovering);
        assertTrue(AutopilotText.reportLine(s).startsWith("Autopilot: on; goal 70%; bar 100%; mode RECOVERY; "
                + "recovering yes; extra 0; AR 52% (Dasher, 12 min ago, 4 offers since)"));
    }

    @Test
    public void historyLinesBecomeOfferRecords() {
        // $9.25 for 6.3 mi and 25 min passes the typical minimums ($6.30 asked).
        OfferSnapshot facts = new OfferSnapshot(925, 6.3, 25, 2);
        FilterSettings rules = FilterSettings.of(true, 400, 100, 25, 0);
        long at = wall - MIN;
        DecisionLog.Entry kept = entry(facts, at, OfferRule.evaluate(facts, rules));
        Autopilot.OfferRecord plain = AutopilotRuntime.record(kept);
        assertSame(facts, plain.facts);
        assertEquals(at, plain.at);
        assertFalse(plain.addOn || plain.replay || plain.accepted || plain.declined || plain.arExempt);

        assertTrue("accepted", AutopilotRuntime.record(step(kept, DecisionLog.StepKind.ACCEPTED)).countsAccepted());
        assertTrue("accepted after an automatic request",
                AutopilotRuntime.record(step(kept, DecisionLog.StepKind.ACCEPTED_AUTOMATIC)).countsAccepted());
        assertTrue("not accepted", AutopilotRuntime.record(step(kept, DecisionLog.StepKind.NOT_ACCEPTED)).declined);
        assertTrue("counted as the user's decline",
                AutopilotRuntime.record(step(kept, DecisionLog.StepKind.DECLINE_COUNTED)).declined);
        assertTrue("marked free to decline",
                AutopilotRuntime.record(step(kept, DecisionLog.StepKind.AR_EXEMPT)).arExempt);
        Autopilot.OfferRecord both = AutopilotRuntime.record(step(step(kept, DecisionLog.StepKind.DECLINE_COUNTED),
                DecisionLog.StepKind.ACCEPTED));
        assertTrue("an acceptance wins", both.countsAccepted() && !both.countsDeclined());

        OfferRule.Decision fails = OfferRule.evaluate(new OfferSnapshot(350, 4.8, 19, 2), rules);
        assertEquals(OfferRule.Result.DECLINE, fails.result);
        assertTrue("the app's decline went through",
                AutopilotRuntime.record(entry(facts, at, fails)).declined);
        assertFalse("the user took over: not the app's decline", AutopilotRuntime.record(new DecisionLog.Entry(at,
                DecisionLog.Source.SCREEN, false, facts, fails.requiredCents, fails.result, fails.reason,
                DecisionLog.Action.USER_TOOK_OVER, true, Collections.<String>emptyList())).declined);
        assertTrue("a hidden notification of a failing offer", AutopilotRuntime.record(new DecisionLog.Entry(at,
                DecisionLog.Source.NOTIFICATION, false, facts, fails.requiredCents, fails.result, fails.reason,
                DecisionLog.Action.NOTIFICATION_HIDDEN, true, Collections.<String>emptyList())).declined);
        assertTrue("a replay", AutopilotRuntime.record(new DecisionLog.Entry(at, DecisionLog.Source.NOTIFICATION,
                false, facts, 0, OfferRule.Result.KEEP, "", DecisionLog.Action.REPLAY, true,
                Collections.<String>emptyList())).replay);
        assertTrue("an add-on", AutopilotRuntime.record(new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, true,
                facts, 0, OfferRule.Result.KEEP, "", DecisionLog.Action.PASSES, true,
                Collections.<String>emptyList())).addOn);
        assertEquals(2, AutopilotRuntime.records(Arrays.asList(kept, null, kept)).size());
    }

    private static DecisionLog.Entry step(DecisionLog.Entry entry, DecisionLog.StepKind kind) {
        return entry.withStep(new DecisionLog.Step(kind, entry.at + 1_000, ""));
    }

    @Test
    public void theRuntimeNeverReferencesTheService() throws IOException {
        File root = new File("../app").isDirectory() ? new File("..") : new File(".");
        String text = new String(Files.readAllBytes(new File(root,
                "app/src/main/java/com/local/dasherfilter/AutopilotRuntime.java").toPath()), StandardCharsets.UTF_8);
        assertFalse(text.contains("OfferFilterService"));
        assertFalse(text.contains("OfferNotificationService"));
    }
}

package com.local.dasherfilter;

import android.app.Application;
import android.content.Context;
import android.os.SystemClock;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.*;

/** Confirmed prospective choices, persistence and atomic safe-point commits; synthetic Android, not a handset. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public final class AutopilotAxesRuntimeTest {
    private static final OfferSnapshot FIRST = new OfferSnapshot(600, 3.0, 20, 2);
    private Application app;
    private long wall;

    @Before public void setUp() {
        app = RuntimeEnvironment.getApplication();
        wall = System.currentTimeMillis();
        AutopilotRuntime.wallClock = () -> wall;
        AutopilotRuntime.elapsedClock = () -> 50_000_000L;
        AutopilotRuntime.executorForTests = Runnable::run;
        AutopilotRuntime.beforeWriteForTests = null;
        AutopilotRuntime.forgetCache();
        AutopilotRuntime.setCommitRequester(null);
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        DiagnosticLog.clear(app);
        AutopilotStore.clear(app);
        Dashing.forgetCache();
        FilterStore.save(app, FilterSettings.of(true, 400, 150, 40, 0).withAcceptanceLearning(false));
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_PAY_FIRST);
        assertTrue(FilterStore.commitAutopilotBar(app, 100, 82));
    }

    @After public void tearDown() {
        AutopilotRuntime.beforeWriteForTests = null;
        AutopilotRuntime.setCommitRequester(null);
        AutopilotRuntime.forgetCache();
        AutopilotRuntime.executorForTests = null;
        AutopilotRuntime.wallClock = System::currentTimeMillis;
        AutopilotRuntime.elapsedClock = SystemClock::elapsedRealtime;
        FilterStore.wallClock = System::currentTimeMillis;
        DecisionLog.forgetCache();
    }

    private FilterSettings rules() { return FilterStore.load(app); }

    private FilterStore.AutopilotPermit observe(OfferSnapshot offer) {
        return FilterStore.captureAutopilotAcceptance(app, offer, false);
    }

    private void confirm(FilterStore.AutopilotPermit permit, OfferSnapshot offer) {
        wall += 1_000;
        assertEquals(FilterStore.AutopilotAcceptance.QUEUED,
                AutopilotRuntime.confirmedManualAcceptance(app, permit, offer));
    }

    private void accept(OfferSnapshot offer) {
        confirm(observe(offer), offer);
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
    }

    private void asks(int pay, int mile, int minute, OfferSnapshot route) {
        FilterSettings current = rules();
        AreaScore.Floors floors = AreaScore.floors(current, route);
        assertEquals(pay, floors.axisRequiredCents(AreaScore.PAY, current.minimumScalePercent));
        assertEquals(mile, floors.axisRequiredCents(AreaScore.MILE, current.minimumScalePercent));
        assertEquals(minute, floors.axisRequiredCents(AreaScore.MINUTE, current.minimumScalePercent));
    }

    @Test public void observingOrAttemptingAnOfferDoesNotChangeAnyMinimum() {
        assertNotNull(observe(FIRST));
        assertNull(FilterStore.pendingAutopilotAxes(app));
        assertFalse(rules().hasAutopilotAxes());
        assertFalse(AutopilotRuntime.commitWanted(app));
        assertEquals(AutopilotRuntime.Commit.NO_PLAN, AutopilotRuntime.commitIfDue(app));
        assertEquals(82, rules().minimumScalePercent);
    }

    @Test public void confirmedChoiceWaitsForCommitThenSetsMixedRatesExactlyWithoutMovingSavedMinimums() {
        AtomicInteger requests = new AtomicInteger();
        AutopilotRuntime.setCommitRequester(requests::incrementAndGet);
        confirm(observe(FIRST), FIRST);
        assertTrue(requests.get() > 0);
        assertTrue(AutopilotRuntime.commitWanted(app));
        assertTrue(AutopilotRuntime.status(app, wall, true).axesPending);
        assertFalse(rules().hasAutopilotAxes());
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        assertNull(FilterStore.pendingAutopilotAxes(app));
        assertFalse(AutopilotRuntime.status(app, wall, true).axesPending);
        assertEquals(82, rules().minimumScalePercent);
        assertEquals(400, rules().flatCents);
        assertEquals(150, rules().perMileCents);
        assertEquals(40, rules().perMinuteCents);
        assertTrue(rules().accepted.isEmpty());
        asks(600, 600, 600, FIRST);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(FIRST, rules()).result);
        assertEquals(OfferRule.Result.DECLINE,
                OfferRule.evaluate(new OfferSnapshot(599, 3.0, 20, 2), rules()).result);
        assertEquals(Autopilot.Reason.ACCEPTED_AXES.name(), AutopilotStore.lastChange(app).why);
    }

    @Test public void newerAcceptanceCanLowerOneAxisAndRaiseAnotherWithLearnOff() {
        accept(FIRST);
        // Pay/stop: $3 → $2; mile: $2 → $2.40; hour: $18 → $36.
        OfferSnapshot next = new OfferSnapshot(1200, 5.0, 20, 6);
        accept(next);
        asks(400, 720, 1200, FIRST);
        assertFalse(rules().acceptanceLearning);
        assertTrue(rules().accepted.isEmpty());
        assertEquals(wall, rules().autopilotAxes.acceptedAt);
        assertEquals(82, rules().minimumScalePercent);
    }

    @Test public void partialAcceptancesComposeBeforeTheFirstCommitEvenWhenTheSecondAddsLearnedProof() {
        FilterStore.setAcceptanceLearning(app, true);
        // No scanner safe point yet; the first choice is confirmed but not applied.
        confirm(observe(FIRST), FIRST);
        AutopilotAxes.Rate mile = FilterStore.pendingAutopilotAxes(app).axes.rate(AreaScore.MILE);
        OfferSnapshot second = new OfferSnapshot(1000, null, 10, 2);
        FilterStore.AutopilotPermit permit = observe(second);
        FilterStore.LearningPermit lesson = FilterStore.captureLearning(app, second);
        assertEquals(FilterStore.AcceptedLesson.RAISED,
                FilterStore.recordConfirmedManualAcceptance(app, lesson, second));
        confirm(permit, second);
        assertEquals(mile, FilterStore.pendingAutopilotAxes(app).axes.rate(AreaScore.MILE));
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        asks(1000, 600, 2000, FIRST);
        assertEquals(1000, rules().accepted.highestPayCents);
        assertEquals(400, rules().flatCents);
    }

    @Test public void missingAxisKeepsItsCommittedRateAndDisabledAxisStaysDisabled() {
        accept(FIRST);
        AutopilotAxes.Rate mile = rules().autopilotAxes.rate(AreaScore.MILE);
        accept(new OfferSnapshot(900, null, 15, 3));
        assertEquals(mile, rules().autopilotAxes.rate(AreaScore.MILE));
        FilterStore.save(app, FilterSettings.of(true, 400, 0, 40, 0).withAcceptanceLearning(false));
        accept(FIRST);
        assertFalse(rules().autopilotAxes.hasAxis(AreaScore.MILE));
        asks(600, -1, 600, FIRST);
    }

    @Test public void pausingAfterConfirmationDoesNotCancelTheChoice() {
        confirm(observe(FIRST), FIRST);
        FilterSettings before = rules();
        FilterStore.save(app, before, before.withEnabled(false));
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        assertFalse(rules().enabled);
        asks(600, 600, 600, FIRST);
    }

    @Test public void firstPendingChoiceSurvivesOffOnWithItsOriginalNonHundredBar() {
        confirm(observe(FIRST), FIRST);
        String pending = FilterStore.pendingAutopilotAxes(app).axes.encode();
        AutopilotRuntime.setAutopilot(app, false, FilterSettings.GOAL_PAY_FIRST);
        assertEquals(100, rules().minimumScalePercent);
        assertEquals(AutopilotRuntime.Commit.NO_PLAN, AutopilotRuntime.commitIfDue(app));
        assertEquals(pending, FilterStore.pendingAutopilotAxes(app).axes.encode());
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_PAY_FIRST);
        assertEquals(82, rules().minimumScalePercent);
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        asks(600, 600, 600, FIRST);
        assertEquals(pending, rules().autopilotAxes.encode());
    }

    @Test public void committedWorkingShapeResumesAfterSameGoalOffOnButANewGoalClearsIt() {
        accept(FIRST);
        String shape = rules().autopilotAxes.encode();
        FilterStore.setAutopilot(app, false, FilterSettings.GOAL_PAY_FIRST);
        assertFalse(rules().hasAutopilotAxes());
        asks(800, 450, 800, FIRST);
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_PAY_FIRST);
        assertEquals(82, rules().minimumScalePercent);
        assertEquals(shape, rules().autopilotAxes.encode());
        asks(600, 600, 600, FIRST);
        FilterStore.setAutopilot(app, false, FilterSettings.GOAL_PAY_FIRST);
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertTrue(rules().autopilotAxes.isEmpty());
        assertNull(FilterStore.pendingAutopilotAxes(app));
        assertEquals(100, rules().minimumScalePercent);
    }

    @Test public void anOfferFirstObservedWhileOffCannotBecomeAProspectiveChoiceByTurningOn() {
        FilterStore.setAutopilot(app, false, FilterSettings.GOAL_PAY_FIRST);
        assertNull(observe(FIRST));
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_PAY_FIRST);
        assertEquals(FilterStore.AutopilotAcceptance.STALE,
                AutopilotRuntime.confirmedManualAcceptance(app, null, FIRST));
        FilterStore.AutopilotPermit permit = observe(FIRST);
        FilterStore.setAutopilot(app, false, FilterSettings.GOAL_PAY_FIRST);
        assertEquals(FilterStore.AutopilotAcceptance.OFF,
                AutopilotRuntime.confirmedManualAcceptance(app, permit, FIRST));
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_PAY_FIRST);
        assertEquals(FilterStore.AutopilotAcceptance.STALE,
                AutopilotRuntime.confirmedManualAcceptance(app, permit, FIRST));
        assertNull(FilterStore.pendingAutopilotAxes(app));
    }

    @Test public void duplicateOrOlderConfirmedEvidenceCannotOverwriteTheNewestChoice() {
        FilterStore.AutopilotPermit older = observe(FIRST);
        OfferSnapshot newer = new OfferSnapshot(900, 3.0, 20, 3);
        FilterStore.AutopilotPermit latest = observe(newer);
        confirm(latest, newer);
        String pending = FilterStore.pendingAutopilotAxes(app).encoded;
        assertEquals(FilterStore.AutopilotAcceptance.STALE,
                AutopilotRuntime.confirmedManualAcceptance(app, latest, newer));
        assertEquals(FilterStore.AutopilotAcceptance.STALE,
                AutopilotRuntime.confirmedManualAcceptance(app, older, FIRST));
        assertEquals(pending, FilterStore.pendingAutopilotAxes(app).encoded);
    }

    @Test public void changedFactsAndExcludedOffersCannotCreateAnUpdate() {
        assertNull(FilterStore.captureAutopilotAcceptance(app, FIRST, true));
        assertEquals(FilterStore.AutopilotAcceptance.STALE,
                AutopilotRuntime.confirmedManualAcceptance(app, observe(FIRST),
                        new OfferSnapshot(900, 3.0, 20, 2)));
        assertEquals(FilterStore.AutopilotAcceptance.UNKNOWN,
                AutopilotRuntime.confirmedManualAcceptance(app, observe(OfferSnapshot.UNKNOWN), OfferSnapshot.UNKNOWN));
        FilterStore.save(app, rules(), rules().withMaxStops(2));
        assertNull(observe(new OfferSnapshot(900, 3.0, 20, 3)));
        assertNull(FilterStore.pendingAutopilotAxes(app));
    }

    @Test public void aUserMinimumEditWinsOverAPendingChoiceButANewerConfirmationStillApplies() {
        confirm(observe(new OfferSnapshot(900, 3.0, 20, 3)), new OfferSnapshot(900, 3.0, 20, 3));
        FilterStore.save(app, FilterSettings.of(true, 500, 150, 40, 0).withAcceptanceLearning(false));
        AutopilotRuntime.rulesChanged(app);
        assertNull(FilterStore.pendingAutopilotAxes(app));
        assertTrue(rules().autopilotAxes.isEmpty());
        // A fresh observed offer accepted after the edit expresses a new choice under those saved requirements.
        FilterStore.AutopilotPermit next = observe(FIRST);
        confirm(next, FIRST);
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        asks(600, 600, 600, FIRST);
        assertEquals(500, rules().flatCents);
    }

    @Test public void anObservedOfferCanBeConfirmedAfterTheUserChangesAMinimum() {
        FilterStore.AutopilotPermit permit = observe(FIRST);
        FilterStore.save(app, FilterSettings.of(true, 500, 150, 40, 0).withAcceptanceLearning(false));
        AutopilotRuntime.rulesChanged(app);
        confirm(permit, FIRST);
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        asks(600, 600, 600, FIRST);
    }

    @Test public void aConfirmationUnderTheNewRulesWinsOverTheirLaterRuntimeNotification() {
        FilterStore.save(app, rules(), rules().withMinimums(500, 150, 40));
        confirm(observe(FIRST), FIRST);
        AutopilotRuntime.rulesChanged(app);
        assertNotNull(FilterStore.pendingAutopilotAxes(app));
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        asks(600, 600, 600, FIRST);
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        confirm(observe(FIRST), FIRST);
        AutopilotRuntime.userChanged(app, AutopilotRuntime.UserChange.GOAL_CHANGED);
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        asks(600, 600, 600, FIRST);
    }

    @Test public void adoptPreservesAConfirmedPendingChoiceAndUndoClearsItsDerivedShape() {
        FilterStore.setAcceptanceLearning(app, true);
        OfferSnapshot higher = new OfferSnapshot(1200, 3.0, 20, 2);
        FilterStore.AutopilotPermit permit = observe(higher);
        FilterStore.recordConfirmedManualAcceptance(app, FilterStore.captureLearning(app, higher), higher);
        confirm(permit, higher);
        AutopilotAxes accepted = FilterStore.pendingAutopilotAxes(app).axes;
        assertTrue(FilterStore.adoptAccepted(app));
        assertEquals(accepted, FilterStore.pendingAutopilotAxes(app).axes);
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        asks(1200, 1200, 1200, FIRST);
        FilterStore.Adoption adoption = FilterStore.pendingAdoption(app);
        assertNotNull(adoption);
        assertTrue(FilterStore.undoAdoption(app, adoption.id));
        assertTrue(rules().autopilotAxes.isEmpty());
        assertNull(FilterStore.pendingAutopilotAxes(app));
    }

    @Test public void adoptWhileOffKeepsAConfirmedChoiceReadyForTheSameGoalResume() {
        FilterStore.setAcceptanceLearning(app, true);
        OfferSnapshot higher = new OfferSnapshot(1200, 3.0, 20, 2);
        FilterStore.AutopilotPermit permit = observe(higher);
        FilterStore.recordConfirmedManualAcceptance(app, FilterStore.captureLearning(app, higher), higher);
        confirm(permit, higher);
        FilterStore.setAutopilot(app, false, FilterSettings.GOAL_PAY_FIRST);
        assertTrue(FilterStore.adoptAccepted(app));
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_PAY_FIRST);
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        asks(1200, 1200, 1200, FIRST);
    }

    @Test public void aConfirmedUpdateSurvivesRuntimeRestartWithoutReconstructingObservationEvidence() {
        confirm(observe(FIRST), FIRST);
        AutopilotRuntime.forgetCache();
        assertTrue(AutopilotRuntime.commitWanted(app));
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        asks(600, 600, 600, FIRST);
    }

    @Test public void clearHistoryRemovesBothShapesAndInvalidatesLiveEvidence() {
        accept(FIRST);
        OfferSnapshot next = new OfferSnapshot(900, 3.0, 20, 3);
        confirm(observe(FIRST), FIRST);
        FilterStore.AutopilotPermit live = observe(next);
        DecisionLog.clear(app);
        assertTrue(rules().autopilotAxes.isEmpty());
        assertNull(FilterStore.pendingAutopilotAxes(app));
        assertEquals(FilterStore.AutopilotAcceptance.STALE,
                AutopilotRuntime.confirmedManualAcceptance(app, live, next));
        assertEquals(400, rules().flatCents);
    }

    @Test public void consentTransitionInvalidatesPendingAndUnconfirmedChoices() {
        FilterStore.AutopilotPermit live = observe(FIRST);
        confirm(observe(FIRST), FIRST);
        FilterStore.invalidateLearningEvidence(app);
        assertNull(FilterStore.pendingAutopilotAxes(app));
        assertEquals(FilterStore.AutopilotAcceptance.STALE,
                AutopilotRuntime.confirmedManualAcceptance(app, live, FIRST));
        assertFalse(rules().hasAutopilotAxes());
    }

    @Test public void aNewerConfirmationDuringCommitWinsAsOneWholeShape() {
        confirm(observe(FIRST), FIRST);
        OfferSnapshot next = new OfferSnapshot(900, 3.0, 15, 3);
        FilterStore.AutopilotPermit permit = observe(next);
        AutopilotRuntime.beforeWriteForTests = () -> {
            AutopilotRuntime.beforeWriteForTests = null;
            confirm(permit, next);
        };
        assertEquals(AutopilotRuntime.Commit.CONFLICT, AutopilotRuntime.commitIfDue(app));
        assertFalse(rules().hasAutopilotAxes());
        assertNotNull(FilterStore.pendingAutopilotAxes(app));
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        asks(600, 900, 1200, FIRST);
    }

    @Test public void clearHistoryDuringCommitCannotBeUndoneByTheOldPendingUpdate() {
        confirm(observe(FIRST), FIRST);
        AutopilotRuntime.beforeWriteForTests = () -> {
            AutopilotRuntime.beforeWriteForTests = null;
            DecisionLog.clear(app);
        };
        assertEquals(AutopilotRuntime.Commit.CONFLICT, AutopilotRuntime.commitIfDue(app));
        assertTrue(rules().autopilotAxes.isEmpty());
        assertNull(FilterStore.pendingAutopilotAxes(app));
    }

    @Test public void historyRoundTripKeepsEachOriginalWorkingScoreIncludingTheFoldedNotification() throws Exception {
        accept(FIRST);
        FilterSettings decided = rules().withScoreByArea(true);
        DecisionLog.Entry notice = entry(1_000, DecisionLog.Source.NOTIFICATION, FIRST, decided);
        accept(new OfferSnapshot(900, 3.0, 15, 3));
        DecisionLog.Entry screen = entry(2_000, DecisionLog.Source.SCREEN, FIRST, rules()).withNotification(notice);
        DecisionLog.Entry restored = DecisionLog.Entry.fromJson(screen.toJson());
        assertEquals(screen.scoringRules.autopilotAxes, restored.scoringRules.autopilotAxes);
        assertEquals(notice.scoringRules.autopilotAxes, restored.notification.scoringRules.autopilotAxes);
        assertEquals(screen.scorePercent, restored.scorePercent);
        assertEquals(notice.scorePercent, restored.notification.scorePercent);
        assertTrue(DecisionLog.score(restored).contains("Autopilot score"));
        assertTrue(DecisionLog.score(restored.notification).contains("Autopilot score"));
        DecisionLog.record(app, restored);
        DecisionLog.flush();
        DecisionLog.forgetCache();
        assertEquals(restored.scoringRules.autopilotAxes, DecisionLog.recent(app, 1).get(0).scoringRules.autopilotAxes);
        String report = DecisionLog.report(app, 1);
        assertFalse(report.contains("autopilotAxes"));
        assertFalse(report.contains(restored.scoringRules.autopilotAxes.encode()));
    }

    @Test public void corruptWorkingHistoryDoesNotSilentlyReplayAsTheSavedBaseline() throws Exception {
        accept(FIRST);
        JSONObject json = entry(wall, DecisionLog.Source.SCREEN, FIRST, rules()).toJson();
        json.getJSONObject("rules").put("autopilotAxes", "invalid");
        assertNull(DecisionLog.Entry.fromJson(json).scoringRules);
        json.getJSONObject("rules").remove("autopilotAxes");
        assertNotNull(DecisionLog.Entry.fromJson(json).scoringRules);
    }

    private static DecisionLog.Entry entry(long at, DecisionLog.Source source, OfferSnapshot facts, FilterSettings rules) {
        OfferRule.Decision decision = OfferRule.evaluate(facts, rules);
        return new DecisionLog.Entry(at, source, false, facts, decision.requiredCents, decision.result,
                decision.reason, DecisionLog.Action.PASSES, true, Collections.emptyList())
                .withScore(decision.scorePercent).withBar(rules.minimumScalePercent, true).withScoringRules(rules);
    }
}

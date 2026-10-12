package com.local.dasherfilter;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36})
public class DispatchStudyTest {
    static final long WALL = 1_800_000_000_000L;
    static FilterSettings rules(int pay) { return FilterSettings.of(true, pay, 0, 0, 0); }
    static OfferSnapshot offer(int pay) { return new OfferSnapshot(pay, 3.0, 15, 2); }
    private static void waiting(DispatchStudy m, long at) {
        m.screen(at, WALL + at, 1, true, DasherScene.WAITING, null, false, false, rules(1000), false);
    }
    private static void ticks(DispatchStudy m, long from, long to) {
        for (long at = from; at <= to; at += 5_000) m.heartbeat(at, WALL + at, true);
    }
    private static void arrival(DispatchStudy m, long at, int pay, boolean fresh) {
        m.screen(at, WALL + at, 1, true, DasherScene.OFFER, offer(pay), false, fresh, rules(1000), false);
    }
    private static DispatchStudy warm() {
        DispatchStudy m = new DispatchStudy();
        for (int i = 0; i < 5; i++) {
            long at = i * 125_000L;
            waiting(m, at); ticks(m, at + 5_000, at + 120_000); arrival(m, at + 120_000, 1200, true);
        }
        return m;
    }
    @Test public void freezesPredictionBeforeOutcomeWithRealCountsAndNoInventedConfidence() throws Exception {
        DispatchStudy m = warm(); waiting(m, 625_000);
        DispatchStudy.Row before = m.snapshotRows(WALL + 625_000, DispatchStudy.Reason.PREVIEW_CUTOFF).get(5);
        assertEquals(5, before.qualifying); assertEquals(5, before.readable);
        // Four rejected-offer handling intervals were positively followed by waiting, so they count too.
        assertEquals(620_000, before.trainingMs); assertEquals(124_000, before.predicted);
        assertEquals(120_000, before.baseline);
        ticks(m, 630_000, 865_000); arrival(m, 865_000, 5000, true);
        DispatchStudy.Row result = m.rows.get(5);
        assertEquals(240_000, result.observedMs); assertEquals(124_000, result.predicted);
        assertEquals(5, result.readable); assertEquals(DispatchStudy.End.QUALIFYING, result.end);
        assertEquals("not_computed", result.json(1).getString("uncertainty"));
    }
    @Test public void coldStartAndOfferWithoutObservedWaitingNeverBecomeForecastSuccess() {
        DispatchStudy m = new DispatchStudy(); arrival(m, 0, 1500, true); assertTrue(m.rows.isEmpty());
        waiting(m, 1000); ticks(m, 6000, 11_000); arrival(m, 11_000, 1500, true);
        assertEquals(-1, m.rows.get(0).predicted); assertEquals(10_000, m.rows.get(0).observedMs);
    }
    @Test public void nonqualifyingOffersKeepOneProspectiveTrialUntilAQualifyingArrival() {
        DispatchStudy m = new DispatchStudy(); waiting(m, 0); ticks(m, 5000, 60_000);
        arrival(m, 60_000, 500, true); ticks(m, 65_000, 70_000); waiting(m, 70_000);
        ticks(m, 75_000, 120_000); arrival(m, 120_000, 1200, true);
        assertEquals(2, m.rows.size()); assertEquals(m.rows.get(0).trial, m.rows.get(1).trial);
        assertEquals(DispatchStudy.End.NONQUALIFYING, m.rows.get(0).end);
        assertEquals(120_000, m.rows.get(1).observedMs); assertEquals(60_000, m.rows.get(1).deltaMs);
    }
    @Test public void duplicateRereadsNeverCreateExtraArrivalsOrOutcomes() {
        DispatchStudy m = new DispatchStudy(); waiting(m, 0); ticks(m, 5000, 10_000);
        arrival(m, 10_000, 500, true);
        for (int i = 1; i <= 8; i++) arrival(m, 10_000 + i * 1000, 500, false);
        assertEquals(1, m.rows.size()); assertEquals(10_000, m.rows.get(0).observedMs);
    }
    @Test public void missingFactsCensorRatherThanRetrospectivelyFillTheForecastOutcome() {
        DispatchStudy m = new DispatchStudy(); waiting(m, 0); ticks(m, 5000, 10_000);
        m.screen(10_000, WALL + 10_000, 1, true, DasherScene.OFFER, OfferSnapshot.UNKNOWN, false, true, rules(1000), false);
        arrival(m, 11_000, 2000, false);
        assertEquals(1, m.rows.size()); assertEquals(DispatchStudy.End.UNKNOWN_OFFER, m.rows.get(0).end);
        assertNull(m.rows.get(0).facts.payCents);
    }
    @Test public void backgroundLockAndPauseExcludeUnobservedTime() {
        for (boolean enabled : new boolean[] {true, false}) {
            DispatchStudy m = new DispatchStudy(); waiting(m, 0); ticks(m, 5000, 10_000);
            m.screen(300_000, WALL + 300_000, 1, false, DasherScene.WAITING, null, false, false,
                    FilterSettings.of(enabled, 1000, 0, 0, 0), false);
            assertEquals(10_000, m.rows.get(0).observedMs);
            assertEquals(enabled ? DispatchStudy.Reason.COVERAGE_LOST : DispatchStudy.Reason.PAUSED,
                    m.rows.get(0).reason);
            waiting(m, 400_000); ticks(m, 405_000, 410_000); arrival(m, 410_000, 2000, true);
            assertEquals(10_000, m.rows.get(1).observedMs);
        }
    }
    @Test public void coverageGapAndClockJumpAreCensoredNotObservedWaits() {
        DispatchStudy m = new DispatchStudy(); waiting(m, 0); ticks(m, 5000, 10_000);
        m.heartbeat(30_000, WALL + 30_000, true);
        assertEquals(DispatchStudy.Reason.GAP, m.rows.get(0).reason);
        assertEquals(10_000, m.rows.get(0).observedMs);
        waiting(m, 40_000); m.heartbeat(45_000, WALL + 200_000, true);
        assertEquals(DispatchStudy.Reason.CLOCK_CHANGED, m.rows.get(1).reason);
        assertEquals(0, m.rows.get(1).observedMs);
    }
    @Test public void policyAndSessionChangesDoNotMixForecastTrials() {
        DispatchStudy m = new DispatchStudy(); waiting(m, 0); ticks(m, 5000, 10_000);
        m.screen(15_000, WALL + 15_000, 1, true, DasherScene.WAITING, null, false, false, rules(2000), false);
        assertEquals(DispatchStudy.Reason.POLICY_CHANGED, m.rows.get(0).reason);
        assertEquals(10_000, m.rows.get(0).observedMs);
        m.screen(20_000, WALL + 20_000, 2, true, DasherScene.WAITING, null, false, false, rules(2000), false);
        assertEquals(DispatchStudy.Reason.SESSION_CHANGED, m.rows.get(1).reason);
        assertTrue(m.rows.get(1).policy > m.rows.get(0).policy);
    }
    @Test public void unknownScenesAndAddonsCannotBecomeQualifiedOutcomes() {
        for (boolean excluded : new boolean[] {true, false}) {
            DispatchStudy m = new DispatchStudy(); waiting(m, 0);
            m.screen(5000, WALL + 5000, 1, true, excluded ? DasherScene.OFFER : DasherScene.UNKNOWN,
                    offer(2000), excluded, true, rules(1000), false);
            assertEquals(DispatchStudy.End.CENSORED, m.rows.get(0).end);
        }
    }
    @Test public void autoAcceptPolicyChangeRestartsTrainingWithoutChangingTheOldForecast() {
        DispatchStudy m = warm(); waiting(m, 625_000);
        m.screen(630_000, WALL + 630_000, 1, true, DasherScene.WAITING, null, false, false, rules(1000), true);
        DispatchStudy.Row old = m.rows.get(5);
        assertEquals(DispatchStudy.Reason.POLICY_CHANGED, old.reason);
        assertEquals(124_000, old.predicted); assertEquals(5, old.readable);
        DispatchStudy.Row current = m.snapshotRows(WALL + 630_000, DispatchStudy.Reason.PREVIEW_CUTOFF).get(6);
        assertTrue(current.policy > old.policy);
        assertEquals(-1, current.predicted); assertEquals(-1, current.baseline);
        assertEquals(0, current.trainingMs); assertEquals(0, current.readable);
    }
    @Test public void requestedTapIsNotAnObservedDeclineAndTouchesRemainSeparate() {
        DispatchStudy m = new DispatchStudy(); waiting(m, 0); arrival(m, 1000, 500, true);
        DecisionLog.Entry e = entry(500, WALL + 1000, DecisionLog.Action.CONFIRMATION_TAPPED);
        m.actions(Collections.singletonList(new DispatchStudy.Action(e)));
        assertTrue(m.rows.get(0).actionKnown);
        assertEquals(DispatchStudy.Observed.UNKNOWN, m.rows.get(0).observed);
        m.actions(Collections.singletonList(new DispatchStudy.Action(entry(500, WALL + 1000, DecisionLog.Action.USER_TOOK_OVER))));
        assertTrue(m.rows.get(0).touch); assertEquals(DispatchStudy.Observed.UNKNOWN, m.rows.get(0).observed);
    }
    @Test public void partialOfferLaterProvenAddonRevokesSuccessButDeliveryDoesNot() {
        for (DasherScene next : Arrays.asList(DasherScene.OFFER, DasherScene.ROUTE)) {
            DispatchStudy m = new DispatchStudy(); waiting(m, 0); arrival(m, 1000, 1200, true);
            m.screen(2000, WALL + 2000, 1, true, next, offer(1200), true, false, rules(1000), false);
            assertEquals(next == DasherScene.OFFER ? DispatchStudy.End.CENSORED : DispatchStudy.End.QUALIFYING,
                    m.rows.get(0).end);
        }
    }
    @Test public void ambiguousIdenticalOffersNeverReceiveAConfidentActionAssociation() {
        DispatchStudy m = new DispatchStudy(); waiting(m, 0); arrival(m, 1000, 500, true); arrival(m, 2000, 500, true);
        m.actions(Collections.singletonList(new DispatchStudy.Action(entry(500, WALL + 1500, DecisionLog.Action.DECLINE_TAPPED))));
        for (DispatchStudy.Row r : m.rows) { assertTrue(r.pairingAmbiguous); assertFalse(r.actionKnown); }
    }
    @Test public void realObservedAcceptAndDeclineAreDistinctFromRequestCategories() {
        for (DecisionLog.StepKind kind : Arrays.asList(DecisionLog.StepKind.ACCEPTED, DecisionLog.StepKind.DECLINE_COUNTED)) {
            DispatchStudy m = new DispatchStudy(); waiting(m, 0); arrival(m, 1000, 1200, true);
            DecisionLog.Entry e = entry(1200, WALL + 1000, DecisionLog.Action.PASSES)
                    .withStep(new DecisionLog.Step(kind, WALL + 2000, "private detail never exported"));
            m.actions(Collections.singletonList(new DispatchStudy.Action(e)));
            assertEquals(kind == DecisionLog.StepKind.ACCEPTED ? DispatchStudy.Observed.ACCEPTED : DispatchStudy.Observed.DECLINED,
                    m.rows.get(0).observed);
        }
    }
    @Test public void rowRetentionAndTrialLengthAreBoundedWithExplicitCensoring() {
        DispatchStudy m = new DispatchStudy(); waiting(m, 0);
        ticks(m, 5000, DispatchStudy.MAX_TRIAL_MS + 5000);
        assertEquals(DispatchStudy.Reason.LIMIT, m.rows.get(0).reason);
        for (int i = 0; i < 40; i++) { waiting(m, 2_000_000 + i * 20_000); m.stop(DispatchStudy.Reason.COVERAGE_LOST); }
        assertEquals(32, m.rows.size()); assertTrue(m.dropped > 0);
        m.prune(WALL + 2 * DispatchStudy.RETAIN_MS); assertTrue(m.rows.isEmpty());
    }
    @Test public void strictSchemaPreservesOnlyNumbersEnumsAndKnownKeys() throws Exception {
        DispatchStudy m = warm(); JSONObject original = m.rows.get(0).json(1);
        JSONObject roundTrip = DispatchStudy.Row.parse(original).json(1);
        assertEquals(original.toString(), roundTrip.toString());
        for (String key : Arrays.asList("merchant", "address", "AR", "autopilotGoal", "flatCents")) {
            JSONObject bad = new JSONObject(original.toString()).put(key, "PRIVATE");
            try { DispatchStudy.Row.parse(bad); fail(key); } catch (org.json.JSONException expected) { }
        }
        original.put("payCents", "PRIVATE");
        try { DispatchStudy.Row.parse(original); fail(); } catch (org.json.JSONException expected) { }
        original.remove("payCents"); original.put("merchant", "PRIVATE");
        try { DispatchStudy.Row.parse(original); fail("a replaced nullable key is still an unknown schema"); }
        catch (org.json.JSONException expected) { }
    }
    static DecisionLog.Entry entry(int pay, long at, DecisionLog.Action action) {
        OfferSnapshot f = offer(pay);
        return DecisionLog.Entry.of(DecisionLog.Source.SCREEN, false, f, OfferRule.evaluate(f, rules(1000)),
                action, true, Collections.emptyList()).withTime(at);
    }
}

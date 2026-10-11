package com.local.dasherfilter;

import java.util.Collections;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Recorded models retain their own meaning and local exact rule proof through history transformations. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
public final class ScoringPolicyHistoryTest {
    private static FilterSettings rules() {
        return FilterSettings.of(true, 500, 100, 25, 3).withScoreByArea(true)
                .withAcceptanceLearning(true).withAccepted(new AcceptedFloors(500,1000,10,500,20))
                .withAutopilot(true,50).withMinimumScalePercent(82);
    }
    private static DecisionLog.Entry entry(FilterSettings settings) {
        OfferSnapshot facts = new OfferSnapshot(800,10.0,20,2);
        return DecisionLog.Entry.of(DecisionLog.Source.SCREEN,false,facts,OfferRule.evaluate(facts,settings),
                DecisionLog.Action.PASSES,false,Collections.emptyList());
    }
    private static void samePolicy(DecisionLog.Entry before, DecisionLog.Entry after) {
        assertEquals(3,after.model); assertEquals(before.scoreKind,after.scoreKind);
        assertNotNull(after.scoringRules);
        assertEquals(before.scoringRules.scoringKey(),after.scoringRules.scoringKey());
        assertEquals(before.barPercent,after.barPercent);
        assertEquals(before.scoringRules.accepted,after.scoringRules.accepted);
        assertEquals(before.scorePercent,after.scorePercent);
    }
    @Test public void newLocalPolicySnapshotRoundTripsAllExactProofWithoutCurrentProjection() throws Exception {
        DecisionLog.Entry before=entry(rules());
        assertEquals("area",before.scoreKind);
        assertTrue(DecisionLog.score(before).contains("area score"));
        JSONObject json=before.toJson();
        assertEquals(3,json.getInt("model"));
        assertEquals(1000,json.getJSONObject("rules").getJSONObject("accepted").getInt("milePay"));
        DecisionLog.Entry after=DecisionLog.Entry.fromJson(json);
        samePolicy(before,after);
        assertEquals(after.scoringRules.scoringKey(),AutopilotRuntime.record(after).scoringRules.scoringKey());
        assertEquals(82,after.scoringRules.minimumScalePercent);
        assertEquals(OfferRule.evaluate(after.facts,after.scoringRules).requiredCents,after.requiredCents);
    }
    @Test public void immutableHistoryCopiesAndNestedNoticeKeepOriginalPolicies() throws Exception {
        DecisionLog.Entry before=entry(rules());
        DecisionLog.Entry notice=entry(rules().withScoreByArea(false));
        DecisionLog.Entry copy=before.peeked(true).withTime(1234).withAlertTag("synthetic",true)
                .withStep(new DecisionLog.Step(DecisionLog.StepKind.LEARNER_BEST_SAVED,1235,""))
                .withNotification(notice);
        samePolicy(before,copy); samePolicy(notice,copy.notification);
        samePolicy(before,DecisionLog.Entry.fromJson(copy.toJson()));
        assertEquals("strict",DecisionLog.Entry.fromJson(copy.toJson()).notification.scoreKind);
    }
    @Test public void shippedModelTwoAlwaysStaysStrictAndKeepsItsBar() throws Exception {
        JSONObject old=entry(rules()).toJson().put("model",2).put("scoreKind","area").put("bar",82).put("auto",true);
        DecisionLog.Entry loaded=DecisionLog.Entry.fromJson(old);
        assertEquals("strict",loaded.scoreKind); assertNull(loaded.scoringRules);
        assertFalse(DecisionLog.score(loaded).contains("area"));
        JSONObject kept=loaded.toJson();
        assertEquals(2,kept.getInt("model")); assertEquals(82,kept.getInt("bar")); assertTrue(kept.getBoolean("auto"));
        assertFalse(kept.has("rules")); assertFalse(kept.has("scoreKind"));
    }
    @Test public void historicalModelOneKeepsAreaLabelAndDoesNotAcquireNewProof() throws Exception {
        JSONObject old=entry(rules()).toJson();old.remove("model");old.remove("bar");old.remove("auto");
        DecisionLog.Entry loaded=DecisionLog.Entry.fromJson(old);
        assertEquals(1,loaded.model); assertEquals("area",loaded.scoreKind); assertNull(loaded.scoringRules);
        assertTrue(DecisionLog.score(loaded).contains("area score"));
        assertFalse(loaded.toJson().has("rules"));
    }
    @Test public void learnerStatusStepsDoNotInventAnotherAcceptanceOrOutcome() {
        DecisionLog.Entry plain=entry(rules());
        for(DecisionLog.StepKind kind:new DecisionLog.StepKind[]{DecisionLog.StepKind.LEARNER_BEST_SAVED,
                DecisionLog.StepKind.LEARNER_MINIMUM_RAISED,DecisionLog.StepKind.LEARNER_UNCHANGED}) {
            DecisionLog.Entry status=plain.withStep(new DecisionLog.Step(kind,1234,""));
            assertEquals(DecisionLog.outcome(plain),DecisionLog.outcome(status));
            assertFalse(DecisionLog.accepted(status));
            DecisionLog.Entry accepted=status.withStep(new DecisionLog.Step(DecisionLog.StepKind.ACCEPTED,1235,""));
            assertTrue(DecisionLog.accepted(accepted));
        }
    }
}

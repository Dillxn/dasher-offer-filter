package com.local.dasherfilter;

import android.app.Application;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Learning steps kept on an offer's history line: they never change its action, its tally or the totals, they last
 * as the line does, and they never leave the phone in an automatic report.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class DecisionLogStepsTest {
    private static final OfferSnapshot PASSING = new OfferSnapshot(1675, 3.9, 30, 3);
    private Application app;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
    }

    private static DecisionLog.Entry passing(long at) {
        return new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false, PASSING, 1502, OfferRule.Result.KEEP,
                "meets enabled rules", DecisionLog.Action.PASSES, true,
                Arrays.asList("$16.75", "3 stops (3.9 mi) • 30 min"));
    }

    @Test
    public void aStepMarksTheOffersOwnLineWithoutChangingItsActionOrCounts() {
        long now = System.currentTimeMillis();
        DecisionLog.record(app, passing(now - 5_000));
        int[] before = DecisionLog.totals(app);
        assertTrue(DecisionLog.markStep(app, PASSING, DecisionLog.StepKind.ACCEPTED_LEARNED,
                "it closed with about 0:30 left on its countdown", 600_000));

        DecisionLog.Entry line = DecisionLog.recent(app, 1).get(0);
        assertEquals(DecisionLog.Action.PASSES, line.action);
        assertEquals(1, line.steps.size());
        assertArrayEquals(before, DecisionLog.totals(app));
        // A later reading of the same offer keeps them, and so does a restart.
        DecisionLog.record(app, passing(now - 4_000));
        DecisionLog.flush();
        DecisionLog.forgetCache();
        line = DecisionLog.recent(app, 1).get(0);
        assertEquals(DecisionLog.StepKind.ACCEPTED_LEARNED, line.steps.get(0).kind);
        String report = DecisionLog.report(app, 5);
        assertTrue(report, report.contains("    learning ")
                && report.contains(" Accepted; the adaptive minimum learned from it: it closed with about 0:30 left"));
    }

    @Test
    public void aStepNeverReachesAnotherOrAnOldOffer() {
        long now = System.currentTimeMillis();
        DecisionLog.record(app, passing(now - 20 * 60_000L));
        assertFalse("older than the window", DecisionLog.markStep(app, PASSING, DecisionLog.StepKind.NOT_ACCEPTED,
                "", 10 * 60_000L));
        assertFalse("a different offer", DecisionLog.markStep(app, new OfferSnapshot(900, 2.0, 10, 2),
                DecisionLog.StepKind.NOT_ACCEPTED, "", 60 * 60_000L));
        assertTrue(DecisionLog.recent(app, 1).get(0).steps.isEmpty());
    }

    @Test
    public void atMostEightStepsAndNoExactRepeats() {
        DecisionLog.record(app, passing(System.currentTimeMillis()));
        DecisionLog.markStep(app, PASSING, DecisionLog.StepKind.TAP_NOT_RECOGNIZED, "", 60_000);
        DecisionLog.markStep(app, PASSING, DecisionLog.StepKind.TAP_NOT_RECOGNIZED, "", 60_000);
        assertEquals(1, DecisionLog.recent(app, 1).get(0).steps.size());
        for (int i = 0; i < 12; i++) {
            DecisionLog.markStep(app, PASSING, DecisionLog.StepKind.NOT_LEARNED, "try " + i, 60_000);
        }
        List<DecisionLog.Step> steps = DecisionLog.recent(app, 1).get(0).steps;
        assertEquals(DecisionLog.MAX_STEPS, steps.size());
        assertEquals("try 11", steps.get(steps.size() - 1).detail);
    }

    @Test
    public void reportsNeverCarrySteps() {
        DecisionLog.record(app, passing(System.currentTimeMillis()));
        DecisionLog.markStep(app, PASSING, DecisionLog.StepKind.DECLINE_COUNTED, "Dasher went back to the wait", 60_000);
        String report = OfferReport.text(OfferReport.Problem.WRONG_DECLINE, "test", 1, "Android test",
                new FilterSettings(true, 1300, 385, 41, 475, 3, true, 0), DecisionLog.recent(app, 1).get(0));
        assertFalse(report, report.contains("\"steps\""));
        assertFalse(report, report.contains("Counted as your Decline"));
        assertFalse(report, report.contains("Dasher went back to the wait"));
    }

    @Test
    public void aHistoryWithStepsThisVersionDoesNotKnowStillLoads() throws Exception {
        DecisionLog.record(app, passing(System.currentTimeMillis()));
        DecisionLog.markStep(app, PASSING, DecisionLog.StepKind.NOT_ACCEPTED, "", 60_000);
        DecisionLog.flush();
        File file = new File(app.getFilesDir(), "decision-log.json");
        JSONArray stored = new JSONArray(new String(java.nio.file.Files.readAllBytes(file.toPath()),
                StandardCharsets.UTF_8));
        stored.getJSONObject(0).getJSONArray("steps").put(new org.json.JSONObject()
                .put("kind", "SOMETHING_NEWER").put("at", 1).put("detail", "x"));
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(stored.toString().getBytes(StandardCharsets.UTF_8));
        }
        DecisionLog.forgetCache();
        List<DecisionLog.Entry> loaded = DecisionLog.recent(app, 10);
        assertEquals(1, loaded.size());
        assertEquals(1, loaded.get(0).steps.size());
        assertEquals(DecisionLog.StepKind.NOT_ACCEPTED, loaded.get(0).steps.get(0).kind);
    }

    @Test public void requestIsNeverAcceptedFromManualNotLearnedInferenceAndPersistsTruthfully() {
        DecisionLog.record(app, passing(System.currentTimeMillis()));
        DecisionLog.markStep(app, PASSING, DecisionLog.StepKind.AUTO_ACCEPT_REQUESTED,
                "awaiting observed delivery", 60_000);
        DecisionLog.markStep(app, PASSING, DecisionLog.StepKind.NOT_LEARNED,
                AcceptedOfferTracker.DELIVERY_UNDER_WAY, 60_000);
        assertEquals(DecisionLog.Outcome.REQUESTED, DecisionLog.outcome(DecisionLog.recent(app, 1).get(0)));
        assertFalse(DecisionLog.accepted(DecisionLog.recent(app, 1).get(0)));
        DecisionLog.markStep(app, PASSING, DecisionLog.StepKind.AUTO_ACCEPT_UNCONFIRMED,
                "delivery not confirmed", 60_000);
        DecisionLog.flush(); DecisionLog.forgetCache();
        assertEquals(DecisionLog.Outcome.YOURS, DecisionLog.outcome(DecisionLog.recent(app, 1).get(0)));
        assertFalse(DecisionLog.accepted(DecisionLog.recent(app, 1).get(0)));
    }

    @Test public void notSentIsLeftToUserAcrossReloadAndCannotEraseALaterActualAcceptance() {
        DecisionLog.record(app, passing(System.currentTimeMillis()));
        DecisionLog.markStep(app, PASSING, DecisionLog.StepKind.AUTO_ACCEPT_NOT_SENT, "content_changed", 60_000);
        DecisionLog.flush(); DecisionLog.forgetCache();
        assertEquals(DecisionLog.Outcome.YOURS, DecisionLog.outcome(DecisionLog.recent(app, 1).get(0)));
        assertFalse(DecisionLog.accepted(DecisionLog.recent(app, 1).get(0)));
        DecisionLog.markStep(app, PASSING, DecisionLog.StepKind.ACCEPTED_NOT_LEARNED,
                "you tapped Accept, and Dasher showed a delivery screen", 60_000);
        assertEquals(DecisionLog.Outcome.ACCEPTED, DecisionLog.outcome(DecisionLog.recent(app, 1).get(0)));
    }
    @Test public void queuedNotSentAfterTheUsersAcceptDoesNotEraseTheirTapEvidence() {
        DecisionLog.record(app, passing(System.currentTimeMillis()));
        DecisionLog.markStep(app, PASSING, DecisionLog.StepKind.ACCEPT_TAPPED, "waiting for a delivery screen", 60_000);
        DecisionLog.markStep(app, PASSING, DecisionLog.StepKind.AUTO_ACCEPT_NOT_SENT, "user_action", 60_000);
        DecisionLog.markStep(app, PASSING, DecisionLog.StepKind.NOT_LEARNED,
                AcceptedOfferTracker.DELIVERY_UNDER_WAY, 60_000);
        assertEquals(DecisionLog.Outcome.ACCEPTED, DecisionLog.outcome(DecisionLog.recent(app, 1).get(0)));
    }
}

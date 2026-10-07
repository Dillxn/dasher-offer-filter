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
 * Outcome steps kept on an offer's history line: they never change its action, an acceptance moves its tally and the
 * totals and a mark changes neither, they last as the line does, and they never leave the phone in an automatic
 * report. Nothing is learned from any of them; every kind an older version wrote still parses.
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
        assertTrue(DecisionLog.markStep(app, PASSING, DecisionLog.StepKind.ACCEPTED,
                "it closed with about 0:30 left on its countdown", 600_000));

        DecisionLog.Entry line = DecisionLog.recent(app, 1).get(0);
        assertEquals(DecisionLog.Action.PASSES, line.action);
        assertEquals(1, line.steps.size());
        assertArrayEquals("a passing offer accepted still counts as passed", before, DecisionLog.totals(app));
        // A later reading of the same offer keeps them, and so does a restart.
        DecisionLog.record(app, passing(now - 4_000));
        DecisionLog.flush();
        DecisionLog.forgetCache();
        line = DecisionLog.recent(app, 1).get(0);
        assertEquals(DecisionLog.StepKind.ACCEPTED, line.steps.get(0).kind);
        String report = DecisionLog.report(app, 5);
        // Worded as the summary after a dash words it: what followed the offer, never learning.
        assertTrue(report, report.matches("(?s).*\\n    then \\d\\d:\\d\\d:\\d\\d Accepted: it closed with about 0:30 "
                + "left on its countdown\\n.*"));
        assertFalse(report, report.contains("learning"));
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
                FilterSettings.of(true, 1300, 385, 41, 3), DecisionLog.recent(app, 1).get(0));
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

    // ---- 0.5.0: acceptances by the user or after an automatic request, the exemption mark, the retired kinds ----

    private static DecisionLog.Entry declined(long at) {
        return new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false, DECLINED, 1500, OfferRule.Result.DECLINE,
                "dollars per mile", DecisionLog.Action.CONFIRMATION_TAPPED, true, Collections.<String>emptyList());
    }

    private static final OfferSnapshot DECLINED = new OfferSnapshot(700, 6.0, 20, 2);

    @Test public void anAcceptanceByTheUserOrAfterAnAutomaticRequestCountsAsAccepted() {
        DecisionLog.Entry line = passing(System.currentTimeMillis());
        assertFalse(DecisionLog.accepted(line));
        for (DecisionLog.StepKind kind : new DecisionLog.StepKind[] {DecisionLog.StepKind.ACCEPTED,
                DecisionLog.StepKind.ACCEPTED_AUTOMATIC}) {
            DecisionLog.Entry accepted = line.withStep(new DecisionLog.Step(kind, 1, "how it was seen"));
            assertTrue(kind.name(), DecisionLog.accepted(accepted));
            assertEquals(kind.name(), DecisionLog.Outcome.ACCEPTED, DecisionLog.outcome(accepted));
            assertEquals(kind.name(), DecisionLog.Tally.PASSED, DecisionLog.tally(accepted));
        }
        // An automatic request, then its confirmation: accepted, never left "requested".
        DecisionLog.Entry requested = line.withStep(new DecisionLog.Step(DecisionLog.StepKind.AUTO_ACCEPT_REQUESTED, 1,
                "waiting up to 15 s"));
        assertEquals(DecisionLog.Outcome.REQUESTED, DecisionLog.outcome(requested));
        DecisionLog.Entry confirmed = requested.withStep(new DecisionLog.Step(
                DecisionLog.StepKind.ACCEPTED_AUTOMATIC, 2, "automatic Accept was requested"));
        assertEquals(DecisionLog.Outcome.ACCEPTED, DecisionLog.outcome(confirmed));
        // A failing offer the app had declined, later accepted: the acceptance came last and wins.
        DecisionLog.Entry declinedThenAccepted = declined(1).withStep(new DecisionLog.Step(
                DecisionLog.StepKind.ACCEPTED, 3, "you tapped Accept"));
        assertEquals(DecisionLog.Outcome.ACCEPTED, DecisionLog.outcome(declinedThenAccepted));
        assertEquals(DecisionLog.Tally.PASSED, DecisionLog.tally(declinedThenAccepted));
    }

    @Test public void anAcceptanceStepMovesTheTotalsOnceAndSurvivesARestart() {
        long now = System.currentTimeMillis();
        DecisionLog.record(app, declined(now - 5_000));
        assertArrayEquals(new int[] {0, 1, 0}, DecisionLog.totals(app));
        assertTrue(DecisionLog.markStep(app, DECLINED, DecisionLog.StepKind.ACCEPTED_AUTOMATIC,
                "automatic Accept was requested, and Dasher showed a delivery screen", 60_000));
        assertArrayEquals(new int[] {1, 0, 0}, DecisionLog.totals(app));
        DecisionLog.flush();
        DecisionLog.forgetCache();
        DecisionLog.Entry line = DecisionLog.recent(app, 1).get(0);
        assertTrue(DecisionLog.hasStep(line, DecisionLog.StepKind.ACCEPTED_AUTOMATIC));
        assertFalse(DecisionLog.hasStep(line, DecisionLog.StepKind.ACCEPTED));
        assertTrue(DecisionLog.accepted(line));
        assertArrayEquals(new int[] {1, 0, 0}, DecisionLog.totals(app));
    }

    @Test public void theExemptionMarkChangesNoOutcomeTallyOrTotal() {
        long now = System.currentTimeMillis();
        DecisionLog.record(app, declined(now - 5_000));
        DecisionLog.record(app, passing(now - 3_000));
        int[] before = DecisionLog.totals(app);
        assertArrayEquals(new int[] {1, 1, 0}, before);
        String why = "Dasher said declining it does not lower your acceptance rate";
        assertTrue(DecisionLog.markStep(app, DECLINED, DecisionLog.StepKind.AR_EXEMPT, why, 60_000));
        assertTrue(DecisionLog.markStep(app, PASSING, DecisionLog.StepKind.AR_EXEMPT, "", 60_000));
        assertArrayEquals(before, DecisionLog.totals(app));
        List<DecisionLog.Entry> lines = DecisionLog.recent(app, 2);
        assertEquals(DecisionLog.Outcome.PASSED, DecisionLog.outcome(lines.get(0)));
        assertEquals(DecisionLog.Outcome.DECLINED, DecisionLog.outcome(lines.get(1)));
        assertTrue(DecisionLog.hasStep(lines.get(0), DecisionLog.StepKind.AR_EXEMPT));
        assertTrue(DecisionLog.hasStep(lines.get(1), DecisionLog.StepKind.AR_EXEMPT));
        assertEquals(why, DecisionLog.StepKind.AR_EXEMPT.label);
        // Between a seen Accept tap and the delivery screen after it, the mark changes nothing either.
        DecisionLog.Entry tapped = passing(now).withStep(new DecisionLog.Step(DecisionLog.StepKind.ACCEPT_TAPPED, 1,
                "waiting for a delivery screen"));
        DecisionLog.Entry marked = tapped.withStep(new DecisionLog.Step(DecisionLog.StepKind.AR_EXEMPT, 2, ""));
        DecisionLog.Step delivery = new DecisionLog.Step(DecisionLog.StepKind.NOT_LEARNED, 3,
                AcceptedOfferTracker.DELIVERY_UNDER_WAY);
        assertEquals(DecisionLog.accepted(tapped.withStep(delivery)), DecisionLog.accepted(marked.withStep(delivery)));
        assertTrue(DecisionLog.accepted(marked.withStep(delivery)));
        // It survives a restart and shows in the shared report's line.
        DecisionLog.flush();
        DecisionLog.forgetCache();
        assertTrue(DecisionLog.hasStep(DecisionLog.recent(app, 1).get(0), DecisionLog.StepKind.AR_EXEMPT));
        String report = DecisionLog.report(app, 2);
        assertEquals(report, 2, report.split(" \\| ar-exempt", -1).length - 1);
        assertArrayEquals(before, DecisionLog.totals(app));
    }

    @Test public void whatFollowedNotCountingItNeverContradictsASeenAcceptTap() {
        // After the user's seen Accept tap a delivery screen came, but a delivery was already under way: the screen
        // proves nothing, the tap still counts the offer as accepted. The step's words say only what followed.
        long now = System.currentTimeMillis();
        DecisionLog.Step delivery = new DecisionLog.Step(DecisionLog.StepKind.NOT_LEARNED, now + 2,
                AcceptedOfferTracker.DELIVERY_UNDER_WAY);
        DecisionLog.Entry tapped = passing(now).withStep(new DecisionLog.Step(DecisionLog.StepKind.ACCEPT_TAPPED,
                now + 1, "waiting for a delivery screen")).withStep(delivery);
        assertTrue(DecisionLog.accepted(tapped));
        assertEquals(DecisionLog.Outcome.ACCEPTED, DecisionLog.outcome(tapped));
        assertEquals("Not counted from what followed: a delivery was already under way when it came, so the delivery "
                + "screen after it proves nothing", delivery.text());
        // Without the tap the same step leaves it uncounted, and the same words hold.
        DecisionLog.Entry untapped = passing(now).withStep(delivery);
        assertFalse(DecisionLog.accepted(untapped));
        assertEquals(DecisionLog.Outcome.PASSED, DecisionLog.outcome(untapped));
        assertFalse(DecisionLog.StepKind.NOT_LEARNED.label.contains("accepted"));
    }

    @Test public void everyKindAnOlderVersionWroteStillParsesWithNeutralWords() throws Exception {
        long now = System.currentTimeMillis();
        DecisionLog.record(app, passing(now));
        DecisionLog.flush();
        File file = new File(app.getFilesDir(), "decision-log.json");
        JSONArray stored = new JSONArray(new String(java.nio.file.Files.readAllBytes(file.toPath()),
                StandardCharsets.UTF_8));
        JSONArray steps = new JSONArray();
        String[] retired = {"ACCEPTED_LEARNED", "ACCEPTED_NOT_LEARNED", "ACCEPTED_BEST_SAVED",
                "ACCEPTED_MINIMUMS_UNCHANGED", "ACCEPTED_ADD_ON", "NOT_LEARNED", "DECLINE_TAUGHT", "DECLINE_NOT_TAUGHT"};
        for (int i = 0; i < retired.length; i++) {
            steps.put(new org.json.JSONObject().put("kind", retired[i]).put("at", now + i).put("detail", "d" + i));
        }
        stored.getJSONObject(0).put("steps", steps);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(stored.toString().getBytes(StandardCharsets.UTF_8));
        }
        DecisionLog.forgetCache();
        List<DecisionLog.Step> loaded = DecisionLog.recent(app, 1).get(0).steps;
        assertEquals(retired.length, loaded.size());
        String[] said = {"Accepted", "Accepted", "Accepted", "Accepted", "Accepted add-on",
                "Not counted from what followed", "Your Decline (older version)", "Your Decline (older version)"};
        for (int i = 0; i < retired.length; i++) {
            assertEquals(retired[i], loaded.get(i).kind.name());
            assertEquals(retired[i], said[i], loaded.get(i).kind.label);
            assertFalse(retired[i], loaded.get(i).text().toLowerCase(java.util.Locale.US).contains("learn"));
            assertFalse(retired[i], loaded.get(i).text().toLowerCase(java.util.Locale.US).contains("taught"));
        }
        assertTrue("an older acceptance still counts", DecisionLog.accepted(DecisionLog.recent(app, 1).get(0)));
        for (DecisionLog.StepKind kind : DecisionLog.StepKind.values()) {
            String words = kind.label.toLowerCase(java.util.Locale.US);
            assertFalse(kind.name(), words.contains("learn") || words.contains("adaptive") || words.contains("taught"));
        }
    }
}

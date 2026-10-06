package com.local.dasherfilter;

import android.app.Application;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The on-device decision history: merging, bounds, persistence, privacy filtering and the report text. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class DecisionLogTest {
    private Application app;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        DecisionLog.forgetCache();
    }

    private static DecisionLog.Entry entry(long at, int pay, OfferRule.Result result, DecisionLog.Action action) {
        return new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false, new OfferSnapshot(pay, 7.2, 21, 2), 1080,
                result, "dollars per mile", action, true, Arrays.asList("$7.90", "2 stops (7.2 mi) • 21 min"));
    }

    @Test
    public void laterActionOnTheSameOfferUpgradesItsEntry() {
        DecisionLog.record(app, entry(1000, 790, OfferRule.Result.DECLINE, DecisionLog.Action.DECLINE_TAPPED));
        DecisionLog.record(app, entry(1200, 790, OfferRule.Result.DECLINE, DecisionLog.Action.CONFIRMATION_TAPPED));
        // Repeated screen reads of the same offer with a lighter action change nothing.
        DecisionLog.record(app, entry(1400, 790, OfferRule.Result.DECLINE, DecisionLog.Action.DECLINE_TAPPED));

        List<DecisionLog.Entry> recent = DecisionLog.recent(app, 10);
        assertEquals(1, recent.size());
        assertEquals(DecisionLog.Action.CONFIRMATION_TAPPED, recent.get(0).action);
        assertEquals(1000, recent.get(0).at);
    }

    @Test
    public void differentOffersAndLaterRepeatsGetTheirOwnEntries() {
        DecisionLog.record(app, entry(1000, 790, OfferRule.Result.DECLINE, DecisionLog.Action.DECLINE_TAPPED));
        DecisionLog.record(app, entry(2000, 2500, OfferRule.Result.KEEP, DecisionLog.Action.PASSES));
        // The same facts again, well after the merge window, are a new offer.
        DecisionLog.record(app, entry(500_000, 790, OfferRule.Result.DECLINE, DecisionLog.Action.DECLINE_TAPPED));

        List<DecisionLog.Entry> recent = DecisionLog.recent(app, 10);
        assertEquals(3, recent.size());
        assertEquals(500_000, recent.get(0).at);
        assertEquals(OfferRule.Result.KEEP, recent.get(1).result);
    }

    @Test
    public void historyIsBoundedToTheNewestEntries() {
        for (int i = 0; i < DecisionLog.MAX_ENTRIES + 25; i++) {
            DecisionLog.record(app, entry(i * 1_000_000L, 100 + i, OfferRule.Result.KEEP, DecisionLog.Action.PASSES));
        }
        List<DecisionLog.Entry> recent = DecisionLog.recent(app, Integer.MAX_VALUE);
        assertEquals(DecisionLog.MAX_ENTRIES, recent.size());
        assertEquals(Integer.valueOf(100 + DecisionLog.MAX_ENTRIES + 24), recent.get(0).facts.payCents);
    }

    @Test
    public void historySurvivesAProcessRestart() {
        DecisionLog.record(app, entry(1000, 790, OfferRule.Result.DECLINE, DecisionLog.Action.DECLINE_TAPPED));
        DecisionLog.record(app, new DecisionLog.Entry(900_000, DecisionLog.Source.NOTIFICATION, true,
                new OfferSnapshot(null, null, null, null), 0, OfferRule.Result.REVIEW, "pay not found",
                DecisionLog.Action.SILENT_CARD, false, Collections.emptyList()));
        DecisionLog.flush();
        DecisionLog.forgetCache();

        List<DecisionLog.Entry> recent = DecisionLog.recent(app, 10);
        assertEquals(2, recent.size());
        DecisionLog.Entry review = recent.get(0);
        assertEquals(DecisionLog.Source.NOTIFICATION, review.source);
        assertTrue(review.addOn);
        assertFalse(review.autoDecline);
        assertNull(review.facts.payCents);
        DecisionLog.Entry declined = recent.get(1);
        assertEquals(Integer.valueOf(790), declined.facts.payCents);
        assertEquals(7.2, declined.facts.miles, 0.001);
        assertEquals(1080, declined.requiredCents);
        assertEquals(Arrays.asList("$7.90", "2 stops (7.2 mi) • 21 min"), declined.evidence);
    }

    @Test
    public void clearedHistoryStaysClearedAfterRestart() {
        DecisionLog.record(app, entry(1000, 790, OfferRule.Result.DECLINE, DecisionLog.Action.DECLINE_TAPPED));
        DecisionLog.flush();
        DecisionLog.clear(app);
        DecisionLog.flush();
        DecisionLog.forgetCache();
        assertTrue(DecisionLog.recent(app, 10).isEmpty());
    }

    @Test
    public void evidenceKeepsFiguresAndPayLabelsButNotNamesOrAddresses() {
        List<String> kept = DecisionLog.evidence(Arrays.asList(
                "$7.90", "Guaranteed (incl. tips)", "2 stops (7.2 mi) • 21 min", "Chick-fil-A",
                "123 Main St, Apt 4", "Jane D.", "Deliver by 9:45 PM", "+$2.00 Peak Pay", "Accept",
                "Call +1 555 123 4567", "Open 24 hrs"));
        assertEquals(Arrays.asList("$7.90", "Guaranteed (incl. tips)", "2 stops (7.2 mi) • 21 min",
                "+$2.00 Peak Pay"), kept);
    }

    @Test
    public void reportListsEachDecisionWithWhatWasRead() {
        DecisionLog.record(app, entry(1000, 790, OfferRule.Result.DECLINE, DecisionLog.Action.DECLINE_TAPPED));
        String report = DecisionLog.report(app, 10);
        assertTrue(report, report.contains(
                "| screen | DECLINE | pay $7.90 | needed $10.80 | 7.2 mi · 21 min · 2 stops"
                        + " | dollars per mile | Decline tapped"));
        assertTrue(report, report.contains("read: [$7.90, 2 stops (7.2 mi) • 21 min]"));
    }

    @Test
    public void diagnosticsReportStaysWithinIntentLimits() {
        List<String> longLines = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) longLines.add("$" + i + " " + String.join("", Collections.nCopies(120, "x")));
        for (int i = 0; i < DecisionLog.MAX_ENTRIES; i++) {
            DecisionLog.record(app, new DecisionLog.Entry(i * 1_000_000L, DecisionLog.Source.SCREEN, false,
                    new OfferSnapshot(100 + i, 7.2, 21, 2), 1080, OfferRule.Result.DECLINE, "dollars per mile",
                    DecisionLog.Action.DECLINE_TAPPED, true, longLines));
        }
        DiagnosticLog.setEnabled(app, true);
        for (int i = 0; i < 400; i++) DiagnosticLog.log(app, "screen", String.join("", Collections.nCopies(400, "y")));
        String report = DiagnosticLog.report(app);
        assertTrue(String.valueOf(report.length()), report.length() <= 60_000 + 40);
        assertTrue(report.contains("== Decision history"));
    }

    @Test
    public void corruptHistoryFileStartsFresh() throws Exception {
        java.io.File file = new java.io.File(app.getFilesDir(), "decision-log.json");
        try (java.io.FileWriter out = new java.io.FileWriter(file)) {
            out.write("{not json");
        }
        DecisionLog.forgetCache();
        assertTrue(DecisionLog.recent(app, 10).isEmpty());
        DecisionLog.record(app, entry(1000, 790, OfferRule.Result.DECLINE, DecisionLog.Action.DECLINE_TAPPED));
        assertEquals(1, DecisionLog.recent(app, 10).size());
    }

    @Test
    public void allTimeTotalsFollowEachOfferAndItsLaterStepsUntilCleared() {
        DecisionLog.record(app, entry(1_000, 1500, OfferRule.Result.KEEP, DecisionLog.Action.PASSES));
        // A failing offer is review until the app acts on it; tapping Decline moves it to filtered.
        DecisionLog.record(app, entry(2_000, 700, OfferRule.Result.DECLINE, DecisionLog.Action.DECLINE_REFUSED));
        assertArrayEquals(new int[] {1, 0, 1}, DecisionLog.totals(app));
        DecisionLog.record(app, entry(2_000, 700, OfferRule.Result.DECLINE, DecisionLog.Action.DECLINE_TAPPED));
        assertArrayEquals("the same offer, now filtered, is not counted twice", new int[] {1, 1, 0},
                DecisionLog.totals(app));
        DecisionLog.clear(app);
        assertArrayEquals(new int[] {0, 0, 0}, DecisionLog.totals(app));
    }

    /** Dasher's offer notification as the app records it: no pay shown, so left to review. */
    private static DecisionLog.Entry notice(long at, DecisionLog.Action action) {
        return new DecisionLog.Entry(at, DecisionLog.Source.NOTIFICATION, false, OfferSnapshot.UNKNOWN, 0,
                OfferRule.Result.REVIEW, "pay not found", action, true, Collections.emptyList());
    }

    /** A screen offer of {@code pay} needing $10.00, declined at once unless it passes. */
    private static DecisionLog.Entry onScreen(long at, int pay) {
        boolean passes = pay >= 1000;
        return new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false, new OfferSnapshot(pay, 4.0, 21, 2), 1000,
                passes ? OfferRule.Result.KEEP : OfferRule.Result.DECLINE,
                passes ? "meets enabled rules" : "flat minimum",
                passes ? DecisionLog.Action.PASSES : DecisionLog.Action.DECLINE_TAPPED, true,
                Arrays.asList("$" + pay / 100 + "." + String.format(java.util.Locale.US, "%02d", pay % 100),
                        "2 stops (4.0 mi) • 21 min"));
    }

    @Test
    public void notificationThenTheScreensReadingOfTheSameOfferIsOneOffer() throws Exception {
        DecisionLog.record(app, notice(10_000, DecisionLog.Action.CHECK_BELL));
        DecisionLog.record(app, onScreen(24_000, 900));

        List<DecisionLog.Entry> recent = DecisionLog.recent(app, 10);
        assertEquals("one offer, one line", 1, recent.size());
        assertEquals(DecisionLog.Source.SCREEN, recent.get(0).source);
        assertEquals(OfferRule.Result.DECLINE, recent.get(0).result);
        assertArrayEquals("counted once, with the screen's decision", new int[] {0, 1, 0}, DecisionLog.totals(app));
        String report = DecisionLog.report(app, 10);
        assertTrue(report, report.contains("    notification 14 s earlier | REVIEW | pay ? | pay not found"
                + " | Rang once: open Dasher to check it\n"));
        assertFalse(report, report.contains("| notification | REVIEW |"));

        DecisionLog.flush();
        DecisionLog.forgetCache();
        recent = DecisionLog.recent(app, 10);
        assertEquals(1, recent.size());
        assertTrue("the notification is kept with the offer", recent.get(0).toJson().has("notification"));
        assertArrayEquals(new int[] {0, 1, 0}, DecisionLog.totals(app));
    }

    @Test
    public void aSecondReadingOfAnOfferTakesTheNotificationsRecordedSinceItsLine() throws Exception {
        // Read on screen, then (Dasher left) its notification, and a different offer's; then read again.
        DecisionLog.record(app, onScreen(0, 1200));
        DecisionLog.record(app, notice(5_000, DecisionLog.Action.CHECK_BELL));
        DecisionLog.record(app, new DecisionLog.Entry(9_000, DecisionLog.Source.NOTIFICATION, false,
                new OfferSnapshot(1500, null, null, null), 1000, OfferRule.Result.KEEP, "meets enabled rules",
                DecisionLog.Action.BELL, true, Collections.emptyList()));
        assertArrayEquals(new int[] {2, 0, 1}, DecisionLog.totals(app));
        DecisionLog.record(app, onScreen(12_000, 1200));

        List<DecisionLog.Entry> recent = DecisionLog.recent(app, 10);
        assertEquals("the same offer is one line; different pay is another offer", 2, recent.size());
        assertEquals(1500, (int) recent.get(0).facts.payCents);
        DecisionLog.Entry offer = recent.get(1);
        assertEquals(DecisionLog.Source.SCREEN, offer.source);
        assertEquals(5_000, offer.toJson().getJSONObject("notification").getLong("at"));
        assertArrayEquals("counted once, with the screen's decision", new int[] {2, 0, 0}, DecisionLog.totals(app));
        assertTrue(DecisionLog.report(app, 10).contains("    notification 5 s later | REVIEW | pay ? | pay not found"
                + " | Rang once: open Dasher to check it\n"));
    }

    @Test
    public void aFoldedNotificationKeepsNoScreenLinesOfItsOwn() throws Exception {
        DecisionLog.record(app, new DecisionLog.Entry(1_000, DecisionLog.Source.NOTIFICATION, false,
                OfferSnapshot.UNKNOWN, 0, OfferRule.Result.REVIEW, "pay not found", DecisionLog.Action.CHECK_BELL, true,
                Collections.singletonList("$5.00 guaranteed Store A")));
        DecisionLog.record(app, onScreen(6_000, 900));
        DecisionLog.flush();

        String file = new String(java.nio.file.Files.readAllBytes(
                new java.io.File(app.getFilesDir(), "decision-log.json").toPath()), "UTF-8");
        assertFalse(file, file.contains("Store A"));
        assertEquals(0, DecisionLog.recent(app, 1).get(0).toJson().getJSONObject("notification")
                .getJSONArray("evidence").length());

        // Whatever a stored file says, a folded notification comes back without lines and is never nested deeper.
        org.json.JSONObject inner = notice(2_000, DecisionLog.Action.SILENT_CARD).toJson()
                .put("evidence", new org.json.JSONArray(Collections.singletonList("Store B")));
        org.json.JSONObject stored = onScreen(3_000, 900).toJson()
                .put("notification", notice(2_500, DecisionLog.Action.CHECK_BELL).toJson()
                        .put("evidence", new org.json.JSONArray(Collections.singletonList("Store C")))
                        .put("notification", inner));
        try (java.io.FileWriter out = new java.io.FileWriter(new java.io.File(app.getFilesDir(),
                "decision-log.json"))) {
            out.write(new org.json.JSONArray().put(stored).toString());
        }
        DecisionLog.forgetCache();
        org.json.JSONObject nested = DecisionLog.recent(app, 1).get(0).toJson().getJSONObject("notification");
        assertEquals(0, nested.getJSONArray("evidence").length());
        assertFalse(nested.has("notification"));
    }

    @Test
    public void eachScreenOfferTakesOnlyItsAdjacentNotification() {
        DecisionLog.record(app, notice(0, DecisionLog.Action.CHECK_BELL));
        DecisionLog.record(app, notice(200_000, DecisionLog.Action.CHECK_BELL));
        DecisionLog.record(app, onScreen(210_000, 900));
        DecisionLog.record(app, notice(400_000, DecisionLog.Action.CHECK_BELL));
        DecisionLog.record(app, onScreen(409_000, 800));

        List<DecisionLog.Entry> recent = DecisionLog.recent(app, 10);
        assertEquals(3, recent.size());
        assertEquals(409_000, recent.get(0).at);
        assertTrue(DecisionLog.report(app, 10).contains("    notification 9 s earlier |"));
        assertEquals(210_000, recent.get(1).at);
        assertTrue(DecisionLog.report(app, 10).contains("    notification 10 s earlier |"));
        assertEquals("an offer never opened stays a review of its own", DecisionLog.Source.NOTIFICATION,
                recent.get(2).source);
        assertArrayEquals(new int[] {0, 2, 1}, DecisionLog.totals(app));
    }

    @Test
    public void aScreenFrameWithNothingReadIsNeitherFoldedNorInTheWay() throws Exception {
        DecisionLog.record(app, notice(0, DecisionLog.Action.CHECK_BELL));
        DecisionLog.record(app, new DecisionLog.Entry(3_000, DecisionLog.Source.SCREEN, false, OfferSnapshot.UNKNOWN,
                0, OfferRule.Result.REVIEW, "pay not found", DecisionLog.Action.NEEDS_REVIEW, true,
                Collections.emptyList()));
        DecisionLog.record(app, onScreen(5_000, 900));

        List<DecisionLog.Entry> recent = DecisionLog.recent(app, 10);
        assertEquals(2, recent.size());
        assertEquals(OfferRule.Result.DECLINE, recent.get(0).result);
        assertTrue(recent.get(0).toJson().toString().contains("notification"));
        assertEquals(3_000, recent.get(1).at);
        assertArrayEquals(new int[] {0, 1, 1}, DecisionLog.totals(app));
    }

    @Test
    public void historyRecordedBeforeTheFoldIsFoldedOnceWithItsTotals() throws Exception {
        org.json.JSONArray stored = new org.json.JSONArray()
                .put(notice(1_000_000, DecisionLog.Action.CHECK_BELL).toJson())
                .put(new DecisionLog.Entry(1_014_000, DecisionLog.Source.SCREEN, false,
                        new OfferSnapshot(900, 4.0, 21, 2), 1000, OfferRule.Result.DECLINE, "flat minimum",
                        DecisionLog.Action.CONFIRMATION_TAPPED, true, Collections.emptyList()).toJson())
                .put(onScreen(2_000_000, 700).toJson())
                // Posted without sound: Dasher was on screen, and the screen had read the offer a moment before.
                .put(notice(2_001_000, DecisionLog.Action.SILENT_CARD).toJson())
                .put(notice(3_000_000, DecisionLog.Action.CHECK_BELL).toJson())
                .put(onScreen(4_000_000, 1500).toJson())
                .put(notice(4_005_000, DecisionLog.Action.SILENT_CARD).toJson())
                .put(notice(5_000_000, DecisionLog.Action.CHECK_BELL).toJson())
                // 90 s after the notification before it: a different offer.
                .put(onScreen(5_090_000, 600).toJson());
        try (java.io.FileWriter out = new java.io.FileWriter(new java.io.File(app.getFilesDir(),
                "decision-log.json"))) {
            out.write(stored.toString());
        }
        app.getSharedPreferences("decision_totals", android.content.Context.MODE_PRIVATE).edit()
                .putInt("PASSED", 1).putInt("FILTERED", 3).putInt("REVIEW", 5).commit();
        DecisionLog.forgetCache();

        assertArrayEquals(new int[] {1, 3, 2}, DecisionLog.totals(app));
        assertEquals(6, DecisionLog.recent(app, 20).size());

        // Once only.
        DecisionLog.flush();
        DecisionLog.forgetCache();
        assertArrayEquals(new int[] {1, 3, 2}, DecisionLog.totals(app));
        assertEquals(6, DecisionLog.recent(app, 20).size());
    }

    @Test
    public void farApartKnownFailuresAddOnsAndContradictionsStaySeparate() {
        // Each pair is ten minutes from the next.
        DecisionLog.record(app, notice(0, DecisionLog.Action.CHECK_BELL));
        DecisionLog.record(app, onScreen(40_000, 900));
        assertEquals("beyond the 30 s an offer without a countdown allows", 2, DecisionLog.recent(app, 20).size());

        DecisionLog.record(app, new DecisionLog.Entry(600_000, DecisionLog.Source.NOTIFICATION, false,
                new OfferSnapshot(700, null, null, null), 1000, OfferRule.Result.DECLINE, "flat minimum",
                DecisionLog.Action.NOTIFICATION_DECLINE_SENT, true, Collections.emptyList()));
        DecisionLog.record(app, onScreen(605_000, 800));
        assertEquals("a known failure the notification acted on is never folded", 4,
                DecisionLog.recent(app, 20).size());

        DecisionLog.record(app, new DecisionLog.Entry(1_200_000, DecisionLog.Source.NOTIFICATION, true,
                OfferSnapshot.UNKNOWN, 0, OfferRule.Result.REVIEW, "pay not found", DecisionLog.Action.CHECK_BELL, true,
                Collections.emptyList()));
        DecisionLog.record(app, onScreen(1_205_000, 850));
        assertEquals("an add-on's notification never folds into a standalone offer", 6,
                DecisionLog.recent(app, 20).size());

        DecisionLog.record(app, new DecisionLog.Entry(1_800_000, DecisionLog.Source.NOTIFICATION, false,
                new OfferSnapshot(1500, null, null, null), 1000, OfferRule.Result.KEEP, "meets enabled rules",
                DecisionLog.Action.BELL, true, Collections.emptyList()));
        DecisionLog.record(app, onScreen(1_805_000, 900));
        assertEquals("different pay is a different offer", 8, DecisionLog.recent(app, 20).size());
    }

    @Test
    public void aNotificationIsNeverFoldedPastAnotherScreenOffer() {
        DecisionLog.record(app, new DecisionLog.Entry(0, DecisionLog.Source.NOTIFICATION, true,
                OfferSnapshot.UNKNOWN, 0, OfferRule.Result.REVIEW, "pay not found", DecisionLog.Action.CHECK_BELL, true,
                Collections.emptyList()));
        // A standalone offer cannot take an add-on's notification, and the add-on after it is past that offer.
        DecisionLog.record(app, onScreen(10_000, 900));
        DecisionLog.record(app, new DecisionLog.Entry(20_000, DecisionLog.Source.SCREEN, true,
                new OfferSnapshot(300, 2.0, 8, 2), 0, OfferRule.Result.REVIEW,
                "add-on has missing or ambiguous incremental/route evidence", DecisionLog.Action.NEEDS_REVIEW, true,
                Collections.emptyList()));
        assertEquals(3, DecisionLog.recent(app, 20).size());
    }

    // ---- 0.5.0: the bar each decision used, whether Autopilot set it, and the rules model ----

    /** A decision at an 82% Autopilot bar: $5.75 for 6.6 mi and 27 min under $4 / $1.00 per mile / $15 per hour. */
    private static OfferRule.Decision belowMinimums() {
        FilterSettings rules = new FilterSettings(true, 400, 100, 25, 0, true, FilterSettings.GOAL_TOP_TIER, 82);
        return OfferRule.evaluate(new OfferSnapshot(575, 6.6, 27, 2), rules);
    }

    @Test
    public void aLineKeepsTheBarItUsedWhetherAutopilotSetItAndItsModelAcrossARestart() throws Exception {
        OfferRule.Decision decision = belowMinimums();
        assertEquals(OfferRule.Result.KEEP, decision.result);
        assertTrue(decision.belowMinimums);
        DecisionLog.Entry line = DecisionLog.Entry.of(DecisionLog.Source.SCREEN, false, decision.basis, decision,
                DecisionLog.Action.PASSES, true, Arrays.asList("$5.75", "2 stops (6.6 mi) • 27 min"));
        assertEquals(82, line.barPercent);
        assertTrue(line.autopilot);
        assertEquals(DecisionLog.MODEL, line.model);
        assertEquals(85, line.scorePercent);
        org.json.JSONObject json = line.toJson();
        assertEquals(82, json.getInt("bar"));
        assertTrue(json.getBoolean("auto"));
        assertEquals(2, json.getInt("model"));
        assertEquals(85, json.getInt("score"));

        DecisionLog.clear(app);
        DecisionLog.record(app, line);
        DecisionLog.flush();
        DecisionLog.forgetCache();
        DecisionLog.Entry loaded = DecisionLog.recent(app, 1).get(0);
        assertEquals(82, loaded.barPercent);
        assertTrue(loaded.autopilot);
        assertEquals(2, loaded.model);
        assertEquals(85, loaded.scorePercent);
        String report = DecisionLog.report(app, 1);
        assertTrue(report, report.contains(" | score 85% | bar 82% auto | 6.6 mi · 27 min · 2 stops | "));

        // A copy at another bar (a re-recorded line) keeps what it is given, and nothing else changes.
        DecisionLog.Entry copied = loaded.withBar(100, false);
        assertEquals(100, copied.barPercent);
        assertFalse(copied.autopilot);
        assertEquals(loaded.scorePercent, copied.scorePercent);
        assertEquals(loaded.facts.fingerprint(), copied.facts.fingerprint());
    }

    @Test
    public void aLineAtExactlyTheMinimumsWithAutopilotOffNamesNoBar() throws Exception {
        DecisionLog.Entry plain = entry(1000, 790, OfferRule.Result.DECLINE, DecisionLog.Action.DECLINE_TAPPED);
        assertEquals(100, plain.barPercent);
        assertFalse(plain.autopilot);
        assertEquals(DecisionLog.MODEL, plain.model);
        org.json.JSONObject json = plain.toJson();
        assertEquals(100, json.getInt("bar"));
        assertFalse(json.getBoolean("auto"));
        assertEquals(2, json.getInt("model"));
        DecisionLog.clear(app);
        DecisionLog.record(app, plain);
        DecisionLog.record(app, entry(500_000, 900, OfferRule.Result.KEEP, DecisionLog.Action.PASSES)
                .withBar(100, true));
        DecisionLog.record(app, entry(900_000, 950, OfferRule.Result.KEEP, DecisionLog.Action.PASSES)
                .withBar(120, false));
        String report = DecisionLog.report(app, 3);
        assertFalse(report, report.contains("| bar 100% |"));
        assertTrue(report, report.contains(" | bar 100% auto | "));
        assertTrue(report, report.contains(" | bar 120% | "));
        assertEquals(report, 2, report.split(" \\| bar ", -1).length - 1);
    }

    @Test
    public void aLineFromAnOlderVersionLoadsAsModelOneWithItsAreaScore() throws Exception {
        org.json.JSONObject legacy = new org.json.JSONObject()
                .put("at", 1_000_000L).put("source", "SCREEN").put("addOn", false).put("required", 1080)
                .put("result", "KEEP").put("reason", "score 121% (needs 100%)").put("action", "PASSES")
                .put("autoDecline", true).put("evidence", new org.json.JSONArray())
                .put("pay", 1835).put("miles", 16.3).put("minutes", 33).put("stops", 2)
                .put("finalStopHotspotMiles", 0.25).put("score", 121)
                .put("notification", new org.json.JSONObject().put("at", 990_000L).put("source", "NOTIFICATION")
                        .put("addOn", false).put("required", 0).put("result", "REVIEW").put("reason", "pay not found")
                        .put("action", "CHECK_BELL").put("autoDecline", true).put("evidence", new org.json.JSONArray())
                        .put("score", 64));
        try (java.io.FileWriter out = new java.io.FileWriter(new java.io.File(app.getFilesDir(),
                "decision-log.json"))) {
            out.write(new org.json.JSONArray().put(legacy).toString());
        }
        DecisionLog.forgetCache();
        DecisionLog.Entry line = DecisionLog.recent(app, 1).get(0);
        assertEquals(DecisionLog.LEGACY_MODEL, line.model);
        assertEquals(121, line.scorePercent);
        assertEquals("a line that does not say was decided at the minimums", 100, line.barPercent);
        assertFalse(line.autopilot);
        assertEquals("still read", 0.25, line.facts.finalStopHotspotMiles, 0);
        assertEquals(DecisionLog.LEGACY_MODEL, line.notification.model);
        assertEquals(64, line.notification.scorePercent);
        String report = DecisionLog.report(app, 1);
        assertTrue(report, report.contains(" | needed $10.80 | area score 121% | 16.3 mi · 33 min · 2 stops | "));
        assertTrue(report, report.contains(" | pay ? | area score 64% | pay not found"));
        assertFalse(report, report.contains("hotspot"));
        assertFalse(report, report.contains("| bar "));

        // Written again (a newer line is recorded): the older line keeps its shape, without the hotspot distance.
        DecisionLog.record(app, entry(5_000_000, 790, OfferRule.Result.DECLINE, DecisionLog.Action.DECLINE_TAPPED));
        DecisionLog.flush();
        org.json.JSONArray stored = new org.json.JSONArray(new String(java.nio.file.Files.readAllBytes(
                new java.io.File(app.getFilesDir(), "decision-log.json").toPath()), "UTF-8"));
        org.json.JSONObject kept = stored.getJSONObject(0);
        assertEquals(1_000_000L, kept.getLong("at"));
        assertFalse(kept.toString(), kept.has("model"));
        assertFalse(kept.toString(), kept.has("bar"));
        assertFalse(kept.toString(), kept.has("auto"));
        assertFalse(kept.toString(), kept.has("finalStopHotspotMiles"));
        assertEquals(121, kept.getInt("score"));
        assertFalse(kept.getJSONObject("notification").has("model"));
        org.json.JSONObject fresh = stored.getJSONObject(1);
        assertEquals(2, fresh.getInt("model"));
        DecisionLog.forgetCache();
        assertEquals(DecisionLog.LEGACY_MODEL, DecisionLog.recent(app, 2).get(1).model);
        assertEquals(DecisionLog.MODEL, DecisionLog.recent(app, 2).get(0).model);
    }

    @Test
    public void aNewLineNeverWritesTheRetiredHotspotDistance() throws Exception {
        @SuppressWarnings("deprecation")
        OfferSnapshot facts = new OfferSnapshot(790, 7.2, 21, 2).withFinalStopHotspotMiles(1.5);
        DecisionLog.Entry line = new DecisionLog.Entry(1000, DecisionLog.Source.SCREEN, false, facts, 1080,
                OfferRule.Result.DECLINE, "dollars per mile", DecisionLog.Action.DECLINE_TAPPED, true,
                Collections.emptyList());
        assertFalse(line.toJson().has("finalStopHotspotMiles"));
        assertFalse(DecisionLog.facts(facts), DecisionLog.facts(facts).contains("hotspot"));
        assertEquals("7.2 mi · 21 min · 2 stops", DecisionLog.facts(facts));
    }

    @Test
    public void aFoldedNotificationKeepsItsOwnBarAndModel() throws Exception {
        DecisionLog.Entry notice = new DecisionLog.Entry(10_000, DecisionLog.Source.NOTIFICATION, false,
                OfferSnapshot.UNKNOWN, 0, OfferRule.Result.REVIEW, "pay not found", DecisionLog.Action.CHECK_BELL, true,
                Collections.emptyList()).withBar(91, true);
        DecisionLog.Entry screen = onScreen(24_000, 900).withBar(88, true);
        DecisionLog.clear(app);
        DecisionLog.record(app, notice);
        DecisionLog.record(app, screen);
        DecisionLog.flush();
        DecisionLog.forgetCache();
        DecisionLog.Entry line = DecisionLog.recent(app, 1).get(0);
        assertEquals(88, line.barPercent);
        assertEquals(91, line.notification.barPercent);
        assertTrue(line.notification.autopilot);
        assertEquals(DecisionLog.MODEL, line.notification.model);
    }
}

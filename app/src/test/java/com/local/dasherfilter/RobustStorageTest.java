package com.local.dasherfilter;

import android.app.Application;
import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** Synthetic interruption/overload receipts; no captured customer or payment information. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
public class RobustStorageTest {
    private Application app;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        DiagnosticLog.forgetCache();
        DiagnosticLog.clear(app);
        DiagnosticLog.setEnabled(app, true);
        DiagnosticLog.cleanUpOnce(app);
        DecisionLog.flush();
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        DecisionLog.flush();
    }

    @After public void cleanup() {
        DecisionLog.flush();
        DiagnosticLog.read(app);
    }

    private static DecisionLog.Entry entry(long at, int pay) {
        return new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(pay, 2.0, 10, 2), 0, OfferRule.Result.KEEP, "passes",
                DecisionLog.Action.PASSES, true, Collections.emptyList());
    }

    @Test public void scanTimingCannotEvictDecisionOrConfirmationLinesFromEitherBudget() {
        String important = "2026-10-02 00:00:00.000 +00:00 [decision] declined invented offer\n"
                + "2026-10-02 00:00:00.001 +00:00 [confirm] question answered\n";
        StringBuilder noisy = new StringBuilder(important);
        for (int i = 0; i < 300; i++) noisy.append("2026-10-02 00:00:01.000 +00:00 [scan] read 1234 ms ").append(i).append('\n');
        for (boolean bytes : new boolean[] {false, true}) {
            String kept = DiagnosticLog.fitLog(noisy.toString(), 500, bytes);
            assertTrue(kept, kept.startsWith(important));
            assertTrue(kept.getBytes(StandardCharsets.UTF_8).length <= 500);
            assertTrue(kept.contains("299"));
        }
    }

    @Test public void diskLogAndSharedReportPreserveContextDuringSlowScanFlood() {
        DiagnosticLog.log(app, "confirm", "unique confirmation context");
        DiagnosticLog.read(app);
        for (int i = 0; i < 200; i++) {
            DiagnosticLog.log(app, "scan", "slow read " + i + ": " + "1234567890".repeat(25));
            if (i % 20 == 0) DiagnosticLog.read(app);
        }
        assertTrue(DiagnosticLog.read(app).contains("unique confirmation context"));
        assertTrue(DiagnosticLog.fullReport(app).contains("unique confirmation context"));
        assertTrue(new File(app.getFilesDir(), "offer-filter-diagnostics.log").length() <= 20 * 1024);
    }

    @Test public void writerOverloadIsCountedWithoutRunningDroppedSuppliers() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch processed = new CountDownLatch(64);
        AtomicInteger evaluated = new AtomicInteger();
        DiagnosticLog.log(app, "test", () -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "writer held";
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            for (int i = 0; i < 100; i++) DiagnosticLog.log(app, "test", () -> {
                evaluated.incrementAndGet(); processed.countDown(); return "safe numeric counter";
            });
            assertTrue(DiagnosticLog.lossSummary(), DiagnosticLog.lossSummary().contains("dropped lines=36"));
            assertEquals(0, evaluated.get());
        } finally { release.countDown(); }
        // A full bounded writer queue can reject read()'s flush barrier. Wait for the admitted suppliers,
        // rather than making their scheduling speed part of the overload assertion.
        assertTrue(processed.await(5, TimeUnit.SECONDS));
        DiagnosticLog.read(app);
        assertEquals(64, evaluated.get());
    }

    @Test public void aSlowWriterCannotResurrectLinesAfterHistoryWasCleared() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        DiagnosticLog.log(app, "test", () -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "obsolete synthetic line";
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try { DiagnosticLog.clear(app); } finally { release.countDown(); }
        assertFalse(DiagnosticLog.read(app).contains("obsolete synthetic line"));
        DiagnosticLog.log(app, "test", "fresh synthetic line");
        assertTrue(DiagnosticLog.read(app).contains("fresh synthetic line"));
    }

    @Test public void interruptedHistoryReplacementKeepsThePreviousCompleteHistory() throws Exception {
        DecisionLog.record(app, entry(1000, 700));
        DecisionLog.flush();
        File file = new File(app.getFilesDir(), "decision-log.json");
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream unfinished = atomic.startWrite();
        unfinished.write("[{incomplete".getBytes(StandardCharsets.UTF_8));
        unfinished.close(); // simulate a process stopping before finishWrite/failWrite
        DecisionLog.forgetCache();
        assertEquals(Integer.valueOf(700), DecisionLog.recent(app, 10).get(0).facts.payCents);
    }

    @Test public void malformedOneEntryDoesNotDiscardValidNeighboringHistory() throws Exception {
        JSONArray data = new JSONArray().put(entry(1000, 700).toJson()).put("bad entry")
                .put(entry(2000, 900).toJson());
        Files.write(new File(app.getFilesDir(), "decision-log.json").toPath(),
                data.toString().getBytes(StandardCharsets.UTF_8));
        DecisionLog.forgetCache();
        assertEquals(2, DecisionLog.recent(app, 10).size());
        assertEquals(Integer.valueOf(900), DecisionLog.recent(app, 10).get(0).facts.payCents);
        assertTrue(DiagnosticLog.read(app).contains("unreadable history entries skipped: 1"));
    }

    @Test public void historyClearRemovesAtomicRecoveryFilesToo() throws Exception {
        DecisionLog.record(app, entry(1000, 700));
        DecisionLog.flush();
        File file = new File(app.getFilesDir(), "decision-log.json");
        FileOutputStream unfinished = new AtomicFile(file).startWrite();
        unfinished.write("[".getBytes(StandardCharsets.UTF_8));
        unfinished.close();
        DecisionLog.clear(app);
        DecisionLog.flush();
        DecisionLog.forgetCache();
        assertTrue(DecisionLog.recent(app, 10).isEmpty());
        assertFalse(new File(file.getPath() + ".bak").exists());
        assertFalse(new File(file.getPath() + ".new").exists());
    }

    @Test public void provenFreshSameFactsOfferKeepsItsOwnOutcomeAndCountsOnce() {
        OfferSnapshot facts = new OfferSnapshot(700, 2.0, 10, 2);
        DecisionLog.Entry previous = new DecisionLog.Entry(1000, DecisionLog.Source.SCREEN, false,
                facts, 1200, OfferRule.Result.DECLINE, "minimum payout", DecisionLog.Action.USER_TOOK_OVER,
                true, Collections.emptyList());
        DecisionLog.record(app, previous, 8);
        DecisionLog.record(app, new DecisionLog.Entry(59000, DecisionLog.Source.NOTIFICATION, false,
                new OfferSnapshot(null, null, null, null), 0, OfferRule.Result.REVIEW, "figures missing",
                DecisionLog.Action.CHECK_BELL, true, Collections.emptyList()).withAlertTag("fresh", false));
        DecisionLog.Entry fresh = new DecisionLog.Entry(60000, DecisionLog.Source.SCREEN, false,
                facts, 1200, OfferRule.Result.DECLINE, "minimum payout", DecisionLog.Action.DECLINE_TAPPED,
                true, Collections.emptyList());
        java.util.List<DecisionLog.Entry> paired = DecisionLog.record(app, fresh, 35, true);
        assertEquals(1, paired.size());
        assertEquals("fresh", paired.get(0).alertTag);
        java.util.List<DecisionLog.Entry> recent = DecisionLog.recent(app, 10);
        assertEquals(2, recent.size());
        assertEquals(60000, recent.get(0).at);
        assertEquals(DecisionLog.Action.DECLINE_TAPPED, recent.get(0).action);
        assertEquals(DecisionLog.Action.USER_TOOK_OVER, recent.get(1).action);
        assertEquals(59000, recent.get(0).notification.at);
        int[] totals = DecisionLog.totals(app);
        assertEquals(1, totals[DecisionLog.Tally.FILTERED.ordinal()]);
        assertEquals(1, totals[DecisionLog.Tally.REVIEW.ordinal()]);
        assertEquals(0, totals[DecisionLog.Tally.PASSED.ordinal()]);

        // Confirmation is a later action on this incarnation, not a third offer.
        DecisionLog.record(app, new DecisionLog.Entry(61000, DecisionLog.Source.SCREEN, false,
                facts, 1200, OfferRule.Result.DECLINE, "minimum payout", DecisionLog.Action.CONFIRMATION_TAPPED,
                true, Collections.emptyList()), 34);
        DecisionLog.flush();
        DecisionLog.forgetCache();
        recent = DecisionLog.recent(app, 10);
        assertEquals(2, recent.size());
        assertEquals(DecisionLog.Action.CONFIRMATION_TAPPED, recent.get(0).action);
        assertEquals(DecisionLog.Action.USER_TOOK_OVER, recent.get(1).action);
        assertArrayEquals(totals, DecisionLog.totals(app));
    }

    @Test public void reportsNameCurrentRuleContextManualFloorsAndRecordedOutcomes() throws Exception {
        DeclinedFloor declined = new DeclinedFloor(1100, new AcceptedBest(1300, 25, 1700, 4.5, 2200, 3));
        FilterSettings rules = new FilterSettings(true, 1200, 150, 50, 400, 5, true, 1900,
                new AcceptedBest(1800, 30, 1200, 3.0, 1400, 2), declined, true, 50, 97);
        FilterStore.save(app, rules);
        DecisionLog.record(app, entry(1000, 700));
        String shared = DiagnosticLog.fullReport(app);
        assertTrue(shared.contains("Current when this report was generated"));
        assertTrue(shared.contains("minimum scale percent=97"));
        assertTrue(shared.contains("hotspot proximity hundredths/mi=50"));
        assertTrue(shared.contains("learning now=on"));
        assertTrue(shared.contains("outcome PASSED"));
        assertTrue(shared.matches("(?s).*learning \\(auto-decline and Adaptive minimum both on\\) since="
                + "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} (?:Z|[+-]\\d{2}:\\d{2}).*"));
        JSONObject json = new JSONObject(OfferReport.text(OfferReport.Problem.OTHER, "test", 1, "Android test",
                rules, entry(1000, 700)));
        JSONObject saved = json.getJSONObject("rules");
        assertEquals(97, saved.getInt("minimumScalePercent"));
        assertTrue(saved.getString("context").startsWith("current rules"));
        JSONObject floor = saved.getJSONObject("declinedByHand");
        assertEquals(1100, floor.getInt("payCents"));
        assertEquals(1300, floor.getInt("minutePay"));
        assertEquals(25, floor.getInt("minutes"));
        assertEquals(1700, floor.getInt("milePay"));
        assertEquals(4.5, floor.getDouble("miles"), 0);
        assertEquals(2200, floor.getInt("stopPay"));
        assertEquals(3, floor.getInt("stops"));
    }
}

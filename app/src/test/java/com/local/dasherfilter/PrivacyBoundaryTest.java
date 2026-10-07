package com.local.dasherfilter;

import android.app.Application;
import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

/**
 * Synthetic privacy regressions only; no recovered diagnostic text or real payment details. Among them, where Dasher's
 * acceptance rate may go: it leaves the phone only in what the user sends (Share report, feedback with masked
 * diagnostics attached, and Report this offer, whose dialog says so before Send), never in the automatic summary after
 * a dash or in feedback without diagnostics; Clear history removes it; and no text of Dasher's question goes with it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class PrivacyBoundaryTest {
    private static final String QUESTION = "Are you sure you want to decline this offer?";
    private static final String WARNING = "Declining this offer may lower your acceptance rate";
    /** Dasher's question as the screen reader hands it over: the rate (37%, a figure nothing else here shows). */
    private static final List<String> ASKED = Arrays.asList(QUESTION, WARNING, "37%", "Decline offer", "Go back");
    /** The worked example's offers, newest first: pay in cents, miles, minutes; two stops each. */
    private static final int[] PAY = {1625, 975, 700, 1350, 575, 800, 2250, 600, 1100, 350, 925, 1490, 400, 1050,
            725, 1700, 875, 500, 1225, 650};
    private static final double[] MILES = {6.2, 11.8, 2.2, 7.0, 6.6, 3.0, 12.6, 7.9, 5.1, 4.8, 6.3, 9.9, 2.6, 4.4,
            5.5, 8.8, 7.4, 3.2, 6.0, 4.1};
    private static final int[] MINUTES = {26, 41, 14, 28, 27, 16, 44, 29, 23, 19, 25, 38, 15, 20, 24, 33, 31, 18, 26,
            22};

    private Application app;
    private long wall;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        DiagnosticLog.forgetCache();
        DiagnosticLog.clear(app);
        DiagnosticLog.setEnabled(app, true);
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
    }

    @After public void cleanup() {
        DiagnosticLog.forgetCache();
        AutopilotRuntime.forgetCache();
        AutopilotRuntime.executorForTests = null;
        AutopilotRuntime.wallClock = System::currentTimeMillis;
        // Whatever these tests sent, the real feedback service would have taken.
        FakeFeedbackTransport.assertHonored();
    }

    @Test public void paymentScreenNeverReachesEitherLogOrDecisionEvidence() {
        String card = "Card details, 4111 1111 1111 1111, CVV, 123, Available balance $123.45";
        DiagnosticLog.logScreen(app, card);
        DiagnosticLog.log(app, "screen", card);
        String combined = DiagnosticLog.readScreens(app) + DiagnosticLog.read(app);
        assertFalse(combined.contains("4111"));
        assertFalse(combined.contains("123.45"));
        assertFalse(combined.contains("CVV"));
        assertTrue(combined.contains(DiagnosticLog.NOT_KEPT));
        assertTrue(DecisionLog.evidence(Arrays.asList("Card details", "$123.45", "4111 1111 1111 1111")).isEmpty());
    }

    @Test public void cleanupPurgesBothLegacyLogsAndQueueOnce() throws Exception {
        DiagnosticLog.read(app); // finish the writer before simulating a previous installation
        app.getSharedPreferences("offer_filter_diagnostics", Context.MODE_PRIVATE).edit()
                .remove(DiagnosticLog.CLEANED_UP).commit();
        File screens = new File(app.getFilesDir(), "dasher-screens.log");
        File offers = new File(app.getFilesDir(), "offer-filter-diagnostics.log");
        Files.write(screens.toPath(), "Card details, CVV 123\n".getBytes(StandardCharsets.UTF_8));
        Files.write(offers.toPath(), ("old [screen] labels=[Card details, $123.45]\n"
                + "old [screen] labels=[$7.50, 2 stops, Deliver to Robin Q]\n").getBytes(StandardCharsets.UTF_8));
        File queue = new File(app.getFilesDir(), "report-outbox");
        queue.mkdirs();
        File pending = new File(queue, "legacy-d.json");
        Files.write(pending.toPath(), "{\"body\":\"CVV 123\"}".getBytes(StandardCharsets.UTF_8));
        DiagnosticLog.forgetCache();
        DiagnosticLog.cleanUpOnce(app);
        assertFalse(screens.exists());
        assertFalse(pending.exists());
        String cleaned = new String(Files.readAllBytes(offers.toPath()), StandardCharsets.UTF_8);
        assertFalse(cleaned.contains("$123.45"));
        assertFalse(cleaned.contains("Robin"));
        assertFalse(cleaned.contains("$7.50"));
        assertTrue(cleaned.contains(DiagnosticLog.CLEANED_UP_LINE));
        Files.write(screens.toPath(), "new safe dash".getBytes(StandardCharsets.UTF_8));
        DiagnosticLog.forgetCache();
        DiagnosticLog.cleanUpOnce(app);
        assertEquals("new safe dash", new String(Files.readAllBytes(screens.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void partialPaymentScreenWithoutHeadingKeepsOnlyStructure() {
        assertFalse(PersonalText.recognizedDashScreen(Arrays.asList("731", "12/34", "Copy")));
        OfferFilterService.scanLooperForTests = android.os.Looper.getMainLooper();
        org.robolectric.android.controller.ServiceController<OfferFilterService> service =
                org.robolectric.Robolectric.buildService(OfferFilterService.class).create();
        try {
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1));
            Dashing.seen(app);
            android.view.accessibility.AccessibilityNodeInfo root = node("");
            for (String label : new String[] {"731", "12/34", "Copy"}) {
                org.robolectric.Shadows.shadowOf(root).addChild(node(label));
            }
            org.robolectric.Shadows.shadowOf(service.get()).setRootInActiveWindow(root);
            android.view.accessibility.AccessibilityEvent event = android.view.accessibility.AccessibilityEvent.obtain(
                    android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            event.setPackageName("com.doordash.driverapp");
            service.get().onAccessibilityEvent(event);
            String captured = DiagnosticLog.readScreens(app) + DiagnosticLog.read(app);
            assertTrue(captured, captured.contains(PersonalText.UNKNOWN_NOT_KEPT));
            assertFalse(captured.contains("12/34"));
            assertFalse(captured.contains("labels=[731"));
            assertFalse(captured.contains("Copy"));
        } finally {
            service.destroy();
            OfferFilterService.scanLooperForTests = null;
        }
    }

    @Test public void oneControlOnPartialPaymentPageCannotPersistItsAmountThroughStatus() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        OfferFilterService.scanLooperForTests = android.os.Looper.getMainLooper();
        org.robolectric.android.controller.ServiceController<OfferFilterService> service =
                org.robolectric.Robolectric.buildService(OfferFilterService.class).create();
        try {
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1));
            Dashing.seen(app);
            android.view.accessibility.AccessibilityNodeInfo root = node("");
            android.view.accessibility.AccessibilityNodeInfo accept = null;
            for (String label : new String[] {"Accept", "$123.45", "731", "12/34", "Copy"}) {
                android.view.accessibility.AccessibilityNodeInfo child = node(label);
                if (label.equals("Accept")) {
                    accept = child;
                    child.setClickable(true);
                    child.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
                }
                org.robolectric.Shadows.shadowOf(root).addChild(child);
            }
            TestWindows.full(service.get(), root);
            android.view.accessibility.AccessibilityEvent event = android.view.accessibility.AccessibilityEvent.obtain(
                    android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            event.setPackageName("com.doordash.driverapp");
            service.get().onAccessibilityEvent(event);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            String status = FilterStore.lastStatus(app);
            assertTrue(status, status.contains("Both offer controls are not yet readable"));
            String captured = status + DiagnosticLog.readScreens(app) + DiagnosticLog.read(app);
            assertFalse(captured, captured.contains("123.45"));
            assertFalse(captured, captured.contains("12/34"));
            assertTrue(org.robolectric.Shadows.shadowOf(accept).getPerformedActions().isEmpty());
            assertTrue(DecisionLog.recent(app, 10).isEmpty());
        } finally {
            service.destroy();
            OfferFilterService.scanLooperForTests = null;
        }
    }

    private android.view.accessibility.AccessibilityNodeInfo node(String label) {
        android.view.accessibility.AccessibilityNodeInfo node =
                android.view.accessibility.AccessibilityNodeInfo.obtain(new android.view.View(app));
        node.setPackageName("com.doordash.driverapp");
        node.setText(label);
        node.setVisibleToUser(true);
        node.setEnabled(true);
        return node;
    }

    @Test public void failedCleanupCannotMarkDoneReadOldTextOrSendReports() throws Exception {
        DiagnosticLog.read(app);
        app.getSharedPreferences("offer_filter_diagnostics", Context.MODE_PRIVATE).edit()
                .remove(DiagnosticLog.CLEANED_UP).commit();
        File blocked = new File(app.getFilesDir(), "dasher-screens.log");
        blocked.delete();
        assertTrue(blocked.mkdir());
        File child = new File(blocked, "synthetic-private.txt");
        Files.write(child.toPath(), "731 12/34".getBytes(StandardCharsets.UTF_8));
        DiagnosticLog.forgetCache();
        assertFalse(DiagnosticLog.cleanUpOnce(app));
        assertFalse(app.getSharedPreferences("offer_filter_diagnostics", Context.MODE_PRIVATE)
                .getBoolean(DiagnosticLog.CLEANED_UP, false));
        assertEquals("Diagnostics paused: privacy cleanup incomplete.", DiagnosticLog.readScreens(app));
        assertTrue(child.delete());
        assertTrue(DiagnosticLog.cleanUpOnce(app));
        assertFalse(blocked.exists());
    }

    @Test public void anExistingUnlistableOutboxFailsCleanupClosed() throws Exception {
        DiagnosticLog.read(app);
        File queue = new File(app.getFilesDir(), "report-outbox");
        if (queue.exists()) assertTrue(queue.delete());
        Files.write(queue.toPath(), "synthetic inaccessible queue".getBytes(StandardCharsets.UTF_8));
        app.getSharedPreferences("offer_filter_diagnostics", Context.MODE_PRIVATE).edit()
                .remove(DiagnosticLog.CLEANED_UP).commit();
        DiagnosticLog.forgetCache();
        assertFalse(LegacyReportingCleanup.deleteOutbox(app));
        assertFalse(DiagnosticLog.cleanUpOnce(app));
        assertTrue(queue.delete());
        assertTrue(DiagnosticLog.cleanUpOnce(app));
    }

    @Test public void offerReportsAndLegacyEvidenceRejectPaymentOrPartialScreens() throws Exception {
        java.util.List<String> payment = Arrays.asList("Card details", "CVV", "731", "$123.45");
        assertTrue(OfferReport.redact(payment).isEmpty());
        DecisionLog.Entry old = new DecisionLog.Entry(System.currentTimeMillis(), DecisionLog.Source.SCREEN,
                false, new OfferSnapshot(750, 2.0, 10, 2), 2000, OfferRule.Result.DECLINE, "synthetic",
                DecisionLog.Action.DECLINE_TAPPED, true, payment);
        String report = OfferReport.text(OfferReport.Problem.MISREAD, "test", 1, "Android test",
                new FilterSettings(true, 2000, 0, 0, 0, 0), old);
        assertFalse(report, report.contains("Card details"));
        assertFalse(report, report.contains("731"));
        assertFalse(report, report.contains("123.45"));
        assertTrue(DecisionLog.Entry.fromJson(old.toJson()).evidence.isEmpty());

        // An unreadable offer's lines in the summary after a dash: a bare CVV and expiry with no payment heading (a
        // partial screen) is no recognized offer screen, so only a not-kept note is kept, as for a payment screen.
        java.util.List<String> partial = Arrays.asList("731", "12/34", "Copy");
        assertEquals(java.util.Collections.singletonList(PersonalText.UNKNOWN_NOT_KEPT), DashSummary.readLines(partial));
        assertEquals(java.util.Collections.singletonList(PersonalText.UNKNOWN_NOT_KEPT), DashSummary.readLines(payment));
        app.getSharedPreferences("dashing", Context.MODE_PRIVATE).edit().clear().commit();
        Dashing.forgetCache();
        Feedback.setAfterDash(app, true);
        Dashing.seen(app);
        long start = Dashing.currentStart(app);
        DashSummary.unreadable(app, partial);
        DashSummary.unreadable(app, payment);
        DashSummary.flush();
        String kept = app.getSharedPreferences("dash_summary", Context.MODE_PRIVATE).getString("anomalies", "");
        assertTrue(kept, kept.contains(PersonalText.UNKNOWN_NOT_KEPT));
        org.json.JSONObject model = new org.json.JSONObject()
                .put("counts", new org.json.JSONObject(app.getSharedPreferences("dash_summary", Context.MODE_PRIVATE)
                        .getString("counts", "{}")))
                .put("anomalies", new org.json.JSONArray(kept)).put("start", start);
        String summary = DashSummary.build(app, start, start + 60_000L, DashSummary.End.DASH_OVER, model);
        // What each kept problem says and the lines it kept: its time ("t", epoch milliseconds) is no screen text, and
        // its digits can spell "731" for a few seconds at a time.
        StringBuilder keptText = new StringBuilder();
        org.json.JSONArray problems = new org.json.JSONArray(kept);
        for (int i = 0; i < problems.length(); i++) {
            org.json.JSONObject problem = problems.getJSONObject(i);
            keptText.append(problem.optString("what")).append('\n');
            org.json.JSONArray lines = problem.optJSONArray("read");
            for (int k = 0; lines != null && k < lines.length(); k++) keptText.append(lines.getString(k)).append('\n');
        }
        assertEquals(2, problems.length());
        for (String secret : new String[] {"731", "12/34", "Card details", "123.45"}) {
            assertFalse(secret, keptText.toString().contains(secret));
            assertFalse(secret, summary.contains(secret));
        }
        // A recognized offer screen keeps its lines, masked twice.
        assertEquals(Arrays.asList("Guaranteed pay", "$7.90", "Accept", "Decline"),
                DashSummary.readLines(Arrays.asList("Guaranteed pay", "$7.90", "Accept", "Decline")));
        Feedback.setAfterDash(app, false);
        DashSummary.flush();
    }

    // ---- Dasher's acceptance rate ----

    /**
     * Autopilot on (400 / 100 / 25, goal 70%) over the worked example's 20 offers, and Dasher's decline question
     * showing 37%: logged as the screen reader logs the question's screen, and handed to Autopilot, which keeps the
     * reading, logs it and plans from it. Plans and readings run on this thread.
     */
    private void dasherShowedTheRate() {
        wall = System.currentTimeMillis();
        AutopilotRuntime.wallClock = () -> wall;
        AutopilotRuntime.executorForTests = Runnable::run;
        AutopilotRuntime.forgetCache();
        AutopilotStore.clear(app);
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 0));
        AutopilotRuntime.setAutopilot(app, true, 70);
        FilterSettings rules = FilterStore.load(app);
        for (int i = PAY.length - 1; i >= 0; i--) {
            OfferSnapshot facts = new OfferSnapshot(PAY[i], MILES[i], MINUTES[i], 2);
            OfferRule.Decision decision = OfferRule.evaluate(facts, rules);
            DecisionLog.record(app, new DecisionLog.Entry(wall - (2 + 3L * i) * 60_000L, DecisionLog.Source.SCREEN,
                    false, facts, decision.requiredCents, decision.result, decision.reason,
                    decision.result == OfferRule.Result.DECLINE ? DecisionLog.Action.CONFIRMATION_TAPPED
                            : DecisionLog.Action.PASSES, true, Collections.<String>emptyList()));
        }
        DiagnosticLog.log(app, "screen", "confirmation|||false|true win=full/-/dasher/100 labels=" + ASKED
                + " metricParts=[]");
        AutopilotRuntime.confirmationSeen(app, ASKED, null);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(37, AutopilotStore.reading(app, wall).percent);
    }

    private static void settle() {
        Feedback.flush();
        DashSummary.flush();
        Feedback.flush();
        FeedbackOutbox.flush();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    /** The diagnostic log's lines of one source, without their time and tag. */
    private List<String> logged(String source) {
        List<String> lines = new ArrayList<>();
        String tag = "[" + source + "] ";
        for (String line : DiagnosticLog.read(app).split("\n")) {
            int at = line.indexOf(tag);
            if (at >= 0) lines.add(line.substring(at + tag.length()));
        }
        return lines;
    }

    @Test public void dashersAcceptanceRateLeavesOnlyInWhatTheUserSends() throws Exception {
        FakeFeedbackTransport service = FakeFeedbackTransport.installed();
        dasherShowedTheRate();

        // Share report: Autopilot's line carries it, and so do the log lines it shows the user.
        String shared = DiagnosticLog.report(app);
        assertTrue(shared, shared.contains("\nAutopilot: on; goal 70%; bar 100%; mode RECOVERY; recovering yes; "));
        assertTrue(shared, shared.contains("; AR 37% (Dasher, "));
        assertTrue(shared, shared.contains("[autopilot] ar reading 37% (exempt no)"));

        // What the user sends: feedback with masked diagnostics attached (the report Share report builds), feedback
        // without them (only what was typed), and Report this offer (its rules carry Autopilot's state, as its
        // dialog says before Send).
        String attached = Feedback.sendFeedback(app, Feedback.Category.BUG, "It declined too much.", true, null);
        String typed = Feedback.sendFeedback(app, Feedback.Category.GENERAL, "Words only.", false, null);
        String offer = Feedback.sendOfferReport(app, DecisionLog.recent(app, 1).get(0),
                OfferReport.Problem.WRONG_DECLINE, "It declined this one.", null);
        settle();
        assertTrue(FeedbackDialogs.OFFER_REPORT_SAYS.contains("Autopilot's state (on or off, goal, bar, mode and last "
                + "change, and the acceptance rate it counts with: Dasher's latest, carried forward, or its own "
                + "estimate)"));
        int seen = 0;
        for (JSONObject request : service.requests()) {
            String token = request.getString("reportToken");
            String diagnostics = request.has("diagnostics") ? Feedback.unframed(request.getString("diagnostics")) : "";
            if (token.equals(attached)) {
                assertTrue(diagnostics, diagnostics.contains("; AR 37% (Dasher, "));
            } else if (token.equals(typed)) {
                assertFalse(request.has("diagnostics"));
                assertFalse(request.toString(), request.toString().contains("37%"));
            } else if (token.equals(offer)) {
                JSONObject rules = new JSONObject(diagnostics).getJSONObject("rules");
                assertEquals(37, rules.getInt("arPercent"));
                assertEquals("DASHER", rules.getString("arSource"));
                assertEquals("RECOVERY", rules.getString("autopilotMode"));
            } else {
                continue;
            }
            seen++;
        }
        assertEquals(3, seen);

        // The summary after a dash, which leaves without a tap: never the rate, even around a problem right beside
        // Dasher's question, Autopilot's reading and its plan.
        Feedback.setAfterDash(app, true);
        Dashing.forgetCache();
        app.getSharedPreferences("dashing", Context.MODE_PRIVATE).edit().clear().commit();
        Dashing.seen(app);
        long start = Dashing.currentStart(app);
        // The same question as other screen readers or another Dasher may hand it over: the rate written out, split
        // into two labels, abbreviated, or among an offer's own labels with no screen line of its own.
        for (String rate : new String[] {"37 percent", "37, %", "37 pct", "３７％"}) {
            DiagnosticLog.log(app, "screen", "confirmation|||false|true win=full/-/dasher/100 labels=[" + QUESTION
                    + ", " + WARNING + ", " + rate + ", Decline offer, Go back] metricParts=[]");
        }
        DiagnosticLog.log(app, "screen", "offer|1625:6.2:26:2|KEEP: passes|true|true win=full/-/dasher/100 "
                + "labels=[$16.25, " + WARNING + ", 37 percent, 0:35] metricParts=[2 stops (6.2 mi) • 26 min]");
        DashSummary.stuck(app, "waiting for the question");
        DashSummary.unreadable(app, ASKED);
        DashSummary.unreadable(app, Arrays.asList(QUESTION, "AR 37 %", "Decline offer"));
        DashSummary.unreadable(app, Arrays.asList(QUESTION, "37", "%", "Decline offer", "Go back"));
        DashSummary.flush();
        // A line whose kept evidence merged pay with the question, as a card's description can.
        long now = System.currentTimeMillis();
        DecisionLog.record(app, new DecisionLog.Entry(now, DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(575, 6.6, 27, 2), 675, OfferRule.Result.DECLINE, "dollars per hour",
                DecisionLog.Action.CONFIRMATION_TAPPED, true, Arrays.asList("$5.75",
                "Declining this $5.75 offer may lower your acceptance rate to 37 percent")));
        int before = service.count();
        DashSummary.summarize(app, start, System.currentTimeMillis() + 1_000L, DashSummary.End.DASH_OVER);
        settle();
        assertEquals("one summary", before + 1, service.count());
        JSONObject sent = service.requests().get(before);
        assertEquals("diagnostics", sent.getString("kind"));
        String summary = Feedback.unframed(sent.getString("diagnostics"));
        assertTrue(summary, summary.contains(" · bar 100% (Autopilot on) · "));
        assertTrue("the question's screen line stays, its rate masked", summary.contains(WARNING + ", #%"));
        assertTrue("split in two, both masked", summary.contains(WARNING + ", #, %, Decline offer"));
        assertTrue("the offer's pay and countdown stay",
                summary.contains("labels=[$16.25, " + WARNING + ", #%, 0:35]"));
        assertTrue(summary, summary.contains("read: [" + QUESTION + ", XX #%, Decline offer]"));
        assertTrue(summary, summary.contains("read: [$5.75, Declining this $5.75 offer xxx lower your acceptance rate "
                + "to # xxxxxxx]"));
        // Not one 37 anywhere, written however: a clock's ":37" alone would not be the rate.
        assertFalse(summary, java.util.regex.Pattern.compile("(?<![:\\d])37(?!\\d)|３７").matcher(summary)
                .find());
        assertFalse(summary, summary.contains("ar reading"));
        assertFalse(summary, java.util.regex.Pattern.compile("\\[autopilot\\] plan \\d").matcher(summary).find());
        assertTrue("Autopilot's lines without the rate stay", summary.contains("[autopilot] on; goal 70%"));
        Feedback.setAfterDash(app, false);
        DashSummary.flush();
    }

    @Test public void clearingHistoryLeavesNoReadingAndNoCopyOfItOnThePhone() throws Exception {
        dasherShowedTheRate();
        // Clear history's own steps, in the order Settings must take them: the decisions and the watched waiting,
        // then Autopilot (AutopilotRuntime.cleared runs after the history it plans from is gone, and moves its
        // generation on, so a reading or plan worked out from before is dropped instead of logged), and the logs
        // last, so a line Autopilot queued just before is deleted with them rather than landing in the new log.
        DecisionLog.clear(app);
        QualifyingWaitStore.clear(app);
        AutopilotRuntime.cleared(app);
        DiagnosticLog.clear(app);

        assertFalse("no copy of the rate in the log either", DiagnosticLog.read(app).contains("37"));
        assertNull(AutopilotStore.reading(app, wall));
        assertFalse(app.getSharedPreferences(AutopilotStore.PREFS, Context.MODE_PRIVATE).contains("ar_percent"));
        assertTrue("the switch, goal and minimums stay", FilterStore.load(app).autopilot);
        assertEquals(400, FilterStore.load(app).flatCents);
        String shared = DiagnosticLog.report(app);
        assertFalse(shared, shared.contains("37%"));
        assertTrue(shared, shared.contains("; AR unknown"));
        DecisionLog.Entry later = new DecisionLog.Entry(wall, DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(790, 7.2, 21, 2), 1080, OfferRule.Result.DECLINE, "dollars per mile",
                DecisionLog.Action.DECLINE_TAPPED, true, Collections.singletonList("$7.90"));
        JSONObject rules = new JSONObject(OfferReport.text(app, OfferReport.Problem.MISREAD, "test", 1,
                "Android test", later)).getJSONObject("rules");
        assertEquals(-1, rules.getInt("arPercent"));
        assertEquals("UNKNOWN", rules.getString("arSource"));
        assertEquals(-1, rules.getInt("arAgeMinutes"));
    }

    @Test public void noTextOfDashersQuestionGoesWithTheRate() throws Exception {
        dasherShowedTheRate();
        List<String> autopilot = logged(AutopilotRuntime.LOG);
        assertFalse(autopilot.isEmpty());
        String[] words = {"Are you sure", "decline this offer", "Declining", "may lower", "Decline offer", "Go back"};
        for (String line : autopilot) {
            for (String word : words) assertFalse(line, line.contains(word));
            // Of Autopilot's lines only its reading and its plans name the rate (fixed words and numbers).
            if (line.contains("37%")) {
                assertTrue(line, line.equals("ar reading 37% (exempt no)")
                        || line.matches("plan \\d+% [A-Z_]+: .*, ar 37% dasher \\(.*"));
            }
        }
        String shared = DiagnosticLog.report(app);
        String line = shared.substring(shared.indexOf("\nAutopilot: ") + 1);
        line = line.substring(0, line.indexOf('\n'));
        String rules = OfferReport.rulesJson(AutopilotRuntime.status(app, wall, false)).toString();
        for (String word : words) {
            assertFalse(line, line.contains(word));
            assertFalse(rules, rules.contains(word));
        }
        assertTrue(line, line.contains("AR 37%"));
    }
}

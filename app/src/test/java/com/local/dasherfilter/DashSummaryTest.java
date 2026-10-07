package com.local.dasherfilter;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Diagnostics after each dash, the user's opt-in: off by default and never turned on by an update; with it on, one
 * masked summary of at most 30,000 characters per dash (three a day at most) once Dasher shows the dash ended or it
 * went quiet, carrying counts, this dash's decisions timed from its start and log lines around its problems, and
 * never a name, an address, a place or Dasher's acceptance rate. The feedback service is a fake (ConsentedTestApp).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class DashSummaryTest {
    private Application app;
    private FakeFeedbackTransport service;
    private ServiceController<OfferFilterService> controller;
    private AccessibilityNodeInfo decline;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        FilterStore.save(app, FilterSettings.of(true, 2000, 0, 0, 0));
        DiagnosticLog.clear(app);
        DiagnosticLog.setEnabled(app, true);
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        Dashing.forgetCache();
        app.getSharedPreferences("dashing", Context.MODE_PRIVATE).edit().clear().commit();
        ActiveRouteStore.clear(app);
        service = FakeFeedbackTransport.installed();
    }

    @After
    public void teardown() {
        if (controller != null) controller.destroy();
        OfferFilterService.scanLooperForTests = null;
        settle();
        FakeFeedbackTransport.assertHonored();
    }

    private void settle() {
        DashSummary.flush();
        Feedback.flush();
        DashSummary.flush();
        FeedbackOutbox.flush();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private String summarySent(int index) throws Exception {
        JSONObject request = service.requests().get(index);
        assertEquals("diagnostics", request.getString("kind"));
        assertEquals("general", request.getString("category"));
        assertTrue(request.getBoolean("diagnosticsConsented"));
        assertEquals(1, request.getInt("partCount"));
        assertFalse("no typed message", request.has("message"));
        return Feedback.unframed(request.getString("diagnostics"));
    }

    @Test
    public void offByDefaultAndAnOlderVersionsSwitchNeverTurnsItOn() {
        assertFalse(Feedback.afterDashOn(app));
        // The retired GitHub switch, on, in an older version: removed, and the new opt-in stays off.
        app.getSharedPreferences("dash_diagnostics", Context.MODE_PRIVATE).edit().putBoolean("on", true).commit();
        app.getSharedPreferences("reports", Context.MODE_PRIVATE).edit().putBoolean("via_github", true).commit();
        LegacyReportingCleanup.forgetCache();
        assertTrue(LegacyReportingCleanup.run(app));
        assertFalse(Feedback.afterDashOn(app));
        assertFalse(DashSummary.collecting(app));

        // Off, a dash's end queues nothing and nothing is counted.
        Dashing.seen(app);
        DashSummary.unreadable(app, Arrays.asList("Guaranteed pay", "Accept", "Decline"));
        Dashing.ended(app);
        settle();
        assertEquals(0, service.count());
        assertTrue(app.getSharedPreferences("dash_summary", Context.MODE_PRIVATE).getAll().isEmpty());
    }

    @Test
    public void aDashEndedOnScreenSendsOneMaskedSummaryOfThatDashOnly() throws Exception {
        Feedback.setAfterDash(app, true);
        long now = System.currentTimeMillis();
        // A decision from before this dash: not in its summary.
        DecisionLog.record(app, new DecisionLog.Entry(now - 3 * 3_600_000L, DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(555, 2.0, 10, 2), 2000, OfferRule.Result.DECLINE, "an older dash",
                DecisionLog.Action.DECLINE_TAPPED, true, Collections.singletonList("$5.55")));
        show(node("Finding offers", false));
        show(offer("$7.90", "Deliver to Sam P"));
        pass(1_100);
        show(offer("Guaranteed pay", "100 Example St"));
        pass(1_100);
        show(screen("Dash ended", "Total earned $84.20"));
        settle();

        assertEquals("one summary", 1, service.count());
        String summary = summarySent(0);
        assertTrue(summary.length() <= Feedback.MAX_SUMMARY_CHARS);
        assertTrue(summary, summary.startsWith("Diagnostics after a dash"));
        assertTrue(summary, summary.contains(DiagnosticLog.phone()));
        assertTrue(summary, summary.contains("ended: Dasher showed the dash ended"));
        assertTrue(summary, summary.contains("Settings: auto-decline on · Peek on"));
        assertTrue(summary, summary.contains("Offers: 2 (DECLINE 1, REVIEW 1)"));
        assertTrue(summary, summary.contains("Unreadable offers: offers 1"));
        assertTrue("decisions timed from the dash's start", summary.contains("+0:00:0"));
        assertTrue(summary, summary.contains("| DECLINE | pay $7.90 | needed $20.00"));
        assertTrue(summary, summary.contains("-- +0:00:0") && summary.contains("unreadable offer"));
        assertFalse("only this dash's decisions", summary.contains("an older dash"));
        for (String personal : new String[] {"Sam", "Example", "Total earned $84.20"}) {
            assertFalse(personal, summary.contains(personal));
        }
        assertFalse("no exact clock times", summary.matches("(?s).*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}.*"));
        assertEquals(Feedback.State.SENT.name(), Feedback.prefs(app).getString("last_state", ""));

        // The end screen is read again and again: still one summary.
        show(screen("Dash ended", "Total earned $84.20"));
        settle();
        assertEquals(1, service.count());
    }

    @Test
    public void aDashThatWentQuietIsSummarizedWhenTheNextOneBegins() throws Exception {
        Feedback.setAfterDash(app, true);
        long now = System.currentTimeMillis();
        long started = now - 3 * 3_600_000L;
        long seen = now - 40 * 60_000L;
        app.getSharedPreferences("dashing", Context.MODE_PRIVATE).edit().putLong("started_at", started)
                .putLong("seen_at", seen).putBoolean("open", true).commit();
        DecisionLog.record(app, new DecisionLog.Entry(seen - 60_000L, DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(790, 7.2, 21, 2), 2000, OfferRule.Result.DECLINE, "the quiet dash's offer",
                DecisionLog.Action.DECLINE_TAPPED, true, Collections.singletonList("$7.90")));
        Dashing.seen(app);
        settle();
        assertEquals(1, service.count());
        String summary = summarySent(0);
        assertTrue(summary, summary.contains("ended: nothing of the dash seen for 30 minutes"));
        assertTrue(summary, summary.contains("the quiet dash's offer"));
        assertTrue("about 2 h 20 min, rounded to five minutes", summary.contains("Dash: about 2 h 20 min"));
    }

    @Test
    public void atMostOnePerDashAndThreeADay() throws Exception {
        Feedback.setAfterDash(app, true);
        for (int dash = 0; dash < 5; dash++) {
            long start = System.currentTimeMillis() - 60_000L * (10 - dash);
            DashSummary.summarize(app, start, start + 30_000L, DashSummary.End.DASH_OVER);
            DashSummary.summarize(app, start, start + 30_000L, DashSummary.End.DASH_OVER);
        }
        settle();
        assertEquals(DashSummary.MAX_PER_DAY, service.count());
        assertTrue(DiagnosticLog.read(app).contains("dash summary not queued: today's 3 were"));
    }

    @Test
    public void problemsAreCountedByFixedCategoryWithMaskedExcerpts() throws Exception {
        Feedback.setAfterDash(app, true);
        Dashing.seen(app);
        long start = Dashing.currentStart(app);
        DiagnosticLog.log(app, "accessibility", "decline still showing after 5012 ms; waiting for the question");
        DashSummary.unreadable(app, Arrays.asList("Order for Jane D.", "Guaranteed pay", "Accept", "Decline"));
        DashSummary.stuck(app, "waiting for the question");
        DashSummary.recovery(app, "Back requested");
        DashSummary.notSent(app, "rules_changed");
        DashSummary.error(app, "scan", new IllegalStateException("tree changed for Jane"));
        DashSummary.error(app, "notification", new NumberFormatException("For input string: \"Jane\""));
        DashSummary.peek(app, "opening Dasher for Chipotle (was in front: a navigation app)");
        DashSummary.peek(app, "skipped: the notification is 12.0 s old car=no lock=no");
        DashSummary.peek(app, "returned to a navigation app: its confirmation was tapped (Dasher up 1.2 s after it "
                + "was opened; 4.0 s in all) car=no lock=no");
        DashSummary.window(app, "win=split/bottom/dasher/48");
        DashSummary.flush();
        String summary = DashSummary.build(app, start, start + 600_000L, DashSummary.End.DASH_OVER,
                modelForTest(start));
        assertTrue(summary, summary.contains("Unreadable offers: offers 1"));
        assertTrue(summary, summary.contains("Declines still showing, by stage: waiting for the question 1"));
        assertTrue(summary, summary.contains("Decline-error recoveries: Back requested 1"));
        assertTrue(summary, summary.contains("Automatic accepts not sent, by reason: rules_changed 1"));
        assertTrue(summary, summary.contains("Screen-read errors: IllegalStateException at DashSummaryTest."));
        assertTrue(summary, summary.contains("Notification errors: NumberFormatException at DashSummaryTest."));
        assertTrue(summary, summary.contains("Peek: opened Dasher 1, returned to a navigation app: its confirmation "
                + "was tapped 1, skipped: the notification is ##.# s old 1"));
        assertTrue(summary, summary.contains("-- +0:00:00 unreadable offer"));
        assertTrue(summary, summary.contains("read: [Order for [name], Guaranteed pay"));
        assertTrue(summary, summary.contains("decline still showing after 5012 ms"));
        for (String personal : new String[] {"Jane", "Chipotle", "For input string", "tree changed"}) {
            assertFalse(personal, summary.contains(personal));
        }
        assertEquals("digits masked", "IllegalStateException at OfferFilterService.read:###",
                DashSummary.signature(error("com.local.dasherfilter.OfferFilterService", "read", 123)));
    }

    private static Throwable error(String type, String method, int line) {
        IllegalStateException error = new IllegalStateException("message never kept");
        error.setStackTrace(new StackTraceElement[] {new StackTraceElement(type, method, "File.java", line)});
        return error;
    }

    /** What the dash counted, as kept. */
    private JSONObject modelForTest(long start) throws Exception {
        android.content.SharedPreferences prefs = app.getSharedPreferences("dash_summary", Context.MODE_PRIVATE);
        assertEquals(start, prefs.getLong("start", 0));
        return new JSONObject().put("counts", new JSONObject(prefs.getString("counts", "{}")))
                .put("anomalies", new org.json.JSONArray(prefs.getString("anomalies", "[]"))).put("start", start);
    }

    @Test
    public void turningItOffDiscardsUnsentSummariesAndTheDashsCounts() throws Exception {
        Feedback.setAfterDash(app, true);
        service.down = true;
        Dashing.seen(app);
        DashSummary.stuck(app, "waiting for the question");
        settle();
        long start = Dashing.currentStart(app);
        DashSummary.summarize(app, start, start + 60_000L, DashSummary.End.DASH_OVER);
        settle();
        File outbox = new File(app.getFilesDir(), FeedbackOutbox.DIR);
        assertEquals(1, outbox.listFiles((dir, name) -> name.endsWith("-a.json")).length);
        Dashing.seen(app);
        DashSummary.stuck(app, "waiting for the question");
        settle();
        Feedback.setAfterDash(app, false);
        assertFalse("off at once for the hooks", DashSummary.collecting(app));
        // The files go on the summary's own thread, never the caller's.
        DashSummary.flush();
        assertEquals(0, outbox.listFiles((dir, name) -> name.endsWith(".json")).length);
        assertFalse(app.getSharedPreferences("dash_summary", Context.MODE_PRIVATE).contains("counts"));
        assertTrue(DiagnosticLog.read(app).contains("diagnostics after each dash turned off; unsent ones discarded"));
    }

    // ---- What a dash counted goes with its summary, and only then ----

    private android.content.SharedPreferences counted() {
        return app.getSharedPreferences("dash_summary", Context.MODE_PRIVATE);
    }

    @Test
    public void whenTheDailyCapIsReachedTheDashsCountsStillGo() {
        Feedback.setAfterDash(app, true);
        Dashing.seen(app);
        long start = Dashing.currentStart(app);
        DashSummary.unreadable(app, Arrays.asList("Guaranteed pay", "2 stops (7.2 mi) • 21 min", "Accept", "Decline"));
        DashSummary.stuck(app, "question seen; not confirmed");
        DashSummary.window(app, "win=split/bottom/dasher/48");
        DashSummary.flush();
        assertTrue(counted().contains("anomalies"));
        long now = System.currentTimeMillis();
        long day = (now + TimeZone.getDefault().getOffset(now)) / 86_400_000L;
        counted().edit().putLong("day", day).putInt("today", DashSummary.MAX_PER_DAY).commit();

        DashSummary.summarize(app, start, now, DashSummary.End.QUIET);
        settle();
        assertEquals("over today's cap: nothing queued", 0, service.count());
        for (String kept : new String[] {"start", "counts", "anomalies"}) {
            assertFalse("the dash's " + kept + " go all the same", counted().contains(kept));
        }
        assertEquals("never summarized twice", start, counted().getLong("summarized", 0));
    }

    @Test
    public void aCountMadeJustBeforeTheDashEndedGoesInItsSummary() throws Exception {
        Feedback.setAfterDash(app, true);
        Dashing.seen(app);
        CountDownLatch release = new CountDownLatch(1);
        DashSummary.onWorkerForTests(() -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            // Counted while the dash is on, handled only after Dasher showed its end.
            DashSummary.stuck(app, "waiting for the question");
            Dashing.ended(app);
        } finally {
            release.countDown();
        }
        settle();
        assertEquals(1, service.count());
        String summary = summarySent(0);
        assertTrue(summary, summary.contains("Declines still showing, by stage: waiting for the question 1"));
    }

    @Test
    public void anOptOutRacingACountNeverWritesTheCountsBack() throws Exception {
        Feedback.setAfterDash(app, true);
        Dashing.seen(app);
        CountDownLatch release = new CountDownLatch(1);
        DashSummary.onWorkerForTests(() -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            DashSummary.stuck(app, "waiting for the question");
            Feedback.setAfterDash(app, false);
            // Cleared directly too, as Clear history's own clear() could be, before the count is handled.
            DashSummary.clear(app);
        } finally {
            release.countDown();
        }
        DashSummary.flush();
        for (String kept : new String[] {"start", "counts", "anomalies"}) {
            assertFalse(kept + " never comes back", counted().contains(kept));
        }
    }

    @Test
    public void aSummaryNotYetBuiltWhenTheOptInWentOffAndOnIsNeverSent() throws Exception {
        Feedback.setAfterDash(app, true);
        Dashing.seen(app);
        DashSummary.stuck(app, "waiting for the question");
        DashSummary.flush();
        CountDownLatch release = new CountDownLatch(1);
        DashSummary.onWorkerForTests(() -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            // The dash ends; before its summary is built, the user turns the option off and on again.
            Dashing.ended(app);
            Feedback.setAfterDash(app, false);
            Feedback.setAfterDash(app, true);
        } finally {
            release.countDown();
        }
        settle();
        assertEquals("discarded with what was not yet sent", 0, service.count());
        assertEquals(0, FeedbackOutbox.pending(app));
        assertTrue(DiagnosticLog.read(app).contains("dash summary not queued: the option changed since the dash ended"));
        assertTrue("the option itself stays on", Feedback.afterDashOn(app));
    }

    @Test
    public void aDashThatEndedBeforeTheOptInWentOffIsNeverSummarizedLater() throws Exception {
        Feedback.setAfterDash(app, true);
        // Went quiet half an hour ago; nothing has looked yet.
        openDash(40 * 60_000L);
        Feedback.setAfterDash(app, false);
        Feedback.setAfterDash(app, true);
        DashSummary.flush();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            settle();
            assertEquals("its summary would be one not yet sent", 0, service.count());
        }
        // The next dash is summarized as ever.
        Dashing.seen(app);
        Dashing.ended(app);
        settle();
        assertEquals(1, service.count());
    }

    @Test
    public void nothingIsCountedOrSummarizedBeforeTheNoticeIsAccepted() {
        Feedback.setAfterDash(app, true);
        Dashing.seen(app);
        long start = Dashing.currentStart(app);
        ConsentedTestApp.forget(app);
        DashSummary.unreadable(app, Arrays.asList("Guaranteed pay", "Accept", "Decline"));
        DashSummary.stuck(app, "waiting for the question");
        DashSummary.flush();
        assertFalse(counted().contains("counts"));
        DashSummary.summarize(app, start, System.currentTimeMillis(), DashSummary.End.DASH_OVER);
        settle();
        assertEquals(0, service.count());
        assertFalse("not even marked", counted().contains("summarized"));
        assertEquals(0, FeedbackOutbox.pending(app));
    }

    @Test
    public void logExcerptsNeverCarryAPaymentOrAccountLine() throws Exception {
        Feedback.setAfterDash(app, true);
        Dashing.seen(app);
        long start = Dashing.currentStart(app);
        // Written by a source the write-time filter does not cover: only the read-time one stands between.
        DiagnosticLog.log(app, "accessibility", "window text: Fast Pay, Available balance $123.45");
        DiagnosticLog.log(app, "accessibility", "decline still showing after 5012 ms; waiting for the question");
        DashSummary.stuck(app, "waiting for the question");
        DashSummary.flush();
        String summary = DashSummary.build(app, start, start + 600_000L, DashSummary.End.DASH_OVER,
                modelForTest(start));
        assertTrue(summary, summary.contains("decline still showing after 5012 ms"));
        assertTrue(summary, summary.contains(DiagnosticLog.NOT_KEPT));
        for (String account : new String[] {"Fast Pay", "Available balance", "123.45"}) {
            assertFalse(account, summary.contains(account));
        }
    }

    // ---- Settings and Dasher's acceptance rate ----

    @Test
    public void theSettingsNameTheBarAndAutopilotAndNoRetiredRule() throws Exception {
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 3));
        Feedback.setAfterDash(app, true);
        Dashing.seen(app);
        long start = Dashing.currentStart(app);
        DashSummary.stuck(app, "waiting for the question");
        DashSummary.flush();
        String off = DashSummary.build(app, start, start + 600_000L, DashSummary.End.DASH_OVER, modelForTest(start));
        assertTrue(off, off.contains(" · bar 100% (Autopilot off) · rules set: pay, per mile, per hour, max stops\n"));

        // Autopilot on, its bar moved: the settings say the bar and the switch, never what the bar came from.
        FilterStore.setAutopilot(app, true, 70);
        assertTrue(FilterStore.commitAutopilotBar(app, 100, 82));
        AutopilotStore.recordReading(app, 37, "", System.currentTimeMillis());
        String on = DashSummary.build(app, start, start + 600_000L, DashSummary.End.DASH_OVER, modelForTest(start));
        String settings = on.substring(on.indexOf("Settings: "), on.indexOf('\n', on.indexOf("Settings: ")));
        assertTrue(settings, settings.startsWith("Settings: auto-decline on · Peek on · "));
        assertTrue(settings, settings.endsWith(" · bar 82% (Autopilot on) · rules set: pay, per mile, per hour, "
                + "max stops"));
        assertFalse(settings, settings.contains("37"));
        assertFalse(settings, settings.contains("goal"));
        for (String retired : new String[] {"score by area", "adaptive", "minimums scale", "per stop", "per item",
                "hotspot", "per minute", "learned", "learning"}) {
            assertFalse(retired, on.toLowerCase(java.util.Locale.US).contains(retired));
        }
    }

    @Test
    public void aLogLineCarriesTheAcceptanceRateIntoTheSummaryNever() {
        // Autopilot's plans and Dasher's readings stay on the phone; so does any Autopilot line not listed as safe.
        assertNull(DashSummary.withoutAcceptanceRate(" [autopilot] plan 100% RECOVERY: need 80%, pass 16/20 at 100%, "
                + "share bar 100%, ar 55% dasher (12 min ago, 2 since), recovering yes, extra 0"));
        assertNull(DashSummary.withoutAcceptanceRate(" [autopilot] plan 50% PINNED: need 80%, ar ~31% estimate (29 "
                + "counted)"));
        assertNull(DashSummary.withoutAcceptanceRate(" [autopilot] ar reading 55% (exempt no)"));
        assertNull(DashSummary.withoutAcceptanceRate(" [autopilot] ar reading none (exempt yes)"));
        assertNull("a line a later version adds", DashSummary.withoutAcceptanceRate(" [autopilot] status AR 55%"));
        // Autopilot's lines that never name it stay as they are, their bars and goals included.
        for (String kept : new String[] {" [autopilot] on; goal 70%", " [autopilot] on; pay first",
                " [autopilot] off; bar back to 100%", " [autopilot] goal 50% (was 70%)",
                " [autopilot] pay first (was 70%)", " [autopilot] commit 100% -> 82% (acceptance rate below your goal)",
                " [autopilot] commit deferred: offer or decline in flight", " [autopilot] commit failed: "
                + "IllegalStateException", " [autopilot] plan discarded: rules changed",
                " [autopilot] plan failed: IllegalStateException",
                " [autopilot] ar exemptions ignored: 12 of 20 declines flagged"}) {
            assertEquals(kept, DashSummary.withoutAcceptanceRate(kept));
        }
        // Dasher's question, as the screen reader logs it: its percentages masked, its words kept.
        assertEquals(" [screen] confirmation|||false|true win=full/-/dasher/100 labels=[Are you sure you want to "
                + "decline this offer?, Does not lower acceptance rate, #%, Decline offer, Go back] metricParts=[]",
                DashSummary.withoutAcceptanceRate(" [screen] confirmation|||false|true win=full/-/dasher/100 "
                + "labels=[Are you sure you want to decline this offer?, Does not lower acceptance rate, 9%, Decline "
                + "offer, Go back] metricParts=[]"));
        assertEquals("the question's line, whatever its words", " [screen] confirmation|x|y labels=[Sure?, #%]",
                DashSummary.withoutAcceptanceRate(" [screen] confirmation|x|y labels=[Sure?, 55 %]"));
        assertEquals(" [screen] other labels=[Your Acceptance Rate, #%, Completion rate, #%]",
                DashSummary.withoutAcceptanceRate(" [screen] other labels=[Your Acceptance Rate, 55%, Completion "
                + "rate, 98%]"));
        assertEquals("as Android may hand it over (NFKC)", " [screen] other labels=[acceptance\u00A0rate, #%, #%]",
                DashSummary.withoutAcceptanceRate(" [screen] other labels=[acceptance\u00A0rate, 55\u00A0%, "
                + "\uFF15\uFF15\uFF05]"));
        assertEquals(" [screen] other labels=[Your acceptance-rate goal, #%]",
                DashSummary.withoutAcceptanceRate(" [screen] other labels=[Your acceptance-rate goal, 70%]"));
        // The rate as Autopilot's own words put it, wherever such a line came from, and the accepts that give it away.
        assertEquals(" [status] Autopilot #% · AR #% → #%: about # more accepts", DashSummary.withoutAcceptanceRate(
                " [status] Autopilot 82% · AR 55% → 70%: about 15 more accepts"));
        assertEquals(" [status] AR ~#% → #%: about # more accepts", DashSummary.withoutAcceptanceRate(
                " [status] AR ~31% → 70%: about 39 more accepts"));
        // Any other line, percentages and all, as it was.
        for (String other : new String[] {" [scan] read took 230 ms", " [screen] offer|a|b labels=[$7.90, 100% of "
                + "tips] metricParts=[]", " [accessibility] decline still showing after 5012 ms",
                " [status] Autopilot bar 82% (goal 70%)", " [screen] other labels=[Car 50% charged, 3 stops]"}) {
            assertEquals(other, DashSummary.withoutAcceptanceRate(other));
        }
        // Except the accepts a goal still needs, wherever they are: beside the goal they give the rate away.
        assertEquals(" [screen] other labels=[Car 50% charged, # more accepts]",
                DashSummary.withoutAcceptanceRate(" [screen] other labels=[Car 50% charged, 3 more accepts]"));

        // Read labels of a problem: masked only where they name the acceptance rate.
        assertEquals(Arrays.asList("Are you sure you want to decline this offer?", "Declining this offer xxx lower "
                + "your acceptance rate", "#%", "Decline offer", "Go back"),
                DashSummary.readLines(Arrays.asList("Are you sure you want to decline this offer?",
                        "Declining this offer may lower your acceptance rate", "37%", "Decline offer", "Go back")));
        assertEquals(Arrays.asList("$7.90", "100% of tips"),
                DashSummary.withoutAcceptanceRate(Arrays.asList("$7.90", "100% of tips")));
    }

    /**
     * The rate's figure standing on its own anywhere in a text ("37%", "37 percent", "AR 37", "37, %"), in any digits;
     * never part of a word or a longer number (a feedback reference "37f3a024", "1370"), a clock (":37") or a decimal
     * ("$3.37"), which are not the rate.
     */
    private static final java.util.regex.Pattern RATE_FIGURE =
            java.util.regex.Pattern.compile("(?<![\\w:.])37(?![\\w:])|\uFF13\uFF17");

    /** Dasher's question as the screen reader logs it, its labels in between. */
    private static String questionLine(String labels) {
        return " [screen] confirmation|||false|true win=full/-/dasher/100 labels=[Are you sure you want to decline "
                + "this offer?, Declining this offer may lower your acceptance rate, " + labels + ", Decline offer, "
                + "Go back] metricParts=[]";
    }

    @Test
    public void theRateNeverLeavesInAnyWayItCanBeWritten() {
        // Dasher's question, its rate written every way a screen reader may hand it over: never a digit of it.
        String masked = questionLine("#%");
        for (String rate : new String[] {"9%", "9 %", "9\u00A0%", "9\u202F%", "\uFF19\uFF05", "9\uFE6A", "9 percent",
                "9 Percent", "9percent", "9 per cent", "9 pct", "9 PCT", "9.5%"}) {
            assertEquals(rate, masked, DashSummary.withoutAcceptanceRate(questionLine(rate)));
        }
        assertEquals("a lone 9 beside a lone %", questionLine("#, %"),
                DashSummary.withoutAcceptanceRate(questionLine("9, %")));
        assertEquals("spelled out", questionLine("# percent"),
                DashSummary.withoutAcceptanceRate(questionLine("nine percent")));
        assertEquals("in the rate's own words", questionLine("Your acceptance rate is #%"),
                DashSummary.withoutAcceptanceRate(questionLine("Your acceptance rate is 9 percent")));
        assertEquals(questionLine("Acceptance rate: #%"),
                DashSummary.withoutAcceptanceRate(questionLine("Acceptance rate: 9 pct")));
        assertEquals("joined from its sibling nodes", " [screen] confirmation|x|y labels=[Sure?] metricParts=[#%]",
                DashSummary.withoutAcceptanceRate(" [screen] confirmation|x|y labels=[Sure?] metricParts=[9 %]"));

        // The question's labels among another read's: the question alone is enough, with or without the rate's words,
        // and every number goes but money and clock times; the line's head (the app's own) stays as it was.
        assertEquals(" [screen] offer|790:7.2:21:2|DECLINE: required at least $10.80 (dollars per mile)|true|true "
                + "win=full/-/dasher/100 labels=[$7.90, Declining this offer may lower your acceptance rate, #%, 0:35, "
                + "# mi] metricParts=[# stops (# mi) • # min]",
                DashSummary.withoutAcceptanceRate(" [screen] offer|790:7.2:21:2|DECLINE: required at least $10.80 "
                + "(dollars per mile)|true|true win=full/-/dasher/100 labels=[$7.90, Declining this offer may lower "
                + "your acceptance rate, 9 percent, 0:35, 7.2 mi] metricParts=[2 stops (7.2 mi) • 21 min]"));
        assertEquals("no rate words, only the question", " [screen] offer|a|b labels=[Are you sure you want to "
                + "decline this offer?, #%, Decline offer, Go back] metricParts=[]",
                DashSummary.withoutAcceptanceRate(" [screen] offer|a|b labels=[Are you sure you want to decline this "
                + "offer?, 9%, Decline offer, Go back] metricParts=[]"));
        assertEquals(" [screen] incomplete-controls|a|b labels=[Decline offer?, #, %] metricParts=[]",
                DashSummary.withoutAcceptanceRate(" [screen] incomplete-controls|a|b labels=[Decline offer?, 9, %] "
                + "metricParts=[]"));

        // Any other line that names the rate: every number on it goes, written however.
        assertEquals(" [status] AR: #", DashSummary.withoutAcceptanceRate(" [status] AR: 9"));
        assertEquals(" [status] Your acceptance rate is #%",
                DashSummary.withoutAcceptanceRate(" [status] Your acceptance rate is 9 percent"));
        // Autopilot's own words that never name the rate: the accepts a goal still needs go all the same.
        assertEquals(" [status] Below your goal: about # more accepts to reach 70% (an estimate)",
                DashSummary.withoutAcceptanceRate(" [status] Below your goal: about 61 more accepts to reach 70% (an "
                + "estimate)"));
        assertEquals(" [status] about # more accept",
                DashSummary.withoutAcceptanceRate(" [status] about 1 more accept"));

        // Autopilot's safe lines are kept only whole: one that adds to a listed line stays on the phone.
        assertNull(DashSummary.withoutAcceptanceRate(" [autopilot] on; goal 70%; AR 9%"));
        assertNull(DashSummary.withoutAcceptanceRate(" [autopilot] commit 100% -> 82% (acceptance rate below your "
                + "goal) AR 9%"));
        assertNull(DashSummary.withoutAcceptanceRate(" [autopilot] goal 70%: about 61 more accepts"));
        for (Autopilot.Reason reason : Autopilot.Reason.values()) {
            String commit = " [autopilot] " + AutopilotText.logCommit(100, 82, reason);
            assertEquals("every commit reason is a fixed one", commit, DashSummary.withoutAcceptanceRate(commit));
        }
        for (String kept : new String[] {AutopilotText.DISCARD_OLD, AutopilotText.DISCARD_CLEARED,
                AutopilotText.DISCARD_AUTOPILOT_CHANGED, AutopilotText.LOG_OFF, AutopilotText.logGoal(70, 0),
                AutopilotText.logOn(0)}) {
            assertEquals(kept, " [autopilot] " + kept, DashSummary.withoutAcceptanceRate(" [autopilot] " + kept));
        }
    }

    @Test
    public void aProblemsReadLabelsAreJudgedBeforeRedactionReducesThem() {
        String question = "Are you sure you want to decline this offer?";
        // "AR" and "percent" are no offer words, so redaction reduces them to a shape: they are judged before that.
        assertEquals(Arrays.asList(question, "XX #%", "Decline offer"),
                DashSummary.readLines(Arrays.asList(question, "AR 9 %", "Decline offer")));
        assertEquals(Arrays.asList(question, "Your acceptance rate is # xxxxxxx", "Decline offer"),
                DashSummary.readLines(Arrays.asList(question, "Your acceptance rate is 9 percent", "Decline offer")));
        assertEquals("the question's own percent, no rate words beside it", Arrays.asList(question, "#%",
                "Decline offer", "Go back"), DashSummary.readLines(Arrays.asList(question, "9%", "Decline offer",
                "Go back")));
        assertEquals(Arrays.asList(question, "acceptance rate", "#", "%", "Decline offer"),
                DashSummary.readLines(Arrays.asList(question, "acceptance rate", "9", "%", "Decline offer")));
        // On an offer's own screen, only where a label names the rate; its pay and countdown stay.
        assertEquals(Arrays.asList("Guaranteed pay", "$7.90", "XX #%", "0:35", "Accept", "Decline"),
                DashSummary.readLines(Arrays.asList("Guaranteed pay", "$7.90", "AR 9 %", "0:35", "Accept",
                        "Decline")));
        assertEquals(Arrays.asList("Guaranteed pay", "$7.90", "2 stops (7.2 mi) • 21 min", "Accept", "Decline"),
                DashSummary.readLines(Arrays.asList("Guaranteed pay", "$7.90", "2 stops (7.2 mi) • 21 min", "Accept",
                        "Decline")));
        // Labels kept as an older version counted them (already redacted): the question still shows, so they go too.
        assertEquals(Arrays.asList(question, "XX #%", "Decline offer"),
                DashSummary.withoutAcceptanceRate(Arrays.asList(question, "XX 9 %", "Decline offer")));
        assertEquals(Arrays.asList("Your acceptance rate is # xxxxxxx"),
                DashSummary.withoutAcceptanceRate(Arrays.asList("Your acceptance rate is 9 xxxxxxx")));
    }

    @Test
    public void aDecisionsReadLinesAndStepsNeverCarryTheRate() throws Exception {
        Feedback.setAfterDash(app, true);
        Dashing.seen(app);
        long start = Dashing.currentStart(app);
        DashSummary.stuck(app, "waiting for the question");
        DashSummary.flush();
        long now = System.currentTimeMillis();
        // A card whose description merged its pay and Dasher's question: kept as evidence for its "$".
        DecisionLog.record(app, new DecisionLog.Entry(now, DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(575, 6.6, 27, 2), 625, OfferRule.Result.DECLINE, "dollars per hour",
                DecisionLog.Action.CONFIRMATION_TAPPED, true, Arrays.asList("$5.75",
                "Declining this $5.75 offer may lower your acceptance rate to 37%", "$5.75 · AR 37 pct"))
                .withStep(new DecisionLog.Step(DecisionLog.StepKind.NOT_LEARNED, now + 1_000L,
                        "acceptance rate 37 percent")));
        String summary = DashSummary.build(app, start, start + 600_000L, DashSummary.End.DASH_OVER,
                modelForTest(start));
        // Redaction reduces "pct" to a shape before the numbers go: "# xxx", never the figure.
        assertTrue(summary, summary.contains("\n    read: [$5.75, Declining this $5.75 offer xxx lower your acceptance "
                + "rate to #%, $5.75 · XX # xxx]\n"));
        assertTrue(summary, summary.contains("\n    then " + DashSummary.relative(now + 1_000L - start)
                + " Not counted from what followed: acceptance rate #%\n"));
        assertTrue("the decision's own figures stay", summary.contains("| DECLINE | pay $5.75 | needed $6.25"));
        assertFalse(summary, RATE_FIGURE.matcher(summary).find());
    }

    @Test
    public void aSummaryAnOlderVersionQueuedIsFilteredAgainBeforeItIsSent() throws Exception {
        Feedback.setAfterDash(app, true);
        service.down = true;
        // Queued as a version with a weaker filter would have built it: the rate in a question's line written out,
        // Autopilot's reading, and a problem's and a decision's read labels and a step that name it.
        String older = "Diagnostics after a dash, sent because Share anonymous diagnostics after each dash is on.\n"
                + "== This dash's decisions (oldest first)\n"
                + "+0:00:01 | screen | DECLINE | pay $5.75 | needed $6.25 | score 92% | 6.6 mi · 27 min · 2 stops "
                + "| dollars per hour | Decline and its confirmation tapped | outcome DECLINED\n"
                + "    read: [$5.75, Declining this $5.75 offer xxx lower your acceptance rate to 37%]\n"
                + "    then +0:00:03 Your acceptance rate is 37 percent\n"
                + "\n== Log lines around those problems (masked)\n"
                + "-- +0:00:05 decline still showing (waiting for the question)\n"
                + "   read: [Are you sure you want to decline this offer?, XX 37 %, Decline offer]\n"
                + "+0:00:04" + questionLine("37 percent") + "\n"
                + "+0:00:04" + questionLine("37, %") + "\n"
                + "+0:00:04 [autopilot] ar reading 37% (exempt no)\n"
                + "+0:00:04 [autopilot] plan 82% RECOVERY: need 80%, ar 37% dasher (0 min ago, 0 since)\n"
                + "+0:00:04 [status] Autopilot 82% · AR 37% → 70%: about 33 more accepts\n"
                + "+0:00:04 [autopilot] commit 100% -> 82% (acceptance rate below your goal)\n"
                + "+0:00:05 [accessibility] decline still showing after 5012 ms; waiting for the question\n";
        String token = Feedback.newToken();
        assertTrue(FeedbackOutbox.submitAutomatic(app, FeedbackOutbox.automatic(token,
                Collections.singletonList(older), Feedback.automaticEpoch(app))));
        settle();
        int tried = service.count();
        service.down = false;
        FeedbackOutbox.sendSoon(app);
        settle();
        assertEquals(tried + 1, service.count());
        String sent = summarySent(tried);
        assertEquals(token, service.requests().get(tried).getString("reportToken"));
        assertFalse(sent, RATE_FIGURE.matcher(sent).find());
        assertFalse(sent, sent.contains("33 more"));
        assertFalse(sent, sent.contains("ar reading"));
        assertFalse(sent, sent.contains("RECOVERY: need"));
        for (String kept : new String[] {"| DECLINE | pay $5.75 | needed $6.25 | score 92% |",
                "    read: [$5.75, Declining this $5.75 offer xxx lower your acceptance rate to #%]\n",
                "    then +0:00:03 Your acceptance rate is #%\n",
                "   read: [Are you sure you want to decline this offer?, XX #%, Decline offer]\n",
                "+0:00:04" + questionLine("#%") + "\n", "+0:00:04" + questionLine("#, %") + "\n",
                "+0:00:04 [status] Autopilot #% · AR #% → #%: about # more accepts\n",
                "+0:00:04 [autopilot] commit 100% -> 82% (acceptance rate below your goal)\n",
                "decline still showing after 5012 ms"}) {
            assertTrue(kept, sent.contains(kept));
        }

        // What this version builds comes back from that second filter as it was.
        Dashing.seen(app);
        long start = Dashing.currentStart(app);
        DiagnosticLog.log(app, "screen", questionLine("37 pct").substring(" [screen] ".length()));
        DashSummary.stuck(app, "waiting for the question");
        DashSummary.unreadable(app, Arrays.asList("Are you sure you want to decline this offer?", "AR 37 %",
                "Decline offer"));
        DashSummary.flush();
        String built = DashSummary.build(app, start, start + 600_000L, DashSummary.End.DASH_OVER,
                modelForTest(start));
        assertFalse(built, RATE_FIGURE.matcher(built).find());
        assertEquals(built, DashSummary.remask(built));
        assertEquals(built, FeedbackOutbox.remask(FeedbackOutbox.TEXT, true, built));
        // What the user sends themselves keeps what they chose to send: only the personal masking applies to it.
        String shared = "+0:00:04 [autopilot] ar reading 37% (exempt no)\n";
        assertEquals(shared, FeedbackOutbox.remask(FeedbackOutbox.TEXT, false, shared));
    }

    @Test
    public void theSummaryNeverCarriesTheAcceptanceRateAroundAProblem() throws Exception {
        Feedback.setAfterDash(app, true);
        Dashing.seen(app);
        long start = Dashing.currentStart(app);
        List<String> asked = Arrays.asList("Are you sure you want to decline this offer?",
                "Declining this offer may lower your acceptance rate", "37%", "Decline offer", "Go back");
        DiagnosticLog.log(app, "screen", "confirmation|||false|true win=full/-/dasher/100 labels=" + asked
                + " metricParts=[]");
        DiagnosticLog.log(app, AutopilotRuntime.LOG, AutopilotText.logReading(37, false));
        DiagnosticLog.log(app, AutopilotRuntime.LOG, "plan 100% RECOVERY: need 80%, pass 16/20 at 100%, share bar "
                + "100%, ar 37% dasher (0 min ago, 0 since), recovering yes, extra 0, lambda 6.0/h, mix 20");
        DiagnosticLog.log(app, AutopilotRuntime.LOG, AutopilotText.logCommit(100, 82, Autopilot.Reason.RECOVERY));
        DiagnosticLog.log(app, "accessibility", "decline still showing after 5012 ms; waiting for the question");
        DashSummary.stuck(app, "waiting for the question");
        DashSummary.unreadable(app, asked);
        DashSummary.flush();
        String kept = counted().getString("anomalies", "");
        assertFalse("not even kept on the phone for the summary", kept.contains("37%"));
        // The declined offer's line, with Dasher's mark that declining it was free: a step, never the rate.
        long now = System.currentTimeMillis();
        DecisionLog.record(app, new DecisionLog.Entry(now, DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(575, 6.6, 27, 2), 625, OfferRule.Result.DECLINE, "dollars per hour",
                DecisionLog.Action.CONFIRMATION_TAPPED, true, Collections.singletonList("$5.75"))
                .withStep(new DecisionLog.Step(DecisionLog.StepKind.AR_EXEMPT, now + 1_000L, "")));

        Dashing.ended(app);
        settle();
        assertEquals(1, service.count());
        String summary = summarySent(0);
        assertTrue(summary, summary.contains("| DECLINE | pay $5.75 | needed $6.25"));
        assertTrue(summary, summary.contains("\n    then " + DashSummary.relative(now + 1_000L - start)
                + " Dasher said declining it does not lower your acceptance rate\n"));
        assertFalse(summary, summary.contains("learning"));
        assertTrue(summary, summary.contains("decline still showing after 5012 ms"));
        assertTrue(summary, summary.contains("lower your acceptance rate, #%, Decline offer"));
        assertTrue(summary, summary.contains("[autopilot] commit 100% -> 82% (acceptance rate below your goal)"));
        assertFalse(summary, summary.contains("ar reading"));
        assertFalse(summary, summary.contains("RECOVERY: need"));
        assertFalse(summary, java.util.regex.Pattern.compile("37 ?%|(?i)\\bar ~?37\\b").matcher(summary).find());
    }

    @Test
    public void windowTimeIsKeptOffTheScreenReadersThread() throws Exception {
        Feedback.setAfterDash(app, true);
        Dashing.seen(app);
        long start = Dashing.currentStart(app);
        DashSummary.window(app, "win=full/-/dasher/100");
        DashSummary.flush();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(20));
        DashSummary.window(app, "win=split/bottom/dasher/48");
        DashSummary.flush();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(20));
        DashSummary.window(app, "win=split/bottom/dasher/48");
        // Read again at once: a comparison on the reader's thread, nothing handed over.
        DashSummary.window(app, "win=split/bottom/dasher/48");
        DashSummary.window(app, "win=full/-/dasher/100");
        DashSummary.stuck(app, "waiting for the question");
        DashSummary.flush();
        String summary = DashSummary.build(app, start, start + 600_000L, DashSummary.End.DASH_OVER,
                modelForTest(start));
        assertTrue(summary, summary.contains("Window layout (share of observed time): full 50%, split 50% of"));
    }

    // ---- The quiet end, found when the app opens or a service connects ----

    /** A dash still open whose last sighting was {@code quietMs} ago, begun three hours ago. */
    private long openDash(long quietMs) {
        long now = System.currentTimeMillis();
        long started = now - 3 * 3_600_000L;
        app.getSharedPreferences("dashing", Context.MODE_PRIVATE).edit().putLong("started_at", started)
                .putLong("seen_at", now - quietMs).putBoolean("open", true).commit();
        return started;
    }

    @Test
    public void thirtyQuietMinutesEndADashForItsSummaryAndNothingSooner() {
        openDash(29 * 60_000L);
        assertNull("29 minutes is not quiet yet", Dashing.quietlyEnded(app));
        long started = openDash(Dashing.WINDOW_MS + 1_000L);
        android.content.SharedPreferences dashing = app.getSharedPreferences("dashing", Context.MODE_PRIVATE);
        assertArrayEquals(new long[] {started, dashing.getLong("seen_at", 0)}, Dashing.quietlyEnded(app));
        Dashing.ended(app);
        assertNull("an ended dash is not one that went quiet", Dashing.quietlyEnded(app));
    }

    @Test
    public void aDashThatWentQuietIsSummarizedWhenTheAppOpens() throws Exception {
        Feedback.setAfterDash(app, true);
        openDash(40 * 60_000L);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            settle();
            assertEquals(1, service.count());
            assertTrue(summarySent(0).contains("ended: nothing of the dash seen for 30 minutes"));
            // Opened again: one summary a dash.
            activity.recreate();
            settle();
            assertEquals(1, service.count());
        }
    }

    @Test
    public void aDashThatWentQuietIsSummarizedWhenAServiceConnects() throws Exception {
        Feedback.setAfterDash(app, true);
        openDash(40 * 60_000L);
        ServiceController<OfferNotificationService> listener =
                Robolectric.buildService(OfferNotificationService.class).create();
        try {
            listener.get().onListenerConnected();
            settle();
            assertEquals(1, service.count());
            assertTrue(summarySent(0).contains("ended: nothing of the dash seen for 30 minutes"));
        } finally {
            listener.destroy();
        }
    }

    @Test
    public void aDashThatWentQuietIsNotSummarizedWhileTheOptInIsOff() {
        openDash(40 * 60_000L);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            settle();
            assertEquals(0, service.count());
        }
    }

    @Test
    public void dashersEndScreensEndADashButAPauseOrTheDashsOwnScreenDoNot() {
        assertTrue(OfferEvidence.isDashOver(Arrays.asList("Dash ended", "$84.20")));
        assertTrue(OfferEvidence.isDashOver(Arrays.asList("Dash summary", "4 offers")));
        assertTrue(OfferEvidence.isDashOver(Arrays.asList("Dash now", "Schedule")));
        assertTrue(OfferEvidence.isDashOver(Arrays.asList("Your dash has ended")));
        assertFalse("a proposal to end can still be cancelled", OfferEvidence.isDashOver(Arrays.asList("End dash?",
                "Are you sure you want to end your dash?", "End dash", "Cancel")));
        assertFalse("Dasher's own question, whatever it is drawn over",
                OfferEvidence.isDashOver(Arrays.asList("End your current dash?", "End dash", "Go back")));
        assertFalse("a menu action alone is not completion", OfferEvidence.isDashOver(Arrays.asList("End dash")));
        assertFalse("the dash's own screen", OfferEvidence.isDashOver(Arrays.asList("Finding offers", "End dash")));
        assertFalse("a pause", OfferEvidence.isDashOver(Arrays.asList("Dash paused", "Resume dash", "End dash")));
        assertFalse("a delivery", OfferEvidence.isDashOver(Arrays.asList("Deliver by 9:45 PM",
                "Complete delivery steps", "End dash")));
        assertFalse(OfferEvidence.isDashOver(Arrays.asList("Deliver to [name]", "Call", "Message")));
    }

    @Test
    public void durationsAreRoundedAndTimesAreRelative() {
        assertEquals("3 h 05 min", DashSummary.duration(3 * 3_600_000L + 4 * 60_000L));
        assertEquals("15 min", DashSummary.duration(13 * 60_000L));
        assertEquals("+1:02:07", DashSummary.relative(3_727_000L));
        assertEquals("-0:00:05", DashSummary.relative(-5_000L));
        assertEquals("split", DashSummary.layoutOf("win=split/bottom/dasher/48"));
        assertEquals("unknown", DashSummary.layoutOf("win=unknown"));
        assertEquals("opened Dasher", DashSummary.peekCategory("opening Dasher for Chipotle (was in front: x)"));
    }

    /** A peek's cost line is all figures: one fixed category, never one per count of digits. */
    @Test
    public void aPeeksCostLineIsOneFixedCategory() {
        assertEquals("cost", DashSummary.peekCategory("cost: 3 reads, 1204 nodes, 2 remote fetches ≥2 ms, slowest 412 ms, "
                + "57 Dasher events"));
        assertEquals("cost", DashSummary.peekCategory("cost: 12 reads, 98 nodes, 0 remote fetches ≥2 ms, slowest 9 ms, "
                + "3 Dasher events"));
    }

    // ---- Dasher on screen ----

    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo result = AccessibilityNodeInfo.obtain(new View(app));
        result.setPackageName("com.doordash.driverapp");
        result.setText(text);
        result.setVisibleToUser(true);
        result.setEnabled(true);
        result.setClickable(clickable);
        if (clickable) {
            result.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            Shadows.shadowOf(result).setOnPerformActionListener((action, args) -> true);
        }
        return result;
    }

    private AccessibilityNodeInfo screen(String... labels) {
        AccessibilityNodeInfo root = node("", false);
        for (String label : labels) Shadows.shadowOf(root).addChild(node(label, false));
        return root;
    }

    private AccessibilityNodeInfo offer(String pay, String extra) {
        AccessibilityNodeInfo root = node("", false);
        decline = node("Decline", true);
        List<AccessibilityNodeInfo> children = Arrays.asList(node(extra, false), decline, node(pay, false),
                node("2 stops (7.2 mi) • 21 min", false), node("Accept", true), node("0:35", false));
        for (AccessibilityNodeInfo child : children) Shadows.shadowOf(root).addChild(child);
        return root;
    }

    private void show(AccessibilityNodeInfo root) {
        if (controller == null) {
            OfferFilterService.scanLooperForTests = Looper.getMainLooper();
            controller = Robolectric.buildService(OfferFilterService.class).create();
        }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2));
        TestWindows.full(controller.get(), root);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("com.doordash.driverapp");
        controller.get().onAccessibilityEvent(event);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void pass(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }
}

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
 * never a name, an address or a place. The feedback service is a fake (ConsentedTestApp).
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
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
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

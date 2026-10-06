package com.local.dasherfilter;

import android.app.Application;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.os.Looper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The feedback outbox: written whole before anything is sent, sent part by part (each crossed off as the service
 * acknowledges it), the notice and the opt-in rechecked before every part, masked again right before sending, kept
 * and retried with backoff on no connection, 429 or a server error, dropped with an honest word on 400/413, and
 * bounded to five submissions and seven days. The service is a fake (ConsentedTestApp).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class FeedbackOutboxTest {
    private Application app;
    private FakeFeedbackTransport service;
    private final List<Feedback.Event> events = new ArrayList<>();

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        service = FakeFeedbackTransport.installed();
        Feedback.listen(events::add);
    }

    @After
    public void cleanup() {
        FeedbackOutbox.clock = System::currentTimeMillis;
        settle();
    }

    private void settle() {
        Feedback.flush();
        FeedbackOutbox.flush();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private File[] queued() {
        File[] files = new File(app.getFilesDir(), FeedbackOutbox.DIR).listFiles();
        if (files == null) return new File[0];
        Arrays.sort(files);
        return files;
    }

    private static JSONObject read(File file) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    private JSONObject item(boolean automatic, String... parts) {
        return FeedbackOutbox.item(Feedback.newToken(), automatic ? Feedback.Kind.DIAGNOSTICS : Feedback.Kind.FEEDBACK,
                "general", automatic, FeedbackOutbox.TEXT, automatic ? "" : "Typed words", parts.length > 0,
                Arrays.asList(parts));
    }

    private Feedback.Event last() {
        assertFalse("an event", events.isEmpty());
        return events.get(events.size() - 1);
    }

    @Test
    public void aSubmissionIsWrittenWholeBeforeAnythingIsSentAndWaitsForANetwork() throws Exception {
        service.down = true;
        String token = Feedback.sendFeedback(app, Feedback.Category.IDEA, "  Make the map bigger  ", false, null);
        settle();

        File[] files = queued();
        assertEquals("written whole, no half-written file left", 1, files.length);
        assertTrue(files[0].getName().endsWith(".json"));
        JSONObject item = read(files[0]);
        assertEquals(token, item.getString("token"));
        assertEquals("feedback", item.getString("kind"));
        assertEquals("feature", item.getString("category"));
        assertEquals("Make the map bigger", item.getString("message"));
        assertEquals(0, item.getInt("sent"));
        assertEquals(1, service.count());
        assertEquals(Feedback.State.OFFLINE, last().state);
        assertEquals("Saved; it will send when you're online", last().said);
        assertTrue(last().user);

        JobInfo job = app.getSystemService(JobScheduler.class).getPendingJob(FeedbackOutbox.JOB_ID);
        assertNotNull("a job sends it once a network is available", job);
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.getNetworkType());
        assertTrue("kept across a restart", job.isPersisted());
        assertTrue(job.getMinLatencyMillis() >= FeedbackOutbox.FIRST_RETRY_MS);
        assertEquals("1 waiting to send · Saved; it will send when you're online", Feedback.status(app));

        service.down = false;
        assertFalse(FeedbackOutbox.drain(app, () -> false).retry);
        settle();
        assertEquals(0, queued().length);
        assertEquals(Feedback.State.SENT, last().state);
        assertEquals(token.substring(0, 8), last().reference);
        assertEquals(token.substring(0, 8), Feedback.references(app).get(0).reference);
        assertTrue(Feedback.status(app), Feedback.status(app).startsWith("Sent "));
    }

    @Test
    public void acknowledgedPartsAreCrossedOffAndARetrySendsOnlyThoseStillOwed() throws Exception {
        service.answer(201, "{\"ok\":true,\"reference\":\"aaaa1111\"}").answer(201, "{\"ok\":true,\"reference\":"
                + "\"bbbb2222\"}").offline();
        FeedbackOutbox.submit(app, item(false, "first\n", "second\n", "third"));
        settle();
        assertEquals(3, service.count());
        JSONObject waiting = read(queued()[0]);
        assertEquals("crossed off at once", 2, waiting.getInt("sent"));
        assertEquals("the first part's reference names the submission", "aaaa1111", waiting.getString("reference"));

        FeedbackOutbox.drain(app, () -> false);
        settle();
        List<JSONObject> sent = service.requests();
        assertEquals(4, sent.size());
        assertEquals("only the part still owed", 2, sent.get(3).getInt("partIndex"));
        Set<String> rows = new HashSet<>();
        for (JSONObject request : sent) rows.add(request.getString("reportToken") + "/" + request.getInt("partIndex"));
        assertEquals("one token, three parts", 3, rows.size());
        assertEquals(0, queued().length);
        assertEquals("aaaa1111", last().reference);
    }

    @Test
    public void aReplyLostAfterTheServiceStoredItIsRetriedUnderTheSameTokenAndPart() throws Exception {
        service.offline();
        FeedbackOutbox.submit(app, item(false));
        settle();
        FeedbackOutbox.drain(app, () -> false);
        settle();
        List<JSONObject> sent = service.requests();
        assertEquals(2, sent.size());
        assertEquals("the service stores a (token, part) once", sent.get(0).getString("reportToken"),
                sent.get(1).getString("reportToken"));
        assertEquals(sent.get(0).getInt("partIndex"), sent.get(1).getInt("partIndex"));
        assertEquals(0, queued().length);
    }

    @Test
    public void tooManySendsWaitsAtLeastTheServicesWindowAndAServerErrorItsRetryAfter() throws Exception {
        service.answer(429, "{\"ok\":false,\"error\":\"rate_limited\"}");
        FeedbackOutbox.submit(app, item(false, "part\n"));
        settle();
        assertEquals(Feedback.State.RATE_LIMITED, last().state);
        assertEquals("Too many sends from this network right now; it will retry", last().said);
        assertEquals(1, queued().length);
        JobInfo job = app.getSystemService(JobScheduler.class).getPendingJob(FeedbackOutbox.JOB_ID);
        assertTrue(job.getMinLatencyMillis() >= FeedbackOutbox.RATE_LIMIT_MS);

        service.answer(503, "{\"ok\":false}", 20 * 60_000L);
        FeedbackOutbox.Result result = FeedbackOutbox.drain(app, () -> false);
        settle();
        assertTrue(result.retry);
        assertTrue("the service's Retry-After", result.delayMs >= 20 * 60_000L);
        assertEquals(Feedback.State.UNAVAILABLE, last().state);
        assertEquals(1, queued().length);
    }

    @Test
    public void aRefusalDropsTheSubmissionSaysSoAndHandsTheWordsBack() throws Exception {
        for (int status : new int[] {400, 413}) {
            events.clear();
            service.answer(status, "{\"ok\":false,\"error\":\"invalid_feedback\"}");
            FeedbackOutbox.submit(app, item(false, "part\n"));
            settle();
            assertEquals(0, queued().length);
            assertEquals(Feedback.State.REJECTED, last().state);
            assertEquals("Couldn't be accepted (too long?)", last().said);
            assertEquals("the draft can be restored", "Typed words", last().typed);
            assertNull("nothing to retry", app.getSystemService(JobScheduler.class)
                    .getPendingJob(FeedbackOutbox.JOB_ID));
        }
        assertEquals("Couldn't be accepted (too long?)", Feedback.status(app));
    }

    @Test
    public void theNoticeIsRecheckedBeforeEveryPart() throws Exception {
        boolean[] once = {true};
        service.during = () -> {
            if (once[0]) ConsentedTestApp.forget(app);
            once[0] = false;
        };
        FeedbackOutbox.submit(app, item(false, "first\n", "second"));
        settle();
        assertEquals("nothing more leaves once the notice is no longer accepted", 1, service.count());
        assertEquals(1, read(queued()[0]).getInt("sent"));
        assertFalse(FeedbackOutbox.drain(app, () -> false).retry);
        assertEquals(1, service.count());

        service.during = null;
        Consent.accept(app);
        settle();
        assertEquals("accepting it sends what waited", 2, service.count());
        assertEquals(1, service.requests().get(1).getInt("partIndex"));
        assertEquals(0, queued().length);
    }

    @Test
    public void anAutomaticSummaryNeedsItsOptInBeforeEveryPart() throws Exception {
        Feedback.setAfterDash(app, true);
        service.during = () -> Feedback.setAfterDash(app, false);
        assertTrue(FeedbackOutbox.submitAutomatic(app, item(true, "first\n", "second")));
        settle();
        assertEquals("turning it off stops the rest, and discards it", 1, service.count());
        assertEquals(0, queued().length);
    }

    @Test
    public void eachPartIsMaskedAgainRightBeforeItIsSent() throws Exception {
        // Queued by an older version, before a masking repair: the repair reaches it.
        FeedbackOutbox.submit(app, item(false, "[screen] labels=[Deliver to Sam P, 100 Example St, $7.90]\n"));
        settle();
        String sent = Feedback.unframed(service.requests().get(0).getString("diagnostics"));
        assertFalse(sent, sent.contains("Sam"));
        assertFalse(sent, sent.contains("Example"));
        assertTrue(sent, sent.contains("Deliver to [name]"));
        assertTrue(sent, sent.contains("$7.90"));

        String report = new JSONObject().put("report", "offer").put("entry", new JSONObject()
                .put("evidence", new org.json.JSONArray().put("Order for Jane D. $7.90"))).toString();
        FeedbackOutbox.submit(app, FeedbackOutbox.item(Feedback.newToken(), Feedback.Kind.PROBLEM, "bug", false,
                FeedbackOutbox.OFFER, "", true, Collections.singletonList(report)));
        settle();
        JSONObject offer = new JSONObject(Feedback.unframed(service.requests().get(1).getString("diagnostics")));
        assertEquals("Order for [name] $7.90", offer.getJSONObject("entry").getJSONArray("evidence").getString(0));
        assertTrue(service.requests().get(1).getBoolean("diagnosticsConsented"));
    }

    @Test
    public void atMostFiveWaitAndTheUsersWordsComeBeforeAnAutomaticSummary() throws Exception {
        service.down = true;
        Feedback.setAfterDash(app, true);
        for (int i = 0; i < 4; i++) FeedbackOutbox.submit(app, item(false));
        assertTrue(FeedbackOutbox.submitAutomatic(app, item(true, "summary")));
        settle();
        assertEquals(5, queued().length);
        assertFalse("an automatic summary never pushes out the user's", FeedbackOutbox.submitAutomatic(app,
                item(true, "another summary")));
        FeedbackOutbox.submit(app, item(false));
        settle();
        File[] files = queued();
        assertEquals(5, files.length);
        for (File file : files) assertFalse("the automatic one made room", file.getName().endsWith("-a.json"));

        events.clear();
        FeedbackOutbox.submit(app, item(false));
        settle();
        assertEquals(Feedback.State.NOT_QUEUED, last().state);
        assertEquals("5 submissions are already waiting to send; try again later.", last().said);
        assertEquals(5, queued().length);
    }

    @Test
    public void nothingWaitsLongerThanSevenDays() throws Exception {
        service.down = true;
        FeedbackOutbox.submit(app, item(false));
        settle();
        assertEquals(1, queued().length);
        long now = System.currentTimeMillis();
        FeedbackOutbox.clock = () -> now + FeedbackOutbox.MAX_AGE_MS + 60_000L;
        service.down = false;
        FeedbackOutbox.drain(app, () -> false);
        assertEquals(0, queued().length);
        assertEquals("expired, not sent", 1, service.count());
        assertTrue(DiagnosticLog.read(app).contains("unsent after seven days, discarded"));
    }

    @Test
    public void clearingHistoryDiscardsOnlyAutomaticSummaries() throws Exception {
        service.down = true;
        Feedback.setAfterDash(app, true);
        FeedbackOutbox.submit(app, item(false));
        FeedbackOutbox.submitAutomatic(app, item(true, "summary"));
        settle();
        assertEquals(2, queued().length);
        FeedbackOutbox.discardAutomatic(app);
        File[] left = queued();
        assertEquals(1, left.length);
        assertFalse(left[0].getName().endsWith("-a.json"));
    }

    @Test
    public void nothingIsSentWhileTheOneTimePrivacyCleanupIsUnfinished() throws Exception {
        service.down = true;
        FeedbackOutbox.submit(app, item(false));
        settle();
        int before = service.count();
        DiagnosticLog.read(app);
        app.getSharedPreferences("offer_filter_diagnostics", Context.MODE_PRIVATE).edit()
                .remove(DiagnosticLog.CLEANED_UP).commit();
        File blocked = new File(app.getFilesDir(), "dasher-screens.log");
        assertTrue(blocked.mkdir());
        File child = new File(blocked, "synthetic.txt");
        Files.write(child.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        DiagnosticLog.forgetCache();
        service.down = false;
        try {
            assertTrue(FeedbackOutbox.drain(app, () -> false).retry);
            assertEquals(before, service.count());
        } finally {
            assertTrue(child.delete());
            DiagnosticLog.forgetCache();
        }
        assertFalse(FeedbackOutbox.drain(app, () -> false).retry);
        assertEquals(before + 1, service.count());
    }

    @Test
    public void aStoppedJobStartsNoFurtherRequest() throws Exception {
        service.down = true;
        FeedbackOutbox.submit(app, item(false, "one\n", "two"));
        settle();
        service.down = false;
        int before = service.count();
        FeedbackOutbox.Result result = FeedbackOutbox.drain(app, () -> true);
        assertTrue(result.retry);
        assertEquals(before, service.count());
        assertEquals(1, queued().length);
    }

    @Test
    public void everySubmissionGetsItsOwnTokenAndNoDeviceIdentifier() throws Exception {
        Feedback.sendFeedback(app, Feedback.Category.GENERAL, "one", false, null);
        Feedback.sendFeedback(app, Feedback.Category.GENERAL, "two", false, null);
        settle();
        List<JSONObject> sent = service.requests();
        assertEquals(2, sent.size());
        assertFalse(sent.get(0).getString("reportToken").equals(sent.get(1).getString("reportToken")));
        for (JSONObject request : sent) {
            assertEquals(1, request.getInt("partCount"));
            assertFalse(request.has("diagnostics"));
            assertFalse(request.getBoolean("diagnosticsConsented"));
            assertEquals(Feedback.versionCode(app), request.getLong("appVersionCode"));
        }
    }
}

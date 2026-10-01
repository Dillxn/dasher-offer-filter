package com.local.dasherfilter;

import android.app.Application;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Problem reports: what they contain, when they are filed, and how they reach GitHub (a local fake here). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ReportOutboxTest {
    private static final String PRODUCTION_ENDPOINT = GitHubIssues.endpoint;
    private static final String SHIPPED_CLIENT_ID = GitHubConnect.clientId;
    private static final FilterSettings RULES = new FilterSettings(true, 1200, 150, 60, 0, 4);

    private Application app;
    private ServerSocket server;
    private final List<JSONObject> received = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();
    private final List<String> apiVersions = new ArrayList<>();

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        FilterStore.save(app, RULES);
        DecisionLog.forgetCache();
        ReportOutbox.forgetCache();
    }

    @After
    public void stop() {
        GitHubConnect.disconnect(app);
        GitHubConnect.clientId = SHIPPED_CLIENT_ID;
        GitHubIssues.endpoint = PRODUCTION_ENDPOINT;
        ReportOutbox.clock = System::currentTimeMillis;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    private static DecisionLog.Entry unreadable(String pay) {
        return new DecisionLog.Entry(1000, DecisionLog.Source.SCREEN, false, new OfferSnapshot(null, 7.2, 21, 2), 0,
                OfferRule.Result.REVIEW, "pay not found", DecisionLog.Action.NEEDS_REVIEW, true,
                Arrays.asList(pay, "2 stops (7.2 mi) • 21 min"));
    }

    private static ProblemReport report(String pay) {
        DecisionLog.Entry entry = unreadable(pay);
        return ProblemReport.build(ProblemReport.Kind.UNREADABLE_OFFER, "0.4.9", RULES, entry,
                Arrays.asList(pay, "2 stops (7.2 mi) • 21 min", "Accept", "Decline"), null, null,
                Collections.<DecisionLog.Entry>emptyList());
    }

    /** The JSON block the fixer reads. */
    private static JSONObject data(ProblemReport report) throws JSONException {
        int start = report.body.indexOf("```json\n") + "```json\n".length();
        return new JSONObject(report.body.substring(start, report.body.indexOf("\n```", start)));
    }

    /** A one-thread HTTP/1.1 fake of GitHub's issue endpoint that answers every POST with {@code status}. */
    private void fakeGitHub(int status, String response) throws IOException {
        // Each fake keeps its own socket, so one replaced mid-test never answers for the next.
        ServerSocket listening = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        server = listening;
        Thread thread = new Thread(() -> {
            while (!listening.isClosed()) {
                try (Socket socket = listening.accept()) {
                    answer(socket, status, response);
                } catch (IOException | JSONException closedOrMalformed) {
                    // The next accept() fails once the server is closed, which ends the loop.
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        GitHubIssues.endpoint = "http://127.0.0.1:" + server.getLocalPort() + "/issues";
    }

    private void answer(Socket socket, int status, String response) throws IOException, JSONException {
        InputStream in = socket.getInputStream();
        Map<String, String> headers = new HashMap<>();
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US), line.substring(colon + 1).trim());
        }
        byte[] body = new byte[Integer.parseInt(headers.getOrDefault("content-length", "0"))];
        for (int read = 0, count; read < body.length; read += count) {
            count = in.read(body, read, body.length - read);
            if (count < 0) throw new IOException("request cut short");
        }
        synchronized (received) {
            received.add(new JSONObject(new String(body, StandardCharsets.UTF_8)));
            authorizations.add(headers.get("authorization"));
            apiVersions.add(headers.get("x-github-api-version"));
        }
        byte[] answer = response.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 " + status + " Fake\r\nContent-Type: application/json\r\nContent-Length: " + answer.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(answer);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n') if (c != '\r') line.append((char) c);
        return line.toString();
    }

    private void queue(int count) {
        for (int i = 0; i < count; i++) assertTrue(ReportOutbox.submit(app, report("Pay " + i), true));
        ReportOutbox.flush();
    }

    // ---- What a report contains ----

    @Test
    public void reportCarriesExactlyWhatWasReadAsATestFixture() throws JSONException {
        ProblemReport report = report("Guaranteed pay");

        assertTrue(report.title.startsWith(ProblemReport.TITLE_PREFIX + " Unreadable offer: "));
        assertTrue(report.body.contains("**Rules:** "));
        JSONObject data = data(report);
        assertEquals("UNREADABLE_OFFER", data.getString("kind"));
        assertEquals("0.4.9", data.getString("appVersion"));
        assertEquals(report.signature, data.getString("signature"));
        assertEquals(1200, data.getJSONObject("rules").getInt("flatCents"));
        assertEquals(4, data.getJSONObject("rules").getInt("maxStops"));
        JSONArray labels = data.getJSONArray("labels");
        assertEquals("Guaranteed pay", labels.getString(0));
        assertEquals("Decline", labels.getString(3));
        assertEquals("pay not found", data.getJSONObject("entry").getString("reason"));
    }

    @Test
    public void errorsCarryTheirTypeAndTopFrames() throws JSONException {
        IllegalStateException error = new IllegalStateException("tree changed");
        ProblemReport report = ProblemReport.build(ProblemReport.Kind.SCAN_ERROR, "0.4.9", RULES, null, null, error,
                null, Collections.<DecisionLog.Entry>emptyList());

        assertEquals(ProblemReport.TITLE_PREFIX + " Screen read error: IllegalStateException", report.title);
        JSONObject thrown = data(report).getJSONObject("error");
        assertEquals("java.lang.IllegalStateException", thrown.getString("type"));
        // Messages can quote screen text, so they are masked like it; the type and stack carry the diagnosis.
        assertEquals("xxxx xxxxxxx", thrown.getString("message"));
        assertTrue(thrown.getJSONArray("stack").length() > 0);
        assertTrue(thrown.getJSONArray("stack").length() <= 12);
    }

    @Test
    public void theSameReadingGapIsOneProblemPerVersion() {
        assertEquals(report("Pay $7.90").signature, report("Guaranteed pay").signature);
        DecisionLog.Entry milesMissing = new DecisionLog.Entry(1000, DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(790, null, 21, 2), 1080, OfferRule.Result.REVIEW, "an enabled value was not found",
                DecisionLog.Action.NEEDS_REVIEW, true, Arrays.asList("$7.90", "2 stops • 21 min"));
        ProblemReport other = ProblemReport.build(ProblemReport.Kind.UNREADABLE_OFFER, "0.4.9", RULES, milesMissing,
                milesMissing.evidence, null, null, Collections.<DecisionLog.Entry>emptyList());
        assertNotEquals(report("Pay $7.90").signature, other.signature);
        ProblemReport nextVersion = ProblemReport.build(ProblemReport.Kind.UNREADABLE_OFFER, "0.5.0", RULES,
                unreadable("Pay $7.90"), null, null, null, Collections.<DecisionLog.Entry>emptyList());
        assertNotEquals(report("Pay $7.90").signature, nextVersion.signature);
    }

    @Test
    public void entrylessReportsTellLayoutsApartByWordsNotNumbers() {
        List<DecisionLog.Entry> none = Collections.emptyList();
        assertEquals(ProblemReport.build(ProblemReport.Kind.UNREADABLE_OFFER, "0.4.9", RULES, null,
                        Arrays.asList("Pay $7.90", "Decline"), null, null, none).signature,
                ProblemReport.build(ProblemReport.Kind.UNREADABLE_OFFER, "0.4.9", RULES, null,
                        Arrays.asList("Pay $12.25", "Decline"), null, null, none).signature);
        assertNotEquals(ProblemReport.build(ProblemReport.Kind.UNREADABLE_OFFER, "0.4.9", RULES, null,
                        Arrays.asList("Pay $7.90", "Decline"), null, null, none).signature,
                ProblemReport.build(ProblemReport.Kind.UNREADABLE_OFFER, "0.4.9", RULES, null,
                        Arrays.asList("Guaranteed pay", "Decline"), null, null, none).signature);
    }

    @Test
    public void screenTextCannotCloseTheJsonBlockEarly() throws JSONException {
        ProblemReport report = report("```\n# injected");

        assertEquals(2, report.body.split("```", -1).length - 1);
        assertEquals("'''\n# xxxxxxxx", data(report).getJSONArray("labels").getString(0));
    }

    @Test
    public void oversizedReportsShrinkToFitGitHub() throws JSONException {
        StringBuilder longLine = new StringBuilder();
        for (int i = 0; i < 400; i++) longLine.append("word ");
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < 2000; i++) labels.add(longLine.toString());
        List<DecisionLog.Entry> recent = new ArrayList<>();
        for (int i = 0; i < 200; i++) recent.add(unreadable("Pay " + i));

        ProblemReport report = ProblemReport.build(ProblemReport.Kind.USER_REPORT, "0.4.9", RULES,
                unreadable("Pay"), labels, null, "declined a good one", recent);

        assertTrue(report.body.length() <= ProblemReport.MAX_BODY_CHARS);
        JSONObject data = data(report);
        assertEquals("declined a good one", data.getString("note"));
        assertEquals("pay not found", data.getJSONObject("entry").getString("reason"));
        assertFalse(data.has("recent"));
    }

    @Test
    public void namesAndAddressesAreMaskedButFiguresAndOfferWordsStay() throws JSONException {
        List<String> screen = Arrays.asList("Order for Jane D.", "Chick-fil-A", "123 Main St",
                "$7.90 Guaranteed (incl. tips)", "2 stops (7.2 mi) • 21 min", "Deliver by 7:45 PM", "Accept", "Decline");
        DecisionLog.Entry entry = new DecisionLog.Entry(1000, DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(790, 7.2, 21, 2), 1080, OfferRule.Result.DECLINE, "dollars per mile",
                DecisionLog.Action.DECLINE_TAPPED, true, Arrays.asList("Jane's order $7.90"));
        ProblemReport report = ProblemReport.build(ProblemReport.Kind.DECLINE_STUCK, "0.4.9", RULES, entry, screen,
                null, null, Collections.singletonList(entry));

        JSONArray labels = data(report).getJSONArray("labels");
        // Names and addresses go as the phone keeps them (PersonalText), then every other word to its shape.
        assertEquals("Order for [name]", labels.getString(0));
        assertEquals("single-letter words like \"A\" are vocabulary", "Xxxxx-xxx-A", labels.getString(1));
        assertEquals("[address]", labels.getString(2));
        assertEquals("$7.90 Guaranteed (incl. tips)", labels.getString(3));
        assertEquals("2 stops (7.2 mi) • 21 min", labels.getString(4));
        assertEquals("Deliver by 7:45 XX", labels.getString(5));
        assertEquals("Accept", labels.getString(6));
        assertEquals("[name]'s order $7.90",
                data(report).getJSONObject("entry").getJSONArray("evidence").getString(0));
        assertEquals("[name]'s order $7.90",
                data(report).getJSONArray("recent").getJSONObject(0).getJSONArray("evidence").getString(0));
        assertFalse(report.body.contains("Jane"));
        assertFalse(report.body.contains("Main"));
        assertEquals(Collections.singletonList("Order for [name] at Xxxxx-xxx-A $7.90"),
                ProblemReport.redact(Collections.singletonList("Order for Jane D. at Chick-fil-A $7.90")));
    }

    @Test
    public void namesThatAreAlsoWordsAndLongNumbersAreMaskedToo() throws JSONException {
        assertEquals("Order for Xxxx Xxx", ProblemReport.redact("Order for Will May"));
        assertEquals("#### Xxxx Xx Xxx 5, Xxx Xxxxxxxxx, XX #####",
                ProblemReport.redact("1234 Main St Apt 5, San Francisco, CA 94110"));
        assertEquals("(415) 555-####", ProblemReport.redact("(415) 555-1234"));
        assertEquals("$12.50 · 3.4 mi · 120 min", ProblemReport.redact("$12.50 · 3.4 mi · 120 min"));

        ProblemReport report = ProblemReport.build(ProblemReport.Kind.SCAN_ERROR, "0.4.9", RULES, null, null,
                new NumberFormatException("For input string: \"Jane\""), null,
                Collections.<DecisionLog.Entry>emptyList());
        assertFalse(report.body.contains("Jane"));
    }

    @Test
    public void labelsAreClippedSoOneHugeNodeCannotCrowdOutTheRest() throws JSONException {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 1000; i++) huge.append('x');
        ProblemReport report = ProblemReport.build(ProblemReport.Kind.UNREADABLE_OFFER, "0.4.9", RULES, null,
                Arrays.asList(huge.toString(), "Decline", "Accept"), null, null, Collections.<DecisionLog.Entry>emptyList());

        String clipped = data(report).getJSONArray("labels").getString(0);
        assertEquals(201, clipped.length());
        assertTrue(clipped.endsWith("…"));
    }

    // ---- When reports are filed ----

    @Test
    public void nothingIsFiledOrQueuedUntilReportsAreOn() {
        ReportOutbox.fileAutomatic(app, ProblemReport.Kind.SCAN_ERROR, null, null, new IllegalStateException());
        assertFalse(ReportOutbox.fileByUser(app, unreadable("Pay"), "note"));
        // Connected to GitHub, but Send problem reports still off.
        connectedToGitHub();
        ReportOutbox.fileAutomatic(app, ProblemReport.Kind.SCAN_ERROR, null, null, new IllegalStateException());
        assertFalse(ReportOutbox.fileByUser(app, unreadable("Pay"), "note"));
        ReportOutbox.flush();

        assertEquals(0, ReportOutbox.queued(app));
        assertTrue(ReportOutbox.status(app).startsWith("Off"));
    }

    /** Connected to GitHub and "Send problem reports" on: the only way reports go. */
    private void reportsOn() {
        connectedToGitHub();
        ReportOutbox.useGitHub(app, true);
    }

    @Test
    public void theSameAutomaticProblemIsFiledOnceEvenAcrossARestart() {
        reportsOn();
        ReportOutbox.fileAutomatic(app, ProblemReport.Kind.UNREADABLE_OFFER, unreadable("Pay $7.90"),
                Arrays.asList("Pay $7.90", "Decline"), null);
        ReportOutbox.fileAutomatic(app, ProblemReport.Kind.UNREADABLE_OFFER, unreadable("Pay $8.40"),
                Arrays.asList("Pay $8.40", "Decline"), null);
        ReportOutbox.forgetCache();
        ReportOutbox.fileAutomatic(app, ProblemReport.Kind.UNREADABLE_OFFER, unreadable("Pay $9.10"),
                Arrays.asList("Pay $9.10", "Decline"), null);
        ReportOutbox.flush();

        assertEquals(1, ReportOutbox.queued(app));
    }

    @Test
    public void reportsTheUserAsksForAreNeverDeduplicated() {
        reportsOn();
        assertTrue(ReportOutbox.fileByUser(app, unreadable("Pay"), "wrong"));
        assertTrue(ReportOutbox.fileByUser(app, unreadable("Pay"), "still wrong"));
        ReportOutbox.flush();

        assertEquals(2, ReportOutbox.queued(app));
    }

    @Test
    public void automaticReportsStopAtTheDailyCap() {
        reportsOn();
        for (int i = 0; i < 15; i++) {
            StringBuilder layout = new StringBuilder("layout");
            for (int j = 0; j < i; j++) layout.append(" x");
            ReportOutbox.fileAutomatic(app, ProblemReport.Kind.UNREADABLE_OFFER, null,
                    Arrays.asList(layout.toString(), "Decline"), null);
        }
        ReportOutbox.flush();

        assertEquals(10, ReportOutbox.queued(app));
        // The user's own reports have their own allowance.
        assertTrue(ReportOutbox.fileByUser(app, unreadable("Pay"), null));
    }

    @Test
    public void reportsOfOneProblemInTheSameMillisecondAreAllKept() {
        // A coarse clock (as on some build machines, and some phones) gives consecutive reports the same time.
        ReportOutbox.clock = () -> 1_790_000_000_000L;
        reportsOn();
        ProblemReport report = report("Pay $7.90");
        for (int i = 0; i < 5; i++) assertTrue(ReportOutbox.submit(app, report, true));
        ReportOutbox.flush();

        assertEquals(5, ReportOutbox.queued(app));
    }

    @Test
    public void queuedReportsWaitForAnyNetwork() {
        reportsOn();
        queue(1);

        JobInfo job = app.getSystemService(JobScheduler.class).getPendingJob(ReportOutbox.JOB_ID);
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.getNetworkType());
        assertTrue(job.isPersisted());
    }

    // ---- Sending ----

    @Test
    public void queuedReportsAreFiledAsIssuesAndRemembered() throws IOException, JSONException {
        fakeGitHub(201, "{\"number\": 42}");
        reportsOn();
        queue(2);

        assertFalse(ReportOutbox.drain(app));

        assertEquals(0, ReportOutbox.queued(app));
        assertEquals(2, received.size());
        assertTrue(received.get(0).getString("title").startsWith(ProblemReport.TITLE_PREFIX));
        assertTrue(received.get(0).getString("body").contains("```json"));
        assertEquals("Bearer ghu_connection", authorizations.get(0));
        assertEquals("2022-11-28", apiVersions.get(0));
        assertTrue(ReportOutbox.status(app).contains("#42"));
    }

    @Test
    public void aRefusedConnectionKeepsReportsUntilGitHubTakesThem() throws IOException {
        fakeGitHub(401, "{\"message\": \"Bad credentials\"}");
        reportsOn();
        queue(2);

        assertFalse(ReportOutbox.drain(app));

        assertEquals(1, received.size());
        assertEquals(2, ReportOutbox.queued(app));
        assertTrue(ReportOutbox.status(app), ReportOutbox.status(app).contains("GitHub refused the report (401"));

        server.close();
        fakeGitHub(201, "{\"number\": 3}");
        assertFalse(ReportOutbox.drain(app));
        assertEquals(0, ReportOutbox.queued(app));
        assertTrue(ReportOutbox.status(app).startsWith("On"));
    }

    @Test
    public void aReportGitHubRefusesIsDroppedRatherThanRetriedForever() throws IOException {
        fakeGitHub(422, "{\"message\": \"Validation Failed\"}");
        reportsOn();
        queue(2);

        assertFalse(ReportOutbox.drain(app));

        assertEquals(2, received.size());
        assertEquals(0, ReportOutbox.queued(app));
    }

    @Test
    public void anOutageOrRateLimitKeepsEveryReportForARetry() throws IOException {
        for (int status : new int[] {503, 429, 500}) {
            fakeGitHub(status, "{\"message\": \"try later\"}");
            reportsOn();
            queue(2);

            assertTrue(ReportOutbox.drain(app));
            assertEquals(status + " keeps the queue", 2, ReportOutbox.queued(app));
            assertTrue(ReportOutbox.status(app).startsWith("On"));
            ReportOutbox.useGitHub(app, false);
            ReportOutbox.flush();
            stop();
            server = null;
        }
    }

    @Test
    public void aRateLimitSaidOnlyInTheMessageIsRetriedNotBlamedOnTheToken() throws IOException {
        fakeGitHub(403, "{\"message\": \"You have exceeded a secondary rate limit.\"}");
        reportsOn();
        queue(1);

        assertTrue(ReportOutbox.drain(app));
        assertEquals(1, ReportOutbox.queued(app));
        assertTrue(ReportOutbox.status(app).startsWith("On"));
    }

    @Test
    public void noConnectionKeepsEveryReportAndAsksForARetry() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        GitHubIssues.endpoint = "http://127.0.0.1:" + closedPort + "/issues";
        reportsOn();
        queue(2);

        assertTrue(ReportOutbox.drain(app));
        assertEquals(2, ReportOutbox.queued(app));
    }

    @Test
    public void turningReportsOffDiscardsWhatWasWaiting() throws IOException {
        fakeGitHub(201, "{\"number\": 7}");
        reportsOn();
        queue(1);
        ReportOutbox.useGitHub(app, false);
        ReportOutbox.flush();

        assertFalse(ReportOutbox.drain(app));
        assertEquals(0, received.size());
        assertEquals(0, ReportOutbox.queued(app));
        assertTrue(ReportOutbox.status(app).startsWith("Off"));
    }

    /** Signed in to GitHub for updates, as the phone would be after Connect GitHub. */
    private void connectedToGitHub() {
        GitHubConnect.clientId = "Iv1.test";
        app.getSharedPreferences("github", android.content.Context.MODE_PRIVATE).edit()
                .putString("access_token", "ghu_connection").commit();
    }

    @Test
    public void reportsUseTheGitHubConnectionOnlyOnceTurnedOn() throws IOException {
        String shipped = GitHubConnect.clientId;
        try {
            fakeGitHub(201, "{\"number\": 9}");
            connectedToGitHub();
            assertFalse("connecting GitHub never turns reports on", ReportOutbox.enabled(app));
            assertEquals("Off", ReportOutbox.status(app));

            ReportOutbox.useGitHub(app, true);
            assertTrue(ReportOutbox.enabled(app));
            queue(1);
            assertFalse(ReportOutbox.drain(app));
            assertEquals("Bearer ghu_connection", authorizations.get(0));
            assertTrue(ReportOutbox.status(app).contains("#9"));
        } finally {
            GitHubConnect.disconnect(app);
            GitHubConnect.clientId = shipped;
        }
    }

    @Test
    public void turningTheConnectionOffOrDisconnectingDiscardsWhatWasWaiting() throws IOException {
        String shipped = GitHubConnect.clientId;
        try {
            fakeGitHub(201, "{\"number\": 7}");
            connectedToGitHub();
            ReportOutbox.useGitHub(app, true);
            queue(1);
            ReportOutbox.useGitHub(app, false);
            ReportOutbox.flush();
            assertEquals(0, ReportOutbox.queued(app));
            assertFalse(ReportOutbox.enabled(app));

            ReportOutbox.useGitHub(app, true);
            queue(1);
            GitHubConnect.disconnect(app);
            ReportOutbox.flush();
            assertFalse(ReportOutbox.enabled(app));
            assertEquals("disconnecting GitHub is removing the token", 0, ReportOutbox.queued(app));
            assertFalse(ReportOutbox.drain(app));
            assertEquals(0, received.size());
        } finally {
            GitHubConnect.disconnect(app);
            GitHubConnect.clientId = shipped;
        }
    }

    @Test
    public void aConnectionWithoutIssuePermissionSaysWhatToChange() throws IOException {
        String shipped = GitHubConnect.clientId;
        try {
            fakeGitHub(403, "{\"message\": \"Resource not accessible by integration\"}");
            connectedToGitHub();
            ReportOutbox.useGitHub(app, true);
            queue(1);
            assertFalse(ReportOutbox.drain(app));
            assertEquals("kept until it works", 1, ReportOutbox.queued(app));
            String status = ReportOutbox.status(app);
            assertTrue(status, status.contains("Issues: Read and write"));
            assertTrue("GitHub's own words are shown", status.contains("(403: Resource not accessible by integration)"));
            assertTrue("adding the permission is not enough; the installation must accept it",
                    status.contains("Installed GitHub Apps") && status.contains("accept its new permissions"));
        } finally {
            GitHubConnect.disconnect(app);
            GitHubConnect.clientId = shipped;
        }
    }

    @Test
    public void aRefusedReportIsSentAgainWhenTheAppOpensOnceGitHubAllowsIt() throws Exception {
        String shipped = GitHubConnect.clientId;
        try {
            fakeGitHub(403, "{\"message\": \"Resource not accessible by integration\"}");
            connectedToGitHub();
            android.content.SharedPreferences github =
                    app.getSharedPreferences("github", android.content.Context.MODE_PRIVATE);
            github.edit().putString("refresh_token", "ghr_refresh")
                    .putLong("access_expires_at", System.currentTimeMillis() + 3_600_000L).commit();
            ReportOutbox.useGitHub(app, true);
            queue(1);
            android.app.job.JobScheduler jobs = app.getSystemService(android.app.job.JobScheduler.class);
            jobs.cancelAll();
            assertFalse(ReportOutbox.drain(app));
            assertEquals("the next attempt asks GitHub for a fresh token first", 1,
                    github.getLong("access_expires_at", 0));

            // The permission is accepted on github.com; opening the app tries again.
            server.close();
            fakeGitHub(201, "{\"number\": 5}");
            github.edit().putLong("access_expires_at", 0).commit();
            ReportOutbox.retryRefused(app);
            ReportOutbox.flush();
            assertEquals("a send is scheduled", 1, jobs.getAllPendingJobs().size());
            assertFalse(ReportOutbox.drain(app));
            assertEquals(0, ReportOutbox.queued(app));
            assertTrue(ReportOutbox.status(app), ReportOutbox.status(app).contains("#5"));

            // With nothing refused, opening the app sends nothing more.
            jobs.cancelAll();
            ReportOutbox.retryRefused(app);
            ReportOutbox.flush();
            assertEquals(0, jobs.getAllPendingJobs().size());
        } finally {
            GitHubConnect.disconnect(app);
            GitHubConnect.clientId = shipped;
        }
    }

    @Test
    public void githubsErrorMessageIsOneShortPlainLine() {
        assertEquals("Resource not accessible by integration",
                GitHubIssues.messageOf("{\"message\": \"Resource not accessible by integration\", \"status\": \"403\"}"));
        assertEquals("", GitHubIssues.messageOf("<html>Forbidden</html>"));
        assertEquals("", GitHubIssues.messageOf(""));
        assertEquals("line one line two", GitHubIssues.messageOf("{\"message\": \"line one\\nline two\"}"));
        String longer = GitHubIssues.messageOf("{\"message\": \"" + "x".repeat(300) + "\"}");
        assertTrue(longer.length() <= 120 && longer.endsWith("…"));
    }
}

package com.local.dasherfilter;

import android.app.Application;
import android.app.job.JobScheduler;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Diagnostics after each dash: the switch and what turns it off, a dash's end filing exactly one issue (in parts) in
 * the repository through the GitHub connection, and nothing at all while it is off. GitHub is a local fake here.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class DashDiagnosticsTest {
    private static final String PRODUCTION_ENDPOINT = GitHubIssues.endpoint;
    private static final String CONNECTION_TOKEN = "ghu_connectionSecret123";

    /** One request the fake GitHub received. */
    private static final class Request {
        final String path;
        final String authorization;
        final JSONObject body;

        Request(String path, String authorization, JSONObject body) {
            this.path = path;
            this.authorization = authorization;
            this.body = body;
        }
    }

    private Application app;
    private String shippedClientId;
    private ServerSocket server;
    private final Map<String, Deque<String[]>> answers = new HashMap<>();
    private final List<Request> requests = new ArrayList<>();
    private ServiceController<OfferFilterService> controller;

    @Before
    public void setup() throws IOException {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        DiagnosticLog.setEnabled(app, true);
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        DecisionLog.forgetCache();
        ReportOutbox.forgetCache();
        DashDiagnostics.forgetCache();
        OfferSilencer.forgetCache();
        shippedClientId = GitHubConnect.clientId;
        server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread thread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    answer(socket);
                } catch (IOException | JSONException closedOrMalformed) {
                    // The next accept() fails once the server is closed, which ends the loop.
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        GitHubIssues.endpoint = "http://127.0.0.1:" + server.getLocalPort() + "/issues";
    }

    @After
    public void stop() throws IOException {
        if (controller != null) controller.destroy();
        OfferFilterService.scanLooperForTests = null;
        GitHubConnect.disconnect(app);
        GitHubConnect.clientId = shippedClientId;
        GitHubIssues.endpoint = PRODUCTION_ENDPOINT;
        DashDiagnostics.clock = System::currentTimeMillis;
        ReportOutbox.clock = System::currentTimeMillis;
        server.close();
    }

    // ---- The fake GitHub ----

    /** The next request to {@code path} gets {@code status} and {@code body}; unexpected ones get 404. */
    private void on(String path, int status, String body) {
        synchronized (answers) {
            answers.computeIfAbsent(path, unused -> new ArrayDeque<>()).add(new String[] {String.valueOf(status), body});
        }
    }

    private void answer(Socket socket) throws IOException, JSONException {
        InputStream in = socket.getInputStream();
        String path = readLine(in).split(" ")[1];
        Map<String, String> headers = new HashMap<>();
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US), line.substring(colon + 1).trim());
            }
        }
        byte[] body = new byte[Integer.parseInt(headers.getOrDefault("content-length", "0"))];
        for (int read = 0, count; read < body.length; read += count) {
            count = in.read(body, read, body.length - read);
            if (count < 0) throw new IOException("request cut short");
        }
        String[] answer;
        synchronized (answers) {
            requests.add(new Request(path, headers.get("authorization"),
                    new JSONObject(new String(body, StandardCharsets.UTF_8))));
            Deque<String[]> queue = answers.get(path);
            answer = queue == null || queue.isEmpty() ? new String[] {"404", "{}"} : queue.poll();
        }
        byte[] response = answer[1].getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 " + answer[0] + " Fake\r\nContent-Type: application/json\r\nContent-Length: "
                + response.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(response);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n') if (c != '\r') line.append((char) c);
        return line.toString();
    }

    private List<Request> requests(String path) {
        List<Request> out = new ArrayList<>();
        synchronized (answers) {
            for (Request request : requests) if (request.path.equals(path)) out.add(request);
        }
        return out;
    }

    // ---- The phone ----

    /** Signed in to GitHub for updates, as the phone would be after Connect GitHub. */
    private void connectedToGitHub() {
        GitHubConnect.clientId = "Iv1.test";
        app.getSharedPreferences("github", Context.MODE_PRIVATE).edit()
                .putString("access_token", CONNECTION_TOKEN).commit();
    }

    /** Connected, reports through the connection on, and diagnostics after each dash on. */
    private void diagnosticsOn() {
        connectedToGitHub();
        ReportOutbox.useGitHub(app, true);
        assertTrue(DashDiagnostics.set(app, true));
    }

    /** Lets queued work (the dash's report, the outbox's disk writes) finish. */
    private static void settle() {
        DashDiagnostics.flush();
        ReportOutbox.flush();
        DashDiagnostics.flush();
        ReportOutbox.flush();
    }

    private int queuedDiagnostics() {
        ReportOutbox.flush();
        java.io.File[] files = new java.io.File(app.getFilesDir(), "report-outbox").listFiles(
                (parent, name) -> name.endsWith("-d.json"));
        return files == null ? 0 : files.length;
    }

    /** A whole dash's worth of history, so its report is too long for one issue. */
    private void aLongDash() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 10; i++) lines.add("$" + i + ".50 " + String.join("", Collections.nCopies(110, "y")));
        for (int i = 0; i < DecisionLog.MAX_ENTRIES; i++) {
            DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis() - 1000L * i,
                    DecisionLog.Source.SCREEN, false, new OfferSnapshot(500 + i, 7.2, 21, 2), 2000,
                    OfferRule.Result.DECLINE, "below the minimum", DecisionLog.Action.DECLINE_TAPPED, true, lines));
        }
        DecisionLog.flush();
    }

    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(new View(app));
        node.setPackageName("com.doordash.driverapp");
        node.setText(text);
        node.setVisibleToUser(true);
        node.setEnabled(true);
        node.setClickable(clickable);
        if (clickable) {
            node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            Shadows.shadowOf(node).setOnPerformActionListener((action, args) -> true);
        }
        return node;
    }

    private AccessibilityNodeInfo screen(String... labels) {
        AccessibilityNodeInfo root = node("", false);
        for (String label : labels) Shadows.shadowOf(root).addChild(node(label, !label.startsWith("$")
                && (label.equals("Accept") || label.equals("Decline"))));
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

    /** Dasher on screen: an offer (declined by the $20 minimum), the wait for offers, then the dash's end. */
    private void dashUntilItEnds() {
        show(screen("Deliver to Sam P", "$7.90", "2 stops (7.2 mi) • 21 min", "100 Example St", "Accept", "Decline"));
        show(screen("Finding offers", "End dash"));
        settle();
        show(screen("Dash ended", "Total earned $84.20"));
        // Dasher's last screen is read again and again: still one dash.
        show(screen("Dash ended", "Total earned $84.20"));
        settle();
    }

    // ---- The switch ----

    private static android.widget.Switch toggle(View view, String label) {
        if (view instanceof android.widget.Switch && label.contentEquals(((android.widget.Switch) view).getText())) {
            return (android.widget.Switch) view;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.Switch found = toggle(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void settingsOffersTheSwitchOnlyWithReportsThroughTheConnection() {
        try (org.robolectric.android.controller.ActivityController<MainActivity> activity =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
            idle();
            View content = activity.get().findViewById(android.R.id.content);
            android.widget.Switch diagnostics = toggle(content, "Share diagnostics after each dash");
            assertEquals("hidden until GitHub is connected", View.GONE, diagnostics.getVisibility());
        }
        connectedToGitHub();
        try (org.robolectric.android.controller.ActivityController<MainActivity> activity =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
            idle();
            View content = activity.get().findViewById(android.R.id.content);
            android.widget.Switch diagnostics = toggle(content, "Share diagnostics after each dash");
            android.widget.Switch viaGitHub = toggle(content, "Send problem reports");
            assertEquals(View.VISIBLE, diagnostics.getVisibility());
            assertFalse("not until reports go through the connection", diagnostics.isEnabled());
            assertFalse(diagnostics.isChecked());

            viaGitHub.setChecked(true);
            idle();
            assertTrue(diagnostics.isEnabled());
            assertFalse("never on by itself", diagnostics.isChecked());
            assertFalse(DashDiagnostics.on(app));
            diagnostics.setChecked(true);
            idle();
            assertTrue(DashDiagnostics.on(app));

            viaGitHub.setChecked(false);
            idle();
            assertFalse(DashDiagnostics.on(app));
            assertFalse("turned off with reports through the connection", diagnostics.isChecked());
            assertFalse(diagnostics.isEnabled());
        }
    }

    @Test
    public void theSwitchOnlyTurnsOnWithTheConnectionAndReportsThroughIt() {
        assertFalse("not connected", DashDiagnostics.set(app, true));
        assertFalse(DashDiagnostics.on(app));

        connectedToGitHub();
        assertFalse("connected, but reports through it are off", DashDiagnostics.set(app, true));
        assertFalse(DashDiagnostics.on(app));
        assertEquals("Needs Send problem reports", DashDiagnostics.status(app));

        ReportOutbox.useGitHub(app, true);
        assertFalse("turning reports through the connection on never turns diagnostics on", DashDiagnostics.on(app));
        assertTrue(DashDiagnostics.set(app, true));
        assertTrue(DashDiagnostics.on(app));
        assertTrue(DashDiagnostics.status(app).startsWith("On"));
    }

    @Test
    public void turningReportsThroughTheConnectionOffTurnsItOffAndDiscardsWhatWaits() {
        diagnosticsOn();
        dashUntilItEnds();
        assertEquals(1, queuedDiagnostics());
        assertTrue(ReportOutbox.fileByUser(app, new DecisionLog.Entry(1000, DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(790, 7.2, 21, 2), 1080, OfferRule.Result.DECLINE, "dollars per mile",
                DecisionLog.Action.DECLINE_TAPPED, true, java.util.Collections.<String>emptyList()), "wrong"));
        ReportOutbox.flush();

        ReportOutbox.useGitHub(app, false);
        ReportOutbox.flush();

        assertFalse(DashDiagnostics.on(app));
        assertEquals("unsent diagnostics are discarded", 0, queuedDiagnostics());
        assertEquals("and the problem report waiting with them", 0, ReportOutbox.queued(app));
        ReportOutbox.useGitHub(app, true);
        assertFalse("turning reports through the connection on again leaves diagnostics off", DashDiagnostics.on(app));
    }

    @Test
    public void disconnectingOrTurningReportsOffTurnsItOffAndDiscardsWhatWaits() {
        diagnosticsOn();
        dashUntilItEnds();
        assertEquals(1, queuedDiagnostics());
        GitHubConnect.disconnect(app);
        assertFalse(DashDiagnostics.on(app));
        assertEquals(0, queuedDiagnostics());
        connectedToGitHub();
        ReportOutbox.useGitHub(app, true);
        assertFalse("connecting again never turns it on", DashDiagnostics.on(app));

        assertTrue(DashDiagnostics.set(app, true));
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2));
        DashDiagnostics.forgetCache();
        dashUntilItEnds();
        assertEquals(1, queuedDiagnostics());
        ReportOutbox.useGitHub(app, false);
        assertFalse("turning reports off turns it off", DashDiagnostics.on(app));
        assertEquals(0, queuedDiagnostics());
    }

    @Test
    public void turningTheSwitchOffDiscardsUnsentDiagnosticsAndNothingIsSent() {
        diagnosticsOn();
        dashUntilItEnds();
        assertEquals(1, queuedDiagnostics());

        assertFalse(DashDiagnostics.set(app, false));
        assertEquals(0, queuedDiagnostics());
        assertTrue("reports through the connection stay on", ReportOutbox.throughGitHub(app));
        assertFalse(ReportOutbox.drain(app));
        assertTrue(requests.isEmpty());
    }

    // ---- A dash's end ----

    @Test
    public void mixedEndAndDeliveryDoesNotFileOrReleaseTheHoldBeforeAnUnambiguousEnd() {
        diagnosticsOn();
        DashDiagnostics.offerSeen(app);
        settle();
        ActiveRouteStore.save(app, new OfferSnapshot(2400, 5.0, 20, 2));
        Dashing.seen(app);
        // Contradictory delivery text can remain during task/window transitions. Neither the route, the update
        // hold nor this dash's automatic report may complete from that one mixed screen.
        show(screen("Dash ended", "Deliver to Sam P", "Total earned $84.20"));
        settle();
        assertEquals("delivery and end at once are not completion evidence", 0, queuedDiagnostics());
        assertTrue(Dashing.awaitingEnd(app));
        assertTrue(ActiveRouteStore.load(app) != null);
        show(screen("Dash ended", "Total earned $84.20"));
        settle();
        assertEquals("a clear completed end still files exactly once", 1, queuedDiagnostics());
        assertFalse(Dashing.awaitingEnd(app));
        assertNull(ActiveRouteStore.load(app));
        show(screen("Dash ended", "Total earned $84.20"));
        settle();
        assertEquals(1, queuedDiagnostics());
    }

    @Test
    public void aDashsEndFilesExactlyOneIssueInPartsThroughTheConnection() throws JSONException {
        on("/issues", 201, "{\"number\": 41}");
        for (int i = 0; i < 5; i++) on("/issues/41/comments", 201, "{\"id\": " + i + "}");
        diagnosticsOn();
        aLongDash();

        dashUntilItEnds();
        assertEquals(1, queuedDiagnostics());
        assertFalse(ReportOutbox.drain(app));

        List<Request> issues = requests("/issues");
        assertEquals("one issue for the dash", 1, issues.size());
        JSONObject issue = issues.get(0).body;
        String title = issue.getString("title");
        assertTrue(title, title.matches("\\[diagnostics] Offer Filter \\S+ \\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d–"
                + "(\\d{4}-\\d\\d-\\d\\d )?\\d\\d:\\d\\d"));
        assertEquals("diagnostics", issue.getJSONArray("labels").getString(0));
        List<Request> comments = requests("/issues/41/comments");
        assertTrue("the rest of a long report follows as comments", comments.size() >= 1);
        assertEquals(1 + comments.size(), requests.size());

        StringBuilder whole = new StringBuilder(issue.getString("body"));
        for (Request comment : comments) whole.append(comment.body.getString("body"));
        assertTrue(issue.getString("body").length() <= 60_000);
        for (Request comment : comments) assertTrue(comment.body.getString("body").length() <= 60_000);
        assertTrue(issue.getString("body"), issue.getString("body").contains("Part 1 of " + (1 + comments.size())));
        String report = whole.toString();
        assertTrue(report.contains("== Decision history") && report.contains("== Raw diagnostic log")
                && report.contains("== Dasher's other screens"));
        assertFalse("the whole report, never cut", report.contains("[report truncated]"));
        // Masked, and the token is never in it.
        assertTrue(report.contains("Deliver to [name]"));
        assertFalse(report.contains("Sam P") || report.contains("Example St"));
        for (Request request : requests) {
            assertEquals("only through the GitHub connection", "Bearer " + CONNECTION_TOKEN, request.authorization);
            assertFalse(request.body.toString().contains(CONNECTION_TOKEN));
        }
        assertFalse(DiagnosticLog.read(app).contains(CONNECTION_TOKEN));
        assertTrue(DiagnosticLog.read(app).contains("filed diagnostics issue #41"));
        assertTrue(DashDiagnostics.status(app), DashDiagnostics.status(app).contains("#41"));

        // Dasher's end screen read again, and the app opening: nothing more for that dash.
        show(screen("Dash ended", "Total earned $84.20"));
        DashDiagnostics.checkSoon(app);
        settle();
        assertEquals(0, ReportOutbox.queued(app));
        assertEquals(1 + comments.size(), requests.size());
    }

    @Test
    public void rateLimitedPartsWaitAndARetryNeverFilesTheDashTwice() throws IOException, JSONException {
        diagnosticsOn();
        aLongDash();
        dashUntilItEnds();
        assertEquals(1, queuedDiagnostics());

        // An explicit rate-limit refusal proves the comment was not created; it may be retried.
        on("/issues", 201, "{\"number\": 7}");
        on("/issues/7/comments", 429, "{\"message\": \"try later\"}");
        assertTrue("asks Android to retry", ReportOutbox.drain(app));
        assertEquals(1, queuedDiagnostics());
        JobScheduler jobs = app.getSystemService(JobScheduler.class);
        assertTrue(jobs.getPendingJob(ReportOutbox.JOB_ID) != null);

        // While Android has stopped the job, no outgoing part is attempted or marked ambiguous.
        assertTrue(ReportOutbox.drain(app, () -> true));
        assertEquals(1, queuedDiagnostics());

        // Back online: only the comments still owed are sent, on the issue already filed.
        for (int i = 0; i < 5; i++) on("/issues/7/comments", 201, "{\"id\": " + i + "}");
        assertFalse(ReportOutbox.drain(app));
        assertEquals(0, queuedDiagnostics());
        assertEquals("the dash's issue was filed once", 1, requests("/issues").size());
        int sent = 0;
        for (Request comment : requests("/issues/7/comments")) {
            if (comment.body.getString("body").startsWith("Part 2 of")) sent++;
        }
        assertEquals("the part that failed was sent again, once it went through", 2, sent);
    }

    @Test
    public void aLabelGitHubWillNotTakeDoesNotCostTheDashItsDiagnostics() throws JSONException {
        diagnosticsOn();
        dashUntilItEnds();
        on("/issues", 422, "{\"message\": \"Validation Failed\"}");
        on("/issues", 201, "{\"number\": 3}");
        on("/issues/3/comments", 201, "{\"id\": 1}");

        assertFalse(ReportOutbox.drain(app));

        List<Request> issues = requests("/issues");
        assertEquals(2, issues.size());
        assertFalse("filed again without the label", issues.get(1).body.has("labels"));
        assertEquals(0, queuedDiagnostics());
    }

    @Test
    public void thirtyMinutesWithNoOfferEndTheDash() {
        long[] now = {1_790_000_000_000L};
        DashDiagnostics.clock = () -> now[0];
        diagnosticsOn();
        DashDiagnostics.offerSeen(app);
        settle();
        JobScheduler jobs = app.getSystemService(JobScheduler.class);
        assertTrue("the quiet check is scheduled", jobs.getPendingJob(DashDiagnostics.JOB_ID) != null);

        now[0] += DashDiagnostics.QUIET_MS - 60_000L;
        DashDiagnostics.check(app);
        settle();
        assertEquals("29 minutes is not quiet yet", 0, queuedDiagnostics());

        now[0] += 60_000L;
        DashDiagnostics.check(app);
        settle();
        assertEquals(1, queuedDiagnostics());
        DashDiagnostics.check(app);
        settle();
        assertEquals("one per dash", 1, queuedDiagnostics());
    }

    @Test
    public void anOfferAfterAQuietHalfHourFilesTheDashBeforeIt() {
        long[] now = {1_790_000_000_000L};
        DashDiagnostics.clock = () -> now[0];
        diagnosticsOn();
        DashDiagnostics.offerSeen(app);
        settle();
        // Android held the quiet check back; the next offer comes 40 minutes later.
        now[0] += 40 * 60_000L;
        DashDiagnostics.offerSeen(app);
        settle();
        assertEquals(1, queuedDiagnostics());
        assertTrue(DashDiagnostics.status(app), DashDiagnostics.status(app).contains("dash under way"));
    }

    @Test
    public void dashersEndScreensEndADashButAPauseOrTheDashsOwnScreenDoNot() {
        assertEquals(DashDiagnostics.End.DASH_OVER, DashDiagnostics.endOf(Arrays.asList("Dash ended", "$84.20")));
        assertEquals(DashDiagnostics.End.DASH_OVER, DashDiagnostics.endOf(Arrays.asList("Dash summary", "4 offers")));
        assertEquals(DashDiagnostics.End.DASH_OVER, DashDiagnostics.endOf(Arrays.asList("Dash now", "Schedule")));
        assertNull("a proposal to end can still be cancelled", DashDiagnostics.endOf(Arrays.asList("End dash?",
                "Are you sure you want to end your dash?", "End dash", "Cancel")));
        assertNull("a menu action alone is not completion", DashDiagnostics.endOf(Arrays.asList("End dash")));
        assertNull("the dash's own screen", DashDiagnostics.endOf(Arrays.asList("Finding offers", "End dash")));
        assertNull("a pause", DashDiagnostics.endOf(Arrays.asList("Dash paused", "Resume dash", "End dash")));
        assertNull("a delivery", DashDiagnostics.endOf(Arrays.asList("Deliver by 9:45 PM", "Complete delivery steps",
                "End dash")));
        assertNull(DashDiagnostics.endOf(Arrays.asList("Deliver to [name]", "Call", "Message")));
    }

    @Test
    public void reportsAreSplitIntoPartsThatFitAnIssue() {
        StringBuilder report = new StringBuilder();
        for (int i = 0; i < 3000; i++) report.append("line ").append(i).append(' ').append("x".repeat(40)).append('\n');
        List<String> parts = DashDiagnostics.parts("Intro.\n", report.toString(), 60_000);
        assertEquals(3, parts.size());
        StringBuilder rebuilt = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            assertTrue(part.length() <= 60_000);
            String head = i == 0 ? "Intro.\nPart 1 of 3; the rest follow as comments.\n\n"
                    : "Part " + (i + 1) + " of 3 (continued)\n\n";
            assertTrue(part, part.startsWith(head));
            String piece = part.substring(head.length());
            if (i < parts.size() - 1) assertTrue("cut at a line's end", piece.endsWith("\n"));
            rebuilt.append(piece);
        }
        assertEquals(report.toString(), rebuilt.toString());
        assertEquals(Collections.singletonList("Intro.\n\nshort"), DashDiagnostics.parts("Intro.\n", "short", 60_000));
    }

    // ---- Off ----

    @Test
    public void nothingIsUploadedWhileTheSwitchIsOff() {
        on("/issues", 201, "{\"number\": 9}");
        connectedToGitHub();
        ReportOutbox.useGitHub(app, true);

        dashUntilItEnds();
        long[] now = {System.currentTimeMillis() + DashDiagnostics.QUIET_MS * 2};
        DashDiagnostics.clock = () -> now[0];
        DashDiagnostics.check(app);
        settle();

        assertEquals(0, ReportOutbox.queued(app));
        assertFalse(ReportOutbox.drain(app));
        assertTrue(requests.isEmpty());
        JobScheduler jobs = app.getSystemService(JobScheduler.class);
        assertNull(jobs.getPendingJob(DashDiagnostics.JOB_ID));
    }
}

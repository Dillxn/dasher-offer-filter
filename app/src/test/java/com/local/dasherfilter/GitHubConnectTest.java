package com.local.dasherfilter;

import android.app.Application;
import android.content.Context;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Signing in to GitHub with the device flow, and keeping the token fresh, against a local fake GitHub. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class GitHubConnectTest {
    private static final String DEVICE_CODE = GitHubConnect.deviceCodeEndpoint;
    private static final String TOKEN = GitHubConnect.tokenEndpoint;
    private static final String USER = GitHubConnect.userEndpoint;
    private static final String CLIENT_ID = GitHubConnect.clientId;

    /** One request the fake GitHub received. */
    private static final class Request {
        final String path;
        final Map<String, String> headers;
        final String body;

        Request(String path, Map<String, String> headers, String body) {
            this.path = path;
            this.headers = headers;
            this.body = body;
        }
    }

    /** What the fake GitHub does for one request: runs {@code before}, then answers. */
    private static final class Answer {
        final int status;
        final String body;
        final Runnable before;

        Answer(int status, String body, Runnable before) {
            this.status = status;
            this.body = body;
            this.before = before;
        }
    }

    private Application app;
    private ServerSocket server;
    private final Map<String, Deque<Answer>> answers = new HashMap<>();
    private final List<Request> requests = new ArrayList<>();

    @Before
    public void setup() throws IOException {
        app = RuntimeEnvironment.getApplication();
        GitHubConnect.clientId = "Iv1.test";
        GitHubConnect.disconnect(app);
        server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread thread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    answer(socket);
                } catch (IOException closedOrMalformed) {
                    // The next accept() fails once the server is closed, which ends the loop.
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        String base = "http://127.0.0.1:" + server.getLocalPort();
        GitHubConnect.deviceCodeEndpoint = base + "/login/device/code";
        GitHubConnect.tokenEndpoint = base + "/login/oauth/access_token";
        GitHubConnect.userEndpoint = base + "/user";
    }

    @After
    public void stop() throws IOException {
        GitHubConnect.deviceCodeEndpoint = DEVICE_CODE;
        GitHubConnect.tokenEndpoint = TOKEN;
        GitHubConnect.userEndpoint = USER;
        GitHubConnect.disconnect(app);
        GitHubConnect.clientId = CLIENT_ID;
        server.close();
    }

    private void on(String path, int status, String body) {
        on(path, status, body, null);
    }

    private void on(String path, int status, String body, Runnable before) {
        synchronized (answers) {
            answers.computeIfAbsent(path, unused -> new ArrayDeque<>()).add(new Answer(status, body, before));
        }
    }

    private void answer(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        String[] requestLine = readLine(in).split(" ");
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
        String path = requestLine[1];
        Answer answer;
        synchronized (answers) {
            requests.add(new Request(path, headers, new String(body, StandardCharsets.UTF_8)));
            Deque<Answer> queue = answers.get(path);
            answer = queue == null || queue.isEmpty() ? new Answer(404, "{}", null) : queue.poll();
        }
        if (answer.before != null) answer.before.run();
        byte[] response = answer.body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 " + answer.status + " Fake\r\nContent-Type: application/json\r\nContent-Length: "
                + response.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(response);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            if (c != '\r') line.append((char) c);
        }
        return line.toString();
    }

    private Request request(int index) {
        synchronized (answers) {
            return requests.get(index);
        }
    }

    private void waitingForCode() throws IOException {
        on("/login/device/code", 200,
                "{\"device_code\":\"device-1\",\"user_code\":\"WDJB-MJHT\",\"expires_in\":900,\"interval\":5}");
        GitHubConnect.requestCode(app);
    }

    private long interval() {
        return app.getSharedPreferences("github", Context.MODE_PRIVATE).getLong("interval_s", 0);
    }

    @Test
    public void aCodeIsAskedForWithOnlyTheClientIdAndShownUntilApproved() throws IOException {
        waitingForCode();
        assertEquals("client_id=Iv1.test", request(0).body);
        assertEquals(GitHubConnect.State.WAITING, GitHubConnect.state(app));
        assertEquals("WDJB-MJHT", GitHubConnect.userCode(app));
        assertEquals("On GitHub, enter this code to connect:", GitHubConnect.status(app));
        assertNull("no token while waiting", GitHubConnect.token(app));
    }

    @Test
    public void pollingWaitsAndSlowsDownWhenGitHubAsks() throws IOException {
        waitingForCode();
        on("/login/oauth/access_token", 200, "{\"error\":\"authorization_pending\"}");
        assertEquals(GitHubConnect.Poll.PENDING, GitHubConnect.poll(app));
        assertEquals(5, interval());
        on("/login/oauth/access_token", 200, "{\"error\":\"slow_down\",\"interval\":10}");
        assertEquals(GitHubConnect.Poll.PENDING, GitHubConnect.poll(app));
        assertEquals(10, interval());
        on("/login/oauth/access_token", 200, "{\"error\":\"slow_down\"}");
        GitHubConnect.poll(app);
        assertEquals("five more seconds each time GitHub says slow down", 15, interval());
        assertEquals(GitHubConnect.State.WAITING, GitHubConnect.state(app));
    }

    @Test
    public void approvalStoresATokenForThisRepositoryOnlyAndNamesTheAccount() throws Exception {
        waitingForCode();
        on("/login/oauth/access_token", 200, "{\"access_token\":\"ghu_first\",\"expires_in\":28800,"
                + "\"refresh_token\":\"ghr_first\",\"refresh_token_expires_in\":15897600,\"token_type\":\"bearer\"}");
        on("/user", 200, "{\"login\":\"Dillxn\",\"id\":1}");
        assertEquals(GitHubConnect.Poll.CONNECTED, GitHubConnect.poll(app));

        String form = request(1).body;
        assertTrue(form, form.contains("client_id=Iv1.test"));
        assertTrue(form, form.contains("device_code=device-1"));
        assertTrue(form, form.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"));
        assertTrue("the token is limited to this repository", form.contains("repository_id=1391329716"));
        assertTrue("no client secret exists or is sent", !form.contains("secret"));
        assertEquals("Bearer ghu_first", request(2).headers.get("authorization"));

        assertEquals(GitHubConnect.State.CONNECTED, GitHubConnect.state(app));
        assertNull("the code is forgotten once used", GitHubConnect.userCode(app));
        assertEquals("Connected to GitHub as Dillxn. Updates also come from your repository.",
                GitHubConnect.status(app));
        assertEquals("ghu_first", GitHubConnect.token(app));
        assertEquals("a fresh token is used without asking GitHub again", 3, requests.size());
    }

    @Test
    public void aTokenAboutToExpireIsRefreshedWithTheClientIdAlone() throws Exception {
        app.getSharedPreferences("github", Context.MODE_PRIVATE).edit()
                .putString("access_token", "ghu_old").putLong("access_expires_at", System.currentTimeMillis() + 60_000L)
                .putString("refresh_token", "ghr_old").putLong("refresh_expires_at", Long.MAX_VALUE).commit();
        on("/login/oauth/access_token", 200, "{\"access_token\":\"ghu_new\",\"expires_in\":28800,"
                + "\"refresh_token\":\"ghr_new\",\"refresh_token_expires_in\":15897600}");
        assertEquals("ghu_new", GitHubConnect.token(app));
        assertEquals("client_id=Iv1.test&grant_type=refresh_token&refresh_token=ghr_old", request(0).body);
        assertEquals("ghu_new", GitHubConnect.token(app));
        assertEquals(1, requests.size());
    }

    @Test
    public void aRefusedRefreshForgetsTheConnectionAndSaysSo() {
        app.getSharedPreferences("github", Context.MODE_PRIVATE).edit()
                .putString("access_token", "ghu_old").putLong("access_expires_at", System.currentTimeMillis() - 1)
                .putString("refresh_token", "ghr_old").putLong("refresh_expires_at", Long.MAX_VALUE).commit();
        on("/login/oauth/access_token", 200, "{\"error\":\"bad_refresh_token\"}");
        assertThrows(IOException.class, () -> GitHubConnect.token(app));
        assertEquals(GitHubConnect.State.OFF, GitHubConnect.state(app));
        assertEquals("The GitHub connection ran out. Tap Connect GitHub again.", GitHubConnect.status(app));
    }

    @Test
    public void aRefusedRefreshIsLoggedAndStopsReportsSentThroughTheConnection() {
        app.getSharedPreferences("github", Context.MODE_PRIVATE).edit()
                .putString("access_token", "ghu_old").putLong("access_expires_at", System.currentTimeMillis() - 1)
                .putString("refresh_token", "ghr_old").putLong("refresh_expires_at", Long.MAX_VALUE).commit();
        ReportOutbox.useGitHub(app, true);
        on("/login/oauth/access_token", 200, "{\"error\":\"bad_refresh_token\"}");
        assertThrows(IOException.class, () -> GitHubConnect.token(app));

        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("[github] connection ended: GitHub refused to renew it (bad_refresh_token); "
                + "updates come only from Render until Connect GitHub is used again"));
        assertTrue(log, !log.contains("ghr_old") && !log.contains("ghu_old"));
        // As with Disconnect: reports through the connection stop, and connecting again never turns them back on.
        assertTrue(!ReportOutbox.useGitHubChosen(app));
        String report = DiagnosticLog.report(app);
        assertTrue(report, report.contains("GitHub connection: off; last ended: "));
        assertTrue(report, report.contains("(GitHub refused to renew it (bad_refresh_token))"));
    }

    @Test
    public void connectingAgainAfterTheConnectionEndedByItselfNeverTurnsReportsOn() throws IOException {
        // Connected, with reports sent through the connection turned on...
        app.getSharedPreferences("github", Context.MODE_PRIVATE).edit()
                .putString("access_token", "ghu_old").putLong("access_expires_at", 0)
                .putString("refresh_token", "ghr_old").putLong("refresh_expires_at", Long.MAX_VALUE).commit();
        ReportOutbox.useGitHub(app, true);
        assertTrue(ReportOutbox.throughGitHub(app));
        // ...then its renewal runs out (GitHub's limit) before anything asks for the token.
        app.getSharedPreferences("github", Context.MODE_PRIVATE).edit()
                .putLong("refresh_expires_at", System.currentTimeMillis() - 1).commit();
        assertEquals(GitHubConnect.State.OFF, GitHubConnect.state(app));

        // The user taps Connect GitHub and approves the new code.
        waitingForCode();
        assertTrue("connecting again counts as the old connection ending", !ReportOutbox.useGitHubChosen(app));
        on("/login/oauth/access_token", 200, "{\"access_token\":\"ghu_new\",\"expires_in\":28800,"
                + "\"refresh_token\":\"ghr_new\",\"refresh_token_expires_in\":15897600}");
        on("/user", 200, "{\"login\":\"Dillxn\",\"id\":1}");
        assertEquals(GitHubConnect.Poll.CONNECTED, GitHubConnect.poll(app));

        assertEquals(GitHubConnect.State.CONNECTED, GitHubConnect.state(app));
        assertTrue("reports stay off until the user turns them on again", !ReportOutbox.throughGitHub(app));
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("[github] connection ended: its renewal ran out (GitHub's limit for a connection)"));
        assertTrue(log, !log.contains("ghr_old") && !log.contains("ghu_old"));

        // A working connection replaced by connecting again ends the same way.
        ReportOutbox.useGitHub(app, true);
        assertTrue(ReportOutbox.throughGitHub(app));
        on("/login/device/code", 200,
                "{\"device_code\":\"device-2\",\"user_code\":\"ABCD-EFGH\",\"expires_in\":900,\"interval\":5}");
        GitHubConnect.requestCode(app);
        assertTrue(!ReportOutbox.useGitHubChosen(app));
        assertTrue(DiagnosticLog.read(app).contains("[github] connection ended: replaced by connecting again"));
    }

    @Test
    public void disconnectingAConnectedPhoneIsLoggedOnce() {
        app.getSharedPreferences("github", Context.MODE_PRIVATE).edit().putString("access_token", "ghu_x").commit();
        GitHubConnect.disconnect(app);
        GitHubConnect.disconnect(app);
        String log = DiagnosticLog.read(app);
        assertEquals(log, 1, log.split("\\[github\\] connection ended: disconnected in Settings", -1).length - 1);
        assertTrue(log, !log.contains("ghu_x"));
    }

    @Test
    public void disconnectNeverWaitsForGitHubAndALateRenewalIsNotKept() throws Exception {
        app.getSharedPreferences("github", Context.MODE_PRIVATE).edit()
                .putString("access_token", "ghu_old").putLong("access_expires_at", System.currentTimeMillis() - 1)
                .putString("refresh_token", "ghr_old").putLong("refresh_expires_at", Long.MAX_VALUE).commit();
        boolean[] disconnectedAtOnce = new boolean[1];
        on("/login/oauth/access_token", 200, "{\"access_token\":\"ghu_late\",\"expires_in\":28800,"
                + "\"refresh_token\":\"ghr_late\"}", () -> {
                    // The user taps Disconnect while GitHub is answering: Settings must not freeze waiting for it.
                    Thread tap = new Thread(() -> GitHubConnect.disconnect(app));
                    tap.start();
                    try {
                        tap.join(2_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    disconnectedAtOnce[0] = !tap.isAlive();
                });
        assertNull("disconnected meanwhile: no token", GitHubConnect.token(app));
        assertTrue("Disconnect waited for GitHub's answer", disconnectedAtOnce[0]);
        assertEquals(GitHubConnect.State.OFF, GitHubConnect.state(app));
        assertNull("the late renewal is not kept",
                app.getSharedPreferences("github", Context.MODE_PRIVATE).getString("access_token", null));
    }

    @Test
    public void oneRenewalAtATimeAndARefusedOneLeavesANewerConnection() throws Exception {
        app.getSharedPreferences("github", Context.MODE_PRIVATE).edit()
                .putString("access_token", "ghu_old").putLong("access_expires_at", System.currentTimeMillis() - 1)
                .putString("refresh_token", "ghr_old").putLong("refresh_expires_at", Long.MAX_VALUE).commit();
        String[] second = new String[1];
        boolean[] renewSoonAtOnce = new boolean[1];
        Thread[] waiting = new Thread[1];
        on("/login/oauth/access_token", 200, "{\"access_token\":\"ghu_new\",\"expires_in\":28800,"
                + "\"refresh_token\":\"ghr_new\",\"refresh_token_expires_in\":15897600}", () -> {
                    // A second caller (the report job, say) arrives during the renewal: it waits, and asks nothing.
                    waiting[0] = new Thread(() -> {
                        try {
                            second[0] = GitHubConnect.token(app);
                        } catch (IOException unexpected) {
                            second[0] = "failed";
                        }
                    });
                    waiting[0].start();
                    Thread renew = new Thread(() -> GitHubConnect.renewSoon(app));
                    renew.start();
                    try {
                        renew.join(2_000);
                        Thread.sleep(200);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    renewSoonAtOnce[0] = !renew.isAlive();
                });
        assertEquals("ghu_new", GitHubConnect.token(app));
        waiting[0].join(5_000);
        assertEquals("the second caller uses the renewal the first stored", "ghu_new", second[0]);
        assertEquals("one renewal", 1, requests.size());
        assertTrue("nothing else waits for GitHub's answer", renewSoonAtOnce[0]);

        // A refused renewal while a newer connection was stored meanwhile leaves that connection alone.
        app.getSharedPreferences("github", Context.MODE_PRIVATE).edit()
                .putLong("access_expires_at", System.currentTimeMillis() - 1).commit();
        on("/login/oauth/access_token", 200, "{\"error\":\"bad_refresh_token\"}",
                () -> app.getSharedPreferences("github", Context.MODE_PRIVATE).edit()
                        .putString("access_token", "ghu_fresh").putLong("access_expires_at", 0)
                        .putString("refresh_token", "ghr_fresh").commit());
        assertEquals("ghu_fresh", GitHubConnect.token(app));
        assertEquals(GitHubConnect.State.CONNECTED, GitHubConnect.state(app));
    }

    @Test
    public void anUnreachableGitHubKeepsTheConnectionForLater() {
        app.getSharedPreferences("github", Context.MODE_PRIVATE).edit()
                .putString("access_token", "ghu_old").putLong("access_expires_at", System.currentTimeMillis() - 1)
                .putString("refresh_token", "ghr_old").putLong("refresh_expires_at", Long.MAX_VALUE).commit();
        on("/login/oauth/access_token", 503, "Service Unavailable");
        assertThrows(IOException.class, () -> GitHubConnect.token(app));
        assertEquals(GitHubConnect.State.CONNECTED, GitHubConnect.state(app));
    }

    @Test
    public void aDeniedOrDisabledSignInSaysWhatToDo() throws IOException {
        waitingForCode();
        on("/login/oauth/access_token", 200, "{\"error\":\"access_denied\"}");
        assertEquals(GitHubConnect.Poll.DENIED, GitHubConnect.poll(app));
        assertEquals(GitHubConnect.State.OFF, GitHubConnect.state(app));
        assertEquals("GitHub was not allowed to connect. Tap Connect GitHub to try again.", GitHubConnect.status(app));

        waitingForCode();
        on("/login/oauth/access_token", 200, "{\"error\":\"device_flow_disabled\"}");
        assertEquals(GitHubConnect.Poll.DENIED, GitHubConnect.poll(app));
        assertTrue(GitHubConnect.status(app).startsWith("Turn on Enable Device Flow"));

        waitingForCode();
        on("/login/oauth/access_token", 200, "{\"error\":\"expired_token\"}");
        assertEquals(GitHubConnect.Poll.EXPIRED, GitHubConnect.poll(app));
        assertEquals("The code expired. Tap Connect GitHub for a new one.", GitHubConnect.status(app));
    }

    @Test
    public void cancellingWhileGitHubAnswersStoresNoToken() throws IOException {
        waitingForCode();
        on("/login/oauth/access_token", 200, "{\"access_token\":\"ghu_late\"}", () -> GitHubConnect.disconnect(app));
        assertEquals(GitHubConnect.Poll.EXPIRED, GitHubConnect.poll(app));
        assertEquals(GitHubConnect.State.OFF, GitHubConnect.state(app));
        assertNull(app.getSharedPreferences("github", Context.MODE_PRIVATE).getString("access_token", null));
    }

    @Test
    public void aMalformedCodeAnswerIsAFailureNotAWait() {
        on("/login/device/code", 200, new JSONObject().toString());
        assertThrows(IOException.class, () -> GitHubConnect.requestCode(app));
        assertEquals(GitHubConnect.State.OFF, GitHubConnect.state(app));
    }

    @Test
    public void theRepositorysReleaseIsUsedOnlyWhenItIsNewer() throws Exception {
        Updater.Release render = new Updater.Release(UpdatePolicy.Channel.RENDER,
                new JSONObject().put("versionCode", 24), null);
        Updater.Release repo = new Updater.Release(UpdatePolicy.Channel.REPO,
                new JSONObject().put("versionCode", 25), "ghu_test");
        Updater.Release sameAsRender = new Updater.Release(UpdatePolicy.Channel.REPO,
                new JSONObject().put("versionCode", 24), "ghu_test");
        assertEquals(repo, Updater.newer(render, repo));
        assertEquals(repo, Updater.newer(repo, render));
        assertEquals("a tie stays with Render", render, Updater.newer(render, sameAsRender));
        assertEquals("either one alone is used", render, Updater.newer(render, null));
        assertEquals(repo, Updater.newer(null, repo));
        assertNull(Updater.newer(null, null));
    }
}

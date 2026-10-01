package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The phone's connection to GitHub, used only to read updates from the app's private repository. It signs in with
 * GitHub's device flow: the app asks GitHub for a short code, the user approves it at github.com/login/device, and
 * the app receives a token that can do only what the user's "Offer Filter Updates" GitHub App may do (read this
 * repository's files, and file issues if the app is given that), restricted to this one repository. There is no
 * client secret and no server: device-flow tokens refresh with the client ID alone. Tokens stay in the app's private
 * storage and go only to GitHub. Connecting never turns reports on; the user does that separately in Reports.
 */
final class GitHubConnect {
    /** The user's own GitHub App (public identifier, not a secret); empty hides the feature. */
    static final String CLIENT_ID = "Iv23liz8X14UB0s60G2B";
    /** Dillxn/dasher-offer-filter, so the token cannot reach any other repository. */
    static final long REPOSITORY_ID = 1391329716L;
    static final String VERIFICATION_URL = "https://github.com/login/device";

    /** Package-private so tests can stand in an app and a local server; production always uses the above. */
    static String clientId = CLIENT_ID;
    static String deviceCodeEndpoint = "https://github.com/login/device/code";
    static String tokenEndpoint = "https://github.com/login/oauth/access_token";
    static String userEndpoint = "https://api.github.com/user";
    /** Replaced in tests so polling does not wait in real time. */
    static Sleeper sleeper = Thread::sleep;

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    enum State { OFF, WAITING, CONNECTED }

    /** What one poll of a pending sign-in found. */
    enum Poll { PENDING, CONNECTED, EXPIRED, DENIED }

    private static final String DEVICE_CODE = "device_code";
    private static final String USER_CODE = "user_code";
    private static final String CODE_EXPIRES_AT = "code_expires_at";
    private static final String INTERVAL = "interval_s";
    private static final String ACCESS = "access_token";
    private static final String ACCESS_EXPIRES_AT = "access_expires_at";
    private static final String REFRESH = "refresh_token";
    private static final String REFRESH_EXPIRES_AT = "refresh_expires_at";
    private static final String LOGIN = "login";
    private static final String NOTE = "note";
    /** An access token this close to expiry is refreshed before use. */
    private static final long REFRESH_MARGIN_MS = 300_000L;
    private static final int MAX_RESPONSE_BYTES = 65_536;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean POLLING = new AtomicBoolean();
    /** Guards the stored connection; held only to read or write it, never across a call to GitHub. */
    private static final Object LOCK = new Object();
    /** One refresh at a time: a second caller waits, then uses what the first stored. */
    private static final Object REFRESHING = new Object();
    /** Why the connection last ended, and when (wall clock); kept apart, so connecting again does not erase it. */
    private static final String HISTORY = "github_history";
    private static final String ENDED_AT = "ended_at";
    private static final String ENDED_WHY = "ended_why";

    static boolean configured() {
        return !clientId.isEmpty();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("github", Context.MODE_PRIVATE);
    }

    static State state(Context context) {
        SharedPreferences prefs = prefs(context);
        long now = System.currentTimeMillis();
        if (prefs.contains(REFRESH) ? now < prefs.getLong(REFRESH_EXPIRES_AT, Long.MAX_VALUE) : prefs.contains(ACCESS)) {
            return State.CONNECTED;
        }
        if (prefs.contains(DEVICE_CODE) && now < prefs.getLong(CODE_EXPIRES_AT, 0)) return State.WAITING;
        return State.OFF;
    }

    /** The code to enter at github.com/login/device while waiting, else null. */
    static String userCode(Context context) {
        return state(context) == State.WAITING ? prefs(context).getString(USER_CODE, null) : null;
    }

    /** One line for Settings. */
    static String status(Context context) {
        SharedPreferences prefs = prefs(context);
        switch (state(context)) {
            case CONNECTED:
                String login = prefs.getString(LOGIN, "");
                return "Connected to GitHub" + (login.isEmpty() ? "" : " as " + login)
                        + ". Updates also come from your repository.";
            case WAITING:
                return "On GitHub, enter this code to connect:";
            default:
                String note = prefs.getString(NOTE, "");
                return note.isEmpty() ? "Not connected. Connect GitHub to get updates from your repository." : note;
        }
    }

    /**
     * Starts signing in on the worker thread: asks GitHub for a code, then waits for the user to approve it.
     *
     * @param codeAsked posted to the main thread once GitHub answered, whether or not it sent a code
     */
    static void connect(Context context, Runnable codeAsked) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        WORKER.execute(() -> {
            try {
                requestCode(app);
            } catch (IOException | RuntimeException error) {
                note(app, "Could not reach GitHub (" + error.getMessage() + "). Try again.");
                return;
            } finally {
                main.post(codeAsked);
            }
            pollUntilDone(app);
        });
    }

    /** Picks up waiting for approval again, for example after the app was closed while the user was on GitHub. */
    static void resume(Context context) {
        Context app = context.getApplicationContext();
        if (state(app) == State.WAITING && !POLLING.get()) WORKER.execute(() -> pollUntilDone(app));
    }

    /**
     * Makes the next {@link #token} ask GitHub for a fresh token first, when the connection can be refreshed: after
     * GitHub refused a report, so permissions approved since then are sure to be in the token used next.
     */
    static void renewSoon(Context context) {
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            if (prefs.getString(REFRESH, null) != null) prefs.edit().putLong(ACCESS_EXPIRES_AT, 1).commit();
        }
    }

    /** Forgets the connection on this phone. Updates then come from Render only, and reports sent through it stop. */
    static void disconnect(Context context) {
        State was;
        synchronized (LOCK) {
            was = state(context);
            prefs(context).edit().clear().commit();
        }
        ReportOutbox.connectionRemoved(context);
        if (was == State.CONNECTED) ended(context, "disconnected in Settings");
        else if (was == State.WAITING) DiagnosticLog.log(context, "github", "connecting cancelled in Settings");
    }

    /**
     * The connection ended: the reason is logged and kept (never a token, the account name or a code), so the next
     * shared report says why updates come only from Render, and reports sent through the connection stop.
     */
    private static void ended(Context context, String why) {
        context.getSharedPreferences(HISTORY, Context.MODE_PRIVATE).edit()
                .putLong(ENDED_AT, System.currentTimeMillis()).putString(ENDED_WHY, why).apply();
        DiagnosticLog.log(context, "github", "connection ended: " + why
                + "; updates come only from Render until Connect GitHub is used again");
    }

    /** For a shared report: the connection's state now, and when and why it last ended. Never a token or name. */
    static String reportLine(Context context) {
        if (!configured()) return "GitHub connection: not set up in this build";
        SharedPreferences history = context.getSharedPreferences(HISTORY, Context.MODE_PRIVATE);
        long at = history.getLong(ENDED_AT, 0);
        String last = at <= 0 ? "never recorded"
                : new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", java.util.Locale.US)
                        .format(new java.util.Date(at)) + " (" + history.getString(ENDED_WHY, "") + ")";
        return "GitHub connection: " + state(context).name().toLowerCase(java.util.Locale.US)
                + "; last ended: " + last;
    }

    /**
     * Asks GitHub for a device code and remembers it. A connection still stored (one that ended by itself and was not
     * noticed yet, or one still working) is forgotten as with Disconnect: reports sent through it stop.
     */
    static void requestCode(Context context) throws IOException {
        JSONObject answer = post(deviceCodeEndpoint, "client_id=" + encode(clientId));
        State was;
        try {
            long now = System.currentTimeMillis();
            String deviceCode = answer.getString("device_code");
            String userCode = answer.getString("user_code");
            synchronized (LOCK) {
                was = stored(context);
                prefs(context).edit().clear()
                        .putString(DEVICE_CODE, deviceCode)
                        .putString(USER_CODE, userCode)
                        .putLong(CODE_EXPIRES_AT, now + answer.optLong("expires_in", 900) * 1000L)
                        .putLong(INTERVAL, Math.max(5, answer.optLong("interval", 5)))
                        .commit();
            }
        } catch (JSONException malformed) {
            throw new IOException("GitHub sent no code", malformed);
        }
        forgotten(context, was, "replaced by connecting again");
    }

    /**
     * What a connection still stored is: {@link State#CONNECTED}, {@link State#OFF} for one that ended by itself (its
     * renewal ran out) but is still stored, or null when none is stored. Under {@link #LOCK}.
     */
    private static State stored(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.contains(ACCESS) && !prefs.contains(REFRESH)) return null;
        return state(context) == State.CONNECTED ? State.CONNECTED : State.OFF;
    }

    /**
     * A stored connection was just wiped: reports sent through it stop and unsent ones are discarded, as with
     * Disconnect, and why it ended is kept.
     *
     * @param was what {@link #stored} said before the wipe
     */
    private static void forgotten(Context context, State was, String whyWorking) {
        if (was == null) return;
        ReportOutbox.connectionRemoved(context);
        ended(context, was == State.CONNECTED ? whyWorking : "its renewal ran out (GitHub's limit for a connection)");
    }

    private static void pollUntilDone(Context context) {
        if (!POLLING.compareAndSet(false, true)) return;
        try {
            while (state(context) == State.WAITING) {
                sleeper.sleep(prefs(context).getLong(INTERVAL, 5) * 1000L);
                Poll result;
                try {
                    result = poll(context);
                } catch (IOException offline) {
                    continue;
                }
                if (result != Poll.PENDING) return;
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } finally {
            POLLING.set(false);
        }
    }

    /** Asks GitHub once whether the user approved the code; stores the token when they did. */
    static Poll poll(Context context) throws IOException {
        String deviceCode = prefs(context).getString(DEVICE_CODE, null);
        if (deviceCode == null) return Poll.EXPIRED;
        JSONObject answer = post(tokenEndpoint, "client_id=" + encode(clientId) + "&device_code=" + encode(deviceCode)
                + "&grant_type=" + encode("urn:ietf:params:oauth:grant-type:device_code")
                + "&repository_id=" + REPOSITORY_ID);
        String error = answer.optString("error", "");
        synchronized (LOCK) {
            // Cancelled, or a newer code asked for, while GitHub was answering: this answer is no longer wanted.
            if (!deviceCode.equals(prefs(context).getString(DEVICE_CODE, null))) return Poll.EXPIRED;
            if (error.isEmpty()) store(context, answer);
        }
        switch (error) {
            case "":
                // A new connection: reports go through it only once the user turns that on again, however the last
                // one ended.
                ReportOutbox.connectionRemoved(context);
                fetchLogin(context);
                return Poll.CONNECTED;
            case "authorization_pending":
                return Poll.PENDING;
            case "slow_down":
                prefs(context).edit().putLong(INTERVAL, Math.max(prefs(context).getLong(INTERVAL, 5) + 5,
                        answer.optLong("interval", 0))).commit();
                return Poll.PENDING;
            case "access_denied":
                note(context, "GitHub was not allowed to connect. Tap Connect GitHub to try again.");
                return Poll.DENIED;
            case "device_flow_disabled":
                note(context, "Turn on Enable Device Flow in your GitHub App's settings, then connect again.");
                return Poll.DENIED;
            default:
                note(context, "The code expired. Tap Connect GitHub for a new one.");
                return Poll.EXPIRED;
        }
    }

    /** What the stored connection holds now: a token to use, or the refresh token to renew it with. */
    private static final class Held {
        final boolean connected;
        final String access;
        final String refresh;
        final boolean fresh;
        final boolean refreshRanOut;

        Held(Context context) {
            SharedPreferences prefs = prefs(context);
            long now = System.currentTimeMillis();
            access = prefs.getString(ACCESS, null);
            refresh = prefs.getString(REFRESH, null);
            long expires = prefs.getLong(ACCESS_EXPIRES_AT, 0);
            fresh = access != null && (expires == 0 || now < expires - REFRESH_MARGIN_MS);
            refreshRanOut = refresh != null && now >= prefs.getLong(REFRESH_EXPIRES_AT, Long.MAX_VALUE);
            connected = state(context) == State.CONNECTED;
        }
    }

    /**
     * A usable access token, refreshed first when it is about to expire; null when not connected. Blocking: call it
     * off the main thread.
     *
     * <p>One refresh at a time: a caller that finds one under way waits for it, then reads the stored connection
     * again and uses the token it stored. The stored connection is locked only to read or write it, never while
     * GitHub answers, so Disconnect (on the main thread) never waits for GitHub. A refused refresh forgets the
     * connection only if it is still the one that refresh renewed: a disconnect, a new connection or another refresh
     * stored meanwhile is left as it is.
     *
     * @throws IOException when GitHub could not be reached to refresh it (the connection is kept), or refused the
     *     refresh (the connection is forgotten and must be made again)
     */
    static String token(Context context) throws IOException {
        synchronized (LOCK) {
            Held held = new Held(context);
            if (held.refreshRanOut) {
                ranOut(context);
                return null;
            }
            if (!held.connected) return null;
            if (held.fresh || held.refresh == null) return held.access;
        }
        synchronized (REFRESHING) {
            String refresh;
            synchronized (LOCK) {
                // After waiting: another refresh may have stored a fresh token, or the connection may be gone.
                Held held = new Held(context);
                if (!held.connected) return null;
                if (held.fresh || held.refresh == null) return held.access;
                refresh = held.refresh;
            }
            JSONObject answer = post(tokenEndpoint, "client_id=" + encode(clientId) + "&grant_type=refresh_token"
                    + "&refresh_token=" + encode(refresh));
            String error = answer.optString("error", "");
            synchronized (LOCK) {
                Held now = new Held(context);
                if (!refresh.equals(now.refresh)) {
                    // Disconnected, connected again or renewed meanwhile: that stands, whatever GitHub said here.
                    return now.connected ? now.access : null;
                }
                if (error.isEmpty() && answer.has("access_token")) {
                    store(context, answer);
                    return prefs(context).getString(ACCESS, null);
                }
                prefs(context).edit().clear()
                        .putString(NOTE, "The GitHub connection ran out. Tap Connect GitHub again.").commit();
            }
            ReportOutbox.connectionRemoved(context);
            ended(context, "GitHub refused to renew it (" + (error.isEmpty() ? "no token in its answer" : error) + ")");
            throw new IOException("GitHub connection expired");
        }
    }

    /** The refresh token's own time ran out (GitHub's limit): the connection is forgotten, and that is said once. */
    private static void ranOut(Context context) {
        prefs(context).edit().clear().putString(NOTE, "The GitHub connection ran out. Tap Connect GitHub again.")
                .commit();
        ReportOutbox.connectionRemoved(context);
        ended(context, "its renewal ran out (GitHub's limit for a connection)");
    }

    private static void store(Context context, JSONObject answer) throws IOException {
        try {
            long now = System.currentTimeMillis();
            SharedPreferences.Editor edit = prefs(context).edit()
                    .remove(DEVICE_CODE).remove(USER_CODE).remove(CODE_EXPIRES_AT).remove(NOTE)
                    .putString(ACCESS, answer.getString("access_token"));
            long expiresIn = answer.optLong("expires_in", 0);
            edit.putLong(ACCESS_EXPIRES_AT, expiresIn > 0 ? now + expiresIn * 1000L : 0);
            if (answer.has("refresh_token")) {
                edit.putString(REFRESH, answer.getString("refresh_token"));
                long refreshIn = answer.optLong("refresh_token_expires_in", 0);
                edit.putLong(REFRESH_EXPIRES_AT, refreshIn > 0 ? now + refreshIn * 1000L : Long.MAX_VALUE);
            } else {
                edit.remove(REFRESH).remove(REFRESH_EXPIRES_AT);
            }
            edit.commit();
        } catch (JSONException malformed) {
            throw new IOException("GitHub sent no token", malformed);
        }
    }

    /** The signed-in account's name, only to show whom the phone is connected as. */
    private static void fetchLogin(Context context) {
        String access = prefs(context).getString(ACCESS, null);
        if (access == null) return;
        try {
            HttpURLConnection connection = open(userEndpoint);
            try {
                connection.setRequestProperty("Authorization", "Bearer " + access);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                if (connection.getResponseCode() != 200) return;
                try (InputStream in = connection.getInputStream()) {
                    String login = new JSONObject(readBounded(in)).optString("login", "");
                    prefs(context).edit().putString(LOGIN, login).apply();
                }
            } finally {
                connection.disconnect();
            }
        } catch (IOException | JSONException unknown) {
            // The connection works without the name.
        }
    }

    private static void note(Context context, String note) {
        State was;
        synchronized (LOCK) {
            was = stored(context);
            prefs(context).edit().clear().putString(NOTE, note).commit();
        }
        forgotten(context, was, "forgotten when connecting again failed");
    }

    /** A form POST to GitHub's sign-in endpoints; GitHub answers JSON, with an "error" field when it declines. */
    private static JSONObject post(String endpoint, String form) throws IOException {
        byte[] body = form.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = open(endpoint);
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int status = connection.getResponseCode();
            InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) throw new IOException("GitHub HTTP " + status);
            try (InputStream in = stream) {
                JSONObject answer = new JSONObject(readBounded(in));
                if (status >= 400 && !answer.has("error")) throw new IOException("GitHub HTTP " + status);
                return answer;
            } catch (JSONException malformed) {
                throw new IOException("GitHub HTTP " + status, malformed);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String endpoint) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "OfferFilter-Updater");
        return connection;
    }

    private static String readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            out.write(buffer, 0, count);
            if (out.size() > MAX_RESPONSE_BYTES) throw new IOException("GitHub response too large");
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private GitHubConnect() {}
}

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
 * repository's files), restricted to this one repository. There is no client secret and no server: device-flow
 * tokens refresh with the client ID alone. Tokens stay in the app's private storage and go only to GitHub. This is
 * separate from the report token, so connecting never turns reports on.
 */
final class GitHubConnect {
    /** The user's own GitHub App (public identifier, not a secret); empty hides the feature. */
    static final String CLIENT_ID = "";
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
    private static final Object LOCK = new Object();

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

    /** Forgets the connection on this phone. Updates then come from Render only. */
    static void disconnect(Context context) {
        synchronized (LOCK) {
            prefs(context).edit().clear().commit();
        }
    }

    /** Asks GitHub for a device code and remembers it. */
    static void requestCode(Context context) throws IOException {
        JSONObject answer = post(deviceCodeEndpoint, "client_id=" + encode(clientId));
        try {
            long now = System.currentTimeMillis();
            synchronized (LOCK) {
                prefs(context).edit().clear()
                        .putString(DEVICE_CODE, answer.getString("device_code"))
                        .putString(USER_CODE, answer.getString("user_code"))
                        .putLong(CODE_EXPIRES_AT, now + answer.optLong("expires_in", 900) * 1000L)
                        .putLong(INTERVAL, Math.max(5, answer.optLong("interval", 5)))
                        .commit();
            }
        } catch (JSONException malformed) {
            throw new IOException("GitHub sent no code", malformed);
        }
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

    /**
     * A usable access token, refreshed first when it is about to expire; null when not connected. Blocking: call it
     * off the main thread.
     *
     * @throws IOException when GitHub could not be reached to refresh it (the connection is kept), or refused the
     *     refresh (the connection is forgotten and must be made again)
     */
    static String token(Context context) throws IOException {
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            if (state(context) != State.CONNECTED) return null;
            long now = System.currentTimeMillis();
            String access = prefs.getString(ACCESS, null);
            long expires = prefs.getLong(ACCESS_EXPIRES_AT, 0);
            if (access != null && (expires == 0 || now < expires - REFRESH_MARGIN_MS)) return access;
            String refresh = prefs.getString(REFRESH, null);
            if (refresh == null) return access;
            JSONObject answer = post(tokenEndpoint, "client_id=" + encode(clientId) + "&grant_type=refresh_token"
                    + "&refresh_token=" + encode(refresh));
            if (!answer.optString("error", "").isEmpty() || !answer.has("access_token")) {
                prefs.edit().clear().putString(NOTE, "The GitHub connection ran out. Tap Connect GitHub again.")
                        .commit();
                throw new IOException("GitHub connection expired");
            }
            store(context, answer);
            return prefs.getString(ACCESS, null);
        }
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
        synchronized (LOCK) {
            prefs(context).edit().clear().putString(NOTE, note).commit();
        }
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

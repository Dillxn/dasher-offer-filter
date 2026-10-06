package com.local.dasherfilter;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONException;
import org.json.JSONObject;

/** Accountless feedback transport. No user token, account identifier, email or privileged backend key exists here. */
final class AnonymousFeedback {
    static final String ENDPOINT =
            "https://zlnfvqyyjsltmkmmpgzp.supabase.co/functions/v1/offer-filter-feedback";
    private static final int MAX_RESPONSE = 16 * 1024;
    private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor();

    static final class Result {
        final boolean ok;
        final String reference;
        final String message;
        Result(boolean ok, String reference, String message) {
            this.ok = ok;
            this.reference = reference == null ? "" : reference;
            this.message = message == null ? "" : message;
        }
    }

    interface Callback { void done(Result result); }

    static void send(Context context, String kind, String category, String message, boolean attachDiagnostics,
                     Callback callback) {
        Context app = context.getApplicationContext();
        String diagnostics = attachDiagnostics ? DiagnosticLog.report(app) : null;
        NETWORK.execute(() -> {
            Result result;
            try {
                result = post(payload(app, kind, category, message, diagnostics, attachDiagnostics));
            } catch (IOException | JSONException | RuntimeException failure) {
                result = new Result(false, "", "check your connection and try again");
            }
            Result delivered = result;
            new Handler(Looper.getMainLooper()).post(() -> callback.done(delivered));
        });
    }

    /** One offer's masked report (no learning steps, time to the minute) as diagnostics; the note as the message. */
    static void sendOffer(Context context, DecisionLog.Entry entry, String note, Callback callback) {
        Context app = context.getApplicationContext();
        String report = OfferReport.text(OfferReport.Problem.OTHER, Updater.version(app), versionCode(app),
                "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")", FilterStore.load(app),
                entry);
        String typed = note == null ? "" : note.trim();
        NETWORK.execute(() -> {
            Result result;
            try {
                JSONObject payload = payload(app, "problem", OfferReport.Problem.OTHER.category(), typed, report, true);
                if (typed.isEmpty()) payload.remove("message");
                result = post(payload);
            } catch (IOException | JSONException | RuntimeException failure) {
                result = new Result(false, "", "check your connection and try again");
            }
            Result delivered = result;
            new Handler(Looper.getMainLooper()).post(() -> callback.done(delivered));
        });
    }

    static JSONObject payload(Context context, String kind, String category, String message, String diagnostics,
                              boolean diagnosticsConsented) throws JSONException {
        JSONObject json = new JSONObject()
                .put("kind", kind)
                .put("category", category)
                .put("message", message)
                .put("appVersion", Updater.version(context))
                .put("appVersionCode", versionCode(context))
                .put("diagnosticsConsented", diagnosticsConsented);
        if (diagnostics != null) json.put("diagnostics", diagnostics);
        return json;
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (android.content.pm.PackageManager.NameNotFoundException impossible) {
            return 1;
        }
    }

    private static Result post(JSONObject payload) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("User-Agent", "OfferFilter/" + payload.optString("appVersion", "unknown"));
        connection.setDoOutput(true);
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = connection.getOutputStream()) { out.write(body); }
        int status = connection.getResponseCode();
        InputStream source = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        String response = source == null ? "" : read(source);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            return new Result(false, "", status == 429 ? "too many submissions; try again later"
                    : "service unavailable; try again later");
        }
        JSONObject json = response.isEmpty() ? new JSONObject() : new JSONObject(response);
        return new Result(json.optBoolean("ok", true), json.optString("reference", ""), "sent");
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = source.read(buffer)) != -1) {
                if (out.size() + count > MAX_RESPONSE) throw new IOException("response too large");
                out.write(buffer, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private AnonymousFeedback() {}
}

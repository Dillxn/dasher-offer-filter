package com.local.dasherfilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The one real {@link Feedback.Transport}: an HTTPS POST of JSON to {@link Feedback#ENDPOINT}, with no Origin header
 * (the service files it as from the app), no cookies, no redirects and no credential of any kind. A request that has
 * not finished within a minute is cut off, so a stalled upload cannot hold the sender for good.
 */
final class FeedbackHttp {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final long TOTAL_TIMEOUT_MS = 60_000;
    private static final int MAX_RESPONSE = 16 * 1024;
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(work -> {
        Thread thread = new Thread(work, "feedback-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    static Feedback.Response post(byte[] body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(Feedback.ENDPOINT).openConnection();
        // HttpURLConnection has no write timeout: past the deadline, the connection is closed under the upload.
        ScheduledFuture<?> deadline = WATCHDOG.schedule(connection::disconnect, TOTAL_TIMEOUT_MS,
                TimeUnit.MILLISECONDS);
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "OfferFilter-Feedback");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int status = connection.getResponseCode();
            InputStream source = status >= 200 && status < 300 ? connection.getInputStream()
                    : connection.getErrorStream();
            String response = source == null ? "" : read(source);
            return new Feedback.Response(status, response, retryAfter(connection.getHeaderField("Retry-After")));
        } finally {
            deadline.cancel(false);
            connection.disconnect();
        }
    }

    /** A Retry-After in seconds, as milliseconds (at most an hour); 0 when absent or a date. */
    static long retryAfter(String header) {
        if (header == null) return 0;
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds <= 0 ? 0 : Math.min(3_600L, seconds) * 1000L;
        } catch (NumberFormatException date) {
            return 0;
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = source.read(buffer)) != -1) {
                if (out.size() + count > MAX_RESPONSE) throw new IOException("response too large");
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private FeedbackHttp() {}
}

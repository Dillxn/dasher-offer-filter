package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Opt-in local diagnostics; raw capture expires, bounded I/O never blocks screen-decision callbacks. */
final class DiagnosticLog {
    private static final String PREFS = "offer_filter_diagnostics";
    private static final String FILE = "offer-filter-diagnostics.log";
    private static final long SESSION_MS = 1_800_000L;
    private static final int MAX_BYTES = 128 * 1024;
    private static final int KEEP_BYTES = 96 * 1024;
    private static final int MAX_MESSAGE_CHARS = 4096;
    /** One writer thread with a bounded queue; entries beyond the queue are dropped, never blocking callers. */
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.AbortPolicy());
    private static final Object LOCK = new Object();

    static boolean isEnabled(Context context) {
        SharedPreferences prefs = prefs(context);
        long remaining = prefs.getLong("until", 0) - System.currentTimeMillis();
        return prefs.getBoolean("enabled", false) && remaining > 0 && remaining <= SESSION_MS;
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit()
                .putBoolean("enabled", enabled)
                .putLong("until", enabled ? System.currentTimeMillis() + SESSION_MS : 0)
                .apply();
        if (enabled) log(context, "diagnostics", "local raw capture enabled for 30 minutes");
    }

    static void log(Context context, String source, String message) {
        if (!isEnabled(context)) return;
        Context app = context.getApplicationContext();
        String safe = message == null ? "" : message.replace('\r', ' ').replace('\n', ' ');
        if (safe.length() > MAX_MESSAGE_CHARS) safe = safe.substring(0, MAX_MESSAGE_CHARS) + " [truncated]";
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS XXX", Locale.US).format(new Date());
        byte[] line = (timestamp + " [" + source + "] " + safe + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            WRITER.execute(() -> append(app, line));
        } catch (RejectedExecutionException queueFull) {
            // Diagnostics are best effort; dropping an entry is preferable to delaying an offer decision.
        }
    }

    static String read(Context context) {
        flush();
        synchronized (LOCK) {
            File file = file(context);
            if (!file.exists()) return "No diagnostic entries yet.";
            try {
                return new String(readBytes(file), StandardCharsets.UTF_8);
            } catch (IOException error) {
                return "Could not read diagnostics: " + error.getClass().getSimpleName();
            }
        }
    }

    /** A shareable report. Saved rules and updater state are included even when raw capture is off. */
    static String report(Context context) {
        FilterSettings rules = FilterStore.load(context);
        SharedPreferences updates = Updater.prefs(context);
        return "Offer Filter " + Updater.version(context) + " diagnostics\n"
                + "Generated locally. Raw labels may include personal/location text; review before sharing.\n"
                + "Capture active: " + isEnabled(context) + " (30-minute session)\n"
                + "Accessibility connected: " + OfferFilterService.isConnected() + "\n"
                + "Notification access granted: " + OfferNotificationService.hasAccess(context) + "\n"
                + "Notification listener connected: " + OfferNotificationService.isConnected() + "\n"
                + "Selective alerts permitted: " + OfferAlerts.canNotify(context) + "\n"
                + "Auto-decline saved: " + rules.enabled
                + "; flat cents=" + rules.flatCents
                + "; per-mile cents=" + rules.perMileCents
                + "; per-minute cents=" + rules.perMinuteCents
                + "; extra-stop cents=" + rules.extraStopCents
                + "; max stops=" + rules.maxStops
                + "; rising offers=" + rules.risingOffers
                + "; last accepted cents=" + rules.lastAcceptedCents + "\n"
                + "Updater: " + Updater.status(context) + "\n"
                + "Latest advertised version: " + updates.getString("advertised", "none") + "\n"
                + "Last update attempt epoch ms: " + updates.getLong("attempt_at", 0) + "\n"
                + "Last successful feed check epoch ms: " + updates.getLong("checked_at", 0) + "\n\n"
                + read(context);
    }

    static void clear(Context context) {
        flush();
        synchronized (LOCK) {
            //noinspection ResultOfMethodCallIgnored
            file(context).delete();
        }
    }

    private static void append(Context context, byte[] bytes) {
        synchronized (LOCK) {
            File file = file(context);
            try {
                try (OutputStream out = new FileOutputStream(file, true)) {
                    out.write(bytes);
                }
                if (file.length() > MAX_BYTES) trimToRecentLines(file);
            } catch (IOException ignored) {
                // Best effort, as above.
            }
        }
    }

    /** Keeps roughly the newest {@link #KEEP_BYTES}, starting at a line boundary. */
    private static void trimToRecentLines(File file) throws IOException {
        byte[] data = readBytes(file);
        int start = Math.max(0, data.length - KEEP_BYTES);
        while (start < data.length && data[start] != '\n') start++;
        if (start < data.length) start++;
        try (OutputStream out = new FileOutputStream(file, false)) {
            out.write(data, start, data.length - start);
        }
    }

    private static byte[] readBytes(File file) throws IOException {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }

    /** Waits up to one second for queued entries so reads and clears observe them. */
    private static void flush() {
        try {
            WRITER.submit(() -> { }).get(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RejectedExecutionException ignored) {
            // Read whatever has been written so far.
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE);
    }

    private DiagnosticLog() {}
}

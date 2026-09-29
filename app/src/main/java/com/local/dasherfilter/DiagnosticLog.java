package com.local.dasherfilter;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.*;

/** Opt-in local diagnostics; raw capture expires, bounded I/O never blocks screen-decision callbacks. */
final class DiagnosticLog {
    private static final String PREFS = "offer_filter_diagnostics", FILE = "offer-filter-diagnostics.log";
    private static final long SESSION_MS = 1_800_000L;
    private static final int MAX_BYTES = 128 * 1024, KEEP_BYTES = 96 * 1024;
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.AbortPolicy());
    private static final Object LOCK = new Object();
    static boolean isEnabled(Context context) {
        android.content.SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long remaining = p.getLong("until", 0) - System.currentTimeMillis();
        return p.getBoolean("enabled", false) && remaining > 0 && remaining <= SESSION_MS;
    }
    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).putLong("until", enabled ? System.currentTimeMillis() + SESSION_MS : 0).apply();
        if (enabled) log(context, "diagnostics", "local raw capture enabled for 30 minutes");
    }
    static void log(Context context, String source, String message) {
        if (!isEnabled(context)) return;
        Context app = context.getApplicationContext(); String safe = message == null ? "" : message.replace('\r', ' ').replace('\n', ' ');
        if (safe.length() > 4096) safe = safe.substring(0, 4096) + " [truncated]";
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS XXX", Locale.US).format(new Date()) + " [" + source + "] " + safe + "\n";
        try { WRITER.execute(() -> append(app, line.getBytes(StandardCharsets.UTF_8))); } catch (RejectedExecutionException ignored) {}
    }
    private static void append(Context context, byte[] bytes) {
        synchronized (LOCK) {
            File file = file(context);
            try {
                try (OutputStream out = new FileOutputStream(file, true)) { out.write(bytes); }
                if (file.length() > MAX_BYTES) {
                    byte[] data = readBytes(file); int start = Math.max(0, data.length - KEEP_BYTES);
                    while (start < data.length && data[start] != '\n') start++; if (start < data.length) start++;
                    try (OutputStream out = new FileOutputStream(file, false)) { out.write(data, start, data.length - start); }
                }
            } catch (IOException ignored) {}
        }
    }
    static String read(Context context) {
        flush(); synchronized (LOCK) {
            if (!file(context).exists()) return "No diagnostic entries yet.";
            try { return new String(readBytes(file(context)), StandardCharsets.UTF_8); } catch (IOException e) { return "Could not read diagnostics: " + e.getClass().getSimpleName(); }
        }
    }
    private static byte[] readBytes(File file) throws IOException {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) { byte[] b = new byte[4096]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); return out.toByteArray(); }
    }
    static String report(Context context) {
        FilterSettings s = FilterStore.load(context);
        return "Offer Filter " + Updater.version(context) + " diagnostics\n" +
                "Generated locally. Raw labels may include personal/location text; review before sharing.\n" +
                "Capture active: " + isEnabled(context) + " (30-minute session)\n" +
                "Accessibility connected: " + OfferFilterService.isConnected() + "\n" +
                "Notification listener connected: " + OfferNotificationService.isConnected() + "\n" +
                "Selective alerts permitted: " + OfferAlerts.canNotify(context) + "\n" +
                "Auto-decline saved: " + s.enabled + "; flat cents=" + s.flatCents + "; per-mile cents=" + s.perMileCents +
                "; per-minute cents=" + s.perMinuteCents + "; extra-stop cents=" + s.extraStopCents + "; max stops=" + s.maxStops + "\n" +
                "Updater: " + Updater.status(context) + "\n" +
                "Last update attempt epoch ms: " + Updater.prefs(context).getLong("attempt_at", 0) + "\n" +
                "Last successful feed check epoch ms: " + Updater.prefs(context).getLong("checked_at", 0) + "\n\n" + read(context);
    }
    static void clear(Context context) { flush(); synchronized (LOCK) { file(context).delete(); } }
    private static void flush() {
        try { WRITER.submit(() -> {}).get(1, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } catch (ExecutionException | TimeoutException | RejectedExecutionException ignored) {}
    }
    private static File file(Context context) { return new File(context.getFilesDir(), FILE); }
    private DiagnosticLog() {}
}

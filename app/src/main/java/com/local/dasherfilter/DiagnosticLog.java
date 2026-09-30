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

/**
 * Local diagnostics, captured automatically: what the screen reader and the notification path saw and did, in two
 * small rolling logs on this phone, never older than a day. One holds offers, decisions and status; the other holds
 * Dasher's other screens (a shopping list, an item), so a few minutes of shopping can never push the offers out.
 * Each is kept only about as long as a report can carry, and leaves the phone only in a report the user shares.
 * Bounded I/O never blocks screen-decision callbacks.
 */
final class DiagnosticLog {
    private static final String PREFS = "offer_filter_diagnostics";
    private static final String FILE = "offer-filter-diagnostics.log";
    /** Dasher's other screens, kept apart. */
    private static final String SCREENS_FILE = "dasher-screens.log";
    /** Entries older than this are dropped. */
    static final long KEEP_MS = 24 * 3_600_000L;
    /** How often the writer drops old entries (each report drops them too). */
    private static final long PRUNE_EVERY_MS = 3_600_000L;
    private static final String TIME_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS XXX";
    private static volatile long prunedAt;
    /** Each log is trimmed to roughly what a report carries of it, with a little to spare. */
    private static final int MAX_BYTES = 20 * 1024;
    private static final int KEEP_BYTES = 16 * 1024;
    private static final int SCREENS_MAX_BYTES = 16 * 1024;
    private static final int SCREENS_KEEP_BYTES = 12 * 1024;
    private static final int MAX_MESSAGE_CHARS = 4096;
    /** Keeps a shared report comfortably inside Android's intent size limit (strings travel as UTF-16, twice). */
    private static final int MAX_REPORT_LOG_CHARS = 14_000;
    private static final int MAX_REPORT_SCREENS_CHARS = 10_000;
    private static final int MAX_REPORT_CHARS = 60_000;
    private static final int REPORT_DECISIONS = 100;
    /** Every report subject starts with this, so reports are easy to find in a mailbox. */
    static final String REPORT_SUBJECT = "Dash Buddy diagnostics";
    /** One writer thread with a bounded queue; entries beyond the queue are dropped, never blocking callers. */
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.AbortPolicy());
    private static final Object LOCK = new Object();

    /** On unless turned off. There is no switch for it; tests turn it off. */
    static boolean isEnabled(Context context) {
        return !prefs(context).getBoolean("off", false);
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("off", !enabled).remove("enabled").remove("until").apply();
    }

    static void log(Context context, String source, String message) {
        if (!isEnabled(context)) return;
        Context app = context.getApplicationContext();
        String safe = message == null ? "" : message.replace('\r', ' ').replace('\n', ' ');
        if (safe.length() > MAX_MESSAGE_CHARS) safe = safe.substring(0, MAX_MESSAGE_CHARS) + " [truncated]";
        String timestamp = new SimpleDateFormat(TIME_PATTERN, Locale.US).format(new Date());
        byte[] line = (timestamp + " [" + source + "] " + safe + "\n").getBytes(StandardCharsets.UTF_8);
        write(app, FILE, line);
    }

    /** One of Dasher's other screens, as read: kept in its own log. */
    static void logScreen(Context context, String message) {
        if (!isEnabled(context)) return;
        Context app = context.getApplicationContext();
        String safe = message == null ? "" : message.replace('\r', ' ').replace('\n', ' ');
        if (safe.length() > MAX_MESSAGE_CHARS) safe = safe.substring(0, MAX_MESSAGE_CHARS) + " [truncated]";
        String timestamp = new SimpleDateFormat(TIME_PATTERN, Locale.US).format(new Date());
        write(app, SCREENS_FILE, (timestamp + " [screen] " + safe + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void write(Context app, String name, byte[] line) {
        try {
            WRITER.execute(() -> append(app, name, line));
        } catch (RejectedExecutionException queueFull) {
            // Diagnostics are best effort; dropping an entry is preferable to delaying an offer decision.
        }
    }

    static String read(Context context) {
        return read(context, FILE, "No diagnostic entries yet.");
    }

    /** Dasher's other screens, oldest first. */
    static String readScreens(Context context) {
        return read(context, SCREENS_FILE, "No other screens yet.");
    }

    private static String read(Context context, String name, String none) {
        flush();
        synchronized (LOCK) {
            File file = new File(context.getFilesDir(), name);
            if (!file.exists()) return none;
            try {
                dropOlderThan(file, System.currentTimeMillis() - KEEP_MS);
            } catch (IOException ignored) {
                // Read what is there.
            }
            try {
                return new String(readBytes(file), StandardCharsets.UTF_8);
            } catch (IOException error) {
                return "Could not read diagnostics: " + error.getClass().getSimpleName();
            }
        }
    }

    /**
     * A shareable report: readiness, every saved rule, updater state and the decision history are always included;
     * raw screen text only when capture was on. Nothing is sent unless the user shares it.
     */
    static String report(Context context) {
        String report = fullReport(context);
        return report.length() <= MAX_REPORT_CHARS ? report
                : report.substring(0, MAX_REPORT_CHARS) + "\n[report truncated]";
    }

    private static String fullReport(Context context) {
        FilterSettings rules = FilterStore.load(context);
        SharedPreferences updates = Updater.prefs(context);
        String log = newest(read(context), MAX_REPORT_LOG_CHARS);
        String screens = newest(readScreens(context), MAX_REPORT_SCREENS_CHARS);
        return REPORT_SUBJECT + " — Dash Buddy " + Updater.version(context) + "\n"
                + "Generated " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", Locale.US).format(new Date())
                + ". Raw labels may include personal/location text; review before sharing.\n\n"
                + "== Readiness\n"
                + "Accessibility connected: " + OfferFilterService.isConnected() + "\n"
                + "Notification access granted: " + OfferNotificationService.hasAccess(context) + "\n"
                + "Notification listener connected: " + OfferNotificationService.isConnected() + "\n"
                + "Selective alerts permitted: " + OfferAlerts.canNotify(context) + "\n"
                + "Screen text capture: " + (isEnabled(context) ? "automatic" : "off")
                + " (kept on this phone: about what one report carries, never older than 24 hours)\n"
                + "Last status: " + FilterStore.lastStatus(context).replace('\n', ' ') + "\n"
                + AreaMap.summary(context) + "\n\n"
                + "== Rules\n"
                + "Auto-decline saved: " + rules.enabled
                + "; flat cents=" + rules.flatCents
                + "; per-mile cents=" + rules.perMileCents
                + "; per-minute cents=" + rules.perMinuteCents
                + "; extra-stop cents=" + rules.extraStopCents
                + "; max stops=" + rules.maxStops
                + "; rising offers=" + rules.risingOffers
                + "; highest accepted cents=" + rules.lastAcceptedCents
                + "; best accepted=" + (rules.best.isEmpty() ? "none" : rules.best.summary())
                + "; learned from manual declines=" + (rules.declined.isEmpty() ? "none" : rules.declined.summary())
                + "\n"
                + "In words: " + rules.describe() + "\n\n"
                + "== Decision history (newest first)\n"
                + DecisionLog.report(context, REPORT_DECISIONS) + "\n"
                + "== Updater\n"
                + "Status: " + Updater.status(context) + "\n"
                + "Latest advertised version: " + updates.getString("advertised", "none") + "\n"
                + "Last update attempt epoch ms: " + updates.getLong("attempt_at", 0) + "\n"
                + "Last successful feed check epoch ms: " + updates.getLong("checked_at", 0) + "\n\n"
                + "== Raw diagnostic log\n"
                + log + "\n\n"
                + "== Dasher's other screens (newest)\n"
                + screens;
    }

    /** The newest {@code chars} of a log, starting at a whole line. */
    static String newest(String log, int chars) {
        if (log.length() <= chars) return log;
        String tail = log.substring(log.length() - chars);
        int line = tail.indexOf('\n');
        return "[older entries omitted]\n" + (line >= 0 ? tail.substring(line + 1) : tail);
    }

    /** "Dash Buddy diagnostics 0.4.7 2026-09-29 21:45". */
    static String reportSubject(Context context) {
        return REPORT_SUBJECT + " " + Updater.version(context) + " "
                + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
    }

    static void clear(Context context) {
        flush();
        synchronized (LOCK) {
            //noinspection ResultOfMethodCallIgnored
            file(context).delete();
            //noinspection ResultOfMethodCallIgnored
            new File(context.getFilesDir(), SCREENS_FILE).delete();
        }
    }

    private static void append(Context context, String name, byte[] bytes) {
        synchronized (LOCK) {
            File file = new File(context.getFilesDir(), name);
            boolean screens = SCREENS_FILE.equals(name);
            try {
                try (OutputStream out = new FileOutputStream(file, true)) {
                    out.write(bytes);
                }
                if (file.length() > (screens ? SCREENS_MAX_BYTES : MAX_BYTES)) {
                    trimToRecentLines(file, screens ? SCREENS_KEEP_BYTES : KEEP_BYTES);
                }
                long now = System.currentTimeMillis();
                if (now - prunedAt > PRUNE_EVERY_MS) {
                    prunedAt = now;
                    dropOlderThan(file, now - KEEP_MS);
                }
            } catch (IOException ignored) {
                // Best effort, as above.
            }
        }
    }

    /** Drops the entries written before {@code cutoff}; a line whose time cannot be read is kept. */
    private static void dropOlderThan(File file, long cutoff) throws IOException {
        byte[] data = readBytes(file);
        SimpleDateFormat time = new SimpleDateFormat(TIME_PATTERN, Locale.US);
        int length = time.format(new Date(0)).length();
        int start = 0;
        while (start < data.length) {
            int end = start;
            while (end < data.length && data[end] != '\n') end++;
            if (end - start < length) break;
            Date at;
            try {
                at = time.parse(new String(data, start, length, StandardCharsets.UTF_8));
            } catch (java.text.ParseException unreadable) {
                break;
            }
            if (at == null || at.getTime() >= cutoff) break;
            start = Math.min(data.length, end + 1);
        }
        if (start == 0) return;
        try (OutputStream out = new FileOutputStream(file, false)) {
            out.write(data, start, data.length - start);
        }
    }

    /** Keeps roughly the newest {@code keep} bytes, starting at a line boundary. */
    private static void trimToRecentLines(File file, int keep) throws IOException {
        byte[] data = readBytes(file);
        int start = Math.max(0, data.length - keep);
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

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
import java.util.function.Supplier;

/**
 * Local diagnostics, captured automatically: what the screen reader and the notification path saw and did, in two
 * small rolling logs on this phone, never older than a day. One holds offers, decisions and status; the other holds
 * Dasher's other screens (a shopping list, an item), so a few minutes of shopping can never push the offers out.
 * Each is kept only about as long as a report can carry, and leaves the phone only in a report the user shares or, with
 * "Share diagnostics after each dash" on, in the one {@link DashDiagnostics} files after a dash. Every line is masked
 * ({@link PersonalText}) before it is written: names, addresses, phone numbers, emails and a customer's own words never
 * reach either log. Masking and bounded I/O run on the writer thread, so neither blocks screen-decision callbacks.
 *
 * <p>Some lines are states, not events (what an automatic update check found, DoorDash's alert-channel settings):
 * {@link #logOnChange} writes one only when it differs from the copy kept, or that copy is a day old, and the latest
 * copy of each is in the shared report's "Latest states". States never hold screen text, notification values,
 * tokens, the GitHub account name or a sign-in code.
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
    /** What every report says about the screen text it carries. */
    static final String MASKED_NOTE = "Screen text is masked on the phone: names, street addresses, phone numbers, "
            + "emails and a customer's own instructions read [name], [address], [phone], [email] and [instructions]; "
            + "stores, offer figures and Dasher's own words stay.";
    /** Every report subject starts with this, so reports are easy to find in a mailbox. */
    static final String REPORT_SUBJECT = AppName.NAME + " diagnostics";
    /** One writer thread with a bounded queue; entries beyond the queue are dropped, never blocking callers. */
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.AbortPolicy());
    private static final Object LOCK = new Object();
    /** Kept states, by source and topic, and when each was last written (wall clock). */
    private static final String STATE = "state:";
    private static final String STATE_AT = "state_at:";
    private static final int MAX_STATES = 12;
    private static final int MAX_STATE_CHARS = 1_000;
    private static final int MAX_REPORT_STATES_CHARS = 4_000;
    /** States are written from the main thread (notifications) and the updater's: one at a time. */
    private static final Object STATES = new Object();

    /** On unless turned off. There is no switch for it; tests turn it off. */
    static boolean isEnabled(Context context) {
        return !prefs(context).getBoolean("off", false);
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("off", !enabled).remove("enabled").remove("until").apply();
    }

    static void log(Context context, String source, String message) {
        log(context, source, () -> message);
    }

    /**
     * A line built on the writer thread, so a caller on the screen reader's thread never waits for the labels it
     * holds to be written out and masked: hand over copies of anything that may change.
     */
    static void log(Context context, String source, Supplier<String> message) {
        if (!isEnabled(context)) return;
        long at = System.currentTimeMillis();
        write(context.getApplicationContext(), FILE, () -> line(at, source, message.get()));
    }

    /** One stored line: one physical line, masked, then cut to {@link #MAX_MESSAGE_CHARS}. */
    private static byte[] line(long at, String source, String message) {
        String safe = message == null ? "" : message.replace('\r', ' ').replace('\n', ' ');
        // Masked before it is cut, so a cut can never leave half an address unmasked.
        safe = PersonalText.maskLine(safe);
        if (safe.length() > MAX_MESSAGE_CHARS) safe = safe.substring(0, MAX_MESSAGE_CHARS) + " [truncated]";
        String timestamp = new SimpleDateFormat(TIME_PATTERN, Locale.US).format(new Date(at));
        return (timestamp + " [" + source + "] " + safe + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A state, not an event: logged (with {@code prefix} before it) only when it differs from the copy kept for this
     * source and topic, or that copy is older than a day. The copy is kept for the shared report's "Latest states".
     *
     * @return whether it was logged
     */
    static boolean logOnChange(Context context, String source, String topic, String message, String prefix) {
        if (!isEnabled(context)) return false;
        String state = clip(message);
        String key = source + "|" + topic;
        long now = System.currentTimeMillis();
        synchronized (STATES) {
            SharedPreferences prefs = prefs(context);
            long at = prefs.getLong(STATE_AT + key, 0);
            if (state.equals(prefs.getString(STATE + key, null)) && at <= now && now - at < KEEP_MS) return false;
            remember(prefs, key, state, now);
        }
        log(context, source, prefix + state);
        return true;
    }

    static boolean logOnChange(Context context, String source, String topic, String message) {
        return logOnChange(context, source, topic, message, "");
    }

    /** A state logged every time (a manual check, a failure), and kept as the latest copy all the same. */
    static void logAndRemember(Context context, String source, String topic, String message, String prefix) {
        if (!isEnabled(context)) return;
        String state = clip(message);
        synchronized (STATES) {
            remember(prefs(context), source + "|" + topic, state, System.currentTimeMillis());
        }
        log(context, source, prefix + state);
    }

    /** Forgets the copy kept, so the next {@link #logOnChange} of it logs whatever it says. */
    static void forget(Context context, String source, String topic) {
        synchronized (STATES) {
            SharedPreferences prefs = prefs(context);
            String key = source + "|" + topic;
            if (prefs.contains(STATE + key)) prefs.edit().remove(STATE + key).remove(STATE_AT + key).apply();
        }
    }

    private static String clip(String message) {
        String safe = message == null ? "" : message.replace('\r', ' ').replace('\n', ' ');
        return safe.length() > MAX_STATE_CHARS ? safe.substring(0, MAX_STATE_CHARS) + " [truncated]" : safe;
    }

    /** Keeps one state, dropping the oldest beyond {@link #MAX_STATES}. Under {@link #STATES}. */
    private static void remember(SharedPreferences prefs, String key, String state, long now) {
        SharedPreferences.Editor edit = prefs.edit().putString(STATE + key, state).putLong(STATE_AT + key, now);
        java.util.Map<String, ?> all = prefs.getAll();
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (String name : all.keySet()) {
            if (name.startsWith(STATE_AT) && !name.equals(STATE_AT + key)) keys.add(name.substring(STATE_AT.length()));
        }
        // The kept ones, oldest first; this one is the newest.
        java.util.Collections.sort(keys, (a, b) -> Long.compare(prefs.getLong(STATE_AT + a, 0),
                prefs.getLong(STATE_AT + b, 0)));
        for (int i = 0; i < keys.size() + 1 - MAX_STATES; i++) {
            edit.remove(STATE + keys.get(i)).remove(STATE_AT + keys.get(i));
        }
        edit.apply();
    }

    /** The kept states newer than a day, newest first, as report lines. */
    private static String states(Context context) {
        SharedPreferences prefs = prefs(context);
        java.util.Map<String, ?> all = prefs.getAll();
        java.util.List<String> keys = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();
        for (String name : all.keySet()) {
            if (!name.startsWith(STATE_AT)) continue;
            String key = name.substring(STATE_AT.length());
            long at = prefs.getLong(name, 0);
            if (at <= now && now - at < KEEP_MS && prefs.getString(STATE + key, null) != null) keys.add(key);
        }
        if (keys.isEmpty()) return "None in the last day.\n";
        java.util.Collections.sort(keys, (a, b) -> Long.compare(prefs.getLong(STATE_AT + b, 0),
                prefs.getLong(STATE_AT + a, 0)));
        SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", Locale.US);
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            int bar = key.indexOf('|');
            String line = time.format(new Date(prefs.getLong(STATE_AT + key, 0))) + " ["
                    + (bar < 0 ? key : key.substring(0, bar)) + "] " + prefs.getString(STATE + key, "") + "\n";
            if (out.length() + line.length() > MAX_REPORT_STATES_CHARS) {
                out.append("[older states omitted]\n");
                break;
            }
            out.append(line);
        }
        return out.toString();
    }

    /** One of Dasher's other screens, as read: kept in its own log. */
    static void logScreen(Context context, String message) {
        logScreen(context, () -> message);
    }

    /** As {@link #logScreen(Context, String)}, built (and masked) on the writer thread. */
    static void logScreen(Context context, Supplier<String> message) {
        if (!isEnabled(context)) return;
        long at = System.currentTimeMillis();
        write(context.getApplicationContext(), SCREENS_FILE, () -> line(at, "screen", message.get()));
    }

    private static void write(Context app, String name, Supplier<byte[]> line) {
        try {
            WRITER.execute(() -> {
                byte[] bytes;
                try {
                    bytes = line.get();
                } catch (RuntimeException | StackOverflowError unmaskable) {
                    // Never written unmasked, and never a crash: an escaping error here would take the screen reader.
                    bytes = line(System.currentTimeMillis(), "log", "a line could not be masked and was dropped: "
                            + unmaskable.getClass().getSimpleName());
                }
                append(app, name, bytes);
            });
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
     * A shareable report: readiness, every saved rule, updater state, the decision history and the captured screen
     * text, all masked ({@link PersonalText}). Nothing is sent unless the user shares it, or (with "Share diagnostics
     * after each dash" on) {@link DashDiagnostics} files it after a dash, whole and in parts.
     */
    static String report(Context context) {
        String report = fullReport(context);
        return report.length() <= MAX_REPORT_CHARS ? report
                : report.substring(0, MAX_REPORT_CHARS) + "\n[report truncated]";
    }

    /**
     * The whole report, never cut. The logs are masked again as they are read, so lines that a version before masking
     * wrote (kept up to a day) leave masked too; masked text masks to itself.
     */
    static String fullReport(Context context) {
        FilterSettings rules = FilterStore.load(context);
        SharedPreferences updates = Updater.prefs(context);
        String log = newest(PersonalText.maskLine(read(context)), MAX_REPORT_LOG_CHARS);
        String screens = newest(PersonalText.maskLine(readScreens(context)), MAX_REPORT_SCREENS_CHARS);
        return REPORT_SUBJECT + " — " + AppName.NAME + " " + Updater.version(context) + "\n"
                + "Generated " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", Locale.US).format(new Date())
                + ". " + MASKED_NOTE + " Review before sharing.\n\n"
                + "== Readiness\n"
                + "Accessibility connected: " + OfferFilterService.isConnected() + "\n"
                + "Notification access granted: " + OfferNotificationService.hasAccess(context) + "\n"
                + "Notification listener connected: " + OfferNotificationService.isConnected() + "\n"
                + "Selective alerts permitted: " + OfferAlerts.canNotify(context) + "\n"
                + "Screen text capture: " + (isEnabled(context) ? "automatic, masked" : "off")
                + " (kept on this phone: about what one report carries, never older than 24 hours)\n"
                + "Diagnostics after each dash: " + DashDiagnostics.reportLine(context) + "\n"
                + "Last status: " + PersonalText.maskLine(FilterStore.lastStatus(context).replace('\n', ' ')) + "\n"
                + AreaMap.summary(context) + "\n\n"
                + "== Rules\n"
                + "Auto-decline saved: " + rules.enabled
                + "; flat cents=" + rules.flatCents
                + "; per-mile cents=" + rules.perMileCents
                + "; per-minute cents=" + rules.perMinuteCents
                + "; per-stop cents=" + rules.perStopCents
                + "; max stops=" + rules.maxStops
                + "; rising offers=" + rules.risingOffers
                + "; highest accepted cents=" + rules.lastAcceptedCents
                + "; best accepted=" + (rules.best.isEmpty() ? "none" : rules.best.summary())
                + "; learned from manual declines=" + (rules.declined.isEmpty() ? "none" : rules.declined.summary())
                + "; score by area=" + rules.scoreByArea
                + learningTimes(context)
                + "\n"
                + "In words: " + rules.describe() + "\n\n"
                + "== Decision history (newest first)\n"
                + DecisionLog.report(context, REPORT_DECISIONS) + "\n"
                + "== Updater\n"
                + "Status: " + Updater.status(context) + "\n"
                + "Latest advertised version: " + updates.getString("advertised", "none")
                + (updates.contains("advertised_via") ? " (" + updates.getString("advertised_via", "") + ")" : "")
                + "\n"
                + "Last update attempt epoch ms: " + updates.getLong("attempt_at", 0) + "\n"
                + "Last successful feed check epoch ms: " + updates.getLong("checked_at", 0) + "\n"
                + Updater.cadenceSummary(context) + "\n"
                + GitHubConnect.reportLine(context) + "\n\n"
                + "== Latest states (each logged once when it changes, and at least daily)\n"
                + states(context) + "\n"
                + "== Raw diagnostic log\n"
                + log + "\n\n"
                + "== Dasher's other screens (newest)\n"
                + screens;
    }

    /**
     * When learning was on (auto-decline and the adaptive minimum both), and when the adaptive minimums were last
     * reset, so a report can tell whether an offer accepted then could have taught.
     */
    private static String learningTimes(Context context) {
        long[] times = FilterStore.learningTimes(context);
        SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        String[] shown = new String[times.length];
        for (int i = 0; i < times.length; i++) {
            shown[i] = times[i] > 0 ? time.format(new Date(times[i])) : "not recorded";
        }
        return "; learning (auto-decline and Adaptive minimum both on) since=" + shown[0]
                + "; learning last turned off=" + shown[1] + "; adaptive minimums last reset=" + shown[2];
    }

    /** The newest {@code chars} of a log, starting at a whole line. */
    static String newest(String log, int chars) {
        if (log.length() <= chars) return log;
        String tail = log.substring(log.length() - chars);
        int line = tail.indexOf('\n');
        return "[older entries omitted]\n" + (line >= 0 ? tail.substring(line + 1) : tail);
    }

    /** "Offer Filter diagnostics 0.4.7 2026-09-29 21:45". */
    static String reportSubject(Context context) {
        return REPORT_SUBJECT + " " + Updater.version(context) + " "
                + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
    }

    /** Removes both logs and every kept state (Clear history). */
    static void clear(Context context) {
        flush();
        synchronized (LOCK) {
            //noinspection ResultOfMethodCallIgnored
            file(context).delete();
            //noinspection ResultOfMethodCallIgnored
            new File(context.getFilesDir(), SCREENS_FILE).delete();
        }
        synchronized (STATES) {
            SharedPreferences prefs = prefs(context);
            SharedPreferences.Editor edit = prefs.edit();
            for (String name : prefs.getAll().keySet()) {
                if (name.startsWith(STATE) || name.startsWith(STATE_AT)) edit.remove(name);
            }
            edit.commit();
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

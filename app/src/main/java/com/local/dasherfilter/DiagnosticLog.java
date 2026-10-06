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
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Local diagnostics, captured automatically: what the screen reader and the notification path saw and did, in two
 * small rolling logs on this phone, never older than a day. One holds offers, decisions and status; the other holds
 * Dasher's other screens (a shopping list, an item), so a few minutes of shopping can never push the offers out.
 * Turn-by-turn navigation goes into the screens log at most once a minute, and never pushes another screen of the
 * last 30 minutes out of it or out of a report (the pickup leg after an Accept stays).
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
    /** The screens log's lines for Dasher's other screens, and for its turn-by-turn navigation. */
    private static final String SCREEN_SOURCE = "screen";
    private static final String NAVIGATION_SOURCE = "navigation";
    /** Other screens this recent are never pushed out of the screens log by navigation ({@link #fitScreens}). */
    static final long SCREENS_PROTECTED_MS = 30 * 60_000L;
    private static final int MAX_MESSAGE_CHARS = 4096;
    /** Keeps a shared report comfortably inside Android's intent size limit (strings travel as UTF-16, twice). */
    private static final int MAX_REPORT_LOG_CHARS = 14_000;
    private static final int MAX_REPORT_SCREENS_CHARS = 10_000;
    private static final int MAX_REPORT_CHARS = 60_000;
    private static final int REPORT_DECISIONS = 100;
    /** What every report says about the screen text it carries. */
    static final String MASKED_NOTE = "Screen text is masked on the phone: names, street addresses, phone numbers, "
            + "emails, a customer's own instructions, card numbers and codes, and navigation's streets read [name], "
            + "[address], [phone], [email], [instructions], [card] and [street]; stores, offer figures and Dasher's own "
            + "words stay. Payment, account and earnings screens are never kept.";
    /** A screen showing a payment, account or earnings marker leaves only this line, at most once a minute. */
    static final String NOT_KEPT = PersonalText.NOT_KEPT;
    static final long NOT_KEPT_EVERY_MS = 60_000L;
    /** When the last {@link #NOT_KEPT} line was written (the line's own time). Written on the writer thread. */
    private static volatile long notKeptAt = Long.MIN_VALUE;
    /**
     * Set once the one-time clean-up after the payment-page fix has run ({@link #cleanUpOnce}): an older version
     * kept Dasher's wallet page, card number and all, in the screens log, and a report carried it.
     */
    static final String CLEANED_UP = "cleaned_up_payment_screens";
    static final String CLEANED_UP_LINE = "cleared both diagnostic logs and unsent reports: older text could include "
            + "payment details";
    private static volatile boolean cleanedUp;
    /** Held through the clean-up, ahead of {@link #LOCK} (never the other way round). */
    private static final Object CLEANUP = new Object();
    /** Every report subject starts with this, so reports are easy to find in a mailbox. */
    static final String REPORT_SUBJECT = AppName.NAME + " diagnostics";
    /** One writer thread with a bounded queue; entries beyond the queue are dropped, never blocking callers. */
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.AbortPolicy());
    private static final Object LOCK = new Object();
    private static final AtomicLong droppedLines = new AtomicLong();
    private static final AtomicLong failedWrites = new AtomicLong();
    private static volatile long clearGeneration;
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
        write(context.getApplicationContext(), FILE, () -> kept(at, source, message.get(), false));
    }

    /**
     * What is written of a line: nothing more than {@link #NOT_KEPT} (at most once a minute, else nothing) when it is a
     * screen's that showed a payment, account or earnings screen ({@link #NOT_KEPT} itself, as the screen reader
     * hands over such a screen's labels, or, for the screens log, any marker in its raw text), and otherwise the line.
     * On the writer thread.
     *
     * @return the bytes to append, or null for none
     */
    private static byte[] kept(long at, String source, String message, boolean screen) {
        if (NOT_KEPT.equals(message) || ((screen || "screen".equals(source) || "click".equals(source))
                && PersonalText.accountText(message))) {
            if (notKeptAt != Long.MIN_VALUE && at >= notKeptAt && at - notKeptAt < NOT_KEPT_EVERY_MS) return null;
            notKeptAt = at;
            return line(at, SCREEN_SOURCE, NOT_KEPT);
        }
        return line(at, source, message);
    }

    /** One stored line: one physical line, masked, then cut to {@link #MAX_MESSAGE_CHARS}. */
    private static byte[] line(long at, String source, String message) {
        String safe = message == null ? "" : message.replace('\r', ' ').replace('\n', ' ');
        // Masked before it is cut, so a cut can never leave half an address unmasked.
        safe = PersonalText.maskLine(safe, NAVIGATION_SOURCE.equals(source));
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

    /**
     * One of Dasher's other screens, as read: kept in its own log. A screen showing a payment, account or earnings
     * marker anywhere in its words is not kept: one {@link #NOT_KEPT} line at most once a minute instead.
     */
    static void logScreen(Context context, String message) {
        logScreen(context, () -> message);
    }

    /** As {@link #logScreen(Context, String)}, built (and masked) on the writer thread. */
    static void logScreen(Context context, Supplier<String> message) {
        if (!isEnabled(context)) return;
        long at = System.currentTimeMillis();
        write(context.getApplicationContext(), SCREENS_FILE, () -> kept(at, SCREEN_SOURCE, message.get(), true));
    }

    /**
     * One of Dasher's turn-by-turn navigation screens, in the screens log as a "[navigation]" line: when the log is
     * full, navigation lines (and lines older than {@link #SCREENS_PROTECTED_MS}) go first, so navigation never
     * pushes out another screen of the last 30 minutes ({@link #fitScreens}). Built (and masked, its streets too) on
     * the writer thread; never kept with a payment, account or earnings marker, as {@link #logScreen}.
     */
    static void logNavigation(Context context, Supplier<String> message) {
        if (!isEnabled(context)) return;
        long at = System.currentTimeMillis();
        write(context.getApplicationContext(), SCREENS_FILE, () -> kept(at, NAVIGATION_SOURCE, message.get(), true));
    }

    /**
     * The screens log without a line of a payment, account or earnings screen (each one {@link #NOT_KEPT} line, with
     * its time): what a report carries of it, whatever wrote it.
     */
    static String withoutAccountScreens(String log) {
        if (!PersonalText.accountText(log)) return log;
        StringBuilder out = new StringBuilder(log.length());
        int start = 0;
        while (start < log.length()) {
            int end = log.indexOf('\n', start);
            end = end < 0 ? log.length() : end + 1;
            String line = log.substring(start, end);
            if (PersonalText.accountText(line)) {
                int source = line.indexOf(" [");
                out.append(source < 0 ? "" : line.substring(0, source)).append(" [").append(SCREEN_SOURCE)
                        .append("] ").append(NOT_KEPT).append('\n');
            } else {
                out.append(line);
            }
            start = end;
        }
        return out.toString();
    }

    /**
     * Fits the screens log into {@code budget} (UTF-8 bytes, or characters), dropping whole lines, oldest first: first
     * navigation lines and lines older than {@link #SCREENS_PROTECTED_MS} (or of no readable time), and only then, if
     * the rest alone is still too big, the other lines of the last 30 minutes. So navigation never pushes out another
     * screen of the last 30 minutes (the pickup leg after an Accept, say), however much of it there is.
     *
     * @return the lines kept, in order, each ending in a line break; {@code log} itself when it fits
     */
    static String fitScreens(String log, int budget, boolean bytes, long now) {
        if (size(log, bytes) <= budget) return log;
        List<String> lines = new ArrayList<>();
        int start = 0;
        while (start < log.length()) {
            int end = log.indexOf('\n', start);
            end = end < 0 ? log.length() : end + 1;
            lines.add(log.substring(start, end));
            start = end;
        }
        long total = size(log, bytes);
        boolean[] dropped = new boolean[lines.size()];
        SimpleDateFormat time = new SimpleDateFormat(TIME_PATTERN, Locale.US);
        for (int i = 0; i < lines.size() && total > budget; i++) {
            String line = lines.get(i);
            Date at = time.parse(line, new ParsePosition(0));
            boolean old = at == null || now - at.getTime() > SCREENS_PROTECTED_MS;
            if (old || navigationLine(line)) {
                dropped[i] = true;
                total -= size(line, bytes);
            }
        }
        for (int i = 0; i < lines.size() && total > budget; i++) {
            if (dropped[i]) continue;
            dropped[i] = true;
            total -= size(lines.get(i), bytes);
        }
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (dropped[i]) continue;
            String line = lines.get(i);
            kept.append(line);
            if (!line.endsWith("\n")) kept.append('\n');
        }
        return kept.toString();
    }

    /** Whether a stored line is one of {@link #logNavigation}'s: "[navigation]" right after its time. */
    static boolean navigationLine(String line) {
        int source = line.indexOf(" [");
        return source >= 0 && line.startsWith("[" + NAVIGATION_SOURCE + "]", source + 1);
    }

    /** Timing telemetry has a separate effective budget: it cannot evict decision/confirmation context. */
    static String fitLog(String log, int budget, boolean bytes) {
        if (size(log, bytes) <= budget) return log;
        List<String> lines = new ArrayList<>();
        int start = 0;
        while (start < log.length()) {
            int end = log.indexOf('\n', start);
            end = end < 0 ? log.length() : end + 1;
            lines.add(log.substring(start, end));
            start = end;
        }
        long total = size(log, bytes);
        boolean[] dropped = new boolean[lines.size()];
        for (int pass = 0; pass < 2 && total > budget; pass++) {
            for (int i = 0; i < lines.size() && total > budget; i++) {
                String line = lines.get(i);
                int source = line.indexOf(" [");
                boolean timing = source >= 0 && line.startsWith("[scan]", source + 1);
                if (dropped[i] || (pass == 0 && !timing)) continue;
                dropped[i] = true;
                total -= size(line, bytes);
            }
        }
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) if (!dropped[i]) kept.append(lines.get(i));
        return kept.toString();
    }

    private static long size(String text, boolean bytes) {
        return bytes ? text.getBytes(StandardCharsets.UTF_8).length : text.length();
    }

    private static void write(Context app, String name, Supplier<byte[]> line) {
        long generation = clearGeneration;
        try {
            WRITER.execute(() -> {
                if (generation != clearGeneration) return;
                byte[] bytes;
                try {
                    bytes = line.get();
                } catch (RuntimeException | StackOverflowError unmaskable) {
                    droppedLines.incrementAndGet();
                    // Never written unmasked, and never a crash: an escaping error here would take the screen reader.
                    bytes = line(System.currentTimeMillis(), "log", "a line could not be masked and was dropped: "
                            + unmaskable.getClass().getSimpleName());
                }
                // A screen not kept, and its note already written this minute.
                if (bytes != null) append(app, name, bytes, generation);
            });
        } catch (RejectedExecutionException queueFull) {
            // Diagnostics must never delay an offer; retain only a numeric loss count.
            droppedLines.incrementAndGet();
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
        if (!cleanUpOnce(context)) return "Diagnostics paused: privacy cleanup incomplete.";
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
        String log = fitLog(PersonalText.maskLine(withoutAccountScreens(read(context))), MAX_REPORT_LOG_CHARS, false);
        String screens = newestScreens(PersonalText.maskLine(withoutAccountScreens(readScreens(context))),
                System.currentTimeMillis());
        return REPORT_SUBJECT + " — " + AppName.NAME + " " + Updater.version(context) + "\n"
                + phone() + "\n"
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
                + lossSummary() + "; " + ReportOutbox.lossSummary(context) + "\n"
                + "Last status: " + PersonalText.maskLine(FilterStore.lastStatus(context).replace('\n', ' ')) + "\n"
                + AreaMap.summary(context) + "\n\n"
                + "== Rules\n"
                + "Current when this report was generated; not a reconstructed historical baseline.\n"
                + "Auto-decline saved: " + rules.enabled
                + "; flat cents=" + rules.flatCents
                + "; per-mile cents=" + rules.perMileCents
                + "; per-minute cents=" + rules.perMinuteCents
                + "; per-stop cents=" + rules.perStopCents
                + "; per-item cents=" + rules.perItemCents
                + "; max stops=" + rules.maxStops
                + "; hotspot proximity hundredths/mi=" + rules.hotspotProximityHundredths
                + "; minimum scale percent=" + rules.minimumScalePercent
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
                + Updater.cadenceSummary(context) + "\n\n"
                + "== Latest states (each logged once when it changes, and at least daily)\n"
                + states(context) + "\n"
                + "== Raw diagnostic log\n"
                + log + "\n\n"
                + "== Dasher's other screens (newest)\n"
                + screens;
    }

    /**
     * "Android 15 (API 35), Google": which Android and whose phone, since split screen and its windows differ by both.
     * In the shared report and the diagnostics after a dash only, never in an automatic problem report.
     */
    static String phone() {
        String maker = android.os.Build.MANUFACTURER == null ? "" : android.os.Build.MANUFACTURER.trim();
        return "Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + "), "
                + (maker.isEmpty() ? "unknown maker" : maker);
    }

    /**
     * When learning was on (auto-decline and the adaptive minimum both), and when the adaptive minimums were last
     * reset, so a report can tell whether an offer accepted then could have taught.
     */
    private static String learningTimes(Context context) {
        long[] times = FilterStore.learningTimes(context);
        SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS XXX", Locale.US);
        String[] shown = new String[times.length];
        for (int i = 0; i < times.length; i++) {
            shown[i] = times[i] > 0 ? time.format(new Date(times[i])) : "not recorded";
        }
        FilterSettings current = FilterStore.load(context);
        return "; learning now=" + (current.enabled && current.risingOffers ? "on" : "off")
                + "; learning (auto-decline and Adaptive minimum both on) since=" + shown[0]
                + "; learning last turned off=" + shown[1] + "; adaptive minimums last reset=" + shown[2];
    }

    /**
     * What a report carries of the screens log: at most {@link #MAX_REPORT_SCREENS_CHARS}, whole lines, fitted as the
     * log itself is ({@link #fitScreens}), so navigation never pushes another screen of the last 30 minutes out.
     */
    static String newestScreens(String log, long now) {
        if (log.length() <= MAX_REPORT_SCREENS_CHARS) return log;
        String omitted = "[older entries omitted]\n";
        return omitted + fitScreens(log, MAX_REPORT_SCREENS_CHARS - omitted.length(), false, now);
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

    /**
     * Remove all legacy captured text and queued reports once. Unknown partial payment pages cannot be reliably
     * remasked retrospectively. A failed deletion or durable preference write keeps reads/sends paused for retry.
     */
    static boolean cleanUpOnce(Context context) {
        if (cleanedUp) return true;
        Context app = context.getApplicationContext();
        synchronized (CLEANUP) {
            if (cleanedUp) return true;
            SharedPreferences prefs = prefs(app);
            if (prefs.getBoolean(CLEANED_UP, false)) {
                cleanedUp = true;
                return true;
            }
            boolean found;
            synchronized (LOCK) {
                File screens = new File(app.getFilesDir(), SCREENS_FILE);
                File offers = file(app);
                found = screens.exists() || offers.exists();
                if (!deleteLegacy(screens) || !deleteLegacy(offers)) return false;
            }
            found |= ReportOutbox.queued(app) > 0;
            if (!ReportOutbox.discardAllNow(app)) return false;
            if (!prefs.edit().putBoolean(CLEANED_UP, true).commit()) {
                // SharedPreferences updates memory even if its disk write failed; don't trust that in-process flag.
                prefs.edit().remove(CLEANED_UP).apply();
                return false;
            }
            cleanedUp = true;
            if (found && isEnabled(app)) {
                synchronized (LOCK) {
                    try (OutputStream out = new FileOutputStream(file(app), true)) {
                        out.write(line(System.currentTimeMillis(), "privacy", CLEANED_UP_LINE));
                    } catch (IOException ignored) {
                        // Only a generic cleanup receipt; no legacy data remains.
                    }
                }
            }
            return true;
        }
    }

    private static boolean deleteLegacy(File file) {
        return !file.exists() || file.delete() || !file.exists();
    }

    /** {@link #cleanUpOnce} on the writer thread, for a start that may write nothing (the app opening). */
    static void cleanUpSoon(Context context) {
        if (cleanedUp) return;
        Context app = context.getApplicationContext();
        try {
            WRITER.execute(() -> cleanUpOnce(app));
        } catch (RejectedExecutionException queueFull) {
            // The next line written cleans up first anyway.
        }
    }

    /** For tests: as a process start would, forget that the clean-up ran and when a screen was last not kept. */
    static void forgetCache() {
        cleanedUp = false;
        notKeptAt = Long.MIN_VALUE;
    }

    static String lossSummary() {
        return "Diagnostics since process start: dropped lines=" + droppedLines.get()
                + "; failed writes=" + failedWrites.get();
    }

    /** Removes both logs and every kept state (Clear history). */
    static void clear(Context context) {
        synchronized (LOCK) { clearGeneration++; }
        flush();
        droppedLines.set(0);
        failedWrites.set(0);
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

    private static void append(Context context, String name, byte[] bytes, long generation) {
        // Before anything new is written, what an older version kept is cleaned up (once; never under LOCK).
        if (!cleanUpOnce(context)) return;
        synchronized (LOCK) {
            if (generation != clearGeneration) return;
            File file = new File(context.getFilesDir(), name);
            boolean screens = SCREENS_FILE.equals(name);
            try {
                try (OutputStream out = new FileOutputStream(file, true)) {
                    out.write(bytes);
                }
                long now = System.currentTimeMillis();
                if (screens && file.length() > SCREENS_MAX_BYTES) {
                    String log = new String(readBytes(file), StandardCharsets.UTF_8);
                    try (OutputStream out = new FileOutputStream(file, false)) {
                        out.write(fitScreens(log, SCREENS_KEEP_BYTES, true, now).getBytes(StandardCharsets.UTF_8));
                    }
                } else if (!screens && file.length() > MAX_BYTES) {
                    String log = new String(readBytes(file), StandardCharsets.UTF_8);
                    try (OutputStream out = new FileOutputStream(file, false)) {
                        out.write(fitLog(log, KEEP_BYTES, true).getBytes(StandardCharsets.UTF_8));
                    }
                }
                if (now - prunedAt > PRUNE_EVERY_MS) {
                    prunedAt = now;
                    dropOlderThan(file, now - KEEP_MS);
                }
            } catch (IOException | RuntimeException ignored) {
                failedWrites.incrementAndGet();
                // Numeric accounting must not recursively enqueue another failing log write.
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

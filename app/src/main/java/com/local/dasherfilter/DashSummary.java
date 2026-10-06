package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Diagnostics after each dash: the user's opt-in, "Share anonymous diagnostics after each dash" (off by default, and
 * no update turns it on). With it on, the app counts this dash's problems as they happen, and once the dash ends
 * (Dasher showed it ended, as {@link Dashing#ended} knows it, or thirty minutes passed with nothing of the dash seen)
 * it queues one purpose-built summary of at most {@value Feedback#MAX_SUMMARY_CHARS} characters: the app's and
 * Android's version and the phone's maker; the settings' switches; counts by result, action and outcome; unreadable
 * offers, declines still showing by how far they got, decline-error recoveries, automatic accepts not sent and why,
 * screen-read and notification errors (an exception's type and top frame, digits masked); how the windows were laid
 * out and what Peek did (fixed categories); this dash's decisions only, timed from the dash's start; recent stops; and
 * masked log lines only around those problems. Never coordinates, place names or any install or device identifier,
 * and never more than one summary a dash or {@value #MAX_PER_DAY} a day. The old automatic problem reports (an
 * unreadable offer, a decline still showing, a read or notification error) live on here as counts and excerpts.
 *
 * <p>The hooks are cheap on the screen reader's thread: nothing is counted while the opt-in is off; what is is kept
 * on this class's own thread, so a dash that outlives its process loses little.
 */
final class DashSummary {
    static final int MAX_PER_DAY = 3;
    private static final int MAX_ANOMALIES = 6;
    private static final int MAX_EXCERPT_LINES = 25;
    private static final int MAX_EXCERPT_CHARS = 2_000;
    private static final long EXCERPT_BEFORE_MS = 30_000L;
    private static final long EXCERPT_AFTER_MS = 10_000L;
    /** A gap this long between reads is not attributed to the window layout last seen. */
    private static final long WINDOW_GAP_MS = 30_000L;
    private static final long WINDOW_SAVE_EVERY_MS = 5 * 60_000L;
    private static final long WINDOW_ACCOUNT_MS = 5_000L;
    private static final String TIME_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS XXX";
    private static final Pattern DIGITS = Pattern.compile("\\d");

    private static final String PREFS = "dash_summary";
    private static final String START = "start";
    private static final String COUNTS = "counts";
    private static final String ANOMALIES = "anomalies";
    private static final String SUMMARIZED = "summarized";
    private static final String DAY = "day";
    private static final String TODAY = "today";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "dash-summary");
        thread.setDaemon(true);
        return thread;
    });
    private static final Object WINDOW = new Object();
    /** Whether the opt-in is on, for the hooks; read again for a new application (a new process, or test). */
    private static volatile boolean collecting;
    private static volatile Object collectingFor;
    // Window layout time, in memory between saves (guarded by WINDOW).
    private static final Map<String, Long> windowMs = new TreeMap<>();
    private static String windowLayout;
    private static long windowSince;
    private static long windowSavedAt;

    /** Why a dash counts as over. */
    enum End {
        DASH_OVER("Dasher showed the dash ended"),
        QUIET("nothing of the dash seen for 30 minutes");

        final String why;

        End(String why) {
            this.why = why;
        }
    }

    /** Whether the hooks count: the opt-in on. Cheap: a field, after the first look. */
    static boolean collecting(Context context) {
        Context app = context.getApplicationContext();
        if (collectingFor != app) {
            collecting = Feedback.afterDashOn(app);
            collectingFor = app;
        }
        return collecting;
    }

    /** The user turned the opt-in on or off: off forgets what this dash counted. */
    static void optInChanged(Context context, boolean on) {
        Context app = context.getApplicationContext();
        collecting = on;
        collectingFor = app;
        if (!on) clear(app);
    }

    // ---- Hooks (any thread; cheap) ----

    /** A visible offer the rules could not judge (a reading gap): counted, with its read lines masked twice. */
    static void unreadable(Context context, List<String> labels) {
        if (!collecting(context)) return;
        List<String> copy = labels == null ? Collections.<String>emptyList() : new ArrayList<>(labels);
        long at = System.currentTimeMillis();
        record(context, model -> {
            increment(model, "unreadable", "offers");
            anomaly(model, at, "unreadable offer", OfferReport.redact(copy));
        });
    }

    /** A declined offer or its confirmation still showing seconds after the first Decline: counted by stage. */
    static void stuck(Context context, String stage) {
        if (!collecting(context)) return;
        long at = System.currentTimeMillis();
        record(context, model -> {
            increment(model, "stuck", stage);
            anomaly(model, at, "decline still showing (" + stage + ")", null);
        });
    }

    /** A Back after Dasher's decline error: requested or refused. */
    static void recovery(Context context, String outcome) {
        if (!collecting(context)) return;
        long at = System.currentTimeMillis();
        record(context, model -> {
            increment(model, "recovery", outcome);
            anomaly(model, at, "decline-error recovery (" + outcome + ")", null);
        });
    }

    /** An armed automatic Accept not sent, by its fixed reason category. */
    static void notSent(Context context, String reason) {
        if (!collecting(context)) return;
        record(context, model -> increment(model, "notSent", reason));
    }

    /** A screen-read or notification-handler error: its type and top frame, digits masked; never its message. */
    static void error(Context context, String where, Throwable error) {
        if (!collecting(context) || error == null) return;
        String signature = signature(error);
        long at = System.currentTimeMillis();
        record(context, model -> {
            increment(model, where + "Errors", signature);
            anomaly(model, at, where + " error " + signature, null);
        });
    }

    /** "IllegalStateException at OfferFilterService.read:###": an error's type and top frame, digits masked. */
    static String signature(Throwable error) {
        StackTraceElement[] stack = error.getStackTrace();
        String frame = "";
        if (stack.length > 0) {
            String type = stack[0].getClassName();
            frame = " at " + type.substring(type.lastIndexOf('.') + 1) + "." + stack[0].getMethodName() + ":"
                    + stack[0].getLineNumber();
        }
        return DIGITS.matcher(error.getClass().getSimpleName() + frame).replaceAll("#");
    }

    /** One of Peek's log lines, as a fixed category: what it did, never which app or what it read. */
    static void peek(Context context, String line) {
        if (!collecting(context) || line == null) return;
        String category = peekCategory(line);
        record(context, model -> increment(model, "peek", category));
    }

    /**
     * A Peek line's category: its words up to the first parenthesis, semicolon, timing or the per-line phone state
     * (" car="), digits masked, at most 60 characters. Peek's lines name kinds of apps, never an app; the one line that
     * names the offer's store ("opening Dasher for …") is only "opened Dasher".
     */
    static String peekCategory(String line) {
        if (line.startsWith("opening Dasher")) return "opened Dasher";
        int cut = line.length();
        for (String stop : new String[] {" car=", " (", ";", " after ", " within "}) {
            int at = line.indexOf(stop);
            if (at >= 0 && at < cut) cut = at;
        }
        String text = DIGITS.matcher(line.substring(0, cut)).replaceAll("#").trim();
        return text.length() > 60 ? text.substring(0, 60) : text;
    }

    /**
     * Where Dasher was at a read ("win=split/top/dasher/48"): time is counted per layout (split, full, hidden), so the
     * summary can say what share of the dash each took. Called on every read: a field check, then memory only.
     */
    static void window(Context context, String field) {
        if (!collecting(context)) return;
        String layout = layoutOf(field);
        long now = SystemClock.elapsedRealtime();
        synchronized (WINDOW) {
            // The same layout is accounted at its next change, or every few seconds: most reads cost a comparison.
            if (layout.equals(windowLayout) && now - windowSince >= 0 && now - windowSince < WINDOW_ACCOUNT_MS) return;
        }
        // Time outside a dash is nobody's.
        boolean dashing = Dashing.on(context);
        boolean save = false;
        synchronized (WINDOW) {
            if (windowLayout != null && dashing) {
                long spent = now - windowSince;
                if (spent > 0) windowMs.merge(windowLayout, Math.min(spent, WINDOW_GAP_MS), Long::sum);
            }
            windowLayout = dashing ? layout : null;
            windowSince = now;
            if (dashing && now - windowSavedAt >= WINDOW_SAVE_EVERY_MS) {
                windowSavedAt = now;
                save = true;
            }
        }
        if (save) record(context, model -> { });
    }

    /** "split", "full", "hidden" or "unknown", from a read's window field. */
    static String layoutOf(String field) {
        if (field == null || !field.startsWith("win=")) return "unknown";
        int slash = field.indexOf('/');
        String layout = field.substring(4, slash < 0 ? field.length() : slash);
        return layout.equals("split") || layout.equals("full") || layout.equals("hidden") ? layout : "unknown";
    }

    // ---- A dash's end ----

    /** Dasher showed the dash ended ({@link Dashing#ended}): its summary is built and queued, on this thread's own. */
    static void dashEnded(Context context, long start, long end) {
        if (!collecting(context) || start <= 0) return;
        Context app = context.getApplicationContext();
        WORKER.execute(() -> summarizeSafely(app, start, end, End.DASH_OVER));
    }

    /**
     * A new dash begins ({@link Dashing#seen}): the one before it, still open, went quiet without an end being seen,
     * and is summarized now (the existing thirty-minute quiet end).
     */
    static void dashStarting(Context context, long previousStart, long previousSeen) {
        if (!collecting(context) || previousStart <= 0) return;
        Context app = context.getApplicationContext();
        WORKER.execute(() -> summarizeSafely(app, previousStart, previousSeen, End.QUIET));
    }

    /** When the app opens or a service connects: a dash that went quiet meanwhile is summarized now. */
    static void checkSoon(Context context) {
        if (!collecting(context)) return;
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            long[] quiet = Dashing.quietlyEnded(app);
            if (quiet != null) summarizeSafely(app, quiet[0], quiet[1], End.QUIET);
        });
    }

    private static void summarizeSafely(Context app, long start, long end, End why) {
        try {
            summarize(app, start, end, why);
        } catch (RuntimeException failure) {
            // On a background thread an escaping exception would take the whole app, screen reader included.
            DiagnosticLog.log(app, "feedback", "could not build the dash's summary: "
                    + failure.getClass().getSimpleName());
        }
    }

    /** At most one summary a dash and {@value #MAX_PER_DAY} a day; only with the opt-in on and the notice accepted. */
    static void summarize(Context app, long start, long end, End why) {
        if (!Consent.accepted(app) || !Feedback.afterDashOn(app)) return;
        SharedPreferences prefs = prefs(app);
        JSONObject model;
        synchronized (DashSummary.class) {
            if (prefs.getLong(SUMMARIZED, 0) == start) return;
            long now = System.currentTimeMillis();
            long day = (now + TimeZone.getDefault().getOffset(now)) / 86_400_000L;
            int today = prefs.getLong(DAY, -1) == day ? prefs.getInt(TODAY, 0) : 0;
            // Marked first: whatever happens next, this dash never queues a second summary.
            SharedPreferences.Editor edit = prefs.edit().putLong(SUMMARIZED, start).putLong(DAY, day);
            if (today >= MAX_PER_DAY) {
                edit.commit();
                DiagnosticLog.log(app, "feedback", "dash summary not queued: today's " + MAX_PER_DAY + " were");
                return;
            }
            edit.putInt(TODAY, today + 1).commit();
            model = model(app, start);
            flushWindow(model);
            // The counts are this dash's; the next dash starts afresh.
            prefs.edit().remove(START).remove(COUNTS).remove(ANOMALIES).commit();
            synchronized (WINDOW) {
                windowMs.clear();
            }
        }
        String text = build(app, start, end, why, model);
        List<String> parts = Feedback.chunks(text, Feedback.MAX_SUMMARY_CHARS, Feedback.MAX_PART_BYTES);
        boolean queued = parts.size() == 1 && FeedbackOutbox.submitAutomatic(app, FeedbackOutbox.item(
                Feedback.newToken(), Feedback.Kind.DIAGNOSTICS, Feedback.Category.GENERAL.wire, true,
                FeedbackOutbox.TEXT, "", true, parts));
        if (queued) StopReports.acknowledge(app);
        DiagnosticLog.log(app, "feedback", "dash ended (" + why.why + "): summary "
                + (queued ? "queued to send" : "not queued (the outbox is full)"));
    }

    // ---- The summary ----

    /** The summary's text: masked, at most {@value Feedback#MAX_SUMMARY_CHARS} characters, oldest decisions cut first. */
    static String build(Context app, long start, long end, End why, JSONObject model) {
        FilterSettings rules = FilterStore.load(app);
        List<DecisionLog.Entry> decisions = new ArrayList<>();
        for (DecisionLog.Entry entry : DecisionLog.recent(app, DecisionLog.MAX_ENTRIES)) {
            if (entry.at >= start - 60_000L && entry.at <= end + 60_000L) decisions.add(entry);
        }
        Collections.reverse(decisions); // Oldest first.
        StringBuilder head = new StringBuilder()
                .append("Diagnostics after a dash, sent because Share anonymous diagnostics after each dash is on.\n")
                .append("App ").append(Updater.version(app)).append(" (").append(Feedback.versionCode(app))
                .append(") · ").append(DiagnosticLog.phone()).append('\n')
                .append("Dash: about ").append(duration(Math.max(0, end - start))).append("; ended: ").append(why.why)
                .append('\n')
                .append("Settings: ").append(settings(app, rules)).append('\n')
                .append("Readiness: screen reading ").append(OfferFilterService.isConnected() ? "connected" : "not connected")
                .append(" · background offers ").append(OfferNotificationService.isConnected() ? "connected" : "not connected")
                .append(" · alerts ").append(OfferAlerts.canNotify(app) ? "permitted" : "blocked").append('\n')
                .append(DiagnosticLog.MASKED_NOTE).append(" Times are from the dash's start.\n\n")
                .append("== Counts\n").append(counts(decisions, model)).append('\n');
        String stops = StopReports.section(app).trim();
        if (!stops.isEmpty()) head.append(stops).append("\n\n");
        String excerpts = excerpts(app, start, model);
        StringBuilder out = new StringBuilder();
        List<String> lines = decisionLines(decisions, start);
        int omitted = 0;
        while (true) {
            out.setLength(0);
            out.append(head);
            out.append("== This dash's decisions (oldest first)\n");
            if (omitted > 0) out.append("[").append(omitted).append(" earlier decisions omitted]\n");
            if (lines.isEmpty()) out.append("None recorded.\n");
            for (int i = omitted; i < lines.size(); i++) out.append(lines.get(i));
            if (!excerpts.isEmpty()) out.append('\n').append(excerpts);
            String masked = PersonalText.maskLine(out.toString());
            if (masked.length() <= Feedback.MAX_SUMMARY_CHARS || omitted >= lines.size()) {
                return masked.length() <= Feedback.MAX_SUMMARY_CHARS ? masked
                        : masked.substring(0, Feedback.MAX_SUMMARY_CHARS - 16).trim() + "\n[cut to fit]";
            }
            omitted = Math.min(lines.size(), omitted + Math.max(1, lines.size() / 10));
        }
    }

    /** "3 h 05 min", rounded to five minutes. */
    static String duration(long ms) {
        long minutes = Math.round(ms / 300_000.0) * 5;
        return minutes >= 60 ? (minutes / 60) + " h " + String.format(Locale.US, "%02d", minutes % 60) + " min"
                : minutes + " min";
    }

    private static String settings(Context app, FilterSettings rules) {
        List<String> set = new ArrayList<>();
        if (rules.flatCents > 0) set.add("pay");
        if (rules.perMileCents > 0) set.add("per mile");
        if (rules.perMinuteCents > 0) set.add("per minute");
        if (rules.perStopCents > 0) set.add("per stop");
        if (rules.perItemCents > 0) set.add("per item");
        if (rules.hotspotProximityHundredths > 0) set.add("hotspot");
        if (rules.maxStops > 0) set.add("max stops");
        return "auto-decline " + onOff(rules.enabled) + " · Peek " + onOff(FilterStore.peek(app))
                + " · quiet while declining " + onOff(FilterStore.silenceWhileDeclining(app))
                + " · auto-accept " + onOff(FilterStore.autoAcceptEnabled(app))
                + " · offer map " + onOff(AreaMap.enabled(app))
                + " · score by area " + onOff(rules.scoreByArea)
                + " · adaptive minimum " + onOff(rules.risingOffers)
                + " · minimums scale " + rules.minimumScalePercent + "%"
                + " · rules set: " + (set.isEmpty() ? "none" : String.join(", ", set));
    }

    private static String onOff(boolean on) {
        return on ? "on" : "off";
    }

    private static String counts(List<DecisionLog.Entry> decisions, JSONObject model) {
        Map<String, Integer> results = new TreeMap<>();
        Map<String, Integer> actions = new TreeMap<>();
        Map<String, Integer> outcomes = new TreeMap<>();
        for (DecisionLog.Entry entry : decisions) {
            results.merge(entry.result.name(), 1, Integer::sum);
            actions.merge(entry.action.label, 1, Integer::sum);
            outcomes.merge(DecisionLog.outcome(entry).word, 1, Integer::sum);
        }
        StringBuilder out = new StringBuilder()
                .append("Offers: ").append(decisions.size()).append(" (").append(join(results)).append(")\n")
                .append("Actions: ").append(join(actions)).append('\n')
                .append("Outcomes: ").append(join(outcomes)).append('\n')
                .append("Unreadable offers: ").append(join(group(model, "unreadable"))).append('\n')
                .append("Declines still showing, by stage: ").append(join(group(model, "stuck"))).append('\n')
                .append("Decline-error recoveries: ").append(join(group(model, "recovery"))).append('\n')
                .append("Automatic accepts not sent, by reason: ").append(join(group(model, "notSent"))).append('\n')
                .append("Screen-read errors: ").append(join(group(model, "scanErrors"))).append('\n')
                .append("Notification errors: ").append(join(group(model, "notificationErrors"))).append('\n')
                .append("Window layout (share of observed time): ").append(shares(model)).append('\n')
                .append("Peek: ").append(join(group(model, "peek"))).append('\n');
        return out.toString();
    }

    private static Map<String, Integer> group(JSONObject model, String name) {
        Map<String, Integer> out = new TreeMap<>();
        JSONObject counts = model.optJSONObject(COUNTS);
        JSONObject group = counts == null ? null : counts.optJSONObject(name);
        if (group == null) return out;
        for (Iterator<String> keys = group.keys(); keys.hasNext(); ) {
            String key = keys.next();
            out.put(key, group.optInt(key));
        }
        return out;
    }

    private static String join(Map<String, Integer> counts) {
        if (counts.isEmpty()) return "none";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> count : counts.entrySet()) parts.add(count.getKey() + " " + count.getValue());
        return String.join(", ", parts);
    }

    private static String shares(JSONObject model) {
        JSONObject counts = model.optJSONObject(COUNTS);
        JSONObject window = counts == null ? null : counts.optJSONObject("window");
        if (window == null) return "not observed";
        long total = 0;
        Map<String, Long> spent = new TreeMap<>();
        for (Iterator<String> keys = window.keys(); keys.hasNext(); ) {
            String key = keys.next();
            long ms = window.optLong(key);
            spent.put(key, ms);
            total += ms;
        }
        if (total <= 0) return "not observed";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Long> one : spent.entrySet()) {
            parts.add(one.getKey() + " " + Math.round(100.0 * one.getValue() / total) + "%");
        }
        return String.join(", ", parts) + " of " + duration(total);
    }

    /** Each decision as a line (and its read lines, masked twice, and learning lines), timed from the dash's start. */
    private static List<String> decisionLines(List<DecisionLog.Entry> decisions, long start) {
        List<String> out = new ArrayList<>();
        for (DecisionLog.Entry entry : decisions) {
            StringBuilder line = new StringBuilder()
                    .append(relative(entry.at - start))
                    .append(" | ").append(entry.source.name().toLowerCase(Locale.US))
                    .append(entry.peeked ? " (peeked)" : "").append(entry.addOn ? " add-on" : "")
                    .append(" | ").append(entry.result)
                    .append(" | pay ").append(entry.facts.payCents == null ? "?" : DecisionLog.money(entry.facts.payCents))
                    .append(" | needed ").append(entry.requiredCents == 0 ? "-" : DecisionLog.money(entry.requiredCents))
                    .append(entry.scorePercent >= 0 ? " | score " + entry.scorePercent + "%" : "")
                    .append(" | ").append(DecisionLog.facts(entry.facts))
                    .append(" | ").append(entry.reason)
                    .append(" | ").append(entry.action.label)
                    .append(" | outcome ").append(DecisionLog.outcome(entry).word)
                    .append(entry.autoDecline ? "" : " | auto-decline paused")
                    .append('\n');
            List<String> read = OfferReport.redact(entry.evidence);
            if (!read.isEmpty()) {
                line.append("    read: ").append(read.subList(0, Math.min(4, read.size()))).append('\n');
            }
            DecisionLog.Entry notice = entry.notification;
            if (notice != null) {
                line.append("    notification ").append(DecisionLog.noticeWhen(entry)).append(" | ")
                        .append(notice.result).append(" | ").append(notice.reason).append(" | ")
                        .append(notice.action.label).append('\n');
            }
            for (DecisionLog.Step step : entry.steps) {
                line.append("    learning ").append(relative(step.at - start)).append(' ').append(step.text())
                        .append('\n');
            }
            out.add(line.toString());
        }
        return out;
    }

    /** "+1:02:07" from the dash's start ("-0:00:05" for an offer just before its first sighting). */
    static String relative(long ms) {
        long seconds = Math.abs(ms) / 1000;
        return (ms < 0 ? "-" : "+") + seconds / 3600 + ":" + String.format(Locale.US, "%02d:%02d",
                (seconds / 60) % 60, seconds % 60);
    }

    /**
     * Masked log lines around each problem the dash counted (at most {@value #MAX_ANOMALIES}, the newest), timed from
     * the dash's start; "" when there was none. The log is already masked; it is masked again as it is read.
     */
    private static String excerpts(Context app, long start, JSONObject model) {
        JSONArray anomalies = model.optJSONArray(ANOMALIES);
        if (anomalies == null || anomalies.length() == 0) return "";
        String log = DiagnosticLog.read(app);
        List<long[]> times = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        SimpleDateFormat format = new SimpleDateFormat(TIME_PATTERN, Locale.US);
        int length = format.format(new Date(0)).length();
        for (String line : log.split("\n")) {
            Date at = line.length() >= length ? format.parse(line, new ParsePosition(0)) : null;
            if (at == null) continue;
            times.add(new long[] {at.getTime()});
            texts.add(line.substring(length));
        }
        StringBuilder out = new StringBuilder("== Log lines around those problems (masked)\n");
        for (int i = 0; i < anomalies.length(); i++) {
            JSONObject anomaly = anomalies.optJSONObject(i);
            if (anomaly == null) continue;
            long at = anomaly.optLong("t");
            out.append("-- ").append(relative(at - start)).append(' ').append(anomaly.optString("what")).append('\n');
            JSONArray read = anomaly.optJSONArray("read");
            if (read != null && read.length() > 0) {
                // As a plain list: JSON's quoting is what the masking reads as a quoted name, and would mangle it.
                List<String> shown = new ArrayList<>();
                for (int k = 0; k < read.length(); k++) shown.add(read.optString(k));
                out.append("   read: ").append(shown).append('\n');
            }
            StringBuilder excerpt = new StringBuilder();
            int lines = 0;
            for (int j = 0; j < times.size() && lines < MAX_EXCERPT_LINES; j++) {
                long when = times.get(j)[0];
                if (when < at - EXCERPT_BEFORE_MS || when > at + EXCERPT_AFTER_MS) continue;
                String line = relative(when - start) + texts.get(j) + "\n";
                if (excerpt.length() + line.length() > MAX_EXCERPT_CHARS) break;
                excerpt.append(line);
                lines++;
            }
            out.append(excerpt.length() == 0 ? "   (no log lines kept)\n" : excerpt);
        }
        return out.toString();
    }

    // ---- What a dash counted, kept on this class's thread ----

    private interface Change {
        void apply(JSONObject model) throws JSONException;
    }

    /** Applies a change to this dash's counts, off the caller's thread; dropped outside a dash. */
    private static void record(Context context, Change change) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                if (!Feedback.afterDashOn(app) || !Consent.accepted(app)) return;
                long start = Dashing.currentStart(app);
                if (start <= 0) return;
                synchronized (DashSummary.class) {
                    JSONObject model = model(app, start);
                    change.apply(model);
                    flushWindow(model);
                    save(app, model);
                }
            } catch (JSONException | RuntimeException failure) {
                // Only a count.
            }
        });
    }

    /** This dash's counts, or new ones when the counts kept were another dash's. */
    private static JSONObject model(Context app, long start) {
        SharedPreferences prefs = prefs(app);
        JSONObject model = new JSONObject();
        try {
            if (prefs.getLong(START, 0) == start) {
                model.put(COUNTS, new JSONObject(prefs.getString(COUNTS, "{}")));
                model.put(ANOMALIES, new JSONArray(prefs.getString(ANOMALIES, "[]")));
            } else {
                model.put(COUNTS, new JSONObject()).put(ANOMALIES, new JSONArray());
                synchronized (WINDOW) {
                    windowMs.clear();
                }
            }
            model.put(START, start);
        } catch (JSONException corrupt) {
            try {
                model = new JSONObject().put(COUNTS, new JSONObject()).put(ANOMALIES, new JSONArray()).put(START, start);
            } catch (JSONException impossible) {
                // Only strings and objects.
            }
        }
        return model;
    }

    private static void save(Context app, JSONObject model) {
        prefs(app).edit().putLong(START, model.optLong(START)).putString(COUNTS, model.optJSONObject(COUNTS).toString())
                .putString(ANOMALIES, model.optJSONArray(ANOMALIES).toString()).apply();
    }

    private static void increment(JSONObject model, String group, String key) throws JSONException {
        JSONObject counts = model.getJSONObject(COUNTS);
        JSONObject one = counts.optJSONObject(group);
        if (one == null) {
            one = new JSONObject();
            counts.put(group, one);
        }
        String safe = key == null || key.isEmpty() ? "unknown" : key.length() > 80 ? key.substring(0, 80) : key;
        one.put(safe, one.optInt(safe) + 1);
    }

    private static void anomaly(JSONObject model, long at, String what, List<String> read) throws JSONException {
        JSONArray anomalies = model.getJSONArray(ANOMALIES);
        JSONObject one = new JSONObject().put("t", at).put("what", what);
        if (read != null && !read.isEmpty()) one.put("read", new JSONArray(read.subList(0, Math.min(6, read.size()))));
        anomalies.put(one);
        // The newest are kept.
        while (anomalies.length() > MAX_ANOMALIES) anomalies.remove(0);
    }

    /** Folds the window time counted in memory into the model. Under the class lock. */
    private static void flushWindow(JSONObject model) {
        try {
            JSONObject counts = model.getJSONObject(COUNTS);
            JSONObject window = counts.optJSONObject("window");
            if (window == null) {
                window = new JSONObject();
                counts.put("window", window);
            }
            synchronized (WINDOW) {
                for (Map.Entry<String, Long> spent : windowMs.entrySet()) {
                    window.put(spent.getKey(), window.optLong(spent.getKey()) + spent.getValue());
                }
                windowMs.clear();
            }
        } catch (JSONException impossible) {
            // Only numbers.
        }
    }

    /** Clear history, or the opt-in turned off: this dash's counts go. */
    static void clear(Context context) {
        Context app = context.getApplicationContext();
        synchronized (DashSummary.class) {
            prefs(app).edit().remove(START).remove(COUNTS).remove(ANOMALIES).commit();
        }
        synchronized (WINDOW) {
            windowMs.clear();
            windowLayout = null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Waits for queued counting and summaries; for tests. */
    static void flush() {
        try {
            WORKER.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } catch (Exception interruptedOrTimedOut) {
            if (interruptedOrTimedOut instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    /** As a new process would: the opt-in is read again and nothing is counted in memory. For tests. */
    static void forgetCache() {
        collectingFor = null;
        synchronized (WINDOW) {
            windowMs.clear();
            windowLayout = null;
            windowSavedAt = 0;
        }
    }

    private DashSummary() {}
}

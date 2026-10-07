package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.text.Normalizer;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.regex.Matcher;
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
 * <p>Dasher's acceptance rate itself never leaves the phone in this summary ({@link #withoutAcceptanceRate}), however
 * it is written: Autopilot's plan and reading lines stay out of the excerpts, and wherever the rate could be (Dasher's
 * decline question, on its own screen line or among another read's labels, and any line, read label or step that
 * names the rate) every number is masked but money and clock times; the accepts a goal still needs are masked
 * everywhere. A summary waiting to be sent is filtered again right before it goes ({@link #remask}). What the summary
 * does carry are Autopilot's bar (in the settings and the decisions' "needed") and why it moved ("acceptance rate below
 * your goal" on a kept commit line): they can show that the rate was below the goal, never what it was. The rate goes
 * only in what the user sends: Share report, feedback with masked diagnostics attached, or Report this offer.
 *
 * <p>The hooks are cheap on the screen reader's thread: nothing is counted while the opt-in is off; what is (and every
 * preference or dash lookup) is handled on this class's own thread, so a dash that outlives its process loses little.
 * A count belongs to the dash under way when its hook fired, even when the dash ends before it is handled.
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
    /**
     * Words naming the acceptance rate, as Dasher's decline question shows them, in any case ("acceptance rate",
     * "Acceptance-rate"), or "AR" before a figure, as Autopilot's own words abbreviate it ("AR 55%", "AR ~31%", "ar
     * reading 55%", "AR: 9"); looked for in the NFKC form, as {@link AcceptanceRate} reads the question.
     */
    private static final Pattern RATE_WORDS = Pattern.compile("(?i)acceptance[\\s\\p{Pd}]*rate"
            + "|\\bar\\b[\\s:~]*(?:reading[\\s:~]+)?\\p{N}");
    /**
     * A percentage however it is written: digits of any script, with or without a decimal part, any spacing, then
     * "%", its full-width, small or Arabic form, or the word "percent", "per cent" or "pct", in any case ("9%", "9 %",
     * "９％", "9 percent", "9 Pct").
     */
    private static final String PERCENT = "(?<![\\p{N}.,])\\p{N}+(?:[.,]\\p{N}+)?[\\p{Z}\\s]*"
            + "(?:[%\\uFF05\\uFE6A\\u066A]|(?i:per[\\p{Z}\\s]?cent|pct)(?![\\p{L}\\p{N}]))";
    /** A spelled-out number ("nine", "Thirty"), which could spell the rate too. */
    private static final String NUMBER_WORD = "(?i:\\b(?:zero|one|two|three|four|five|six|seven|eight|nine|ten"
            + "|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty"
            + "|fifty|sixty|seventy|eighty|ninety|hundred)\\b)";
    /**
     * Where the acceptance rate could be, every number goes, however it is written ({@link #numbersMasked}). Group 1
     * is what cannot be a rate and stays: the window field ("win=split/top/dasher/48"), money ("$7.90", "+$1") and a
     * clock or countdown ("0:35", "9:45"). Group 2 is a percentage ({@link #PERCENT}), group 3 any other number (a
     * lone "9" beside a lone "%", "9 of 100") or a spelled-out one.
     */
    private static final Pattern NUMBERS = Pattern.compile("(\\bwin=[\\w/-]+"
            + "|[+-]?\\$[\\p{Z}\\s]?\\p{N}+(?:[.,]\\p{N}+)*"
            + "|(?<![\\p{N}:.,])\\p{N}{1,2}:\\p{N}{2}(?![\\p{N}:]))"
            + "|(" + PERCENT + ")"
            + "|(\\p{N}+(?:[.,]\\p{N}+)*|" + NUMBER_WORD + ")");
    /** A percentage as a summary keeps it where it could be the acceptance rate: no digit, so not even its size. */
    static final String MASKED_PERCENT = "#%";
    /** Any other number where the acceptance rate could be, and the accepts a goal still needs. */
    static final String MASKED_NUMBER = "#";
    /**
     * "about 15 more accepts": the accepts a goal still needs, which beside that goal give the rate away (Autopilot's
     * "about 61 more accepts to reach 70%" never names the rate): masked on every line ("about # more accepts").
     */
    private static final Pattern ACCEPTS_NEEDED =
            Pattern.compile("(?i)(?<!\\p{N})\\p{N}+(?=[\\p{Z}\\s]+more[\\p{Z}\\s]+accepts?\\b)");
    /** A log line's source tag, after its time: "[autopilot] ", "[screen] ". */
    private static final String AUTOPILOT_TAG = "[" + AutopilotRuntime.LOG + "] ";
    /**
     * The Autopilot log lines a summary may carry, each whole ({@link AutopilotText}'s fixed lines that never name the
     * acceptance rate): on, off, a goal, a commit with one of its fixed reasons (or its deferral or failure), a
     * discarded or failed plan, and the exemption valve. Every other Autopilot line (its plans and Dasher's readings
     * carry the rate) stays on the phone, as does any line a later version adds, or one that adds to a listed line,
     * until it is listed here.
     */
    private static final Pattern AUTOPILOT_KEPT = Pattern.compile("on; (?:goal \\d{1,3}%|pay first)"
            + "|" + Pattern.quote(AutopilotText.LOG_OFF)
            + "|(?:goal \\d{1,3}%|pay first) \\(was (?:\\d{1,3}%|pay first)\\)"
            + "|commit \\d{1,3}% -> \\d{1,3}% \\((?:" + commitReasons() + ")\\)"
            + "|" + Pattern.quote(AutopilotText.LOG_DEFERRED)
            + "|commit failed: [\\w$]+"
            + "|" + Pattern.quote(AutopilotText.DISCARD_RULES)
            + "|" + Pattern.quote(AutopilotText.DISCARD_OLD)
            + "|" + Pattern.quote(AutopilotText.DISCARD_CLEARED)
            + "|" + Pattern.quote(AutopilotText.DISCARD_AUTOPILOT_CHANGED)
            + "|plan failed: [\\w$]+"
            + "|ar exemptions ignored: \\d+ of \\d+ declines flagged");
    /**
     * The decline question's own screen line ("[screen] confirmation|…", {@link DiagnosticLog#QUESTION_PHASE}): all of
     * its labels' numbers go, whatever its words. Any other line whose labels show the question goes the same, so a
     * renamed phase stays masked too.
     */
    private static final String QUESTION_TAG =
            "[" + DiagnosticLog.SCREEN_SOURCE + "] " + DiagnosticLog.QUESTION_PHASE + "|";
    /** Where a screen line's labels begin ("… labels=[…] metricParts=[…]"): its head before them is the app's own. */
    private static final String LABELS = "labels=[";
    private static final String METRIC_PARTS = "metricParts=[";
    /** A summary's lines, as {@link #remask} tells them apart: a log excerpt's, a read list's and an outcome step's. */
    private static final Pattern EXCERPT_LINE = Pattern.compile("([+-]\\d+:\\d{2}:\\d{2})( \\[.*)");
    private static final Pattern READ_LINE = Pattern.compile("( {3,4}read: )\\[(.*)\\]");
    private static final Pattern STEP_LINE = Pattern.compile("( {4}then [+-]\\d+:\\d{2}:\\d{2} )(.*)");

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
    // What the screen reader's thread last handed over (guarded by WINDOW): a layout, and when.
    private static String postedLayout;
    private static long postedAt;

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

    /** The user turned the opt-in on or off: the hooks follow at once; off forgets what this dash counted. */
    static void optInChanged(Context context, boolean on) {
        Context app = context.getApplicationContext();
        collecting = on;
        collectingFor = app;
        if (!on) forgetSoon(app);
    }

    /**
     * The opt-in turned off, or Clear history: what this dash counted and every automatic summary not yet sent go, on
     * this class's thread, after every count and summary queued before (one queued after belongs to a later epoch, and
     * stays). Any thread; nothing waits for the disk.
     */
    static void forgetSoon(Context context) {
        Context app = context.getApplicationContext();
        synchronized (WINDOW) {
            windowMs.clear();
            windowLayout = null;
            postedLayout = null;
        }
        WORKER.execute(() -> {
            try {
                clear(app);
                FeedbackOutbox.discardAutomatic(app);
                // A dash that already ended without its summary is never summarized now (one went quiet and is found
                // later, say): what it counted is gone, and its summary would be one not yet sent.
                long[] last = Dashing.lastDash(app);
                if (last != null && last[1] != Long.MAX_VALUE) {
                    synchronized (DashSummary.class) {
                        prefs(app).edit().putLong(SUMMARIZED, last[0]).commit();
                    }
                }
            } catch (RuntimeException failure) {
                // Discarded anyway before any send: the outbox checks the opt-in and its epoch before every part.
            }
        });
    }

    // ---- Hooks (any thread; cheap) ----

    /**
     * A visible offer the rules could not judge (a reading gap): counted, with its read lines masked twice, and only
     * when they are a recognized offer screen (anything else, a partial screen among it, keeps a not-kept note).
     */
    static void unreadable(Context context, List<String> labels) {
        if (!collecting(context)) return;
        List<String> copy = labels == null ? Collections.<String>emptyList() : new ArrayList<>(labels);
        long at = System.currentTimeMillis();
        record(context, at, model -> {
            increment(model, "unreadable", "offers");
            anomaly(model, at, "unreadable offer", readLines(copy));
        });
    }

    /**
     * An offer's read lines as a summary keeps them: masked twice when recognized, else only a not-kept note; with
     * Dasher's decline question among them, or a label naming the acceptance rate, without a single number but money
     * and clock times ({@link #withoutAcceptanceRate(List, List)}, decided on these raw labels).
     */
    static List<String> readLines(List<String> labels) {
        return PersonalText.recognizedDashScreen(labels) ? withoutAcceptanceRate(labels, OfferReport.redact(labels))
                : Collections.singletonList(PersonalText.UNKNOWN_NOT_KEPT);
    }

    /**
     * {@code redacted} ({@link OfferReport#redact} of {@code raw}) as a summary may carry it. Decided on the raw labels
     * as well, before redaction reduced "AR" or "percent" to a shape: when they ask Dasher's question or name the rate,
     * every number among the redacted ones goes but money and clock times ({@link #numbersMasked}); otherwise as
     * {@link #withoutAcceptanceRate(List)}.
     */
    static List<String> withoutAcceptanceRate(List<String> raw, List<String> redacted) {
        if (redacted == null) return null;
        return raw != null && asksOrNamesTheRate(raw) ? masked(redacted, true) : withoutAcceptanceRate(redacted);
    }

    /**
     * Read labels as a summary may carry them (a problem's, kept since it was counted, or a decision's): when they show
     * Dasher's decline question (its rate may be any label, with or without words beside it) or any of them names the
     * acceptance rate, every number among them is masked but money and clock times ({@link #numbersMasked}); otherwise
     * only the accepts a goal still needs.
     */
    static List<String> withoutAcceptanceRate(List<String> labels) {
        return labels == null ? null : masked(labels, asksOrNamesTheRate(labels));
    }

    private static List<String> masked(List<String> labels, boolean rate) {
        List<String> out = new ArrayList<>(labels.size());
        for (String label : labels) {
            out.add(label == null ? null : rate ? numbersMasked(label) : acceptsNeededMasked(label));
        }
        return out;
    }

    /**
     * A log line's text (after its time: " [source] message") as a summary may carry it, or null when it stays on the
     * phone. The acceptance rate Dasher shows on its decline question leaves the phone only in what the user sends
     * (Share report, feedback with masked diagnostics attached, Report this offer), never in this automatic summary,
     * whatever its form ("9%", "9 percent", "9 pct", a lone "9" beside a lone "%"):
     * <ul>
     *   <li>of Autopilot's lines only those that never name it are kept, each whole ({@link #AUTOPILOT_KEPT}: not its
     *   plans, not Dasher's readings, nothing unlisted);</li>
     *   <li>a screen line's labels ("labels=[…] metricParts=[…]") lose every number but money and clock times when the
     *   line is the decline question's own ({@link #QUESTION_TAG}) or its labels show the question or name the rate;
     *   its head, the app's own (the phase, the offer's figures, the decision, the window field), stays;</li>
     *   <li>any other line that shows the question or names the rate loses every such number too;</li>
     *   <li>and every line loses the accepts a goal still needs ("about # more accepts").</li>
     * </ul>
     */
    static String withoutAcceptanceRate(String text) {
        if (text == null) return null;
        String line = text.trim();
        if (line.startsWith(AUTOPILOT_TAG)) {
            return AUTOPILOT_KEPT.matcher(line.substring(AUTOPILOT_TAG.length())).matches() ? text : null;
        }
        boolean question = line.startsWith(QUESTION_TAG);
        int labels = text.indexOf(LABELS);
        String head = labels < 0 ? text : text.substring(0, labels);
        String shown = labels < 0 ? "" : text.substring(labels);
        if (labels >= 0 && (question || asksOrNamesTheRate(labelsOf(shown)))) shown = numbersMasked(shown);
        // The head is the app's own words; were it ever to name the rate (or a question line carry no labels), it
        // loses its numbers too.
        if (asksOrNamesTheRate(Collections.singletonList(head)) || (question && labels < 0)) head = numbersMasked(head);
        return acceptsNeededMasked(head + shown);
    }

    /** {@code text} (an outcome step's words) with every number masked when it names the rate; as it was otherwise. */
    private static String maskedWhereItNamesTheRate(String text) {
        if (text == null) return null;
        return asksOrNamesTheRate(Collections.singletonList(text)) ? numbersMasked(text) : acceptsNeededMasked(text);
    }

    /**
     * Whether {@code labels} show Dasher's decline question ({@link DeclineConfirmation#hasPrompt}: its rate may stand
     * alone, with no words beside it) or any of them names the acceptance rate ({@link #RATE_WORDS}).
     */
    static boolean asksOrNamesTheRate(List<String> labels) {
        List<String> present = new ArrayList<>(labels.size());
        for (String label : labels) if (label != null) present.add(label);
        if (DeclineConfirmation.hasPrompt(present)) return true;
        for (String label : present) {
            if (RATE_WORDS.matcher(Normalizer.normalize(label, Normalizer.Form.NFKC)).find()) return true;
        }
        return false;
    }

    /** A screen line's labels, as nearly as their printed form ("labels=[a, b] metricParts=[c]") tells them. */
    private static List<String> labelsOf(String shown) {
        String inner = shown.replace(LABELS, "").replace("] " + METRIC_PARTS, ", ").replace(METRIC_PARTS, ", ");
        if (inner.endsWith("]")) inner = inner.substring(0, inner.length() - 1);
        return Arrays.asList(inner.split(", "));
    }

    /**
     * Every number in {@code text} masked, however it is written: a percentage ("9%", "9 %", "９％", "9 percent",
     * "9 pct") as {@value #MASKED_PERCENT}, any other figure or a spelled-out number as {@value #MASKED_NUMBER}. Only
     * what cannot be a rate stays: money ("$7.90"), a clock or countdown ("0:35") and the window field.
     */
    static String numbersMasked(String text) {
        if (text == null) return null;
        Matcher number = NUMBERS.matcher(text);
        StringBuffer out = new StringBuffer(text.length());
        while (number.find()) {
            String replacement = number.group(1) != null ? number.group(1)
                    : number.group(2) != null ? MASKED_PERCENT : MASKED_NUMBER;
            number.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        number.appendTail(out);
        return out.toString();
    }

    /** "about # more accepts": the accepts a goal still needs, masked wherever they are. */
    private static String acceptsNeededMasked(String text) {
        return ACCEPTS_NEEDED.matcher(text).replaceAll(MASKED_NUMBER);
    }

    /** {@link AutopilotText}'s commit reasons, each quoted, for {@link #AUTOPILOT_KEPT} (and none at all). */
    private static String commitReasons() {
        StringBuilder out = new StringBuilder();
        for (Autopilot.Reason reason : Autopilot.Reason.values()) {
            out.append(Pattern.quote(AutopilotText.reasonWords(reason))).append('|');
        }
        return out.toString();
    }

    /**
     * A summary waiting to be sent, filtered again right before it goes ({@link FeedbackOutbox}), so a summary an
     * older version queued gets this version's filter: each log excerpt's line goes through
     * {@link #withoutAcceptanceRate(String)} (an Autopilot line not listed as safe is dropped), each read list through
     * {@link #withoutAcceptanceRate(List)}, each outcome step as it is written, and every line loses the accepts a goal
     * still needs. What this version built comes back as it was.
     */
    static String remask(String text) {
        if (text == null) return null;
        StringBuilder out = new StringBuilder(text.length());
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            String line = text.substring(start, end < 0 ? text.length() : end);
            String kept = remaskLine(line);
            if (kept != null) {
                out.append(kept);
                if (end >= 0) out.append('\n');
            }
            start = end < 0 ? text.length() : end + 1;
        }
        return out.toString();
    }

    private static String remaskLine(String line) {
        Matcher excerpt = EXCERPT_LINE.matcher(line);
        if (excerpt.matches()) {
            String kept = withoutAcceptanceRate(excerpt.group(2));
            return kept == null ? null : excerpt.group(1) + kept;
        }
        Matcher read = READ_LINE.matcher(line);
        if (read.matches()) {
            return read.group(1) + withoutAcceptanceRate(Arrays.asList(read.group(2).split(", ", -1)));
        }
        Matcher step = STEP_LINE.matcher(line);
        if (step.matches()) return step.group(1) + maskedWhereItNamesTheRate(step.group(2));
        return acceptsNeededMasked(line);
    }

    /** A declined offer or its confirmation still showing seconds after the first Decline: counted by stage. */
    static void stuck(Context context, String stage) {
        if (!collecting(context)) return;
        long at = System.currentTimeMillis();
        record(context, at, model -> {
            increment(model, "stuck", stage);
            anomaly(model, at, "decline still showing (" + stage + ")", null);
        });
    }

    /** A Back after Dasher's decline error: requested or refused. */
    static void recovery(Context context, String outcome) {
        if (!collecting(context)) return;
        long at = System.currentTimeMillis();
        record(context, at, model -> {
            increment(model, "recovery", outcome);
            anomaly(model, at, "decline-error recovery (" + outcome + ")", null);
        });
    }

    /** An armed automatic Accept not sent, by its fixed reason category. */
    static void notSent(Context context, String reason) {
        if (!collecting(context)) return;
        record(context, System.currentTimeMillis(), model -> increment(model, "notSent", reason));
    }

    /** A screen-read or notification-handler error: its type and top frame, digits masked; never its message. */
    static void error(Context context, String where, Throwable error) {
        if (!collecting(context) || error == null) return;
        String signature = signature(error);
        long at = System.currentTimeMillis();
        record(context, at, model -> {
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
        record(context, System.currentTimeMillis(), model -> increment(model, "peek", category));
    }

    /**
     * A Peek line's category: its words up to the first parenthesis, semicolon, timing or the per-line phone state
     * (" car="), digits masked, at most 60 characters. Peek's lines name kinds of apps, never an app; the one line that
     * names the offer's store ("opening Dasher for …") is only "opened Dasher", and a peek's cost line, all figures,
     * is only "cost".
     */
    static String peekCategory(String line) {
        if (line.startsWith("opening Dasher")) return "opened Dasher";
        if (line.startsWith("cost:")) return "cost";
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
     * summary can say what share of the dash each took. Called on every read, on the screen reader's thread: a field
     * check and a comparison in memory; a changed layout, or every few seconds the same one, is handed to this class's
     * thread, which alone asks whether a dash is on and keeps the time.
     */
    static void window(Context context, String field) {
        if (!collecting(context)) return;
        String layout = layoutOf(field);
        long now = SystemClock.elapsedRealtime();
        synchronized (WINDOW) {
            // The same layout is accounted at its next change, or every few seconds: most reads cost a comparison.
            if (layout.equals(postedLayout) && now - postedAt >= 0 && now - postedAt < WINDOW_ACCOUNT_MS) return;
            postedLayout = layout;
            postedAt = now;
        }
        Context app = context.getApplicationContext();
        long at = System.currentTimeMillis();
        WORKER.execute(() -> {
            try {
                account(app, layout, now, at);
            } catch (RuntimeException failure) {
                // Only a share of time.
            }
        });
    }

    /** Folds the time since the last layout into it, during a dash only. On this class's thread. */
    private static void account(Context app, String layout, long now, long at) {
        // Time outside a dash is nobody's.
        boolean dashing = Dashing.on(app);
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
        if (save) apply(app, at, model -> { });
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
        // The opt-in's epoch as the dash ended: turning it off (and on again) before the summary is built discards it.
        long epoch = Feedback.automaticEpoch(app);
        WORKER.execute(() -> summarizeSafely(app, start, end, End.DASH_OVER, epoch));
    }

    /**
     * A new dash begins ({@link Dashing#seen}): the one before it, still open, went quiet without an end being seen,
     * and is summarized now (the existing thirty-minute quiet end).
     */
    static void dashStarting(Context context, long previousStart, long previousSeen) {
        if (!collecting(context) || previousStart <= 0) return;
        Context app = context.getApplicationContext();
        long epoch = Feedback.automaticEpoch(app);
        WORKER.execute(() -> summarizeSafely(app, previousStart, previousSeen, End.QUIET, epoch));
    }

    /** When the app opens or a service connects: a dash that went quiet meanwhile is summarized now. */
    static void checkSoon(Context context) {
        if (!collecting(context)) return;
        Context app = context.getApplicationContext();
        long epoch = Feedback.automaticEpoch(app);
        WORKER.execute(() -> {
            long[] quiet = Dashing.quietlyEnded(app);
            if (quiet != null) summarizeSafely(app, quiet[0], quiet[1], End.QUIET, epoch);
        });
    }

    private static void summarizeSafely(Context app, long start, long end, End why, long epoch) {
        try {
            summarize(app, start, end, why, epoch);
        } catch (RuntimeException failure) {
            // On a background thread an escaping exception would take the whole app, screen reader included.
            DiagnosticLog.log(app, "feedback", "could not build the dash's summary: "
                    + failure.getClass().getSimpleName());
        }
    }

    /** {@link #summarize(Context, long, long, End, long)} in the opt-in's current epoch (as a caller on this thread). */
    static void summarize(Context app, long start, long end, End why) {
        summarize(app, start, end, why, Feedback.automaticEpoch(app));
    }

    /**
     * At most one summary a dash and {@value #MAX_PER_DAY} a day; only with the opt-in on and the notice accepted, and
     * only in the opt-in's epoch the dash ended in ({@link Feedback#automaticEpoch}): turning the opt-in off (and on
     * again) or clearing history after the dash ended, before or while its summary is built, discards it.
     */
    static void summarize(Context app, long start, long end, End why, long epoch) {
        if (!Consent.accepted(app) || !Feedback.afterDashOn(app)) return;
        SharedPreferences prefs = prefs(app);
        JSONObject model = null;
        synchronized (DashSummary.class) {
            if (prefs.getLong(SUMMARIZED, 0) == start) return;
            boolean changed = !Feedback.automaticAllowed(app, epoch);
            long now = System.currentTimeMillis();
            long day = (now + TimeZone.getDefault().getOffset(now)) / 86_400_000L;
            int today = prefs.getLong(DAY, -1) == day ? prefs.getInt(TODAY, 0) : 0;
            boolean capped = !changed && today >= MAX_PER_DAY;
            // Marked first: whatever happens next, this dash never queues a second summary.
            SharedPreferences.Editor edit = prefs.edit().putLong(SUMMARIZED, start);
            if (!changed && !capped) {
                edit.putLong(DAY, day).putInt(TODAY, today + 1);
                model = model(app, start);
                flushWindow(model);
            }
            // The counts are this dash's (or an older one's), summarized or not: they go now. A later dash's stay.
            if (prefs.getLong(START, 0) <= start) {
                edit.remove(START).remove(COUNTS).remove(ANOMALIES);
                synchronized (WINDOW) {
                    windowMs.clear();
                }
            }
            edit.commit();
            if (changed || capped) {
                DiagnosticLog.log(app, "feedback", "dash summary not queued: " + (changed
                        ? "the option changed since the dash ended" : "today's " + MAX_PER_DAY + " were"));
                return;
            }
        }
        String text = build(app, start, end, why, model);
        List<String> parts = Feedback.chunks(text, Feedback.MAX_SUMMARY_CHARS, Feedback.MAX_PART_BYTES);
        boolean queued = parts.size() == 1
                && FeedbackOutbox.submitAutomatic(app, FeedbackOutbox.automatic(Feedback.newToken(), parts, epoch));
        if (queued) StopReports.acknowledge(app);
        DiagnosticLog.log(app, "feedback", "dash ended (" + why.why + "): summary "
                + (queued ? "queued to send" : Feedback.automaticAllowed(app, epoch)
                        ? "not queued (the outbox is full)" : "not queued (the option changed meanwhile)"));
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

    /**
     * The switches, the bar and which rules are set (never their amounts): "auto-decline on · Peek on · quiet while
     * declining off · auto-accept off · offer map off · bar 82% (Autopilot on) · rules set: pay, per mile, per hour".
     * Never Autopilot's acceptance-rate reading, its goal or its plan. The bar is Autopilot's, and Autopilot moves it
     * for the goal, so a bar below 100% can say the rate was below the goal; it never says what the rate was.
     */
    private static String settings(Context app, FilterSettings rules) {
        List<String> set = new ArrayList<>();
        if (rules.flatCents > 0) set.add("pay");
        if (rules.perMileCents > 0) set.add("per mile");
        if (rules.perMinuteCents > 0) set.add("per hour");
        if (rules.maxStops > 0) set.add("max stops");
        return "auto-decline " + onOff(rules.enabled) + " · Peek " + onOff(FilterStore.peek(app))
                + " · quiet while declining " + onOff(FilterStore.silenceWhileDeclining(app))
                + " · auto-accept " + onOff(FilterStore.autoAcceptEnabled(app))
                + " · offer map " + onOff(AreaMap.enabled(app))
                + " · bar " + rules.minimumScalePercent + "% (Autopilot " + onOff(rules.autopilot) + ")"
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

    /**
     * Each decision as a line (and its read lines, masked twice, and what became of it after, one step a line), timed
     * from the dash's start. A line an older version decided shows its retired score as an area score.
     */
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
                    .append(entry.scorePercent < 0 ? "" : (entry.model >= DecisionLog.MODEL ? " | score "
                            : " | area score ") + entry.scorePercent + "%")
                    .append(" | ").append(DecisionLog.facts(entry.facts))
                    .append(" | ").append(entry.reason)
                    .append(" | ").append(entry.action.label)
                    .append(" | outcome ").append(DecisionLog.outcome(entry).word)
                    .append(entry.autoDecline ? "" : " | auto-decline paused")
                    .append('\n');
            // As a problem's read lines: a label that merged pay and Dasher's question keeps the pay, never the rate.
            List<String> read = withoutAcceptanceRate(entry.evidence, OfferReport.redact(entry.evidence));
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
                // Fixed words; one that ever named the acceptance rate beside a figure would carry it masked.
                line.append("    then ").append(relative(step.at - start)).append(' ')
                        .append(maskedWhereItNamesTheRate(step.text().replace('\n', ' '))).append('\n');
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
     * the dash's start; "" when there was none. The log is already masked; it is masked again as it is read, and
     * nothing of it carries the acceptance rate ({@link #withoutAcceptanceRate(String)}).
     */
    private static String excerpts(Context app, long start, JSONObject model) {
        JSONArray anomalies = model.optJSONArray(ANOMALIES);
        if (anomalies == null || anomalies.length() == 0) return "";
        // As every report reads the log: a line of a payment, account or earnings screen is a not-kept note.
        String log = DiagnosticLog.withoutAccountScreens(DiagnosticLog.read(app));
        List<long[]> times = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        SimpleDateFormat format = new SimpleDateFormat(TIME_PATTERN, Locale.US);
        for (String line : log.split("\n")) {
            // The text starts where the time ends, whatever the length of its zone ("Z" or "-04:00").
            ParsePosition end = new ParsePosition(0);
            Date at = format.parse(line, end);
            if (at == null) continue;
            String text = withoutAcceptanceRate(line.substring(end.getIndex()));
            if (text == null) continue;
            times.add(new long[] {at.getTime()});
            texts.add(text);
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
                // Masked as they were counted (on the raw labels); again here, on what was kept, for lines an older
                // version counted: the question's words survive redaction, and so does "acceptance rate".
                out.append("   read: ").append(withoutAcceptanceRate(shown)).append('\n');
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

    /**
     * Applies a change to the counts of the dash under way at {@code at} (when its hook fired), off the caller's
     * thread; dropped outside a dash, and once that dash's summary was decided.
     */
    private static void record(Context context, long at, Change change) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> apply(app, at, change));
    }

    /** {@link #record}'s work, on this class's thread. */
    private static void apply(Context app, long at, Change change) {
        try {
            long start = dashAt(app, at);
            if (start <= 0) return;
            synchronized (DashSummary.class) {
                // Checked under the lock clear() takes: an opt-out or Clear history before this wins.
                if (!Feedback.afterDashOn(app) || !Consent.accepted(app)) return;
                if (prefs(app).getLong(SUMMARIZED, 0) == start) return;
                JSONObject model = model(app, start);
                change.apply(model);
                flushWindow(model);
                save(app, model);
            }
        } catch (JSONException | RuntimeException failure) {
            // Only a count.
        }
    }

    /**
     * The start of the dash under way at {@code at}: the current one, or (for a hook that fired just before Dasher
     * showed its end, or before it went quiet) the last one, when {@code at} lies within it; else 0.
     */
    private static long dashAt(Context app, long at) {
        long current = Dashing.currentStart(app);
        if (current > 0 && at >= current) return current;
        long[] last = Dashing.lastDash(app);
        return last != null && at >= last[0] && at <= last[1] ? last[0] : 0;
    }

    /** This dash's counts, or new ones when the counts kept were another dash's. */
    private static JSONObject model(Context app, long start) {
        SharedPreferences prefs = prefs(app);
        JSONObject model = new JSONObject();
        try {
            long kept = prefs.getLong(START, 0);
            if (kept == start) {
                model.put(COUNTS, new JSONObject(prefs.getString(COUNTS, "{}")));
                model.put(ANOMALIES, new JSONArray(prefs.getString(ANOMALIES, "[]")));
            } else {
                model.put(COUNTS, new JSONObject()).put(ANOMALIES, new JSONArray());
                // Window time in memory belongs to this dash, unless another dash's counts were still kept.
                if (kept != 0) {
                    synchronized (WINDOW) {
                        windowMs.clear();
                    }
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

    /** Clear history, or the opt-in turned off: this dash's counts go. On a worker ({@link #forgetSoon}). */
    static void clear(Context context) {
        Context app = context.getApplicationContext();
        synchronized (DashSummary.class) {
            prefs(app).edit().remove(START).remove(COUNTS).remove(ANOMALIES).commit();
        }
        synchronized (WINDOW) {
            windowMs.clear();
            windowLayout = null;
            postedLayout = null;
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
            postedLayout = null;
            postedAt = 0;
        }
    }

    /** Runs {@code work} on this class's thread, after what is queued; for tests (to hold it, or follow it). */
    static void onWorkerForTests(Runnable work) {
        WORKER.execute(work);
    }

    private DashSummary() {}
}

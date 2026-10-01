package com.local.dasherfilter;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Diagnostics after each dash, the user's own opt-in ("Share diagnostics after each dash" in Settings, Reports), so a
 * report reaches the repository without being shared by hand. Once a dash ends, the report Share report builds, its
 * screen text masked on the phone ({@link PersonalText}), is filed as one issue in the app's private repository,
 * titled "[diagnostics] Offer Filter &lt;version&gt; 2026-09-30 18:02–21:47" with the label "diagnostics", in parts of at most
 * 60,000 characters (the issue, then comments). It goes only through the user's GitHub connection, to this
 * repository's issues, and only while GitHub is connected and "Send reports through my GitHub connection" is on:
 * turning either off (or reports, or disconnecting) turns this off and discards anything unsent. The fixer acts only
 * on "[offer-report]" issues, never on these.
 *
 * <p>A dash runs from the first offer (on screen or in a notification) after the last one filed, to its end:
 * Dasher showing the dash ended (its summary, or Dasher's home), Dasher's "End dash" screen, or 30 minutes with no
 * offer after the last one. One issue per dash at most. Nothing about it decides anything about offers.
 */
final class DashDiagnostics {
    static final String TITLE_PREFIX = "[diagnostics]";
    static final String LABEL = "diagnostics";
    /** No offer for this long after the last one ends the dash. */
    static final long QUIET_MS = 30 * 60_000L;
    /** Runs the quiet check; sent by {@link ReportJobService}. */
    static final int JOB_ID = 7245;
    /** GitHub rejects an issue body or comment over 65,536 characters. */
    static final int MAX_PART_CHARS = ProblemReport.MAX_BODY_CHARS;
    /** Offers seen within this long of the last one noted are the same moment: the screen reads one many times. */
    private static final long NOTE_EVERY_MS = 60_000L;

    private static final String PREFS = "dash_diagnostics";
    private static final String ON = "on";
    private static final String STARTED_AT = "started_at";
    private static final String LAST_OFFER_AT = "last_offer_at";
    private static final String LAST_DASH = "last_dash";

    /** Dasher showing the dash is over: its end, its summary, or Dasher's home with its Dash button. */
    private static final Set<String> ENDED = new HashSet<>(Arrays.asList(
            "dash ended", "your dash has ended", "dash summary", "dash now", "start dashing"));
    /** Dasher's own "End dash" screen. */
    private static final Set<String> END_DASH = new HashSet<>(Arrays.asList(
            "end dash", "end your dash", "end this dash"));

    /** Why a dash counts as over. */
    enum End {
        DASH_OVER("Dasher showed the dash ended"),
        END_DASH("Dasher's End dash screen"),
        QUIET("no offer for 30 minutes after the last one");

        final String why;

        End(String why) {
            this.why = why;
        }
    }

    /** One dash, from its first offer to its end (wall clock). */
    static final class Dash {
        final long start;
        final long end;
        final End why;

        Dash(long start, long end, End why) {
            this.start = start;
            this.end = Math.max(start, end);
            this.why = why;
        }
    }

    /** The wall clock; tests move it. */
    static java.util.function.LongSupplier clock = System::currentTimeMillis;
    /** Everything but the cheap checks runs here, never on the screen reader's thread or the main thread. */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    /** Guards the open dash. */
    private static final Object LOCK = new Object();
    private static volatile long notedAt;

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whether the switch may be on: GitHub connected and "Send reports through my GitHub connection" on. */
    static boolean allowed(Context context) {
        return ReportOutbox.throughGitHub(context);
    }

    /** On: the user turned it on, and it is still allowed. */
    static boolean on(Context context) {
        return prefs(context).getBoolean(ON, false) && allowed(context);
    }

    /**
     * Turns diagnostics after each dash on (only while {@link #allowed}) or off. Off discards any still waiting.
     *
     * @return whether it is on now
     */
    static boolean set(Context context, boolean on) {
        Context app = context.getApplicationContext();
        if (!on || !allowed(app)) {
            stop(app);
            return false;
        }
        synchronized (LOCK) {
            prefs(app).edit().putBoolean(ON, true).remove(STARTED_AT).remove(LAST_OFFER_AT).apply();
        }
        notedAt = 0;
        DiagnosticLog.log(app, "diagnostics", "on: the report goes to the repository's issues after each dash");
        return true;
    }

    /**
     * Off, with nothing left to send: the switch, "Send reports through my GitHub connection", reports or the GitHub
     * connection went off. Any dash under way is forgotten and unsent diagnostics are discarded.
     */
    static void stop(Context context) {
        Context app = context.getApplicationContext();
        boolean was;
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(app);
            was = prefs.getBoolean(ON, false);
            if (was || prefs.contains(STARTED_AT)) {
                prefs.edit().putBoolean(ON, false).remove(STARTED_AT).remove(LAST_OFFER_AT).apply();
            }
        }
        notedAt = 0;
        cancelCheck(app);
        ReportOutbox.discardDiagnostics(app);
        if (was) DiagnosticLog.log(app, "diagnostics", "off; diagnostics not yet sent were discarded");
    }

    /**
     * An offer was seen (on screen, after it was handled, or in Dasher's notification): it opens a dash, or keeps one
     * open. Cheap on the caller's thread: at most one note a minute, and none while off.
     */
    static void offerSeen(Context context) {
        long now = clock.getAsLong();
        long noted = notedAt;
        if (noted != 0 && now >= noted && now - noted < NOTE_EVERY_MS) return;
        if (!on(context)) return;
        notedAt = now;
        Context app = context.getApplicationContext();
        WORKER.execute(() -> noteOffer(app, now));
    }

    private static void noteOffer(Context app, long now) {
        Dash quiet = null;
        synchronized (LOCK) {
            if (!on(app)) return;
            SharedPreferences prefs = prefs(app);
            long started = prefs.getLong(STARTED_AT, 0);
            long last = Math.max(started, prefs.getLong(LAST_OFFER_AT, 0));
            if (started > 0 && now - last >= QUIET_MS) {
                // The quiet check never ran (Android held the job back): the dash before ended all the same.
                quiet = new Dash(started, last, End.QUIET);
                started = 0;
            }
            SharedPreferences.Editor edit = prefs.edit().putLong(LAST_OFFER_AT, now);
            if (started <= 0) edit.putLong(STARTED_AT, now);
            edit.commit();
        }
        if (quiet != null) file(app, quiet);
        scheduleCheck(app, QUIET_MS);
    }

    /**
     * One of Dasher's screens with no offer on it: when it shows the dash ended, the dash's diagnostics are filed.
     * Cheap on the screen reader's thread: nothing is looked at while off or with no dash open.
     */
    static void screen(Context context, List<String> labels) {
        if (!on(context) || prefs(context).getLong(STARTED_AT, 0) <= 0) return;
        End end = endOf(labels);
        if (end == null) return;
        long now = clock.getAsLong();
        Context app = context.getApplicationContext();
        WORKER.execute(() -> ended(app, now, end));
    }

    /**
     * Whether a screen ends a dash, and how: Dasher's dash ended, its summary or its "Dash now" home
     * ({@link End#DASH_OVER}, as {@link Dashing} counts them, less a pause), or its "End dash" screen when nothing of
     * the dash (the wait for offers, a delivery, an offer) is on it ({@link End#END_DASH}). A paused dash is not over;
     * 30 quiet minutes end it. Null for any other screen.
     */
    static End endOf(List<String> labels) {
        if (labels == null || labels.isEmpty()) return null;
        boolean endDash = false;
        for (String raw : labels) {
            String label = OfferEvidence.normalize(raw).toLowerCase(Locale.US).replaceAll("[.!?…]+$", "");
            if (ENDED.contains(label)) return End.DASH_OVER;
            if (END_DASH.contains(label) || label.startsWith("are you sure you want to end your dash")) endDash = true;
        }
        // Dasher's home with its "Dash" button alone is left out, as the homepage's count of dashes leaves it out.
        boolean dashing = DasherScene.showsRoute(labels) || DasherScene.showsWaiting(labels)
                || DasherScene.showsNewOffer(labels) || AcceptedOfferTracker.showsOfferFacts(labels);
        return endDash && !dashing ? End.END_DASH : null;
    }

    private static void ended(Context app, long now, End why) {
        Dash dash;
        synchronized (LOCK) {
            if (!on(app)) return;
            SharedPreferences prefs = prefs(app);
            long started = prefs.getLong(STARTED_AT, 0);
            if (started <= 0) return;
            prefs.edit().remove(STARTED_AT).remove(LAST_OFFER_AT).commit();
            dash = new Dash(started, now, why);
        }
        notedAt = 0;
        cancelCheck(app);
        file(app, dash);
    }

    /** When the app opens: a dash that went quiet while the check was held back is filed now. */
    static void checkSoon(Context context) {
        Context app = context.getApplicationContext();
        if (!on(app) || prefs(app).getLong(STARTED_AT, 0) <= 0) return;
        WORKER.execute(() -> check(app));
    }

    /** The quiet check: 30 minutes with no offer after the last one ends the dash; otherwise it looks again later. */
    static void check(Context context) {
        Context app = context.getApplicationContext();
        long now = clock.getAsLong();
        Dash dash = null;
        long wait = 0;
        synchronized (LOCK) {
            if (!on(app)) return;
            SharedPreferences prefs = prefs(app);
            long started = prefs.getLong(STARTED_AT, 0);
            if (started <= 0) return;
            long last = Math.max(started, prefs.getLong(LAST_OFFER_AT, 0));
            if (now - last >= QUIET_MS) {
                prefs.edit().remove(STARTED_AT).remove(LAST_OFFER_AT).commit();
                dash = new Dash(started, last, End.QUIET);
            } else {
                wait = Math.min(QUIET_MS, QUIET_MS - (now - last));
            }
        }
        if (dash == null) {
            scheduleCheck(app, wait);
            return;
        }
        notedAt = 0;
        file(app, dash);
    }

    /** Builds the dash's report and queues it to send. On {@link #WORKER} or the job's own thread. */
    private static void file(Context app, Dash dash) {
        String span = span(dash.start, dash.end);
        boolean queued = false;
        int count = 0;
        try {
            String version = Updater.version(app);
            List<String> parts = parts(intro(version, span, dash.why), DiagnosticLog.fullReport(app), MAX_PART_CHARS);
            count = parts.size();
            queued = ReportOutbox.submitDiagnostics(app, TITLE_PREFIX + " " + AppName.NAME + " " + version + " " + span,
                    parts);
        } catch (RuntimeException failure) {
            // On a background thread an escaping exception would take the whole app, screen reader included.
            DiagnosticLog.log(app, "diagnostics", "could not build the dash's report: "
                    + failure.getClass().getSimpleName());
            return;
        }
        if (queued) prefs(app).edit().putString(LAST_DASH, span).apply();
        DiagnosticLog.log(app, "diagnostics", "dash " + span + " ended (" + dash.why.why + "): "
                + (queued ? count + (count == 1 ? " part" : " parts") + " queued to send"
                        : "not queued (diagnostics are off, the queue is full or today's 6 were sent)"));
    }

    private static String intro(String version, String span, End why) {
        return "Filed automatically by " + AppName.NAME + " " + version
                + " after a dash (\"Share diagnostics after each "
                + "dash\" is on).\n"
                + "Dash: " + span + " (ended: " + why.why + ").\n"
                + DiagnosticLog.MASKED_NOTE + "\n";
    }

    /**
     * The intro and the report in parts of at most {@code max} characters, each cut at a line's end where one is near:
     * the first is the issue, the others its comments, in order.
     */
    static List<String> parts(String intro, String report, int max) {
        // Room for a part's own heading ("Part 12 of 13 (continued)").
        int room = 64;
        List<String> pieces = new ArrayList<>();
        int at = 0;
        while (at < report.length() || pieces.isEmpty()) {
            int budget = Math.max(1, (pieces.isEmpty() ? max - intro.length() : max) - room);
            int end = Math.min(report.length(), at + budget);
            if (end < report.length()) {
                int line = report.lastIndexOf('\n', end - 1);
                if (line >= at + budget / 2) {
                    end = line + 1;
                } else if (Character.isHighSurrogate(report.charAt(end - 1))) {
                    end--;
                }
            }
            pieces.add(report.substring(at, end));
            at = end;
        }
        List<String> parts = new ArrayList<>();
        int count = pieces.size();
        for (int i = 0; i < count; i++) {
            parts.add(i == 0
                    ? intro + (count > 1 ? "Part 1 of " + count + "; the rest follow as comments.\n" : "") + "\n"
                            + pieces.get(0)
                    : "Part " + (i + 1) + " of " + count + " (continued)\n\n" + pieces.get(i));
        }
        return parts;
    }

    /** "2026-09-30 18:02–21:47", or both dates when the dash crossed midnight; the phone's time zone. */
    static String span(long start, long end) {
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat full = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        boolean sameDay = day.format(new Date(start)).equals(day.format(new Date(end)));
        return full.format(new Date(start)) + "–"
                + (sameDay ? new SimpleDateFormat("HH:mm", Locale.US) : full).format(new Date(end));
    }

    /** What the switch does and where it stands, for Settings under it. */
    static String status(Context context) {
        String what = "After a dash ends, the report Share report builds (names, addresses, phone numbers and "
                + "instructions masked) goes to your repository's issues through your GitHub connection.";
        if (!allowed(context)) return "Needs Send reports through my GitHub connection. " + what;
        if (!on(context)) return "Off. " + what;
        int issue = ReportOutbox.lastDiagnostics(context);
        String last = prefs(context).getString(LAST_DASH, "");
        return "On" + (issue > 0 ? " · last #" + issue : last.isEmpty() ? "" : " · last dash " + last + " queued")
                + (prefs(context).getLong(STARTED_AT, 0) > 0 ? " · dash under way" : "") + ". " + what;
    }

    /** For the shared report: whether it is on, and the last dash filed. */
    static String reportLine(Context context) {
        String last = prefs(context).getString(LAST_DASH, "");
        int issue = ReportOutbox.lastDiagnostics(context);
        return (on(context) ? "on" : prefs(context).getBoolean(ON, false) ? "chosen, but the GitHub connection is off"
                : "off") + "; last dash filed: " + (last.isEmpty() ? "none" : last)
                + (issue > 0 ? "; last issue #" + issue : "");
    }

    private static void scheduleCheck(Context app, long delay) {
        JobScheduler jobs = app.getSystemService(JobScheduler.class);
        if (jobs == null) return;
        try {
            jobs.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(app, ReportJobService.class))
                    .setMinimumLatency(Math.max(60_000L, delay))
                    .setPersisted(true)
                    .build());
        } catch (RuntimeException refused) {
            // Android limits how many jobs an app may schedule; the app opening checks too.
        }
    }

    private static void cancelCheck(Context app) {
        JobScheduler jobs = app.getSystemService(JobScheduler.class);
        if (jobs != null) jobs.cancel(JOB_ID);
    }

    /** Waits briefly for queued work; for tests. */
    static void flush() {
        try {
            WORKER.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } catch (Exception interruptedOrTimedOut) {
            if (interruptedOrTimedOut instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    /** Forgets the in-memory throttle, as a process restart would; for tests. */
    static void forgetCache() {
        notedAt = 0;
    }

    private DashDiagnostics() {}
}

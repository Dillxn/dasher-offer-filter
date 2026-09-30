package com.local.dasherfilter;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Automatic problem reports, filed as issues in the app's private repository so the fixer workflow can act on them.
 * Off until the user enters a report token: that opt-in is the explicit consent for reports to leave the phone.
 * Reports queue on disk and a network-constrained job sends them, so no signal or a restart loses nothing.
 */
final class ReportOutbox {
    static final int JOB_ID = 7244;
    /** The same automatic problem is filed at most once a day. */
    private static final long SAME_PROBLEM_MS = 86_400_000L;
    private static final int MAX_AUTOMATIC_PER_DAY = 10;
    private static final int MAX_BY_USER_PER_DAY = 20;
    private static final int MAX_QUEUED = 30;
    private static final String DIR = "report-outbox";

    private static final String TOKEN = "token";
    /** The user chose to send reports through their GitHub connection (the one updates use). */
    private static final String VIA_GITHUB = "via_github";
    private static final String DAY = "day";
    private static final String AUTOMATIC_TODAY = "automatic_today";
    private static final String BY_USER_TODAY = "by_user_today";
    private static final String SEEN = "seen";
    private static final String LAST_SENT_AT = "last_sent_at";
    private static final String LAST_ISSUE = "last_issue";
    private static final String LAST_ERROR = "last_error";

    private static final ExecutorService DISK = Executors.newSingleThreadExecutor();
    /** Signatures filed recently, mirrored from prefs so repeat screen reads cost no I/O. Guarded by itself. */
    private static final Map<String, Long> seen = new HashMap<>();
    private static boolean seenLoaded;
    private static volatile String appVersion;
    /** The wall clock; tests pin it to make two reports land in the same millisecond. */
    static java.util.function.LongSupplier clock = System::currentTimeMillis;
    private static final java.util.concurrent.atomic.AtomicLong sequence = new java.util.concurrent.atomic.AtomicLong();

    /** Reports are on with a pasted token, or through the GitHub connection once the user turned that on. */
    static boolean enabled(Context context) {
        return !prefs(context).getString(TOKEN, "").isEmpty() || throughGitHub(context);
    }

    /** Whether reports go through the GitHub connection now: chosen, and still connected. */
    static boolean throughGitHub(Context context) {
        return prefs(context).getBoolean(VIA_GITHUB, false) && GitHubConnect.configured()
                && GitHubConnect.state(context) == GitHubConnect.State.CONNECTED;
    }

    static boolean useGitHubChosen(Context context) {
        return prefs(context).getBoolean(VIA_GITHUB, false);
    }

    /**
     * Sends reports through the GitHub connection, or stops. Stopping, with no token pasted, discards any report
     * still waiting to send, as removing a token does. Connecting GitHub alone never turns reports on.
     */
    static void useGitHub(Context context, boolean on) {
        prefs(context).edit().putBoolean(VIA_GITHUB, on).remove(LAST_ERROR).apply();
        Context app = context.getApplicationContext();
        if (on) schedule(app);
        else if (!enabled(app)) discard(app);
    }

    /** GitHub was disconnected: reports that went through it stop, and what was waiting is discarded. */
    static void connectionRemoved(Context context) {
        if (!prefs(context).getBoolean(VIA_GITHUB, false)) return;
        prefs(context).edit().putBoolean(VIA_GITHUB, false).apply();
        if (!enabled(context)) discard(context.getApplicationContext());
    }

    private static void discard(Context app) {
        DISK.execute(() -> {
            for (File file : files(app)) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        });
        JobScheduler jobs = app.getSystemService(JobScheduler.class);
        if (jobs != null) jobs.cancel(JOB_ID);
    }

    /**
     * Saves the token, clearing any earlier rejection. A blank token turns reports off and discards any report still
     * waiting to send.
     */
    static void setToken(Context context, String token) {
        String clean = token == null ? "" : token.trim();
        prefs(context).edit().putString(TOKEN, clean).remove(LAST_ERROR).apply();
        Context app = context.getApplicationContext();
        if (!clean.isEmpty()) {
            schedule(app);
            return;
        }
        // Turning reports off turns off the GitHub connection's use for them too.
        prefs(context).edit().putBoolean(VIA_GITHUB, false).apply();
        discard(app);
    }

    static String token(Context context) {
        return prefs(context).getString(TOKEN, "");
    }

    /**
     * The token a report is sent with: the pasted one, else the GitHub connection's (refreshed if due; blocking).
     *
     * @throws IOException when GitHub could not be reached to refresh the connection
     */
    private static String sendingToken(Context context) throws IOException {
        String pasted = token(context);
        if (!pasted.isEmpty()) return pasted;
        if (!throughGitHub(context)) return "";
        String connection = GitHubConnect.token(context);
        return connection == null ? "" : connection;
    }

    /** "On · last report #12, 7:31 PM", "Token rejected…", or "Off". */
    static String status(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!enabled(context)) {
            return GitHubConnect.configured() && GitHubConnect.state(context) == GitHubConnect.State.CONNECTED
                    ? "Off. Turn on to send reports through your GitHub connection."
                    : "Off. Paste a GitHub token to turn on.";
        }
        String error = prefs.getString(LAST_ERROR, "");
        if (!error.isEmpty()) return error;
        int issue = prefs.getInt(LAST_ISSUE, 0);
        int queued = queued(context);
        String last = issue > 0 ? "last report #" + issue : "no reports sent yet";
        return "On · " + last + (queued > 0 ? " · " + queued + " waiting to send" : "");
    }

    /**
     * Files an automatic report when reports are on; never throws into the caller's decision path. A problem
     * already filed today costs one lookup, so a repeating failure cannot slow the screen reader down.
     */
    static void fileAutomatic(Context context, ProblemReport.Kind kind, DecisionLog.Entry entry,
                              java.util.List<String> labels, Throwable error) {
        if (!enabled(context)) return;
        try {
            String version = version(context);
            if (filedRecently(context, ProblemReport.signature(kind, version, entry, labels, error))) return;
            submit(context, ProblemReport.build(kind, version, FilterStore.load(context), entry,
                    labels, error, null, DecisionLog.recent(context, 10)), false);
        } catch (RuntimeException failure) {
            DiagnosticLog.log(context, "report", "could not build report: " + failure.getClass().getSimpleName());
        }
    }

    private static String version(Context context) {
        if (appVersion == null) appVersion = Updater.version(context);
        return appVersion;
    }

    private static boolean filedRecently(Context context, String signature) {
        long now = clock.getAsLong();
        synchronized (seen) {
            loadSeen(context);
            Long filed = seen.get(signature);
            return filed != null && now - filed < SAME_PROBLEM_MS && now >= filed;
        }
    }

    /** Files the user's report about one decision, with their note and recent context. */
    static boolean fileByUser(Context context, DecisionLog.Entry entry, String note) {
        if (!enabled(context)) return false;
        return submit(context, ProblemReport.build(ProblemReport.Kind.USER_REPORT, version(context),
                FilterStore.load(context), entry, entry.evidence, null, note, DecisionLog.recent(context, 20)), true);
    }

    /** Files a report that only checks the path from phone to fixer. */
    static boolean fileTest(Context context) {
        if (!enabled(context)) return false;
        return submit(context, ProblemReport.build(ProblemReport.Kind.TEST, version(context),
                FilterStore.load(context), null, null, null, "Checking that reports arrive.",
                java.util.Collections.<DecisionLog.Entry>emptyList()), true);
    }

    /**
     * Queues a report. Automatic reports of a problem already filed in the last day, or beyond the daily cap, are
     * dropped; reports the user asked for are only rate-limited.
     *
     * @return whether the report was queued
     */
    static boolean submit(Context context, ProblemReport report, boolean byUser) {
        if (!enabled(context)) return false;
        long now = clock.getAsLong();
        synchronized (seen) {
            loadSeen(context);
            if (!byUser) {
                Long filed = seen.get(report.signature);
                if (filed != null && now - filed < SAME_PROBLEM_MS && now >= filed) return false;
            }
            if (!withinDailyCap(context, byUser, now)) return false;
            if (!byUser) {
                seen.put(report.signature, now);
                saveSeen(context, now);
            }
        }
        Context app = context.getApplicationContext();
        DISK.execute(() -> write(app, report, now));
        return true;
    }

    private static boolean withinDailyCap(Context context, boolean byUser, long now) {
        SharedPreferences prefs = prefs(context);
        // The phone's own day, so the allowance renews at local midnight.
        long day = (now + TimeZone.getDefault().getOffset(now)) / 86_400_000L;
        SharedPreferences.Editor editor = prefs.edit();
        if (prefs.getLong(DAY, -1) != day) {
            editor.putLong(DAY, day).putInt(AUTOMATIC_TODAY, 0).putInt(BY_USER_TODAY, 0);
        }
        String key = byUser ? BY_USER_TODAY : AUTOMATIC_TODAY;
        int count = prefs.getLong(DAY, -1) == day ? prefs.getInt(key, 0) : 0;
        if (count >= (byUser ? MAX_BY_USER_PER_DAY : MAX_AUTOMATIC_PER_DAY)) {
            editor.apply();
            return false;
        }
        editor.putInt(key, count + 1).apply();
        return true;
    }

    private static void write(Context context, ProblemReport report, long now) {
        File dir = dir(context);
        if (dir == null || queued(context) >= MAX_QUEUED) return;
        try {
            JSONObject item = new JSONObject().put("title", report.title).put("body", report.body);
            // Time first, so the oldest sends first; the sequence keeps same-millisecond reports apart.
            File file;
            do {
                file = new File(dir, String.format(java.util.Locale.US, "%013d-%06d.json", now,
                        sequence.incrementAndGet() % 1_000_000));
            } while (file.exists());
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(item.toString().getBytes(StandardCharsets.UTF_8));
            }
            schedule(context);
        } catch (IOException | JSONException | RuntimeException error) {
            // On this background thread an escaping exception would take the whole app, screen reader included.
            DiagnosticLog.log(context, "report", "could not queue report: " + error.getClass().getSimpleName());
        }
    }

    /** Asks Android to send queued reports once any network is available. */
    static void schedule(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs == null || queued(context) == 0) return;
        jobs.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, ReportJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .build());
    }

    /**
     * Sends every queued report, oldest first. Called on a background thread.
     *
     * @return true when some reports should be retried later (for example, no connection)
     */
    static boolean drain(Context context) {
        for (File file : files(context)) {
            if (Thread.currentThread().isInterrupted()) return true;
            // Read per report, so turning reports off stops sending at once.
            String token;
            try {
                token = sendingToken(context);
            } catch (IOException offline) {
                return true;
            }
            if (token.isEmpty()) return false;
            boolean connection = token(context).isEmpty();
            try {
                JSONObject item = new JSONObject(read(file));
                int issue = GitHubIssues.create(token, item.getString("title"), item.getString("body"));
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                prefs(context).edit().putInt(LAST_ISSUE, issue).putLong(LAST_SENT_AT, clock.getAsLong())
                        .remove(LAST_ERROR).apply();
                DiagnosticLog.log(context, "report", "filed issue #" + issue);
            } catch (GitHubIssues.Rejected rejected) {
                if (rejected.tokenProblem()) {
                    String said = rejected.code + (rejected.message.isEmpty() ? "" : ": " + rejected.message);
                    prefs(context).edit().putString(LAST_ERROR, connection
                            ? "GitHub refused the report (" + said + "). The GitHub App needs Issues: Read and write, "
                                    + "and adding it is not enough by itself: on github.com, open Settings → "
                                    + "Applications → Installed GitHub Apps, tap Configure next to the app, and "
                                    + "accept its new permissions (GitHub also emails a request to review them). "
                                    + "Reports are kept and sent again each time you open Offer Filter."
                            : "GitHub rejected the token (" + said
                                    + "). Paste a new one; reports are kept until then.").apply();
                    // Permissions approved from now on reach the next attempt's token.
                    if (connection) GitHubConnect.renewSoon(context);
                    return false;
                }
                // An outage or a rate limit passes: keep everything and let Android retry later.
                if (rejected.retryable()) return true;
                // GitHub refused this one report (for example 422 invalid); sending it again cannot help.
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                DiagnosticLog.log(context, "report", "report dropped by GitHub: " + rejected.code);
            } catch (JSONException corrupt) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            } catch (IOException offline) {
                return true;
            }
        }
        return false;
    }

    /**
     * When the app opens: reports GitHub refused (a token or permission problem) are tried again, so fixing the
     * problem on github.com is enough. Nothing is sent while reports are off, and nothing is tried without one waiting.
     */
    static void retryRefused(Context context) {
        if (!enabled(context) || prefs(context).getString(LAST_ERROR, "").isEmpty()) return;
        Context app = context.getApplicationContext();
        DISK.execute(() -> {
            if (queued(app) > 0) schedule(app);
        });
    }

    static int queued(Context context) {
        return files(context).length;
    }

    private static File[] files(Context context) {
        // Listing never creates the folder, so the status line costs no write.
        File dir = new File(context.getApplicationContext().getFilesDir(), DIR);
        File[] files = dir.listFiles((parent, name) -> name.endsWith(".json"));
        if (files == null) return new File[0];
        Arrays.sort(files);
        return files;
    }

    private static File dir(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), DIR);
        return dir.isDirectory() || dir.mkdirs() ? dir : null;
    }

    private static String read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void loadSeen(Context context) {
        if (seenLoaded) return;
        seenLoaded = true;
        try {
            JSONObject stored = new JSONObject(prefs(context).getString(SEEN, "{}"));
            for (Iterator<String> keys = stored.keys(); keys.hasNext(); ) {
                String key = keys.next();
                seen.put(key, stored.getLong(key));
            }
        } catch (JSONException corrupt) {
            seen.clear();
        }
    }

    private static void saveSeen(Context context, long now) {
        JSONObject stored = new JSONObject();
        for (Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Long> item = it.next();
            if (now - item.getValue() >= SAME_PROBLEM_MS) {
                it.remove();
                continue;
            }
            try {
                stored.put(item.getKey(), (long) item.getValue());
            } catch (JSONException impossible) {
                // Keys are strings and values numbers.
            }
        }
        prefs(context).edit().putString(SEEN, stored.toString()).apply();
    }

    /** Waits briefly for queued disk writes; used by tests and before reading the queue directly. */
    static void flush() {
        try {
            DISK.submit(() -> { }).get(2, TimeUnit.SECONDS);
        } catch (Exception interruptedOrTimedOut) {
            if (interruptedOrTimedOut instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    /** Forgets the in-memory copy of filed signatures, as a process restart would. */
    static void forgetCache() {
        synchronized (seen) {
            seen.clear();
            seenLoaded = false;
        }
        appVersion = null;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("reports", Context.MODE_PRIVATE);
    }

    private ReportOutbox() {}
}

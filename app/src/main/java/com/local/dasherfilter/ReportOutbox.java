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
 * They go only through the user's GitHub connection, and are off until the user turns on "Send problem reports" (shown
 * once GitHub is connected): that opt-in is the explicit consent for reports to leave the phone. Turning it off, or
 * disconnecting GitHub, stops them and discards any still waiting. Reports queue on disk and a network-constrained job
 * sends them, so no signal or a restart loses nothing. Older versions also took a pasted report token: one still stored
 * is removed the first time the outbox is used, with the problem reports that were waiting to go with it.
 *
 * <p>The same queue carries diagnostics after a dash ({@link DashDiagnostics}, a further opt-in): an issue and its
 * comments, sent only through the GitHub connection and only while the user has them on; turning them off, or
 * reports through the connection, discards any still waiting.
 */
final class ReportOutbox {
    static final int JOB_ID = 7244;
    /** The same automatic problem is filed at most once a day. */
    private static final long SAME_PROBLEM_MS = 86_400_000L;
    private static final int MAX_AUTOMATIC_PER_DAY = 10;
    private static final int MAX_BY_USER_PER_DAY = 20;
    private static final int MAX_QUEUED = 30;
    private static final String DIR = "report-outbox";
    /** Diagnostics after a dash are queued under names ending in this, so they can be discarded on their own. */
    private static final String DIAGNOSTICS_SUFFIX = "-d.json";
    /** One dash files one issue; this many a day at most, whatever goes wrong. */
    private static final int MAX_DIAGNOSTICS_PER_DAY = 6;

    /** Where older versions kept a pasted report token; removed on sight (see {@link #retireToken}). */
    private static final String RETIRED_TOKEN = "token";
    /** The user chose to send reports through their GitHub connection (the one updates use): "Send problem reports". */
    private static final String VIA_GITHUB = "via_github";
    private static final String DAY = "day";
    private static final String AUTOMATIC_TODAY = "automatic_today";
    private static final String BY_USER_TODAY = "by_user_today";
    private static final String DIAGNOSTICS_TODAY = "diagnostics_today";
    private static final String LAST_DIAGNOSTICS = "last_diagnostics";
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

    /** Reports are on: through the GitHub connection, once the user turned "Send problem reports" on. */
    static boolean enabled(Context context) {
        return throughGitHub(context);
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
     * Sends reports through the GitHub connection, or stops. Stopping discards any report still waiting to send.
     * Connecting GitHub alone never turns reports on.
     */
    static void useGitHub(Context context, boolean on) {
        prefs(context).edit().putBoolean(VIA_GITHUB, on).remove(LAST_ERROR).apply();
        Context app = context.getApplicationContext();
        if (on) {
            schedule(app);
            return;
        }
        // Diagnostics after a dash go only through the connection: they stop too, and what was waiting goes.
        DashDiagnostics.stop(app);
        if (!enabled(app)) discard(app);
    }

    /** GitHub was disconnected: reports that went through it stop, and what was waiting is discarded. */
    static void connectionRemoved(Context context) {
        DashDiagnostics.stop(context);
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
     * A report token pasted in an older version: removed, with any refusal it earned and every problem report waiting
     * to send (each would have gone with that token; the user's opt-in was the token). Reports now go only through the
     * GitHub connection, once "Send problem reports" is on, which this leaves as it was; diagnostics after a dash only
     * ever went through the connection and stay. Runs once, the first time the outbox is used after the update.
     */
    private static void retireToken(Context context, SharedPreferences prefs) {
        boolean pasted;
        synchronized (ReportOutbox.class) {
            if (!prefs.contains(RETIRED_TOKEN)) return;
            Object stored = prefs.getAll().get(RETIRED_TOKEN);
            pasted = stored instanceof String && !((String) stored).trim().isEmpty();
            SharedPreferences.Editor edit = prefs.edit().remove(RETIRED_TOKEN);
            if (pasted) edit.remove(LAST_ERROR);
            edit.commit();
        }
        if (!pasted) return;
        Context app = context.getApplicationContext();
        DISK.execute(() -> {
            for (File file : files(app)) {
                //noinspection ResultOfMethodCallIgnored
                if (!isDiagnostics(file)) file.delete();
            }
        });
        DiagnosticLog.log(app, "report", "the report token was removed; problem reports waiting to go with it were "
                + "discarded; reports go only through the GitHub connection, once Send problem reports is on");
    }

    /** Unsent diagnostics after a dash are deleted; problem reports stay. */
    static void discardDiagnostics(Context context) {
        Context app = context.getApplicationContext();
        DISK.execute(() -> {
            for (File file : files(app)) {
                //noinspection ResultOfMethodCallIgnored
                if (isDiagnostics(file)) file.delete();
            }
        });
    }

    private static boolean isDiagnostics(File file) {
        return file.getName().endsWith(DIAGNOSTICS_SUFFIX);
    }

    /**
     * The GitHub connection's token, for diagnostics after a dash only while they are on (refreshed if due;
     * blocking); "" when they are off.
     */
    private static String connectionToken(Context context) throws IOException {
        if (!DashDiagnostics.on(context)) return "";
        String connection = GitHubConnect.token(context);
        return connection == null ? "" : connection;
    }

    /**
     * The token a problem report is sent with: the GitHub connection's while reports go through it (refreshed if due;
     * blocking), else "".
     *
     * @throws IOException when GitHub could not be reached to refresh the connection
     */
    private static String sendingToken(Context context) throws IOException {
        if (!throughGitHub(context)) return "";
        String connection = GitHubConnect.token(context);
        return connection == null ? "" : connection;
    }

    /** "On · last report #12 · 2 waiting to send", what GitHub refused and what to change, or "Off". */
    static String status(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!enabled(context)) return "Off";
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
        return withinDailyCap(context, byUser ? BY_USER_TODAY : AUTOMATIC_TODAY,
                byUser ? MAX_BY_USER_PER_DAY : MAX_AUTOMATIC_PER_DAY, now);
    }

    private static boolean withinDailyCap(Context context, String key, int max, long now) {
        SharedPreferences prefs = prefs(context);
        // The phone's own day, so the allowance renews at local midnight.
        long day = (now + TimeZone.getDefault().getOffset(now)) / 86_400_000L;
        SharedPreferences.Editor editor = prefs.edit();
        if (prefs.getLong(DAY, -1) != day) {
            editor.putLong(DAY, day).putInt(AUTOMATIC_TODAY, 0).putInt(BY_USER_TODAY, 0).putInt(DIAGNOSTICS_TODAY, 0);
        }
        int count = prefs.getLong(DAY, -1) == day ? prefs.getInt(key, 0) : 0;
        if (count >= max) {
            editor.apply();
            return false;
        }
        editor.putInt(key, count + 1).apply();
        return true;
    }

    /**
     * Queues one dash's diagnostics: an issue with the first part and the {@code diagnostics} label, and each later
     * part as a comment on it. Only while diagnostics after a dash are on.
     *
     * @return whether they were queued
     */
    static boolean submitDiagnostics(Context context, String title, java.util.List<String> parts) {
        if (!DashDiagnostics.on(context) || parts.isEmpty()) return false;
        long now = clock.getAsLong();
        if (!withinDailyCap(context, DIAGNOSTICS_TODAY, MAX_DIAGNOSTICS_PER_DAY, now)) return false;
        JSONObject item;
        try {
            org.json.JSONArray comments = new org.json.JSONArray();
            for (String part : parts.subList(1, parts.size())) comments.put(part);
            item = new JSONObject().put("title", title).put("body", parts.get(0))
                    .put("labels", new org.json.JSONArray().put(DashDiagnostics.LABEL)).put("comments", comments);
        } catch (JSONException impossible) {
            return false;
        }
        Context app = context.getApplicationContext();
        DISK.execute(() -> write(app, item, now, DIAGNOSTICS_SUFFIX));
        return true;
    }

    private static void write(Context context, ProblemReport report, long now) {
        try {
            write(context, new JSONObject().put("title", report.title).put("body", report.body), now, ".json");
        } catch (JSONException impossible) {
            // Only strings are put.
        }
    }

    private static void write(Context context, JSONObject item, long now, String suffix) {
        File dir = dir(context);
        if (dir == null || queued(context) >= MAX_QUEUED) return;
        try {
            // Time first, so the oldest sends first; the sequence keeps same-millisecond reports apart.
            File file;
            do {
                file = new File(dir, String.format(java.util.Locale.US, "%013d-%06d", now,
                        sequence.incrementAndGet() % 1_000_000) + suffix);
            } while (file.exists());
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(item.toString().getBytes(StandardCharsets.UTF_8));
            }
            schedule(context);
        } catch (IOException | RuntimeException error) {
            // On this background thread an escaping exception would take the whole app, screen reader included.
            DiagnosticLog.log(context, "report", "could not queue report: " + error.getClass().getSimpleName());
        }
    }

    /** Replaces a queued item with what is left of it to send, whole or not at all. */
    private static void rewrite(File file, JSONObject item) throws IOException {
        File next = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(next)) {
            out.write(item.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!next.renameTo(file)) throw new IOException("could not replace " + file.getName());
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
            boolean diagnostics = isDiagnostics(file);
            // Read per report, so turning reports off stops sending at once.
            String token;
            try {
                token = diagnostics ? connectionToken(context) : sendingToken(context);
            } catch (IOException offline) {
                return true;
            }
            if (token.isEmpty()) {
                if (!diagnostics) return false;
                // Diagnostics go only through the GitHub connection, while the user has them on: never otherwise.
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                continue;
            }
            try {
                JSONObject item = new JSONObject(read(file));
                if (diagnostics) {
                    int issue = sendDiagnostics(token, file, item);
                    prefs(context).edit().putInt(LAST_DIAGNOSTICS, issue).remove(LAST_ERROR).apply();
                    DiagnosticLog.log(context, "report", "filed diagnostics issue #" + issue);
                    continue;
                }
                int issue = GitHubIssues.create(token, item.getString("title"), item.getString("body"));
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                prefs(context).edit().putInt(LAST_ISSUE, issue).putLong(LAST_SENT_AT, clock.getAsLong())
                        .remove(LAST_ERROR).apply();
                DiagnosticLog.log(context, "report", "filed issue #" + issue);
            } catch (GitHubIssues.Rejected rejected) {
                if (rejected.tokenProblem()) {
                    String said = rejected.code + (rejected.message.isEmpty() ? "" : ": " + rejected.message);
                    prefs(context).edit().putString(LAST_ERROR, "GitHub refused the report (" + said + "). The "
                            + "GitHub App needs Issues: Read and write, and adding it is not enough by itself: on "
                            + "github.com, open Settings → Applications → Installed GitHub Apps, tap Configure next to "
                            + "your GitHub App, and accept its new permissions (GitHub also emails a request to review "
                            + "them). Reports are kept and sent again each time you open " + AppName.NAME + ".")
                            .apply();
                    // Permissions approved from now on reach the next attempt's token.
                    GitHubConnect.renewSoon(context);
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
     * One dash's diagnostics: the issue once (its number is kept in the queued file at once, so a retry never files
     * it twice), then each part still left as a comment, in order, each crossed off as it is sent.
     *
     * @return the issue's number
     */
    private static int sendDiagnostics(String token, File file, JSONObject item) throws IOException, JSONException {
        int issue = item.optInt("issue", 0);
        if (issue <= 0) {
            java.util.List<String> labels = new java.util.ArrayList<>();
            org.json.JSONArray named = item.optJSONArray("labels");
            for (int i = 0; named != null && i < named.length(); i++) labels.add(named.getString(i));
            String title = item.getString("title");
            String body = item.getString("body");
            try {
                issue = GitHubIssues.create(token, title, body, labels);
            } catch (GitHubIssues.Rejected rejected) {
                // A label GitHub will not take must not cost the dash its diagnostics: filed again without it.
                if (rejected.code != 422 || labels.isEmpty()) throw rejected;
                issue = GitHubIssues.create(token, title, body, null);
            }
            item.put("issue", issue);
            rewrite(file, item);
        }
        org.json.JSONArray comments = item.optJSONArray("comments");
        while (comments != null && comments.length() > 0) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("stopped");
            GitHubIssues.comment(token, issue, comments.getString(0));
            comments.remove(0);
            rewrite(file, item);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        return issue;
    }

    /** The issue the last diagnostics after a dash went to; 0 before the first. */
    static int lastDiagnostics(Context context) {
        return prefs(context).getInt(LAST_DIAGNOSTICS, 0);
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
        SharedPreferences prefs = context.getSharedPreferences("reports", Context.MODE_PRIVATE);
        // A token from an older version goes the first time the outbox is used (a lookup in memory after that).
        if (prefs.contains(RETIRED_TOKEN)) retireToken(context, prefs);
        return prefs;
    }

    private ReportOutbox() {}
}

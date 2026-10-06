package com.local.dasherfilter;

import android.app.job.JobScheduler;
import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * What the retired GitHub connection and its report queues left on a phone, removed once: the connection's settings
 * (its tokens, account name and sign-in code among them), why it last ended, the report queue's state (with the report
 * token older versions took), the diagnostics-after-each-dash switch, the queued reports themselves (half-written
 * {@code .tmp} files too) and the two jobs that sent and checked them. Built from literals only, so it never needs,
 * or brings back, the classes that wrote them.
 *
 * <p>Permanent, like {@link Updater#retireSwitch}: a phone can skip versions, so every later version runs it once.
 * Idempotent. A durable done flag is written only after every step succeeded; a failed step leaves it unset and the
 * next trigger (an update arriving, the app opening, the screen reader or the notification listener connecting) tries
 * again. A fresh install has nothing to remove and gets no legacy settings file from it. One generic log line counts
 * what went, never a token, an account name or a code. Never on the main thread ({@link #cleanUpSoon}).
 */
final class LegacyReportingCleanup {
    /** The retired stack's settings files: the connection, its last end, the report queue, diagnostics after a dash. */
    static final String[] PREFS = {"github", "github_history", "reports", "dash_diagnostics"};
    /** The retired report queue's folder under the app's files. */
    static final String OUTBOX = "report-outbox";
    /** The retired report sender's persisted job, and the retired quiet-dash check's. */
    static final int[] JOBS = {7244, 7245};
    /** Where the done flag is kept: a settings file of this cleanup's own, never one of {@link #PREFS}. */
    static final String STATE = "legacy_reporting_cleanup";
    static final String DONE = "done_v1";

    private static final Object LOCK = new Object();
    /** The application the done flag was last read or written for; a new process (or test) reads it again. */
    private static volatile Object doneFor;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "legacy-cleanup");
        thread.setDaemon(true);
        return thread;
    });
    /** Deletes one file; tests stand in a refusal to see a failed run retried. */
    static volatile Predicate<File> delete = File::delete;

    /**
     * Removes whatever is left, at most once per installation. On the calling thread (it reads and deletes files).
     *
     * @return whether nothing is left (now or from an earlier run); false keeps the flag unset for a retry
     */
    static boolean run(Context context) {
        Context app = context.getApplicationContext();
        if (doneFor == app) return true;
        synchronized (LOCK) {
            if (doneFor == app) return true;
            SharedPreferences state = app.getSharedPreferences(STATE, Context.MODE_PRIVATE);
            if (state.getBoolean(DONE, false)) {
                doneFor = app;
                return true;
            }
            boolean ok = true;
            int settings = 0;
            for (String name : PREFS) {
                boolean present = prefsPresent(app, name);
                if (!deletePrefs(app, name)) ok = false;
                else if (present) settings++;
            }
            int queued = queuedFiles(app);
            if (!deleteOutbox(app)) ok = false;
            int jobs = 0;
            try {
                JobScheduler scheduler = app.getSystemService(JobScheduler.class);
                if (scheduler != null) {
                    for (int id : JOBS) {
                        if (scheduler.getPendingJob(id) != null) jobs++;
                        scheduler.cancel(id);
                    }
                }
            } catch (RuntimeException refused) {
                ok = false;
            }
            // The retired repository channel's name for where an update was last advertised.
            SharedPreferences updates = Updater.prefs(app);
            if ("GitHub".equals(updates.getString("advertised_via", null))
                    && !updates.edit().remove("advertised_via").commit()) ok = false;
            if (!ok) return false;
            if (!state.edit().putBoolean(DONE, true).commit()) {
                // SharedPreferences keeps the value in memory even when its disk write failed: don't trust it.
                state.edit().remove(DONE).apply();
                return false;
            }
            doneFor = app;
            if (settings + queued + jobs > 0) {
                DiagnosticLog.log(app, "privacy", "retired GitHub reporting removed: " + settings + " settings "
                        + (settings == 1 ? "file" : "files") + ", " + queued + " unsent "
                        + (queued == 1 ? "report file" : "report files") + ", " + jobs + " scheduled "
                        + (jobs == 1 ? "job" : "jobs"));
            }
            return true;
        }
    }

    /** {@link #run} on a worker thread, unless it already ran in this process: cheap enough for every trigger. */
    static void cleanUpSoon(Context context) {
        Context app = context.getApplicationContext();
        if (doneFor == app) return;
        try {
            WORKER.execute(() -> {
                try {
                    run(app);
                } catch (RuntimeException unexpected) {
                    // An escaping exception would take the whole app; the next trigger tries again.
                }
            });
        } catch (RejectedExecutionException shutDown) {
            // The next trigger tries again.
        }
    }

    /** Whether the retired report queue's folder still holds anything (or cannot be listed). */
    static boolean outboxPresent(Context context) {
        File folder = new File(context.getApplicationContext().getFilesDir(), OUTBOX);
        if (!folder.exists()) return false;
        String[] names = folder.list();
        return names == null || names.length > 0 || folder.isFile();
    }

    /**
     * Deletes the retired report queue's folder and everything in it, half-written files included.
     *
     * @return whether nothing of it is left; false when a file could not be deleted or the folder listed
     */
    static boolean deleteOutbox(Context context) {
        File folder = new File(context.getApplicationContext().getFilesDir(), OUTBOX);
        if (!folder.exists()) return true;
        // Something there that cannot be listed (even a stray file in the folder's place) may hold reports: fail
        // closed, as the one-time privacy cleanup always has, and try again next time.
        if (folder.listFiles() == null) return false;
        return deleteTree(folder);
    }

    private static boolean deleteTree(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            // A folder that cannot be listed may still hold reports: fail closed.
            if (children == null) return false;
            boolean ok = true;
            for (File child : children) ok &= deleteTree(child);
            if (!ok) return false;
        }
        return delete.test(file) || !file.exists();
    }

    private static int queuedFiles(Context app) {
        File[] files = new File(app.getFilesDir(), OUTBOX).listFiles(File::isFile);
        return files == null ? 0 : files.length;
    }

    /** The settings file Android keeps for {@code name}, or its backup copy, is on disk. */
    private static boolean prefsPresent(Context app, String name) {
        File file = prefsFile(app, name);
        return file.exists() || new File(file.getPath() + ".bak").exists();
    }

    static File prefsFile(Context context, String name) {
        return new File(new File(context.getApplicationContext().getDataDir(), "shared_prefs"), name + ".xml");
    }

    /**
     * Deletes one settings file (and Android's in-memory copy of it). Should Android fail to, it is emptied instead:
     * what remains then holds nothing.
     */
    private static boolean deletePrefs(Context app, String name) {
        try {
            if (app.deleteSharedPreferences(name)) return true;
        } catch (RuntimeException refused) {
            // Emptied below instead.
        }
        try {
            boolean emptied = app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
            return app.deleteSharedPreferences(name) || emptied;
        } catch (RuntimeException refused) {
            return false;
        }
    }

    /** Waits for a cleanup already queued; for tests. */
    static void flush() {
        try {
            WORKER.submit(() -> { }).get(5, TimeUnit.SECONDS);
        } catch (Exception interruptedOrTimedOut) {
            if (interruptedOrTimedOut instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    /** As a new process would: the done flag is read from disk again. For tests. */
    static void forgetCache() {
        doneFor = null;
    }

    private LegacyReportingCleanup() {}
}

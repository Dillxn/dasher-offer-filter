package com.local.dasherfilter;

import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.Context;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serial report sends and quiet-dash checks; rescheduling an active Android job would interrupt its own send. */
public final class ReportJobService extends JobService {
    private static final ExecutorService SENDER = Executors.newSingleThreadExecutor();
    /** Includes jobs waiting on the executor, so scheduling cannot cancel one before its body starts. */
    private static final Map<Integer, Work> RUNNING = new HashMap<>();
    private static final Map<Integer, Runnable> AFTER = new HashMap<>();

    @Override public boolean onStartJob(JobParameters parameters) {
        int id = parameters.getJobId();
        synchronized (RUNNING) {
            if (RUNNING.containsKey(id)) {
                // Android may retry a stopped job before an interrupted network request has unwound.
                AFTER.put(id, () -> {
                    if (id == DashDiagnostics.JOB_ID) DashDiagnostics.checkSoon(this);
                    else ReportOutbox.schedule(this);
                });
                return false;
            }
            Work work = new Work(parameters);
            RUNNING.put(id, work);
            SENDER.execute(work);
        }
        return true;
    }

    private final class Work implements Runnable {
        final JobParameters parameters;
        volatile boolean stopped;
        Thread thread;

        Work(JobParameters parameters) { this.parameters = parameters; }

        @Override public void run() {
            int id = parameters.getJobId();
            boolean check = id == DashDiagnostics.JOB_ID;
            boolean retry = !check;
            try {
                synchronized (RUNNING) {
                    if (stopped) return;
                    thread = Thread.currentThread();
                }
                if (check) DashDiagnostics.check(ReportJobService.this);
                else retry = ReportOutbox.drain(ReportJobService.this, () -> stopped);
            } catch (RuntimeException unexpected) {
                DiagnosticLog.log(ReportJobService.this, check ? "diagnostics" : "report",
                        (check ? "quiet check failed: " : "send failed: ") + unexpected.getClass().getSimpleName());
            } finally {
                Runnable after;
                synchronized (RUNNING) {
                    // Finish Android's job before declaring it idle to any scheduling caller.
                    if (!stopped) jobFinished(parameters, retry);
                    thread = null;
                    RUNNING.remove(id);
                    after = AFTER.remove(id);
                }
                // Android schedules normal retries with backoff. A stopped job's deferred work still matters.
                if (after != null && (!retry || stopped)) after.run();
                Thread.interrupted(); // don't carry a stopped job's interruption into the next executor task
            }
        }
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        int id = parameters.getJobId();
        synchronized (RUNNING) {
            Work work = RUNNING.get(id);
            if (work != null && work.parameters == parameters) {
                work.stopped = true;
                // Let the in-flight request finish and keep its receipt. The drain stops before its next request.
                // Don't cancel the queued runnable: its finally must release RUNNING even if it never started.
            }
        }
        return id == DashDiagnostics.JOB_ID || ReportOutbox.queued(this) > 0;
    }

    static void scheduleWhenIdle(int id, Runnable schedule) {
        synchronized (RUNNING) {
            if (RUNNING.containsKey(id)) AFTER.put(id, schedule);
            else schedule.run();
        }
    }

    /** A running job observes opt-out before its next part; cancelling it mid-POST could lose the receipt. */
    static void cancelWhenIdle(Context context, int id) {
        synchronized (RUNNING) {
            AFTER.remove(id);
            if (RUNNING.containsKey(id)) return;
            JobScheduler jobs = context.getSystemService(JobScheduler.class);
            if (jobs != null) jobs.cancel(id);
        }
    }

    static boolean running(int id) {
        synchronized (RUNNING) { return RUNNING.containsKey(id); }
    }
}

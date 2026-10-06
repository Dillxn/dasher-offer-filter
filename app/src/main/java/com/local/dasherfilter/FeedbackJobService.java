package com.local.dasherfilter;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Sends the feedback outbox ({@link FeedbackOutbox}) once any network is available, again after a failure with
 * backoff, and across a restart (the job is persisted). It is scheduled as a submission is written, before its first
 * send, so a crash or a reboot mid-send loses nothing. A send under way is never rescheduled (Android would stop it
 * mid-request); a schedule asked for meanwhile follows when it ends, also when Android stopped it. Android stopping the
 * job lets the request under way finish and starts no other; Android then retries with exponential backoff.
 */
public final class FeedbackJobService extends JobService {
    private static final Object LOCK = new Object();
    private static boolean running;
    /** A schedule asked for while the job ran: its delay, or -1 for none. */
    private static long after = -1;
    private volatile Work current;

    @Override public boolean onStartJob(JobParameters parameters) {
        synchronized (LOCK) {
            if (running) {
                // The send under way goes on; this start is taken as a schedule for right after it.
                after = 0;
                return false;
            }
            running = true;
        }
        Work work = new Work(parameters);
        current = work;
        FeedbackOutbox.runOnSender(work);
        return true;
    }

    private final class Work implements Runnable {
        final JobParameters parameters;
        volatile boolean stopped;

        Work(JobParameters parameters) {
            this.parameters = parameters;
        }

        @Override public void run() {
            Context app = getApplicationContext();
            FeedbackOutbox.Result result = FeedbackOutbox.Result.DONE;
            try {
                if (!stopped) result = FeedbackOutbox.drain(app, () -> stopped);
            } catch (RuntimeException unexpected) {
                DiagnosticLog.log(app, "feedback", "send failed: " + unexpected.getClass().getSimpleName());
                result = FeedbackOutbox.Result.retry(FeedbackOutbox.FIRST_RETRY_MS);
            } finally {
                long next;
                boolean wasStopped = stopped;
                synchronized (LOCK) {
                    if (!wasStopped) jobFinished(parameters, false);
                    running = false;
                    next = after;
                    after = -1;
                }
                if (FeedbackOutbox.pending(app) > 0) {
                    // A stopped job is retried by Android (onStopJob asked for it); otherwise the next try is ours. A
                    // schedule asked for while it ran (a new submission, or a start Android made meanwhile) follows
                    // either way: Android's retry of a stopped job may already have been spent on that start.
                    long delay = !wasStopped && result.retry ? result.delayMs : -1;
                    if (next >= 0) delay = delay < 0 ? next : Math.min(delay, next);
                    if (delay >= 0) schedule(app, delay);
                }
            }
        }
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        Work work = current;
        if (work != null && work.parameters == parameters) work.stopped = true;
        // On the main thread: the count kept in memory, never a disk read (not yet counted counts as waiting).
        return FeedbackOutbox.pendingKnown(this) != 0;
    }

    /** Sends the outbox once a network is available and {@code delayMs} has passed (never while a send runs). */
    static void schedule(Context context, long delayMs) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (running) {
                after = after < 0 ? Math.max(0, delayMs) : Math.min(after, Math.max(0, delayMs));
                return;
            }
            JobScheduler jobs = app.getSystemService(JobScheduler.class);
            if (jobs == null) return;
            JobInfo.Builder job = new JobInfo.Builder(FeedbackOutbox.JOB_ID,
                    new ComponentName(app, FeedbackJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setBackoffCriteria(FeedbackOutbox.FIRST_RETRY_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .setPersisted(true);
            if (delayMs > 0) job.setMinimumLatency(delayMs);
            try {
                jobs.schedule(job.build());
            } catch (RuntimeException refused) {
                // Android limits how many jobs an app may schedule; the app opening sends what waits anyway.
            }
        }
    }

    /**
     * Nothing waits any more: the job that protected a first send goes, unless it is running (it then finds none) or a
     * submission was queued meanwhile (counted under this lock, so its own schedule always comes after this).
     */
    static void cancelIfIdle(Context context) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (running || after >= 0 || FeedbackOutbox.pending(app) > 0) return;
            JobScheduler jobs = app.getSystemService(JobScheduler.class);
            if (jobs == null) return;
            try {
                jobs.cancel(FeedbackOutbox.JOB_ID);
            } catch (RuntimeException refused) {
                // Only a wake-up that finds nothing to send.
            }
        }
    }

    /** For tests: as a new process would. */
    static void forgetCache() {
        synchronized (LOCK) {
            running = false;
            after = -1;
        }
    }
}

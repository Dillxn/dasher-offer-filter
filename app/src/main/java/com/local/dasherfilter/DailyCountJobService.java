package com.local.dasherfilter;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Sends the anonymous daily count ({@link DailyCount}) about once a day on any network, and again after a count that
 * is to be retried, with backoff and across a restart (the job is persisted). It is one job at a time under
 * {@link #JOB_ID}: after a run that finished the day it is scheduled again for the next UTC day, at a random time in
 * its first hours; a count to be retried is handed back to Android ({@code jobFinished(…, true)}), which runs it again
 * with exponential backoff from {@link DailyCount#FIRST_RETRY_MS}, and the triggers leave a retry that waits to it
 * ({@link #waiting}), so the backoff is never set back to its start. It is kept scheduled only while the switch is on
 * and the current notice is accepted: the switch turned off cancels it, and a run that finds either missing schedules
 * no other (accepting the notice, or turning the switch on, starts it again).
 *
 * <p>The work runs on DailyCount's own thread, never the main one. A run is never rescheduled while it runs (Android
 * would stop it mid-request): a schedule asked for meanwhile is left to the run, which comes after the work that asked
 * on that same thread and decides the next one itself. Android stopping the job lets a request under way finish and
 * starts no other; Android then runs it again, which finds the day's count finished or not (a start Android makes
 * before the stopped run has ended is taken as a schedule for when it ends).
 */
public final class DailyCountJobService extends JobService {
    /** Not 7241, 7243 (updates), 7244, 7245 (retired reporting) or 7246 (feedback). */
    static final int JOB_ID = 7247;
    private static final Object LOCK = new Object();
    private static boolean running;
    /** Android started the job again while a stopped run had not ended: that run schedules the next itself. */
    private static boolean startedMeanwhile;
    private volatile Work current;

    @Override public boolean onStartJob(JobParameters parameters) {
        synchronized (LOCK) {
            if (running) {
                // Android's retry of a stopped run that has not ended: taken as a schedule for when it ends.
                startedMeanwhile = true;
                return false;
            }
            running = true;
        }
        Work work = new Work(parameters);
        current = work;
        DailyCount.runOnWorker(work);
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
            DailyCount.Outcome outcome = DailyCount.Outcome.NONE;
            try {
                if (!stopped) outcome = DailyCount.sendIfDue(app);
            } catch (RuntimeException unexpected) {
                DiagnosticLog.log(app, DailyCount.LOG, "daily count failed: " + unexpected.getClass().getSimpleName());
                outcome = DailyCount.Outcome.RETRY;
            } finally {
                boolean wasStopped = stopped;
                boolean retry = outcome == DailyCount.Outcome.RETRY;
                boolean again;
                synchronized (LOCK) {
                    if (!wasStopped) jobFinished(parameters, retry);
                    running = false;
                    again = startedMeanwhile;
                    startedMeanwhile = false;
                }
                // A retry is Android's (asked for just now), and so is a stopped run's (onStopJob asked for it), unless
                // Android already spent that on a start taken while this ran; otherwise the next run is this one's to
                // schedule, while the switch is on and the notice accepted: the next day's once today's is finished.
                if ((again || (!retry && !wasStopped)) && Consent.accepted(app) && DailyCount.on(app)) {
                    schedule(app, DailyCount.finishedToday(app) ? DailyCount.nextDayDelay(app)
                            : DailyCount.FIRST_RETRY_MS);
                }
            }
        }
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        Work work = current;
        if (work != null && work.parameters == parameters) work.stopped = true;
        // On the main thread, so no disk read: always run again; that run finds the day's count finished or not.
        return true;
    }

    /** Runs the job once a network is available and {@code delayMs} has passed, in place of any waiting run. */
    static void schedule(Context context, long delayMs) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (running) return;
            JobScheduler jobs = app.getSystemService(JobScheduler.class);
            if (jobs == null) return;
            JobInfo.Builder job = new JobInfo.Builder(JOB_ID, new ComponentName(app, DailyCountJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setBackoffCriteria(DailyCount.FIRST_RETRY_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .setPersisted(true);
            if (delayMs > 0) job.setMinimumLatency(delayMs);
            try {
                jobs.schedule(job.build());
            } catch (RuntimeException refused) {
                // Android limits how many jobs an app may schedule; the app opening tries again anyway.
            }
        }
    }

    /** As {@link #schedule}, only when no run waits already (a trigger leaves a waiting run, or a retry, as it is). */
    static void scheduleIfNone(Context context, long delayMs) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (running) return;
            JobScheduler jobs = app.getSystemService(JobScheduler.class);
            if (jobs == null) return;
            try {
                if (jobs.getPendingJob(JOB_ID) != null) return;
            } catch (RuntimeException unknown) {
                return;
            }
        }
        schedule(app, delayMs);
    }

    /**
     * Whether a run waits (a retry with Android's backoff, or the next day's) or is under way: a trigger then leaves
     * today's retry to it. Not known counts as none, so the trigger tries itself.
     */
    static boolean waiting(Context context) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (running) return true;
            JobScheduler jobs = app.getSystemService(JobScheduler.class);
            if (jobs == null) return false;
            try {
                return jobs.getPendingJob(JOB_ID) != null;
            } catch (RuntimeException unknown) {
                return false;
            }
        }
    }

    /** The switch turned off, or found off: no run waits any more, and one under way is stopped by Android. */
    static void cancel(Context context) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            JobScheduler jobs = app.getSystemService(JobScheduler.class);
            if (jobs == null) return;
            try {
                jobs.cancel(JOB_ID);
            } catch (RuntimeException refused) {
                // Only a wake-up that finds the switch off.
            }
        }
    }

    /** For tests: as a new process would. */
    static void forgetCache() {
        synchronized (LOCK) {
            running = false;
            startedMeanwhile = false;
        }
    }
}

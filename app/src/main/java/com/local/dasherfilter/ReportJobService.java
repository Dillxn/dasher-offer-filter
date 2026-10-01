package com.local.dasherfilter;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Sends queued problem reports when Android grants network; asks to be retried when sending failed. Also runs the
 * check that a dash went quiet ({@link DashDiagnostics#JOB_ID}), which only queues; sending is the other job's.
 */
public final class ReportJobService extends JobService {
    private static final ExecutorService SENDER = Executors.newSingleThreadExecutor();
    private Future<?> sending;
    private Future<?> checking;

    @Override public boolean onStartJob(JobParameters parameters) {
        if (parameters.getJobId() == DashDiagnostics.JOB_ID) {
            checking = SENDER.submit(() -> {
                try {
                    DashDiagnostics.check(this);
                } catch (RuntimeException unexpected) {
                    DiagnosticLog.log(this, "diagnostics", "quiet check failed: "
                            + unexpected.getClass().getSimpleName());
                } finally {
                    jobFinished(parameters, false);
                }
            });
            return true;
        }
        sending = SENDER.submit(() -> {
            boolean retry = true;
            try {
                retry = ReportOutbox.drain(this);
            } catch (RuntimeException unexpected) {
                DiagnosticLog.log(this, "report", "send failed: " + unexpected.getClass().getSimpleName());
            } finally {
                jobFinished(parameters, retry);
            }
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        boolean check = parameters.getJobId() == DashDiagnostics.JOB_ID;
        Future<?> stopping = check ? checking : sending;
        if (stopping != null) stopping.cancel(true);
        return check || ReportOutbox.queued(this) > 0;
    }
}

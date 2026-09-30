package com.local.dasherfilter;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Sends queued problem reports when Android grants network; asks to be retried when sending failed. */
public final class ReportJobService extends JobService {
    private static final ExecutorService SENDER = Executors.newSingleThreadExecutor();
    private Future<?> running;

    @Override public boolean onStartJob(JobParameters parameters) {
        running = SENDER.submit(() -> {
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
        if (running != null) running.cancel(true);
        return ReportOutbox.queued(this) > 0;
    }
}

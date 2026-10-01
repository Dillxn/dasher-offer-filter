package com.local.dasherfilter;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;

/** Runs update checks for the periodic and retry jobs. A stopped job's late callback cannot finish its successor. */
public final class UpdateJobService extends JobService {
    private static final class Run {
        final JobParameters parameters;
        Future<?> task;

        Run(JobParameters parameters) {
            this.parameters = parameters;
        }
    }

    /** Main-thread only: job callbacks and the check's completion callback all run on the main looper. */
    private final Map<Integer, Run> running = new HashMap<>();

    @Override public boolean onStartJob(JobParameters parameters) {
        int jobId = parameters.getJobId();
        Run run = new Run(parameters);
        running.put(jobId, run);
        run.task = Updater.check(this, UpdateCadence.forJob(jobId, Updater.RETRY_JOB_ID), () -> {
            if (running.get(jobId) == run) {
                running.remove(jobId);
                jobFinished(parameters, false);
            }
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        Run run = running.get(parameters.getJobId());
        if (run != null && run.parameters == parameters) {
            running.remove(parameters.getJobId());
            if (run.task != null) run.task.cancel(true);
        }
        return Updater.enabled(this);
    }
}

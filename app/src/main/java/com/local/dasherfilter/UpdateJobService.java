package com.local.dasherfilter;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;

/** A stopped job's late callback must not finish a replacement job with the same ID. */
public final class UpdateJobService extends JobService {
    private static final class Run {
        final JobParameters parameters; Future<?> task;
        Run(JobParameters parameters) { this.parameters = parameters; }
    }
    private final Map<Integer, Run> running = new HashMap<>();
    @Override public boolean onStartJob(JobParameters parameters) {
        Run run = new Run(parameters); running.put(parameters.getJobId(), run);
        run.task = Updater.check(this, false, () -> {
            if (running.get(parameters.getJobId()) == run) { running.remove(parameters.getJobId()); jobFinished(parameters, false); }
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        Run run = running.get(parameters.getJobId());
        if (run != null && run.parameters == parameters) {
            running.remove(parameters.getJobId()); if (run.task != null) run.task.cancel(true);
        }
        return Updater.enabled(this);
    }
}

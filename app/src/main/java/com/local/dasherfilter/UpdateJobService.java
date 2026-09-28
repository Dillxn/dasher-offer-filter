package com.local.dasherfilter;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.Future;

public final class UpdateJobService extends JobService {
    private volatile boolean stopped;
    private Future<?> task;

    @Override public boolean onStartJob(JobParameters params) {
        stopped = false;
        task = Updater.check(this, false, () -> { if (!stopped) jobFinished(params, false); });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        stopped = true;
        if (task != null) task.cancel(true);
        return true;
    }
}

package com.local.dasherfilter;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;

public final class UpdateJobService extends JobService {
    private final Map<Integer, Future<?>> running = new HashMap<>();
    @Override public boolean onStartJob(JobParameters p) {
        Future<?> task = Updater.check(this, false, () -> { if (running.remove(p.getJobId()) != null) jobFinished(p, false); });
        running.put(p.getJobId(), task); return true;
    }
    @Override public boolean onStopJob(JobParameters p) {
        Future<?> task = running.remove(p.getJobId()); if (task != null) task.cancel(true); return Updater.enabled(this);
    }
}

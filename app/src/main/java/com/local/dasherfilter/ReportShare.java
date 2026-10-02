package com.local.dasherfilter;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Builds one explicitly requested share off the UI thread, discarding it when its screen leaves. */
final class ReportShare {
    static final class Report {
        final String subject;
        final String body;

        Report(String subject, String body) {
            this.subject = subject;
            this.body = body;
        }
    }

    interface Builder {
        Report build(Context context);
    }

    static final Builder DIAGNOSTICS = context -> new Report(DiagnosticLog.reportSubject(context),
            DiagnosticLog.report(context));
    /** Replaced only by tests that hold a build while the activity pauses or the user taps twice. */
    static volatile Builder builder = DIAGNOSTICS;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private long generation;
    private Consumer<Report> completion;

    synchronized boolean start(Context context, Consumer<Report> ready) {
        if (completion != null) return false;
        Context app = context.getApplicationContext();
        long request = ++generation;
        completion = ready;
        Builder build = builder;
        WORK.execute(() -> {
            synchronized (ReportShare.this) {
                if (request != generation) return;
            }
            Report report;
            try {
                report = build.build(app);
            } catch (RuntimeException failure) {
                report = null;
            }
            Report result = report;
            main.post(() -> finish(request, result));
        });
        return true;
    }

    private void finish(long request, Report report) {
        Consumer<Report> ready;
        synchronized (this) {
            if (request != generation) return;
            ready = completion;
            completion = null;
        }
        if (ready != null) ready.accept(report);
    }

    synchronized void cancel() {
        generation++;
        // A blocked diagnostic writer may finish later, but keeps no Activity callback and opens nothing.
        completion = null;
    }
}

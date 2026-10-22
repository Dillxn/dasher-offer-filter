package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The offer timing study's scope and bounded worker. No network calls, raw labels, or reads of prior waiting history.
 * It runs only while Share anonymous diagnostics is on (0.6.34; its own choice before, retired once by
 * {@link #retireOwnChoice}), and every change of that switch starts it afresh ({@link #clear}).
 */
final class DispatchStudyStore {
    static final String PREFS = "dispatch-study";
    static final String FILE = "dispatch-study.json";
    static final int MAX_FILE_BYTES = 60_000;
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), r -> { Thread t = new Thread(r, "offer-timing-study"); t.setDaemon(true); return t; });
    private static volatile boolean overflow;
    private static DispatchStudy model;
    private static Object owner;
    private static long modelEpoch = -1, lastSave;
    private static SharedPreferences watchedRules;
    private static SharedPreferences.OnSharedPreferenceChangeListener rulesListener;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static long epoch(Context c) { return prefs(c).getLong("epoch", 0); }
    static boolean enabled(Context c) {
        boolean on = Consent.accepted(c) && Feedback.diagnosticsOn(c);
        if (on) watchRules(c.getApplicationContext());
        return on;
    }
    static boolean allowed(Context c, long expected) { return enabled(c) && epoch(c) == expected; }

    private static synchronized void watchRules(Context app) {
        SharedPreferences p = app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE);
        if (watchedRules == p) return;
        if (watchedRules != null) watchedRules.unregisterOnSharedPreferenceChangeListener(rulesListener);
        rulesListener = (prefs, key) -> {
            if (key != null && !key.startsWith("accepted_v3_")
                    && !java.util.Arrays.asList("enabled", "flat", "mile", "minute", "max_stops",
                    "autopilot_on", "autopilot_bar_percent", "auto_accept_matching_offers",
                    FilterStore.AREA_MODE, FilterStore.ACCEPTANCE_LEARNING).contains(key)) return;
            if (!enabled(app)) return;
            long e = epoch(app);
            execute(() -> {
                if (!allowed(app, e)) return;
                load(app, e).policyChanged();
                save(app, e, System.currentTimeMillis(), true);
            });
        };
        watchedRules = p; p.registerOnSharedPreferenceChangeListener(rulesListener);
    }

    /**
     * The study's own choice before 0.6.34 ("version" and "enabled"), forgotten once with whatever it kept: the one
     * switch covers it now, and starts off. Called by {@link Feedback} as it retires the separate choices.
     *
     * @return whether a choice was kept
     */
    static boolean retireOwnChoice(Context c) {
        Context app = c.getApplicationContext();
        SharedPreferences p = prefs(app);
        boolean kept = p.contains("enabled") || p.contains("version");
        if (!kept) return false;
        p.edit().remove("enabled").remove("version").apply();
        clear(app);
        return true;
    }

    /**
     * A fresh scope: a new epoch, so nothing kept or queued before it is used or sent (prepared reports from an
     * earlier scope are stripped), and its file goes. Share anonymous diagnostics turned on or off, and Clear history.
     */
    static void clear(Context c) {
        Context app = c.getApplicationContext();
        long next = epoch(app) + 1;
        prefs(app).edit().putLong("epoch", next).putLong("since", System.currentTimeMillis()).apply();
        resetSoon(app, next);
        FeedbackOutbox.discardStudy(app);
    }

    private static void resetSoon(Context app, long next) {
        // Consent/clear commands cannot be dropped behind a full observation queue. No disk work on the UI thread.
        synchronized (WORK) {
            for (Runnable queued : WORK.getQueue()) if (queued instanceof FutureTask<?>) ((FutureTask<?>) queued).cancel(false);
            WORK.getQueue().clear();
            WORK.execute(() -> {
                if (epoch(app) != next) return;
                model = new DispatchStudy(); owner = app; modelEpoch = next; lastSave = 0;
                new AtomicFile(file(app)).delete();
            });
        }
    }

    static void screen(Context c, boolean eligible, DasherScene scene, OfferSnapshot offer,
                       boolean excluded, boolean fresh) {
        if (!enabled(c)) return;
        Context app = c.getApplicationContext();
        long e = epoch(c), now = SystemClock.elapsedRealtime(), wall = System.currentTimeMillis();
        OfferSnapshot facts = DispatchStudy.numeric(offer);
        FilterSettings rules = FilterStore.load(c);
        // Freeze the full observation policy here, not when the worker later processes this screen. Preference
        // callbacks can arrive later (or after a second toggle); neither may mix training across this boundary.
        boolean autoAccept = FilterStore.autoAcceptEnabled(c);
        long session = Dashing.currentStart(c);
        execute(() -> {
            if (!allowed(app, e)) return;
            DispatchStudy study = load(app, e);
            if (overflow) { study.stop(DispatchStudy.Reason.QUEUE_OVERFLOW); overflow = false; }
            study.screen(now, wall, session, eligible, scene, facts, excluded, fresh, rules, autoAccept);
            save(app, e, wall, scene == DasherScene.OFFER || !eligible);
        });
    }

    static void heartbeat(Context c, boolean eligible) {
        if (!enabled(c)) return;
        Context app = c.getApplicationContext();
        long e = epoch(c), now = SystemClock.elapsedRealtime(), wall = System.currentTimeMillis();
        execute(() -> {
            if (!allowed(app, e)) return;
            DispatchStudy study = load(app, e);
            if (overflow) { study.stop(DispatchStudy.Reason.QUEUE_OVERFLOW); overflow = false; }
            study.heartbeat(now, wall, eligible);
            save(app, e, wall, !eligible);
        });
    }

    static void stop(Context c) {
        if (!enabled(c)) return;
        Context app = c.getApplicationContext(); long e = epoch(c);
        execute(() -> {
            if (!allowed(app, e)) return;
            load(app, e).stop(DispatchStudy.Reason.COVERAGE_LOST);
            save(app, e, System.currentTimeMillis(), true);
        });
    }

    static void decisions(Context c, List<DecisionLog.Entry> entries) {
        if (!enabled(c)) return;
        Context app = c.getApplicationContext(); long e = epoch(c);
        List<DispatchStudy.Action> facts = new ArrayList<>();
        // Only recent screen evidence, no text or arbitrary step detail crosses this boundary.
        for (int i = Math.max(0, entries.size() - 8); i < entries.size(); i++) {
            DecisionLog.Entry entry = entries.get(i);
            if (entry.source == DecisionLog.Source.SCREEN && !entry.addOn
                    && entry.at >= prefs(c).getLong("since", Long.MAX_VALUE)) facts.add(new DispatchStudy.Action(entry));
        }
        execute(() -> {
            if (!allowed(app, e)) return;
            load(app, e).actions(facts);
            save(app, e, System.currentTimeMillis(), true);
        });
    }

    /** Worker-only report snapshot. A timeout/overflow omits this optional section, never blocks the scanner. */
    static String section(Context c) {
        return section(c, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    static String section(Context c, long start, long end) {
        if (!enabled(c)) return "";
        Context app = c.getApplicationContext(); long e = epoch(c);
        FutureTask<String> task = new FutureTask<>(() -> {
            if (!allowed(app, e)) return "";
            DispatchStudy study = load(app, e);
            List<DispatchStudy.Row> rows = study.snapshotRows(System.currentTimeMillis(), DispatchStudy.Reason.PREVIEW_CUTOFF);
            rows.removeIf(row -> row.keptAt < start || row.keptAt > end);
            return DispatchStudyPayload.section(rows, e, study.dropped);
        });
        try {
            synchronized (WORK) { WORK.execute(task); }
            String result = task.get(3, TimeUnit.SECONDS);
            return allowed(app, e) ? result : "";
        } catch (Exception unavailable) { task.cancel(false); return ""; }
    }

    static void preview(Context c, Consumer<String> done) {
        Context app = c.getApplicationContext(); long e = epoch(c);
        Handler main = new Handler(Looper.getMainLooper());
        execute(() -> {
            String result = "";
            if (allowed(app, e)) {
                DispatchStudy study = load(app, e);
                result = DispatchStudyPayload.section(study.snapshotRows(System.currentTimeMillis(),
                        DispatchStudy.Reason.PREVIEW_CUTOFF), e, study.dropped);
            }
            String text = result;
            main.post(() -> done.accept(allowed(app, e) ? text : ""));
        });
    }

    private static void execute(Runnable r) {
        try { synchronized (WORK) {
            WORK.execute(() -> { try { r.run(); } catch (RuntimeException unavailable) { overflow = true; } });
        } }
        catch (java.util.concurrent.RejectedExecutionException full) { overflow = true; }
    }

    private static DispatchStudy load(Context app, long e) {
        if (model != null && owner == app && modelEpoch == e) return model;
        model = new DispatchStudy(); owner = app; modelEpoch = e; lastSave = 0;
        AtomicFile atomic = new AtomicFile(file(app));
        try (FileInputStream in = atomic.openRead()) {
            if (in.getChannel().size() > MAX_FILE_BYTES) throw new java.io.IOException("study size");
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > MAX_FILE_BYTES) throw new java.io.IOException("study size");
                out.write(buffer, 0, n);
            }
            JSONObject saved = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            if (saved.getLong("epoch") != e) { atomic.delete(); return model; }
            model.dropped = Math.max(0, saved.optInt("dropped", 0));
            JSONArray rows = saved.getJSONArray("rows");
            if (rows.length() > DispatchStudy.MAX_ROWS + 1) throw new java.io.IOException("study rows");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject wrapper = rows.getJSONObject(i);
                DispatchStudy.Row r = DispatchStudy.Row.parse(wrapper.getJSONObject("data"));
                r.keptAt = wrapper.getLong("keptAt");
                model.restore(r);
            }
            model.prune(System.currentTimeMillis());
        } catch (Exception absentOrCorrupt) { atomic.delete(); model = new DispatchStudy(); }
        return model;
    }

    private static void save(Context app, long e, long wall, boolean force) {
        if (!allowed(app, e) || !force && wall >= lastSave && wall - lastSave < 30_000) return;
        FileOutputStream out = null; AtomicFile atomic = new AtomicFile(file(app));
        try {
            JSONArray rows = new JSONArray();
            for (DispatchStudy.Row r : model.snapshotRows(wall, DispatchStudy.Reason.RESTART))
                rows.put(new JSONObject().put("keptAt", r.keptAt).put("data", r.json(e)));
            byte[] bytes = new JSONObject().put("epoch", e).put("dropped", model.dropped).put("rows", rows)
                    .toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_FILE_BYTES || !allowed(app, e)) return;
            out = atomic.startWrite(); out.write(bytes); atomic.finishWrite(out); lastSave = wall;
            if (!allowed(app, e)) atomic.delete();
        } catch (Exception unavailable) { if (out != null) atomic.failWrite(out); }
    }
    private static File file(Context app) { return new File(app.getFilesDir(), FILE); }

    static void flush() {
        // A withdrawal or a clear (resetSoon) cancels what is queued, this barrier too; queued again, it waits behind
        // that reset. Submitted under WORK's lock, so it never lands between resetSoon's cancels and its clear().
        for (int tries = 0; tries < 4; tries++) {
            java.util.concurrent.Future<?> barrier;
            synchronized (WORK) { barrier = WORK.submit(() -> {}); }
            try { barrier.get(5, TimeUnit.SECONDS); return; }
            catch (java.util.concurrent.CancellationException resetMeanwhile) { /* queue it again */ }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        throw new IllegalStateException("offer timing study work kept being reset");
    }
    static void resetProcessForTest() { flush(); model = null; owner = null; modelEpoch = -1; overflow = false; }
    private DispatchStudyStore() {}
}

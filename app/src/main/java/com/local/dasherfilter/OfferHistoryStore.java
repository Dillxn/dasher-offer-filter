package com.local.dasherfilter;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * On-device offer history (filesDir/offer-history.txt): an in-memory OfferHistory plus an append-only log of serialized
 * records written on one bounded background thread, so the accessibility and listener callbacks never wait for disk.
 * The file is read once in the background; records added before it finished are kept (they are newer). Nothing leaves the
 * device except through an explicit share sheet (csv()). History never feeds decisions except the opt-in decline budget.
 */
final class OfferHistoryStore {
    static final String FILE = "offer-history.txt";
    private static final int COMPACT_AFTER_APPENDS = 2000;
    private static final Object INSTANCE_LOCK = new Object();
    private static OfferHistoryStore instance;

    private final Context app;
    private final File file;
    private final OfferHistory history = new OfferHistory();
    /** Shared by both services: screen taps, notification decline actions and hides in the trailing hour. */
    private final DeclineBudget budget = new DeclineBudget();
    private final ThreadPoolExecutor writer = newWriter();
    private final AtomicLong version = new AtomicLong();
    private volatile boolean loaded;
    /** Appends were dropped (queue full or I/O error): the next write rewrites the whole file from memory. */
    private volatile boolean dirty;
    private int appendsSinceCompact;

    private OfferHistoryStore(Context context) {
        app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        file = new File(app.getFilesDir(), FILE);
        submit(this::load);
    }

    /** The process-wide store; starts the background load on first use. Cheap after the first call. */
    static OfferHistoryStore get(Context context) {
        Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        synchronized (INSTANCE_LOCK) {
            if (instance == null || instance.app != app) instance = new OfferHistoryStore(app);
            return instance;
        }
    }

    /** A unique id for a new record (wall-clock seeded, increasing). */
    long nextId(long nowWall) { return history.nextId(nowWall); }
    OfferRecord get(long id) { return history.get(id); }
    DeclineBudget budget() { return budget; }
    /** Budget key for one record, identical for live requests and requests seeded from the file. */
    static String budgetKey(long recordId) { return "record:" + recordId; }
    /** True once the file has been read (records from before this process are then included). */
    boolean loaded() { return loaded; }
    /** Changes whenever a record is added, updated, or cleared; lets the UI refresh only when needed. */
    long version() { return version.get(); }

    /** Inserts or replaces a record in memory and queues it for the file. An identical record is not written again. */
    void upsert(OfferRecord record) {
        if (record == null) return;
        long now = System.currentTimeMillis();
        OfferRecord old = history.get(record.id);
        if (old != null && old.serialize().equals(record.serialize())) return;
        history.upsert(record, now);
        if (record.declineRequestCounted()) budget.record(budgetKey(record.id), record.at);
        version.incrementAndGet();
        String line = record.serialize() + "\n";
        submit(() -> append(line));
    }
    /** Adds action flags to an existing record; returns it, or null when the record is gone. */
    OfferRecord addActions(long id, int flags) {
        OfferRecord r = history.get(id);
        if (r == null) return null;
        OfferRecord next = r.withAction(flags);
        upsert(next);
        return next;
    }

    List<OfferRecord> newestFirst() { return history.newestFirst(); }
    List<OfferRecord> chronological() { return history.chronological(); }
    List<OfferRecord> between(long fromInclusive, long toExclusive) { return history.between(fromInclusive, toExclusive); }
    int size() { return history.size(); }
    /** Today's counts in the device time zone. */
    HistoryStats today() { return HistoryStats.today(history.chronological(), System.currentTimeMillis(), ZoneId.systemDefault()); }
    /** CSV for an explicit, user-initiated share sheet only. */
    String csv() { return history.csv(ZoneId.systemDefault()); }

    /** Deletes every record from memory and disk. The in-memory decline budget is kept: clearing history never loosens the guard. */
    void clear() {
        history.clear();
        version.incrementAndGet();
        submit(() -> { synchronized (this) { appendsSinceCompact = 0; dirty = false; if (file.exists() && !file.delete()) dirty = true; } });
    }

    /** Waits (bounded) until queued writes and the initial load are done. For tests and the share action. */
    void flush() {
        try { writer.submit(() -> {}).get(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (ExecutionException | TimeoutException | RejectedExecutionException ignored) {}
    }

    private static ThreadPoolExecutor newWriter() {
        ThreadPoolExecutor w = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256), r -> {
            Thread t = new Thread(r, "offer-history"); t.setDaemon(true); return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        w.allowCoreThreadTimeOut(true);
        return w;
    }

    private void submit(Runnable task) {
        try { writer.execute(task); } catch (RejectedExecutionException full) { dirty = true; }
    }

    private void load() {
        long now = System.currentTimeMillis();
        try {
            if (file.isFile()) {
                OfferHistory disk = OfferHistory.parse(read(file), now);
                for (OfferRecord r : disk.chronological()) {
                    if (history.get(r.id) == null) history.upsert(r, now);   // records written in this process are newer
                    if (r.declineRequestCounted()) budget.record(budgetKey(r.id), r.at);
                }
                history.nextId(now);
                version.incrementAndGet();
            }
        } catch (IOException | RuntimeException error) {
            DiagnosticLog.log(app, "history", "history file unreadable; starting empty: " + error.getClass().getSimpleName());
        }
        loaded = true;
        rewrite();   // compacts: drops expired records and duplicate lines
    }

    private synchronized void append(String line) {
        if (dirty || ++appendsSinceCompact > COMPACT_AFTER_APPENDS) { rewrite(); return; }
        try (OutputStream out = new FileOutputStream(file, true)) { out.write(line.getBytes(StandardCharsets.UTF_8)); }
        catch (IOException error) { dirty = true; }
    }

    private synchronized void rewrite() {
        history.prune(System.currentTimeMillis());
        File tmp = new File(file.getParentFile(), FILE + ".tmp");
        try {
            try (OutputStream out = new FileOutputStream(tmp, false)) { out.write(history.serialize().getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(file)) throw new IOException("rename failed");
            dirty = false; appendsSinceCompact = 0;
        } catch (IOException error) {
            dirty = true; tmp.delete();
        }
    }

    private static String read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n; long total = 0;
            while ((n = in.read(b)) != -1) { total += n; if (total > 4L * 1024 * 1024) throw new IOException("history file too large"); out.write(b, 0, n); }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

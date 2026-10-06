package com.local.dasherfilter;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Submissions waiting to reach the feedback service ({@link Feedback}): at most {@value #MAX_ITEMS}, each kept at most
 * seven days, in files/feedback-outbox, each written whole (a temporary file, synced, then renamed). A submission is
 * written before anything is sent, so no signal, a closed screen or a restart loses nothing; the user sees "Saved" and
 * it goes later through {@link FeedbackJobService}.
 *
 * <p>Before every part: the current notice must be accepted (else nothing is sent and it waits), the one-time privacy
 * cleanup must have finished, and an automatic summary (the opt-in after each dash) needs that opt-in still on (else
 * it is discarded). The part's text is masked again with the current rules right before it is sent. A 201 crosses the
 * part off at once, durably; a retry sends only the parts still owed, under the same token, so a reply lost after the
 * service stored a part never stores it twice. 429, a server error or no connection keep the submission for a later
 * try, with backoff; 400 or 413 drop it (sending it again cannot help) and say so.
 */
final class FeedbackOutbox {
    static final int JOB_ID = 7246;
    static final String DIR = "feedback-outbox";
    static final int MAX_ITEMS = 5;
    static final long MAX_AGE_MS = 7L * 24 * 3_600_000L;
    /** A submission's diagnostics: masked text, or one offer's report (JSON, masked again as such). */
    static final String TEXT = "text";
    static final String OFFER = "offer";
    static final long FIRST_RETRY_MS = 30_000L;
    static final long MAX_RETRY_MS = 3_600_000L;
    /** The service counts sends per network for ten minutes: a refusal waits at least that long. */
    static final long RATE_LIMIT_MS = 10 * 60_000L;
    /** Automatic summaries are named so they can be discarded without being read. */
    private static final String AUTOMATIC_SUFFIX = "-a.json";

    private static final Object QUEUE_LOCK = new Object();
    /** Sends, one at a time, oldest first: an in-process send after Send, and the job's. */
    private static final ExecutorService SENDER = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "feedback-sender");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean sendQueued = new AtomicBoolean();
    private static final AtomicLong sequence = new AtomicLong();
    /** How many submissions wait, kept so Settings' line costs no disk read; -1 until listed. */
    private static volatile int pendingCount = -1;
    /** The application {@link #pendingCount} was counted for: a new process (or test) counts again. */
    private static volatile Object countedFor;
    /** The wall clock; tests move it. */
    static volatile java.util.function.LongSupplier clock = System::currentTimeMillis;

    /** How a send ended: done (nothing more to do now), or try again after {@code delayMs}. */
    static final class Result {
        static final Result DONE = new Result(false, 0);
        final boolean retry;
        final long delayMs;

        private Result(boolean retry, long delayMs) {
            this.retry = retry;
            this.delayMs = delayMs;
        }

        static Result retry(long delayMs) {
            return new Result(true, Math.max(0, delayMs));
        }
    }

    /** One submission, as written to the outbox. */
    static JSONObject item(String token, Feedback.Kind kind, String category, boolean automatic, String format,
                           String message, boolean consented, List<String> parts) {
        try {
            JSONArray texts = new JSONArray();
            for (String part : parts) texts.put(part);
            return new JSONObject().put("v", 1).put("token", token).put("kind", kind.wire).put("category", category)
                    .put("automatic", automatic).put("format", format).put("createdAt", clock.getAsLong())
                    .put("message", message == null ? "" : message).put("consented", consented && !parts.isEmpty())
                    .put("parts", texts).put("sent", 0).put("reference", "").put("attempts", 0);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Queues a submission the user made and sends it at once; when it cannot be queued, says why. On a worker.
     */
    static void submit(Context app, JSONObject item) {
        String refused = enqueue(app, item);
        Feedback.Kind kind = Feedback.Kind.named(item.optString("kind"));
        if (refused != null) {
            Feedback.post(new Feedback.Event(item.optString("token"), Feedback.State.NOT_QUEUED, "", refused, kind,
                    true, item.optString("message", "")));
            return;
        }
        sendSoon(app);
    }

    /** Queues an automatic summary (the opt-in after each dash) and sends it soon. @return whether it was queued */
    static boolean submitAutomatic(Context app, JSONObject item) {
        if (enqueue(app, item) != null) return false;
        sendSoon(app);
        return true;
    }

    /** @return null once written, or why it was not */
    private static String enqueue(Context context, JSONObject item) {
        Context app = context.getApplicationContext();
        boolean automatic = item.optBoolean("automatic");
        Feedback.Kind kind = Feedback.Kind.named(item.optString("kind"));
        JSONArray texts = item.optJSONArray("parts");
        if (texts == null || !Feedback.validToken(item.optString("token"))) return "Couldn't prepare it; try again.";
        if (kind == Feedback.Kind.FEEDBACK && item.optString("message", "").trim().isEmpty()) {
            return "Write something first.";
        }
        if (kind != Feedback.Kind.FEEDBACK && texts.length() == 0) return "Nothing to send.";
        if (texts.length() > Feedback.MAX_PARTS) return "Diagnostics are too long to send.";
        if (OFFER.equals(item.optString("format")) && item.optJSONArray("parts").length() != 1) {
            return "The offer's report is too long to send.";
        }
        synchronized (QUEUE_LOCK) {
            prune(app);
            File dir = dir(app);
            if (dir == null) return "Storage unavailable; try again.";
            File[] queued = files(app);
            if (queued.length >= MAX_ITEMS) {
                File oldestAutomatic = null;
                for (File file : queued) {
                    if (file.getName().endsWith(AUTOMATIC_SUFFIX)) {
                        oldestAutomatic = file;
                        break;
                    }
                }
                if (automatic || oldestAutomatic == null) {
                    return MAX_ITEMS + " submissions are already waiting to send; try again later.";
                }
                // The user's own words come before an automatic summary.
                deleteQuietly(oldestAutomatic);
            }
            File file;
            do {
                file = new File(dir, String.format(java.util.Locale.US, "%013d-%06d", clock.getAsLong(),
                        sequence.incrementAndGet() % 1_000_000) + (automatic ? AUTOMATIC_SUFFIX : ".json"));
            } while (file.exists());
            try {
                replace(file, item);
            } catch (IOException | RuntimeException failed) {
                return "Couldn't save it on this phone; try again.";
            } finally {
                recount(app);
            }
        }
        return null;
    }

    /** Sends what waits now, on the sending thread (one send queued at a time); a retry is left to the job. */
    static void sendSoon(Context context) {
        Context app = context.getApplicationContext();
        if (!sendQueued.compareAndSet(false, true)) return;
        SENDER.execute(() -> {
            sendQueued.set(false);
            Result result;
            try {
                result = drain(app, () -> false);
            } catch (RuntimeException unexpected) {
                DiagnosticLog.log(app, "feedback", "send failed: " + unexpected.getClass().getSimpleName());
                result = Result.retry(FIRST_RETRY_MS);
            }
            if (result.retry && pending(app) > 0) FeedbackJobService.schedule(app, result.delayMs);
        });
    }

    /** Runs the job's work on the sending thread. */
    static void runOnSender(Runnable work) {
        SENDER.execute(work);
    }

    /**
     * Sends every waiting submission, oldest first, each part in order. On the sending thread only.
     *
     * @param stopped Android stopped the job: no further request is started (one under way finishes)
     */
    static synchronized Result drain(Context context, BooleanSupplier stopped) {
        Context app = context.getApplicationContext();
        // The one-time privacy cleanup comes first; until it has finished nothing is sent.
        if (!DiagnosticLog.cleanUpOnce(app)) return Result.retry(FIRST_RETRY_MS);
        // Waits for the current notice; accepting it sends what waited (consented).
        if (!Consent.accepted(app)) return Result.DONE;
        while (true) {
            if (stopped.getAsBoolean()) return Result.retry(0);
            File next;
            synchronized (QUEUE_LOCK) {
                prune(app);
                File[] queued = files(app);
                next = queued.length == 0 ? null : queued[0];
            }
            if (next == null) return Result.DONE;
            Result result = send(app, next, stopped);
            if (result != null) return result;
        }
    }

    /** One submission's parts still owed. @return null once it is finished (sent or dropped), else when to retry */
    private static Result send(Context app, File file, BooleanSupplier stopped) {
        JSONObject item;
        try {
            item = new JSONObject(read(file));
        } catch (IOException | JSONException corrupt) {
            deleteQueued(app, file);
            return null;
        }
        boolean automatic = item.optBoolean("automatic");
        Feedback.Kind kind = Feedback.Kind.named(item.optString("kind"));
        String token = item.optString("token");
        JSONArray parts = item.optJSONArray("parts");
        if (!Feedback.validToken(token) || parts == null) {
            deleteQueued(app, file);
            return null;
        }
        int count = Math.max(1, parts.length());
        String version = Updater.version(app);
        long code = Feedback.versionCode(app);
        for (int index = item.optInt("sent", 0); index < count; index++) {
            if (stopped.getAsBoolean()) return Result.retry(0);
            // Checked before every part, so declining the notice or turning the opt-in off stops the rest.
            if (!Consent.accepted(app)) return Result.DONE;
            if (automatic && !Feedback.afterDashOn(app)) {
                deleteQueued(app, file);
                return null;
            }
            if (!file.isFile()) return null;
            String text = null;
            if (parts.length() > 0) {
                text = remask(item.optString("format"), parts.optString(index, ""));
                if (text == null || text.isEmpty()) {
                    finish(app, file, item, kind, automatic, Feedback.State.REJECTED, "");
                    return null;
                }
            }
            byte[] body;
            try {
                body = Feedback.bytes(Feedback.request(kind, item.optString("category", "general"),
                        item.optString("message", ""), item.optBoolean("consented"), token, index, count, text,
                        version, code));
            } catch (JSONException impossible) {
                deleteQueued(app, file);
                return null;
            }
            if (body.length > Feedback.MAX_BODY_BYTES) {
                finish(app, file, item, kind, automatic, Feedback.State.REJECTED, "");
                return null;
            }
            Feedback.Response response;
            try {
                response = Feedback.transport.post(body);
            } catch (IOException offline) {
                return waitAndRetry(app, file, item, kind, automatic, Feedback.State.OFFLINE, 0);
            }
            Feedback.State state = Feedback.outcome(response.status);
            if (state == Feedback.State.REJECTED) {
                finish(app, file, item, kind, automatic, state, "");
                return null;
            }
            if (state != Feedback.State.SENT) {
                return waitAndRetry(app, file, item, kind, automatic, state, response.retryAfterMs);
            }
            try {
                String reference = Feedback.reference(response);
                if (item.optString("reference", "").isEmpty() && !reference.isEmpty()) item.put("reference", reference);
                item.put("sent", index + 1).put("attempts", 0);
            } catch (JSONException impossible) {
                return null;
            }
            // Crossed off at once: a retry never sends this part again (the service would not store it twice anyway).
            if (!rewrite(app, file, item)) return null;
        }
        finish(app, file, item, kind, automatic, Feedback.State.SENT, item.optString("reference", ""));
        return null;
    }

    /** Masks a part again with the current rules; null when it cannot be (an offer report this app did not write). */
    static String remask(String format, String text) {
        if (OFFER.equals(format)) return OfferReport.remask(text);
        return PersonalText.maskLine(text);
    }

    private static Result waitAndRetry(Context app, File file, JSONObject item, Feedback.Kind kind, boolean automatic,
                                       Feedback.State state, long retryAfterMs) {
        int attempts = item.optInt("attempts", 0) + 1;
        try {
            item.put("attempts", attempts);
        } catch (JSONException impossible) {
            // Only a number.
        }
        rewrite(app, file, item);
        Feedback.Event event = new Feedback.Event(item.optString("token"), state, item.optString("reference", ""),
                "", kind, !automatic);
        Feedback.noteState(app, event);
        Feedback.post(event);
        if (attempts == 1) DiagnosticLog.log(app, "feedback", kind.wire + " waiting to send: " + state.name());
        return Result.retry(delay(state, attempts, retryAfterMs));
    }

    /** 30 s, doubling to an hour; at least ten minutes after 429, and never sooner than the service asked. */
    static long delay(Feedback.State state, int attempts, long retryAfterMs) {
        long backoff = Math.min(MAX_RETRY_MS, FIRST_RETRY_MS << Math.min(10, Math.max(0, attempts - 1)));
        if (state == Feedback.State.RATE_LIMITED) backoff = Math.max(RATE_LIMIT_MS, backoff);
        if (retryAfterMs > 0) backoff = Math.max(backoff, Math.min(MAX_RETRY_MS, retryAfterMs));
        return backoff;
    }

    private static void finish(Context app, File file, JSONObject item, Feedback.Kind kind, boolean automatic,
                               Feedback.State state, String reference) {
        deleteQueued(app, file);
        int count = Math.max(1, item.optJSONArray("parts") == null ? 1 : item.optJSONArray("parts").length());
        if (state == Feedback.State.SENT) {
            Feedback.remember(app, new Feedback.Sent(clock.getAsLong(), reference, kind, automatic));
            DiagnosticLog.log(app, "feedback", kind.wire + " sent: " + count + (count == 1 ? " part" : " parts")
                    + (reference.isEmpty() ? "" : "; reference " + reference));
        } else {
            DiagnosticLog.log(app, "feedback", kind.wire + " not accepted by the feedback service and dropped");
        }
        Feedback.Event event = new Feedback.Event(item.optString("token"), state, reference, "", kind, !automatic,
                state == Feedback.State.SENT || automatic ? "" : item.optString("message", ""));
        Feedback.noteState(app, event);
        Feedback.post(event);
    }

    /** Every automatic summary not yet sent is deleted (the opt-in turned off, Clear history); the user's stay. */
    static void discardAutomatic(Context context) {
        Context app = context.getApplicationContext();
        synchronized (QUEUE_LOCK) {
            for (File file : files(app)) {
                if (file.getName().endsWith(AUTOMATIC_SUFFIX)) deleteQuietly(file);
            }
            recount(app);
        }
    }

    /** How many submissions wait to send. Cheap after the first look. */
    static int pending(Context context) {
        Context app = context.getApplicationContext();
        int known = pendingCount;
        if (known >= 0 && countedFor == app) return known;
        synchronized (QUEUE_LOCK) {
            recount(app);
            return pendingCount;
        }
    }

    /** Under the lock. */
    private static void recount(Context app) {
        pendingCount = files(app).length;
        countedFor = app;
    }

    /** The notice was accepted: whatever waited for it goes now. */
    static void consented(Context context) {
        if (pending(context) > 0) sendSoon(context);
    }

    /** Old submissions (seven days), summaries whose opt-in is off, and half-written files go. Under the lock. */
    private static void prune(Context app) {
        File dir = new File(app.getFilesDir(), DIR);
        File[] all = dir.listFiles();
        if (all == null) return;
        long now = clock.getAsLong();
        boolean afterDash = Feedback.afterDashOn(app);
        int expired = 0;
        for (File file : all) {
            String name = file.getName();
            if (name.endsWith(".tmp")) {
                deleteQuietly(file);
                continue;
            }
            if (!name.endsWith(".json")) continue;
            if (name.endsWith(AUTOMATIC_SUFFIX) && !afterDash) {
                deleteQuietly(file);
                continue;
            }
            long created = createdAt(name);
            // A future time (the clock moved back) is as old as can be: it would otherwise never expire.
            if (created <= 0 || now - created > MAX_AGE_MS || created - now > MAX_AGE_MS) {
                deleteQuietly(file);
                expired++;
            }
        }
        if (expired > 0) {
            DiagnosticLog.log(app, "feedback", expired + (expired == 1 ? " submission" : " submissions")
                    + " unsent after seven days, discarded");
        }
        recount(app);
    }

    private static long createdAt(String name) {
        try {
            return Long.parseLong(name.substring(0, 13));
        } catch (RuntimeException unnamed) {
            return 0;
        }
    }

    private static File[] files(Context app) {
        // Listing never creates the folder, so the status line costs no write.
        File[] files = new File(app.getFilesDir(), DIR).listFiles((parent, name) -> name.endsWith(".json"));
        if (files == null) return new File[0];
        Arrays.sort(files);
        return files;
    }

    private static File dir(Context app) {
        File dir = new File(app.getFilesDir(), DIR);
        return dir.isDirectory() || dir.mkdirs() ? dir : null;
    }

    private static void deleteQueued(Context app, File file) {
        synchronized (QUEUE_LOCK) {
            deleteQuietly(file);
            recount(app);
        }
    }

    private static void deleteQuietly(File file) {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /** Rewrites a waiting submission, unless it was discarded meanwhile (never brings one back). */
    private static boolean rewrite(Context app, File file, JSONObject item) {
        synchronized (QUEUE_LOCK) {
            if (!file.isFile()) return false;
            try {
                replace(file, item);
                return true;
            } catch (IOException | RuntimeException failed) {
                DiagnosticLog.log(app, "feedback", "could not save progress: " + failed.getClass().getSimpleName());
                return false;
            }
        }
    }

    /** Writes {@code item} whole: a temporary file, synced, renamed over {@code file}, the folder synced. */
    private static void replace(File file, JSONObject item) throws IOException {
        File next = new File(file.getPath() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(next)) {
                out.write(item.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!next.renameTo(file)) throw new IOException("could not replace " + file.getName());
            try (java.nio.channels.FileChannel folder = java.nio.channels.FileChannel.open(
                    file.getParentFile().toPath(), java.nio.file.StandardOpenOption.READ)) {
                folder.force(true);
            } catch (IOException | RuntimeException unsupported) {
                // Some file systems cannot sync a folder; the file itself was synced before the rename.
            }
        } finally {
            if (next.exists()) deleteQuietly(next);
        }
    }

    private static String read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Waits for queued sends; for tests. */
    static void flush() {
        try {
            SENDER.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } catch (Exception interruptedOrTimedOut) {
            if (interruptedOrTimedOut instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    /** As a new process would: the pending count is read from disk again. For tests. */
    static void forgetCache() {
        pendingCount = -1;
        countedFor = null;
        sendQueued.set(false);
    }

    private FeedbackOutbox() {}
}

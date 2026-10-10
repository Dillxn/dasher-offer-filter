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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Submissions waiting to reach the feedback service ({@link Feedback}): at most {@value #MAX_ITEMS}, each kept at most
 * seven days, in files/feedback-outbox, each written whole (a temporary file, synced, then renamed). A submission is
 * written before anything is sent, and the persisted job ({@link FeedbackJobService}) is scheduled before its first
 * send, so no signal, a closed screen, a crash, a reboot or a restart loses nothing; the user sees "Saved" and it goes
 * later. The app opening and either service connecting send what waits too, once per process.
 *
 * <p>Before every part: the current notice must be accepted (else nothing is sent and it waits), the one-time privacy
 * cleanup must have finished, and an automatic summary (the opt-in after each dash) needs that opt-in still on, in the
 * epoch it was begun in (else it is discarded). The part's text is masked again with the current rules right before it
 * is sent, and an automatic summary is filtered again for Dasher's acceptance rate ({@link DashSummary#remask}). A 201
 * crosses the part off at once (in memory, so this process never sends it again, and on disk); a retry sends only the
 * parts still owed, under the same token, so a reply lost after the service stored a part never stores it twice.
 *
 * <p>Each pass looks at each submission once, oldest first, and one that has to wait never holds back another: every
 * one hears how it went. 429 or a Retry-After keeps a submission back until then, whatever else is sent meanwhile; a
 * server error keeps it for a later try with backoff. With no connection, or the network over the service's limit,
 * the others are told so without a try. 400 or 413 drop it (sending it again cannot help) and say so.
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
    /** One pass looks at no more submissions than this (each at most once, whatever is queued meanwhile). */
    private static final int MAX_LOOKS = MAX_ITEMS * 4;

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
    /** The application whose process already sent what waited as it started ({@link #resume}). */
    private static volatile Object resumedFor;
    /** The wall clock; tests move it. */
    static volatile java.util.function.LongSupplier clock = System::currentTimeMillis;
    /** Deletes one file of the outbox; tests make it fail, as a broken file system would. */
    static volatile Predicate<File> remover = File::delete;

    /** Parts the service acknowledged, by token, whatever the disk kept of it: this process never sends them again. */
    private static final Map<String, Acknowledged> acknowledged = new ConcurrentHashMap<>();
    /** Submissions done with (sent, or refused for good) whose file could not be deleted: never sent again. */
    private static final Set<String> done = ConcurrentHashMap.newKeySet();
    /** Writes or deletions that failed in a row, by token, for their backoff. */
    private static final Map<String, Integer> diskFailures = new ConcurrentHashMap<>();
    /** The one-time privacy cleanup's failures in a row, for its backoff. On the sending thread (drain) only. */
    private static int cleanupFailures;

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

    /** What the service acknowledged of one submission: how many parts, and the reference it gave. */
    private static final class Acknowledged {
        final int parts;
        final String reference;

        Acknowledged(int parts, String reference) {
            this.parts = parts;
            this.reference = reference;
        }
    }

    /** One pass over the outbox: what it looked at, when the soonest waiting submission is due, and why it stopped. */
    private static final class Pass {
        final Set<String> looked = new HashSet<>();
        long soonest = -1;
        /** No connection, or the network over its limit: the rest are told so, not tried, this pass. */
        Feedback.State unreachable;
        /** Set to end the pass: Android stopped the job, or the notice is no longer accepted. */
        Result halt;

        void waits(long delayMs) {
            soonest = soonest < 0 ? Math.max(0, delayMs) : Math.min(soonest, Math.max(0, delayMs));
        }

        Result result() {
            return soonest < 0 ? Result.DONE : Result.retry(soonest);
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
                    .put("parts", texts).put("sent", 0).put("reference", "").put("attempts", 0).put("nextAt", 0);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * An automatic summary (the opt-in after each dash): masked text the opt-in consented to, tied to the opt-in's
     * epoch ({@link Feedback#automaticEpoch}) as its dash ended, so turning the opt-in off (and on again) or clearing
     * history since keeps it from ever being queued or sent.
     */
    static JSONObject automatic(String token, List<String> parts, long epoch) {
        try {
            return item(token, Feedback.Kind.DIAGNOSTICS, Feedback.Category.GENERAL.wire, true, TEXT, "", true, parts)
                    .put("epoch", epoch);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Queues a submission the user made and sends it at once; when it cannot be queued, says why. On a worker.
     */
    static void submit(Context context, JSONObject item) {
        Context app = context.getApplicationContext();
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
    static boolean submitAutomatic(Context context, JSONObject item) {
        Context app = context.getApplicationContext();
        if (enqueue(app, item) != null) return false;
        sendSoon(app);
        return true;
    }

    /** @return null once written (and the job that sends it scheduled), or why it was not */
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
            // Checked under the lock the discarding takes: a summary begun before the opt-in changed never lands.
            if (automatic && !Feedback.automaticAllowed(app, item.optLong("epoch", -1))) {
                return "Diagnostics after each dash changed meanwhile.";
            }
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
        // Before the first send: should the process die mid-send, Android still sends it (the job is persisted).
        FeedbackJobService.schedule(app, FIRST_RETRY_MS);
        return null;
    }

    /**
     * Sends what waits now, on the sending thread (one send queued at a time). What still waits afterwards goes
     * through the job; with nothing waiting, the job that protected a first send is no longer needed.
     */
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
            if (result.retry && pending(app) > 0) {
                FeedbackJobService.schedule(app, result.delayMs);
            } else {
                FeedbackJobService.cancelIfIdle(app);
            }
        });
    }

    /**
     * The app opened or a service connected: what waits (a job lost to a crash, a reboot or a stopped job) goes now,
     * when it is due. Once per process; never a disk read on the caller's thread.
     */
    static void resume(Context context) {
        Context app = context.getApplicationContext();
        if (resumedFor == app) return;
        resumedFor = app;
        sendSoon(app);
    }

    /** Runs the job's work on the sending thread. */
    static void runOnSender(Runnable work) {
        SENDER.execute(work);
    }

    /**
     * Sends every waiting submission that is due, oldest first, each part in order. On the sending thread only.
     *
     * @param stopped Android stopped the job: no further request is started (one under way finishes)
     */
    static synchronized Result drain(Context context, BooleanSupplier stopped) {
        Context app = context.getApplicationContext();
        if (!DiagnosticLog.cleanUpOnce(app)) {
            // The one-time privacy cleanup comes first; until it has finished nothing is sent, and it is tried again
            // with backoff. Old submissions still expire meanwhile.
            synchronized (QUEUE_LOCK) {
                prune(app);
            }
            cleanupFailures = Math.min(cleanupFailures + 1, 20);
            return pending(app) > 0 ? Result.retry(delay(Feedback.State.UNAVAILABLE, cleanupFailures, 0))
                    : Result.DONE;
        }
        cleanupFailures = 0;
        // Waits for the current notice; accepting it sends what waited (consented).
        if (!Consent.accepted(app)) return Result.DONE;
        Pass pass = new Pass();
        for (int look = 0; look < MAX_LOOKS; look++) {
            if (stopped.getAsBoolean()) return Result.retry(0);
            File next = null;
            synchronized (QUEUE_LOCK) {
                prune(app);
                for (File file : files(app)) {
                    if (pass.looked.add(file.getName())) {
                        next = file;
                        break;
                    }
                }
            }
            if (next == null) break;
            send(app, next, stopped, pass);
            if (pass.halt != null) return pass.halt;
        }
        return pass.result();
    }

    /** One submission's parts still owed, as far as this pass goes: the pass records what it waits for. */
    private static void send(Context app, File file, BooleanSupplier stopped, Pass pass) {
        JSONObject item;
        try {
            item = new JSONObject(read(file));
        } catch (IOException | JSONException corrupt) {
            deleteQueued(app, file);
            return;
        }
        boolean automatic = item.optBoolean("automatic");
        Feedback.Kind kind = Feedback.Kind.named(item.optString("kind"));
        String token = item.optString("token");
        JSONArray parts = item.optJSONArray("parts");
        if (!Feedback.validToken(token) || parts == null) {
            deleteQueued(app, file);
            return;
        }
        if (done.contains(token)) {
            // Sent (or refused) before, its file left behind: deleted now if it can be, never sent again.
            if (deleteQueued(app, file) || !file.exists()) {
                done.remove(token);
                diskFailures.remove(token);
            } else {
                pass.waits(diskBackoff(token));
            }
            return;
        }
        long now = clock.getAsLong();
        long nextAt = item.optLong("nextAt", 0);
        // The service asked it to wait (429, Retry-After): not before then. A clock moved back counts as due.
        if (nextAt > now && nextAt - now <= MAX_RETRY_MS) {
            pass.waits(nextAt - now);
            return;
        }
        if (pass.unreachable != null) {
            // No connection, or this network over its limit, a moment ago: it waits for the next try too, and hears so.
            if (item.optInt("attempts", 0) == 0) tell(app, item, kind, automatic, pass.unreachable);
            return;
        }
        int count = Math.max(1, parts.length());
        Acknowledged known = acknowledged.get(token);
        int from = Math.max(item.optInt("sent", 0), known == null ? 0 : known.parts);
        try {
            if (known != null && item.optString("reference", "").isEmpty()) item.put("reference", known.reference);
        } catch (JSONException impossible) {
            // Only a string.
        }
        String version = Updater.version(app);
        long code = Feedback.versionCode(app);
        for (int index = from; index < count; index++) {
            if (stopped.getAsBoolean()) {
                pass.halt = Result.retry(0);
                return;
            }
            // Checked before every part, so declining the notice or turning the opt-in off stops the rest.
            if (!Consent.accepted(app)) {
                pass.halt = Result.DONE;
                return;
            }
            if (automatic && !Feedback.automaticAllowed(app, item.optLong("epoch", -1))) {
                deleteQueued(app, file);
                return;
            }
            if (!file.isFile()) return;
            String text = null;
            if (parts.length() > 0) {
                text = remask(item.optString("format"), automatic, parts.optString(index, ""));
                if (text == null || text.isEmpty()) {
                    finish(app, file, item, kind, automatic, Feedback.State.REJECTED, "", pass);
                    return;
                }
            }
            byte[] body;
            try {
                body = Feedback.bytes(Feedback.request(kind, item.optString("category", "general"),
                        item.optString("message", ""), item.optBoolean("consented"), token, index, count, text,
                        version, code));
            } catch (JSONException impossible) {
                deleteQueued(app, file);
                return;
            }
            if (body.length > Feedback.MAX_BODY_BYTES) {
                finish(app, file, item, kind, automatic, Feedback.State.REJECTED, "", pass);
                return;
            }
            Feedback.Response response;
            try {
                response = Feedback.transport.post(body);
            } catch (IOException offline) {
                waitAndRetry(app, file, item, kind, automatic, Feedback.State.OFFLINE, 0, pass);
                return;
            }
            Feedback.State state = Feedback.outcome(response.status);
            if (state == Feedback.State.REJECTED) {
                finish(app, file, item, kind, automatic, state, "", pass);
                return;
            }
            if (state != Feedback.State.SENT) {
                waitAndRetry(app, file, item, kind, automatic, state, response.retryAfterMs, pass);
                return;
            }
            String reference = Feedback.reference(response);
            try {
                if (item.optString("reference", "").isEmpty() && !reference.isEmpty()) item.put("reference", reference);
                item.put("sent", index + 1).put("attempts", 0).put("nextAt", 0);
            } catch (JSONException impossible) {
                // Only numbers and a string.
            }
            // Crossed off at once: in memory first, so this process never sends the part again, saved or not.
            acknowledged.put(token, new Acknowledged(index + 1, item.optString("reference", "")));
            if (index + 1 < count && !rewrite(app, file, item)) {
                // Its progress could not be saved: the rest go later, from where it got to. (Or discarded meanwhile.)
                if (file.isFile()) pass.waits(diskBackoff(token));
                else acknowledged.remove(token);
                return;
            }
        }
        finish(app, file, item, kind, automatic, Feedback.State.SENT, item.optString("reference", ""), pass);
    }

    /** Masks a part again with the current rules; null when it cannot be (an offer report this app did not write). */
    static String remask(String format, String text) {
        return remask(format, false, text);
    }

    /**
     * Masks a part again with the current rules; null when it cannot be (an offer report this app did not write). An
     * automatic summary (the opt-in after each dash) also goes through the summary's own filter again
     * ({@link DashSummary#remask}), so one an older version queued never carries Dasher's acceptance rate that this
     * version's filter would have kept out; what the user sends themselves keeps it, as they chose.
     */
    static String remask(String format, boolean automatic, String text) {
        if (OFFER.equals(format)) return OfferReport.remask(text);
        String safe = DiagnosticLog.withoutTaggedAccountScreens(text);
        return PersonalText.maskLine(automatic ? DashSummary.remask(safe) : safe);
    }

    private static void waitAndRetry(Context app, File file, JSONObject item, Feedback.Kind kind, boolean automatic,
                                     Feedback.State state, long retryAfterMs, Pass pass) {
        int attempts = item.optInt("attempts", 0) + 1;
        long delay = delay(state, attempts, retryAfterMs);
        // What the service asked for (429, Retry-After) holds this submission back until then, whatever else is sent.
        long nextAt = state == Feedback.State.RATE_LIMITED || retryAfterMs > 0 ? clock.getAsLong() + delay : 0;
        try {
            item.put("attempts", attempts).put("nextAt", nextAt);
        } catch (JSONException impossible) {
            // Only numbers.
        }
        rewrite(app, file, item);
        tell(app, item, kind, automatic, state);
        if (attempts == 1) DiagnosticLog.log(app, "feedback", kind.wire + " waiting to send: " + state.name());
        pass.waits(delay);
        // No connection, or the network over its limit, is so for the others too; a server error may be this one's.
        if (state == Feedback.State.OFFLINE || state == Feedback.State.RATE_LIMITED) pass.unreachable = state;
    }

    /** The submission's sender hears that it waits, and why; so does Settings' line. */
    private static void tell(Context app, JSONObject item, Feedback.Kind kind, boolean automatic,
                             Feedback.State state) {
        Feedback.Event event = new Feedback.Event(item.optString("token"), state, item.optString("reference", ""),
                "", kind, !automatic);
        Feedback.noteState(app, event);
        Feedback.post(event);
    }

    /** 30 s, doubling to an hour; at least ten minutes after 429, and never sooner than the service asked. */
    static long delay(Feedback.State state, int attempts, long retryAfterMs) {
        long backoff = Math.min(MAX_RETRY_MS, FIRST_RETRY_MS << Math.min(10, Math.max(0, attempts - 1)));
        if (state == Feedback.State.RATE_LIMITED) backoff = Math.max(RATE_LIMIT_MS, backoff);
        if (retryAfterMs > 0) backoff = Math.max(backoff, Math.min(MAX_RETRY_MS, retryAfterMs));
        return backoff;
    }

    /** A write or deletion of this submission failed (again): when to try once more. */
    private static long diskBackoff(String token) {
        return delay(Feedback.State.UNAVAILABLE, diskFailures.merge(token, 1, Integer::sum), 0);
    }

    private static void finish(Context app, File file, JSONObject item, Feedback.Kind kind, boolean automatic,
                               Feedback.State state, String reference, Pass pass) {
        String token = item.optString("token");
        acknowledged.remove(token);
        if (!deleteQueued(app, file) && file.exists()) {
            // Done with all the same: never sent again; its file goes at a later try.
            done.add(token);
            pass.waits(diskBackoff(token));
        } else {
            diskFailures.remove(token);
        }
        int count = Math.max(1, item.optJSONArray("parts") == null ? 1 : item.optJSONArray("parts").length());
        if (state == Feedback.State.SENT) {
            Feedback.remember(app, new Feedback.Sent(clock.getAsLong(), reference, kind, automatic));
            // Never the reference: a later report with diagnostics attached carries this log, and the reference would
            // tie this submission (words only, say) to that report's phone. Settings keeps the last 10 references.
            DiagnosticLog.log(app, "feedback", kind.wire + " sent: " + count + (count == 1 ? " part" : " parts"));
        } else {
            DiagnosticLog.log(app, "feedback", kind.wire + " not accepted by the feedback service and dropped");
        }
        Feedback.Event event = new Feedback.Event(token, state, reference, "", kind, !automatic,
                state == Feedback.State.SENT || automatic ? "" : item.optString("message", ""));
        Feedback.noteState(app, event);
        Feedback.post(event);
    }

    /**
     * Every automatic summary not yet sent is deleted (the opt-in turned off, Clear history); the user's stay. On a
     * worker: DashSummary runs it on its own thread, after any summary begun before.
     */
    static void discardAutomatic(Context context) {
        Context app = context.getApplicationContext();
        synchronized (QUEUE_LOCK) {
            for (File file : files(app)) {
                if (file.getName().endsWith(AUTOMATIC_SUFFIX)) deleteQuietly(file);
            }
            recount(app);
        }
    }

    /** How many submissions wait to send. Cheap after the first look (which lists the folder). */
    static int pending(Context context) {
        Context app = context.getApplicationContext();
        int known = pendingCount;
        if (known >= 0 && countedFor == app) return known;
        synchronized (QUEUE_LOCK) {
            recount(app);
            return pendingCount;
        }
    }

    /** How many submissions wait, as last counted, or -1 when this process has not counted them: never a disk read. */
    static int pendingKnown(Context context) {
        int known = pendingCount;
        return countedFor == context.getApplicationContext() ? known : -1;
    }

    /** Under the lock. */
    private static void recount(Context app) {
        pendingCount = files(app).length;
        countedFor = app;
    }

    /** The notice was accepted: whatever waited for it goes now (the folder is looked at on the sending thread). */
    static void consented(Context context) {
        sendSoon(context);
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
            if ((created <= 0 || now - created > MAX_AGE_MS || created - now > MAX_AGE_MS) && deleteQuietly(file)) {
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

    /** @return whether the file was deleted */
    private static boolean deleteQueued(Context app, File file) {
        synchronized (QUEUE_LOCK) {
            boolean deleted = deleteQuietly(file);
            recount(app);
            return deleted;
        }
    }

    private static boolean deleteQuietly(File file) {
        try {
            return remover.test(file);
        } catch (RuntimeException refused) {
            return false;
        }
    }

    /** Rewrites a waiting submission, unless it was discarded meanwhile (never brings one back). */
    private static boolean rewrite(Context app, File file, JSONObject item) {
        synchronized (QUEUE_LOCK) {
            if (!file.isFile()) return false;
            try {
                replace(file, item);
                diskFailures.remove(item.optString("token"));
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
            if (next.isFile()) deleteQuietly(next);
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

    /** As a new process would: the pending count is read from disk again, and nothing is remembered. For tests. */
    static void forgetCache() {
        pendingCount = -1;
        countedFor = null;
        resumedFor = null;
        sendQueued.set(false);
        acknowledged.clear();
        done.clear();
        diskFailures.clear();
        remover = File::delete;
        cleanupFailures = 0;
    }

    private FeedbackOutbox() {}
}

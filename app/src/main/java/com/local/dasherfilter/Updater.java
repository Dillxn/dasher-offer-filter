package com.local.dasherfilter;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Update checks, download verification, and installation are separate observable stages. An APK is installed only
 * after its size, SHA-256, package, embedded version, and signing certificate all match the feed and this app.
 * Releases are read from the public Render feed, which needs no account.
 */
final class Updater {
    private static final int PERIODIC_JOB_ID = 7241;
    private static final int CONFIRMATION_NOTICE_ID = 7242;
    static final int RETRY_JOB_ID = 7243;
    private static final long PERIOD_MS = 900_000L;
    private static final long FLEX_MS = 300_000L;
    private static final long MIN_RETRY_DELAY_MS = 60_000L;
    private static final long SUCCESS_COOLDOWN_MS = 60_000L;
    private static final long PENDING_SESSION_GRACE_MS = 600_000L;
    private static final int MAX_FEED_BYTES = 16384;
    private static final int MAX_FAILURES = 8;
    private static final Pattern POSITIVE_INTEGER = Pattern.compile("[1-9][0-9]{0,9}");

    private static final String ENABLED = "enabled";
    /** Set once the retired Automatic updates switch's "off" was cleared (or checks were set since). */
    private static final String SWITCH_RETIRED = "switch_retired";
    private static final String STATUS = "status";
    private static final String ATTEMPT_AT = "attempt_at";
    private static final String CHECKED_AT = "checked_at";
    private static final String NEXT_CHECK_AT = "next_check_at";
    private static final String FAILURE_COUNT = "failure_count";
    private static final String ADVERTISED = "advertised";
    /** Which feed the advertised version came from: "Render" (older versions could also say "GitHub"). */
    private static final String ADVERTISED_VIA = "advertised_via";
    /** Automatic checks since the last logged update line that found the same, or were too soon to start. */
    private static final String QUIET = "quiet_";
    private static final String HELD = "held_";
    private static final String CADENCE_AT = "cadence_at";
    private static final String MANUAL_RETRY_REQUIRED = "manual_retry_required";
    private static final String SESSION = "session";
    private static final String SESSION_AT = "session_at";
    /** When the session was handed to Android to install, and whether Offer Filter was on screen then. */
    private static final String COMMITTED_AT = "committed_at";
    private static final String RELAUNCH_AT = "relaunch_at";
    private static final String RELAUNCH_FROM = "relaunch_from_code";
    private static final long INSTALL_WINDOW_MS = 10 * 60_000L;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    /** Reads a feed's JSON. Tests only stand in feed bytes here, which still pass every check; APKs never come here. */
    interface FeedReader {
        void read(String address, OutputStream out, long limit) throws IOException;
    }

    static volatile FeedReader feedReader = UpdateTransport::download;

    /** For tests: waits until checks queued so far have finished. */
    static void awaitIdle(long ms) throws Exception {
        WORKER.submit(() -> { }).get(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile WeakReference<Activity> foreground = new WeakReference<>(null);
    private static volatile Intent pendingConfirmation;
    /** Only skips work while waiting; it never authorizes an install or survives a process restart. */
    private static volatile VerifiedReady verifiedReady;

    private static final class VerifiedReady {
        final File file;
        final Release release;
        final long installedCode;
        final long size;
        final long modified;

        VerifiedReady(File file, Release release, long installedCode) {
            this.file = file;
            this.release = release;
            this.installedCode = installedCode;
            this.size = file.length();
            this.modified = file.lastModified();
        }

        boolean unchanged(Context app, PackageInfo installed) throws IOException {
            return installedCode == versionCode(installed) && file.equals(apkFile(app)) && file.isFile()
                    && file.length() == size && file.lastModified() == modified;
        }
    }

    /** A screen that can say the user is mid-task (for example typing rules), which an update would interrupt. */
    interface Busy {
        boolean midTask();
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("updates", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context context) {
        return prefs(context).getBoolean(ENABLED, true);
    }

    /** Checks on or off. Settings has no switch for this (checks are always automatic); tests turn them off. */
    static void setEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(ENABLED, value).putBoolean(SWITCH_RETIRED, true).apply();
        schedule(context);
    }

    /**
     * Automatic updates are always on since Settings lost its switch for them: an "off" an older version kept is
     * cleared, once, and noted in the log; the periodic check is scheduled again. Nothing about how an update is
     * verified changes.
     */
    static void retireSwitch(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.getBoolean(SWITCH_RETIRED, false)) return;
        boolean wasOff = !prefs.getBoolean(ENABLED, true);
        prefs.edit().putBoolean(ENABLED, true).putBoolean(SWITCH_RETIRED, true).apply();
        if (!wasOff) return;
        DiagnosticLog.log(context, "update", "automatic updates were turned off in an older version; Settings has no "
                + "such switch now, so they are on again");
        schedule(context);
    }

    static String status(Context context) {
        return prefs(context).getString(STATUS, "No update check has completed yet.");
    }

    /**
     * An event (downloading, installing, a confirmation, an install result): shown and always logged. The next
     * automatic check's result is then logged again, whatever it says.
     */
    static void status(Context context, String message) {
        prefs(context).edit().putString(STATUS, message).apply();
        DiagnosticLog.log(context, "update", message);
        DiagnosticLog.forget(context, "update", "result");
        resetCadence(context);
    }

    /** Shown in Settings only; whether it is logged is up to the caller. */
    private static void show(Context context, String message) {
        prefs(context).edit().putString(STATUS, message).apply();
    }

    private static void countQuiet(Context context, UpdateCadence.Trigger trigger) {
        count(context, QUIET + trigger.label());
    }

    private static void countHeld(Context context, UpdateCadence.Trigger trigger) {
        count(context, HELD + trigger.label());
    }

    private static void count(Context context, String key) {
        SharedPreferences prefs = prefs(context);
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).putLong(CADENCE_AT, System.currentTimeMillis()).apply();
    }

    private static void resetCadence(Context context) {
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor edit = prefs.edit();
        boolean any = false;
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(QUIET) || key.startsWith(HELD) || key.equals(CADENCE_AT)) {
                edit.remove(key);
                any = true;
            }
        }
        if (any) edit.apply();
    }

    /**
     * For a shared report: the automatic checks since the last logged update line that found the same result, and
     * those too soon to start, by what started them.
     */
    static String cadenceSummary(Context context) {
        SharedPreferences prefs = prefs(context);
        StringBuilder quiet = new StringBuilder();
        StringBuilder held = new StringBuilder();
        int quietTotal = 0;
        int heldTotal = 0;
        for (UpdateCadence.Trigger trigger : UpdateCadence.Trigger.values()) {
            int q = prefs.getInt(QUIET + trigger.label(), 0);
            int h = prefs.getInt(HELD + trigger.label(), 0);
            if (q > 0) quiet.append(quiet.length() == 0 ? "" : ", ").append(trigger.label()).append(' ').append(q);
            if (h > 0) held.append(held.length() == 0 ? "" : ", ").append(trigger.label()).append(' ').append(h);
            quietTotal += q;
            heldTotal += h;
        }
        long at = prefs.getLong(CADENCE_AT, 0);
        return "Automatic checks since the last logged update line: same result " + quietTotal
                + (quietTotal > 0 ? " (" + quiet + ")" : "") + "; too soon to start " + heldTotal
                + (heldTotal > 0 ? " (" + held + ")" : "")
                + (at > 0 ? "; latest " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX",
                        java.util.Locale.US).format(new java.util.Date(at)) : "");
    }

    static void foreground(Activity activity) {
        foreground = new WeakReference<>(activity);
    }

    static void background(Activity activity) {
        if (foreground.get() == activity) foreground.clear();
    }

    static String version(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException error) {
            return "unknown";
        }
    }

    /** True when an install result belongs to the session this app most recently committed. */
    static boolean isCurrentSession(Context context, int sessionId) {
        return sessionId >= 0 && sessionId == prefs(context).getInt(SESSION, -1);
    }

    /** Keeps the 15-minute periodic check scheduled while updates are enabled, and cancels all jobs otherwise. */
    static void schedule(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs == null) return;
        if (!enabled(context)) {
            jobs.cancel(PERIODIC_JOB_ID);
            jobs.cancel(RETRY_JOB_ID);
            return;
        }
        JobInfo pending = jobs.getPendingJob(PERIODIC_JOB_ID);
        if (pending == null || pending.getIntervalMillis() != PERIOD_MS || pending.getFlexMillis() != FLEX_MS) {
            int result = jobs.schedule(new JobInfo.Builder(PERIODIC_JOB_ID, jobComponent(context))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(PERIOD_MS, FLEX_MS)
                    .setPersisted(true)
                    .build());
            DiagnosticLog.log(context, "update", "periodic scheduling result=" + result);
        }
    }

    /** Schedules a real one-shot retry job, no sooner than one minute from now. */
    static void retry(Context context, long delay) {
        retry(context, delay, true);
    }

    /** As {@link #retry(Context, long)}; logged when {@code log}, or when Android refused it. */
    private static void retry(Context context, long delay, boolean log) {
        if (!enabled(context)) return;
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs == null) return;
        int result = jobs.schedule(new JobInfo.Builder(RETRY_JOB_ID, jobComponent(context))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(Math.max(MIN_RETRY_DELAY_MS, delay))
                .setPersisted(true)
                .build());
        if (log || result != JobScheduler.RESULT_SUCCESS) {
            DiagnosticLog.log(context, "update", "one-shot retry scheduled result=" + result + " earliestDelayMs="
                    + delay);
        }
    }

    private static ComponentName jobComponent(Context context) {
        return new ComponentName(context, UpdateJobService.class);
    }

    /**
     * Runs one check on the worker thread. A check already in progress absorbs this request.
     *
     * @param manual the user asked: bypasses the cooldown and may install while the app is open; otherwise an
     *               automatic check, which waits {@link UpdateCadence#AUTOMATIC_SPACING_MS} after the last one
     * @param done   posted to the main thread once this request is finished
     */
    static Future<?> check(Context context, boolean manual, Runnable done) {
        return check(context, manual ? UpdateCadence.Trigger.MANUAL : UpdateCadence.Trigger.AUTOMATIC, done);
    }

    /**
     * Runs one check on the worker thread, if what started it may start one now ({@link UpdateCadence}). A check
     * already in progress absorbs this request. Only a manual check bypasses the cooldown and may install while the
     * app is open. An automatic check logs its result only when it differs from the last one logged (or a day
     * passed); a failure, a manual check and every install step are always logged.
     *
     * @param done posted to the main thread once this request is finished
     */
    static Future<?> check(Context context, UpdateCadence.Trigger trigger, Runnable done) {
        Context app = context.getApplicationContext();
        if (!BUSY.compareAndSet(false, true)) {
            if (done != null) MAIN.post(done);
            return CompletableFuture.completedFuture(null);
        }
        FutureTask<Void> task = new FutureTask<Void>(() -> {
            runCheck(app, trigger);
            return null;
        }) {
            @Override protected void done() {
                BUSY.set(false);
                if (done != null) MAIN.post(done);
            }
        };
        WORKER.execute(task);
        return task;
    }

    private static void runCheck(Context app, UpdateCadence.Trigger trigger) {
        boolean manual = trigger.manual();
        try {
            if (!enabled(app) && !manual) return;
            long now = System.currentTimeMillis();
            SharedPreferences prefs = prefs(app);
            if (!UpdateCadence.mayStart(trigger, now, prefs.getLong(ATTEMPT_AT, 0), prefs.getLong(NEXT_CHECK_AT, 0))) {
                countHeld(app, trigger);
                return;
            }
            prefs.edit().putLong(ATTEMPT_AT, now).apply();
            if (manual) DiagnosticLog.log(app, "update", "check start manual=true installed=" + version(app));
            PackageInfo installed = app.getPackageManager().getPackageInfo(app.getPackageName(), signingFlags());
            VerifiedReady ready = verifiedReady;
            if (!manual && ready != null && ready.unchanged(app, installed) && UpdateHold.holds(app)
                    && !prefs.getBoolean(MANUAL_RETRY_REQUIRED, false)
                    && app.getPackageManager().canRequestPackageInstalls()) {
                // The first check already downloaded and verified this APK. A five-minute wakeup during a
                // hours-long dash only needs to keep waiting. Once the dash ends, its hold reaches its ceiling
                // (UpdateHold) or the user asks, the feed and every APK check run again before installation; these
                // cheap file checks cannot install it.
                hold(app, ready.release);
                settle(app, trigger, AFTER_DASH, ready.release);
                retry(app, DASH_RETRY_MS, false);
                return;
            }
            verifiedReady = null;
            if (manual) status(app, "Checking for updates…");
            else show(app, "Checking for updates…");

            Release release = newestRelease(app, now, manual);
            long advertised = release.json.getLong("versionCode");
            prefs.edit()
                    .putLong(CHECKED_AT, now)
                    .putLong(NEXT_CHECK_AT, now + SUCCESS_COOLDOWN_MS)
                    .putInt(FAILURE_COUNT, 0)
                    .putString(ADVERTISED, release.json.getString("versionName"))
                    .putString(ADVERTISED_VIA, "Render")
                    .apply();
            if (!UpdatePolicy.isNewer(advertised, versionCode(installed))) {
                settle(app, trigger, (advertised == versionCode(installed)
                        ? "Up to date: " + installed.versionName
                        : "Feed is older than this installation; no downgrade attempted."), release);
                clearReady(app);
                JobScheduler jobs = app.getSystemService(JobScheduler.class);
                if (jobs != null) jobs.cancel(RETRY_JOB_ID);
                return;
            }
            File apk = verifiedApk(app, release, installed);
            verifiedReady = new VerifiedReady(apk, release, versionCode(installed));
            install(app, apk, release, trigger);
        } catch (Exception error) {
            if (Thread.currentThread().isInterrupted()) {
                status(app, "Update check interrupted; no installation started.");
                return;
            }
            int failures = Math.min(MAX_FAILURES, prefs(app).getInt(FAILURE_COUNT, 0) + 1);
            long delay = UpdatePolicy.retryDelayMillis(failures);
            prefs(app).edit()
                    .putInt(FAILURE_COUNT, failures)
                    .putLong(NEXT_CHECK_AT, System.currentTimeMillis() + delay)
                    .apply();
            String failed = "Update failed: " + error.getClass().getSimpleName() + ": " + error.getMessage()
                    + ". Retry requested after " + delay / 60000 + " min; Android may defer it.";
            // A failure is always logged in full, automatic or not; Settings shows it in plain words.
            show(app, failedInPlainWords(error, delay));
            DiagnosticLog.logAndRemember(app, "update", "result", failed,
                    manual ? "" : "automatic check (" + trigger.label() + "): ");
            resetCadence(app);
            retry(app, delay);
        }
    }

    /**
     * What Settings says of a failed check: no exception names (the log keeps them), only whether the server was out
     * of reach or the update did not pass, and when it tries again.
     */
    static String failedInPlainWords(Throwable error, long retryDelayMs) {
        boolean unreachable = false;
        for (Throwable cause = error; cause != null && !unreachable; cause = cause.getCause()) {
            unreachable = cause instanceof java.net.UnknownHostException || cause instanceof java.net.SocketException
                    || cause instanceof java.net.SocketTimeoutException || cause instanceof javax.net.ssl.SSLException;
        }
        long minutes = Math.max(1, retryDelayMs / 60_000L);
        return (unreachable ? "Couldn't reach the update server (no connection?)."
                : "The update check didn't finish; nothing was installed.") + " Tries again in " + minutes + " min.";
    }

    /**
     * A check's result that is a state, not an event: shown in Settings, and logged in full when the user asked; an
     * automatic check logs it only when it differs from the last one logged (with the feed it came from), and
     * otherwise counts it for the report.
     */
    private static void settle(Context app, UpdateCadence.Trigger trigger, String message, Release release) {
        show(app, message);
        String state = message + " (installed " + version(app) + "; newest feed "
                + release.json.optString("versionName", "?") + " via Render)";
        if (trigger.manual()) {
            DiagnosticLog.logAndRemember(app, "update", "result", state, "");
            resetCadence(app);
        } else if (DiagnosticLog.logOnChange(app, "update", "result", state,
                "automatic check (" + trigger.label() + "): ")) {
            resetCadence(app);
        } else {
            countQuiet(app, trigger);
        }
    }

    /** A release as the feed advertised it. */
    static final class Release {
        final JSONObject json;

        Release(JSONObject json) {
            this.json = json;
        }

        long versionCode() {
            return json.optLong("versionCode", 0);
        }
    }

    /** The public, accountless signed release feed. */
    private static Release newestRelease(Context app, long now, boolean manual) throws Exception {
        return fetchRelease(UpdatePolicy.FEED + "?t=" + now);
    }

    private static Release fetchRelease(String address) throws IOException, JSONException {
        ByteArrayOutputStream feed = new ByteArrayOutputStream();
        feedReader.read(address, feed, MAX_FEED_BYTES);
        Release release = new Release(new JSONObject(feed.toString(StandardCharsets.UTF_8.name())));
        checkMetadata(release);
        return release;
    }

    /** The cached APK when it still matches this release; otherwise a fresh download that passed every check. */
    private static File verifiedApk(Context app, Release release, PackageInfo installed) throws Exception {
        File apk = apkFile(app);
        if (apk.isFile()) {
            try {
                validateApk(app, apk, release, installed);
                return apk;
            } catch (Exception mismatch) {
                DiagnosticLog.log(app, "update", "cached APK does not match current release; replacing");
            }
        }
        File part = new File(apk.getParentFile(), "download.apk");
        try {
            status(app, "Downloading " + release.json.getString("versionName")
                    + "…");
            try (OutputStream out = new FileOutputStream(part)) {
                UpdateTransport.download(release.json.getString("apkUrl"), out, release.json.getLong("size"));
            }
            validateApk(app, part, release, installed);
            if (apk.exists() && !apk.delete()) throw new IOException("Could not replace old update");
            if (!part.renameTo(apk)) throw new IOException("Could not save update");
            return apk;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            if (part.exists()) part.delete();
        }
    }

    private static File apkFile(Context context) throws IOException {
        File dir = new File(context.getFilesDir(), "updates");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create update folder");
        return new File(dir, "OfferFilter.apk");
    }

    private static void checkMetadata(Release release) throws IOException, JSONException {
        JSONObject json = release.json;
        if (!POSITIVE_INTEGER.matcher(String.valueOf(json.get("versionCode"))).matches()
                || !POSITIVE_INTEGER.matcher(String.valueOf(json.get("size"))).matches()) {
            throw new IOException("Noninteger version or size");
        }
        UpdatePolicy.validate(json.getString("packageName"), json.getLong("versionCode"),
                json.getString("apkUrl"), json.getString("sha256"), json.getLong("size"),
                json.optString("encoding", "raw"));
        if (!UpdatePolicy.validVersionName(json.getString("versionName"))) {
            throw new IOException("Invalid version name");
        }
    }

    private static void validateApk(Context context, File file, Release checked, PackageInfo installed)
            throws Exception {
        checkMetadata(checked);
        JSONObject release = checked.json;
        if (file.length() != release.getLong("size")) throw new IOException("Update size mismatch");
        if (!sha256(file).equals(release.getString("sha256"))) throw new IOException("Update checksum mismatch");
        PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), signingFlags());
        if (archive == null
                || !context.getPackageName().equals(archive.packageName)
                || versionCode(archive) != release.getLong("versionCode")
                || !UpdatePolicy.isNewer(versionCode(archive), versionCode(installed))
                || !release.getString("versionName").equals(archive.versionName)
                || !signingCompatible(installed, archive)) {
            throw new IOException("Update package/version/signing certificate mismatch");
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    @SuppressWarnings("deprecation")
    private static int signingFlags() {
        return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    @SuppressWarnings("deprecation")
    private static Set<Signature> signers(PackageInfo info) {
        Signature[] all = Build.VERSION.SDK_INT >= 28 && info.signingInfo != null
                ? info.signingInfo.getApkContentsSigners() : info.signatures;
        Set<Signature> signers = new HashSet<>();
        if (all != null) Collections.addAll(signers, all);
        return signers;
    }

    /** The update must be signed by the installed signer, or by a rotated key whose lineage includes it. */
    private static boolean signingCompatible(PackageInfo installed, PackageInfo archive) {
        Set<Signature> current = signers(installed);
        if (current.isEmpty()) return false;
        if (Build.VERSION.SDK_INT < 28) return current.equals(signers(archive));
        if (archive.signingInfo == null || installed.signingInfo == null) return false;
        if (archive.signingInfo.hasMultipleSigners() || installed.signingInfo.hasMultipleSigners()) {
            return current.equals(signers(archive));
        }
        Signature[] history = archive.signingInfo.getSigningCertificateHistory();
        return history != null && new HashSet<>(Arrays.asList(history)).containsAll(current);
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    /** What an automatic install says while a dash is on: it waits for the dash to end. */
    static final String AFTER_DASH = "Update ready: installs after your dash";
    /** What a verified update says while updates from this app are not allowed; a tap on Updates opens the switch. */
    static final String BLOCKED = "Update ready: tap to allow updates from " + AppName.NAME;
    /** How often an update held for a dash is tried again. */
    static final long DASH_RETRY_MS = 5 * 60_000L;
    /**
     * The version name of a verified update held back (by the dash, the switch or a declined install); see below. Not
     * "held_…": resetCadence clears every key with that prefix.
     */
    private static final String HELD_VERSION = "ready_version";

    /**
     * A verified update an automatic check held back, as its version name, or null: for the homepage's "Update ready ·
     * Install now" line, whose tap is the user's own check (the fresh feed and every APK check again). Kept until the
     * update is handed to Android or the app is up to date. It never authorizes anything.
     */
    static String heldVersion(Context context) {
        return prefs(context).getString(HELD_VERSION, null);
    }

    private static void hold(Context context, Release release) {
        String name = release.json.optString("versionName", "");
        if (!name.equals(heldVersion(context))) prefs(context).edit().putString(HELD_VERSION, name).apply();
    }

    /** Whether the verified update waits only for updates from this app to be allowed (a tap then opens the switch). */
    static boolean waitsForPermission(Context context) {
        return heldVersion(context) != null && !context.getPackageManager().canRequestPackageInstalls();
    }

    /**
     * Why a verified update does not install now (it is retried), or null when it may. An automatic one waits while a
     * dash has not been seen to end ({@link Dashing#awaitingEnd}): installing closes Offer Filter, and with it its
     * half of a split screen beside Dasher and anything it was watching; that wait ends at its ceiling
     * ({@link UpdateHold}), logged with its reason. It also waits while an offer or delivery is up, or while the user
     * is in the middle of something on Offer Filter's screen an update would lose (typing rules). The user's own
     * check still installs mid-dash; only Dasher on screen holds it back.
     */
    static String heldBack(Context context, boolean manual) {
        if (!manual && Dashing.awaitingEnd(context)) {
            String ceiling = UpdateHold.ceilingReached(context, System.currentTimeMillis());
            if (ceiling == null) return AFTER_DASH;
            DiagnosticLog.log(context, "update", "dash hold reached its ceiling: " + ceiling
                    + "; the freshly verified update may install");
        }
        boolean offerOrDelivery = OfferNotificationService.hasActiveOffer() || ActiveRouteStore.load(context) != null;
        if (OfferFilterService.isDasherForeground() || (!manual && offerOrDelivery)) {
            return "Update verified; installation deferred while an offer/delivery is active.";
        }
        // With Offer Filter open, the update installs too (the screen shows it and reopens after), except while the
        // user is in the middle of something an update would lose, such as typing rules.
        Activity open = foreground.get();
        if (!manual && open instanceof Busy && ((Busy) open).midTask()) {
            return "Update ready; it installs when you leave Settings.";
        }
        return null;
    }

    private static void install(Context context, File file, Release checked, UpdateCadence.Trigger trigger)
            throws Exception {
        boolean manual = trigger.manual();
        JSONObject release = checked.json;
        SharedPreferences prefs = prefs(context);
        if (!enabled(context) && !manual) return;
        if (!manual && prefs.getBoolean(MANUAL_RETRY_REQUIRED, false)) {
            hold(context, checked);
            settle(context, trigger, "Android declined the previous installation. Manual retry is required.", checked);
            return;
        }
        if (manual) prefs.edit().remove(MANUAL_RETRY_REQUIRED).apply();
        if (!context.getPackageManager().canRequestPackageInstalls()) {
            hold(context, checked);
            settle(context, trigger, BLOCKED, checked);
            // Once per version, a quiet notice whose tap opens the switch; the homepage asks too (Allow updates).
            UpdateNotices.installsBlocked(context, checked.versionCode());
            return;
        }
        String wait = heldBack(context, manual);
        if (wait != null) {
            hold(context, checked);
            settle(context, trigger, wait, checked);
            // A dash lasts hours: looked at again every few minutes, not every minute.
            retry(context, AFTER_DASH.equals(wait) ? DASH_RETRY_MS : MIN_RETRY_DELAY_MS, manual);
            return;
        }
        if (Thread.currentThread().isInterrupted()) return;
        if (manual && pendingConfirmation != null && foreground.get() != null) {
            confirmation(context, pendingConfirmation);
            return;
        }

        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        int oldId = prefs.getInt(SESSION, -1);
        PackageInstaller.SessionInfo old = oldId < 0 ? null : installer.getSessionInfo(oldId);
        if (old != null) {
            long age = System.currentTimeMillis() - prefs.getLong(SESSION_AT, 0);
            if (old.isSealed() && age >= 0 && age < PENDING_SESSION_GRACE_MS && !manual) {
                settle(context, trigger, "An installation is already pending; check Android's confirmation "
                        + "notification.", checked);
                return;
            }
            installer.abandonSession(oldId);
        }

        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setAppLabel(AppName.NAME);
        params.setSize(file.length());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(manual
                    ? PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                    : PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = installer.createSession(params);
        prefs.edit().putInt(SESSION, id).putLong(SESSION_AT, System.currentTimeMillis()).commit();
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(file);
                 OutputStream out = session.openWrite("base.apk", 0, file.length())) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Install interrupted");
                    out.write(buffer, 0, count);
                }
                session.fsync(out);
            }
            // Mutable so the installer can attach the result extras; explicit, so no other app can receive it.
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            Intent resultIntent = new Intent(context, UpdateReceiver.class).setAction(UpdateReceiver.INSTALL_RESULT);
            PendingIntent result = PendingIntent.getBroadcast(context, id, resultIntent, flags);
            status(context, "Installing " + release.getString("versionName") + "… Android may request confirmation.");
            installStarted(context, foreground.get() != null);
            session.commit(result.getIntentSender());
        } catch (Exception error) {
            installer.abandonSession(id);
            prefs.edit().remove(SESSION).apply();
            throw error;
        }
    }

    /** Shows Android's install confirmation: directly when the app is open, otherwise as a quiet notification. */
    static void confirmation(Context context, Intent intent) {
        pendingConfirmation = intent;
        // Android is waiting for the user, not installing. Keep its session/intent, but release the blocking
        // updating cover so the user can reach Settings and reopen the confirmation after dismissing it.
        prefs(context).edit().remove(COMMITTED_AT).apply();
        status(context, "Android requires installation confirmation. Tap Updates in Settings or the update "
                + "notification.");
        MAIN.post(() -> {
            Activity activity = foreground.get();
            if (activity != null && !activity.isFinishing()) {
                try {
                    activity.startActivity(intent);
                    return;
                } catch (RuntimeException error) {
                    DiagnosticLog.log(context, "update", "confirmation UI unavailable");
                }
            }
            postConfirmationNotice(context, intent);
        });
    }

    private static void postConfirmationNotice(Context context, Intent intent) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        UpdateNotices.ensureChannel(manager);
        PendingIntent action = PendingIntent.getActivity(context, CONFIRMATION_NOTICE_ID, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(CONFIRMATION_NOTICE_ID, new Notification.Builder(context, UpdateNotices.CHANNEL_ID)
                .setSmallIcon(OfferAlerts.smallIcon(context))
                .setContentTitle(AppName.NAME + " update ready")
                .setContentText("Tap to confirm installation.")
                .setContentIntent(action)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build());
    }

    /**
     * The update was handed to Android to install. With Offer Filter on screen, the screen shows it is updating
     * (and takes no input) until the new version starts, which then opens Offer Filter again.
     */
    static void installStarted(Context context, boolean onScreen) {
        long now = System.currentTimeMillis();
        // Handed to Android: no longer an update held back.
        SharedPreferences.Editor edit = prefs(context).edit().putLong(COMMITTED_AT, now).remove(HELD_VERSION);
        if (onScreen) {
            edit.putLong(RELAUNCH_AT, now).putLong(RELAUNCH_FROM, versionCode(context));
        } else {
            edit.remove(RELAUNCH_AT).remove(RELAUNCH_FROM);
        }
        edit.commit();
    }

    /** An update is being installed: handed to Android, and neither failed nor finished yet. */
    static boolean installing(Context context) {
        return installingSince(context) > 0;
    }

    /** When the update being installed was handed to Android (wall clock), or 0 while none is. */
    static long installingSince(Context context) {
        SharedPreferences prefs = prefs(context);
        long at = prefs.getLong(COMMITTED_AT, 0);
        long age = System.currentTimeMillis() - at;
        return at > 0 && prefs.contains(SESSION) && age >= 0 && age < INSTALL_WINDOW_MS ? at : 0;
    }

    /**
     * After an update installed while Offer Filter was on screen, opens it again. Safe to ask more than once: it
     * brings back the one screen, and the screen itself says it is open ({@link #relaunched}). Returns whether it
     * asked Android to open it.
     */
    static boolean relaunchAfterUpdate(Context context) {
        if (!relaunchPending(context)) return false;
        try {
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            return true;
        } catch (RuntimeException refused) {
            DiagnosticLog.log(context, "update", "reopen after update refused: " + refused.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Whether an update installed while Offer Filter was on screen, moments ago, and this is the new version running:
     * its screen is to be opened again.
     */
    static boolean relaunchPending(Context context) {
        SharedPreferences prefs = prefs(context);
        long at = prefs.getLong(RELAUNCH_AT, 0);
        long age = System.currentTimeMillis() - at;
        if (at <= 0 || age < 0 || age > INSTALL_WINDOW_MS) return false;
        // Only once the new version is the one running.
        return versionCode(context) > prefs.getLong(RELAUNCH_FROM, Long.MAX_VALUE);
    }

    /** Offer Filter's screen is open again: nothing more to reopen. */
    static void relaunched(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.contains(RELAUNCH_AT)) prefs.edit().remove(RELAUNCH_AT).remove(RELAUNCH_FROM).apply();
    }

    private static long versionCode(Context context) {
        try {
            return versionCode(context.getPackageManager().getPackageInfo(context.getPackageName(), 0));
        } catch (android.content.pm.PackageManager.NameNotFoundException impossible) {
            return 0;
        }
    }

    static void installationFailed(Context context, int code, String detail) {
        pendingConfirmation = null;
        prefs(context).edit()
                .remove(SESSION)
                .remove(SESSION_AT)
                .remove(COMMITTED_AT)
                .remove(RELAUNCH_AT)
                .remove(RELAUNCH_FROM)
                .putBoolean(MANUAL_RETRY_REQUIRED, true)
                .apply();
        // Android's code and words go to the log; Settings says it in plain words.
        DiagnosticLog.log(context, "update", "Android installation failed (" + code + "): " + detail);
        status(context, INSTALL_FAILED);
    }

    /** What Settings says after Android did not install an update. */
    static final String INSTALL_FAILED = "The update didn't install. Tap Updates to try again.";

    /** Forgets any prepared update: session bookkeeping, the cached APK, and the confirmation notice. */
    static void clearReady(Context context) {
        pendingConfirmation = null;
        verifiedReady = null;
        prefs(context).edit()
                .remove(SESSION)
                .remove(SESSION_AT)
                .remove(COMMITTED_AT)
                .remove(MANUAL_RETRY_REQUIRED)
                .remove(HELD_VERSION)
                // Written by 0.4.5 and earlier but never read; removed here so old installs shed them.
                .remove("ready")
                .remove("confirmation_needed")
                .apply();
        try {
            //noinspection ResultOfMethodCallIgnored
            apkFile(context).delete();
        } catch (IOException ignored) {
            // Nothing cached.
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(CONFIRMATION_NOTICE_ID);
        UpdateNotices.cancelBlocked(context);
    }

    private Updater() {}
}

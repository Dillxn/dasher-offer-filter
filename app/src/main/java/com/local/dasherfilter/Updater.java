package com.local.dasherfilter;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
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
 * Releases are read from Render and, once the user connects GitHub, from the app's private repository too; the newer
 * of the two is used, and one being unreachable does not stop the other.
 */
final class Updater {
    private static final int PERIODIC_JOB_ID = 7241;
    private static final int CONFIRMATION_NOTICE_ID = 7242;
    private static final int RETRY_JOB_ID = 7243;
    private static final long PERIOD_MS = 900_000L;
    private static final long FLEX_MS = 300_000L;
    private static final long MIN_RETRY_DELAY_MS = 60_000L;
    private static final long SUCCESS_COOLDOWN_MS = 60_000L;
    private static final long PENDING_SESSION_GRACE_MS = 600_000L;
    private static final int MAX_FEED_BYTES = 16384;
    private static final int MAX_FAILURES = 8;
    private static final Pattern POSITIVE_INTEGER = Pattern.compile("[1-9][0-9]{0,9}");
    private static final String UPDATE_CHANNEL_ID = "updates";

    private static final String ENABLED = "enabled";
    private static final String STATUS = "status";
    private static final String ATTEMPT_AT = "attempt_at";
    private static final String CHECKED_AT = "checked_at";
    private static final String NEXT_CHECK_AT = "next_check_at";
    private static final String FAILURE_COUNT = "failure_count";
    private static final String ADVERTISED = "advertised";
    private static final String MANUAL_RETRY_REQUIRED = "manual_retry_required";
    private static final String SESSION = "session";
    private static final String SESSION_AT = "session_at";
    /** When the session was handed to Android to install, and whether Offer Filter was on screen then. */
    private static final String COMMITTED_AT = "committed_at";
    private static final String RELAUNCH_AT = "relaunch_at";
    private static final String RELAUNCH_FROM = "relaunch_from_code";
    private static final long INSTALL_WINDOW_MS = 10 * 60_000L;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile WeakReference<Activity> foreground = new WeakReference<>(null);
    private static volatile Intent pendingConfirmation;

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

    static void setEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(ENABLED, value).apply();
        schedule(context);
    }

    static String status(Context context) {
        return prefs(context).getString(STATUS, "No update check has completed yet.");
    }

    static void status(Context context, String message) {
        prefs(context).edit().putString(STATUS, message).apply();
        DiagnosticLog.log(context, "update", message);
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
        if (!enabled(context)) return;
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs == null) return;
        int result = jobs.schedule(new JobInfo.Builder(RETRY_JOB_ID, jobComponent(context))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(Math.max(MIN_RETRY_DELAY_MS, delay))
                .setPersisted(true)
                .build());
        DiagnosticLog.log(context, "update", "one-shot retry scheduled result=" + result + " earliestDelayMs=" + delay);
    }

    private static ComponentName jobComponent(Context context) {
        return new ComponentName(context, UpdateJobService.class);
    }

    /**
     * Runs one check on the worker thread. A check already in progress absorbs this request.
     *
     * @param manual the user asked: bypasses the cooldown and may install while the app is open
     * @param done   posted to the main thread once this request is finished
     */
    static Future<?> check(Context context, boolean manual, Runnable done) {
        Context app = context.getApplicationContext();
        if (!BUSY.compareAndSet(false, true)) {
            if (done != null) MAIN.post(done);
            return CompletableFuture.completedFuture(null);
        }
        FutureTask<Void> task = new FutureTask<Void>(() -> {
            runCheck(app, manual);
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

    private static void runCheck(Context app, boolean manual) {
        try {
            if (!enabled(app) && !manual) return;
            long now = System.currentTimeMillis();
            if (!manual && UpdatePolicy.coolingDown(now, prefs(app).getLong(NEXT_CHECK_AT, 0))) return;
            prefs(app).edit().putLong(ATTEMPT_AT, now).apply();
            DiagnosticLog.log(app, "update", "check start manual=" + manual + " installed=" + version(app));
            PackageInfo installed = app.getPackageManager().getPackageInfo(app.getPackageName(), signingFlags());
            status(app, "Checking for updates…");

            Release release = newestRelease(app, now);
            long advertised = release.json.getLong("versionCode");
            prefs(app).edit()
                    .putLong(CHECKED_AT, now)
                    .putLong(NEXT_CHECK_AT, now + SUCCESS_COOLDOWN_MS)
                    .putInt(FAILURE_COUNT, 0)
                    .putString(ADVERTISED, release.json.getString("versionName"))
                    .apply();
            if (!UpdatePolicy.isNewer(advertised, versionCode(installed))) {
                status(app, (advertised == versionCode(installed)
                        ? "Up to date: " + installed.versionName
                        : "Feed is older than this installation; no downgrade attempted.") + release.caveat);
                clearReady(app);
                JobScheduler jobs = app.getSystemService(JobScheduler.class);
                if (jobs != null) jobs.cancel(RETRY_JOB_ID);
                return;
            }
            install(app, verifiedApk(app, release, installed), release, manual);
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
            status(app, "Update failed: " + error.getClass().getSimpleName() + ": " + error.getMessage()
                    + ". Retry requested after " + delay / 60000 + " min; Android may defer it.");
            retry(app, delay);
        }
    }

    /** A release as one channel advertised it, with the token that channel needs (null for Render). */
    static final class Release {
        final UpdatePolicy.Channel channel;
        final JSONObject json;
        final String token;
        /** A note about the other channel when it could not be read, for the status line. */
        String caveat = "";

        Release(UpdatePolicy.Channel channel, JSONObject json, String token) {
            this.channel = channel;
            this.json = json;
            this.token = token;
        }

        long versionCode() {
            return json.optLong("versionCode", 0);
        }
    }

    /**
     * The newest release among the channels that answered: Render always, and the repository while GitHub is
     * connected. A tie goes to the first (Render).
     *
     * @throws Exception the first channel's failure when none answered
     */
    private static Release newestRelease(Context app, long now) throws Exception {
        Release render = null;
        Release repo = null;
        Exception renderError = null;
        Exception repoError = null;
        try {
            render = fetchRelease(UpdatePolicy.Channel.RENDER, UpdatePolicy.FEED + "?t=" + now, null);
        } catch (Exception error) {
            renderError = error;
        }
        if (GitHubConnect.configured()) {
            try {
                String token = GitHubConnect.token(app);
                if (token != null) repo = fetchRelease(UpdatePolicy.Channel.REPO, UpdatePolicy.REPO_FEED, token);
            } catch (Exception error) {
                repoError = error;
                DiagnosticLog.log(app, "update", "repository feed failed: " + error.getClass().getSimpleName());
            }
        }
        Release newest = newer(render, repo);
        if (newest == null) throw renderError != null ? renderError : repoError;
        if (repoError != null) newest.caveat = " (GitHub not reachable: " + repoError.getMessage() + ")";
        else if (renderError != null && newest.channel == UpdatePolicy.Channel.REPO) newest.caveat = " (from GitHub)";
        return newest;
    }

    /** The one with the higher version code; {@code first} on a tie; null only when both are null. */
    static Release newer(Release first, Release second) {
        if (first == null) return second;
        if (second == null) return first;
        return second.versionCode() > first.versionCode() ? second : first;
    }

    private static Release fetchRelease(UpdatePolicy.Channel channel, String address, String token)
            throws IOException, JSONException {
        ByteArrayOutputStream feed = new ByteArrayOutputStream();
        UpdateTransport.download(channel, address, token, feed, MAX_FEED_BYTES);
        Release release = new Release(channel, new JSONObject(feed.toString(StandardCharsets.UTF_8.name())), token);
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
                    + (release.channel == UpdatePolicy.Channel.REPO ? " from GitHub…" : "…"));
            try (OutputStream out = new FileOutputStream(part)) {
                UpdateTransport.download(release.channel, release.json.getString("apkUrl"), release.token, out,
                        release.json.getLong("size"));
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
        UpdatePolicy.validate(release.channel, json.getString("packageName"), json.getLong("versionCode"),
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

    private static void install(Context context, File file, Release checked, boolean manual) throws Exception {
        JSONObject release = checked.json;
        SharedPreferences prefs = prefs(context);
        if (!enabled(context) && !manual) return;
        if (!manual && prefs.getBoolean(MANUAL_RETRY_REQUIRED, false)) {
            status(context, "Android declined the previous installation. Manual retry is required.");
            return;
        }
        if (manual) prefs.edit().remove(MANUAL_RETRY_REQUIRED).apply();
        if (!context.getPackageManager().canRequestPackageInstalls()) {
            status(context, "Update verified and ready. Enable Allow from this source under Allow automatic installs.");
            return;
        }
        boolean offerOrDelivery = OfferNotificationService.hasActiveOffer() || ActiveRouteStore.load(context) != null;
        if (OfferFilterService.isDasherForeground() || (!manual && offerOrDelivery)) {
            status(context, "Update verified; installation deferred while an offer/delivery is active.");
            retry(context, MIN_RETRY_DELAY_MS);
            return;
        }
        // With Offer Filter open, the update installs too (the screen shows it and reopens after), except while the
        // user is in the middle of something an update would lose, such as typing rules.
        Activity open = foreground.get();
        if (!manual && open instanceof Busy && ((Busy) open).midTask()) {
            status(context, "Update ready; it installs when you leave Settings.");
            retry(context, MIN_RETRY_DELAY_MS);
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
                status(context, "An installation is already pending; check Android's confirmation notification.");
                return;
            }
            installer.abandonSession(oldId);
        }

        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setAppLabel("Offer Filter");
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
        status(context, "Android requires installation confirmation. Tap Check / install update or the update "
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
        NotificationChannel channel =
                new NotificationChannel(UPDATE_CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        manager.createNotificationChannel(channel);
        PendingIntent action = PendingIntent.getActivity(context, CONFIRMATION_NOTICE_ID, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(CONFIRMATION_NOTICE_ID, new Notification.Builder(context, UPDATE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Offer Filter update ready")
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
        SharedPreferences.Editor edit = prefs(context).edit().putLong(COMMITTED_AT, now);
        if (onScreen) {
            edit.putLong(RELAUNCH_AT, now).putLong(RELAUNCH_FROM, versionCode(context));
        } else {
            edit.remove(RELAUNCH_AT).remove(RELAUNCH_FROM);
        }
        edit.commit();
    }

    /** An update is being installed: handed to Android, and neither failed nor finished yet. */
    static boolean installing(Context context) {
        SharedPreferences prefs = prefs(context);
        long at = prefs.getLong(COMMITTED_AT, 0);
        long age = System.currentTimeMillis() - at;
        return at > 0 && prefs.contains(SESSION) && age >= 0 && age < INSTALL_WINDOW_MS;
    }

    /**
     * After an update installed while Offer Filter was on screen, opens it again. Safe to ask more than once: it
     * brings back the one screen, and the screen itself says it is open ({@link #relaunched}). Returns whether it
     * asked Android to open it.
     */
    static boolean relaunchAfterUpdate(Context context) {
        SharedPreferences prefs = prefs(context);
        long at = prefs.getLong(RELAUNCH_AT, 0);
        long age = System.currentTimeMillis() - at;
        if (at <= 0 || age < 0 || age > INSTALL_WINDOW_MS) return false;
        // Only once the new version is the one running.
        if (versionCode(context) <= prefs.getLong(RELAUNCH_FROM, Long.MAX_VALUE)) return false;
        try {
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            return true;
        } catch (RuntimeException refused) {
            DiagnosticLog.log(context, "update", "reopen after update refused: " + refused.getClass().getSimpleName());
            return false;
        }
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
        status(context, "Android installation failed (" + code + "): " + detail
                + ". Tap Check / install update to retry.");
    }

    /** Forgets any prepared update: session bookkeeping, the cached APK, and the confirmation notice. */
    static void clearReady(Context context) {
        pendingConfirmation = null;
        prefs(context).edit()
                .remove(SESSION)
                .remove(SESSION_AT)
                .remove(COMMITTED_AT)
                .remove(MANUAL_RETRY_REQUIRED)
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
    }

    private Updater() {}
}

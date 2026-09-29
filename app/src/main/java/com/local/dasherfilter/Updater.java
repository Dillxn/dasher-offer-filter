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
import org.json.JSONObject;
import java.io.*;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Update checks, download verification, and installation are separate observable stages. */
final class Updater {
    private static final int JOB = 7241, NOTICE = 7242, RETRY_JOB = 7243, RETRY_JOB_ALT = 7244;
    private static final long PERIOD_MS = 900_000L, FLEX_MS = 300_000L;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    /** A manual check requested while another check runs; it runs right after instead of being dropped. */
    private static final AtomicBoolean MANUAL_QUEUED = new AtomicBoolean();
    /** The network path; replaceable only by tests. Production always uses UpdateTransport (also used by the release probe). */
    interface Fetcher { void download(String address, OutputStream output, long limit) throws IOException; }
    static volatile Fetcher fetcher = UpdateTransport::download;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile WeakReference<Activity> foreground = new WeakReference<>(null);
    private static volatile Intent pendingConfirmation;
    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("updates", Context.MODE_PRIVATE); }
    static boolean enabled(Context context) { return prefs(context).getBoolean("enabled", true); }
    static void setEnabled(Context context, boolean value) { prefs(context).edit().putBoolean("enabled", value).apply(); schedule(context); }
    static String status(Context context) { return prefs(context).getString("status", "No update check has completed yet."); }
    static void status(Context context, String message) { prefs(context).edit().putString("status", message).apply(); DiagnosticLog.log(context, "update", message); }
    static void foreground(Activity activity) { foreground = new WeakReference<>(activity); }
    static void background(Activity activity) { if (foreground.get() == activity) foreground.clear(); }
    static String version(Context context) {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException e) { return "unknown"; }
    }
    static void schedule(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class); if (jobs == null) return;
        if (!enabled(context)) { jobs.cancel(JOB); jobs.cancel(RETRY_JOB); jobs.cancel(RETRY_JOB_ALT); return; }
        JobInfo pending = jobs.getPendingJob(JOB);
        if (pending == null || pending.getIntervalMillis() != PERIOD_MS || pending.getFlexMillis() != FLEX_MS) {
            int result = jobs.schedule(new JobInfo.Builder(JOB, new ComponentName(context, UpdateJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(PERIOD_MS, FLEX_MS).setPersisted(true).build());
            DiagnosticLog.log(context, "update", "periodic scheduling result=" + result);
        }
    }
    private static void retry(Context context, long delay) { retry(context, delay, -1); }
    /**
     * One-shot retry. Scheduling a job with the id of the job that is running right now would make Android stop that job
     * (interrupting this very check), so a retry requested from inside a retry job uses the other retry id.
     */
    private static void retry(Context context, long delay, int runningJobId) {
        if (!enabled(context)) return;
        JobScheduler jobs = context.getSystemService(JobScheduler.class); if (jobs == null) return;
        int id = runningJobId == RETRY_JOB ? RETRY_JOB_ALT : RETRY_JOB;
        int result = jobs.schedule(new JobInfo.Builder(id, new ComponentName(context, UpdateJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(Math.max(60_000L, delay)).setPersisted(true).build());
        DiagnosticLog.log(context, "update", "one-shot retry scheduled id=" + id + " result=" + result + " earliestDelayMs=" + delay);
    }
    private static void cancelRetries(Context app) { JobScheduler jobs = app.getSystemService(JobScheduler.class); if (jobs != null) { jobs.cancel(RETRY_JOB); jobs.cancel(RETRY_JOB_ALT); } }
    static Future<?> check(Context context, boolean manual, Runnable done) { return check(context, manual, done, -1); }
    /** jobId: the JobScheduler job running this check (-1 when not run by a job). */
    static Future<?> check(Context context, boolean manual, Runnable done, int jobId) {
        Context app = context.getApplicationContext();
        if (!BUSY.compareAndSet(false, true)) {
            if (manual && MANUAL_QUEUED.compareAndSet(false, true)) {
                // The single worker runs it right after the current check; a manual tap is never silently dropped.
                status(app, "An update check is already running; your check will run right after it.");
                FutureTask<Void> queued = new FutureTask<>(() -> { MANUAL_QUEUED.set(false); runCheck(app, true, -1); return null; }) {
                    @Override protected void done() { MANUAL_QUEUED.set(false); if (done != null) MAIN.post(done); }
                };
                try { WORKER.execute(queued); return queued; } catch (RuntimeException error) { MANUAL_QUEUED.set(false); }
            }
            if (done != null) MAIN.post(done); return CompletableFuture.completedFuture(null);
        }
        // BUSY is released when the check itself ends (so a caller that waited for it can start the next one at once) and
        // again in done(), which also covers a task cancelled before it ran.
        FutureTask<Void> task = new FutureTask<>(() -> { try { runCheck(app, manual, jobId); } finally { BUSY.set(false); } return null; }) {
            @Override protected void done() { BUSY.set(false); if (done != null) MAIN.post(done); }
        };
        WORKER.execute(task); return task;
    }
    private static void runCheck(Context app, boolean manual, int jobId) {
        try {
            if (!enabled(app) && !manual) return;
            long now = System.currentTimeMillis();
            if (!manual && UpdatePolicy.coolingDown(now, prefs(app).getLong("next_check_at", 0))) return;
            prefs(app).edit().putLong("attempt_at", now).apply();
            DiagnosticLog.log(app, "update", "check start manual=" + manual + " installed=" + version(app));
            PackageInfo installed = app.getPackageManager().getPackageInfo(app.getPackageName(), signingFlags());
            if (!manual && installBlocked(app, false) && verifiedWaiting(app, installed, now) != null) {
                // Still waiting for Dasher/offers/deliveries to finish and the APK verified earlier is still the same file:
                // neither the feed nor the APK is fetched or hashed again (the feed is re-read every FEED_REUSE_MS). The full
                // download-and-verify path runs again before an actual installation.
                deferInstall(app, jobId); return;
            }
            status(app, "Checking for updates…");
            ByteArrayOutputStream feed = new ByteArrayOutputStream();
            fetcher.download(UpdatePolicy.FEED + "?t=" + now, feed, 16384);
            JSONObject release = new JSONObject(feed.toString(StandardCharsets.UTF_8.name())); metadata(release);
            long advertised = release.getLong("versionCode");
            prefs(app).edit().putLong("checked_at", now).putLong("next_check_at", now + 60_000L).putInt("failure_count", 0).putString("advertised", release.getString("versionName")).apply();
            if (advertised <= code(installed)) {
                status(app, advertised == code(installed) ? "Up to date: " + installed.versionName : "Feed is older than this installation; no downgrade attempted.");
                clearReady(app); cancelRetries(app); return;
            }
            File apk = apk(app); boolean reusable = false;
            if (apk.isFile()) { try { validate(app, apk, release, installed); reusable = true; } catch (Exception ignored) { DiagnosticLog.log(app, "update", "cached APK does not match current release; replacing"); } }
            if (!reusable) {
                File part = new File(apk.getParentFile(), "download.apk");
                try {
                    status(app, "Downloading " + release.getString("versionName") + "…"); downloadUpdate(release, part); validate(app, part, release, installed);
                    if (apk.exists() && !apk.delete()) throw new IOException("Could not replace old update");
                    if (!part.renameTo(apk)) throw new IOException("Could not save update");
                } finally { if (part.exists()) part.delete(); }
            }
            prefs(app).edit().putString("ready", release.toString()).putLong("ready_size", apk.length()).putLong("ready_modified", apk.lastModified()).commit();
            install(app, apk, release, manual, jobId);
        } catch (Exception error) {
            if (Thread.currentThread().isInterrupted()) { status(app, "Update check interrupted; no installation started."); return; }
            int failures = Math.min(8, prefs(app).getInt("failure_count", 0) + 1); long delay = UpdatePolicy.retryDelayMillis(failures);
            prefs(app).edit().putInt("failure_count", failures).putLong("next_check_at", System.currentTimeMillis() + delay).apply();
            status(app, "Update failed: " + error.getClass().getSimpleName() + ": " + error.getMessage() + ". Retry requested after " + delay / 60000 + " min; Android may defer it."); retry(app, delay, jobId);
        }
    }
    private static File apk(Context context) throws IOException {
        File dir = new File(context.getFilesDir(), "updates"); if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create update folder"); return new File(dir, "OfferFilter.apk");
    }
    private static void metadata(JSONObject r) throws Exception {
        if (!String.valueOf(r.get("versionCode")).matches("[1-9][0-9]{0,9}") || !String.valueOf(r.get("size")).matches("[1-9][0-9]{0,9}")) throw new IOException("Noninteger version or size");
        UpdatePolicy.validate(r.getString("packageName"), r.getLong("versionCode"), r.getString("apkUrl"), r.getString("sha256"), r.getLong("size"), r.optString("encoding", "raw"));
        if (!r.getString("versionName").matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-zA-Z0-9.]+)?")) throw new IOException("Invalid version name");
    }
    private static void validate(Context context, File file, JSONObject r, PackageInfo installed) throws Exception {
        metadata(r); if (file.length() != r.getLong("size")) throw new IOException("Update size mismatch");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) { byte[] b = new byte[8192]; int count; while ((count = in.read(b)) != -1) digest.update(b, 0, count); }
        if (!hex(digest.digest()).equals(r.getString("sha256"))) throw new IOException("Update checksum mismatch");
        PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), UpdatePolicy.archiveSigningFlags(Build.VERSION.SDK_INT));
        if (archive == null || !context.getPackageName().equals(archive.packageName) || code(archive) != r.getLong("versionCode") || code(archive) <= code(installed) || !r.getString("versionName").equals(archive.versionName) || !signingCompatible(installed, archive)) throw new IOException("Update package/version/signing certificate mismatch");
    }
    /** Dasher is in the foreground, or (automatic installs only) an offer or delivery is active. */
    private static boolean installBlocked(Context context, boolean manual) {
        return OfferFilterService.isDasherForeground() || (!manual && (OfferNotificationService.hasActiveOffer() || ActiveRouteStore.load(context) != null));
    }
    private static void deferInstall(Context context, int jobId) {
        status(context, "Update verified; installation deferred while an offer/delivery is active. It installs after Dasher is closed and no delivery is active.");
        retry(context, UpdatePolicy.DEFER_RETRY_MS, jobId);
    }
    /** A verified APK waiting for installation whose file is unchanged since verification, or null. */
    private static JSONObject verifiedWaiting(Context app, PackageInfo installed, long now) {
        SharedPreferences p = prefs(app); String ready = p.getString("ready", null); if (ready == null) return null;
        try {
            JSONObject release = new JSONObject(ready); metadata(release); File file = apk(app);
            if (!UpdatePolicy.reuseVerified(release.getLong("versionCode"), code(installed), file.isFile() ? file.length() : -1, file.lastModified(),
                    p.getLong("ready_size", -2), p.getLong("ready_modified", -2), p.getLong("checked_at", 0), now)) return null;
            return release;
        } catch (Exception error) { return null; }
    }
    /** Installed-package signer lookup (archives use UpdatePolicy.archiveSigningFlags). */
    @SuppressWarnings("deprecation") private static int signingFlags() { return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES; }
    @SuppressWarnings("deprecation") private static Set<Signature> signers(PackageInfo p) {
        Signature[] all = Build.VERSION.SDK_INT >= 28 && p.signingInfo != null ? p.signingInfo.getApkContentsSigners() : p.signatures;
        Set<Signature> s = new HashSet<>(); if (all != null) Collections.addAll(s, all); return s;
    }
    private static boolean signingCompatible(PackageInfo installed, PackageInfo archive) {
        Set<Signature> current = signers(installed); if (current.isEmpty()) return false;
        if (Build.VERSION.SDK_INT < 28) return current.equals(signers(archive));
        if (archive.signingInfo == null || installed.signingInfo == null) return false;
        if (archive.signingInfo.hasMultipleSigners() || installed.signingInfo.hasMultipleSigners()) return current.equals(signers(archive));
        Signature[] history = archive.signingInfo.getSigningCertificateHistory(); return history != null && new HashSet<>(Arrays.asList(history)).containsAll(current);
    }
    @SuppressWarnings("deprecation") private static long code(PackageInfo p) { return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode; }
    private static String hex(byte[] bytes) { StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format(Locale.US, "%02x", b & 255)); return s.toString(); }
    private static void downloadUpdate(JSONObject r, File file) throws Exception {
        long size = r.getLong("size");
        try (OutputStream out = new FileOutputStream(file)) {
            if ("base64".equals(r.optString("encoding", "raw"))) {
                ByteArrayOutputStream encoded = new ByteArrayOutputStream(); fetcher.download(r.getString("apkUrl"), encoded, ((size + 2) / 3) * 4 + 4096);
                byte[] decoded = Base64.getDecoder().decode(encoded.toString(StandardCharsets.US_ASCII.name()).replaceAll("[\\r\\n\\t ]", ""));
                if (decoded.length != size) throw new IOException("Decoded size mismatch"); out.write(decoded);
            } else fetcher.download(r.getString("apkUrl"), out, size);
        }
    }
    private static void install(Context context, File file, JSONObject release, boolean manual, int jobId) throws Exception {
        if (!enabled(context) && !manual) return;
        if (!manual && prefs(context).getBoolean("manual_retry_required", false)) { status(context, "Android declined the previous installation. Manual retry is required."); return; }
        if (manual) prefs(context).edit().remove("manual_retry_required").apply();
        if (!context.getPackageManager().canRequestPackageInstalls()) { status(context, "Update verified and ready. Enable Allow from this source under Allow automatic installs."); return; }
        if (installBlocked(context, manual)) { deferInstall(context, jobId); return; }
        if (!manual && foreground.get() != null) { status(context, "Update verified; tap Check / install update to install."); return; }
        if (Thread.currentThread().isInterrupted()) return;
        if (manual && pendingConfirmation != null && foreground.get() != null) { confirmation(context, pendingConfirmation); return; }
        PackageInstaller installer = context.getPackageManager().getPackageInstaller(); int oldId = prefs(context).getInt("session", -1);
        PackageInstaller.SessionInfo old = oldId < 0 ? null : installer.getSessionInfo(oldId);
        if (old != null) {
            long age = System.currentTimeMillis() - prefs(context).getLong("session_at", 0);
            if (old.isSealed() && age >= 0 && age < 600_000L && !manual) { status(context, "An installation is already pending; check Android's confirmation notification."); return; }
            installer.abandonSession(oldId);
        }
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName()); params.setAppLabel("Offer Filter"); params.setSize(file.length());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(manual ? PackageInstaller.SessionParams.USER_ACTION_REQUIRED : PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = installer.createSession(params); prefs(context).edit().putInt("session", id).putLong("session_at", System.currentTimeMillis()).putBoolean("confirmation_needed", false).commit();
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(file); OutputStream out = session.openWrite("base.apk", 0, file.length())) {
                byte[] b = new byte[8192]; int count; while ((count = in.read(b)) != -1) { if (Thread.currentThread().isInterrupted()) throw new IOException("Install interrupted"); out.write(b, 0, count); } session.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent result = PendingIntent.getBroadcast(context, id, new Intent(context, UpdateReceiver.class).setAction(UpdateReceiver.INSTALL_RESULT), flags);
            status(context, "Installing " + release.getString("versionName") + "… Android may request confirmation."); session.commit(result.getIntentSender());
        } catch (Exception error) { installer.abandonSession(id); prefs(context).edit().remove("session").apply(); throw error; }
    }
    static void confirmation(Context context, Intent intent) {
        pendingConfirmation = intent; prefs(context).edit().putBoolean("confirmation_needed", true).apply();
        status(context, "Android requires installation confirmation. Tap Check / install update or the update notification.");
        MAIN.post(() -> {
            Activity a = foreground.get();
            if (a != null && !a.isFinishing()) { try { a.startActivity(intent); return; } catch (RuntimeException error) { DiagnosticLog.log(context, "update", "confirmation UI unavailable"); } }
            NotificationManager m = context.getSystemService(NotificationManager.class);
            if (m == null || !m.areNotificationsEnabled() || (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return;
            NotificationChannel c = new NotificationChannel("updates", "App updates", NotificationManager.IMPORTANCE_LOW); c.setSound(null, null); c.enableVibration(false); m.createNotificationChannel(c);
            PendingIntent action = PendingIntent.getActivity(context, NOTICE, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            m.notify(NOTICE, new Notification.Builder(context, "updates").setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Offer Filter update ready").setContentText("Tap to confirm installation.").setContentIntent(action).setAutoCancel(true).setOnlyAlertOnce(true).build());
        });
    }
    static void installationFailed(Context context, int code, String detail) {
        pendingConfirmation = null;
        prefs(context).edit().remove("session").remove("session_at").remove("confirmation_needed").putBoolean("manual_retry_required", true).apply();
        status(context, "Android installation failed (" + code + "): " + detail + ". Tap Check / install update to retry.");
    }
    static void clearReady(Context context) {
        pendingConfirmation = null; prefs(context).edit().remove("ready").remove("ready_size").remove("ready_modified").remove("session").remove("session_at").remove("confirmation_needed").remove("manual_retry_required").apply();
        try { apk(context).delete(); } catch (IOException ignored) {} NotificationManager m = context.getSystemService(NotificationManager.class); if (m != null) m.cancel(NOTICE);
    }
    private Updater() {}
}

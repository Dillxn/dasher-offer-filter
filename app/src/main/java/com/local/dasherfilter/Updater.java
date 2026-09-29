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
    private static final int JOB = 7241, NOTICE = 7242, RETRY_JOB = 7243;
    private static final long PERIOD_MS = 900_000L, FLEX_MS = 300_000L;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
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
        if (!enabled(context)) { jobs.cancel(JOB); jobs.cancel(RETRY_JOB); return; }
        JobInfo pending = jobs.getPendingJob(JOB);
        if (pending == null || pending.getIntervalMillis() != PERIOD_MS || pending.getFlexMillis() != FLEX_MS) {
            int result = jobs.schedule(new JobInfo.Builder(JOB, new ComponentName(context, UpdateJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(PERIOD_MS, FLEX_MS).setPersisted(true).build());
            DiagnosticLog.log(context, "update", "periodic scheduling result=" + result);
        }
    }
    private static void retry(Context context, long delay) {
        if (!enabled(context)) return;
        JobScheduler jobs = context.getSystemService(JobScheduler.class); if (jobs == null) return;
        int result = jobs.schedule(new JobInfo.Builder(RETRY_JOB, new ComponentName(context, UpdateJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(Math.max(60_000L, delay)).setPersisted(true).build());
        DiagnosticLog.log(context, "update", "one-shot retry scheduled result=" + result + " earliestDelayMs=" + delay);
    }
    static Future<?> check(Context context, boolean manual, Runnable done) {
        Context app = context.getApplicationContext();
        if (!BUSY.compareAndSet(false, true)) { if (done != null) MAIN.post(done); return CompletableFuture.completedFuture(null); }
        FutureTask<Void> task = new FutureTask<>(() -> { runCheck(app, manual); return null; }) {
            @Override protected void done() { BUSY.set(false); if (done != null) MAIN.post(done); }
        };
        WORKER.execute(task); return task;
    }
    private static void runCheck(Context app, boolean manual) {
        try {
            if (!enabled(app) && !manual) return;
            long now = System.currentTimeMillis();
            if (!manual && UpdatePolicy.coolingDown(now, prefs(app).getLong("next_check_at", 0))) return;
            prefs(app).edit().putLong("attempt_at", now).apply();
            DiagnosticLog.log(app, "update", "check start manual=" + manual + " installed=" + version(app));
            PackageInfo installed = app.getPackageManager().getPackageInfo(app.getPackageName(), signingFlags());
            status(app, "Checking for updates…");
            ByteArrayOutputStream feed = new ByteArrayOutputStream();
            UpdateTransport.download(UpdatePolicy.FEED + "?t=" + now, feed, 16384);
            JSONObject release = new JSONObject(feed.toString(StandardCharsets.UTF_8.name())); metadata(release);
            long advertised = release.getLong("versionCode");
            prefs(app).edit().putLong("checked_at", now).putLong("next_check_at", now + 60_000L).putInt("failure_count", 0).putString("advertised", release.getString("versionName")).apply();
            if (advertised <= code(installed)) {
                status(app, advertised == code(installed) ? "Up to date: " + installed.versionName : "Feed is older than this installation; no downgrade attempted.");
                clearReady(app); JobScheduler jobs = app.getSystemService(JobScheduler.class); if (jobs != null) jobs.cancel(RETRY_JOB); return;
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
            prefs(app).edit().putString("ready", release.toString()).commit(); install(app, apk, release, manual);
        } catch (Exception error) {
            if (Thread.currentThread().isInterrupted()) { status(app, "Update check interrupted; no installation started."); return; }
            int failures = Math.min(8, prefs(app).getInt("failure_count", 0) + 1); long delay = UpdatePolicy.retryDelayMillis(failures);
            prefs(app).edit().putInt("failure_count", failures).putLong("next_check_at", System.currentTimeMillis() + delay).apply();
            status(app, "Update failed: " + error.getClass().getSimpleName() + ": " + error.getMessage() + ". Retry requested after " + delay / 60000 + " min; Android may defer it."); retry(app, delay);
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
        PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), signingFlags());
        if (archive == null || !context.getPackageName().equals(archive.packageName) || code(archive) != r.getLong("versionCode") || code(archive) <= code(installed) || !r.getString("versionName").equals(archive.versionName) || !signingCompatible(installed, archive)) throw new IOException("Update package/version/signing certificate mismatch");
    }
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
                ByteArrayOutputStream encoded = new ByteArrayOutputStream(); UpdateTransport.download(r.getString("apkUrl"), encoded, ((size + 2) / 3) * 4 + 4096);
                byte[] decoded = Base64.getDecoder().decode(encoded.toString(StandardCharsets.US_ASCII.name()).replaceAll("[\\r\\n\\t ]", ""));
                if (decoded.length != size) throw new IOException("Decoded size mismatch"); out.write(decoded);
            } else UpdateTransport.download(r.getString("apkUrl"), out, size);
        }
    }
    private static void install(Context context, File file, JSONObject release, boolean manual) throws Exception {
        if (!enabled(context) && !manual) return;
        if (!manual && prefs(context).getBoolean("manual_retry_required", false)) { status(context, "Android declined the previous installation. Manual retry is required."); return; }
        if (manual) prefs(context).edit().remove("manual_retry_required").apply();
        if (!context.getPackageManager().canRequestPackageInstalls()) { status(context, "Update verified and ready. Enable Allow from this source under Allow automatic installs."); return; }
        if (OfferFilterService.isDasherForeground() || (!manual && (OfferNotificationService.hasActiveOffer() || ActiveRouteStore.load(context) != null))) { status(context, "Update verified; installation deferred while an offer/delivery is active."); retry(context, 60_000L); return; }
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
        pendingConfirmation = null; prefs(context).edit().remove("ready").remove("session").remove("session_at").remove("confirmation_needed").remove("manual_retry_required").apply();
        try { apk(context).delete(); } catch (IOException ignored) {} NotificationManager m = context.getSystemService(NotificationManager.class); if (m != null) m.cancel(NOTICE);
    }
    private Updater() {}
}

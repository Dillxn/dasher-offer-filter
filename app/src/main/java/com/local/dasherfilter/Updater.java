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

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class Updater {
    private static final String FEED = "https://dash-offer-filter-build.onrender.com/latest.json";
    private static final int JOB = 7241;
    private static final int NOTICE = 7242;
    private static final long PERIOD_MS = 15 * 60 * 1000L;
    private static final long FLEX_MS = 5 * 60 * 1000L;
    private static final long FOREGROUND_RECHECK_MS = 60 * 1000L;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static volatile WeakReference<Activity> foreground = new WeakReference<>(null);

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("updates", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context context) { return prefs(context).getBoolean("enabled", true); }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("enabled", enabled).apply();
        schedule(context);
    }

    static String status(Context context) {
        return prefs(context).getString("status", "Automatic update checks are enabled.");
    }

    static void status(Context context, String message) {
        prefs(context).edit().putString("status", message).apply();
    }

    static void foreground(Activity activity) { foreground = new WeakReference<>(activity); }
    static void background(Activity activity) {
        if (foreground.get() == activity) foreground.clear();
    }

    static String version(Context context) {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException error) { return "unknown"; }
    }

    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        if (!enabled(context)) { scheduler.cancel(JOB); return; }
        JobInfo pending = scheduler.getPendingJob(JOB);
        if (pending == null || pending.getIntervalMillis() != PERIOD_MS ||
                pending.getFlexMillis() != FLEX_MS) {
            scheduler.schedule(new JobInfo.Builder(JOB, new ComponentName(context, UpdateJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(PERIOD_MS, FLEX_MS).setPersisted(true).build());
        }
    }

    static Future<?> check(Context context, boolean manual, Runnable done) {
        Context app = context.getApplicationContext();
        return WORKER.submit(() -> {
            try {
                if (!enabled(app) && !manual) return;
                DiagnosticLog.log(app, "update", "check start manual=" + manual +
                        " installed=" + version(app));
                PackageInfo installed = app.getPackageManager().getPackageInfo(app.getPackageName(), signingFlags());
                long current = code(installed);
                File apk = apk(app);
                String cached = prefs(app).getString("ready", "");
                if (!cached.isEmpty() && apk.isFile()) {
                    JSONObject release = new JSONObject(cached);
                    if (UpdatePolicy.isNewer(release.getLong("versionCode"), current)) {
                        try {
                            validate(app, apk, release, installed);
                        } catch (Exception corrupt) {
                            clearReady(app);
                            release = null;
                        }
                        if (release != null) {
                            install(app, apk, release, manual);
                            return;
                        }
                    }
                    clearReady(app);
                }
                long now = System.currentTimeMillis();
                if (!manual && now < prefs(app).getLong("next_check_at", 0)) return;
                status(app, "Checking for updates…");
                ByteArrayOutputStream feed = new ByteArrayOutputStream();
                download(FEED + "?t=" + now, feed, 16 * 1024);
                JSONObject release = new JSONObject(feed.toString(StandardCharsets.UTF_8.name()));
                metadata(release);
                if (!UpdatePolicy.isNewer(release.getLong("versionCode"), current)) {
                    prefs(app).edit()
                            .putLong("checked_at", now)
                            .putLong("next_check_at", now + FOREGROUND_RECHECK_MS)
                            .putInt("failure_count", 0)
                            .apply();
                    status(app, "Up to date: " + installed.versionName);
                    DiagnosticLog.log(app, "update", "feed up to date installed=" +
                            installed.versionName + " advertised=" + release.getString("versionName"));
                    return;
                }
                status(app, "Downloading " + release.getString("versionName") + "…");
                DiagnosticLog.log(app, "update", "downloading advertised=" +
                        release.getString("versionName") + " url=" + release.getString("apkUrl"));
                File part = new File(apk.getParentFile(), "download.apk");
                downloadUpdate(release, part);
                validate(app, part, release, installed);
                if (!part.renameTo(apk)) throw new IOException("Could not save update");
                prefs(app).edit()
                        .putString("ready", release.toString())
                        .putLong("checked_at", now)
                        .putLong("next_check_at", now + FOREGROUND_RECHECK_MS)
                        .putInt("failure_count", 0)
                        .commit();
                install(app, apk, release, manual);
            } catch (Exception error) {
                long now = System.currentTimeMillis();
                int failures = Math.min(8, prefs(app).getInt("failure_count", 0) + 1);
                long delay = UpdatePolicy.retryDelayMillis(failures);
                prefs(app).edit()
                        .putInt("failure_count", failures)
                        .putLong("next_check_at", now + delay)
                        .apply();
                status(app, "Update check failed: " + error.getMessage() +
                        ". Retrying automatically in " + Math.max(1, delay / 60000L) + " min.");
                DiagnosticLog.log(app, "update", "check failed " +
                        error.getClass().getSimpleName() + ": " + error.getMessage());
            } finally {
                if (done != null) done.run();
            }
        });
    }

    private static File apk(Context context) throws IOException {
        File directory = new File(context.getFilesDir(), "updates");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create update folder");
        return new File(directory, "OfferFilter.apk");
    }

    private static void metadata(JSONObject release) throws Exception {
        UpdatePolicy.validate(release.getString("packageName"), release.getLong("versionCode"),
                release.getString("apkUrl"), release.getString("sha256"), release.getLong("size"),
                release.optString("encoding", "raw"));
        if (!release.getString("versionName").matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-zA-Z0-9.]+)?")) {
            throw new IOException("Invalid version name");
        }
    }

    private static void validate(Context context, File apk, JSONObject release, PackageInfo installed) throws Exception {
        metadata(release);
        if (apk.length() != release.getLong("size")) throw new IOException("Update size mismatch");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(apk)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        if (!hex(digest.digest()).equals(release.getString("sha256"))) throw new IOException("Update checksum mismatch");
        PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), signingFlags());
        if (archive == null || !context.getPackageName().equals(archive.packageName) ||
                code(archive) != release.getLong("versionCode") || code(archive) <= code(installed) ||
                !signingCompatible(installed, archive)) {
            throw new IOException("Update package, version, or signing certificate mismatch");
        }
    }

    @SuppressWarnings("deprecation")
    private static int signingFlags() {
        return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    @SuppressWarnings("deprecation")
    private static Set<Signature> signers(PackageInfo info) {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28 && info.signingInfo != null
                ? info.signingInfo.getApkContentsSigners() : info.signatures;
        Set<Signature> result = new HashSet<>();
        if (signatures != null) for (Signature signature : signatures) result.add(signature);
        return result;
    }

    @SuppressWarnings("deprecation")
    private static boolean signingCompatible(PackageInfo installed, PackageInfo archive) {
        Set<Signature> installedCurrent = signers(installed);
        if (installedCurrent.isEmpty()) return false;
        if (Build.VERSION.SDK_INT < 28) return installedCurrent.equals(signers(archive));
        if (archive.signingInfo == null) return false;
        Signature[] history = archive.signingInfo.hasPastSigningCertificates()
                ? archive.signingInfo.getSigningCertificateHistory()
                : archive.signingInfo.getApkContentsSigners();
        if (history == null || history.length == 0) return false;
        Set<Signature> trustedHistory = new HashSet<>();
        for (Signature signature : history) trustedHistory.add(signature);
        return trustedHistory.containsAll(installedCurrent);
    }

    @SuppressWarnings("deprecation")
    private static long code(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte item : bytes) value.append(String.format(Locale.US, "%02x", item & 255));
        return value.toString();
    }

    private static void downloadUpdate(JSONObject release, File part) throws Exception {
        String encoding = release.optString("encoding", "raw");
        long size = release.getLong("size");
        if ("base64".equals(encoding)) {
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            long encodedLimit = ((size + 2) / 3) * 4 + 4096;
            download(release.getString("apkUrl"), encoded, encodedLimit);
            byte[] decoded;
            try {
                decoded = Base64.getMimeDecoder().decode(encoded.toByteArray());
            } catch (IllegalArgumentException error) {
                throw new IOException("Update base64 is invalid", error);
            }
            if (decoded.length != size) throw new IOException("Decoded update size mismatch");
            try (OutputStream out = new FileOutputStream(part)) {
                out.write(decoded);
            }
            return;
        }
        try (OutputStream out = new FileOutputStream(part)) {
            download(release.getString("apkUrl"), out, size);
        }
    }

    private static void download(String address, OutputStream output, long limit) throws Exception {
        URL url = new URL(address);
        for (int redirect = 0; redirect < 5; redirect++) {
            if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null ||
                    !UpdatePolicy.trustedDownloadHost(url.getHost())) {
                throw new IOException("Untrusted update download host");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "OfferFilter-Updater");
            connection.setRequestProperty("Cache-Control", "no-cache");
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    url = new URL(url, connection.getHeaderField("Location"));
                    continue;
                }
                if (status != 200) throw new IOException("Download HTTP " + status);
                if (connection.getContentLengthLong() > limit) throw new IOException("Update is too large");
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    long received = 0;
                    long deadline = System.currentTimeMillis() + 60000;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Update check cancelled");
                        received += count;
                        if (received > limit || System.currentTimeMillis() > deadline) throw new IOException("Update download limit reached");
                        output.write(buffer, 0, count);
                    }
                }
                return;
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many download redirects");
    }

    private static void install(Context context, File apk, JSONObject release, boolean manual) throws Exception {
        if (!enabled(context) && !manual) return;
        if (!context.getPackageManager().canRequestPackageInstalls()) {
            status(context, "Update ready. Tap Allow automatic installs and enable Allow from this source.");
            return;
        }
        if (Thread.currentThread().isInterrupted()) return;
        if (OfferFilterService.isDasherForeground()) {
            status(context, "Update ready; installation waits until you leave Dasher.");
            return;
        }
        if (!manual && foreground.get() != null) {
            status(context, "Update ready; tap Check / install update or let it install in the background.");
            return;
        }
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        int oldId = prefs(context).getInt("session", -1);
        PackageInstaller.SessionInfo old = oldId < 0 ? null : installer.getSessionInfo(oldId);
        if (old != null) {
            boolean pending = prefs(context).getBoolean("confirmation_needed", false);
            if (old.isSealed() && !(pending && foreground.get() != null && manual) &&
                    System.currentTimeMillis() - prefs(context).getLong("session_at", 0) < 10 * 60 * 1000L) return;
            installer.abandonSession(oldId);
        }
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setAppLabel("Offer Filter");
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = installer.createSession(params);
        prefs(context).edit().putInt("session", id).putLong("session_at", System.currentTimeMillis())
                .putBoolean("confirmation_needed", false).commit();
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream input = new FileInputStream(apk); OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                session.fsync(output);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent result = PendingIntent.getBroadcast(context, id,
                    new Intent(context, UpdateReceiver.class).setAction(UpdateReceiver.INSTALL_RESULT), flags);
            status(context, "Installing " + release.getString("versionName") + "…");
            if (Thread.currentThread().isInterrupted()) throw new IOException("Update check cancelled");
            session.commit(result.getIntentSender());
        } catch (Exception error) {
            installer.abandonSession(id);
            prefs(context).edit().remove("session").apply();
            throw error;
        }
    }

    static void confirmation(Context context, Intent intent) {
        status(context, "Android needs confirmation. Tap Check / install update to finish.");
        prefs(context).edit().putBoolean("confirmation_needed", true).apply();
        Activity activity = foreground.get();
        if (activity != null && !activity.isFinishing()) {
            try { activity.startActivity(intent); return; }
            catch (RuntimeException ignored) { /* Offer a silent notification instead. */ }
        }
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel("updates", "App updates", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        manager.createNotificationChannel(channel);
        PendingIntent action = PendingIntent.getActivity(context, NOTICE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(NOTICE, new Notification.Builder(context, "updates")
                .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Offer Filter update ready")
                .setContentText("Tap to finish Android's installation confirmation.")
                .setContentIntent(action).setAutoCancel(true).setOnlyAlertOnce(true).build());
    }

    static void clearReady(Context context) {
        prefs(context).edit().remove("ready").remove("session").remove("session_at")
                .remove("confirmation_needed").apply();
        try { apk(context).delete(); } catch (IOException ignored) {}
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(NOTICE);
    }
}

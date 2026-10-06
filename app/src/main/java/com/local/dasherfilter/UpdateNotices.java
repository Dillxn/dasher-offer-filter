package com.local.dasherfilter;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/**
 * The updater's notifications, on its own quiet channel ("App updates", low importance: no sound, no pop-up). Besides
 * Android's install confirmation ({@link Updater}), one notice says a verified update cannot install because updates
 * from Offer Filter are not allowed ("Install unknown apps"): posted at most once per version, its tap opens that very
 * switch. A sideloaded tester who never opens Settings would otherwise never update.
 */
final class UpdateNotices {
    static final String CHANNEL_ID = "updates";
    /** The notice that a verified update waits for updates to be allowed. */
    static final int BLOCKED_ID = 7247;
    static final String BLOCKED_TITLE = AppName.NAME + " update ready";
    static final String BLOCKED_TEXT = "Allow updates from " + AppName.NAME + " to install it.";
    /** In the updater's prefs: the newest versionCode the blocked notice was posted for. */
    private static final String BLOCKED_FOR = "blocked_notice_code";

    private UpdateNotices() {}

    /** The channel, made once (Android keeps the user's own choices for it after that). */
    static void ensureChannel(NotificationManager manager) {
        NotificationChannel channel =
                new NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        manager.createNotificationChannel(channel);
    }

    /** Android's own switch for whether this app may install updates ("Install unknown apps", this app only). */
    static Intent allowUpdates(Context context) {
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.getPackageName()));
    }

    /**
     * A verified update ({@code versionCode}) cannot install until updates are allowed: says so once for that version.
     * Not posted, and not counted, while notifications are off; never posted for a version already told.
     *
     * @return whether it was posted now
     */
    static boolean installsBlocked(Context context, long versionCode) {
        SharedPreferences prefs = Updater.prefs(context);
        if (prefs.getLong(BLOCKED_FOR, 0) >= versionCode) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !canPost(context, manager)) return false;
        ensureChannel(manager);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        PendingIntent allow = PendingIntent.getActivity(context, BLOCKED_ID,
                allowUpdates(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            manager.notify(BLOCKED_ID, new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(OfferAlerts.smallIcon(context))
                    .setContentTitle(BLOCKED_TITLE)
                    .setContentText(BLOCKED_TEXT)
                    .setContentIntent(allow)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .build());
        } catch (RuntimeException refused) {
            DiagnosticLog.log(context, "update", "install-permission notice refused: " + refused.getClass().getSimpleName());
            return false;
        }
        prefs.edit().putLong(BLOCKED_FOR, versionCode).apply();
        DiagnosticLog.log(context, "update", "install-permission notice posted once for versionCode " + versionCode);
        return true;
    }

    /** Updates are allowed again, or the update is in: the notice goes. */
    static void cancelBlocked(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(BLOCKED_ID);
    }

    private static boolean canPost(Context context, NotificationManager manager) {
        if (!manager.areNotificationsEnabled()) return false;
        return Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }
}

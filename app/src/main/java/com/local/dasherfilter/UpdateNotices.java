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
 * from Offer Filter are not allowed ("Install unknown apps"): posted at most once per version, and only once the
 * first-run notice is accepted (before that the paused-until-opened reminder is the one thing posted), its tap opens
 * that very switch, and it goes as soon as updates are allowed. A sideloaded tester who never opens Settings would
 * otherwise never update.
 */
final class UpdateNotices {
    static final String CHANNEL_ID = "updates";
    /** The notice that a verified update waits for updates to be allowed. */
    static final int BLOCKED_ID = 7247;
    static final String BLOCKED_TITLE = AppName.NAME + " update ready";
    static final String BLOCKED_TEXT = "Allow updates from " + AppName.NAME + " to install it.";
    /** In the updater's prefs: the newest versionCode the blocked notice was posted for. */
    private static final String BLOCKED_FOR = "blocked_notice_code";
    /** In the updater's prefs: the blocked notice may still be up (posted, and updates not seen allowed since). */
    private static final String BLOCKED_UP = "blocked_notice_up";

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
     * Not posted, and not counted, before the first-run notice is accepted or while notifications are off (so it
     * comes once they are); never posted for a version already told.
     *
     * @return whether it was posted now
     */
    static boolean installsBlocked(Context context, long versionCode) {
        // Before the notice is accepted the reminder is the one thing posted; this waits for the acceptance.
        if (!Consent.accepted(context)) return false;
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
        prefs.edit().putLong(BLOCKED_FOR, versionCode).putBoolean(BLOCKED_UP, true).apply();
        DiagnosticLog.log(context, "update", "install-permission notice posted once for versionCode " + versionCode);
        return true;
    }

    /** The update is in, or no longer waits: the notice goes. */
    static void cancelBlocked(Context context) {
        Updater.prefs(context).edit().remove(BLOCKED_UP).apply();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(BLOCKED_ID);
    }

    /**
     * Updates from this app are allowed now (Android said so lately): the notice asking for that goes at once, not
     * only when the update is in. Cheap while it is not up (one preference read), so it may be asked every refresh.
     */
    static void allowed(Context context) {
        if (Updater.prefs(context).getBoolean(BLOCKED_UP, false)) cancelBlocked(context);
    }

    private static boolean canPost(Context context, NotificationManager manager) {
        if (!manager.areNotificationsEnabled()) return false;
        return Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }
}

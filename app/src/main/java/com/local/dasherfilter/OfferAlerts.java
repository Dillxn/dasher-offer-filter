package com.local.dasherfilter;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;

/** Passing offers may ring; unknown offers get a separate, noninterrupting review card. */
final class OfferAlerts {
    static final String CHANNEL_ID = "qualifying_offers";
    static final String REVIEW_CHANNEL_ID = "unclassified_offers_v1";
    static final int NOTIFICATION_ID = 8241;
    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
    private static final int MAX_BODY_CHARS = 500;

    static void ensureChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;

        NotificationChannel passing =
                new NotificationChannel(CHANNEL_ID, "Offers that pass", NotificationManager.IMPORTANCE_HIGH);
        passing.setDescription("Only offers proven to meet enabled rules. Android sound and DND settings still apply.");
        passing.enableVibration(true);
        passing.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        manager.createNotificationChannel(passing);

        NotificationChannel review = new NotificationChannel(
                REVIEW_CHANNEL_ID, "Unclassified offers (silent)", NotificationManager.IMPORTANCE_LOW);
        review.setDescription("An offer exists, but required pay/distance evidence is missing. Tap to inspect it.");
        review.enableVibration(false);
        review.setSound(null, null);
        manager.createNotificationChannel(review);
    }

    /** Whether the passing-offer channel can actually post. */
    static boolean canNotify(Context context) {
        return canNotify(context, CHANNEL_ID);
    }

    private static boolean canNotify(Context context, String channelId) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationChannel channel = manager.getNotificationChannel(channelId);
        return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    /**
     * Posts or replaces the Offer Filter card for one DoorDash notification.
     *
     * @return true only when Android accepted the post. The caller must keep the original DoorDash notification
     *         whenever this returns false.
     */
    static boolean notifyOffer(Context context, String tag, PendingIntent doorDashIntent, OfferRule.Result result,
                               String detail, boolean ring) {
        if (result == OfferRule.Result.DECLINE) {
            clear(context, tag);
            return false;
        }
        ensureChannel(context);
        String channel = result == OfferRule.Result.KEEP ? CHANNEL_ID : REVIEW_CHANNEL_ID;
        if (!canNotify(context, channel)) {
            DiagnosticLog.log(context, "alert", "replacement unavailable; original notification retained; channel="
                    + channel);
            return false;
        }

        String body = detail == null ? "" : detail;
        if (body.length() > MAX_BODY_CHARS) body = body.substring(0, MAX_BODY_CHARS) + "…";
        boolean audible = result == OfferRule.Result.KEEP && ring;
        Notification.Builder builder = new Notification.Builder(context, channel)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(result == OfferRule.Result.KEEP
                        ? "DoorDash offer passes filter" : "DoorDash offer: not yet classified")
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setTimeoutAfter(OfferAlertState.LIFETIME_MS)
                .setOnlyAlertOnce(!audible)
                .setCategory(Notification.CATEGORY_RECOMMENDATION);
        if (!audible) {
            // A group child with summary-only alerting never alerts, even on a high-importance channel.
            builder.setGroup("quiet-" + tag).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY);
        }
        PendingIntent open = openDasherIntent(context, doorDashIntent);
        if (open != null) builder.setContentIntent(open);

        try {
            // A decline of an earlier offer may still have the sound turned down; this offer's bell must be heard.
            if (audible) OfferSilencer.yieldToPassingAlert(context);
            context.getSystemService(NotificationManager.class).notify(tag, NOTIFICATION_ID, builder.build());
            DiagnosticLog.log(context, "alert", "posted " + result + " audibleRequested=" + audible);
            return true;
        } catch (RuntimeException error) {
            DiagnosticLog.log(context, "alert", "post failed; original retained: " + error.getClass().getSimpleName());
            return false;
        }
    }

    /** DoorDash's own content intent when DoorDash created it; otherwise Dasher's launcher activity. */
    private static PendingIntent openDasherIntent(Context context, PendingIntent doorDashIntent) {
        if (doorDashIntent != null && DASHER_PACKAGE.equals(doorDashIntent.getCreatorPackage())) return doorDashIntent;
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(DASHER_PACKAGE);
        if (launch == null) return null;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        return PendingIntent.getActivity(context, NOTIFICATION_ID, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void clear(Context context, String tag) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(tag, NOTIFICATION_ID);
    }

    /**
     * Removes every Offer Filter card, whatever its tag. Used when the listener (re)connects: per-notification
     * state from before a process restart is gone, so its cards would otherwise linger beside replayed ones.
     */
    static void clearAll(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        try {
            for (StatusBarNotification posted : manager.getActiveNotifications()) {
                if (posted.getId() == NOTIFICATION_ID) manager.cancel(posted.getTag(), NOTIFICATION_ID);
            }
        } catch (RuntimeException error) {
            DiagnosticLog.log(context, "alert", "could not list own cards: " + error.getClass().getSimpleName());
        }
    }

    private OfferAlerts() {}
}

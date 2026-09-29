package com.local.dasherfilter;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.media.AudioAttributes;
import android.provider.Settings;

final class OfferAlerts {
    static final String CHANNEL_ID = "qualifying_offers";
    private static final int NOTIFICATION_ID = 8241;
    private static final String DASHER_PACKAGE = "com.doordash.driverapp";

    static void ensureChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Qualifying DoorDash offers", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Alerts only after Offer Filter decides an offer passes or needs review.");
        channel.enableVibration(true);
        channel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI,
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
        manager.createNotificationChannel(channel);
    }

    static boolean canNotify(Context context) {
        if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            NotificationChannel channel = manager == null ? null : manager.getNotificationChannel(CHANNEL_ID);
            return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    static void notifyOffer(Context context, PendingIntent doorDashIntent,
                            OfferRule.Result result, String detail) {
        ensureChannel(context);
        if (!canNotify(context)) {
            DiagnosticLog.log(context, "alert", "qualifying alert suppressed: POST_NOTIFICATIONS not granted");
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;

        PendingIntent action = doorDashIntent;
        if (action == null) {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(DASHER_PACKAGE);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                action = PendingIntent.getActivity(context, NOTIFICATION_ID, launch,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            }
        }

        String title = result == OfferRule.Result.KEEP
                ? "DoorDash offer passes filter"
                : "DoorDash offer needs review";
        String body = detail == null ? "" : detail;
        if (body.length() > 220) body = body.substring(0, 220) + "…";

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_ALL);
        builder.setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setTimeoutAfter(90_000L)
                .setCategory(Notification.CATEGORY_RECOMMENDATION);
        if (action != null) builder.setContentIntent(action);
        manager.cancel(NOTIFICATION_ID);
        manager.notify(NOTIFICATION_ID, builder.build());
        DiagnosticLog.log(context, "alert", "posted selective offer alert: " + result + " " + body);
    }

    static void clear(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    private OfferAlerts() {}
}

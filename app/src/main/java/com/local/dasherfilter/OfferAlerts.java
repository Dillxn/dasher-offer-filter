package com.local.dasherfilter;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;

/**
 * Passing offers may ring on their own channel. An offer that cannot be judged (DoorDash's background notification
 * shows no pay, or auto-decline is paused) rings once on "Offers to check", so it is not missed while Dasher is in
 * the background; it is never judged, declined or opened for you.
 */
final class OfferAlerts {
    static final String CHANNEL_ID = "qualifying_offers";
    static final String REVIEW_CHANNEL_ID = "offers_to_check_v2";
    /** 0.4.15 and earlier posted review cards silently here; Android cannot raise a channel's importance later. */
    private static final String RETIRED_SILENT_REVIEW_CHANNEL_ID = "unclassified_offers_v1";
    static final int NOTIFICATION_ID = 8241;
    /** A card times out after this: Dasher's countdown runs 47-60 s, so a later tap would find no offer. */
    static final long CARD_MS = 60_000;
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
                REVIEW_CHANNEL_ID, "Offers to check", NotificationManager.IMPORTANCE_HIGH);
        review.setDescription("An offer arrived while Dasher was in the background, but its pay or distance was not "
                + "shown, so it could not be judged. Rings once; tap to open Dasher.");
        review.enableVibration(true);
        review.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        manager.createNotificationChannel(review);
        manager.deleteNotificationChannel(RETIRED_SILENT_REVIEW_CHANNEL_ID);
    }

    /** Readiness covers both offer channels and a notice channel the user has already blocked. */
    static boolean canNotify(Context context) {
        if (!canNotify(context, CHANNEL_ID) || !canNotify(context, REVIEW_CHANNEL_ID)) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel reminder = manager.getNotificationChannel(ConsentReminder.CHANNEL_ID);
        // This channel is created only when a notice waits. Its absence before then is not a missing permission.
        return reminder == null || reminder.getImportance() != NotificationManager.IMPORTANCE_NONE;
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
    /**
     * The app's funnel for the status bar. Looked up by name because this build has no generated R class; falls
     * back to a system icon if the resource is missing.
     */
    @SuppressWarnings("DiscouragedApi")
    static int smallIcon(Context context) {
        int id = context.getResources().getIdentifier("ic_notification", "drawable", context.getPackageName());
        return id != 0 ? id : android.R.drawable.stat_notify_more;
    }

    static boolean notifyOffer(Context context, String tag, PendingIntent doorDashIntent, OfferRule.Result result,
                               String detail, boolean ring) {
        return notifyOffer(context, tag, doorDashIntent, result, detail, ring, "");
    }

    /**
     * As {@link #notifyOffer(Context, String, PendingIntent, OfferRule.Result, String, boolean)}, titled with the
     * store Dasher's notification names ("Taco Bell offer: open Dasher to check it") when it names one.
     */
    static boolean notifyOffer(Context context, String tag, PendingIntent doorDashIntent, OfferRule.Result result,
                               String detail, boolean ring, String store) {
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
        boolean audible = ring;
        Notification.Builder builder = new Notification.Builder(context, channel)
                .setSmallIcon(smallIcon(context))
                .setContentTitle(title(result, store))
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setTimeoutAfter(CARD_MS)
                .setOnlyAlertOnce(!audible)
                .setCategory(Notification.CATEGORY_RECOMMENDATION);
        if (!audible) {
            // A group child with summary-only alerting never alerts, even on a high-importance channel.
            builder.setGroup("quiet-" + tag).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY);
        }
        PendingIntent open = openDasherIntent(context, tag, doorDashIntent);
        if (open != null) builder.setContentIntent(open);

        try {
            // A decline of an earlier offer may still have the sound turned down; this offer's alert must be heard.
            if (audible) OfferSilencer.yieldToPassingAlert(context);
            context.getSystemService(NotificationManager.class).notify(tag, NOTIFICATION_ID, builder.build());
            // Where Dasher was, whether the screen was on, and what was playing (Dasher rings on the alarm stream, usage
            // 4; streams and usages only, never an app), so a report can tell whether our card rang over Dasher.
            DiagnosticLog.log(context, "alert", "posted " + result + " audibleRequested=" + audible + " "
                    + OfferFilterService.windowsNow(context) + " playing=" + OfferSilencer.playing(context));
            return true;
        } catch (RuntimeException error) {
            DiagnosticLog.log(context, "alert", "post failed; original retained: " + error.getClass().getSimpleName());
            return false;
        }
    }

    /** "Taco Bell offer: open Dasher to check it", or "DoorDash offer …" when the store is not named. */
    static String title(OfferRule.Result result, String store) {
        String offer = (store == null || store.trim().isEmpty() ? "DoorDash" : store.trim()) + " offer";
        return result == OfferRule.Result.KEEP ? offer + " meets your rules" : offer + ": open Dasher to check it";
    }

    /**
     * The card's tap: {@link OpenDasherActivity}, which opens Dasher as its launcher icon does (in its own half when
     * it is already in split screen, into the other half when only ours is) and clears the card. DoorDash's own content intent only when Dasher has no launch intent, and only when
     * DoorDash created it.
     */
    private static PendingIntent openDasherIntent(Context context, String tag, PendingIntent doorDashIntent) {
        PendingIntent dashers = doorDashIntent != null && DASHER_PACKAGE.equals(doorDashIntent.getCreatorPackage())
                ? doorDashIntent : null;
        return OpenDasherActivity.forCard(context, tag, dashers);
    }

    /** Whether the card with this tag is still posted (not cleared, timed out, or tapped away). */
    static boolean showing(Context context, String tag) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return false;
        try {
            for (StatusBarNotification posted : manager.getActiveNotifications()) {
                if (posted.getId() == NOTIFICATION_ID && tag.equals(posted.getTag())) return true;
            }
        } catch (RuntimeException unknown) {
            return true;
        }
        return false;
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

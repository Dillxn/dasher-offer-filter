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
import android.os.Build;

/**
 * The one thing posted before the first-run notice ({@link Consent}) is accepted. An existing install gets a new
 * notice through an automatic update, often mid-dash with Dasher in front, and would otherwise lose auto-decline
 * without a word until Offer Filter is next opened. So when the accessibility service connects, or Dasher posts on
 * the channel its offers come on, while the notice waits, one notification says the app is paused; its tap opens the
 * app, which shows the notice. It reads nothing of Dasher's, and rings at most once per notice version: a fixed id,
 * only-alert-once, and the version it was posted for kept, so reconnects and further offers never post it again.
 * Accepting the notice cancels it.
 */
final class ConsentReminder {
    /** Its own channel, not an offer's: one sound at default importance, never a pop-up over Dasher's screen. */
    static final String CHANNEL_ID = "paused_until_opened";
    static final int NOTIFICATION_ID = 7246;
    /** In {@link Consent#PREFS}: the notice version the reminder was last posted for. */
    static final String REMINDED_VERSION = "reminded_version";
    static final String TITLE = AppName.NAME + " is paused until you open it";
    static final String TEXT = "Tap to review and keep auto-declining.";

    /** Whether "notifications are off" was logged in this process, so each connect and offer does not log it again. */
    private static volatile boolean unavailableLogged;

    /**
     * Posts the reminder unless the notice is accepted or the reminder was already posted for this notice version.
     * Safe from any thread and as often as asked.
     *
     * @param why what asked for it, for the log ("accessibility connected", "Dasher's notification")
     * @return true only when Android accepted the post now
     */
    static boolean postIfPaused(Context context, String why) {
        synchronized (ConsentReminder.class) {
            if (Consent.accepted(context)) return false;
            SharedPreferences prefs = prefs(context);
            if (prefs.getInt(REMINDED_VERSION, 0) >= Consent.VERSION) return false;
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return false;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Paused until opened",
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Once, when " + AppName.NAME + " is paused until you open it (a notice to read, "
                    + "for example after an update). Never about an offer.");
            manager.createNotificationChannel(channel);
            if (!canPost(context, manager)) {
                if (!unavailableLogged) {
                    unavailableLogged = true;
                    DiagnosticLog.log(context, "consent", "paused-until-opened reminder not posted: notifications are "
                            + "off for " + AppName.NAME);
                }
                return false;
            }
            Notification reminder = new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(OfferAlerts.smallIcon(context))
                    .setContentTitle(TITLE)
                    .setContentText(TEXT)
                    .setContentIntent(openApp(context))
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .build();
            try {
                manager.notify(NOTIFICATION_ID, reminder);
            } catch (RuntimeException refused) {
                DiagnosticLog.log(context, "consent", "paused-until-opened reminder refused: "
                        + refused.getClass().getSimpleName());
                return false;
            }
            prefs.edit().putInt(REMINDED_VERSION, Consent.VERSION).commit();
            DiagnosticLog.log(context, "consent", "paused-until-opened reminder posted (" + why + "); notice "
                    + Consent.VERSION + " waits to be accepted");
            return true;
        }
    }

    /**
     * Whether a post of Dasher's on {@code channelId} may be an offer: it is on the channel Dasher's offers last came
     * on, or on any of Dasher's while none is known yet. Only the channel's id is looked at, never the words.
     */
    static boolean onOfferChannel(Context context, String channelId) {
        String offers = FilterStore.doorDashOfferChannel(context);
        return offers.isEmpty() || offers.equals(channelId);
    }

    /** The notice was accepted: the reminder goes. */
    static void cancel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    /** Its tap: the app's own screen, which shows the notice (as the update relaunch opens it). */
    private static PendingIntent openApp(Context context) {
        Intent app = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, NOTIFICATION_ID, app,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static boolean canPost(Context context, NotificationManager manager) {
        if (!manager.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(Consent.PREFS, Context.MODE_PRIVATE);
    }

    private ConsentReminder() {}
}

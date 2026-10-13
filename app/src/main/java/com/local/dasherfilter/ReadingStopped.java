package com.local.dasherfilter;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * What the 11:06 stop on 2026-10-12 left unknown, and unsaid. Android unbound the screen reader mid-dash while the app
 * still ran, and the notification listener stayed down after two rebind requests; the Dasher found out only when
 * offers stopped being filtered. So:
 * <ul>
 *   <li>at the screen reader's unbind, the log says whether it is still on in Android's settings (off there is the
 *       user's own doing; on there means Android stopped it), with low memory, the standby bucket and battery saver;
 *   <li>when Android stops it mid-dash (still on in settings, not paused, notice accepted), one notification says so,
 *       once per dash, on its own channel; reconnecting cancels it;
 *   <li>when notification access is allowed but the listener has not connected a while after a rebind request, its
 *       component is turned off and on (DONT_KILL_APP), at most once per hour, and logged. Unverified on One UI.
 * </ul>
 */
final class ReadingStopped {
    static final String CHANNEL_ID = "reading_stopped";
    static final int NOTIFICATION_ID = 7247;
    static final String TITLE = AppName.NAME + " stopped reading Dasher";
    static final String TEXT = "Tap to turn it back on";
    /** In {@link #PREFS}: the start of the dash the notice was last posted in, so it rings once per dash. */
    static final String NOTIFIED_DASH = "notified_dash";
    static final String PREFS = "reading_stopped";
    /** How long after a rebind request the listener has to connect before its component is turned off and on. */
    static final long LISTENER_GRACE_MS = 10_000;
    static final long NUDGE_EVERY_MS = 60 * 60 * 1000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long lastNudgeAt = -1;
    private static boolean nudgeWaiting;

    /** At the screen reader's unbind: the log line, then the notice when Android (not the user) stopped it mid-dash. */
    static void unbound(Context context) {
        boolean onInSettings = SetupChecklist.accessibilityEnabled(context);
        DiagnosticLog.log(context, "service", "screen reader unbound; " + (onInSettings
                ? "still on in Android's settings (Android stopped it)" : "off in Android's settings") + "; "
                + phoneState(context));
        if (onInSettings) notifyIfDashing(context);
    }

    /** Low memory, the standby bucket and battery saver, for the log. */
    static String phoneState(Context context) {
        StringBuilder state = new StringBuilder();
        try {
            ActivityManager activities = context.getSystemService(ActivityManager.class);
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            if (activities != null) activities.getMemoryInfo(memory);
            state.append("low memory ").append(memory.lowMemory ? "yes" : "no");
        } catch (RuntimeException unknown) {
            state.append("low memory unknown");
        }
        try {
            UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
            state.append(", standby bucket ").append(usage == null ? "unknown" : usage.getAppStandbyBucket());
        } catch (RuntimeException unknown) {
            state.append(", standby bucket unknown");
        }
        try {
            PowerManager power = context.getSystemService(PowerManager.class);
            state.append(", battery saver ").append(power != null && power.isPowerSaveMode() ? "on" : "off");
        } catch (RuntimeException unknown) {
            state.append(", battery saver unknown");
        }
        return state.toString();
    }

    /**
     * One notification, once per dash, when a dash is on, not paused, and the notice is accepted.
     *
     * @return true only when Android accepted the post now
     */
    static boolean notifyIfDashing(Context context) {
        if (!Consent.accepted(context) || !Dashing.on(context) || Dashing.isPaused(context)) return false;
        long dash = Dashing.currentStart(context);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (dash > 0 && prefs.getLong(NOTIFIED_DASH, 0) == dash) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return false;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Stopped reading Dasher",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Once per dash, when Android stops " + AppName.NAME + " from reading Dasher's "
                + "screen. Never about an offer.");
        manager.createNotificationChannel(channel);
        if (!canPost(context, manager)) {
            DiagnosticLog.log(context, "service", "stopped-reading notice not posted: notifications are off");
            return false;
        }
        Intent app = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification notice = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(OfferAlerts.smallIcon(context))
                .setContentTitle(TITLE)
                .setContentText(TEXT)
                .setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_ID, app,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_STATUS)
                .build();
        try {
            manager.notify(NOTIFICATION_ID, notice);
        } catch (RuntimeException refused) {
            DiagnosticLog.log(context, "service", "stopped-reading notice refused: "
                    + refused.getClass().getSimpleName());
            return false;
        }
        prefs.edit().putLong(NOTIFIED_DASH, dash).apply();
        DiagnosticLog.log(context, "service", "stopped-reading notice posted (mid-dash)");
        return true;
    }

    /** The screen reader is back: the notice goes. */
    static void cancel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    /**
     * After a rebind request: if the listener has not connected {@link #LISTENER_GRACE_MS} later while access is
     * allowed, its component is turned off and on, at most once per {@link #NUDGE_EVERY_MS}.
     */
    static void afterRebind(Context context) {
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (ReadingStopped.class) {
            if (nudgeWaiting) return;
            nudgeWaiting = true;
        }
        MAIN.postDelayed(() -> {
            synchronized (ReadingStopped.class) {
                nudgeWaiting = false;
            }
            nudgeListener(app);
        }, LISTENER_GRACE_MS);
    }

    /** @return true when the component was turned off and on now */
    static boolean nudgeListener(Context context) {
        if (OfferNotificationService.isConnected()) return false;
        ComponentName listener = new ComponentName(context, OfferNotificationService.class);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        try {
            if (manager == null || !manager.isNotificationListenerAccessGranted(listener)) return false;
        } catch (RuntimeException unknown) {
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (ReadingStopped.class) {
            if (lastNudgeAt >= 0 && now - lastNudgeAt < NUDGE_EVERY_MS) {
                DiagnosticLog.log(context, "notification", "listener still down after a rebind request; "
                        + "turned off and on within the hour already");
                return false;
            }
            lastNudgeAt = now;
        }
        try {
            PackageManager packages = context.getPackageManager();
            packages.setComponentEnabledSetting(listener, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            packages.setComponentEnabledSetting(listener, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    PackageManager.DONT_KILL_APP);
        } catch (RuntimeException refused) {
            DiagnosticLog.log(context, "notification", "listener toggle refused: "
                    + refused.getClass().getSimpleName());
            return false;
        }
        DiagnosticLog.log(context, "notification", "listener still down " + LISTENER_GRACE_MS / 1000
                + " s after a rebind request while access is allowed; component turned off and on");
        return true;
    }

    /** For tests: no nudge made or waiting. */
    static void forget() {
        synchronized (ReadingStopped.class) {
            lastNudgeAt = -1;
            nudgeWaiting = false;
        }
        MAIN.removeCallbacksAndMessages(null);
    }

    private static boolean canPost(Context context, NotificationManager manager) {
        if (!manager.areNotificationsEnabled()) return false;
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private ReadingStopped() {}
}

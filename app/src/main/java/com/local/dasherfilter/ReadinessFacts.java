package com.local.dasherfilter;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * Read-only readiness facts for the UI. Each value is what Android reports right now; nothing here changes a setting or
 * opens another app. "Enabled in settings" and "connected" are separate facts: Android can list a service as enabled while
 * it is not (yet) bound.
 */
final class ReadinessFacts {
    static final String DASHER_PACKAGE = "com.doordash.driverapp";

    /** Offer Filter is listed in Settings > Accessibility as enabled. */
    static boolean accessibilityEnabledInSettings(Context context) {
        try {
            if (Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false;
            String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            ComponentName mine = new ComponentName(context, OfferFilterService.class);
            TextUtils.SimpleStringSplitter split = new TextUtils.SimpleStringSplitter(':'); split.setString(enabled);
            for (String value : split) if (mine.equals(ComponentName.unflattenFromString(value))) return true;
            return false;
        } catch (RuntimeException error) { return false; }
    }
    /** The accessibility service is bound and receiving events. */
    static boolean accessibilityConnected() { return OfferFilterService.isConnected(); }
    /** Notification access is granted in settings. */
    static boolean listenerAccess(Context context) { try { return OfferNotificationService.hasAccess(context); } catch (RuntimeException error) { return false; } }
    /** The notification listener is bound. */
    static boolean listenerConnected() { return OfferNotificationService.isConnected(); }
    /** App notifications are on and (Android 13+) POST_NOTIFICATIONS is granted. */
    static boolean notificationsPermitted(Context context) {
        NotificationManager m = context.getSystemService(NotificationManager.class);
        if (m == null || !m.areNotificationsEnabled()) return false;
        return Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }
    /** Importance of one of Offer Filter's channels, or IMPORTANCE_NONE when it does not exist yet. */
    static int channelImportance(Context context, String channelId) {
        NotificationManager m = context.getSystemService(NotificationManager.class);
        NotificationChannel c = m == null ? null : m.getNotificationChannel(channelId);
        return c == null ? NotificationManager.IMPORTANCE_NONE : c.getImportance();
    }
    /** One of Offer Filter's channels has a sound set (the user may have silenced it). */
    static boolean channelSound(Context context, String channelId) {
        NotificationManager m = context.getSystemService(NotificationManager.class);
        NotificationChannel c = m == null ? null : m.getNotificationChannel(channelId);
        return c != null && c.getSound() != null;
    }
    /** The passing-offer channel can actually make a sound: posting allowed, importance default or higher, and a sound set. */
    static boolean passingAlertAudible(Context context) {
        return OfferAlerts.canNotify(context) && channelImportance(context, OfferAlerts.CHANNEL_ID) >= NotificationManager.IMPORTANCE_DEFAULT && channelSound(context, OfferAlerts.CHANNEL_ID);
    }
    /** The last observed settings of DoorDash's own offer channel, or null before any offer notification was seen. */
    static DoorDashChannelFacts doorDashChannel(Context context) { return DoorDashChannelFacts.load(context); }
    /** Android's current interruption filter (NotificationManager.INTERRUPTION_FILTER_*), or INTERRUPTION_FILTER_UNKNOWN. */
    static int interruptionFilter(Context context) {
        NotificationManager m = context.getSystemService(NotificationManager.class);
        try { return m == null ? NotificationManager.INTERRUPTION_FILTER_UNKNOWN : m.getCurrentInterruptionFilter(); }
        catch (RuntimeException error) { return NotificationManager.INTERRUPTION_FILTER_UNKNOWN; }
    }
    /** Do Not Disturb (any filter other than "all") may silence a passing-offer bell. */
    static boolean doNotDisturbOn(Context context) {
        int filter = interruptionFilter(context);
        return filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
    }
    /** AudioManager.RINGER_MODE_NORMAL / VIBRATE / SILENT, or -1 when unknown. */
    static int ringerMode(Context context) {
        AudioManager a = context.getSystemService(AudioManager.class);
        try { return a == null ? -1 : a.getRingerMode(); } catch (RuntimeException error) { return -1; }
    }
    /** The DoorDash Dasher app is installed (visible through the manifest's package query). */
    static boolean dasherInstalled(Context context) {
        try { context.getPackageManager().getPackageInfo(DASHER_PACKAGE, 0); return true; }
        catch (PackageManager.NameNotFoundException | RuntimeException error) { return false; }
    }
    /** At least one rule is saved, so auto-decline may be switched on. */
    static boolean rulesReady(Context context) { return FilterStore.load(context).hasAnyRule(); }
    /** Auto-decline is saved as on. */
    static boolean autoDeclineOn(Context context) { return FilterStore.load(context).enabled; }
    /** Android allows this app to install its verified updates. */
    static boolean canInstallUpdates(Context context) {
        try { return context.getPackageManager().canRequestPackageInstalls(); } catch (RuntimeException error) { return false; }
    }
    private ReadinessFacts() {}
}

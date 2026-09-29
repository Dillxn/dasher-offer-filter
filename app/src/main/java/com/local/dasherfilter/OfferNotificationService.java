package com.local.dasherfilter;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/** Bridges DoorDash offer notifications into the on-device offer filter while Dasher is backgrounded. */
public final class OfferNotificationService extends NotificationListenerService {
    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
    private static final long PENDING_MS = 90_000;
    private static final long WAKE_POLL_MS = 6_000;
    private static final Object LOCK = new Object();
    private static volatile OfferNotificationService active;
    private static String pendingKey;
    private static long pendingAt;
    private static boolean pendingWake;
    private static PendingIntent pendingContentIntent;
    private static boolean pendingAlertAfterScreen;
    private final Handler handler = new Handler(Looper.getMainLooper());

    static boolean isConnected() { return active != null; }

    static boolean hasAccess(Context context) {
        ComponentName component = new ComponentName(context, OfferNotificationService.class);
        if (Build.VERSION.SDK_INT >= 27) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            return manager != null && manager.isNotificationListenerAccessGranted(component);
        }
        String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(component.flattenToString());
    }

    static boolean hasRecentPendingWake(long now) {
        synchronized (LOCK) {
            expireLocked(now);
            return pendingKey != null && pendingWake && now - pendingAt < WAKE_POLL_MS;
        }
    }

    static void screenResolved(Context context, OfferRule.Result result, String detail) {
        String key = null;
        PendingIntent contentIntent = null;
        boolean shouldAlert = false;
        synchronized (LOCK) {
            expireLocked(SystemClock.uptimeMillis());
            if (pendingKey == null) return;
            DiagnosticLog.log(context, "notification", "screen resolved " + result + ": " + detail);
            if (result != OfferRule.Result.DECLINE) {
                key = pendingKey;
                contentIntent = pendingContentIntent;
                shouldAlert = pendingAlertAfterScreen;
                clearLocked();
            }
        }
        if (shouldAlert) {
            OfferAlerts.notifyOffer(context, contentIntent, result, detail);
            OfferNotificationService service = active;
            if (service != null && key != null) {
                try {
                    service.cancelNotification(key);
                    DiagnosticLog.log(context, "notification", "replaced silent DoorDash offer with selective alert");
                } catch (Exception error) {
                    DiagnosticLog.log(context, "notification", "could not remove silent DoorDash offer: " +
                            error.getClass().getSimpleName());
                }
            }
        }
    }

    static void cancelPendingFiltered(Context context, String detail) {
        OfferNotificationService service = active;
        String key;
        synchronized (LOCK) {
            expireLocked(SystemClock.uptimeMillis());
            key = pendingKey;
            clearLocked();
        }
        if (key == null) return;
        if (service == null) {
            DiagnosticLog.log(context, "notification", "filtered offer matched, but notification listener disconnected: " + detail);
            return;
        }
        try {
            service.cancelNotification(key);
            DiagnosticLog.log(context, "notification", "cancelled filtered offer notification after Decline: " + detail);
        } catch (Exception error) {
            DiagnosticLog.log(context, "notification", "failed to cancel filtered offer notification: " + error.getClass().getSimpleName());
        }
    }

    @Override public void onListenerConnected() {
        active = this;
        Updater.schedule(this);
        Updater.check(this, false, null);
        OfferAlerts.ensureChannel(this);
        DiagnosticLog.log(this, "notification", "listener connected");
    }

    @Override public void onListenerDisconnected() {
        if (active == this) active = null;
        DiagnosticLog.log(this, "notification", "listener disconnected");
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !DASHER_PACKAGE.equals(sbn.getPackageName())) return;
        Notification notification = sbn.getNotification();
        List<String> labels = labels(notification);
        DiagnosticLog.log(this, "notification", "posted key=" + sbn.getKey() + " labels=" + labels);

        FilterSettings settings = FilterStore.load(this);
        if (!settings.enabled) {
            DiagnosticLog.log(this, "notification", "ignored because auto-decline is off");
            return;
        }
        if (!NotificationOffer.isLikelyOffer(labels)) {
            DiagnosticLog.log(this, "notification", "ignored because payload does not look like an offer");
            return;
        }
        FilterStore.recordDoorDashOfferChannel(this, notification.getChannelId());
        DiagnosticLog.log(this, "notification", "DoorDash offer channel=" + notification.getChannelId());

        boolean alreadyForeground = OfferFilterService.isDasherForeground();
        attachPending(sbn.getKey(), !alreadyForeground, notification.contentIntent, false);
        if (alreadyForeground) {
            DiagnosticLog.log(this, "notification", "Dasher already foreground; requesting immediate accessibility scan");
            OfferFilterService.requestCheckFromNotification();
            return;
        }

        OfferSnapshot offer = OfferParser.parse(labels);
        OfferSnapshot activeRoute = ActiveRouteStore.load(this);
        AddOnOffer addOn = activeRoute != null && AddOnOffer.isLikely(labels)
                ? AddOnOffer.parse(activeRoute, offer, labels) : null;
        OfferRule.Decision decision = addOn == null
                ? OfferRule.evaluate(offer, settings)
                : OfferRule.evaluateAddOn(addOn, settings);
        DiagnosticLog.log(this, "notification", "parsed " +
                (addOn == null ? offer.summary() : addOn.summary()) + "; " + decision.summary());

        String evaluated = addOn == null ? offer.summary() : addOn.summary();
        if (decision.result == OfferRule.Result.DECLINE) {
            Notification.Action decline = findAction(notification, "decline");
            if (decline != null && send(decline.actionIntent)) {
                try { cancelNotification(sbn.getKey()); } catch (Exception ignored) {}
                synchronized (LOCK) { clearLocked(); }
                DiagnosticLog.log(this, "notification", "filtered offer handled silently through notification Decline action");
                return;
            }
            DiagnosticLog.log(this, "notification", "filtered by notification data; opening Dasher silently so Accessibility can decline it");
            wakeDasher(sbn);
            return;
        }

        if (decision.result == OfferRule.Result.KEEP) {
            OfferAlerts.notifyOffer(this, notification.contentIntent, decision.result,
                    evaluated + "; " + decision.summary());
            String route = wakeDasher(sbn);
            try {
                cancelNotification(sbn.getKey());
                DiagnosticLog.log(this, "notification", "replaced qualifying silent DoorDash notification with Offer Filter alert");
            } catch (Exception error) {
                DiagnosticLog.log(this, "notification", "could not remove qualifying DoorDash notification: " +
                        error.getClass().getSimpleName());
            }
            synchronized (LOCK) { clearLocked(); }
            return;
        }

        synchronized (LOCK) {
            pendingAlertAfterScreen = true;
        }
        DiagnosticLog.log(this, "notification",
                "notification lacks enough rule data; opening Dasher silently for screen evaluation before alerting");
        wakeDasher(sbn);
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null || !DASHER_PACKAGE.equals(sbn.getPackageName())) return;
        synchronized (LOCK) {
            if (sbn.getKey().equals(pendingKey)) {
                if (pendingAlertAfterScreen) {
                    DiagnosticLog.log(this, "notification",
                            "DoorDash notification removed while screen classification is still pending");
                } else {
                    clearLocked();
                }
            }
        }
        DiagnosticLog.log(this, "notification", "removed key=" + sbn.getKey());
    }

    private String wakeDasher(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        String route = "none";
        PendingIntent content = notification.contentIntent;
        if (content != null) {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    ActivityOptions options = ActivityOptions.makeBasic();
                    options.setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    content.send(this, 0, null, null, null, null, options.toBundle());
                } else {
                    content.send();
                }
                route = "notification-content-intent";
            } catch (Exception error) {
                DiagnosticLog.log(this, "notification", "contentIntent wake failed: " + error.getClass().getSimpleName());
            }
        }
        if ("none".equals(route)) {
            try {
                Intent launch = getPackageManager().getLaunchIntentForPackage(DASHER_PACKAGE);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(launch);
                    route = "direct-launch-fallback";
                }
            } catch (Exception error) {
                DiagnosticLog.log(this, "notification", "direct launch fallback failed: " + error.getClass().getSimpleName());
            }
        }
        DiagnosticLog.log(this, "notification", "wake route=" + route);
        OfferFilterService.requestCheckFromNotification();
        final String usedRoute = route;
        handler.postDelayed(() -> {
            if ("none".equals(usedRoute)) return;
            DiagnosticLog.log(this, "notification", OfferFilterService.isDasherForeground()
                    ? "wake verified: Dasher is foreground"
                    : "wake sent but Dasher is not foreground; Android likely blocked the background launch");
            OfferFilterService.requestCheckFromNotification();
        }, 1200);
        return route;
    }

    private static Notification.Action findAction(Notification notification, String action) {
        if (notification.actions == null) return null;
        for (Notification.Action candidate : notification.actions) {
            if (candidate != null && candidate.title != null &&
                    OfferControls.isButton(candidate.title.toString(), action)) return candidate;
        }
        return null;
    }

    private boolean send(PendingIntent intent) {
        if (intent == null) return false;
        try {
            intent.send();
            return true;
        } catch (PendingIntent.CanceledException error) {
            DiagnosticLog.log(this, "notification", "notification action was cancelled");
            return false;
        }
    }

    private static List<String> labels(Notification notification) {
        List<String> result = new ArrayList<>();
        if (notification == null) return result;
        Bundle extras = notification.extras;
        if (extras != null) {
            add(result, extras.getCharSequence(Notification.EXTRA_TITLE));
            add(result, extras.getCharSequence(Notification.EXTRA_TEXT));
            add(result, extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
            add(result, extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
            add(result, extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null) for (CharSequence line : lines) add(result, line);
        }
        if (notification.actions != null) {
            for (Notification.Action action : notification.actions) {
                if (action != null) add(result, action.title);
            }
        }
        return result;
    }

    private static void add(List<String> labels, CharSequence value) {
        if (value == null) return;
        String label = value.toString().trim();
        if (label.isEmpty() || labels.contains(label)) return;
        if (label.length() > 240) label = label.substring(0, 240) + "…";
        labels.add(label);
    }

    private static void attachPending(String key, boolean wake, PendingIntent contentIntent,
                                      boolean alertAfterScreen) {
        synchronized (LOCK) {
            pendingKey = key;
            pendingAt = SystemClock.uptimeMillis();
            pendingWake = wake;
            pendingContentIntent = contentIntent;
            pendingAlertAfterScreen = alertAfterScreen;
        }
    }

    private static void expireLocked(long now) {
        if (pendingKey != null && now - pendingAt > PENDING_MS) clearLocked();
    }

    private static void clearLocked() {
        pendingKey = null;
        pendingAt = 0;
        pendingWake = false;
        pendingContentIntent = null;
        pendingAlertAfterScreen = false;
    }
}

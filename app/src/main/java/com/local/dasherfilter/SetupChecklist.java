package com.local.dasherfilter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * The homepage's setup, in order, each step one quiet line ({@link SetupRow}) only while it is needed, numbered in the
 * order it is meant to be done:
 * <ol>
 * <li>Allow restricted settings: Android 13 and later on an install from the web ({@link RestrictedSettingsGuide}),
 * before Accessibility and notification access hit Android's wall;</li>
 * <li>Turn on Offer Filter in Accessibility (the screen reading);</li>
 * <li>Allow notification access (background offers);</li>
 * <li>Allow alerts (the cards for offers that pass or need a look);</li>
 * <li>Allow updates ("Install unknown apps" for this app), once the steps above are done, or at once while a verified
 * update waits for it.</li>
 * </ol>
 * An access granted but not working gets its own words and fix instead of a page whose switch is already on:
 * "Turn Offer Filter off and on in Accessibility" opens the service's page, and "Reconnect notification access" asks
 * Android to bind the listener again (a second tap opens its page). On Android 13+, a switch tried from here that
 * stayed off also offers the old hint ("switch greyed out?") when the restriction could not be told in advance.
 */
final class SetupChecklist {
    /** The prefs older versions kept these answers in (MainActivity's "setup_steps"). */
    static final String PREFS = "setup_steps";
    static final String NOTIFICATIONS_ASKED = "notifications_asked";
    static final String ACCESSIBILITY_OPENED = "accessibility_opened";
    static final String LISTENER_OPENED = "listener_opened";

    static final String RESTRICTED = RestrictedSettingsGuide.TITLE;
    static final String ACCESSIBILITY = "Turn on " + AppName.NAME + " in Accessibility";
    static final String ACCESSIBILITY_RESTART = "Turn " + AppName.NAME + " off and on in Accessibility";
    static final String NOTIFICATIONS = "Allow notification access";
    static final String NOTIFICATIONS_RECONNECT = "Reconnect notification access";
    static final String ALERTS = "Allow alerts";
    static final String UPDATES = "Allow updates";
    /** Added to a step whose switch was tried from here and stayed off (the restriction not told in advance). */
    static final String GREYED = " · switch greyed out?";
    static final String GREYED_HELP = "If Android blocks the switch, open App info, tap ⋮ → Allow restricted settings, "
            + "then come back here.";
    static final String FIX = "Fix";
    /** Where the accessibility list keeps downloaded apps, for phones without the service's own page. */
    static final String FIND_IN_LIST = "In Accessibility, open Downloaded apps (or Installed apps), then "
            + AppName.NAME + ".";
    /** How long after asking Android to reconnect the listener a second tap opens its page instead. */
    static final long RECONNECT_GRACE_MS = 60_000;

    private final Activity activity;
    private final int alertsRequest;
    private final SetupRow restricted;
    private final SetupRow accessibility;
    private final SetupRow notifications;
    private final SetupRow alerts;
    private final SetupRow updates;
    private final MainActivity.Asked<Boolean> accessibilityOn;
    private final MainActivity.Asked<Boolean> listenerOn;
    private final MainActivity.Asked<Boolean> installsAllowed;
    private final MainActivity.Asked<RestrictedSettingsGuide.State> restriction;
    /** A switch tried from here that stayed off, on Android 13+ (the hint shows only while the restriction is unknown). */
    private boolean accessibilityTried;
    private boolean listenerTried;
    private boolean guideNeeded;
    private boolean accessibilityHint;
    private boolean listenerHint;
    private long reconnectAskedAt = -1;

    /**
     * @param parent        the homepage column the lines go in, in this order
     * @param alertsRequest the request code of the alerts permission, answered in the Activity
     */
    SetupChecklist(Activity activity, Ui ui, LinearLayout parent, int alertsRequest) {
        this.activity = activity;
        this.alertsRequest = alertsRequest;
        accessibilityOn = new MainActivity.Asked<>(() -> accessibilityEnabled(activity));
        listenerOn = new MainActivity.Asked<>(() -> OfferNotificationService.hasAccess(activity));
        installsAllowed = new MainActivity.Asked<>(() -> activity.getPackageManager().canRequestPackageInstalls());
        restriction = new MainActivity.Asked<>(() -> RestrictedSettingsGuide.state(activity));
        restricted = new SetupRow(activity, ui, parent, () -> guide(firstMissing()));
        accessibility = new SetupRow(activity, ui, parent, this::fixAccessibility);
        notifications = new SetupRow(activity, ui, parent, this::fixNotifications);
        alerts = new SetupRow(activity, ui, parent, this::allowAlerts);
        updates = new SetupRow(activity, ui, parent, () -> open(UpdateNotices.allowUpdates(activity)));
    }

    /** Android is asked again, at the next refresh. */
    void forget() {
        accessibilityOn.forget();
        listenerOn.forget();
        installsAllowed.forget();
        restriction.forget();
    }

    /**
     * Back on the page, perhaps from Android's settings: everything is asked again, a switch tried from here that
     * stayed off is noted, and coming back from App info opens the access being set up (the guide's last step).
     */
    void resumed() {
        forget();
        SharedPreferences prefs = prefs();
        boolean a11y = accessibilityOn.get();
        boolean listener = listenerOn.get();
        accessibilityTried = Build.VERSION.SDK_INT >= 33 && prefs.getBoolean(ACCESSIBILITY_OPENED, false) && !a11y;
        listenerTried = Build.VERSION.SDK_INT >= 33 && prefs.getBoolean(LISTENER_OPENED, false) && !listener;
        RestrictedSettingsGuide.Access next = RestrictedSettingsGuide.resumed(activity, a11y, listener);
        restriction.forget();
        if (next != null) {
            toast(next == RestrictedSettingsGuide.Access.ACCESSIBILITY ? "Now turn on " + AppName.NAME + " here."
                    : "Now allow " + AppName.NAME + " here.");
            openAccess(next);
        }
    }

    /** Whether this app may install its updates ("Install unknown apps"), as Android said lately. */
    boolean installsAllowed() {
        return installsAllowed.get();
    }

    /**
     * The lines as things stand: {@code readerConnected} is the screen reader running, {@code alertsAllowed} as the
     * page asked Android lately.
     */
    void refresh(boolean readerConnected, boolean alertsAllowed) {
        boolean a11y = accessibilityOn.get();
        boolean listenerGranted = listenerOn.get();
        boolean listenerConnected = OfferNotificationService.isConnected();
        RestrictedSettingsGuide.State state = restriction.get();
        guideNeeded = RestrictedSettingsGuide.needed(activity, state, a11y, listenerGranted);
        boolean unknown = state == RestrictedSettingsGuide.State.UNKNOWN;
        accessibilityHint = unknown && accessibilityTried;
        listenerHint = unknown && listenerTried;
        int step = 0;
        if (RestrictedSettingsGuide.applies(activity, state)) step++;
        if (guideNeeded) restricted.show(SetupRow.Mark.STEP, step, RESTRICTED, FIX);
        else restricted.hide();

        step++;
        if (readerConnected) {
            accessibility.hide();
        } else if (a11y) {
            accessibility.show(SetupRow.Mark.PROBLEM, step, ACCESSIBILITY_RESTART, FIX);
        } else {
            accessibility.show(SetupRow.Mark.STEP, step, ACCESSIBILITY + (accessibilityHint ? GREYED : ""), FIX);
        }

        step++;
        if (listenerConnected) {
            notifications.hide();
        } else if (listenerGranted) {
            notifications.show(SetupRow.Mark.PROBLEM, step, NOTIFICATIONS_RECONNECT, FIX);
        } else {
            notifications.show(SetupRow.Mark.STEP, step, NOTIFICATIONS + (listenerHint ? GREYED : ""), FIX);
        }

        step++;
        if (alertsAllowed) alerts.hide();
        else alerts.show(SetupRow.Mark.STEP, step, ALERTS, FIX);

        step++;
        // Updates matter once the app works, or at once when a verified update waits for this switch.
        boolean coreDone = readerConnected && listenerConnected && alertsAllowed;
        if (installsAllowed.get() || (!coreDone && Updater.heldVersion(activity) == null)) updates.hide();
        else updates.show(SetupRow.Mark.STEP, step, UPDATES, FIX);
    }

    // ---- Steps ----

    private RestrictedSettingsGuide.Access firstMissing() {
        return accessibilityOn.get() ? RestrictedSettingsGuide.Access.NOTIFICATIONS
                : RestrictedSettingsGuide.Access.ACCESSIBILITY;
    }

    private void guide(RestrictedSettingsGuide.Access target) {
        RestrictedSettingsGuide.show(activity, target, this::openAccess, this::open);
    }

    private void fixAccessibility() {
        if (accessibilityOn.get() && !OfferFilterService.isConnected()) {
            // On, but not running (a crash, an update Android did not rebind, a stopped reader): off and on fixes it.
            DiagnosticLog.log(activity, "setup", "screen reading on but not running; opening its page to restart it");
            toast("Turn " + AppName.NAME + " off, then on again.");
            openAccessibility();
        } else if (guideNeeded) {
            guide(RestrictedSettingsGuide.Access.ACCESSIBILITY);
        } else if (accessibilityHint) {
            greyedOut(RestrictedSettingsGuide.Access.ACCESSIBILITY);
        } else {
            openAccessibility();
        }
    }

    private void fixNotifications() {
        if (listenerOn.get() && !OfferNotificationService.isConnected()) {
            long now = SystemClock.uptimeMillis();
            boolean askedLately = reconnectAskedAt >= 0 && now >= reconnectAskedAt
                    && now - reconnectAskedAt < RECONNECT_GRACE_MS;
            if (!askedLately) {
                reconnectAskedAt = now;
                try {
                    NotificationListenerService.requestRebind(
                            new ComponentName(activity, OfferNotificationService.class));
                    DiagnosticLog.log(activity, "setup", "notification access allowed but not connected; rebind asked");
                    toast("Reconnecting notification access…");
                    return;
                } catch (RuntimeException refused) {
                    DiagnosticLog.log(activity, "setup", "rebind refused: " + refused.getClass().getSimpleName());
                }
            }
            // Asked already (or refused): the page itself, where turning it off and on reconnects it.
            toast("Turn notification access for " + AppName.NAME + " off, then on again.");
            openListener();
        } else if (guideNeeded) {
            guide(RestrictedSettingsGuide.Access.NOTIFICATIONS);
        } else if (listenerHint) {
            greyedOut(RestrictedSettingsGuide.Access.NOTIFICATIONS);
        } else {
            openListener();
        }
    }

    /** The fallback for a switch that stayed off when the restriction could not be told in advance. */
    private void greyedOut(RestrictedSettingsGuide.Access target) {
        boolean a11y = target == RestrictedSettingsGuide.Access.ACCESSIBILITY;
        OwnWindowTouches.show(new AlertDialog.Builder(activity)
                .setTitle("Switch greyed out?")
                .setMessage(GREYED_HELP)
                .setPositiveButton("App info", (dialog, which) -> RestrictedSettingsGuide.appInfo(activity, target,
                        this::open))
                .setNegativeButton(a11y ? "Accessibility" : "Notification access", (dialog, which) -> openAccess(target))
                .setNeutralButton("Cancel", null));
    }

    private void openAccess(RestrictedSettingsGuide.Access access) {
        if (access == RestrictedSettingsGuide.Access.ACCESSIBILITY) openAccessibility();
        else openListener();
    }

    /** The service's own Accessibility page where Android has one (12 and later), else the list. */
    private void openAccessibility() {
        prefs().edit().putBoolean(ACCESSIBILITY_OPENED, true).apply();
        if (Build.VERSION.SDK_INT >= 31) {
            // AOSP's service-specific settings action is not part of the public SDK constants. OEMs may omit it.
            Intent details = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME,
                            new ComponentName(activity, OfferFilterService.class).flattenToString());
            try {
                activity.startActivity(details);
                return;
            } catch (RuntimeException unsupported) {
                // The public accessibility list remains available on phones without a direct service screen.
            }
        }
        toast(FIND_IN_LIST);
        open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    /** The listener's own page (Android 11 and later), else the list of apps with notification access. */
    private void openListener() {
        prefs().edit().putBoolean(LISTENER_OPENED, true).apply();
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        if (Build.VERSION.SDK_INT >= 30) {
            intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    new ComponentName(activity, OfferNotificationService.class).flattenToString());
        }
        open(intent);
    }

    /** Android's own question first (13 and later), and the app's notification settings once it was answered. */
    private void allowAlerts() {
        OfferAlerts.ensureChannel(activity);
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            SharedPreferences prefs = prefs();
            boolean asked = prefs.getBoolean(NOTIFICATIONS_ASKED, false);
            if (!asked || activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                prefs.edit().putBoolean(NOTIFICATIONS_ASKED, true).apply();
                activity.requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, alertsRequest);
                return;
            }
        }
        // Passing offers, review cards and the paused-until-opened reminder each have their own channel.
        open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,
                activity.getPackageName()));
    }

    // ---- Android ----

    /** Whether Offer Filter's screen reading is switched on in Accessibility (running or not). */
    static boolean accessibilityEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName ours = new ComponentName(context, OfferFilterService.class);
        for (String component : enabled.split(":")) {
            if (ours.equals(ComponentName.unflattenFromString(component))) return true;
        }
        return false;
    }

    private void open(Intent intent) {
        try {
            activity.startActivity(intent);
        } catch (RuntimeException error) {
            DiagnosticLog.log(activity, "setup", "Android could not open " + intent.getAction() + ": "
                    + error.getClass().getSimpleName());
            toast("Android couldn't open that screen.");
        }
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

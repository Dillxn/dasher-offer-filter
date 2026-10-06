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
import java.util.ArrayList;
import java.util.List;

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
 * With three or more to do, only the first shows, and the rest fold into one line, "N more to set up", whose tap shows
 * them all: the lines cross the constellation's lower part, and five of them would shrink it to a sliver under words,
 * its knobs out of reach just as the start line says to drag one.
 *
 * <p>An access granted but not working gets its own words and fix instead of a page whose switch is already on:
 * "Turn Offer Filter off and on in Accessibility" opens the service's page, and "Reconnect notification access" asks
 * Android to bind the listener again (a second tap opens its page). On Android 13+, a switch tried from here that
 * stayed off also offers the old hint ("switch greyed out?") when the restriction could not be told in advance. Each
 * of Android's pages opens with at most one toast, naming what to turn on there.
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
    /** The folded steps' line, and its action. */
    static final String SHOW = "Show";
    /** From this many steps to do, the ones after the first fold into one line. */
    static final int FOLD_FROM = 3;
    /** The listener's name in Android's list of apps with notification access (strings.xml listener_name). */
    static final String LISTENER_NAME = AppName.NAME + " background offers";
    /** Where the accessibility list keeps downloaded apps, for phones that do not let an app open its own page. */
    static final String FIND_IN_LIST = "In Accessibility, open Downloaded apps (or Installed apps) → " + AppName.NAME
            + ", and turn it on.";
    static final String RESTART_ON_PAGE = "Turn " + AppName.NAME + " off, then on again.";
    static final String RESTART_IN_LIST = "In Accessibility, open Downloaded apps (or Installed apps) → "
            + AppName.NAME + ", then turn it off and on again.";
    /** Back from App info, on the service's own page. */
    static final String NOW_ON_PAGE = "Now turn on " + AppName.NAME + " here.";
    static final String FIND_LISTENER = "Turn on " + LISTENER_NAME + " here.";
    static final String NOW_LISTENER = "Now allow " + AppName.NAME + " here.";
    static final String RECONNECT_ON_PAGE = "Turn notification access for " + AppName.NAME + " off, then on again.";
    static final String RECONNECT_IN_LIST = "Turn " + LISTENER_NAME + " off, then on again.";
    /** Android's own name for the switch behind Allow updates ("Install unknown apps" for this app). */
    static final String ALLOW_SOURCE = "Turn on Allow from this source.";
    static final String ALLOW_NOTIFICATIONS = "Turn on notifications for " + AppName.NAME + ", every category.";
    /** How long after asking Android to reconnect the listener a second tap opens its page instead. */
    static final long RECONNECT_GRACE_MS = 60_000;

    private final Activity activity;
    private final int alertsRequest;
    private final SetupRow restricted;
    private final SetupRow accessibility;
    private final SetupRow notifications;
    private final SetupRow alerts;
    private final SetupRow updates;
    /** "N more to set up": the steps after the first, folded. */
    private final SetupRow more;
    private final MainActivity.Asked<Boolean> accessibilityOn;
    private final MainActivity.Asked<Boolean> listenerOn;
    private final MainActivity.Asked<Boolean> installsAllowed;
    private final MainActivity.Asked<RestrictedSettingsGuide.Reading> restriction;
    /**
     * A switch tried from here that stayed off, on Android 13+ (the hint shows only while the restriction is unknown,
     * or Android's record says allowed).
     */
    private boolean accessibilityTried;
    private boolean listenerTried;
    private boolean guideNeeded;
    private boolean accessibilityHint;
    private boolean listenerHint;
    private long reconnectAskedAt = -1;
    /** The folded steps were asked for: every step shows, for as long as this page lives. */
    private boolean unfolded;
    /** What the page said last time, so a tap on the folded line can lay the steps out at once. */
    private boolean lastReaderConnected;
    private boolean lastAlertsAllowed;

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
        restriction = new MainActivity.Asked<>(() -> RestrictedSettingsGuide.read(activity));
        restricted = new SetupRow(activity, ui, parent, () -> guide(firstMissing()));
        accessibility = new SetupRow(activity, ui, parent, this::fixAccessibility);
        notifications = new SetupRow(activity, ui, parent, this::fixNotifications);
        alerts = new SetupRow(activity, ui, parent, this::allowAlerts);
        updates = new SetupRow(activity, ui, parent, this::allowUpdates);
        more = new SetupRow(activity, ui, parent, () -> {
            unfolded = true;
            refresh(lastReaderConnected, lastAlertsAllowed);
        });
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
     * stayed off is noted, and coming back from App info opens the access being set up (the guide's last step), but
     * only within minutes of leaving for it, never while a dash is on or beside another app, and never behind the
     * notice ({@code behindNotice}: nothing behind it refreshes, so what the guide noted waits for the homepage).
     */
    void resumed(boolean behindNotice) {
        forget();
        if (behindNotice) return;
        SharedPreferences prefs = prefs();
        boolean a11y = accessibilityOn.get();
        boolean listener = listenerOn.get();
        accessibilityTried = Build.VERSION.SDK_INT >= 33 && prefs.getBoolean(ACCESSIBILITY_OPENED, false) && !a11y;
        listenerTried = Build.VERSION.SDK_INT >= 33 && prefs.getBoolean(LISTENER_OPENED, false) && !listener;
        boolean mayOpen = !activity.isInMultiWindowMode() && !UpdateReadyRow.dashOn(activity);
        RestrictedSettingsGuide.Access next = RestrictedSettingsGuide.resumed(activity, a11y, listener, mayOpen);
        restriction.forget();
        if (next == RestrictedSettingsGuide.Access.ACCESSIBILITY) openAccessibility(NOW_ON_PAGE, FIND_IN_LIST);
        else if (next == RestrictedSettingsGuide.Access.NOTIFICATIONS) openListener(NOW_LISTENER, FIND_LISTENER);
    }

    /** Whether this app may install its updates ("Install unknown apps"), as Android said lately. */
    boolean installsAllowed() {
        return installsAllowed.get();
    }

    /** One line to show, in the checklist's order. */
    private static final class Line {
        final SetupRow row;
        final SetupRow.Mark mark;
        final int number;
        final String words;

        Line(SetupRow row, SetupRow.Mark mark, int number, String words) {
            this.row = row;
            this.mark = mark;
            this.number = number;
            this.words = words;
        }
    }

    /**
     * The lines as things stand: {@code readerConnected} is the screen reader running, {@code alertsAllowed} as the
     * page asked Android lately.
     */
    void refresh(boolean readerConnected, boolean alertsAllowed) {
        lastReaderConnected = readerConnected;
        lastAlertsAllowed = alertsAllowed;
        boolean a11y = accessibilityOn.get();
        boolean listenerGranted = listenerOn.get();
        boolean listenerConnected = OfferNotificationService.isConnected();
        RestrictedSettingsGuide.Reading reading = restriction.get();
        guideNeeded = RestrictedSettingsGuide.needed(activity, reading, a11y, listenerGranted);
        boolean hint = reading.hintAfterTry();
        accessibilityHint = hint && accessibilityTried;
        listenerHint = hint && listenerTried;
        List<Line> lines = new ArrayList<>();
        int step = 0;
        if (RestrictedSettingsGuide.applies(activity, reading)) step++;
        if (guideNeeded) lines.add(new Line(restricted, SetupRow.Mark.STEP, step, RESTRICTED));

        step++;
        if (!readerConnected) {
            lines.add(a11y ? new Line(accessibility, SetupRow.Mark.PROBLEM, step, ACCESSIBILITY_RESTART)
                    : new Line(accessibility, SetupRow.Mark.STEP, step,
                            ACCESSIBILITY + (accessibilityHint ? GREYED : "")));
        }

        step++;
        if (!listenerConnected) {
            lines.add(listenerGranted ? new Line(notifications, SetupRow.Mark.PROBLEM, step, NOTIFICATIONS_RECONNECT)
                    : new Line(notifications, SetupRow.Mark.STEP, step, NOTIFICATIONS + (listenerHint ? GREYED : "")));
        }

        step++;
        if (!alertsAllowed) lines.add(new Line(alerts, SetupRow.Mark.STEP, step, ALERTS));

        step++;
        // Updates matter once the app works, or at once when a verified update waits for this switch.
        boolean coreDone = readerConnected && listenerConnected && alertsAllowed;
        if (!installsAllowed.get() && (coreDone || Updater.heldVersion(activity) != null)) {
            lines.add(new Line(updates, SetupRow.Mark.STEP, step, UPDATES));
        }

        boolean fold = !unfolded && lines.size() >= FOLD_FROM;
        int showing = fold ? 1 : lines.size();
        for (SetupRow row : new SetupRow[] {restricted, accessibility, notifications, alerts, updates}) {
            Line line = null;
            for (int i = 0; i < showing; i++) if (lines.get(i).row == row) line = lines.get(i);
            if (line == null) row.hide();
            else row.show(line.mark, line.number, line.words, FIX);
        }
        if (fold) more.show(SetupRow.Mark.MORE, 0, (lines.size() - 1) + " more to set up", SHOW);
        else more.hide();
    }

    // ---- Steps ----

    private RestrictedSettingsGuide.Access firstMissing() {
        return accessibilityOn.get() ? RestrictedSettingsGuide.Access.NOTIFICATIONS
                : RestrictedSettingsGuide.Access.ACCESSIBILITY;
    }

    private void guide(RestrictedSettingsGuide.Access target) {
        RestrictedSettingsGuide.show(activity, target, this::openAccess, this::open);
    }

    /**
     * While the restriction stands: before a try, an access's own Fix leads through the guide; after one, it opens
     * the switch itself (allowed already, perhaps, as the website's help says to), and the restricted step's line
     * keeps the guide.
     */
    private boolean guideFirst() {
        return guideNeeded && restriction.get().state == RestrictedSettingsGuide.State.UNTRIED;
    }

    private void fixAccessibility() {
        if (accessibilityOn.get() && !OfferFilterService.isConnected()) {
            // On, but not running (a crash, an update Android did not rebind, a stopped reader): off and on fixes it.
            DiagnosticLog.log(activity, "setup", "screen reading on but not running; opening its page to restart it");
            openAccessibility(RESTART_ON_PAGE, RESTART_IN_LIST);
        } else if (guideFirst()) {
            guide(RestrictedSettingsGuide.Access.ACCESSIBILITY);
        } else if (guideNeeded) {
            RestrictedSettingsGuide.trying(activity, RestrictedSettingsGuide.Access.ACCESSIBILITY);
            openAccess(RestrictedSettingsGuide.Access.ACCESSIBILITY);
        } else if (accessibilityHint) {
            greyedOut(RestrictedSettingsGuide.Access.ACCESSIBILITY);
        } else {
            openAccess(RestrictedSettingsGuide.Access.ACCESSIBILITY);
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
            openListener(RECONNECT_ON_PAGE, RECONNECT_IN_LIST);
        } else if (guideFirst()) {
            guide(RestrictedSettingsGuide.Access.NOTIFICATIONS);
        } else if (guideNeeded) {
            RestrictedSettingsGuide.trying(activity, RestrictedSettingsGuide.Access.NOTIFICATIONS);
            openAccess(RestrictedSettingsGuide.Access.NOTIFICATIONS);
        } else if (listenerHint) {
            greyedOut(RestrictedSettingsGuide.Access.NOTIFICATIONS);
        } else {
            openAccess(RestrictedSettingsGuide.Access.NOTIFICATIONS);
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

    /** An access's own page, as a step's Fix opens it: the page needs no toast, the list says where to look. */
    private void openAccess(RestrictedSettingsGuide.Access access) {
        if (access == RestrictedSettingsGuide.Access.ACCESSIBILITY) openAccessibility(null, FIND_IN_LIST);
        else openListener(null, FIND_LISTENER);
    }

    /**
     * The service's own Accessibility page where Android lets an app open it, else the list. Stock Android guards that
     * page with a permission only system apps hold (OPEN_ACCESSIBILITY_DETAILS_SETTINGS), so the list is what most
     * phones show; one toast, for the page that opened: {@code onPage} (none when null) or {@code inList}.
     */
    private void openAccessibility(String onPage, String inList) {
        prefs().edit().putBoolean(ACCESSIBILITY_OPENED, true).apply();
        if (Build.VERSION.SDK_INT >= 31) {
            // AOSP's service-specific settings action is not part of the public SDK constants. OEMs may omit it.
            Intent details = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME,
                            new ComponentName(activity, OfferFilterService.class).flattenToString());
            try {
                activity.startActivity(details);
                if (onPage != null) toast(onPage);
                return;
            } catch (RuntimeException refused) {
                // The public accessibility list remains available on phones without a direct service screen.
                DiagnosticLog.log(activity, "setup", "the service's own Accessibility page is not open to apps here ("
                        + refused.getClass().getSimpleName() + "); the list instead");
            }
        }
        if (open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) toast(inList);
    }

    /**
     * The listener's own page (Android 11 and later), else the list of apps with notification access, where it is
     * {@link #LISTENER_NAME}; one toast, as {@link #openAccessibility}.
     */
    private void openListener(String onPage, String inList) {
        prefs().edit().putBoolean(LISTENER_OPENED, true).apply();
        if (Build.VERSION.SDK_INT >= 30) {
            Intent detail = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    new ComponentName(activity, OfferNotificationService.class).flattenToString());
            try {
                activity.startActivity(detail);
                if (onPage != null) toast(onPage);
                return;
            } catch (RuntimeException missing) {
                // A phone without the listener's own page: the list, where the listener has its own name.
                DiagnosticLog.log(activity, "setup", "no page of its own for notification access here ("
                        + missing.getClass().getSimpleName() + "); the list instead");
            }
        }
        if (open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) toast(inList);
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
        if (open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,
                activity.getPackageName()))) {
            toast(ALLOW_NOTIFICATIONS);
        }
    }

    /** Android's "Install unknown apps" switch for this app, named as Android names it there. */
    private void allowUpdates() {
        if (open(UpdateNotices.allowUpdates(activity))) toast(ALLOW_SOURCE);
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

    /** Opens one of Android's pages; whether it opened (a toast says so when it did not). */
    private boolean open(Intent intent) {
        try {
            activity.startActivity(intent);
            return true;
        } catch (RuntimeException error) {
            DiagnosticLog.log(activity, "setup", "Android could not open " + intent.getAction() + ": "
                    + error.getClass().getSimpleName());
            toast("Android couldn't open that screen.");
            return false;
        }
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

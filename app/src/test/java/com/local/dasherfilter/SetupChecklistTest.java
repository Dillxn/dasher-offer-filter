package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.Instrumentation;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowInstrumentation;
import org.robolectric.shadows.ShadowNotificationListenerService;
import org.robolectric.shadows.ShadowToast;

/**
 * BETA-03, BETA-15 and the Allow updates step of BETA-04: the homepage's setup leads a stranger who sideloaded the
 * app through Android 13+'s restricted settings before either switch hits the wall, tells a granted-but-stopped access
 * from one never granted, and asks to allow updates once the rest works (or at once when an update waits). With three
 * or more steps to do, the ones after the first fold into one line, so the constellation keeps its sky.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class SetupChecklistTest extends AndroidAdapterTestBase {
    private static final String FIX = ". Fix.";
    private static final String DETAILS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS";

    @Before public void clean() {
        app.getSharedPreferences(SetupChecklist.PREFS, 0).edit().clear().commit();
        Updater.prefs(app).edit().remove("ready_version").commit();
        GuardedPages.refused.clear();
    }

    @After public void reopen() {
        GuardedPages.refused.clear();
    }

    /** Installed from a file the browser downloaded, as testers install it from offerfilter.org. */
    private void sideloaded(int source) {
        Shadows.shadowOf(app.getPackageManager()).setInstallSourceInfo(app.getPackageName(), "com.android.chrome",
                null, null, "com.google.android.packageinstaller", null, source);
    }

    /** Android's own record of the restriction, as the app may read it. */
    private void restriction(int mode) {
        Shadows.shadowOf(app.getSystemService(AppOpsManager.class)).setMode(RestrictedSettingsGuide.OP,
                Process.myUid(), app.getPackageName(), mode);
    }

    private void accessibilityOn(boolean on) {
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                on ? new ComponentName(app, OfferFilterService.class).flattenToString() : "");
    }

    private void listenerAllowed(boolean allowed) {
        Shadows.shadowOf(app.getSystemService(android.app.NotificationManager.class))
                .setNotificationListenerAccessGranted(new ComponentName(app, OfferNotificationService.class), allowed);
    }

    private static void tick() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
    }

    private static View line(View content, String words) {
        return shownIcon(content, words + FIX);
    }

    /** The folded steps' line, for {@code count} more steps. */
    private static View more(View content, int count) {
        return shownIcon(content, count + " more to set up. " + SetupChecklist.SHOW + ".");
    }

    /** Every step shown, as a tap on the folded line asks. */
    private static void unfold(View content, int count) {
        View folded = more(content, count);
        assertNotNull(count + " more to set up", folded);
        folded.performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static String badge(View line) {
        return ((TextView) ((ViewGroup) line).getChildAt(0)).getText().toString();
    }

    private Intent opened() {
        return Shadows.shadowOf(app).getNextStartedActivity();
    }

    private static void press(int button) {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("a dialog", dialog);
        dialog.getButton(button).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static String message() {
        return Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getMessage().toString();
    }

    /**
     * Android's pages as an app may open them: stock Settings guards the service's own Accessibility page with a
     * permission only system apps hold (a SecurityException), and a phone may lack a page altogether.
     */
    @Implements(Instrumentation.class)
    public static class GuardedPages extends ShadowInstrumentation {
        static final Set<String> refused = new HashSet<>();

        @Implementation
        protected Instrumentation.ActivityResult execStartActivity(Context who, IBinder contextThread, IBinder token,
                                                                   Activity target, Intent intent, int requestCode,
                                                                   Bundle options) {
            String action = intent == null ? null : intent.getAction();
            if (DETAILS.equals(action) && refused.contains(action)) {
                throw new SecurityException("Permission Denial: starting Intent { act=" + action + " } requires "
                        + "android.permission.OPEN_ACCESSIBILITY_DETAILS_SETTINGS");
            }
            if (action != null && refused.contains(action)) throw new ActivityNotFoundException(action);
            return super.execStartActivity(who, contextThread, token, target, intent, requestCode, options);
        }
    }

    @Test public void aSideloadLeadsWithRestrictedSettingsThenReturnsToTheSwitchItWasFor() {
        sideloaded(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE);
        restriction(AppOpsManager.MODE_ERRORED);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            View restricted = line(content, SetupChecklist.RESTRICTED);
            assertNotNull("before either switch hits the wall", restricted);
            assertEquals("1", badge(restricted));
            assertNull("the steps after the first fold away", line(content, SetupChecklist.ACCESSIBILITY));
            View folded = more(content, 2);
            assertNotNull(folded);
            ViewGroup column = (ViewGroup) restricted.getParent();
            assertEquals("right under the first", column.indexOfChild(folded), visibleAfter(column, restricted));
            unfold(content, 2);
            View accessibility = line(content, SetupChecklist.ACCESSIBILITY);
            View notifications = line(content, SetupChecklist.NOTIFICATIONS);
            assertNotNull(accessibility);
            assertNotNull(notifications);
            assertNull("all of them show now", more(content, 2));
            assertTrue("first", column.indexOfChild(restricted) < column.indexOfChild(accessibility));
            assertEquals("2", badge(accessibility));
            assertEquals("3", badge(notifications));

            // Not tried yet: Android shows the App info item only after one try, so the switch comes first.
            restricted.performClick();
            assertEquals(RestrictedSettingsGuide.TRY_FIRST, message());
            assertTrue("one line with the exact path", message().contains("App info → ⋮ → Allow restricted settings"));
            press(AlertDialog.BUTTON_POSITIVE);
            assertEquals(DETAILS, opened().getAction());

            // Android blocked it (its record says the user saw the restriction): the path to allow it.
            restriction(AppOpsManager.MODE_IGNORED);
            activity.pause().resume();
            tick();
            assertNull("nothing opens by itself", opened());
            line(content, SetupChecklist.RESTRICTED).performClick();
            assertEquals(RestrictedSettingsGuide.PATH, message());
            press(AlertDialog.BUTTON_POSITIVE);
            Intent info = opened();
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, info.getAction());
            assertEquals("package:" + app.getPackageName(), info.getDataString());

            // Allowed in App info and back: the switch it was for opens next, and the step is done.
            restriction(AppOpsManager.MODE_ALLOWED);
            activity.pause().resume();
            tick();
            Intent next = opened();
            assertNotNull("back to the right access setting", next);
            assertEquals(DETAILS, next.getAction());
            assertEquals(SetupChecklist.NOW_ON_PAGE, ShadowToast.getTextOfLatestToast());
            assertNull("success seen on return", line(content, SetupChecklist.RESTRICTED));
            assertNotNull(line(content, SetupChecklist.ACCESSIBILITY));

            // Notification access then opens straight to its page: no restriction stands in the way.
            line(content, SetupChecklist.NOTIFICATIONS).performClick();
            assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, opened().getAction());
        }
    }

    /** The index of the next visible child after {@code view} in {@code column}. */
    private static int visibleAfter(ViewGroup column, View view) {
        for (int i = column.indexOfChild(view) + 1; i < column.getChildCount(); i++) {
            if (column.getChildAt(i).getVisibility() == View.VISIBLE) return i;
        }
        return -1;
    }

    /**
     * BETA-03's walk-through on a stock phone (the record unreadable): the user tries the switch, follows the
     * website's help to App info on their own, and taps Fix again. The guide now offers the switch beside App info
     * (whose menu item is gone once allowed), and the access rows open their switches straight away.
     */
    @Test public void afterATryTheSwitchStaysInReachForOneWhoAllowedItElsewhere() {
        sideloaded(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE);
        restriction(AppOpsManager.MODE_DEFAULT);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            line(content, SetupChecklist.RESTRICTED).performClick();
            assertEquals(RestrictedSettingsGuide.TRY_FIRST, message());
            press(AlertDialog.BUTTON_POSITIVE);
            assertEquals("the switch, tried", DETAILS, opened().getAction());
            activity.pause().resume();
            tick();
            assertNull(opened());

            // Allowed in App info from the launcher, as offerfilter.org/install says; back here, Fix again.
            line(content, SetupChecklist.RESTRICTED).performClick();
            assertEquals(RestrictedSettingsGuide.PATH, message());
            AlertDialog guide = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("Open App info", guide.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertEquals("Try the switch", guide.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
            press(AlertDialog.BUTTON_NEUTRAL);
            assertEquals("not only App info, whose menu item is gone once allowed", DETAILS, opened().getAction());

            // And the access rows open their own switches now, with no dialog in the way.
            ShadowAlertDialog.reset();
            unfold(content, 2);
            line(content, SetupChecklist.NOTIFICATIONS).performClick();
            assertNull("no guide in front of the switch after a try", ShadowAlertDialog.getLatestAlertDialog());
            assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, opened().getAction());

            accessibilityOn(true);
            activity.pause().resume();
            tick();
            assertNull("an access turned on: the step is done", line(content, SetupChecklist.RESTRICTED));
            assertTrue(DiagnosticLog.read(app).contains("restricted settings allowed: an access turned on"));
        } finally {
            accessibilityOn(false);
        }
    }

    @Test public void theAccessOpenedOnReturnFromAppInfoIsATryOfItsOwn() {
        sideloaded(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE);
        restriction(AppOpsManager.MODE_DEFAULT);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            line(content, SetupChecklist.RESTRICTED).performClick();
            press(AlertDialog.BUTTON_NEUTRAL);
            assertEquals("App info, for one who tried before", Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    opened().getAction());
            activity.pause().resume();
            tick();
            assertEquals("back from App info: the switch it was for", DETAILS, opened().getAction());
            // Still blocked: that was a try, so the guide gives the path now, not "tap it once" again.
            activity.pause().resume();
            tick();
            assertNull(opened());
            line(content, SetupChecklist.RESTRICTED).performClick();
            assertEquals(RestrictedSettingsGuide.PATH, message());
        }
    }

    @Test public void theReturnFromAppInfoIsHonouredOnlyForMinutesAndNeverMidDashOrBehindTheNotice() {
        sideloaded(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE);
        restriction(AppOpsManager.MODE_DEFAULT);
        android.content.SharedPreferences prefs = app.getSharedPreferences(SetupChecklist.PREFS, 0);
        long now = System.currentTimeMillis();
        // Left for App info eleven minutes ago (and never came back until now).
        prefs.edit().putString("restricted_return_to", "ACCESSIBILITY")
                .putLong("restricted_return_at", now - RestrictedSettingsGuide.RETURN_WITHIN_MS - 60_000).commit();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tick();
            assertNull("too long ago to be why the page opened", opened());
            assertFalse("and forgotten", prefs.contains("restricted_return_to"));
        }

        // Mid-dash, Offer Filter beside Dasher perhaps: Settings never replaces the page by itself.
        prefs.edit().putString("restricted_return_to", "ACCESSIBILITY")
                .putLong("restricted_return_at", System.currentTimeMillis()).commit();
        Dashing.seen(app);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tick();
            assertNull("a dash is on", opened());
        } finally {
            app.getSharedPreferences("dashing", 0).edit().clear().commit();
            Dashing.forgetCache();
        }

        // Behind the first-run notice nothing refreshes, and nothing opens.
        prefs.edit().putString("restricted_return_to", "ACCESSIBILITY")
                .putLong("restricted_return_at", System.currentTimeMillis()).commit();
        ConsentedTestApp.forget(app);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tick();
            assertNotNull(shownTextContaining(activity.get().findViewById(android.R.id.content), Consent.TITLE));
            assertNull("nothing opens behind the notice", opened());
        } finally {
            ConsentedTestApp.accept(app);
        }
    }

    @Test public void anUnreadableRestrictionFollowsTheInstallSourceAndAGrantedAccessProvesItLifted() {
        sideloaded(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE);
        restriction(AppOpsManager.MODE_DEFAULT);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNotNull(line(content, SetupChecklist.RESTRICTED));
            unfold(content, 2);
            line(content, SetupChecklist.NOTIFICATIONS).performClick();
            assertEquals("for notification access too", RestrictedSettingsGuide.TRY_FIRST, message());
            press(AlertDialog.BUTTON_NEUTRAL);
            assertEquals("App info at once, for one who tried before", Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    opened().getAction());

            // The user allowed it and turned screen reading on from there: proof the restriction lifted.
            accessibilityOn(true);
            activity.pause().resume();
            tick();
            assertEquals("back to the notification page it was for", Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS,
                    opened().getAction());
            assertEquals(SetupChecklist.NOW_LISTENER, ShadowToast.getTextOfLatestToast());
            assertNull(line(content, SetupChecklist.RESTRICTED));
            assertTrue(DiagnosticLog.read(app).contains("restricted settings allowed: an access turned on"));
            accessibilityOn(false);
            activity.pause().resume();
            tick();
            assertNull("kept: turning a switch off later does not bring the step back",
                    line(content, SetupChecklist.RESTRICTED));
        }
    }

    @Test public void bothAccessesOnSettleTheStepForGoodSoTurningBothOffLaterBringsNoFalseWords() {
        // A tester who allowed it long ago, both accesses on; offerfilter.org says turning both off stops it all.
        sideloaded(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE);
        restriction(AppOpsManager.MODE_DEFAULT);
        accessibilityOn(true);
        listenerAllowed(true);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNull(line(content, SetupChecklist.RESTRICTED));
            assertTrue(DiagnosticLog.read(app).contains("restricted settings allowed: both accesses on"));
            accessibilityOn(false);
            listenerAllowed(false);
            activity.pause().resume();
            tick();
            assertNull("lifted long ago: no step, no \"Android blocks this switch\"",
                    line(content, SetupChecklist.RESTRICTED));
            View accessibility = line(content, SetupChecklist.ACCESSIBILITY);
            assertNotNull(accessibility);
            assertEquals("its number keeps the step done here", "2", badge(accessibility));
        } finally {
            accessibilityOn(false);
            listenerAllowed(false);
        }
    }

    @Test public void anAccessAlreadyOnMeansNoStepWhenOnlyTheInstallSourceSuggestedOne() {
        // A tester updating from an older version: screen reading allowed long ago, notification access never.
        sideloaded(PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED);
        restriction(AppOpsManager.MODE_DEFAULT);
        accessibilityOn(true);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNull("restricted settings were allowed already (or never applied)",
                    line(content, SetupChecklist.RESTRICTED));
            assertTrue(DiagnosticLog.read(app).contains("restricted settings allowed: an access was already on"));
            line(content, SetupChecklist.NOTIFICATIONS).performClick();
            assertNull("no guide in the way", ShadowAlertDialog.getLatestAlertDialog());
            assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, opened().getAction());
        } finally {
            accessibilityOn(false);
        }
    }

    @Test public void anInstallSourceThatOnlySuggestsTheRestrictionSaysAndroidMayBlockTheSwitch() {
        // Self-updated, or installed by adb or another installer: Android 13 and 14 restrict only file installs.
        sideloaded(PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED);
        restriction(AppOpsManager.MODE_DEFAULT);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            line(content, SetupChecklist.RESTRICTED).performClick();
            assertEquals(RestrictedSettingsGuide.TRY_FIRST_MAYBE, message());
            assertTrue(message().startsWith("Android may block this switch"));
        }
    }

    @Test public void androidsOwnRecordStillDecidesWithAnAccessOn() {
        // Granted through other means while Android's record says restricted: the other switch is still blocked.
        sideloaded(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE);
        restriction(AppOpsManager.MODE_IGNORED);
        accessibilityOn(true);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNotNull(line(content, SetupChecklist.RESTRICTED));
            line(content, SetupChecklist.RESTRICTED).performClick();
            assertEquals(RestrictedSettingsGuide.PATH, message());
        } finally {
            accessibilityOn(false);
        }
    }

    @Test public void aStoreInstallHasNoSuchStep() {
        sideloaded(PackageInstaller.PACKAGE_SOURCE_STORE);
        restriction(AppOpsManager.MODE_DEFAULT);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNull(line(content, SetupChecklist.RESTRICTED));
            View accessibility = line(content, SetupChecklist.ACCESSIBILITY);
            assertEquals("the first step", "1", badge(accessibility));
            accessibility.performClick();
            assertNull("no dialog", ShadowAlertDialog.getLatestAlertDialog());
            assertEquals(DETAILS, opened().getAction());
        }
    }

    /**
     * A readable "allowed" does not overrule a browser install's source (Android 15's Enhanced Confirmation Mode can
     * guard an app by its source while that record stays at its default): the step stays until an access turns on.
     */
    @Test public void aReadableAllowedRecordCountsForASideloadOnlyOnceAnAccessIsOn() {
        sideloaded(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE);
        restriction(AppOpsManager.MODE_ALLOWED);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNotNull("both off: the source decides", line(content, SetupChecklist.RESTRICTED));
            accessibilityOn(true);
            activity.pause().resume();
            tick();
            assertNull("an access on: Android's allowed is borne out", line(content, SetupChecklist.RESTRICTED));
        } finally {
            accessibilityOn(false);
        }
    }

    @Test public void aRecordThatSaysAllowedStillGetsTheHintAfterAFailedTry() {
        // No install source Android will tell, its record readable and "allowed": a switch that stayed off anyway.
        restriction(AppOpsManager.MODE_ALLOWED);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNull(line(content, SetupChecklist.RESTRICTED));
            line(content, SetupChecklist.ACCESSIBILITY).performClick();
            assertEquals(DETAILS, opened().getAction());
            activity.pause().resume();
            tick();
            assertNotNull(line(content, SetupChecklist.ACCESSIBILITY + SetupChecklist.GREYED));
        }
    }

    @Test public void notificationAccessGrantedButNotConnectedAsksForARebindThenOpensItsPage() {
        listenerAllowed(true);
        int before = ShadowNotificationListenerService.getRebindRequestCount();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNull("not the page whose switch is already on", line(content, SetupChecklist.NOTIFICATIONS));
            View reconnect = line(content, SetupChecklist.NOTIFICATIONS_RECONNECT);
            assertNotNull(reconnect);
            reconnect.performClick();
            assertEquals(before + 1, ShadowNotificationListenerService.getRebindRequestCount());
            assertNull("only Android was asked", opened());
            assertEquals("Reconnecting notification access…", ShadowToast.getTextOfLatestToast());
            reconnect.performClick();
            assertEquals("a second tap: the page, to turn it off and on",
                    Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, opened().getAction());
            assertEquals(SetupChecklist.RECONNECT_ON_PAGE, ShadowToast.getTextOfLatestToast());

            ServiceController<OfferNotificationService> service =
                    Robolectric.buildService(OfferNotificationService.class).create();
            try {
                service.get().onListenerConnected();
                tick();
                assertNull("connected: the line goes", line(content, SetupChecklist.NOTIFICATIONS_RECONNECT));
            } finally {
                service.destroy();
            }
        } finally {
            listenerAllowed(false);
        }
    }

    @Test public void screenReadingOnButNotRunningSaysToTurnItOffAndOn() {
        accessibilityOn(true);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNull(line(content, SetupChecklist.ACCESSIBILITY));
            View restart = line(content, SetupChecklist.ACCESSIBILITY_RESTART);
            assertNotNull(restart);
            assertEquals("a warning sign, not a step", View.GONE, ((ViewGroup) restart).getChildAt(0).getVisibility());
            int toasts = ShadowToast.shownToastCount();
            restart.performClick();
            assertEquals(DETAILS, opened().getAction());
            assertEquals(SetupChecklist.RESTART_ON_PAGE, ShadowToast.getTextOfLatestToast());
            assertEquals("one toast", toasts + 1, ShadowToast.shownToastCount());
        } finally {
            accessibilityOn(false);
        }
    }

    /**
     * Stock Android lets only system apps open a service's own Accessibility page: every path lands on the list,
     * with one toast worded for the list (never one for the page and then another for the list).
     */
    @Test @Config(shadows = GuardedPages.class)
    public void stockAndroidGuardsTheServicesOwnPageSoEveryPathOpensTheListWithOneToast() {
        GuardedPages.refused.add(DETAILS);
        sideloaded(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE);
        restriction(AppOpsManager.MODE_DEFAULT);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            int toasts = ShadowToast.shownToastCount();
            line(content, SetupChecklist.RESTRICTED).performClick();
            press(AlertDialog.BUTTON_POSITIVE);
            assertEquals("the list", Settings.ACTION_ACCESSIBILITY_SETTINGS, opened().getAction());
            assertEquals(SetupChecklist.FIND_IN_LIST, ShadowToast.getTextOfLatestToast());
            assertEquals(toasts + 1, ShadowToast.shownToastCount());
            assertTrue(DiagnosticLog.read(app).contains("not open to apps here (SecurityException)"));

            // Back from App info: the list again, with the one toast that says where to look in it.
            activity.pause().resume();
            tick();
            line(content, SetupChecklist.RESTRICTED).performClick();
            press(AlertDialog.BUTTON_POSITIVE);
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened().getAction());
            toasts = ShadowToast.shownToastCount();
            activity.pause().resume();
            tick();
            assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, opened().getAction());
            assertEquals("no \"here\" toast for a page that did not open", toasts + 1, ShadowToast.shownToastCount());
            assertEquals(SetupChecklist.FIND_IN_LIST, ShadowToast.getTextOfLatestToast());
        }
        // On, but not running: the list, and the words for turning it off and on there.
        accessibilityOn(true);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            int toasts = ShadowToast.shownToastCount();
            line(content, SetupChecklist.ACCESSIBILITY_RESTART).performClick();
            assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, opened().getAction());
            assertEquals(SetupChecklist.RESTART_IN_LIST, ShadowToast.getTextOfLatestToast());
            assertEquals(toasts + 1, ShadowToast.shownToastCount());
        } finally {
            accessibilityOn(false);
        }
    }

    @Test @Config(shadows = GuardedPages.class)
    public void aPhoneWithoutTheListenersOwnPageOpensTheListAndNamesItsEntry() {
        GuardedPages.refused.add(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            line(content, SetupChecklist.NOTIFICATIONS).performClick();
            assertEquals("not a dead end", Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, opened().getAction());
            assertEquals("Turn on " + AppName.NAME + " background offers here.", ShadowToast.getTextOfLatestToast());
            assertEquals(SetupChecklist.FIND_LISTENER, ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void allowUpdatesWaitsForTheRestOfSetupUnlessAnUpdateWaitsForIt() {
        Shadows.shadowOf(app.getPackageManager()).setCanRequestPackageInstalls(false);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNull("the rest of setup first", line(content, SetupChecklist.UPDATES));
            assertNull("two to do: both show", more(content, 1));
            Updater.prefs(app).edit().putString("ready_version", "99.0.0").commit();
            tick();
            assertNull("a third to do folds the rest", line(content, SetupChecklist.UPDATES));
            unfold(content, 2);
            View updates = line(content, SetupChecklist.UPDATES);
            assertNotNull("a verified update waits for it", updates);
            assertEquals("the last step", "4", badge(updates));
            updates.performClick();
            Intent allow = opened();
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, allow.getAction());
            assertEquals("package:" + app.getPackageName(), allow.getDataString());
            assertEquals("Android's own name for the switch", SetupChecklist.ALLOW_SOURCE,
                    ShadowToast.getTextOfLatestToast());
            assertEquals("Turn on Allow from this source.", SetupChecklist.ALLOW_SOURCE);
        }
        Updater.prefs(app).edit().remove("ready_version").commit();
        ServiceController<OfferFilterService> reader = Robolectric.buildService(OfferFilterService.class).create();
        ServiceController<OfferNotificationService> listener =
                Robolectric.buildService(OfferNotificationService.class).create();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            reader.get().onServiceConnected();
            listener.get().onListenerConnected();
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNull(line(content, SetupChecklist.ACCESSIBILITY));
            assertNull(line(content, SetupChecklist.NOTIFICATIONS));
            assertNull(line(content, SetupChecklist.ALERTS));
            assertNotNull("everything else works: updates are the last step", line(content, SetupChecklist.UPDATES));
            Shadows.shadowOf(app.getPackageManager()).setCanRequestPackageInstalls(true);
            activity.pause().resume();
            tick();
            assertNull("allowed: the line goes", line(content, SetupChecklist.UPDATES));
        } finally {
            listener.destroy();
            reader.destroy();
        }
    }

    @Test public void allowAlertsNamesWhatToTurnOnWhenItOpensAndroidsPage() {
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.NotificationManager.class))
                .setNotificationsEnabled(false);
        ServiceController<OfferFilterService> reader = Robolectric.buildService(OfferFilterService.class).create();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            reader.get().onServiceConnected();
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            line(content, SetupChecklist.ALERTS).performClick();
            Intent settings = opened();
            assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, settings.getAction());
            assertEquals(SetupChecklist.ALLOW_NOTIFICATIONS, ShadowToast.getTextOfLatestToast());
        } finally {
            reader.destroy();
            org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.NotificationManager.class))
                    .setNotificationsEnabled(true);
        }
    }

    @Test public void threeOrMoreStepsFoldIntoTheFirstAndOneLineThatShowsTheRest() {
        // A fresh store install with nothing allowed yet: Accessibility, notification access and alerts to do.
        Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            View first = line(content, SetupChecklist.ACCESSIBILITY);
            assertNotNull(first);
            assertNull(line(content, SetupChecklist.NOTIFICATIONS));
            assertNull(line(content, SetupChecklist.ALERTS));
            View folded = more(content, 2);
            assertNotNull("one line says how many more", folded);
            assertEquals("no number of its own", View.INVISIBLE, ((ViewGroup) folded).getChildAt(0).getVisibility());
            assertTrue("a 48 dp target", folded.getMinimumHeight() >= new Ui(app).dp(48));
            unfold(content, 2);
            assertNotNull(line(content, SetupChecklist.NOTIFICATIONS));
            assertNotNull(line(content, SetupChecklist.ALERTS));
            assertNull(more(content, 2));

            // Two to do: both show; nothing is folded.
            ServiceController<OfferFilterService> reader = Robolectric.buildService(OfferFilterService.class).create();
            try {
                reader.get().onServiceConnected();
                tick();
                assertNull(line(content, SetupChecklist.ACCESSIBILITY));
                assertNotNull(line(content, SetupChecklist.NOTIFICATIONS));
                assertNotNull(line(content, SetupChecklist.ALERTS));
            } finally {
                reader.destroy();
            }
        }
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNotNull("a new page folds them again", more(content, 2));
        }
    }

    @Test public void aFailedTryWithNoRestrictionKnownStillOffersTheHintForBothSwitches() {
        // No install source Android will tell and no restriction record: only the old hint after a failed try.
        restriction(AppOpsManager.MODE_DEFAULT);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNull(line(content, SetupChecklist.RESTRICTED));
            line(content, SetupChecklist.NOTIFICATIONS).performClick();
            assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, opened().getAction());
            activity.pause().resume();
            tick();
            View hinted = line(content, SetupChecklist.NOTIFICATIONS + SetupChecklist.GREYED);
            assertNotNull(hinted);
            hinted.performClick();
            assertEquals(SetupChecklist.GREYED_HELP, message());
            press(AlertDialog.BUTTON_POSITIVE);
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened().getAction());
            activity.pause().resume();
            tick();
            assertEquals("back from App info: the switch it was for",
                    Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, opened().getAction());
        }
    }

    @Test @Config(sdk = 26)
    public void olderAndroidHasNoRestrictedStepOrHint() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNull(line(content, SetupChecklist.RESTRICTED));
            line(content, SetupChecklist.ACCESSIBILITY).performClick();
            assertEquals("Android 8 has no page for one service: the list, and where to look in it",
                    Settings.ACTION_ACCESSIBILITY_SETTINGS, opened().getAction());
            assertEquals(SetupChecklist.FIND_IN_LIST, ShadowToast.getTextOfLatestToast());
            activity.pause().resume();
            tick();
            assertNotNull("no greyed-out hint before Android 13", line(content, SetupChecklist.ACCESSIBILITY));
        }
    }

    /**
     * The largest font on a narrow phone: a step's words keep to two lines (they would take three), a little smaller,
     * never below three quarters of the user's size; longer words take as few lines as that allows (three, not four);
     * words that fit keep the user's size.
     */
    @Test @Config(sdk = {26, 35}, qualifiers = "w320dp-h640dp-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aStepsWordsKeepToTwoLinesAtTheLargestFontAndOtherwiseTheUsersSize() {
        RuntimeEnvironment.setFontScale(2f);
        try {
            Activity screen = Robolectric.buildActivity(Activity.class).setup().get();
            Ui ui = new Ui(screen);
            LinearLayout column = ui.column();
            SetupRow longer = new SetupRow(screen, ui, column, () -> { });
            SetupRow shorter = new SetupRow(screen, ui, column, () -> { });
            SetupRow restart = new SetupRow(screen, ui, column, () -> { });
            longer.show(SetupRow.Mark.STEP, 2, SetupChecklist.NOTIFICATIONS, SetupChecklist.FIX);
            shorter.show(SetupRow.Mark.STEP, 3, SetupChecklist.ALERTS, SetupChecklist.FIX);
            restart.show(SetupRow.Mark.PROBLEM, 1, SetupChecklist.ACCESSIBILITY_RESTART, SetupChecklist.FIX);
            // The homepage's column on a 320 dp phone.
            int width = ui.dp(288);
            column.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            column.layout(0, 0, width, column.getMeasuredHeight());
            float user = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, SetupRow.WORDS_SP,
                    screen.getResources().getDisplayMetrics());
            SetupRow[] steps = {longer, restart};
            int[] most = {2, 3};
            for (int i = 0; i < steps.length; i++) {
                TextView words = steps[i].wordsView();
                assertTrue(steps[i].words() + ": " + words.getLineCount() + " lines", words.getLineCount() <= most[i]);
                assertTrue(steps[i].words() + ": " + words.getTextSize() + " of " + user,
                        words.getTextSize() < user && words.getTextSize() >= user * SetupRow.LEAST_SCALE);
            }
            assertEquals("fits: the user's size", user, shorter.wordsView().getTextSize(), 0.01f);
            assertEquals(1, shorter.wordsView().getLineCount());
        } finally {
            RuntimeEnvironment.setFontScale(1f);
        }
    }
}

package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.Duration;
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
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowNotificationListenerService;
import org.robolectric.shadows.ShadowToast;

/**
 * BETA-03, BETA-15 and the Allow updates step of BETA-04: the homepage's setup leads a stranger who sideloaded the
 * app through Android 13+'s restricted settings before either switch hits the wall, tells a granted-but-stopped access
 * from one never granted, and asks to allow updates once the rest works (or at once when an update waits).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class SetupChecklistTest extends AndroidAdapterTestBase {
    private static final String FIX = ". Fix.";

    @Before public void clean() {
        app.getSharedPreferences(SetupChecklist.PREFS, 0).edit().clear().commit();
        Updater.prefs(app).edit().remove("ready_version").commit();
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

    private static void tick() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
    }

    private static View line(View content, String words) {
        return shownIcon(content, words + FIX);
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

    @Test public void aSideloadLeadsWithRestrictedSettingsThenReturnsToTheSwitchItWasFor() {
        sideloaded(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE);
        restriction(AppOpsManager.MODE_ERRORED);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            View restricted = line(content, SetupChecklist.RESTRICTED);
            View accessibility = line(content, SetupChecklist.ACCESSIBILITY);
            View notifications = line(content, SetupChecklist.NOTIFICATIONS);
            assertNotNull("before either switch hits the wall", restricted);
            assertNotNull(accessibility);
            assertNotNull(notifications);
            ViewGroup column = (ViewGroup) restricted.getParent();
            assertTrue("first", column.indexOfChild(restricted) < column.indexOfChild(accessibility));
            assertEquals("1", badge(restricted));
            assertEquals("2", badge(accessibility));
            assertEquals("3", badge(notifications));

            // Not tried yet: Android shows the App info item only after one try, so the switch comes first.
            restricted.performClick();
            assertEquals(RestrictedSettingsGuide.TRY_FIRST, message());
            assertTrue("one line with the exact path", message().contains("App info → ⋮ → Allow restricted settings"));
            press(AlertDialog.BUTTON_POSITIVE);
            Intent tried = opened();
            assertEquals("android.settings.ACCESSIBILITY_DETAILS_SETTINGS", tried.getAction());

            // Android blocked it (its record says the user saw the restriction): the path to allow it.
            restriction(AppOpsManager.MODE_IGNORED);
            activity.pause().resume();
            tick();
            assertNull("nothing opens by itself", opened());
            line(content, SetupChecklist.ACCESSIBILITY).performClick();
            assertEquals("the switch's own Fix leads through the guide too", RestrictedSettingsGuide.PATH, message());
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
            assertEquals("android.settings.ACCESSIBILITY_DETAILS_SETTINGS", next.getAction());
            assertEquals("Now turn on " + AppName.NAME + " here.", ShadowToast.getTextOfLatestToast());
            assertNull("success seen on return", line(content, SetupChecklist.RESTRICTED));
            assertNotNull(line(content, SetupChecklist.ACCESSIBILITY));

            // Notification access then opens straight to its page: no restriction stands in the way.
            line(content, SetupChecklist.NOTIFICATIONS).performClick();
            assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, opened().getAction());
        }
    }

    @Test public void anUnreadableRestrictionFollowsTheInstallSourceAndAGrantedAccessProvesItLifted() {
        sideloaded(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE);
        restriction(AppOpsManager.MODE_DEFAULT);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNotNull(line(content, SetupChecklist.RESTRICTED));
            line(content, SetupChecklist.NOTIFICATIONS).performClick();
            assertEquals("for notification access too", RestrictedSettingsGuide.TRY_FIRST, message());
            press(AlertDialog.BUTTON_NEUTRAL);
            assertEquals("App info at once, for one who tried before", Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    opened().getAction());

            // The user allowed it and turned screen reading on from there: proof the restriction lifted.
            Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    new ComponentName(app, OfferFilterService.class).flattenToString());
            activity.pause().resume();
            tick();
            assertEquals("back to the notification page it was for", Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS,
                    opened().getAction());
            assertNull(line(content, SetupChecklist.RESTRICTED));
            assertTrue(DiagnosticLog.read(app).contains("restricted settings allowed: an access turned on"));
            Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
            activity.pause().resume();
            tick();
            assertNull("kept: turning a switch off later does not bring the step back",
                    line(content, SetupChecklist.RESTRICTED));
        }
    }

    @Test public void anAccessAlreadyOnMeansNoStepWhenOnlyTheInstallSourceSuggestedOne() {
        // A tester updating from an older version: screen reading allowed long ago, notification access never.
        sideloaded(PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED);
        restriction(AppOpsManager.MODE_DEFAULT);
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                new ComponentName(app, OfferFilterService.class).flattenToString());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNull("restricted settings were allowed already (or never applied)",
                    line(content, SetupChecklist.RESTRICTED));
            assertTrue(DiagnosticLog.read(app).contains("restricted settings allowed: an access was already on"));
            line(content, SetupChecklist.NOTIFICATIONS).performClick();
            assertNull("no guide in the way", ShadowAlertDialog.getLatestAlertDialog());
            assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, opened().getAction());
        }
    }

    @Test public void androidsOwnRecordStillDecidesWithAnAccessOn() {
        // Granted through other means while Android's record says restricted: the other switch is still blocked.
        sideloaded(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE);
        restriction(AppOpsManager.MODE_IGNORED);
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                new ComponentName(app, OfferFilterService.class).flattenToString());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNotNull(line(content, SetupChecklist.RESTRICTED));
            line(content, SetupChecklist.NOTIFICATIONS).performClick();
            assertEquals(RestrictedSettingsGuide.PATH, message());
        }
    }

    @Test public void aStoreInstallOrAnAllowedRestrictionHasNoSuchStep() {
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
            assertEquals("android.settings.ACCESSIBILITY_DETAILS_SETTINGS", opened().getAction());
        }
        sideloaded(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE);
        restriction(AppOpsManager.MODE_ALLOWED);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tick();
            assertNull("Android says it is allowed", line(activity.get().findViewById(android.R.id.content),
                    SetupChecklist.RESTRICTED));
        }
    }

    @Test public void notificationAccessGrantedButNotConnectedAsksForARebindThenOpensItsPage() {
        ComponentName listener = new ComponentName(app, OfferNotificationService.class);
        Shadows.shadowOf(app.getSystemService(android.app.NotificationManager.class))
                .setNotificationListenerAccessGranted(listener, true);
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

            ServiceController<OfferNotificationService> service =
                    Robolectric.buildService(OfferNotificationService.class).create();
            try {
                service.get().onListenerConnected();
                tick();
                assertNull("connected: the line goes", line(content, SetupChecklist.NOTIFICATIONS_RECONNECT));
            } finally {
                service.destroy();
            }
        }
    }

    @Test public void screenReadingOnButNotRunningSaysToTurnItOffAndOn() {
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                new ComponentName(app, OfferFilterService.class).flattenToString());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNull(line(content, SetupChecklist.ACCESSIBILITY));
            View restart = line(content, SetupChecklist.ACCESSIBILITY_RESTART);
            assertNotNull(restart);
            assertEquals("a warning sign, not a step", View.GONE, ((ViewGroup) restart).getChildAt(0).getVisibility());
            restart.performClick();
            assertEquals("android.settings.ACCESSIBILITY_DETAILS_SETTINGS", opened().getAction());
            assertEquals("Turn " + AppName.NAME + " off, then on again.", ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void allowUpdatesWaitsForTheRestOfSetupUnlessAnUpdateWaitsForIt() {
        Shadows.shadowOf(app.getPackageManager()).setCanRequestPackageInstalls(false);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNull("the rest of setup first", line(content, SetupChecklist.UPDATES));
            Updater.prefs(app).edit().putString("ready_version", "99.0.0").commit();
            tick();
            View updates = line(content, SetupChecklist.UPDATES);
            assertNotNull("a verified update waits for it", updates);
            assertEquals("the last step", "4", badge(updates));
            updates.performClick();
            Intent allow = opened();
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, allow.getAction());
            assertEquals("package:" + app.getPackageName(), allow.getDataString());
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

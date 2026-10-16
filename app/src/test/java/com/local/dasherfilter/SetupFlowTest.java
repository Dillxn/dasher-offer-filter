package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.Instrumentation;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.RadioButton;
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
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowInstrumentation;
import org.robolectric.shadows.ShadowNotificationListenerService;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

/**
 * 0.6.26's setup (the owner, 16 October 2026: "shouldn't have this limbo screen. if the app is unable to work for
 * permissions or whathaveyou then we shepherd the user directly to resolution. all ui on screen needs to be justified
 * by what is currently functional", and of 0.6.25 on his Galaxy: "it gave me a toast with instructions saying andrioid
 * would block turning on notifications, it didnt, then it drops the user into the settings screen which does block
 * them with toast instructions too long to read"): while Offer Filter cannot filter, setup's page is the whole screen,
 * one step and one button at a time, its words read before Android's page opens; Android's block is said only where it
 * is coming, and counts as lifted only by screen reading on or Android's own record; the first run's walk ends on
 * Start auto-decline or Set my own; and the homepage then shows each part only once it works.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class SetupFlowTest extends AndroidAdapterTestBase {
    private static final String DETAILS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS";
    private ServiceController<OfferFilterService> screen;
    private ServiceController<OfferNotificationService> listener;

    @Before public void setupOn() {
        SetupFlow.pageShows = true;
        GuardedPages.refused.clear();
    }

    @After public void servicesDown() {
        if (listener != null && OfferNotificationService.isConnected()) listener.destroy();
        if (screen != null && OfferFilterService.isConnected()) screen.destroy();
        GuardedPages.refused.clear();
    }

    /** Installed from a file the browser downloaded, as testers install it from offerfilter.org. */
    private void installedFrom(int source, String installer) {
        Shadows.shadowOf(app.getPackageManager()).setInstallSourceInfo(app.getPackageName(), installer, null, null,
                installer, null, source);
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

    private void readerRunning() {
        accessibilityOn(true);
        screen = Robolectric.buildService(OfferFilterService.class).create();
        screen.get().onServiceConnected();
    }

    private void listenerRunning() {
        listenerAllowed(true);
        listener = Robolectric.buildService(OfferNotificationService.class).create();
        listener.get().onListenerConnected();
    }

    private static void tick() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
    }

    private Intent opened() {
        return Shadows.shadowOf(app).getNextStartedActivity();
    }

    private static View content(ActivityController<MainActivity> activity) {
        return activity.get().findViewById(android.R.id.content);
    }

    /** The title of setup's step on screen, or null. */
    private static String step(View content) {
        for (String title : new String[] {SetupFlow.READING_TITLE, SetupFlow.BLOCKED_TITLE,
                SetupFlow.READING_NOW_TITLE, SetupFlow.RESTART_TITLE, SetupFlow.BACKGROUND_TITLE,
                SetupFlow.BACKGROUND_NOW_TITLE, SetupFlow.RECONNECT_TITLE, SetupFlow.ALERTS_TITLE,
                SetupFlow.LOCATION_TITLE, SetupFlow.MINIMUMS_TITLE}) {
            if (says(content, title)) return title;
        }
        return null;
    }

    /** Words on screen, exactly (not a button's). */
    private static boolean says(View view, String words) {
        if (view instanceof TextView && !(view instanceof Button) && view.isShown()
                && words.contentEquals(((TextView) view).getText())) {
            return true;
        }
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                if (says(((ViewGroup) view).getChildAt(i), words)) return true;
            }
        }
        return false;
    }

    private static void tap(View content, String label) {
        Button button = shownButton(content, label);
        assertNotNull("a button: " + label, button);
        button.performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static boolean homepageShown(View content) {
        ScenePage scene = find(content, ScenePage.class);
        return scene != null && scene.isShown();
    }

    private String log() {
        return DiagnosticLog.read(app);
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

    /**
     * The owner's Galaxy as 0.6.25 left it (installed from offerfilter.org, Android's record at its default, notification
     * access let on with no block, screen reading blocked): one step and one button at a time, straight through to
     * auto-decline on. Notification access turning on lifts nothing (0.6.25 took it as proof, and its App info step
     * vanished for good); 0.6.25's mark of that is forgotten.
     */
    @Test public void theGalaxysWalkIsOneStepAtATimeStraightToAutoDecline() {
        installedFrom(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE, "com.android.chrome");
        restriction(AppOpsManager.MODE_DEFAULT);
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        SharedPreferences prefs = SetupFlow.prefs(app);
        prefs.edit().putBoolean("restricted_cleared", true).putBoolean("restricted_tried", true).commit();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = content(activity);
            tick();
            assertFalse("0.6.25's marks forgotten", prefs.contains("restricted_cleared")
                    || prefs.contains("restricted_tried"));
            assertEquals(SetupFlow.READING_TITLE, step(content));
            assertTrue(says(content, "1 of 5"));
            assertTrue(says(content, SetupFlow.READING_WHY));
            assertTrue("where the switch is, in his Galaxy's words or stock Android's", says(content,
                    "In Accessibility, tap " + SetupFlow.appsList() + ", then " + AppName.NAME + ", and turn it on."));
            assertTrue("Android's block said before it comes, for an install from the web",
                    says(content, SetupFlow.BLOCK_AHEAD));
            assertFalse("nothing of the homepage", homepageShown(content));
            assertNotNull("help at the foot", shownButton(content, SetupPage.STUCK));
            assertNull("one button: no second one on the page", shownButton(content, SetupFlow.OPEN_APP_INFO));

            // The try: the service's own page where Android lets an app open it, with no toast over its switch.
            int toasts = ShadowToast.shownToastCount();
            tap(content, SetupFlow.OPEN_ACCESSIBILITY);
            Intent details = opened();
            assertEquals(DETAILS, details.getAction());
            assertEquals(new ComponentName(app, OfferFilterService.class).flattenToString(),
                    details.getStringExtra(Intent.EXTRA_COMPONENT_NAME));
            assertEquals(toasts, ShadowToast.shownToastCount());

            // Blocked, and back: App info is next, the page saying where its menu item is before it opens.
            activity.pause().resume();
            tick();
            assertNull("nothing opens by itself", opened());
            assertEquals(SetupFlow.BLOCKED_TITLE, step(content));
            assertTrue(says(content, SetupFlow.READING_BLOCKED_WORDS));
            assertNotNull(shownButton(content, SetupFlow.TRY_AGAIN));
            assertTrue(log(), log().contains("a try at screen reading came back off (record default); App info next"));

            // Notification access turned on meanwhile (One UI lets it on): Android's block still stands.
            listenerRunning();
            activity.pause().resume();
            tick();
            assertEquals("notification access on lifts nothing", SetupFlow.BLOCKED_TITLE, step(content));

            tap(content, SetupFlow.OPEN_APP_INFO);
            Intent info = opened();
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, info.getAction());
            assertEquals("package:" + app.getPackageName(), info.getDataString());
            assertEquals(SetupFlow.IN_APP_INFO, ShadowToast.getTextOfLatestToast());

            // Back from App info: the switch again, the block no longer warned of.
            activity.pause().resume();
            tick();
            assertEquals(SetupFlow.READING_NOW_TITLE, step(content));
            assertFalse(says(content, SetupFlow.BLOCK_AHEAD));
            assertTrue(log(), log().contains("back from App info (record default); the switch next"));
            tap(content, SetupFlow.OPEN_ACCESSIBILITY);
            assertEquals(DETAILS, opened().getAction());

            // Turned on: Offer Filter asks to come back to the front by itself.
            readerRunning();
            Intent back = opened();
            assertNotNull("Offer Filter brought back", back);
            assertEquals(MainActivity.class.getName(), back.getComponent().getClassName());
            assertTrue((back.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0
                    && (back.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0
                    && (back.getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0);
            assertTrue(log(), log().contains("screen reading turned on; " + AppName.NAME
                    + " asked back to the front"));
            activity.pause().resume();
            tick();

            // Both on: the first run's walk. Alerts, asked once; a refusal is the user's answer, with no toast.
            assertEquals(SetupFlow.ALERTS_TITLE, step(content));
            assertTrue(says(content, "3 of 5"));
            tap(content, SetupFlow.ALLOW_ALERTS);
            org.robolectric.shadows.ShadowActivity.PermissionsRequest asked =
                    Shadows.shadowOf(activity.get()).getLastRequestedPermission();
            assertEquals(Manifest.permission.POST_NOTIFICATIONS, asked.requestedPermissions[0]);
            toasts = ShadowToast.shownToastCount();
            activity.get().onRequestPermissionsResult(asked.requestCode, asked.requestedPermissions,
                    new int[] {PackageManager.PERMISSION_DENIED});
            tick();
            assertEquals(toasts, ShadowToast.shownToastCount());

            // The map, while it is on: Not now asks nothing.
            assertEquals(SetupFlow.LOCATION_TITLE, step(content));
            assertTrue(says(content, "4 of 5"));
            tap(content, SetupFlow.NOT_NOW);
            assertSame("Not now asks Android nothing", asked,
                    Shadows.shadowOf(activity.get()).getLastRequestedPermission());
            tick();

            // The typical minimums: Start auto-decline saves them and turns auto-decline on at once, no dialog after.
            assertEquals(SetupFlow.MINIMUMS_TITLE, step(content));
            assertTrue(says(content, "5 of 5"));
            assertTrue(says(content, SetupFlow.MINIMUMS_WORDS));
            ShadowAlertDialog.reset();
            tap(content, SetupFlow.START);
            tick();
            FilterSettings saved = FilterStore.load(app);
            assertTrue("auto-decline on", saved.enabled);
            assertEquals(Autopilot.STARTER_FLAT_CENTS, saved.flatCents);
            assertEquals(Autopilot.STARTER_PER_MILE_CENTS, saved.perMileCents);
            assertEquals(Autopilot.STARTER_PER_MINUTE_CENTS, saved.perMinuteCents);
            assertNull("setup ends on the homepage, no dialog over it", ShadowAlertDialog.getLatestAlertDialog());
            assertEquals("Auto-decline is on.", ShadowToast.getTextOfLatestToast());
            assertNull(step(content));
            assertTrue(homepageShown(content));
            assertTrue(log(), log().contains("done: the home screen shows"));
        } finally {
            Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        }
        try (ActivityController<MainActivity> again = Robolectric.buildActivity(MainActivity.class).setup()) {
            tick();
            assertNull("the walk never comes again", step(content(again)));
            assertTrue(homepageShown(content(again)));
        }
    }

    /** An app-store install is never told of a block, and a try that comes back off keeps its step: no App info. */
    @Test public void aStoreInstallIsNeverToldOfABlock() {
        installedFrom(PackageInstaller.PACKAGE_SOURCE_STORE, "com.android.vending");
        restriction(AppOpsManager.MODE_DEFAULT);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = content(activity);
            tick();
            assertEquals(SetupFlow.READING_TITLE, step(content));
            assertFalse(says(content, SetupFlow.BLOCK_AHEAD));
            tap(content, SetupFlow.OPEN_ACCESSIBILITY);
            assertNotNull(opened());
            activity.pause().resume();
            tick();
            assertEquals(SetupFlow.READING_TITLE, step(content));
            assertNull(shownButton(content, SetupFlow.OPEN_APP_INFO));
            assertTrue(log(), log().contains("screen reading still off, back from Android's page (record default)"));
        }
    }

    /**
     * Android's own record, where it can be read, decides: restricted and untried warns of the block whatever the
     * install source said; tried shows App info's step at once; allowed turns a stale block into the switch again.
     */
    @Test public void androidsOwnRecordDecides() {
        restriction(AppOpsManager.MODE_ERRORED);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = content(activity);
            tick();
            assertTrue("restricted, untried", says(content, SetupFlow.BLOCK_AHEAD));

            restriction(AppOpsManager.MODE_IGNORED);
            activity.pause().resume();
            tick();
            assertEquals("tried: App info at once", SetupFlow.BLOCKED_TITLE, step(content));

            SetupFlow.prefs(app).edit().putString(SetupFlow.PHASE_READING, SetupFlow.BLOCKED).commit();
            restriction(AppOpsManager.MODE_ALLOWED);
            activity.pause().resume();
            tick();
            assertEquals("allowed elsewhere: the switch next", SetupFlow.READING_NOW_TITLE, step(content));
        }
    }

    /**
     * Notification access is never said to be blocked before a try (One UI lets it on); a try that comes back off
     * meets App info's step only where Android's record says restricted.
     */
    @Test public void notificationAccessIsBlockedOnlyWhereAndroidsRecordSaysSo() {
        installedFrom(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE, "com.android.chrome");
        restriction(AppOpsManager.MODE_DEFAULT);
        readerRunning();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = content(activity);
            tick();
            assertEquals(SetupFlow.BACKGROUND_TITLE, step(content));
            assertTrue(says(content, "2 of 5"));
            assertTrue(says(content, SetupFlow.BACKGROUND_WHY));
            assertTrue(says(content, SetupFlow.BACKGROUND_PATH));
            assertFalse("no warning of a block", says(content, SetupFlow.BACKGROUND_BLOCKED_WORDS));
            int toasts = ShadowToast.shownToastCount();
            tap(content, SetupFlow.OPEN_NOTIFICATION_ACCESS);
            Intent page = opened();
            assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, page.getAction());
            assertEquals(new ComponentName(app, OfferNotificationService.class).flattenToString(),
                    page.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME));
            assertEquals("its own page, its switch right there: no toast", toasts, ShadowToast.shownToastCount());
            activity.pause().resume();
            tick();
            assertEquals("the record at its default: the same step", SetupFlow.BACKGROUND_TITLE, step(content));

            tap(content, SetupFlow.OPEN_NOTIFICATION_ACCESS);
            assertNotNull(opened());
            restriction(AppOpsManager.MODE_IGNORED);
            activity.pause().resume();
            tick();
            assertEquals("the record says blocked", SetupFlow.BLOCKED_TITLE, step(content));
            assertTrue(says(content, SetupFlow.BACKGROUND_BLOCKED_WORDS));
            tap(content, SetupFlow.OPEN_APP_INFO);
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened().getAction());
            restriction(AppOpsManager.MODE_ALLOWED);
            activity.pause().resume();
            tick();
            assertEquals(SetupFlow.BACKGROUND_NOW_TITLE, step(content));
        }
    }

    /** Where Android keeps the service's own page from apps, the list opens with a toast naming the path. */
    @Test @Config(shadows = GuardedPages.class)
    public void wherePagesAreGuardedTheListOpensWithTheShortestPath() {
        GuardedPages.refused.add(DETAILS);
        GuardedPages.refused.add(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = content(activity);
            tick();
            tap(content, SetupFlow.OPEN_ACCESSIBILITY);
            assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, opened().getAction());
            assertEquals(SetupFlow.inAccessibilityList(false), ShadowToast.getTextOfLatestToast());
            assertTrue(log(), log().contains("not open to apps here (SecurityException)"));
        }
        readerRunning();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = content(activity);
            tick();
            tap(content, SetupFlow.OPEN_NOTIFICATION_ACCESS);
            assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, opened().getAction());
            assertEquals(SetupFlow.LISTENER_IN_LIST, ShadowToast.getTextOfLatestToast());
        }
    }

    /**
     * A switch on but not running gets its step only after {@link SetupFlow#GRACE_MS} (Android binds both again within
     * seconds of an update): screen reading's says to turn it off and on; background offers' asks Android to reconnect
     * first, then opens the page.
     */
    @Test public void aSwitchOnButNotRunningGetsItsStepOnlyAfterTheGrace() {
        FilterStore.save(app, FilterSettings.of(true, 1000, 0, 0, 0));
        accessibilityOn(true);
        listenerRunning();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = content(activity);
            tick();
            assertNull("within the grace: no step", step(content));
            assertTrue(homepageShown(content));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SetupFlow.GRACE_MS));
            assertEquals(SetupFlow.RESTART_TITLE, step(content));
            assertFalse("no counter once the walk is done", says(content, "1 of 5"));
            tap(content, SetupFlow.OPEN_ACCESSIBILITY);
            assertEquals(DETAILS, opened().getAction());
            assertEquals(SetupFlow.OFF_AND_ON_HERE, ShadowToast.getTextOfLatestToast());
        }
        accessibilityOn(true);
        readerRunning();
        assertEquals("on again after the restart: back by itself", MainActivity.class.getName(),
                opened().getComponent().getClassName());
        listener.destroy();
        int rebinds = ShadowNotificationListenerService.getRebindRequestCount();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = content(activity);
            tick();
            assertNull(step(content));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SetupFlow.GRACE_MS));
            assertEquals(SetupFlow.RECONNECT_TITLE, step(content));
            tap(content, SetupFlow.RECONNECT);
            assertEquals(rebinds + 1, ShadowNotificationListenerService.getRebindRequestCount());
            assertNull("only Android was asked", opened());
            assertEquals(SetupFlow.REBINDING, ShadowToast.getTextOfLatestToast());
            tap(content, SetupFlow.RECONNECT);
            assertEquals("asked lately: the page, to turn it off and on",
                    Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, opened().getAction());
            assertEquals(SetupFlow.OFF_AND_ON_HERE, ShadowToast.getTextOfLatestToast());
            listener = Robolectric.buildService(OfferNotificationService.class).create();
            listener.get().onListenerConnected();
            tick();
            assertNull("connected: the step goes", step(content));
            assertTrue(homepageShown(content));
        }
    }

    /**
     * Offer Filter comes back by itself only for the switch setup sent the user to, within ten minutes, once, and
     * never before the current notice is accepted.
     */
    @Test public void theReturnComesOnlyForTheSwitchSetupSentToAndOnlyOnce() {
        SetupFlow.connected(app, RestrictedSettingsGuide.Access.ACCESSIBILITY);
        assertNull("not sent there", opened());
        SharedPreferences prefs = SetupFlow.prefs(app);
        prefs.edit().putString(SetupFlow.SENT, "ACCESSIBILITY").putLong(SetupFlow.SENT_AT, System.currentTimeMillis())
                .commit();
        SetupFlow.connected(app, RestrictedSettingsGuide.Access.NOTIFICATIONS);
        assertNull("another switch", opened());
        SetupFlow.connected(app, RestrictedSettingsGuide.Access.ACCESSIBILITY);
        assertEquals(MainActivity.class.getName(), opened().getComponent().getClassName());
        SetupFlow.connected(app, RestrictedSettingsGuide.Access.ACCESSIBILITY);
        assertNull("once", opened());

        prefs.edit().putString(SetupFlow.SENT, "NOTIFICATIONS")
                .putLong(SetupFlow.SENT_AT, System.currentTimeMillis() - SetupFlow.RETURN_WITHIN_MS - 1).commit();
        SetupFlow.connected(app, RestrictedSettingsGuide.Access.NOTIFICATIONS);
        assertNull("too long ago", opened());

        ConsentedTestApp.forget(app);
        prefs.edit().putString(SetupFlow.SENT, "NOTIFICATIONS").putLong(SetupFlow.SENT_AT, System.currentTimeMillis())
                .commit();
        SetupFlow.connected(app, RestrictedSettingsGuide.Access.NOTIFICATIONS);
        assertNull("the notice not accepted", opened());
    }

    /**
     * An install in use (a rule saved, as every install updated from before 0.6.26) never sees the walk; its switches'
     * steps still come back, with no counter, whenever one goes off.
     */
    @Test public void anInstallInUseSkipsTheWalk() {
        FilterStore.save(app, FilterSettings.of(true, 1000, 0, 0, 0));
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        readerRunning();
        listenerRunning();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = content(activity);
            tick();
            assertNull("alerts off, still no walk", step(content));
            assertTrue(SetupFlow.prefs(app).getBoolean(SetupFlow.WALK_DONE, false));
            listener.destroy();
            listenerAllowed(false);
            activity.pause().resume();
            tick();
            assertEquals(SetupFlow.BACKGROUND_TITLE, step(content));
            assertFalse(says(content, "2 of 5"));
        } finally {
            Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    /**
     * With screen reading running and a rule saved, auto-decline goes on behind setup's page (here background offers
     * are off, as a listener that drops mid-dash leaves it), so the page carries the mascot's Pause auto-decline, then
     * Resume; with screen reading stopped nothing can be declined, and the toggle goes.
     */
    @Test public void whileAutoDeclineGoesOnBehindSetupItsPauseIsOnThePage() {
        FilterStore.save(app, FilterSettings.of(true, 1000, 0, 0, 0));
        readerRunning();
        listenerAllowed(false);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = content(activity);
            tick();
            assertEquals(SetupFlow.BACKGROUND_TITLE, step(content));
            tap(content, SetupPage.PAUSE);
            assertFalse("paused", FilterStore.load(app).enabled);
            assertTrue("every rule kept", FilterStore.load(app).hasAnyRule());
            assertEquals("Paused. Nothing will be declined.", ShadowToast.getTextOfLatestToast());
            assertEquals("the step stays", SetupFlow.BACKGROUND_TITLE, step(content));
            assertNull(shownButton(content, SetupPage.PAUSE));
            tap(content, SetupPage.RESUME);
            assertTrue("on again", FilterStore.load(app).enabled);
            assertNotNull(shownButton(content, SetupPage.PAUSE));
            screen.destroy();
            tick();
            assertNotNull("setup's page still", step(content));
            assertNull("screen reading stopped: nothing to pause", shownButton(content, SetupPage.PAUSE));
            assertNull(shownButton(content, SetupPage.RESUME));
        }
    }

    /**
     * Set my own saves nothing: the homepage, auto-decline still off, its knobs beckoning. With the map off, the walk
     * has four steps and no location step; with alerts and location allowed already, it goes straight to the minimums.
     */
    @Test public void setMyOwnLeavesTheMinimumsToTheKnobs() {
        AreaMap.setEnabled(app, false);
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        readerRunning();
        listenerRunning();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = content(activity);
            tick();
            assertEquals(SetupFlow.MINIMUMS_TITLE, step(content));
            assertTrue(says(content, "4 of 4"));
            tap(content, SetupFlow.SET_MY_OWN);
            tick();
            assertNull(step(content));
            assertTrue(homepageShown(content));
            assertFalse(FilterStore.load(app).hasAnyRule());
            assertFalse(FilterStore.load(app).enabled);
            assertTrue("the knobs beckon", find(content, MinimumsStarView.class).beckoned());
            assertEquals(MainActivity.START_LINE, statusLine(content).getText().toString());
        }
    }

    /** Location from setup's step: Android's question; a refusal walks on, with no toast. */
    @Test public void locationFromSetupIsAskedOnceAndARefusalWalksOn() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        readerRunning();
        listenerRunning();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = content(activity);
            tick();
            assertEquals(SetupFlow.LOCATION_TITLE, step(content));
            assertTrue(says(content, SetupFlow.LOCATION_WORDS));
            tap(content, SetupFlow.ALLOW_LOCATION);
            org.robolectric.shadows.ShadowActivity.PermissionsRequest asked =
                    Shadows.shadowOf(activity.get()).getLastRequestedPermission();
            assertEquals(Manifest.permission.ACCESS_COARSE_LOCATION, asked.requestedPermissions[0]);
            int toasts = ShadowToast.shownToastCount();
            activity.get().onRequestPermissionsResult(asked.requestCode, asked.requestedPermissions,
                    new int[] {PackageManager.PERMISSION_DENIED});
            tick();
            assertEquals(toasts, ShadowToast.shownToastCount());
            assertEquals(SetupFlow.MINIMUMS_TITLE, step(content));
        }
    }

    /** The notice comes first; setup's page after it, never behind it. Back on setup's page leaves. */
    @Test public void theNoticeComesFirstThenSetupAndBackLeaves() {
        ConsentedTestApp.forget(app);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = content(activity);
            tick();
            assertNull(step(content));
            tap(content, Consent.ACCEPT);
            tick();
            assertEquals(SetupFlow.READING_TITLE, step(content));
            activity.get().onBackPressed();
            assertTrue("Back leaves: nothing is behind setup", activity.get().isFinishing());
        }
    }

    /** "Stuck? Send feedback": the feedback form, Usability chosen, diagnostics attached only if the user chooses. */
    @Test public void stuckOpensFeedbackAsUsability() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = content(activity);
            tick();
            tap(content, SetupPage.STUCK);
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            assertTrue(dialog.isShowing());
            View form = dialog.getWindow().getDecorView();
            CompoundButton usability = checkable(form, "Usability");
            assertTrue("a Usability chip", usability instanceof RadioButton);
            assertTrue("Usability chosen", usability.isChecked());
            CompoundButton attach = checkable(form, "Attach masked diagnostics");
            assertNotNull(attach);
            assertFalse("diagnostics only if the user chooses", attach.isChecked());
            assertTrue(log(), log().contains("Stuck? Send feedback tapped on reading"));
        }
    }

    private static CompoundButton checkable(View view, String text) {
        if (view instanceof CompoundButton && text.contentEquals(((CompoundButton) view).getText())) {
            return (CompoundButton) view;
        }
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                CompoundButton found = checkable(((ViewGroup) view).getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Once Offer Filter can filter, the homepage shows each part only once it works: the skyline and its caption from
     * the first offer, the map while it is on with location allowed. Hidden, each keeps its room.
     */
    @Test public void theHomepageShowsEachPartOnlyOnceItWorks() {
        SetupFlow.pageShows = false;
        FilterStore.save(app, FilterSettings.of(true, 1000, 0, 0, 0));
        AreaMap.setEnabled(app, true);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = content(activity);
            tick();
            DecisionChartView chart = find(content, DecisionChartView.class);
            AreaMapView map = find(content, AreaMapView.class);
            View caption = ReflectionHelpers.getField(activity.get(), "offerCaption");
            assertEquals("no offer yet: no skyline", View.INVISIBLE, chart.getVisibility());
            assertEquals(View.INVISIBLE, caption.getVisibility());
            assertEquals("no location: no map", View.INVISIBLE, map.getVisibility());

            DecisionLog.record(app, declinedEntry());
            Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);
            activity.pause().resume();
            tick();
            assertEquals(View.VISIBLE, chart.getVisibility());
            assertEquals(View.VISIBLE, caption.getVisibility());
            assertEquals(View.VISIBLE, map.getVisibility());

            AreaMap.setEnabled(app, false);
            tick();
            assertEquals("the map turned off in Settings", View.INVISIBLE, map.getVisibility());
        }
    }
}

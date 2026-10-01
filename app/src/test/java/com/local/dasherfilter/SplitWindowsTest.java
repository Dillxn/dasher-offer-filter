package com.local.dasherfilter;

import android.Manifest;
import android.app.Application;
import android.app.KeyguardManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.shadows.ShadowBuild;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowWindowManagerImpl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Dasher's half of a split screen, as Android's windows show it (split-audit P6, P7, P10), with the real
 * screen-reading service and simulated windows. Each test fails on 0.4.45:
 * <ul>
 *   <li>it read and declined Dasher's half beside any active app window, so under the keyboard, recent apps, a pop-up
 *       or a system window over that half too;
 *   <li>its screen and alert lines said nothing of where Dasher was, and a report did not name Android or the maker;
 *   <li>with Dasher's half at the top, the guide sat 72 dp below the screen's top edge, under the status bar's height
 *       of Dasher's own top bar.
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class SplitWindowsTest {
    private static final Rect SCREEN = new Rect(0, 0, 1080, 2040);
    private static final Rect TOP_HALF = new Rect(0, 0, 1080, 1000);
    private static final Rect DIVIDER = new Rect(0, 1000, 1080, 1040);
    private static final Rect BOTTOM_HALF = new Rect(0, 1040, 1080, 2040);

    private Application app;
    private ServiceController<OfferFilterService> controller;
    private AccessibilityNodeInfo decline;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        DecisionLog.forgetCache();
        ReportOutbox.forgetCache();
        OfferSilencer.forgetCache();
        AreaMap.forgetCache();
        OfferFilterService.forgetScreenState();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        controller = Robolectric.buildService(OfferFilterService.class).create();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        controller.get().onServiceConnected();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @After
    public void stop() {
        controller.destroy();
        OfferFilterService.sawDasherBeside(0);
        OfferFilterService.scanLooperForTests = null;
    }

    private AccessibilityNodeInfo node(String packageName, String text, boolean clickable) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(new View(app));
        node.setPackageName(packageName);
        if (text != null) node.setText(text);
        node.setVisibleToUser(true);
        node.setEnabled(true);
        node.setClickable(clickable);
        if (clickable) {
            node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            Shadows.shadowOf(node).setOnPerformActionListener((action, args) -> true);
        }
        return node;
    }

    private AccessibilityNodeInfo dasher(String text) {
        return node("com.doordash.driverapp", text, false);
    }

    /** An offer below the $20 minimum: declined at once wherever it is read. */
    private AccessibilityNodeInfo offer() {
        AccessibilityNodeInfo root = dasher(null);
        decline = node("com.doordash.driverapp", "Decline", true);
        Shadows.shadowOf(root).addChild(dasher("$7.90"));
        Shadows.shadowOf(root).addChild(dasher("2 stops (7.2 mi) • 21 min"));
        Shadows.shadowOf(root).addChild(node("com.doordash.driverapp", "Accept", true));
        Shadows.shadowOf(root).addChild(decline);
        return root;
    }

    private AccessibilityNodeInfo ours() {
        return node("com.local.dasherfilter", null, false);
    }

    private static AccessibilityWindowInfo window(int type, AccessibilityNodeInfo root, boolean active, Rect bounds) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setType(type);
        if (root != null) shadow.setRoot(root);
        shadow.setActive(active);
        shadow.setBoundsInScreen(bounds);
        return window;
    }

    private static AccessibilityWindowInfo app(AccessibilityNodeInfo root, boolean active, Rect bounds) {
        return window(AccessibilityWindowInfo.TYPE_APPLICATION, root, active, bounds);
    }

    private static AccessibilityWindowInfo divider() {
        return window(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, null, false, DIVIDER);
    }

    /** Android lists these windows, {@code active} has the active root, and Dasher says its window changed. */
    private void windows(AccessibilityNodeInfo active, AccessibilityWindowInfo... listed) {
        Shadows.shadowOf(controller.get()).setWindows(Arrays.asList(listed));
        Shadows.shadowOf(controller.get()).setRootInActiveWindow(active);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("com.doordash.driverapp");
        controller.get().onAccessibilityEvent(event);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private boolean declined() {
        return !Shadows.shadowOf(decline).getPerformedActions().isEmpty();
    }

    private static void contains(String text, String part) {
        assertTrue(part + " in:\n" + text, text.contains(part));
    }

    // ---- P7: Dasher's half is read only while it is really in view ----

    @Test
    public void underTheKeyboardDashersHalfIsLeftAloneUntilTheKeyboardGoes() {
        AccessibilityNodeInfo ours = ours();
        AccessibilityNodeInfo offer = offer();
        // Typing in Offer Filter's half: the keyboard rises over Dasher's half below.
        windows(ours, app(ours, true, TOP_HALF), divider(), app(offer, false, BOTTOM_HALF),
                window(AccessibilityWindowInfo.TYPE_INPUT_METHOD, null, false, new Rect(0, 1280, 1080, 2040)));
        assertFalse("not declined under the keyboard", declined());
        assertFalse(OfferFilterService.isDasherForeground());
        contains(DiagnosticLog.read(app), "[split] Dasher's half not read: the keyboard covers it "
                + "(win=split/bottom/ours/49)");

        windows(ours, app(ours, true, TOP_HALF), divider(), app(offer, false, BOTTOM_HALF));
        assertTrue("declined once in view", declined());
        assertTrue(OfferFilterService.isDasherForeground());
        contains(DiagnosticLog.read(app), "[split] Dasher's half in view again");
    }

    @Test
    public void recentAppsOrAPopUpOverDashersHalfIsNotTheOtherHalf() {
        // Recent apps: the launcher fills the screen and is active, the divider and both halves still listed.
        AccessibilityNodeInfo launcher = node("com.google.android.apps.nexuslauncher", null, false);
        windows(launcher, app(launcher, true, SCREEN), app(ours(), false, TOP_HALF), divider(),
                app(offer(), false, BOTTOM_HALF));
        assertFalse("not declined under recent apps", declined());
        assertFalse(OfferFilterService.isDasherForeground());

        // A pop-up window of another app, active, floating over Dasher's half.
        AccessibilityNodeInfo popUp = node("com.example.notes", null, false);
        windows(popUp, app(ours(), false, TOP_HALF), divider(), app(offer(), false, BOTTOM_HALF),
                app(popUp, true, new Rect(100, 1200, 980, 1800)));
        assertFalse("not declined under a pop-up", declined());
        contains(DiagnosticLog.read(app), "[split] Dasher's half not read: the active app's window overlaps it");
    }

    @Test
    public void aSystemWindowOverMostOfDashersHalfHidesItButTheBarsDoNot() {
        AccessibilityNodeInfo ours = ours();
        // The navigation bar over the bottom edge of Dasher's half: Dasher is in view all the same.
        windows(ours, app(ours, true, TOP_HALF), divider(), app(offer(), false, BOTTOM_HALF),
                window(AccessibilityWindowInfo.TYPE_SYSTEM, null, false, new Rect(0, 1960, 1080, 2040)));
        assertTrue("declined beside the navigation bar", declined());

        // A system dialog over most of Dasher's half (the user in Offer Filter's half all the same).
        windows(ours, app(ours, true, TOP_HALF), divider(), app(offer(), false, BOTTOM_HALF),
                window(AccessibilityWindowInfo.TYPE_SYSTEM, null, false, new Rect(0, 1100, 1080, 2040)));
        assertFalse("not declined under a system window", declined());
        contains(DiagnosticLog.read(app), "[split] Dasher's half not read: a system window covers it");
    }

    // ---- P6: where Dasher was, in every screen and alert line, and the phone in the report ----

    @Test
    public void screenLinesSayWhereDasherWasAndWhoseWindowWasActive() {
        AccessibilityNodeInfo ours = ours();
        windows(ours, app(ours, true, TOP_HALF), divider(), app(offer(), false, BOTTOM_HALF));
        assertTrue(declined());
        contains(DiagnosticLog.read(app), "|true|true win=split/bottom/ours/49 labels=[");

        // The user touches Dasher's half: its window is the active one now, and a new line says so.
        AccessibilityNodeInfo touched = offer();
        windows(touched, app(ours, false, TOP_HALF), divider(), app(touched, true, BOTTOM_HALF));
        contains(DiagnosticLog.read(app), "win=split/bottom/dasher/49");

        // Dasher alone, full screen.
        AccessibilityNodeInfo full = offer();
        windows(full, app(full, true, SCREEN));
        contains(DiagnosticLog.read(app), "win=full/-/dasher/100");
        // The masking that every line goes through keeps the field whole.
        assertEquals("win=split/bottom/ours/49 screen=off locked=yes",
                PersonalText.maskLine("win=split/bottom/ours/49 screen=off locked=yes"));
    }

    @Test
    public void anAlertSaysWhereDasherWasAndWhetherThePhoneWasLocked() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        OfferAlerts.ensureChannel(app);
        // Dasher on screen once (its window known from then on), then the phone locks with the shade's lock screen
        // in front.
        AccessibilityNodeInfo idle = dasher("Finding offers");
        AccessibilityWindowInfo dasherWindow = app(idle, true, SCREEN);
        ((ShadowAccessibilityWindowInfo) Shadow.extract(dasherWindow)).setId(41);
        windows(idle, dasherWindow);
        AccessibilityWindowInfo behind = app(idle, false, SCREEN);
        ((ShadowAccessibilityWindowInfo) Shadow.extract(behind)).setId(41);
        Shadows.shadowOf(controller.get()).setWindows(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_SYSTEM, null, true, SCREEN), behind));
        Shadows.shadowOf(app.getSystemService(PowerManager.class)).setIsInteractive(false);
        Shadows.shadowOf(app.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);

        assertTrue(OfferAlerts.notifyOffer(app, "offer-1", null, OfferRule.Result.REVIEW, "Not classified", true));
        contains(DiagnosticLog.read(app),
                "[alert] posted REVIEW audibleRequested=true win=hidden/-/system/0 screen=off locked=yes");
    }

    @Test
    public void aSharedReportNamesAndroidAndThePhonesMaker() {
        ShadowBuild.setManufacturer("Google");
        String[] lines = DiagnosticLog.report(app).split("\n");
        assertEquals("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), Google", lines[1]);
        assertTrue(lines[2], lines[2].startsWith("Generated "));
    }

    // ---- P10: the guide below the status bar when Dasher's half is at the top ----

    @Test
    public void withDashersHalfAtTheTopTheGuideSitsBelowTheStatusBar() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        AreaMap.setEnabled(app, true);
        int[][] pays = {{1000, 1200, 800}, {1500, 1800, 1200}};
        double[][] spots = {{37.7749, -122.4194}, {37.8149, -122.3794}};
        double[] miles = {5.0, 6.0, 4.0};
        for (int spot = 0; spot < 2; spot++) {
            for (int i = 0; i < 3; i++) {
                at(spots[spot][0], spots[spot][1]);
                AreaMap.note(app, new DecisionLog.Entry(System.currentTimeMillis() + spot * 10 + i,
                        DecisionLog.Source.SCREEN, false, new OfferSnapshot(pays[spot][i], miles[i], 20 + i, 2), 1000,
                        OfferRule.Result.KEEP, "meets enabled rules", DecisionLog.Action.PASSES, true,
                        Collections.emptyList()));
            }
        }
        at(37.7749, -122.4194);
        AccessibilityNodeInfo ours = ours();
        // Dasher in the top half (its window from the screen's top edge, under the status bar), Offer Filter below.
        windows(ours, app(dasher("Finding offers"), false, TOP_HALF), divider(),
                app(ours, true, BOTTOM_HALF));
        DasherGuide guide = null;
        ShadowWindowManagerImpl manager = Shadow.extract(app.getSystemService(WindowManager.class));
        for (View view : manager.getViews()) if (view instanceof DasherGuide) guide = (DasherGuide) view;
        assertNotNull("the guide over Dasher's half", guide);
        int statusBar = DasherOverlay.statusBarHeight(app);
        assertTrue(statusBar > 0);
        int dp72 = Math.round(DasherOverlay.GUIDE_BELOW_TOP_DP * app.getResources().getDisplayMetrics().density);
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) guide.getLayoutParams();
        assertEquals("below the status bar and Dasher's top bar", TOP_HALF.top + statusBar + dp72, params.y);
    }

    private void at(double latitude, double longitude) {
        android.location.Location fix = new android.location.Location(android.location.LocationManager.NETWORK_PROVIDER);
        fix.setLatitude(latitude);
        fix.setLongitude(longitude);
        fix.setAccuracy(1500);
        fix.setTime(System.currentTimeMillis());
        fix.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());
        Shadows.shadowOf(app.getSystemService(android.location.LocationManager.class))
                .setLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER, fix);
    }
}

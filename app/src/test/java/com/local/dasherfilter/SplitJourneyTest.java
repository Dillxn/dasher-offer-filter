package com.local.dasherfilter;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.shadows.ShadowBuild;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowToast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Getting into split screen with Dasher and staying there (split-audit P1, P5, P9), through the real page and the
 * real screen-reading service, Android simulated. Each test fails on 0.4.45:
 * <ul>
 *   <li>0.4.45 took Android's "true" for the split as the split itself, so on a Pixel the button did nothing and said
 *       nothing; and its recent-apps words were the same on every phone;
 *   <li>it logged no step of the split ([split] lines);
 *   <li>it hid the button in split screen, leaving no way back to Dasher's half from the app;
 *   <li>it looked at the windows only at Dasher's events, so coming back to the page beside Dasher showed the map until
 *       Dasher's next event, and a sighting of Dasher beside aged while the screen was off.
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class SplitJourneyTest extends AndroidAdapterTestBase {
    private static final Rect TOP_HALF = new Rect(0, 0, 1080, 1000);
    private static final Rect DIVIDER = new Rect(0, 1000, 1080, 1040);
    private static final Rect BOTTOM_HALF = new Rect(0, 1040, 1080, 2040);

    @After
    public void forgetSplit() {
        DasherSplit.forget();
        OfferFilterService.sawDasherBeside(0);
        OfferFilterService.forgetScreenState();
    }

    private ServiceController<OfferFilterService> connectedService() {
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        idle();
        return service;
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void pass(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private static void contains(String text, String part) {
        assertTrue(part + " in:\n" + text, text.contains(part));
    }

    private AccessibilityNodeInfo root(String packageName, String text) {
        AccessibilityNodeInfo root = AccessibilityNodeInfo.obtain(new View(app));
        root.setPackageName(packageName);
        root.setVisibleToUser(true);
        if (text != null) {
            AccessibilityNodeInfo child = AccessibilityNodeInfo.obtain(new View(app));
            child.setPackageName(packageName);
            child.setText(text);
            child.setVisibleToUser(true);
            Shadows.shadowOf(root).addChild(child);
        }
        return root;
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

    /** Offer Filter in the top half, active; Dasher waiting for offers in the bottom half. No event of Dasher's. */
    private void splitWithDasherBelow(OfferFilterService service) {
        AccessibilityNodeInfo ours = root("com.local.dasherfilter", null);
        Shadows.shadowOf(service).setWindows(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, ours, true, TOP_HALF),
                window(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, null, false, DIVIDER),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, root("com.doordash.driverapp", "Finding offers"),
                        false, BOTTOM_HALF)));
        Shadows.shadowOf(service).setRootInActiveWindow(ours);
    }

    private static void enterSplit(ActivityController<MainActivity> activity) {
        Shadows.shadowOf(activity.get()).setInMultiWindowMode(true);
        activity.get().onMultiWindowModeChanged(true, activity.get().getResources().getConfiguration());
    }

    private static boolean launchesDasherBeside(Intent opened) {
        return opened != null && "com.doordash.driverapp".equals(opened.getComponent() != null
                ? opened.getComponent().getPackageName() : opened.getPackage())
                && (opened.getFlags() & Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT) != 0;
    }

    // ---- P1: a split request Android took is checked ----

    @Test
    public void onAPixelARequestAndroidTookButNeverActedOnOpensRecentAppsWithPixelWords() {
        // A Pixel: Android takes the split request (true) and then does nothing with it.
        ShadowBuild.setManufacturer("Google");
        dasherInstalled();
        DasherSplit.forget();
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            shownIcon(content, DasherSplit.SPLIT_LABEL).performClick();
            assertEquals(Collections.singletonList(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN),
                    Shadows.shadowOf(service.get()).getGlobalActionsPerformed());
            pass(DasherSplit.VERIFY_MS - 100);
            assertEquals("not yet", 1, Shadows.shadowOf(service.get()).getGlobalActionsPerformed().size());

            // 1.5 s on, the screen has not split: recent apps, with the Pixel's own steps. (0.4.45: nothing at all.)
            pass(200);
            assertEquals(Arrays.asList(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN,
                    AccessibilityService.GLOBAL_ACTION_RECENTS),
                    Shadows.shadowOf(service.get()).getGlobalActionsPerformed());
            assertEquals("In recent apps, tap Offer Filter's icon above its card, choose Split screen, then tap Dasher.",
                    ShadowToast.getTextOfLatestToast());
            assertNull(Shadows.shadowOf(app).getNextStartedActivity());
            String log = DiagnosticLog.read(app);
            contains(log, "[split] tap: Android took the split request; looking again in 1500 ms");
            contains(log, "[split] no split 1500 ms after Android took the request: opening recent apps");
            contains(log, "[split] recent apps opened, with the pixel hint");

            // The user splits it there 20 s later: Dasher opens beside, once.
            ShadowSystemClock.advanceBy(Duration.ofSeconds(20));
            enterSplit(activity);
            assertTrue(launchesDasherBeside(Shadows.shadowOf(app).getNextStartedActivity()));
            assertNull(Shadows.shadowOf(app).getNextStartedActivity());
            log = DiagnosticLog.read(app);
            contains(log, "[split] entered split screen");
            // Elapsed numbers can be masked by the diagnostic address filter; timing and launch flags are
            // asserted above, while this checks which split-launch path ran.
            contains(log, "after the tap: Dasher's launch intent into the other half");
        } finally {
            service.destroy();
        }
    }

    @Test
    public void onASamsungTheWordsNameItsOwnSplitScreenChoice() {
        ShadowBuild.setManufacturer("samsung");
        dasherInstalled();
        DasherSplit.forget();
        DasherSplit.split = () -> false;
        DasherSplit.recents = () -> true;
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            shownIcon(activity.get().findViewById(android.R.id.content), DasherSplit.SPLIT_LABEL).performClick();
            assertEquals("In recent apps, tap Offer Filter's icon above its card and choose Open in split screen view. "
                    + "Dasher then opens in the other half.", ShadowToast.getTextOfLatestToast());
            contains(DiagnosticLog.read(app), "[split] tap: Android refused the split request");
            contains(DiagnosticLog.read(app), "[split] recent apps opened, with the samsung hint");
        } finally {
            service.destroy();
        }
    }

    @Test
    public void aSplitWithinTheCheckOpensDasherOnceAndNoRecentApps() {
        dasherInstalled();
        DasherSplit.forget();
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            shownIcon(activity.get().findViewById(android.R.id.content), DasherSplit.SPLIT_LABEL).performClick();
            pass(500);
            enterSplit(activity);
            assertTrue(launchesDasherBeside(Shadows.shadowOf(app).getNextStartedActivity()));
            pass(5_000);
            assertEquals("never recent apps", Collections.singletonList(
                    AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN),
                    Shadows.shadowOf(service.get()).getGlobalActionsPerformed());
            assertNull(Shadows.shadowOf(app).getNextStartedActivity());
            String log = DiagnosticLog.read(app);
            contains(log, "[split] split ");
            assertFalse(log, log.contains("no split 1500 ms"));
        } finally {
            service.destroy();
        }
    }

    @Test
    public void leavingThePageBeforeTheCheckOpensNothingAndSaysWhy() {
        dasherInstalled();
        DasherSplit.forget();
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            shownIcon(activity.get().findViewById(android.R.id.content), DasherSplit.SPLIT_LABEL).performClick();
            pass(300);
            activity.pause();
            pass(5_000);
            assertEquals(1, Shadows.shadowOf(service.get()).getGlobalActionsPerformed().size());
            assertNull(Shadows.shadowOf(app).getNextStartedActivity());
            contains(DiagnosticLog.read(app), "[split] left Offer Filter before the screen was looked at again");
        } finally {
            service.destroy();
        }
    }

    // ---- P5: split without Dasher beside ----

    @Test
    public void splitWithoutDasherBesideTheButtonPutsDasherInTheOtherHalf() {
        dasherInstalled();
        DasherSplit.forget();
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            // Split with another app (Dasher's navigation opened Maps in its half, say): 0.4.45 hid the button.
            OfferFilterService.sawDasherBeside(0);
            enterSplit(activity);
            pass(1_100);
            assertNull(shownIcon(content, DasherSplit.SPLIT_LABEL));
            View beside = shownIcon(content, DasherSplit.BESIDE_LABEL);
            assertNotNull("Put Dasher beside", beside);

            beside.performClick();
            assertTrue(launchesDasherBeside(Shadows.shadowOf(app).getNextStartedActivity()));
            assertTrue("no split request: the screen is split already",
                    Shadows.shadowOf(service.get()).getGlobalActionsPerformed().isEmpty());
            contains(DiagnosticLog.read(app), "[split] tap in split screen: Dasher's launch intent into the other half");

            // Dasher is seen beside: nothing to put there.
            OfferFilterService.sawDasherBeside(SystemClock.uptimeMillis());
            pass(1_100);
            assertNull(shownIcon(content, DasherSplit.BESIDE_LABEL));
        } finally {
            service.destroy();
        }
    }

    // ---- P9: the windows looked at when the page comes back, and the sighting kept while the screen is off ----

    @Test
    public void comingBackToThePageBesideDasherLooksAtTheWindowsAtOnce() {
        dasherInstalled();
        // Connected while Dasher was not on screen: nothing watches the windows.
        ServiceController<OfferFilterService> service = connectedService();
        OfferFilterService.sawDasherBeside(0);
        // Now the pair is on screen, and Dasher has sent no event.
        splitWithDasherBelow(service.get());
        ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).create();
        try {
            Shadows.shadowOf(activity.get()).setInMultiWindowMode(true);
            activity.start().resume().visible();
            idle();
            assertTrue("Dasher beside, seen at once", OfferFilterService.dasherBeside());
            AreaMapView map = find(activity.get().findViewById(android.R.id.content), AreaMapView.class);
            assertEquals("no map of our own beside Dasher's", View.GONE, map.getVisibility());
        } finally {
            activity.pause().stop().destroy();
            service.destroy();
        }
    }

    @Test
    public void aSightingOfDasherBesideDoesNotAgeWhileTheScreenIsOff() {
        ServiceController<OfferFilterService> service = connectedService();
        splitWithDasherBelow(service.get());
        OfferFilterService.lookSoon(null);
        idle();
        assertTrue(OfferFilterService.dasherBeside());

        // The phone locked for five minutes: Android lists nothing of either app meanwhile.
        Shadows.shadowOf(service.get()).setWindows(Collections.emptyList());
        app.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        idle();
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5));
        assertTrue("kept while the screen is off", OfferFilterService.dasherBeside());
        app.sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));
        idle();
        assertTrue("and on unlocking, until a look says otherwise", OfferFilterService.dasherBeside());

        // With the screen on, it ages as before.
        ShadowSystemClock.advanceBy(Duration.ofMillis(OfferFilterService.BESIDE_MS));
        assertFalse(OfferFilterService.dasherBeside());
        service.destroy();
    }
}

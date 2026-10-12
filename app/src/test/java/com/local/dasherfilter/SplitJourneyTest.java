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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
import static org.junit.Assert.assertNotEquals;
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
@Config(sdk = {35, 36})
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

    /** A positively identified current pair; unlike a missing window list, it proves which pane would change. */
    private void identifiedPair(OfferFilterService service, String otherPackage) {
        identifiedPair(service, app.getPackageName(), otherPackage);
    }

    private void identifiedPair(OfferFilterService service, String firstPackage, String otherPackage) {
        AccessibilityNodeInfo ours = root(firstPackage, null);
        List<AccessibilityWindowInfo> windows = Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, ours, true, TOP_HALF),
                window(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, null, false, DIVIDER),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, root(otherPackage, null), false, BOTTOM_HALF));
        int id = 901;
        for (AccessibilityWindowInfo window : windows) {
            ((ShadowAccessibilityWindowInfo) Shadow.extract(window)).setId(id++);
            if (window.getRoot() != null) Shadows.shadowOf(window.getRoot()).setAccessibilityWindowInfo(window);
        }
        Shadows.shadowOf(service).setWindows(windows);
        Shadows.shadowOf(service).setRootInActiveWindow(ours);
        OfferFilterService.lookSoon(null); idle();
        assertEquals(SplitWindows.CardPlacement.OTHER_PAIR, OfferFilterService.cardSplitPlacementNow());
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
        DasherSplit.adjacentFirst = () -> false; // the split action first, as before Android 12L
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
            assertEquals("Tap Offer Filter's icon above its card → Split screen, then Dasher",
                    ShadowToast.getTextOfLatestToast());
            assertNull(Shadows.shadowOf(app).getNextStartedActivity());
            String log = DiagnosticLog.read(app);
            contains(log, "[split] tap: Android took the split request; looking again in 1500 ms");
            contains(log, "[split] no split 1500 ms after Android took the request: opening recent apps");
            contains(log, "[split] recent apps opened, with the pixel hint");

            // The user splits it there 20 s later: Dasher opens beside, once.
            ShadowSystemClock.advanceBy(Duration.ofSeconds(20));
            identifiedPair(service.get(), "com.example.launcher");
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
        DasherSplit.adjacentFirst = () -> false; // the split action first, as before Android 12L
        DasherSplit.split = () -> false;
        DasherSplit.recents = () -> true;
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            shownIcon(activity.get().findViewById(android.R.id.content), DasherSplit.SPLIT_LABEL).performClick();
            assertEquals("Tap Offer Filter's icon above its card → Open in split screen view",
                    ShadowToast.getTextOfLatestToast());
            contains(DiagnosticLog.read(app), "[split] tap: Android refused the split request");
            contains(DiagnosticLog.read(app), "[split] recent apps opened, with the samsung hint");
        } finally {
            service.destroy();
        }
    }

    /**
     * The owner's report of 7 October 2026: the tap was refused, recent apps opened, the user went back to Offer Filter
     * without a split, and an offer 37 s after the tap got no peek ("a split screen with Dasher is being set up").
     * Back on the page not split, the tap is over: no split is waited for, and a split made by hand later opens
     * nothing by itself.
     */
    /**
     * The owner's Android 16 Samsung refuses the split action every time (from Android 15 it is SystemUI's to offer),
     * so the tap only ever opened recent apps. From Android 12L the tap first starts Dasher beside this screen
     * (launch adjacent), which the phone answers by splitting the screen itself: one tap, nothing asked of Android's
     * split action, and the split it makes opens nothing more.
     */
    @Test
    public void aTapFromFullScreenStartsDasherBesideAndTheSplitItMakesOpensNothingMore() {
        ShadowBuild.setManufacturer("samsung");
        dasherInstalled();
        DasherSplit.forget();
        DasherSplit.adjacentFirst = () -> true;
        List<String> asked = new ArrayList<>();
        DasherSplit.split = () -> asked.add("split");
        DasherSplit.recents = () -> asked.add("recents");
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            shownIcon(activity.get().findViewById(android.R.id.content), DasherSplit.SPLIT_LABEL).performClick();
            Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
            assertTrue("Dasher's own launcher, beside this screen", launchesDasherBeside(opened));
            assertTrue("its existing task, never a second one",
                    (opened.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0
                            && (opened.getFlags() & Intent.FLAG_ACTIVITY_MULTIPLE_TASK) == 0);
            assertTrue("nothing asked of Android's split action or recent apps: " + asked, asked.isEmpty());
            assertNull(org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
            // The phone splits the screen: Offer Filter in its half, Dasher in the other.
            activity.pause();
            splitWithDasherBelow(service.get());
            enterSplit(activity);
            activity.resume();
            idle();
            assertNull("the split it made opens nothing more", Shadows.shadowOf(app).getNextStartedActivity());
            pass(DasherSplit.VERIFY_MS + 100);
            assertTrue("and never recent apps: " + asked, asked.isEmpty());
            assertFalse(DasherSplit.pending());
            contains(DiagnosticLog.read(app), "[split] tap: Dasher's launch intent beside this screen (launch adjacent)");
            contains(DiagnosticLog.read(app), "[split] split by Dasher's launch-adjacent start; nothing more opened");
            // Android's list of windows 5 and 10 s after the start, so a report shows whether Dasher's half drew.
            pass(10_000);
            contains(DiagnosticLog.read(app), "[split] Dasher's launch-adjacent start + 5 s: win=");
            contains(DiagnosticLog.read(app), "[split] Dasher's launch-adjacent start + 10 s: win=");
        } finally {
            service.destroy();
        }
    }

    /**
     * During a delivery the tap never starts Dasher's launcher beside this screen: on 11 October 2026 the owner's tap
     * during Dasher's own turn-by-turn navigation left Dasher's half on its launch screen. Recent apps open with the
     * phone's words instead, so Dasher's existing screen moves into the split as it is.
     */
    @Test
    public void aTapDuringADeliveryOpensRecentAppsInsteadOfStartingDasher() {
        ShadowBuild.setManufacturer("samsung");
        dasherInstalled();
        DasherSplit.forget();
        DasherSplit.adjacentFirst = () -> true;
        DasherSplit.onDelivery = context -> true;
        List<String> asked = new ArrayList<>();
        DasherSplit.split = () -> asked.add("split");
        DasherSplit.recents = () -> asked.add("recents");
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            shownIcon(activity.get().findViewById(android.R.id.content), DasherSplit.SPLIT_LABEL).performClick();
            assertNull("nothing of Dasher's started", Shadows.shadowOf(app).getNextStartedActivity());
            assertEquals(Arrays.asList("recents"), asked);
            assertEquals(DasherSplit.hintWithDasher("samsung"), org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
            contains(DiagnosticLog.read(app), "[split] tap during a delivery: " + DasherSplit.DELIVERY_RECENTS);
            contains(DiagnosticLog.read(app), "[split] recent apps opened, with the samsung hint to pick Dasher's card");
        } finally {
            service.destroy();
        }
    }

    /**
     * The owner's reports of 12 October 2026: Split tapped while Dasher was finding offers left Dasher's half on its
     * launch screen. During the whole dash, not only a delivery, the tap opens recent apps with the phone's words, and
     * the split the user makes there opens nothing: Dasher's own card goes in the other half.
     */
    @Test
    public void aTapDuringADashOpensRecentAppsAndTheSplitMadeThereOpensNothing() {
        ShadowBuild.setManufacturer("samsung");
        dasherInstalled();
        DasherSplit.forget();
        DasherSplit.adjacentFirst = () -> true;
        DasherSplit.onDelivery = context -> false;
        List<String> asked = new ArrayList<>();
        DasherSplit.split = () -> asked.add("split");
        DasherSplit.recents = () -> asked.add("recents");
        Dashing.seen(app);
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            shownIcon(activity.get().findViewById(android.R.id.content), DasherSplit.SPLIT_LABEL).performClick();
            assertNull("Dasher's launcher is never started beside", Shadows.shadowOf(app).getNextStartedActivity());
            assertEquals(Arrays.asList("recents"), asked);
            assertEquals(DasherSplit.hintWithDasher("samsung"), org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
            contains(DiagnosticLog.read(app), "[split] tap during a dash: " + DasherSplit.RUNNING_RECENTS);
            // The user splits it in recent apps, picking Dasher's own card for the other half.
            activity.pause();
            enterSplit(activity);
            activity.resume();
            idle();
            assertNull("nothing is opened into the other half", Shadows.shadowOf(app).getNextStartedActivity());
            assertFalse(DasherSplit.pending());
            contains(DiagnosticLog.read(app), "during a dash: Dasher's own card goes in the other half; nothing opened");
            assertNotEquals("no toast after the split", "Keeping this split. Open Dasher from its icon if needed.",
                    org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
        } finally {
            service.destroy();
        }
    }

    /** Dasher's pickup or turn-by-turn screen counts as a delivery for ten minutes, and the wait for offers ends it. */
    @Test
    public void aDeliveryScreenCountsUntilTheWaitOrTenMinutes() {
        OfferFilterService.forgetScreenState();
        assertFalse(OfferFilterService.dasherOnDelivery(app));
        OfferFilterService.deliveryScreenSeen(android.os.SystemClock.uptimeMillis());
        assertTrue(OfferFilterService.dasherOnDelivery(app));
        pass(OfferFilterService.DELIVERY_SCREEN_MS + 1000);
        assertFalse(OfferFilterService.dasherOnDelivery(app));
    }

    /** A phone that opens Dasher full screen instead: recent apps then, with the phone's words, as before. */
    @Test
    public void anAdjacentStartThatDoesNotSplitOpensRecentAppsWithThePhonesWords() {
        ShadowBuild.setManufacturer("samsung");
        dasherInstalled();
        DasherSplit.forget();
        DasherSplit.adjacentFirst = () -> true;
        List<String> asked = new ArrayList<>();
        DasherSplit.split = () -> asked.add("split");
        DasherSplit.recents = () -> asked.add("recents");
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            shownIcon(activity.get().findViewById(android.R.id.content), DasherSplit.SPLIT_LABEL).performClick();
            assertTrue(launchesDasherBeside(Shadows.shadowOf(app).getNextStartedActivity()));
            // Dasher came up full screen over the page.
            activity.pause();
            pass(DasherSplit.VERIFY_MS + 100);
            assertEquals("recent apps, never Android's split action", Arrays.asList("recents"), asked);
            assertEquals("Tap Offer Filter's icon above its card → Open in split screen view",
                    org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
            contains(DiagnosticLog.read(app), "[split] no split 1500 ms after Dasher's launch-adjacent start: opening recent apps");
            // A split made there within the minute still opens Dasher beside.
            pass(20_000);
            identifiedPair(service.get(), "com.example.launcher");
            enterSplit(activity);
            activity.resume();
            idle();
            assertTrue(launchesDasherBeside(Shadows.shadowOf(app).getNextStartedActivity()));
        } finally {
            service.destroy();
        }
    }

    @Test
    public void backOnThePageWithoutASplitTheTapIsOver() {
        ShadowBuild.setManufacturer("samsung");
        dasherInstalled();
        DasherSplit.forget();
        DasherSplit.adjacentFirst = () -> false; // the split action first, as before Android 12L
        DasherSplit.split = () -> false;
        DasherSplit.recents = () -> true;
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            shownIcon(activity.get().findViewById(android.R.id.content), DasherSplit.SPLIT_LABEL).performClick();
            assertTrue("the tap waits for the split", DasherSplit.pending());
            // Recent apps over the page, then back on it, still full screen.
            activity.pause();
            pass(10_000);
            assertTrue("in recent apps the tap still waits", DasherSplit.pending());
            activity.resume();
            idle();
            assertFalse("back on the page without a split, nothing waits", DasherSplit.pending());
            contains(DiagnosticLog.read(app), "[split] back on Offer Filter without a split: the tap is over");
            while (Shadows.shadowOf(app).getNextStartedActivity() != null) { /* Nothing of the tap's. */ }
            enterSplit(activity);
            idle();
            assertNull("a split made later opens nothing by itself", Shadows.shadowOf(app).getNextStartedActivity());
        } finally {
            service.destroy();
        }
    }

    @Test
    public void aSplitMadeInRecentAppsStillOpensDasherBeside() {
        ShadowBuild.setManufacturer("samsung");
        dasherInstalled();
        DasherSplit.forget();
        DasherSplit.adjacentFirst = () -> false; // the split action first, as before Android 12L
        DasherSplit.split = () -> false;
        DasherSplit.recents = () -> true;
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            shownIcon(activity.get().findViewById(android.R.id.content), DasherSplit.SPLIT_LABEL).performClick();
            activity.pause();
            pass(5_000);
            // "Open in split screen view" on Offer Filter's card: the page comes back in its half of the split.
            identifiedPair(service.get(), "com.example.launcher");
            Shadows.shadowOf(activity.get()).setInMultiWindowMode(true);
            activity.resume();
            idle();
            assertTrue(launchesDasherBeside(Shadows.shadowOf(app).getNextStartedActivity()));
            assertNull("once", Shadows.shadowOf(app).getNextStartedActivity());
            assertFalse(DasherSplit.pending());
        } finally {
            service.destroy();
        }
    }

    @Test
    public void aSplitWithinTheCheckOpensDasherOnceAndNoRecentApps() {
        dasherInstalled();
        DasherSplit.forget();
        DasherSplit.adjacentFirst = () -> false; // the split action first, as before Android 12L
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            shownIcon(activity.get().findViewById(android.R.id.content), DasherSplit.SPLIT_LABEL).performClick();
            pass(500);
            identifiedPair(service.get(), "com.example.launcher");
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
        DasherSplit.adjacentFirst = () -> false; // the split action first, as before Android 12L
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

    @Test
    public void aPendingSplitDoesNotLaunchAgainAfterAndroidAlreadyPairedDasher() {
        dasherInstalled();
        DasherSplit.forget();
        DasherSplit.adjacentFirst = () -> false; // the split action first, as before Android 12L
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            shownIcon(activity.get().findViewById(android.R.id.content), DasherSplit.SPLIT_LABEL).performClick();
            splitWithDasherBelow(service.get());
            java.util.List<AccessibilityWindowInfo> windows = service.get().getWindows();
            int id = 901;
            for (AccessibilityWindowInfo window : windows) {
                ((ShadowAccessibilityWindowInfo) Shadow.extract(window)).setId(id++);
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) Shadows.shadowOf(root).setAccessibilityWindowInfo(window);
            }
            OfferFilterService.lookSoon(null);
            idle();
            assertTrue(OfferFilterService.dasherBeside());
            enterSplit(activity);
            assertNull("already paired: no redundant launcher/onNewIntent into Dasher", Shadows.shadowOf(app).getNextStartedActivity());
            assertFalse(DasherSplit.pending());
            pass(DasherSplit.VERIFY_MS + 50);
            assertNull(Shadows.shadowOf(app).getNextStartedActivity());
            contains(DiagnosticLog.read(app), "Dasher already visible beside");
        } finally {
            service.destroy();
        }
    }

    // ---- P5: split without Dasher beside ----

    @Test public void pendingPairThenMissingResizeMetadataNeverRelaunchesDasher() {
        dasherInstalled();
        DasherSplit.forget();
        DasherSplit.adjacentFirst = () -> false;
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            shownIcon(activity.get().findViewById(android.R.id.content), DasherSplit.SPLIT_LABEL).performClick();
            splitWithDasherBelow(service.get());
            OfferFilterService.lookSoon(null);
            idle();
            assertTrue(OfferFilterService.dasherBeside());
            // Android already paired the existing task. Divider movement can temporarily omit its metadata.
            Shadows.shadowOf(service.get()).setWindows(Collections.emptyList());
            Shadows.shadowOf(service.get()).setRootInActiveWindow(null);
            enterSplit(activity);
            assertNull("unknown is not permission to replace the paired task",
                    Shadows.shadowOf(app).getNextStartedActivity());
            assertFalse(DasherSplit.pending());
            activity.pause().resume();
            enterSplit(activity);
            pass(DasherSplit.VERIFY_MS + 100);
            assertNull("neither resize nor resume retries the launch", Shadows.shadowOf(app).getNextStartedActivity());
        } finally { service.destroy(); }
    }

    @Test public void unrequestedResizeNeverLaunchesEvenWithNoWindowMetadata() {
        dasherInstalled(); DasherSplit.forget();
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            for (int n = 0; n < 3; n++) {
                enterSplit(activity);
                activity.pause().resume();
            }
            assertNull(Shadows.shadowOf(app).getNextStartedActivity());
            assertTrue(Shadows.shadowOf(service.get()).getGlobalActionsPerformed().isEmpty());
        } finally { service.destroy(); }
    }

    @Test public void pendingSplitKeepsTheMapPaneDuringAnObservedDash() {
        dasherInstalled(); DasherSplit.forget(); DasherSplit.adjacentFirst = () -> false;
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            shownIcon(activity.get().findViewById(android.R.id.content), DasherSplit.SPLIT_LABEL).performClick();
            Dashing.seen(app);
            identifiedPair(service.get(), "com.google.android.apps.maps");
            AccessibilityWindowInfo mapPane = service.get().getWindows().get(2);
            enterSplit(activity);
            assertNull("a pending setup must not replace the user's map pane", Shadows.shadowOf(app).getNextStartedActivity());
            assertTrue(service.get().getWindows().contains(mapPane));
            assertFalse(DasherSplit.pending());
            // A new explicit handoff remains available. Its ordinary task launch requests no adjacent replacement.
            DasherSplit.swapIn(activity.get());
            Intent explicit = Shadows.shadowOf(app).getNextStartedActivity();
            assertNotNull(explicit);
            assertEquals(0, explicit.getFlags() & Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        } finally { service.destroy(); }
    }

    @Test public void visibleMapIsPreservedWithoutClaimingItsRouteIsActive() {
        dasherInstalled(); DasherSplit.forget(); DasherSplit.adjacentFirst = () -> false;
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            shownIcon(activity.get().findViewById(android.R.id.content), DasherSplit.SPLIT_LABEL).performClick();
            identifiedPair(service.get(), "com.waze");
            assertFalse("no observed dash or route is inferred from a visible map", Dashing.now(app));
            enterSplit(activity);
            assertNull("keep the known map pane without sending any destination or launch",
                    Shadows.shadowOf(app).getNextStartedActivity());
            assertFalse(DasherSplit.pending());
        } finally { service.destroy(); }
    }

    @Test public void aPendingRequestCannotReplaceSomeoneElsesCurrentSplit() {
        dasherInstalled(); DasherSplit.forget(); DasherSplit.adjacentFirst = () -> false;
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            shownIcon(activity.get().findViewById(android.R.id.content), DasherSplit.SPLIT_LABEL).performClick();
            identifiedPair(service.get(), "com.example.reader", "com.example.notes");
            enterSplit(activity);
            assertNull("a resumed Activity is not proof our pane is in the current pair",
                    Shadows.shadowOf(app).getNextStartedActivity());
            assertFalse(DasherSplit.pending());
        } finally { service.destroy(); }
    }

    @Test
    public void splitWithoutDasherBesideTheButtonPutsDasherInTheOtherHalf() {
        dasherInstalled();
        DasherSplit.forget();
        DasherSplit.adjacentFirst = () -> false; // the split action first, as before Android 12L
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
            View content = activity.get().findViewById(android.R.id.content);
            assertNull("so the header offers no Put Dasher beside", shownIcon(content, DasherSplit.BESIDE_LABEL));
            // 0.5.1 hid the page's own map beside Dasher's; the fluid page keeps it, whoever is beside (the owner: "I do
            // not like how it only shows the map or the radar").
            assertEquals("the page's own map stays", View.VISIBLE, find(content, AreaMapView.class).getVisibility());
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

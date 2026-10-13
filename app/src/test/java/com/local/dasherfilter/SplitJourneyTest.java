package com.local.dasherfilter;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
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
import org.robolectric.shadows.ShadowAccessibilityRecord;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.shadows.ShadowBuild;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Getting into split screen with Dasher and staying there (split-audit P1, P5, P9), through the real page and the
 * real screen-reading service, Android simulated. From 0.6.4 a tap from full screen starts this app's own split pane
 * beside the page, launch-adjacent (during a delivery, or while auto-decline is paused, recent apps instead); in split
 * screen the pane starts Dasher's launcher as its home-screen icon does, and is gone. No start of Dasher ever asks for
 * the other half (the owner's "dd is stuck again" reports followed
 * Split taps that started Dasher's launcher launch-adjacent). Each test fails on 0.4.45, on 0.6.2 or on 0.6.3:
 * <ul>
 *   <li>0.4.45 took Android's "true" for the split as the split itself, so on a Pixel the button did nothing and said
 *       nothing; and its recent-apps words were the same on every phone;
 *   <li>it logged no step of the split ([split] lines);
 *   <li>it hid the button in split screen, leaving no way back to Dasher's half from the app;
 *   <li>it looked at the windows only at Dasher's events, so coming back to the page beside Dasher showed the map until
 *       Dasher's next event, and a sighting of Dasher beside aged while the screen was off;
 *   <li>0.6.2 started Dasher's launcher launch-adjacent from the tap, and again into a split the user made;
 *   <li>0.6.3 started Dasher full screen and left its own launch-adjacent start to the screen reader, which One UI
 *       never split for.
 * </ul>
 * Every activity start these tests saw is recorded, and none of Dasher's may carry the launch-adjacent flag.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public class SplitJourneyTest extends AndroidAdapterTestBase {
    private static final String DASHER = "com.doordash.driverapp";
    private static final ComponentName DASHER_HOME = new ComponentName(DASHER, DASHER + ".Home");
    /** A screen of Dasher's past its launcher, as its own window changes name it. */
    private static final String DASHER_MAIN = DASHER + ".ui.MainActivity";
    private static final Rect SCREEN = new Rect(0, 0, 1080, 2040);
    private static final Rect TOP_HALF = new Rect(0, 0, 1080, 1000);
    private static final Rect DIVIDER = new Rect(0, 1000, 1080, 1040);
    private static final Rect BOTTOM_HALF = new Rect(0, 1040, 1080, 2040);
    private static final int OURS_WINDOW = 921;
    private static final int DASHER_WINDOW = 922;
    private static final int DIVIDER_WINDOW = 923;
    private static final String KEEPING = "Keeping this split. Open Dasher from its icon if needed.";

    /** Every activity started in a test (by the page or by the screen reader), in order. */
    private final List<Intent> everStarted = new ArrayList<>();

    @After
    public void forgetSplit() {
        for (Intent left = next(); left != null; left = next()) {
            // Drained: recorded with the rest.
        }
        // No start of Dasher, from any path, is launch-adjacent: only this app's own task may be.
        for (Intent started : everStarted) {
            if (isDashers(started)) {
                assertEquals(started.toString(), 0, started.getFlags() & Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
            }
        }
        DasherSplit.forget();
        app.getSharedPreferences(DasherSplit.PANE_PREFS, android.content.Context.MODE_PRIVATE).edit().clear().commit();
        OfferFilterService.sawDasherBeside(0);
        OfferFilterService.forgetScreenState();
    }

    /**
     * Auto-decline on with a $4.00 minimum, so Dasher's screen is read: the look after the pane's start can say no
     * offer is up. Paused (no rule, the default, or auto-decline off) nothing of Dasher's is read, and the tap opens
     * recent apps instead ({@link #aTapWhilePausedOpensRecentAppsAndStartsNothing}).
     */
    private void readingDasher() {
        FilterStore.save(app, FilterSettings.of(true, 400, 0, 0, 0));
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

    private static int count(String text, String part) {
        int found = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) found++;
        return found;
    }

    /** The next activity started (by the page or the screen reader), recorded; null when none. */
    private Intent next() {
        Intent started = Shadows.shadowOf(app).getNextStartedActivity();
        if (started != null) everStarted.add(started);
        return started;
    }

    private static boolean isDashers(Intent started) {
        String pkg = started.getComponent() != null ? started.getComponent().getPackageName() : started.getPackage();
        return DASHER.equals(pkg);
    }

    private static View content(ActivityController<MainActivity> activity) {
        return activity.get().findViewById(android.R.id.content);
    }

    private List<Integer> globalActions(ServiceController<OfferFilterService> service) {
        return Shadows.shadowOf(service.get()).getGlobalActionsPerformed();
    }

    /** Dasher's launcher, as its home-screen icon starts it: never launch-adjacent, never a new or cleared task. */
    private static void assertDashersLauncher(String why, Intent opened) {
        assertNotNull(why + ": Dasher's launcher is started", opened);
        assertEquals(why, DASHER_HOME, opened.getComponent());
        assertNull(why + ": its component only, as the home screen starts it", opened.getPackage());
        assertTrue(why, opened.hasCategory(Intent.CATEGORY_LAUNCHER));
        assertEquals(why + ": as its home-screen icon starts it (no launch adjacent, no new or cleared task)",
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED, opened.getFlags());
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

    private AccessibilityNodeInfo root(String packageName, String text) {
        AccessibilityNodeInfo root = node(packageName, null, false);
        if (text != null) Shadows.shadowOf(root).addChild(node(packageName, text, false));
        return root;
    }

    /** A Compose button of Dasher's: a clickable node without text, holding its label. */
    private AccessibilityNodeInfo button(String label) {
        AccessibilityNodeInfo button = node(DASHER, null, true);
        Shadows.shadowOf(button).addChild(node(DASHER, label, false));
        return button;
    }

    /** An offer that passes the $4.00 minimum, as Dasher draws it: the user's to accept, never declined. */
    private AccessibilityNodeInfo passingOffer() {
        AccessibilityNodeInfo root = node(DASHER, null, false);
        Shadows.shadowOf(root).addChild(button("Decline"));
        Shadows.shadowOf(root).addChild(node(DASHER, "$9.90", false));
        Shadows.shadowOf(root).addChild(node(DASHER, "incl. tips", false));
        Shadows.shadowOf(root).addChild(node(DASHER, "2 stops (7.2 mi) • 21 min", false));
        Shadows.shadowOf(root).addChild(button("Accept"));
        Shadows.shadowOf(root).addChild(node(DASHER, "0:35", false));
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

    private static AccessibilityWindowInfo window(int id, int type, AccessibilityNodeInfo root, boolean active,
            Rect bounds) {
        AccessibilityWindowInfo window = window(type, root, active, bounds);
        ((ShadowAccessibilityWindowInfo) Shadow.extract(window)).setId(id);
        if (root != null) Shadows.shadowOf(root).setAccessibilityWindowInfo(window);
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

    /** Dasher up full screen and in front, showing {@code shows}, in the window its own events name. */
    private void dasherInFront(OfferFilterService service, AccessibilityNodeInfo shows) {
        Shadows.shadowOf(service).setWindows(Collections.singletonList(
                window(DASHER_WINDOW, AccessibilityWindowInfo.TYPE_APPLICATION, shows, true, SCREEN)));
        Shadows.shadowOf(service).setRootInActiveWindow(shows);
    }

    /** The screen split by the phone: this app's half on top (active), Dasher's below, each in its own window. */
    private void splitAboveDasher(OfferFilterService service) {
        splitAboveDasher(service, "Finding offers");
    }

    /** As {@link #splitAboveDasher(OfferFilterService)}, Dasher's half showing {@code dasherText} (null: nothing yet). */
    private void splitAboveDasher(OfferFilterService service, String dasherText) {
        AccessibilityNodeInfo ours = root(app.getPackageName(), null);
        Shadows.shadowOf(service).setWindows(Arrays.asList(
                window(OURS_WINDOW, AccessibilityWindowInfo.TYPE_APPLICATION, ours, true, TOP_HALF),
                window(DIVIDER_WINDOW, AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, null, false, DIVIDER),
                window(DASHER_WINDOW, AccessibilityWindowInfo.TYPE_APPLICATION, root(DASHER, dasherText),
                        false, BOTTOM_HALF)));
        Shadows.shadowOf(service).setRootInActiveWindow(ours);
    }

    /**
     * Dasher's own window change naming {@code activity}, as Android delivers it; {@code ofItsWindow} names the window
     * it is in (Dasher's events teach the screen reader its window IDs).
     */
    private static void dasherWindowChange(OfferFilterService service, String activity, boolean ofItsWindow) {
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName(DASHER);
        event.setClassName(activity);
        event.setEventTime(SystemClock.uptimeMillis());
        if (ofItsWindow) ((ShadowAccessibilityRecord) Shadow.extract(event)).setWindowId(DASHER_WINDOW);
        service.onAccessibilityEvent(event);
        idle();
    }

    /** The user's own tap on Dasher (no tap of the app's under way), as Android reports it. */
    private static void usersClickOnDasher(OfferFilterService service) {
        AccessibilityEvent click = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
        click.setPackageName(DASHER);
        click.setEventTime(SystemClock.uptimeMillis());
        service.onAccessibilityEvent(click);
        idle();
    }

    /** The user's tap on Split with Dasher, from the page's header. */
    private static void tapSplit(ActivityController<MainActivity> activity) {
        View button = shownIcon(content(activity), DasherSplit.SPLIT_LABEL);
        assertNotNull("the header's Split with Dasher", button);
        button.performClick();
        idle();
    }

    private static void enterSplit(ActivityController<MainActivity> activity) {
        Shadows.shadowOf(activity.get()).setInMultiWindowMode(true);
        activity.get().onMultiWindowModeChanged(true, activity.get().getResources().getConfiguration());
    }

    /** A positively identified current pair; unlike a missing window list, it proves which pane would change. */
    private void identifiedPair(OfferFilterService service, String otherPackage) {
        identifiedPair(service, app.getPackageName(), otherPackage);
    }

    /** The screen split between {@code firstPackage} (active, above) and {@code otherPackage}, windows listed. */
    private void pairedWith(OfferFilterService service, String firstPackage, String otherPackage) {
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
    }

    /** As {@link #pairedWith}, and positively identified as the current pair. */
    private void identifiedPair(OfferFilterService service, String firstPackage, String otherPackage) {
        pairedWith(service, firstPackage, otherPackage);
        assertEquals(SplitWindows.CardPlacement.OTHER_PAIR, OfferFilterService.cardSplitPlacementNow());
    }

    // ---- The split pane: one tap, this app's own pane beside the page, then Dasher from the pane ----

    /** The split pane started beside the page: exactly a new task launched adjacent, nothing reset or cleared. */
    private void assertPane(String why, Intent opened) {
        assertNotNull(why + ": the split pane is started", opened);
        assertEquals(why, new ComponentName(app, SplitPaneActivity.class), opened.getComponent());
        assertEquals(why + ": exactly a new task, launched adjacent",
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT, opened.getFlags());
        assertNotEquals(why + ": the tap's number", 0, opened.getIntExtra(SplitPaneActivity.EXTRA_TAP, 0));
    }

    /** The split pane comes up as Android starts it, in split screen ({@code split}) or full screen. */
    private static ActivityController<SplitPaneActivity> paneUp(Intent pane, boolean split) {
        ActivityController<SplitPaneActivity> controller = Robolectric.buildActivity(SplitPaneActivity.class, pane);
        Shadows.shadowOf(controller.get()).setInMultiWindowMode(split);
        controller.setup();
        idle();
        return controller;
    }

    /**
     * The owner's report of 12 October 2026, 7:37 PM ("split screen button didnt work"): 0.6.3 opened Dasher full screen
     * and its screen reader's launch-adjacent start of this app never split One UI's screen, where 0.6.2's start from
     * the homepage had. The tap now starts this app's own split pane beside the page, launch-adjacent; the phone splits;
     * the pane starts Dasher's launcher as its icon does (never launch-adjacent) and is gone; Dasher is beside, and
     * nothing more opens. The same whether a dash is on. The phone is remembered as one the pane splits.
     */
    @Test
    public void thePaneSplitsWithOneTapWithAndWithoutADash() {
        ShadowBuild.setManufacturer("samsung");
        dasherInstalled();
        readingDasher();
        for (String variant : new String[] {"no dash", "during a dash"}) {
            DasherSplit.forget();
            DiagnosticLog.clear(app);
            ServiceController<OfferFilterService> service = connectedService();
            if (variant.equals("during a dash")) Dashing.seen(app);
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                int toasts = ShadowToast.shownToastCount();
                tapSplit(activity);
                Intent pane = next();
                assertPane(variant, pane);
                assertNull(variant + ": nothing else, and nothing of Dasher's, from the page", next());
                assertTrue(variant, DasherSplit.pending());
                // The phone splits the screen: the page above, the pane below.
                enterSplit(activity);
                activity.resume();
                idle();
                assertFalse(variant + ": the split is made", DasherSplit.pending());
                ActivityController<SplitPaneActivity> up = paneUp(pane, true);
                assertDashersLauncher(variant + ": Dasher from the pane", next());
                assertTrue(variant + ": the pane is gone", up.get().isFinishing());
                assertNull(variant + ": once", next());
                // Dasher comes into the pane's half.
                splitAboveDasher(service.get());
                dasherWindowChange(service.get(), DASHER_MAIN, true);
                pass(DasherSplit.BESIDE_CHECK_MS + 100);
                assertNull(variant + ": the split opens nothing more", next());
                assertTrue(variant + ": never recent apps, never Android's split action", globalActions(service).isEmpty());
                assertEquals(variant + ": no words", toasts, ShadowToast.shownToastCount());
                assertFalse(variant, DasherSplit.paneNeverSplits(app));
                String log = DiagnosticLog.read(app);
                contains(log, "[split] tap: this app's split pane beside this screen, launch-adjacent (own tasks ");
                contains(log, "[split] screen split by the split pane's launch-adjacent start (");
                contains(log, "[split] split pane started (in split screen: yes)");
                contains(log, "[split] split pane in split screen: Dasher's launcher from the pane, as its icon starts it");
                contains(log, "[split] after Dasher's start from the pane: Dasher beside yes");
                assertFalse(variant + ": " + log, log.contains("not paired"));
                // Android's list of windows 5 and 10 s after Dasher's start, so a report shows whether Dasher drew.
                pass(10_000);
                log = DiagnosticLog.read(app);
                contains(log, "[launch] split + 5 s: win=");
                contains(log, "[launch] split + 10 s: win=");
                assertFalse(variant + ": Dasher drew", log.contains("Dasher still on its launch screen"));
                assertEquals(0, OfferFilterService.launchScreenHeldSince(app));
                up.destroy();
            } finally {
                service.destroy();
            }
        }
    }

    /**
     * Dasher started from the pane but named only by its launcher activity, its red launch screen (the incident of 10
     * to 12 October): 10 s after its start the launch watch says so once; nothing is started again for it and nothing
     * automatic acts on Dasher, and the line goes once Dasher draws.
     */
    @Test
    public void dasherOnItsLaunchScreenAfterThePaneIsWatchedNeverStartedAgain() {
        dasherInstalled();
        readingDasher();
        DasherSplit.forget();
        DiagnosticLog.clear(app);
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tapSplit(activity);
            Intent pane = next();
            assertPane("the tap", pane);
            enterSplit(activity);
            activity.resume();
            idle();
            ActivityController<SplitPaneActivity> up = paneUp(pane, true);
            assertDashersLauncher("Dasher from the pane", next());
            splitAboveDasher(service.get(), null);
            dasherWindowChange(service.get(), DASHER_HOME.getClassName(), true);
            pass(OfferFilterService.LAUNCH_VERDICT_MS);
            String log = DiagnosticLog.read(app);
            contains(log, "[launch] Dasher still on its launch screen 10 s after split (only Home named; no Dasher "
                    + "screen recognised)");
            assertEquals(log, 1, count(log, "Dasher still on its launch screen 10 s after"));
            assertNotEquals("the homepage asks", 0, OfferFilterService.launchScreenHeldSince(app));
            assertNull("nothing is started again for it", next());
            assertTrue("no recent apps, no Back: nothing automatic", globalActions(service).isEmpty());

            // Dasher draws at last: the line goes.
            dasherWindowChange(service.get(), DASHER_MAIN, true);
            assertEquals(0, OfferFilterService.launchScreenHeldSince(app));
            log = DiagnosticLog.read(app);
            contains(log, "[launch] Dasher past its launch screen ");
            contains(log, "[split] launch-screen line gone: Dasher drew");
            assertNull(next());
            up.destroy();
        } finally {
            service.destroy();
        }
    }

    /**
     * The pane never starts Dasher outside split screen: up full screen (no split), it waits, starts nothing and is
     * gone; the page's look opens recent apps. (0.6.3's Dasher first opened Dasher full screen and waited out any offer
     * before its own start; the pane never puts Dasher in front full screen, so there is nothing of Dasher's to wait out
     * before the split, and the look below waits out an offer instead.)
     */
    @Test
    public void thePaneOutsideSplitScreenStartsNothing() {
        ShadowBuild.setManufacturer("samsung");
        dasherInstalled();
        readingDasher();
        DasherSplit.forget();
        DasherSplit.recents = () -> true;
        DiagnosticLog.clear(app);
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tapSplit(activity);
            Intent pane = next();
            assertPane("the tap", pane);
            activity.pause();
            ActivityController<SplitPaneActivity> up = paneUp(pane, false);
            pass(DasherSplit.VERIFY_MS + 100);
            assertNull("nothing of Dasher's from a pane not in split screen", next());
            assertTrue("gone", up.get().isFinishing());
            String log = DiagnosticLog.read(app);
            contains(log, "[split] split pane started (in split screen: no)");
            contains(log, "[split] split pane never in split screen: gone, nothing started");
            assertFalse(log, log.contains("Dasher's launcher from the pane"));
            up.destroy();
        } finally {
            service.destroy();
        }
    }

    /**
     * A newer tap ends the earlier one with its own line, and only the newer tap's look runs: one pane per tap, no
     * recent apps from the earlier one. Another app in front (a peek's look finds it) ends the tap too, and its look
     * opens nothing.
     */
    @Test
    public void aNewerTapOrAnotherAppInFrontEndsTheTap() {
        dasherInstalled();
        readingDasher();
        for (String cause : new String[] {"newer tap", "another app"}) {
            DasherSplit.forget();
            DiagnosticLog.clear(app);
            ServiceController<OfferFilterService> service = connectedService();
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                tapSplit(activity);
                Intent first = next();
                assertPane(cause, first);
                pass(300);
                if (cause.equals("newer tap")) {
                    tapSplit(activity);
                    Intent second = next();
                    assertPane("the newer tap", second);
                    assertNotEquals(first.getIntExtra(SplitPaneActivity.EXTRA_TAP, 0),
                            second.getIntExtra(SplitPaneActivity.EXTRA_TAP, 0));
                    String log = DiagnosticLog.read(app);
                    assertEquals(log, 1, count(log, "[split] not paired: a newer tap; nothing more opened"));
                    // The earlier tap's look finds it over; the newer tap's is the one that runs.
                    enterSplit(activity);
                    activity.resume();
                    idle();
                    pass(DasherSplit.VERIFY_MS + 100);
                    assertFalse(DasherSplit.pending());
                    assertEquals(1, count(DiagnosticLog.read(app), "screen split by the split pane's"));
                } else {
                    activity.pause();
                    DasherSplit.abandon(app, "another app");
                    pass(DasherSplit.VERIFY_MS + 100);
                    assertFalse(cause, DasherSplit.pending());
                    assertFalse(DiagnosticLog.read(app).contains("no split 1500 ms"));
                }
                assertNull(cause + ": nothing more started", next());
                assertTrue(cause + ": no recent apps", globalActions(service).isEmpty());
            } finally {
                service.destroy();
            }
        }
    }

    /**
     * The pane's start made no split (Android opened it full screen, say): recent apps then, once, with the phone's
     * words, where the user puts Dasher's own card in the other half; the split made there opens nothing. The phone is
     * remembered: until a pane splits on it, the next tap opens recent apps at once, starting nothing.
     */
    @Test
    public void thePaneWithoutASplitOpensRecentAppsAndThePhoneIsRemembered() {
        ShadowBuild.setManufacturer("samsung");
        dasherInstalled();
        readingDasher();
        DasherSplit.forget();
        DiagnosticLog.clear(app);
        app.getSharedPreferences(DasherSplit.PANE_PREFS, android.content.Context.MODE_PRIVATE).edit().clear().commit();
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tapSplit(activity);
            assertPane("the tap", next());
            activity.pause();
            pass(DasherSplit.VERIFY_MS - 100);
            assertTrue("not yet", globalActions(service).isEmpty());
            pass(200);
            contains(DiagnosticLog.read(app),
                    "[split] no split 1500 ms after the split pane's launch-adjacent start: opening recent apps");
            assertEquals("recent apps, once", Collections.singletonList(AccessibilityService.GLOBAL_ACTION_RECENTS),
                    globalActions(service));
            assertEquals(DasherSplit.hintWithDasher("samsung"), ShadowToast.getTextOfLatestToast());
            contains(DiagnosticLog.read(app), "[split] recent apps opened, with the samsung hint to pick Dasher's card");
            assertNull(next());
            assertTrue("remembered", DasherSplit.paneNeverSplits(app));

            // The user splits it there 20 s later, Dasher's own card in the other half: nothing is opened into it.
            pass(20_000);
            splitAboveDasher(service.get());
            enterSplit(activity);
            activity.resume();
            idle();
            assertNull("the split made in recent apps opens nothing", next());
            assertFalse(DasherSplit.pending());
            assertFalse("no toast after the split", ShadowToast.showedToast(KEEPING));
            assertEquals(1, globalActions(service).size());
            contains(DiagnosticLog.read(app), "nothing opened (recent apps path)");
        } finally {
            service.destroy();
        }

        // The next tap, full screen again: recent apps at once, no pane, nothing of Dasher's.
        DasherSplit.forget();
        DiagnosticLog.clear(app);
        service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tapSplit(activity);
            assertNull("nothing started", next());
            assertEquals(Collections.singletonList(AccessibilityService.GLOBAL_ACTION_RECENTS), globalActions(service));
            contains(DiagnosticLog.read(app), "[split] tap: this phone has not split for the split pane before: "
                    + DasherSplit.PANE_RECENTS);
        } finally {
            service.destroy();
            app.getSharedPreferences(DasherSplit.PANE_PREFS, android.content.Context.MODE_PRIVATE).edit().clear()
                    .commit();
        }
    }
    /**
     * Paused (no rule set, or auto-decline off), nothing of Dasher's is read (the owner's "Read nothing"), so no read
     * could say that Dasher shows no offer: the tap starts nothing of Dasher's and opens recent apps with the phone's
     * words, where the user splits the screen with Dasher's own card, and the split made there opens nothing. Paused
     * after the tap, before Dasher is up, the pairing is given up and nothing is started beside Dasher.
     */
    @Test
    public void aTapWhilePausedOpensRecentAppsAndStartsNothing() {
        ShadowBuild.setManufacturer("samsung");
        dasherInstalled();
        for (String paused : new String[] {"no rule set", "auto-decline off"}) {
            DasherSplit.forget();
            DiagnosticLog.clear(app);
            FilterStore.save(app, paused.equals("no rule set") ? FilterSettings.of(true, 0, 0, 0, 0)
                    : FilterSettings.of(false, 400, 0, 0, 0));
            ServiceController<OfferFilterService> service = connectedService();
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                tapSplit(activity);
                assertNull(paused + ": nothing of Dasher's started", next());
                assertEquals(paused + ": no split pane", 0, DasherSplit.pairing());
                assertEquals(paused, Collections.singletonList(AccessibilityService.GLOBAL_ACTION_RECENTS),
                        globalActions(service));
                assertEquals(DasherSplit.hintWithDasher("samsung"), ShadowToast.getTextOfLatestToast());
                String log = DiagnosticLog.read(app);
                contains(log, "[split] tap while paused: " + DasherSplit.PAUSED_RECENTS);
                contains(log, "[split] recent apps opened, with the samsung hint to pick Dasher's card");
                assertFalse(log, log.contains("[split] tap: this app's split pane"));

                // The user splits it there, Dasher's own card in the other half: nothing is opened into it.
                activity.pause();
                pass(5_000);
                splitAboveDasher(service.get());
                enterSplit(activity);
                activity.resume();
                idle();
                assertNull(paused + ": nothing is opened into the other half", next());
                assertFalse(paused, DasherSplit.pending());
                assertEquals(paused, 1, globalActions(service).size());
                contains(DiagnosticLog.read(app), "nothing opened (recent apps path)");
            } finally {
                service.destroy();
            }
        }

        // Reading at the tap, paused before the look: nothing more, no recent apps.
        DasherSplit.forget();
        DiagnosticLog.clear(app);
        readingDasher();
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tapSplit(activity);
            assertPane("the tap", next());
            activity.pause();
            pass(300);
            FilterStore.save(app, FilterSettings.of(false, 400, 0, 0, 0));
            pass(DasherSplit.VERIFY_MS + 1_000);
            assertNull("nothing more", next());
            assertFalse(DasherSplit.pending());
            assertTrue("no recent apps", globalActions(service).isEmpty());
            String log = DiagnosticLog.read(app);
            assertEquals(log, 1, count(log, "[split] not paired: " + OfferFilterService.NOT_READING
                    + " (no split after the split pane's start); nothing more opened"));
        } finally {
            service.destroy();
        }
    }
    /**
     * The pane up full screen over the page, not split (a phone may do that): the page paused under it is not the user
     * leaving, so the tap still waits for the look 1.5 s after the pane's start, which opens recent apps with the
     * phone's words; the split made there opens nothing.
     */
    @Test
    public void thePaneFullScreenOverThePageIsNotTheUserComingBack() {
        ShadowBuild.setManufacturer("samsung");
        dasherInstalled();
        readingDasher();
        DasherSplit.forget();
        app.getSharedPreferences(DasherSplit.PANE_PREFS, android.content.Context.MODE_PRIVATE).edit().clear().commit();
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tapSplit(activity);
            Intent pane = next();
            assertPane("the tap", pane);
            activity.pause();
            ActivityController<SplitPaneActivity> up = paneUp(pane, false);
            pass(300);
            String log = DiagnosticLog.read(app);
            assertFalse("not the user back on the page: " + log, log.contains("not paired"));
            assertFalse(log, log.contains("without a split: the tap is over"));
            assertTrue("the tap still waits for its look", DasherSplit.pending());
            assertTrue("not yet", globalActions(service).isEmpty());

            // The look 1.5 s after the start: no split, so recent apps, once, with the phone's words.
            pass(DasherSplit.VERIFY_MS);
            assertEquals(Collections.singletonList(AccessibilityService.GLOBAL_ACTION_RECENTS), globalActions(service));
            assertEquals(DasherSplit.hintWithDasher("samsung"), ShadowToast.getTextOfLatestToast());
            contains(DiagnosticLog.read(app),
                    "[split] no split 1500 ms after the split pane's launch-adjacent start: opening recent apps");
            assertNull(next());
            up.destroy();

            // The user splits it there: nothing is opened into it.
            pass(20_000);
            splitAboveDasher(service.get());
            enterSplit(activity);
            activity.resume();
            idle();
            assertNull("the split made in recent apps opens nothing", next());
            assertFalse(DasherSplit.pending());
            assertEquals(1, globalActions(service).size());
            contains(DiagnosticLog.read(app), "nothing opened (recent apps path)");
        } finally {
            service.destroy();
            app.getSharedPreferences(DasherSplit.PANE_PREFS, android.content.Context.MODE_PRIVATE).edit().clear()
                    .commit();
        }
    }

    /**
     * The look 1.5 s after the pane's start opens recent apps only when nothing would meet them: the phone locked, an
     * offer up, an automatic acceptance just requested, or the notice no longer accepted each leave everything as it
     * is, with one line saying why, and nothing more opens. The phone is not remembered as one that never splits.
     */
    @Test
    public void theLookAfterThePanesStartOpensNothingOverAnOfferOrTheUsersOwnMove() {
        dasherInstalled();
        readingDasher();
        String[][] causes = {
                {"lock", "the screen is off or locked"},
                {"offer", OfferFilterService.SPLIT_OFFER_UP},
                {"automatic acceptance", OfferFilterService.SPLIT_BUSY},
                {"notice", "the notice is not accepted"},
        };
        for (String[] cause : causes) {
            DasherSplit.forget();
            DiagnosticLog.clear(app);
            app.getSharedPreferences(DasherSplit.PANE_PREFS, android.content.Context.MODE_PRIVATE).edit().clear()
                    .commit();
            ServiceController<OfferFilterService> service = connectedService();
            OfferFilterService screen = service.get();
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                tapSplit(activity);
                assertPane(cause[0], next());
                activity.pause();
                switch (cause[0]) {
                    case "lock":
                        Shadows.shadowOf(screen.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
                        break;
                    case "offer":
                        dasherInFront(screen, passingOffer());
                        dasherWindowChange(screen, DASHER_MAIN, true);
                        break;
                    case "automatic acceptance":
                        ReflectionHelpers.setField(screen, "autoRequestedAt", SystemClock.uptimeMillis());
                        break;
                    default:
                        ConsentedTestApp.forget(app);
                        break;
                }
                pass(DasherSplit.VERIFY_MS + 100);
                String log = DiagnosticLog.read(app);
                assertEquals(cause[0] + ":\n" + log, 1, count(log, "[split] not paired: " + cause[1]
                        + " (no split after the split pane's start); nothing more opened"));
                assertFalse(cause[0] + ": " + log, log.contains("no split 1500 ms"));
                assertTrue(cause[0] + ": no recent apps", globalActions(service).isEmpty());
                assertNull(cause[0] + ": nothing more started", next());
                assertFalse(cause[0], DasherSplit.pending());
                assertFalse(cause[0], DasherSplit.paneNeverSplits(app));
            } finally {
                Shadows.shadowOf(screen.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
                ConsentedTestApp.accept(app);
                service.destroy();
            }
        }
    }

    /**
     * Dasher not beside 2.5 s after its start from the pane (the phone put it elsewhere): recent apps with the phone's
     * words, so the user can put Dasher's card in the other half; with an automatic acceptance just requested (an offer
     * being answered), nothing opens.
     */
    @Test
    public void dasherNotBesideAfterThePaneOpensRecentAppsUnlessAnOfferIsBeingAnswered() {
        ShadowBuild.setManufacturer("samsung");
        dasherInstalled();
        readingDasher();
        for (boolean offer : new boolean[] {false, true}) {
            DasherSplit.forget();
            DiagnosticLog.clear(app);
            ServiceController<OfferFilterService> service = connectedService();
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                tapSplit(activity);
                Intent pane = next();
                assertPane("the tap", pane);
                enterSplit(activity);
                activity.resume();
                idle();
                ActivityController<SplitPaneActivity> up = paneUp(pane, true);
                assertDashersLauncher("Dasher from the pane", next());
                if (offer) ReflectionHelpers.setField(service.get(), "autoRequestedAt", SystemClock.uptimeMillis());
                // Split beside another app, not Dasher.
                pairedWith(service.get(), app.getPackageName(), "com.example.reader");
                pass(DasherSplit.BESIDE_CHECK_MS + 100);
                String log = DiagnosticLog.read(app);
                if (offer) {
                    assertTrue("no recent apps over the offer", globalActions(service).isEmpty());
                    contains(log, "[split] not paired: " + OfferFilterService.SPLIT_BUSY
                            + " (Dasher not beside after its start from the pane); nothing more opened");
                } else {
                    assertEquals(Collections.singletonList(AccessibilityService.GLOBAL_ACTION_RECENTS),
                            globalActions(service));
                    assertEquals(DasherSplit.hintWithDasher("samsung"), ShadowToast.getTextOfLatestToast());
                    contains(log, "[split] after Dasher's start from the pane: Dasher not beside; opening recent apps");
                }
                assertNull(next());
                up.destroy();
            } finally {
                service.destroy();
            }
        }
    }
    /**
     * The owner's 0.6.1 rule, kept: during a delivery (a stored route, or Dasher's pickup, delivery or turn-by-turn
     * screen read within 10 minutes) nothing of Dasher's is started, from full screen or in split screen: recent apps
     * open with the phone's words, and the split made there opens nothing and says nothing (the 11:13:34 report's
     * "Keeping this split" toast is gone).
     */
    @Test
    public void duringADeliveryTheTapAndSwapOpenRecentAppsAndNothingIsStarted() {
        ShadowBuild.setManufacturer("samsung");
        dasherInstalled();
        for (String delivery : new String[] {"a stored route", "a delivery screen"}) {
            DasherSplit.forget();
            DiagnosticLog.clear(app);
            ActiveRouteStore.clear(app);
            ServiceController<OfferFilterService> service = connectedService();
            if (delivery.equals("a stored route")) ActiveRouteStore.save(app, new OfferSnapshot(2500, 7.2, 21, 2));
            else OfferFilterService.deliveryScreenSeen(SystemClock.uptimeMillis());
            assertTrue(delivery, OfferFilterService.dasherOnDelivery(app));
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                tapSplit(activity);
                assertNull(delivery + ": nothing of Dasher's started", next());
                assertEquals(delivery, Collections.singletonList(AccessibilityService.GLOBAL_ACTION_RECENTS),
                        globalActions(service));
                assertEquals(DasherSplit.hintWithDasher("samsung"), ShadowToast.getTextOfLatestToast());
                String log = DiagnosticLog.read(app);
                contains(log, "[split] tap during a delivery: " + DasherSplit.DELIVERY_RECENTS);
                contains(log, "[split] recent apps opened, with the samsung hint to pick Dasher's card");

                // The user splits it there, Dasher's own screen (its navigation and all) in the other half.
                activity.pause();
                pass(5_000);
                splitAboveDasher(service.get());
                enterSplit(activity);
                activity.resume();
                idle();
                assertNull(delivery + ": nothing is opened into the other half", next());
                assertFalse(DasherSplit.pending());
                assertFalse(delivery + ": no toast after the split", ShadowToast.showedToast(KEEPING));
                contains(DiagnosticLog.read(app), "nothing opened (recent apps path)");

                // Split beside a map instead: the header's Swap in Dasher, and the layout's Swap, open recent apps too.
                OfferFilterService.sawDasherBeside(0);
                identifiedPair(service.get(), "com.google.android.apps.maps");
                pass(1_100);
                View swap = shownIcon(content(activity), DasherSplit.SWAP_LABEL);
                assertNotNull(delivery + ": Swap in Dasher", swap);
                swap.performClick();
                idle();
                assertNull(delivery + ": nothing of Dasher's started from this half", next());
                // The layout note's Swap (MainActivity.swapInDasher).
                assertEquals(DasherSplit.hintWithDasher("samsung"), DasherSplit.swapIn(activity.get()));
                assertNull(delivery + ": nor by the layout's Swap", next());
                assertEquals(delivery, Arrays.asList(AccessibilityService.GLOBAL_ACTION_RECENTS,
                        AccessibilityService.GLOBAL_ACTION_RECENTS, AccessibilityService.GLOBAL_ACTION_RECENTS),
                        globalActions(service));
                log = DiagnosticLog.read(app);
                contains(log, "[split] tap in split screen during a delivery: " + DasherSplit.DELIVERY_RECENTS);
                contains(log, "[split] swap during a delivery: " + DasherSplit.DELIVERY_RECENTS);
            } finally {
                service.destroy();
                ActiveRouteStore.clear(app);
                OfferFilterService.forgetScreenState();
            }
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

    // ---- P1: when no split follows, recent apps, in the phone's own words ----

    @Test
    public void onAPixelThePanesStartThatDoesNotSplitOpensRecentAppsWithPixelWords() {
        // A Pixel: Android takes the split pane's start and splits nothing.
        ShadowBuild.setManufacturer("Google");
        dasherInstalled();
        readingDasher();
        DasherSplit.forget();
        app.getSharedPreferences(DasherSplit.PANE_PREFS, android.content.Context.MODE_PRIVATE).edit().clear().commit();
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tapSplit(activity);
            assertPane("the tap", next());
            assertTrue("Android's split action is no longer asked for", globalActions(service).isEmpty());
            activity.pause();

            // 1.5 s on, the screen has not split: recent apps, with the Pixel's own steps. (0.4.45: nothing at all.)
            pass(DasherSplit.VERIFY_MS - 100);
            assertTrue("not yet", globalActions(service).isEmpty());
            pass(200);
            assertEquals(Collections.singletonList(AccessibilityService.GLOBAL_ACTION_RECENTS), globalActions(service));
            assertEquals("Tap Offer Filter's icon above its card → Split screen, then Dasher",
                    ShadowToast.getTextOfLatestToast());
            assertNull(next());
            String log = DiagnosticLog.read(app);
            contains(log, "[split] no split 1500 ms after the split pane's launch-adjacent start: opening recent apps");
            contains(log, "[split] recent apps opened, with the pixel hint");

            // The user splits it there 20 s later with Dasher's own card: nothing is opened into it.
            ShadowSystemClock.advanceBy(Duration.ofSeconds(20));
            identifiedPair(service.get(), "com.example.launcher");
            enterSplit(activity);
            assertNull(next());
            log = DiagnosticLog.read(app);
            contains(log, "[split] entered split screen");
            contains(log, "nothing opened (recent apps path)");
        } finally {
            service.destroy();
        }
    }

    @Test
    public void onASamsungTheWordsNameItsOwnSplitScreenChoice() {
        ShadowBuild.setManufacturer("samsung");
        dasherInstalled();
        DasherSplit.forget();
        DasherSplit.onDelivery = context -> true;
        DasherSplit.recents = () -> true;
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tapSplit(activity);
            String said = ShadowToast.getTextOfLatestToast();
            assertEquals(DasherSplit.hintWithDasher("samsung"), said);
            assertTrue(said, said.contains("Open in split screen view") && said.endsWith("then Dasher"));
            contains(DiagnosticLog.read(app), "[split] recent apps opened, with the samsung hint");
            assertNull(next());
        } finally {
            service.destroy();
        }
    }

    /**
     * The owner's report of 7 October 2026: recent apps opened, the user went back to Offer Filter without a split,
     * and an offer 37 s after the tap got no peek ("a split screen with Dasher is being set up"). Back on the page not
     * split, the tap is over: no split is waited for, and a split made by hand later opens nothing by itself.
     */
    @Test
    public void backOnThePageWithoutASplitTheTapIsOver() {
        ShadowBuild.setManufacturer("samsung");
        dasherInstalled();
        DasherSplit.forget();
        DasherSplit.onDelivery = context -> true;
        DasherSplit.recents = () -> true;
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tapSplit(activity);
            assertTrue("the tap waits for the split", DasherSplit.pending());
            // Recent apps over the page, then back on it, still full screen.
            activity.pause();
            pass(10_000);
            assertTrue("in recent apps the tap still waits", DasherSplit.pending());
            activity.resume();
            idle();
            assertFalse("back on the page without a split, nothing waits", DasherSplit.pending());
            contains(DiagnosticLog.read(app), "[split] back on Offer Filter without a split: the tap is over");
            assertNull("nothing of the tap's", next());
            enterSplit(activity);
            idle();
            assertNull("a split made later opens nothing by itself", next());
        } finally {
            service.destroy();
        }
    }

    // ---- A split made another way, before this app's own start, opens nothing ----

    /**
     * The screen split after the tap, whether the page said so first or Android's list of windows did at the look, is
     * the split pane's split: nothing more is started from the page, the tap no longer waits and recent apps never
     * open. The line says so once.
     */
    @Test
    public void aSplitAfterTheTapIsThePanesAndOpensNoRecentApps() {
        dasherInstalled();
        readingDasher();
        for (boolean pageFirst : new boolean[] {true, false}) {
            DasherSplit.forget();
            DiagnosticLog.clear(app);
            ServiceController<OfferFilterService> service = connectedService();
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                tapSplit(activity);
                assertPane("the tap", next());
                pass(500);
                if (pageFirst) {
                    enterSplit(activity);
                    splitAboveDasher(service.get());
                } else {
                    // Android lists the divider before the page says: the look 1.5 s after the start finds it.
                    splitAboveDasher(service.get());
                    pass(DasherSplit.VERIFY_MS);
                    enterSplit(activity);
                }
                assertNull("split: nothing more from the page", next());
                assertFalse(DasherSplit.pending());
                pass(DasherSplit.PENDING_MS);
                assertNull(next());
                assertTrue("never recent apps", globalActions(service).isEmpty());
                String log = DiagnosticLog.read(app);
                assertEquals(log, 1, count(log, "[split] screen split by the split pane's launch-adjacent start ("));
                assertFalse(log, log.contains("no split 1500 ms"));
                assertFalse(log, log.contains("not paired"));
            } finally {
                service.destroy();
            }
        }
    }

    // ---- P5: split without Dasher beside ----

    @Test public void pendingPairThenMissingResizeMetadataNeverRelaunchesDasher() {
        dasherInstalled();
        readingDasher();
        DasherSplit.forget();
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tapSplit(activity);
            assertPane("the tap", next());
            splitWithDasherBelow(service.get());
            OfferFilterService.lookSoon(null);
            idle();
            assertTrue(OfferFilterService.dasherBeside());
            // Android already paired the existing task. Divider movement can temporarily omit its metadata.
            Shadows.shadowOf(service.get()).setWindows(Collections.emptyList());
            Shadows.shadowOf(service.get()).setRootInActiveWindow(null);
            enterSplit(activity);
            assertNull("unknown is not permission to replace the paired task", next());
            assertFalse(DasherSplit.pending());
            activity.pause().resume();
            enterSplit(activity);
            pass(DasherSplit.VERIFY_MS + 100);
            assertNull("neither resize nor resume retries the launch", next());
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
            assertNull(next());
            assertTrue(globalActions(service).isEmpty());
        } finally { service.destroy(); }
    }

    @Test public void pendingSplitKeepsTheMapPaneDuringAnObservedDash() {
        dasherInstalled(); DasherSplit.forget();
        readingDasher();
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tapSplit(activity);
            assertPane("the tap", next());
            Dashing.seen(app);
            identifiedPair(service.get(), "com.google.android.apps.maps");
            AccessibilityWindowInfo mapPane = service.get().getWindows().get(2);
            enterSplit(activity);
            assertNull("a pending setup must not replace the user's map pane", next());
            assertTrue(service.get().getWindows().contains(mapPane));
            assertFalse(DasherSplit.pending());
            // A new explicit handoff remains available. Its ordinary task launch requests no adjacent replacement.
            DasherSplit.swapIn(activity.get());
            Intent explicit = next();
            assertDashersLauncher("Swap in Dasher", explicit);
        } finally { service.destroy(); }
    }

    @Test public void visibleMapIsPreservedWithoutClaimingItsRouteIsActive() {
        dasherInstalled(); DasherSplit.forget();
        readingDasher();
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tapSplit(activity);
            assertPane("the tap", next());
            identifiedPair(service.get(), "com.waze");
            assertFalse("no observed dash or route is inferred from a visible map", Dashing.now(app));
            enterSplit(activity);
            assertNull("keep the known map pane without sending any destination or launch", next());
            assertFalse(DasherSplit.pending());
        } finally { service.destroy(); }
    }

    @Test public void aPendingRequestCannotReplaceSomeoneElsesCurrentSplit() {
        dasherInstalled(); DasherSplit.forget();
        readingDasher();
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tapSplit(activity);
            assertPane("the tap", next());
            identifiedPair(service.get(), "com.example.reader", "com.example.notes");
            enterSplit(activity);
            assertNull("a resumed Activity is not proof our pane is in the current pair", next());
            assertFalse(DasherSplit.pending());
        } finally { service.destroy(); }
    }

    @Test
    public void splitWithoutDasherBesideTheButtonSwapsDasherIntoThisHalf() {
        dasherInstalled();
        DasherSplit.forget();
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = content(activity);
            // Split with another app (Dasher's navigation opened Maps in its half, say): 0.4.45 hid the button.
            OfferFilterService.sawDasherBeside(0);
            enterSplit(activity);
            pass(1_100);
            assertNull(shownIcon(content, DasherSplit.SPLIT_LABEL));
            View swap = shownIcon(content, DasherSplit.SWAP_LABEL);
            assertNotNull("Swap in Dasher, dash or not (Put Dasher beside is retired)", swap);

            swap.performClick();
            assertDashersLauncher("Dasher into this half, the other half kept", next());
            assertTrue("no split request: the screen is split already", globalActions(service).isEmpty());
            contains(DiagnosticLog.read(app), "[split] tap in split screen: Dasher's launch intent into this half (the "
                    + "other half stays)");

            // Dasher is seen beside: nothing to swap in.
            OfferFilterService.sawDasherBeside(SystemClock.uptimeMillis());
            pass(1_100);
            assertNull(shownIcon(content, DasherSplit.SWAP_LABEL));
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
            assertNull("so the header offers no Swap in Dasher", shownIcon(content, DasherSplit.SWAP_LABEL));
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

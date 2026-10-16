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
 * real screen-reading service, Android simulated. From 0.6.7, as in 0.6.2, a tap from full screen starts Dasher's
 * launcher beside the page, launch-adjacent (during a delivery, or while auto-decline is paused, recent apps instead);
 * with no split 1.5 s later, recent apps open with the phone's words. That tap's start is the one start of Dasher that
 * asks for the other half (the owner, 13 October 2026, of 0.6.4's split pane: "it's still worse than before we tried
 * to fix split"). Each test fails on 0.4.45, on 0.6.2, on 0.6.3 or on 0.6.4:
 * <ul>
 *   <li>0.4.45 took Android's "true" for the split as the split itself, so on a Pixel the button did nothing and said
 *       nothing; and its recent-apps words were the same on every phone;
 *   <li>it logged no step of the split ([split] lines);
 *   <li>it hid the button in split screen, leaving no way back to Dasher's half from the app;
 *   <li>it looked at the windows only at Dasher's events, so coming back to the page beside Dasher showed the map until
 *       Dasher's next event, and a sighting of Dasher beside aged while the screen was off;
 *   <li>0.6.2 started Dasher's launcher launch-adjacent from the tap, and again into a split the user made;
 *   <li>0.6.3 started Dasher full screen and left its own launch-adjacent start to the screen reader, which One UI
 *       never split for;
 *   <li>0.6.4 started this app's own split pane beside the page, which then started Dasher in its half.
 * </ul>
 * Every activity start these tests saw is recorded, and none of Dasher's may carry the launch-adjacent flag but the
 * Split tap's own start ({@link #assertAdjacentDasher}).
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
    /** The Split taps' own starts of Dasher, the only ones that may be launch-adjacent. */
    private final List<Intent> tapStarts = new ArrayList<>();

    @After
    public void forgetSplit() {
        for (Intent left = next(); left != null; left = next()) {
            // Drained: recorded with the rest.
        }
        // No start of Dasher, from any path, is launch-adjacent, but the Split tap's own.
        for (Intent started : everStarted) {
            if (isDashers(started) && !tapStarts.contains(started)) {
                assertEquals(started.toString(), 0, started.getFlags() & Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
            }
        }
        DasherSplit.forget();
        OfferFilterService.sawDasherBeside(0);
        OfferFilterService.forgetScreenState();
    }

    /**
     * Auto-decline on with a $4.00 minimum, so Dasher's screen is read: the look after the tap's start can say no
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

    // ---- The tap: Dasher's launcher beside the page, launch-adjacent, as 0.6.2 started it ----

    /**
     * Dasher's launcher started by the Split tap beside the page: as its icon starts it, plus launch adjacent; nothing
     * cleared. Recorded as the one start of Dasher that may ask for the other half.
     */
    private void assertAdjacentDasher(String why, Intent opened) {
        assertNotNull(why + ": Dasher's launcher is started", opened);
        assertEquals(why, DASHER_HOME, opened.getComponent());
        assertTrue(why, opened.hasCategory(Intent.CATEGORY_LAUNCHER));
        assertEquals(why + ": its launch intent, launched adjacent", Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT, opened.getFlags());
        tapStarts.add(opened);
    }

    /**
     * The owner's report of 13 October 2026 on 0.6.4 ("it's still worse than before we tried to fix split"): 0.6.2's
     * start split his phone in about a quarter of a second at every tap, Dasher's offers read in its half; 0.6.4's pane
     * split, but Dasher took 2 to 3 s to reach its half. The tap starts Dasher's launcher beside the page,
     * launch-adjacent; the phone splits; nothing more opens, never recent apps. The same whether a dash is on.
     */
    @Test
    public void aTapStartsDasherBesideAndTheSplitItMakesOpensNothingMore() {
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
                assertAdjacentDasher(variant, next());
                assertNull(variant + ": nothing else from the page", next());
                assertTrue(variant, DasherSplit.pending());
                // The phone splits the screen: the page above, Dasher below.
                activity.pause();
                pass(200);
                enterSplit(activity);
                splitAboveDasher(service.get());
                activity.resume();
                idle();
                assertFalse(variant + ": the split is made", DasherSplit.pending());
                dasherWindowChange(service.get(), DASHER_MAIN, true);
                pass(DasherSplit.VERIFY_MS + 100);
                assertNull(variant + ": the split opens nothing more", next());
                assertTrue(variant + ": never recent apps, never Android's split action", globalActions(service).isEmpty());
                assertEquals(variant + ": no words", toasts, ShadowToast.shownToastCount());
                String log = DiagnosticLog.read(app);
                contains(log, "[split] tap: Dasher's launch intent beside this screen (launch adjacent); looking again in "
                        + DasherSplit.VERIFY_MS + " ms");
                assertEquals(log, 1, count(log, "[split] split by Dasher's launch-adjacent start ("));
                assertFalse(variant + ": " + log, log.contains("not paired"));
                assertFalse(variant + ": " + log, log.contains("no split 1500 ms"));
                // Android's list of windows 5 and 10 s after Dasher's start, so a report shows whether Dasher drew.
                pass(10_000);
                log = DiagnosticLog.read(app);
                contains(log, "[launch] split + 5 s: win=");
                contains(log, "[launch] split + 10 s: win=");
                assertFalse(variant + ": Dasher drew", log.contains("Dasher still on its launch screen"));
                assertEquals(0, OfferFilterService.launchScreenHeldSince(app));
            } finally {
                service.destroy();
            }
        }
    }

    /**
     * Dasher started beside the page but named only by its launcher activity, its red launch screen (the incident of 10
     * to 12 October): 10 s after its start the launch watch says so once; nothing is started again for it and nothing
     * automatic acts on Dasher, and the line goes once Dasher draws.
     */
    @Test
    public void dasherOnItsLaunchScreenAfterTheTapIsWatchedNeverStartedAgain() {
        dasherInstalled();
        readingDasher();
        DasherSplit.forget();
        DiagnosticLog.clear(app);
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tapSplit(activity);
            assertAdjacentDasher("the tap", next());
            activity.pause();
            enterSplit(activity);
            splitAboveDasher(service.get(), null);
            activity.resume();
            idle();
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
        } finally {
            service.destroy();
        }
    }

    /**
     * A newer tap ends the earlier one with its own line, and only the newer tap's look runs: one start per tap, no
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
                assertAdjacentDasher(cause, next());
                pass(300);
                if (cause.equals("newer tap")) {
                    tapSplit(activity);
                    assertAdjacentDasher("the newer tap", next());
                    String log = DiagnosticLog.read(app);
                    assertEquals(log, 1, count(log, "[split] not paired: a newer tap; nothing more opened"));
                    // The earlier tap's look finds it over; the newer tap's is the one that runs.
                    enterSplit(activity);
                    activity.resume();
                    idle();
                    pass(DasherSplit.VERIFY_MS + 100);
                    assertFalse(DasherSplit.pending());
                    assertEquals(1, count(DiagnosticLog.read(app), "split by Dasher's launch-adjacent start ("));
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
     * Dasher's launch-adjacent start made no split (the phone opened Dasher full screen, say): recent apps then, once,
     * 1.5 s after the tap, with the phone's words, where the user puts Dasher's own card in the other half; the split
     * made there opens nothing. Nothing is remembered: the next tap starts Dasher beside the page again.
     */
    @Test
    public void anAdjacentStartThatDoesNotSplitOpensRecentAppsWithThePhonesWords() {
        ShadowBuild.setManufacturer("samsung");
        dasherInstalled();
        readingDasher();
        DasherSplit.forget();
        DiagnosticLog.clear(app);
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tapSplit(activity);
            assertAdjacentDasher("the tap", next());
            activity.pause();
            pass(DasherSplit.VERIFY_MS - 100);
            assertTrue("not yet", globalActions(service).isEmpty());
            pass(200);
            contains(DiagnosticLog.read(app),
                    "[split] no split 1500 ms after Dasher's launch-adjacent start: opening recent apps");
            assertEquals("recent apps, once", Collections.singletonList(AccessibilityService.GLOBAL_ACTION_RECENTS),
                    globalActions(service));
            assertEquals(DasherSplit.hintWithDasher("samsung"), ShadowToast.getTextOfLatestToast());
            contains(DiagnosticLog.read(app), "[split] recent apps opened, with the samsung hint to pick Dasher's card");
            assertNull(next());

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

        // The next tap, full screen again: Dasher beside the page again, nothing remembered.
        DasherSplit.forget();
        DiagnosticLog.clear(app);
        service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tapSplit(activity);
            assertAdjacentDasher("the next tap", next());
            assertTrue(globalActions(service).isEmpty());
        } finally {
            service.destroy();
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
                assertEquals(paused + ": no start beside the page", 0, DasherSplit.pairing());
                assertEquals(paused, Collections.singletonList(AccessibilityService.GLOBAL_ACTION_RECENTS),
                        globalActions(service));
                assertEquals(DasherSplit.hintWithDasher("samsung"), ShadowToast.getTextOfLatestToast());
                String log = DiagnosticLog.read(app);
                contains(log, "[split] tap while paused: " + DasherSplit.PAUSED_RECENTS);
                contains(log, "[split] recent apps opened, with the samsung hint to pick Dasher's card");
                assertFalse(log, log.contains("[split] tap: Dasher's launch intent beside"));

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
            assertAdjacentDasher("the tap", next());
            activity.pause();
            pass(300);
            FilterStore.save(app, FilterSettings.of(false, 400, 0, 0, 0));
            pass(DasherSplit.VERIFY_MS + 1_000);
            assertNull("nothing more", next());
            assertFalse(DasherSplit.pending());
            assertTrue("no recent apps", globalActions(service).isEmpty());
            String log = DiagnosticLog.read(app);
            assertEquals(log, 1, count(log, "[split] not paired: " + OfferFilterService.NOT_READING
                    + " (no split after Dasher's launch-adjacent start); nothing more opened"));
        } finally {
            service.destroy();
        }
    }
    /**
     * Dasher up full screen over the page, not split (a phone may do that): the page paused under it is not the user
     * leaving, so the tap still waits for the look 1.5 s after the start, which opens recent apps with the phone's
     * words.
     */
    @Test
    public void dasherFullScreenOverThePageIsNotTheUserComingBack() {
        ShadowBuild.setManufacturer("samsung");
        dasherInstalled();
        readingDasher();
        DasherSplit.forget();
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tapSplit(activity);
            assertAdjacentDasher("the tap", next());
            activity.pause();
            dasherInFront(service.get(), root(DASHER, "Finding offers"));
            dasherWindowChange(service.get(), DASHER_MAIN, true);
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
                    "[split] no split 1500 ms after Dasher's launch-adjacent start: opening recent apps");
            assertNull(next());
        } finally {
            service.destroy();
        }
    }

    /**
     * The look 1.5 s after the tap's start opens recent apps only when nothing would meet them: the phone locked, an
     * offer up, an automatic acceptance just requested, or the notice no longer accepted each leave everything as it
     * is, with one line saying why, and nothing more opens.
     */
    @Test
    public void theLookAfterTheTapsStartOpensNothingOverAnOfferOrTheUsersOwnMove() {
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
            ServiceController<OfferFilterService> service = connectedService();
            OfferFilterService screen = service.get();
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                tapSplit(activity);
                assertAdjacentDasher(cause[0], next());
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
                        + " (no split after Dasher's launch-adjacent start); nothing more opened"));
                assertFalse(cause[0] + ": " + log, log.contains("no split 1500 ms"));
                assertTrue(cause[0] + ": no recent apps", globalActions(service).isEmpty());
                assertNull(cause[0] + ": nothing more started", next());
                assertFalse(cause[0], DasherSplit.pending());
            } finally {
                Shadows.shadowOf(screen.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
                ConsentedTestApp.accept(app);
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
    public void onAPixelAnAdjacentStartThatDoesNotSplitOpensRecentAppsWithPixelWords() {
        // A Pixel: Android takes Dasher's launch-adjacent start and splits nothing.
        ShadowBuild.setManufacturer("Google");
        dasherInstalled();
        readingDasher();
        DasherSplit.forget();
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tapSplit(activity);
            assertAdjacentDasher("the tap", next());
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
            contains(log, "[split] no split 1500 ms after Dasher's launch-adjacent start: opening recent apps");
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

    // ---- A split after the tap's start opens nothing more ----

    /**
     * The screen split after the tap, whether the page said so first or Android's list of windows did at the look, is
     * the split Dasher's launch-adjacent start made: nothing more is started from the page, the tap no longer waits and recent apps never
     * open. The line says so once.
     */
    @Test
    public void aSplitAfterTheTapIsTheStartsAndOpensNoRecentApps() {
        dasherInstalled();
        readingDasher();
        for (boolean pageFirst : new boolean[] {true, false}) {
            DasherSplit.forget();
            DiagnosticLog.clear(app);
            ServiceController<OfferFilterService> service = connectedService();
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                tapSplit(activity);
                assertAdjacentDasher("the tap", next());
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
                assertEquals(log, 1, count(log, "[split] split by Dasher's launch-adjacent start ("));
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
            assertAdjacentDasher("the tap", next());
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
            assertAdjacentDasher("the tap", next());
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
            assertAdjacentDasher("the tap", next());
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
            assertAdjacentDasher("the tap", next());
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
        // The map shows once location is allowed (0.6.26).
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION);
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

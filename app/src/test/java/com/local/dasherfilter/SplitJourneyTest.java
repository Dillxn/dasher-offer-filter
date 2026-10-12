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
 * real screen-reading service, Android simulated. From 0.6.3 a tap from full screen goes "Dasher first" (during a
 * delivery, or while auto-decline is paused, recent apps instead): Dasher's launcher as its home-screen icon starts it,
 * then, once Dasher is up past its launch screen with nothing of an offer up, this app's own task beside it, once,
 * launch-adjacent; no start of Dasher ever asks for the other half (the owner's "dd is stuck again" reports followed
 * Split taps that started Dasher's launcher launch-adjacent). Each test fails on 0.4.45 or on 0.6.2:
 * <ul>
 *   <li>0.4.45 took Android's "true" for the split as the split itself, so on a Pixel the button did nothing and said
 *       nothing; and its recent-apps words were the same on every phone;
 *   <li>it logged no step of the split ([split] lines);
 *   <li>it hid the button in split screen, leaving no way back to Dasher's half from the app;
 *   <li>it looked at the windows only at Dasher's events, so coming back to the page beside Dasher showed the map until
 *       Dasher's next event, and a sighting of Dasher beside aged while the screen was off;
 *   <li>0.6.2 started Dasher's launcher launch-adjacent from the tap, and again into a split the user made.
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
        OfferFilterService.sawDasherBeside(0);
        OfferFilterService.forgetScreenState();
    }

    /**
     * Auto-decline on with a $4.00 minimum, so Dasher's screen is read: Dasher first waits for a read to say no offer
     * is up. Paused (no rule, the default, or auto-decline off) nothing of Dasher's is read, and the tap opens recent
     * apps instead ({@link #aTapWhilePausedOpensRecentAppsAndStartsNothing}).
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

    /** This app's own task started beside Dasher: exactly a new task launched adjacent, nothing reset or cleared. */
    private void assertOwnBeside(String why, Intent opened) {
        assertNotNull(why + ": this app's own start beside Dasher", opened);
        assertNotNull(why, opened.getComponent());
        assertTrue(why + ": " + opened.getComponent(), LauncherAppearance.ourHome(app, opened.getComponent()));
        assertEquals(why + ": exactly a new task, launched adjacent",
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT, opened.getFlags());
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
        AccessibilityNodeInfo ours = root(app.getPackageName(), null);
        Shadows.shadowOf(service).setWindows(Arrays.asList(
                window(OURS_WINDOW, AccessibilityWindowInfo.TYPE_APPLICATION, ours, true, TOP_HALF),
                window(DIVIDER_WINDOW, AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, null, false, DIVIDER),
                window(DASHER_WINDOW, AccessibilityWindowInfo.TYPE_APPLICATION, root(DASHER, "Finding offers"),
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

    // ---- Dasher first: one tap, Dasher's launcher, then this app's own task beside it ----

    /**
     * The owner's reports of 10, 11 and 12 October 2026: after Split taps that started Dasher's launcher beside this
     * app (launch adjacent), Dasher's half stayed on its red launch screen, with a dash on or not (why is not
     * established). The tap now starts Dasher's launcher full screen, as its home-screen icon does, and once Dasher is
     * up past its launch screen the screen reader starts this app's own task beside it, once; the split that makes
     * opens nothing more. The same whether a dash is on, and whether Dasher's window was known before.
     */
    @Test
    public void dasherFirstSplitsWithOneTapWithAndWithoutADash() {
        ShadowBuild.setManufacturer("samsung");
        dasherInstalled();
        readingDasher();
        for (String variant : new String[] {"no dash", "Dasher not yet known", "during a dash"}) {
            DasherSplit.forget();
            DiagnosticLog.clear(app);
            boolean dash = variant.equals("during a dash");
            ServiceController<OfferFilterService> service = connectedService();
            if (dash) {
                // During the dash Dasher was in front before: its window is known by the ID its events named.
                Dashing.seen(app);
                dasherInFront(service.get(), root(DASHER, "Finding offers"));
                dasherWindowChange(service.get(), DASHER_MAIN, true);
                Shadows.shadowOf(service.get()).setWindows(Collections.emptyList());
                Shadows.shadowOf(service.get()).setRootInActiveWindow(null);
            }
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                int toasts = ShadowToast.shownToastCount();
                tapSplit(activity);
                assertDashersLauncher(variant, next());
                assertNull(variant + ": nothing else yet", next());
                assertTrue(variant, DasherSplit.pending());
                // Dasher comes up over the page, full screen, past its launch screen.
                activity.pause();
                pass(400);
                assertNull(variant + ": nothing beside Dasher before it is up", next());
                dasherInFront(service.get(), root(DASHER, "Finding offers"));
                dasherWindowChange(service.get(), DASHER_MAIN, !variant.equals("Dasher not yet known"));
                pass(300);
                assertOwnBeside(variant, next());
                assertNull(variant + ": this app's own start, once", next());
                // The phone splits the screen: this app above, Dasher below.
                splitAboveDasher(service.get());
                enterSplit(activity);
                activity.resume();
                idle();
                pass(DasherSplit.VERIFY_MS + 100);
                assertNull(variant + ": the split it made opens nothing more", next());
                assertTrue(variant + ": never recent apps, never Android's split action", globalActions(service).isEmpty());
                assertEquals(variant + ": no words", toasts, ShadowToast.shownToastCount());
                assertFalse(variant, DasherSplit.pending());
                String log = DiagnosticLog.read(app);
                String window = dash ? "known window" : "new window";
                contains(log, "[split] tap: Dasher first, then this app beside it (own tasks ");
                contains(log, "[split] Dasher window: MainActivity (" + window + ")");
                contains(log, "ms after the tap (MainActivity, " + window + "): this app's own launch-adjacent start");
                contains(log, "[split] split beside Dasher by this app's own launch-adjacent start (");
                assertFalse(variant + ": " + log, log.contains("not paired"));
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
     * Dasher named only by its launcher activity, its red launch screen, is never put into a half: the incident
     * itself. 8 s after the tap nothing more is started, and 10 s after Dasher's start the launch watch says so once;
     * nothing automatic acts on Dasher for it, and the line goes once Dasher draws.
     */
    @Test
    public void dasherFirstNeverPairsALauncherOnlyDasher() {
        dasherInstalled();
        readingDasher();
        DasherSplit.forget();
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tapSplit(activity);
            assertDashersLauncher("the tap", next());
            activity.pause();
            pass(500);
            dasherInFront(service.get(), root(DASHER, null));
            dasherWindowChange(service.get(), DASHER_HOME.getClassName(), true);
            pass(DasherSplit.PENDING_MS);
            assertNull("never paired into a half", next());
            assertFalse(DasherSplit.pending());
            String log = DiagnosticLog.read(app);
            contains(log, "[split] Dasher window: Home (new window)");
            contains(log, "[split] not paired: Dasher still on its launch screen; nothing more opened");
            assertFalse(log, log.contains("this app's own launch-adjacent start"));

            pass(OfferFilterService.LAUNCH_VERDICT_MS - DasherSplit.PENDING_MS);
            log = DiagnosticLog.read(app);
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
            contains(log, "(MainActivity)");
            contains(log, "[split] launch-screen line gone: Dasher drew");
            assertNull(next());
        } finally {
            service.destroy();
        }
    }

    /**
     * Nothing of an offer may meet the split: an offer read after Dasher came up holds this app's start until it has
     * gone, and one still up when the tap's 8 s run out is never split at all (Dasher stays full screen).
     */
    @Test
    public void dasherFirstWaitsOutAnOffer() {
        dasherInstalled();
        FilterStore.save(app, FilterSettings.of(true, 400, 0, 0, 0));
        for (boolean offerGoes : new boolean[] {true, false}) {
            DasherSplit.forget();
            DiagnosticLog.clear(app);
            ServiceController<OfferFilterService> service = connectedService();
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                tapSplit(activity);
                assertDashersLauncher("the tap", next());
                activity.pause();
                pass(300);
                dasherInFront(service.get(), passingOffer());
                dasherWindowChange(service.get(), DASHER_MAIN, true);
                pass(2_000);
                assertNull("nothing beside Dasher while its offer is up", next());
                if (offerGoes) {
                    dasherInFront(service.get(), root(DASHER, "Finding offers"));
                    dasherWindowChange(service.get(), DASHER_MAIN, true);
                    pass(1_000);
                    assertOwnBeside("the offer gone", next());
                    assertNull(next());
                    contains(DiagnosticLog.read(app), "ms after the tap (MainActivity, ");
                } else {
                    pass(DasherSplit.PENDING_MS);
                    assertNull("never split while the offer is up", next());
                    assertFalse(DasherSplit.pending());
                    contains(DiagnosticLog.read(app),
                            "[split] not paired: an offer or Dasher's question is up; nothing more opened");
                }
                assertTrue("no recent apps", globalActions(service).isEmpty());
            } finally {
                service.destroy();
            }
        }
    }

    /**
     * The user's own move always wins: their tap on Dasher, a card's tap, the phone locked or its screen off, the page
     * back without a split, another app in front or a newer tap each give the pairing up, with its own line, and
     * nothing more is started (Dasher simply stays full screen).
     */
    @Test
    public void dasherFirstIsGivenUpForTheUsersOwnMoves() {
        dasherInstalled();
        readingDasher();
        String[][] causes = {
                {"click", "your tap on Dasher"},
                {"card", "a card was tapped"},
                {"lock", "the screen is off or locked"},
                {"screen off", "the screen is off or locked"},
                {"page back", "this app came back without a split"},
                {"another app", "another app came to the front"},
                {"newer tap", "a newer tap"},
        };
        for (String[] cause : causes) {
            DasherSplit.forget();
            DiagnosticLog.clear(app);
            ServiceController<OfferFilterService> service = connectedService();
            OfferFilterService screen = service.get();
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                tapSplit(activity);
                assertDashersLauncher(cause[0], next());
                if (!cause[0].equals("newer tap")) activity.pause();
                pass(300);
                switch (cause[0]) {
                    case "click": usersClickOnDasher(screen); break;
                    case "card": OfferFilterService.cardTapped(); idle(); break;
                    case "lock":
                        Shadows.shadowOf(screen.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
                        break;
                    case "screen off":
                        Shadows.shadowOf(screen.getSystemService(PowerManager.class)).setIsInteractive(false);
                        break;
                    case "page back": activity.resume(); idle(); break;
                    case "another app": {
                        AccessibilityNodeInfo reader = root("com.example.reader", "Chapter 1");
                        Shadows.shadowOf(screen).setWindows(Collections.singletonList(window(950,
                                AccessibilityWindowInfo.TYPE_APPLICATION, reader, true, SCREEN)));
                        Shadows.shadowOf(screen).setRootInActiveWindow(reader);
                        break;
                    }
                    default: tapSplit(activity); break;
                }
                pass(300);
                String log = DiagnosticLog.read(app);
                assertEquals(cause[0] + ":\n" + log, 1,
                        count(log, "[split] not paired: " + cause[1] + "; nothing more opened"));
                Shadows.shadowOf(screen.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
                Shadows.shadowOf(screen.getSystemService(PowerManager.class)).setIsInteractive(true);
                if (cause[0].equals("newer tap")) {
                    // The newer tap starts Dasher again and is the one paired, once.
                    assertDashersLauncher("the newer tap", next());
                    activity.pause();
                }
                // Dasher comes up past its launch screen all the same.
                dasherInFront(screen, root(DASHER, "Finding offers"));
                dasherWindowChange(screen, DASHER_MAIN, true);
                pass(1_000);
                if (cause[0].equals("newer tap")) {
                    assertOwnBeside("the newer tap", next());
                } else {
                    assertNull(cause[0] + ": nothing more is started", next());
                    assertFalse(cause[0], DasherSplit.pending());
                }
                assertNull(cause[0] + ": once", next());
                assertTrue(cause[0] + ": no recent apps", globalActions(service).isEmpty());
            } finally {
                service.destroy();
            }
        }
    }

    /**
     * This app's own start beside Dasher made no split (Android opened it full screen, say): recent apps then, once,
     * with the phone's words, where the user puts Dasher's own card in the other half; the split made there opens
     * nothing. A refused start of this app's own opens recent apps the same way.
     */
    @Test
    public void dasherFirstWithoutASplitOpensRecentAppsAndTheSplitThereOpensNothing() {
        ShadowBuild.setManufacturer("samsung");
        dasherInstalled();
        readingDasher();
        // Not refused; refused as a start from the background; refused with an error of Android's own, which must never
        // reach the scanner (it would stop all screen reading).
        RuntimeException[] refusals = {null, new SecurityException("not from the background"),
                new IllegalArgumentException("an error of Android's own")};
        for (RuntimeException refusal : refusals) {
            boolean refused = refusal != null;
            DasherSplit.forget();
            DiagnosticLog.clear(app);
            ServiceController<OfferFilterService> service = connectedService();
            if (refused) {
                service.get().peekStarter = intent -> {
                    throw refusal;
                };
            }
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                tapSplit(activity);
                assertDashersLauncher("the tap", next());
                activity.pause();
                pass(300);
                dasherInFront(service.get(), root(DASHER, "Finding offers"));
                dasherWindowChange(service.get(), DASHER_MAIN, true);
                if (refused) {
                    assertNull("refused: nothing was started", next());
                    contains(DiagnosticLog.read(app),
                            "[split] this app's own launch-adjacent start was refused: opening recent apps ("
                                    + refusal.getClass().getSimpleName() + ")");
                    assertTrue("screen reading goes on", OfferFilterService.isConnected());
                } else {
                    assertOwnBeside("the start", next());
                    pass(DasherSplit.VERIFY_MS - 100);
                    assertTrue("not yet", globalActions(service).isEmpty());
                    pass(200);
                    contains(DiagnosticLog.read(app),
                            "[split] no split 1500 ms after this app's own launch-adjacent start: opening recent apps");
                }
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
                assertEquals(paused + ": no Dasher first", 0, DasherSplit.pairing());
                assertEquals(paused, Collections.singletonList(AccessibilityService.GLOBAL_ACTION_RECENTS),
                        globalActions(service));
                assertEquals(DasherSplit.hintWithDasher("samsung"), ShadowToast.getTextOfLatestToast());
                String log = DiagnosticLog.read(app);
                contains(log, "[split] tap while paused: " + DasherSplit.PAUSED_RECENTS);
                contains(log, "[split] recent apps opened, with the samsung hint to pick Dasher's card");
                assertFalse(log, log.contains("[split] tap: Dasher first"));

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

        // Reading at the tap, paused before Dasher is up: given up, nothing beside Dasher, no recent apps.
        DasherSplit.forget();
        DiagnosticLog.clear(app);
        readingDasher();
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tapSplit(activity);
            assertDashersLauncher("the tap", next());
            activity.pause();
            pass(300);
            FilterStore.save(app, FilterSettings.of(false, 400, 0, 0, 0));
            dasherInFront(service.get(), root(DASHER, "Finding offers"));
            dasherWindowChange(service.get(), DASHER_MAIN, true);
            pass(DasherSplit.VERIFY_MS + 1_000);
            assertNull("nothing beside Dasher", next());
            assertFalse(DasherSplit.pending());
            assertTrue("no recent apps", globalActions(service).isEmpty());
            String log = DiagnosticLog.read(app);
            assertEquals(log, 1, count(log, "[split] not paired: " + OfferFilterService.NOT_READING
                    + "; nothing more opened"));
            assertFalse(log, log.contains("this app's own launch-adjacent start"));
        } finally {
            service.destroy();
        }
    }

    /**
     * This app's own start beside Dasher brought this app's own screen back full screen, not split (a phone may bring
     * the existing task to the front instead): that is not the user coming back without a split, so the tap still
     * waits for the look 1.5 s after the start, which opens recent apps with the phone's words; the split made there
     * opens nothing.
     */
    @Test
    public void ourOwnStartBringingThePageBackFullScreenIsNotTheUserComingBack() {
        ShadowBuild.setManufacturer("samsung");
        dasherInstalled();
        readingDasher();
        DasherSplit.forget();
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tapSplit(activity);
            assertDashersLauncher("the tap", next());
            activity.pause();
            pass(300);
            dasherInFront(service.get(), root(DASHER, "Finding offers"));
            dasherWindowChange(service.get(), DASHER_MAIN, true);
            assertOwnBeside("the start", next());

            // Android brings this app's own task up full screen, over Dasher: the page resumes, not split.
            AccessibilityNodeInfo ours = root(app.getPackageName(), null);
            Shadows.shadowOf(service.get()).setWindows(Collections.singletonList(
                    window(OURS_WINDOW, AccessibilityWindowInfo.TYPE_APPLICATION, ours, true, SCREEN)));
            Shadows.shadowOf(service.get()).setRootInActiveWindow(ours);
            pass(300);
            activity.resume();
            idle();
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
                    "[split] no split 1500 ms after this app's own launch-adjacent start: opening recent apps");
            assertNull(next());

            // The user splits it there: nothing is opened into it.
            activity.pause();
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
        }
    }

    /**
     * The look 1.5 s after this app's own start beside Dasher opens recent apps only when nothing since would meet them:
     * the user's tap on Dasher, the phone locked, another app in front (by that look, or by a peek's), or an offer up
     * each leave everything as it is, with one line saying why, and nothing more opens.
     */
    @Test
    public void theLookAfterThisAppsOwnStartOpensNothingOverTheUsersOwnMoveOrAnOffer() {
        dasherInstalled();
        readingDasher();
        String[][] causes = {
                {"click", "your tap on Dasher"},
                {"lock", "the screen is off or locked"},
                {"another app", "another app came to the front"},
                {"a peek's look", "another app came to the front"},
                {"offer", "an offer or Dasher's question is up"},
        };
        for (String[] cause : causes) {
            DasherSplit.forget();
            DiagnosticLog.clear(app);
            ServiceController<OfferFilterService> service = connectedService();
            OfferFilterService screen = service.get();
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                tapSplit(activity);
                assertDashersLauncher(cause[0], next());
                activity.pause();
                pass(300);
                dasherInFront(screen, root(DASHER, "Finding offers"));
                dasherWindowChange(screen, DASHER_MAIN, true);
                assertOwnBeside(cause[0], next());
                switch (cause[0]) {
                    case "click": usersClickOnDasher(screen); break;
                    case "lock":
                        Shadows.shadowOf(screen.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
                        break;
                    case "offer":
                        dasherInFront(screen, passingOffer());
                        dasherWindowChange(screen, DASHER_MAIN, true);
                        break;
                    default: {
                        AccessibilityNodeInfo reader = root("com.example.reader", "Chapter 1");
                        Shadows.shadowOf(screen).setWindows(Collections.singletonList(window(950,
                                AccessibilityWindowInfo.TYPE_APPLICATION, reader, true, SCREEN)));
                        Shadows.shadowOf(screen).setRootInActiveWindow(reader);
                        // A peek's own look at the windows finds that app in front first (DasherSplit.abandon).
                        if (cause[0].equals("a peek's look")) DasherSplit.abandon(app, "another app");
                        break;
                    }
                }
                pass(DasherSplit.VERIFY_MS + 100);
                String log = DiagnosticLog.read(app);
                assertEquals(cause[0] + ":\n" + log, 1, count(log, "[split] not paired: " + cause[1]
                        + " (no split after this app's own start); nothing more opened"));
                assertFalse(cause[0] + ": " + log, log.contains("no split 1500 ms"));
                assertTrue(cause[0] + ": no recent apps", globalActions(service).isEmpty());
                assertNull(cause[0] + ": nothing more started", next());
                assertFalse(cause[0], DasherSplit.pending());
            } finally {
                Shadows.shadowOf(screen.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
                service.destroy();
            }
        }
    }

    /**
     * The app's own work on an offer waits out the pairing too: an automatic Accept requested a moment ago keeps this
     * app's own start back, and once the tap's 8 s run out the pairing is given up with its line. The notice no longer
     * accepted (a newer one, say) gives it up at its next look.
     */
    @Test
    public void dasherFirstWaitsOutAnAutomaticAcceptanceAndEndsWithoutTheNotice() {
        dasherInstalled();
        readingDasher();
        for (String cause : new String[] {"automatic acceptance", "notice"}) {
            DasherSplit.forget();
            DiagnosticLog.clear(app);
            ServiceController<OfferFilterService> service = connectedService();
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                tapSplit(activity);
                assertDashersLauncher(cause, next());
                activity.pause();
                pass(300);
                if (cause.equals("notice")) {
                    ConsentedTestApp.forget(app);
                } else {
                    // An automatic Accept was requested a moment ago (two minutes of care follow it).
                    ReflectionHelpers.setField(service.get(), "autoRequestedAt", SystemClock.uptimeMillis());
                }
                dasherInFront(service.get(), root(DASHER, "Finding offers"));
                dasherWindowChange(service.get(), DASHER_MAIN, true);
                pass(1_000);
                assertNull(cause + ": nothing beside Dasher", next());
                String line = "[split] not paired: " + (cause.equals("notice") ? "the notice is not accepted"
                        : "a decline, peek or automatic acceptance is under way") + "; nothing more opened";
                if (!cause.equals("notice")) {
                    assertTrue("still waiting", DasherSplit.pending());
                    assertFalse(DiagnosticLog.read(app).contains(line));
                    pass(DasherSplit.PENDING_MS);
                    assertNull("never beside Dasher", next());
                }
                assertFalse(cause, DasherSplit.pending());
                String log = DiagnosticLog.read(app);
                assertEquals(cause + ":\n" + log, 1, count(log, line));
                assertTrue(cause + ": no recent apps", globalActions(service).isEmpty());
            } finally {
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
    public void onAPixelOurOwnStartThatDoesNotSplitOpensRecentAppsWithPixelWords() {
        // A Pixel: Android takes this app's own start beside Dasher and splits nothing.
        ShadowBuild.setManufacturer("Google");
        dasherInstalled();
        readingDasher();
        DasherSplit.forget();
        ServiceController<OfferFilterService> service = connectedService();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tapSplit(activity);
            assertDashersLauncher("the tap", next());
            assertTrue("Android's split action is no longer asked for", globalActions(service).isEmpty());
            activity.pause();
            dasherInFront(service.get(), root(DASHER, "Finding offers"));
            dasherWindowChange(service.get(), DASHER_MAIN, true);
            assertOwnBeside("the start", next());

            // 1.5 s on, the screen has not split: recent apps, with the Pixel's own steps. (0.4.45: nothing at all.)
            pass(DasherSplit.VERIFY_MS - 100);
            assertTrue("not yet", globalActions(service).isEmpty());
            pass(200);
            assertEquals(Collections.singletonList(AccessibilityService.GLOBAL_ACTION_RECENTS), globalActions(service));
            assertEquals("Tap Offer Filter's icon above its card → Split screen, then Dasher",
                    ShadowToast.getTextOfLatestToast());
            assertNull(next());
            String log = DiagnosticLog.read(app);
            contains(log, "[split] no split 1500 ms after this app's own launch-adjacent start: opening recent apps");
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
     * A split made before this app's own start beside Dasher (the user split it, or Android paired Dasher by itself)
     * is the split: nothing is started into it, the tap no longer waits and recent apps never open, whether the page
     * said so first or Android's list of windows did. Dasher is never started again for it.
     */
    @Test
    public void aSplitBeforeThisAppsOwnStartOpensNothingAndNoRecentApps() {
        dasherInstalled();
        readingDasher();
        for (boolean pageFirst : new boolean[] {true, false}) {
            DasherSplit.forget();
            DiagnosticLog.clear(app);
            ServiceController<OfferFilterService> service = connectedService();
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                tapSplit(activity);
                assertDashersLauncher("the tap", next());
                pass(500);
                if (pageFirst) {
                    enterSplit(activity);
                    splitAboveDasher(service.get());
                } else {
                    // Android lists the divider first: the pairing's own look gives the tap up.
                    splitAboveDasher(service.get());
                    pass(300);
                    enterSplit(activity);
                }
                assertNull("already split: nothing is opened into it", next());
                assertFalse(DasherSplit.pending());
                pass(DasherSplit.PENDING_MS);
                assertNull(next());
                assertTrue("never recent apps", globalActions(service).isEmpty());
                String log = DiagnosticLog.read(app);
                contains(log, pageFirst ? "after the tap, before this app's own start: Dasher beside "
                        : "[split] not paired: the screen split before this app's own start; nothing more opened");
                assertFalse(log, log.contains("): this app's own launch-adjacent start"));
                assertFalse(log, log.contains("split beside Dasher by"));
                assertFalse(log, log.contains("no split 1500 ms"));
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
            assertDashersLauncher("the tap", next());
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
            assertDashersLauncher("the tap", next());
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
            assertDashersLauncher("the tap", next());
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
            assertDashersLauncher("the tap", next());
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

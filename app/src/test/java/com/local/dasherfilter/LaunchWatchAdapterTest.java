package com.local.dasherfilter;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.robolectric.shadows.ShadowSystemClock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The launch watch (0.6.3): after any start of Dasher this app makes (the Split button's, Swap's, Open Dasher's, a
 * card's and its fallback, a peek's, Dasher's own notification tap), Dasher's window changes are named by their short
 * class, with whether the window was new since the start, and Android's list of windows is logged 5 and 10 s after it,
 * so a report shows whether Dasher drew. Dasher named only by its launcher activity 10 s after the start, with no
 * screen of its recognised, is logged once as still on its launch screen and the homepage's line asks the user;
 * nothing automatic is done to Dasher for it. The lines say nothing of Dasher's words or its full class names, and the
 * looks ask Dasher for nothing (no root is fetched for them). What is wrong on the phone when Dasher stays on its launch
 * screen is not established, and nothing here claims it. Simulated Android only.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public class LaunchWatchAdapterTest extends AndroidAdapterTestBase {
    private static final String DASHER = "com.doordash.driverapp";
    private static final String HOME = DASHER + ".Home";
    private static final String MAIN = DASHER + ".ui.MainActivity";
    private static final int DASHER_WINDOW = 960;
    private static final int SECOND_WINDOW = 961;
    private static final Rect SCREEN = new Rect(0, 0, 1080, 2040);

    @After
    public void forgetLaunches() {
        OfferFilterService.rootFetchForTests = null;
        OfferFilterService.describerForTests = Runnable::run;
        DasherSplit.forget();
        OfferFilterService.forgetScreenState();
    }

    private ServiceController<OfferFilterService> connected() {
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

    private static int count(String text, String part) {
        int found = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) found++;
        return found;
    }

    private static void contains(String text, String part) {
        assertTrue(part + " in:\n" + text, text.contains(part));
    }

    /** The diagnostic log's lines tagged [launch]. */
    private List<String> launchLines() {
        List<String> lines = new ArrayList<>();
        for (String line : DiagnosticLog.read(app).split("\n")) if (line.contains("[launch]")) lines.add(line);
        return lines;
    }

    private AccessibilityNodeInfo dasherScreen(String... words) {
        AccessibilityNodeInfo root = AccessibilityNodeInfo.obtain(new View(app));
        root.setPackageName(DASHER);
        root.setVisibleToUser(true);
        for (String said : words) {
            AccessibilityNodeInfo label = AccessibilityNodeInfo.obtain(new View(app));
            label.setPackageName(DASHER);
            label.setText(said);
            label.setVisibleToUser(true);
            Shadows.shadowOf(root).addChild(label);
        }
        return root;
    }

    private static AccessibilityWindowInfo window(int id, AccessibilityNodeInfo root, boolean active) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setId(id);
        shadow.setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        shadow.setActive(active);
        shadow.setBoundsInScreen(SCREEN);
        if (root != null) {
            shadow.setRoot(root);
            Shadows.shadowOf(root).setAccessibilityWindowInfo(window);
        }
        return window;
    }

    /** Dasher up full screen in front, showing {@code root}. */
    private static void dasherInFront(OfferFilterService service, AccessibilityNodeInfo root) {
        Shadows.shadowOf(service).setWindows(Collections.singletonList(window(DASHER_WINDOW, root, true)));
        Shadows.shadowOf(service).setRootInActiveWindow(root);
    }

    /** Dasher's own window change naming {@code activity}, in that window. */
    private static void dasherWindowChange(OfferFilterService service, String activity, int windowId) {
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName(DASHER);
        event.setClassName(activity);
        event.setEventTime(SystemClock.uptimeMillis());
        ((ShadowAccessibilityRecord) Shadow.extract(event)).setWindowId(windowId);
        service.onAccessibilityEvent(event);
        idle();
    }

    @Test
    public void everyStartOfDashersThatStaysOnItsLauncherGetsOneVerdictAndTwoLooks() {
        dasherInstalled();
        String[] paths = {OfferFilterService.LAUNCH_SPLIT, OfferFilterService.LAUNCH_SWAP,
                OfferFilterService.LAUNCH_OPEN_DASHER, OfferFilterService.LAUNCH_CARD,
                OfferFilterService.LAUNCH_CARD_FALLBACK, OfferFilterService.LAUNCH_PEEK,
                OfferFilterService.LAUNCH_OWN_TAP};
        for (String path : paths) {
            DiagnosticLog.clear(app);
            ServiceController<OfferFilterService> service = connected();
            try {
                assertEquals(path, 0, OfferFilterService.launchScreenHeldSince(app));
                dasherInFront(service.get(), dasherScreen());
                OfferFilterService.launchWatch(path);
                dasherWindowChange(service.get(), HOME, DASHER_WINDOW);
                pass(OfferFilterService.LAUNCH_VERDICT_MS + 100);
                String log = DiagnosticLog.read(app);
                boolean split = path.equals(OfferFilterService.LAUNCH_SPLIT)
                        || path.equals(OfferFilterService.LAUNCH_SWAP)
                        || path.equals(OfferFilterService.LAUNCH_OPEN_DASHER);
                contains(log, (split ? "[split]" : "[peek]") + " Dasher window: Home (new window)");
                contains(log, "[launch] " + path + " + 5 s: win=");
                contains(log, "[launch] " + path + " + 10 s: win=");
                contains(log, "Dasher windows listed 1 (new 1); last Dasher class Home; last read ");
                String verdict = "[launch] Dasher still on its launch screen 10 s after " + path
                        + " (only Home named; no Dasher screen recognised)";
                assertEquals(path + ":\n" + log, 1, count(log, verdict));
                assertNotEquals(path + ": the homepage's line asks", 0, OfferFilterService.launchScreenHeldSince(app));
                assertTrue(path + ": nothing automatic is done to Dasher",
                        Shadows.shadowOf(service.get()).getGlobalActionsPerformed().isEmpty());
                assertNull(path + ": nothing started", Shadows.shadowOf(app).getNextStartedActivity());
                pass(20_000);
                assertEquals(path + ": once", 1, count(DiagnosticLog.read(app), "Dasher still on its launch screen"));
            } finally {
                service.destroy();
                OfferFilterService.forgetLaunchScreen();
            }
        }
    }

    @Test
    public void aStartThatDrawsWithinTenSecondsGetsNoVerdict() {
        dasherInstalled();
        ServiceController<OfferFilterService> service = connected();
        try {
            dasherInFront(service.get(), dasherScreen());
            OfferFilterService.launchWatch(OfferFilterService.LAUNCH_SPLIT);
            dasherWindowChange(service.get(), HOME, DASHER_WINDOW);
            // A cold start: its launch screen first, then Dasher's own screen 4 s later.
            pass(4_000);
            dasherWindowChange(service.get(), MAIN, DASHER_WINDOW);
            pass(OfferFilterService.LAUNCH_VERDICT_MS);
            String log = DiagnosticLog.read(app);
            contains(log, "[split] Dasher window: Home (new window)");
            contains(log, "[split] Dasher window: MainActivity (new window)");
            contains(log, "[launch] split + 10 s: win=");
            contains(log, "last Dasher class MainActivity");
            assertFalse(log, log.contains("Dasher still on its launch screen"));
            assertEquals(0, OfferFilterService.launchScreenHeldSince(app));
        } finally {
            service.destroy();
        }
    }

    @Test
    public void aReadOfDashersScreenAfterTheStartSaysItDrew() {
        dasherInstalled();
        FilterStore.save(app, FilterSettings.of(true, 400, 0, 0, 0));
        ServiceController<OfferFilterService> service = connected();
        try {
            dasherInFront(service.get(), dasherScreen("Finding offers"));
            OfferFilterService.launchWatch(OfferFilterService.LAUNCH_CARD);
            // Named by its launcher only, but its screen was read and recognised: Dasher drew.
            dasherWindowChange(service.get(), HOME, DASHER_WINDOW);
            pass(OfferFilterService.LAUNCH_VERDICT_MS + 100);
            String log = DiagnosticLog.read(app);
            contains(log, "[launch] card + 10 s: win=");
            contains(log, "last Dasher class Home; last read waiting");
            assertFalse(log, log.contains("Dasher still on its launch screen"));
            assertEquals(0, OfferFilterService.launchScreenHeldSince(app));
        } finally {
            service.destroy();
        }
    }

    @Test
    public void eachWindowChangeSaysWhetherItsWindowIsNewSinceTheStart() {
        dasherInstalled();
        ServiceController<OfferFilterService> service = connected();
        try {
            // Dasher's window is known before the start, from its own event.
            dasherInFront(service.get(), dasherScreen());
            dasherWindowChange(service.get(), MAIN, DASHER_WINDOW);
            DiagnosticLog.clear(app);
            OfferFilterService.launchWatch(OfferFilterService.LAUNCH_PEEK);
            Shadows.shadowOf(service.get()).setWindows(Arrays.asList(
                    window(DASHER_WINDOW, dasherScreen(), false), window(SECOND_WINDOW, dasherScreen(), true)));
            dasherWindowChange(service.get(), MAIN, DASHER_WINDOW);
            dasherWindowChange(service.get(), HOME, SECOND_WINDOW);
            pass(5_100);
            String log = DiagnosticLog.read(app);
            contains(log, "[peek] Dasher window: MainActivity (known window)");
            contains(log, "[peek] Dasher window: Home (new window)");
            contains(log, "[launch] peek + 5 s: win=");
            contains(log, "Dasher windows listed 2 (new 1); last Dasher class Home");
        } finally {
            service.destroy();
        }
    }

    /**
     * The looks are Android's list of windows alone: no root of Dasher's (or any app's) is fetched for them, and their
     * lines carry no word of Dasher's screen and no full class name.
     */
    @Test
    public void theLooksAskDasherForNothingAndKeepNoWords() {
        dasherInstalled();
        ServiceController<OfferFilterService> service = connected();
        try {
            dasherInFront(service.get(), dasherScreen("Deliver to Sam Rivera", "1234 Elm Street"));
            OfferFilterService.launchWatch(OfferFilterService.LAUNCH_OPEN_DASHER);
            dasherWindowChange(service.get(), HOME, DASHER_WINDOW);
            AtomicInteger fetched = new AtomicInteger();
            OfferFilterService.rootFetchForTests = prefetch -> fetched.incrementAndGet();
            pass(OfferFilterService.LAUNCH_VERDICT_MS + 100);
            assertEquals("no root asked for by the looks", 0, fetched.get());
            List<String> lines = launchLines();
            assertTrue(lines.toString(), lines.size() >= 3);
            for (String line : lines) {
                assertFalse(line, line.contains("Sam") || line.contains("Rivera") || line.contains("Elm"));
                assertFalse(line, line.contains(DASHER + "."));
            }
            String log = DiagnosticLog.read(app);
            assertFalse(log, log.contains("Sam Rivera") || log.contains("1234 Elm"));
            for (String line : log.split("\n")) {
                if (line.contains("Dasher window:")) assertFalse(line, line.contains(HOME));
            }
        } finally {
            service.destroy();
        }
    }

    /**
     * The starts themselves arm the watch: a card's tap (its screen that never shows), Swap in Dasher and Open Dasher,
     * each through its own code, then Dasher seen only on its launcher, get their own verdict 10 s after the start;
     * nothing more is started for it, and nothing automatic is done to Dasher.
     */
    @Test
    public void eachRealStartOfDashersArmsItsOwnWatch() {
        dasherInstalled();
        String[] sites = {OfferFilterService.LAUNCH_CARD, OfferFilterService.LAUNCH_SWAP,
                OfferFilterService.LAUNCH_OPEN_DASHER};
        for (String site : sites) {
            DiagnosticLog.clear(app);
            ServiceController<OfferFilterService> service = connected();
            try (ActivityController<MainActivity> page = Robolectric.buildActivity(MainActivity.class).setup()) {
                switch (site) {
                    case OfferFilterService.LAUNCH_CARD:
                        Robolectric.buildActivity(OpenDasherActivity.class,
                                new Intent(app, OpenDasherActivity.class)).create();
                        break;
                    case OfferFilterService.LAUNCH_SWAP:
                        assertNull(site, DasherSplit.swapIn(page.get()));
                        break;
                    default:
                        assertNull(site, DasherSplit.openDasher(page.get()));
                        break;
                }
                idle();
                Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
                assertNotNull(site + ": Dasher started", opened);
                assertEquals(site, DASHER, opened.getComponent().getPackageName());
                assertNull(site + ": once", Shadows.shadowOf(app).getNextStartedActivity());
                dasherInFront(service.get(), dasherScreen());
                dasherWindowChange(service.get(), HOME, DASHER_WINDOW);
                pass(OfferFilterService.LAUNCH_VERDICT_MS + 100);
                String log = DiagnosticLog.read(app);
                contains(log, "[launch] " + site + " + 5 s: win=");
                assertEquals(site + ":\n" + log, 1, count(log, "[launch] Dasher still on its launch screen 10 s after "
                        + site + " (only Home named"));
                assertNotEquals(site, 0, OfferFilterService.launchScreenHeldSince(app));
                assertNull(site + ": nothing more started", Shadows.shadowOf(app).getNextStartedActivity());
                assertTrue(site + ": nothing automatic",
                        Shadows.shadowOf(service.get()).getGlobalActionsPerformed().isEmpty());
            } finally {
                service.destroy();
                OfferFilterService.forgetLaunchScreen();
            }
        }
    }

    /**
     * Each start is judged on its own: a card's start of Dasher, then Dasher's own notification tap 3 s later (as a
     * card's watch sends it), Dasher seen only on its launcher after each. The card's verdict still comes 10 s after the
     * card's start (the later start's watch had replaced it), and the tap's 10 s after its own. Dasher past its launcher
     * after the later start speaks for both: no verdict at all.
     */
    @Test
    public void aLaterStartNeverDropsAnEarlierStartsVerdict() {
        dasherInstalled();
        String card = "[launch] Dasher still on its launch screen 10 s after card (only Home named";
        String tap = "[launch] Dasher still on its launch screen 10 s after own tap (only Home named";
        for (boolean drew : new boolean[] {false, true}) {
            DiagnosticLog.clear(app);
            ServiceController<OfferFilterService> service = connected();
            try {
                dasherInFront(service.get(), dasherScreen());
                OfferFilterService.launchWatch(OfferFilterService.LAUNCH_CARD);
                dasherWindowChange(service.get(), HOME, DASHER_WINDOW);
                pass(3_000);
                OfferFilterService.launchWatch(OfferFilterService.LAUNCH_OWN_TAP);
                dasherWindowChange(service.get(), drew ? MAIN : HOME, DASHER_WINDOW);
                pass(OfferFilterService.LAUNCH_VERDICT_MS - 3_000 + 100);
                String log = DiagnosticLog.read(app);
                contains(log, "[launch] card + 10 s: win=");
                assertEquals(drew + ":\n" + log, drew ? 0 : 1, count(log, card));
                assertEquals(drew + ": the tap's 10 s are not up yet", 0, count(log, tap));
                pass(3_000);
                log = DiagnosticLog.read(app);
                contains(log, "[launch] own tap + 10 s: win=");
                assertEquals(drew + ":\n" + log, drew ? 0 : 1, count(log, card));
                assertEquals(drew + ":\n" + log, drew ? 0 : 1, count(log, tap));
                assertEquals(drew + ": the homepage asks only while Dasher stayed on its launcher", drew,
                        OfferFilterService.launchScreenHeldSince(app) == 0);
                assertNull("nothing started for it", Shadows.shadowOf(app).getNextStartedActivity());
            } finally {
                service.destroy();
                OfferFilterService.forgetLaunchScreen();
            }
        }
    }

    /**
     * Dasher's activities are described for a report off the scanner, whose next read may be an offer's: a launch
     * watch's window change only hands the description over (nothing of it is done there), and an activity described
     * within the hour is not handed over again, however often it is seen.
     */
    @Test
    public void dashersActivitiesAreDescribedOffTheScannerAtMostHourly() {
        dasherInstalled();
        List<Runnable> handed = new ArrayList<>();
        OfferFilterService.describerForTests = handed::add;
        ServiceController<OfferFilterService> service = connected();
        try {
            dasherInFront(service.get(), dasherScreen());
            OfferFilterService.launchWatch(OfferFilterService.LAUNCH_OPEN_DASHER);
            dasherWindowChange(service.get(), HOME, DASHER_WINDOW);
            assertEquals("handed to the describer", 1, handed.size());
            assertFalse("nothing described on the scanner", DiagnosticLog.read(app).contains(" launcher Home"));
            // Seen again, and after another start: not handed over again within the hour.
            dasherWindowChange(service.get(), HOME, DASHER_WINDOW);
            OfferFilterService.launchWatch(OfferFilterService.LAUNCH_OPEN_DASHER);
            dasherWindowChange(service.get(), HOME, DASHER_WINDOW);
            assertEquals("once within the hour", 1, handed.size());
            handed.remove(0).run();
            String log = DiagnosticLog.read(app);
            contains(log, "[launch] Dasher ");
            contains(log, " launcher Home");
            // An hour on, a start that sees it again describes it again (a newer Dasher may launch differently).
            ShadowSystemClock.advanceBy(Duration.ofMinutes(61));
            idle();
            OfferFilterService.launchWatch(OfferFilterService.LAUNCH_OPEN_DASHER);
            dasherWindowChange(service.get(), HOME, DASHER_WINDOW);
            assertEquals(1, handed.size());
        } finally {
            service.destroy();
        }
    }
}

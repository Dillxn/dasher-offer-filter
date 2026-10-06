package com.local.dasherfilter;

import android.Manifest;
import android.app.Application;
import android.graphics.Rect;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.robolectric.shadows.ShadowAccessibilityRecord;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.shadows.ShadowSystemClock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Offer Filter never keeps Dasher from drawing or answering the user (the 6 October 2026 field incident: Navigate,
 * completing deliveries and ending the dash stopped working while the screen reader ran). Every node read is a call
 * Dasher's own UI thread must serve; here Dasher's map and delivery screens change 60 times a second for a minute and
 * each node fetched costs Dasher 0.2 ms on a fake clock (the service reads on the main looper). The read budget, map
 * pruning, the paused safe mode, delivery calm and the watchdog keep that cost small, while an offer's sheet is still
 * read and declined at once. Each scenario prints a "MEASURE" line: the numbers the change reports.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class NeverStarveStressTest {
    private static final Rect SCREEN = new Rect(0, 0, 1080, 2040);
    private static final int WINDOW = 41;
    private static final long MINUTE = 60_000;

    private Application app;
    private ServiceController<OfferFilterService> controller;
    /** Children fetched from Dasher, Dasher's (simulated) time serving them, and those of the map node. */
    private final AtomicLong fetches = new AtomicLong();
    private final AtomicLong busyMs = new AtomicLong();
    private final AtomicLong mapFetches = new AtomicLong();
    private AccessibilityNodeInfo mapNode;
    private AccessibilityNodeInfo accept;
    private AccessibilityNodeInfo decline;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        Updater.setEnabled(app, false);
        OfferAlerts.ensureChannel(app);
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        DiagnosticLog.clear(app);
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        AreaMap.forgetCache();
        OfferSilencer.forgetCache();
        ActiveRouteStore.clear(app);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
    }

    @After
    public void tearDown() {
        if (controller != null) controller.destroy();
        OfferFilterService.scanLooperForTests = null;
        OfferFilterService.nodeFetchForTests = null;
        OfferFilterService.childFetchForTests = null;
        ActiveRouteStore.clear(app);
    }

    // ---- The fixture: the real service, reading on the main looper with simulated time ----

    private OfferFilterService service() {
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        controller = Robolectric.buildService(OfferFilterService.class).create();
        OfferFilterService service = controller.get();
        service.onServiceConnected();
        idle();
        return service;
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void pass(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    /** Each child Dasher serves costs it {@code tenthsOfMs} tenths of a millisecond; the map's are counted apart. */
    private void cost(long tenthsOfMs) {
        fetches.set(0);
        busyMs.set(0);
        mapFetches.set(0);
        long[] owed = {0};
        OfferFilterService.nodeFetchForTests = () -> {
            fetches.incrementAndGet();
            owed[0] += tenthsOfMs;
            long ms = owed[0] / 10;
            if (ms > 0) {
                owed[0] -= ms * 10;
                busyMs.addAndGet(ms);
                ShadowSystemClock.advanceBy(Duration.ofMillis(ms));
            }
        };
        OfferFilterService.childFetchForTests = parent -> {
            if (mapNode != null && parent.equals(mapNode)) mapFetches.incrementAndGet();
        };
    }

    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(new View(app));
        node.setPackageName("com.doordash.driverapp");
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

    private static void add(AccessibilityNodeInfo parent, AccessibilityNodeInfo... children) {
        for (AccessibilityNodeInfo child : children) Shadows.shadowOf(parent).addChild(child);
    }

    /** Google's map view: its own node and {@code markers} children, none of them an offer's. */
    private AccessibilityNodeInfo map(int markers) {
        AccessibilityNodeInfo map = node(null, false);
        map.setClassName("com.google.android.gms.maps.MapView");
        map.setContentDescription("Google Map");
        for (int i = 0; i < markers; i++) add(map, node(null, false));
        mapNode = map;
        return map;
    }

    /** Dasher's wait for offers over its map: 4000 nodes, 3900 of them the map's. */
    private AccessibilityNodeInfo mapScreen() {
        AccessibilityNodeInfo root = node(null, false);
        add(root, node("Finding offers", false), map(3900));
        AccessibilityNodeInfo panel = node(null, false);
        add(panel, node("Zone offer wait", false), node("Dash along the way", false));
        for (int i = 0; i < 94; i++) add(panel, node(null, false));
        add(root, panel);
        return root;
    }

    /** An offer's sheet: pay, its route, Accept and Decline. */
    private AccessibilityNodeInfo sheet(String pay) {
        AccessibilityNodeInfo sheet = node(null, false);
        accept = node("Accept", true);
        decline = node("Decline", true);
        add(sheet, node(pay, false), node("2 stops (7.2 mi) • 21 min", false), accept, decline);
        return sheet;
    }

    /** A delivery under way: its route markers, its own travel time and distance, and its map. */
    private AccessibilityNodeInfo deliveryScreen() {
        AccessibilityNodeInfo root = node(null, false);
        add(root, node("Deliver by 7:45 PM", false), node("Complete delivery steps", false),
                node("Directions", true), node("12 min", false), node("3.4 mi", false), map(500));
        return root;
    }

    private AccessibilityNodeInfo navigationScreen() {
        AccessibilityNodeInfo root = node(null, false);
        add(root, node("Turn left", false), node("0.3 mi", false), node("25 mph", false), map(500));
        return root;
    }

    private AccessibilityNodeInfo smallIdle() {
        AccessibilityNodeInfo root = node(null, false);
        add(root, node("Finding offers", false));
        for (int i = 0; i < 8; i++) add(root, node(null, false));
        return root;
    }

    /** Dasher fills the screen in its window {@code id}, showing {@code root}; no event is sent. */
    private void dasherShows(OfferFilterService service, AccessibilityNodeInfo root, int id) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setId(id);
        shadow.setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        shadow.setRoot(root);
        shadow.setActive(true);
        shadow.setBoundsInScreen(SCREEN);
        Shadows.shadowOf(root).setAccessibilityWindowInfo(window);
        Shadows.shadowOf(service).setWindows(Collections.singletonList(window));
        Shadows.shadowOf(service).setRootInActiveWindow(root);
    }

    private static AccessibilityEvent event(int type, int window, String text) {
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setPackageName("com.doordash.driverapp");
        if (text != null) event.getText().add(text);
        ((ShadowAccessibilityRecord) Shadow.extract(event)).setWindowId(window);
        return event;
    }

    /** Dasher shows {@code root} and says so with a window change. */
    private void show(OfferFilterService service, AccessibilityNodeInfo root) {
        dasherShows(service, root, WINDOW);
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, WINDOW, null));
        idle();
    }

    /** Dasher's screen changes {@code hz} times a second for {@code ms} of simulated time; the events sent. */
    private static int churn(OfferFilterService service, int hz, long ms) {
        long end = SystemClock.uptimeMillis() + ms;
        int sent = 0;
        while (SystemClock.uptimeMillis() < end) {
            service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, WINDOW, null));
            sent++;
            pass(1000L * sent / hz - 1000L * (sent - 1) / hz);
        }
        return sent;
    }

    private void measure(String scenario, OfferFilterService service, int reads, int roots, long started, int events) {
        System.out.println("MEASURE " + scenario + " (SDK " + Build.VERSION.SDK_INT + "): events=" + events
                + " ms=" + (SystemClock.uptimeMillis() - started) + " contentReads=" + (service.contentReads - reads)
                + " rootFetches=" + (service.rootFetches - roots) + " childFetches=" + fetches.get()
                + " mapChildFetches=" + mapFetches.get() + " dasherBusyMs=" + busyMs.get());
    }

    private static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }

    /** When the sheet's Decline is tapped, on the simulated clock (-1 until it is). */
    private long[] declineTimes() {
        long[] at = {-1};
        Shadows.shadowOf(decline).setOnPerformActionListener((action, args) -> {
            if (at[0] < 0) at[0] = SystemClock.uptimeMillis();
            return true;
        });
        return at;
    }

    private static String declineLine(String log) {
        Matcher line = Pattern.compile("first-step Decline REQUESTED: [^\\n]*").matcher(log);
        return line.find() ? line.group() : "(none)";
    }

    // ---- (a) A 4000-node screen whose map changes 60 times a second for a minute ----

    @Test
    public void aBigMapChangingSixtyTimesASecondIsReadOnTheBudgetAndItsMapNeverFetched() {
        OfferFilterService service = service();
        show(service, mapScreen());
        cost(2);
        int reads = service.contentReads;
        int roots = service.rootFetches;
        int uninterruptible = service.uninterruptibleRootFetches;
        long started = SystemClock.uptimeMillis();
        int events = churn(service, 60, MINUTE);
        pass(1_000);
        measure("a map screen 4000 nodes 60Hz 60s", service, reads, roots, started, events);

        int read = service.contentReads - reads;
        assertTrue("at most 4 reads a second and a burst of 2: " + read, read <= 4 * 60 + ReadBudget.BURST);
        assertTrue("still read every quarter second: " + read, read >= 4 * 59);
        assertEquals("the map's 3900 children are never fetched", 0, mapFetches.get());
        assertEquals("a root per read, no other look", read, service.rootFetches - roots);
        assertTrue("about 100 nodes a read, not 1500: " + fetches.get(), fetches.get() <= 100L * read);
        assertTrue("Dasher serves the reader under a tenth of the time: " + busyMs.get() + " ms",
                busyMs.get() < MINUTE / 10);
        if (Build.VERSION.SDK_INT >= 33) {
            assertEquals("routine reads never ask for an uninterruptible prefetch", uninterruptible,
                    service.uninterruptibleRootFetches);
        }
        String log = DiagnosticLog.read(app);
        assertEquals(log, 1, count(log, "[screen] map subtree skipped (3900 children) on Dasher's waiting screen"));
        Matcher load = Pattern.compile("\\[screen\\] read load: (\\d+) reads/min \\(fact-free (\\d+)\\), nodes \\d+, "
                + "slowest \\d+ ms, median fetch \\d+ ms, truncated 0, pruned map subtrees (\\d+), yields 0").matcher(log);
        assertTrue(log, load.find());
        assertTrue(load.group(), Integer.parseInt(load.group(1)) <= 4 * 60 + ReadBudget.BURST + 1);
        assertEquals("all of them fact-free", load.group(1), load.group(2));
        assertEquals("one map pruned a read", load.group(1), load.group(3));
        assertFalse("never taken for an offer", log.contains("exceeded safe read limits"));
    }

    // ---- (b) The same screen when an offer's sheet appears ----

    @Test
    public void anOfferSheetOverTheBusyMapIsDeclinedAtOnceByItsWindowChange() {
        OfferFilterService service = service();
        AccessibilityNodeInfo root = mapScreen();
        show(service, root);
        cost(2);
        churn(service, 60, 10_000);
        add(root, sheet("$3.50"));
        dasherShows(service, root, WINDOW);
        long[] declined = declineTimes();
        long at = SystemClock.uptimeMillis();
        long fetched = fetches.get();
        int reads = service.contentReads;
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, WINDOW, null));
        String log = DiagnosticLog.read(app);
        System.out.println("MEASURE b1 sheet by window change (SDK " + Build.VERSION.SDK_INT + "): declined "
                + (declined[0] - at) + " ms after its event, " + (fetches.get() - fetched) + " nodes in "
                + (service.contentReads - reads) + " reads; " + declineLine(log));
        assertTrue("declined", declined[0] >= 0);
        assertEquals("in the one read its window change asked for", 1, service.contentReads - reads);
        assertTrue("a read of the sheet and what is beside the map, not the map: " + (fetches.get() - fetched),
                fetches.get() - fetched <= 110);
        assertTrue(log, declineLine(log).contains("; read after change (waited 0 ms)"));
    }

    @Test
    public void anOfferSheetWhoseEventCarriesItsPayIsReadAtOnceWhateverTheBudget() {
        OfferFilterService service = service();
        AccessibilityNodeInfo root = mapScreen();
        show(service, root);
        cost(2);
        churn(service, 60, 10_000);
        add(root, sheet("$3.50"));
        long[] declined = declineTimes();
        long at = SystemClock.uptimeMillis();
        // A content change alone, but Android delivered the sheet's pay with it: no node of Dasher's asked for that.
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, WINDOW, "$3.50"));
        String log = DiagnosticLog.read(app);
        System.out.println("MEASURE b2 sheet by a content change carrying its pay (SDK " + Build.VERSION.SDK_INT
                + "): declined " + (declined[0] - at) + " ms after its event; " + declineLine(log));
        assertTrue("declined at once", declined[0] >= 0);
        assertTrue(log, declineLine(log).contains("; read after content (waited 0 ms)"));
    }

    @Test
    public void anOfferSheetDrawnByATextlessChangeWaitsOneTokenAtMost() {
        OfferFilterService service = service();
        AccessibilityNodeInfo root = mapScreen();
        show(service, root);
        cost(2);
        churn(service, 60, 10_000);
        add(root, sheet("$3.50"));
        long[] declined = declineTimes();
        long at = SystemClock.uptimeMillis();
        churn(service, 60, 1_000);
        String log = DiagnosticLog.read(app);
        long waited = declined[0] - at;
        System.out.println("MEASURE b3 sheet by a textless content change (SDK " + Build.VERSION.SDK_INT
                + "): declined " + waited + " ms after its event; " + declineLine(log));
        assertTrue("declined", declined[0] >= 0);
        assertTrue("by the next token of four a second, and the read's own cost: " + waited,
                waited <= ReadBudget.TOKEN_MS + 40);
        // Its decline under way, the question that follows is read at once.
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        AccessibilityNodeInfo question = node("Are you sure you want to decline this offer?", false);
        add(question, confirm);
        dasherShows(service, question, WINDOW);
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, WINDOW, null));
        assertEquals(1, Shadows.shadowOf(confirm).getPerformedActions().size());
    }

    @Test
    public void aContentChangeFromANewWindowOfDashersIsReadAtOnce() {
        OfferFilterService service = service();
        show(service, mapScreen());
        cost(2);
        churn(service, 60, 5_000);
        // Dasher draws the offer in a window of its own, which announces itself by a content change only.
        AccessibilityNodeInfo popup = node(null, false);
        add(popup, sheet("$3.50"));
        dasherShows(service, popup, 77);
        long[] declined = declineTimes();
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, 77, null));
        assertTrue("a new window of Dasher's is read at once", declined[0] >= 0);
    }

    // ---- (c) Paused: nothing of Dasher's is read ----

    @Test
    public void pausedNothingOfDasherIsReadNotOneNode() {
        FilterStore.save(app, new FilterSettings(false, 2000, 0, 0, 0, 0));
        OfferFilterService service = service();
        cost(2);
        AccessibilityNodeInfo root = mapScreen();
        show(service, root);
        assertTrue("the tab still follows Dasher (Android's list of windows)", OfferFilterService.isDasherForeground());
        int events = churn(service, 60, MINUTE);
        // An offer comes and is clicked; a notification asks for a check: still nothing is read.
        add(root, sheet("$3.50"));
        show(service, root);
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_CLICKED, WINDOW, "Accept"));
        OfferFilterService.requestCheckFromNotification();
        idle();
        pass(5_000);
        System.out.println("MEASURE c paused map 60Hz 60s (SDK " + Build.VERSION.SDK_INT + "): events=" + events
                + " contentReads=" + service.contentReads + " rootFetches=" + service.rootFetches
                + " childFetches=" + fetches.get());
        assertEquals("not one content read", 0, service.contentReads);
        assertEquals("not one root", 0, service.rootFetches);
        assertEquals("not one node", 0, fetches.get());
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        assertTrue("the history records nothing read while paused", DecisionLog.recent(app, 5).isEmpty());
        assertTrue(FilterStore.lastStatus(app), FilterStore.lastStatus(app)
                .endsWith("Paused: " + AppName.NAME + " is not reading Dasher"));
        assertTrue(OfferFilterService.isDasherForeground());
        String log = DiagnosticLog.read(app);
        assertEquals(log, 1, count(log, "[screen] not reading Dasher while paused (auto-decline is off)"));

        // Resumed: the offer is read and declined at once.
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        OfferFilterService.requestCheckFromNotification();
        idle();
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
        assertTrue(DiagnosticLog.read(app).contains("[screen] reading Dasher again"));
    }

    @Test
    public void withNoRuleSetNothingOfDasherIsReadEither() {
        FilterStore.save(app, new FilterSettings(true, 0, 0, 0, 0, 0));
        OfferFilterService service = service();
        cost(2);
        show(service, mapScreen());
        churn(service, 60, 5_000);
        assertEquals(0, service.contentReads);
        assertEquals(0, fetches.get());
        assertTrue(DiagnosticLog.read(app).contains("[screen] not reading Dasher while paused (no rule is set)"));
    }

    // ---- (d) Dasher slow to answer: the watchdog yields ----

    @Test
    public void dasherSlowToAnswerEveryFetchMakesTheReaderYieldLongerWhileItLasts() {
        OfferFilterService service = service();
        show(service, smallIdle());
        cost(3_000);
        int reads = service.contentReads;
        int roots = service.rootFetches;
        long started = SystemClock.uptimeMillis();
        int events = churn(service, 10, MINUTE);
        pass(5_000);
        measure("d slow 300ms fetches 10Hz 60s", service, reads, roots, started, events);
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("[screen] yielding to Dasher: median fetch 300 ms"));
        int read = service.contentReads - reads;
        // Before: one read every 2.85 s (2.7 s each, then the 150 ms gap), Dasher serving the reader 95% of the time.
        assertTrue("reads: " + read, read <= 8);
        assertTrue("Dasher serves the reader a third of the time at most: " + busyMs.get(),
                busyMs.get() <= MINUTE * 35 / 100);
        Matcher load = Pattern.compile("\\[screen\\] read load: \\d+ reads/min \\(fact-free \\d+\\), nodes \\d+, "
                + "slowest \\d+ ms, median fetch 300 ms, truncated 0, pruned map subtrees 0, yields (\\d+)").matcher(log);
        assertTrue(log, load.find());
        assertTrue("yields counted", Integer.parseInt(load.group(1)) >= 1);

        // An offer still gets through at once: a window change is never held back by a pause.
        AccessibilityNodeInfo offer = node(null, false);
        add(offer, sheet("$3.50"));
        cost(0);
        show(service, offer);
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
    }

    // ---- (e) A delivery under way: one read a second at most ----

    @Test
    public void aDeliveryScreenIsReadAtMostOnceASecondAndItsMapNever() {
        OfferFilterService service = service();
        show(service, deliveryScreen());
        cost(2);
        int reads = service.contentReads;
        int roots = service.rootFetches;
        long started = SystemClock.uptimeMillis();
        int events = churn(service, 60, MINUTE);
        measure("e delivery screen 506 nodes 60Hz 60s", service, reads, roots, started, events);
        int read = service.contentReads - reads;
        assertTrue("one a second at most: " + read, read <= 61);
        assertTrue(read >= 59);
        assertEquals(0, mapFetches.get());
        assertTrue(DiagnosticLog.read(app).contains("[screen] map subtree skipped (500 children) on Dasher's delivery screen"));
    }

    @Test
    public void navigationAndAStoredRouteAreCalmToo() {
        OfferFilterService service = service();
        show(service, navigationScreen());
        cost(2);
        int reads = service.contentReads;
        int roots = service.rootFetches;
        long started = SystemClock.uptimeMillis();
        int events = churn(service, 60, MINUTE);
        measure("e2 navigation screen 504 nodes 60Hz 60s", service, reads, roots, started, events);
        assertTrue(service.contentReads - reads <= 61);

        // A route stored (a delivery under way): any screen with nothing of an offer is calm, one Dasher's words do
        // not recognise included (the wait for offers would end the route).
        ActiveRouteStore.save(app, OfferParser.parse(Arrays.asList("$7.00", "2 stops (3.1 mi) • 18 min")));
        AccessibilityNodeInfo unknown = node(null, false);
        add(unknown, node("Heading to store", false), map(500));
        show(service, unknown);
        reads = service.contentReads;
        churn(service, 60, 10_000);
        assertTrue("one a second: " + (service.contentReads - reads), service.contentReads - reads <= 11);
    }

    // ---- Screens too big to read ----

    /** {@code nodes} nodes, none of them a map, with {@code early} among the first labels. */
    private AccessibilityNodeInfo hugeList(int nodes, String early) {
        AccessibilityNodeInfo root = node(null, false);
        if (early != null) add(root, node(early, false));
        AccessibilityNodeInfo list = node(null, false);
        for (int i = 0; i < nodes; i++) add(list, node(null, false));
        add(root, list);
        return root;
    }

    @Test
    public void aScreenTooBigToReadWithNothingOfAnOfferIsReadOnTheBudget() {
        OfferFilterService service = service();
        show(service, hugeList(2_000, "Your dash so far"));
        cost(2);
        int reads = service.contentReads;
        int events = churn(service, 60, 10_000);
        pass(MINUTE);
        int read = service.contentReads - reads;
        System.out.println("MEASURE g truncated fact-free 2000 nodes 60Hz 10s (SDK " + Build.VERSION.SDK_INT
                + "): events=" + events + " contentReads=" + read + " childFetches=" + fetches.get()
                + " dasherBusyMs=" + busyMs.get());
        // Before: each change was read at once, back to back, 1500 nodes each, as a possible offer.
        assertTrue("on the budget, its cost doubling the gap: " + read, read <= 4 * 10 + ReadBudget.BURST);
        assertTrue(FilterStore.lastStatus(app), FilterStore.lastStatus(app)
                .endsWith("Dasher's screen is too big to read in full; no sign of an offer in the part read."));
        assertTrue(DiagnosticLog.read(app), DiagnosticLog.read(app).matches("(?s).*\\[screen\\] read load: \\d+ "
                + "reads/min \\(fact-free \\d+\\), nodes \\d+, slowest \\d+ ms, median fetch \\d+ ms, truncated [1-9]\\d*,.*"));
    }

    @Test
    public void aScreenTooBigToReadShowingASignOfAnOfferIsStillReadAtEveryChange() {
        OfferFilterService service = service();
        show(service, hugeList(2_000, "$7.90"));
        cost(0);
        int reads = service.contentReads;
        for (int i = 0; i < 20; i++) {
            service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, WINDOW, null));
            pass(16);
        }
        assertEquals("a possible offer: every change read at once, as always", 20, service.contentReads - reads);
        assertTrue(FilterStore.lastStatus(app).endsWith("Offer screen exceeded safe read limits; no automatic action."));
    }

    // ---- What a read asks of Dasher ----

    @Test
    public void aMapWhoseOwnWordsShowAnOffersFactsIsReadInFull() {
        OfferFilterService service = service();
        AccessibilityNodeInfo root = node(null, false);
        AccessibilityNodeInfo map = map(30);
        map.setText("3 stops (8.1 mi)");
        add(root, node("Finding offers", false), map);
        cost(0);
        show(service, root);
        assertEquals("its children are read as any", 30, mapFetches.get());
    }

    @Test
    public void onlyAnOffersReadAsksForItsRootWithUninterruptiblePrefetchAndAnotherAppsRootComesAlone() {
        if (Build.VERSION.SDK_INT < 33) return; // Prefetch strategies are Android 13's.
        OfferFilterService service = service();
        show(service, smallIdle());
        int uninterruptible = service.uninterruptibleRootFetches;
        // A routine read of a screen with nothing of an offer: Dasher's UI thread may break off its prefetch.
        pass(1_000);
        int reads = service.contentReads;
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, WINDOW, null));
        assertEquals(reads + 1, service.contentReads);
        assertEquals(uninterruptible, service.uninterruptibleRootFetches);
        // An offer's read: Dasher's nodes come with its root, all at once, as always.
        AccessibilityNodeInfo offer = node(null, false);
        add(offer, sheet("$3.50"));
        show(service, offer);
        assertEquals(uninterruptible + 1, service.uninterruptibleRootFetches);
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());

        // Another app comes to the front: only its root is asked for (whose window it is), never its nodes.
        AccessibilityNodeInfo maps = AccessibilityNodeInfo.obtain(new View(app));
        maps.setPackageName("com.google.android.apps.maps");
        maps.setVisibleToUser(true);
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setId(88);
        shadow.setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        shadow.setRoot(maps);
        shadow.setActive(true);
        shadow.setBoundsInScreen(SCREEN);
        Shadows.shadowOf(maps).setAccessibilityWindowInfo(window);
        Shadows.shadowOf(service).setWindows(Collections.singletonList(window));
        Shadows.shadowOf(service).setRootInActiveWindow(maps);
        int alone = service.identityRootFetches;
        int roots = service.rootFetches;
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED));
        idle();
        assertEquals("the root alone", alone + 1, service.identityRootFetches);
        assertEquals(roots + 1, service.rootFetches);
        assertFalse(OfferFilterService.isDasherForeground());
        // Known by its ID from then on: not asked again.
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED));
        idle();
        assertEquals(roots + 1, service.rootFetches);
    }

    @Test
    public void theWindowWatchOnlyListsWindowsWhileDashersKnownWindowStaysInFront() {
        OfferFilterService service = service();
        show(service, smallIdle());
        int roots = service.rootFetches;
        pass(MINUTE);
        System.out.println("MEASURE f static idle screen, no events, 60s (SDK " + Build.VERSION.SDK_INT
                + "): rootFetches=" + (service.rootFetches - roots));
        assertEquals("before: a root every 500 ms, 120 a minute", roots, service.rootFetches);
        assertTrue(OfferFilterService.isDasherForeground());
    }

    @Test
    public void anOfferNotificationReadsAtOnceAndKeepsTheOldCadenceForAMoment() {
        OfferFilterService service = service();
        show(service, mapScreen());
        cost(0);
        churn(service, 60, 5_000);
        int reads = service.contentReads;
        OfferFilterService.requestCheckFromNotification();
        idle();
        assertEquals("the notification's read, at once", reads + 1, service.contentReads);
        // For the next moments every change is read on the old 150 ms rule, not the budget's 250 ms.
        reads = service.contentReads;
        churn(service, 60, 1_000);
        int read = service.contentReads - reads;
        assertTrue("about one read every 150 ms: " + read, read >= 6);
    }
}

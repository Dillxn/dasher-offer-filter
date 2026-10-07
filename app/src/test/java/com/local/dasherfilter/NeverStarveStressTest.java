package com.local.dasherfilter;

import android.Manifest;
import android.app.Application;
import android.graphics.Rect;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
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
 * Dasher's own UI thread must serve; here Dasher's screens change 60 times a second and each node fetched, roots
 * included, costs Dasher 0.2 ms on a fake clock (the service reads on the main looper). Reads are made cheap, not late:
 * a map's subtree is never walked, figures a screen explains itself are no sign of an offer, an unchanged decided offer
 * gets the quiet gap, paused reads nothing; only a screen too big to read waits on the read budget. An offer's sheet is
 * still read and declined within the 150 ms quiet gap, the only wait before a read that can find a readable offer.
 * Each scenario prints a "MEASURE" line: the numbers the change reports.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class NeverStarveStressTest {
    private static final Rect SCREEN = new Rect(0, 0, 1080, 2040);
    private static final int WINDOW = 41;
    private static final long MINUTE = 60_000;
    /** The quiet gap's cadence at most: one read every 150 ms (and each read's own time on top). */
    private static final int QUIET_READS_A_MINUTE = (int) (MINUTE / OfferFilterService.QUIET_SCAN_GAP_MS) + 2;

    private Application app;
    private ServiceController<OfferFilterService> controller;
    /** Children fetched from Dasher, roots asked for, Dasher's (simulated) time serving both, and the map's children. */
    private final AtomicLong fetches = new AtomicLong();
    private final AtomicLong roots = new AtomicLong();
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
        FilterStore.save(app, FilterSettings.of(true, 2000, 0, 0, 0));
        DiagnosticLog.clear(app);
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        AreaMap.forgetCache();
        OfferSilencer.forgetCache();
        ActiveRouteStore.clear(app);
        OfferFilterService.forgetScreenState();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
    }

    @After
    public void tearDown() {
        if (controller != null) controller.destroy();
        OfferFilterService.scanLooperForTests = null;
        OfferFilterService.nodeFetchForTests = null;
        OfferFilterService.childFetchForTests = null;
        OfferFilterService.rootFetchForTests = null;
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

    /** Each node Dasher serves (a child, or a root) costs it {@code tenthsOfMs} tenths of a millisecond. */
    private void cost(long tenthsOfMs) {
        cost(tenthsOfMs, tenthsOfMs);
    }

    /** As {@link #cost(long)}, with a root costing {@code rootTenths} (a slow root: Dasher answers late). */
    private void cost(long tenthsOfMs, long rootTenths) {
        fetches.set(0);
        roots.set(0);
        busyMs.set(0);
        mapFetches.set(0);
        long[] owed = {0};
        OfferFilterService.nodeFetchForTests = () -> {
            fetches.incrementAndGet();
            charge(owed, tenthsOfMs);
        };
        OfferFilterService.rootFetchForTests = prefetch -> {
            roots.incrementAndGet();
            charge(owed, rootTenths);
        };
        OfferFilterService.childFetchForTests = parent -> {
            if (mapNode != null && parent.equals(mapNode)) mapFetches.incrementAndGet();
        };
    }

    private void charge(long[] owed, long tenths) {
        owed[0] += tenths;
        long ms = owed[0] / 10;
        if (ms > 0) {
            owed[0] -= ms * 10;
            busyMs.addAndGet(ms);
            ShadowSystemClock.advanceBy(Duration.ofMillis(ms));
        }
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

    /** A map view: its own node, as {@code className} and {@code description} name it, and {@code markers} children. */
    private AccessibilityNodeInfo map(String className, String description, int markers) {
        AccessibilityNodeInfo map = node(null, false);
        map.setClassName(className);
        if (description != null) map.setContentDescription(description);
        for (int i = 0; i < markers; i++) add(map, node(null, false));
        mapNode = map;
        return map;
    }

    /** Google's map view as its SDK draws it: its class and its own description, "Google Map". */
    private AccessibilityNodeInfo map(int markers) {
        return map("com.google.android.gms.maps.MapView", "Google Map", markers);
    }

    /** Dasher's labels over {@code map}, and a panel of 94 nodes without words. */
    private AccessibilityNodeInfo screen(AccessibilityNodeInfo map, String... labels) {
        AccessibilityNodeInfo root = node(null, false);
        for (String label : labels) add(root, node(label, label.equals("Directions")));
        if (map != null) add(root, map);
        AccessibilityNodeInfo panel = node(null, false);
        for (int i = 0; i < 94; i++) add(panel, node(null, false));
        add(root, panel);
        return root;
    }

    /** Dasher's wait for offers over its map: 4000 nodes, 3900 of them the map's. */
    private AccessibilityNodeInfo mapScreen() {
        return screen(map(3900), "Finding offers", "Zone offer wait", "Dash along the way");
    }

    /** An offer's sheet: pay, its route, Accept and Decline, and its countdown when given. */
    private AccessibilityNodeInfo sheet(String pay, String countdown) {
        AccessibilityNodeInfo sheet = node(null, false);
        accept = node("Accept", true);
        decline = node("Decline", true);
        add(sheet, node(pay, false), node("2 stops (7.2 mi) • 21 min", false), accept, decline);
        if (countdown != null) add(sheet, node(countdown, false));
        return sheet;
    }

    /** A delivery under way: its route markers, its own travel time and distance, and its map. */
    private AccessibilityNodeInfo deliveryScreen() {
        return screen(map(500), "Deliver by 7:45 PM", "Complete delivery steps", "Directions", "12 min", "3.4 mi");
    }

    private AccessibilityNodeInfo smallIdle() {
        AccessibilityNodeInfo root = node(null, false);
        add(root, node("Finding offers", false));
        for (int i = 0; i < 8; i++) add(root, node(null, false));
        return root;
    }

    /** Dasher fills the screen in its window {@code id}, showing {@code root}; no event is sent. */
    private AccessibilityWindowInfo dasherShows(OfferFilterService service, AccessibilityNodeInfo root, int id) {
        AccessibilityWindowInfo window = window(id, root, true, SCREEN, 1);
        Shadows.shadowOf(service).setWindows(Collections.singletonList(window));
        Shadows.shadowOf(service).setRootInActiveWindow(root);
        return window;
    }

    private static AccessibilityWindowInfo window(int id, AccessibilityNodeInfo root, boolean active, Rect bounds,
                                                  int layer) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setId(id);
        shadow.setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        shadow.setRoot(root);
        shadow.setActive(active);
        shadow.setBoundsInScreen(bounds);
        shadow.setLayer(layer);
        if (root != null) Shadows.shadowOf(root).setAccessibilityWindowInfo(window);
        return window;
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

    /** One minute of a 60 Hz churn over {@code root}: the reads it took, after a MEASURE line. */
    private int minuteOf(OfferFilterService service, AccessibilityNodeInfo root, String scenario) {
        show(service, root);
        cost(2);
        int reads = service.contentReads;
        int rootFetches = service.rootFetches;
        long started = SystemClock.uptimeMillis();
        int events = churn(service, 60, MINUTE);
        measure(scenario, service, reads, rootFetches, started, events);
        return service.contentReads - reads;
    }

    private void measure(String scenario, OfferFilterService service, int reads, int rootFetches, long started,
                         int events) {
        long ms = Math.max(1, SystemClock.uptimeMillis() - started);
        System.out.println("MEASURE " + scenario + " (SDK " + Build.VERSION.SDK_INT + "): events=" + events
                + " ms=" + ms + " contentReads=" + (service.contentReads - reads)
                + " rootFetches=" + (service.rootFetches - rootFetches) + " childFetches=" + fetches.get()
                + " mapChildFetches=" + mapFetches.get() + " dasherBusyMs=" + busyMs.get()
                + String.format(java.util.Locale.US, " (%.1f%%)", 100.0 * busyMs.get() / ms));
    }

    /** Dasher's share of a span spent serving the reader. */
    private double busyShare(long ms) {
        return (double) busyMs.get() / ms;
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

    /** How long the read that declined waited after the change it took in, as its decline line says. */
    private static long waited(String line) {
        Matcher waited = Pattern.compile("\\(waited ([\\d,]+) ms\\)").matcher(line);
        assertTrue(line, waited.find());
        return Long.parseLong(waited.group(1).replace(",", ""));
    }

    /** Content changes at 60 Hz until the sheet's Decline is tapped (5 s at most); the decline line. */
    private String churnUntilDeclined(OfferFilterService service, long[] declined, String scenario) {
        long at = SystemClock.uptimeMillis();
        long end = at + 5_000;
        while (declined[0] < 0 && SystemClock.uptimeMillis() < end) {
            service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, WINDOW, null));
            pass(16);
        }
        String line = declineLine(DiagnosticLog.read(app));
        System.out.println("MEASURE " + scenario + " (SDK " + Build.VERSION.SDK_INT + "): declined "
                + (declined[0] - at) + " ms after the change that drew it; " + line);
        assertTrue(scenario + ": declined", declined[0] >= 0);
        return line;
    }

    /** Whether the tab over Dasher shows and takes touches (it may be held back, not there at all, or a peek). */
    private static boolean tabTakesTouches(OfferFilterService service) {
        DasherTab tab = service.overlay().tab();
        if (tab == null || tab.getVisibility() != View.VISIBLE) return false;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) tab.getLayoutParams();
        return (params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0;
    }

    // ---- (a) A 4000-node screen whose map changes 60 times a second for a minute ----

    @Test
    public void aBigMapChangingSixtyTimesASecondIsReadOnTheQuietGapAndItsMapNeverFetched() {
        OfferFilterService service = service();
        int uninterruptible = service.uninterruptibleRootFetches;
        int read = minuteOf(service, mapScreen(), "a map screen 4000 nodes 60Hz 60s");
        // Before (0.4.72): 190 reads of 1500 nodes each, back to back, 95% of Dasher's time.
        assertTrue("the quiet gap's cadence at most: " + read, read <= QUIET_READS_A_MINUTE);
        assertTrue("still read every moment: " + read, read >= 300);
        assertEquals("the map's 3900 children are never fetched", 0, mapFetches.get());
        assertTrue("about 100 nodes a read, not 1500: " + fetches.get(), fetches.get() <= 100L * read);
        assertTrue("Dasher serves the reader under 15% of the time: " + busyMs.get() + " ms",
                busyShare(MINUTE) < 0.15);
        if (Build.VERSION.SDK_INT >= 33) {
            assertEquals("routine reads never ask for an uninterruptible prefetch", uninterruptible + 1,
                    service.uninterruptibleRootFetches);
        }
        String log = DiagnosticLog.read(app);
        assertEquals(log, 1, count(log, "[screen] map subtree skipped (3900 children) on Dasher's waiting screen"));
        Matcher load = Pattern.compile("\\[screen\\] read load: (\\d+) reads/min \\(fact-free (\\d+)\\), nodes \\d+, "
                + "slowest \\d+ ms, median fetch \\d+ ms, truncated 0, pruned map subtrees (\\d+), yields 0").matcher(log);
        assertTrue(log, load.find());
        assertEquals("all of them fact-free", load.group(1), load.group(2));
        assertEquals("one map pruned a read", load.group(1), load.group(3));
        assertFalse("never taken for an offer", log.contains("exceeded safe read limits"));
    }

    @Test
    public void aMapKnownOnlyByItsGoogleMapDescriptionIsLeftUnreadToo() {
        // On a phone a MapView that does not override its accessibility class reports FrameLayout; Google's SDK still
        // describes it "Google Map".
        OfferFilterService service = service();
        int read = minuteOf(service, screen(map("android.widget.FrameLayout", "Google Map", 3900), "Finding offers"),
                "a2 FrameLayout 'Google Map' 3900 60Hz 60s");
        assertEquals(0, mapFetches.get());
        assertTrue(read <= QUIET_READS_A_MINUTE);
        assertTrue(busyShare(MINUTE) < 0.15);
    }

    @Test
    public void aMapNothingNamesIsTooBigToReadAndWaitsOnTheBudgetAQuarterOfDashersTimeAtMost() {
        // Neither its class nor its description says map: its 3900 children are read until the read stops at 1500.
        OfferFilterService service = service();
        int read = minuteOf(service, screen(map("android.widget.FrameLayout", null, 3900), "Finding offers",
                "Zone offer wait"), "a3 unnamed map 3900 (too big) 60Hz 60s");
        // Before (0.4.72): 190 reads back to back, 95% of Dasher's time; 215122e: 28 reads, 14%.
        assertTrue("a quarter of Dasher's time at most: " + busyMs.get(), busyShare(MINUTE) <= 0.25);
        assertTrue(read >= 10);
        assertTrue(FilterStore.lastStatus(app),
                FilterStore.lastStatus(app).endsWith("Dasher's screen is too big to read in full; no automatic action."));
        assertFalse("an offer may be in the part not read: the tab takes no touches", tabTakesTouches(service));
        assertTrue(DiagnosticLog.read(app).matches("(?s).*\\[screen\\] read load: \\d+ reads/min \\(fact-free \\d+\\), "
                + "nodes \\d+, slowest \\d+ ms, median fetch \\d+ ms, truncated [1-9]\\d*,.*"));
    }

    @Test
    public void aScreenGrownTooBigToReadTakesTheTabsTouchesAway() {
        // Read in full first (the tab out over the wait for offers), then too big after content changes alone.
        OfferFilterService service = service();
        AccessibilityNodeInfo root = smallIdle();
        show(service, root);
        assertTrue(tabTakesTouches(service));
        AccessibilityNodeInfo list = node(null, false);
        for (int i = 0; i < 2_000; i++) add(list, node(null, false));
        add(root, list);
        pass(1_000);
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, WINDOW, null));
        idle();
        // 215122e: the tab out as over an unrecognised screen, taking touches over a possible offer.
        assertFalse(tabTakesTouches(service));
        assertEquals(DasherTab.Look.PEEK, service.overlay().tab().look());
    }

    // ---- (b) The same screens when an offer's sheet appears: within the quiet gap, as always ----

    @Test
    public void anOfferSheetOverTheBusyMapIsDeclinedAtOnceByItsWindowChange() {
        OfferFilterService service = service();
        AccessibilityNodeInfo root = mapScreen();
        show(service, root);
        cost(2);
        churn(service, 60, 10_000);
        add(root, sheet("$3.50", null));
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
    public void anOfferSheetWhoseEventCarriesItsPayIsReadAtOnce() {
        OfferFilterService service = service();
        AccessibilityNodeInfo root = mapScreen();
        show(service, root);
        cost(2);
        churn(service, 60, 10_000);
        add(root, sheet("$3.50", null));
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
    public void anOfferSheetDrawnByATextlessChangeWaitsTheQuietGapAtMost() {
        OfferFilterService service = service();
        AccessibilityNodeInfo root = mapScreen();
        show(service, root);
        cost(2);
        churn(service, 60, 10_000);
        add(root, sheet("$3.50", null));
        long[] declined = declineTimes();
        String line = churnUntilDeclined(service, declined, "b3 sheet by a textless content change");
        // 215122e held it for the budget's next token: 230 ms.
        assertTrue(line, waited(line) <= OfferFilterService.QUIET_SCAN_GAP_MS);
        // Its decline under way, the question that follows is read at once.
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        AccessibilityNodeInfo question = node("Are you sure you want to decline this offer?", false);
        add(question, confirm);
        dasherShows(service, question, WINDOW);
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, WINDOW, null));
        assertEquals(1, Shadows.shadowOf(confirm).getPerformedActions().size());
    }

    @Test
    public void anOfferRightAfterASlowReadThatShowedNothingWaitsTheQuietGapAtMost() {
        OfferFilterService service = service();
        AccessibilityNodeInfo root = mapScreen();
        show(service, root);
        cost(2);
        churn(service, 60, 10_000);
        // One read of a hundred nodes at 3 ms each: 300 ms, and nothing of an offer on it.
        cost(30);
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, WINDOW, null));
        pass(400);
        cost(2);
        add(root, sheet("$3.50", null));
        long[] declined = declineTimes();
        String line = churnUntilDeclined(service, declined, "b4 sheet right after a 300 ms read");
        // 215122e: the cost backoff held it 588 ms.
        assertTrue(line, waited(line) <= OfferFilterService.QUIET_SCAN_GAP_MS);
    }

    @Test
    public void anAddOnDrawnByATextlessChangeOnADeliveryScreenWaitsOneReadASecondAtMost() {
        // The owner's decision (6 October 2026): during a delivery, one read a second.
        OfferFilterService service = service();
        AccessibilityNodeInfo root = deliveryScreen();
        show(service, root);
        cost(2);
        churn(service, 60, 10_000);
        add(root, sheet("$3.50", null));
        long[] declined = declineTimes();
        String line = churnUntilDeclined(service, declined, "b5 add-on by a textless change on a delivery screen");
        assertTrue(line, waited(line) <= ReadBudget.CALM_MS);
    }

    @Test
    public void anAddOnWhoseEventCarriesItsPayOnADeliveryScreenIsReadAtOnce() {
        OfferFilterService service = service();
        AccessibilityNodeInfo root = deliveryScreen();
        show(service, root);
        cost(2);
        churn(service, 60, 10_000);
        add(root, sheet("$3.50", null));
        long[] declined = declineTimes();
        long at = SystemClock.uptimeMillis();
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, WINDOW, "$3.50"));
        String log = DiagnosticLog.read(app);
        System.out.println("MEASURE b5b add-on by a change carrying its pay on a delivery screen (SDK "
                + Build.VERSION.SDK_INT + "): declined " + (declined[0] - at) + " ms after its event; "
                + declineLine(log));
        assertTrue("declined at once", declined[0] >= 0);
        assertTrue(log, declineLine(log).contains("; read after content (waited 0 ms)"));
    }

    @Test
    public void aDeliveryScreenWhileDasherAnswersSlowlyStaysAtOneReadASecondAndAnAddOnWaitsASecondAtMost() {
        // A delivery screen that can be read in full: neither the cost backoff nor the watchdog's pauses hold its reads
        // back beyond one a second, so an add-on drawn there by a change alone still waits a second at most.
        OfferFilterService service = service();
        AccessibilityNodeInfo root = deliveryScreen();
        show(service, root);
        // Dasher answers every root 250 ms late.
        cost(2, 2_500);
        int reads = service.contentReads;
        churn(service, 10, 10_000);
        int read = service.contentReads - reads;
        assertTrue("one a second at most: " + read, read <= 11);
        assertTrue("still read every second: " + read, read >= 8);
        add(root, sheet("$3.50", null));
        long[] declined = declineTimes();
        String line = churnUntilDeclined(service, declined, "b5c add-on on a delivery screen, Dasher 250 ms late");
        assertTrue(line, waited(line) <= ReadBudget.CALM_MS);
    }

    @Test
    public void anOfferWhileDasherAnswersSlowlyIsNotHeldByTheWatchdog() {
        OfferFilterService service = service();
        AccessibilityNodeInfo root = smallIdle();
        show(service, root);
        // Dasher answers every root 250 ms late: the watchdog judges it slow.
        cost(2, 2_500);
        churn(service, 10, 10_000);
        assertTrue(DiagnosticLog.read(app).contains("[screen] yielding to Dasher: median fetch 250 ms"));
        add(root, sheet("$3.50", null));
        long[] declined = declineTimes();
        String line = churnUntilDeclined(service, declined, "b6 sheet while Dasher answers 250 ms late");
        // 215122e: held for the rest of the watchdog's pause, 3,500 ms.
        assertTrue(line, waited(line) <= OfferFilterService.QUIET_SCAN_GAP_MS);
    }

    @Test
    public void aContentChangeFromANewWindowOfDashersIsReadAtOnce() {
        OfferFilterService service = service();
        show(service, mapScreen());
        cost(2);
        churn(service, 60, 5_000);
        // Dasher draws the offer in a window of its own, which announces itself by a content change only.
        AccessibilityNodeInfo popup = node(null, false);
        add(popup, sheet("$3.50", null));
        dasherShows(service, popup, 77);
        long[] declined = declineTimes();
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, 77, null));
        assertTrue("a new window of Dasher's is read at once", declined[0] >= 0);
    }

    @Test
    public void anOfferInAWindowOnlyAndroidsListAnnouncesComesWithItsNodesAllAtOnce() {
        if (Build.VERSION.SDK_INT < 33) return; // Prefetch strategies are Android 13's.
        OfferFilterService service = service();
        show(service, smallIdle());
        // A window of Dasher's no event of Dasher's named yet: Android's list changes, and its root is asked whose it
        // is (alone), then again with Dasher's nodes, for the offer's read taken at once.
        AccessibilityNodeInfo popup = node(null, false);
        add(popup, sheet("$3.50", null));
        dasherShows(service, popup, 78);
        int uninterruptible = service.uninterruptibleRootFetches;
        int alone = service.identityRootFetches;
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED));
        idle();
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
        assertEquals("its root alone, to learn whose window it is", alone + 1, service.identityRootFetches);
        assertEquals("then with the offer's nodes, all at once", uninterruptible + 1,
                service.uninterruptibleRootFetches);
    }

    // ---- (c) Paused: nothing of Dasher's is read ----

    @Test
    public void pausedNothingOfDasherIsReadNotOneNode() {
        FilterStore.save(app, FilterSettings.of(false, 2000, 0, 0, 0));
        OfferFilterService service = service();
        cost(2);
        AccessibilityNodeInfo root = mapScreen();
        show(service, root);
        assertTrue("the tab still follows Dasher (Android's list of windows)", OfferFilterService.isDasherForeground());
        int events = churn(service, 60, MINUTE);
        // An offer comes and is clicked; a notification asks for a check: still nothing is read.
        add(root, sheet("$3.50", null));
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
        // Nothing read, an offer may be up unseen: the tab over Dasher is the slim peek that takes no touches.
        assertEquals(DasherTab.Look.PEEK, service.overlay().tab().look());
        assertFalse(tabTakesTouches(service));
        assertTrue(service.overlay().guide() == null || service.overlay().guide().getVisibility() != View.VISIBLE);
        String log = DiagnosticLog.read(app);
        assertEquals(log, 1, count(log, "[screen] not reading Dasher while paused (auto-decline is off)"));

        // Resumed: the offer is read and declined at once.
        FilterStore.save(app, FilterSettings.of(true, 2000, 0, 0, 0));
        OfferFilterService.requestCheckFromNotification();
        idle();
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
        assertTrue(DiagnosticLog.read(app).contains("[screen] reading Dasher again"));
    }

    @Test
    public void pausedTheLastReadsSceneStandsOnlyUntilDashersNextEvent() {
        OfferFilterService service = service();
        AccessibilityNodeInfo root = smallIdle();
        show(service, root);
        assertTrue("over the wait for offers the tab takes touches", tabTakesTouches(service));
        int reads = service.contentReads;
        int roots = service.rootFetches;
        FilterStore.save(app, FilterSettings.of(false, 2000, 0, 0, 0));
        OfferFilterService.requestCheckForRules();
        idle();
        // Paused with nothing changed since the last read: the read the pause asked for is not made, and what the last
        // read made of the screen stands (the tab still takes touches, so a second tap resumes).
        assertEquals(reads, service.contentReads);
        assertTrue(tabTakesTouches(service));
        assertEquals(DasherTab.Look.REST, service.overlay().tab().look());

        // Dasher changes its screen, unread (an offer may be up): from then on the tab takes no touches.
        add(root, sheet("$3.50", null));
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, WINDOW, null));
        idle();
        assertFalse(tabTakesTouches(service));
        assertEquals(DasherTab.Look.PEEK, service.overlay().tab().look());
        // A window change places it the same way, by Android's list of windows alone.
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, WINDOW, null));
        idle();
        assertFalse(tabTakesTouches(service));
        assertEquals(reads, service.contentReads);
        assertEquals("not even Dasher's root", roots, service.rootFetches);
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());

        // Resumed (a rules check, 150 ms after the pause's at the soonest): read again; the offer is declined.
        FilterStore.save(app, FilterSettings.of(true, 2000, 0, 0, 0));
        OfferFilterService.requestCheckForRules();
        pass(OfferFilterService.QUIET_SCAN_GAP_MS);
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
    }

    @Test
    public void pausedAndAnyWindowChangedTheTabTakesNoTouchesThoughDasherSentNothing() {
        OfferFilterService service = service();
        show(service, smallIdle());
        FilterStore.save(app, FilterSettings.of(false, 2000, 0, 0, 0));
        OfferFilterService.requestCheckForRules();
        idle();
        assertTrue(tabTakesTouches(service));
        // Android's list changes (the shade, another app's window): the screen under the tab is no longer known.
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED));
        idle();
        assertFalse(tabTakesTouches(service));
    }

    @Test
    public void withNoRuleSetNothingOfDasherIsReadEither() {
        FilterStore.save(app, FilterSettings.of(true, 0, 0, 0, 0));
        OfferFilterService service = service();
        cost(2);
        show(service, mapScreen());
        churn(service, 60, 5_000);
        assertEquals(0, service.contentReads);
        assertEquals(0, fetches.get());
        assertTrue(DiagnosticLog.read(app).contains("[screen] not reading Dasher while paused (no rule is set)"));
    }

    // ---- (d) Dasher slow to answer: the watchdog yields what the budget holds back ----

    @Test
    public void dasherSlowToAnswerOnAScreenTooBigToReadPausesItsReadsLongerWhileItLasts() {
        OfferFilterService service = service();
        AccessibilityNodeInfo root = node(null, false);
        add(root, node("Finding offers", false));
        AccessibilityNodeInfo list = node(null, false);
        for (int i = 0; i < 2_000; i++) add(list, node(null, false));
        add(root, list);
        show(service, root);
        // Every root 300 ms late; each child 0.2 ms.
        cost(2, 3_000);
        int reads = service.contentReads;
        int rootFetches = service.rootFetches;
        long started = SystemClock.uptimeMillis();
        int events = churn(service, 10, MINUTE);
        pass(5_000);
        measure("d too big + 300 ms roots 10Hz 60s", service, reads, rootFetches, started, events);
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("[screen] yielding to Dasher: median fetch 300 ms"));
        int read = service.contentReads - reads;
        // 0.4.72: back to back, 95% of Dasher's time.
        assertTrue("reads: " + read, read <= 12);
        assertTrue("Dasher serves the reader a quarter of the time at most: " + busyMs.get(),
                busyShare(SystemClock.uptimeMillis() - started) <= 0.25);
        Matcher load = Pattern.compile("\\[screen\\] read load: \\d+ reads/min \\(fact-free \\d+\\), nodes \\d+, "
                + "slowest \\d+ ms, median fetch 300 ms, truncated [1-9]\\d*, pruned map subtrees 0, yields (\\d+)")
                .matcher(log);
        assertTrue(log, load.find());
        assertTrue("yields counted", Integer.parseInt(load.group(1)) >= 1);

        // An offer still gets through at once: a window change is never held back by a pause.
        AccessibilityNodeInfo offer = node(null, false);
        add(offer, sheet("$3.50", null));
        cost(0);
        show(service, offer);
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
    }

    // ---- (e) Deliveries: their figures are their own, and one read a second (the owner's decision) ----

    @Test
    public void aDeliveryScreenIsReadOnceASecondAndItsMapNever() {
        OfferFilterService service = service();
        int read = minuteOf(service, deliveryScreen(), "e delivery screen 606 nodes 60Hz 60s");
        // 0.4.72: 439 reads of 600 nodes at every change, 88% of Dasher's time. 0b423af: the quiet gap, about 12%.
        assertTrue("one a second at most: " + read, read <= 61);
        assertTrue("still read every second: " + read, read >= 55);
        assertEquals(0, mapFetches.get());
        assertTrue(busyMs.get() + " ms", busyShare(MINUTE) < 0.05);
        assertTrue(DiagnosticLog.read(app).contains(
                "[screen] map subtree skipped (500 children) on Dasher's delivery screen"));
    }

    @Test
    public void aPickupScreenShowingItsOrdersTotalIsReadOnceASecondNotAtEveryChange() {
        // The owner's 0.4.72 reports: the pickup screen shows "$9.30 / this offer" (or "$0.00 / this dash") beside
        // "Pick up by …", "Directions" and "Arrived at store". OfferParser takes the one amount for pay.
        for (String[] figure : new String[][] {{"$9.30", "this offer"}, {"$0.00", "this dash"}}) {
            OfferFilterService service = service();
            int uninterruptible = service.uninterruptibleRootFetches;
            int read = minuteOf(service, screen(map(500), "Pick up by 7:30 PM", figure[0], figure[1], "Directions",
                    "Arrived at store"), "e2 pickup '" + figure[0] + " " + figure[1] + "' + map 500 60Hz 60s");
            // 215122e: 1628 reads, every change at once, all with uninterruptible prefetch, 55% of Dasher's time.
            assertTrue("one a second at most: " + read, read <= 61);
            assertTrue(busyMs.get() + " ms", busyShare(MINUTE) < 0.05);
            if (Build.VERSION.SDK_INT >= 33) {
                assertEquals("only the window change's read asks for an uninterruptible prefetch", uninterruptible + 1,
                        service.uninterruptibleRootFetches);
            }
            controller.destroy();
            controller = null;
        }
    }

    @Test
    public void aDeliveryScreenTooBigToReadIsReadOnceASecondAtMost() {
        OfferFilterService service = service();
        int read = minuteOf(service, screen(map("android.widget.FrameLayout", null, 3900), "Deliver by 7:45 PM",
                "Complete delivery steps", "Directions", "12 min", "3.4 mi"), "e3 delivery + unnamed map 60Hz 60s");
        assertTrue("one a second at most: " + read, read <= 61);
        assertTrue(busyShare(MINUTE) <= 0.25);
    }

    @Test
    public void aStoredRouteIsCalmOnAScreenTooBigToReadToo() {
        ActiveRouteStore.save(app, OfferParser.parse(Arrays.asList("$7.00", "2 stops (3.1 mi) • 18 min")));
        OfferFilterService service = service();
        int read = minuteOf(service, screen(map("android.widget.FrameLayout", null, 3900), "Heading to store"),
                "e4 stored route + unnamed map 60Hz 60s");
        assertTrue("one a second: " + read, read <= 61);
    }

    // ---- (f) Dasher's own screens whose one amount is theirs ----

    @Test
    public void theDashsOwnScreensWithTheirEarningsAreReadOnTheQuietGapNotAtEveryChange() {
        // As captured: the in-dash screen (DasherSceneTest), Dasher's End-dash question over it, and the dash's end.
        String[][] screens = {
                {"This dash", "$0.00", "Dash Preferences", "Safety tools"},
                {"End your current dash?", "End dash", "Go back", "This dash", "$0.00"},
                {"Dash ended", "Total earned $84.20"}};
        for (String[] labels : screens) {
            OfferFilterService service = service();
            int read = minuteOf(service, screen(map(3900), labels), "f " + String.join(" | ", labels)
                    + " + map 3900 60Hz 60s");
            // 215122e: about 1630 reads, every change at once with uninterruptible prefetch, 55% of Dasher's time.
            assertTrue(String.join(", ", labels) + ": " + read, read <= QUIET_READS_A_MINUTE);
            assertTrue(busyMs.get() + " ms", busyShare(MINUTE) < 0.15);
            controller.destroy();
            controller = null;
        }
    }

    @Test
    public void figuresOnAScreenDashersWordsDoNotNameAreStillAnOffersSign() {
        // One amount on a screen none of Dasher's words name may be an offer being drawn: every change is read at once.
        OfferFilterService service = service();
        show(service, screen(map(100), "$7.90"));
        cost(0);
        int reads = service.contentReads;
        for (int i = 0; i < 20; i++) {
            service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, WINDOW, null));
            pass(16);
        }
        assertEquals(20, service.contentReads - reads);
    }

    // ---- (g) An offer left to the user: its changes get the quiet gap, as under takeover ----

    @Test
    public void aPassingOfferOnScreenIsReadOnTheQuietGapOnceDecided() {
        OfferFilterService service = service();
        AccessibilityNodeInfo root = mapScreen();
        show(service, root);
        cost(2);
        churn(service, 60, 5_000);
        add(root, sheet("$30.00", "0:35"));
        dasherShows(service, root, WINDOW);
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, WINDOW, null));
        idle();
        cost(2);
        int reads = service.contentReads;
        int rootFetches = service.rootFetches;
        long started = SystemClock.uptimeMillis();
        int events = churn(service, 60, 30_000);
        measure("g passing $30 offer (0:35) over the map 60Hz 30s", service, reads, rootFetches, started, events);
        int read = service.contentReads - reads;
        // 215122e: 801 reads at every change, 55% of Dasher's time.
        assertTrue("the quiet gap's cadence: " + read, read <= QUIET_READS_A_MINUTE / 2 + 2);
        assertTrue(busyShare(30_000) < 0.15);
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        assertTrue(Shadows.shadowOf(accept).getPerformedActions().isEmpty());
        // The user's own Accept tap on it is read at once, as always.
        reads = service.contentReads;
        AccessibilityEvent click = event(AccessibilityEvent.TYPE_VIEW_CLICKED, WINDOW, "Accept");
        ((ShadowAccessibilityRecord) Shadow.extract(click)).setSourceNode(accept);
        service.onAccessibilityEvent(click);
        idle();
        assertEquals(reads + 1, service.contentReads);
    }

    @Test
    public void aDifferentOfferReplacingADecidedOneIsDeclinedWithinTheQuietGap() {
        OfferFilterService service = service();
        AccessibilityNodeInfo root = mapScreen();
        show(service, root);
        cost(2);
        AccessibilityNodeInfo passing = sheet("$30.00", "0:35");
        add(root, passing);
        dasherShows(service, root, WINDOW);
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, WINDOW, null));
        idle();
        churn(service, 60, 3_000);
        // Dasher swaps the sheet's words for a failing offer's, by content changes alone.
        AccessibilityNodeInfo failing = node(null, false);
        add(failing, sheet("$3.10", "0:40"));
        AccessibilityNodeInfo next = mapScreen();
        add(next, failing);
        dasherShows(service, next, WINDOW);
        long[] declined = declineTimes();
        String line = churnUntilDeclined(service, declined, "g2 a failing offer replacing a decided one");
        assertTrue(line, waited(line) <= OfferFilterService.QUIET_SCAN_GAP_MS);
    }

    // ---- What a read asks of Dasher, and when ----

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
        add(offer, sheet("$3.50", null));
        show(service, offer);
        assertEquals(uninterruptible + 1, service.uninterruptibleRootFetches);
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());

        // Another app comes to the front: only its root is asked for (whose window it is), never its nodes.
        AccessibilityNodeInfo maps = AccessibilityNodeInfo.obtain(new View(app));
        maps.setPackageName("com.google.android.apps.maps");
        maps.setVisibleToUser(true);
        AccessibilityWindowInfo window = window(88, maps, true, SCREEN, 1);
        Shadows.shadowOf(service).setWindows(Collections.singletonList(window));
        Shadows.shadowOf(service).setRootInActiveWindow(maps);
        int alone = service.identityRootFetches;
        int rootFetches = service.rootFetches;
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED));
        idle();
        assertEquals("the root alone", alone + 1, service.identityRootFetches);
        assertEquals(rootFetches + 1, service.rootFetches);
        assertFalse(OfferFilterService.isDasherForeground());
        // Known by its ID from then on: not asked again.
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED));
        idle();
        assertEquals(rootFetches + 1, service.rootFetches);
    }

    @Test
    public void theWindowWatchAsksDasherNothingEvenWhileOtherWindowsMove() {
        OfferFilterService service = service();
        AccessibilityNodeInfo waiting = smallIdle();
        show(service, waiting);
        int rootFetches = service.rootFetches;
        pass(MINUTE);
        System.out.println("MEASURE h static idle screen, no events, 60s (SDK " + Build.VERSION.SDK_INT
                + "): rootFetches=" + (service.rootFetches - rootFetches));
        assertEquals("before: a root every 500 ms, 120 a minute", rootFetches, service.rootFetches);
        assertTrue(OfferFilterService.isDasherForeground());

        // A bubble of another app moves every 400 ms: its window is asked whose it is once, Dasher's never.
        AccessibilityWindowInfo dasher = window(WINDOW, waiting, true, SCREEN, 1);
        AccessibilityNodeInfo other = AccessibilityNodeInfo.obtain(new View(app));
        other.setPackageName("com.example.bubble");
        service.windowSource = () -> {
            long slot = SystemClock.uptimeMillis() / 400;
            Rect bubble = new Rect((int) (slot % 30), 900, 160 + (int) (slot % 30), 1060);
            return Arrays.asList(window(90, other, false, bubble, 4), dasher);
        };
        rootFetches = service.rootFetches;
        pass(MINUTE);
        System.out.println("MEASURE h2 a bubble moves every 400 ms, 60s (SDK " + Build.VERSION.SDK_INT
                + "): rootFetches=" + (service.rootFetches - rootFetches));
        // 215122e: 120 of Dasher's roots a minute.
        assertTrue("the bubble's root, once: " + (service.rootFetches - rootFetches),
                service.rootFetches - rootFetches <= 1);
        assertTrue(OfferFilterService.isDasherForeground());
    }

    @Test
    public void aDeliveryScreensRouteFiguresDoNotMakeTheWindowWatchReadIt() {
        // Its own route's time and distance on screen, no event of Dasher's, another app's window moving every 700 ms.
        OfferFilterService service = service();
        AccessibilityNodeInfo delivery = deliveryScreen();
        show(service, delivery);
        cost(2);
        AccessibilityWindowInfo dasher = window(WINDOW, delivery, true, SCREEN, 1);
        AccessibilityNodeInfo other = AccessibilityNodeInfo.obtain(new View(app));
        other.setPackageName("com.google.android.apps.maps");
        service.windowSource = () -> {
            long slot = SystemClock.uptimeMillis() / 700;
            Rect pip = new Rect(600 + (int) (slot % 20), 1500, 1050, 1900);
            return Arrays.asList(window(88, other, false, pip, 3), dasher);
        };
        int reads = service.contentReads;
        int rootFetches = service.rootFetches;
        pass(MINUTE);
        System.out.println("MEASURE e5 delivery, no events, another window moving every 700 ms (SDK "
                + Build.VERSION.SDK_INT + "): contentReads=" + (service.contentReads - reads) + " rootFetches="
                + (service.rootFetches - rootFetches));
        // 215122e: 86 full reads a minute at once, all with uninterruptible prefetch.
        assertEquals(reads, service.contentReads);
        assertTrue(service.rootFetches - rootFetches <= 1);
        assertTrue(OfferFilterService.isDasherForeground());
    }

    @Test
    public void afterADeclinesQuestionIsConfirmedDashersOtherWindowsAreNotReadWithEveryChange() {
        OfferFilterService service = service();
        AccessibilityNodeInfo offer = node(null, false);
        add(offer, sheet("$3.50", null));
        cost(2);
        show(service, offer);
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        AccessibilityNodeInfo question = node("Are you sure you want to decline this offer?", false);
        add(question, confirm);
        pass(300);
        dasherShows(service, question, WINDOW);
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, WINDOW, null));
        idle();
        assertEquals(1, Shadows.shadowOf(confirm).getPerformedActions().size());
        pass(200);
        // Dasher lands on a screen its words do not name, with a second window of its own (a bar) beside it.
        AccessibilityNodeInfo main = screen(map(3900), "Dash along the way", "Head to a busier area");
        AccessibilityNodeInfo bar = node(null, false);
        for (int i = 0; i < 300; i++) add(bar, node(null, false));
        AccessibilityWindowInfo w1 = window(WINDOW, main, true, SCREEN, 1);
        AccessibilityWindowInfo w2 = window(43, bar, false, new Rect(0, 1800, 1080, 2040), 2);
        Shadows.shadowOf(service).setWindows(Arrays.asList(w2, w1));
        Shadows.shadowOf(service).setRootInActiveWindow(main);
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, WINDOW, null));
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, 43, null));
        idle();
        pass(12_000); // the decline's authority (10 s with no countdown) runs out; its episode lasts 2 minutes
        long[] barFetches = {0};
        OfferFilterService.childFetchForTests = parent -> {
            if (parent.equals(bar)) barFetches[0]++;
        };
        int reads = service.contentReads;
        int uninterruptible = service.uninterruptibleRootFetches;
        churn(service, 60, MINUTE);
        System.out.println("MEASURE j after a confirmed decline, unrecognised map + a 2nd window 60Hz 60s (SDK "
                + Build.VERSION.SDK_INT + "): contentReads=" + (service.contentReads - reads)
                + " secondWindowChildFetches=" + barFetches[0]);
        // 215122e: every read also read the second window (200 nodes), both roots with uninterruptible prefetch.
        assertEquals("the question is looked for only while the decline is under way", 0, barFetches[0]);
        assertTrue(service.contentReads - reads <= QUIET_READS_A_MINUTE);
        assertEquals("routine reads", uninterruptible, service.uninterruptibleRootFetches);
    }

    @Test
    public void anOfferNotificationReadsAtOnceAndForAMomentNothingIsHeldOnTheBudget() {
        OfferFilterService service = service();
        AccessibilityNodeInfo root = node(null, false);
        add(root, node("Finding offers", false));
        AccessibilityNodeInfo list = node(null, false);
        for (int i = 0; i < 2_000; i++) add(list, node(null, false));
        add(root, list);
        show(service, root);
        cost(0);
        churn(service, 60, 5_000);
        int reads = service.contentReads;
        OfferFilterService.requestCheckFromNotification();
        idle();
        assertEquals("the notification's read, at once", reads + 1, service.contentReads);
        // For the next moments the screen too big to read is read on the quiet gap, not the budget's.
        reads = service.contentReads;
        churn(service, 60, 1_000);
        int read = service.contentReads - reads;
        assertTrue("about one read every 150 ms: " + read, read >= 6);
    }

    @Test
    public void aFloodOfRuleChangesIsReadOnceEveryQuietGapAtMost() {
        // A drag on the minimums scale saves at every percent.
        OfferFilterService service = service();
        show(service, mapScreen());
        cost(2);
        int reads = service.contentReads;
        long started = SystemClock.uptimeMillis();
        while (SystemClock.uptimeMillis() < started + 5_000) {
            OfferFilterService.requestCheckForRules();
            pass(16);
        }
        pass(500);
        int read = service.contentReads - reads;
        System.out.println("MEASURE i rules changed every 16 ms for 5 s (SDK " + Build.VERSION.SDK_INT + "): reads="
                + read + " dasherBusyMs=" + busyMs.get());
        // 215122e: one read per change, each with uninterruptible prefetch.
        assertTrue("one every 150 ms at most: " + read, read <= 5_500 / OfferFilterService.QUIET_SCAN_GAP_MS + 1);
        assertTrue(read >= 20);
    }

    @Test
    public void longWaitsSurviveTheLogsMasking() {
        // The log's masking takes five digits before "ms" for a ZIP code and a Mississippi address.
        assertEquals("12,345 ms", ReadLoad.ms(12_345));
        assertEquals("9999 ms", ReadLoad.ms(9_999));
        String line = "first-step Decline REQUESTED: Pay $3.50; read after content burst (waited "
                + ReadLoad.ms(12_345) + "); sound playing: nothing";
        assertTrue(PersonalText.maskLine(line), PersonalText.maskLine(line).contains("(waited 12,345 ms)"));
    }
}

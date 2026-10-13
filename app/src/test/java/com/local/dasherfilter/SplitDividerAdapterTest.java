package com.local.dasherfilter;

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
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityRecord;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The split divider moved for a delivery through the real screen reader, Android simulated: a fake divider window
 * ({@code TYPE_SPLIT_SCREEN_DIVIDER}) whose node has stock Android's custom actions, its halves' bounds following each
 * action performed. Offer Filter's half (or Dasher's, beside a map) shrinks at the pickup screen, never during Dasher's
 * own navigation, and gets its size back at the wait for offers; one {@code performAction} on the divider's node, no
 * gesture, never Dasher's root; a divider that doesn't move, or the user's own move, stops it for the dash.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public class SplitDividerAdapterTest extends AndroidAdapterTestBase {
    private static final String DASHER = "com.doordash.driverapp";
    private static final String MAPS = "com.google.android.apps.maps";
    private static final int SCREEN_HEIGHT = 2040;
    private static final int TOP_WINDOW = 921;
    private static final int DASHER_WINDOW = 922;
    private static final int DIVIDER_WINDOW = 923;
    /** Stock Android up to 16 QPR1, portrait, with the IDs SystemUI's resources might give them. */
    private static final List<String> LABELS = Arrays.asList("Top full screen", "Top 70%", "Top 50%", "Top 30%",
            "Bottom full screen", "Swap top app with bottom");
    private static final int FIRST_ACTION = 0x7f0b0100;
    private static final int TOP_70 = FIRST_ACTION + 1;
    private static final int TOP_50 = FIRST_ACTION + 2;
    private static final int TOP_30 = FIRST_ACTION + 3;

    private ServiceController<OfferFilterService> controller;
    private OfferFilterService service;
    /** The app in the top half: this app's package, or a map's. */
    private String topPackage;
    private AccessibilityNodeInfo dasherScreen;
    /** The divider's centre, in percent of the screen's height. */
    private int dividerAt = 50;
    /** Whether the divider follows its actions (a phone where it moves). */
    private boolean follows = true;
    private boolean withActions = true;
    private AccessibilityNodeInfo handle;
    private final List<Integer> performed = new ArrayList<>();
    private final List<Integer> rootsAsked = new ArrayList<>();

    @After
    public void close() {
        SplitDividerMover.rootAskedForTests = null;
        if (controller != null) controller.destroy();
        OfferFilterService.sawDasherBeside(0);
        OfferFilterService.forgetScreenState();
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

    private AccessibilityNodeInfo node(String packageName, String text) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(new View(app));
        node.setPackageName(packageName);
        node.setClassName("android.view.View");
        if (text != null) node.setText(text);
        node.setVisibleToUser(true);
        node.setEnabled(true);
        return node;
    }

    private AccessibilityNodeInfo screen(String packageName, String... texts) {
        AccessibilityNodeInfo root = node(packageName, null);
        for (String text : texts) Shadows.shadowOf(root).addChild(node(packageName, text));
        return root;
    }

    /** The divider window's root and its handle, which holds the actions (when it has them). */
    private AccessibilityNodeInfo dividerRoot() {
        AccessibilityNodeInfo root = node("com.android.systemui", null);
        handle = node("com.android.systemui", null);
        handle.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
        if (withActions) {
            for (int i = 0; i < LABELS.size(); i++) {
                handle.addAction(new AccessibilityNodeInfo.AccessibilityAction(FIRST_ACTION + i, LABELS.get(i)));
            }
        }
        Shadows.shadowOf(handle).setOnPerformActionListener((action, args) -> {
            performed.add(action);
            if (follows) {
                if (action == TOP_30) dividerAt = 30;
                else if (action == TOP_50) dividerAt = 50;
                else if (action == TOP_70) dividerAt = 70;
                layOut();
            }
            return true;
        });
        Shadows.shadowOf(root).addChild(handle);
        return root;
    }

    private static AccessibilityWindowInfo window(int id, int type, AccessibilityNodeInfo root, boolean active,
                                                  Rect bounds) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setType(type);
        shadow.setId(id);
        if (root != null) {
            shadow.setRoot(root);
            Shadows.shadowOf(root).setAccessibilityWindowInfo(window);
        }
        shadow.setActive(active);
        shadow.setBoundsInScreen(bounds);
        return window;
    }

    private AccessibilityNodeInfo topRoot;
    private AccessibilityNodeInfo dividerNode;

    /** The windows as the divider now stands: the top app active above, Dasher below. */
    private void layOut() {
        int centre = SCREEN_HEIGHT * dividerAt / 100;
        Shadows.shadowOf(service).setWindows(Arrays.asList(
                window(TOP_WINDOW, AccessibilityWindowInfo.TYPE_APPLICATION, topRoot, true,
                        new Rect(0, 0, 1080, centre - 20)),
                window(DIVIDER_WINDOW, AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, dividerNode, false,
                        new Rect(0, centre - 20, 1080, centre + 20)),
                window(DASHER_WINDOW, AccessibilityWindowInfo.TYPE_APPLICATION, dasherScreen, false,
                        new Rect(0, centre + 20, 1080, SCREEN_HEIGHT))));
        Shadows.shadowOf(service).setRootInActiveWindow(topRoot);
    }

    /** Auto-decline on with a rule, a dash on, the screen reader connected, the screen split. */
    private void start(String top) {
        FilterStore.save(app, FilterSettings.of(true, 400, 0, 0, 0));
        DiagnosticLog.clear(app);
        Dashing.seen(app);
        SplitDividerMover.rootAskedForTests = window -> rootsAsked.add(window.getId());
        controller = Robolectric.buildService(OfferFilterService.class).create();
        service = controller.get();
        service.onServiceConnected();
        idle();
        topPackage = top;
        topRoot = screen(top);
        dividerNode = dividerRoot();
        dasherShows("Finding offers");
        pass(4_000);
    }

    /** Dasher's half shows {@code texts}, and Dasher says its window changed. */
    private void dasherShows(String... texts) {
        dasherScreen = screen(DASHER, texts);
        layOut();
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName(DASHER);
        event.setClassName(DASHER + ".ui.MainActivity");
        event.setEventTime(SystemClock.uptimeMillis());
        ((ShadowAccessibilityRecord) Shadow.extract(event)).setWindowId(DASHER_WINDOW);
        service.onAccessibilityEvent(event);
        idle();
    }

    private void pickup() {
        dasherShows("Pick up by 7:30 PM", "Taco Bell", "Directions");
    }

    private void navigation() {
        dasherShows("25 mph", "Turn left onto Main St", "0.3 mi");
    }

    private void assertNeverDashersRootNorAGesture() {
        assertFalse("Dasher's root is never asked for: " + rootsAsked, rootsAsked.contains(DASHER_WINDOW));
        assertTrue(Shadows.shadowOf(service).getGesturesDispatched().isEmpty());
    }

    @Test
    public void offerFiltersHalfShrinksAtThePickupAndComesBackAtTheWaitForOffers() {
        start(app.getPackageName());
        assertTrue("nothing before a delivery", performed.isEmpty());
        pickup();
        pass(SplitDividerMover.KICK_GAP_MS);
        assertEquals("once, at the pickup screen", Arrays.asList(TOP_30), performed);
        assertEquals(30, dividerAt);
        pass(10_000);
        assertEquals("once per delivery", Arrays.asList(TOP_30), performed);
        String log = DiagnosticLog.read(app);
        contains(log, "[split] divider: Offer Filter's half to 30% for the delivery (\"Top 30%\")");
        assertFalse(log, log.contains("didn't move"));
        dasherShows("Finding offers");
        pass(1_000);
        assertEquals("back at the wait for offers", Arrays.asList(TOP_30, TOP_50), performed);
        assertEquals(50, dividerAt);
        pass(5_000);
        assertEquals(Arrays.asList(TOP_30, TOP_50), performed);
        log = DiagnosticLog.read(app);
        contains(log, "[split] divider: Offer Filter's half back to 50% after the delivery (\"Top 50%\")");
        assertFalse(log, log.contains("didn't move"));
        assertEquals("only the divider's own action, on its handle", Arrays.asList(TOP_30, TOP_50),
                Shadows.shadowOf(handle).getPerformedActions());
        assertNeverDashersRootNorAGesture();
    }

    @Test
    public void nothingMovesDuringDashersNavigationThenThePickupMovesIt() {
        start(app.getPackageName());
        navigation();
        pass(10_000);
        navigation();
        pass(10_000);
        assertTrue("never during Dasher's own navigation", performed.isEmpty());
        pickup();
        pass(1_000);
        assertEquals(Arrays.asList(TOP_30), performed);
        // Navigation again: the wait for offers after it restores, never the navigation itself.
        navigation();
        pass(5_000);
        assertEquals(Arrays.asList(TOP_30), performed);
        assertNeverDashersRootNorAGesture();
    }

    @Test
    public void besideAMapDashersHalfShrinks() {
        start(MAPS);
        pickup();
        pass(1_000);
        assertEquals("the bottom half small: the largest share up to 80% for the top", Arrays.asList(TOP_70),
                performed);
        contains(DiagnosticLog.read(app), "[split] divider: Dasher's half to 30% for the delivery (\"Top 70%\")");
        assertFalse("the map is never named", DiagnosticLog.read(app).contains(MAPS));
        dasherShows("Finding offers");
        pass(1_000);
        assertEquals(Arrays.asList(TOP_70, TOP_50), performed);
        assertNeverDashersRootNorAGesture();
    }

    @Test
    public void aDividerThatDoesNotMoveStopsItForTheDash() {
        start(app.getPackageName());
        follows = false;
        pickup();
        pass(SplitDivider.VERIFY_MS + 500);
        contains(DiagnosticLog.read(app), "[split] divider didn't move (top or left half 50%, wanted 30%): left alone "
                + "for the rest of this dash");
        dasherShows("Finding offers");
        pass(5_000);
        pickup();
        pass(5_000);
        assertEquals("one move, no more this dash", Arrays.asList(TOP_30), performed);
        assertNeverDashersRootNorAGesture();
    }

    @Test
    public void theUsersOwnMoveLeavesItAloneForTheDash() {
        start(app.getPackageName());
        pickup();
        pass(SplitDivider.VERIFY_MS + 500);
        // The user drags the divider to 40%.
        dividerAt = 40;
        layOut();
        pass(1_500);
        contains(DiagnosticLog.read(app), "[split] you moved the divider: left alone for the rest of this dash");
        dasherShows("Finding offers");
        pass(5_000);
        assertEquals("no restore over the user's move", Arrays.asList(TOP_30), performed);
        assertEquals(40, dividerAt);
    }

    @Test
    public void theProbeLogsTheDividersActionsOnceEvenWithNoDelivery() {
        start(app.getPackageName());
        pass(70_000);
        String log = DiagnosticLog.read(app);
        assertEquals(log, 1, count(log, "[split] divider actions: Top full screen | Top 70% | Top 50% | Top 30% | "
                + "Bottom full screen | Swap top app with bottom"));
        assertTrue(performed.isEmpty());
    }

    @Test
    public void theProbeSaysNoneWithTheNodesClass() {
        withActions = false;
        start(app.getPackageName());
        pass(2_000);
        contains(DiagnosticLog.read(app), "[split] divider actions: none (android.view.View)");
        pickup();
        pass(5_000);
        assertTrue(performed.isEmpty());
        contains(DiagnosticLog.read(app), "[split] divider: no action names a share (none); left as it is");
    }
}

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
 * The split divider moved for a map through the real screen reader, Android simulated: a fake divider window
 * ({@code TYPE_SPLIT_SCREEN_DIVIDER}) whose node has the divider's custom actions, its halves' bounds following each
 * action performed. A map in the top half gets 70% once the split settles, Dasher's half 30%, and the divider goes back
 * once Offer Filter takes the map's half (0.6.21, the owner: "whenever one half has nav open make it 70% height");
 * Dasher beside Offer Filter is left alone, delivery or not; nothing moves during Dasher's own navigation; one
 * {@code performAction} on the divider's node, no gesture, never Dasher's root; a divider that doesn't move, or the
 * user's own move, stops it for the dash.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public class SplitDividerAdapterTest extends AndroidAdapterTestBase {
    private static final String DASHER = "com.doordash.driverapp";
    private static final String MAPS = "com.google.android.apps.maps";
    private static final int SCREEN_HEIGHT = 2040;
    private static final int DASHER_WINDOW = 922;
    private static final int DIVIDER_WINDOW = 923;
    private static final int PIP_WINDOW = 924;
    /** Stock Android up to 16 QPR1, portrait, with the IDs SystemUI's resources might give them. */
    private static final List<String> QPR1 = Arrays.asList("Top full screen", "Top 70%", "Top 50%", "Top 30%",
            "Bottom full screen", "Swap top app with bottom");
    /** The owner's Samsung on Android 16 (his 0.6.11 report, 13 October 2026). */
    private static final List<String> SAMSUNG = Arrays.asList("Make top app full screen", "70% top, 30% bottom",
            "50% top, 50% bottom", "30% top, 70% bottom", "Make bottom app full screen",
            "Swap top app with bottom app, keep current split");
    private static final int FIRST_ACTION = 0x7f0b0100;
    private static final int TOP_70 = FIRST_ACTION + 1;
    private static final int TOP_50 = FIRST_ACTION + 2;

    private ServiceController<OfferFilterService> controller;
    private OfferFilterService service;
    private AccessibilityNodeInfo dasherScreen;
    /** The top half's window: a new one each time another app takes the half, as Android gives it. */
    private int topWindow = 921;
    /** The divider's centre, in percent of the screen's height. */
    private int dividerAt = 50;
    /** Whether the divider follows its actions (a phone where it moves). */
    private boolean follows = true;
    private boolean withActions = true;
    private List<String> labels = QPR1;
    /** A map's window floating in picture-in-picture over the halves. */
    private boolean pip;
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
            for (int i = 0; i < labels.size(); i++) {
                handle.addAction(new AccessibilityNodeInfo.AccessibilityAction(FIRST_ACTION + i, labels.get(i)));
            }
        }
        Shadows.shadowOf(handle).setOnPerformActionListener((action, args) -> {
            performed.add(action);
            int share = action >= FIRST_ACTION && action < FIRST_ACTION + labels.size()
                    ? SplitDivider.share(labels.get(action - FIRST_ACTION)) : -1;
            if (follows && share > 0) {
                dividerAt = share;
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

    /** The windows as the divider now stands: the top app active above, Dasher below (and a map in PiP). */
    private void layOut() {
        int centre = SCREEN_HEIGHT * dividerAt / 100;
        List<AccessibilityWindowInfo> windows = new ArrayList<>(Arrays.asList(
                window(topWindow, AccessibilityWindowInfo.TYPE_APPLICATION, topRoot, true,
                        new Rect(0, 0, 1080, centre - 20)),
                window(DIVIDER_WINDOW, AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, dividerNode, false,
                        new Rect(0, centre - 20, 1080, centre + 20)),
                window(DASHER_WINDOW, AccessibilityWindowInfo.TYPE_APPLICATION, dasherScreen, false,
                        new Rect(0, centre + 20, 1080, SCREEN_HEIGHT))));
        if (pip) {
            AccessibilityWindowInfo floating = window(PIP_WINDOW, AccessibilityWindowInfo.TYPE_APPLICATION,
                    screen(MAPS), false, new Rect(700, 1500, 1040, 1900));
            ((ShadowAccessibilityWindowInfo) Shadow.extract(floating)).setPictureInPicture(true);
            windows.add(0, floating);
        }
        Shadows.shadowOf(service).setWindows(windows);
        Shadows.shadowOf(service).setRootInActiveWindow(topRoot);
    }

    /** Auto-decline on with a rule, a dash on, the screen reader connected, the screen split, Dasher showing these. */
    private void start(String top, String... dasher) {
        FilterStore.save(app, FilterSettings.of(true, 400, 0, 0, 0));
        DiagnosticLog.clear(app);
        Dashing.seen(app);
        SplitDividerMover.rootAskedForTests = window -> rootsAsked.add(window.getId());
        controller = Robolectric.buildService(OfferFilterService.class).create();
        service = controller.get();
        service.onServiceConnected();
        idle();
        topRoot = screen(top);
        dividerNode = dividerRoot();
        dasherShows(dasher.length == 0 ? new String[] {"Finding offers"} : dasher);
        pass(4_000);
    }

    /** Another app takes the top half, in a new window. */
    private void topBecomes(String top) {
        topWindow += 10;
        topRoot = screen(top);
        layOut();
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

    private static final String[] NAVIGATION = {"25 mph", "Turn left onto Main St", "0.3 mi"};

    private void assertNeverDashersRootNorAGesture() {
        assertFalse("Dasher's root is never asked for: " + rootsAsked, rootsAsked.contains(DASHER_WINDOW));
        assertTrue(Shadows.shadowOf(service).getGesturesDispatched().isEmpty());
    }

    @Test
    public void aMapInTheTopHalfGetsSeventyPercentAndTheDividerGoesBackWhenOfferFilterTakesItsPlace() {
        start(MAPS);
        assertEquals("once, as soon as the split settled", Arrays.asList(TOP_70), performed);
        assertEquals(70, dividerAt);
        pass(10_000);
        assertEquals("once while the map stays", Arrays.asList(TOP_70), performed);
        String log = DiagnosticLog.read(app);
        contains(log, "[split] divider: Dasher's half to 30% for the map (\"Top 70%\")");
        assertFalse(log, log.contains("didn't move"));
        assertFalse("the map is never named", log.contains(MAPS));
        topBecomes(app.getPackageName());
        pass(3_000);
        assertEquals("back once the map left", Arrays.asList(TOP_70, TOP_50), performed);
        assertEquals(50, dividerAt);
        pass(5_000);
        assertEquals(Arrays.asList(TOP_70, TOP_50), performed);
        log = DiagnosticLog.read(app);
        contains(log, "[split] divider: top or left half back to 50% after the map left (\"Top 50%\")");
        assertFalse(log, log.contains("you moved the divider"));
        assertEquals("only the divider's own action, on its handle", Arrays.asList(TOP_70, TOP_50),
                Shadows.shadowOf(handle).getPerformedActions());
        // The map again: again.
        topBecomes(MAPS);
        pass(3_000);
        assertEquals(Arrays.asList(TOP_70, TOP_50, TOP_70), performed);
        assertNeverDashersRootNorAGesture();
    }

    @Test
    public void onTheOwnersSamsungTheMapGetsSeventyTopThirtyBottom() {
        labels = SAMSUNG;
        start(MAPS);
        assertEquals(Arrays.asList(FIRST_ACTION + 1), performed);
        assertEquals(70, dividerAt);
        contains(DiagnosticLog.read(app), "[split] divider: Dasher's half to 30% for the map (\"70% top, 30% bottom\")");
        assertNeverDashersRootNorAGesture();
    }

    @Test
    public void dasherBesideOfferFilterIsLeftAloneDeliveryOrNot() {
        start(app.getPackageName());
        pass(10_000);
        dasherShows("Pick up by 7:30 PM", "Taco Bell", "Directions");
        pass(10_000);
        dasherShows("Finding offers");
        pass(5_000);
        assertTrue("0.6.21: no move beside Offer Filter", performed.isEmpty());
        assertNeverDashersRootNorAGesture();
    }

    @Test
    public void nothingMovesDuringDashersNavigationThenItDoes() {
        start(MAPS, NAVIGATION);
        pass(10_000);
        dasherShows(NAVIGATION);
        pass(10_000);
        assertTrue("never during Dasher's own navigation", performed.isEmpty());
        dasherShows("Finding offers");
        pass(3_000);
        assertEquals(Arrays.asList(TOP_70), performed);
        assertNeverDashersRootNorAGesture();
    }

    @Test
    public void aMapInPictureInPictureNoLongerHidesTheSplit() {
        pip = true;
        start(MAPS);
        assertEquals(Arrays.asList(TOP_70), performed);
        assertFalse("the floating map's root is never needed: " + rootsAsked, rootsAsked.contains(PIP_WINDOW));
    }

    @Test
    public void aDividerThatDoesNotMoveStopsItForTheDash() {
        follows = false;
        start(MAPS);
        contains(DiagnosticLog.read(app), "[split] divider didn't move (top or left half 50%, wanted 70%): left alone "
                + "for the rest of this dash");
        topBecomes(app.getPackageName());
        pass(5_000);
        topBecomes(MAPS);
        pass(5_000);
        assertEquals("one move, no more this dash", Arrays.asList(TOP_70), performed);
        assertNeverDashersRootNorAGesture();
    }

    @Test
    public void theUsersOwnMoveLeavesItAloneForTheDash() {
        start(MAPS);
        assertEquals(Arrays.asList(TOP_70), performed);
        // The user drags the divider to 60%.
        dividerAt = 60;
        layOut();
        pass(1_500);
        contains(DiagnosticLog.read(app), "[split] you moved the divider: left alone for the rest of this dash");
        topBecomes(app.getPackageName());
        pass(5_000);
        assertEquals("no restore over the user's move", Arrays.asList(TOP_70), performed);
        assertEquals(60, dividerAt);
    }

    @Test
    public void theProbeLogsTheDividersActionsOnceEvenWithNoMap() {
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
        start(MAPS);
        pass(2_000);
        contains(DiagnosticLog.read(app), "[split] divider actions: none (android.view.View)");
        assertTrue(performed.isEmpty());
        contains(DiagnosticLog.read(app), "[split] divider: no action names a share (none); left as it is");
    }
}

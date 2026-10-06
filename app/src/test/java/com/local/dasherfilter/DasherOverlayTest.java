package com.local.dasherfilter;

import android.app.Application;
import android.graphics.Rect;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
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
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowWindowManagerImpl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The tab and the best-area guide over Dasher: the guide only over the wait for offers, the tab moved by the user and
 * kept where they put it, tucked away during a delivery or an offer. Reads run on the main looper here.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public class DasherOverlayTest {
    private static final Rect PORTRAIT = new Rect(0, 0, 1080, 2040);
    private static final Rect LANDSCAPE = new Rect(0, 0, 2040, 1080);

    private Application app;
    private ServiceController<OfferFilterService> controller;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        DecisionLog.forgetCache();
        ReportOutbox.forgetCache();
        OfferSilencer.forgetCache();
        ActiveRouteStore.clear(app);
        Dashing.forgetCache();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        controller = Robolectric.buildService(OfferFilterService.class).create();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        controller.get().onServiceConnected();
    }

    @After
    public void stop() {
        controller.destroy();
        OfferFilterService.scanLooperForTests = null;
    }

    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(new View(app));
        node.setPackageName("com.doordash.driverapp");
        node.setText(text);
        node.setVisibleToUser(true);
        node.setEnabled(true);
        node.setClickable(clickable);
        if (clickable) {
            node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            Shadows.shadowOf(node).setOnPerformActionListener((action, args) -> true);
        }
        return node;
    }

    /** A Dasher screen showing these labels. */
    private AccessibilityNodeInfo screen(String... labels) {
        AccessibilityNodeInfo root = node("", false);
        for (String label : labels) Shadows.shadowOf(root).addChild(node(label, false));
        return root;
    }

    private AccessibilityNodeInfo offer(String money) {
        AccessibilityNodeInfo root = screen(money, "2 stops (7.2 mi) • 21 min");
        Shadows.shadowOf(root).addChild(node("Accept", true));
        Shadows.shadowOf(root).addChild(node("Decline", true));
        return root;
    }

    /** The waiting screen of the user's report: Dasher on a dash, between offers (no "finding offers" line). */
    private AccessibilityNodeInfo waiting() {
        return screen("This dash", "You’re in a good place to wait for offers", "Zone offer wait", "5-10 min");
    }

    /** Dasher's delivery screen, as in the user's screenshot: no route stored, nothing an older check knew. */
    private AccessibilityNodeInfo delivery() {
        return screen("Deliver by 7:45 PM", "Delivery for Sam", "100 Example St", "Complete delivery steps");
    }

    /** Dasher fills a screen of {@code bounds} and shows {@code root}; read at once. */
    private void show(AccessibilityNodeInfo root, Rect bounds) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        shadow.setRoot(root);
        shadow.setActive(true);
        shadow.setBoundsInScreen(bounds);
        Shadows.shadowOf(controller.get()).setWindows(Collections.singletonList(window));
        Shadows.shadowOf(controller.get()).setRootInActiveWindow(root);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("com.doordash.driverapp");
        controller.get().onAccessibilityEvent(event);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void show(AccessibilityNodeInfo root) {
        show(root, PORTRAIT);
    }

    /** Dasher leaves the screen (another app comes up), so the tab and guide go. */
    private void leaveDasher() {
        AccessibilityNodeInfo maps = AccessibilityNodeInfo.obtain(new View(app));
        maps.setPackageName("com.google.android.apps.maps");
        Shadows.shadowOf(controller.get()).setWindows(Collections.emptyList());
        Shadows.shadowOf(controller.get()).setRootInActiveWindow(maps);
        controller.get().onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private List<View> windows() {
        ShadowWindowManagerImpl windows = Shadow.extract(controller.get().getSystemService(WindowManager.class));
        return windows.getViews();
    }

    private DasherTab tab() {
        for (View view : windows()) if (view instanceof DasherTab) return (DasherTab) view;
        return null;
    }

    private DasherGuide guide() {
        for (View view : windows()) if (view instanceof DasherGuide) return (DasherGuide) view;
        return null;
    }

    private static WindowManager.LayoutParams params(View view) {
        return (WindowManager.LayoutParams) view.getLayoutParams();
    }

    private int dp(int value) {
        return Math.round(value * app.getResources().getDisplayMetrics().density);
    }

    /** A finger on the tab: down, moved by (dx, dy) in a few steps, and lifted. */
    private void drag(DasherTab tab, float dx, float dy) {
        float x = 20;
        float y = 1000;
        tab.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0));
        for (int step = 1; step <= 4; step++) {
            tab.dispatchTouchEvent(MotionEvent.obtain(0, step * 16, MotionEvent.ACTION_MOVE,
                    x + dx * step / 4, y + dy * step / 4, 0));
        }
        tab.dispatchTouchEvent(MotionEvent.obtain(0, 80, MotionEvent.ACTION_UP, x + dx, y + dy, 0));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** A plain tap on the tab. */
    private void tap(DasherTab tab) {
        tab.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10, 1000, 0));
        tab.dispatchTouchEvent(MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, 10, 1000, 0));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** A best area to the north-east of a fresh position, so the guide has something to point at. */
    private void seedBestArea() {
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        AreaMap.forgetCache();
        AreaMap.setEnabled(app, true);
        int[][] pays = {{1000, 1200, 800}, {1500, 1800, 1200}};
        double[][] spots = {{37.7749, -122.4194}, {37.8149, -122.3794}};
        double[] miles = {5.0, 6.0, 4.0};
        for (int spot = 0; spot < 2; spot++) {
            for (int i = 0; i < 3; i++) {
                at(spots[spot][0], spots[spot][1]);
                AreaMap.note(app, new DecisionLog.Entry(System.currentTimeMillis() + spot * 10 + i,
                        DecisionLog.Source.SCREEN, false, new OfferSnapshot(pays[spot][i], miles[i], 20 + i, 2), 1000,
                        OfferRule.Result.KEEP, "meets enabled rules", DecisionLog.Action.PASSES, true,
                        Collections.emptyList()));
            }
        }
        at(37.7749, -122.4194);
    }

    private void at(double latitude, double longitude) {
        android.location.Location fix = new android.location.Location(android.location.LocationManager.NETWORK_PROVIDER);
        fix.setLatitude(latitude);
        fix.setLongitude(longitude);
        fix.setAccuracy(1500);
        fix.setTime(System.currentTimeMillis());
        fix.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());
        Shadows.shadowOf(app.getSystemService(android.location.LocationManager.class))
                .setLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER, fix);
    }

    @Test
    public void theGuideShowsOnlyWhileDasherShowsTheWaitForOffers() {
        seedBestArea();
        show(screen("Finding offers"));
        DasherGuide guide = guide();
        assertNotNull("pointed out while Dasher is finding offers", guide);
        assertEquals("3.6 mi NE · $3.00/mi", guide.label());
        assertTrue("touches pass through it", (params(guide).flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0);

        // The user's screenshot: a delivery under way, though no accepted route was stored.
        show(delivery());
        assertNull("never over a delivery", guide());

        show(waiting());
        assertNotNull("the dash's own screen between offers is waiting too", guide());

        // A screen not recognised (here the side menu) is not taken for waiting.
        show(screen("Ratings", "Earnings", "Promos", "Help"));
        assertNull("unknown hides it", guide());

        // A route still under way (stored from an accepted offer): a screen that does not show the wait keeps it.
        ActiveRouteStore.save(app, new OfferSnapshot(900, 3.0, 20, 2));
        show(screen("Navigate", "Heading to store"));
        assertNull(guide());
        // Dasher's wait for offers, with no sign of a delivery, ends that route, so the guide comes back.
        show(screen("Zone offer wait", "5-10 min"));
        assertNull(ActiveRouteStore.load(app));
        assertNotNull(guide());

        show(screen("Finding offers"));
        assertNotNull(guide());
        show(offer("$25.00"));
        assertNull("never over an offer", guide());
        // An offer still being drawn (pay, no buttons yet) over the finding-offers screen.
        show(screen("Finding offers", "$25.00"));
        assertNull("nor over any sign of one", guide());
    }

    @Test
    public void draggingTheTabMovesItAlongAndAcrossAndItStaysWhereItWasPut() {
        // No rule saved: a tap would open Offer Filter, so a drag that counted as a tap would show it.
        FilterStore.save(app, new FilterSettings(false, 0, 0, 0, 0, 0));
        show(screen("Finding offers"));
        DasherTab tab = tab();
        assertNotNull(tab);
        int startY = params(tab).y;
        assertEquals("starts a little above the middle of the left edge",
                Math.round(PORTRAIT.height() * DasherOverlay.TOP_SHARE), startY);
        assertEquals(0, params(tab).x);

        drag(tab, 0, -500);
        assertEquals("moved up the edge with the finger", startY - 500, params(tab).y);
        assertEquals("still on the left edge", 0, params(tab).x);
        assertEquals(DasherTab.Look.REST, tab.look());
        assertNull("a drag is not a tap: Offer Filter did not open",
                Shadows.shadowOf(app).getNextStartedActivity());
        assertFalse(FilterStore.load(app).enabled);

        // Across, let go past the middle: it lands on the right edge.
        drag(tab, 700, 120);
        int width = params(tab).width;
        assertEquals("on the right edge", PORTRAIT.right - width, params(tab).x);
        assertEquals(startY - 380, params(tab).y);
        assertTrue(tab.onRight());
        assertNull(Shadows.shadowOf(app).getNextStartedActivity());

        // Remembered: Dasher leaves and comes back, and the service starts again.
        leaveDasher();
        assertNull(tab());
        controller.destroy();
        controller = Robolectric.buildService(OfferFilterService.class).create();
        controller.get().onServiceConnected();
        show(screen("Finding offers"));
        tab = tab();
        assertEquals(PORTRAIT.right - params(tab).width, params(tab).x);
        assertEquals(startY - 380, params(tab).y);

        // Landscape has its own place, from the start; portrait keeps the user's.
        show(screen("Finding offers"), LANDSCAPE);
        assertEquals(0, params(tab).x);
        assertEquals(Math.round(LANDSCAPE.height() * DasherOverlay.TOP_SHARE), params(tab).y);
        show(screen("Finding offers"), PORTRAIT);
        assertEquals(startY - 380, params(tab).y);
        assertEquals(PORTRAIT.right - params(tab).width, params(tab).x);

        // Never dragged off its band: it stops short of the status bar.
        drag(tab, 0, -5000);
        assertEquals(dp(DasherOverlay.TOP_MARGIN_DP), params(tab).y);

        // A plain tap is still a tap.
        tap(tab);
        assertNotNull(Shadows.shadowOf(app).getNextStartedActivity());
    }

    @Test
    public void theGuideSitsBesideTheTabOnEitherEdge() {
        seedBestArea();
        show(screen("Finding offers"));
        DasherTab tab = tab();
        DasherGuide guide = guide();
        assertTrue("right of a tab on the left edge", params(guide).x >= dp(DasherTab.REST_SHOWN_DP));
        assertTrue(params(guide).y > params(tab).y && params(guide).y < params(tab).y + dp(DasherTab.HEIGHT_DP));

        drag(tab, 800, 0);
        guide = guide();
        assertNotNull("back beside it once it is let go", guide);
        guide.measure(0, 0);
        assertTrue("left of a tab on the right edge",
                params(guide).x + guide.getMeasuredWidth() <= PORTRAIT.right - dp(DasherTab.REST_SHOWN_DP));
    }

    @Test
    public void duringADeliveryTheTabTucksAwayUntilTapped() {
        show(screen("Finding offers"));
        DasherTab tab = tab();
        assertEquals(DasherTab.Look.REST, tab.look());
        int rest = params(tab).width;
        assertEquals("still a 48 dp touch target at rest", dp(48), rest);

        show(delivery());
        assertEquals("a slim peek during a delivery", DasherTab.Look.PEEK, tab.look());
        assertTrue(params(tab).width < rest);
        assertEquals("on the same edge", 0, params(tab).x);
        assertTrue(tab.getContentDescription().toString().contains("Tap to show it"));

        // The first tap brings it out; it does not pause.
        tap(tab);
        assertEquals(DasherTab.Look.REST, tab.look());
        assertTrue(FilterStore.load(app).enabled);
        // Out, a tap pauses as always.
        tap(tab);
        assertFalse(FilterStore.load(app).enabled);
        assertEquals(DasherTab.Look.REST, tab.look());
        // A few seconds after the last tap it tucks away again, the delivery still on.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DasherOverlay.OUT_FOR_MS));
        assertEquals(DasherTab.Look.PEEK, tab.look());

        // A route stored from an accepted offer tucks it away too, whatever the screen.
        show(screen("Finding offers"));
        assertEquals(DasherTab.Look.REST, tab.look());
        ActiveRouteStore.save(app, new OfferSnapshot(900, 3.0, 20, 2));
        show(screen("Navigate", "Heading to store"));
        assertEquals(DasherTab.Look.PEEK, tab.look());

        assertEquals("it takes touches away from an offer", 0,
                params(tab).flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);

        // Over an offer it peeks too, takes no touches (they are all Dasher's), and nothing brings it out there.
        ActiveRouteStore.clear(app);
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        show(offer("$25.00"));
        assertEquals(DasherTab.Look.PEEK, tab.look());
        assertTrue((params(tab).flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0);
        tab.performClick();
        assertEquals(DasherTab.Look.PEEK, tab.look());
        assertTrue(FilterStore.load(app).enabled);
        show(screen("Finding offers"));
        assertEquals(0, params(tab).flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
    }

    @Test
    public void aScreenNotRecognisedTucksTheTabAwayOnlyDuringADash() {
        show(screen("Ratings", "Earnings"));
        assertFalse(Dashing.now(app));
        assertEquals("no dash: it rests", DasherTab.Look.REST, tab().look());

        show(screen("Finding offers"));
        assertTrue(Dashing.now(app));
        // On a dash, a screen in words not known yet is most likely a delivery or shopping screen.
        show(screen("Pick these items", "Organic bananas", "Aisle 12"));
        DasherTab tab = tab();
        assertEquals(DasherTab.Look.PEEK, tab.look());
        tap(tab);
        assertEquals(DasherTab.Look.REST, tab.look());
        assertTrue("bringing it out did not pause", FilterStore.load(app).enabled);

        show(screen("Dash ended"));
        assertFalse(Dashing.now(app));
        show(screen("Ratings", "Earnings"));
        assertEquals(DasherTab.Look.REST, tab().look());
    }

    @Test
    public void screenReadersMoveTheTabUpDownAndAcross() {
        show(screen("Finding offers"));
        DasherTab tab = tab();
        int startY = params(tab).y;
        List<AccessibilityNodeInfo.AccessibilityAction> actions = tab.createAccessibilityNodeInfo().getActionList();
        assertTrue(hasAction(actions, "Move up"));
        assertTrue(hasAction(actions, "Move down"));
        assertTrue(hasAction(actions, "Move to other side"));

        assertTrue(tab.performAccessibilityAction(DasherTab.ACTION_MOVE_UP, null));
        assertEquals(startY - dp(DasherOverlay.STEP_DP), params(tab).y);
        assertTrue(tab.performAccessibilityAction(DasherTab.ACTION_MOVE_DOWN, null));
        assertTrue(tab.performAccessibilityAction(DasherTab.ACTION_MOVE_DOWN, null));
        assertEquals(startY + dp(DasherOverlay.STEP_DP), params(tab).y);
        assertTrue(tab.performAccessibilityAction(DasherTab.ACTION_OTHER_SIDE, null));
        assertEquals(PORTRAIT.right - params(tab).width, params(tab).x);
        assertTrue(tab.onRight());
        assertEquals(startY + dp(DasherOverlay.STEP_DP), params(tab).y);
        // Nothing was paused by moving it.
        assertTrue(FilterStore.load(app).enabled);

        // At the top of its band "Move up" goes.
        while (tab.performAccessibilityAction(DasherTab.ACTION_MOVE_UP, null)) {
            // up to the top
        }
        assertEquals(dp(DasherOverlay.TOP_MARGIN_DP), params(tab).y);
        actions = tab.createAccessibilityNodeInfo().getActionList();
        assertFalse(hasAction(actions, "Move up"));
        assertTrue(hasAction(actions, "Move down"));

        // Kept, as a drag is.
        leaveDasher();
        show(screen("Finding offers"));
        assertEquals(dp(DasherOverlay.TOP_MARGIN_DP), params(tab()).y);
        assertTrue(tab().onRight());
    }

    private static boolean hasAction(List<AccessibilityNodeInfo.AccessibilityAction> actions, String label) {
        for (AccessibilityNodeInfo.AccessibilityAction action : actions) {
            if (action.getLabel() != null && label.contentEquals(action.getLabel())) return true;
        }
        return false;
    }
}

package com.local.dasherfilter;

import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.robolectric.shadows.ShadowWindowManagerImpl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The user's decision 1: in split screen only a touch on Dasher's half hands an offer back during a decline; a touch
 * on Offer Filter's own half does not. Offer Filter's page reports its own touches; the touch watch reports every
 * touch, in either order. With Dasher full screen, or the bounds of its half unknown, every touch hands the offer back,
 * as before. Real page and service, Android simulated, reads on the main looper. Each test fails on 0.4.45, where any
 * touch anywhere handed the offer back at once: the confirmation was never tapped after a touch on Offer Filter's
 * half, nothing logged where a touch landed, and Dasher's click after such a touch found no decline under way.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class SplitTouchTest extends AndroidAdapterTestBase {
    private static final Rect SCREEN = new Rect(0, 0, 1080, 2040);
    private static final Rect TOP_HALF = new Rect(0, 0, 1080, 1000);
    private static final Rect DIVIDER = new Rect(0, 1000, 1080, 1040);
    private static final Rect BOTTOM_HALF = new Rect(0, 1040, 1080, 2040);

    private ServiceController<OfferFilterService> controller;
    private ActivityController<MainActivity> page;
    private AccessibilityNodeInfo decline;
    private AccessibilityNodeInfo details;

    @After
    public void close() {
        if (page != null) page.pause().stop().destroy();
        if (controller != null) controller.destroy();
        OfferFilterService.sawDasherBeside(0);
        OfferFilterService.forgetScreenState();
    }

    // ---- Dasher's screens ----

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

    private AccessibilityNodeInfo dasher(String text) {
        return node("com.doordash.driverapp", text, false);
    }

    /** A Compose button: a clickable node without text, holding its label. */
    private AccessibilityNodeInfo button(String label) {
        AccessibilityNodeInfo button = node("com.doordash.driverapp", null, true);
        Shadows.shadowOf(button).addChild(dasher(label));
        return button;
    }

    /** An offer below the $20 minimum, as Dasher draws it. */
    private AccessibilityNodeInfo offer(String pay) {
        AccessibilityNodeInfo root = dasher(null);
        decline = button("Decline");
        details = button("View offer details");
        Shadows.shadowOf(root).addChild(decline);
        Shadows.shadowOf(root).addChild(dasher(pay));
        Shadows.shadowOf(root).addChild(dasher("incl. tips"));
        Shadows.shadowOf(root).addChild(dasher("2 stops (7.2 mi) • 21 min"));
        Shadows.shadowOf(root).addChild(details);
        Shadows.shadowOf(root).addChild(button("Accept"));
        Shadows.shadowOf(root).addChild(dasher("0:35"));
        return root;
    }

    /** Dasher's question after a Decline tap, as the user's report shows it. */
    private AccessibilityNodeInfo question(AccessibilityNodeInfo declineOffer) {
        AccessibilityNodeInfo root = dasher(null);
        Shadows.shadowOf(root).addChild(dasher("Are you sure you want to decline this offer?"));
        Shadows.shadowOf(root).addChild(dasher("Does not lower acceptance rate"));
        Shadows.shadowOf(root).addChild(dasher("50%"));
        Shadows.shadowOf(root).addChild(dasher("Accepting this offer will raise acceptance rate"));
        Shadows.shadowOf(root).addChild(declineOffer);
        Shadows.shadowOf(root).addChild(dasher("0:24"));
        return root;
    }

    private static List<Long> taps(AccessibilityNodeInfo button) {
        List<Long> at = new CopyOnWriteArrayList<>();
        Shadows.shadowOf(button).setOnPerformActionListener((action, args) -> {
            at.add(SystemClock.uptimeMillis());
            return true;
        });
        return at;
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

    // ---- The service and Offer Filter's page ----

    private OfferFilterService start() {
        FilterStore.save(app, FilterSettings.of(true, 2000, 0, 0, 0));
        DiagnosticLog.clear(app);
        DecisionLog.clear(app);
        controller = Robolectric.buildService(OfferFilterService.class).create();
        controller.get().onServiceConnected();
        idle();
        page = Robolectric.buildActivity(MainActivity.class).setup();
        idle();
        return controller.get();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void pass(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    /** Offer Filter's page in the top half (the active one), Dasher showing {@code screen} in the bottom half. */
    private void splitShows(OfferFilterService service, AccessibilityNodeInfo screen) {
        AccessibilityNodeInfo ours = node("com.local.dasherfilter", null, false);
        Shadows.shadowOf(service).setWindows(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, ours, true, TOP_HALF),
                window(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, null, false, DIVIDER),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, screen, false, BOTTOM_HALF)));
        Shadows.shadowOf(service).setRootInActiveWindow(ours);
        changed(service);
    }

    private static void changed(OfferFilterService service) {
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("com.doordash.driverapp");
        service.onAccessibilityEvent(event);
        idle();
    }

    private List<View> touchWatches() {
        ShadowWindowManagerImpl windows = Shadow.extract(app.getSystemService(WindowManager.class));
        List<View> watches = new ArrayList<>();
        for (View view : windows.getViews()) if (view.getClass() == View.class) watches.add(view);
        return watches;
    }

    /** The touch watch hears of a finger landing at {@code at}, somewhere outside it. */
    private void watchHears(long at) {
        assertEquals("one touch watch while declining", 1, touchWatches().size());
        touchWatches().get(0).dispatchTouchEvent(MotionEvent.obtain(at, at, MotionEvent.ACTION_OUTSIDE, 0, 0, 0));
        idle();
    }

    /** Offer Filter's page has the same finger land on it (and lifts it as a cancel, so nothing on it is pressed). */
    private void pageHears(long at) {
        page.get().dispatchTouchEvent(MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, 40, 300, 0));
        page.get().dispatchTouchEvent(MotionEvent.obtain(at, at + 30, MotionEvent.ACTION_CANCEL, 40, 300, 0));
        idle();
    }

    private void userClicks(OfferFilterService service, AccessibilityNodeInfo control) {
        AccessibilityEvent tap = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
        tap.setPackageName("com.doordash.driverapp");
        tap.setEventTime(SystemClock.uptimeMillis());
        ((ShadowAccessibilityRecord) Shadow.extract(tap)).setSourceNode(control);
        service.onAccessibilityEvent(tap);
        idle();
    }

    private DecisionLog.Action lastAction() {
        return DecisionLog.recent(app, 1).get(0).action;
    }

    private static void contains(String text, String part) {
        assertTrue(part + " in:\n" + text, text.contains(part));
    }

    // ---- The tests ----

    @Test
    public void aTouchOnOfferFiltersOwnHalfLetsTheDeclineGoOnInEitherOrder() {
        OfferFilterService service = start();
        // The page hears the touch first, then the watch.
        AccessibilityNodeInfo shown = offer("$7.90");
        List<Long> declines = taps(decline);
        splitShows(service, shown);
        assertEquals("declined at once", 1, declines.size());
        pass(300);
        long at = SystemClock.uptimeMillis();
        pageHears(at);
        watchHears(at);
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirms = taps(confirm);
        splitShows(service, question(confirm));
        assertEquals("the question is tapped: the touch was not on Dasher", 1, confirms.size());
        assertNotEquals(DecisionLog.Action.USER_TOOK_OVER, lastAction());
        contains(DiagnosticLog.read(app), "touch on Offer Filter's half of the split screen, not Dasher's: the decline "
                + "goes on");

        // The next offer: the watch hears the touch first. Its question waits for the page, then is tapped.
        splitShows(service, dasher("Finding offers"));
        pass(3_000);
        shown = offer("$6.40");
        declines = taps(decline);
        splitShows(service, shown);
        assertEquals(1, declines.size());
        pass(300);
        at = SystemClock.uptimeMillis();
        watchHears(at);
        confirm = button("Decline offer");
        confirms = taps(confirm);
        splitShows(service, question(confirm));
        assertTrue("held while the touch is judged", confirms.isEmpty());
        pageHears(at);
        assertEquals("tapped once the touch is found on Offer Filter's half", 1, confirms.size());
        assertNotEquals(DecisionLog.Action.USER_TOOK_OVER, lastAction());

        // Dasher full screen: nothing changes, every touch is the user's at once.
        AccessibilityNodeInfo full = offer("$5.10");
        declines = taps(decline);
        Shadows.shadowOf(service).setWindows(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, full, true, SCREEN)));
        Shadows.shadowOf(service).setRootInActiveWindow(full);
        changed(service);
        assertEquals(1, declines.size());
        pass(300);
        at = SystemClock.uptimeMillis();
        pageHears(at);
        watchHears(at);
        assertEquals(DecisionLog.Action.USER_TOOK_OVER, lastAction());
    }

    @Test
    public void aTouchOnDashersHalfStillHandsTheOfferBackAndNothingIsTappedMeanwhile() {
        OfferFilterService service = start();
        AccessibilityNodeInfo shown = offer("$7.90");
        List<Long> declines = taps(decline);
        splitShows(service, shown);
        assertEquals(1, declines.size());
        pass(300);
        // The watch hears a touch the page never does: Dasher's half.
        watchHears(SystemClock.uptimeMillis());
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirms = taps(confirm);
        splitShows(service, question(confirm));
        assertTrue("nothing tapped while the touch is judged", confirms.isEmpty());
        pass(OfferFilterService.OWN_HALF_WAIT_MS);
        assertEquals(DecisionLog.Action.USER_TOOK_OVER, lastAction());
        pass(2_000);
        splitShows(service, question(confirm));
        assertTrue("never tapped after the hand-back", confirms.isEmpty());
        contains(DiagnosticLog.read(app), "touch during decline: the user's (split screen, not on Offer Filter's half)");

        // The bounds of Dasher's half unknown (Dasher active, its window without bounds): any touch hands back at once,
        // even one the page heard too.
        splitShows(service, dasher("Finding offers"));
        pass(3_000);
        AccessibilityNodeInfo next = offer("$6.10");
        declines = taps(decline);
        AccessibilityNodeInfo ours = node("com.local.dasherfilter", null, false);
        Shadows.shadowOf(service).setWindows(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, ours, false, TOP_HALF),
                window(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, null, false, DIVIDER),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, next, true, new Rect())));
        Shadows.shadowOf(service).setRootInActiveWindow(next);
        changed(service);
        assertEquals(1, declines.size());
        pass(300);
        long at = SystemClock.uptimeMillis();
        pageHears(at);
        watchHears(at);
        assertEquals("at once", DecisionLog.Action.USER_TOOK_OVER, lastAction());
    }

    @Test
    public void unknownActiveDasherBoundsCannotExcludeAKeyboardOrSystemCover() {
        OfferFilterService service = start();
        for (int type : new int[] {AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                AccessibilityWindowInfo.TYPE_SYSTEM}) {
            AccessibilityNodeInfo next = offer(type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ? "$6.10" : "$5.10");
            List<Long> declines = taps(decline);
            AccessibilityNodeInfo ours = node("com.local.dasherfilter", null, false);
            AccessibilityNodeInfo covering = node(type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                    ? "com.android.inputmethod.latin" : "com.android.systemui", null, false);
            Shadows.shadowOf(service).setWindows(Arrays.asList(
                    window(AccessibilityWindowInfo.TYPE_APPLICATION, ours, false, TOP_HALF),
                    window(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, null, false, DIVIDER),
                    window(AccessibilityWindowInfo.TYPE_APPLICATION, next, true, new Rect()),
                    window(type, covering, false, BOTTOM_HALF)));
            Shadows.shadowOf(service).setRootInActiveWindow(next);
            changed(service);
            assertTrue("with unknown bounds, a keyboard or system surface cannot be proved clear of Dasher (type "
                    + type + ")", declines.isEmpty());
        }
    }

    @Test
    public void dashersReportOfTheUsersClickHandsBackAfterATouchOnOfferFiltersHalf() {
        OfferFilterService service = start();
        AccessibilityNodeInfo shown = offer("$7.90");
        List<Long> declines = taps(decline);
        splitShows(service, shown);
        assertEquals(1, declines.size());
        pass(300);
        long at = SystemClock.uptimeMillis();
        pageHears(at);
        watchHears(at);
        assertNotEquals(DecisionLog.Action.USER_TOOK_OVER, lastAction());

        // Then the user taps "View offer details" in Dasher's half: a click on Dasher is always Dasher's half.
        pass(200);
        userClicks(service, details);
        assertEquals(DecisionLog.Action.USER_TOOK_OVER, lastAction());
        contains(DiagnosticLog.read(app), "your tap on Dasher");
    }
}

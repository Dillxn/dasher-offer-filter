package com.local.dasherfilter;

import android.Manifest;
import android.app.Application;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The 0.5.1 report (Samsung, Android 16, split screen, Dasher in the bottom half): Dasher's question came up as a
 * bottom sheet sliding into Dasher's half. Halfway up ("win=split/bottom/dasher/26" of a 48% half) only its upper
 * labels were on screen: "question found ... but no Decline in it can be tapped (0 tappable)". Its "Decline offer" came
 * on screen with no event the poll waits for, and no change in Android's list of windows.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class QuestionSlidingUpTest {
    private static final Rect SCREEN = new Rect(0, 0, 1080, 2040);
    private static final Rect TOP_HALF = new Rect(0, 0, 1080, 1000);
    private static final Rect DIVIDER = new Rect(0, 1000, 1080, 1040);
    private static final Rect BOTTOM_HALF = new Rect(0, 1040, 1080, 2040);
    /** Dasher's sheet halfway up its half: Android lists it alone for Dasher, cut at the screen's edge. */
    private static final Rect SHEET_HALFWAY = new Rect(0, 1540, 1080, 2040);

    private Application app;
    private ServiceController<OfferFilterService> controller;
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
        OfferFilterService.scanLooperForTests = null;
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
    }

    @After
    public void tearDown() {
        if (controller != null) controller.destroy();
        OfferFilterService.sawDasherBeside(0);
        OfferFilterService.scanLooperForTests = null;
    }

    private OfferFilterService service() {
        controller = Robolectric.buildService(OfferFilterService.class).create();
        OfferFilterService service = controller.get();
        service.onServiceConnected();
        settle(service);
        return service;
    }

    private static void settle(OfferFilterService service) {
        if (service.scanLooper() != Looper.getMainLooper()) Shadows.shadowOf(service.scanLooper()).idle();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void pass(OfferFilterService service, long ms) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(ms));
        settle(service);
    }

    private AccessibilityNodeInfo node(String packageName, String text, boolean clickable) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(new View(app));
        node.setPackageName(packageName);
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

    private AccessibilityNodeInfo dasher(String text, boolean clickable) {
        return node("com.doordash.driverapp", text, clickable);
    }

    private AccessibilityNodeInfo offer() {
        AccessibilityNodeInfo root = dasher("", false);
        decline = dasher("Decline", true);
        Shadows.shadowOf(root).addChild(decline);
        Shadows.shadowOf(root).addChild(dasher("$7.90", false));
        Shadows.shadowOf(root).addChild(dasher("2 stops (7.2 mi) • 21 min", false));
        Shadows.shadowOf(root).addChild(dasher("Accept", true));
        return root;
    }

    /** Dasher's question: its prompt and rate up top, its Decline offer lower down. */
    private AccessibilityNodeInfo question(AccessibilityNodeInfo declineOffer) {
        AccessibilityNodeInfo root = dasher("", false);
        Shadows.shadowOf(root).addChild(dasher("Are you sure you want to decline this offer?", false));
        Shadows.shadowOf(root).addChild(dasher("Does not lower acceptance rate", false));
        Shadows.shadowOf(root).addChild(dasher("46%", false));
        Shadows.shadowOf(root).addChild(declineOffer);
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

    private static AccessibilityEvent event(int type) {
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setPackageName("com.doordash.driverapp");
        return event;
    }

    /** Offer Filter's page in the top half, Dasher's {@code dasher} window (active or not) in the bottom one. */
    private void split(OfferFilterService service, AccessibilityNodeInfo ours, AccessibilityNodeInfo dasher,
                       boolean dasherActive, Rect dasherBounds) {
        Shadows.shadowOf(service).setWindows(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, ours, !dasherActive, TOP_HALF),
                window(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, null, false, DIVIDER),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, dasher, dasherActive, dasherBounds)));
        Shadows.shadowOf(service).setRootInActiveWindow(dasherActive ? dasher : ours);
    }

    private static AtomicLong tapTime(AccessibilityNodeInfo button) {
        AtomicLong at = new AtomicLong(-1);
        Shadows.shadowOf(button).setOnPerformActionListener((action, args) -> {
            at.set(android.os.SystemClock.uptimeMillis());
            return true;
        });
        return at;
    }

    private static void contains(String text, String part) {
        assertTrue(part + " in:\n" + text, text.contains(part));
    }

    @Test
    public void aQuestionStillSlidingUpIsTappedAtThePollsNextTickOnceItsDeclineIsOnScreen() {
        OfferFilterService service = service();
        AccessibilityNodeInfo ours = node("com.local.dasherfilter", "", false);
        split(service, ours, offer(), true, BOTTOM_HALF);
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        settle(service);
        assertEquals("first-step Decline", 1, Shadows.shadowOf(decline).getPerformedActions().size());

        // Dasher's question, its sheet halfway up: Android lists the sheet alone for Dasher; its Decline offer is
        // still below the edge, not visible to the user.
        AccessibilityNodeInfo confirm = dasher("Decline offer", true);
        AtomicLong confirmTapAt = tapTime(confirm);
        confirm.setVisibleToUser(false);
        split(service, ours, question(confirm), true, SHEET_HALFWAY);
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        settle(service);
        contains(DiagnosticLog.read(app), "but no Decline in it can be tapped (0 tappable");

        // Nothing off screen is ever tapped, however often it is looked at.
        for (int i = 0; i < 3; i++) pass(service, 100);
        assertEquals("not tapped while off screen", -1, confirmTapAt.get());

        // The sheet comes the rest of the way up: no event of Dasher's, Android's list of windows as it was.
        confirm.setVisibleToUser(true);
        long onScreen = android.os.SystemClock.uptimeMillis();
        pass(service, 50);
        pass(service, 50);
        assertTrue("tapped " + (confirmTapAt.get() - onScreen) + " ms after it came on screen",
                confirmTapAt.get() >= onScreen
                        && confirmTapAt.get() - onScreen <= OfferFilterService.CONFIRM_POLL_MS);
        contains(DiagnosticLog.read(app), "found in Dasher's half of the split screen; requested");
    }

    @Test
    public void dashersSheetSlidingUpIsNotDashersHalfResized() {
        AccessibilityNodeInfo ours = node("com.local.dasherfilter", "", false);
        AccessibilityNodeInfo sheet = question(dasher("Decline offer", true));
        java.util.function.Function<AccessibilityWindowInfo, SplitWindows.Owner> owner =
                QuestionSlidingUpTest::ownerByRoot;
        // Halfway up, then all the way: Dasher's half is its side of the divider either way (49% here).
        assertEquals("win=split/bottom/dasher/49", SplitWindows.field(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, ours, false, TOP_HALF),
                window(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, null, false, DIVIDER),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, sheet, true, SHEET_HALFWAY)), owner, SCREEN));
        assertEquals("win=split/bottom/dasher/49", SplitWindows.field(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, ours, false, TOP_HALF),
                window(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, null, false, DIVIDER),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, sheet, true, BOTTOM_HALF)), owner, SCREEN));
        assertFalse(DiagnosticLog.read(app).contains("Dasher resized"));
    }

    private static SplitWindows.Owner ownerByRoot(AccessibilityWindowInfo window) {
        AccessibilityNodeInfo root = window.getRoot();
        if (root == null) return SplitWindows.Owner.OTHER;
        return "com.doordash.driverapp".contentEquals(root.getPackageName()) ? SplitWindows.Owner.DASHER
                : "com.local.dasherfilter".contentEquals(root.getPackageName()) ? SplitWindows.Owner.OURS
                : SplitWindows.Owner.OTHER;
    }

}

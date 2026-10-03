package com.local.dasherfilter;

import android.app.Application;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Build;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.graphics.Rect;
import java.time.Duration;
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
import org.robolectric.shadows.ShadowAudioManager;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.shadows.ShadowWindowManagerImpl;
import org.robolectric.shadows.ShadowSystemClock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Drives OfferFilterService with synthetic DoorDash accessibility trees and inspects requested clicks. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,35})
@LooperMode(LooperMode.Mode.PAUSED)
public class AccessibilityAdapterTest {
    private Application app;
    private ServiceController<OfferFilterService> controller;
    // Buttons of the most recent screen built by offer(String).
    private AccessibilityNodeInfo decline;
    private AccessibilityNodeInfo accept;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        DecisionLog.forgetCache();
        ReportOutbox.forgetCache();
        OfferSilencer.forgetCache();
        // Reads run on the main looper here, so each event is read before show() returns; ScannerThreadTest runs
        // the service's own thread.
        OfferFilterService.scanLooperForTests = android.os.Looper.getMainLooper();
        controller = Robolectric.buildService(OfferFilterService.class).create();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
    }

    @After
    public void stop() {
        controller.destroy();
        OfferFilterService.sawDasherBeside(0);
        OfferFilterService.scanLooperForTests = null;
    }

    /** A visible, enabled DoorDash node; clickable nodes expose ACTION_CLICK and report clicks as handled. */
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

    /** An offer screen showing {@code money}, a route line, and fresh Accept/Decline buttons. */
    private AccessibilityNodeInfo offer(String money) {
        AccessibilityNodeInfo root = node("", false);
        accept = node("Accept", true);
        decline = node("Decline", true);
        Shadows.shadowOf(root).addChild(node(money, false));
        Shadows.shadowOf(root).addChild(node("2 stops (7.2 mi) • 21 min", false));
        Shadows.shadowOf(root).addChild(accept);
        Shadows.shadowOf(root).addChild(decline);
        return root;
    }

    /**
     * Makes {@code root} the active window and delivers a DoorDash window-state event, which is read at once (a
     * content change while nothing is up may wait for the next quiet read; see ScannerThreadTest).
     */
    private void show(AccessibilityNodeInfo root) {
        TestWindows.full(controller.get(), root);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("com.doordash.driverapp");
        controller.get().onAccessibilityEvent(event);
    }

    /** The service's touch-watch overlays currently on screen (the filter tab is not one). */
    private List<View> overlays() {
        List<View> watches = new java.util.ArrayList<>();
        for (View view : windows()) {
            if (!(view instanceof DasherTab) && !(view instanceof DasherGuide)) watches.add(view);
        }
        return watches;
    }

    private List<View> windows() {
        ShadowWindowManagerImpl windows = Shadow.extract(controller.get().getSystemService(WindowManager.class));
        return windows.getViews();
    }

    /** The filter tab over Dasher, or null. */
    private DasherTab tab() {
        for (View view : windows()) if (view instanceof DasherTab) return (DasherTab) view;
        return null;
    }

    /** A finger landing anywhere on the screen, as Android reports it to a watching overlay. */
    private void touchScreen() {
        assertEquals("one touch watch while declining", 1, overlays().size());
        overlays().get(0).dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_OUTSIDE, 0, 0, 0));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    /** An offer frame Dasher has only partly drawn: the given labels plus Accept and Decline. */
    private AccessibilityNodeInfo partialOffer(String... labels) {
        AccessibilityNodeInfo root = node("", false);
        accept = node("Accept", true);
        decline = node("Decline", true);
        for (String label : labels) Shadows.shadowOf(root).addChild(node(label, false));
        Shadows.shadowOf(root).addChild(accept);
        Shadows.shadowOf(root).addChild(decline);
        return root;
    }

    private AudioManager audio() {
        return app.getSystemService(AudioManager.class);
    }

    /** Players now running, by usage (for example Dasher's offer ring on the alarm stream). */
    private void playing(boolean notify, int... usages) {
        List<AudioAttributes> players = new java.util.ArrayList<>();
        for (int usage : usages) players.add(new AudioAttributes.Builder().setUsage(usage).build());
        ShadowAudioManager shadow = Shadows.shadowOf(audio());
        shadow.setActivePlaybackConfigurationsFor(players, notify);
    }

    private int alarmFloor() {
        return Build.VERSION.SDK_INT >= 28 ? audio().getStreamMinVolume(AudioManager.STREAM_ALARM) : 0;
    }

    /** Taps the declined offer's confirmation, then lets Dasher return to its idle screen. */
    private void finishDecline() {
        show(confirmation(node("Decline offer", true)));
        ShadowSystemClock.advanceBy(Duration.ofMillis(1200));
        show(node("Finding offers", false));
    }

    /** A decline-confirmation dialog containing {@code button}. */
    private AccessibilityNodeInfo confirmation(AccessibilityNodeInfo button) {
        AccessibilityNodeInfo root = node("Are you sure you want to decline this offer?", false);
        Shadows.shadowOf(root).addChild(button);
        return root;
    }

    @Test
    public void failingVisibleOfferRequestsDeclineImmediatelyButNeverAccepts() {
        AccessibilityNodeInfo root = offer("$7.90");
        show(root);
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
        assertTrue(Shadows.shadowOf(accept).getPerformedActions().isEmpty());

        // An immediate repeat event does not click Decline again, and nothing is launched.
        show(root);
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
        assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }

    @Test
    public void unreadableOfferIsReportedOnceAndNeverDeclined() {
        reportsOn();
        AccessibilityNodeInfo root = offer("Guaranteed pay");
        show(root);
        show(root);
        ReportOutbox.flush();

        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        assertEquals(1, ReportOutbox.queued(app));
        assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }

    @Test
    public void nothingIsReportedUntilReportsAreOn() {
        show(offer("Guaranteed pay"));
        show(offer("$7.90"));
        ReportOutbox.flush();

        assertEquals(0, ReportOutbox.queued(app));
    }

    @Test
    public void readableOffersFileNoReport() {
        reportsOn();
        show(offer("$7.90"));
        show(offer("$25.00"));
        ReportOutbox.flush();

        assertEquals(0, ReportOutbox.queued(app));
    }

    // ---- Touching the screen hands the offer back ----

    @Test
    public void touchingTheScreenDuringADeclineStopsItsConfirmation() {
        AccessibilityNodeInfo declined = offer("$7.90");
        show(declined);
        touchScreen();

        assertTrue("the watch goes once the user has taken over", overlays().isEmpty());
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(confirmation(confirm));
        assertTrue(Shadows.shadowOf(confirm).getPerformedActions().isEmpty());
        assertEquals("Offer Filter stopped tapping this offer", ShadowToast.getTextOfLatestToast());
        assertEquals(DecisionLog.Action.USER_TOOK_OVER, DecisionLog.recent(app, 1).get(0).action);
    }

    @Test
    public void anOfferTheUserTookOverIsNeverDeclinedAgain() {
        AccessibilityNodeInfo declined = offer("$7.90");
        show(declined);
        AccessibilityNodeInfo firstDecline = decline;
        touchScreen();

        // The user backs out of the dialog and the same offer returns, still failing the rules.
        ShadowSystemClock.advanceBy(Duration.ofMillis(500));
        show(offer("$7.90"));
        ShadowSystemClock.advanceBy(Duration.ofMillis(500));
        show(declined);
        assertEquals(1, Shadows.shadowOf(firstDecline).getPerformedActions().size());
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
    }

    @Test
    public void aTakeoverHoldsThroughPartlyDrawnFramesOfThatOffer() {
        show(offer("$7.90"));
        touchScreen();

        // Dasher redraws the offer: first the route line without pay, then pay without the route line.
        ShadowSystemClock.advanceBy(Duration.ofMillis(300));
        show(partialOffer("2 stops (7.2 mi) • 21 min"));
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        ShadowSystemClock.advanceBy(Duration.ofMillis(300));
        show(partialOffer("$7.90"));
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(confirmation(confirm));
        assertTrue(Shadows.shadowOf(confirm).getPerformedActions().isEmpty());
        ShadowSystemClock.advanceBy(Duration.ofMillis(300));
        show(offer("$7.90"));
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
    }

    @Test
    public void offersAndDeliveriesMeanDashingUntilDasherSaysTheDashEnded() {
        Dashing.forgetCache();
        controller.get().onServiceConnected();
        assertFalse(Dashing.now(app));
        show(offer("$7.90"));
        assertTrue("an offer on screen means a dash is on", Dashing.now(app));

        show(node("Dash ended", false));
        assertFalse(Dashing.now(app));

        Dashing.forgetCache();
        show(node("Arrived at store", false));
        assertTrue("a delivery screen means a dash is on", Dashing.now(app));
        Dashing.forgetCache();
        show(node("Dash now", false));
        assertFalse("the start screen is not dashing", Dashing.now(app));
    }

    @Test
    public void theFilterTabSitsOverDasherAndPausesOrResumes() {
        controller.get().onServiceConnected();
        show(offer("$25.00"));
        DasherTab tab = tab();
        assertNotNull("the tab is over Dasher", tab);
        assertEquals(FilterHeroView.State.ON, tab.state());
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) tab.getLayoutParams();
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, params.type);
        assertEquals("on the left edge", 0, params.x);
        assertEquals(android.view.Gravity.TOP | android.view.Gravity.START, params.gravity);
        assertTrue("never takes the keyboard or Dasher's focus",
                (params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0);
        // Over an offer only a slim peek shows, and a tap on it is only a touch.
        assertEquals(DasherTab.Look.PEEK, tab.look());
        tab.performClick();
        assertTrue(FilterStore.load(app).enabled);

        show(node("Finding offers", false));
        assertEquals(DasherTab.Look.REST, tab.look());
        tab.performClick();
        assertFalse(FilterStore.load(app).enabled);
        assertTrue("pausing keeps the rules", FilterStore.load(app).hasAnyRule());
        assertEquals(FilterHeroView.State.PAUSED, tab.state());
        assertEquals("Offer Filter: paused. Tap to resume.", tab.getContentDescription().toString());
        tab.performClick();
        assertTrue(FilterStore.load(app).enabled);
        assertEquals("the same tab stays; it is never added twice", 1, windows().size());

        // Dasher leaves the screen: the tab goes with it.
        AccessibilityNodeInfo maps = AccessibilityNodeInfo.obtain(new View(app));
        maps.setPackageName("com.google.android.apps.maps");
        TestWindows.full(controller.get(), maps);
        controller.get().onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED));
        assertNull(tab());

        // Turned off in Settings, it never shows.
        DasherOverlay.setEnabled(app, false);
        show(offer("$25.00"));
        assertNull(tab());
    }

    /** A window on screen as Android lists it to the service. */
    private static AccessibilityWindowInfo window(int type, AccessibilityNodeInfo root, boolean active, Rect bounds) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setType(type);
        if (root != null) shadow.setRoot(root);
        shadow.setActive(active);
        shadow.setBoundsInScreen(bounds);
        return window;
    }

    private AccessibilityNodeInfo appRoot(String packageName) {
        AccessibilityNodeInfo root = AccessibilityNodeInfo.obtain(new View(app));
        root.setPackageName(packageName);
        root.setVisibleToUser(true);
        return root;
    }

    private static final Rect TOP_HALF = new Rect(0, 0, 1080, 1000);
    private static final Rect DIVIDER = new Rect(0, 1000, 1080, 1040);
    private static final Rect BOTTOM_HALF = new Rect(0, 1040, 1080, 2040);

    /** Offer Filter in the top half (the one the user last touched), Dasher showing {@code dasher} in the bottom. */
    private void splitWithDasherBelow(AccessibilityNodeInfo dasher) {
        AccessibilityNodeInfo ours = appRoot("com.local.dasherfilter");
        Shadows.shadowOf(controller.get()).setWindows(java.util.Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, ours, true, TOP_HALF),
                window(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, null, false, DIVIDER),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, dasher, false, BOTTOM_HALF)));
        Shadows.shadowOf(controller.get()).setRootInActiveWindow(ours);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("com.doordash.driverapp");
        controller.get().onAccessibilityEvent(event);
    }

    @Test
    public void inSplitScreenAnOfferInDashersHalfIsDeclinedWhileTheUserIsInTheOtherHalf() {
        controller.get().onServiceConnected();
        splitWithDasherBelow(offer("$7.90"));
        assertEquals("declined at once, though Offer Filter's half is the active one", 1,
                Shadows.shadowOf(decline).getPerformedActions().size());
        assertTrue(Shadows.shadowOf(accept).getPerformedActions().isEmpty());
        assertTrue(OfferFilterService.isDasherForeground());

        // A passing offer there is left for the user, as always.
        splitWithDasherBelow(offer("$25.00"));
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        assertTrue(Shadows.shadowOf(accept).getPerformedActions().isEmpty());
    }

    @Test
    public void withCaptureOnOnlyRecognizedDashersOtherScreensAreKeptForAReport() {
        AccessibilityNodeInfo list = node("", false);
        Shadows.shadowOf(list).addChild(node("Organic bananas", false));
        Shadows.shadowOf(list).addChild(node("Aisle 12", false));
        Shadows.shadowOf(list).addChild(node("Produce", false));
        Dashing.seen(app);
        DiagnosticLog.setEnabled(app, false);
        show(list);
        DiagnosticLog.setEnabled(app, true);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2));
        assertFalse("nothing kept while capture was off", DiagnosticLog.report(app).contains("Aisle 12"));

        show(list);
        String report = DiagnosticLog.report(app);
        assertFalse("an unrecognized screen is not kept even during a dash", report.contains("Organic bananas"));
        assertFalse(report, report.contains("Aisle 12"));

        // A known pickup surface can retain its masked operational wording while capture is on.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2));
        Shadows.shadowOf(list).addChild(node("Complete pickup steps", false));
        show(list);
        report = DiagnosticLog.report(app);
        assertTrue(report, report.contains("Organic bananas") && report.contains("Aisle 12"));
        assertTrue(Shadows.shadowOf(decline == null ? node("", false) : decline).getPerformedActions().isEmpty());
    }

    @Test
    public void aTickingClockOnAnOtherScreenIsKeptAtMostOnceAMinute() {
        Dashing.seen(app);
        for (int minute = 30; minute < 40; minute++) {
            AccessibilityNodeInfo screen = node("", false);
            Shadows.shadowOf(screen).addChild(node("Complete delivery steps", false));
            Shadows.shadowOf(screen).addChild(node("Deliver by 5:" + minute + " PM", false));
            Shadows.shadowOf(screen).addChild(node("Kroger", false));
            show(screen);
            ShadowSystemClock.advanceBy(Duration.ofSeconds(5));
        }
        String kept = DiagnosticLog.readScreens(app);
        assertEquals(kept, 1, kept.split("Deliver by", -1).length - 1);
        // A minute later, the same screen is kept again; a different one at once.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(OfferFilterService.SAME_SCREEN_MS / 1000));
        AccessibilityNodeInfo later = node("", false);
        Shadows.shadowOf(later).addChild(node("Complete delivery steps", false));
        Shadows.shadowOf(later).addChild(node("Deliver by 5:40 PM", false));
        Shadows.shadowOf(later).addChild(node("Kroger", false));
        show(later);
        kept = DiagnosticLog.readScreens(app);
        assertEquals(kept, 2, kept.split("Deliver by", -1).length - 1);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        AccessibilityNodeInfo item = node("", false);
        Shadows.shadowOf(item).addChild(node("Complete pickup steps", false));
        Shadows.shadowOf(item).addChild(node("Organic bananas", false));
        Shadows.shadowOf(item).addChild(node("Aisle 12", false));
        show(item);
        assertTrue(DiagnosticLog.readScreens(app).contains("Aisle 12"));
    }

    @Test
    public void dasherInTheOtherHalfIsRememberedForAMomentOnly() {
        controller.get().onServiceConnected();
        OfferFilterService.sawDasherBeside(0);
        Rect screen = new Rect(0, 0, 1080, 2040);
        AccessibilityNodeInfo idle = node("Finding offers", false);
        Shadows.shadowOf(controller.get()).setWindows(java.util.Collections.singletonList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, idle, true, screen)));
        show(idle);
        assertFalse("Dasher filling the screen is not beside anything", OfferFilterService.dasherBeside());

        splitWithDasherBelow(node("Finding offers", false));
        assertTrue(OfferFilterService.dasherBeside());
        // A moment under the shade or in recent apps does not count as gone; a long absence does.
        Shadows.shadowOf(controller.get()).setWindows(java.util.Collections.emptyList());
        Shadows.shadowOf(controller.get()).setRootInActiveWindow(appRoot("com.android.systemui"));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5));
        assertTrue(OfferFilterService.dasherBeside());
        ShadowSystemClock.advanceBy(Duration.ofMillis(OfferFilterService.BESIDE_MS));
        assertFalse(OfferFilterService.dasherBeside());
    }

    @Test
    public void dasherBehindAnotherAppOrUnderTheShadeIsLeftAlone() {
        controller.get().onServiceConnected();
        // Not split: another app is in front, and Dasher's window is only listed.
        AccessibilityNodeInfo maps = appRoot("com.google.android.apps.maps");
        Shadows.shadowOf(controller.get()).setWindows(java.util.Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, maps, true, new Rect(0, 0, 1080, 2040)),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, offer("$7.90"), false, new Rect(0, 0, 1080, 2040))));
        Shadows.shadowOf(controller.get()).setRootInActiveWindow(maps);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("com.doordash.driverapp");
        controller.get().onAccessibilityEvent(event);
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        assertFalse(OfferFilterService.isDasherForeground());

        // Split, but the shade is down over both halves.
        AccessibilityNodeInfo shade = appRoot("com.android.systemui");
        AccessibilityNodeInfo ours = appRoot("com.local.dasherfilter");
        Shadows.shadowOf(controller.get()).setWindows(java.util.Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_SYSTEM, shade, true, new Rect(0, 0, 1080, 2040)),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, ours, false, TOP_HALF),
                window(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, null, false, DIVIDER),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, offer("$7.90"), false, BOTTOM_HALF)));
        Shadows.shadowOf(controller.get()).setRootInActiveWindow(shade);
        controller.get().onAccessibilityEvent(event);
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        assertNull(tab());
    }

    @Test
    public void theTabIsOverDasherOnlyWhenDasherFillsTheScreen() {
        controller.get().onServiceConnected();
        AccessibilityNodeInfo idle = node("Finding offers", false);
        show(idle);
        Rect screen = new Rect();
        controller.get().getWindows().get(0).getBoundsInScreen(screen);
        DasherTab tab = tab();
        assertNotNull(tab);
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) tab.getLayoutParams();
        assertEquals(Math.round(screen.height() * DasherOverlay.TOP_SHARE), params.y);
        assertTrue("placed in screen coordinates, as Dasher's window bounds are",
                (params.flags & WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN) != 0);

        // Split: Offer Filter's own half has the mascot, so no tab.
        splitWithDasherBelow(node("Finding offers", false));
        assertNull(tab());
    }

    @Test
    public void theBestAreaIsPointedOutOverDasherButNeverOverAnOffer() {
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION,
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
                        java.util.Collections.emptyList()));
            }
        }
        at(37.7749, -122.4194);
        controller.get().onServiceConnected();

        splitWithDasherBelow(node("Finding offers", false));
        DasherGuide guide = null;
        for (View view : windows()) if (view instanceof DasherGuide) guide = (DasherGuide) view;
        assertNotNull("pointed out at the top of Dasher's half", guide);
        assertEquals("3.6 mi NE · $3.00/mi", guide.label());
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) guide.getLayoutParams();
        assertTrue("inside Dasher's half", params.y >= BOTTOM_HALF.top && params.y < BOTTOM_HALF.bottom);
        assertTrue("touches pass through to Dasher",
                (params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0);
        assertTrue("placed in screen coordinates, as Dasher's window bounds are",
                (params.flags & WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN) != 0);
        assertTrue("the guide is not a touch watch", overlays().isEmpty());

        // An offer comes up in Dasher's half: the guide gets out of its way.
        splitWithDasherBelow(offer("$25.00"));
        for (View view : windows()) assertFalse(view instanceof DasherGuide);
    }

    /** The phone's last known position, fresh. */
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
    public void theFilterTabShowsOnlyWhileTheServiceIsConnected() {
        show(offer("$25.00"));
        assertNull("not connected yet", tab());
        controller.get().onServiceConnected();
        show(offer("$25.00"));
        assertNotNull(tab());
        controller.get().onUnbind(null);
        assertNull("gone with the service", tab());
    }

    @Test
    public void theNotificationPathLeavesATakenOverOfferAlone() {
        controller.get().onServiceConnected();
        show(offer("$7.90"));
        touchScreen();

        assertTrue(OfferFilterService.userHasOffer(new OfferSnapshot(790, null, null, null)));
        assertFalse(OfferFilterService.userHasOffer(new OfferSnapshot(610, null, null, null)));
    }

    @Test
    public void aDifferentOfferAfterATakeoverIsJudgedAsUsual() {
        show(offer("$7.90"));
        touchScreen();

        show(offer("$6.10"));
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
    }

    @Test
    public void theTouchWatchRunsOnlyWhileADeclineIsInProgress() {
        show(offer("$25.00"));
        assertTrue("nothing to watch on a passing offer", overlays().isEmpty());

        show(offer("$7.90"));
        assertEquals(1, overlays().size());
        finishDecline();
        assertTrue(overlays().isEmpty());
    }

    // ---- Dasher's own ring while declining ----

    @Test
    public void dasherRingIsTurnedDownWhileDecliningAndRestoredAfter() {
        audio().setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        audio().setStreamVolume(AudioManager.STREAM_MUSIC, 8, 0);
        playing(false, AudioAttributes.USAGE_ALARM);

        show(offer("$7.90"));
        assertEquals(alarmFloor(), audio().getStreamVolume(AudioManager.STREAM_ALARM));
        assertFalse("media was not playing, so it is left alone", audio().isStreamMute(AudioManager.STREAM_MUSIC));

        // Navigation alone never turns media down; a ring starting on the media stream does.
        playing(true, AudioAttributes.USAGE_ALARM, AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE);
        assertFalse(audio().isStreamMute(AudioManager.STREAM_MUSIC));
        playing(true, AudioAttributes.USAGE_ALARM, AudioAttributes.USAGE_MEDIA);
        assertTrue(audio().isStreamMute(AudioManager.STREAM_MUSIC));

        finishDecline();
        assertEquals(5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
        assertFalse(audio().isStreamMute(AudioManager.STREAM_MUSIC));
        assertEquals(8, audio().getStreamVolume(AudioManager.STREAM_MUSIC));
    }

    @Test
    public void passingAndUnreadableOffersAreNeverSilenced() {
        audio().setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        playing(false, AudioAttributes.USAGE_ALARM);

        show(offer("$25.00"));
        show(offer("Guaranteed pay"));
        assertEquals(5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
    }

    @Test
    public void touchingTheScreenBringsTheSoundBack() {
        audio().setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        playing(false, AudioAttributes.USAGE_ALARM);
        show(offer("$7.90"));
        assertEquals(alarmFloor(), audio().getStreamVolume(AudioManager.STREAM_ALARM));

        touchScreen();
        assertEquals(5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
    }

    @Test
    public void aPassingOffersBellOutranksADeclineStillInProgress() {
        audio().setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        playing(false, AudioAttributes.USAGE_ALARM);
        show(offer("$7.90"));
        assertEquals(alarmFloor(), audio().getStreamVolume(AudioManager.STREAM_ALARM));

        // A passing offer arrives by notification before the first decline has finished.
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        assertTrue(OfferAlerts.notifyOffer(app, "passing", null, OfferRule.Result.KEEP, "$25.00", true));
        assertEquals(5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
        // Sound starting while the alert plays is not turned down again.
        playing(true, AudioAttributes.USAGE_ALARM);
        assertEquals(5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
    }

    @Test
    public void aPreviousPassingAlertDoesNotDelaySilencingANewDecline() {
        assertPreviousAlertDoesNotDelayNewDecline(OfferRule.Result.KEEP);
    }

    @Test
    public void aPreviousReviewAlertDoesNotDelaySilencingANewDecline() {
        assertPreviousAlertDoesNotDelayNewDecline(OfferRule.Result.REVIEW);
    }

    private void assertPreviousAlertDoesNotDelayNewDecline(OfferRule.Result result) {
        audio().setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        playing(false, AudioAttributes.USAGE_ALARM);
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        assertTrue(OfferAlerts.notifyOffer(app, "previous", null, result, "Previous offer", true));
        show(offer(result == OfferRule.Result.KEEP ? "$25.00" : "Guaranteed pay"));
        assertEquals("passing or unknown still keeps its sound", 5,
                audio().getStreamVolume(AudioManager.STREAM_ALARM));

        // Another offer is positively judged below the floor, well inside the previous alert's 20-second window.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        show(offer("$7.90"));
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
        assertEquals("a different proven decline must not inherit the old alert's delay", alarmFloor(),
                audio().getStreamVolume(AudioManager.STREAM_ALARM));
    }

    @Test
    public void retriesCannotReclaimSoundFromAnAlertPostedAfterTheDeclineBegan() {
        audio().setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        playing(false, AudioAttributes.USAGE_ALARM);
        AccessibilityNodeInfo failing = offer("$7.90");
        show(failing);
        assertEquals(alarmFloor(), audio().getStreamVolume(AudioManager.STREAM_ALARM));
        ShadowSystemClock.advanceBy(Duration.ofMillis(1));
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        assertTrue(OfferAlerts.notifyOffer(app, "newer", null, OfferRule.Result.REVIEW, "Unclear new offer", true));
        assertEquals(5, audio().getStreamVolume(AudioManager.STREAM_ALARM));

        ShadowSystemClock.advanceBy(Duration.ofMillis(DeclineState.RETRY_INTERVAL_MS));
        show(failing);
        assertEquals("a fresh retry is still the original decline episode", 2,
                Shadows.shadowOf(decline).getPerformedActions().size());
        playing(true, AudioAttributes.USAGE_ALARM);
        assertEquals("the newer alert keeps authority over this old decline", 5,
                audio().getStreamVolume(AudioManager.STREAM_ALARM));
    }

    @Test
    public void aNextOfferOnScreenBringsTheSoundBackEvenUnreadable() {
        audio().setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        playing(false, AudioAttributes.USAGE_ALARM);
        show(offer("$7.90"));
        assertEquals(alarmFloor(), audio().getStreamVolume(AudioManager.STREAM_ALARM));

        // The next offer is only half drawn: its own Accept and pay, no Decline yet. Its ring must be heard.
        AccessibilityNodeInfo next = node("", false);
        Shadows.shadowOf(next).addChild(node("$25.00", false));
        Shadows.shadowOf(next).addChild(node("Accept", true));
        show(next);
        assertEquals(5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
    }

    @Test
    public void aCallStartingMidDeclinePutsTheSoundBack() {
        audio().setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        playing(false, AudioAttributes.USAGE_ALARM);
        show(offer("$7.90"));
        assertEquals(alarmFloor(), audio().getStreamVolume(AudioManager.STREAM_ALARM));

        audio().setMode(AudioManager.MODE_RINGTONE);
        playing(true, AudioAttributes.USAGE_ALARM, AudioAttributes.USAGE_NOTIFICATION_RINGTONE);
        assertEquals(5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
    }

    @Test
    public void ringerNotificationAndSystemStreamsAreNeverTouched() {
        audio().setStreamVolume(AudioManager.STREAM_RING, 5, 0);
        audio().setStreamVolume(AudioManager.STREAM_NOTIFICATION, 5, 0);
        playing(false, AudioAttributes.USAGE_NOTIFICATION_RINGTONE, AudioAttributes.USAGE_NOTIFICATION,
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION);

        show(offer("$7.90"));
        assertFalse(audio().isStreamMute(AudioManager.STREAM_RING));
        assertFalse(audio().isStreamMute(AudioManager.STREAM_NOTIFICATION));
        assertFalse(audio().isStreamMute(AudioManager.STREAM_SYSTEM));
    }

    @Test
    public void aRingingCallIsNeverSilenced() {
        audio().setStreamVolume(AudioManager.STREAM_RING, 5, 0);
        audio().setMode(AudioManager.MODE_RINGTONE);
        playing(false, AudioAttributes.USAGE_NOTIFICATION_RINGTONE);

        show(offer("$7.90"));
        assertFalse(audio().isStreamMute(AudioManager.STREAM_RING));
    }

    @Test
    public void silencingCanBeTurnedOff() {
        FilterStore.setSilenceWhileDeclining(app, false);
        audio().setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        playing(false, AudioAttributes.USAGE_ALARM);

        show(offer("$7.90"));
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
        assertEquals(5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
    }

    @Test
    public void soundLeftDownByACrashIsRestoredAtTheNextStart() {
        audio().setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        playing(false, AudioAttributes.USAGE_ALARM);
        show(offer("$7.90"));
        assertEquals(alarmFloor(), audio().getStreamVolume(AudioManager.STREAM_ALARM));

        // The process dies mid-decline: no stop() runs. The next start puts the sound back.
        Robolectric.buildService(OfferFilterService.class).create();
        assertEquals(5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
    }

    /** Signed in to GitHub, as after Connect GitHub, and "Send problem reports" on: the only way reports go. */
    private void reportsOn() {
        app.getSharedPreferences("github", android.content.Context.MODE_PRIVATE).edit()
                .putString("access_token", "ghu_test").commit();
        ReportOutbox.useGitHub(app, true);
    }

    // ---- A decline that does not finish ----

    @Test
    public void aDeclinedOfferStillShowingAfterFiveSecondsIsReportedOnce() {
        reportsOn();
        AccessibilityNodeInfo stuck = offer("$7.90");
        show(stuck);
        // Taken first-step requests now give Dasher two seconds: requests at 0, 2 and 4 seconds.
        for (int i = 1; i < DeclineState.MAX_ATTEMPTS - 1; i++) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(DeclineState.RETRY_INTERVAL_MS));
            show(stuck);
        }
        ReportOutbox.flush();
        assertEquals("not yet: Dasher may still be closing it", 0, ReportOutbox.queued(app));

        ShadowSystemClock.advanceBy(Duration.ofMillis(OfferFilterService.STUCK_MS
                - (DeclineState.MAX_ATTEMPTS - 2) * DeclineState.RETRY_INTERVAL_MS));
        show(stuck);
        show(stuck);
        ReportOutbox.flush();
        assertEquals(1, ReportOutbox.queued(app));
        // Spend the final retry after its patient interval, then prove the cap still holds before authority expires.
        ShadowSystemClock.advanceBy(Duration.ofMillis(DeclineState.RETRY_INTERVAL_MS));
        show(stuck);
        ShadowSystemClock.advanceBy(Duration.ofMillis(DeclineState.RETRY_INTERVAL_MS));
        show(stuck);
        assertEquals(DeclineState.MAX_ATTEMPTS, Shadows.shadowOf(decline).getPerformedActions().size());
        ReportOutbox.flush();
        assertEquals("later reads and retries never duplicate the report", 1, ReportOutbox.queued(app));
    }

    @Test
    public void pauseRevokesPendingConfirmation() {
        show(offer("$7.90"));
        FilterStore.save(app, new FilterSettings(false, 2000, 0, 0, 0, 0));

        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(confirmation(confirm));
        assertTrue(Shadows.shadowOf(confirm).getPerformedActions().isEmpty());
    }

    @Test
    public void passingScreenRevokesOldConfirmationAuthority() {
        show(offer("$7.90"));
        show(offer("$25.00"));

        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(confirmation(confirm));
        assertTrue(Shadows.shadowOf(confirm).getPerformedActions().isEmpty());
    }

    @Test
    public void declinedOfferConfirmationIsTapped() {
        show(offer("$7.90"));
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());

        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(confirmation(confirm));
        assertEquals(1, Shadows.shadowOf(confirm).getPerformedActions().size());
    }

    @Test
    public void declineAndItsConfirmationAreRecordedAsOneDecision() {
        show(offer("$7.90"));
        show(confirmation(node("Decline offer", true)));

        List<DecisionLog.Entry> recent = DecisionLog.recent(app, 10);
        assertEquals(1, recent.size());
        DecisionLog.Entry entry = recent.get(0);
        assertEquals(OfferRule.Result.DECLINE, entry.result);
        assertEquals(Integer.valueOf(790), entry.facts.payCents);
        assertEquals(2000, entry.requiredCents);
        assertEquals(DecisionLog.Action.CONFIRMATION_TAPPED, entry.action);
        assertTrue(entry.evidence.contains("$7.90"));
    }

    @Test
    public void pausedOfferIsRecordedWithoutAction() {
        FilterStore.save(app, new FilterSettings(false, 2000, 0, 0, 0, 0));
        AccessibilityNodeInfo root = offer("$7.90");
        show(root);
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        assertEquals(DecisionLog.Action.PAUSED, DecisionLog.recent(app, 1).get(0).action);
    }

    @Test
    public void confirmationDialogOverlayingTheDeclinedOfferIsTapped() {
        AccessibilityNodeInfo declined = offer("$7.90");
        show(declined);

        // The sheet is drawn over the original offer, so both sets of labels share one tree.
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        Shadows.shadowOf(declined).addChild(node("Declining this offer may lower your acceptance rate", false));
        Shadows.shadowOf(declined).addChild(node("Go back", true));
        Shadows.shadowOf(declined).addChild(confirm);
        show(declined);
        assertEquals(1, Shadows.shadowOf(confirm).getPerformedActions().size());
    }

    @Test
    public void confirmationAuthorityNeverTapsAPartlyDrawnNextOffer() {
        show(offer("$7.90"));

        // The next offer's Decline is drawn before its pay and Accept button: it is not a confirmation dialog.
        AccessibilityNodeInfo next = node("", false);
        AccessibilityNodeInfo nextDecline = node("Decline", true);
        Shadows.shadowOf(next).addChild(nextDecline);
        show(next);
        assertTrue(Shadows.shadowOf(nextDecline).getPerformedActions().isEmpty());
    }

    @Test
    public void confirmationAuthorityNeverTapsADifferentOfferThatLooksLikeADialog() {
        show(offer("$7.90"));

        // A new, passing offer with a Back button and Decline, whose Accept is not drawn yet.
        AccessibilityNodeInfo next = node("", false);
        AccessibilityNodeInfo nextDecline = node("Decline", true);
        Shadows.shadowOf(next).addChild(node("Back", true));
        Shadows.shadowOf(next).addChild(node("$25.00", false));
        Shadows.shadowOf(next).addChild(node("3 stops (9.1 mi) • 30 min", false));
        Shadows.shadowOf(next).addChild(nextDecline);
        show(next);
        assertTrue(Shadows.shadowOf(nextDecline).getPerformedActions().isEmpty());
    }

    @Test
    public void acceptingAStandaloneOfferRecordsItsPayoutAndBestRates() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0, true, 0));
        show(offer("$25.00"));
        AccessibilityEvent tap = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
        tap.setPackageName("com.doordash.driverapp");
        tap.getText().add("Accept");
        controller.get().onAccessibilityEvent(tap);
        show(node("Arrived at store", false));

        FilterSettings saved = FilterStore.load(app);
        assertEquals(2500, saved.lastAcceptedCents);
        // $25.00 for 21 min, 7.2 mi and 2 stops.
        assertEquals("$1.19/min, $3.47/mi, $12.50/stop", saved.best.summary());
        // Each step is in the log, for a report to show.
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("Accept tap seen on Pay $25.00"));
        assertTrue(log, log.contains("Learned from accepted Pay $25.00"));
    }

    @Test
    public void anAcceptanceThatCannotTeachSaysWhyInTheLog() {
        // Adaptive minimum off: accepted, but not learned.
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0, false, 0));
        show(offer("$25.00"));
        userTaps("Accept");
        show(node("Arrived at store", false));
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
        assertTrue(DiagnosticLog.read(app).contains("but not learned: auto-decline or Adaptive minimum was off"));

        // Adaptive on, but Dasher never shows a delivery screen we know within 15 s.
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0, true, 0));
        show(offer("$26.00"));
        userTaps("Accept");
        ShadowSystemClock.advanceBy(Duration.ofSeconds(16));
        show(node("Heading to Kroger", false));
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("Not learned: no delivery screen recognized within 15 s after Accept on Pay $26.00"));
        assertTrue(log, log.contains(PersonalText.UNKNOWN_NOT_KEPT));
        assertFalse("unknown screen text is excluded from acceptance diagnostics", log.contains("Heading to Kroger"));

        // A tap with no readable offer on screen is noted too.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(120));
        userTaps("Accept");
        assertTrue(DiagnosticLog.read(app).contains("no offer with readable pay was on screen in the last 90 s"));
    }

    /** The user's own tap on a Dasher button, as Android reports it. */
    private void userTaps(String label) {
        AccessibilityEvent tap = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
        tap.setPackageName("com.doordash.driverapp");
        tap.getText().add(label);
        controller.get().onAccessibilityEvent(tap);
    }

    @Test
    public void aManualDeclineTeachesTheClosestMinimumOnceTheNextOfferArrives() {
        // For $14.00 over 7.2 mi, $1.50/mi asks $10.80 and the $7 minimum asks $7: per mile came closest.
        FilterStore.save(app, new FilterSettings(true, 700, 150, 0, 0, 0, true, 0));
        show(offer("$14.00"));
        assertTrue("it passes the rules", Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        userTaps("Decline");
        assertTrue("held until the dash goes on", FilterStore.load(app).declined.isEmpty());

        show(offer("$20.00"));
        DeclinedFloor learned = FilterStore.load(app).declined;
        assertEquals("$1.94/mi", learned.rates.perMileLabel());
        assertEquals("only that rule rises", 0, learned.payCents);

        // An offer like the declined one is now declined; the better one still passes.
        show(offer("$14.00"));
        assertFalse(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        show(offer("$20.00"));
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
    }

    @Test
    public void aDeclineJustBeforeEndingTheDashTeachesNothing() {
        FilterStore.save(app, new FilterSettings(true, 700, 150, 0, 0, 0, true, 0));
        show(offer("$14.00"));
        userTaps("Decline");
        show(node("Dash now", false));
        show(offer("$20.00"));
        assertTrue(FilterStore.load(app).declined.isEmpty());
    }

    @Test
    public void theAppsOwnDeclinesAndDeclinesWhileLearningIsOffTeachNothing() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0, true, 0));
        show(offer("$7.90"));
        assertFalse("the app declines the failing offer", Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        userTaps("Decline");
        show(offer("$25.00"));
        assertTrue(FilterStore.load(app).declined.isEmpty());

        // With the adaptive minimum off, a manual decline of a passing offer is not learned either.
        FilterStore.save(app, new FilterSettings(true, 700, 150, 0, 0, 0, false, 0));
        show(offer("$14.00"));
        userTaps("Decline");
        show(offer("$20.00"));
        assertTrue(FilterStore.load(app).declined.isEmpty());
    }

    @Test
    public void acceptedAddOnUpdatesTheActiveRoute() {
        ActiveRouteStore.save(app, new OfferSnapshot(2500, 10.0, null, 2));
        AccessibilityNodeInfo root = node("", false);
        accept = node("Accept", true);
        decline = node("Decline", true);
        Shadows.shadowOf(root).addChild(node("Add to route", false));
        Shadows.shadowOf(root).addChild(node("+$3.00", false));
        Shadows.shadowOf(root).addChild(node("+1 mi", false));
        Shadows.shadowOf(root).addChild(accept);
        Shadows.shadowOf(root).addChild(decline);
        show(root);
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());

        AccessibilityEvent tap = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
        tap.setPackageName("com.doordash.driverapp");
        tap.getText().add("Accept");
        controller.get().onAccessibilityEvent(tap);
        show(node("Arrived at store", false));

        OfferSnapshot route = ActiveRouteStore.load(app);
        assertEquals(Integer.valueOf(2800), route.payCents);
        assertEquals(11.0, route.miles, 0.001);
    }

    @Test
    public void swallowedConfirmationTapIsRetriedPatientlyAfterABriefMiss() {
        show(offer("$7.90"));
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(confirmation(confirm));
        assertEquals(1, Shadows.shadowOf(confirm).getPerformedActions().size());

        // One read misses the dialog during a transition; the dialog is still there afterwards.
        ShadowSystemClock.advanceBy(Duration.ofMillis(300));
        show(node("Loading", false));
        ShadowSystemClock.advanceBy(Duration.ofMillis(300));
        show(confirmation(confirm));
        assertEquals("a handled request waits for Dasher to respond before retrying", 1,
                Shadows.shadowOf(confirm).getPerformedActions().size());
        ShadowSystemClock.advanceBy(Duration.ofMillis(2_000));
        show(confirmation(confirm));
        assertEquals(2, Shadows.shadowOf(confirm).getPerformedActions().size());
    }

    @Test
    public void offerScreenWithABackButtonIsStillJudged() {
        AccessibilityNodeInfo root = offer("$7.90");
        Shadows.shadowOf(root).addChild(node("Back", true));
        show(root);
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
    }

    @Test
    public void confirmationAuthorityNeverTapsANextOfferWhosePayIsNotDrawnYet() {
        show(offer("$7.90"));

        // The next offer has Accept, Decline and Back, but its pay is not readable yet.
        AccessibilityNodeInfo next = node("", false);
        AccessibilityNodeInfo nextDecline = node("Decline", true);
        Shadows.shadowOf(next).addChild(node("Back", true));
        Shadows.shadowOf(next).addChild(node("Accept", true));
        Shadows.shadowOf(next).addChild(nextDecline);
        show(next);
        assertTrue(Shadows.shadowOf(nextDecline).getPerformedActions().isEmpty());
    }

    @Test
    public void deliveryScreenEndsConfirmationAuthority() {
        show(offer("$7.90"));
        // Declining an add-on returns straight to the delivery; no confirmation is coming.
        show(node("Confirm pickup", false));
        AccessibilityNodeInfo later = node("Decline", true);
        AccessibilityNodeInfo root = node("", false);
        Shadows.shadowOf(root).addChild(node("Go back", true));
        Shadows.shadowOf(root).addChild(later);
        show(root);
        assertTrue(Shadows.shadowOf(later).getPerformedActions().isEmpty());
    }

    @Test
    public void confirmationAuthorityEndsOnceTheDialogCloses() {
        show(offer("$7.90"));
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(confirmation(confirm));
        assertEquals(1, Shadows.shadowOf(confirm).getPerformedActions().size());

        // The dialog closed; a later dialog within the old 10-second window is not ours to confirm.
        ShadowSystemClock.advanceBy(Duration.ofMillis(1200));
        show(node("Loading", false));
        ShadowSystemClock.advanceBy(Duration.ofMillis(300));
        AccessibilityNodeInfo later = node("Decline offer", true);
        show(confirmation(later));
        assertTrue(Shadows.shadowOf(later).getPerformedActions().isEmpty());
    }

    @Test
    public void malformedMoneyCannotTriggerScreenDecline() {
        show(offer("$7.901"));
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
    }

    @Test
    public void anotherAppCannotBecomeADasherOffer() {
        AccessibilityNodeInfo root = offer("$7.90");
        root.setPackageName("com.example.other");
        show(root);
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
    }

    @Test
    public void sharedAcceptDeclineContainerIsNotAClickTarget() {
        AccessibilityNodeInfo root = node("", false);
        AccessibilityNodeInfo shared = node("", true);
        Shadows.shadowOf(root).addChild(node("$7.90", false));
        Shadows.shadowOf(root).addChild(shared);
        Shadows.shadowOf(shared).addChild(node("Accept", false));
        Shadows.shadowOf(shared).addChild(node("Decline", false));
        show(root);
        assertTrue(Shadows.shadowOf(shared).getPerformedActions().isEmpty());
    }

    @Test
    public void anOfferWhoseTotalAndPlusAmountTogetherStillFailIsDeclinedAtOnce() {
        // The report's rules and its "+$1 · $7.35" screen: pay is $7.35, $8.35 or $1, all below $10.
        FilterStore.save(app, new FilterSettings(true, 1000, 100, 0, 0, 3, true, 0));
        show(partialOffer("+$1", "$7.35", "incl. tips", "2 stops (7.1 mi) • 23 min",
                "Guaranteed earnings for completing the offer."));
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
        assertTrue(Shadows.shadowOf(accept).getPerformedActions().isEmpty());
        DecisionLog.Entry entry = DecisionLog.recent(app, 1).get(0);
        assertEquals(OfferRule.Result.DECLINE, entry.result);
        assertEquals(DecisionLog.Action.DECLINE_TAPPED, entry.action);
        assertNull("pay is still unknown", entry.facts.payCents);
    }

    @Test
    public void anOfferWithAPlusAmountThatMayPassIsLeftToTheDasher() {
        FilterStore.save(app, new FilterSettings(true, 1000, 100, 0, 0, 3, true, 0));
        show(partialOffer("+$1", "$10.60", "incl. tips", "2 stops (5.3 mi) • 30 min"));
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        DecisionLog.Entry entry = DecisionLog.recent(app, 1).get(0);
        assertEquals(OfferRule.Result.REVIEW, entry.result);
        assertEquals("pay unclear beside a +$ amount", entry.reason);

        // Accepting it teaches the adaptive minimum nothing: its pay was never read.
        userTaps("Accept");
        show(node("Arrived at store", false));
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
    }
}

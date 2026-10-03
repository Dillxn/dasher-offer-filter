package com.local.dasherfilter;

import android.app.Application;
import android.app.KeyguardManager;
import android.media.AudioManager;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
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
import org.robolectric.shadows.ShadowWindowManagerImpl;
import static org.junit.Assert.*;

/** Actual service adapter, synthetic Android windows. No physical-phone or DoorDash server success claimed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public final class AutoAcceptAdapterTest {
    private Application app;
    private ServiceController<OfferFilterService> controller;
    private OfferFilterService service;
    private AccessibilityNodeInfo accept, decline;
    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0));
        FilterStore.setAutoAcceptEnabled(app, true);
        ActiveRouteStore.clear(app); RestartSuppression.clear(app); AutoAcceptMemory.clear(app);
        DiagnosticLog.clear(app); DecisionLog.forgetCache(); DecisionLog.clear(app);
        OfferSilencer.forgetCache();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        OfferFilterService.forgetScreenState();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        connect();
    }
    private void connect() {
        controller = Robolectric.buildService(OfferFilterService.class).create();
        service = controller.get(); service.onServiceConnected(); pass(0);
    }
    @After public void teardown() {
        ScannerThreadTest.RefusingWindowManager.refuse = false;
        OfferFilterService.nodeFetchForTests = null;
        controller.destroy(); OfferFilterService.scanLooperForTests = null; OfferFilterService.forgetScreenState();
    }
    @Test public void waitsForQuietFreshReadRequestsOnceAndDoesNotClaimAcceptance() {
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30"));
        assertEquals(0, clicks(accept)); pass(500); assertEquals(0, clicks(accept));
        pass(400); assertEquals(1, clicks(accept)); assertEquals(0, clicks(decline));
        assertNull(ActiveRouteStore.load(app));
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
        assertTrue(DiagnosticLog.read(app).contains("Accept REQUESTED"));
        assertEquals(DecisionLog.Action.PASSES, DecisionLog.recent(app, 1).get(0).action);
        pass(500); show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:29")); pass(1000);
        assertEquals(0, clicks(accept)); assertEquals(0, clicks(decline));
    }
    @Test public void disabledDefaultAndPausedRulesNeverAccept() {
        FilterStore.setAutoAcceptEnabled(app, false);
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30")); pass(1200); assertEquals(0, clicks(accept));
        FilterStore.setAutoAcceptEnabled(app, true); FilterStore.save(app, FilterStore.load(app).withEnabled(false));
        show(offer("$22.00", "2 stops (4 mi) • 20 min", "0:30")); pass(1200); assertEquals(0, clicks(accept));
    }
    @Test public void incompleteUnknownItemsAndNoCountdownNeverAccept() {
        show(offer("$20.00", "2 stops", "0:30")); pass(1000); assertEquals(0, clicks(accept));
        show(offer("$21.00", "2 stops (4 mi) • 20 min", "")); pass(1000); assertEquals(0, clicks(accept));
        AccessibilityNodeInfo shopping = offer("$22.00", "2 stops (4 mi) • 20 min", "0:30");
        Shadows.shadowOf(shopping).addChild(node("Shop and deliver", false));
        show(shopping); pass(1000); assertEquals(0, clicks(accept));
    }
    @Test public void addOnAndStoredRouteNeverAccept() {
        AccessibilityNodeInfo addOn = offer("+$20.00", "Additional 2 miles · 10 min", "0:30");
        Shadows.shadowOf(addOn).addChild(node("Add to route", false)); show(addOn); pass(1000); assertEquals(0, clicks(accept));
        ActiveRouteStore.save(app, new OfferSnapshot(1000, 3.0, 20, 2));
        show(offer("$21.00", "2 stops (4 mi) • 20 min", "0:30")); pass(1000); assertEquals(0, clicks(accept));
    }
    @Test public void fingerAndNonAcceptClickCancelTheWholeOffer() {
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30")); pass(300); touch(); pass(1200);
        assertEquals(0, clicks(accept));
        AccessibilityNodeInfo next = offer("$21.00", "2 stops (4 mi) • 20 min", "0:30"); show(next); pass(200);
        click(node("View offer details", true)); pass(1200); assertEquals(0, clicks(accept));
    }
    @Test public void phoneLockOffAndCallCancelBeforeTap() {
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30"));
        Shadows.shadowOf(app.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        pass(900); assertEquals(0, clicks(accept));
        Shadows.shadowOf(app.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        show(offer("$21.00", "2 stops (4 mi) • 20 min", "0:30"));
        Shadows.shadowOf(app.getSystemService(PowerManager.class)).setIsInteractive(false);
        pass(900); assertEquals(0, clicks(accept));
        Shadows.shadowOf(app.getSystemService(PowerManager.class)).setIsInteractive(true);
        app.getSystemService(AudioManager.class).setMode(AudioManager.MODE_IN_COMMUNICATION);
        show(offer("$22.00", "2 stops (4 mi) • 20 min", "0:30")); pass(900); assertEquals(0, clicks(accept));
    }
    @Test public void settingsChangedDuringFinalMetadataCannotTap() {
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30"));
        boolean[] changed = {false};
        service.windowSource = () -> {
            if (!changed[0] && calledFrom("dasherStillReadable")) {
                changed[0] = true;
                FilterStore.save(app, FilterStore.load(app).withMinimums(new int[]{3000, 0, 0, 0}));
            }
            return service.getWindows();
        };
        pass(1000); assertTrue(changed[0]); assertEquals(0, clicks(accept)); assertEquals(0, clicks(decline));
    }
    @Test public void switchDisabledDuringQuietIntervalCannotTap() {
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30")); pass(300);
        FilterStore.setAutoAcceptEnabled(app, false); pass(900); assertEquals(0, clicks(accept));
    }
    @Test public void consentRevokedDuringQuietIntervalCannotReadOrTap() {
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30")); pass(200);
        app.getSharedPreferences(Consent.PREFS, 0).edit().clear().commit();
        pass(900); assertEquals(0, clicks(accept));
    }
    @Test public void ourDirectTouchCancelsWithoutAnOutsideTouchCallback() {
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30")); pass(200);
        OfferFilterService.ownScreenTouched(SystemClock.uptimeMillis()); pass(900);
        assertEquals(0, clicks(accept));
    }
    @Test @Config(shadows = ScannerThreadTest.RefusingWindowManager.class)
    public void missingTouchWatchFailsClosed() {
        ScannerThreadTest.RefusingWindowManager.refuse = true;
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30")); pass(3500);
        assertEquals(0, clicks(accept));
    }
    @Test public void eventArrivingInsideFinalMetadataInvalidatesSameWindowNode() {
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30"));
        boolean[] injected = {false};
        service.windowSource = () -> {
            if (!injected[0] && calledFrom("dasherStillReadable")) {
                injected[0] = true;
                AccessibilityEvent changed = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
                changed.setPackageName("com.doordash.driverapp"); changed.setEventTime(SystemClock.uptimeMillis());
                java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
                Thread delivery = new Thread(() -> {
                    try { service.onAccessibilityEvent(changed); } catch (Throwable error) { failure.set(error); }
                });
                delivery.start();
                try { delivery.join(5000); } catch (InterruptedException e) { throw new AssertionError(e); }
                assertFalse(delivery.isAlive()); if (failure.get() != null) throw new AssertionError(failure.get());
            }
            return service.getWindows();
        };
        pass(1000); assertTrue(injected[0]); assertEquals(0, clicks(accept));
    }
    @Test public void refusedClickNeverRetriesOrClaimsAccepted() {
        AccessibilityNodeInfo root = offer("$20.00", "2 stops (4 mi) • 20 min", "0:30");
        Shadows.shadowOf(accept).setOnPerformActionListener((a, b) -> false);
        show(root); pass(900); assertEquals(1, clicks(accept));
        show(root); pass(900); assertEquals(1, clicks(accept)); assertNull(ActiveRouteStore.load(app));
        assertTrue(DiagnosticLog.read(app).contains("Accept REFUSED"));
    }
    @Test public void observedDeliveryUpdatesRouteAndOutcomeWithoutTeachingAdaptiveMinimums() {
        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0, true, 1500));
        assertTrue(FilterStore.recordAccepted(app, new OfferSnapshot(1500, null, null, null)));
        show(node("Finding offers", false));
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30")); pass(900); assertEquals(1, clicks(accept));
        click(accept); show(node("Arrived at store", true));
        assertNotNull(ActiveRouteStore.load(app)); assertEquals(Integer.valueOf(2000), ActiveRouteStore.load(app).payCents);
        assertEquals(1500, FilterStore.load(app).lastAcceptedCents);
        assertTrue(FilterStore.load(app).best.isEmpty());
        assertTrue(DiagnosticLog.read(app).contains("automatic choices never raise your learned minimums"));
    }
    @Test public void serviceRestartSuppressesRepeatAndRetainsAutomaticLearningProvenance() {
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30")); pass(900); assertEquals(1, clicks(accept));
        controller.destroy(); connect();
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:29")); pass(900);
        assertEquals(0, clicks(accept)); assertEquals(0, clicks(decline));
        assertTrue(AutoAcceptMemory.covers(app, new OfferSnapshot(2000, 4.0, 20, 2)));
    }
    @Test public void ownClickEchoIsNotAUserLessonAndFailedTransitionNeverLearns() {
        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0, true, 1500));
        assertTrue(FilterStore.recordAccepted(app, new OfferSnapshot(1500, null, null, null)));
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30")); pass(900); click(accept);
        show(node("Map", false)); pass(16_000);
        assertNull(ActiveRouteStore.load(app)); assertEquals(1500, FilterStore.load(app).lastAcceptedCents);
    }
    private void touch() {
        ShadowWindowManagerImpl windows = Shadow.extract(service.getSystemService(WindowManager.class));
        for (View view : new java.util.ArrayList<>(windows.getViews())) {
            if (view instanceof DasherTab || view instanceof DasherGuide) continue;
            long now = SystemClock.uptimeMillis();
            MotionEvent event = MotionEvent.obtain(now, now, MotionEvent.ACTION_OUTSIDE, 0, 0, 0);
            view.dispatchTouchEvent(event); event.recycle();
        }
        pass(0);
    }
    private void click(AccessibilityNodeInfo n) {
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
        event.setPackageName("com.doordash.driverapp"); event.setEventTime(SystemClock.uptimeMillis());
        ((ShadowAccessibilityRecord) Shadow.extract(event)).setSourceNode(n);
        service.onAccessibilityEvent(event); pass(0);
    }
    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo n = AccessibilityNodeInfo.obtain(new View(app)); n.setPackageName("com.doordash.driverapp");
        n.setText(text); n.setVisibleToUser(true); n.setEnabled(true); n.setClickable(clickable);
        if (clickable) { n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK); Shadows.shadowOf(n).setOnPerformActionListener((a, b) -> true); }
        return n;
    }
    private AccessibilityNodeInfo offer(String pay, String metrics, String countdown) {
        AccessibilityNodeInfo n = node("", false); accept = node("Accept", true); decline = node("Decline", true);
        for (AccessibilityNodeInfo child : new AccessibilityNodeInfo[]{decline, node(pay, false), node(metrics, false), accept, node(countdown, false)}) Shadows.shadowOf(n).addChild(child);
        return n;
    }
    private void show(AccessibilityNodeInfo root) {
        TestWindows.full(service, root);
        ((ShadowAccessibilityWindowInfo) Shadow.extract(service.getWindows().get(0))).setFocused(true);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("com.doordash.driverapp"); event.setEventTime(SystemClock.uptimeMillis());
        service.onAccessibilityEvent(event); pass(0);
    }
    private static boolean calledFrom(String method) {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) if (frame.getClassName().equals(OfferFilterService.class.getName()) && frame.getMethodName().equals(method)) return true;
        return false;
    }
    private static int clicks(AccessibilityNodeInfo n) { return Shadows.shadowOf(n).getPerformedActions().size(); }
    private static void pass(long ms) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }
}

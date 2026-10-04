package com.local.dasherfilter;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

/** Lifecycle failures and reconnects using synthetic screens, not live handset certification. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public final class ScannerRobustnessTest {
    private Application app;
    private ServiceController<OfferFilterService> controller;
    private OfferFilterService service;
    private AccessibilityNodeInfo decline;
    private AccessibilityNodeInfo accept;
    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        DiagnosticLog.clear(app);
        DiagnosticLog.setEnabled(app, true);
        RestartSuppression.clear(app);
        ScannerFailure.clear(app);
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        OfferFilterService.forgetScreenState();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        connect();
    }
    @After public void teardown() {
        if (controller != null) controller.destroy();
        OfferFilterService.scanLooperForTests = null;
        OfferFilterService.nodeFetchForTests = null;
        OfferFilterService.forgetScreenState();
    }
    private void connect() {
        controller = Robolectric.buildService(OfferFilterService.class).create();
        service = controller.get();
        service.onServiceConnected();
        pass(0);
    }
    private void reconnect() { controller.destroy(); connect(); }

    @Test public void windowWatchStopsOffScreenAndResumesFromAFreshRead() {
        show(node("Finding offers", false));
        AtomicInteger looks = new AtomicInteger();
        service.windowSource = () -> { looks.incrementAndGet(); return service.getWindows(); };
        turnScreen(false);
        int offLooks = looks.get(), offRoots = service.rootFetches;
        pass(4_000);
        assertEquals("no window polling while off", offLooks, looks.get());
        assertEquals("no off-screen app roots", offRoots, service.rootFetches);
        turnScreen(true);
        pass(600);
        assertTrue(looks.get() > offLooks);
        assertTrue(service.rootFetches > offRoots);
    }

    @Test public void aNewDasherWindowHidesOldOverlayControlsBeforeItsFirstNodeRead() {
        show(node("Finding offers", false));
        assertTrue(service.overlay().isShowing());
        AtomicBoolean hiddenDuringRead = new AtomicBoolean();
        OfferFilterService.nodeFetchForTests = () -> {
            hiddenDuringRead.set(!service.overlay().isShowing());
            OfferFilterService.nodeFetchForTests = null;
        };
        show(offer("$7.90", "0:35"));
        assertTrue("the old tab and guide cannot intercept the new offer while Dasher is slow", hiddenDuringRead.get());
        assertEquals("touch watch is separate from hidden page overlays", 1, clicks(decline));
    }

    @Test public void screenOffRevokesConfirmationAndDoesNotRetapTheSameOfferAfterUnlock() {
        AccessibilityNodeInfo offered = offer("$7.90", "0:35");
        show(offered);
        assertEquals(1, clicks(decline));
        AccessibilityNodeInfo firstDecline = decline;
        turnScreen(false);
        pass(3_000);
        // The same offer counts down while off; a reset to 0:35 would be a proven fresh instance.
        AccessibilityNodeInfo remaining = offer("$7.90", "0:31");
        TestWindows.full(service, remaining);
        turnScreen(true);
        show(remaining);
        assertEquals(1, clicks(firstDecline));
        assertEquals(0, clicks(decline));
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(question(confirm));
        assertEquals(0, clicks(confirm));
        assertTrue(DiagnosticLog.read(app).contains("screen off or locked"));
    }

    @Test public void restartPreservesManualTakeoverButNeverPendingConfirmation() {
        show(offer("$7.90", "0:35"));
        pass(300);
        AccessibilityEvent click = event(AccessibilityEvent.TYPE_VIEW_CLICKED);
        click.setEventTime(SystemClock.uptimeMillis());
        ((ShadowAccessibilityRecord) Shadow.extract(click)).setSourceNode(accept);
        service.onAccessibilityEvent(click);
        pass(0);
        reconnect();
        show(offer("$7.90", "0:34"));
        assertEquals(0, clicks(decline));
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(question(confirm));
        assertEquals(0, clicks(confirm));
        assertTrue(DiagnosticLog.read(app).contains("no tap authority restored"));
        show(offer("$6.10", "0:35"));
        assertEquals("a clearly different offer is evaluated normally", 1, clicks(decline));
    }

    @Test public void restartDuringARequestLeavesItsOldOfferAndQuestionToTheUser() {
        show(offer("$7.90", "0:20"));
        reconnect();
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(question(confirm));
        assertEquals(0, clicks(confirm));
        show(offer("$7.90", "0:19"));
        assertEquals(0, clicks(decline));
        show(offer("$7.90", "0:45"));
        assertEquals("a fresh countdown identifies a new instance", 1, clicks(decline));
    }

    @Test public void acceptingANewNoticeAfterReconnectStillRestoresOnlySuppression() {
        show(offer("$7.90", "0:35"));
        app.getSharedPreferences(Consent.PREFS, Context.MODE_PRIVATE).edit()
                .remove(Consent.ACCEPTED_VERSION).commit();
        reconnect();
        show(offer("$7.90", "0:34"));
        assertEquals(0, clicks(decline));
        Consent.accept(app);
        show(offer("$7.90", "0:33"));
        assertEquals("first newly permitted read restores suppression before tapping", 0, clicks(decline));
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(question(confirm));
        assertEquals(0, clicks(confirm));
    }

    @Test public void guardedCallbackFailureStopsTapsAndLeavesOnlyAMinimalReconnectMarker() throws Exception {
        show(offer("$7.90", "0:35"));
        Field field = OfferFilterService.class.getDeclaredField("scanner");
        field.setAccessible(true);
        ((Handler) field.get(service)).post(() -> { throw new IllegalStateException("PRIVATE_ACCOUNT_SENTINEL"); });
        pass(0);
        assertFalse(OfferFilterService.isConnected());
        assertTrue(FilterStore.lastStatus(app).contains("Screen reading stopped"));
        assertEquals("IllegalStateException", app.getSharedPreferences(ScannerFailure.PREFS, Context.MODE_PRIVATE)
                .getString("kind", ""));
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(question(confirm));
        assertEquals(0, clicks(confirm));
        reconnect();
        assertTrue(OfferFilterService.isConnected());
        assertTrue(DiagnosticLog.read(app).contains("reconnected after a scanner failure (IllegalStateException)"));
        assertFalse(DiagnosticLog.read(app).contains("PRIVATE_ACCOUNT_SENTINEL"));
    }

    @Test public void mixedWaitingAndDeliveryDoesNotEraseTheRouteOrSayFindingOffers() {
        OfferSnapshot route = new OfferSnapshot(2400, 5.0, 20, 2);
        ActiveRouteStore.save(app, route);
        Dashing.seen(app);
        AccessibilityNodeInfo mixed = node("Looking for offers", false);
        Shadows.shadowOf(mixed).addChild(node("Pick up by 7:52 PM", false));
        Shadows.shadowOf(mixed).addChild(node("Arrived at store", false));
        show(mixed);
        assertNotNull("a contradictory waiting label must not erase known delivery context", ActiveRouteStore.load(app));
        assertTrue(Dashing.awaitingEnd(app));
        assertFalse(FilterStore.lastStatus(app).contains("Dasher is finding offers"));
        assertFalse(DiagnosticLog.read(app).contains("stored route cleared"));
        pass(1_100);
        show(node("Looking for offers", false));
        assertNull("a later unambiguous wait still completes the route", ActiveRouteStore.load(app));
        assertTrue(DiagnosticLog.read(app).contains("stored route cleared: waiting; no delivery marker"));
    }

    @Test public void mixedDashHomeAndDeliveryDoesNotReleaseTheAutomaticUpdateHold() {
        ActiveRouteStore.save(app, new OfferSnapshot(2400, 5.0, 20, 2));
        Dashing.seen(app);
        AccessibilityNodeInfo mixed = node("Dash now", false);
        Shadows.shadowOf(mixed).addChild(node("Arrived at store", false));
        show(mixed);
        assertNotNull(ActiveRouteStore.load(app));
        assertTrue("conflicting route evidence is not a completed dash", Dashing.awaitingEnd(app));
        pass(1_100);
        show(node("Dash now", false));
        assertNull(ActiveRouteStore.load(app));
        assertFalse(Dashing.awaitingEnd(app));
    }

    @Test public void pausedScreenIsCapturedTruthfullyAndDoesNotReleaseTheUpdateHold() {
        show(node("Finding offers", false));
        pass(1_100);
        show(node("Dash paused", false));
        assertTrue(Dashing.isPaused(app));
        assertTrue(Dashing.awaitingEnd(app));
        assertFalse(ScreenAwake.wanted(app));
        assertTrue(FilterStore.lastStatus(app).contains("Dasher is paused"));
        assertTrue(DiagnosticLog.readScreens(app).contains("Dash paused"));
        pass(1_100);
        show(node("Dash now", false));
        assertFalse(Dashing.awaitingEnd(app));
        assertTrue(FilterStore.lastStatus(app).contains("dash ended"));
    }

    @Test public void explainedDashTotalsCoalesceButNewOfferControlsStayUrgent() {
        AccessibilityNodeInfo summary = node("This dash so far", false);
        Shadows.shadowOf(summary).addChild(node("$0.00", false));
        show(summary);
        pass(OfferFilterService.QUIET_SCAN_GAP_MS);
        AtomicInteger reads = new AtomicInteger();
        OfferFilterService.nodeFetchForTests = reads::incrementAndGet;
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
        int first = reads.get();
        assertTrue(first > 0);
        for (int i = 0; i < 12; i++) {
            service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
        }
        assertEquals("the explained total is not a partial offer needing every redraw", first, reads.get());
        AccessibilityNodeInfo incoming = offer("$7.90", "0:35");
        Shadows.shadowOf(incoming).addChild(node("This dash so far", false));
        show(incoming);
        assertEquals("real offer controls override a remaining summary label", 1, clicks(decline));
    }

    @Test public void launcherChoosesMainLauncherInsteadOfAnInfoActivity() throws Exception {
        String pkg = "example.launchable";
        ComponentName info = new ComponentName(pkg, pkg + ".Info");
        ComponentName launch = new ComponentName(pkg, pkg + ".Home");
        installed(info, Intent.CATEGORY_INFO);
        installed(launch, Intent.CATEGORY_LAUNCHER);
        Method method = OfferFilterService.class.getDeclaredMethod("launcher", String.class);
        method.setAccessible(true);
        assertEquals(launch, method.invoke(service, pkg));
    }

    @Test public void aPeekRuntimeFailurePersistsTheOffSwitchWithoutLeakingTheExceptionMessage() throws Exception {
        FilterStore.setPeek(app, true);
        Method method = OfferFilterService.class.getDeclaredMethod("peekFailed", String.class, RuntimeException.class);
        method.setAccessible(true);
        method.invoke(service, "test", new IllegalStateException("PRIVATE_ACCOUNT_SENTINEL"));
        assertFalse(FilterStore.peek(app));
        reconnect();
        assertFalse(FilterStore.peek(app));
        assertFalse(DiagnosticLog.read(app).contains("PRIVATE_ACCOUNT_SENTINEL"));
    }

    private void installed(ComponentName component, String category) throws Exception {
        ShadowPackageManager packages = Shadows.shadowOf(app.getPackageManager());
        ActivityInfo info = packages.addActivityIfNotPresent(component);
        info.enabled = true;
        info.exported = true;
        IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
        filter.addCategory(category);
        packages.addIntentFilterForActivity(component, filter);
    }
    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo result = AccessibilityNodeInfo.obtain(new View(app));
        result.setPackageName("com.doordash.driverapp");
        result.setText(text);
        result.setVisibleToUser(true);
        result.setEnabled(true);
        result.setClickable(clickable);
        if (clickable) {
            result.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            Shadows.shadowOf(result).setOnPerformActionListener((action, args) -> true);
        }
        return result;
    }
    private AccessibilityNodeInfo offer(String pay, String countdown) {
        AccessibilityNodeInfo root = node("", false);
        decline = node("Decline", true); accept = node("Accept", true);
        for (AccessibilityNodeInfo child : new AccessibilityNodeInfo[]{decline, node(pay, false),
                node("2 stops (7.2 mi) • 21 min", false), accept, node(countdown, false)}) {
            Shadows.shadowOf(root).addChild(child);
        }
        return root;
    }
    private AccessibilityNodeInfo question(AccessibilityNodeInfo confirm) {
        AccessibilityNodeInfo root = node("", false);
        Shadows.shadowOf(root).addChild(node("Are you sure you want to decline this offer?", false));
        Shadows.shadowOf(root).addChild(confirm);
        return root;
    }
    private AccessibilityEvent event(int type) {
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setPackageName("com.doordash.driverapp");
        return event;
    }
    private void show(AccessibilityNodeInfo root) {
        TestWindows.full(service, root);
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        pass(0);
    }
    private void turnScreen(boolean on) {
        Shadows.shadowOf(app.getSystemService(PowerManager.class)).setIsInteractive(on);
        app.sendBroadcast(new Intent(on ? Intent.ACTION_SCREEN_ON : Intent.ACTION_SCREEN_OFF));
        pass(0);
    }
    private static int clicks(AccessibilityNodeInfo node) { return Shadows.shadowOf(node).getPerformedActions().size(); }
    private static void pass(long ms) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }
}

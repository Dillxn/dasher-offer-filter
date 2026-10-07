package com.local.dasherfilter;

import android.accessibilityservice.AccessibilityService;
import android.app.Application;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
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

import static org.junit.Assert.*;

/**
 * Item facts never manufacture automatic-tap, Back or completed-offer evidence. 0.5.0 retired per item as a rule, but
 * item counts are still read (AGENTS: item-only offer evidence resets the empty-screen interval; an item count keeps a
 * partial offer open), so these cases from 0.4.72's suite stand, on the 0.5.0 rules: a $20.00 minimum pay, which
 * declines the $7.90 offers here. Synthetic Dasher screens; not a claim about a real shopping offer.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public final class ItemAuthorityAdapterTest {
    private static final String DASHER = "com.doordash.driverapp";
    private Application app;
    private ServiceController<OfferFilterService> controller;
    private OfferFilterService service;
    private AccessibilityNodeInfo decline;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        FilterStore.save(app, FilterSettings.of(true, 2000, 0, 0, 0));
        DiagnosticLog.clear(app);
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        RestartSuppression.clear(app);
        ActiveRouteStore.clear(app);
        OfferSilencer.forgetCache();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        OfferFilterService.forgetScreenState();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        controller = Robolectric.buildService(OfferFilterService.class).create();
        service = controller.get();
        service.onServiceConnected();
        pass(0);
    }

    @After public void teardown() {
        controller.destroy();
        OfferFilterService.scanLooperForTests = null;
        OfferFilterService.forgetScreenState();
    }

    @Test public void itemOnlyPartialIdleDoesNotCompleteOrEndThePendingOffer() throws Exception {
        show(offer("$7.90", "10 items", "0:35"));
        pass(300);
        assertEquals("declined at once", 1, clicks(decline));
        show(labels("Finding offers", "10 items"));
        pass(1_500);
        assertTrue("underlying idle words cannot prove this partial offer ended",
                state().hasPendingConfirmation(SystemClock.uptimeMillis()));
        assertEquals(DecisionLog.Action.DECLINE_TAPPED, DecisionLog.recent(app, 1).get(0).action);
        assertTrue((Boolean) field("offerEvidence"));
        assertTrue(Shadows.shadowOf(service).getGlobalActionsPerformed().isEmpty());
    }

    /** The declined offer finishing its draw after an item-only frame is the same offer: never a second first Decline. */
    @Test public void theSameOfferFinishingItsDrawAfterAnItemOnlyFrameIsNotDeclinedAgain() {
        show(offer("$7.90", "10 items", "0:35"));
        pass(300);
        assertEquals("declined at once", 1, clicks(decline));
        show(labels("Finding offers", "10 items"));
        pass(400);
        show(offer("$7.90", "10 items", "0:34"));
        pass(300);
        assertEquals("the same offer is never given a second first Decline", 0, clicks(decline));
    }

    @Test public void itemCountAndOfferControlsAloneNeverAuthorizeATap() {
        AccessibilityNodeInfo partial = labels("10 items", "0:35");
        decline = node("Decline", true);
        Shadows.shadowOf(partial).addChild(decline);
        Shadows.shadowOf(partial).addChild(node("Accept", true));
        show(partial);
        pass(3_000);
        assertEquals(0, clicks(decline));
        assertTrue("no judged offer was manufactured from an item count", DecisionLog.recent(app, 1).isEmpty());
    }

    @Test public void missingItemsAfterRecoveryNeverResumeTheOriginalOffer() throws Exception {
        show(offer("$7.90", "10 items", "0:35"));
        pass(300);
        long deadline = state().confirmationUntil();
        toastError();
        show(node("Map", false));
        pass(600);
        assertEquals(Arrays.asList(AccessibilityService.GLOBAL_ACTION_BACK),
                Shadows.shadowOf(service).getGlobalActionsPerformed());
        show(offer("$7.90", "Shop & Deliver", "0:34"));
        pass(2_000);
        assertEquals("missing original item facts cannot replace recovery's full identity", 0, clicks(decline));
        assertTrue(state().confirmationUntil() <= deadline);
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
    }

    @Test public void aDifferentKnownItemCountEndsRecoveryBeforeANewOfferIsJudged() {
        show(offer("$7.90", "10 items", "0:35"));
        pass(300);
        toastError();
        show(node("Map", false));
        pass(600);
        show(offer("$7.90", "11 items", "0:34"));
        assertEquals("a positively different offer is judged on its own", 1, clicks(decline));
        assertTrue(DiagnosticLog.read(app).contains("authority ended: different offer during error recovery"));
        assertEquals("the new offer inherited no Back request", 1,
                Shadows.shadowOf(service).getGlobalActionsPerformed().size());
    }

    /**
     * After a recognized decline error, only a blank, map-only screen may authorize the recovery's Back: a frame that
     * shows nothing but an item count or Dasher's shopping badge is an offer still drawing, never blank.
     */
    @Test public void anItemOnlyFrameAfterADeclineErrorNeverAuthorizesBack() {
        for (String only : new String[] {"Shop & Deliver", "10 items"}) {
            show(labels("Finding offers"));
            pass(2_000);
            show(offer(only.equals("10 items") ? "$7.90" : "$7.80", only, "0:35"));
            pass(300);
            assertEquals(only + ": declined at once", 1, clicks(decline));
            toastError();
            show(labels(only));
            pass(1_000);
            assertTrue(only + ": no Back over an item-only frame",
                    Shadows.shadowOf(service).getGlobalActionsPerformed().isEmpty());
        }
    }

    @Test public void explicitDeliveryMarkersExplainTheirOwnShoppingItems() {
        java.util.List<String> route = Arrays.asList("Complete delivery steps", "10 items");
        assertFalse(AcceptedOfferTracker.offerFacts(OfferParser.parse(route), route));
        assertEquals(AcceptedOfferTracker.After.ROUTE, AcceptedOfferTracker.classify(route, false));
        java.util.List<String> partial = Arrays.asList("Finding offers", "Shop & Deliver");
        assertTrue(AcceptedOfferTracker.offerFacts(OfferParser.parse(partial), partial));
        assertEquals(AcceptedOfferTracker.After.OFFER_FACTS, AcceptedOfferTracker.classify(partial, false));
        java.util.List<String> unknown = Arrays.asList("Order details", "Store A", "Items 3");
        assertFalse(AcceptedOfferTracker.offerFacts(OfferParser.parse(unknown), unknown));
        assertEquals(AcceptedOfferTracker.After.UNCLEAR, AcceptedOfferTracker.classify(unknown, false));
        java.util.List<String> countedDetails = Arrays.asList("Order details", "3 items");
        assertFalse(AcceptedOfferTracker.offerFacts(OfferParser.parse(countedDetails), countedDetails));
        assertEquals(AcceptedOfferTracker.After.UNCLEAR, AcceptedOfferTracker.classify(countedDetails, false));
        java.util.List<String> shoppingList = Arrays.asList("Pick these items", "Organic bananas", "Aisle 12");
        assertFalse(AcceptedOfferTracker.offerFacts(OfferParser.parse(shoppingList), shoppingList));
        assertEquals(AcceptedOfferTracker.After.UNCLEAR, AcceptedOfferTracker.classify(shoppingList, false));
    }

    private Object field(String name) throws Exception {
        Field field = OfferFilterService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(service);
    }

    private DeclineState state() throws Exception {
        return (DeclineState) field("declineState");
    }

    /** Dasher's own failed-decline toast, as 0.4.55's recovery recognizes it. */
    private void toastError() {
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
        event.setPackageName(DASHER);
        event.setClassName("android.widget.Toast");
        event.setEventTime(SystemClock.uptimeMillis());
        event.getText().add("error");
        service.onAccessibilityEvent(event);
        pass(0);
    }

    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo n = AccessibilityNodeInfo.obtain(new View(app));
        n.setPackageName(DASHER);
        n.setText(text);
        n.setVisibleToUser(true);
        n.setEnabled(true);
        n.setClickable(clickable);
        if (clickable) {
            n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            Shadows.shadowOf(n).setOnPerformActionListener((action, args) -> true);
        }
        return n;
    }

    private AccessibilityNodeInfo labels(String... labels) {
        AccessibilityNodeInfo root = node(null, false);
        for (String label : labels) Shadows.shadowOf(root).addChild(node(label, false));
        return root;
    }

    private AccessibilityNodeInfo offer(String pay, String items, String countdown) {
        AccessibilityNodeInfo root = labels(pay, "2 stops (7.2 mi) • 21 min", items, countdown);
        decline = node("Decline", true);
        Shadows.shadowOf(root).addChild(decline);
        Shadows.shadowOf(root).addChild(node("Accept", true));
        return root;
    }

    private static int clicks(AccessibilityNodeInfo control) {
        return Shadows.shadowOf(control).getPerformedActions().size();
    }

    private void show(AccessibilityNodeInfo root) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        shadow.setRoot(root);
        shadow.setActive(true);
        shadow.setFocused(true);
        shadow.setBoundsInScreen(new Rect(0, 0, 1080, 2040));
        Shadows.shadowOf(service).setWindows(Collections.singletonList(window));
        Shadows.shadowOf(service).setRootInActiveWindow(root);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName(DASHER);
        service.onAccessibilityEvent(event);
        pass(0);
    }

    private static void pass(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }
}

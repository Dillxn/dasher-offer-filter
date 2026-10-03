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

/** Item facts may change a rule, never manufacture automatic-tap or completed-delivery evidence. */
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
        FilterStore.save(app, new FilterSettings(true, 0, 0, 0, 0, 0).withPerItem(100));
        DiagnosticLog.clear(app);
        DecisionLog.forgetCache(); DecisionLog.clear(app);
        RestartSuppression.clear(app);
        ActiveRouteStore.clear(app);
        OfferSilencer.forgetCache();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        OfferFilterService.forgetScreenState();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        controller = Robolectric.buildService(OfferFilterService.class).create();
        service = controller.get(); service.onServiceConnected(); pass(0);
    }

    @After public void teardown() {
        OfferFilterService.nodeFetchForTests = null;
        controller.destroy();
        OfferFilterService.scanLooperForTests = null;
        OfferFilterService.forgetScreenState();
    }

    @Test public void changingItemMinimumCancelsOldRetriesAndLateConfirmation() {
        show(offer("$7.90", "10 items", "0:35"));
        AccessibilityNodeInfo original = decline;
        assertEquals(1, clicks(original));
        FilterStore.save(app, FilterStore.load(app).withPerItem(50));
        pass(3_000);
        assertEquals(1, clicks(original));
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(question(confirm)); pass(3_000);
        assertEquals(0, clicks(confirm));
        assertTrue(DiagnosticLog.read(app).contains("per-item minimum changed from 100 to 50"));
        assertTrue(Shadows.shadowOf(service).getGlobalActionsPerformed().isEmpty());
    }

    @Test public void changedItemMinimumDuringFirstReadCannotSendAnObsoleteDecline() {
        changeItemsDuringNextRead(50);
        show(offer("$7.90", "10 items", "0:35")); pass(3_000);
        assertEquals(0, clicks(decline));
        assertEquals(DecisionLog.Action.PASSES, DecisionLog.recent(app, 1).get(0).action);
        assertFalse(DiagnosticLog.read(app).contains("first-step Decline REQUESTED"));
    }

    @Test public void changedItemMinimumDuringConfirmationReadCannotConfirm() {
        show(offer("$7.90", "10 items", "0:35")); pass(300);
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        changeItemsDuringNextRead(50);
        show(question(confirm)); pass(3_000);
        assertEquals(0, clicks(confirm));
        assertTrue(DiagnosticLog.read(app).contains("per-item minimum changed"));
    }

    @Test public void changedItemRuleImmediatelyBeforeRecoveryBackRevokesIt() {
        show(offer("$7.90", "10 items", "0:35")); pass(300);
        toastError(); show(node("Map", false));
        boolean[] changed = {false};
        service.windowSource = () -> {
            if (!changed[0] && calledFrom("foregroundDasherForBack")) {
                changed[0] = true;
                FilterStore.save(app, FilterStore.load(app).withPerItem(50));
            }
            return service.getWindows();
        };
        pass(700);
        assertTrue("the rule changes at the final global-action boundary", changed[0]);
        assertTrue(Shadows.shadowOf(service).getGlobalActionsPerformed().isEmpty());
    }

    @Test public void missingItemsAfterRecoveryNeverResumeTheOriginalOffer() throws Exception {
        show(offer("$7.90", "10 items", "0:35")); pass(300);
        long deadline = state().confirmationUntil();
        toastError(); show(node("Map", false)); pass(600);
        assertEquals(Arrays.asList(AccessibilityService.GLOBAL_ACTION_BACK),
                Shadows.shadowOf(service).getGlobalActionsPerformed());
        show(offer("$7.90", "Shop & Deliver", "0:34")); pass(2_000);
        assertEquals("missing original item facts cannot replace recovery's full identity", 0, clicks(decline));
        assertTrue(state().confirmationUntil() <= deadline);
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
    }

    @Test public void aDifferentKnownItemCountEndsRecoveryBeforeANewOfferIsJudged() {
        show(offer("$7.90", "10 items", "0:35")); pass(300);
        toastError(); show(node("Map", false)); pass(600);
        show(offer("$7.90", "11 items", "0:34"));
        assertEquals("a positively different offer is judged on its own", 1, clicks(decline));
        assertTrue(DiagnosticLog.read(app).contains("authority ended: different offer during error recovery"));
        assertEquals("the new offer inherited no Back request", 1,
                Shadows.shadowOf(service).getGlobalActionsPerformed().size());
    }

    @Test public void itemOnlyPartialIdleDoesNotCompleteOrEndThePendingOffer() throws Exception {
        show(offer("$7.90", "10 items", "0:35")); pass(300);
        AccessibilityNodeInfo partial = labels("Finding offers", "10 items");
        show(partial); pass(1_500);
        assertTrue("underlying idle words cannot prove this partial offer ended",
                state().hasPendingConfirmation(SystemClock.uptimeMillis()));
        assertEquals(DecisionLog.Action.DECLINE_TAPPED, DecisionLog.recent(app, 1).get(0).action);
        assertTrue((Boolean) field("offerEvidence"));
        assertTrue(Shadows.shadowOf(service).getGlobalActionsPerformed().isEmpty());
    }

    @Test public void itemCountAndOfferControlsAloneNeverAuthorizeATap() {
        AccessibilityNodeInfo partial = labels("10 items", "0:35");
        decline = node("Decline", true);
        Shadows.shadowOf(partial).addChild(decline);
        Shadows.shadowOf(partial).addChild(node("Accept", true));
        show(partial); pass(3_000);
        assertEquals(0, clicks(decline));
        assertTrue("no judged offer was manufactured from an item count", DecisionLog.recent(app, 1).isEmpty());
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

    @Test public void boundedPayFlickerPreservesObservedItemFacts() throws Exception {
        FilterStore.save(app, FilterStore.load(app).withPerItem(50));
        // The supported ambiguous shape has one bare +$ adjacent to its total.
        AccessibilityNodeInfo bounded = labels("+$1", "$5.75", "2 stops (7.2 mi) • 21 min", "10 items", "0:35");
        Shadows.shadowOf(bounded).addChild(node("Decline", true));
        Shadows.shadowOf(bounded).addChild(node("Accept", true));
        show(bounded);
        assertEquals(Integer.valueOf(10), ((OfferSnapshot) field("boundedOffer")).items);
        show(offer("$5.75", "10 items", "0:34"));
        OfferSnapshot remembered = (OfferSnapshot) field("boundedOffer");
        assertNotNull(remembered);
        assertEquals(Integer.valueOf(10), remembered.items);
        assertTrue(remembered.itemCountApplicable);
        assertEquals(Integer.valueOf(675), remembered.payAtMostCents);
    }

    private void changeItemsDuringNextRead(int cents) {
        OfferFilterService.nodeFetchForTests = () -> {
            OfferFilterService.nodeFetchForTests = null;
            FilterStore.save(app, FilterStore.load(app).withPerItem(cents));
        };
    }
    private Object field(String name) throws Exception {
        Field field = OfferFilterService.class.getDeclaredField(name);
        field.setAccessible(true); return field.get(service);
    }
    private DeclineState state() throws Exception { return (DeclineState) field("declineState"); }
    private static boolean calledFrom(String method) {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if (frame.getClassName().equals(OfferFilterService.class.getName()) && frame.getMethodName().equals(method)) return true;
        }
        return false;
    }
    private void toastError() {
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
        event.setPackageName(DASHER); event.setClassName("android.widget.Toast");
        event.setEventTime(SystemClock.uptimeMillis()); event.getText().add("error");
        service.onAccessibilityEvent(event); pass(0);
    }
    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo n = AccessibilityNodeInfo.obtain(new View(app));
        n.setPackageName(DASHER); n.setText(text); n.setVisibleToUser(true); n.setEnabled(true); n.setClickable(clickable);
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
    private AccessibilityNodeInfo question(AccessibilityNodeInfo confirm) {
        AccessibilityNodeInfo root = labels("Are you sure you want to decline this offer?");
        Shadows.shadowOf(root).addChild(confirm); return root;
    }
    private static int clicks(AccessibilityNodeInfo control) { return Shadows.shadowOf(control).getPerformedActions().size(); }
    private void show(AccessibilityNodeInfo root) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setType(AccessibilityWindowInfo.TYPE_APPLICATION); shadow.setRoot(root);
        shadow.setActive(true); shadow.setFocused(true); shadow.setBoundsInScreen(new Rect(0, 0, 1080, 2040));
        Shadows.shadowOf(service).setWindows(Collections.singletonList(window));
        Shadows.shadowOf(service).setRootInActiveWindow(root);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName(DASHER); service.onAccessibilityEvent(event); pass(0);
    }
    private static void pass(long ms) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }
}

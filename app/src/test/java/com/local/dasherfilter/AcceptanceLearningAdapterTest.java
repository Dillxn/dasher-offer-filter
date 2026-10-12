package com.local.dasherfilter;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
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
import org.robolectric.shadows.ShadowSystemClock;

/** Synthetic Android event paths through the unchanged confirmation detector and the new learner write boundary. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, instrumentedPackages = {"com.local.dasherfilter"})
@LooperMode(LooperMode.Mode.PAUSED)
public class AcceptanceLearningAdapterTest {
    Application app;
    ServiceController<OfferFilterService> controller;
    AccessibilityNodeInfo accept;
    static final OfferSnapshot FACTS = new OfferSnapshot(1675, 3.9, 30, 3);

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE).edit().clear().commit();
        app.getSharedPreferences(Consent.PREFS, Context.MODE_PRIVATE).edit()
                .putInt(Consent.ACCEPTED_VERSION, Consent.VERSION).putLong(Consent.ACCEPTED_AT, 100).commit();
        Updater.setEnabled(app, false); DiagnosticLog.setEnabled(app, true); DiagnosticLog.clear(app);
        DecisionLog.forgetCache(); DecisionLog.clear(app); AutoAcceptMemory.clear(app);
        ActiveRouteStore.clear(app); OfferSilencer.forgetCache(); OfferNotificationService.forgetDeclineAction();
        FilterStore.save(app, FilterSettings.of(true, 500, 100, 25, 3));
        FilterStore.setAcceptanceLearning(app, true);
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        controller = Robolectric.buildService(OfferFilterService.class).create();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
    }
    @After public void cleanup() {
        controller.destroy(); OfferFilterService.nodeFetchForTests = null; OfferFilterService.forgetScreenState();
        OfferFilterService.scanLooperForTests = null; OfferFilterService.sawDasherBeside(0);
        OfferNotificationService.forgetDeclineAction();
    }
    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo n = AccessibilityNodeInfo.obtain(new View(app));
        n.setPackageName("com.doordash.driverapp"); if (text != null) n.setText(text);
        n.setVisibleToUser(true); n.setEnabled(true); n.setClickable(clickable);
        if (clickable) {
            n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            Shadows.shadowOf(n).setOnPerformActionListener((action, args) -> true);
        }
        return n;
    }
    private AccessibilityNodeInfo button(String label) {
        AccessibilityNodeInfo n = node(null, true); Shadows.shadowOf(n).addChild(node(label, false)); return n;
    }
    private AccessibilityNodeInfo screen(String... labels) {
        AccessibilityNodeInfo root = node(null, false);
        for (String label : labels) Shadows.shadowOf(root).addChild(node(label, false)); return root;
    }
    private void offer() {
        AccessibilityNodeInfo root = screen("$16.75", "incl. tips", "3 stops (3.9 mi) • 30 min", "0:35");
        Shadows.shadowOf(root).addChild(button("Decline")); accept = button("Accept");
        Shadows.shadowOf(root).addChild(accept); show(root);
    }
    private void waiting() { show(screen("This dash", "You're in a good place to wait for offers")); }
    private void show(AccessibilityNodeInfo root) {
        TestWindows.full(controller.get(), root);
        Shadows.shadowOf(controller.get().getWindows().get(0)).setFocused(true);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("com.doordash.driverapp"); controller.get().onAccessibilityEvent(event);
    }
    private void tapAccept() {
        AccessibilityEvent tap = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
        tap.setPackageName("com.doordash.driverapp");
        ((ShadowAccessibilityRecord) Shadow.extract(tap)).setSourceNode(accept);
        controller.get().onAccessibilityEvent(tap);
    }
    private void delivery() { show(screen("Arrived at store")); }
    /** A new dash's first pickup, as the owner's phone drew it on 11 October 2026 (store and address made up). */
    private void pickupWithDashEarnings() {
        show(screen("Current dash", "Pick up by 7:43 PM", "Safety", "Help", "$0.00", "this dash", "Pickup from",
                "Corner Chili", "12 Elm St", "Directions"));
    }
    private DecisionLog.Entry latest() {
        DecisionLog.flush(); return DecisionLog.recent(app, 10).get(0);
    }
    @Test public void tapRequestAloneDoesNotTeachButConfirmedManualPickupDoes() {
        waiting(); offer(); tapAccept();
        assertTrue(FilterStore.load(app).accepted.isEmpty());
        delivery();
        assertEquals(1675, FilterStore.load(app).accepted.highestPayCents);
        assertEquals(1675, FilterStore.load(app).accepted.milePay);
        assertTrue(DecisionLog.hasStep(latest(), DecisionLog.StepKind.ACCEPTED));
        assertTrue(DecisionLog.hasStep(latest(), DecisionLog.StepKind.LEARNER_MINIMUM_RAISED));
        // The new high raised the saved minimums to match (0.6.1), with the homepage's note and Undo.
        assertArrayEquals(new int[] {1676, 430, 56, 0, 0, 0}, FilterStore.load(app).minimums());
        assertNotNull(FilterStore.learnedRaiseNote(app));
        assertTrue(DiagnosticLog.read(app).contains("[learner] manual acceptance: learned; your minimums rose"));
    }
    @Test public void aPickupShowingTheDashsZeroEarningsStillCountsAndTeaches() {
        waiting(); assertFalse(OfferFilterService.dasherOnDelivery(app));
        offer(); ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); pickupWithDashEarnings();
        assertTrue("the Split button now knows Dasher is on a delivery", OfferFilterService.dasherOnDelivery(app));
        assertTrue(DecisionLog.hasStep(latest(), DecisionLog.StepKind.ACCEPTED));
        assertEquals(1675, FilterStore.load(app).accepted.highestPayCents);
        assertTrue(DecisionLog.hasStep(latest(), DecisionLog.StepKind.LEARNER_MINIMUM_RAISED));
    }
    @Test public void currentConservativeNoTapConfirmationCanTeach() {
        waiting(); offer(); ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); delivery();
        assertTrue(DecisionLog.hasStep(latest(), DecisionLog.StepKind.ACCEPTED));
        assertFalse(DecisionLog.hasStep(latest(), DecisionLog.StepKind.ACCEPT_TAPPED));
        assertEquals(1675, FilterStore.load(app).accepted.highestPayCents);
    }
    @Test public void automaticProvenanceCountsOutcomeButNeverTeaches() {
        waiting(); offer(); assertTrue(AutoAcceptMemory.remember(app, FACTS)); tapAccept(); delivery();
        assertTrue(DecisionLog.hasStep(latest(), DecisionLog.StepKind.ACCEPTED_AUTOMATIC));
        assertTrue(FilterStore.load(app).accepted.isEmpty());
    }
    @Test public void resetAfterTapBeforeConfirmationDoesNotResurrectTheOldOffer() {
        waiting(); offer(); tapAccept(); FilterStore.resetAccepted(app); delivery();
        assertTrue(DecisionLog.hasStep(latest(), DecisionLog.StepKind.ACCEPTED));
        assertTrue(FilterStore.load(app).accepted.isEmpty());
    }
    @Test public void historyClearAfterTapBeforeConfirmationDoesNotRestoreDeletedProof() {
        waiting(); offer(); tapAccept(); DecisionLog.clear(app); delivery();
        assertNotNull("existing detector still observed delivery", ActiveRouteStore.load(app));
        assertTrue(FilterStore.load(app).accepted.isEmpty());
        assertNull(FilterStore.pendingAdoption(app));
    }
    @Test public void disableThenReenableBeforeConfirmationDoesNotRenewThePermit() {
        waiting(); offer(); tapAccept();
        FilterStore.setAcceptanceLearning(app, false); FilterStore.setAcceptanceLearning(app, true); delivery();
        assertTrue(DecisionLog.hasStep(latest(), DecisionLog.StepKind.ACCEPTED));
        assertTrue(FilterStore.load(app).accepted.isEmpty());
    }
    @Test public void enablingDuringTheSameVisibleOfferDoesNotCaptureItRetrospectively() {
        FilterStore.setAcceptanceLearning(app, false); waiting(); offer();
        FilterStore.setAcceptanceLearning(app, true);
        // Re-reading the same offer is not a new prospective learner observation.
        ShadowSystemClock.advanceBy(Duration.ofMillis(100)); offer(); tapAccept(); delivery();
        assertTrue(DecisionLog.hasStep(latest(), DecisionLog.StepKind.ACCEPTED));
        assertTrue(FilterStore.load(app).accepted.isEmpty());
    }
}

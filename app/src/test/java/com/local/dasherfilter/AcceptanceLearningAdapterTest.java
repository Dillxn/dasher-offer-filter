package com.local.dasherfilter;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.time.Duration;
import java.util.ArrayList;
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
import org.robolectric.shadows.ShadowAccessibilityRecord;
import org.robolectric.shadows.ShadowSystemClock;

/** Synthetic Android event paths through the unchanged confirmation detector and the new learner write boundary. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36}, instrumentedPackages = {"com.local.dasherfilter"})
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
        FilterStore.save(app, FilterSettings.of(true, 250, 100, 25, 3));
        FilterStore.setAcceptanceLearning(app, true);
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        controller = Robolectric.buildService(OfferFilterService.class).create();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
    }
    @After public void cleanup() {
        AutopilotRuntime.executorForTests = null; AutopilotRuntime.afterInputsForTests = null;
        AutopilotRuntime.forgetCache();
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
    /** The same offer, its Decline counting the app's taps on it. */
    private AccessibilityNodeInfo offerCountingDeclines(List<Integer> taps) {
        return offerCountingDeclines("$16.75", taps);
    }
    private AccessibilityNodeInfo offerCountingDeclines(String pay, List<Integer> taps) {
        AccessibilityNodeInfo root = screen(pay, "incl. tips", "3 stops (3.9 mi) • 30 min", "0:35");
        AccessibilityNodeInfo decline = button("Decline");
        Shadows.shadowOf(decline).setOnPerformActionListener((action, args) -> {
            if (action == AccessibilityNodeInfo.ACTION_CLICK) taps.add(action);
            return true;
        });
        Shadows.shadowOf(root).addChild(decline); accept = button("Accept");
        Shadows.shadowOf(root).addChild(accept);
        return root;
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
        // The new high raised the learned minimums, with the homepage's note and Adopt; the saved ones stay (the
        // owner, 12 October 2026).
        assertArrayEquals(new int[] {250, 100, 25, 0, 0, 0}, FilterStore.load(app).minimums());
        assertNotNull(FilterStore.learnedRaise(app));
        assertTrue(DiagnosticLog.read(app).contains(
                "[learner] manual acceptance: learned; the learned minimums rose"));
    }
    /**
     * The owner's 11 October 7:02 PM offer: it passed while he stepped a knob, until a step took its requirement past
     * its pay and it was declined under his finger. Now it stays his, and his accept of it still teaches.
     */
    @Test public void aKnobChangedWhileAnOfferShowsLeavesItToYouAndItsAcceptStillTeaches() {
        waiting();
        List<Integer> declines = new ArrayList<>();
        AccessibilityNodeInfo shown = offerCountingDeclines(declines);
        show(shown);
        assertTrue("it passes", declines.isEmpty());
        FilterSettings before = FilterStore.load(app);
        FilterStore.save(app, before, before.withMinimums(new int[] {2000, 100, 25, 0, 0, 0}));
        ShadowSystemClock.advanceBy(Duration.ofMillis(200));
        show(shown);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("the offer on screen is left to you", declines.isEmpty());
        assertTrue(DiagnosticLog.read(app).contains("[rules] offer on screen left to you"));
        tapAccept(); delivery();
        assertTrue(DecisionLog.hasStep(latest(), DecisionLog.StepKind.ACCEPTED));
        assertEquals("the knob change did not void its lesson", 1675, FilterStore.load(app).accepted.highestPayCents);
        assertArrayEquals(new int[] {2000, 100, 25, 0, 0, 0}, FilterStore.load(app).minimums());
    }
    @Test public void theNewRulesJudgeTheNextOffer() {
        waiting();
        List<Integer> first = new ArrayList<>();
        show(offerCountingDeclines(first));
        FilterSettings before = FilterStore.load(app);
        FilterStore.save(app, before, before.withMinimums(new int[] {2000, 100, 25, 0, 0, 0}));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        waiting();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        List<Integer> next = new ArrayList<>();
        show(offerCountingDeclines("$18.25", next));
        assertTrue(first.isEmpty());
        assertEquals("the next offer is declined under the new rules", 1, next.size());
    }
    /** A wait-for-offers frame between the user's Accept tap and the pickup screen leaves the lesson standing. */
    @Test public void aWaitFrameAfterTheAcceptTapStillTeaches() {
        waiting(); offer(); tapAccept();
        ShadowSystemClock.advanceBy(Duration.ofMillis(300));
        waiting();
        ShadowSystemClock.advanceBy(Duration.ofMillis(300));
        delivery();
        assertTrue(DecisionLog.hasStep(latest(), DecisionLog.StepKind.ACCEPTED));
        assertEquals(1675, FilterStore.load(app).accepted.highestPayCents);
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
    /**
     * Autopilot on (0.6.11): the service's no-tap accept ("Accepted without a seen tap") puts the ACCEPTED step on the
     * line before the plan it asks for reads the history, so that plan holds the bar at the offer's score, and the
     * next commit makes it so. Learning is off here: the floor counts the accepts the learner is asked about, whatever
     * it makes of them (with learning on, this offer's own lesson would put its score just under 100).
     */
    @Test public void aNoTapAcceptLiftsAutopilotsBarToItsScore() {
        FilterStore.setAcceptanceLearning(app, false);
        AutopilotStore.clear(app); AutopilotRuntime.forgetCache();
        AutopilotRuntime.executorForTests = Runnable::run;
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        List<Boolean> stepped = new ArrayList<>();
        AutopilotRuntime.afterInputsForTests = () -> {
            DecisionLog.Entry line = latest();
            if (line.facts.payCents != null && line.facts.payCents == 1050) {
                stepped.add(DecisionLog.hasStep(line, DecisionLog.StepKind.ACCEPTED));
            }
        };
        waiting();
        // $10.50 for 3 stops, 3.9 mi and 30 min: 140% of the $7.50 the minimums ask (its 3 stops ask pay per stop
        // three times, 3 × $2.50, the pickup included, from 0.6.14; 30 min at $15/hr asks $7.50 too).
        List<Integer> declines = new ArrayList<>();
        show(offerCountingDeclines("$10.50", declines));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        delivery();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(declines.isEmpty());
        assertTrue(Dashing.on(app));
        assertTrue(DecisionLog.hasStep(latest(), DecisionLog.StepKind.ACCEPTED));
        assertFalse(DecisionLog.hasStep(latest(), DecisionLog.StepKind.ACCEPT_TAPPED));
        assertTrue(DiagnosticLog.read(app).contains("Accepted without a seen tap"));
        assertFalse("its plan was asked for", stepped.isEmpty());
        assertTrue("the plan after the accept read the line with its step", stepped.get(stepped.size() - 1));
        Autopilot.Plan plan = AutopilotRuntime.latest();
        assertEquals(140, plan.floor);
        assertEquals(140, plan.target);
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        assertEquals(140, FilterStore.load(app).minimumScalePercent);
        assertTrue(DiagnosticLog.read(app).contains(
                "[autopilot] commit 100% -> 140% (you accepted an offer at this score)"));
    }
}

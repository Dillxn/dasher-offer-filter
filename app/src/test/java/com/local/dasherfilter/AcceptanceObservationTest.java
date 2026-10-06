package com.local.dasherfilter;

import android.app.Application;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.time.Duration;
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
import org.robolectric.shadows.ShadowAccessibilityRecord;
import org.robolectric.shadows.ShadowSystemClock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Missing-event outcome observations with synthetic same-window Android trees; no handset success claimed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public class AcceptanceObservationTest {
    /** The user's own kind of rules: $13, $3.85 a mile, $0.41 a minute, $4.75 a stop, at most 3 stops. */
    private static final FilterSettings RULES = new FilterSettings(true, 1300, 385, 41, 475, 3, true, 0);

    private Application app;
    private ServiceController<OfferFilterService> controller;
    private AccessibilityNodeInfo accept;
    private AccessibilityNodeInfo decline;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        DiagnosticLog.setEnabled(app, true);
        DiagnosticLog.clear(app);
        FilterStore.save(app, RULES);
        FilterStore.resetAccepted(app);
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        ReportOutbox.forgetCache();
        OfferSilencer.forgetCache();
        ActiveRouteStore.clear(app);
        OfferNotificationService.forgetDeclineAction();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        controller = Robolectric.buildService(OfferFilterService.class).create();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
    }

    @After
    public void stop() {
        OfferNotificationService.forgetDeclineAction();
        OfferFilterService.nodeFetchForTests = null;
        controller.destroy();
        OfferFilterService.sawDasherBeside(0);
        OfferFilterService.scanLooperForTests = null;
    }

    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(new View(app));
        node.setPackageName("com.doordash.driverapp");
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

    /** A Compose-style button: a clickable node without text, holding its label. */
    private AccessibilityNodeInfo button(String label) {
        AccessibilityNodeInfo button = node(null, true);
        Shadows.shadowOf(button).addChild(node(label, false));
        return button;
    }

    /** An offer screen as Dasher draws it: Decline, the pay, the route line, Accept, then its countdown. */
    private AccessibilityNodeInfo offer(String pay, String route, String countdown) {
        AccessibilityNodeInfo root = node(null, false);
        decline = button("Decline");
        accept = button("Accept");
        Shadows.shadowOf(root).addChild(decline);
        Shadows.shadowOf(root).addChild(node(pay, false));
        Shadows.shadowOf(root).addChild(node("incl. tips", false));
        Shadows.shadowOf(root).addChild(node(route, false));
        Shadows.shadowOf(root).addChild(accept);
        if (countdown != null) Shadows.shadowOf(root).addChild(node(countdown, false));
        return root;
    }

    /** $16.75 for 3 stops, 3.9 mi and 30 min: it passes those rules. */
    private AccessibilityNodeInfo passing(String countdown) {
        return offer("$16.75", "3 stops (3.9 mi) • 30 min", countdown);
    }

    private AccessibilityNodeInfo screen(String... labels) {
        AccessibilityNodeInfo root = node(null, false);
        for (String label : labels) Shadows.shadowOf(root).addChild(node(label, false));
        return root;
    }

    private AccessibilityNodeInfo delivery() {
        return screen("Deliver by 9:45 PM", "Delivery for Sam", "Complete delivery steps");
    }

    private AccessibilityNodeInfo waiting() {
        return screen("This dash", "You're in a good place to wait for offers", "Zone offer wait", "1-2 min");
    }

    private void show(AccessibilityNodeInfo root) {
        TestWindows.full(controller.get(), root);
        Shadows.shadowOf(controller.get().getWindows().get(0)).setFocused(true);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("com.doordash.driverapp");
        controller.get().onAccessibilityEvent(event);
    }

    /** A click Dasher reports on {@code source}, with no text, as Jetpack Compose sends one. */
    private void clicked(AccessibilityNodeInfo source) {
        AccessibilityEvent tap = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
        tap.setPackageName("com.doordash.driverapp");
        ((ShadowAccessibilityRecord) Shadow.extract(tap)).setSourceNode(source);
        controller.get().onAccessibilityEvent(tap);
    }

    /** Replace only content: keep the same window id/layer/bounds, and emit no event at all. */
    private void replaceWithoutEvent(AccessibilityNodeInfo root) {
        android.view.accessibility.AccessibilityWindowInfo window = controller.get().getWindows().get(0);
        Shadows.shadowOf(window).setRoot(root);
        associate(root, window);
        Shadows.shadowOf(controller.get()).setRootInActiveWindow(root);
    }

    private void associate(AccessibilityNodeInfo node, android.view.accessibility.AccessibilityWindowInfo window) {
        Shadows.shadowOf(node).setAccessibilityWindowInfo(window);
        for (int i = 0; i < node.getChildCount(); i++) associate(node.getChild(i), window);
    }

    private void later(long ms) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(ms));
    }

    private String history() {
        return DecisionLog.report(app, 20);
    }

    private static void contains(String text, String part) {
        assertTrue(part + " in:\n" + text, text.contains(part));
    }

    @Test public void deliveryWithoutAnotherEventAfterPartialFactsIsObservedAndLearned() {
        show(waiting());
        show(passing("0:35"));
        later(1_000);
        show(screen("$16.75", "3 stops (3.9 mi) • 30 min"));
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
        // Dasher finishes the transition, but sends no accessibility event for the completed frame.
        replaceWithoutEvent(delivery());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100));
        assertEquals(1675, FilterStore.load(app).lastAcceptedCents);
        contains(history(), "Accepted; the adaptive minimum learned from it");
        contains(DiagnosticLog.read(app), "progress=false; route=false");
        contains(DiagnosticLog.read(app), "outcome=ROUTE; observation=eligible");
    }

    @Test public void qualifyingShoppingOfferUpdatesSupportedIndependentBestsAfterEventlessPickup() {
        // Captured numeric offer shape, synthetic explicit rule setup: this is not the missing phone node tree.
        FilterStore.recordAccepted(app, new OfferSnapshot(940, 2.3, 16, 2));
        FilterStore.save(app, new FilterSettings(true, 1950, 0, 0, 0, 3, true, 940,
                new AcceptedBest(940, 16, 940, 2.3, 940, 2), DeclinedFloor.NONE, true, 0, 100, 100));
        show(waiting());
        AccessibilityNodeInfo shopping = offer("$14.70", "2 stops (6.8 mi) • 33 min", "0:35");
        Shadows.shadowOf(shopping).addChild(node("Shop & deliver", false));
        Shadows.shadowOf(shopping).addChild(node("2 items", false));
        show(shopping);
        assertTrue("the synthetic area offer passes", Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        later(1_000);
        show(screen("$14.70", "Order (2 items)"));
        replaceWithoutEvent(screen("Pick up by 7:52 PM", "Pickup from", "Directions", "Order (2 items)",
                "Arrived at store"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100));
        FilterSettings learned = FilterStore.load(app);
        assertEquals(1470, learned.lastAcceptedCents);
        assertEquals(735, learned.best.forStops(1));
        assertEquals(735, learned.best.forItems(1));
        assertEquals(940, learned.best.minutePay);
        assertEquals(940, learned.best.milePay);
        assertEquals(1950, learned.flatCents);
    }

    @Test public void observedAcceptFollowedByEventlessExplicitPickupIsLearned() {
        show(waiting());
        show(passing("0:35"));
        clicked(accept);
        later(1_000);
        show(screen("$16.75", "Order (2 items)"));
        replaceWithoutEvent(screen("Pick up by 7:52 PM", "Pickup from", "Directions",
                "Order (2 items)", "Arrived at store"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100));
        assertEquals(1675, FilterStore.load(app).lastAcceptedCents);
        contains(history(), "you tapped Accept, and Dasher showed a delivery screen");
    }

    @Test public void automaticRequestObservedWithoutAnotherEventConfirmsButNeverTeaches() {
        controller.get().onServiceConnected();
        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0, true, 0));
        FilterStore.setAutoAcceptEnabled(app, true);
        show(waiting());
        AccessibilityNodeInfo shopping = offer("$20.00", "2 stops (4 mi) • 20 min", "0:30");
        Shadows.shadowOf(shopping).addChild(node("2 items", false));
        show(shopping);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        assertEquals(1, Shadows.shadowOf(accept).getPerformedActions().size());
        show(screen("$20.00", "Order (2 items)"));
        replaceWithoutEvent(screen("Arrived at store", "Order (2 items)"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100));
        assertTrue(DecisionLog.accepted(DecisionLog.recent(app, 1).get(0)));
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
        assertTrue(FilterStore.load(app).best.isEmpty());
        assertEquals(1, Shadows.shadowOf(accept).getPerformedActions().size());
    }

    @Test public void provenUnsentAfterPersistenceDoesNotSuppressLaterManualLearning() {
        finalGuardRefusal(null);
        assertFalse("restart suppression remains present", app.getSharedPreferences("restart_suppression", 0)
                .getAll().isEmpty());
        clicked(accept);
        show(screen("Arrived at store"));
        assertEquals(1675, FilterStore.load(app).lastAcceptedCents);
        assertFalse(AutoAcceptMemory.covers(app, new OfferSnapshot(1675, 3.9, 30, 3)));
        assertTrue(DecisionLog.accepted(DecisionLog.recent(app, 1).get(0)));
    }

    @Test public void provenUnsentRollbackPreservesEarlierAutomaticRequestForTheSameOffer() {
        OfferSnapshot prior = new OfferSnapshot(1675, 3.9, 30, 3);
        finalGuardRefusal(prior);
        assertTrue(AutoAcceptMemory.covers(app, prior));
        clicked(accept);
        show(screen("Arrived at store"));
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
        assertTrue(DecisionLog.accepted(DecisionLog.recent(app, 1).get(0)));
    }

    @Test public void provenUnsentRollbackRestoresDifferentEarlierRequestInsteadOfErasingIt() {
        OfferSnapshot prior = new OfferSnapshot(2100, 4.0, 35, 2);
        finalGuardRefusal(prior);
        assertTrue(AutoAcceptMemory.covers(app, prior));
        assertFalse(AutoAcceptMemory.covers(app, new OfferSnapshot(1675, 3.9, 30, 3)));
    }

    @Test public void provenUnsentCleanupDoesNotOverwriteNewerRequestProvenance() {
        OfferSnapshot newer = new OfferSnapshot(2400, 5.0, 35, 2);
        finalGuardRefusal(null, () -> AutoAcceptMemory.remember(app, newer));
        assertTrue(AutoAcceptMemory.covers(app, newer));
    }

    @Test public void provenUnsentCleanupNeverRestoresProvenanceAfterHistoryWasCleared() {
        OfferSnapshot prior = new OfferSnapshot(2100, 4.0, 35, 2);
        finalGuardRefusal(prior, () -> AutoAcceptMemory.clear(app));
        assertFalse(AutoAcceptMemory.covers(app, prior));
        assertFalse(AutoAcceptMemory.covers(app, new OfferSnapshot(1675, 3.9, 30, 3)));
    }

    private void finalGuardRefusal(OfferSnapshot previous) { finalGuardRefusal(previous, () -> {}); }

    private void finalGuardRefusal(OfferSnapshot previous, Runnable afterWrite) {
        controller.get().onServiceConnected();
        AutoAcceptMemory.clear(app);
        if (previous != null) assertTrue(AutoAcceptMemory.remember(app, previous));
        FilterStore.setAutoAcceptEnabled(app, true);
        show(waiting());
        show(passing("0:35"));
        int[] metadataChecks = {0};
        controller.get().windowSource = () -> {
            if (calledFrom("dasherStillReadable") && ++metadataChecks[0] == 2) {
                // This is the second final visibility check, after both persistence commits, before dispatch.
                assertTrue(AutoAcceptMemory.covers(app, new OfferSnapshot(1675, 3.9, 30, 3)));
                afterWrite.run();
                FilterStore.setAutoAcceptEnabled(app, false);
            }
            return controller.get().getWindows();
        };
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        assertEquals(2, metadataChecks[0]);
        assertTrue(Shadows.shadowOf(accept).getPerformedActions().isEmpty());
        contains(DiagnosticLog.read(app), "Accept NOT_SENT; reason=auto_accept_disabled");
        controller.get().windowSource = controller.get()::getWindows;
    }

    private static boolean calledFrom(String method) {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if (frame.getClassName().equals(OfferFilterService.class.getName()) && frame.getMethodName().equals(method)) {
                return true;
            }
        }
        return false;
    }

    @Test public void itemOnlyPartialFrameStillExpiresWithinTheExistingMinute() {
        show(waiting()); show(passing("0:35")); later(1_000);
        show(screen("Shop & deliver", "2 items"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61));
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
        contains(history(), "Not learned:");
    }

    @Test public void explicitPickupWithUnexplainedPayStillCannotTeach() {
        show(waiting());
        show(passing("0:35"));
        later(1_000);
        show(screen("$16.75", "Order (2 items)"));
        replaceWithoutEvent(screen("Pick up by 7:52 PM", "Arrived at store", "$9.99", "Order (2 items)"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
        contains(DiagnosticLog.read(app), "progress=true; route=true");
        contains(DiagnosticLog.read(app), "outcome=OFFER_FACTS");
    }

    @Test public void explicitPickupRetainingTheExactWatchedPayAndItemsLearnsOriginalFacts() {
        show(waiting());
        AccessibilityNodeInfo shopping = passing("0:35");
        Shadows.shadowOf(shopping).addChild(node("2 items", false));
        show(shopping);
        later(1_000);
        show(screen("$16.75", "2 items"));
        replaceWithoutEvent(screen("Pick up by 7:52 PM", "Arrived at store", "$16.75", "2 items"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100));
        FilterSettings learned = FilterStore.load(app);
        assertEquals(1675, learned.lastAcceptedCents);
        assertEquals(1675, learned.best.itemPay);
        assertEquals(2, learned.best.items);
        assertEquals("the retained screen cannot replace the original route", Double.valueOf(3.9),
                ActiveRouteStore.load(app).miles);
        contains(history(), "Accepted; the adaptive minimum learned from it");
        String diagnostics = DiagnosticLog.read(app);
        contains(diagnostics, "progress_kind=arrived_at_store; pay_relation=matched; money_malformed=false");
        for (String line : diagnostics.split("\\n")) if (line.contains("outcome evidence:")) {
            assertFalse("categorical evidence has no amount", line.contains("$") || line.contains("16.75"));
        }
    }

    @Test public void uncertainAutomaticProvenanceStillExcludesRetainedPayFromLearning() {
        OfferSnapshot original = new OfferSnapshot(1675, 3.9, 30, 3).withItems(2, true);
        assertTrue(AutoAcceptMemory.remember(app, original));
        show(waiting());
        AccessibilityNodeInfo shopping = passing("0:35");
        Shadows.shadowOf(shopping).addChild(node("2 items", false));
        show(shopping);
        later(1_000);
        show(screen("Arrived at store", "$16.75", "2 items"));
        assertTrue(DecisionLog.accepted(DecisionLog.recent(app, 1).get(0)));
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
        assertTrue(FilterStore.load(app).best.isEmpty());
        assertTrue(AutoAcceptMemory.covers(app, original));
    }

    @Test public void provenUnsentCancellationAllowsLaterManualLearningWithMatchingRetainedPay() {
        finalGuardRefusal(null);
        clicked(accept);
        show(screen("Arrived at store", "$16.75"));
        assertEquals(1675, FilterStore.load(app).lastAcceptedCents);
        assertFalse(AutoAcceptMemory.covers(app, new OfferSnapshot(1675, 3.9, 30, 3)));
        assertTrue(DecisionLog.accepted(DecisionLog.recent(app, 1).get(0)));
    }

    @Test public void malformedMoneyAndCountdownCannotTurnAPartialOfferIntoAnAcceptance() {
        show(waiting());
        show(passing("0:35"));
        later(1_000);
        show(screen("Arrived at store", "$16.750", "0:30"));
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
        assertFalse(DecisionLog.accepted(DecisionLog.recent(app, 1).get(0)));
        contains(DiagnosticLog.read(app), "pay_relation=ambiguous; money_malformed=true");
    }

    @Test public void deadlineIsNotExtendedByRepeatedObservation() {
        show(waiting());
        show(passing("0:35"));
        later(1_000);
        show(screen("$16.75", "Order (2 items)"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61));
        contains(history(), "Not learned:");
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
        int roots = controller.get().rootFetches;
        replaceWithoutEvent(delivery());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals("the ended watch no longer reads through numeric-remnant busy state", roots, controller.get().rootFetches);
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
    }

    @Test public void lockedPhoneCancelsObservationBeforeReadingAPickup() {
        show(waiting()); show(passing("0:35")); later(1_000);
        show(screen("$16.75"));
        replaceWithoutEvent(delivery());
        Shadows.shadowOf(app.getSystemService(android.app.KeyguardManager.class)).setKeyguardLocked(true);
        int roots = controller.get().rootFetches;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(roots, controller.get().rootFetches);
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
    }

    @Test public void lockDuringTheFinalChildFetchCannotTurnAStaleObservationIntoALesson() {
        show(waiting()); show(passing("0:35")); later(1_000);
        show(screen("$16.75"));
        replaceWithoutEvent(screen("Arrived at store"));
        OfferFilterService.nodeFetchForTests = () -> {
            OfferFilterService.nodeFetchForTests = null;
            Shadows.shadowOf(app.getSystemService(android.app.KeyguardManager.class)).setKeyguardLocked(true);
        };
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
        assertFalse(DecisionLog.accepted(DecisionLog.recent(app, 1).get(0)));
    }

    @Test public void aNewCompleteEventlessOfferIsHandledNormallyAndNeverCountsAsTheOldAcceptance() {
        show(waiting()); show(passing("0:35")); later(1_000);
        show(screen("$16.75"));
        replaceWithoutEvent(offer("$19.00", "2 stops (4 mi) • 30 min", "0:35"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100));
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
        assertEquals(Integer.valueOf(1900), DecisionLog.recent(app, 1).get(0).facts.payCents);
        assertFalse(DecisionLog.accepted(DecisionLog.recent(app, 1).get(0)));
    }

    @Test public void pendingObservationReadsNothingAfterConsentIsRevoked() {
        show(waiting()); show(passing("0:35")); later(1_000);
        show(screen("$16.75"));
        replaceWithoutEvent(delivery());
        app.getSharedPreferences(Consent.PREFS, 0).edit().clear().commit();
        int roots = controller.get().rootFetches;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(roots, controller.get().rootFetches);
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
    }

    @Test public void interruptDiscardsPendingObservation() {
        show(waiting()); show(passing("0:35")); later(1_000);
        show(screen("$16.75"));
        controller.get().onInterrupt();
        replaceWithoutEvent(delivery());
        int roots = controller.get().rootFetches;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(roots, controller.get().rootFetches);
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
    }

    @Test public void hiddenDasherIsNotReadAndDoesNotTeach() {
        show(waiting()); show(passing("0:35")); later(1_000);
        show(screen("$16.75"));
        AccessibilityNodeInfo other = node("Arrived at store", false);
        other.setPackageName("another.app");
        replaceWithoutEvent(other);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
        assertFalse(history(), history().contains("Accepted;"));
    }

    @Test public void countdownOrOneOfferControlKeepsObservationOff() {
        for (String blocker : new String[]{"0:24", "Accept", "Decline", "New Delivery!"}) {
            show(waiting()); show(passing("0:35")); later(1_000);
            show(screen("$16.75", blocker));
            replaceWithoutEvent(delivery());
            // Normal incomplete-control retries may still run; only assert no extra observation timer.
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
            assertEquals(0, FilterStore.load(app).lastAcceptedCents);
            contains(DiagnosticLog.read(app), "observation=blocked");
        }
    }

    @Test public void outcomeDiagnosticsAreDeduplicatedBoundedAndContainNoTextOrAmounts() {
        show(waiting()); show(passing("0:35")); later(1_000);
        for (int i = 0; i < 20; i++) {
            show(screen("$12.34", "Private wallet words", i % 2 == 0 ? "Arrived at store" : "Anything"));
            later(100);
        }
        String log = DiagnosticLog.read(app);
        int count = 0;
        for (String line : log.split("\n")) if (line.contains("outcome evidence:")) {
            count++;
            assertFalse(line, line.contains("Private") || line.contains("12.34") || line.contains("Anything"));
        }
        assertEquals(8, count);
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
    }
}

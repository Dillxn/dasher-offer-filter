package com.local.dasherfilter;

import android.app.Application;
import android.os.Looper;
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Missing-event outcome observations with synthetic same-window Android trees; no handset success claimed. What became
 * of an offer is a step on its own history line (0.5.0 learns nothing from it): ACCEPTED for the user's acceptance,
 * ACCEPTED_AUTOMATIC for one after the app's own Accept request, and no step at all when the evidence is not enough.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class AcceptanceObservationTest {
    /**
     * The user's own kind of rules: $13, $3.85 a mile, $0.41 a minute ($24.60 an hour), at most 3 stops. Their $4.75 a
     * stop folds into minimum pay as 2 × $4.75 = $9.50, below the $13 already asked.
     */
    private static final FilterSettings RULES = FilterSettings.of(true, Math.max(1300, 2 * 475), 385, 41, 3);

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
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
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

    /** $16.75 for 3 stops, 3.9 mi and 30 min: it passes those rules (it needs $15.02, for its miles). */
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

    // ---- What became of an offer: steps on its own line ----

    /** The newest screen line of the offer with this pay, as the history has it now. */
    private DecisionLog.Entry line(int payCents) {
        DecisionLog.flush();
        for (DecisionLog.Entry entry : DecisionLog.recent(app, 20)) {
            if (entry.source == DecisionLog.Source.SCREEN && entry.facts.payCents != null
                    && entry.facts.payCents == payCents) {
                return entry;
            }
        }
        throw new AssertionError("no line for pay " + payCents + " in:\n" + history());
    }

    private static boolean has(DecisionLog.Entry line, DecisionLog.StepKind kind) {
        return DecisionLog.hasStep(line, kind);
    }

    /** The detail of the line's step of this kind; fails when the line has none. */
    private static String detail(DecisionLog.Entry line, DecisionLog.StepKind kind) {
        for (DecisionLog.Step step : line.steps) if (step.kind == kind) return step.detail;
        throw new AssertionError("no " + kind + " step on the line");
    }

    /** The user accepted it: counted as accepted, as the user's (never the app's automatic request). */
    private void assertAcceptedByTheUser(int payCents) {
        DecisionLog.Entry line = line(payCents);
        assertTrue(history(), DecisionLog.accepted(line));
        assertEquals(DecisionLog.Outcome.ACCEPTED, DecisionLog.outcome(line));
        assertTrue(history(), has(line, DecisionLog.StepKind.ACCEPTED));
        assertFalse(history(), has(line, DecisionLog.StepKind.ACCEPTED_AUTOMATIC));
    }

    /** Accepted after the app's own automatic Accept request: counted, with that provenance, never as the user's. */
    private void assertAcceptedAutomatically(int payCents) {
        DecisionLog.Entry line = line(payCents);
        assertTrue(history(), DecisionLog.accepted(line));
        assertTrue(history(), has(line, DecisionLog.StepKind.ACCEPTED_AUTOMATIC));
        assertFalse(history(), has(line, DecisionLog.StepKind.ACCEPTED));
    }

    /** Nothing counted the offer as accepted: no acceptance step of any kind, and no route kept from it. */
    private void assertNotAccepted(int payCents) {
        DecisionLog.Entry line = line(payCents);
        assertFalse(history(), DecisionLog.accepted(line));
        assertFalse(history(), has(line, DecisionLog.StepKind.ACCEPTED));
        assertFalse(history(), has(line, DecisionLog.StepKind.ACCEPTED_AUTOMATIC));
    }

    /** No acceptance learns anything: the minimums stay exactly as set. */
    private void assertRulesUnchanged(FilterSettings set) {
        FilterSettings now = FilterStore.load(app);
        assertArrayEquals(set.minimums(), now.minimums());
        assertEquals(set.maxStops, now.maxStops);
        assertEquals(set.minimumScalePercent, now.minimumScalePercent);
    }

    @Test public void deliveryWithoutAnotherEventAfterPartialFactsIsObservedAndCounted() {
        show(waiting());
        show(passing("0:35"));
        later(1_000);
        show(screen("$16.75", "3 stops (3.9 mi) • 30 min"));
        assertNotAccepted(1675);
        // Dasher finishes the transition, but sends no accessibility event for the completed frame.
        replaceWithoutEvent(delivery());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100));
        assertAcceptedByTheUser(1675);
        contains(detail(line(1675), DecisionLog.StepKind.ACCEPTED),
                "it closed with about 0:34 left on its countdown, and Dasher showed a delivery screen");
        contains(history(), "Accepted: it closed with about 0:34 left on its countdown");
        assertRulesUnchanged(RULES);
        contains(DiagnosticLog.read(app), "progress=false; route=false");
        contains(DiagnosticLog.read(app), "outcome=ROUTE; observation=eligible");
    }

    @Test public void qualifyingShoppingOfferAcceptedThroughAnEventlessPickupKeepsItsItemsAndChangesNoRule() {
        // Captured numeric offer shape, synthetic rule setup: this is not the missing phone node tree. $14.70 for
        // 6.8 mi and 33 min needs $10.00 (its minimum pay) under these.
        FilterSettings rules = FilterSettings.of(true, 1000, 100, 20, 3);
        FilterStore.save(app, rules);
        show(waiting());
        AccessibilityNodeInfo shopping = offer("$14.70", "2 stops (6.8 mi) • 33 min", "0:35");
        Shadows.shadowOf(shopping).addChild(node("Shop & deliver", false));
        Shadows.shadowOf(shopping).addChild(node("2 items", false));
        show(shopping);
        assertTrue("the offer passes", Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        later(1_000);
        show(screen("$14.70", "Order (2 items)"));
        replaceWithoutEvent(screen("Pick up by 7:52 PM", "Pickup from", "Directions", "Order (2 items)",
                "Arrived at store"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100));
        assertAcceptedByTheUser(1470);
        assertEquals(Integer.valueOf(2), line(1470).facts.items);
        OfferSnapshot route = ActiveRouteStore.load(app);
        assertNotNull("the accepted route is kept for the deliveries to come", route);
        assertEquals(Integer.valueOf(1470), route.payCents);
        assertEquals(Integer.valueOf(2), route.items);
        assertRulesUnchanged(rules);
    }

    @Test public void observedAcceptFollowedByEventlessExplicitPickupIsCounted() {
        show(waiting());
        show(passing("0:35"));
        clicked(accept);
        later(1_000);
        show(screen("$16.75", "Order (2 items)"));
        replaceWithoutEvent(screen("Pick up by 7:52 PM", "Pickup from", "Directions",
                "Order (2 items)", "Arrived at store"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100));
        assertAcceptedByTheUser(1675);
        assertTrue(has(line(1675), DecisionLog.StepKind.ACCEPT_TAPPED));
        assertEquals("you tapped Accept, and Dasher showed a delivery screen",
                detail(line(1675), DecisionLog.StepKind.ACCEPTED));
        contains(history(), "you tapped Accept, and Dasher showed a delivery screen");
        assertRulesUnchanged(RULES);
    }

    @Test public void automaticRequestObservedWithoutAnotherEventIsCountedAsAutomatic() {
        controller.get().onServiceConnected();
        FilterSettings rules = FilterSettings.of(true, 1000, 0, 0, 0);
        FilterStore.save(app, rules);
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
        assertAcceptedAutomatically(2000);
        assertEquals("automatic Accept was requested, and Dasher showed a delivery screen",
                detail(line(2000), DecisionLog.StepKind.ACCEPTED_AUTOMATIC));
        assertRulesUnchanged(rules);
        assertEquals(1, Shadows.shadowOf(accept).getPerformedActions().size());
    }

    @Test public void provenUnsentAfterPersistenceLeavesALaterManualAcceptanceTheUsers() {
        finalGuardRefusal(null);
        assertFalse("restart suppression remains present", app.getSharedPreferences("restart_suppression", 0)
                .getAll().isEmpty());
        clicked(accept);
        show(screen("Arrived at store"));
        assertAcceptedByTheUser(1675);
        assertFalse(AutoAcceptMemory.covers(app, new OfferSnapshot(1675, 3.9, 30, 3)));
        assertRulesUnchanged(RULES);
    }

    @Test public void provenUnsentRollbackPreservesEarlierAutomaticRequestForTheSameOffer() {
        OfferSnapshot prior = new OfferSnapshot(1675, 3.9, 30, 3);
        finalGuardRefusal(prior);
        assertTrue(AutoAcceptMemory.covers(app, prior));
        clicked(accept);
        show(screen("Arrived at store"));
        // The earlier automatic request for this very offer is kept: its acceptance is marked automatic, never the
        // user's own.
        assertAcceptedAutomatically(1675);
        contains(detail(line(1675), DecisionLog.StepKind.ACCEPTED_AUTOMATIC),
                "an automatic Accept was requested for this offer moments before");
        assertRulesUnchanged(RULES);
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
        assertNotAccepted(1675);
        assertTrue(history(), has(line(1675), DecisionLog.StepKind.NOT_LEARNED));
        contains(history(), "Not counted from what followed: Dasher showed neither a delivery nor the wait for offers "
                + "within 60 s");
    }

    @Test public void explicitPickupWithUnexplainedPayStillCannotCount() {
        show(waiting());
        show(passing("0:35"));
        later(1_000);
        show(screen("$16.75", "Order (2 items)"));
        replaceWithoutEvent(screen("Pick up by 7:52 PM", "Arrived at store", "$9.99", "Order (2 items)"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertNotAccepted(1675);
        contains(DiagnosticLog.read(app), "progress=true; route=true");
        contains(DiagnosticLog.read(app), "outcome=OFFER_FACTS");
    }

    @Test public void explicitPickupRetainingTheExactWatchedPayAndItemsCountsTheOriginalOffer() {
        show(waiting());
        AccessibilityNodeInfo shopping = passing("0:35");
        Shadows.shadowOf(shopping).addChild(node("2 items", false));
        show(shopping);
        later(1_000);
        show(screen("$16.75", "2 items"));
        replaceWithoutEvent(screen("Pick up by 7:52 PM", "Arrived at store", "$16.75", "2 items"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100));
        assertAcceptedByTheUser(1675);
        DecisionLog.Entry accepted = line(1675);
        assertEquals("the original offer's line is the one counted", Double.valueOf(3.9), accepted.facts.miles);
        assertEquals(Integer.valueOf(2), accepted.facts.items);
        OfferSnapshot route = ActiveRouteStore.load(app);
        assertEquals("the retained screen cannot replace the original route", Double.valueOf(3.9), route.miles);
        assertEquals(Integer.valueOf(1675), route.payCents);
        assertEquals(Integer.valueOf(2), route.items);
        contains(history(), "Accepted: it closed with about 0:34 left on its countdown");
        assertRulesUnchanged(RULES);
        String diagnostics = DiagnosticLog.read(app);
        contains(diagnostics, "progress_kind=arrived_at_store; pay_relation=matched; money_malformed=false");
        for (String line : diagnostics.split("\\n")) if (line.contains("outcome evidence:")) {
            assertFalse("categorical evidence has no amount", line.contains("$") || line.contains("16.75"));
        }
    }

    @Test public void uncertainAutomaticProvenanceMarksTheRetainedPayAcceptanceAutomatic() {
        OfferSnapshot original = new OfferSnapshot(1675, 3.9, 30, 3).withItems(2, true);
        assertTrue(AutoAcceptMemory.remember(app, original));
        show(waiting());
        AccessibilityNodeInfo shopping = passing("0:35");
        Shadows.shadowOf(shopping).addChild(node("2 items", false));
        show(shopping);
        later(1_000);
        show(screen("Arrived at store", "$16.75", "2 items"));
        assertAcceptedAutomatically(1675);
        assertRulesUnchanged(RULES);
        assertTrue(AutoAcceptMemory.covers(app, original));
    }

    @Test public void provenUnsentCancellationLeavesALaterManualAcceptanceWithMatchingRetainedPayTheUsers() {
        finalGuardRefusal(null);
        clicked(accept);
        show(screen("Arrived at store", "$16.75"));
        assertAcceptedByTheUser(1675);
        assertFalse(AutoAcceptMemory.covers(app, new OfferSnapshot(1675, 3.9, 30, 3)));
        assertRulesUnchanged(RULES);
    }

    @Test public void malformedMoneyAndCountdownCannotTurnAPartialOfferIntoAnAcceptance() {
        show(waiting());
        show(passing("0:35"));
        later(1_000);
        show(screen("Arrived at store", "$16.750", "0:30"));
        assertNotAccepted(1675);
        contains(DiagnosticLog.read(app), "pay_relation=ambiguous; money_malformed=true");
    }

    @Test public void deadlineIsNotExtendedByRepeatedObservation() {
        show(waiting());
        show(passing("0:35"));
        later(1_000);
        show(screen("$16.75", "Order (2 items)"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61));
        contains(history(), "Not counted from what followed:");
        assertNotAccepted(1675);
        int roots = controller.get().rootFetches;
        replaceWithoutEvent(delivery());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals("the ended watch no longer reads through numeric-remnant busy state", roots, controller.get().rootFetches);
        assertNotAccepted(1675);
    }

    @Test public void lockedPhoneCancelsObservationBeforeReadingAPickup() {
        show(waiting()); show(passing("0:35")); later(1_000);
        show(screen("$16.75"));
        replaceWithoutEvent(delivery());
        Shadows.shadowOf(app.getSystemService(android.app.KeyguardManager.class)).setKeyguardLocked(true);
        int roots = controller.get().rootFetches;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(roots, controller.get().rootFetches);
        assertNotAccepted(1675);
    }

    @Test public void lockDuringTheFinalChildFetchCannotTurnAStaleObservationIntoAnAcceptance() {
        show(waiting()); show(passing("0:35")); later(1_000);
        show(screen("$16.75"));
        replaceWithoutEvent(screen("Arrived at store"));
        OfferFilterService.nodeFetchForTests = () -> {
            OfferFilterService.nodeFetchForTests = null;
            Shadows.shadowOf(app.getSystemService(android.app.KeyguardManager.class)).setKeyguardLocked(true);
        };
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertNotAccepted(1675);
        assertFalse(DecisionLog.accepted(DecisionLog.recent(app, 1).get(0)));
    }

    @Test public void aNewCompleteEventlessOfferIsHandledNormallyAndNeverCountsAsTheOldAcceptance() {
        show(waiting()); show(passing("0:35")); later(1_000);
        show(screen("$16.75"));
        replaceWithoutEvent(offer("$19.00", "2 stops (4 mi) • 30 min", "0:35"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100));
        assertNotAccepted(1675);
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
        assertNotAccepted(1675);
    }

    @Test public void interruptDiscardsPendingObservation() {
        show(waiting()); show(passing("0:35")); later(1_000);
        show(screen("$16.75"));
        controller.get().onInterrupt();
        replaceWithoutEvent(delivery());
        int roots = controller.get().rootFetches;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(roots, controller.get().rootFetches);
        assertNotAccepted(1675);
    }

    @Test public void hiddenDasherIsNotReadAndCountsNothing() {
        show(waiting()); show(passing("0:35")); later(1_000);
        show(screen("$16.75"));
        AccessibilityNodeInfo other = node("Arrived at store", false);
        other.setPackageName("another.app");
        replaceWithoutEvent(other);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertNotAccepted(1675);
        assertFalse(history(), history().contains("Accepted:"));
    }

    @Test public void countdownOrOneOfferControlKeepsObservationOff() {
        for (String blocker : new String[]{"0:24", "Accept", "Decline", "New Delivery!"}) {
            show(waiting()); show(passing("0:35")); later(1_000);
            show(screen("$16.75", blocker));
            replaceWithoutEvent(delivery());
            // Normal incomplete-control retries may still run; only assert no extra observation timer.
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
            for (DecisionLog.Entry entry : DecisionLog.recent(app, 20)) {
                assertFalse(blocker + ":\n" + history(), DecisionLog.accepted(entry));
            }
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
        assertNotAccepted(1675);
        assertRulesUnchanged(RULES);
    }

    @Test public void theUsersOwnAcceptanceIsCountedOnceAndNothingIsKeptOfItButItsLine() {
        // The step goes on the offer's own line only: no other record of accepted offers is kept (0.5.0 keeps no
        // learned minimum), and the line keeps the facts it was decided on.
        show(waiting());
        show(passing("0:35"));
        clicked(accept);
        show(delivery());
        DecisionLog.Entry accepted = line(1675);
        long acceptances = accepted.steps.stream().filter(step -> step.kind == DecisionLog.StepKind.ACCEPTED).count();
        assertEquals(history(), 1, acceptances);
        assertEquals(Collections.emptyMap(), app.getSharedPreferences(FilterStore.RETIRED_MANUAL_DECLINES, 0).getAll());
        for (String retired : FilterStore.RETIRED_KEYS) {
            assertFalse(retired, app.getSharedPreferences("offer_filter", 0).contains(retired));
        }
        assertRulesUnchanged(RULES);
        // What came after the offer gives it no second acceptance: the tap path counted it already.
        later(1_000);
        show(waiting());
        assertEquals(history(), 1, line(1675).steps.stream()
                .filter(step -> step.kind == DecisionLog.StepKind.ACCEPTED).count());
    }
}

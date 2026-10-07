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
        FilterStore.save(app, FilterSettings.of(true, 1000, 0, 0, 0));
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
        assertRulesUnchanged();
        assertTrue(DiagnosticLog.read(app).contains("Accept REQUESTED"));
        assertFalse(DiagnosticLog.read(app).contains("Accept NOT_SENT"));
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
        assertNotSent("offer_rules_or_deadline_changed");
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
    @Test public void contentEventInvalidatesOldNodeAndRequiresAFreshCompleteReadBeforeAccept() {
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
                assertEquals("The stale read cannot dispatch", 0, clicks(accept));
            }
            return service.getWindows();
        };
        pass(1500); assertTrue(injected[0]);
        assertEquals(DiagnosticLog.read(app), 1, clicks(accept));
        assertTrue(DiagnosticLog.read(app).contains("Accept verification reread"));
        assertFalse(DiagnosticLog.read(app).contains("Accept NOT_SENT"));
        pass(2000); assertEquals(1, clicks(accept));
    }
    @Test public void refusedClickNeverRetriesOrClaimsAccepted() {
        AccessibilityNodeInfo root = offer("$20.00", "2 stops (4 mi) • 20 min", "0:30");
        Shadows.shadowOf(accept).setOnPerformActionListener((a, b) -> false);
        show(root); pass(900); assertEquals(1, clicks(accept));
        show(root); pass(900); assertEquals(1, clicks(accept)); assertNull(ActiveRouteStore.load(app));
        assertTrue(DiagnosticLog.read(app).contains("Accept REFUSED"));
    }

    @Test public void repeatedFinalContentEventsExhaustRereadsWithoutAnyTap() {
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30"));
        int[] changes = {0};
        service.windowSource = () -> {
            if (calledFrom("dasherStillReadable") && changes[0] < 3) {
                changes[0]++;
                contentEventOnMainCallback();
            }
            return service.getWindows();
        };
        pass(2500);
        assertEquals(3, changes[0]);
        assertEquals(0, clicks(accept));
        assertNotSent("content_changed");
        pass(2000); assertEquals(0, clicks(accept));
    }

    @Test public void postPersistenceContentEventStillPermanentlyStopsTheOffer() {
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30"));
        int[] checks = {0};
        service.windowSource = () -> {
            if (calledFrom("dasherStillReadable") && ++checks[0] == 2) contentEventOnMainCallback();
            return service.getWindows();
        };
        pass(1500);
        assertEquals(0, clicks(accept));
        assertNotSent("content_changed");
        assertTrue(DiagnosticLog.read(app).contains("phase=after_persistence"));
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:29"));
        pass(1500); assertEquals(0, clicks(accept));
    }
    @Test public void observedDeliveryUpdatesRouteAndOutcomeAsAnAutomaticAcceptanceChangingNoRule() {
        show(node("Finding offers", false));
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30")); pass(900); assertEquals(1, clicks(accept));
        click(accept); show(node("Arrived at store", true));
        assertNotNull(ActiveRouteStore.load(app)); assertEquals(Integer.valueOf(2000), ActiveRouteStore.load(app).payCents);
        DecisionLog.Entry entry = DecisionLog.recent(app, 1).get(0);
        // Accepted after the app's own request: counted, with that provenance, never as the user's own acceptance.
        assertEquals(DecisionLog.Outcome.ACCEPTED, DecisionLog.outcome(entry));
        assertTrue(DecisionLog.hasStep(entry, DecisionLog.StepKind.ACCEPTED_AUTOMATIC));
        assertFalse(DecisionLog.hasStep(entry, DecisionLog.StepKind.ACCEPTED));
        assertFalse(DecisionLog.hasStep(entry, DecisionLog.StepKind.ACCEPT_TAPPED));
        assertRulesUnchanged();
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("[accept] Accepted after an automatic Accept request: Pay $20.00"));
        assertFalse(log, log.contains("Learned from") || log.contains("learned minimums"));
    }
    @Test public void automaticShoppingAcceptanceKeepsTheObservedItemsOnItsRouteAndChangesNoRule() {
        show(node("Finding offers", false));
        AccessibilityNodeInfo shopping = offer("$20.00", "2 stops (4 mi) • 20 min", "0:30");
        Shadows.shadowOf(shopping).addChild(node("Shop and deliver", false));
        Shadows.shadowOf(shopping).addChild(node("2 items", false));
        show(shopping); pass(900); assertEquals(1, clicks(accept));
        click(accept); show(node("Arrived at store", true));
        assertNotNull(ActiveRouteStore.load(app));
        assertEquals(Integer.valueOf(2), ActiveRouteStore.load(app).items);
        DecisionLog.Entry entry = DecisionLog.recent(app, 1).get(0);
        assertTrue(DecisionLog.accepted(entry));
        assertTrue(DecisionLog.hasStep(entry, DecisionLog.StepKind.ACCEPTED_AUTOMATIC));
        assertFalse(DecisionLog.hasStep(entry, DecisionLog.StepKind.ACCEPTED));
        assertEquals(Integer.valueOf(2), entry.facts.items);
        assertRulesUnchanged();
        assertTrue(DiagnosticLog.read(app).contains("automatic Accept was requested, and Dasher showed a delivery screen"));
    }
    @Test public void serviceRestartSuppressesRepeatAndRetainsAutomaticProvenance() {
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30")); pass(900); assertEquals(1, clicks(accept));
        controller.destroy(); connect();
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:29")); pass(900);
        assertEquals(0, clicks(accept)); assertEquals(0, clicks(decline));
        assertTrue(AutoAcceptMemory.covers(app, new OfferSnapshot(2000, 4.0, 20, 2)));
    }
    @Test public void ownClickEchoIsNotTheUsersAcceptAndAFailedTransitionCountsNothing() {
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30")); pass(900); click(accept);
        show(node("Map", false)); pass(16_000);
        assertNull(ActiveRouteStore.load(app));
        DecisionLog.Entry entry = DecisionLog.recent(app, 1).get(0);
        assertFalse("its own click's echo is no Accept of the user's",
                DecisionLog.hasStep(entry, DecisionLog.StepKind.ACCEPT_TAPPED));
        assertFalse(DecisionLog.accepted(entry));
        assertTrue(DecisionLog.hasStep(entry, DecisionLog.StepKind.AUTO_ACCEPT_UNCONFIRMED));
        assertEquals(DecisionLog.Outcome.YOURS, DecisionLog.outcome(entry));
        assertRulesUnchanged();
    }
    /** No acceptance changes any rule (0.5.0): the $10 minimum and its bar stay exactly as set. */
    private void assertRulesUnchanged() {
        FilterSettings now = FilterStore.load(app);
        assertArrayEquals(new int[] {1000, 0, 0, 0, 0, 0}, now.minimums());
        assertEquals(0, now.maxStops);
        assertEquals(100, now.minimumScalePercent);
    }
    @Test public void genericDirectionsCannotPromoteAutomaticRequestThroughManualInference() {
        show(node("Finding offers", false));
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30")); pass(900);
        assertEquals(1, clicks(accept));
        assertFalse(DecisionLog.accepted(DecisionLog.recent(app, 1).get(0)));
        show(node("Directions", true));
        assertNull(ActiveRouteStore.load(app));
        assertFalse(DecisionLog.accepted(DecisionLog.recent(app, 1).get(0)));
        pass(16_000);
        assertEquals(DecisionLog.Outcome.YOURS, DecisionLog.outcome(DecisionLog.recent(app, 1).get(0)));
        assertRulesUnchanged();
    }
    @Test public void idleEndsAutomaticConfirmationBeforeALaterUnrelatedDelivery() {
        show(node("Finding offers", false));
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30")); pass(900);
        show(node("Finding offers", false));
        show(node("Arrived at store", true));
        assertNull(ActiveRouteStore.load(app));
        assertFalse(DecisionLog.accepted(DecisionLog.recent(app, 1).get(0)));
        assertEquals(DecisionLog.Outcome.YOURS, DecisionLog.outcome(DecisionLog.recent(app, 1).get(0)));
    }
    @Test public void partialAcceptControlsCannotConfirmDeliveryBehindThem() {
        show(node("Finding offers", false));
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30")); pass(900);
        AccessibilityNodeInfo partial = node("Arrived at store", true);
        Shadows.shadowOf(partial).addChild(node("Accept", true));
        show(partial);
        assertNull(ActiveRouteStore.load(app));
        assertFalse(DecisionLog.accepted(DecisionLog.recent(app, 1).get(0)));
    }
    @Test public void aRefusedAutomaticClickCannotBecomeAnInferredAcceptance() {
        show(node("Finding offers", false));
        AccessibilityNodeInfo root = offer("$20.00", "2 stops (4 mi) • 20 min", "0:30");
        Shadows.shadowOf(accept).setOnPerformActionListener((a, b) -> false);
        show(root); pass(900);
        show(node("Directions", true));
        assertNull(ActiveRouteStore.load(app));
        assertEquals(DecisionLog.Outcome.YOURS, DecisionLog.outcome(DecisionLog.recent(app, 1).get(0)));
    }
    @Test public void timeoutAndSameOfferRereadsNeverRecreateManualInferenceOrRetry() {
        show(node("Finding offers", false));
        AccessibilityNodeInfo root = offer("$20.00", "2 stops (4 mi) • 20 min", "0:30");
        show(root); pass(900); assertEquals(1, clicks(accept));
        pass(16_000);
        assertEquals(DecisionLog.Outcome.YOURS, DecisionLog.outcome(DecisionLog.recent(app, 1).get(0)));
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:13")); pass(900);
        assertEquals(0, clicks(accept)); assertEquals(0, clicks(decline));
        show(node("Directions", true));
        show(node("Arrived at store", true));
        assertNull(ActiveRouteStore.load(app));
        assertFalse(DecisionLog.accepted(DecisionLog.recent(app, 1).get(0)));
        assertRulesUnchanged();
    }
    @Test public void confirmedAutomaticRequestNeverSaysTheUserTappedAccept() {
        show(node("Finding offers", false));
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30")); pass(900);
        show(node("Arrived at store", true));
        DecisionLog.Entry entry = DecisionLog.recent(app, 1).get(0);
        assertTrue(DecisionLog.accepted(entry));
        for (DecisionLog.Step step : entry.steps) {
            assertNotEquals(DecisionLog.StepKind.ACCEPT_TAPPED, step.kind);
            assertFalse(step.detail.contains("you tapped Accept"));
        }
    }
    @Test public void targetWindowHiddenAtFinalGuardIsNotSentAndExplained() {
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30"));
        boolean[] changed = {false};
        service.windowSource = () -> {
            if (!changed[0] && calledFrom("dasherStillReadable")) {
                changed[0] = true;
                return java.util.Collections.emptyList();
            }
            return service.getWindows();
        };
        pass(1000); assertTrue(changed[0]); assertEquals(0, clicks(accept));
        assertNotSent("target_window_hidden");
    }
    @Test public void canceledCandidateMarksNotSentOnceWithoutBlockingLaterManualAcceptance() {
        show(node("Finding offers", false));
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30")); pass(300);
        touch(); pass(0);
        assertEquals(0, clicks(accept));
        assertNotSent("user_action");
        pass(200); show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:29"));
        assertEquals(0, clicks(accept));
        click(accept); show(node("Arrived at store", true));
        DecisionLog.Entry entry = DecisionLog.recent(app, 1).get(0);
        assertTrue(DecisionLog.accepted(entry));
        assertNotNull(ActiveRouteStore.load(app));
        long notSent = entry.steps.stream().filter(step -> step.kind == DecisionLog.StepKind.AUTO_ACCEPT_NOT_SENT).count();
        assertEquals(1, notSent);
        assertFalse(DiagnosticLog.read(app).contains("Accept REQUESTED"));
    }
    @Test public void replacedCandidateGetsNotSentBeforeTheNewOfferCanRequestAccept() {
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30")); pass(300);
        show(offer("$22.00", "2 stops (4 mi) • 20 min", "0:30"));
        assertEquals(0, clicks(accept));
        java.util.List<DecisionLog.Entry> entries = DecisionLog.recent(app, 2);
        assertEquals(2, entries.size());
        assertEquals(Integer.valueOf(2000), entries.get(1).facts.payCents);
        assertEquals(DecisionLog.Outcome.YOURS, DecisionLog.outcome(entries.get(1)));
        assertTrue(entries.get(1).steps.stream().anyMatch(step -> step.kind == DecisionLog.StepKind.AUTO_ACCEPT_NOT_SENT
                && step.detail.equals("candidate_replaced")));
        assertTrue(entries.get(0).steps.stream().noneMatch(step -> step.kind == DecisionLog.StepKind.AUTO_ACCEPT_NOT_SENT));
        pass(900); assertEquals(1, clicks(accept));
        assertEquals(DecisionLog.Outcome.REQUESTED, DecisionLog.outcome(DecisionLog.recent(app, 1).get(0)));
    }
    @Test public void freshCountdownWithSameFactsMarksOnlyThePreviousHistoryInstance() {
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30")); pass(300);
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:45"));
        java.util.List<DecisionLog.Entry> entries = DecisionLog.recent(app, 2);
        assertEquals(2, entries.size());
        assertEquals(DecisionLog.Outcome.YOURS, DecisionLog.outcome(entries.get(1)));
        assertEquals(DecisionLog.Outcome.PASSED, DecisionLog.outcome(entries.get(0)));
        pass(900); assertEquals(1, clicks(accept));
        assertEquals(DecisionLog.Outcome.REQUESTED, DecisionLog.outcome(DecisionLog.recent(app, 1).get(0)));
    }
    @Test public void ordinaryContinuingCountdownDoesNotInventCandidateReplacement() {
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30")); pass(300);
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:29")); pass(900);
        assertEquals(1, clicks(accept));
        assertFalse(DiagnosticLog.read(app).contains("Accept NOT_SENT"));
    }
    @Test public void diagnosticRuntimeFailureCannotInterruptCancellationCleanup() {
        show(offer("$20.00", "2 stops (4 mi) • 20 min", "0:30")); pass(300);
        android.content.SharedPreferences diagnostics = app.getSharedPreferences("offer_filter_diagnostics", 0);
        diagnostics.edit().putString("off", "synthetic wrong type").commit();
        try {
            org.robolectric.util.ReflectionHelpers.callInstanceMethod(service, "cancelAutoAccept",
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(boolean.class, true),
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(String.class, "user_action"));
            assertFalse(org.robolectric.util.ReflectionHelpers.<Boolean>getField(service, "autoAcceptWatched"));
            AutoAccept state = org.robolectric.util.ReflectionHelpers.getField(service, "autoAccept");
            assertNull(state.candidate());
            assertTrue(state.blocks(new OfferSnapshot(2000, 4.0, 20, 2), SystemClock.uptimeMillis()));
        } finally {
            diagnostics.edit().remove("off").commit();
        }
        pass(1200);
        assertEquals(0, clicks(accept));
        assertFalse(org.robolectric.util.ReflectionHelpers.<Boolean>getField(service, "touchWatchOn"));
    }
    private void assertNotSent(String reason) {
        DecisionLog.Entry entry = DecisionLog.recent(app, 1).get(0);
        assertEquals(DecisionLog.Outcome.YOURS, DecisionLog.outcome(entry));
        assertFalse(DecisionLog.accepted(entry));
        assertTrue(entry.steps.stream().anyMatch(step -> step.kind == DecisionLog.StepKind.AUTO_ACCEPT_NOT_SENT
                && step.detail.equals(reason)));
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("Accept NOT_SENT; reason=" + reason + "; offer left to user"));
        assertFalse(log, log.contains("Accept REQUESTED"));
        assertFalse(log, log.contains("Accept REFUSED"));
    }
    private void contentEventOnMainCallback() {
        AccessibilityEvent changed = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        changed.setPackageName("com.doordash.driverapp"); changed.setEventTime(SystemClock.uptimeMillis());
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread delivery = new Thread(() -> {
            try { service.onAccessibilityEvent(changed); } catch (Throwable error) { failure.set(error); }
        });
        delivery.start();
        try { delivery.join(5000); } catch (InterruptedException e) { throw new AssertionError(e); }
        assertFalse(delivery.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private void touch() {
        ShadowWindowManagerImpl windows = Shadow.extract(service.getSystemService(WindowManager.class));
        for (View view : new java.util.ArrayList<>(windows.getViews())) {
            if (view instanceof DasherTab || view instanceof DasherGuide || view instanceof BackToMapChip) continue;
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

package com.local.dasherfilter;

import android.accessibilityservice.AccessibilityService;
import android.app.Application;
import android.app.Notification;
import android.content.Intent;
import android.graphics.Rect;
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
import java.util.Arrays;
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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityRecord;
import org.robolectric.shadows.ShadowAccessibilityService;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowWindowManagerImpl;
import static org.junit.Assert.*;

/** Synthetic toast delivery through the real service; actual Dasher/OEM toast exposure still needs phone testing. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, shadows = DeclineErrorRecoveryAdapterTest.GlobalActions.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DeclineErrorRecoveryAdapterTest {
    private static final String DASHER = "com.doordash.driverapp";
    private static final String OBSERVED_ERROR = "Something went wrong. Please try again.";
    private Application app;
    private ServiceController<OfferFilterService> controller;
    private OfferFilterService service;
    private AccessibilityNodeInfo decline;
    private long began;
    @Implements(AccessibilityService.class)
    public static class GlobalActions extends ShadowAccessibilityService {
        static boolean refuse;
        @Implementation @Override protected boolean performGlobalAction(int action) {
            super.performGlobalAction(action);
            return !refuse;
        }
    }
    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        DiagnosticLog.clear(app);
        DecisionLog.forgetCache(); DecisionLog.clear(app);
        RestartSuppression.clear(app);
        OfferSilencer.forgetCache();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        OfferFilterService.forgetScreenState();
        GlobalActions.refuse = false;
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
    @Test public void exactToastAndStrandedMapBackThenRevalidateSameOfferWithinOriginalBudget() throws Exception {
        begin();
        long deadline = state().confirmationUntil();
        failOn(node("Map", false));
        pass(600);
        assertEquals(Arrays.asList(AccessibilityService.GLOBAL_ACTION_BACK), actions());
        assertEquals(DecisionLog.Action.DECLINE_TAPPED, DecisionLog.recent(app, 1).get(0).action);
        assertEquals(1, state().declineAttempts());
        show(offer("$7.90", remaining()));
        pass(1_500);
        assertEquals(1, clicks(decline));
        assertEquals(2, state().declineAttempts());
        assertEquals("Back and rereads never extend original authority", deadline, state().confirmationUntil());
        assertEquals(1, DecisionLog.recent(app, 10).size());
    }
    @Test public void observedErrorToastRecoversOnlyWithinTheOriginalOfferBudget() throws Exception {
        begin();
        long deadline = state().confirmationUntil();
        toast(OBSERVED_ERROR, DASHER, "android.widget.Toast", SystemClock.uptimeMillis(), false);
        show(node("Map", false));
        pass(600);
        assertEquals(Arrays.asList(AccessibilityService.GLOBAL_ACTION_BACK), actions());
        show(offer("$7.90", remaining()));
        pass(1_500);
        assertEquals(1, clicks(decline));
        assertEquals(2, state().declineAttempts());
        assertEquals(deadline, state().confirmationUntil());
        assertFalse("toast wording is not retained", DiagnosticLog.read(app).contains(OBSERVED_ERROR));
    }
    @Test public void observedErrorToastAllowsOnlyCaseWhitespaceAndTerminalPunctuationNormalization() {
        begin();
        toast("  SOMETHING WENT WRONG. PLEASE TRY AGAIN!  ", DASHER, "android.widget.Toast",
                SystemClock.uptimeMillis(), false);
        show(node("Map", false));
        pass(600);
        assertEquals(1, actions().size());
    }
    @Test public void observedErrorWithoutOurDeclineRequestCannotArmRecovery() {
        show(node("Map", false));
        toast(OBSERVED_ERROR, DASHER, "android.widget.Toast", SystemClock.uptimeMillis(), false);
        pass(600);
        assertTrue(actions().isEmpty());
        assertFalse(DiagnosticLog.read(app).contains("Dasher reported an error after a Decline request"));
    }
    @Test public void observedErrorStillRejectsOtherSourcesStaleEventsAndMisleadingText() {
        begin();
        show(node("Map", false));
        pass(5_000);
        long now = SystemClock.uptimeMillis();
        toast(OBSERVED_ERROR, "com.example.other", "android.widget.Toast", now, false);
        toast(OBSERVED_ERROR, DASHER, "android.app.Notification", now, false);
        toast(OBSERVED_ERROR, DASHER, "android.widget.Toast", began - 1, false);
        toast(OBSERVED_ERROR, DASHER, "android.widget.Toast", now - 4_001, false);
        toast(OBSERVED_ERROR, DASHER, "android.widget.Toast", now + 1_000, false);
        toast(OBSERVED_ERROR, DASHER, "android.widget.Toast", now, true);
        toast(OBSERVED_ERROR + " PRIVATE_SENTINEL", DASHER, "android.widget.Toast", now, false);
        toast("Something went wrong. Please try again later.", DASHER, "android.widget.Toast", now, false);
        pass(600);
        assertTrue(actions().isEmpty());
        String log = DiagnosticLog.read(app);
        assertFalse(log.contains("Dasher reported an error after a Decline request"));
        assertFalse(log.contains("PRIVATE_SENTINEL"));
    }
    @Test public void observedErrorInInactiveSplitHalfStaysUnconfirmedWithoutGlobalBack() {
        observedErrorInSplit(false);
    }
    @Test public void observedErrorInFocusedSplitHalfStillCannotAuthorizeGlobalBack() {
        observedErrorInSplit(true);
    }
    private void observedErrorInSplit(boolean dasherFocused) {
        begin();
        AccessibilityNodeInfo map = node("Map", false);
        show(map);
        AccessibilityWindowInfo dasher = service.getWindows().get(0);
        ShadowAccessibilityWindowInfo ds = Shadow.extract(dasher);
        ds.setActive(dasherFocused); ds.setFocused(dasherFocused);
        ds.setBoundsInScreen(new Rect(0, 1040, 1080, 2040));
        AccessibilityNodeInfo own = node("Other", false); own.setPackageName(app.getPackageName());
        AccessibilityWindowInfo other = window(own, 200, !dasherFocused, new Rect(0, 0, 1080, 1000));
        AccessibilityWindowInfo divider = AccessibilityWindowInfo.obtain();
        ((ShadowAccessibilityWindowInfo) Shadow.extract(divider)).setType(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER);
        Shadows.shadowOf(service).setWindows(Arrays.asList(dasher, other, divider));
        Shadows.shadowOf(service).setRootInActiveWindow(dasherFocused ? map : own);
        toast(OBSERVED_ERROR, DASHER, "android.widget.Toast", SystemClock.uptimeMillis(), false);
        pass(4_500);
        assertTrue("Back must never target either split half", actions().isEmpty());
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
        assertTrue(DiagnosticLog.read(app).contains("Dasher reported an error after a Decline request"));
        assertFalse(DiagnosticLog.read(app).contains(OBSERVED_ERROR));
    }
    @Test public void errorAfterEightSecondHangStillRecoversWithoutDeadlineExtension() throws Exception {
        begin(); long deadline = state().confirmationUntil();
        show(node("Map", false)); pass(8_000);
        toast("ERROR!", DASHER, "android.widget.Toast", SystemClock.uptimeMillis(), false);
        pass(600);
        assertEquals(1, actions().size());
        assertEquals(deadline, state().confirmationUntil());
    }
    @Test public void originalDeadlineCanExpireBeforeTheStrandedMapSettles() throws Exception {
        begin();
        long deadline = state().confirmationUntil();
        show(node("Map", false));
        pass(deadline - SystemClock.uptimeMillis() - 200);
        toast("error", DASHER, "android.widget.Toast", SystemClock.uptimeMillis(), false);
        pass(600);
        assertTrue("fresh error evidence cannot extend the original offer deadline", actions().isEmpty());
        assertFalse(state().hasPendingConfirmation(SystemClock.uptimeMillis()));
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
    }
    @Test public void repeatedErrorCyclesStopAfterTwoBacksAndDoNotResetAttemptCount() throws Exception {
        begin(); long deadline = state().confirmationUntil();
        for (int i = 0; i < 2; i++) {
            failOn(node("Map", false)); pass(600);
            assertEquals(i + 1, actions().size());
            show(offer("$7.90", remaining())); pass(1_500);
            assertEquals(i + 2, state().declineAttempts());
            assertEquals(deadline, state().confirmationUntil());
        }
        failOn(node("Map", false)); pass(1_000);
        assertEquals(2, actions().size());
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
    }
    @Test public void aReturnWithMissingOriginalNumericFactsCannotResumeItsDecline() {
        begin(); failOn(node("Map", false)); pass(600);
        AccessibilityNodeInfo partial = node("", false);
        AccessibilityNodeInfo nextDecline = node("Decline", true);
        Shadows.shadowOf(partial).addChild(nextDecline);
        Shadows.shadowOf(partial).addChild(node("$7.90", false));
        Shadows.shadowOf(partial).addChild(node("Accept", true));
        show(partial); pass(2_500);
        assertEquals(0, clicks(nextDecline));
        assertEquals(1, actions().size());
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
    }
    @Test public void loadingWithoutToastAndNearMissMessagesNeverAuthorizeBack() {
        begin(); show(node("Map", false));
        long now = SystemClock.uptimeMillis();
        toast("error", "com.example.other", "android.widget.Toast", now, false);
        toast("error", DASHER, "android.app.Notification", now, false);
        toast("error PRIVATE_SENTINEL", DASHER, "android.widget.Toast", now, false);
        toast("error", DASHER, "android.widget.Toast", began - 1, false);
        toast("error", DASHER, "android.widget.Toast", now, true);
        pass(1_000);
        assertTrue(actions().isEmpty());
        assertFalse(DiagnosticLog.read(app).contains("PRIVATE_SENTINEL"));
    }
    @Test public void actionableControlsAndAmbiguousIdleNeverBackOrClaimCompletion() {
        begin(); failOn(node("Finding offers", false)); pass(4_500);
        assertTrue(actions().isEmpty());
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
        assertFalse(DiagnosticLog.read(app).contains("returned to the idle screen after decline"));
    }
    @Test public void anUnknownClickableControlBlocksBack() {
        begin(); failOn(node("Map", true)); pass(1_000);
        assertTrue(actions().isEmpty());
    }
    @Test public void aMapsOwnControlBlocksBackThoughOtherReadsLeaveTheMapUnread() {
        // Every other read leaves a map's subtree unread (MapNodes); the recovery's reads take it in full, so a control
        // drawn inside the map is the actionable control it is.
        AccessibilityNodeInfo map = node("", false);
        map.setClassName("com.google.android.gms.maps.MapView");
        map.setContentDescription("Google Map");
        Shadows.shadowOf(map).addChild(node("My location", true));
        begin(); failOn(map); pass(1_000);
        assertTrue(actions().isEmpty());
    }
    @Test public void aMapWithNothingActionableInsideStillAllowsTheRecovery() {
        AccessibilityNodeInfo map = node("", false);
        map.setClassName("com.google.android.gms.maps.MapView");
        map.setContentDescription("Google Map");
        for (int i = 0; i < 5; i++) Shadows.shadowOf(map).addChild(node("", false));
        begin(); failOn(map); pass(600);
        assertEquals(Arrays.asList(AccessibilityService.GLOBAL_ACTION_BACK), actions());
    }
    @Test public void activeButUnfocusedDasherCannotReceiveGlobalBack() {
        begin(); failOn(node("Map", false));
        ((ShadowAccessibilityWindowInfo) Shadow.extract(service.getWindows().get(0))).setFocused(false);
        pass(600); assertTrue(actions().isEmpty());
    }
    @Test public void inactiveDasherHalfNeverBacksOutOfTheOtherApp() {
        begin(); failOn(node("Map", false));
        AccessibilityWindowInfo dasher = service.getWindows().get(0);
        ShadowAccessibilityWindowInfo ds = Shadow.extract(dasher);
        ds.setActive(false); ds.setFocused(false); ds.setBoundsInScreen(new Rect(0, 1040, 1080, 2040));
        AccessibilityNodeInfo other = node("Other", false); other.setPackageName(app.getPackageName());
        AccessibilityWindowInfo foreground = window(other, 200, true, new Rect(0, 0, 1080, 1000));
        AccessibilityWindowInfo divider = AccessibilityWindowInfo.obtain();
        ((ShadowAccessibilityWindowInfo) Shadow.extract(divider)).setType(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER);
        Shadows.shadowOf(service).setWindows(Arrays.asList(dasher, foreground, divider));
        Shadows.shadowOf(service).setRootInActiveWindow(other);
        pass(600); assertTrue(actions().isEmpty());
    }
    @Test public void switchingToAnotherFullScreenAppRevokesPendingBack() {
        begin(); failOn(node("Map", false));
        AccessibilityNodeInfo other = node("PRIVATE_OTHER_APP", false);
        other.setPackageName("com.example.other");
        AccessibilityWindowInfo foreground = window(other, 200, true, new Rect(0, 0, 1080, 2040));
        Shadows.shadowOf(service).setWindows(Arrays.asList(foreground));
        Shadows.shadowOf(service).setRootInActiveWindow(other);
        AccessibilityEvent changed = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED);
        service.onAccessibilityEvent(changed);
        pass(600);
        assertTrue(actions().isEmpty());
        assertFalse("another app's text must never enter recovery diagnostics",
                DiagnosticLog.read(app).contains("PRIVATE_OTHER_APP"));
        show(offer("$7.90", remaining())); pass(2_000);
        assertEquals("returning to Dasher cannot restore the revoked decline", 0, clicks(decline));
    }
    @Test public void aUserClickCancelsPendingBack() {
        begin(); failOn(node("Map", false));
        AccessibilityEvent touch = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
        touch.setPackageName(DASHER); touch.setEventTime(SystemClock.uptimeMillis());
        ((ShadowAccessibilityRecord) Shadow.extract(touch)).setSourceNode(node("Details", true));
        service.onAccessibilityEvent(touch); pass(600);
        assertTrue(actions().isEmpty());
    }
    @Test public void aFingerTouchAfterBackPreventsTheReturnedOfferFromBeingRetried() {
        begin(); failOn(node("Map", false)); pass(600);
        assertEquals(1, actions().size());
        touchScreen();
        show(offer("$7.90", remaining())); pass(2_000);
        assertEquals(0, clicks(decline));
        assertEquals(1, actions().size());
        assertEquals(DecisionLog.Action.USER_TOOK_OVER, DecisionLog.recent(app, 1).get(0).action);
    }
    @Test public void scaleChangeCancelsPendingBack() {
        begin(); failOn(node("Map", false));
        FilterStore.save(app, FilterStore.load(app).withMinimumScalePercent(97));
        pass(600); assertTrue(actions().isEmpty());
    }
    @Test public void screenOffCancelsPendingBack() {
        begin(); failOn(node("Map", false));
        Shadows.shadowOf(app.getSystemService(PowerManager.class)).setIsInteractive(false);
        app.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        pass(600); assertTrue(actions().isEmpty());
    }
    @Test public void interruptionDuringFreshMetadataCheckCancelsBack() {
        begin(); failOn(node("Map", false));
        final int[] calls = {0};
        service.windowSource = () -> {
            if (++calls[0] == 2) service.onInterrupt();
            return service.getWindows();
        };
        pass(600); assertTrue(actions().isEmpty());
    }
    @Test public void slowFinalMetadataCannotUseAnExpiredErrorForBack() {
        begin(); failOn(node("Map", false));
        boolean[] injected = {false};
        service.windowSource = () -> {
            if (!injected[0] && calledFrom("foregroundDasherForBack")) {
                injected[0] = true;
                ShadowSystemClock.advanceBy(Duration.ofMillis(DeclineErrorRecovery.ERROR_FRESH_MS + 1));
            }
            return service.getWindows();
        };
        pass(700);
        assertTrue("the simulated slow call must occur at the final Back check", injected[0]);
        assertTrue("freshness must be rechecked after Android's metadata call returns", actions().isEmpty());
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
    }
    @Test public void contentEventDuringFinalMetadataInvalidatesTheBlankRead() {
        eventDuringFinalMetadata(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }
    @Test public void windowEventDuringFinalMetadataInvalidatesTheBlankRead() {
        eventDuringFinalMetadata(AccessibilityEvent.TYPE_WINDOWS_CHANGED);
    }
    @Test public void refusedBackLeavesOfferUnconfirmedAndNeverRetriesIt() {
        begin(); GlobalActions.refuse = true;
        failOn(node("Map", false)); pass(600);
        assertEquals(1, actions().size());
        show(offer("$7.90", remaining())); pass(3_000);
        assertEquals(0, clicks(decline));
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
        show(node("Finding offers", false)); pass(2_000);
        assertEquals("a later idle frame cannot turn refused Back into a completed decline",
                DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
    }
    @Test public void returningAfterTheThreeSecondRecoveryWindowCannotResumeAutomaticDecline() {
        begin(); failOn(node("Map", false)); pass(3_700);
        assertEquals(1, actions().size());
        show(offer("$7.90", remaining())); pass(2_000);
        assertEquals(0, clicks(decline));
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
    }
    @Test public void missingCountdownAfterBackCannotRevalidateTheOldOffer() {
        begin(); failOn(node("Map", false)); pass(600);
        assertEquals(1, actions().size());
        show(offer("$7.90", "")); pass(2_000);
        assertEquals("a matching price alone cannot establish the same countdown instance", 0, clicks(decline));
        assertEquals(1, actions().size());
    }
    @Test public void changedStableLabelWhileErrorIsPendingCannotStartANewAttempt() throws Exception {
        begin(); long deadline = state().confirmationUntil();
        AccessibilityNodeInfo changed = offer("$7.90", remaining());
        Shadows.shadowOf(changed).addChild(node("Different merchant", false));
        failOn(changed); pass(2_000);
        assertEquals("matching metrics do not make changed stable labels the same offer", 0, clicks(decline));
        assertTrue(actions().isEmpty());
        assertTrue("uncertain identity never grants a new deadline", state().confirmationUntil() <= deadline);
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
        failOn(node("Map", false)); pass(600);
        assertTrue("the ambiguous offer cannot authorize a later Back", actions().isEmpty());
    }
    @Test public void partialMetricsWhileErrorIsPendingCannotStartANewAttempt() throws Exception {
        begin(); long deadline = state().confirmationUntil();
        failOn(partialOffer("$7.90", remaining())); pass(2_000);
        assertEquals("known failing pay cannot substitute for recovery identity", 0, clicks(decline));
        assertTrue(actions().isEmpty());
        assertTrue(state().confirmationUntil() <= deadline);
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
        failOn(node("Map", false)); pass(600);
        assertTrue(actions().isEmpty());
    }
    @Test public void changedStableLabelOnALaterRecoveredReadCannotResetDeadlineOrBackBudget() throws Exception {
        begin(); long deadline = state().confirmationUntil();
        failOn(node("Map", false)); pass(600);
        show(offer("$7.90", remaining())); pass(1_500);
        assertEquals(1, clicks(decline));
        assertEquals(2, state().declineAttempts());
        assertEquals(deadline, state().confirmationUntil());
        AccessibilityNodeInfo changed = offer("$7.90", remaining());
        Shadows.shadowOf(changed).addChild(node("Different merchant", false));
        show(changed); pass(2_000);
        assertEquals("successful recovery does not release the original stable identity", 0, clicks(decline));
        assertTrue(state().confirmationUntil() <= deadline);
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
        failOn(node("Map", false)); pass(600);
        assertEquals("changed labels cannot replenish the Back budget", 1, actions().size());
    }
    @Test public void partialMetricsOnALaterRecoveredReadCannotResetDeadlineOrBackBudget() throws Exception {
        begin(); long deadline = state().confirmationUntil();
        failOn(node("Map", false)); pass(600);
        show(offer("$7.90", remaining())); pass(1_500);
        assertEquals(1, clicks(decline));
        assertEquals(2, state().declineAttempts());
        assertEquals(deadline, state().confirmationUntil());
        show(partialOffer("$7.90", remaining())); pass(2_000);
        assertEquals("later missing facts must not create a fresh retry key", 0, clicks(decline));
        assertTrue(state().confirmationUntil() <= deadline);
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
        failOn(node("Map", false)); pass(600);
        assertEquals("partial facts cannot replenish the Back budget", 1, actions().size());
    }
    @Test public void aDifferentPassingOfferAfterBackDoesNotInheritRecoveryAuthority() {
        begin(); failOn(node("Map", false)); pass(600);
        show(offer("$30.00", "0:35")); pass(2_000);
        assertEquals(0, clicks(decline));
        assertEquals(DecisionLog.Action.PASSES, DecisionLog.recent(app, 1).get(0).action);
        failOn(node("Map", false)); pass(600);
        assertEquals("a later toast has no failed first-step request for the passing offer", 1, actions().size());
    }
    @Test public void confirmationInsteadOfBlankUsesOnlyTheNormalDeclineButton() {
        begin(); failOn(node("Map", false));
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(question(confirm)); pass(600);
        assertEquals(1, clicks(confirm)); assertTrue(actions().isEmpty());
    }
    @Test public void ownedConfirmationAfterRecoveryUsesNormalSettlingAndStillReportsFreshErrors() throws Exception {
        begin(); long deadline = state().confirmationUntil();
        failOn(node("Map", false)); pass(600);
        show(offer("$7.90", remaining())); pass(1_500);
        assertEquals(1, clicks(decline));
        assertEquals(2, state().declineAttempts());
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(question(confirm)); assertEquals(1, clicks(confirm));
        pass(300);
        show(offer("$7.90", remaining())); pass(500);
        assertEquals("the confirmed offer waits for normal settling instead of retrying immediately", 0, clicks(decline));
        assertEquals("the owned question ends error-recovery identity restrictions",
                DecisionLog.Action.CONFIRMATION_TAPPED, DecisionLog.recent(app, 1).get(0).action);
        assertEquals(deadline, state().confirmationUntil());
        assertEquals(1, actions().size());
        toast("error", DASHER, "android.widget.Toast", SystemClock.uptimeMillis(), false);
        show(node("Finding offers", false)); pass(1_500);
        assertEquals("ending Back recovery must retain error evidence for the confirmation request",
                DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
        assertEquals(1, clicks(confirm));
        assertEquals(1, actions().size());
    }
    @Test public void errorAfterConfirmationIsUnconfirmedAndIdleDoesNotCorrectItToSuccess() {
        begin(); AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(question(confirm)); assertEquals(1, clicks(confirm));
        toast("error", DASHER, "android.widget.Toast", SystemClock.uptimeMillis(), false);
        show(node("Finding offers", false)); pass(2_000);
        assertTrue(actions().isEmpty());
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
    }
    @Test public void errorAfterConfirmationStaysUnconfirmedThroughAnOfferRereadAndIdle() {
        begin(); AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(question(confirm)); assertEquals(1, clicks(confirm));
        toast("error", DASHER, "android.widget.Toast", SystemClock.uptimeMillis(), false);
        show(offer("$7.90", remaining())); pass(2_000);
        assertEquals(0, clicks(decline));
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
        show(node("Finding offers", false)); pass(2_000);
        assertTrue(actions().isEmpty());
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
    }
    @Test public void confirmationErrorDuringSlowNodeReadCannotBeLostToIdleCompletion() {
        begin(); AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(question(confirm)); assertEquals(1, clicks(confirm));
        ShadowSystemClock.advanceBy(Duration.ofMillis(1_200));
        boolean[] injected = {false};
        OfferFilterService.nodeFetchForTests = () -> {
            OfferFilterService.nodeFetchForTests = null;
            ShadowSystemClock.advanceBy(Duration.ofMillis(200));
            injected[0] = true;
            toastWithoutDraining("error");
        };
        AccessibilityNodeInfo idle = node("", false);
        Shadows.shadowOf(idle).addChild(node("Finding offers", false));
        show(idle); pass(1_500);
        assertTrue(injected[0]);
        assertTrue(actions().isEmpty());
        assertEquals("an in-flight read must see the error before inferring completion",
                DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
    }
    @Test public void confirmationErrorDuringSlowWindowReadCannotBeLostToIdleCompletion() {
        begin(); AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(question(confirm)); assertEquals(1, clicks(confirm));
        ShadowSystemClock.advanceBy(Duration.ofMillis(1_200));
        boolean[] injected = {false};
        service.windowSource = () -> {
            if (!injected[0] && calledFrom("see")) {
                injected[0] = true;
                ShadowSystemClock.advanceBy(Duration.ofMillis(200));
                toastWithoutDraining("error");
            }
            return service.getWindows();
        };
        show(node("Finding offers", false)); pass(1_500);
        assertTrue(injected[0]);
        assertTrue(actions().isEmpty());
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
    }
    @Test public void confirmationErrorRemainsFailureEvidenceAfterTheReadPassesTheOfferDeadline() throws Exception {
        begin(); AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(question(confirm)); assertEquals(1, clicks(confirm));
        long deadline = state().confirmationUntil();
        boolean[] injected = {false};
        OfferFilterService.nodeFetchForTests = () -> {
            OfferFilterService.nodeFetchForTests = null;
            assertTrue("the error arrives while the confirmation request still has authority",
                    SystemClock.uptimeMillis() < deadline);
            toastWithoutDraining("error");
            injected[0] = true;
            // Simulate Android holding the scanner past the deadline after main already delivered the toast.
            ShadowSystemClock.advanceBy(Duration.ofMillis(deadline - SystemClock.uptimeMillis() + 1));
        };
        AccessibilityNodeInfo idle = node("", false);
        Shadows.shadowOf(idle).addChild(node("Finding offers", false));
        show(idle); pass(500);
        assertTrue(injected[0]);
        assertTrue(actions().isEmpty());
        assertEquals("the expired action deadline cannot erase observed failure", 1, clicks(confirm));
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
        assertFalse(state().hasPendingConfirmation(SystemClock.uptimeMillis()));
    }
    @Test public void firstStepErrorDuringAQuestionReadRequiresAFreshReadBeforeConfirmation() {
        begin(); AccessibilityNodeInfo confirm = node("Decline offer", true);
        int[] rootsAtError = {-1};
        java.util.ArrayList<Integer> rootsAtTap = new java.util.ArrayList<>();
        Shadows.shadowOf(confirm).setOnPerformActionListener((action, args) -> {
            rootsAtTap.add(service.rootFetches);
            return true;
        });
        OfferFilterService.nodeFetchForTests = () -> {
            OfferFilterService.nodeFetchForTests = null;
            ShadowSystemClock.advanceBy(Duration.ofMillis(200));
            rootsAtError[0] = service.rootFetches;
            toastWithoutDraining("error");
        };
        show(question(confirm)); pass(500);
        assertTrue("the error must be delivered within the first question read", rootsAtError[0] >= 0);
        assertEquals(1, clicks(confirm));
        assertEquals(1, rootsAtTap.size());
        assertTrue("the interrupted read must not confirm; a later complete read may",
                rootsAtTap.get(0) > rootsAtError[0]);
        assertTrue(actions().isEmpty());
    }
    @Test public void freshCountdownAfterBackBecomesANewOfferWithoutOldRecoveryAuthority() {
        begin(); failOn(node("Map", false)); pass(600);
        show(offer("$7.90", "0:55"));
        assertEquals(1, clicks(decline));
        assertEquals(2, DecisionLog.recent(app, 10).size());
        pass(300); show(node("Map", false)); pass(600);
        assertEquals("new offer needs its own error event", 1, actions().size());
    }
    @Test public void installedServiceSubscribesToToastEvents() throws Exception {
        int resource = app.getResources().getIdentifier("accessibility_service_config", "xml", app.getPackageName());
        assertTrue(resource != 0);
        android.content.res.XmlResourceParser xml = app.getResources().getXml(resource);
        try {
            while (xml.next() != org.xmlpull.v1.XmlPullParser.START_TAG) { }
            int events = xml.getAttributeIntValue("http://schemas.android.com/apk/res/android", "accessibilityEventTypes", 0);
            assertTrue((events & AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) != 0);
        } finally { xml.close(); }
    }
    @Test public void anUnacceptedNoticePreventsToastReadsAndRecovery() {
        begin();
        ConsentedTestApp.forget(app);
        int roots = service.rootFetches;
        toast("error", DASHER, "android.widget.Toast", SystemClock.uptimeMillis(), false);
        pass(600);
        assertEquals(roots, service.rootFetches);
        assertTrue(actions().isEmpty());
    }
    private void begin() { began = SystemClock.uptimeMillis(); show(offer("$7.90", "0:35")); assertEquals(1, clicks(decline)); pass(300); }
    private void touchScreen() {
        ShadowWindowManagerImpl windows = Shadow.extract(service.getSystemService(WindowManager.class));
        java.util.ArrayList<View> watches = new java.util.ArrayList<>();
        for (View view : windows.getViews()) {
            if (!(view instanceof DasherTab) && !(view instanceof DasherGuide)) watches.add(view);
        }
        assertEquals("the pending recovery must retain its touch watch", 1, watches.size());
        long now = SystemClock.uptimeMillis();
        MotionEvent touch = MotionEvent.obtain(now, now, MotionEvent.ACTION_OUTSIDE, 0, 0, 0);
        try { watches.get(0).dispatchTouchEvent(touch); } finally { touch.recycle(); }
        pass(0);
    }
    private void eventDuringFinalMetadata(int eventType) {
        begin(); failOn(node("Map", false));
        boolean[] injected = {false};
        service.windowSource = () -> {
            if (!injected[0] && calledFrom("foregroundDasherForBack")) {
                injected[0] = true;
                AccessibilityEvent changed = AccessibilityEvent.obtain(eventType);
                changed.setPackageName(DASHER);
                changed.setEventTime(SystemClock.uptimeMillis());
                // Production receives this on main while its scanner is blocked in Android. The test scanner
                // shares main, so another thread updates the event counters without recursively scanning here.
                java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
                Thread delivery = new Thread(() -> {
                    try { service.onAccessibilityEvent(changed); }
                    catch (Throwable error) { failure.set(error); }
                }, "recovery-event-delivery");
                delivery.start();
                try { delivery.join(5_000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                assertFalse("event delivery must finish without running the scanner queue", delivery.isAlive());
                if (failure.get() != null) throw new AssertionError(failure.get());
            }
            return service.getWindows();
        };
        pass(700);
        assertTrue("the event must arrive inside the final metadata lookup", injected[0]);
        assertTrue("content and window events invalidate the earlier blank observation", actions().isEmpty());
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
    }
    private static boolean calledFrom(String method) {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if (frame.getClassName().equals(OfferFilterService.class.getName()) && frame.getMethodName().equals(method)) return true;
        }
        return false;
    }
    private void toastWithoutDraining(String text) {
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
        event.setPackageName(DASHER); event.setClassName("android.widget.Toast");
        event.setEventTime(SystemClock.uptimeMillis()); event.getText().add(text);
        service.onAccessibilityEvent(event);
    }
    private void failOn(AccessibilityNodeInfo root) {
        // The toast can precede the stalled frame, so it must not be mistaken for a window transition.
        toast("Error.", DASHER, "android.widget.Toast", SystemClock.uptimeMillis(), false);
        show(root);
    }
    private String remaining() { return String.format(java.util.Locale.US, "0:%02d", Math.max(1, 35 - (SystemClock.uptimeMillis() - began) / 1000)); }
    private void toast(String text, String pkg, String clazz, long at, boolean notification) {
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
        event.setPackageName(pkg); event.setClassName(clazz); event.setEventTime(at); event.getText().add(text);
        if (notification) event.setParcelableData(new Notification());
        service.onAccessibilityEvent(event); pass(0);
    }
    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo n = AccessibilityNodeInfo.obtain(new View(app));
        n.setPackageName(DASHER); n.setText(text); n.setVisibleToUser(true); n.setEnabled(true); n.setClickable(clickable);
        if (clickable) { n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK); Shadows.shadowOf(n).setOnPerformActionListener((a, b) -> true); }
        return n;
    }
    private AccessibilityNodeInfo offer(String pay, String countdown) {
        AccessibilityNodeInfo n = node("", false); decline = node("Decline", true);
        for (AccessibilityNodeInfo c : new AccessibilityNodeInfo[]{decline, node(pay, false), node("2 stops (7.2 mi) • 21 min", false), node("Accept", true), node(countdown, false)}) Shadows.shadowOf(n).addChild(c);
        return n;
    }
    private AccessibilityNodeInfo partialOffer(String pay, String countdown) {
        AccessibilityNodeInfo n = node("", false); decline = node("Decline", true);
        for (AccessibilityNodeInfo c : new AccessibilityNodeInfo[]{decline, node(pay, false), node("Accept", true), node(countdown, false)}) Shadows.shadowOf(n).addChild(c);
        return n;
    }
    private AccessibilityNodeInfo question(AccessibilityNodeInfo confirm) { AccessibilityNodeInfo n = node("Are you sure you want to decline this offer?", false); Shadows.shadowOf(n).addChild(confirm); return n; }
    private void show(AccessibilityNodeInfo root) {
        TestWindows.full(service, root);
        ((ShadowAccessibilityWindowInfo) Shadow.extract(service.getWindows().get(0))).setFocused(true);
        AccessibilityEvent e = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED); e.setPackageName(DASHER);
        service.onAccessibilityEvent(e); pass(0);
    }
    private AccessibilityWindowInfo window(AccessibilityNodeInfo root, int id, boolean active, Rect bounds) {
        AccessibilityWindowInfo w = AccessibilityWindowInfo.obtain(); ShadowAccessibilityWindowInfo s = Shadow.extract(w);
        s.setId(id); s.setType(AccessibilityWindowInfo.TYPE_APPLICATION); s.setRoot(root); s.setActive(active); s.setFocused(active); s.setBoundsInScreen(bounds); return w;
    }
    private DeclineState state() throws Exception { java.lang.reflect.Field f = OfferFilterService.class.getDeclaredField("declineState"); f.setAccessible(true); return (DeclineState) f.get(service); }
    private List<Integer> actions() { return Shadows.shadowOf(service).getGlobalActionsPerformed(); }
    private static int clicks(AccessibilityNodeInfo n) { return Shadows.shadowOf(n).getPerformedActions().size(); }
    private static void pass(long ms) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }
}

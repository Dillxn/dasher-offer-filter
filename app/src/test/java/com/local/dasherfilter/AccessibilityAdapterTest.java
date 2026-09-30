package com.local.dasherfilter;

import android.app.Application;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Build;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.time.Duration;
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
import org.robolectric.shadows.ShadowAudioManager;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.shadows.ShadowWindowManagerImpl;
import org.robolectric.shadows.ShadowSystemClock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Drives OfferFilterService with synthetic DoorDash accessibility trees and inspects requested clicks. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,35})
@LooperMode(LooperMode.Mode.PAUSED)
public class AccessibilityAdapterTest {
    private Application app;
    private ServiceController<OfferFilterService> controller;
    // Buttons of the most recent screen built by offer(String).
    private AccessibilityNodeInfo decline;
    private AccessibilityNodeInfo accept;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        DecisionLog.forgetCache();
        ReportOutbox.forgetCache();
        OfferSilencer.forgetCache();
        controller = Robolectric.buildService(OfferFilterService.class).create();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
    }

    @After
    public void stop() {
        controller.destroy();
    }

    /** A visible, enabled DoorDash node; clickable nodes expose ACTION_CLICK and report clicks as handled. */
    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(new View(app));
        node.setPackageName("com.doordash.driverapp");
        node.setText(text);
        node.setVisibleToUser(true);
        node.setEnabled(true);
        node.setClickable(clickable);
        if (clickable) {
            node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            Shadows.shadowOf(node).setOnPerformActionListener((action, args) -> true);
        }
        return node;
    }

    /** An offer screen showing {@code money}, a route line, and fresh Accept/Decline buttons. */
    private AccessibilityNodeInfo offer(String money) {
        AccessibilityNodeInfo root = node("", false);
        accept = node("Accept", true);
        decline = node("Decline", true);
        Shadows.shadowOf(root).addChild(node(money, false));
        Shadows.shadowOf(root).addChild(node("2 stops (7.2 mi) • 21 min", false));
        Shadows.shadowOf(root).addChild(accept);
        Shadows.shadowOf(root).addChild(decline);
        return root;
    }

    /** Makes {@code root} the active window and delivers a DoorDash content-changed event. */
    private void show(AccessibilityNodeInfo root) {
        Shadows.shadowOf(controller.get()).setRootInActiveWindow(root);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        event.setPackageName("com.doordash.driverapp");
        controller.get().onAccessibilityEvent(event);
    }

    /** The service's touch-watch overlays currently on screen. */
    private List<View> overlays() {
        ShadowWindowManagerImpl windows = Shadow.extract(controller.get().getSystemService(WindowManager.class));
        return windows.getViews();
    }

    /** A finger landing anywhere on the screen, as Android reports it to a watching overlay. */
    private void touchScreen() {
        assertEquals("one touch watch while declining", 1, overlays().size());
        overlays().get(0).dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_OUTSIDE, 0, 0, 0));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    /** An offer frame Dasher has only partly drawn: the given labels plus Accept and Decline. */
    private AccessibilityNodeInfo partialOffer(String... labels) {
        AccessibilityNodeInfo root = node("", false);
        accept = node("Accept", true);
        decline = node("Decline", true);
        for (String label : labels) Shadows.shadowOf(root).addChild(node(label, false));
        Shadows.shadowOf(root).addChild(accept);
        Shadows.shadowOf(root).addChild(decline);
        return root;
    }

    private AudioManager audio() {
        return app.getSystemService(AudioManager.class);
    }

    /** Players now running, by usage (for example Dasher's offer ring on the alarm stream). */
    private void playing(boolean notify, int... usages) {
        List<AudioAttributes> players = new java.util.ArrayList<>();
        for (int usage : usages) players.add(new AudioAttributes.Builder().setUsage(usage).build());
        ShadowAudioManager shadow = Shadows.shadowOf(audio());
        shadow.setActivePlaybackConfigurationsFor(players, notify);
    }

    private int alarmFloor() {
        return Build.VERSION.SDK_INT >= 28 ? audio().getStreamMinVolume(AudioManager.STREAM_ALARM) : 0;
    }

    /** Taps the declined offer's confirmation, then lets Dasher return to its idle screen. */
    private void finishDecline() {
        show(confirmation(node("Decline offer", true)));
        ShadowSystemClock.advanceBy(Duration.ofMillis(1200));
        show(node("Finding offers", false));
    }

    /** A decline-confirmation dialog containing {@code button}. */
    private AccessibilityNodeInfo confirmation(AccessibilityNodeInfo button) {
        AccessibilityNodeInfo root = node("Are you sure you want to decline this offer?", false);
        Shadows.shadowOf(root).addChild(button);
        return root;
    }

    @Test
    public void failingVisibleOfferRequestsDeclineImmediatelyButNeverAccepts() {
        AccessibilityNodeInfo root = offer("$7.90");
        show(root);
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
        assertTrue(Shadows.shadowOf(accept).getPerformedActions().isEmpty());

        // An immediate repeat event does not click Decline again, and nothing is launched.
        show(root);
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
        assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }

    @Test
    public void unreadableOfferIsReportedOnceAndNeverDeclined() {
        ReportOutbox.setToken(app, "github_pat_test");
        AccessibilityNodeInfo root = offer("Guaranteed pay");
        show(root);
        show(root);
        ReportOutbox.flush();

        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        assertEquals(1, ReportOutbox.queued(app));
        assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }

    @Test
    public void nothingIsReportedWithoutAToken() {
        show(offer("Guaranteed pay"));
        show(offer("$7.90"));
        ReportOutbox.flush();

        assertEquals(0, ReportOutbox.queued(app));
    }

    @Test
    public void readableOffersFileNoReport() {
        ReportOutbox.setToken(app, "github_pat_test");
        show(offer("$7.90"));
        show(offer("$25.00"));
        ReportOutbox.flush();

        assertEquals(0, ReportOutbox.queued(app));
    }

    // ---- Touching the screen hands the offer back ----

    @Test
    public void touchingTheScreenDuringADeclineStopsItsConfirmation() {
        AccessibilityNodeInfo declined = offer("$7.90");
        show(declined);
        touchScreen();

        assertTrue("the watch goes once the user has taken over", overlays().isEmpty());
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(confirmation(confirm));
        assertTrue(Shadows.shadowOf(confirm).getPerformedActions().isEmpty());
        assertEquals("Offer Filter stopped tapping this offer", ShadowToast.getTextOfLatestToast());
        assertEquals(DecisionLog.Action.USER_TOOK_OVER, DecisionLog.recent(app, 1).get(0).action);
    }

    @Test
    public void anOfferTheUserTookOverIsNeverDeclinedAgain() {
        AccessibilityNodeInfo declined = offer("$7.90");
        show(declined);
        AccessibilityNodeInfo firstDecline = decline;
        touchScreen();

        // The user backs out of the dialog and the same offer returns, still failing the rules.
        ShadowSystemClock.advanceBy(Duration.ofMillis(500));
        show(offer("$7.90"));
        ShadowSystemClock.advanceBy(Duration.ofMillis(500));
        show(declined);
        assertEquals(1, Shadows.shadowOf(firstDecline).getPerformedActions().size());
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
    }

    @Test
    public void aTakeoverHoldsThroughPartlyDrawnFramesOfThatOffer() {
        show(offer("$7.90"));
        touchScreen();

        // Dasher redraws the offer: first the route line without pay, then pay without the route line.
        ShadowSystemClock.advanceBy(Duration.ofMillis(300));
        show(partialOffer("2 stops (7.2 mi) • 21 min"));
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        ShadowSystemClock.advanceBy(Duration.ofMillis(300));
        show(partialOffer("$7.90"));
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(confirmation(confirm));
        assertTrue(Shadows.shadowOf(confirm).getPerformedActions().isEmpty());
        ShadowSystemClock.advanceBy(Duration.ofMillis(300));
        show(offer("$7.90"));
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
    }

    @Test
    public void offersAndDeliveriesMeanDashingUntilDasherSaysTheDashEnded() {
        Dashing.forgetCache();
        controller.get().onServiceConnected();
        assertFalse(Dashing.now(app));
        show(offer("$7.90"));
        assertTrue("an offer on screen means a dash is on", Dashing.now(app));

        show(node("Dash ended", false));
        assertFalse(Dashing.now(app));

        Dashing.forgetCache();
        show(node("Arrived at store", false));
        assertTrue("a delivery screen means a dash is on", Dashing.now(app));
        Dashing.forgetCache();
        show(node("Dash now", false));
        assertFalse("the start screen is not dashing", Dashing.now(app));
    }

    @Test
    public void theNotificationPathLeavesATakenOverOfferAlone() {
        controller.get().onServiceConnected();
        show(offer("$7.90"));
        touchScreen();

        assertTrue(OfferFilterService.userHasOffer(new OfferSnapshot(790, null, null, null)));
        assertFalse(OfferFilterService.userHasOffer(new OfferSnapshot(610, null, null, null)));
    }

    @Test
    public void aDifferentOfferAfterATakeoverIsJudgedAsUsual() {
        show(offer("$7.90"));
        touchScreen();

        show(offer("$6.10"));
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
    }

    @Test
    public void theTouchWatchRunsOnlyWhileADeclineIsInProgress() {
        show(offer("$25.00"));
        assertTrue("nothing to watch on a passing offer", overlays().isEmpty());

        show(offer("$7.90"));
        assertEquals(1, overlays().size());
        finishDecline();
        assertTrue(overlays().isEmpty());
    }

    // ---- Dasher's own ring while declining ----

    @Test
    public void dasherRingIsTurnedDownWhileDecliningAndRestoredAfter() {
        audio().setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        audio().setStreamVolume(AudioManager.STREAM_MUSIC, 8, 0);
        playing(false, AudioAttributes.USAGE_ALARM);

        show(offer("$7.90"));
        assertEquals(alarmFloor(), audio().getStreamVolume(AudioManager.STREAM_ALARM));
        assertFalse("media was not playing, so it is left alone", audio().isStreamMute(AudioManager.STREAM_MUSIC));

        // Navigation alone never turns media down; a ring starting on the media stream does.
        playing(true, AudioAttributes.USAGE_ALARM, AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE);
        assertFalse(audio().isStreamMute(AudioManager.STREAM_MUSIC));
        playing(true, AudioAttributes.USAGE_ALARM, AudioAttributes.USAGE_MEDIA);
        assertTrue(audio().isStreamMute(AudioManager.STREAM_MUSIC));

        finishDecline();
        assertEquals(5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
        assertFalse(audio().isStreamMute(AudioManager.STREAM_MUSIC));
        assertEquals(8, audio().getStreamVolume(AudioManager.STREAM_MUSIC));
    }

    @Test
    public void passingAndUnreadableOffersAreNeverSilenced() {
        audio().setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        playing(false, AudioAttributes.USAGE_ALARM);

        show(offer("$25.00"));
        show(offer("Guaranteed pay"));
        assertEquals(5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
    }

    @Test
    public void touchingTheScreenBringsTheSoundBack() {
        audio().setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        playing(false, AudioAttributes.USAGE_ALARM);
        show(offer("$7.90"));
        assertEquals(alarmFloor(), audio().getStreamVolume(AudioManager.STREAM_ALARM));

        touchScreen();
        assertEquals(5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
    }

    @Test
    public void aPassingOffersBellOutranksADeclineStillInProgress() {
        audio().setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        playing(false, AudioAttributes.USAGE_ALARM);
        show(offer("$7.90"));
        assertEquals(alarmFloor(), audio().getStreamVolume(AudioManager.STREAM_ALARM));

        // A passing offer arrives by notification before the first decline has finished.
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        assertTrue(OfferAlerts.notifyOffer(app, "passing", null, OfferRule.Result.KEEP, "$25.00", true));
        assertEquals(5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
        // Sound starting while the alert plays is not turned down again.
        playing(true, AudioAttributes.USAGE_ALARM);
        assertEquals(5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
    }

    @Test
    public void aNextOfferOnScreenBringsTheSoundBackEvenUnreadable() {
        audio().setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        playing(false, AudioAttributes.USAGE_ALARM);
        show(offer("$7.90"));
        assertEquals(alarmFloor(), audio().getStreamVolume(AudioManager.STREAM_ALARM));

        // The next offer is only half drawn: its own Accept and pay, no Decline yet. Its ring must be heard.
        AccessibilityNodeInfo next = node("", false);
        Shadows.shadowOf(next).addChild(node("$25.00", false));
        Shadows.shadowOf(next).addChild(node("Accept", true));
        show(next);
        assertEquals(5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
    }

    @Test
    public void aCallStartingMidDeclinePutsTheSoundBack() {
        audio().setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        playing(false, AudioAttributes.USAGE_ALARM);
        show(offer("$7.90"));
        assertEquals(alarmFloor(), audio().getStreamVolume(AudioManager.STREAM_ALARM));

        audio().setMode(AudioManager.MODE_RINGTONE);
        playing(true, AudioAttributes.USAGE_ALARM, AudioAttributes.USAGE_NOTIFICATION_RINGTONE);
        assertEquals(5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
    }

    @Test
    public void ringerNotificationAndSystemStreamsAreNeverTouched() {
        audio().setStreamVolume(AudioManager.STREAM_RING, 5, 0);
        audio().setStreamVolume(AudioManager.STREAM_NOTIFICATION, 5, 0);
        playing(false, AudioAttributes.USAGE_NOTIFICATION_RINGTONE, AudioAttributes.USAGE_NOTIFICATION,
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION);

        show(offer("$7.90"));
        assertFalse(audio().isStreamMute(AudioManager.STREAM_RING));
        assertFalse(audio().isStreamMute(AudioManager.STREAM_NOTIFICATION));
        assertFalse(audio().isStreamMute(AudioManager.STREAM_SYSTEM));
    }

    @Test
    public void aRingingCallIsNeverSilenced() {
        audio().setStreamVolume(AudioManager.STREAM_RING, 5, 0);
        audio().setMode(AudioManager.MODE_RINGTONE);
        playing(false, AudioAttributes.USAGE_NOTIFICATION_RINGTONE);

        show(offer("$7.90"));
        assertFalse(audio().isStreamMute(AudioManager.STREAM_RING));
    }

    @Test
    public void silencingCanBeTurnedOff() {
        FilterStore.setSilenceWhileDeclining(app, false);
        audio().setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        playing(false, AudioAttributes.USAGE_ALARM);

        show(offer("$7.90"));
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
        assertEquals(5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
    }

    @Test
    public void soundLeftDownByACrashIsRestoredAtTheNextStart() {
        audio().setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        playing(false, AudioAttributes.USAGE_ALARM);
        show(offer("$7.90"));
        assertEquals(alarmFloor(), audio().getStreamVolume(AudioManager.STREAM_ALARM));

        // The process dies mid-decline: no stop() runs. The next start puts the sound back.
        Robolectric.buildService(OfferFilterService.class).create();
        assertEquals(5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
    }

    // ---- A decline that does not finish ----

    @Test
    public void aDeclinedOfferStillShowingAfterFiveSecondsIsReportedOnce() {
        ReportOutbox.setToken(app, "github_pat_test");
        AccessibilityNodeInfo stuck = offer("$7.90");
        for (int i = 0; i < DeclineState.MAX_ATTEMPTS; i++) {
            show(stuck);
            ShadowSystemClock.advanceBy(Duration.ofMillis(300));
        }
        show(stuck);
        ReportOutbox.flush();
        assertEquals("not yet: Dasher may still be closing it", 0, ReportOutbox.queued(app));

        ShadowSystemClock.advanceBy(Duration.ofMillis(OfferFilterService.STUCK_MS));
        show(stuck);
        show(stuck);
        ReportOutbox.flush();
        assertEquals(1, ReportOutbox.queued(app));
        assertEquals(DeclineState.MAX_ATTEMPTS, Shadows.shadowOf(decline).getPerformedActions().size());
    }

    @Test
    public void pauseRevokesPendingConfirmation() {
        show(offer("$7.90"));
        FilterStore.save(app, new FilterSettings(false, 2000, 0, 0, 0, 0));

        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(confirmation(confirm));
        assertTrue(Shadows.shadowOf(confirm).getPerformedActions().isEmpty());
    }

    @Test
    public void passingScreenRevokesOldConfirmationAuthority() {
        show(offer("$7.90"));
        show(offer("$25.00"));

        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(confirmation(confirm));
        assertTrue(Shadows.shadowOf(confirm).getPerformedActions().isEmpty());
    }

    @Test
    public void declinedOfferConfirmationIsTapped() {
        show(offer("$7.90"));
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());

        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(confirmation(confirm));
        assertEquals(1, Shadows.shadowOf(confirm).getPerformedActions().size());
    }

    @Test
    public void declineAndItsConfirmationAreRecordedAsOneDecision() {
        show(offer("$7.90"));
        show(confirmation(node("Decline offer", true)));

        List<DecisionLog.Entry> recent = DecisionLog.recent(app, 10);
        assertEquals(1, recent.size());
        DecisionLog.Entry entry = recent.get(0);
        assertEquals(OfferRule.Result.DECLINE, entry.result);
        assertEquals(Integer.valueOf(790), entry.facts.payCents);
        assertEquals(2000, entry.requiredCents);
        assertEquals(DecisionLog.Action.CONFIRMATION_TAPPED, entry.action);
        assertTrue(entry.evidence.contains("$7.90"));
    }

    @Test
    public void pausedOfferIsRecordedWithoutAction() {
        FilterStore.save(app, new FilterSettings(false, 2000, 0, 0, 0, 0));
        AccessibilityNodeInfo root = offer("$7.90");
        show(root);
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        assertEquals(DecisionLog.Action.PAUSED, DecisionLog.recent(app, 1).get(0).action);
    }

    @Test
    public void confirmationDialogOverlayingTheDeclinedOfferIsTapped() {
        AccessibilityNodeInfo declined = offer("$7.90");
        show(declined);

        // The sheet is drawn over the original offer, so both sets of labels share one tree.
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        Shadows.shadowOf(declined).addChild(node("Declining this offer may lower your acceptance rate", false));
        Shadows.shadowOf(declined).addChild(node("Go back", true));
        Shadows.shadowOf(declined).addChild(confirm);
        show(declined);
        assertEquals(1, Shadows.shadowOf(confirm).getPerformedActions().size());
    }

    @Test
    public void confirmationAuthorityNeverTapsAPartlyDrawnNextOffer() {
        show(offer("$7.90"));

        // The next offer's Decline is drawn before its pay and Accept button: it is not a confirmation dialog.
        AccessibilityNodeInfo next = node("", false);
        AccessibilityNodeInfo nextDecline = node("Decline", true);
        Shadows.shadowOf(next).addChild(nextDecline);
        show(next);
        assertTrue(Shadows.shadowOf(nextDecline).getPerformedActions().isEmpty());
    }

    @Test
    public void confirmationAuthorityNeverTapsADifferentOfferThatLooksLikeADialog() {
        show(offer("$7.90"));

        // A new, passing offer with a Back button and Decline, whose Accept is not drawn yet.
        AccessibilityNodeInfo next = node("", false);
        AccessibilityNodeInfo nextDecline = node("Decline", true);
        Shadows.shadowOf(next).addChild(node("Back", true));
        Shadows.shadowOf(next).addChild(node("$25.00", false));
        Shadows.shadowOf(next).addChild(node("3 stops (9.1 mi) • 30 min", false));
        Shadows.shadowOf(next).addChild(nextDecline);
        show(next);
        assertTrue(Shadows.shadowOf(nextDecline).getPerformedActions().isEmpty());
    }

    @Test
    public void acceptingAStandaloneOfferRecordsItsPayoutAndBestRates() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0, true, 0));
        show(offer("$25.00"));
        AccessibilityEvent tap = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
        tap.setPackageName("com.doordash.driverapp");
        tap.getText().add("Accept");
        controller.get().onAccessibilityEvent(tap);
        show(node("Arrived at store", false));

        FilterSettings saved = FilterStore.load(app);
        assertEquals(2500, saved.lastAcceptedCents);
        // $25.00 for 21 min, 7.2 mi and 2 stops.
        assertEquals("$1.19/min, $3.47/mi, $12.50/stop", saved.best.summary());
    }

    /** The user's own tap on a Dasher button, as Android reports it. */
    private void userTaps(String label) {
        AccessibilityEvent tap = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
        tap.setPackageName("com.doordash.driverapp");
        tap.getText().add(label);
        controller.get().onAccessibilityEvent(tap);
    }

    @Test
    public void aManualDeclineTeachesTheClosestMinimumOnceTheNextOfferArrives() {
        // For $14.00 over 7.2 mi, $1.50/mi asks $10.80 and the $7 minimum asks $7: per mile came closest.
        FilterStore.save(app, new FilterSettings(true, 700, 150, 0, 0, 0, true, 0));
        show(offer("$14.00"));
        assertTrue("it passes the rules", Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        userTaps("Decline");
        assertTrue("held until the dash goes on", FilterStore.load(app).declined.isEmpty());

        show(offer("$20.00"));
        DeclinedFloor learned = FilterStore.load(app).declined;
        assertEquals("$1.94/mi", learned.rates.perMileLabel());
        assertEquals("only that rule rises", 0, learned.payCents);

        // An offer like the declined one is now declined; the better one still passes.
        show(offer("$14.00"));
        assertFalse(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        show(offer("$20.00"));
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
    }

    @Test
    public void aDeclineJustBeforeEndingTheDashTeachesNothing() {
        FilterStore.save(app, new FilterSettings(true, 700, 150, 0, 0, 0, true, 0));
        show(offer("$14.00"));
        userTaps("Decline");
        show(node("Dash now", false));
        show(offer("$20.00"));
        assertTrue(FilterStore.load(app).declined.isEmpty());
    }

    @Test
    public void theAppsOwnDeclinesAndDeclinesWhileLearningIsOffTeachNothing() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0, true, 0));
        show(offer("$7.90"));
        assertFalse("the app declines the failing offer", Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        userTaps("Decline");
        show(offer("$25.00"));
        assertTrue(FilterStore.load(app).declined.isEmpty());

        // With the adaptive minimum off, a manual decline of a passing offer is not learned either.
        FilterStore.save(app, new FilterSettings(true, 700, 150, 0, 0, 0, false, 0));
        show(offer("$14.00"));
        userTaps("Decline");
        show(offer("$20.00"));
        assertTrue(FilterStore.load(app).declined.isEmpty());
    }

    @Test
    public void acceptedAddOnUpdatesTheActiveRoute() {
        ActiveRouteStore.save(app, new OfferSnapshot(2500, 10.0, null, 2));
        AccessibilityNodeInfo root = node("", false);
        accept = node("Accept", true);
        decline = node("Decline", true);
        Shadows.shadowOf(root).addChild(node("Add to route", false));
        Shadows.shadowOf(root).addChild(node("+$3.00", false));
        Shadows.shadowOf(root).addChild(node("+1 mi", false));
        Shadows.shadowOf(root).addChild(accept);
        Shadows.shadowOf(root).addChild(decline);
        show(root);
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());

        AccessibilityEvent tap = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
        tap.setPackageName("com.doordash.driverapp");
        tap.getText().add("Accept");
        controller.get().onAccessibilityEvent(tap);
        show(node("Arrived at store", false));

        OfferSnapshot route = ActiveRouteStore.load(app);
        assertEquals(Integer.valueOf(2800), route.payCents);
        assertEquals(11.0, route.miles, 0.001);
    }

    @Test
    public void swallowedConfirmationTapIsRetriedAfterABriefMiss() {
        show(offer("$7.90"));
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(confirmation(confirm));
        assertEquals(1, Shadows.shadowOf(confirm).getPerformedActions().size());

        // One read misses the dialog during a transition; the dialog is still there afterwards.
        ShadowSystemClock.advanceBy(Duration.ofMillis(300));
        show(node("Loading", false));
        ShadowSystemClock.advanceBy(Duration.ofMillis(300));
        show(confirmation(confirm));
        assertEquals(2, Shadows.shadowOf(confirm).getPerformedActions().size());
    }

    @Test
    public void offerScreenWithABackButtonIsStillJudged() {
        AccessibilityNodeInfo root = offer("$7.90");
        Shadows.shadowOf(root).addChild(node("Back", true));
        show(root);
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
    }

    @Test
    public void confirmationAuthorityNeverTapsANextOfferWhosePayIsNotDrawnYet() {
        show(offer("$7.90"));

        // The next offer has Accept, Decline and Back, but its pay is not readable yet.
        AccessibilityNodeInfo next = node("", false);
        AccessibilityNodeInfo nextDecline = node("Decline", true);
        Shadows.shadowOf(next).addChild(node("Back", true));
        Shadows.shadowOf(next).addChild(node("Accept", true));
        Shadows.shadowOf(next).addChild(nextDecline);
        show(next);
        assertTrue(Shadows.shadowOf(nextDecline).getPerformedActions().isEmpty());
    }

    @Test
    public void deliveryScreenEndsConfirmationAuthority() {
        show(offer("$7.90"));
        // Declining an add-on returns straight to the delivery; no confirmation is coming.
        show(node("Confirm pickup", false));
        AccessibilityNodeInfo later = node("Decline", true);
        AccessibilityNodeInfo root = node("", false);
        Shadows.shadowOf(root).addChild(node("Go back", true));
        Shadows.shadowOf(root).addChild(later);
        show(root);
        assertTrue(Shadows.shadowOf(later).getPerformedActions().isEmpty());
    }

    @Test
    public void confirmationAuthorityEndsOnceTheDialogCloses() {
        show(offer("$7.90"));
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(confirmation(confirm));
        assertEquals(1, Shadows.shadowOf(confirm).getPerformedActions().size());

        // The dialog closed; a later dialog within the old 10-second window is not ours to confirm.
        ShadowSystemClock.advanceBy(Duration.ofMillis(1200));
        show(node("Loading", false));
        ShadowSystemClock.advanceBy(Duration.ofMillis(300));
        AccessibilityNodeInfo later = node("Decline offer", true);
        show(confirmation(later));
        assertTrue(Shadows.shadowOf(later).getPerformedActions().isEmpty());
    }

    @Test
    public void malformedMoneyCannotTriggerScreenDecline() {
        show(offer("$7.901"));
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
    }

    @Test
    public void anotherAppCannotBecomeADasherOffer() {
        AccessibilityNodeInfo root = offer("$7.90");
        root.setPackageName("com.example.other");
        show(root);
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
    }

    @Test
    public void sharedAcceptDeclineContainerIsNotAClickTarget() {
        AccessibilityNodeInfo root = node("", false);
        AccessibilityNodeInfo shared = node("", true);
        Shadows.shadowOf(root).addChild(node("$7.90", false));
        Shadows.shadowOf(root).addChild(shared);
        Shadows.shadowOf(shared).addChild(node("Accept", false));
        Shadows.shadowOf(shared).addChild(node("Decline", false));
        show(root);
        assertTrue(Shadows.shadowOf(shared).getPerformedActions().isEmpty());
    }
}

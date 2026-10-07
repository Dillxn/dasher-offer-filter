package com.local.dasherfilter;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.Application;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.shadows.ShadowWindowManagerImpl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Autopilot moves the bar only through the screen reader, at a safe point between offers (finalSpec updateTiming):
 * never before a read of Dasher's own screen since screen reading connected or resumed after a pause, nor while an
 * offer is up or was seen in the last 10 s, a decline or its question is under way (its question held for a touch, its
 * episode, a decline-error recovery included), the user holds an offer they took over (or a restart's record of one
 * stands), an automatic Accept is armed, watched or was requested in the last 2 minutes, a peek is under way, Dasher's
 * notification of an offer is tracked, a read is queued, or a late completion is watched; each of these is also held on
 * its own. A commit that could not be made then is made at the next safe tick. The user's own change (turning Autopilot
 * off) never waits: a decline under way at the old bar is handed back. Through the real screen reader on the main
 * looper with simulated time; synthetic Android, no handset claimed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public final class AutopilotCommitAdapterTest {
    /** The starter minimums: $4 an offer, $1 a mile, $0.25 a minute ($15 an hour). */
    private static final FilterSettings STARTER = FilterSettings.of(true, 400, 100, 25, 0);
    /** 6.6 mi and 27 min ask $6.75 at 100%: per hour asks the most. */
    private static final String ROUTE = "2 stops (6.6 mi) • 27 min";
    private static final Rect SCREEN = new Rect(0, 0, 1080, 2040);
    private static final String DASHER = "com.doordash.driverapp";
    private static final ComponentName DASHER_HOME = new ComponentName(DASHER, DASHER + ".Home");
    private static final String MAPS = "com.google.android.apps.maps";
    private static final ComponentName MAPS_HOME = new ComponentName(MAPS, MAPS + ".MapsActivity");
    /** What a commit from Autopilot's 82% to the learning target, 100%, logs first. */
    private static final String COMMIT_TO_100 = "commit 82% -> 100%";

    private Application app;
    private ServiceController<OfferFilterService> controller;
    private ServiceController<OfferNotificationService> listener;
    private OfferFilterService service;
    private long connectedAt;
    private AccessibilityNodeInfo accept;
    private AccessibilityNodeInfo decline;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        Updater.setEnabled(app, false);
        OfferAlerts.ensureChannel(app);
        DiagnosticLog.setEnabled(app, true);
        DiagnosticLog.clear(app);
        FilterStore.save(app, STARTER);
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        ActiveRouteStore.clear(app);
        RestartSuppression.clear(app);
        AutoAcceptMemory.clear(app);
        OfferSilencer.forgetCache();
        AutopilotStore.clear(app);
        AutopilotRuntime.executorForTests = Runnable::run;
        AutopilotRuntime.forgetCache();
        OfferFilterService.forgetScreenState();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        // Autopilot on, its bar where an earlier commit left it.
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertTrue(FilterStore.commitAutopilotBar(app, FilterSettings.BAR_AT_MINIMUMS, 82));
    }

    @After public void tearDown() {
        AutopilotRuntime.beforeWriteForTests = null;
        if (controller != null) controller.destroy();
        if (listener != null) listener.destroy();
        OfferFilterService.sawDasherBeside(0);
        OfferFilterService.scanLooperForTests = null;
        OfferFilterService.forgetScreenState();
    }

    // ---- An offer up, or seen moments ago ----

    @Test public void noCommitWhileAnOfferIsUpNorInTheTenSecondsAfterItThenAtTheNextSafeTick() {
        connect(null);
        show(waiting());
        pass(5_000);
        // An offer that meets the minimums, left to the user: it is up.
        show(offer("$7.00", "0:35"));
        wantCommit();
        assertEquals("not while an offer is up", 82, bar());
        assertEquals(1, logged(AutopilotText.LOG_DEFERRED));
        pass(5_000);
        // Read once more as it goes.
        show(offer("$7.00", "0:30"));
        show(waiting());
        long gone = now();
        pass(5_000);
        // Asked again 5 s later (the user changed a minimum, say): still too soon after the offer.
        wantCommit();
        assertEquals("not within 10 s of an offer", 82, bar());
        assertEquals("deferrals are logged at most once a minute", 1, logged(AutopilotText.LOG_DEFERRED));
        assertCommitsAtTheFirstTickFrom(gone + OfferFilterService.BAR_CHANGE_QUIET_MS);
        DecisionLog.Entry line = line(700);
        assertEquals("decided at the bar it was shown at", 82, line.barPercent);
    }

    // ---- Nothing of Dasher's screen read yet ----

    /**
     * Screen reading connects (again) while Dasher already shows an offer that a read before may have decided, with a
     * commit due (the user chose another goal meanwhile): nothing of Dasher's screen is known to the new connection, so
     * the bar stays until a read says what is up. The offer is judged at the bar it was shown under, and the commit
     * lands at the first safe tick after it.
     */
    @Test public void noCommitAtAConnectionBeforeDashersScreenIsReadEvenWithAnOfferUp() {
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TIER);
        idle();
        assertEquals("GOAL_CHANGED", AutopilotStore.jump(app));
        assertEquals(82, bar());
        // $5.75 is 85% of the $6.75 the minimums ask: it passes at 82% and fails at exactly the minimums.
        AccessibilityNodeInfo shown = offer("$5.75", "0:35");
        List<Long> declines = taps(decline, true);
        connect(shown);
        assertEquals("nothing of Dasher's read: no commit", 82, bar());
        assertEquals("the commit the connection's plan asked for waits", 1, logged(AutopilotText.LOG_DEFERRED));
        assertEquals(0, service.contentReads);
        // Dasher's countdown ticks: the offer's first read, at the bar it was shown under.
        shown.getChild(5).setText("0:34");
        tick();
        pass(500);
        assertTrue(service.contentReads > 0);
        assertEquals(82, bar());
        assertTrue("left to the user, as shown", declines.isEmpty());
        DecisionLog.Entry line = line(575);
        assertEquals(OfferRule.Result.KEEP, line.result);
        assertEquals(82, line.barPercent);
        pass(3_000);
        show(waiting());
        long gone = now();
        long tick = tickAtOrAfter(gone + OfferFilterService.BAR_CHANGE_QUIET_MS);
        passUntil(tick - 1);
        assertEquals("nothing before Autopilot's next safe tick", 82, bar());
        passUntil(tick + 1);
        assertEquals("committed at the next safe tick", 100, bar());
        assertTrue(autopilotLog().toString(), firstCommit().startsWith(COMMIT_TO_100 + " (you changed your goal)"));
        assertTrue("never declined", declines.isEmpty());
    }

    @Test public void noCommitWhileOnlyAnotherAppWasSeenSinceTheConnectionNorWhilePaused() {
        // Screen reading connects with the map in front: nothing of Dasher's is read, so an offer may be up there.
        connect(app(MAPS));
        pass(15_000);
        wantCommit();
        assertEquals("nothing of Dasher's screen known: no commit", 82, bar());
        pass(180_000);
        assertEquals(82, bar());
        // The user goes back to Dasher, waiting for offers.
        show(waiting());
        assertCommitsAtTheFirstTickFrom(now());

        // Paused: nothing of Dasher's is read, so an offer may come up unseen; the bar waits for the first read after.
        assertTrue(FilterStore.commitAutopilotBar(app, FilterSettings.BAR_AT_MINIMUMS, 82));
        DiagnosticLog.clear(app);
        FilterStore.save(app, FilterSettings.of(false, 400, 100, 25, 0));
        OfferFilterService.requestCheckForRules();
        pass(1_000);
        assertTrue(log(), log().contains("[screen] not reading Dasher while paused (auto-decline is off)"));
        pass(15_000);
        wantCommit();
        assertEquals("not while paused", 82, bar());
        pass(180_000);
        assertEquals(82, bar());
        assertEquals(0, commits());
        // Resumed: read at once, the wait for offers still up.
        FilterStore.save(app, STARTER);
        OfferFilterService.requestCheckForRules();
        pass(1_000);
        assertTrue(log(), log().contains("[screen] reading Dasher again"));
        assertCommitsAtTheFirstTickFrom(now());
    }

    // ---- A decline and its question ----

    @Test public void noCommitWhileADeclineOrItsQuestionIsUnderWayThenAtTheNextSafeTick() {
        connect(null);
        show(waiting());
        pass(15_000);
        // $5.00 is 74% of the $6.75 the minimums ask: it fails the 82% bar and is declined at once.
        AccessibilityNodeInfo failing = offer("$5.00", "0:35");
        List<Long> declines = taps(decline, true);
        show(failing);
        assertEquals(1, declines.size());
        wantCommit();
        assertEquals("not while its Decline waits for Dasher's question", 82, bar());
        pass(300);
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirms = taps(confirm, true);
        // Its question shows Dasher's acceptance rate: the reading asks for a plan, whose commit waits too.
        show(question(confirm, "Your acceptance rate", "40%"));
        assertEquals(1, confirms.size());
        assertEquals("not while Dasher closes its question", 82, bar());
        pass(400);
        show(waiting());
        long gone = now();
        assertCommitsAtTheFirstTickFrom(gone + OfferFilterService.BAR_CHANGE_QUIET_MS);
        DecisionLog.Entry line = line(500);
        assertEquals("the decline went through as decided", DecisionLog.Action.CONFIRMATION_TAPPED, line.action);
        assertEquals(82, line.barPercent);
        assertNoHandBackOrStaleTap();
    }

    /**
     * Dasher answers the app's Decline with "Something went wrong. Please try again." over a screen showing only its
     * map: the app goes Back to the offer and declines it again (the approved recovery). The bar stays through all of
     * it, and moves at the first safe tick after the offer is over.
     */
    @Test public void noCommitDuringADeclineErrorRecoveryThenAtTheNextSafeTick() {
        connect(null);
        focused(waiting());
        pass(15_000);
        long shownAt = now();
        AccessibilityNodeInfo failing = offer("$5.00", "0:35");
        List<Long> declines = taps(decline, true);
        focused(failing);
        assertEquals("declined at once, at the 82% bar", 1, declines.size());
        pass(300);
        errorToast();
        focused(node("Map", false));
        wantCommit();
        assertEquals("not while Dasher's error is recovered from", 82, bar());
        pass(600);
        assertEquals("the recovery's one Back", Collections.singletonList(AccessibilityService.GLOBAL_ACTION_BACK),
                Shadows.shadowOf(service).getGlobalActionsPerformed());
        wantCommit();
        assertEquals("not while the recovery waits for the offer again", 82, bar());
        // Back on the offer, its countdown going on: declined again, and its question confirmed.
        AccessibilityNodeInfo shownAgain = offer("$5.00", remaining(shownAt));
        List<Long> again = taps(decline, true);
        focused(shownAgain);
        pass(1_500);
        assertEquals("declined once more after the recovery", 1, again.size());
        assertEquals(82, bar());
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirms = taps(confirm, true);
        focused(question(confirm));
        assertEquals(1, confirms.size());
        assertEquals(82, bar());
        pass(400);
        focused(waiting());
        long gone = now();
        assertCommitsAtTheFirstTickFrom(gone + OfferFilterService.BAR_CHANGE_QUIET_MS);
        assertEquals("decided at the bar it was shown at", 82, line(500).barPercent);
        assertNoHandBackOrStaleTap();
    }

    /**
     * In split screen beside Offer Filter, a finger lands while the app declines: until Offer Filter's half reports it,
     * the touch may be the user's on Dasher's half, so Dasher's question is held, untapped. The bar stays while it is
     * held; the touch was on Offer Filter's half, the question is tapped, and the bar moves at the first safe tick
     * after the offer is over.
     */
    @Test public void noCommitWhileDashersQuestionIsHeldForATouchInSplitScreen() {
        connect(null);
        split(waiting());
        pass(15_000);
        AccessibilityNodeInfo failing = offer("$5.00", "0:35");
        List<Long> declines = taps(decline, true);
        split(failing);
        assertEquals("declined at once, in Dasher's half", 1, declines.size());
        pass(300);
        long at = now();
        watchHears(at);
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirms = taps(confirm, true);
        split(question(confirm));
        assertTrue("held while the touch is judged", confirms.isEmpty());
        assertTrue(log(), log().contains("held while a touch in split screen is judged"));
        wantCommit();
        assertEquals("not while the question is held", 82, bar());
        // Offer Filter's half reports the same finger: the decline goes on.
        OfferFilterService.ownScreenTouched(at);
        idle();
        assertEquals("tapped once the touch is found on Offer Filter's half", 1, confirms.size());
        assertEquals(82, bar());
        pass(400);
        split(waiting());
        long gone = now();
        assertCommitsAtTheFirstTickFrom(gone + OfferFilterService.BAR_CHANGE_QUIET_MS);
        assertEquals(DecisionLog.Action.CONFIRMATION_TAPPED, line(500).action);
        assertNoHandBackOrStaleTap();
    }

    // ---- The user's offer ----

    @Test public void noCommitWhileTheUserHoldsAnOfferTheyTookOver() {
        connect(null);
        show(waiting());
        pass(15_000);
        show(offer("$5.00", "0:35"));
        pass(300);
        // Android refuses the question's Decline; the user taps "View offer details": the offer is theirs.
        AccessibilityNodeInfo confirm = button("Decline offer");
        taps(confirm, false);
        AccessibilityNodeInfo details = button("View offer details");
        show(question(confirm, details));
        pass(100);
        userClicks(details);
        long tookOver = now();
        assertTrue(OfferFilterService.userHasOffer(new OfferSnapshot(500, 6.6, 27, 2)));
        // The user looks at another of Dasher's screens: no offer is up, but the offer is still theirs.
        show(screen("Ratings", "Customer rating", "Acceptance rate"));
        pass(15_000);
        wantCommit();
        assertEquals("not while the user holds the offer", 82, bar());
        pass(60_000);
        assertEquals(82, bar());
        assertTrue(OfferFilterService.userHasOffer(new OfferSnapshot(500, 6.6, 27, 2)));
        // The takeover, and its record for a restart, last 2 minutes.
        assertCommitsAtTheFirstTickFrom(tookOver + OfferFilterService.TAKEOVER_MS);
    }

    @Test public void noCommitWhileTheLastLookAtDasherShowedAnOffer() {
        connect(null);
        show(waiting());
        pass(15_000);
        // An offer the user leaves for their map: Dasher is not read from there, so its offer may still be up.
        show(offer("$7.00", "0:35"));
        showApp(MAPS);
        pass(15_000);
        wantCommit();
        assertEquals("not while Dasher was last seen with an offer", 82, bar());
        pass(120_000);
        assertEquals(82, bar());
        // Back in Dasher: the offer's last moment, then the wait for offers.
        show(offer("$7.00", "0:01"));
        show(waiting());
        assertCommitsAtTheFirstTickFrom(now() + OfferFilterService.BAR_CHANGE_QUIET_MS);
    }

    @Test public void noCommitWhileARestartsRecordOfATakeoverStands() {
        // A restart came while the user held an offer: its record stands for 2 minutes.
        assertTrue(RestartSuppression.remember(app, new OfferSnapshot(500, 6.6, 27, 2), 30));
        long saved = now();
        connect(app(MAPS));
        // The user looks at one of Dasher's own screens, no offer on it (nor the wait for offers, which would end it).
        show(screen("Ratings", "Customer rating", "Acceptance rate"));
        pass(15_000);
        wantCommit();
        assertEquals("not while the record stands", 82, bar());
        assertCommitsAtTheFirstTickFrom(saved + RestartSuppression.MAX_MS);
    }

    @Test public void noCommitWhileATakeoverHoldsEvenWithoutItsRestartRecord() {
        connect(null);
        show(waiting());
        pass(15_000);
        show(offer("$5.00", "0:35"));
        pass(300);
        AccessibilityNodeInfo confirm = button("Decline offer");
        taps(confirm, false);
        AccessibilityNodeInfo details = button("View offer details");
        show(question(confirm, details));
        pass(100);
        userClicks(details);
        long tookOver = now();
        assertTrue(OfferFilterService.userHasOffer(new OfferSnapshot(500, 6.6, 27, 2)));
        // Only the takeover itself holds now: its record for a restart is gone (as if it could not be kept).
        RestartSuppression.clear(app);
        show(screen("Ratings", "Customer rating", "Acceptance rate"));
        pass(15_000);
        wantCommit();
        assertEquals("not while the user holds the offer", 82, bar());
        assertCommitsAtTheFirstTickFrom(tookOver + OfferFilterService.TAKEOVER_MS);
    }

    // ---- An automatic acceptance ----

    @Test public void noCommitForTwoMinutesAfterAnAutomaticAcceptRequest() {
        FilterStore.setAutoAcceptEnabled(app, true);
        connect(null);
        show(waiting());
        pass(15_000);
        // $7.00 is 103% of the minimums: an automatic Accept takes it.
        AccessibilityNodeInfo passing = offer("$7.00", "0:35");
        List<Long> accepts = taps(accept, true);
        show(passing);
        wantCommit();
        assertEquals("not while it waits to be accepted", 82, bar());
        pass(1_200);
        assertEquals("accepted automatically", 1, accepts.size());
        long requested = accepts.get(0);
        // Dasher reports the click of the app's own tap, then its delivery screen.
        userClicks(accept);
        show(delivery());
        pass(20_000);
        // No offer for 20 s, but the request was 21 s ago.
        wantCommit();
        assertEquals("not within 2 minutes of an automatic Accept request", 82, bar());
        assertCommitsAtTheFirstTickFrom(requested + AutoAccept.SUPPRESS_MS + 1);
        assertTrue(DecisionLog.report(app, 5) + "\n" + log(),
                DecisionLog.hasStep(line(700), DecisionLog.StepKind.ACCEPTED_AUTOMATIC));
        assertFalse(log().contains("Accept NOT_SENT"));
    }

    // ---- Dasher's notification, and a peek ----

    @Test public void noCommitWhileDashersNotificationOfAnOfferIsTracked() {
        listen();
        connect(null);
        show(waiting());
        // The user goes to their map: Dasher was last seen waiting for offers.
        showApp(MAPS);
        pass(15_000);
        StatusBarNotification source = withFigures("$7.00");
        listener.get().onNotificationPosted(source, null);
        idle();
        assertTrue(OfferNotificationService.hasActiveOffer());
        wantCommit();
        assertEquals("not while its notification is tracked", 82, bar());
        pass(30_000);
        assertEquals(82, bar());
        // Dasher takes its notification down: the offer is gone.
        listener.get().onNotificationRemoved(source, null, NotificationListenerService.REASON_APP_CANCEL);
        idle();
        assertFalse(OfferNotificationService.hasActiveOffer());
        assertCommitsAtTheFirstTickFrom(now());
    }

    @Test public void noCommitWhileAPeekIsUnderWay() {
        installed(DASHER_HOME);
        installed(MAPS_HOME);
        listen();
        connect(app(MAPS));
        pass(15_000);
        // Dasher's background notification names the store only: Peek brings Dasher up to read the offer.
        StatusBarNotification source = payless("Taco Bell");
        listener.get().onNotificationPosted(source, null);
        idle();
        pass(Peek.QUIET_MS);
        Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
        assertNotNull("Dasher is brought up", opened);
        assertEquals(DASHER_HOME, opened.getComponent());
        dasherShows(node("Starting…", false));
        wantCommit();
        assertEquals("not while the peek is under way", 82, bar());
        pass(10_000);
        assertEquals(82, bar());
        // The peek's deadline passes, and Dasher's notification goes.
        pass(12_000);
        assertTrue(log(), log().contains("[peek] ended because 20 s passed"));
        listener.get().onNotificationRemoved(source, null, NotificationListenerService.REASON_APP_CANCEL);
        idle();
        assertCommitsAtTheFirstTickFrom(now());
    }

    @Test public void noCommitWhileAPeekIsUnderWayAfterItsOffersNotificationWentAway() throws Exception {
        installed(DASHER_HOME);
        installed(MAPS_HOME);
        listen();
        connect(app(MAPS));
        pass(15_000);
        StatusBarNotification source = payless("Taco Bell");
        listener.get().onNotificationPosted(source, null);
        idle();
        pass(Peek.QUIET_MS);
        Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
        assertNotNull("Dasher is brought up", opened);
        dasherShows(node("Starting…", false));
        // Dasher takes its notification down while the peek still waits for the offer to be drawn: the notification
        // no longer holds the bar, and nothing of an offer was read. Only the peek does.
        listener.get().onNotificationRemoved(source, null, NotificationListenerService.REASON_APP_CANCEL);
        idle();
        pass(2_000);
        assertFalse(OfferNotificationService.hasActiveOffer());
        assertTrue("the peek is still under way", peekActive());
        wantCommit();
        assertEquals("not while the peek is under way", 82, bar());
        assertEquals(1, logged(AutopilotText.LOG_DEFERRED));
        long until = now() + 30_000;
        while (peekActive() && now() < until) {
            pass(250);
            if (peekActive()) assertEquals("not while the peek is under way", 82, bar());
        }
        assertFalse("the peek ended", peekActive());
        assertCommitsAtTheFirstTickFrom(now());
    }

    private boolean peekActive() throws Exception {
        Field field = OfferFilterService.class.getDeclaredField("peek");
        field.setAccessible(true);
        return ((Peek) field.get(service)).active();
    }

    // ---- The scanner's own work ----

    @Test public void noCommitWhileAReadIsQueuedARecheckIsDueOrALateCompletionIsWatched() throws Exception {
        connect(null);
        show(waiting());
        pass(15_000);
        String[] states = {"queued", "recheckPending", "lateCompletionUntil"};
        for (String state : states) {
            hold(state, true);
            wantCommit();
            assertEquals(state, 82, bar());
            pass(30_000);
            assertEquals(state, 82, bar());
            hold(state, false);
            assertCommitsAtTheFirstTickFrom(now());
            // Back where an earlier commit left it, for the next state.
            assertTrue(FilterStore.commitAutopilotBar(app, FilterSettings.BAR_AT_MINIMUMS, 82));
            DiagnosticLog.clear(app);
            pass(61_000);
        }
    }

    /**
     * Each state the commit waits for, held on its own with nothing else under way (Dasher read showing the wait for
     * offers, nothing of an offer seen for 15 s): a question held for the touch watch or a split-screen touch, a decline
     * episode (whose restart record would otherwise hold the bar too), a decline-error recovery, an automatic acceptance
     * armed (with no offer up any more) or watched. Each keeps the bar where it is across Autopilot's ticks, and the
     * commit lands at the first tick after it ends.
     */
    @Test public void noCommitWhileAnyOneDeclineOrAcceptanceStateHoldsOnItsOwn() throws Exception {
        connect(null);
        show(waiting());
        pass(15_000);
        String[] states = {"heldConfirmation", "episode", "declineError", "autoAccept", "autoAcceptWatched"};
        for (String state : states) {
            hold(state, true);
            wantCommit();
            assertEquals(state, 82, bar());
            assertEquals(state, 1, logged(AutopilotText.LOG_DEFERRED));
            pass(90_000);
            assertEquals(state, 82, bar());
            hold(state, false);
            assertCommitsAtTheFirstTickFrom(now());
            // Back where an earlier commit left it, for the next state.
            assertTrue(FilterStore.commitAutopilotBar(app, FilterSettings.BAR_AT_MINIMUMS, 82));
            DiagnosticLog.clear(app);
            pass(61_000);
        }
    }

    /**
     * Puts the scanner in {@code state}, or takes it out: a read queued, a recheck due, a late completion watched; a
     * question held, a decline episode, a decline-error recovery, an armed automatic acceptance, one watched.
     */
    private void hold(String state, boolean on) throws Exception {
        Field field = OfferFilterService.class.getDeclaredField(state);
        field.setAccessible(true);
        OfferSnapshot offer = new OfferSnapshot(500, 6.6, 27, 2);
        switch (state) {
            case "queued":
                ((AtomicInteger) field.get(service)).set(on ? 1 : 0);
                break;
            case "recheckPending":
            case "autoAcceptWatched":
                field.setBoolean(service, on);
                break;
            case "heldConfirmation": {
                // Dasher's question as a read found it, held until the touch watch is up or a touch is judged.
                java.lang.reflect.Constructor<?> scan =
                        Class.forName(OfferFilterService.class.getName() + "$Scan").getDeclaredConstructor();
                scan.setAccessible(true);
                field.set(service, on ? scan.newInstance() : null);
                break;
            }
            case "episode": {
                DeclineEpisode episode = (DeclineEpisode) field.get(service);
                if (on) {
                    episode.declined("held offer", offer, now());
                    assertTrue(episode.active(now()));
                } else {
                    episode.end();
                }
                break;
            }
            case "declineError": {
                DeclineErrorRecovery recovery = (DeclineErrorRecovery) field.get(service);
                if (on) {
                    Object request = new Object();
                    recovery.requested(request, now(), 1);
                    assertTrue(recovery.error(request, now(), now()));
                    assertTrue(recovery.pending());
                } else {
                    recovery.end();
                }
                break;
            }
            case "autoAccept": {
                AutoAccept accept = (AutoAccept) field.get(service);
                if (on) {
                    assertEquals(AutoAccept.State.WAIT, accept.observe(new OfferSnapshot(700, 6.6, 27, 2),
                            "held offer", FilterStore.load(app), 30, OfferNotificationService.generation(), now(),
                            true));
                    assertNotNull(accept.candidate());
                } else {
                    accept.clear();
                }
                break;
            }
            default:
                field.setLong(service, on ? now() + 60_000 : 0);
        }
    }

    // ---- A dash of 30 offers ----

    @Test public void aThirtyOfferDashCommitsOnlyBetweenOffers() {
        // Autopilot as the user turns it on (aim: top tier): learning, then planning from the offers that came.
        FilterStore.setAutopilot(app, false, FilterSettings.GOAL_TOP_TIER);
        FilterStore.setAutoAcceptEnabled(app, true);
        connect(null);
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        idle();
        boolean[] offerUp = {false};
        long[] offerGone = {Long.MIN_VALUE / 2};
        int[] moves = {0};
        List<String> unsafe = new ArrayList<>();
        AutopilotRuntime.beforeWriteForTests = () -> {
            long at = now();
            moves[0]++;
            if (offerUp[0]) unsafe.add("a commit at " + at + " with an offer up");
            else if (at - offerGone[0] < OfferFilterService.BAR_CHANGE_QUIET_MS) {
                unsafe.add("a commit " + (at - offerGone[0]) + " ms after an offer");
            }
        };
        show(waiting());
        int[] pays = {480, 520, 560, 600, 640, 680, 720, 760, 800, 840};
        long[] gaps = {20_000, 75_000, 40_000, 140_000, 30_000, 65_000, 90_000, 25_000, 150_000, 45_000};
        Set<Integer> seen = new HashSet<>();
        int declined = 0;
        int acceptedAutomatically = 0;
        int leftToTheUser = 0;
        int goalChanges = 0;
        for (int i = 0; i < 30; i++) {
            pass(gaps[i % gaps.length]);
            int cents = pays[i % pays.length] + i / pays.length;
            seen.add(cents);
            int barAtShow = bar();
            offerUp[0] = true;
            AccessibilityNodeInfo shown = offer(money(cents), "0:35");
            List<Long> declines = taps(decline, true);
            List<Long> accepts = taps(accept, true);
            show(shown);
            if (i == 21 || i == 24 || i == 27) {
                // The user chooses another goal while this offer is up (or its decline under way): Autopilot's move
                // to the new goal's bar waits for the offer to be over.
                AutopilotRuntime.setAutopilot(app, true, i == 24 ? FilterSettings.GOAL_TOP_TIER
                        : FilterSettings.GOAL_TIER);
                idle();
                goalChanges++;
            }
            if (!declines.isEmpty()) {
                declined++;
                pass(300);
                AccessibilityNodeInfo confirm = button("Decline offer");
                List<Long> confirms = taps(confirm, true);
                show(question(confirm, "Your acceptance rate", "40%"));
                assertEquals("offer " + i + ": its question confirmed", 1, confirms.size());
                pass(400);
                show(waiting());
                offerUp[0] = false;
                offerGone[0] = now();
            } else {
                pass(1_200);
                if (!accepts.isEmpty()) {
                    acceptedAutomatically++;
                    userClicks(accept);
                    show(delivery());
                    offerUp[0] = false;
                    offerGone[0] = now();
                    pass(5_000);
                    show(waiting());
                } else {
                    // Below the minimums, passed by a lower bar: the user lets it go.
                    leftToTheUser++;
                    pass(3_000);
                    show(waiting());
                    offerUp[0] = false;
                    offerGone[0] = now();
                }
            }
            assertEquals("offer " + i + ": no commit while it was up", barAtShow, bar());
            DecisionLog.Entry line = line(cents);
            assertEquals("offer " + i + ": decided at the bar it was shown at", barAtShow, line.barPercent);
            assertTrue("offer " + i + " (" + money(cents) + " at " + barAtShow + "%): at most one tap of each",
                    declines.size() <= 1 && accepts.size() <= 1);
            assertFalse("offer " + i + ": both declined and accepted", !declines.isEmpty() && !accepts.isEmpty());
        }
        pass(180_000);

        String history = DecisionLog.report(app, 40);
        assertTrue(unsafe.toString(), unsafe.isEmpty());
        assertEquals(3, goalChanges);
        assertTrue("Autopilot is to move the bar between offers in this dash, after each goal change at least ("
                + moves[0] + " moves):\n" + String.join("\n", autopilotLog()), moves[0] >= goalChanges);
        assertTrue("declined " + declined + ", accepted " + acceptedAutomatically + ", left " + leftToTheUser,
                declined > 0 && acceptedAutomatically > 0 && declined + acceptedAutomatically + leftToTheUser == 30);
        assertNoHandBackOrStaleTap();
        String log = log();
        assertFalse("no automatic Accept was blocked", log.contains("Accept NOT_SENT"));
        // One line per offer: none decided twice.
        List<DecisionLog.Entry> lines = new ArrayList<>();
        for (DecisionLog.Entry entry : DecisionLog.recent(app, 100)) {
            if (entry.source == DecisionLog.Source.SCREEN) lines.add(entry);
        }
        assertEquals(history, 30, lines.size());
        Set<Integer> pays30 = new HashSet<>();
        for (DecisionLog.Entry entry : lines) pays30.add(entry.facts.payCents);
        assertEquals(history, seen, pays30);
    }

    // ---- The user's own change never waits ----

    @Test public void turningAutopilotOffMidDeclineStillHandsItBack() {
        connect(null);
        show(waiting());
        pass(15_000);
        AccessibilityNodeInfo failing = offer("$5.00", "0:35");
        List<Long> declines = taps(decline, true);
        show(failing);
        assertEquals("declined at once, at the 82% bar", 1, declines.size());
        pass(300);
        // The user turns Autopilot off: the bar is back at 100% at once, not at Autopilot's next safe point.
        AutopilotRuntime.setAutopilot(app, false, FilterSettings.GOAL_TOP_TIER);
        assertEquals(100, bar());
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirms = taps(confirm, true);
        show(question(confirm));
        pass(500);

        assertTrue("its question is left to the user", confirms.isEmpty());
        assertEquals("never declined again", 1, declines.size());
        String log = log();
        assertTrue(log, log.contains("bar changed from 82% to 100%"));
        DecisionLog.Entry line = line(500);
        assertEquals(DecisionLog.Action.USER_TOOK_OVER, line.action);
        assertEquals("its line keeps the bar it was decided at", 82, line.barPercent);
        // What happened, in the user's words: Autopilot went off, not the minimums.
        assertTrue(FilterStore.lastStatus(app), FilterStore.lastStatus(app).endsWith(
                "Autopilot turned off (bar back to 100%); this earlier decline is left to you."));
        assertEquals("Autopilot turned off (bar back to 100%); automatic taps stopped for this offer",
                ShadowToast.getTextOfLatestToast());
        assertFalse(log, log.contains("Minimums changed"));
        assertEquals("Autopilot itself committed nothing", 0, commits());
    }

    // ---- Assertions ----

    /**
     * Nothing commits before the first of Autopilot's ticks at or after {@code safeFrom} (uptime), when nothing else
     * is under way, and that tick commits the bar from 82% to the learning target, 100%.
     */
    private void assertCommitsAtTheFirstTickFrom(long safeFrom) {
        long tick = tickAtOrAfter(safeFrom);
        passUntil(tick - 1);
        assertEquals("nothing before Autopilot's next safe tick", 82, bar());
        assertEquals(0, commits());
        passUntil(tick + 1);
        assertEquals("committed at the next safe tick", 100, bar());
        assertEquals(1, commits());
        assertTrue(autopilotLog().toString(), firstCommit().startsWith(COMMIT_TO_100));
    }

    /** No decline handed back for a bar that changed, and no tap refused as decided at another bar. */
    private void assertNoHandBackOrStaleTap() {
        String log = log();
        assertFalse(log, log.contains("bar changed"));
        assertFalse(log, log.contains("Autopilot turned off"));
        assertFalse(log, log.contains("while reading; checking the offer again"));
        assertFalse(log, log.contains("Minimums changed"));
    }

    // ---- Autopilot ----

    /** The user changed a minimum, say: Autopilot plans, and wants its next commit to jump to its target. */
    private void wantCommit() {
        AutopilotRuntime.rulesChanged(app);
        idle();
    }

    private int bar() {
        return FilterStore.load(app).minimumScalePercent;
    }

    /** The first of Autopilot's ticks at or after {@code at}: every 60 s from the connection. */
    private long tickAtOrAfter(long at) {
        long ticks = Math.max(1, (at - connectedAt + Autopilot.TICK_MS - 1) / Autopilot.TICK_MS);
        return connectedAt + ticks * Autopilot.TICK_MS;
    }

    private List<String> autopilotLog() {
        List<String> lines = new ArrayList<>();
        for (String line : log().split("\n")) {
            int at = line.indexOf("[autopilot] ");
            if (at >= 0) lines.add(line.substring(at + "[autopilot] ".length()));
        }
        return lines;
    }

    private int logged(String line) {
        int count = 0;
        for (String logged : autopilotLog()) if (logged.equals(line)) count++;
        return count;
    }

    /** The bar's moves Autopilot logged ("commit 100% -> 82% (…)"; never "commit deferred"). */
    private int commits() {
        int count = 0;
        for (String logged : autopilotLog()) {
            if (logged.startsWith("commit ") && logged.contains("% -> ")) count++;
        }
        return count;
    }

    private String firstCommit() {
        for (String logged : autopilotLog()) {
            if (logged.startsWith("commit ") && logged.contains("% -> ")) return logged;
        }
        return "";
    }

    private String log() {
        return DiagnosticLog.read(app);
    }

    // ---- The phone ----

    /** Screen reading connects; {@code front}, when given, is the app in front. */
    private void connect(AccessibilityNodeInfo front) {
        controller = Robolectric.buildService(OfferFilterService.class).create();
        service = controller.get();
        if (front != null) inFront(front);
        service.onServiceConnected();
        connectedAt = now();
        idle();
    }

    private void listen() {
        listener = Robolectric.buildService(OfferNotificationService.class).create();
        listener.get().onListenerConnected();
        idle();
    }

    private void installed(ComponentName activity) {
        ShadowPackageManager packages = Shadows.shadowOf(app.getPackageManager());
        android.content.pm.ActivityInfo declared = packages.addActivityIfNotPresent(activity);
        declared.enabled = true;
        declared.exported = true;
        IntentFilter entry = new IntentFilter(Intent.ACTION_MAIN);
        entry.addCategory(Intent.CATEGORY_LAUNCHER);
        entry.addCategory(Intent.CATEGORY_DEFAULT);
        packages.addIntentFilterForActivity(activity, entry);
    }

    private AccessibilityNodeInfo node(String text, boolean clickable) {
        return node(DASHER, text, clickable);
    }

    private AccessibilityNodeInfo node(String pkg, String text, boolean clickable) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(new View(app));
        node.setPackageName(pkg);
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

    /** A Compose button: a clickable node without text, holding its label. */
    private AccessibilityNodeInfo button(String label) {
        AccessibilityNodeInfo button = node(null, true);
        Shadows.shadowOf(button).addChild(node(label, false));
        return button;
    }

    /** An offer as Dasher draws it: Decline, the pay, the route line, Accept, then its countdown. */
    private AccessibilityNodeInfo offer(String pay, String countdown) {
        AccessibilityNodeInfo root = node(null, false);
        decline = button("Decline");
        accept = button("Accept");
        Shadows.shadowOf(root).addChild(decline);
        Shadows.shadowOf(root).addChild(node(pay, false));
        Shadows.shadowOf(root).addChild(node("incl. tips", false));
        Shadows.shadowOf(root).addChild(node(ROUTE, false));
        Shadows.shadowOf(root).addChild(accept);
        Shadows.shadowOf(root).addChild(node(countdown, false));
        return root;
    }

    /** Dasher's question after a Decline tap, with what else it shows. */
    private AccessibilityNodeInfo question(AccessibilityNodeInfo declineOffer, Object... more) {
        AccessibilityNodeInfo root = node(null, false);
        Shadows.shadowOf(root).addChild(node("Are you sure you want to decline this offer?", false));
        for (Object part : more) {
            if (part instanceof String) Shadows.shadowOf(root).addChild(node((String) part, false));
        }
        Shadows.shadowOf(root).addChild(declineOffer);
        Shadows.shadowOf(root).addChild(node("0:24", false));
        boolean controls = false;
        for (Object part : more) {
            if (part instanceof AccessibilityNodeInfo) {
                Shadows.shadowOf(root).addChild((AccessibilityNodeInfo) part);
                controls = true;
            }
        }
        if (!controls) Shadows.shadowOf(root).addChild(button("View offer details"));
        return root;
    }

    private AccessibilityNodeInfo waiting() {
        return screen("Finding offers");
    }

    /** One of Dasher's screens with these labels. */
    private AccessibilityNodeInfo screen(String... labels) {
        AccessibilityNodeInfo root = node(null, false);
        for (String label : labels) Shadows.shadowOf(root).addChild(node(label, false));
        return root;
    }

    /** The pickup an accepted offer leads to: explicit progress, its "Arrived at store" control. */
    private AccessibilityNodeInfo delivery() {
        return node("Arrived at store", true);
    }

    /** Another app's screen: its root and a label. */
    private AccessibilityNodeInfo app(String pkg) {
        AccessibilityNodeInfo root = node(pkg, null, false);
        Shadows.shadowOf(root).addChild(node(pkg, "Head north on Main St", false));
        return root;
    }

    /** {@code root}'s app fills the screen and is the one in front. */
    private void inFront(AccessibilityNodeInfo root) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        shadow.setRoot(root);
        shadow.setActive(true);
        shadow.setBoundsInScreen(SCREEN);
        Shadows.shadowOf(service).setWindows(Collections.singletonList(window));
        Shadows.shadowOf(service).setRootInActiveWindow(root);
    }

    /** Dasher fills the screen showing {@code root} and says so with a window change. */
    private void show(AccessibilityNodeInfo root) {
        dasherShows(root);
    }

    private void dasherShows(AccessibilityNodeInfo root) {
        inFront(root);
        service.onAccessibilityEvent(event(DASHER, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        idle();
    }

    /** Another app comes to the front. */
    private void showApp(String pkg) {
        inFront(app(pkg));
        service.onAccessibilityEvent(event(pkg, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        idle();
    }

    /** Dasher's content changes (its countdown ticks, say), with no window change. */
    private void tick() {
        service.onAccessibilityEvent(event(DASHER, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
        idle();
    }

    /**
     * Dasher fills the screen showing {@code root}, its window focused as well as active (a decline-error recovery's
     * Back goes only to such a window), and says so with a window change.
     */
    private void focused(AccessibilityNodeInfo root) {
        TestWindows.full(service, root);
        ((ShadowAccessibilityWindowInfo) Shadow.extract(service.getWindows().get(0))).setFocused(true);
        service.onAccessibilityEvent(event(DASHER, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        idle();
    }

    /** Dasher's toast after the app's Decline: "Something went wrong. Please try again." */
    private void errorToast() {
        AccessibilityEvent toast = event(DASHER, AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
        toast.setClassName("android.widget.Toast");
        toast.getText().add("Something went wrong. Please try again.");
        service.onAccessibilityEvent(toast);
        idle();
    }

    /** The countdown of an offer of 35 s shown at {@code shownAt}, as it reads now ("0:31"). */
    private static String remaining(long shownAt) {
        return String.format(Locale.US, "0:%02d", Math.max(1, 35 - (now() - shownAt) / 1000));
    }

    /** Offer Filter's page in the top half, the active one; Dasher showing {@code root} in the bottom half. */
    private void split(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo ours = node("com.local.dasherfilter", null, false);
        Shadows.shadowOf(service).setWindows(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, ours, true, new Rect(0, 0, 1080, 1000)),
                window(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, null, false, new Rect(0, 1000, 1080, 1040)),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, root, false, new Rect(0, 1040, 1080, 2040))));
        Shadows.shadowOf(service).setRootInActiveWindow(ours);
        service.onAccessibilityEvent(event(DASHER, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        idle();
    }

    private static AccessibilityWindowInfo window(int type, AccessibilityNodeInfo root, boolean active, Rect bounds) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setType(type);
        if (root != null) shadow.setRoot(root);
        shadow.setActive(active);
        shadow.setBoundsInScreen(bounds);
        return window;
    }

    /** The touch watch, up while the app declines, hears of a finger landing at {@code at}, somewhere outside it. */
    private void watchHears(long at) {
        ShadowWindowManagerImpl windows = Shadow.extract(app.getSystemService(WindowManager.class));
        List<View> watches = new ArrayList<>();
        for (View view : windows.getViews()) if (view.getClass() == View.class) watches.add(view);
        assertEquals("one touch watch while declining", 1, watches.size());
        watches.get(0).dispatchTouchEvent(MotionEvent.obtain(at, at, MotionEvent.ACTION_OUTSIDE, 0, 0, 0));
        idle();
    }

    /** The user taps {@code control}: Dasher reports the click, stamped now, with its node and no text (Compose). */
    private void userClicks(AccessibilityNodeInfo control) {
        AccessibilityEvent tap = event(DASHER, AccessibilityEvent.TYPE_VIEW_CLICKED);
        ((ShadowAccessibilityRecord) Shadow.extract(tap)).setSourceNode(control);
        service.onAccessibilityEvent(tap);
        idle();
    }

    private static AccessibilityEvent event(String pkg, int type) {
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setPackageName(pkg);
        event.setEventTime(SystemClock.uptimeMillis());
        return event;
    }

    /** Records when (uptime) {@code button} is tapped; Android takes or refuses each tap. */
    private static List<Long> taps(AccessibilityNodeInfo button, boolean taken) {
        List<Long> at = new CopyOnWriteArrayList<>();
        Shadows.shadowOf(button).setOnPerformActionListener((action, args) -> {
            at.add(SystemClock.uptimeMillis());
            return taken;
        });
        return at;
    }

    /** Dasher's notification of a new offer with its pay and route on it. */
    private StatusBarNotification withFigures(String pay) {
        Notification payload = new Notification.Builder(app, "dasher_offers")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("New Delivery!")
                .setContentText("New Order: Go to Store A")
                .setStyle(new Notification.InboxStyle().addLine(pay).addLine(ROUTE))
                .build();
        return new StatusBarNotification(DASHER, DASHER, 3, "NEW_ORDER", 10001, 0, 0, payload,
                android.os.Process.myUserHandle(), System.currentTimeMillis());
    }

    /** Dasher's background notification of a new offer: the store only, no pay, miles or time. */
    private StatusBarNotification payless(String store) {
        Notification payload = new Notification.Builder(app, "dasher_offers")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("New Delivery!")
                .setContentText("New Order: Go to " + store)
                .build();
        return new StatusBarNotification(DASHER, DASHER, 3, "NEW_ORDER", 10001, 0, 0, payload,
                android.os.Process.myUserHandle(), System.currentTimeMillis());
    }

    /** The newest screen line of the offer with this pay. */
    private DecisionLog.Entry line(int payCents) {
        DecisionLog.flush();
        for (DecisionLog.Entry entry : DecisionLog.recent(app, 100)) {
            if (entry.source == DecisionLog.Source.SCREEN && entry.facts.payCents != null
                    && entry.facts.payCents == payCents) {
                return entry;
            }
        }
        throw new AssertionError("no line for pay " + payCents + " in:\n" + DecisionLog.report(app, 40));
    }

    private static String money(int cents) {
        return String.format(Locale.US, "$%d.%02d", cents / 100, cents % 100);
    }

    private static long now() {
        return SystemClock.uptimeMillis();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void pass(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    /** Lets time run until uptime {@code at}, running what falls due on the way. */
    private static void passUntil(long at) {
        long left = at - now();
        if (left > 0) pass(left);
    }
}

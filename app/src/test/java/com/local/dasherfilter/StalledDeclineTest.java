package com.local.dasherfilter;

import android.app.Application;
import android.app.KeyguardManager;
import android.graphics.Rect;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.shadows.ShadowSystemClock;

import static org.junit.Assert.*;

/** Synthetic stalled first-step reads, not a claim about DoorDash's real loading screen or server behavior. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public final class StalledDeclineTest {
    private Application app;
    private ServiceController<OfferFilterService> controller;
    private OfferFilterService service;
    private AccessibilityNodeInfo decline;
    private AccessibilityNodeInfo accept;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        DiagnosticLog.clear(app);
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        OfferSilencer.forgetCache();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        controller = Robolectric.buildService(OfferFilterService.class).create();
        service = controller.get();
        service.onServiceConnected();
        pass(0);
    }

    @After public void teardown() {
        if (controller != null) controller.destroy();
        OfferFilterService.scanLooperForTests = null;
        OfferFilterService.nodeFetchForTests = null;
        OfferFilterService.forgetScreenState();
    }

    @Test public void firstStepRetriesWaitForDasherEvenDuringFrequentContentChanges() {
        AccessibilityNodeInfo offered = offer("$7.90", "0:35");
        List<Long> tapped = taps(decline);
        show(offered);
        for (int i = 0; i < 80; i++) { pass(100); event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED); }
        assertEquals("four requests spread across six seconds, never four in one second", 4, tapped.size());
        for (int i = 1; i < tapped.size(); i++) assertTrue(tapped.toString(), tapped.get(i) - tapped.get(i - 1) >= 2_000);
        assertTrue(DiagnosticLog.read(app).contains("waiting for the question"));
        assertTrue("no Back action on an unrecognized stall", Shadows.shadowOf(service).getGlobalActionsPerformed().isEmpty());
    }

    @Test public void aStillVisibleFailingOfferRetriesWithoutAnotherDasherEvent() {
        AccessibilityNodeInfo offered = offer("$7.90", "0:35");
        List<Long> tapped = taps(decline);
        show(offered);
        // show() also drains the first read's bookkeeping; the retry is timed from the tap, not its return.
        long first = tapped.get(0);
        pass(Math.max(0, first + 1_900 - SystemClock.uptimeMillis()));
        assertEquals(1, tapped.size());
        pass(Math.max(0, first + 2_100 - SystemClock.uptimeMillis()));
        assertEquals(2, tapped.size());
        long gap = tapped.get(1) - first;
        assertTrue("retry never precedes its two-second guard: " + gap, gap >= 2_000);
        assertTrue("the timer retries without a Dasher event: " + gap, gap <= 2_100);
        pass(8_000);
        assertEquals(4, tapped.size());
    }

    @Test public void anUnannouncedQuestionAtSevenSecondsIsFoundWithoutAnotherEvent() {
        show(offer("$7.90", "0:35"));
        pass(7_100);
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        List<Long> confirmed = taps(confirm);
        silentlyShow(question(confirm));
        pass(1_000);
        assertEquals("bounded quiet read finds the late question", 1, confirmed.size());
        assertTrue(DiagnosticLog.read(app).contains("confirmation found"));
    }

    @Test public void aLateQuestionAtTenSecondsStillUsesTheOriginalCountdownAuthority() {
        show(offer("$7.90", "0:35"));
        pass(10_000);
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        List<Long> confirmed = taps(confirm);
        show(question(confirm));
        assertEquals(1, confirmed.size());
    }

    @Test public void userTakeoverCancelsEveryScheduledFirstStepRetry() {
        AccessibilityNodeInfo offered = offer("$7.90", "0:35");
        List<Long> tapped = taps(decline);
        show(offered);
        pass(400);
        AccessibilityEvent click = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
        click.setPackageName("com.doordash.driverapp");
        click.setEventTime(SystemClock.uptimeMillis());
        ((ShadowAccessibilityRecord) Shadow.extract(click)).setSourceNode(accept);
        service.onAccessibilityEvent(click);
        pass(8_000);
        assertEquals(1, tapped.size());
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        List<Long> confirmed = taps(confirm);
        show(question(confirm));
        assertTrue(confirmed.isEmpty());
    }

    @Test public void aLockedPhoneCannotBeRetappedByTheScheduledRetry() {
        AccessibilityNodeInfo offered = offer("$7.90", "0:35");
        List<Long> tapped = taps(decline);
        show(offered);
        Shadows.shadowOf(app.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        Shadows.shadowOf(app.getSystemService(PowerManager.class)).setIsInteractive(false);
        pass(4_000);
        assertEquals(1, tapped.size());
    }

    @Test public void aDeliveryOrUnknownScreenNeverGetsAnAutomaticBackAction() {
        show(offer("$7.90", "0:35"));
        show(node("Loading", false));
        pass(4_000);
        show(node("Complete delivery steps", false));
        pass(4_000);
        assertTrue(Shadows.shadowOf(service).getGlobalActionsPerformed().isEmpty());
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        List<Long> confirmed = taps(confirm);
        show(question(confirm));
        assertTrue("delivery ended the earlier authority", confirmed.isEmpty());
    }

    @Test public void returningFromAnUnreadableSurfaceStillBelongsToTheUser() {
        show(offer("$7.90", "0:35"));
        pass(400);
        show(node("Loading", false));
        pass(400);
        AccessibilityNodeInfo returned = offer("$7.90", "0:34");
        List<Long> tapped = taps(decline);
        show(returned);
        pass(4_000);
        assertTrue("an unknown surface gives no authority to retry an unseen modal", tapped.isEmpty());
    }

    @Test public void aFreshCountdownStartsANewEpisodeWithoutAnEarlierTakeover() {
        show(offer("$7.90", "0:10"));
        pass(5_000);
        AccessibilityNodeInfo reoffer = offer("$7.90", "0:40");
        List<Long> tapped = taps(decline);
        show(reoffer);
        assertEquals("the fresh instance is evaluated and declined promptly", 1, tapped.size());
        assertTrue(DiagnosticLog.read(app).contains("authority ended: new instance (countdown)"));
        pass(10_000);
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        List<Long> confirmed = taps(confirm);
        show(question(confirm));
        assertEquals("fresh instance has its own countdown authority", 1, confirmed.size());
    }

    @Test public void aDifferentPassingOfferRevokesTheOldDeclineAndItsTimer() {
        show(offer("$7.90", "0:35"));
        pass(400);
        AccessibilityNodeInfo next = offer("$27.90", "0:35");
        List<Long> tapped = taps(decline);
        show(next);
        pass(4_000);
        assertTrue(tapped.isEmpty());
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        List<Long> confirmed = taps(confirm);
        show(question(confirm));
        assertTrue(confirmed.isEmpty());
    }

    @Test public void loweringMinimumsCancelsTheOldRetryAndItsLateConfirmation() {
        autopilotBarAt103();
        AccessibilityNodeInfo offered = offer("$20.30", "0:35");
        List<Long> declined = taps(decline);
        show(offered);
        assertEquals(1, declined.size());
        // The user turns Autopilot off mid-decline: the bar drops to exactly the minimums, where $20.30 passes.
        FilterStore.setAutopilot(app, false, FilterSettings.GOAL_TOP_TIER);
        pass(3_000);
        assertEquals("the original 103% request is never retried", 1, declined.size());
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        List<Long> confirmed = taps(confirm);
        show(question(confirm));
        pass(3_000);
        assertTrue("a late dialog cannot use the superseded 103% authority", confirmed.isEmpty());
        assertTrue(DiagnosticLog.read(app).contains("bar changed from 103% to 100%"));
        assertTrue(Shadows.shadowOf(service).getGlobalActionsPerformed().isEmpty());

        AccessibilityNodeInfo next = offer("$7.90", "0:35");
        List<Long> nextDeclines = taps(decline);
        show(next);
        assertEquals("a clearly different offer is judged using the new minimums", 1, nextDeclines.size());
    }

    @Test public void minimumScaleChangingDuringTheFirstReadCannotSendTheOldDecline() {
        autopilotBarAt103();
        AccessibilityNodeInfo offered = offer("$20.30", "0:35");
        List<Long> declined = taps(decline);
        turnAutopilotOffDuringNextRead();
        show(offered);
        pass(3_000);
        assertTrue("fresh saved minimums are checked immediately before tapping", declined.isEmpty());
        assertEquals(DecisionLog.Action.PASSES, DecisionLog.recent(app, 1).get(0).action);
        assertFalse(DiagnosticLog.read(app).contains("first-step Decline REQUESTED"));
    }

    @Test public void minimumScaleChangingDuringAConfirmationReadRevokesThatRequest() {
        autopilotBarAt103();
        show(offer("$20.30", "0:35"));
        pass(300);
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        List<Long> confirmed = taps(confirm);
        turnAutopilotOffDuringNextRead();
        show(question(confirm));
        pass(3_000);
        assertTrue(confirmed.isEmpty());
        assertTrue(DiagnosticLog.read(app).contains("bar changed from 103% to 100%"));
    }

    @Test public void changingMinimumsStopsRetriesOfAnAlreadyRequestedConfirmation() {
        autopilotBarAt103();
        show(offer("$20.30", "0:35"));
        pass(300);
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        List<Long> confirmed = taps(confirm);
        show(question(confirm));
        assertEquals(1, confirmed.size());
        FilterStore.setAutopilot(app, false, FilterSettings.GOAL_TOP_TIER);
        pass(4_000);
        assertEquals("a taken request cannot be recalled, but no further request is sent", 1, confirmed.size());
        assertTrue(DiagnosticLog.read(app).contains("automatic decline stopped after its confirmation was tapped"));
    }

    /**
     * Since 0.5.0 only Autopilot moves the bar, and only between offers; the one change mid-decline is the user's own,
     * turning Autopilot off (back to exactly the minimums). Here Autopilot set 103% before the offer came: the $20.00
     * minimum then asks $20.60, so $20.30 is declined at 103% and passes at 100%.
     */
    private void autopilotBarAt103() {
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertTrue(FilterStore.commitAutopilotBar(app, 100, 103));
        assertEquals(103, FilterStore.load(app).minimumScalePercent);
    }

    private void turnAutopilotOffDuringNextRead() {
        OfferFilterService.nodeFetchForTests = () -> {
            OfferFilterService.nodeFetchForTests = null;
            FilterStore.setAutopilot(app, false, FilterSettings.GOAL_TOP_TIER);
        };
    }

    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo item = AccessibilityNodeInfo.obtain(new View(app));
        item.setPackageName("com.doordash.driverapp");
        if (text != null) item.setText(text);
        item.setVisibleToUser(true);
        item.setEnabled(true);
        item.setClickable(clickable);
        if (clickable) {
            item.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            Shadows.shadowOf(item).setOnPerformActionListener((action, args) -> true);
        }
        return item;
    }

    private AccessibilityNodeInfo offer(String pay, String countdown) {
        AccessibilityNodeInfo root = node(null, false);
        decline = node("Decline", true);
        accept = node("Accept", true);
        for (AccessibilityNodeInfo child : new AccessibilityNodeInfo[]{decline, node(pay, false),
                node("2 stops (7.2 mi) • 21 min", false), accept, node(countdown, false)}) {
            Shadows.shadowOf(root).addChild(child);
        }
        return root;
    }

    private AccessibilityNodeInfo question(AccessibilityNodeInfo confirm) {
        AccessibilityNodeInfo root = node(null, false);
        Shadows.shadowOf(root).addChild(node("Are you sure you want to decline this offer?", false));
        Shadows.shadowOf(root).addChild(confirm);
        return root;
    }

    private List<Long> taps(AccessibilityNodeInfo control) {
        List<Long> times = new ArrayList<>();
        Shadows.shadowOf(control).setOnPerformActionListener((action, args) -> {
            times.add(SystemClock.uptimeMillis());
            return true;
        });
        return times;
    }

    private void silentlyShow(AccessibilityNodeInfo root) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        shadow.setRoot(root);
        shadow.setActive(true);
        shadow.setBoundsInScreen(new Rect(0, 0, 1080, 2040));
        shadow.setLayer(0);
        Shadows.shadowOf(service).setWindows(Collections.singletonList(window));
        Shadows.shadowOf(service).setRootInActiveWindow(root);
    }

    private void show(AccessibilityNodeInfo root) {
        silentlyShow(root);
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
    }

    private void event(int type) {
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setPackageName("com.doordash.driverapp");
        service.onAccessibilityEvent(event);
        pass(0);
    }

    private static void pass(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }
}

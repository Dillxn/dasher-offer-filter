package com.local.dasherfilter;

import android.Manifest;
import android.app.Application;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.robolectric.shadows.ShadowToast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The Earn by Time guard ({@link TimeModeGuard}), through the real service reading on the main looper with simulated
 * time: Dasher's question that says another decline ends the dash is never confirmed, the offer is left to the user
 * and auto-decline pauses with its homepage line; Dasher ending the dash right after a confirmed decline pauses it too.
 * Dasher's real time-mode wording is not known yet: every question here is made up.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public class TimeModeGuardTest {
    private static final Rect SCREEN = new Rect(0, 0, 1080, 2040);

    private Application app;
    private ServiceController<OfferFilterService> controller;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        Updater.setEnabled(app, false);
        OfferAlerts.ensureChannel(app);
        FilterStore.save(app, FilterSettings.of(true, 2000, 0, 0, 0));
        DiagnosticLog.clear(app);
        TimeModeGuard.resumed(app);
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        AreaMap.forgetCache();
        OfferSilencer.forgetCache();
        OfferFilterService.scanLooperForTests = null;
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
    }

    @After
    public void tearDown() {
        if (controller != null) controller.destroy();
        OfferFilterService.sawDasherBeside(0);
        OfferFilterService.scanLooperForTests = null;
        OfferFilterService.nodeFetchForTests = null;
    }

    @Test
    public void madeUpTimeModeWordingIsRecognisedAndTheOrdinaryQuestionIsNot() {
        assertTrue(TimeModeGuard.endsDash(Arrays.asList("Decline offer?",
                "Declining another offer will end your dash")));
        assertTrue(TimeModeGuard.endsDash(Arrays.asList("Are you sure?",
                "You can decline or unassign up to one offer every hour")));
        assertTrue(TimeModeGuard.endsDash(Arrays.asList("Your dash will end if you decline")));
        assertFalse(TimeModeGuard.endsDash(Arrays.asList("Are you sure you want to decline this offer?",
                "Does not lower acceptance rate", "50%", "Accepting this offer will raise acceptance rate")));
        assertFalse("pay per hour is not a decline limit", TimeModeGuard.endsDash(Arrays.asList(
                "Are you sure you want to decline this offer?", "$24.10/hr", "Earn per hour")));
    }

    @Test
    public void aQuestionSayingTheDashWouldEndIsNeverConfirmedAndAutoDeclinePauses() {
        OfferFilterService service = service();
        show(service, offer("$7.90", "0:35"));
        pass(300);
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirmTaps = taps(confirm, true);
        show(service, timeModeQuestion(confirm));
        for (int i = 0; i < 40; i++) pass(50);

        assertEquals("never confirmed", 0, confirmTaps.size());
        DecisionLog.Entry line = DecisionLog.recent(app, 1).get(0);
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, line.action);
        assertEquals(DecisionLog.Outcome.YOURS, DecisionLog.outcome(line));
        assertFalse("auto-decline paused", FilterStore.load(app).enabled);
        assertEquals("its rules kept", 2000, FilterStore.load(app).flatCents);
        assertEquals(TimeModeGuard.WOULD_END_LINE, TimeModeGuard.line(app));
        assertEquals("Dasher said another decline would end this dash. Offer left to you; auto-decline paused",
                ShadowToast.getTextOfLatestToast());
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("confirmation not tapped: Dasher's question says another decline ends the dash"));

        // Resumed by the user: the line goes.
        FilterStore.save(app, FilterStore.load(app).withEnabled(true));
        TimeModeGuard.resumed(app);
        assertNull(TimeModeGuard.line(app));
    }

    /**
     * The owner's time-mode report (12 October 2026, 9:23 PM): after the first Decline Dasher showed a screen of its
     * own, not its usual question, whose words the report did not keep. Words like these on such a screen hand the
     * offer back and pause too, and the screen's words are kept, masked, for the next report.
     */
    @Test
    public void aScreenOfItsOwnSayingTheDashWouldEndHandsTheOfferBack() {
        OfferFilterService service = service();
        show(service, offer("$7.90", "0:35"));
        pass(300);
        AccessibilityNodeInfo root = node(null, false);
        Shadows.shadowOf(root).addChild(node("Declining this offer will end your dash", false));
        Shadows.shadowOf(root).addChild(button("Keep offer"));
        Shadows.shadowOf(root).addChild(button("End dash"));
        show(service, root);
        for (int i = 0; i < 40; i++) pass(50);

        assertFalse("auto-decline paused", FilterStore.load(app).enabled);
        assertEquals(TimeModeGuard.WOULD_END_LINE, TimeModeGuard.line(app));
        assertEquals(DecisionLog.Outcome.YOURS, DecisionLog.outcome(DecisionLog.recent(app, 1).get(0)));
        assertTrue("its words kept, masked", PersonalText.recognizedDashScreen(Arrays.asList(
                "Declining this offer will end your dash", "Keep offer", "End dash")));
        assertTrue(PersonalText.recognizedDashScreen(Arrays.asList("You've reached your decline limit", "OK")));
    }

    @Test
    public void dasherEndingTheDashRightAfterAConfirmedDeclinePausesOnlyThen() {
        for (boolean rightAfter : new boolean[] {true, false}) {
            setup();
            if (controller != null) controller.destroy();
            OfferFilterService service = service();
            show(service, offer("$7.90", "0:35"));
            pass(300);
            AccessibilityNodeInfo confirm = button("Decline offer");
            List<Long> confirmTaps = taps(confirm, true);
            show(service, question(confirm));
            assertEquals(1, confirmTaps.size());
            show(service, waiting());
            pass(rightAfter ? 2_000 : TimeModeGuard.ENDED_AFTER_DECLINE_MS + 5_000);
            show(service, dashEnded());
            pass(300);
            assertEquals(rightAfter ? "paused" : "not paused", !rightAfter, FilterStore.load(app).enabled);
            assertEquals(rightAfter ? TimeModeGuard.ENDED_LINE : null, TimeModeGuard.line(app));
        }
    }

    private AccessibilityNodeInfo timeModeQuestion(AccessibilityNodeInfo declineOffer) {
        // Made-up wording: no time-mode report has shown Dasher's real question yet.
        AccessibilityNodeInfo root = node(null, false);
        Shadows.shadowOf(root).addChild(node("Are you sure you want to decline this offer?", false));
        Shadows.shadowOf(root).addChild(node("Declining another offer will end your dash", false));
        Shadows.shadowOf(root).addChild(declineOffer);
        Shadows.shadowOf(root).addChild(button("Go back"));
        Shadows.shadowOf(root).addChild(node("0:24", false));
        return root;
    }

    private AccessibilityNodeInfo waiting() {
        AccessibilityNodeInfo root = node(null, false);
        Shadows.shadowOf(root).addChild(node("Finding offers", false));
        return root;
    }

    private AccessibilityNodeInfo dashEnded() {
        AccessibilityNodeInfo root = node(null, false);
        Shadows.shadowOf(root).addChild(node("Your dash has ended", false));
        Shadows.shadowOf(root).addChild(node("Dash summary", false));
        return root;
    }

    // ---- Dasher's screens, as DeclineHandBackTest draws them ----

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

    private AccessibilityNodeInfo button(String label) {
        AccessibilityNodeInfo button = node(null, true);
        Shadows.shadowOf(button).addChild(node(label, false));
        return button;
    }

    private AccessibilityNodeInfo offer(String pay, String countdown) {
        AccessibilityNodeInfo root = node(null, false);
        Shadows.shadowOf(root).addChild(button("Decline"));
        Shadows.shadowOf(root).addChild(node(pay, false));
        Shadows.shadowOf(root).addChild(node("incl. tips", false));
        Shadows.shadowOf(root).addChild(node("2 stops (7.2 mi) • 21 min", false));
        Shadows.shadowOf(root).addChild(button("Accept"));
        Shadows.shadowOf(root).addChild(node(countdown, false));
        return root;
    }

    private AccessibilityNodeInfo question(AccessibilityNodeInfo declineOffer, AccessibilityNodeInfo... more) {
        AccessibilityNodeInfo root = node(null, false);
        Shadows.shadowOf(root).addChild(node("Are you sure you want to decline this offer?", false));
        Shadows.shadowOf(root).addChild(node("Does not lower acceptance rate", false));
        Shadows.shadowOf(root).addChild(node("50%", false));
        Shadows.shadowOf(root).addChild(node("Accepting this offer will raise acceptance rate", false));
        Shadows.shadowOf(root).addChild(declineOffer);
        Shadows.shadowOf(root).addChild(node("0:24", false));
        for (AccessibilityNodeInfo control : more) Shadows.shadowOf(root).addChild(control);
        return root;
    }

    private static List<Long> taps(AccessibilityNodeInfo button, boolean taken) {
        List<Long> at = new CopyOnWriteArrayList<>();
        Shadows.shadowOf(button).setOnPerformActionListener((action, args) -> {
            at.add(SystemClock.uptimeMillis());
            return taken;
        });
        return at;
    }

    private static AccessibilityWindowInfo window(AccessibilityNodeInfo root) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        shadow.setRoot(root);
        shadow.setActive(true);
        shadow.setBoundsInScreen(SCREEN);
        shadow.setLayer(0);
        return window;
    }

    private static AccessibilityEvent event(int type) {
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setPackageName("com.doordash.driverapp");
        return event;
    }

    private OfferFilterService service() {
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        controller = Robolectric.buildService(OfferFilterService.class).create();
        OfferFilterService service = controller.get();
        service.onServiceConnected();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return service;
    }

    private void show(OfferFilterService service, AccessibilityNodeInfo root) {
        Shadows.shadowOf(service).setWindows(Collections.singletonList(window(root)));
        Shadows.shadowOf(service).setRootInActiveWindow(root);
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void pass(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }
}

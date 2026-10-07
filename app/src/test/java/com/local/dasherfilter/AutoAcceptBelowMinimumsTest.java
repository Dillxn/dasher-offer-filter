package com.local.dasherfilter;

import android.app.Application;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
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
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowWindowManagerImpl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The owner's decision (6 October 2026): auto-accept never goes below the user's own minimums, even in recovery. An
 * offer that passes only because Autopilot's bar is below 100% is the user's to accept: KEEP, Dasher left up with it,
 * the slim bar amber, and never armed for an automatic Accept ({@link AutoAccept#acceptRules}). Through the real
 * service, with auto-accept turned on; synthetic Android windows, no handset claimed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public final class AutoAcceptBelowMinimumsTest {
    /** The starter minimums: $4 an offer, $1 a mile, $0.25 a minute ($15 an hour). */
    private static final FilterSettings STARTER = FilterSettings.of(true, 400, 100, 25, 0);
    /** 6.6 mi and 27 min ask $6.75 at 100%: per hour asks the most. */
    private static final String ROUTE = "2 stops (6.6 mi) • 27 min";

    private Application app;
    private ServiceController<OfferFilterService> controller;
    private OfferFilterService service;
    private AccessibilityNodeInfo accept;
    private AccessibilityNodeInfo decline;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        DiagnosticLog.setEnabled(app, true);
        DiagnosticLog.clear(app);
        FilterStore.save(app, STARTER);
        FilterStore.setAutoAcceptEnabled(app, true);
        ActiveRouteStore.clear(app);
        RestartSuppression.clear(app);
        AutoAcceptMemory.clear(app);
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        OfferSilencer.forgetCache();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        OfferFilterService.forgetScreenState();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        controller = Robolectric.buildService(OfferFilterService.class).create();
        service = controller.get();
        service.onServiceConnected();
        pass(0);
    }

    @After public void teardown() {
        controller.destroy();
        OfferFilterService.scanLooperForTests = null;
        OfferFilterService.forgetScreenState();
    }

    /** Autopilot on, its bar where a commit between offers left it. */
    private void autopilotBarAt(int bar) {
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertTrue(FilterStore.commitAutopilotBar(app, FilterStore.load(app).minimumScalePercent, bar));
    }

    @Test public void aPassOnlyBelowTheMinimumsIsLeftToTheUserAndNeverArmed() {
        autopilotBarAt(82);
        show(node("Finding offers", false));
        // $5.75 is 85% of the $6.75 the minimums ask: under the 82% bar it passes, below the minimums.
        show(offer("$5.75", ROUTE, "0:30"));
        pass(1_200);
        assertEquals("never accepted automatically", 0, clicks(accept));
        assertEquals("never declined", 0, clicks(decline));
        DecisionLog.Entry line = DecisionLog.recent(app, 1).get(0);
        assertEquals(OfferRule.Result.KEEP, line.result);
        assertEquals("below your minimums; passes the 82% bar", line.reason);
        assertEquals(85, line.scorePercent);
        assertEquals(82, line.barPercent);
        assertTrue(line.autopilot);
        assertEquals(DecisionLog.Action.PASSES, line.action);
        for (DecisionLog.Step step : line.steps) {
            assertFalse("no automatic Accept was armed, requested or refused: " + step.kind,
                    step.kind == DecisionLog.StepKind.AUTO_ACCEPT_REQUESTED
                            || step.kind == DecisionLog.StepKind.AUTO_ACCEPT_NOT_SENT
                            || step.kind == DecisionLog.StepKind.ACCEPTED_AUTOMATIC);
        }
        String log = DiagnosticLog.read(app);
        assertFalse(log, log.contains("Accept REQUESTED"));
        assertFalse(log, log.contains("Accept NOT_SENT"));
        assertTrue("no touch watch was raised to verify an automatic Accept", touchWatches().isEmpty());
        assertFalse(AutoAcceptMemory.covers(app, line.facts));
        // The slim bar over it is amber, as over an offer needing the user's review: it is theirs to take.
        DasherTab tab = tab();
        assertNotNull(tab);
        assertEquals(OfferRule.Result.REVIEW, tab.verdict());
        assertEquals(Ui.WARNING, tab.peekColor());
        // Still showing a second later, its countdown going on: still never armed.
        show(offer("$5.75", ROUTE, "0:28"));
        pass(1_200);
        assertEquals(0, clicks(accept));
    }

    @Test public void anOfferMeetingAllOfTheMinimumsIsStillAcceptedAutomaticallyUnderALowerBar() {
        autopilotBarAt(82);
        show(node("Finding offers", false));
        // $7.00 is 103% of the $6.75 the minimums ask: it meets them, whatever the bar.
        show(offer("$7.00", ROUTE, "0:30"));
        pass(1_200);
        assertEquals(1, clicks(accept));
        DecisionLog.Entry line = DecisionLog.recent(app, 1).get(0);
        assertEquals(OfferRule.Result.KEEP, line.result);
        assertEquals(OfferRule.MEETS_MINIMUMS, line.reason);
        assertTrue(DecisionLog.hasStep(line, DecisionLog.StepKind.AUTO_ACCEPT_REQUESTED));
        assertTrue(DiagnosticLog.read(app).contains("Accept REQUESTED"));
    }

    @Test public void aPassJustBelowTheMinimumsIsNeverAcceptedAtAnyBarBelowOneHundred() {
        for (int bar : new int[] {50, 75, 99}) {
            autopilotBarAt(bar);
            show(node("Finding offers", false));
            // 99% of the minimums ($6.69 of $6.75): a pass at every one of these bars, and below the minimums.
            show(offer("$6.69", ROUTE, "0:30"));
            pass(1_200);
            assertEquals("bar " + bar, 0, clicks(accept));
            DecisionLog.Entry line = DecisionLog.recent(app, 1).get(0);
            assertEquals(OfferRule.Result.KEEP, line.result);
            assertEquals(bar, line.barPercent);
            assertFalse(DecisionLog.hasStep(line, DecisionLog.StepKind.AUTO_ACCEPT_REQUESTED));
            show(node("Finding offers", false));
            pass(61_000);
        }
        assertFalse(DiagnosticLog.read(app).contains("Accept REQUESTED"));
    }

    @Test public void aBarAboveOneHundredIsWhatAnAutomaticAcceptAsks() {
        autopilotBarAt(120);
        show(node("Finding offers", false));
        // $7.80 is 115% of the minimums: under the 120% bar it fails, and is declined, never accepted.
        show(offer("$7.80", ROUTE, "0:30"));
        pass(1_200);
        assertEquals(0, clicks(accept));
        assertEquals(1, clicks(decline));
        DecisionLog.Entry declined = DecisionLog.recent(app, 1).get(0);
        assertEquals(OfferRule.Result.DECLINE, declined.result);
        assertEquals("120% bar: dollars per hour", declined.reason);
        assertEquals(120, declined.barPercent);
        show(node("Finding offers", false));
        pass(61_000);
        // A tick between offers lets the planner move the bar; set it back where this offer is to meet it.
        autopilotBarAt(120);
        // $8.50 is 125%: it meets the 120% bar, so an automatic Accept may take it.
        show(offer("$8.50", ROUTE, "0:30"));
        pass(1_200);
        assertEquals(1, clicks(accept));
        assertEquals("meets the 120% bar", DecisionLog.recent(app, 1).get(0).reason);
    }

    // ---- Dasher's screens ----

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

    private AccessibilityNodeInfo offer(String pay, String route, String countdown) {
        AccessibilityNodeInfo root = node("", false);
        accept = node("Accept", true);
        decline = node("Decline", true);
        for (AccessibilityNodeInfo child : new AccessibilityNodeInfo[] {decline, node(pay, false), node(route, false),
                accept, node(countdown, false)}) {
            Shadows.shadowOf(root).addChild(child);
        }
        return root;
    }

    private void show(AccessibilityNodeInfo root) {
        TestWindows.full(service, root);
        ((ShadowAccessibilityWindowInfo) Shadow.extract(service.getWindows().get(0))).setFocused(true);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("com.doordash.driverapp");
        event.setEventTime(SystemClock.uptimeMillis());
        service.onAccessibilityEvent(event);
        pass(0);
    }

    private List<View> overlays() {
        ShadowWindowManagerImpl windows = Shadow.extract(service.getSystemService(WindowManager.class));
        return windows.getViews();
    }

    private DasherTab tab() {
        for (View view : overlays()) if (view instanceof DasherTab) return (DasherTab) view;
        return null;
    }

    private List<View> touchWatches() {
        List<View> watches = new ArrayList<>();
        for (View view : overlays()) {
            if (!(view instanceof DasherTab) && !(view instanceof DasherGuide) && !(view instanceof BackToMapChip)) {
                watches.add(view);
            }
        }
        return watches;
    }

    private static int clicks(AccessibilityNodeInfo node) {
        return Shadows.shadowOf(node).getPerformedActions().size();
    }

    private static void pass(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }
}

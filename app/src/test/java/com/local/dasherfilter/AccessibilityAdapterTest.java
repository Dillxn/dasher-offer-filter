package com.local.dasherfilter;

import android.app.Application;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
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
import org.robolectric.shadows.ShadowSystemClock;

import static org.junit.Assert.assertEquals;
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

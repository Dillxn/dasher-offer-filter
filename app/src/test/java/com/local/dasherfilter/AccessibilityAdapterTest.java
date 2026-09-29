package com.local.dasherfilter;

import android.app.Application;
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
        DecisionLog.forgetCache();
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

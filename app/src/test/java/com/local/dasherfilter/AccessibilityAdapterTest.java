package com.local.dasherfilter;

import android.app.Application;
import android.view.View;
import android.view.accessibility.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadows.ShadowSystemClock;
import java.time.Duration;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,35})
@LooperMode(LooperMode.Mode.PAUSED)
public class AccessibilityAdapterTest {
    private Application app;
    private ServiceController<OfferFilterService> controller;
    private AccessibilityNodeInfo decline, accept;
    @Before public void setup() {
        app=RuntimeEnvironment.getApplication();Updater.setEnabled(app,false);
        FilterStore.save(app,new FilterSettings(true,2000,0,0,0,0));
        controller=Robolectric.buildService(OfferFilterService.class).create();ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
    }
    @After public void stop() { controller.destroy(); }
    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo n=AccessibilityNodeInfo.obtain(new View(app));n.setPackageName("com.doordash.driverapp");n.setText(text);n.setVisibleToUser(true);n.setEnabled(true);n.setClickable(clickable);
        if(clickable){n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);Shadows.shadowOf(n).setOnPerformActionListener((action,args)->true);}return n;
    }
    private AccessibilityNodeInfo offer(String money) {
        AccessibilityNodeInfo root=node("",false);accept=node("Accept",true);decline=node("Decline",true);
        Shadows.shadowOf(root).addChild(node(money,false));Shadows.shadowOf(root).addChild(node("2 stops (7.2 mi) • 21 min",false));Shadows.shadowOf(root).addChild(accept);Shadows.shadowOf(root).addChild(decline);return root;
    }
    private void show(AccessibilityNodeInfo root) {
        Shadows.shadowOf(controller.get()).setRootInActiveWindow(root);
        AccessibilityEvent e=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);e.setPackageName("com.doordash.driverapp");controller.get().onAccessibilityEvent(e);
    }
    private AccessibilityNodeInfo confirmation(AccessibilityNodeInfo button) {
        AccessibilityNodeInfo root=node("Are you sure you want to decline this offer?",false);Shadows.shadowOf(root).addChild(button);return root;
    }
    @Test public void failingVisibleOfferRequestsDeclineImmediatelyButNeverAccepts() {
        AccessibilityNodeInfo root=offer("$7.90");show(root);assertEquals(1,Shadows.shadowOf(decline).getPerformedActions().size());assertTrue(Shadows.shadowOf(accept).getPerformedActions().isEmpty());
        show(root);assertEquals(1,Shadows.shadowOf(decline).getPerformedActions().size());assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }
    @Test public void pauseRevokesPendingConfirmation() {
        show(offer("$7.90"));FilterStore.save(app,new FilterSettings(false,2000,0,0,0,0));AccessibilityNodeInfo confirm=node("Decline offer",true);show(confirmation(confirm));assertTrue(Shadows.shadowOf(confirm).getPerformedActions().isEmpty());
    }
    @Test public void passingScreenRevokesOldConfirmationAuthority() {
        show(offer("$7.90"));show(offer("$25.00"));AccessibilityNodeInfo confirm=node("Decline offer",true);show(confirmation(confirm));assertTrue(Shadows.shadowOf(confirm).getPerformedActions().isEmpty());
    }
    @Test public void malformedMoneyCannotTriggerScreenDecline() { show(offer("$7.901"));assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty()); }
    @Test public void anotherAppCannotBecomeADasherOffer() { AccessibilityNodeInfo root=offer("$7.90");root.setPackageName("com.example.other");show(root);assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty()); }
    @Test public void sharedAcceptDeclineContainerIsNotAClickTarget() {
        AccessibilityNodeInfo root=node("",false),shared=node("",true);Shadows.shadowOf(root).addChild(node("$7.90",false));Shadows.shadowOf(root).addChild(shared);Shadows.shadowOf(shared).addChild(node("Accept",false));Shadows.shadowOf(shared).addChild(node("Decline",false));show(root);assertTrue(Shadows.shadowOf(shared).getPerformedActions().isEmpty());
    }
}

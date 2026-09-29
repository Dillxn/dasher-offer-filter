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
    // ---- 0.5.0 adapter regressions (each fails on 0.4.5 behavior; auditor probes ported) ----
    private final java.util.Map<String, AccessibilityNodeInfo> nodes = new java.util.HashMap<>();
    /** A Dasher root with one node per label; controls and navigation labels are clickable. nodes.get(label) finds each. */
    private AccessibilityNodeInfo screen(String... labels) {
        AccessibilityNodeInfo root=node("",false);nodes.clear();
        for (String label : labels) {
            boolean clickable=OfferControls.isControl(label)||label.equals("Back")||label.equals("Cancel")||label.equals("Decline offer")||label.equals("Directions");
            AccessibilityNodeInfo n=node(label,clickable);nodes.put(label,n);Shadows.shadowOf(root).addChild(n);
        }
        return root;
    }
    private int clicks(String label) { return Shadows.shadowOf(nodes.get(label)).getPerformedActions().size(); }
    private static int clicks(AccessibilityNodeInfo n) { return Shadows.shadowOf(n).getPerformedActions().size(); }
    private OfferHistoryStore history() { return OfferHistoryStore.get(app); }
    private OfferRecord onlyRecord() { assertEquals(1,history().size()); return history().newestFirst().get(0); }
    private String status() { return FilterStore.lastStatus(app); }
    private void tapAccept(String label) {
        AccessibilityEvent e=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);e.setPackageName("com.doordash.driverapp");e.getText().add(label);controller.get().onAccessibilityEvent(e);
    }
    /** Declines A ($7.90), confirms it on a sheet, then Dasher shows its map again (H2-1 setup). */
    private void declineAndConfirmA() {
        show(offer("$7.90"));assertEquals(1,clicks(decline));ShadowSystemClock.advanceBy(Duration.ofMillis(400));
        show(screen("Are you sure you want to decline this offer?","Decline offer","Cancel"));assertEquals(1,clicks("Decline offer"));
        ShadowSystemClock.advanceBy(Duration.ofMillis(600));show(screen("Heading to Chipotle","Directions"));ShadowSystemClock.advanceBy(Duration.ofSeconds(4));
    }
    /** H2-1: a confirmed decline of A never spends its authority on B ($25, passing) shown 5 s later, whatever B's controls look like. */
    @Test public void pendingConfirmationNeverDeclinesADifferentNextOffer() {
        declineAndConfirmA();
        show(screen("$25.00","2 stops (7.2 mi) • 21 min","Accept","Decline","Back"));assertEquals(0,clicks("Decline"));assertEquals(0,clicks("Accept"));
        show(screen("$25.00","2 stops (7.2 mi) • 21 min","Slide to accept","Decline"));assertEquals(0,clicks("Decline"));
        AccessibilityNodeInfo hiddenAccept=screen("$25.00","2 stops (7.2 mi) • 21 min","Accept","Decline");nodes.get("Accept").setVisibleToUser(false);show(hiddenAccept);assertEquals(0,clicks("Decline"));
    }
    /** H2-1 single-step variant: A declined with no sheet, the card leaves, B arrives within the 10 s authority window. */
    @Test public void singleStepDeclineAuthorityNeverReachesTheNextOffer() {
        show(offer("$7.90"));assertEquals(1,clicks(decline));show(screen("Heading to Chipotle","Directions"));ShadowSystemClock.advanceBy(Duration.ofSeconds(4));
        show(screen("$25.00","2 stops (7.2 mi) • 21 min","Accept","Decline","Back"));assertEquals(0,clicks("Decline"));
        show(screen("$25.00","2 stops (7.2 mi) • 21 min","Slide to accept","Decline"));assertEquals(0,clicks("Decline"));
    }
    /** A confirmation sheet laid over the declined offer in the same tree still confirms that offer. */
    @Test public void sheetOverTheDeclinedOfferStillConfirms() {
        show(offer("$7.90"));assertEquals(1,clicks(decline));ShadowSystemClock.advanceBy(Duration.ofMillis(300));
        show(screen("$7.90","2 stops (7.2 mi) • 21 min","Accept","Decline","Are you sure you want to decline this offer?","Decline offer","Cancel"));
        assertEquals(1,clicks("Decline offer"));assertEquals(0,clicks("Decline"));assertEquals(0,clicks("Accept"));
        assertTrue(onlyRecord().has(OfferRecord.CONFIRM_REQUESTED));
    }
    /** H2-8: a failing offer that also shows a toolbar Back still gets the immediate first decline. */
    @Test public void failingOfferWithBackLabelGetsImmediateFirstDecline() {
        show(screen("$7.90","2 stops (7.2 mi) • 21 min","Accept","Decline","Back"));assertEquals(1,clicks("Decline"));assertEquals(0,clicks("Back"));
    }
    /** "Add to route" is the add-on accept control: evaluated on the add-on path, never a confirmation, and its tap reaches the tracker. */
    @Test public void addToRouteCardIsAnAddOnNeverAConfirmationAndItsAcceptIsTracked() {
        ActiveRouteStore.save(app,new OfferSnapshot(1000,3.0,20,2));
        show(offer("$7.90"));assertEquals(1,clicks(decline));ShadowSystemClock.advanceBy(Duration.ofMillis(300));   // pending authority from A
        show(screen("Add this order to your route","+$6.00","+2.1 mi","Add to route 30","Decline"));
        assertEquals("an add-on card is not A's confirmation sheet",0,clicks("Decline"));assertEquals(0,clicks("Add to route 30"));
        OfferRecord addOn=history().newestFirst().get(0);assertTrue(addOn.addOn);assertEquals(Integer.valueOf(600),addOn.payCents);
        assertTrue(status(),status().contains("add-on"));
        tapAccept("Add to route 30");
        assertNull("any accept tap clears the stored route until acceptance is observed",ActiveRouteStore.load(app));
        show(screen("Heading to Chipotle","Confirm pickup"));
        OfferSnapshot route=ActiveRouteStore.load(app);assertNotNull(status(),route);assertEquals(Integer.valueOf(1600),route.payCents);
        assertTrue(history().get(addOn.id).has(OfferRecord.ACCEPT_OBSERVED));assertTrue(status().contains("Acceptance observed"));
        assertEquals("an add-on never moves the standalone baseline",0,FilterStore.load(app).lastAcceptedCents);
    }
    /** After an accept tap only the tapped card keeps its pre-tap route; a different add-on shown next never uses that stale route. */
    @Test public void differentAddOnAfterAnAcceptTapNeverUsesTheStaleRoute() {
        FilterStore.save(app,new FilterSettings(true,0,0,0,0,2));   // max 2 stops: the add-on stop ceiling uses the stored route's stops
        ActiveRouteStore.save(app,new OfferSnapshot(1000,3.0,20,2));
        show(screen("Add this order to your route","New total $16.00","+2.1 mi","Add to route 30","Decline"));assertEquals(0,clicks("Decline"));
        OfferRecord tapped=history().newestFirst().get(0);assertNull("a pay increment derived from the stored route is not recorded as a fact",tapped.payCents);
        tapAccept("Add to route 30");
        show(screen("Add this order to your route","+$5.00","+1 stop","Add to route 45","Decline"));
        OfferRecord next=history().newestFirst().get(0);assertNotEquals(tapped.id,next.id);
        assertEquals("the stored route was cleared by the tap: 2 old stops + 1 is not a known total",0,clicks("Decline"));
        assertNotEquals(OfferRule.Code.MAX_STOPS,next.reasonCode);assertEquals(Integer.valueOf(500),next.payCents);
    }
    /** A2/H4.2: an Earn by Time card is never clicked, even when a flat rule would fail its hourly rate. */
    @Test public void earnByTimeCardIsNeverDeclined() {
        show(screen("$15/active hr + tips","From when you accept to when you complete this offer","Accept","Decline"));
        assertEquals(0,clicks("Decline"));assertTrue(status(),status().contains("Earn by Time offer — not auto-declined"));
        assertEquals(OfferRule.Code.HOURLY_MODE,onlyRecord().reasonCode);
        show(screen("$15","/active hr","+ tips","Accept","Decline"));assertEquals(0,clicks("Decline"));
    }
    /** A1: the redesigned flash card puts $28.24 before "Guaranteed"; the $15.00 boost is never the pay. */
    @Test public void flashSplitNodeCardUsesGuaranteedPayNotTheBoost() {
        show(screen("$28.24","Guaranteed","Includes a $15.00 Flash offer boost","3.2 mi • 18 min","Accept","Decline"));
        assertEquals(0,clicks("Decline"));assertEquals(Integer.valueOf(2824),onlyRecord().payCents);assertEquals(OfferRule.Result.KEEP,onlyRecord().verdict);
    }
    /** B5: an avoided store on a standalone screen is a known failure: immediate first decline. */
    @Test public void avoidedStoreOnStandaloneScreenDeclinesImmediately() {
        FilterStore.save(app,new FilterSettings(true,2000,0,0,0,0).withAvoidStores(java.util.Arrays.asList("Chick-fil-A")));
        show(screen("$25.00","2 stops (7.2 mi) • 21 min","Chick-fil-A","Accept","Decline"));
        assertEquals(1,clicks("Decline"));OfferRecord r=onlyRecord();assertEquals(OfferRule.Code.AVOIDED_STORE,r.reasonCode);assertEquals("chick fil a",r.store);assertTrue(r.declineRequested());
    }
    /** D2: one record per screen offer across countdown ticks; DECLINE_REQUESTED is recorded only after the click. */
    @Test public void historyRecordsOneRowPerOfferAfterTheClick() {
        int[] recordsAtClick={-1};
        AccessibilityNodeInfo first=screen("$7.90","2 stops (7.2 mi) • 21 min","Accept 30","Decline");
        Shadows.shadowOf(nodes.get("Decline")).setOnPerformActionListener((action,args)->{recordsAtClick[0]=history().size();return true;});
        show(first);assertEquals(1,clicks("Decline"));assertEquals("no history work before the first decline click",0,recordsAtClick[0]);
        ShadowSystemClock.advanceBy(Duration.ofMillis(100));show(screen("$7.90","2 stops (7.2 mi) • 21 min","Accept 29","Decline"));
        ShadowSystemClock.advanceBy(Duration.ofMillis(100));show(screen("$7.90","2 stops (7.2 mi) • 21 min","Accept 28","Decline"));
        OfferRecord r=onlyRecord();assertEquals(OfferRecord.Source.SCREEN,r.source);assertEquals(OfferRule.Result.DECLINE,r.verdict);assertTrue(r.has(OfferRecord.DECLINE_REQUESTED));
        assertEquals("Decline requested",r.outcome());
        show(screen("Heading to Chipotle","Directions"));show(offer("$25.00"));assertEquals("offer gone: the next offer is a new record",2,history().size());
    }
    /** D2: with auto-decline off a failing offer is recorded as a shadow ("would have requested decline"), never clicked. */
    @Test public void pausedFailingOfferIsAShadowRecordWithoutAClick() {
        FilterStore.save(app,new FilterSettings(false,2000,0,0,0,0));show(offer("$7.90"));
        assertEquals(0,clicks(decline));OfferRecord r=onlyRecord();assertTrue(r.has(OfferRecord.PAUSED_SHADOW));assertFalse(r.declineRequested());
        assertEquals(OfferRule.Result.DECLINE,r.verdict);assertTrue(status(),status().contains("Auto-decline is off"));
    }
    /** B7: with a limit of 2 per hour, the third failing offer becomes REVIEW with no click. */
    @Test public void declineBudgetTurnsTheNextDeclineIntoReview() {
        FilterStore.save(app,new FilterSettings(true,2000,0,0,0,0).withMaxDeclinesPerHour(2));
        show(offer("$7.90"));assertEquals(1,clicks(decline));
        ShadowSystemClock.advanceBy(Duration.ofMillis(300));show(offer("$7.90"));assertEquals("a retry of the same offer is not blocked by its own request",1,clicks(decline));
        show(screen("Heading to Chipotle","Directions"));
        show(offer("$8.10"));assertEquals(1,clicks(decline));show(screen("Heading to Chipotle","Directions"));
        show(offer("$8.30"));assertEquals("limit reached: no click",0,clicks(decline));
        OfferRecord last=history().newestFirst().get(0);assertEquals(OfferRule.Code.DECLINE_LIMIT,last.reasonCode);assertEquals(OfferRule.Result.REVIEW,last.verdict);
        assertFalse(last.declineRequested());assertEquals(3,history().size());
        assertTrue(status(),status().contains("Decline limit reached"));
    }
    /** H2-6: the declined offer's own (merchant-only) notification must not revoke its confirmation. */
    @Test public void declinedOffersOwnNotificationDoesNotRevokeItsConfirmation() {
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);OfferAlerts.ensureChannel(app);
        org.robolectric.android.controller.ServiceController<OfferNotificationService> ns=Robolectric.buildService(OfferNotificationService.class).create();
        try {
            controller.get().onServiceConnected();
            show(offer("$7.90"));assertEquals(1,clicks(decline));
            android.app.Notification n=new android.app.Notification.Builder(app,"source").setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle("New Delivery!").setContentText("New Order: Go to Chick-fil-A").build();
            ns.get().onNotificationPosted(new android.service.notification.StatusBarNotification("com.doordash.driverapp","com.doordash.driverapp",3,"NEW_ORDER",10001,0,0,n,android.os.Process.myUserHandle(),System.currentTimeMillis()),null);
            ShadowSystemClock.advanceBy(Duration.ofMillis(300));show(screen("Are you sure you want to decline this offer?","Decline offer"));
            assertEquals("confirmation for the offer we declined should be requested",1,clicks("Decline offer"));
            // A different offer's notification with its own facts does revoke it.
            show(offer("$7.10"));assertEquals(1,clicks(decline));
            android.app.Notification other=new android.app.Notification.Builder(app,"source").setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle("New delivery offer").setContentText("$25.00 · 5.2 mi · 20 min").build();
            ns.get().onNotificationPosted(new android.service.notification.StatusBarNotification("com.doordash.driverapp","com.doordash.driverapp",4,"OTHER",10001,0,0,other,android.os.Process.myUserHandle(),System.currentTimeMillis()),null);
            ShadowSystemClock.advanceBy(Duration.ofMillis(300));show(screen("Are you sure you want to decline this offer?","Decline offer"));assertEquals(0,clicks("Decline offer"));
        } finally { ns.destroy(); }
    }
    @Test public void sharedAcceptDeclineContainerIsNotAClickTarget() {
        AccessibilityNodeInfo root=node("",false),shared=node("",true);Shadows.shadowOf(root).addChild(node("$7.90",false));Shadows.shadowOf(root).addChild(shared);Shadows.shadowOf(shared).addChild(node("Accept",false));Shadows.shadowOf(shared).addChild(node("Decline",false));show(root);assertTrue(Shadows.shadowOf(shared).getPerformedActions().isEmpty());
    }
}

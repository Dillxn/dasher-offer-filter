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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A decline whose question the app never managed to confirm is left to the user ("confirmation not tapped"), and its
 * history line says so: before, the line kept "Decline tapped", so its ticket was stamped "DECLINED" and it counted
 * as filtered, though Dasher still asked. Through the real service, reading on the main looper with simulated time.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public class ConfirmationLeftToYouTest {
    private static final Rect SCREEN = new Rect(0, 0, 1080, 2040);

    private Application app;
    private ServiceController<OfferFilterService> controller;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        Updater.setEnabled(app, false);
        OfferAlerts.ensureChannel(app);
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        DiagnosticLog.clear(app);
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        AreaMap.forgetCache();
        ReportOutbox.forgetCache();
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
    public void aQuestionAndroidRefusesEveryTryAtIsLeftToTheUserAndCountsAsReview() {
        assertLeftToTheUser(false);
    }

    @Test
    public void aQuestionDasherNeverActsOnIsLeftToTheUserAndCountsAsReview() {
        // Android took each try, so its confirmation was tapped; Dasher still asked, so the decline did not go through.
        assertLeftToTheUser(true);
    }

    private void assertLeftToTheUser(boolean tapsTaken) {
        OfferFilterService service = service();
        show(service, offer("$7.90", "0:35"));
        pass(300);
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirmTaps = taps(confirm, tapsTaken);
        show(service, question(confirm, button("View offer details")));
        for (int i = 0; i < (tapsTaken ? 130 : 40); i++) pass(50);

        assertEquals("the first try and two retries", 3, confirmTaps.size());
        DecisionLog.Entry line = DecisionLog.recent(app, 1).get(0);
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, line.action);
        assertEquals("one line for the offer", 1, DecisionLog.recent(app, 10).size());
        assertEquals(DecisionLog.Outcome.YOURS, DecisionLog.outcome(line));
        assertEquals(DecisionLog.Tally.REVIEW, DecisionLog.tally(line));
        int[] totals = DecisionLog.totals(app);
        assertEquals(0, totals[DecisionLog.Tally.FILTERED.ordinal()]);
        assertEquals(1, totals[DecisionLog.Tally.REVIEW.ordinal()]);
        assertEquals("Rules: decline — below your minimum pay", MainActivity.reasonLine(line));
    }

    @Test
    public void aFiveSecondReadStillConfirmsAQuestionTenSecondsAfterTheActualTap() {
        OfferFilterService service = service();
        OfferFilterService.nodeFetchForTests = () -> {
            OfferFilterService.nodeFetchForTests = null;
            ShadowSystemClock.advanceBy(Duration.ofSeconds(5));
        };
        show(service, offer("$7.90", "0:35"));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> at = taps(confirm, true);
        show(service, question(confirm));
        assertEquals("late question still belongs to the offer countdown", 1, at.size());
    }

    @Test
    public void unreadableLookAndFigurelessAnimationDoNotRevokeTheDecline() {
        OfferFilterService service = service();
        show(service, offer("$7.90", "0:35"));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        show(service, null);
        AccessibilityNodeInfo frame = node(null, false);
        Shadows.shadowOf(frame).addChild(button("Accept"));
        Shadows.shadowOf(frame).addChild(button("Decline"));
        show(service, frame);
        assertEquals("animation creates no REVIEW line", 1, DecisionLog.recent(app, 10).size());
        assertEquals(DecisionLog.Action.DECLINE_TAPPED, DecisionLog.recent(app, 1).get(0).action);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(7));
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> at = taps(confirm, true);
        show(service, question(confirm));
        assertEquals(1, at.size());
    }

    @Test
    public void aTakenTapWaitsForDasherAndAClosingQuestionDoesNotBecomeLeftToYou() {
        OfferFilterService service = service();
        show(service, offer("$7.90", "0:35"));
        pass(300);
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> at = taps(confirm, true);
        show(service, question(confirm));
        pass(1_800);
        assertEquals("wait for the app to close the dialog", 1, at.size());
        show(service, node("Finding offers", false));
        assertEquals(DecisionLog.Action.CONFIRMATION_TAPPED, DecisionLog.recent(app, 1).get(0).action);
    }

    @Test
    public void lateIdleEvidenceCorrectsAJustGivenUpConfirmation() {
        OfferFilterService service = service();
        show(service, offer("$7.90", "0:35"));
        pass(300);
        show(service, question(button("Decline offer")));
        pass(6_100);
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
        show(service, node("Finding offers", false));
        assertEquals(DecisionLog.Action.CONFIRMATION_TAPPED, DecisionLog.recent(app, 1).get(0).action);
    }

    @Test
    public void vanishedPlusPayDoesNotTurnTheSameUncertainOfferIntoAKnownFailure() {
        FilterStore.save(app, new FilterSettings(true, 600, 0, 0, 0, 0));
        OfferFilterService service = service();
        AccessibilityNodeInfo first = node(null, false);
        for (String label : new String[]{"+$1", "$5.75", "2 stops (7.2 mi) • 21 min", "0:35"}) {
            Shadows.shadowOf(first).addChild(node(label, false));
        }
        Shadows.shadowOf(first).addChild(button("Accept"));
        Shadows.shadowOf(first).addChild(button("Decline"));
        show(service, first);
        show(service, offer("$5.75", "0:34"));
        assertEquals("one offer while its plus label flickers", 1, DecisionLog.recent(app, 10).size());
        assertEquals(DecisionLog.Action.NEEDS_REVIEW, DecisionLog.recent(app, 1).get(0).action);
    }

    @Test
    public void aLargerFreshBonusNeverInheritsTheSmallerCeiling() throws Exception {
        FilterStore.save(app, new FilterSettings(true, 800, 0, 0, 0, 0));
        OfferFilterService service = service();
        show(service, bonusOffer("+$1", "$7.00", "2 stops (7.2 mi) • 21 min", "0:35"));
        show(service, bonusOffer("+$2", "$7.00", "2 stops (7.2 mi) • 21 min", "0:34"));
        assertEquals(Integer.valueOf(900), currentOffer(service).payAtMostCents);
    }

    @Test
    public void newQualifierInvalidatesTheOldCeilingEvenAfterItDisappears() throws Exception {
        FilterStore.save(app, new FilterSettings(true, 800, 0, 0, 0, 0));
        OfferFilterService service = service();
        show(service, bonusOffer("+$1", "$7.00", "2 stops (7.2 mi) • 21 min", "0:35"));
        show(service, bonusOffer("+$1", "$7.00", "2 stops (7.2 mi) • 21 min", "0:34", "per delivery"));
        assertEquals(null, currentOffer(service).payAtMostCents);
        show(service, bonusOffer(null, "$7.00", "2 stops (7.2 mi) • 21 min", "0:33"));
        assertEquals(null, currentOffer(service).payCents);
        assertEquals(null, currentOffer(service).payAtMostCents);
        show(service, bonusOffer("+$1", "$7.00", "2 stops (7.2 mi) • 21 min", "0:32"));
        assertEquals("a vanished qualifier cannot restore the ceiling", null, currentOffer(service).payAtMostCents);
        show(service, bonusOffer(null, "$9.00", "2 stops (7.2 mi) • 21 min", "0:31"));
        assertEquals("a discredited ceiling cannot establish a new offer", null, currentOffer(service).payCents);
        assertEquals(null, currentOffer(service).payAtMostCents);
        show(service, bonusOffer(null, "$7.00", "", "0:30"));
        assertEquals("missing route figures cannot erase prior uncertainty", null, currentOffer(service).payCents);
        assertEquals(null, currentOffer(service).payAtMostCents);
        assertEquals(DecisionLog.Action.NEEDS_REVIEW, DecisionLog.recent(app, 1).get(0).action);
    }

    @Test
    public void aSecondBonusInvalidatesTheOldCeiling() throws Exception {
        FilterStore.save(app, new FilterSettings(true, 800, 0, 0, 0, 0));
        OfferFilterService service = service();
        show(service, bonusOffer("+$1", "$7.00", "2 stops (7.2 mi) • 21 min", "0:35"));
        show(service, bonusOffer("+$1", "$7.00", "2 stops (7.2 mi) • 21 min", "0:34", "+$2"));
        assertEquals(null, currentOffer(service).payAtMostCents);
        assertEquals(null, currentOffer(service).payCents);
    }

    @Test
    public void changedStackCountCannotReuseTheOldBoundOrBecomeExactPay() throws Exception {
        FilterStore.save(app, new FilterSettings(true, 1500, 0, 0, 0, 0));
        OfferFilterService service = service();
        show(service, bonusOffer("+$1", "$13.00", "3 stops (7.0 mi) • 33 min", "0:35",
                "Pick up 2 orders", "Multiple dropoffs (2 stops)"));
        show(service, bonusOffer("+$1", "$13.00", "3 stops (7.0 mi) • 33 min", "0:34",
                "Pick up 3 orders", "Multiple dropoffs (2 stops)"));
        assertEquals(Integer.valueOf(1600), currentOffer(service).payAtMostCents);
        show(service, bonusOffer(null, "$13.00", "3 stops (7.0 mi) • 33 min", "0:33",
                "Pick up 4 orders", "Multiple dropoffs (2 stops)"));
        assertEquals(null, currentOffer(service).payCents);
        assertEquals(null, currentOffer(service).payAtMostCents);
        show(service, bonusOffer(null, "$13.00", "3 stops (7.0 mi) • 33 min", "0:32"));
        assertEquals(null, currentOffer(service).payCents);
        assertEquals(null, currentOffer(service).payAtMostCents);
        assertEquals(DecisionLog.Action.NEEDS_REVIEW, DecisionLog.recent(app, 1).get(0).action);
    }

    private static OfferSnapshot currentOffer(OfferFilterService service) throws Exception {
        java.lang.reflect.Field field = OfferFilterService.class.getDeclaredField("waitReadOffer");
        field.setAccessible(true);
        return (OfferSnapshot) field.get(service);
    }

    private AccessibilityNodeInfo bonusOffer(String bonus, String pay, String route, String countdown,
                                             String... details) {
        AccessibilityNodeInfo root = node(null, false);
        if (bonus != null) Shadows.shadowOf(root).addChild(node(bonus, false));
        Shadows.shadowOf(root).addChild(button("Decline"));
        Shadows.shadowOf(root).addChild(node(pay, false));
        Shadows.shadowOf(root).addChild(node("incl. tips", false));
        Shadows.shadowOf(root).addChild(node(route, false));
        for (String detail : details) Shadows.shadowOf(root).addChild(node(detail, false));
        Shadows.shadowOf(root).addChild(button("Accept"));
        Shadows.shadowOf(root).addChild(node(countdown, false));
        return root;
    }

    @Test
    public void foregroundNoticePairsByTimeOrStoreButNeverByContradictingFigures() {
        OfferSnapshot facts = new OfferSnapshot(790, 7.2, 21, 2);
        List<String> screen = java.util.Arrays.asList("New Order: Go to Balance Bowls", "$7.90", "7.2 mi", "21 min", "2 stops");
        assertEquals(false, OfferFilterService.sameForegroundNotice(java.util.Arrays.asList("New Order: Go to Elsewhere"),
                facts, screen, 5_000));
        assertTrue(OfferFilterService.sameForegroundNotice(java.util.Arrays.asList("New Delivery!"),
                facts, screen, 5_000));
        assertTrue(OfferFilterService.sameForegroundNotice(java.util.Arrays.asList("New Order: Go to Balance Bowls"),
                facts, screen, 15_000));
        assertEquals(false, OfferFilterService.sameForegroundNotice(java.util.Arrays.asList("New Order", "$9.50"),
                facts, screen, 100));
    }

    @Test
    public void theOffersOwnNewNotificationKeyDoesNotCancelConfirmation() {
        OfferFilterService service = service();
        show(service, offer("$7.90", "0:35"));
        long before = OfferNotificationService.generation();
        ServiceController<OfferNotificationService> listener = Robolectric.buildService(OfferNotificationService.class).create();
        try {
            listener.get().onNotificationPosted(notice(), null);
            assertEquals("own notification is the same offer", before, OfferNotificationService.generation());
            pass(300);
            AccessibilityNodeInfo confirm = button("Decline offer");
            List<Long> at = taps(confirm, true);
            show(service, question(confirm));
            assertEquals(1, at.size());
        } finally { listener.destroy(); }
    }

    @Test
    public void aNoticeArrivingInsideTheFirstReadDoesNotCancelItsConfirmation() {
        OfferFilterService service = service();
        ServiceController<OfferNotificationService> listener = Robolectric.buildService(OfferNotificationService.class).create();
        try {
            OfferFilterService.nodeFetchForTests = () -> {
                OfferFilterService.nodeFetchForTests = null;
                listener.get().onNotificationPosted(notice(), null);
            };
            show(service, offer("$7.90", "0:35"));
            pass(300);
            AccessibilityNodeInfo confirm = button("Decline offer");
            List<Long> at = taps(confirm, true);
            show(service, question(confirm));
            assertEquals(1, at.size());
        } finally { listener.destroy(); }
    }

    @Test
    public void checkingForegroundForANotificationNeverFetchesAnAppRoot() {
        OfferFilterService service = service();
        show(service, offer("$7.90", "0:35"));
        int roots = service.rootFetches;
        assertTrue(OfferFilterService.isDasherOnScreenNow());
        assertEquals(roots, service.rootFetches);
    }

    @Test
    public void ownOverlayChangesDoNotInterruptOrQueueADasherRead() {
        OfferFilterService service = service();
        show(service, offer("$7.90", "0:35"));
        AccessibilityWindowInfo overlay = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(overlay);
        shadow.setId(900);
        shadow.setType(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY);
        List<AccessibilityWindowInfo> windows = new java.util.ArrayList<>(service.getWindows());
        windows.add(overlay);
        Shadows.shadowOf(service).setWindows(windows);
        AccessibilityEvent changed = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED);
        changed.setPackageName(app.getPackageName());
        ((org.robolectric.shadows.ShadowAccessibilityRecord) Shadow.extract(changed)).setWindowId(900);
        int roots = service.rootFetches;
        service.onAccessibilityEvent(changed);
        assertEquals("an own overlay is not a new Dasher screen", roots, service.rootFetches);
        changed.setPackageName("another.accessibility.service");
        shadow.setId(901);
        ((org.robolectric.shadows.ShadowAccessibilityRecord) Shadow.extract(changed)).setWindowId(901);
        service.onAccessibilityEvent(changed);
        assertTrue("unknown overlays still cause a fresh check", service.rootFetches > roots);
    }

    @Test
    public void navigationDistanceChangesUseQuietCadenceWithoutDelayingRealOfferSigns() {
        OfferFilterService service = service();
        AccessibilityNodeInfo nav = node(null, false);
        Shadows.shadowOf(nav).addChild(node("Turn left", false));
        Shadows.shadowOf(nav).addChild(node("9 mi", false));
        show(service, nav);
        int roots = service.rootFetches;
        for (int i = 0; i < 20; i++) service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
        assertTrue("a burst is coalesced while navigating", service.rootFetches - roots <= 1);
        show(service, offer("$7.90", "0:35"));
        assertEquals(DecisionLog.Action.DECLINE_TAPPED, DecisionLog.recent(app, 1).get(0).action);
    }

    private android.service.notification.StatusBarNotification notice() {
        android.app.Notification payload = new android.app.Notification.Builder(app, "source")
                .setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle("New Delivery!")
                .setContentText("New Order: Go to Balance Bowls").build();
        return new android.service.notification.StatusBarNotification("com.doordash.driverapp", "com.doordash.driverapp",
                3, "NEW_ORDER", 10001, 0, 0, payload, android.os.Process.myUserHandle(), System.currentTimeMillis());
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

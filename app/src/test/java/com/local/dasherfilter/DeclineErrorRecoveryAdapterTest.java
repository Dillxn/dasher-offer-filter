package com.local.dasherfilter;

import android.accessibilityservice.AccessibilityService;
import android.app.Application;
import android.app.Notification;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
import java.util.Arrays;
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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityRecord;
import org.robolectric.shadows.ShadowAccessibilityService;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

/** Synthetic toast delivery through the real service; actual Dasher/OEM toast exposure still needs phone testing. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, shadows = DeclineErrorRecoveryAdapterTest.GlobalActions.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DeclineErrorRecoveryAdapterTest {
    private static final String DASHER = "com.doordash.driverapp";
    private Application app;
    private ServiceController<OfferFilterService> controller;
    private OfferFilterService service;
    private AccessibilityNodeInfo decline;
    private long began;
    @Implements(AccessibilityService.class)
    public static class GlobalActions extends ShadowAccessibilityService {
        static boolean refuse;
        @Implementation @Override protected boolean performGlobalAction(int action) {
            super.performGlobalAction(action);
            return !refuse;
        }
    }
    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        DiagnosticLog.clear(app);
        DecisionLog.forgetCache(); DecisionLog.clear(app);
        RestartSuppression.clear(app);
        OfferSilencer.forgetCache();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        OfferFilterService.forgetScreenState();
        GlobalActions.refuse = false;
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        controller = Robolectric.buildService(OfferFilterService.class).create();
        service = controller.get(); service.onServiceConnected(); pass(0);
    }
    @After public void teardown() {
        OfferFilterService.nodeFetchForTests = null;
        controller.destroy();
        OfferFilterService.scanLooperForTests = null;
        OfferFilterService.forgetScreenState();
    }
    @Test public void exactToastAndStrandedMapBackThenRevalidateSameOfferWithinOriginalBudget() throws Exception {
        begin();
        long deadline = state().confirmationUntil();
        failOn(node("Map", false));
        pass(600);
        assertEquals(Arrays.asList(AccessibilityService.GLOBAL_ACTION_BACK), actions());
        assertEquals(DecisionLog.Action.DECLINE_TAPPED, DecisionLog.recent(app, 1).get(0).action);
        assertEquals(1, state().declineAttempts());
        show(offer("$7.90", remaining()));
        pass(1_500);
        assertEquals(1, clicks(decline));
        assertEquals(2, state().declineAttempts());
        assertEquals("Back and rereads never extend original authority", deadline, state().confirmationUntil());
        assertEquals(1, DecisionLog.recent(app, 10).size());
    }
    @Test public void errorAfterEightSecondHangStillRecoversWithoutDeadlineExtension() throws Exception {
        begin(); long deadline = state().confirmationUntil();
        show(node("Map", false)); pass(8_000);
        toast("ERROR!", DASHER, "android.widget.Toast", SystemClock.uptimeMillis(), false);
        pass(600);
        assertEquals(1, actions().size());
        assertEquals(deadline, state().confirmationUntil());
    }
    @Test public void repeatedErrorCyclesStopAfterTwoBacksAndDoNotResetAttemptCount() throws Exception {
        begin(); long deadline = state().confirmationUntil();
        for (int i = 0; i < 2; i++) {
            failOn(node("Map", false)); pass(600);
            assertEquals(i + 1, actions().size());
            AccessibilityNodeInfo returned = offer("$7.90", remaining());
            // Harmless static-label changes must not manufacture a new offer key/deadline or replenish Back budget.
            Shadows.shadowOf(returned).addChild(node("Store label variant " + i, false));
            show(returned); pass(1_500);
            assertEquals(i + 2, state().declineAttempts());
            assertEquals(deadline, state().confirmationUntil());
        }
        failOn(node("Map", false)); pass(1_000);
        assertEquals(2, actions().size());
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
    }
    @Test public void aReturnWithMissingOriginalNumericFactsCannotResumeItsDecline() {
        begin(); failOn(node("Map", false)); pass(600);
        AccessibilityNodeInfo partial = node("", false);
        AccessibilityNodeInfo nextDecline = node("Decline", true);
        Shadows.shadowOf(partial).addChild(nextDecline);
        Shadows.shadowOf(partial).addChild(node("$7.90", false));
        Shadows.shadowOf(partial).addChild(node("Accept", true));
        show(partial); pass(2_500);
        assertEquals(0, clicks(nextDecline));
        assertEquals(1, actions().size());
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
    }
    @Test public void loadingWithoutToastAndNearMissMessagesNeverAuthorizeBack() {
        begin(); show(node("Map", false));
        long now = SystemClock.uptimeMillis();
        toast("error", "com.example.other", "android.widget.Toast", now, false);
        toast("error", DASHER, "android.app.Notification", now, false);
        toast("error PRIVATE_SENTINEL", DASHER, "android.widget.Toast", now, false);
        toast("error", DASHER, "android.widget.Toast", began - 1, false);
        toast("error", DASHER, "android.widget.Toast", now, true);
        pass(1_000);
        assertTrue(actions().isEmpty());
        assertFalse(DiagnosticLog.read(app).contains("PRIVATE_SENTINEL"));
    }
    @Test public void actionableControlsAndAmbiguousIdleNeverBackOrClaimCompletion() {
        begin(); failOn(node("Finding offers", false)); pass(4_500);
        assertTrue(actions().isEmpty());
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
        assertFalse(DiagnosticLog.read(app).contains("returned to the idle screen after decline"));
    }
    @Test public void anUnknownClickableControlBlocksBack() {
        begin(); failOn(node("Map", true)); pass(1_000);
        assertTrue(actions().isEmpty());
    }
    @Test public void activeButUnfocusedDasherCannotReceiveGlobalBack() {
        begin(); failOn(node("Map", false));
        ((ShadowAccessibilityWindowInfo) Shadow.extract(service.getWindows().get(0))).setFocused(false);
        pass(600); assertTrue(actions().isEmpty());
    }
    @Test public void inactiveDasherHalfNeverBacksOutOfTheOtherApp() {
        begin(); failOn(node("Map", false));
        AccessibilityWindowInfo dasher = service.getWindows().get(0);
        ShadowAccessibilityWindowInfo ds = Shadow.extract(dasher);
        ds.setActive(false); ds.setFocused(false); ds.setBoundsInScreen(new Rect(0, 1040, 1080, 2040));
        AccessibilityNodeInfo other = node("Other", false); other.setPackageName(app.getPackageName());
        AccessibilityWindowInfo foreground = window(other, 200, true, new Rect(0, 0, 1080, 1000));
        AccessibilityWindowInfo divider = AccessibilityWindowInfo.obtain();
        ((ShadowAccessibilityWindowInfo) Shadow.extract(divider)).setType(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER);
        Shadows.shadowOf(service).setWindows(Arrays.asList(dasher, foreground, divider));
        Shadows.shadowOf(service).setRootInActiveWindow(other);
        pass(600); assertTrue(actions().isEmpty());
    }
    @Test public void aUserClickCancelsPendingBack() {
        begin(); failOn(node("Map", false));
        AccessibilityEvent touch = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
        touch.setPackageName(DASHER); touch.setEventTime(SystemClock.uptimeMillis());
        ((ShadowAccessibilityRecord) Shadow.extract(touch)).setSourceNode(node("Details", true));
        service.onAccessibilityEvent(touch); pass(600);
        assertTrue(actions().isEmpty());
    }
    @Test public void scaleChangeCancelsPendingBack() {
        begin(); failOn(node("Map", false));
        FilterStore.save(app, FilterStore.load(app).withMinimumScalePercent(97));
        pass(600); assertTrue(actions().isEmpty());
    }
    @Test public void screenOffCancelsPendingBack() {
        begin(); failOn(node("Map", false));
        Shadows.shadowOf(app.getSystemService(PowerManager.class)).setIsInteractive(false);
        app.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        pass(600); assertTrue(actions().isEmpty());
    }
    @Test public void interruptionDuringFreshMetadataCheckCancelsBack() {
        begin(); failOn(node("Map", false));
        final int[] calls = {0};
        service.windowSource = () -> {
            if (++calls[0] == 2) service.onInterrupt();
            return service.getWindows();
        };
        pass(600); assertTrue(actions().isEmpty());
    }
    @Test public void refusedBackLeavesOfferUnconfirmedAndNeverRetriesIt() {
        begin(); GlobalActions.refuse = true;
        failOn(node("Map", false)); pass(600);
        assertEquals(1, actions().size());
        show(offer("$7.90", remaining())); pass(3_000);
        assertEquals(0, clicks(decline));
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
    }
    @Test public void confirmationInsteadOfBlankUsesOnlyTheNormalDeclineButton() {
        begin(); failOn(node("Map", false));
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(question(confirm)); pass(600);
        assertEquals(1, clicks(confirm)); assertTrue(actions().isEmpty());
    }
    @Test public void errorAfterConfirmationIsUnconfirmedAndIdleDoesNotCorrectItToSuccess() {
        begin(); AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(question(confirm)); assertEquals(1, clicks(confirm));
        toast("error", DASHER, "android.widget.Toast", SystemClock.uptimeMillis(), false);
        show(node("Finding offers", false)); pass(2_000);
        assertTrue(actions().isEmpty());
        assertEquals(DecisionLog.Action.CONFIRMATION_NOT_TAPPED, DecisionLog.recent(app, 1).get(0).action);
    }
    @Test public void freshCountdownAfterBackBecomesANewOfferWithoutOldRecoveryAuthority() {
        begin(); failOn(node("Map", false)); pass(600);
        show(offer("$7.90", "0:55"));
        assertEquals(1, clicks(decline));
        assertEquals(2, DecisionLog.recent(app, 10).size());
        pass(300); show(node("Map", false)); pass(600);
        assertEquals("new offer needs its own error event", 1, actions().size());
    }
    @Test public void installedServiceSubscribesToToastEvents() throws Exception {
        int resource = app.getResources().getIdentifier("accessibility_service_config", "xml", app.getPackageName());
        assertTrue(resource != 0);
        android.content.res.XmlResourceParser xml = app.getResources().getXml(resource);
        try {
            while (xml.next() != org.xmlpull.v1.XmlPullParser.START_TAG) { }
            int events = xml.getAttributeIntValue("http://schemas.android.com/apk/res/android", "accessibilityEventTypes", 0);
            assertTrue((events & AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) != 0);
        } finally { xml.close(); }
    }
    @Test public void anUnacceptedNoticePreventsToastReadsAndRecovery() {
        begin();
        ConsentedTestApp.forget(app);
        int roots = service.rootFetches;
        toast("error", DASHER, "android.widget.Toast", SystemClock.uptimeMillis(), false);
        pass(600);
        assertEquals(roots, service.rootFetches);
        assertTrue(actions().isEmpty());
    }
    private void begin() { began = SystemClock.uptimeMillis(); show(offer("$7.90", "0:35")); assertEquals(1, clicks(decline)); pass(300); }
    private void failOn(AccessibilityNodeInfo root) {
        // The toast can precede the stalled frame, so it must not be mistaken for a window transition.
        toast("Error.", DASHER, "android.widget.Toast", SystemClock.uptimeMillis(), false);
        show(root);
    }
    private String remaining() { return String.format(java.util.Locale.US, "0:%02d", Math.max(1, 35 - (SystemClock.uptimeMillis() - began) / 1000)); }
    private void toast(String text, String pkg, String clazz, long at, boolean notification) {
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
        event.setPackageName(pkg); event.setClassName(clazz); event.setEventTime(at); event.getText().add(text);
        if (notification) event.setParcelableData(new Notification());
        service.onAccessibilityEvent(event); pass(0);
    }
    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo n = AccessibilityNodeInfo.obtain(new View(app));
        n.setPackageName(DASHER); n.setText(text); n.setVisibleToUser(true); n.setEnabled(true); n.setClickable(clickable);
        if (clickable) { n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK); Shadows.shadowOf(n).setOnPerformActionListener((a, b) -> true); }
        return n;
    }
    private AccessibilityNodeInfo offer(String pay, String countdown) {
        AccessibilityNodeInfo n = node("", false); decline = node("Decline", true);
        for (AccessibilityNodeInfo c : new AccessibilityNodeInfo[]{decline, node(pay, false), node("2 stops (7.2 mi) • 21 min", false), node("Accept", true), node(countdown, false)}) Shadows.shadowOf(n).addChild(c);
        return n;
    }
    private AccessibilityNodeInfo question(AccessibilityNodeInfo confirm) { AccessibilityNodeInfo n = node("Are you sure you want to decline this offer?", false); Shadows.shadowOf(n).addChild(confirm); return n; }
    private void show(AccessibilityNodeInfo root) {
        TestWindows.full(service, root);
        ((ShadowAccessibilityWindowInfo) Shadow.extract(service.getWindows().get(0))).setFocused(true);
        AccessibilityEvent e = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED); e.setPackageName(DASHER);
        service.onAccessibilityEvent(e); pass(0);
    }
    private AccessibilityWindowInfo window(AccessibilityNodeInfo root, int id, boolean active, Rect bounds) {
        AccessibilityWindowInfo w = AccessibilityWindowInfo.obtain(); ShadowAccessibilityWindowInfo s = Shadow.extract(w);
        s.setId(id); s.setType(AccessibilityWindowInfo.TYPE_APPLICATION); s.setRoot(root); s.setActive(active); s.setFocused(active); s.setBoundsInScreen(bounds); return w;
    }
    private DeclineState state() throws Exception { java.lang.reflect.Field f = OfferFilterService.class.getDeclaredField("declineState"); f.setAccessible(true); return (DeclineState) f.get(service); }
    private List<Integer> actions() { return Shadows.shadowOf(service).getGlobalActionsPerformed(); }
    private static int clicks(AccessibilityNodeInfo n) { return Shadows.shadowOf(n).getPerformedActions().size(); }
    private static void pass(long ms) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }
}

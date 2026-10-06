package com.local.dasherfilter;

import static org.junit.Assert.*;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.lang.reflect.Field;
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
import org.robolectric.shadows.ShadowSystemClock;

/** Production scanner and stores with synthetic Android windows; no handset/dispatch behavior is claimed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public final class EarningsAdjustmentAdapterTest {
    private Application app;
    private ServiceController<OfferFilterService> controller;
    private OfferFilterService service;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(Consent.PREFS, Context.MODE_PRIVATE).edit()
                .putInt(Consent.ACCEPTED_VERSION, Consent.VERSION).commit();
        Updater.setEnabled(app, false);
        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0));
        FilterStore.setAutoAcceptEnabled(app, false);
        ActiveRouteStore.clear(app); RestartSuppression.clear(app); AutoAcceptMemory.clear(app);
        DecisionLog.forgetCache(); DecisionLog.clear(app); OfferSilencer.forgetCache();
        EarningsStore.clearHistory(app);
        assertTrue(EarningsStore.saveConfig(app, new EarningsModel.Config(true, 0.0, 90, 110)));
        QualifyingWaitStore.wallClock = () -> 1_800_000_000_000L + SystemClock.elapsedRealtime();
        seedHistory(24, 0);
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        OfferFilterService.forgetScreenState();
        advance(1_000);
        connect();
    }

    @After public void cleanup() {
        OfferFilterService.nodeFetchForTests = null;
        if (controller != null) controller.destroy();
        OfferFilterService.scanLooperForTests = null;
        OfferFilterService.forgetScreenState();
        QualifyingWaitStore.flush(); QualifyingWaitStore.clear(app);
        QualifyingWaitStore.wallClock = System::currentTimeMillis;
        EarningsStore.clearHistory(app);
    }

    @Test public void onlyStableOrdinaryWaitingAppliesOneBoundedStepAndCooldown() {
        assertReady();
        waiting(); assertScale(100);
        advance(1_100); content(); assertScale(105);
        for (int i = 0; i < 4; i++) { advance(1_100); content(); }
        assertScale(105);
        assertEquals(0, DecisionLog.recent(app, 200).size());
        assertNull(ActiveRouteStore.load(app));
    }

    @Test public void existingHeartbeatsNeverAdjustOrReadExtraContent() {
        waiting();
        int[] fetches = {0};
        OfferFilterService.nodeFetchForTests = () -> fetches[0]++;
        for (int i = 0; i < 10; i++) advance(500);
        assertScale(100); assertEquals(0, fetches[0]);
        content(); assertScale(105);
    }

    @Test public void notificationTriggeredCheckCannotAdjustOrProvideStableWaiting() {
        waiting(); advance(1_100);
        OfferFilterService.requestCheckFromNotification(); idle(); assertScale(100);
        content(); assertScale(100);
        advance(1_100); content(); assertScale(105);
    }

    @Test public void optInOffAndMissingCostNeverAdjust() {
        EarningsStore.saveConfig(app, EarningsModel.Config.defaults());
        waiting(); advance(1_100); content(); assertScale(100);
        EarningsStore.saveConfig(app, new EarningsModel.Config(true, null, 90, 110));
        content(); advance(1_100); content(); assertScale(100);
    }

    @Test public void sparseAndStaleHistoryNeverAdjust() {
        seedHistory(4, 0); waiting(); advance(1_100); content(); assertScale(100);
        seedHistory(24, QualifyingWait.RETAIN_MS); content(); advance(1_100); content(); assertScale(100);
    }

    @Test public void incompleteAndUnknownScreensInterruptTheWaitingInterval() {
        waiting(); advance(1_100);
        show(node("Loading", false)); assertScale(100);
        waiting(); advance(1_100); content(); assertScale(105);
    }

    @Test public void itemOnlyOfferEvidenceOverWaitingNeverAdjusts() {
        waiting(); advance(1_100);
        AccessibilityNodeInfo root = node("Finding offers", false);
        Shadows.shadowOf(root).addChild(node("Shop and deliver", false));
        Shadows.shadowOf(root).addChild(node("5 items", false));
        show(root); advance(1_100); content(); assertScale(100);
    }

    @Test public void aVisiblePassingOfferAndItsAcceptAuthorityNeverAdjust() {
        waiting(); advance(1_100);
        AccessibilityNodeInfo root = node("", false);
        for (String text : new String[]{"$30.00", "2 stops (2 mi) • 15 min", "0:30"}) {
            Shadows.shadowOf(root).addChild(node(text, false));
        }
        AccessibilityNodeInfo accept = node("Accept", true), decline = node("Decline", true);
        Shadows.shadowOf(root).addChild(accept); Shadows.shadowOf(root).addChild(decline);
        show(root); advance(1_100); content(); assertScale(100);
        assertEquals(0, Shadows.shadowOf(accept).getPerformedActions().size());
        assertEquals(0, Shadows.shadowOf(decline).getPerformedActions().size());
    }

    @Test public void routeAndContradictoryWaitingCannotAdjust() {
        waiting(); advance(1_100);
        ActiveRouteStore.save(app, new OfferSnapshot(3000, 2.0, 15, 2));
        AccessibilityNodeInfo root = node("Finding offers", false);
        Shadows.shadowOf(root).addChild(node("Arrived at store", true));
        show(root); advance(1_100); content(); assertScale(100);
        assertNotNull(ActiveRouteStore.load(app));
    }

    @Test public void hiddenLockedPausedAndDisabledStatesNeverAdjust() {
        waiting(); advance(1_100);
        Shadows.shadowOf(service).setWindows(Collections.emptyList());
        Shadows.shadowOf(service).setRootInActiveWindow(null);
        content(); assertScale(100);
        waiting();
        Shadows.shadowOf(app.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        advance(1_100); content(); assertScale(100);
        Shadows.shadowOf(app.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        show(node("Your dash is paused", false)); advance(1_100); content(); assertScale(100);
        FilterStore.save(app, FilterStore.load(app).withEnabled(false));
        waiting(); advance(1_100); content(); assertScale(100);
    }

    @Test public void consentLostDuringReadCannotAdjust() {
        waiting(); advance(1_100);
        duringNextFetch(() -> app.getSharedPreferences(Consent.PREFS, 0).edit().clear().commit());
        content(); assertScale(100);
    }

    @Test public void userTouchDuringReadCannotAdjustAndRestartsStability() {
        waiting(); advance(1_100);
        duringNextFetch(() -> OfferFilterService.ownScreenTouched(SystemClock.uptimeMillis()));
        content(); assertScale(100);
        advance(1_100); content(); assertScale(100);
        advance(1_100); content(); assertScale(105);
    }

    @Test public void settingsChangedAndChangedBackDuringReadInvalidatesCapturedGeneration() {
        waiting(); advance(1_100);
        duringNextFetch(() -> {
            FilterSettings original = FilterStore.load(app);
            FilterStore.save(app, original.withMinimumScalePercent(109));
            FilterStore.save(app, original);
        });
        content(); assertScale(100);
        advance(1_100); content(); assertScale(100);
        advance(1_100); content(); assertScale(105);
    }

    @Test public void hiddenDuringFinalMetadataWithoutAnAccessibilityEventCannotAdjust() {
        waiting(); advance(1_100);
        boolean[] checked = {false};
        service.windowSource = () -> {
            if (calledFrom("saveScaleIfUnchanged")) { checked[0] = true; return Collections.emptyList(); }
            return service.getWindows();
        };
        content(); assertTrue(checked[0]); assertScale(100);
    }

    @Test public void manualChangeDuringFinalMetadataCannotBeOverwritten() {
        waiting(); advance(1_100);
        boolean[] changed = {false};
        service.windowSource = () -> {
            if (!changed[0] && calledFrom("saveScaleIfUnchanged")) {
                changed[0] = true;
                FilterStore.save(app, FilterStore.load(app).withMinimumScalePercent(102));
            }
            return service.getWindows();
        };
        content(); assertTrue(changed[0]); assertScale(102);
    }

    @Test public void waitingHistoryClearedDuringFinalMetadataCannotCommit() {
        waiting(); advance(1_100);
        boolean[] cleared = {false};
        service.windowSource = () -> {
            if (!cleared[0] && calledFrom("saveScaleIfUnchanged")) {
                cleared[0] = true; QualifyingWaitStore.clear(app);
            }
            return service.getWindows();
        };
        content(); assertTrue(cleared[0]); assertScale(100);
    }

    @Test public void configChangeBackDuringFinalMetadataCannotCommit() {
        waiting(); advance(1_100);
        boolean[] changed = {false};
        service.windowSource = () -> {
            if (!changed[0] && calledFrom("saveScaleIfUnchanged")) {
                changed[0] = true;
                EarningsStore.saveConfig(app, new EarningsModel.Config(false, 0.0, 90, 110));
                EarningsStore.saveConfig(app, new EarningsModel.Config(true, 0.0, 90, 110));
            }
            return service.getWindows();
        };
        content(); assertTrue(changed[0]); assertScale(100);
    }

    @Test public void configChangedBackDuringReadCannotReuseStableWaiting() {
        waiting(); advance(1_100);
        duringNextFetch(() -> {
            EarningsStore.saveConfig(app, new EarningsModel.Config(false, 0.0, 90, 110));
            EarningsStore.saveConfig(app, new EarningsModel.Config(true, 0.0, 90, 110));
        });
        content(); assertScale(100);
        advance(1_100); content(); assertScale(100);
        advance(1_100); content(); assertScale(105);
    }

    @Test public void notificationGenerationChangedDuringReadInvalidatesWaiting() {
        waiting(); advance(1_100);
        duringNextFetch(() -> {
            try {
                Field field = OfferNotificationService.class.getDeclaredField("generation");
                field.setAccessible(true); field.setLong(null, field.getLong(null) + 1);
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        content(); assertScale(100);
    }

    @Test public void interruptionDuringReadInvalidatesWaiting() {
        waiting(); advance(1_100); duringNextFetch(service::onInterrupt);
        content(); assertScale(100);
    }

    @Test public void armedAutomaticAcceptanceAndDeclineConfirmationBlockAtReadStart() throws Exception {
        waiting(); advance(1_100);
        OfferSnapshot offer = new OfferSnapshot(3000, 2.0, 15, 2);
        ((AutoAccept) get("autoAccept")).observe(offer, "synthetic-offer", FilterStore.load(app), 30,
                OfferNotificationService.generation(), SystemClock.uptimeMillis(), true);
        content(); assertScale(100);
        waiting(); advance(1_100);
        ((DeclineState) get("declineState")).declineSent("synthetic-offer", SystemClock.uptimeMillis(), 30_000);
        ((DeclineEpisode) get("episode")).declined("synthetic-offer", offer, SystemClock.uptimeMillis());
        content(); assertScale(100);
    }

    @Test public void manualScaleChangedDuringReadWins() {
        waiting(); advance(1_100);
        duringNextFetch(() -> FilterStore.save(app, FilterStore.load(app).withMinimumScalePercent(103)));
        content(); assertScale(103);
    }

    @Test public void staleSlowReadNeverAdjusts() {
        waiting(); advance(1_100);
        duringNextFetch(() -> ShadowSystemClock.advanceBy(Duration.ofMillis(2_100)));
        content(); assertScale(100);
    }

    @Test public void failedReadNeverAdjustsFromPartiallyCollectedWaitingLabels() {
        waiting(); advance(1_100);
        duringNextFetch(() -> { throw new IllegalStateException("synthetic unreadable node"); });
        content(); assertScale(100);
        assertFalse(QualifyingWaitStore.observingWaiting(app));
    }

    @Test public void pendingPeekAndTakeoverAtReadStartCannotAdjust() throws Exception {
        waiting(); advance(1_100);
        set("peekReturning", true); content(); assertScale(100); set("peekReturning", false);
        waiting(); advance(1_100);
        Class<?> type = Class.forName("com.local.dasherfilter.OfferFilterService$Takeover");
        java.lang.reflect.Constructor<?> make = type.getDeclaredConstructor(OfferSnapshot.class, long.class);
        make.setAccessible(true);
        set("takeover", make.newInstance(new OfferSnapshot(3000, 2.0, 15, 2), SystemClock.uptimeMillis()));
        content(); assertScale(100);
    }

    @Test public void restartRestoresNumericHistoryButNeverStableWaiting() {
        waiting(); advance(1_100);
        controller.destroy(); QualifyingWaitStore.flush(); QualifyingWaitStore.forgetCache();
        connect(); waiting(); assertScale(100);
        advance(1_100); content(); assertScale(105);
    }

    @Test public void clearingHistoryDuringReadCannotApplyAnOldRecommendation() {
        waiting(); advance(1_100);
        duringNextFetch(() -> { QualifyingWaitStore.clear(app); EarningsStore.clearHistory(app); });
        content(); assertScale(100);
    }

    private void connect() {
        controller = Robolectric.buildService(OfferFilterService.class).create();
        service = controller.get(); service.onServiceConnected(); idle();
    }
    private void assertReady() {
        EarningsModel.Recommendation result = EarningsStore.recommendation(app, QualifyingWaitStore.snapshot(app),
                FilterStore.load(app), QualifyingWaitStore.wallClock.getAsLong());
        assertTrue(result.detail(), result.canAdjust()); assertEquals(105, result.suggestedPercent);
    }
    private void seedHistory(int count, long age) {
        QualifyingWaitStore.flush(); QualifyingWaitStore.clear(app);
        long now = QualifyingWaitStore.wallClock.getAsLong() - age;
        List<QualifyingWait.Sample> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) rows.add(new QualifyingWait.Sample(now - (count - i) * 120_000L, 120_000,
                new OfferSnapshot(i % 2 == 0 ? 1040 : 3000, 2.0, i % 2 == 0 ? 60 : 15, 2)));
        app.getSharedPreferences("qualifying-wait", 0).edit()
                .putString("numeric-history-v1", QualifyingWaitStore.encode(rows)).commit();
        QualifyingWaitStore.forgetCache();
    }
    private void duringNextFetch(Runnable work) {
        OfferFilterService.nodeFetchForTests = () -> { OfferFilterService.nodeFetchForTests = null; work.run(); };
    }
    private Object get(String name) throws Exception {
        Field field = OfferFilterService.class.getDeclaredField(name); field.setAccessible(true); return field.get(service);
    }
    private void set(String name, Object value) throws Exception {
        Field field = OfferFilterService.class.getDeclaredField(name); field.setAccessible(true); field.set(service, value);
    }
    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(new View(app));
        node.setPackageName("com.doordash.driverapp"); node.setText(text); node.setVisibleToUser(true);
        node.setEnabled(true); node.setClickable(clickable);
        if (clickable) {
            node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            Shadows.shadowOf(node).setOnPerformActionListener((action, args) -> true);
        }
        return node;
    }
    private void waiting() {
        AccessibilityNodeInfo root = node("Finding offers", false);
        Shadows.shadowOf(root).addChild(node("You're in a good place to wait for offers", false));
        show(root);
    }
    private void show(AccessibilityNodeInfo root) { TestWindows.full(service, root); event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED); }
    private void content() { event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED); }
    private void event(int type) {
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setPackageName("com.doordash.driverapp"); event.setEventTime(SystemClock.uptimeMillis());
        service.onAccessibilityEvent(event); idle();
    }
    private static boolean calledFrom(String name) {
        for (StackTraceElement item : Thread.currentThread().getStackTrace()) {
            if (name.equals(item.getMethodName())) return true;
        }
        return false;
    }
    private void assertScale(int expected) { assertEquals(expected, FilterStore.load(app).minimumScalePercent); }
    private void advance(long ms) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
}

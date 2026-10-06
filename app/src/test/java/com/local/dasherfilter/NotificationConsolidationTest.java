package com.local.dasherfilter;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowNotificationListenerService;

import static org.junit.Assert.*;

/** Native-first presentation: never dismiss another app's passing/unknown notification. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35, 36}, shadows = NotificationConsolidationTest.NativeNotifications.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class NotificationConsolidationTest extends AndroidAdapterTestBase {
    private ServiceController<OfferNotificationService> controller;
    private OfferNotificationService listener;
    private NativeNotifications nativeAlerts;

    @Before public void start() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        FilterStore.setPeek(app, false);
        ComponentName home = new ComponentName("com.doordash.driverapp", "com.doordash.driverapp.Home");
        org.robolectric.shadows.ShadowPackageManager packages = Shadows.shadowOf(app.getPackageManager());
        android.content.pm.ActivityInfo activity = packages.addActivityIfNotPresent(home);
        activity.enabled = activity.exported = true;
        IntentFilter launcher = new IntentFilter(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        packages.addIntentFilterForActivity(home, launcher);
        controller = Robolectric.buildService(OfferNotificationService.class).create();
        listener = controller.get();
        nativeAlerts = Shadow.extract(listener);
        listener.onListenerConnected();
    }

    @After public void stop() { controller.destroy(); }

    private PendingIntent tap() {
        PendingIntent tap = PendingIntent.getActivity(app, 7, new Intent().setComponent(
                new ComponentName("com.doordash.driverapp", "com.doordash.driverapp.Home")),
                PendingIntent.FLAG_IMMUTABLE);
        Shadows.shadowOf(tap).setCreatorPackage("com.doordash.driverapp");
        return tap;
    }

    private StatusBarNotification source(boolean tappable, boolean passing, long at) {
        Notification.Builder builder = new Notification.Builder(app, "source")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("New Delivery!").setContentText("New Order: Go to Store A");
        if (tappable) builder.setContentIntent(tap());
        if (passing) builder.setStyle(new Notification.InboxStyle().addLine("$25.00")
                .addLine("2 stops (7.2 mi) • 21 min"));
        return new StatusBarNotification("com.doordash.driverapp", "com.doordash.driverapp", 3, "NEW_ORDER",
                10001, 0, 0, builder.build(), android.os.Process.myUserHandle(), at);
    }

    private StatusBarNotification post(boolean tappable, boolean passing) {
        StatusBarNotification source = source(tappable, passing, System.currentTimeMillis());
        nativeAlerts.addActiveNotification(source);
        listener.onNotificationPosted(source, heard(source));
        return source;
    }

    private NotificationListenerService.RankingMap heard(StatusBarNotification source) {
        try {
            return SameOfferAdapterTest.ranking(source,
                    new NotificationChannel("source", "Dasher", NotificationManager.IMPORTANCE_HIGH),
                    NotificationManager.IMPORTANCE_HIGH, true, source.getPostTime());
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    @Test public void silentNativeStillGetsTheExistingReviewBell() {
        StatusBarNotification source = source(true, false, System.currentTimeMillis());
        nativeAlerts.addActiveNotification(source);
        listener.onNotificationPosted(source, null);
        assertEquals(1, notifications().size());
        assertEquals(DecisionLog.Action.CHECK_BELL, DecisionLog.recent(app, 1).get(0).action);
        assertEquals(0, nativeAlerts.cancellations);
    }

    @Test public void silentNativeStillGetsTheSelectivePassingChime() {
        StatusBarNotification source = source(true, true, System.currentTimeMillis());
        nativeAlerts.addActiveNotification(source);
        listener.onNotificationPosted(source, null);
        assertEquals(1, notifications().size());
        assertEquals(OfferAlerts.CHANNEL_ID, notifications().getAllNotifications().get(0).getChannelId());
        assertEquals(DecisionLog.Action.BELL, DecisionLog.recent(app, 1).get(0).action);
        assertEquals(0, nativeAlerts.cancellations);
    }

    private void nativeOnly(StatusBarNotification source) {
        assertEquals(1, listener.getActiveNotifications(new String[] {source.getKey()}).length);
        assertEquals("the generic duplicate is omitted", 0, notifications().size());
        assertEquals("no native dismissal or its deleteIntent can run", 0, nativeAlerts.cancellations);
    }

    @Test public void aTappableUnknownOfferKeepsOnlyItsNativeNotification() {
        OfferNotificationService.forgetDeclineAction();
        nativeOnly(post(true, false));
        assertEquals(OfferRule.Result.REVIEW, DecisionLog.recent(app, 1).get(0).result);
        assertEquals(DecisionLog.Action.NATIVE_ALERT, DecisionLog.recent(app, 1).get(0).action);
        assertFalse(OfferNotificationService.declineActionWithin(60_000));
        assertTrue("the updater still sees this tracked offer", OfferNotificationService.hasActiveOffer());
    }

    @Test public void aTappablePassingOfferDoesNotGetADuplicateGenericCard() {
        nativeOnly(post(true, true));
        assertEquals(OfferRule.Result.KEEP, DecisionLog.recent(app, 1).get(0).result);
    }

    @Test public void anUntappableNativeRetainsTheExistingOwnCardFallback() {
        StatusBarNotification source = post(false, false);
        assertEquals(1, notifications().size());
        assertEquals(1, listener.getActiveNotifications(new String[] {source.getKey()}).length);
        assertEquals(0, nativeAlerts.cancellations);
    }

    @Test public void unavailableNativeListingRetainsOwnFallbackAndNeverDismissesNative() {
        nativeAlerts.failList = true;
        StatusBarNotification source = post(true, false);
        nativeAlerts.failList = false;
        assertEquals(1, notifications().size());
        assertEquals(1, listener.getActiveNotifications(new String[] {source.getKey()}).length);
        assertEquals(0, nativeAlerts.cancellations);
    }

    @Test public void blockedOwnNotificationsStillLeaveTheNativeAlert() {
        notifications().setNotificationsEnabled(false);
        nativeOnly(post(true, false));
    }

    @Test public void reconnectCannotLoseTheLastAlert() {
        StatusBarNotification source = post(true, false);
        listener.onListenerDisconnected();
        assertFalse("no automatic Peek authority survives disconnect", OfferNotificationService.hasActiveOffer());
        nativeOnly(source);
        listener.onListenerConnected();
        nativeOnly(source);
        assertTrue(OfferNotificationService.hasActiveOffer());
    }

    @Test public void destructionCannotRemoveTheNativeAlert() {
        StatusBarNotification source = post(true, false);
        listener.onDestroy();
        nativeOnly(source);
    }

    @Test public void aRuleChangeReevaluatesWithoutClearingTheLastAlert() {
        StatusBarNotification source = post(true, true);
        FilterStore.save(app, new FilterSettings(false, 4000, 0, 0, 0, 0));
        OfferNotificationService.rulesChanged();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        nativeOnly(source);
        assertEquals("paused rules do not leave stale passing wording", OfferRule.Result.REVIEW,
                DecisionLog.recent(app, 1).get(0).result);
    }

    @Test public void anUnchangedUpdateCanDropAFallbackOnceNativeIsVerified() {
        nativeAlerts.failList = true;
        StatusBarNotification source = post(true, false);
        nativeAlerts.failList = false;
        assertEquals(1, notifications().size());
        listener.onNotificationPosted(source, heard(source));
        nativeOnly(source);
    }

    @Test public void anUnchangedNativeCoveredOfferRegainsFallbackIfItsTapDisappears() {
        StatusBarNotification source = post(true, false);
        nativeOnly(source);
        source.getNotification().contentIntent = null;
        listener.onNotificationPosted(source, heard(source));
        assertEquals(1, notifications().size());
        Notification fallback = notifications().getAllNotifications().get(0);
        assertNotNull("the app's launcher fallback still opens Dasher", fallback.contentIntent);
        assertEquals("this offer was already heard", Notification.GROUP_ALERT_SUMMARY,
                fallback.getGroupAlertBehavior());
        assertEquals(0, nativeAlerts.cancellations);
    }

    @Test public void anUnchangedNativeCoveredOfferRegainsFallbackWhenListingIsUnknown() {
        StatusBarNotification source = post(true, false);
        nativeAlerts.failList = true;
        listener.onNotificationPosted(source, null);
        assertEquals(1, notifications().size());
        assertEquals(Notification.GROUP_ALERT_SUMMARY,
                notifications().getAllNotifications().get(0).getGroupAlertBehavior());
        assertEquals(0, nativeAlerts.cancellations);
    }

    @Test public void anUnchangedSilentNativeCannotCancelTheSoleRequestedOwnBell() {
        StatusBarNotification source = source(true, false, System.currentTimeMillis());
        nativeAlerts.addActiveNotification(source);
        listener.onNotificationPosted(source, null);
        Notification first = notifications().getAllNotifications().get(0);
        listener.onNotificationPosted(source, null);
        assertEquals(1, notifications().size());
        assertSame("do not cancel or repost the queued sole bell", first, notifications().getAllNotifications().get(0));
        assertEquals(0, nativeAlerts.cancellations);
    }

    @Test public void aNativePostWithADeletionCallbackIsNeverDismissed() {
        StatusBarNotification source = source(true, false, System.currentTimeMillis());
        source.getNotification().deleteIntent = PendingIntent.getBroadcast(app, 8,
                new Intent("native-dismissal"), PendingIntent.FLAG_IMMUTABLE);
        nativeAlerts.addActiveNotification(source);
        listener.onNotificationPosted(source, heard(source));
        nativeOnly(source);
    }

    @Test public void newerNativePostCannotBeCanceledByAnOlderCallback() {
        StatusBarNotification older = source(true, false, System.currentTimeMillis() - 1_000);
        StatusBarNotification newer = source(true, true, System.currentTimeMillis());
        nativeAlerts.addActiveNotification(newer);
        listener.onNotificationPosted(older, heard(older));
        assertEquals("uncertain old callback uses the existing fallback", 1, notifications().size());
        listener.onNotificationPosted(newer, heard(newer));
        nativeOnly(newer);
    }

    @Test public void genuineNativeRemovalStillEndsTheTrackedOffer() {
        StatusBarNotification source = post(true, false);
        listener.onNotificationRemoved(source, null, NotificationListenerService.REASON_APP_CANCEL);
        assertFalse(OfferNotificationService.hasActiveOffer());
        assertEquals(0, notifications().size());
    }

    @Test public void boundedTrackingStillExpiresWithoutTouchingNative() {
        StatusBarNotification source = post(true, false);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(OfferAlertState.LIFETIME_MS));
        assertFalse(OfferNotificationService.hasActiveOffer());
        nativeOnly(source);
    }

    @Implements(NotificationListenerService.class)
    public static class NativeNotifications extends ShadowNotificationListenerService {
        boolean failList;
        int cancellations;
        @Implementation protected StatusBarNotification[] getActiveNotifications(String[] keys, int trim) {
            if (failList) throw new SecurityException("native listing unavailable");
            return super.getActiveNotifications(keys, trim);
        }
        @Implementation protected void cancelNotification(String key) {
            cancellations++;
            super.cancelNotification(key);
        }
    }
}

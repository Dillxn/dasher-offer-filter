package com.local.dasherfilter;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.graphics.Insets;
import android.os.Build;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import java.time.Duration;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowSystemClock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Notification, job, settings-screen and diagnostics behavior exercised through real Android adapters. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,35})
@LooperMode(LooperMode.Mode.PAUSED)
public class AndroidAdapterTest {
    /** Notification id used for offer alert cards. */
    private static final int ALERT_NOTIFICATION_ID = 8241;
    /** Job id of the one-shot update retry job. */
    private static final int UPDATE_RETRY_JOB_ID = 7243;
    /** Notification id of the updater's install-confirmation notice. */
    private static final int UPDATE_NOTICE_ID = 7242;

    private Application app;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        Updater.setEnabled(app, false);
        OfferAlerts.ensureChannel(app);
    }

    @Test
    public void unclassifiedNotificationUsesSilentChannelAndDoesNotLaunch() {
        assertTrue(OfferAlerts.notifyOffer(app, "review", null, OfferRule.Result.REVIEW, "Missing pay", false));

        Notification notification = notifications().getNotification("review", ALERT_NOTIFICATION_ID);
        assertNotNull(notification);
        assertEquals(OfferAlerts.REVIEW_CHANNEL_ID, notification.getChannelId());
        assertEquals(Notification.GROUP_ALERT_SUMMARY, notification.getGroupAlertBehavior());

        // The review channel is silent, and posting the card starts no activity.
        NotificationChannel channel = app.getSystemService(NotificationManager.class)
                .getNotificationChannel(notification.getChannelId());
        assertNull(channel.getSound());
        assertFalse(channel.shouldVibrate());
        assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }

    @Test
    public void blockedNotificationsReturnFailureInsteadOfClaimingReplacement() {
        notifications().setNotificationsEnabled(false);
        assertFalse(OfferAlerts.notifyOffer(app, "denied", null, OfferRule.Result.KEEP, "Pass", true));
        assertNull(notifications().getNotification("denied", ALERT_NOTIFICATION_ID));
    }

    @Test
    public void notificationRemovalClearsOnlyItsOwnReplacement() {
        OfferAlerts.notifyOffer(app, "a", null, OfferRule.Result.REVIEW, "A", false);
        OfferAlerts.notifyOffer(app, "b", null, OfferRule.Result.REVIEW, "B", false);
        OfferAlerts.clear(app, "a");
        assertNull(notifications().getNotification("a", ALERT_NOTIFICATION_ID));
        assertNotNull(notifications().getNotification("b", ALERT_NOTIFICATION_ID));
    }

    @Test
    public void realPayloadProducesReviewCardWithoutOpeningDasher() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        ServiceController<OfferNotificationService> controller =
                Robolectric.buildService(OfferNotificationService.class).create();
        try {
            Notification payload = new Notification.Builder(app, "source")
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle("New Delivery!")
                    .setContentText("New Order: Go to Chick-fil-A")
                    .build();
            StatusBarNotification source = new StatusBarNotification(
                    "com.doordash.driverapp", "com.doordash.driverapp", 3, "NEW_ORDER", 10001, 0, 0,
                    payload, android.os.Process.myUserHandle(), System.currentTimeMillis());

            // The first post produces exactly one review card and starts no activity.
            controller.get().onNotificationPosted(source, null);
            List<Notification> posted = notifications().getAllNotifications();
            assertEquals(1, posted.size());
            assertEquals(OfferAlerts.REVIEW_CHANNEL_ID, posted.get(0).getChannelId());
            assertNull(Shadows.shadowOf(app).getNextStartedActivity());

            // Reposting the same source notification does not add a second card.
            controller.get().onNotificationPosted(source, null);
            assertEquals(1, notifications().size());

            // Removing the source notification removes the card.
            controller.get().onNotificationRemoved(source);
            assertEquals(0, notifications().size());
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void retryCreatesAnActualAndroidOneShotJob() {
        Updater.setEnabled(app, true);
        Updater.retry(app, 60000L);

        JobInfo job = app.getSystemService(JobScheduler.class).getPendingJob(UPDATE_RETRY_JOB_ID);
        assertNotNull(job);
        assertFalse(job.isPeriodic());
        assertEquals(60000L, job.getMinLatencyMillis());
    }

    @Test
    public void pauseButtonPersistsWithoutPressingSave() {
        FilterStore.save(app, new FilterSettings(true, 2000, 150, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).create()) {
            View content = activity.get().findViewById(android.R.id.content);
            View button = findButton(content, "Pause auto-decline immediately");
            assertNotNull(button);
            button.performClick();
            assertFalse(FilterStore.load(app).enabled);
            assertEquals(2000, FilterStore.load(app).flatCents);
        }
    }

    @Test
    public void rawCaptureExpiresAndReportStillIncludesUpdateState() {
        DiagnosticLog.setEnabled(app, true);
        assertTrue(DiagnosticLog.isEnabled(app));

        // Once the capture window has passed, raw capture reports itself disabled.
        app.getSharedPreferences("offer_filter_diagnostics", Context.MODE_PRIVATE)
                .edit()
                .putLong("until", System.currentTimeMillis() - 1)
                .commit();
        assertFalse(DiagnosticLog.isEnabled(app));

        // The diagnostic report still carries updater status.
        Updater.status(app, "Synthetic updater error for test");
        assertTrue(DiagnosticLog.report(app).contains("Synthetic updater error for test"));
    }

    @Test
    public void passingReplayCannotAcquireANewBellOnLaterUpdate() {
        OfferAlertState state = new OfferAlertState(1, 1);
        state.delivered("first", OfferRule.Result.KEEP, false);
        assertFalse(state.shouldRing(OfferRule.Result.KEEP, false, false));
    }

    @Test
    public void reviewCardSaysWhichEvidenceIsMissing() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        ServiceController<OfferNotificationService> controller =
                Robolectric.buildService(OfferNotificationService.class).create();
        try {
            controller.get().onNotificationPosted(doorDashOffer("New Order: Go to Chick-fil-A"), null);
            Notification card = notifications().getAllNotifications().get(0);
            assertEquals("Not classified: pay not found. Tap to open Dasher. No automatic decline or screen takeover.",
                    card.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void freshOfferOnAReusedKeyIsAnnouncedEvenWhenTheExpiryCallbackIsLate() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        ServiceController<OfferNotificationService> controller =
                Robolectric.buildService(OfferNotificationService.class).create();
        try {
            controller.get().onNotificationPosted(doorDashOffer("New Order: Go to Chick-fil-A"), null);
            assertEquals(1, notifications().size());

            // Deep sleep: elapsed time passes, but the uptime-based expiry callback has not run yet.
            ShadowSystemClock.advanceBy(Duration.ofSeconds(91));
            controller.get().onNotificationPosted(doorDashOffer("New Order: Go to Chick-fil-A"), null);

            // The expired incarnation's card is replaced by a card for the new offer, not silently dropped.
            List<Notification> posted = notifications().getAllNotifications();
            assertEquals(1, posted.size());
            assertEquals(OfferAlerts.REVIEW_CHANNEL_ID, posted.get(0).getChannelId());
            assertTrue(OfferNotificationService.hasActiveOffer());
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void trackedOfferAndItsCardExpireTogether() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        ServiceController<OfferNotificationService> controller =
                Robolectric.buildService(OfferNotificationService.class).create();
        try {
            controller.get().onNotificationPosted(doorDashOffer("New Order: Go to Chick-fil-A"), null);
            assertTrue(OfferNotificationService.hasActiveOffer());

            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(OfferAlertState.LIFETIME_MS / 1000));
            assertEquals(0, notifications().size());
            assertFalse(OfferNotificationService.hasActiveOffer());
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void listenerReconnectClearsCardsLeftByAnEarlierProcess() {
        assertTrue(OfferAlerts.notifyOffer(app, "offer-1-old", null, OfferRule.Result.REVIEW, "Old", false));
        assertTrue(OfferAlerts.notifyOffer(app, "offer-2-old", null, OfferRule.Result.KEEP, "Old", false));
        Notification updateNotice = new Notification.Builder(app, OfferAlerts.REVIEW_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .build();
        app.getSystemService(NotificationManager.class).notify(UPDATE_NOTICE_ID, updateNotice);

        ServiceController<OfferNotificationService> controller =
                Robolectric.buildService(OfferNotificationService.class).create();
        try {
            controller.get().onListenerConnected();
            assertNull(notifications().getNotification("offer-1-old", ALERT_NOTIFICATION_ID));
            assertNull(notifications().getNotification("offer-2-old", ALERT_NOTIFICATION_ID));
            // Other Offer Filter notifications, such as a pending update confirmation, are left alone.
            assertNotNull(notifications().getNotification(UPDATE_NOTICE_ID));
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void settingsScreenStaysClearOfSystemBarsAndKeyboard() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).create()) {
            View page = ((ViewGroup) activity.get().findViewById(android.R.id.content)).getChildAt(0);
            if (Build.VERSION.SDK_INT >= 35) {
                dispatchEdgeToEdgeInsets(page);
                assertEquals(50, page.getPaddingTop());
                assertEquals(300, page.getPaddingBottom());
            } else {
                // Before Android 15 the platform itself lays the window out inside the system bars.
                assertEquals(0, page.getPaddingTop());
                assertEquals(0, page.getPaddingBottom());
            }
        }
    }

    @Test
    public void installResultForAnotherSessionIsIgnored() {
        Updater.prefs(app).edit().putInt("session", 7).commit();
        Updater.status(app, "Installing 9.9.9");

        new UpdateReceiver().onReceive(app, installResult(6, PackageInstaller.STATUS_FAILURE));
        assertEquals("Installing 9.9.9", Updater.status(app));

        new UpdateReceiver().onReceive(app, installResult(7, PackageInstaller.STATUS_FAILURE));
        assertTrue(Updater.status(app).startsWith("Android installation failed"));
    }

    @Test
    public void reportIncludesEverySavedRuleAndNotificationAccess() {
        FilterStore.save(app, new FilterSettings(true, 2000, 150, 30, 100, 3, true, 0));
        FilterStore.recordAccepted(app, 2500);
        String report = DiagnosticLog.report(app);
        assertTrue(report.contains("max stops=3; rising offers=true; last accepted cents=2500"));
        assertTrue(report.contains("Notification access granted: false"));
    }

    /** A DoorDash offer notification in the shape observed on a real device, posted now. */
    private StatusBarNotification doorDashOffer(String text) {
        Notification payload = new Notification.Builder(app, "source")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("New Delivery!")
                .setContentText(text)
                .build();
        return new StatusBarNotification("com.doordash.driverapp", "com.doordash.driverapp", 3, "NEW_ORDER", 10001, 0,
                0, payload, android.os.Process.myUserHandle(), System.currentTimeMillis());
    }

    private static Intent installResult(int session, int status) {
        return new Intent(UpdateReceiver.INSTALL_RESULT)
                .putExtra(PackageInstaller.EXTRA_SESSION_ID, session)
                .putExtra(PackageInstaller.EXTRA_STATUS, status);
    }

    /** Status bar 50 px, navigation bar 80 px, keyboard 300 px. Requires API 30+. */
    private static void dispatchEdgeToEdgeInsets(View view) {
        view.dispatchApplyWindowInsets(new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 50, 0, 0))
                .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 80))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 300))
                .build());
    }

    /** The Robolectric shadow of the app's notification manager, looked up fresh on each call. */
    private ShadowNotificationManager notifications() {
        return Shadows.shadowOf(app.getSystemService(NotificationManager.class));
    }

    /** Depth-first search for a {@link Button} whose text is exactly {@code text}; null if none. */
    private View findButton(View view, String text) {
        if (view instanceof Button && text.contentEquals(((Button) view).getText())) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findButton(group.getChildAt(i), text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}

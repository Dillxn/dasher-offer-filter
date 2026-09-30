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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
        DecisionLog.forgetCache();
        AreaMap.forgetCache();
        ReportOutbox.forgetCache();
        OfferSilencer.forgetCache();
    }

    @Test
    public void theAppHasALauncherIconAndItsAlertsCarryTheFunnel() {
        int icon = app.getApplicationInfo().icon;
        assertTrue("the launcher needs an icon", icon != 0);
        assertTrue(app.getDrawable(icon) instanceof android.graphics.drawable.AdaptiveIconDrawable);
        if (Build.VERSION.SDK_INT >= 33) {
            assertNotNull("themed icons need a monochrome layer",
                    ((android.graphics.drawable.AdaptiveIconDrawable) app.getDrawable(icon)).getMonochrome());
        }

        assertTrue(OfferAlerts.notifyOffer(app, "keep", null, OfferRule.Result.KEEP, "$25.00", true));
        Notification alert = notifications().getNotification("keep", ALERT_NOTIFICATION_ID);
        assertEquals(app.getResources().getIdentifier("ic_notification", "drawable", app.getPackageName()),
                alert.getSmallIcon().getResId());
    }

    @Test
    public void aReviewCardNotAskedToRingStaysQuietAndDoesNotLaunch() {
        assertTrue(OfferAlerts.notifyOffer(app, "review", null, OfferRule.Result.REVIEW, "Missing pay", false));

        Notification notification = notifications().getNotification("review", ALERT_NOTIFICATION_ID);
        assertNotNull(notification);
        assertEquals(OfferAlerts.REVIEW_CHANNEL_ID, notification.getChannelId());
        // A group child with summary-only alerting never alerts, even on the audible "Offers to check" channel.
        assertEquals(Notification.GROUP_ALERT_SUMMARY, notification.getGroupAlertBehavior());
        assertNull(Shadows.shadowOf(app).getNextStartedActivity());
        // The silent channel older versions used is gone.
        assertNull(app.getSystemService(NotificationManager.class).getNotificationChannel("unclassified_offers_v1"));
    }

    @Test
    public void aBackgroundOfferWithoutPayRingsOnceSoItIsNotMissed() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        ServiceController<OfferNotificationService> controller =
                Robolectric.buildService(OfferNotificationService.class).create();
        try {
            // DoorDash's background notification names the store but not the pay, so it cannot be judged.
            StatusBarNotification offer = doorDashOffer("New Order: Go to Chick-fil-A");
            controller.get().onNotificationPosted(offer, null);
            Notification card = notifications().getAllNotifications().get(0);
            NotificationChannel channel = app.getSystemService(NotificationManager.class)
                    .getNotificationChannel(card.getChannelId());
            assertEquals("it must be heard", NotificationManager.IMPORTANCE_HIGH, channel.getImportance());
            assertNotNull(channel.getSound());
            assertTrue(card.getGroupAlertBehavior() != Notification.GROUP_ALERT_SUMMARY);
            assertNull("still never opens Dasher by itself", Shadows.shadowOf(app).getNextStartedActivity());
            assertEquals("CHECK_BELL", DecisionLog.recent(app, 1).get(0).action.name());

            // An update of the same offer (DoorDash changes its title as the offer ages) does not ring again.
            Notification aged = new Notification.Builder(app, "source")
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle("New Delivery! 0:45 left")
                    .setContentText("New Order: Go to Chick-fil-A")
                    .build();
            controller.get().onNotificationPosted(new StatusBarNotification("com.doordash.driverapp",
                    "com.doordash.driverapp", 3, "NEW_ORDER", 10001, 0, 0, aged, android.os.Process.myUserHandle(),
                    System.currentTimeMillis()), null);
            assertEquals(1, notifications().size());
            Notification update = notifications().getAllNotifications().get(0);
            assertEquals(Notification.GROUP_ALERT_SUMMARY, update.getGroupAlertBehavior());
        } finally {
            controller.destroy();
        }
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
    public void tappingTheMascotPausesWithoutPressingSave() {
        FilterStore.save(app, new FilterSettings(true, 2000, 150, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).create()) {
            View content = activity.get().findViewById(android.R.id.content);
            FilterHeroView mascot = find(content, FilterHeroView.class);
            assertEquals("Pause auto-decline", mascot.action());
            mascot.performClick();
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
                dispatchEdgeToEdgeInsets(page, 300);
                assertEquals(50, page.getPaddingTop());
                assertEquals("the keyboard, taller than the navigation bar, sets the bottom", 300,
                        page.getPaddingBottom());
                dispatchEdgeToEdgeInsets(page, 0);
                assertEquals(80, page.getPaddingBottom());
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
        FilterStore.recordAccepted(app, new OfferSnapshot(2500, 6.0, 25, 2));
        String report = DiagnosticLog.report(app);
        assertTrue(report.contains("max stops=3; rising offers=true; highest accepted cents=2500"));
        assertTrue(report.contains("best accepted=$1.00/min, $4.17/mi, $12.50/stop"));
        assertTrue(report.contains("Notification access granted: false"));
    }

    @Test
    public void resumeTurnsAutoDeclineBackOnWithTheSavedRules() {
        FilterStore.save(app, new FilterSettings(false, 2000, 150, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            FilterHeroView mascot = find(content, FilterHeroView.class);
            assertEquals("Resume auto-decline", mascot.action());
            assertNotNull("paused says so, in one word", shownTextContaining(content, "Paused"));
            mascot.performClick();

            FilterSettings saved = FilterStore.load(app);
            assertTrue(saved.enabled);
            assertEquals(2000, saved.flatCents);
            assertEquals(150, saved.perMileCents);
            // The same mascot now offers to pause again.
            assertEquals("Pause auto-decline", mascot.action());
            assertNull("on, the picture says it all", shownTextContaining(content, "Paused"));
        }
    }

    @Test
    public void withoutAnyRuleTheMascotLeadsToTheRules() {
        FilterStore.save(app, new FilterSettings(false, 0, 0, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            FilterHeroView mascot = find(content, FilterHeroView.class);
            assertEquals("Set up rules", mascot.action());
            assertNotNull(shownTextContaining(content, "Tap to set up rules"));
            mascot.performClick();
            settle();
            assertFalse(FilterStore.load(app).enabled);
            assertTrue(fieldLabeled(content, "Minimum pay ($)").isShown());
        }
    }

    @Test
    public void settingsOpenFromTheHeaderAndBackReturnsToTheMainPage() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            // One page for the filter, the offers, the minimums and the areas; what is set once is in Settings.
            assertTrue(find(content, FilterHeroView.class).isShown());
            assertTrue(find(content, DecisionChartView.class).isShown());
            assertTrue(find(content, MinimumsStarView.class).isShown());
            assertFalse(fieldLabeled(content, "Minimum pay ($)").isShown());
            assertNull(shownButton(content, "Share report"));

            iconButton(content, "Settings").performClick();
            settle();
            assertTrue(fieldLabeled(content, "Minimum pay ($)").isShown());
            assertNotNull(shownButton(content, "Share report"));
            assertFalse(find(content, FilterHeroView.class).isShown());

            // The page shown survives the activity being recreated (rotation, dark mode switch).
            activity.recreate();
            content = activity.get().findViewById(android.R.id.content);
            assertTrue(fieldLabeled(content, "Minimum pay ($)").isShown());

            activity.get().onBackPressed();
            assertTrue(find(content, FilterHeroView.class).isShown());
            assertFalse(activity.get().isFinishing());
            iconButton(content, "Settings").performClick();
            iconButton(content, "Back").performClick();
            assertTrue(find(content, FilterHeroView.class).isShown());
        }
    }

    @Test
    public void theNewestOfferIsABuildingThatUnfoldsIntoAStampedTicket() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertNull("no line of words under the skyline", shownTextContaining(content, "Below your per-mile rate"));
            assertNull("the ticket stays folded until asked for", find(content, OfferCardView.class));

            openTicket(content);
            OfferCardView card = find(content, OfferCardView.class);
            assertTrue(card.isShown());
            assertEquals("Paid $7.90, needed $10.80. 7.2 mi · 21 min · 2 stops",
                    card.getContentDescription().toString());
            assertEquals("Declined", find(content, Decor.Stamp.class).getContentDescription().toString());
            assertNotNull(shownTextContaining(content, "Below your per-mile rate"));

            // While no older offer is picked, the ticket follows each new one.
            DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis() + 60_000,
                    DecisionLog.Source.SCREEN, false, new OfferSnapshot(2500, 9.1, 30, 3), 2000,
                    OfferRule.Result.KEEP, "meets enabled rules", DecisionLog.Action.PASSES, true,
                    Collections.emptyList()));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1100));
            assertEquals("Passed", find(content, Decor.Stamp.class).getContentDescription().toString());
            assertNotNull(shownTextContaining(content, "Meets your rules"));

            // Back folds the ticket away.
            activity.get().onBackPressed();
            assertNull(find(content, OfferCardView.class));
        }
    }

    @Test
    public void savingRulesKeepsAutoDeclinePaused() {
        FilterStore.save(app, new FilterSettings(false, 2000, 0, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            fieldLabeled(content, "Minimum pay ($)").setText("25");
            findButton(content, "Save rules").performClick();

            FilterSettings saved = FilterStore.load(app);
            assertFalse(saved.enabled);
            assertEquals(2500, saved.flatCents);
        }
    }

    @Test
    public void everyButtonOnTheScreenHasALabel() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            List<Button> buttons = new ArrayList<>();
            collectButtons(content, buttons);
            assertTrue(buttons.size() >= 10);
            for (Button button : buttons) assertFalse("unlabeled button", button.getText().toString().trim().isEmpty());
            // The round icon buttons have no text, so they must say what they do.
            assertNotNull(iconButton(content, "Settings"));
            assertNotNull(iconButton(content, "Back"));
        }
    }

    @Test
    public void shareReportCarriesTheDecisionHistory() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            findButton(activity.get().findViewById(android.R.id.content), "Share report").performClick();

            Intent chooser = Shadows.shadowOf(app).getNextStartedActivity();
            assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
            Intent sent = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
            assertEquals(Intent.ACTION_SEND, sent.getAction());
            assertTrue(sent.getStringExtra(Intent.EXTRA_SUBJECT).startsWith("Offer Filter diagnostics"));
            String body = sent.getStringExtra(Intent.EXTRA_TEXT);
            assertTrue(body.contains("== Decision history"));
            assertTrue(body.contains("DECLINE | pay $7.90 | needed $10.80"));
        }
    }

    @Test
    public void clearingHistoryAsksFirst() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            findButton(content, "Clear history").performClick();
            android.app.AlertDialog dialog = (android.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertEquals(1, DecisionLog.recent(app, 10).size());
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(DecisionLog.recent(app, 10).isEmpty());
            assertNotNull(shownTextContaining(content, "No offers yet"));
        }
    }

    @Test
    public void recentDecisionsAppearWithTheirReasons() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertEquals("Chart of the last 1 offers: 0 passed, 1 declined, 0 need review.",
                    findChart(content).getContentDescription().toString());
            openTicket(content);
            assertNotNull(shownTextContaining(content, "Below your per-mile rate"));
            assertNotNull(shownTextContaining(content, "Decline tapped · on screen"));
        }
    }

    @Test
    public void tappingAChartColumnShowsWhatWasRead() {
        DecisionLog.record(app, declinedEntry());
        DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis() + 60_000, DecisionLog.Source.SCREEN,
                false, new OfferSnapshot(2500, 9.1, 30, 3), 2000, OfferRule.Result.KEEP, "meets enabled rules",
                DecisionLog.Action.PASSES, true, Collections.emptyList()));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            DecisionChartView chart = findChart(content);
            assertEquals("the newest offer is shown first", Integer.valueOf(2500),
                    chart.selectedEntry().facts.payCents);
            assertNull(shownTextContaining(content, "Read: $7.90"));

            // The older offer is the second building from the right; tapping it opens its ticket.
            float density = app.getResources().getDisplayMetrics().density;
            float left = DecisionChartView.SIDE_DP * density;
            float slot = (chart.getWidth() - DecisionChartView.SIDE_DP * density - left) / DecisionChartView.SLOTS;
            float x = left + slot * (DecisionChartView.SLOTS - 1.5f);
            chart.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, 40, 0));
            chart.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, x, 40, 0));

            assertEquals(Integer.valueOf(790), chart.selectedEntry().facts.payCents);
            assertNotNull(shownTextContaining(content, "Read: $7.90"));
        }
    }

    @Test
    public void reportingNeedsATokenAndThenSendsTheOfferWithTheUsersNote() throws Exception {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            openTicket(content);
            assertNull("no report button without a token", shownButton(content, "Report this offer"));
            assertNotNull(findTextContaining(content, "Off. Paste a GitHub token"));

            EditText token = fieldLabeled(content, "Automatic reports (GitHub token)");
            token.setText("not a token");
            findButton(content, "Save token").performClick();
            assertFalse(ReportOutbox.enabled(app));

            token.setText(" github_pat_11ABCDEFG0123456789_abcdefghijklmnop ");
            findButton(content, "Save token").performClick();
            assertTrue(ReportOutbox.enabled(app));
            assertEquals("", token.getText().toString());
            assertNotNull(findTextContaining(content, "On · no reports sent yet"));

            shownButton(content, "Report this offer").performClick();
            android.app.AlertDialog dialog = (android.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            findEditText(dialog.getWindow().getDecorView()).setText("It paid $12, not $7.90");
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ReportOutbox.flush();

            assertEquals(1, ReportOutbox.queued(app));
            java.io.File[] queued = new java.io.File(app.getFilesDir(), "report-outbox").listFiles();
            String report = new String(java.nio.file.Files.readAllBytes(queued[0].toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            org.json.JSONObject item = new org.json.JSONObject(report);
            assertTrue(item.getString("title").startsWith("[offer-report] You reported: Declined $7.90"));
            assertTrue(item.getString("body").contains("It paid $12, not $7.90"));
            assertTrue(item.getString("body").contains("2 stops (7.2 mi)"));
        }
    }

    @Test
    public void gitHubConnectShowsOnlyInABuildWithAnAppAndWalksThroughTheCode() {
        String shipped = GitHubConnect.clientId;
        try {
            GitHubConnect.clientId = "";
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                assertNull(findButton(activity.get().findViewById(android.R.id.content), "Connect GitHub"));
            }

            GitHubConnect.clientId = "Iv1.test";
            GitHubConnect.disconnect(app);
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                View content = activity.get().findViewById(android.R.id.content);
                assertEquals(View.VISIBLE, findButton(content, "Connect GitHub").getVisibility());
                assertEquals(View.GONE, findButton(content, "Disconnect GitHub").getVisibility());
                assertNotNull(findText(content, "Not connected. Connect GitHub to get updates from your repository."));

                // GitHub sent a code: it is shown large, and the button copies it and opens GitHub's code page.
                app.getSharedPreferences("github", Context.MODE_PRIVATE).edit()
                        .putString("device_code", "device").putString("user_code", "WDJB-MJHT")
                        .putLong("code_expires_at", System.currentTimeMillis() + 600_000L).commit();
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
                TextView code = findText(content, "WDJB-MJHT");
                assertEquals(View.VISIBLE, code.getVisibility());
                findButton(content, "Copy code and open GitHub").performClick();
                android.content.ClipboardManager clipboard = app.getSystemService(android.content.ClipboardManager.class);
                assertEquals("WDJB-MJHT", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
                Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
                assertEquals(Intent.ACTION_VIEW, opened.getAction());
                assertEquals("https://github.com/login/device", opened.getDataString());

                // Cancelling forgets the code.
                findButton(content, "Cancel").performClick();
                assertEquals(GitHubConnect.State.OFF, GitHubConnect.state(app));
                assertEquals(View.GONE, code.getVisibility());
                assertEquals(View.VISIBLE, findButton(content, "Connect GitHub").getVisibility());

                // Connected: says as whom, and offers only to disconnect.
                app.getSharedPreferences("github", Context.MODE_PRIVATE).edit()
                        .putString("access_token", "ghu_test").putString("login", "Dillxn").commit();
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
                assertNotNull(findText(content, "Connected to GitHub as Dillxn. Updates also come from your repository."));
                assertEquals(View.GONE, findButton(content, "Connect GitHub").getVisibility());
                findButton(content, "Disconnect GitHub").performClick();
                assertEquals(GitHubConnect.State.OFF, GitHubConnect.state(app));
                assertNull(app.getSharedPreferences("github", Context.MODE_PRIVATE).getString("access_token", null));
            }
        } finally {
            GitHubConnect.clientId = shipped;
            GitHubConnect.disconnect(app);
        }
    }

    @Test
    public void tipsShowOnlyWithTheAuthorsNamesAndOpenTheirService() {
        String cashApp = Support.cashApp;
        String venmo = Support.venmo;
        String payPal = Support.payPal;
        try {
            Support.cashApp = "";
            Support.venmo = "";
            Support.payPal = "";
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                View content = activity.get().findViewById(android.R.id.content);
                assertNull(findText(content, "SUPPORT"));
                assertNull(findButton(content, "Tip with Cash App"));
            }

            Support.cashApp = "OfferFilterDev";
            Support.venmo = "Offer-Filter";
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                View content = activity.get().findViewById(android.R.id.content);
                assertNotNull(findText(content, "SUPPORT"));
                assertNull("a service without a name is not offered", findButton(content, "Tip with PayPal"));
                findButton(content, "Tip with Cash App").performClick();
                Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
                assertEquals(Intent.ACTION_VIEW, opened.getAction());
                assertEquals("https://cash.app/$OfferFilterDev", opened.getDataString());
                findButton(content, "Tip with Venmo").performClick();
                assertEquals("https://venmo.com/Offer-Filter?txn=pay&note=Offer%20Filter%20tip",
                        Shadows.shadowOf(app).getNextStartedActivity().getDataString());
            }
        } finally {
            Support.cashApp = cashApp;
            Support.venmo = venmo;
            Support.payPal = payPal;
        }
    }

    @Test
    public void turningReportsOffAsksFirst() {
        ReportOutbox.setToken(app, "github_pat_existing");
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            findButton(content, "Save token").performClick();
            assertTrue("an empty field changes nothing", ReportOutbox.enabled(app));
            findButton(content, "Turn off reports").performClick();
            android.app.AlertDialog dialog = (android.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertTrue(ReportOutbox.enabled(app));
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(ReportOutbox.enabled(app));
            assertEquals(View.GONE, findButton(content, "Turn off reports").getVisibility());
        }
    }

    @Test
    public void theFilterPictureShowsThisDashWithAllTimeTotalsAndTheState() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        // One offer from hours before this dash began.
        Dashing.forgetCache();
        DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis() - 3 * 3_600_000L,
                DecisionLog.Source.SCREEN, false, new OfferSnapshot(900, 4.0, 15, 2), 1200,
                OfferRule.Result.DECLINE, "flat minimum", DecisionLog.Action.DECLINE_TAPPED, true,
                Collections.emptyList()));
        // This dash: one declined, one passed.
        Dashing.seen(app);
        DecisionLog.record(app, declinedEntry());
        DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis(), DecisionLog.Source.SCREEN,
                false, new OfferSnapshot(2500, 9.1, 30, 3), 2000, OfferRule.Result.KEEP, "meets enabled rules",
                DecisionLog.Action.PASSES, true, Collections.emptyList()));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            FilterHeroView hero = find(content, FilterHeroView.class);
            assertEquals("Auto-decline on. This dash: 2 offers, 1 passed, 1 filtered, 0 to review. "
                    + "In all: 1 passed, 2 filtered, 0 to review.", hero.getContentDescription().toString());
            assertEquals("the mascot is cheerful while on", Mascot.Mood.HAPPY, hero.mood());

            assertEquals(FilterHeroView.State.ON, hero.state());
            hero.performClick();
            assertTrue(hero.getContentDescription().toString().startsWith("Auto-decline paused."));
            assertEquals(FilterHeroView.State.PAUSED, hero.state());
            assertEquals("and asleep while paused", Mascot.Mood.SLEEPY, hero.mood());
        }
    }

    @Test
    public void theRulesPreviewFollowsTheRulesAsTheyAreTyped() {
        FilterStore.save(app, new FilterSettings(true, 700, 0, 0, 0, 0));
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            // The example is the latest fully read offer: 21 min, 7.2 mi, 2 stops.
            assertNotNull(findText(content, "An offer like 21 min · 7.2 mi · 2 stops needs $7.00."));
            fieldLabeled(content, "Per mile ($)").setText("1.50");
            assertNotNull(findText(content, "An offer like 21 min · 7.2 mi · 2 stops needs $10.80."));
            fieldLabeled(content, "Max stops (1 order = 2)").setText("1");
            assertNotNull(findText(content, "An offer like 21 min · 7.2 mi · 2 stops is declined: at most 1 stop."));
            // Unsaved: nothing changed in the saved rules.
            assertEquals(0, FilterStore.load(app).perMileCents);
        }
    }

    @Test
    public void theStarShowsSetAgainstAdaptiveMinimumsAsTheyAreTyped() {
        FilterStore.save(app, new FilterSettings(true, 700, 150, 30, 100, 3, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(star.isShown());
            // What the example offer needs is heard first; the page shows no words for it.
            assertEquals("An offer like 20 min · 5 mi · 2 stops needs $14.21. "
                    + "Minimums, set and adaptive now. Pay: set $7.00, adaptive more than $14.20. "
                    + "Per mile: set $1.50, adaptive $2.37. Per minute: set $0.30, adaptive $0.59. "
                    + "Per stop: no set minimum, adaptive $7.10.", star.getContentDescription().toString());
            // No offers yet, so the example is a typical one; the largest ask is "more than $14.20".
            assertNotNull(findText(content, "An offer like 20 min · 5 mi · 2 stops needs $14.21."));

            fieldLabeled(content, "Per mile ($)").setText("0");
            assertTrue(star.getContentDescription().toString()
                    .contains("Per mile: no set minimum, adaptive $2.37."));
            ((Switch) findButton(content, "Adaptive minimum")).setChecked(false);
            assertTrue(star.getContentDescription().toString()
                    .endsWith("Adaptive minimum is off, so the adaptive values are not applied."));
            // Unsaved: the saved rules and the learned minimums are untouched.
            assertEquals(150, FilterStore.load(app).perMileCents);
            assertEquals("$2.37", FilterStore.load(app).best.perMile());
        }
    }

    @Test
    public void theStarMarksRecentOffersAndWhatAManualDeclineTaught() {
        FilterStore.save(app, new FilterSettings(true, 700, 0, 0, 0, 0, true, 0));
        // Declined by hand: pay came closest to catching it, so later offers must beat its $9.00.
        FilterStore.learnFromDecline(app, new OfferSnapshot(900, 3.0, 12, 2));
        DecisionLog.record(app, declinedEntry());
        DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis() + 60_000,
                DecisionLog.Source.SCREEN, false, new OfferSnapshot(2500, 9.1, 30, 3), 2000,
                OfferRule.Result.KEEP, "meets enabled rules", DecisionLog.Action.PASSES, true,
                Collections.emptyList()));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            String star = find(content, MinimumsStarView.class).getContentDescription().toString();
            assertTrue(star, star.contains("Pay: set $7.00, adaptive more than $9.00."));
            assertTrue(star, star.endsWith("Marked: your last 2 offers, 1 passed, 1 declined, 0 to review."));
        }
    }

    @Test
    public void theMapIsAlwaysOnTheGroundAndAsksOnlyForApproximateLocation() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            AreaMapView map = find(content, AreaMapView.class);
            assertTrue("the map is part of the page from the start", map.isShown());
            assertTrue(map.getContentDescription().toString().startsWith("Tap to allow location"));
            assertNull("nothing is asked before a tap", Shadows.shadowOf(activity.get()).getLastRequestedPermission());
            map.performClick();
            org.robolectric.shadows.ShadowActivity.PermissionsRequest request =
                    Shadows.shadowOf(activity.get()).getLastRequestedPermission();
            assertEquals(Arrays.asList(Manifest.permission.ACCESS_COARSE_LOCATION),
                    Arrays.asList(request.requestedPermissions));

            // Turned off in Settings, the ground stays, and says a tap turns it back on.
            iconButton(content, "Settings").performClick();
            ((Switch) findButton(content, "Remember where offers come in")).setChecked(false);
            iconButton(content, "Back").performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertTrue(map.isShown());
            assertTrue(map.getContentDescription().toString().startsWith("Tap to map where offers pay best"));
            map.performClick();
            assertTrue(AreaMap.enabled(app));
        }
    }

    @Test
    public void theTreasureMapRanksAreasByPayPerMileAndShowsTheBest() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        AreaMap.setEnabled(app, true);
        // Three offers near one spot at $2/mi, three near another at $3/mi.
        noteOfferAt(37.7749, -122.4194, 1000, 5.0);
        noteOfferAt(37.7749, -122.4194, 1200, 6.0);
        noteOfferAt(37.7749, -122.4194, 800, 4.0);
        noteOfferAt(37.8149, -122.3794, 1500, 5.0);
        noteOfferAt(37.8149, -122.3794, 1800, 6.0);
        noteOfferAt(37.8149, -122.3794, 1200, 4.0);
        setLocation(37.7749, -122.4194);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            AreaMapView map = find(content, AreaMapView.class);
            assertTrue("on the page, no sheet or switch", map.isShown());
            assertEquals("Best paying areas by pay per mile: 1, 3.6 mi NE of you, $3.00/mi over 3 offers; "
                    + "2, Around you, $2.00/mi over 3 offers.", map.getContentDescription().toString());
            TextView best = shownTextContaining(content, "3.6 mi NE · $3.00/mi  ›");
            assertNotNull("the best area is shown until another is picked", best);
            assertTrue(areaLineSaid(content), areaLineSaid(content)
                    .startsWith("#1 · 3.6 mi NE of you · $3.00/mi. Average"));
            assertTrue(areaLineSaid(content).endsWith("Opens it in Maps."));
            best.performClick();
            Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
            assertTrue(opened.getDataString(), opened.getDataString().startsWith("geo:37.81"));
        }
    }

    @Test
    public void theBestAreaIsFollowedUntilOneIsPicked() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        AreaMap.setEnabled(app, true);
        noteOfferAt(37.7749, -122.4194, 1000, 5.0);
        noteOfferAt(37.7749, -122.4194, 1200, 6.0);
        noteOfferAt(37.7749, -122.4194, 800, 4.0);
        setLocation(37.7749, -122.4194);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertTrue(areaLineSaid(content).startsWith("#1 · Around you"));

            // A better area turns up while the map is open: nothing was picked, so the details follow it.
            noteOfferAt(37.8149, -122.3794, 1500, 5.0);
            noteOfferAt(37.8149, -122.3794, 1800, 6.0);
            noteOfferAt(37.8149, -122.3794, 1200, 4.0);
            setLocation(37.7749, -122.4194);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1100));
            assertTrue(areaLineSaid(content).startsWith("#1 · 3.6 mi NE of you"));
        }
    }

    @Test
    public void aPickedAreaStaysPickedAsTheRankingChanges() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        AreaMap.setEnabled(app, true);
        noteOfferAt(37.7749, -122.4194, 1000, 5.0);
        noteOfferAt(37.7749, -122.4194, 1200, 6.0);
        noteOfferAt(37.7749, -122.4194, 800, 4.0);
        noteOfferAt(37.8149, -122.3794, 1500, 5.0);
        noteOfferAt(37.8149, -122.3794, 1800, 6.0);
        noteOfferAt(37.8149, -122.3794, 1200, 4.0);
        setLocation(37.7749, -122.4194);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            AreaMapView map = find(content, AreaMapView.class);
            map.pick(AreaMap.ranked(AreaMap.cells(app)).get(1));
            assertTrue(areaLineSaid(content).startsWith("#2 · Around you"));

            // An even better area turns up: the picked one stays, now third.
            noteOfferAt(37.7349, -122.4594, 3000, 5.0);
            noteOfferAt(37.7349, -122.4594, 3600, 6.0);
            noteOfferAt(37.7349, -122.4594, 2400, 4.0);
            setLocation(37.7749, -122.4194);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1100));
            assertTrue(areaLineSaid(content).startsWith("#3 · Around you"));
        }
    }

    @Test
    public void acceptedOffersOnlyRaiseTheAdaptiveMinimumsUntilReset() {
        // Nothing is learned while the adaptive minimum is off, or while auto-decline is paused.
        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0, false, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(3000, 6.0, 24, 2));
        FilterStore.save(app, new FilterSettings(false, 1000, 0, 0, 0, 0, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(3000, 6.0, 24, 2));
        assertTrue(FilterStore.load(app).best.isEmpty());
        assertEquals("nothing is learned while off or paused, the pay included", 0,
                FilterStore.load(app).lastAcceptedCents);

        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2));
        FilterStore.recordAccepted(app, new OfferSnapshot(900, 6.0, 24, 2));
        FilterSettings saved = FilterStore.load(app);
        assertEquals("a lower offer accepted later does not lower the pay minimum", 1420, saved.lastAcceptedCents);
        assertEquals("nor any best rate", "$0.59/min, $2.37/mi, $7.10/stop", saved.best.summary());
        // Pausing, turning the adaptive minimum off and on, and accepting meanwhile keep what was learned.
        FilterStore.save(app, saved.withEnabled(false));
        FilterStore.recordAccepted(app, new OfferSnapshot(600, 6.0, 24, 2));
        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0, false, 0));
        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0, true, 0));
        assertEquals(1420, FilterStore.load(app).lastAcceptedCents);
        assertEquals("$0.59/min, $2.37/mi, $7.10/stop", FilterStore.load(app).best.summary());

        // Saving rules keeps what was learned; Reset starts over.
        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0, true, 0));
        assertEquals("$0.59/min, $2.37/mi, $7.10/stop", FilterStore.load(app).best.summary());
        FilterStore.resetAccepted(app);
        assertTrue(FilterStore.load(app).best.isEmpty());
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
    }

    @Test
    public void anOrderAcceptedWhileThePageIsOpenShowsOnTheStarWithoutWaitingForAnotherOffer() {
        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0, true, 0));
        FilterStore.resetAccepted(app);
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            MinimumsStarView star = find(activity.get().findViewById(android.R.id.content), MinimumsStarView.class);
            assertTrue(star.getContentDescription().toString().contains("Per mile: no set minimum, no adaptive minimum yet"));

            // Accepted in Dasher while Offer Filter stays open behind it; no new offer has come in since.
            FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            String described = star.getContentDescription().toString();
            assertTrue(described, described.contains("Pay: set $10.00, adaptive more than $14.20"));
            assertTrue(described, described.contains("Per mile: no set minimum, adaptive $2.37."));
        }
    }

    @Test
    public void theHomepageShowsItIsWatchingOnlyWhileDashing() {
        Dashing.forgetCache();
        DecisionLog.record(app, declinedEntry());
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            ScenePage scene = find(content, ScenePage.class);
            FilterHeroView mascot = find(content, FilterHeroView.class);
            assertFalse(scene.watching());
            assertFalse(mascot.getContentDescription().toString().contains("watching"));

            // Dasher seen mid-dash, but nothing can watch it yet: not live.
            Dashing.seen(app);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertFalse(scene.watching());

            service.get().onServiceConnected();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertTrue("the searchlights sweep", scene.watching());
            assertTrue("screen readers hear it: " + mascot.getContentDescription(),
                    mascot.getContentDescription().toString().contains(", watching for offers. "));
            assertNull("no words on the page for it", shownTextContaining(content, "Watching for offers"));

            Dashing.ended(app);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertFalse(scene.watching());
            assertFalse(mascot.getContentDescription().toString().contains("watching"));
        } finally {
            service.destroy();
        }
    }

    @Test
    public void theMainPageIsOneSceneWithItsHorizonAtTheSkylinesStreet() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            ScenePage scene = find(content, ScenePage.class);
            DecisionChartView chart = find(content, DecisionChartView.class);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            content.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.AT_MOST));
            content.layout(0, 0, 1080, 2340);
            int[] sceneAt = new int[2];
            int[] chartAt = new int[2];
            int[] starAt = new int[2];
            scene.getLocationInWindow(sceneAt);
            chart.getLocationInWindow(chartAt);
            star.getLocationInWindow(starAt);
            float street = chartAt[1] - sceneAt[1] + chart.getHeight() - new Ui(app).dp(11);
            assertEquals(street, scene.horizonY(), 1f);
            assertTrue("the constellation is in the sky, above the skyline", starAt[1] < chartAt[1]);

            // Bitmap drawing of the whole scene works in both themes.
            android.graphics.Bitmap page = android.graphics.Bitmap.createBitmap(1080, Math.max(1, scene.getHeight()),
                    android.graphics.Bitmap.Config.ARGB_8888);
            scene.draw(new android.graphics.Canvas(page));
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp-xxhdpi")
    public void theMainPageFitsOneScreenAndTheTicketSlidesUpOverIt() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            int width = content.getResources().getDisplayMetrics().widthPixels;
            int height = content.getResources().getDisplayMetrics().heightPixels;
            content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            content.layout(0, 0, width, height);
            ScenePage scene = find(content, ScenePage.class);
            android.widget.ScrollView page = (android.widget.ScrollView) scene.getParent();
            assertTrue("the whole scene fits: " + scene.getHeight() + " in " + page.getHeight(),
                    scene.getHeight() <= page.getHeight());
            assertTrue(find(content, AreaMapView.class).isShown());

            openTicket(content);
            assertNotNull("the ticket is up", shownTextContaining(content, "Below your per-mile rate"));
            activity.get().onBackPressed();
            assertFalse(activity.get().isFinishing());
            assertNull("folded again", shownTextContaining(content, "Below your per-mile rate"));
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h360dp-xxhdpi")
    public void aShortWindowKeepsTheMascotItsCountsAndTheMap() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertTrue(find(content, FilterHeroView.class).isShown());
            assertTrue("the map stays", find(content, AreaMapView.class).isShown());
            assertFalse("the constellation gives way", find(content, MinimumsStarView.class).isShown());
            assertFalse("the skyline gives way", findChart(content).isShown());
            assertNull(shownTextContaining(content, "No offers yet"));
        }
    }

    /** Dasher installed on the simulated phone, with its launcher activity. */
    private void dasherInstalled() {
        android.content.ComponentName dasher =
                new android.content.ComponentName("com.doordash.driverapp", "com.doordash.driverapp.Home");
        org.robolectric.shadows.ShadowPackageManager packages = Shadows.shadowOf(app.getPackageManager());
        packages.addActivityIfNotPresent(dasher);
        android.content.IntentFilter launcher = new android.content.IntentFilter(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        packages.addIntentFilterForActivity(dasher, launcher);
    }

    @Test
    public void splitWithDasherSplitsTheScreenAtATapThenOpensDasherBelow() throws Exception {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertNull("no Dasher, no button", shownIcon(content, "Split screen with Dasher"));
        }
        dasherInstalled();
        DasherSplit.forget();
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            View split = shownIcon(content, "Split screen with Dasher");
            assertNotNull(split);

            // Screen reading is how Android is asked; without it, the tap only says so.
            split.performClick();
            assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().startsWith("Turn on screen reading"));
            assertNull(Shadows.shadowOf(app).getNextStartedActivity());

            service.get().onServiceConnected();
            split.performClick();
            assertEquals(Collections.singletonList(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN),
                    Shadows.shadowOf(service.get()).getGlobalActionsPerformed());
            assertNull("Dasher waits for the split", Shadows.shadowOf(app).getNextStartedActivity());

            // The screen splits: Dasher opens in the other half, once.
            Shadows.shadowOf(activity.get()).setInMultiWindowMode(true);
            activity.get().onMultiWindowModeChanged(true, activity.get().getResources().getConfiguration());
            Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
            assertNotNull(opened);
            assertEquals("com.doordash.driverapp", opened.getComponent() != null
                    ? opened.getComponent().getPackageName() : opened.getPackage());
            assertTrue((opened.getFlags() & Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT) != 0);
            activity.get().onMultiWindowModeChanged(true, activity.get().getResources().getConfiguration());
            assertNull(Shadows.shadowOf(app).getNextStartedActivity());
            assertNull("already split: no button", shownIcon(content, "Split screen with Dasher"));
        } finally {
            service.destroy();
        }
    }

    /** A shown view with this description, or null. */
    private static View shownIcon(View content, String description) {
        View found = iconDescribed(content, description);
        return found != null && found.isShown() ? found : null;
    }

    @Test
    public void tappingTheSunTurnsTheAppToNightAndTheMoonBackToDay() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View sun = iconDescribed(activity.get().findViewById(android.R.id.content), "Switch to night");
            assertNotNull("by day the sun is a button", sun);
            sun.performClick();
            assertEquals(Boolean.TRUE, Appearance.chosen(app));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertTrue("the whole screen is night now", new Ui(activity.get()).dark);
            View moon = iconDescribed(content, "Switch to day");
            assertNotNull(moon);
            moon.performClick();
            assertEquals(Boolean.FALSE, Appearance.chosen(app));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertFalse(new Ui(activity.get()).dark);
        }
    }

    private static View iconDescribed(View view, String description) {
        if (view.getContentDescription() != null && description.contentEquals(view.getContentDescription())) {
            return view;
        }
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                View found = iconDescribed(((ViewGroup) view).getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    public void whileUpdatingTheScreenSaysSoAndTakesNoInputUntilItFails() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertNull(shownTextContaining(content, "Updating Offer Filter"));

            Updater.prefs(app).edit().putInt("session", 5).commit();
            Updater.installStarted(app, true);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            TextView title = shownTextContaining(content, "Updating Offer Filter");
            assertNotNull(title);
            View cover = (View) title.getParent();
            assertTrue("it takes every touch", cover.dispatchTouchEvent(
                    MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10, 10, 0)));
            activity.get().onBackPressed();
            assertFalse("Back does not interrupt it", activity.get().isFinishing());
            assertTrue(FilterStore.load(app).enabled);

            Updater.installationFailed(app, 1, "test");
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertNull("a failed install gives the screen back", shownTextContaining(content, "Updating Offer Filter"));
        }
    }

    @Test
    public void afterAnUpdateStartedOnScreenOfferFilterOpensAgainOnce() {
        // Not on screen when the update began: nothing opens afterwards.
        Updater.installStarted(app, false);
        assertFalse(Updater.relaunchAfterUpdate(app));

        // On screen, but this is still the version that began the update: not yet.
        Updater.installStarted(app, true);
        assertFalse(Updater.relaunchAfterUpdate(app));

        // The new version is running: Offer Filter opens, and asking again brings back the same screen.
        Updater.prefs(app).edit().putLong("relaunch_from_code", 1).commit();
        assertTrue(Updater.relaunchAfterUpdate(app));
        Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
        assertEquals(MainActivity.class.getName(), opened.getComponent().getClassName());
        assertTrue((opened.getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0);

        // Once the screen is open, nothing more opens.
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertFalse(Updater.relaunchAfterUpdate(app));
        }
    }

    @Test
    public void anUpdateWaitsOnlyWhileTheUserIsMidTask() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertFalse("the main page does not hold an update back", activity.get().midTask());
            openTicket(content);
            assertTrue("reading a ticket does", activity.get().midTask());
            activity.get().onBackPressed();
            iconButton(content, "Settings").performClick();
            assertTrue("typing rules in Settings does", activity.get().midTask());
            iconButton(content, "Back").performClick();
            assertFalse(activity.get().midTask());
        }
    }

    @Test
    public void chartDrawsUnknownPayAndSaturatedRequirements() {
        DecisionChartView chart = new DecisionChartView(app, new Ui(app));
        List<DecisionLog.Entry> entries = new ArrayList<>();
        entries.add(declinedEntry());
        entries.add(new DecisionLog.Entry(2000, DecisionLog.Source.NOTIFICATION, false, OfferSnapshot.UNKNOWN, 0,
                OfferRule.Result.REVIEW, "pay not found", DecisionLog.Action.SILENT_CARD, true,
                Collections.emptyList()));
        entries.add(new DecisionLog.Entry(3000, DecisionLog.Source.SCREEN, false, new OfferSnapshot(100, 1.0, 999, 2),
                Long.MAX_VALUE, OfferRule.Result.DECLINE, "dollars per minute", DecisionLog.Action.DECLINE_TAPPED,
                true, Collections.emptyList()));
        chart.setEntries(entries);
        chart.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
        chart.layout(0, 0, 1000, 400);
        chart.draw(new Canvas(Bitmap.createBitmap(1000, 400, Bitmap.Config.ARGB_8888)));
        assertEquals("Chart of the last 3 offers: 0 passed, 2 declined, 1 need review.", chart.getContentDescription());
    }

    @Test
    public void backgroundDecisionsAreRecorded() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        ServiceController<OfferNotificationService> controller =
                Robolectric.buildService(OfferNotificationService.class).create();
        try {
            controller.get().onNotificationPosted(doorDashOffer("New Order: Go to Chick-fil-A"), null);
            List<DecisionLog.Entry> recent = DecisionLog.recent(app, 10);
            assertEquals(1, recent.size());
            assertEquals(DecisionLog.Source.NOTIFICATION, recent.get(0).source);
            assertEquals(OfferRule.Result.REVIEW, recent.get(0).result);
            assertEquals("pay not found", recent.get(0).reason);
            assertEquals("in the background it rang once, so it is not missed", DecisionLog.Action.CHECK_BELL,
                    recent.get(0).action);
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void notificationAccessShortcutNamesThisListenerAsAString() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            findButton(activity.get().findViewById(android.R.id.content), "Notification access").performClick();
            Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
            if (Build.VERSION.SDK_INT >= 30) {
                assertEquals(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS,
                        opened.getAction());
                // The settings page reads this extra with getStringExtra.
                assertEquals(new android.content.ComponentName(app, OfferNotificationService.class).flattenToString(),
                        opened.getStringExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME));
            } else {
                assertEquals(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, opened.getAction());
            }
        }
    }

    @Test
    public void reasonsAreShownInPlainWords() {
        assertEquals("Below your minimum pay", MainActivity.plainReason("flat minimum"));
        assertEquals("Below your per-mile rate (with stop fees)",
                MainActivity.plainReason("dollars per mile and extra stops"));
        assertEquals("Too many stops (4, max 3)", MainActivity.plainReason("4 stops exceeds maximum 3"));
        assertEquals("Not above last accepted $12.50",
                MainActivity.plainReason("must beat last accepted payout $12.50"));
        assertEquals("Not above your highest accepted $12.50",
                MainActivity.plainReason("must beat highest accepted payout $12.50"));
        assertEquals("Whole route: Below your minimum pay",
                MainActivity.plainReason("combined route fails: flat minimum"));
        assertEquals("Pay not readable", MainActivity.plainReason("pay not found"));
        assertEquals("Not above an offer you declined, $2.50/mi",
                MainActivity.plainReason("must beat declined $2.50/mi"));
        DecisionLog.Entry fromNotification = new DecisionLog.Entry(1, DecisionLog.Source.NOTIFICATION, false,
                OfferSnapshot.UNKNOWN, 0, OfferRule.Result.REVIEW, "pay not found", DecisionLog.Action.SILENT_CARD,
                true, Collections.emptyList());
        assertEquals("Notification showed no pay", MainActivity.plainReason(fromNotification));
    }

    @Test
    public void theMascotIsTheOneButtonOnTheMainPage() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            FilterHeroView mascot = find(content, FilterHeroView.class);
            assertTrue(mascot.isClickable());
            assertTrue(mascot.isFocusable());
            // Screen readers hear a button that says what a tap does, with the state and the day's counts.
            android.view.accessibility.AccessibilityNodeInfo node = mascot.createAccessibilityNodeInfo();
            assertEquals(Button.class.getName(), node.getClassName().toString());
            boolean labeled = false;
            for (android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
                labeled |= action.getId() == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                        && "Pause auto-decline".contentEquals(action.getLabel());
            }
            assertTrue("the tap is labeled", labeled);
            assertTrue(mascot.getContentDescription().toString().startsWith("Auto-decline on. "));

            // No other pause or resume control competes with it on the main page.
            List<Button> buttons = new ArrayList<>();
            collectButtons(content, buttons);
            for (Button button : buttons) {
                String label = button.getText().toString();
                assertFalse(label, button.isShown() && (label.contains("Pause") || label.contains("Resume")));
            }
        }
    }

    @Test
    public void setupProblemsShowOnlyWhileSomethingIsOff() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            // No service is connected in this test, so screen reading is reported off with a Fix button.
            TextView problem = findText(content, "Screen reading is off");
            assertNotNull(problem);
            assertEquals(View.VISIBLE, ((View) problem.getParent()).getVisibility());
        }
    }

    private static DecisionLog.Entry declinedEntry() {
        return new DecisionLog.Entry(System.currentTimeMillis(), DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(790, 7.2, 21, 2), 1080, OfferRule.Result.DECLINE, "dollars per mile",
                DecisionLog.Action.DECLINE_TAPPED, true, Arrays.asList("$7.90", "2 stops (7.2 mi) • 21 min"));
    }

    private static void collectButtons(View view, List<Button> out) {
        if (view instanceof Button) out.add((Button) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectButtons(group.getChildAt(i), out);
        }
    }

    /** The first view of {@code type} in the tree, or null. */
    private static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = find(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The first button labeled {@code text} that is actually on screen (itself and every ancestor visible). */
    private static Button shownButton(View root, String text) {
        List<Button> buttons = new ArrayList<>();
        collectButtons(root, buttons);
        for (Button button : buttons) {
            if (button.isShown() && text.contentEquals(button.getText())) return button;
        }
        return null;
    }

    private static EditText findEditText(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findEditText(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The field a visible label names via {@code labelFor}. */
    private static EditText fieldLabeled(View root, String label) {
        TextView caption = findText(root, label);
        assertNotNull(label, caption);
        return root.findViewById(caption.getLabelFor());
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView && !(view instanceof Button) && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TextView findTextContaining(View view, String text) {
        if (view instanceof TextView && view.getVisibility() == View.VISIBLE
                && ((TextView) view).getText().toString().contains(text)) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findTextContaining(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Like {@link #findTextContaining}, but only text actually on screen (itself and every ancestor visible). */
    private static TextView shownTextContaining(View view, String text) {
        if (view instanceof TextView && view.isShown() && ((TextView) view).getText().toString().contains(text)) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = shownTextContaining(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The round icon button whose description is {@code description}, or null. */
    private static android.widget.ImageButton iconButton(View view, String description) {
        if (view instanceof android.widget.ImageButton && view.getContentDescription() != null
                && description.contentEquals(view.getContentDescription())) {
            return (android.widget.ImageButton) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.ImageButton found = iconButton(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The phone's last known position, fresh, at approximate accuracy. */
    private void setLocation(double latitude, double longitude) {
        android.location.Location fix = new android.location.Location(android.location.LocationManager.NETWORK_PROVIDER);
        fix.setLatitude(latitude);
        fix.setLongitude(longitude);
        fix.setAccuracy(1500);
        fix.setTime(System.currentTimeMillis());
        fix.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());
        Shadows.shadowOf(app.getSystemService(android.location.LocationManager.class))
                .setLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER, fix);
    }

    private int notedOffers;

    /** A distinct standalone offer noted while the phone is at the given position. */
    private void noteOfferAt(double latitude, double longitude, int payCents, double miles) {
        setLocation(latitude, longitude);
        notedOffers++;
        AreaMap.note(app, new DecisionLog.Entry(System.currentTimeMillis(), DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(payCents, miles, 20 + notedOffers, 2), 1000, OfferRule.Result.KEEP,
                "meets enabled rules", DecisionLog.Action.PASSES, true, Collections.emptyList()));
    }

    /** The line under the map as screen readers hear it: its rank is on the map's coin, not in its words. */
    private static String areaLineSaid(View content) {
        TextView line = shownTextContaining(content, "  ›");
        return line == null || line.getContentDescription() == null ? "" : line.getContentDescription().toString();
    }

    /** Taps the skyline, unfolding the chosen offer's ticket. */
    private static void openTicket(View content) {
        DecisionChartView chart = findChart(content);
        assertTrue("the skyline", chart != null && chart.isShown());
        chart.performClick();
    }

    private static void settle() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));
    }

    private static DecisionChartView findChart(View view) {
        if (view instanceof DecisionChartView) return (DecisionChartView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                DecisionChartView found = findChart(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
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

    /** Status bar 50 px, navigation bar 80 px, and a keyboard of {@code keyboard} px (0: none). Requires API 30+. */
    private static void dispatchEdgeToEdgeInsets(View view, int keyboard) {
        view.dispatchApplyWindowInsets(new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 50, 0, 0))
                .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 80))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, keyboard))
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

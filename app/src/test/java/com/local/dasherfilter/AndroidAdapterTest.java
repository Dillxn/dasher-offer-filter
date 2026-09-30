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
            View button = findButton(content, "Pause auto-decline");
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
            View tabBar = ((ViewGroup) page).getChildAt(2);
            if (Build.VERSION.SDK_INT >= 35) {
                // Keyboard up: the tab bar steps aside and the page ends above the keyboard.
                dispatchEdgeToEdgeInsets(page, 300);
                assertEquals(50, page.getPaddingTop());
                assertEquals(300, page.getPaddingBottom());
                assertEquals(View.GONE, tabBar.getVisibility());
                // Keyboard down: the tab bar is back, its color running on under the navigation bar.
                dispatchEdgeToEdgeInsets(page, 0);
                assertEquals(0, page.getPaddingBottom());
                assertEquals(View.VISIBLE, tabBar.getVisibility());
                assertEquals(80, tabBar.getPaddingBottom());
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
        assertTrue(report.contains("max stops=3; rising offers=true; last accepted cents=2500"));
        assertTrue(report.contains("best accepted=$1.00/min, $4.17/mi, $12.50/stop"));
        assertTrue(report.contains("Notification access granted: false"));
    }

    @Test
    public void resumeTurnsAutoDeclineBackOnWithTheSavedRules() {
        FilterStore.save(app, new FilterSettings(false, 2000, 150, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertNull(findButton(content, "Pause auto-decline"));
            findButton(content, "Resume auto-decline").performClick();

            FilterSettings saved = FilterStore.load(app);
            assertTrue(saved.enabled);
            assertEquals(2000, saved.flatCents);
            assertEquals(150, saved.perMileCents);
            // The same button now offers to pause again.
            assertNotNull(findButton(content, "Pause auto-decline"));
        }
    }

    @Test
    public void withoutAnyRuleTheMainButtonLeadsToTheRules() {
        FilterStore.save(app, new FilterSettings(false, 0, 0, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertNull(findButton(content, "Resume auto-decline"));
            findButton(content, "Set up rules").performClick();
            settle();
            assertFalse(FilterStore.load(app).enabled);
            assertTrue(find(content, RuleMeterView.class).isShown());
            assertTrue(findTab(content, "Rules").isSelected());
        }
    }

    @Test
    public void theTabBarSwapsPagesInPlaceAndBackReturnsHome() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            // Home: the filter picture and the latest offer; everything else waits behind its tab.
            assertTrue(find(content, FilterHeroView.class).isShown());
            assertTrue(findTab(content, "Home").isSelected());
            assertFalse(find(content, DecisionChartView.class).isShown());
            assertFalse(find(content, RuleMeterView.class).isShown());
            assertNull(shownButton(content, "Email report"));

            openTab(content, "Rules");
            assertTrue(find(content, RuleMeterView.class).isShown());
            assertFalse(find(content, FilterHeroView.class).isShown());
            assertTrue(findTab(content, "Rules").isSelected());
            assertFalse(findTab(content, "Home").isSelected());

            // The shown tab survives the activity being recreated (rotation, dark mode switch).
            activity.recreate();
            content = activity.get().findViewById(android.R.id.content);
            assertTrue(find(content, RuleMeterView.class).isShown());

            openTab(content, "More");
            assertNotNull(shownButton(content, "Email report"));
            activity.get().onBackPressed();
            assertTrue(find(content, FilterHeroView.class).isShown());
            assertNull(shownButton(content, "Email report"));
            assertFalse(activity.get().isFinishing());
        }
    }

    @Test
    public void homeShowsTheLatestOfferAndATapOnItOpensOffers() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            TextView reason = shownTextContaining(content, "Below your per-mile rate");
            assertNotNull("the latest offer is on Home", reason);
            ((View) reason.getParent().getParent()).performClick();
            settle();
            assertTrue(find(content, DecisionChartView.class).isShown());
            assertTrue(findTab(content, "Offers").isSelected());
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
            List<Button> buttons = new ArrayList<>();
            collectButtons(activity.get().findViewById(android.R.id.content), buttons);
            assertTrue(buttons.size() >= 10);
            for (Button button : buttons) assertFalse("unlabeled button", button.getText().toString().trim().isEmpty());
        }
    }

    @Test
    public void emailReportIsAddressedToTheUserWithDecisionHistory() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            fieldLabeled(content, "Your email").setText(" me@example.com ");
            findButton(content, "Email report").performClick();

            Intent sent = Shadows.shadowOf(app).getNextStartedActivity();
            assertEquals(Intent.ACTION_SEND, sent.getAction());
            assertEquals(Arrays.asList("me@example.com"), Arrays.asList(sent.getStringArrayExtra(Intent.EXTRA_EMAIL)));
            assertTrue(sent.getStringExtra(Intent.EXTRA_SUBJECT).startsWith("Offer Filter diagnostics"));
            String body = sent.getStringExtra(Intent.EXTRA_TEXT);
            assertTrue(body.contains("== Decision history"));
            assertTrue(body.contains("DECLINE | pay $7.90 | needed $10.80"));
            assertEquals("mailto:", sent.getSelector().getDataString());
            assertEquals("me@example.com", FilterStore.reportEmail(app));
        }
    }

    @Test
    public void emailReportNeedsAnAddress() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            findButton(activity.get().findViewById(android.R.id.content), "Email report").performClick();
            assertNull(Shadows.shadowOf(app).getNextStartedActivity());
        }
    }

    @Test
    public void recentDecisionsAppearWithTheirReasons() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertNotNull(findText(content, "$7.90 · needed $10.80"));
            assertNotNull(findTextContaining(content, "Below your per-mile rate"));
        }
    }

    @Test
    public void tappingAChartColumnShowsWhatWasRead() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            openTab(content, "Offers");
            DecisionChartView chart = findChart(content);
            assertNotNull(chart);
            assertNull(chart.selectedEntry());

            float density = app.getResources().getDisplayMetrics().density;
            float left = 44 * density;
            float slot = (chart.getWidth() - 4 * density - left) / DecisionChartView.SLOTS;
            float x = left + slot * (DecisionChartView.SLOTS - 0.5f);
            chart.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, 40, 0));
            chart.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, x, 40, 0));

            assertEquals(Integer.valueOf(790), chart.selectedEntry().facts.payCents);
            assertNotNull(findTextContaining(content, "Read: $7.90"));
        }
    }

    @Test
    public void reportingNeedsATokenAndThenSendsTheOfferWithTheUsersNote() throws Exception {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            openTab(content, "Offers");
            View row = (View) shownTextContaining(content, "Below your per-mile rate").getParent().getParent();
            row.performClick();
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

            row.performClick();
            row.performClick();
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
    public void theFilterPictureShowsTheLast24HoursAndTheState() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        DecisionLog.record(app, declinedEntry());
        DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis() - 60_000, DecisionLog.Source.SCREEN,
                false, new OfferSnapshot(2500, 9.1, 30, 3), 2000, OfferRule.Result.KEEP, "meets enabled rules",
                DecisionLog.Action.PASSES, true, Collections.emptyList()));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            FilterHeroView hero = find(content, FilterHeroView.class);
            assertEquals("Auto-decline on. Last 24 hours: 1 passed, 1 filtered, 0 to review.",
                    hero.getContentDescription().toString());
            assertEquals("the mascot is cheerful while on", Mascot.Mood.HAPPY, hero.mood());

            findButton(content, "Pause auto-decline").performClick();
            assertTrue(hero.getContentDescription().toString().startsWith("Auto-decline paused."));
            assertEquals("and asleep while paused", Mascot.Mood.SLEEPY, hero.mood());
        }
    }

    @Test
    public void theRulesPictureFollowsTheRulesAsTheyAreTyped() {
        FilterStore.save(app, new FilterSettings(true, 700, 0, 0, 0, 0));
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            RuleMeterView meter = find(content, RuleMeterView.class);
            // The example is the latest fully read offer: 21 min, 7.2 mi, 2 stops.
            assertEquals("An offer like 21 min · 7.2 mi · 2 stops: needs $7.00",
                    meter.getContentDescription().toString());

            fieldLabeled(content, "Per mile ($)").setText("1.50");
            assertEquals("An offer like 21 min · 7.2 mi · 2 stops: needs $10.80",
                    meter.getContentDescription().toString());
            fieldLabeled(content, "Max stops (1 order = 2)").setText("1");
            assertTrue(meter.getContentDescription().toString().endsWith("declined. At most 1 stop: this one has 2"));
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
            openTab(content, "Rules");
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(star.isShown());
            assertEquals("Minimums, set and adaptive now. Pay: set $7.00, adaptive more than $14.20. "
                    + "Per mile: set $1.50, adaptive $2.37. Per minute: set $0.30, adaptive $0.59. "
                    + "Per stop: no set minimum, adaptive $7.10.", star.getContentDescription().toString());
            // No offers yet, so the example is the meter's typical one; the largest ask is "more than $14.20".
            assertNotNull(findText(content,
                    "Farther out asks more of an offer like 20 min · 5 mi · 2 stops. Rings are $5.00 apart."));

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
    public void anOpenedHistoryRowDrawsTheOffer() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertNull("drawn only when opened", find(content, OfferCardView.class));
            openTab(content, "Offers");
            ((View) shownTextContaining(content, "Below your per-mile rate").getParent().getParent()).performClick();
            OfferCardView card = find(content, OfferCardView.class);
            assertEquals("Paid $7.90, needed $10.80. 7.2 mi · 21 min · 2 stops",
                    card.getContentDescription().toString());
        }
    }

    @Test
    public void acceptedOffersRaiseTheBestRatesUntilReset() {
        // Nothing is learned while the adaptive minimum is off, or while auto-decline is paused.
        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0, false, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(3000, 6.0, 24, 2));
        FilterStore.save(app, new FilterSettings(false, 1000, 0, 0, 0, 0, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(3000, 6.0, 24, 2));
        assertTrue(FilterStore.load(app).best.isEmpty());
        assertEquals("the payout baseline still follows every acceptance", 3000,
                FilterStore.load(app).lastAcceptedCents);

        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2));
        FilterStore.recordAccepted(app, new OfferSnapshot(900, 6.0, 24, 2));
        FilterSettings saved = FilterStore.load(app);
        assertEquals(900, saved.lastAcceptedCents);
        assertEquals("the lower offer is the last one, not a new best", "$0.59/min, $2.37/mi, $7.10/stop",
                saved.best.summary());

        // Saving rules keeps what was learned; Reset starts over.
        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0, true, 0));
        assertEquals("$0.59/min, $2.37/mi, $7.10/stop", FilterStore.load(app).best.summary());
        FilterStore.resetAccepted(app);
        assertTrue(FilterStore.load(app).best.isEmpty());
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
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
            assertEquals(DecisionLog.Action.SILENT_CARD, recent.get(0).action);
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
        assertEquals("Whole route: Below your minimum pay",
                MainActivity.plainReason("combined route fails: flat minimum"));
        assertEquals("Pay not readable", MainActivity.plainReason("pay not found"));
        DecisionLog.Entry fromNotification = new DecisionLog.Entry(1, DecisionLog.Source.NOTIFICATION, false,
                OfferSnapshot.UNKNOWN, 0, OfferRule.Result.REVIEW, "pay not found", DecisionLog.Action.SILENT_CARD,
                true, Collections.emptyList());
        assertEquals("Notification showed no pay", MainActivity.plainReason(fromNotification));
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

    /** The tab bar item named {@code name}: clickable text that is not a button. */
    private static TextView findTab(View view, String name) {
        if (view instanceof TextView && !(view instanceof Button) && view.isClickable()
                && name.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findTab(group.getChildAt(i), name);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Taps a tab, as a user would, and lets the page swap and a layout pass run. */
    private static void openTab(View root, String name) {
        TextView tab = findTab(root, name);
        assertNotNull(name, tab);
        tab.performClick();
        settle();
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

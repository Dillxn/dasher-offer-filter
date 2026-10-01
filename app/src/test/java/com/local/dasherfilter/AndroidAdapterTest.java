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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
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
    public void aFewMinutesOfShoppingNeverPushTheOffersOutOfAReport() {
        DiagnosticLog.log(app, "screen", "offer|OFFER-MARKER $7.90 7.2 mi 21 min");
        String big = String.join("", Collections.nCopies(3900, "z"));
        for (int i = 0; i < 40; i++) DiagnosticLog.logScreen(app, "other labels=[Item " + i + " " + big + "]");
        String report = DiagnosticLog.report(app);
        assertTrue("the offer is still in the report", report.contains("OFFER-MARKER"));
        assertTrue(report.contains("== Dasher's other screens (newest)"));
        assertTrue("the newest screen is there", report.contains("Item 39 "));
        assertTrue(String.valueOf(report.length()), report.length() <= 60_000 + 40);
        // Each section's window starts at a whole line.
        String screens = report.substring(report.indexOf("== Dasher's other screens (newest)\n") + 35);
        assertTrue(screens, screens.startsWith("[older entries omitted]\n20") || screens.startsWith("20"));
    }

    @Test
    public void screenCaptureIsAutomaticAndKeepsOnlyTheLastDay() throws Exception {
        assertTrue("on from the start, with no switch", DiagnosticLog.isEnabled(app));

        // An entry from two days ago is dropped; today's stays.
        String old = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS XXX", java.util.Locale.US)
                .format(new java.util.Date(System.currentTimeMillis() - 2 * DiagnosticLog.KEEP_MS));
        java.io.File log = new java.io.File(app.getFilesDir(), "offer-filter-diagnostics.log");
        java.nio.file.Files.write(log.toPath(),
                (old + " [screen] other labels=[Two days old]\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        DiagnosticLog.log(app, "screen", "other labels=[Aisle 12]");
        String kept = DiagnosticLog.read(app);
        assertTrue(kept, kept.contains("Aisle 12"));
        assertFalse(kept, kept.contains("Two days old"));

        // The diagnostic report still carries updater status, and says capture is automatic.
        Updater.status(app, "Synthetic updater error for test");
        String report = DiagnosticLog.report(app);
        assertTrue(report.contains("Synthetic updater error for test"));
        assertTrue(report.contains("Screen text capture: automatic"));
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
    public void aBackgroundOfferWithAPlusAmountBesideATotalIsNeverHiddenOrDeclined() {
        // A notification's text may be cut short: the "+$" bound that can decline a screen offer is never used here.
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        ServiceController<OfferNotificationService> controller =
                Robolectric.buildService(OfferNotificationService.class).create();
        try {
            Notification payload = new Notification.Builder(app, "source")
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle("New Delivery!")
                    .setStyle(new Notification.InboxStyle()
                            .addLine("+$1").addLine("$7.35").addLine("2 stops (7.1 mi) • 23 min"))
                    .build();
            controller.get().onNotificationPosted(new StatusBarNotification("com.doordash.driverapp",
                    "com.doordash.driverapp", 3, "NEW_ORDER", 10001, 0, 0, payload,
                    android.os.Process.myUserHandle(), System.currentTimeMillis()), null);
            assertEquals(1, notifications().size());
            assertEquals(OfferAlerts.REVIEW_CHANNEL_ID, notifications().getAllNotifications().get(0).getChannelId());
            DecisionLog.Entry entry = DecisionLog.recent(app, 1).get(0);
            assertEquals(OfferRule.Result.REVIEW, entry.result);
            assertEquals("pay not found", entry.reason);
            assertEquals("CHECK_BELL", entry.action.name());
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
        assertTrue(report.contains("per-minute cents=30; per-stop cents=100; max stops=3; rising offers=true; "
                + "highest accepted cents=2500"));
        assertTrue(report.contains("$1.00 per stop"));
        assertTrue(report.contains("best accepted=$1.00/min, $4.17/mi, $12.50/stop"));
        assertTrue(report.contains("Notification access granted: false"));
    }

    @Test
    public void theOldExtraStopFeeIsRetiredNotReadAsAPerStopMinimum() {
        // Saved by an older version: $2.00 added for each stop after two, beside a $7.00 minimum and $1.50/mi.
        android.content.SharedPreferences prefs = app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("enabled", true).putInt("flat", 700).putInt("mile", 150).putInt("stop", 200)
                .putInt("max_stops", 3).commit();

        FilterSettings loaded = FilterStore.load(app);
        assertEquals("the fee is not a per-stop minimum, so per stop starts off", 0, loaded.perStopCents);
        assertFalse("the old key is gone", prefs.contains("stop"));
        assertFalse(prefs.contains("per_stop"));
        // Everything else is as it was.
        assertTrue(loaded.enabled);
        assertEquals(700, loaded.flatCents);
        assertEquals(150, loaded.perMileCents);
        assertEquals(3, loaded.maxStops);
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("[rules] the old extra-stop fee of $2.00 was retired"));
        // The user is told too: in the status, and once in Settings. Other rules remain, so the filter stays on.
        String notice = "Your $2.00 extra-stop fee was removed: Per stop is now a minimum. Set one if you want it.";
        assertTrue(FilterStore.lastStatus(app), FilterStore.lastStatus(app).endsWith("\n" + notice));
        assertEquals(notice, FilterStore.takeStopFeeNotice(app));
        assertNull("taken once", FilterStore.takeStopFeeNotice(app));

        // Retired once: loading again notes nothing more.
        FilterStore.load(app);
        String again = DiagnosticLog.read(app);
        assertEquals(again, again.indexOf("extra-stop fee"), again.lastIndexOf("extra-stop fee"));
        assertNull(FilterStore.takeStopFeeNotice(app));

        // A per-stop minimum saved now lives under its own key and reads back as set.
        FilterStore.save(app, new FilterSettings(true, 700, 150, 0, 350, 3));
        assertEquals(350, FilterStore.load(app).perStopCents);
        assertEquals(350, prefs.getInt("per_stop", 0));
        assertFalse(prefs.contains("stop"));
    }

    @Test
    public void aStoredZeroExtraStopFeeIsRemovedSilently() {
        // Older versions saved 0 for a fee that was never set: that was no rule, so there is nothing to tell.
        android.content.SharedPreferences prefs = app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("enabled", true).putInt("flat", 700).putInt("stop", 0).commit();

        FilterSettings loaded = FilterStore.load(app);
        assertFalse("the old key is gone all the same", prefs.contains("stop"));
        assertTrue(loaded.enabled);
        assertEquals(700, loaded.flatCents);
        assertEquals(0, loaded.perStopCents);
        assertFalse(DiagnosticLog.read(app).contains("extra-stop fee"));
        assertEquals("No offer evaluated yet.", FilterStore.lastStatus(app));
        assertNull(FilterStore.takeStopFeeNotice(app));
    }

    @Test
    public void aFeeOnlyProfileEndsPausedWithTheNotice() {
        // The fee was the only rule: without it the filter would be on and filter nothing.
        android.content.SharedPreferences prefs = app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("enabled", true).putInt("flat", 0).putInt("stop", 150).commit();

        FilterSettings loaded = FilterStore.load(app);
        assertFalse(prefs.contains("stop"));
        assertFalse("paused in the same edit that removed the fee", loaded.enabled);
        assertFalse(prefs.getBoolean("enabled", true));
        assertFalse(loaded.hasAnyRule());
        String notice = "Your $1.50 extra-stop fee was removed: Per stop is now a minimum. Set one if you want it. "
                + "It was your only rule, so auto-decline is paused.";
        assertTrue(FilterStore.lastStatus(app), FilterStore.lastStatus(app).endsWith("\n" + notice));
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("[rules] the old extra-stop fee of $1.50 was retired"));
        assertTrue(log, log.contains("no rule was left, so auto-decline was paused"));
        assertEquals(notice, FilterStore.takeStopFeeNotice(app));

        // Already paused, a fee-only profile is only told, never switched on or off.
        prefs.edit().clear().putBoolean("enabled", false).putInt("stop", 150).commit();
        assertFalse(FilterStore.load(app).enabled);
        assertEquals("Your $1.50 extra-stop fee was removed: Per stop is now a minimum. Set one if you want it.",
                FilterStore.takeStopFeeNotice(app));
    }

    @Test
    public void theRetiredFeeNoticeShowsOnceBesidePerStop() {
        android.content.SharedPreferences prefs = app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("enabled", true).putInt("flat", 700).putInt("stop", 200).commit();
        String notice = "Your $2.00 extra-stop fee was removed: Per stop is now a minimum. Set one if you want it.";
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertNull("not on the main page", shownTextContaining(content, "extra-stop fee"));

            iconButton(content, "Settings").performClick();
            settle();
            TextView shown = shownTextContaining(content, notice);
            assertNotNull("the first time Settings opens, beside Per stop", shown);
            EditText perStop = fieldLabeled(content, "Per stop ($)");
            assertEquals("0.00", perStop.getText().toString());
            assertTrue("in the row of the Per stop field",
                    isDescendant((View) perStop.getParent().getParent().getParent(), shown));
            assertNull("in place of the hint", shownTextContaining(content, "0 turns a rule off."));

            iconButton(content, "Back").performClick();
            iconButton(content, "Settings").performClick();
            settle();
            assertNull("once only", shownTextContaining(content, "extra-stop fee"));
            assertNotNull(shownTextContaining(content, "0 turns a rule off."));

            activity.recreate();
            content = activity.get().findViewById(android.R.id.content);
            assertNull("nor after the page is rebuilt", shownTextContaining(content, "extra-stop fee"));
        }
    }

    private static boolean isDescendant(View ancestor, View view) {
        for (View at = view; at != null; at = at.getParent() instanceof View ? (View) at.getParent() : null) {
            if (at == ancestor) return true;
        }
        return false;
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
            assertNull("capture is automatic: no switch", findTextView(content, "Capture full screen text"));
            assertNotNull(findTextView(content, "stays on this phone for reports"));

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
            DiagnosticLog.log(app, "screen", "other labels=[Customer's order]");
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(DecisionLog.recent(app, 10).isEmpty());
            assertFalse("the captured screen text goes too", DiagnosticLog.read(app).contains("Customer's order"));
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
                assertEquals("https://venmo.com/Offer-Filter?txn=pay&note=Dash%20Buddy%20tip",
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
                    + "Per stop: set $1.00, adaptive $7.10.", star.getContentDescription().toString());
            // No offers yet, so the example is a typical one; the largest ask is "more than $14.20".
            assertNotNull(findText(content, "An offer like 20 min · 5 mi · 2 stops needs $14.21."));
            // The per-stop spoke holds the set minimum as the example's 2 stops × $1.00, beside the adaptive 2 × $7.10.
            assertEquals(200, star.setAsks(3), 0);
            assertEquals(1420, star.learnedAsks(3), 0);

            // A per-stop minimum is a floor like the others: $8.00 a stop makes the example need $16.00.
            fieldLabeled(content, "Per stop ($)").setText("8.00");
            assertEquals(1600, star.setAsks(3), 0);
            assertTrue(star.getContentDescription().toString().contains("Per stop: set $8.00, adaptive $7.10."));
            assertNotNull(findText(content, "An offer like 20 min · 5 mi · 2 stops needs $16.00."));
            fieldLabeled(content, "Per stop ($)").setText("0");
            assertTrue(Double.isNaN(star.setAsks(3)));
            assertTrue(star.getContentDescription().toString()
                    .contains("Per stop: no set minimum, adaptive $7.10."));

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
            assertFalse("a whole screen keeps it in the page, drawn in full", star.beside());
            assertNotSame("not in the header", iconDescribed(content, "Settings").getParent(), star.getParent());
            assertTrue("drawn as the sky itself, behind the mascot", star.backdrop());

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
    public void aShortWindowKeepsTheConstellationTheMascotItsCountsTheSkylineAndTheMap() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            FilterHeroView mascot = find(content, FilterHeroView.class);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(mascot.isShown());
            assertTrue("the map stays", find(content, AreaMapView.class).isShown());
            assertTrue("the constellation stays, up in the sky", star.isShown());
            int[] starAt = new int[2];
            int[] mascotAt = new int[2];
            star.getLocationInWindow(starAt);
            mascot.getLocationInWindow(mascotAt);
            assertTrue("beside the sun, above the mascot", starAt[1] < mascotAt[1]);
            assertNotNull("in the header, with the sun and Settings",
                    iconDescribed((View) star.getParent(), "Settings"));
            assertTrue("drawn with its icons beside the circle", star.beside());
            View title = iconDescribed(content, "Offer Filter");
            assertTrue("the page's name keeps room, so screen readers reach it", title != null && title.getWidth() > 0);
            star.performClick();
            assertTrue("a tap still opens the minimums", fieldLabeled(content, "Minimum pay ($)").isShown());
            iconButton(content, "Back").performClick();
            assertTrue("the skyline stays", findChart(content).isShown());
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void halfOfASplitScreenFitsOneScreenWithEverythingOnIt() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        DecisionLog.record(app, declinedEntry());
        // Screen reading on, as while dashing; background offers still off, so one line asks for a fix. The other
        // half is not Dasher, so the page keeps its map.
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        OfferFilterService.sawDasherBeside(0);
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            int width = content.getResources().getDisplayMetrics().widthPixels;
            int height = content.getResources().getDisplayMetrics().heightPixels;
            content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            content.layout(0, 0, width, height);
            ScenePage scene = find(content, ScenePage.class);
            android.widget.ScrollView page = (android.widget.ScrollView) scene.getParent();
            assertTrue("no scrolling: " + scene.getHeight() + " in " + page.getHeight(),
                    scene.getHeight() <= page.getHeight());
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(star.beside());
            assertTrue(findChart(content).isShown());
            AreaMapView map = find(content, AreaMapView.class);
            assertTrue(map.isShown() && map.getHeight() >= new Ui(app).dp(96));
            DecisionChartView chart = findChart(content);
            assertEquals("the skyline keeps a fixed height rather than being squeezed", new Ui(app).dp(56),
                    chart.getHeight());
            assertTrue("its flags stay whole inside it", chart.highestWithin(0, chart.getWidth()) >= 0);
            // The skyline's street is still the horizon, above the map.
            int[] mapAt = new int[2];
            int[] sceneAt = new int[2];
            map.getLocationInWindow(mapAt);
            scene.getLocationInWindow(sceneAt);
            assertTrue("the horizon is above the map", scene.horizonY() <= mapAt[1] - sceneAt[1]);
        } finally {
            service.destroy();
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void besideDasherThePageShowsNoSecondMapAndTheSkyTakesItsRoom() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        DecisionLog.record(app, declinedEntry());
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            int width = content.getResources().getDisplayMetrics().widthPixels;
            int height = content.getResources().getDisplayMetrics().heightPixels;
            content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            content.layout(0, 0, width, height);
            AreaMapView map = find(content, AreaMapView.class);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertFalse("Dasher's map is right below: no second one", map.isShown());
            assertTrue("the constellation stands in the sky at full size", star.isShown() && !star.beside());
            assertNotSame("not in the header", iconDescribed(content, "Settings").getParent(), star.getParent());
            assertTrue("spread across the sky: " + star.skyRadius(), star.skyRadius() >= new Ui(app).dp(120));
            assertTrue("the skyline stays", findChart(content).isShown());
            ScenePage scene = find(content, ScenePage.class);
            android.widget.ScrollView page = (android.widget.ScrollView) scene.getParent();
            assertTrue("no scrolling: " + scene.getHeight() + " in " + page.getHeight(),
                    scene.getHeight() <= page.getHeight());
            assertTrue("the constellation gets the room the map left: " + star.getHeight(),
                    star.getHeight() >= new Ui(app).dp(110));

            // Dasher leaves the other half: our map comes back, and the constellation moves up to make room.
            OfferFilterService.sawDasherBeside(0);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertEquals(View.VISIBLE, map.getVisibility());
            assertTrue("in the header again", star.beside());
            assertNotNull(iconDescribed((View) star.getParent(), "Settings"));
        } finally {
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp-xxhdpi")
    public void theTicketDrawerClosesWhenDraggedDownAndSpringsBackOtherwise() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            layOut(content);
            openTicket(content);
            layOut(content);
            DrawerCard card = find(content, DrawerCard.class);
            View sheet = (View) card.getParent();
            assertEquals(View.VISIBLE, sheet.getVisibility());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));

            // A short, slow pull: it follows the finger, then springs back.
            drag(card, 0.1f, 400);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
            assertEquals(View.VISIBLE, sheet.getVisibility());
            assertEquals(0f, card.getTranslationY(), 0.5f);

            // Pulled past a quarter of its height: it closes.
            drag(card, 0.5f, 400);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
            assertEquals(View.GONE, sheet.getVisibility());

            // Screen readers close it with an action.
            openTicket(content);
            layOut(content);
            assertTrue(card.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_DISMISS, null));
            assertEquals(View.GONE, sheet.getVisibility());
        }
    }

    private static void layOut(View content) {
        int width = content.getResources().getDisplayMetrics().widthPixels;
        int height = content.getResources().getDisplayMetrics().heightPixels;
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        content.layout(0, 0, width, height);
    }

    /** A finger on the card's handle, pulled down {@code share} of its height over {@code ms}. */
    private static void drag(View card, float share, long ms) {
        long start = android.os.SystemClock.uptimeMillis();
        float x = card.getWidth() / 2f;
        float y = 4;
        float to = card.getHeight() * share;
        card.dispatchTouchEvent(MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, x, y, 0));
        for (int step = 1; step <= 8; step++) {
            card.dispatchTouchEvent(MotionEvent.obtain(start, start + ms * step / 8, MotionEvent.ACTION_MOVE, x,
                    y + to * step / 8, 0));
        }
        card.dispatchTouchEvent(MotionEvent.obtain(start, start + ms + 50, MotionEvent.ACTION_UP, x, y + to, 0));
    }

    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void besideDasherTheMascotAndTheConstellationBehindItEachTakeTheirOwnTaps() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        FilterStore.save(app, new FilterSettings(true, 2000, 150, 0, 0, 0));
        DecisionLog.record(app, declinedEntry());
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            int width = content.getResources().getDisplayMetrics().widthPixels;
            int height = content.getResources().getDisplayMetrics().heightPixels;
            content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            content.layout(0, 0, width, height);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            FilterHeroView mascot = find(content, FilterHeroView.class);
            ViewGroup sky = (ViewGroup) star.getParent();
            assertTrue("the mascot and its counts float over the constellation", mascot.placed());
            float middleX = star.skyX();
            float middleY = star.skyY();
            assertTrue("the circle's middle is inside the mascot's own box, so the tap must pass through it",
                    middleX >= mascot.getLeft() && middleX <= mascot.getRight()
                            && middleY >= mascot.getTop() && middleY <= mascot.getBottom());

            tap(sky, mascot.getLeft() + mascot.mascotX(), mascot.getTop() + mascot.mascotY());
            assertFalse("a tap on the mascot pauses", FilterStore.load(app).enabled);
            assertFalse(fieldLabeled(content, "Minimum pay ($)").isShown());

            tap(sky, middleX, middleY);
            assertTrue("a tap on the constellation opens the minimums",
                    fieldLabeled(content, "Minimum pay ($)").isShown());
            assertFalse("and does not resume", FilterStore.load(app).enabled);
        } finally {
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void besideDasherTheConstellationRunsPastThePageAndALineCrossesItWithoutShrinkingIt() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        DecisionLog.record(app, declinedEntry());
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        ServiceController<OfferNotificationService> listener =
                Robolectric.buildService(OfferNotificationService.class).create();
        listener.get().onListenerConnected();
        OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            layOut(content);
            Ui ui = new Ui(app);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            FilterHeroView mascot = find(content, FilterHeroView.class);
            ScenePage scene = find(content, ScenePage.class);
            View sky = (View) star.getParent();
            assertNull("nothing to fix yet", shownTextContaining(content, "Background offers are off"));
            float radius = star.skyRadius();
            int width = sky.getWidth();
            assertTrue("as large as the page's width allows: " + radius, radius >= ui.dp(180));
            assertTrue("its middle a little right of the page's", star.skyX() > width / 2f);
            assertTrue("its rings run on past the page's edge", star.skyX() + radius > width);
            android.graphics.RectF counts = new android.graphics.RectF();
            mascot.countsAt(counts);
            counts.offset(mascot.getLeft(), mascot.getTop());
            List<android.graphics.RectF> icons = new ArrayList<>();
            star.iconsAt(icons);
            assertEquals(4, icons.size());
            for (android.graphics.RectF icon : icons) {
                assertTrue("each icon inside the page: " + icon, icon.left >= 0 && icon.right <= width);
                assertFalse("and clear of the counts: " + icon, android.graphics.RectF.intersects(icon, counts));
            }
            assertFalse("the rings' dollars are on the page", star.levelWords().isEmpty());
            List<android.graphics.RectF> words = scene.words();
            assertTrue("the scene knows the counts are words, so no star lands on them",
                    containsPoint(words, counts.centerX() + sky.getLeft(), counts.centerY() + sky.getTop()));

            // Background offers go off: the line asking for a fix crosses the circle's lower part; the circle keeps
            // its size, and the mascot stays above the line.
            listener.destroy();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            layOut(content);
            TextView problem = shownTextContaining(content, "Background offers are off");
            assertNotNull(problem);
            View row = (View) problem.getParent();
            assertEquals("a line does not shrink the constellation", radius, star.skyRadius(), 1f);
            int[] rowAt = new int[2];
            int[] skyAt = new int[2];
            row.getLocationInWindow(rowAt);
            sky.getLocationInWindow(skyAt);
            float rowTop = rowAt[1] - skyAt[1];
            assertTrue("the mascot stands above the line", mascot.getTop() + mascot.mascotY() < rowTop);
            android.graphics.RectF rowBox = new android.graphics.RectF(rowAt[0] - skyAt[0], rowTop,
                    rowAt[0] - skyAt[0] + row.getWidth(), rowTop + row.getHeight());
            icons.clear();
            star.iconsAt(icons);
            assertEquals("every icon still shows, the lower ones stepped above their spokes' ends", 4, icons.size());
            for (android.graphics.RectF icon : icons) {
                assertTrue("each icon inside the page: " + icon, icon.left >= 0 && icon.right <= width);
                assertFalse("and clear of the line: " + icon + " / " + rowBox,
                        android.graphics.RectF.intersects(icon, rowBox));
            }
            assertTrue("the scene knows the line is words too", containsPoint(scene.words(),
                    rowAt[0] + row.getWidth() / 2f - skyAt[0] + sky.getLeft(), rowTop + row.getHeight() / 2f
                            + sky.getTop()));
        } finally {
            if (OfferNotificationService.isConnected()) listener.destroy();
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void besideDasherOnAFreshPageThreeLinesCrossTheConstellationAndTheMascotRisesAboveThem() {
        FilterStore.save(app, new FilterSettings(false, 0, 0, 0, 0, 0));
        Shadows.shadowOf(app.getSystemService(android.app.NotificationManager.class)).setNotificationsEnabled(false);
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            layOut(content);
            Ui ui = new Ui(app);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            FilterHeroView mascot = find(content, FilterHeroView.class);
            View sky = (View) star.getParent();
            int[] skyAt = new int[2];
            sky.getLocationInWindow(skyAt);
            float linesTop = Float.MAX_VALUE;
            for (String line : new String[] {"Tap to set up rules", "Background offers are off", "Alerts are blocked"}) {
                TextView shown = shownTextContaining(content, line);
                assertNotNull(line, shown);
                int[] at = new int[2];
                shown.getLocationInWindow(at);
                linesTop = Math.min(linesTop, at[1] - skyAt[1]);
            }
            assertTrue("three lines do not shrink the constellation: " + star.skyRadius(),
                    star.skyRadius() >= ui.dp(180));
            assertTrue("they cross its lower part", star.skyY() + star.skyRadius() > linesTop);
            float mascotY = mascot.getTop() + mascot.mascotY();
            float mascotX = mascot.getLeft() + mascot.mascotX();
            assertTrue("the mascot rises above them", mascotY + mascot.mascotRadius() <= linesTop);
            android.graphics.RectF counts = new android.graphics.RectF();
            mascot.countsAt(counts);
            counts.offset(mascot.getLeft(), mascot.getTop());
            assertTrue("and stays below the counts", mascotY - mascot.mascotRadius() >= counts.bottom);
            assertTrue("inside the page", mascotX - mascot.mascotRadius() >= 0);
            // Between the two left spokes, clear of the upper one it rose towards.
            double spread = Math.toRadians(MinimumsStarView.SPREAD);
            double dx = star.skyX() - mascotX;
            double dy = star.skyY() - mascotY;
            double fromUpperSpoke = dx * Math.sin(spread) - dy * Math.cos(spread);
            assertTrue("clear of the upper left spoke: " + fromUpperSpoke,
                    fromUpperSpoke >= mascot.mascotRadius() + ui.dp(8));
        } finally {
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void theSignpostStandsOverAFullSkylineAndClearOfTheSkysWordsAndIcons() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        long now = System.currentTimeMillis();
        // A full skyline, its oldest (leftmost) offers the tallest, where the signpost stands.
        for (int i = 0; i < DecisionChartView.SLOTS; i++) {
            int pay = i < 4 ? 3000 : 900 + i * 50;
            DecisionLog.record(app, new DecisionLog.Entry(now - (DecisionChartView.SLOTS - i) * 60_000L,
                    DecisionLog.Source.SCREEN, false, new OfferSnapshot(pay, 5.0, 20, 2), 1000,
                    pay >= 1000 ? OfferRule.Result.KEEP : OfferRule.Result.DECLINE,
                    pay >= 1000 ? "meets enabled rules" : "flat minimum", DecisionLog.Action.PASSES, true,
                    Collections.emptyList()));
        }
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        ServiceController<OfferNotificationService> listener =
                Robolectric.buildService(OfferNotificationService.class).create();
        listener.get().onListenerConnected();
        OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            layOut(content);
            ScenePage scene = find(content, ScenePage.class);
            DecisionChartView chart = findChart(content);
            scene.setPlace("Mission District");
            scene.draw(new Canvas(Bitmap.createBitmap(scene.getWidth(), scene.getHeight(), Bitmap.Config.ARGB_8888)));
            android.graphics.RectF sign = scene.signBoard();
            assertNotNull("it rises over the buildings rather than being left out", sign);
            int[] chartAt = new int[2];
            int[] sceneAt = new int[2];
            chart.getLocationInWindow(chartAt);
            scene.getLocationInWindow(sceneAt);
            float chartLeft = chartAt[0] - sceneAt[0];
            float highest = chartAt[1] - sceneAt[1] + chart.highestWithin(sign.left - chartLeft,
                    sign.right - chartLeft);
            assertTrue("above the buildings and flags under it: " + sign + " / " + highest, sign.bottom <= highest);
            for (android.graphics.RectF box : scene.words()) {
                assertFalse("clear of the sky's words: " + box, android.graphics.RectF.intersects(sign, box));
            }
            List<android.graphics.RectF> icons = new ArrayList<>();
            MinimumsStarView star = find(content, MinimumsStarView.class);
            star.iconsAt(icons);
            int[] starAt = new int[2];
            star.getLocationInWindow(starAt);
            for (android.graphics.RectF icon : icons) {
                icon.offset(starAt[0] - sceneAt[0], starAt[1] - sceneAt[1]);
                assertFalse("clear of the constellation's icons: " + icon,
                        android.graphics.RectF.intersects(sign, icon));
            }
        } finally {
            listener.destroy();
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void aKnobDraggedAlongItsSpokeSavesTheSnappedMinimumAndSettingsShowsIt() {
        FilterStore.save(app, new FilterSettings(true, 1000, 80, 30, 100, 3, true, 0));
        // The example offer: 7.2 mi, 21 min, 2 stops.
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue("a whole screen draws the constellation as the sky", star.backdrop());
            float[] knob = star.knobAt(1);
            assertNotNull("the per-mile spoke has a knob", knob);
            float[] middle = {star.skyX(), star.skyY()};
            long ring = star.ringCents();
            Ui ui = new Ui(app);

            // Out along the per-mile spoke, the finger straying a little off it: only the distance along it counts.
            float[] to = alongSpoke(knob, 1, ui.dp(60), ui.dp(9));
            dragKnob(content, star, knob, to, () -> {
                assertTrue(star.dragging());
                assertEquals("nothing is saved until it is let go", 80, FilterStore.load(app).perMileCents);
            });
            double reach = along(middle, 1, to) / star.skyRadius() * ring * 3;
            int expected = (int) (Math.round(reach / 7.2 / 5) * 5);
            FilterSettings saved = FilterStore.load(app);
            assertTrue("higher than $0.80: " + expected, expected > 80);
            assertEquals("snapped to $0.05 on the chart's scale for 7.2 mi", expected, saved.perMileCents);
            assertEquals(String.format(java.util.Locale.US, "%.2f", expected / 100.0),
                    fieldLabeled(content, "Per mile ($)").getText().toString());
            assertTrue("still on", saved.enabled);
            assertEquals("max stops untouched", 3, saved.maxStops);
            assertArrayEquals("the other minimums untouched", new int[] {1000, expected, 30, 100}, saved.minimums());
            assertTrue(saved.risingOffers);
            assertFalse("a drag does not open Settings", fieldLabeled(content, "Minimum pay ($)").isShown());

            // Into the middle: the rule is off.
            settleSky(content);
            float[] now = star.knobAt(1);
            dragKnob(content, star, now, middle, null);
            assertEquals(0, FilterStore.load(app).perMileCents);
            assertEquals("0.00", fieldLabeled(content, "Per mile ($)").getText().toString());
            assertTrue("other rules remain, so it stays on", FilterStore.load(app).enabled);
            settleSky(content);
            float[] resting = star.knobAt(1);
            assertEquals("its hollow knob rests just outside the middle", ui.dp(20),
                    Math.hypot(resting[0] - middle[0], resting[1] - middle[1]), 1);

            // A tap on the circle away from the knobs, and a tap on a knob, still open the minimums.
            ViewGroup sky = (ViewGroup) star.getParent();
            tap(sky, middle[0] + star.skyRadius() * 0.3f, middle[1]);
            assertTrue(fieldLabeled(content, "Minimum pay ($)").isShown());
            iconButton(content, "Back").performClick();
            settleSky(content);
            float[] pay = star.knobAt(0);
            tap(sky, pay[0], pay[1]);
            assertTrue(fieldLabeled(content, "Minimum pay ($)").isShown());
            assertEquals("a tap sets nothing", 1000, FilterStore.load(app).flatCents);
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void theRingsHoldStillWhileAKnobMovesAndGrowToFitItWhenLetGo() {
        FilterStore.save(app, new FilterSettings(true, 700, 150, 30, 100, 0));
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            long ring = star.ringCents();
            float[] knob = star.knobAt(0);
            // Pushed out past the outer ring.
            float[] to = alongSpoke(knob, 0, star.skyRadius() * 1.4f, 0);
            dragKnob(content, star, knob, to, () -> {
                assertEquals("the scale holds still under the finger", ring, star.ringCents());
                // A new offer while the knob is held does not move the rings either.
                DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis(), DecisionLog.Source.SCREEN,
                        false, new OfferSnapshot(4800, 9.0, 30, 2), 1000, OfferRule.Result.KEEP,
                        "meets enabled rules", DecisionLog.Action.PASSES, true, Collections.emptyList()));
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
                assertTrue(star.dragging());
                assertEquals(ring, star.ringCents());
            });
            int pay = FilterStore.load(app).flatCents;
            assertTrue("past the outer ring's " + ring * 3 + ": " + pay, pay > ring * 3);
            assertEquals("in $0.50 steps", 0, pay % 50);
            assertTrue("the rings grew to fit it: " + star.ringCents(), star.ringCents() > ring);
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void screenReadersAdjustEachKnobByOneStepAndHearTheValue() {
        FilterStore.save(app, new FilterSettings(true, 700, 150, 30, 0, 3, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2));
        FilterStore.save(app, FilterStore.load(app).withMinimums(new int[] {700, 150, 30, 0}));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            android.view.accessibility.AccessibilityNodeProvider nodes = star.getAccessibilityNodeProvider();
            assertNotNull("each knob is its own control", nodes);
            android.view.accessibility.AccessibilityNodeInfo host = nodes.createAccessibilityNodeInfo(
                    android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID);
            assertEquals("four knobs and the adopt button", 5, host.getChildCount());
            android.view.accessibility.AccessibilityNodeInfo mile = nodes.createAccessibilityNodeInfo(1);
            assertEquals("Minimum per mile, $1.50; adaptive $2.37, learned", mile.getContentDescription().toString());
            assertEquals(android.widget.SeekBar.class.getName(), mile.getClassName().toString());
            assertTrue("a screen reader stops on it, not taking it for part of the clickable chart",
                    mile.isFocusable());
            if (Build.VERSION.SDK_INT >= 28) assertTrue(mile.isScreenReaderFocusable());
            assertEquals(1.5f, mile.getRangeInfo().getCurrent(), 0.001f);
            assertEquals(1000f, mile.getRangeInfo().getMax(), 0.001f);
            assertTrue(mile.getActionList().contains(
                    android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD));
            assertEquals("Minimum per stop, off; adaptive $7.10, learned",
                    nodes.createAccessibilityNodeInfo(3).getContentDescription().toString());
            assertFalse("an off knob steps only up", nodes.createAccessibilityNodeInfo(3).getActionList().contains(
                    android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD));

            assertTrue(nodes.performAction(1, android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                    null));
            assertEquals("one $0.05 step, saved", 155, FilterStore.load(app).perMileCents);
            assertEquals("1.55", fieldLabeled(content, "Per mile ($)").getText().toString());
            assertEquals("Minimum per mile, $1.55; adaptive $2.37, learned", star.lastSaid());
            assertTrue(nodes.performAction(1, android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                    null));
            assertTrue(nodes.performAction(1, android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                    null));
            assertEquals(145, FilterStore.load(app).perMileCents);
            assertTrue(nodes.performAction(0, android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                    null));
            assertEquals("pay steps $0.50", 750, FilterStore.load(app).flatCents);
            assertTrue(nodes.performAction(2, android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                    null));
            assertEquals("per minute steps $0.01", 29, FilterStore.load(app).perMinuteCents);
            assertTrue(nodes.performAction(3, android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                    null));
            assertEquals("per stop steps $0.25", 25, FilterStore.load(app).perStopCents);
            assertEquals("Minimum per stop, $0.25; adaptive $7.10, learned", star.lastSaid());
            FilterSettings saved = FilterStore.load(app);
            assertTrue(saved.enabled);
            assertEquals(3, saved.maxStops);
            assertEquals("what was learned stays", "$0.59/min, $2.37/mi, $7.10/stop", saved.best.summary());
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void besideDasherOneButtonMakesTheLearnedMinimumsTheSetOnesAndUndoesIt() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        FilterStore.save(app, new FilterSettings(true, 700, 150, 30, 100, 3, true, 0));
        DecisionLog.record(app, declinedEntry());
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(star.backdrop());
            assertNull("nothing learned yet, so no button", star.adoptBox());
            android.view.accessibility.AccessibilityNodeProvider nodes = star.getAccessibilityNodeProvider();
            assertEquals(4, nodes.createAccessibilityNodeInfo(
                    android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID).getChildCount());

            // An accepted offer teaches the adaptive minimums a best rate: now they ask more than the set ones.
            FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2));
            settleSky(content);
            android.graphics.RectF button = star.adoptBox();
            assertNotNull("the learned minimums can be made the set ones", button);
            assertEquals(MinimumsStarView.ADOPT_SAID,
                    nodes.createAccessibilityNodeInfo(MinimumsStarView.ADOPT_ID).getContentDescription().toString());
            assertTrue("a full touch target", button.width() >= new Ui(app).dp(48) - 1);
            for (int axis = 0; axis < 4; axis++) {
                float[] knob = star.knobAt(axis);
                assertTrue("clear of the knobs", Math.hypot(knob[0] - button.centerX(),
                        knob[1] - button.centerY()) >= new Ui(app).dp(24) + button.width() / 2 - 1);
            }
            List<android.graphics.RectF> icons = new ArrayList<>();
            star.iconsAt(icons);
            assertTrue("the scene keeps its clouds and stars off it",
                    containsPoint(icons, button.centerX(), button.centerY()));

            ViewGroup sky = (ViewGroup) star.getParent();
            tap(sky, button.centerX(), button.centerY());
            FilterSettings adopted = FilterStore.load(app);
            assertArrayEquals("pay beats $14.20, the rates match $2.366…/mi, $0.591…/min and $7.10/stop",
                    new int[] {1421, 237, 60, 710}, adopted.minimums());
            assertTrue("still on", adopted.enabled);
            assertEquals(3, adopted.maxStops);
            assertTrue("the adaptive minimum stays on, keeping what it learned",
                    adopted.risingOffers && adopted.lastAcceptedCents == 1420);
            assertEquals("14.21", fieldLabeled(content, "Minimum pay ($)").getText().toString());
            assertEquals("2.37", fieldLabeled(content, "Per mile ($)").getText().toString());
            assertEquals("0.60", fieldLabeled(content, "Per minute ($)").getText().toString());
            assertEquals("7.10", fieldLabeled(content, "Per stop ($)").getText().toString());
            assertTrue(star.lastSaid(), star.lastSaid().contains(
                    "Pay $14.21, per mile $2.37, per minute $0.60, per stop $7.10"));
            assertFalse("no page opens", fieldLabeled(content, "Minimum pay ($)").isShown());
            assertTrue("the button offers Undo", star.offeringUndo());
            assertEquals("Undo",
                    nodes.createAccessibilityNodeInfo(MinimumsStarView.ADOPT_ID).getContentDescription().toString());
            settleSky(content);
            assertEquals("Undo stays under the finger, though the set shape grew", button, star.adoptBox());

            // Undo puts the four back exactly.
            button = star.adoptBox();
            tap(sky, button.centerX(), button.centerY());
            assertArrayEquals(new int[] {700, 150, 30, 100}, FilterStore.load(app).minimums());
            assertEquals("1.50", fieldLabeled(content, "Per mile ($)").getText().toString());
            assertFalse(star.offeringUndo());
            assertTrue(FilterStore.load(app).enabled);

            // Adopted again, by a screen reader; Undo is offered for eight seconds, then the button goes.
            assertTrue(nodes.performAction(MinimumsStarView.ADOPT_ID,
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null));
            assertEquals(237, FilterStore.load(app).perMileCents);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(7000));
            assertTrue(star.offeringUndo());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
            assertFalse(star.offeringUndo());
            assertNull("set minimums no looser than the learned ones: no button", star.adoptBox());
            assertEquals(4, nodes.createAccessibilityNodeInfo(
                    android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID).getChildCount());
        } finally {
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void undoIsWithdrawnOnceAKnobChangesTheAdoptedMinimums() {
        FilterStore.save(app, new FilterSettings(true, 700, 150, 30, 100, 3, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2));
        try (ActivityController<MainActivity> activity =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            android.view.accessibility.AccessibilityNodeProvider nodes = star.getAccessibilityNodeProvider();
            assertTrue(nodes.performAction(MinimumsStarView.ADOPT_ID,
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null));
            assertTrue(star.offeringUndo());
            assertTrue(nodes.performAction(1, android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                    null));
            assertEquals("the next $0.05 step up from $2.37", 240, FilterStore.load(app).perMileCents);
            assertFalse("Undo would put back more than the adoption now", star.offeringUndo());
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void inAShortSplitWithAnotherAppTheHeaderChartHasNoKnobsOrButton() {
        FilterStore.save(app, new FilterSettings(true, 700, 150, 30, 100, 3, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2));
        OfferFilterService.sawDasherBeside(0);
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue("up in the header", star.beside());
            assertNull("no knobs", star.knobAt(1));
            assertNull("no button, though the learned minimums ask more", star.adoptBox());
            assertNull("no knobs for screen readers either", star.getAccessibilityNodeProvider());
            // A drag across it sets nothing; a tap opens the minimums.
            int[] at = new int[2];
            star.getLocationInWindow(at);
            float x = star.getWidth() * 0.6f;
            float y = star.getHeight() / 2f;
            long now = android.os.SystemClock.uptimeMillis();
            star.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0));
            star.dispatchTouchEvent(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_MOVE, x + 60, y - 30, 0));
            star.dispatchTouchEvent(MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_UP, x + 60, y - 30, 0));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertArrayEquals(new int[] {700, 150, 30, 100}, FilterStore.load(app).minimums());
            tap((ViewGroup) star.getParent(), star.getLeft() + x, star.getTop() + y);
            assertTrue(fieldLabeled(content, "Minimum pay ($)").isShown());
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void aKnobKeepsItsExactValueUntilMovedHalfAStepAlongItsSpoke() {
        // Adopted minimums sit between steps: $14.21, $2.37/mi, $0.60/min, $7.10/stop.
        int[] adopted = {1421, 237, 60, 710};
        FilterStore.save(app, new FilterSettings(true, 1421, 237, 60, 710, 3, true, 0));
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            Ui ui = new Ui(app);
            // A finger that slides off the pay or per-mile knob sideways, well past the touch slop, takes nothing.
            for (int axis : new int[] {0, 1}) {
                float[] knob = star.knobAt(axis);
                dragKnob(content, star, knob, alongSpoke(knob, axis, 0, ui.dp(20)),
                        () -> assertFalse("a slide across the spoke is not a drag", star.dragging()));
                assertArrayEquals(adopted, FilterStore.load(app).minimums());
                assertFalse("nor a tap", fieldLabeled(content, "Minimum pay ($)").isShown());
            }

            // Out along the spoke and back to where it was taken: $14.21 stays as it is, not snapped to $14.00.
            settleSky(content);
            float[] pay = star.knobAt(0);
            dragThrough(content, new float[][] {pay, alongSpoke(pay, 0, ui.dp(30), 0), pay},
                    () -> assertTrue(star.dragging()));
            assertArrayEquals(adopted, FilterStore.load(app).minimums());

            // In past half a step ($0.25) from $14.21: the step below, $14.00, is in reach.
            settleSky(content);
            pay = star.knobAt(0);
            float perCent = star.skyRadius() / (star.ringCents() * 3f);
            dragThrough(content, new float[][] {pay, alongSpoke(pay, 0, -ui.dp(30), 0),
                    alongSpoke(pay, 0, -31 * perCent, 0)}, null);
            assertEquals(1400, FilterStore.load(app).flatCents);
            assertArrayEquals("nothing else moved", new int[] {1400, 237, 60, 710}, FilterStore.load(app).minimums());
        }
    }

    @Test
    public void inAPageThatScrollsASwipeFromAKnobScrollsAndASlideAcrossItSetsNothing() {
        try (ActivityController<android.app.Activity> built =
                Robolectric.buildActivity(android.app.Activity.class).setup()) {
            LoneSky page = new LoneSky(built.get(), new FilterSettings(true, 725, 150, 30, 0, 0));
            Ui ui = page.ui;
            MinimumsStarView star = page.star;

            // A finger landing on the pay knob grows it, before anything is claimed.
            float[] pay = star.knobAt(0);
            long now = android.os.SystemClock.uptimeMillis();
            page.scroll.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, pay[0], pay[1], 0));
            assertEquals(0, star.pressedKnob());
            page.scroll.dispatchTouchEvent(MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, pay[0], pay[1], 0));
            assertEquals(-1, star.pressedKnob());
            assertEquals("a tap on a knob is a tap", 1, page.clicks);

            // A swipe up the page that starts on the pay knob scrolls the page and sets nothing.
            page.swipe(pay, new float[] {pay[0], pay[1] - ui.dp(120)});
            assertTrue("the page scrolled: " + page.scroll.getScrollY(), page.scroll.getScrollY() > 0);
            assertTrue("nothing saved: " + page.saves, page.saves.isEmpty());
            assertEquals(1, page.clicks);
            page.scroll.scrollTo(0, 0);
            page.layOut();

            // A slide across the spoke from the $7.25 knob, either way, past the touch slop (12 dp would do on a
            // phone, whose slop is 8 dp): not a drag, not a tap.
            float across = Math.max(ui.dp(12), page.slop * 1.4f);
            for (int side = -1; side <= 1; side += 2) {
                pay = star.knobAt(0);
                page.swipe(pay, alongSpoke(pay, 0, 0, side * across));
                assertFalse(star.dragging());
                assertTrue("nothing saved: " + page.saves, page.saves.isEmpty());
                assertEquals(1, page.clicks);
                page.scroll.scrollTo(0, 0);
                page.layOut();
            }

            // A drag along the spoke still sets it, and the page holds still meanwhile.
            pay = star.knobAt(0);
            page.swipe(pay, alongSpoke(pay, 0, ui.dp(40), 0));
            assertEquals(1, page.saves.size());
            assertTrue(page.saves.toString(), page.saves.get(0).startsWith("0="));
            assertEquals("the page held still", 0, page.scroll.getScrollY());
        }
    }

    @Test
    public void aKnobSetSoLowItRestsNearTheMiddleTurnsOffOnlyWhenPushedClearlyIn() {
        try (ActivityController<android.app.Activity> built =
                Robolectric.buildActivity(android.app.Activity.class).setup()) {
            // $1.00 a stop asks $2 of the example's 2 stops, on rings reaching $21: inside the 20 dp resting place.
            LoneSky page = new LoneSky(built.get(), new FilterSettings(true, 2000, 0, 0, 100, 0));
            Ui ui = page.ui;
            MinimumsStarView star = page.star;
            float[] stop = star.knobAt(3);
            float[] middle = {star.skyX(), star.skyY()};
            assertTrue("it rests inside the resting place",
                    Math.hypot(stop[0] - middle[0], stop[1] - middle[1]) < ui.dp(20));

            // Sideways past the slop: nothing set, and the rule is not turned off.
            float past = page.slop + ui.dp(4);
            page.swipe(stop, alongSpoke(stop, 3, 0, past));
            assertTrue("nothing saved: " + page.saves, page.saves.isEmpty());
            page.scroll.scrollTo(0, 0);
            page.layOut();
            // Out along its spoke and back to where it was: still nothing.
            page.drag(new float[][] {stop, alongSpoke(stop, 3, past, 0), stop});
            assertTrue("nothing saved: " + page.saves, page.saves.isEmpty());

            // Out, then back in to 9 dp inside where it was (less than a clear 12 dp): it stays on, a step lower.
            page.drag(new float[][] {stop, alongSpoke(stop, 3, past, 0), alongSpoke(stop, 3, -ui.dp(9), 0)});
            assertEquals(1, page.saves.size());
            assertEquals("3=50", page.saves.get(0));

            // Pushed in through the middle: off.
            stop = star.knobAt(3);
            page.drag(new float[][] {stop, alongSpoke(middle, 3, -ui.dp(16), 0)});
            assertEquals("3=0", page.saves.get(page.saves.size() - 1));
        }
    }

    /**
     * The constellation alone as a page's sky, in a column tall enough to scroll, saving into {@link #saves} and
     * shown again from them, as the main page does.
     */
    private static final class LoneSky {
        final Ui ui;
        final MinimumsStarView star;
        final android.widget.ScrollView scroll;
        final List<String> saves = new ArrayList<>();
        final int slop;
        int clicks;
        private FilterSettings rules;

        LoneSky(android.app.Activity activity, FilterSettings rules) {
            this.rules = rules;
            ui = new Ui(activity);
            slop = android.view.ViewConfiguration.get(activity).getScaledTouchSlop();
            star = new MinimumsStarView(activity, ui);
            star.setChanges(new MinimumsStarView.Changes() {
                @Override public void setMinimum(int axis, int cents) {
                    saves.add(axis + "=" + cents);
                    int[] rates = LoneSky.this.rules.minimums();
                    rates[axis] = cents;
                    LoneSky.this.rules = LoneSky.this.rules.withMinimums(rates);
                    show();
                }

                @Override public int[] adoptLearned() {
                    return null;
                }

                @Override public void restore(int[] cents) {}
            });
            star.setOnClickListener(tapped -> clicks++);
            scroll = new android.widget.ScrollView(activity);
            android.widget.LinearLayout column = ui.column();
            android.widget.FrameLayout sky = new android.widget.FrameLayout(activity);
            sky.addView(star, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            column.addView(sky, new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ui.dp(480)));
            column.addView(new View(activity), new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(900)));
            scroll.addView(column);
            activity.setContentView(scroll);
            layOut();
            show();
            star.compose(scroll.getWidth() / 2f, ui.dp(240), ui.dp(180), Collections.emptyList());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        }

        private void show() {
            star.show(rules, new OfferSnapshot(null, 5.0, 20, 2), Collections.emptyList());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        }

        void layOut() {
            View root = scroll.getRootView();
            int width = root.getResources().getDisplayMetrics().widthPixels;
            int height = root.getResources().getDisplayMetrics().heightPixels;
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);
        }

        /** A finger from {@code from} to {@code to} (in the star's pixels, the page unscrolled), then lifted. */
        void swipe(float[] from, float[] to) {
            drag(new float[][] {from, to});
        }

        /** A finger down at the first point, moved through the others in small steps, lifted at the last. */
        void drag(float[][] points) {
            int[] starAt = new int[2];
            int[] scrollAt = new int[2];
            star.getLocationInWindow(starAt);
            scroll.getLocationInWindow(scrollAt);
            float dx = starAt[0] - scrollAt[0];
            float dy = starAt[1] - scrollAt[1];
            long now = android.os.SystemClock.uptimeMillis();
            long at = now;
            scroll.dispatchTouchEvent(MotionEvent.obtain(now, at, MotionEvent.ACTION_DOWN, points[0][0] + dx,
                    points[0][1] + dy, 0));
            for (int leg = 1; leg < points.length; leg++) {
                for (int step = 1; step <= 12; step++) {
                    at += 16;
                    float x = points[leg - 1][0] + (points[leg][0] - points[leg - 1][0]) * step / 12;
                    float y = points[leg - 1][1] + (points[leg][1] - points[leg - 1][1]) * step / 12;
                    scroll.dispatchTouchEvent(MotionEvent.obtain(now, at, MotionEvent.ACTION_MOVE, x + dx, y + dy, 0));
                }
            }
            float[] last = points[points.length - 1];
            scroll.dispatchTouchEvent(MotionEvent.obtain(now, at + 50, MotionEvent.ACTION_UP, last[0] + dx,
                    last[1] + dy, 0));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void besideDasherAKnobBesidePausedTakesItsOwnTouchesAndTheWordStillResumes() {
        // Paused, with a line asking for a fix: "Paused" stands over the circle's lower part, the per-minute knob
        // ($0.75 a minute asks $15.75 of the example's 21 minutes, on rings reaching $21) level with it but beside
        // the word.
        FilterStore.save(app, new FilterSettings(false, 2000, 150, 75, 100, 0));
        DecisionLog.record(app, declinedEntry());
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            ViewGroup sky = (ViewGroup) star.getParent();
            assertNotNull(shownTextContaining(content, "Background offers are off"));
            TextView paused = findText(content, "Paused");
            assertNotNull(paused);
            int[] wordAt = new int[2];
            int[] skyAt = new int[2];
            paused.getLocationInWindow(wordAt);
            sky.getLocationInWindow(skyAt);
            float wordLeft = wordAt[0] - skyAt[0];
            float wordTop = wordAt[1] - skyAt[1];
            float[] knob = star.knobAt(2);
            assertNotNull(knob);
            assertTrue("level with the line: " + knob[1] + " in " + wordTop + "+" + paused.getHeight(),
                    knob[1] > wordTop && knob[1] < wordTop + paused.getHeight());
            assertTrue("beside the word, not under it: " + knob[0] + " / " + wordLeft + "+" + paused.getWidth(),
                    knob[0] > wordLeft + paused.getWidth());

            tap(sky, knob[0], knob[1]);
            assertFalse("a tap on the knob does not resume", FilterStore.load(app).enabled);
            assertTrue("it opens the minimums, as any tap on the chart", fieldLabeled(content,
                    "Minimum pay ($)").isShown());
            iconButton(content, "Back").performClick();
            settleSky(content);
            knob = star.knobAt(2);
            dragKnob(content, star, knob, alongSpoke(knob, 2, new Ui(app).dp(30), 0), null);
            assertTrue("a drag from it sets the per-minute minimum: " + FilterStore.load(app).perMinuteCents,
                    FilterStore.load(app).perMinuteCents > 75);
            assertFalse("still paused", FilterStore.load(app).enabled);

            // The word itself still resumes.
            settleSky(content);
            paused = findText(content, "Paused");
            paused.getLocationInWindow(wordAt);
            tap(sky, wordAt[0] - skyAt[0] + paused.getWidth() / 2f, wordAt[1] - skyAt[1] + paused.getHeight() / 2f);
            assertTrue(FilterStore.load(app).enabled);
        } finally {
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void undoHoldsWhileAScreenReaderIsOnItAndItsFocusGoesWithTheButton() {
        FilterStore.save(app, new FilterSettings(true, 700, 150, 30, 100, 3, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2));
        org.robolectric.shadows.ShadowAccessibilityManager reader = Shadows.shadowOf(
                app.getSystemService(android.view.accessibility.AccessibilityManager.class));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            reader.setEnabled(true);
            reader.setTouchExplorationEnabled(true);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            android.view.accessibility.AccessibilityNodeProvider nodes = star.getAccessibilityNodeProvider();
            int adopt = MinimumsStarView.ADOPT_ID;
            assertTrue(nodes.performAction(adopt,
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
            assertTrue(nodes.performAction(adopt, android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,
                    null));
            assertTrue("what to do comes first: " + star.lastSaid(),
                    star.lastSaid().startsWith("Learned minimums set. Double-tap to undo."));
            assertTrue(star.lastSaid(), star.lastSaid().contains("Add-ons are held to these too."));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3 * MinimumsStarView.UNDO_MS));
            assertTrue("Undo holds while the screen reader is on it", star.offeringUndo());

            // Moved on: Undo's time starts over (Android before 10 has no timeout to ask, so it waits for a change).
            assertTrue(nodes.performAction(adopt,
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(MinimumsStarView.UNDO_MS + 500));
            assertEquals(Build.VERSION.SDK_INT < 29, star.offeringUndo());

            // Back on it, a knob changes the adopted minimums: Undo ends, the button goes, and so does the focus.
            if (star.offeringUndo()) {
                assertTrue(nodes.performAction(adopt,
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
                assertEquals(adopt, star.focusedNode());
                assertTrue(nodes.performAction(1,
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null));
                assertFalse(star.offeringUndo());
                assertNull(star.adoptBox());
                assertEquals("no focus left on a button that is gone", -1, star.focusedNode());
            }
        } finally {
            reader.setTouchExplorationEnabled(false);
            reader.setEnabled(false);
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void adoptingWorksFromTheSavedMinimumsAndLeavesUnsavedTypingAlone() {
        FilterStore.save(app, new FilterSettings(true, 1000, 300, 30, 100, 3, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2));
        FilterStore.save(app, FilterStore.load(app).withMinimums(new int[] {1000, 300, 30, 100}));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            // Typed in Settings, never saved: pay $20 and $1.00 a mile.
            fieldLabeled(content, "Minimum pay ($)").setText("20");
            fieldLabeled(content, "Per mile ($)").setText("1.00");
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            android.view.accessibility.AccessibilityNodeProvider nodes = star.getAccessibilityNodeProvider();
            assertTrue(nodes.performAction(MinimumsStarView.ADOPT_ID,
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null));
            FilterSettings adopted = FilterStore.load(app);
            assertArrayEquals("pay from the saved $10 to beat $14.20, never the typed $20; the saved $3.00/mi was "
                    + "already above $2.37", new int[] {1421, 300, 60, 710}, adopted.minimums());
            assertEquals("14.21", fieldLabeled(content, "Minimum pay ($)").getText().toString());
            assertEquals("unsaved typing on a minimum it left alone stays", "1.00",
                    fieldLabeled(content, "Per mile ($)").getText().toString());

            assertTrue(nodes.performAction(MinimumsStarView.ADOPT_ID,
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null));
            assertArrayEquals("Undo puts back the saved minimums it replaced", new int[] {1000, 300, 30, 100},
                    FilterStore.load(app).minimums());
            assertEquals("10.00", fieldLabeled(content, "Minimum pay ($)").getText().toString());
            assertEquals("1.00", fieldLabeled(content, "Per mile ($)").getText().toString());

            // An unsaved Adaptive minimum switch shows no button: it follows the saved rules.
            FilterStore.save(app, FilterStore.load(app).withMinimums(new int[] {1000, 300, 30, 100}));
            FilterSettings off = FilterStore.load(app);
            FilterStore.save(app, new FilterSettings(off.enabled, off.flatCents, off.perMileCents,
                    off.perMinuteCents, off.perStopCents, off.maxStops, false, off.lastAcceptedCents, off.best,
                    off.declined));
            activity.get().onPause();
            activity.get().onResume();
            fieldLabeled(content, "Minimum pay ($)").setText("10.00");
            settleSky(content);
            assertNull("the saved adaptive minimum is off", star.adoptBox());
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void aFreshPageHasNoKnobsAndAFirstRuleSetByOneStaysPaused() {
        FilterStore.save(app, new FilterSettings(false, 0, 0, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(star.backdrop());
            for (int axis = 0; axis < 4; axis++) assertNull("no knob with no rule", star.knobAt(axis));
            assertEquals("nor any for screen readers", 0, star.getAccessibilityNodeProvider()
                    .createAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID)
                    .getChildCount());
            assertNotNull(shownTextContaining(content, "Tap to set up rules"));

            // A minimum typed in Settings (not yet saved) puts the knobs out; one dragged saves the first rule.
            fieldLabeled(content, "Minimum pay ($)").setText("7");
            settleSky(content);
            float[] mile = star.knobAt(1);
            assertNotNull("a hollow knob beside the typed minimum", mile);
            dragKnob(content, star, mile, alongSpoke(mile, 1, new Ui(app).dp(60), 0), null);
            FilterSettings saved = FilterStore.load(app);
            assertTrue("the per-mile minimum is saved: " + saved.perMileCents, saved.perMileCents > 0);
            assertEquals("only it", 0, saved.flatCents);
            assertFalse("auto-decline stays paused", saved.enabled);
            assertEquals("Rule saved. Auto-decline stays paused until you Resume it.",
                    org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void aLargeFontAndALineToFixKeepTheSkylineAndTheMapAtTheirLeast() {
        RuntimeEnvironment.setFontScale(2f);
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);
        AreaMap.setEnabled(app, true);
        // Areas enough for a best one, so the line naming it stands under the map.
        double[][] spots = {{37.7749, -122.4194}, {37.8149, -122.3794}, {37.7549, -122.4494}};
        for (double[] spot : spots) {
            for (int pay : new int[] {1000, 1500, 1200}) noteOfferAt(spot[0], spot[1], pay, 5.0);
        }
        setLocation(37.7749, -122.4194);
        FilterStore.save(app, new FilterSettings(true, 700, 150, 30, 100, 4, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2));
        DecisionLog.record(app, declinedEntry());
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        // On a dash, so the counts above the constellation are this dash's, with the totals under them.
        Dashing.forgetCache();
        Dashing.seen(app);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            assertNotNull("background offers are off: a line to fix", shownTextContaining(content,
                    "Background offers are off"));
            Ui ui = new Ui(activity.get());
            DecisionChartView chart = findChart(content);
            AreaMapView map = find(content, AreaMapView.class);
            assertTrue("the skyline keeps its least: " + chart.getHeight(), chart.getHeight() >= ui.dp(64));
            assertTrue("the map keeps its least: " + map.getHeight(), map.getHeight() >= ui.dp(96));
            assertNotNull("the line naming the best area stands under it",
                    shownTextContaining(content, "/mi"));
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue("the constellation is still the sky", star.backdrop());
            android.widget.ScrollView page = (android.widget.ScrollView) ((View) star.getParent().getParent())
                    .getParent();
            View column = page.getChildAt(0);
            assertTrue("one screen: " + column.getHeight() + " in " + page.getHeight(),
                    column.getHeight() <= page.getHeight());
        } finally {
            service.destroy();
            RuntimeEnvironment.setFontScale(1f);
        }
    }

    /** A finger down at the first point (in the star's pixels), moved through the others in steps, then lifted. */
    private static void dragThrough(View content, float[][] points, Runnable whileHeld) {
        MinimumsStarView star = find(content, MinimumsStarView.class);
        int[] starAt = new int[2];
        int[] contentAt = new int[2];
        star.getLocationInWindow(starAt);
        content.getLocationInWindow(contentAt);
        float dx = starAt[0] - contentAt[0];
        float dy = starAt[1] - contentAt[1];
        long now = android.os.SystemClock.uptimeMillis();
        long at = now;
        content.dispatchTouchEvent(MotionEvent.obtain(now, at, MotionEvent.ACTION_DOWN, points[0][0] + dx,
                points[0][1] + dy, 0));
        for (int leg = 1; leg < points.length; leg++) {
            for (int step = 1; step <= 12; step++) {
                at += 16;
                float x = points[leg - 1][0] + (points[leg][0] - points[leg - 1][0]) * step / 12;
                float y = points[leg - 1][1] + (points[leg][1] - points[leg - 1][1]) * step / 12;
                content.dispatchTouchEvent(MotionEvent.obtain(now, at, MotionEvent.ACTION_MOVE, x + dx, y + dy, 0));
            }
        }
        if (whileHeld != null) whileHeld.run();
        float[] last = points[points.length - 1];
        content.dispatchTouchEvent(MotionEvent.obtain(now, at + 50, MotionEvent.ACTION_UP, last[0] + dx,
                last[1] + dy, 0));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Lays the page out and lets the constellation's points glide to where they are heading. */
    private static void settleSky(View content) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
        layOut(content);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800));
    }

    private static double spokeAngle(int axis) {
        float spread = MinimumsStarView.SPREAD;
        return Math.toRadians(new float[] {spread - 180, -spread, spread, 180 - spread}[axis]);
    }

    /** {@code from} moved {@code by} out along spoke {@code axis}, and {@code aside} square to it. */
    private static float[] alongSpoke(float[] from, int axis, float by, float aside) {
        double angle = spokeAngle(axis);
        return new float[] {(float) (from[0] + Math.cos(angle) * by - Math.sin(angle) * aside),
                (float) (from[1] + Math.sin(angle) * by + Math.cos(angle) * aside)};
    }

    /** How far out along spoke {@code axis} from {@code middle} the point {@code at} stands. */
    private static double along(float[] middle, int axis, float[] at) {
        double angle = spokeAngle(axis);
        return (at[0] - middle[0]) * Math.cos(angle) + (at[1] - middle[1]) * Math.sin(angle);
    }

    /**
     * A finger down on a knob at {@code from} and moved to {@code to} in steps, then lifted, all delivered from the
     * top of the window as the screen delivers it; {@code whileHeld} runs before the finger lifts.
     */
    private static void dragKnob(View content, MinimumsStarView star, float[] from, float[] to, Runnable whileHeld) {
        int[] starAt = new int[2];
        int[] contentAt = new int[2];
        star.getLocationInWindow(starAt);
        content.getLocationInWindow(contentAt);
        float dx = starAt[0] - contentAt[0];
        float dy = starAt[1] - contentAt[1];
        long now = android.os.SystemClock.uptimeMillis();
        content.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, from[0] + dx, from[1] + dy,
                0));
        for (int step = 1; step <= 12; step++) {
            float x = from[0] + (to[0] - from[0]) * step / 12;
            float y = from[1] + (to[1] - from[1]) * step / 12;
            content.dispatchTouchEvent(MotionEvent.obtain(now, now + step * 16L, MotionEvent.ACTION_MOVE, x + dx,
                    y + dy, 0));
        }
        if (whileHeld != null) whileHeld.run();
        content.dispatchTouchEvent(MotionEvent.obtain(now, now + 300, MotionEvent.ACTION_UP, to[0] + dx, to[1] + dy,
                0));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static boolean containsPoint(List<android.graphics.RectF> boxes, float x, float y) {
        for (android.graphics.RectF box : boxes) if (box.contains(x, y)) return true;
        return false;
    }

    /** A finger down and up at ({@code x}, {@code y}) in {@code parent}, as the screen delivers it. */
    private static void tap(ViewGroup parent, float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0);
        parent.dispatchTouchEvent(down);
        parent.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
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

    @Test
    public void aPhoneThatWillNotSplitOpensRecentAppsAndDasherStillFollows() {
        dasherInstalled();
        DasherSplit.forget();
        List<String> asked = new ArrayList<>();
        DasherSplit.split = () -> {
            asked.add("split");
            return false;
        };
        DasherSplit.recents = () -> {
            asked.add("recents");
            return true;
        };
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            service.get().onServiceConnected();
            View content = activity.get().findViewById(android.R.id.content);
            shownIcon(content, "Split screen with Dasher").performClick();
            assertEquals("asked to split, then opened recent apps", Arrays.asList("split", "recents"), asked);
            assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast()
                    .startsWith("Tap Offer Filter's icon above its card and choose split screen."));
            assertNull(Shadows.shadowOf(app).getNextStartedActivity());

            // The user splits it from recent apps half a minute later: Dasher still opens in the other half.
            ShadowSystemClock.advanceBy(Duration.ofSeconds(30));
            Shadows.shadowOf(activity.get()).setInMultiWindowMode(true);
            activity.get().onMultiWindowModeChanged(true, activity.get().getResources().getConfiguration());
            Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
            assertNotNull(opened);
            assertTrue((opened.getFlags() & Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT) != 0);
        } finally {
            service.destroy();
            DasherSplit.forget();
        }
    }

    @Test
    public void aSplitLongAfterTheTapOpensNothing() {
        dasherInstalled();
        DasherSplit.forget();
        DasherSplit.split = () -> false;
        DasherSplit.recents = () -> true;
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            service.get().onServiceConnected();
            View content = activity.get().findViewById(android.R.id.content);
            shownIcon(content, "Split screen with Dasher").performClick();
            ShadowSystemClock.advanceBy(Duration.ofMillis(DasherSplit.BY_HAND_MS + 1000));
            Shadows.shadowOf(activity.get()).setInMultiWindowMode(true);
            activity.get().onMultiWindowModeChanged(true, activity.get().getResources().getConfiguration());
            assertNull("an unrelated split later never opens Dasher", Shadows.shadowOf(app).getNextStartedActivity());
        } finally {
            service.destroy();
            DasherSplit.forget();
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
        assertEquals("Below your per-stop rate", MainActivity.plainReason("dollars per stop"));
        // Recorded while per stop was a fee on top of the other minimums.
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

    /** Any TextView (a switch's label too) whose text contains {@code part}, shown or not. */
    private static TextView findTextView(View view, String part) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(part)) return (TextView) view;
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                TextView found = findTextView(((ViewGroup) view).getChildAt(i), part);
                if (found != null) return found;
            }
        }
        return null;
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

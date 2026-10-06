package com.local.dasherfilter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.time.Duration;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Offer alerts and the notification listener, and the settings screen and rules, through real Android adapters. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,35})
@LooperMode(LooperMode.Mode.PAUSED)
public class AndroidAdapterAlertsAndSettingsTest extends AndroidAdapterTestBase {
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
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        NotificationChannel passing = manager.getNotificationChannel(OfferAlerts.CHANNEL_ID);
        assertEquals("passing offers use the bundled, recognizable chime", OfferAlerts.passingSound(app),
                passing.getSound());
        assertFalse("the pass chime is not the phone's ordinary notification sound",
                android.provider.Settings.System.DEFAULT_NOTIFICATION_URI.equals(passing.getSound()));
        assertNull("the old default-sound passing channel is retired",
                manager.getNotificationChannel("qualifying_offers"));
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
            assertEquals("Dasher's notification shows no pay. Open Dasher and Offer Filter judges it.",
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
        // The user is told too: in the status, and once on the homepage. Other rules remain, so the filter stays on.
        String notice = "Your $2.00 extra-stop fee was removed; it is not a rule any more. Use Max stops to limit "
                + "stacked orders.";
        assertTrue(FilterStore.lastStatus(app), FilterStore.lastStatus(app).endsWith("\n" + notice));
        assertEquals(notice, FilterStore.takeStopFeeNotice(app));
        assertNull("taken once", FilterStore.takeStopFeeNotice(app));

        // Retired once: loading again notes nothing more.
        FilterStore.load(app);
        String again = DiagnosticLog.read(app);
        assertEquals(again, again.indexOf("extra-stop fee"), again.lastIndexOf("extra-stop fee"));
        assertNull(FilterStore.takeStopFeeNotice(app));

        // Per stop is retired as well (0.5.0): rules saved now write neither key, so nothing brings the fee back.
        FilterStore.save(app, FilterSettings.of(true, 700, 150, 0, 3));
        assertEquals(0, FilterStore.load(app).perStopCents);
        assertFalse(prefs.contains("per_stop"));
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
        String notice = "Your $1.50 extra-stop fee was removed; it is not a rule any more. Use Max stops to limit "
                + "stacked orders. It was your only rule, so auto-decline is paused.";
        assertTrue(FilterStore.lastStatus(app), FilterStore.lastStatus(app).endsWith("\n" + notice));
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("[rules] the old extra-stop fee of $1.50 was retired"));
        assertTrue(log, log.contains("no rule was left, so auto-decline was paused"));
        assertEquals(notice, FilterStore.takeStopFeeNotice(app));

        // Already paused, a fee-only profile is only told, never switched on or off.
        prefs.edit().clear().putBoolean("enabled", false).putInt("stop", 150).commit();
        assertFalse(FilterStore.load(app).enabled);
        assertEquals("Your $1.50 extra-stop fee was removed; it is not a rule any more. Use Max stops to limit "
                + "stacked orders.", FilterStore.takeStopFeeNotice(app));
    }

    @Test
    public void theRetiredFeeNoticeShowsOnceOnTheHomepage() {
        android.content.SharedPreferences prefs = app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("enabled", true).putInt("flat", 700).putInt("stop", 200).commit();
        String notice = "Your $2.00 extra-stop fee was removed; it is not a rule any more. Use Max stops to limit "
                + "stacked orders.";
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            TextView shown = shownTextContaining(content, notice);
            assertNotNull("on the homepage, the first time it opens after the update", shown);
            assertEquals("per stop starts off", 0, FilterStore.load(app).perStopCents);
            assertNull("taken from the store as it is shown", FilterStore.takeStopFeeNotice(app));

            iconButton(content, "Settings").performClick();
            settle();
            assertNull("never in Settings", shownTextContaining(content, "extra-stop fee"));
            iconButton(content, "Back").performClick();
            settle();
            assertNotNull("still there until it is tapped", shownTextContaining(content, notice));

            // A recreated page (a resize, day and night) keeps it until it is tapped.
            activity.recreate();
            content = activity.get().findViewById(android.R.id.content);
            shown = shownTextContaining(content, notice);
            assertNotNull(shown);
            ((View) shown.getParent()).performClick();
            settle();
            assertNull("tapped, it goes", shownTextContaining(content, "extra-stop fee"));
        }
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertNull("once only", shownTextContaining(activity.get().findViewById(android.R.id.content),
                    "extra-stop fee"));
        }
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
    public void withoutAnyRuleTheMascotPointsToTheHollowKnobs() {
        FilterStore.save(app, new FilterSettings(false, 0, 0, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            FilterHeroView mascot = find(content, FilterHeroView.class);
            assertEquals("Set up rules", mascot.action());
            assertNotNull(shownTextContaining(content, MainActivity.START_HINT));
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertFalse(star.beckoned());
            mascot.performClick();
            settle();
            assertFalse(FilterStore.load(app).enabled);
            assertTrue("the hollow knobs beckon", star.beckoned());
            assertFalse("no page opens", settingsShown(content));
        }
    }

    @Test
    public void settingsOpenFromTheHeaderAndBackReturnsToTheMainPage() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            // One page for the filter, the offers, the minimums and the areas; what is set nowhere else is in Settings.
            assertTrue(find(content, FilterHeroView.class).isShown());
            assertTrue(find(content, DecisionChartView.class).isShown());
            assertTrue(find(content, MinimumsStarView.class).isShown());
            assertFalse(settingsShown(content));

            iconButton(content, "Settings").performClick();
            settle();
            assertTrue(settingsShown(content));
            assertFalse(find(content, FilterHeroView.class).isShown());

            // The page shown survives the activity being recreated (rotation, dark mode switch).
            activity.recreate();
            content = activity.get().findViewById(android.R.id.content);
            assertTrue(settingsShown(content));
            assertNull("capture is automatic: no switch", findTextView(content, "Capture full screen text"));

            activity.get().onBackPressed();
            assertTrue(find(content, FilterHeroView.class).isShown());
            assertFalse(activity.get().isFinishing());
            iconButton(content, "Settings").performClick();
            iconButton(content, "Back").performClick();
            assertTrue(find(content, FilterHeroView.class).isShown());
        }
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void savingRulesKeepsAutoDeclinePaused() {
        FilterStore.save(app, new FilterSettings(false, 2000, 0, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            // The pay knob one step up ($0.50), as a screen reader sets it: saved at once.
            assertTrue(act(star, 0, android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD));

            FilterSettings saved = FilterStore.load(app);
            assertFalse(saved.enabled);
            assertEquals(2050, saved.flatCents);
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
            for (Button button : buttons) {
                CharSequence description = button.getContentDescription();
                assertTrue("unlabeled button", !button.getText().toString().trim().isEmpty()
                        || description != null && !description.toString().trim().isEmpty());
            }
            // The round icon buttons have no text, so they must say what they do.
            assertNotNull(iconButton(content, "Settings"));
            assertNotNull(iconButton(content, "Back"));
        }
    }

    @Test
    public void clearingHistoryAsksFirst() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION);
            AreaMap.setEnabled(app, true);
            noteOfferAt(37.79, -122.40, 1200, 4.0);
            assertEquals(1, AreaMap.totalOffers(AreaMap.cells(app)));
            findButton(content, "Clear history").performClick();
            android.app.AlertDialog dialog = (android.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertEquals(1, DecisionLog.recent(app, 10).size());
            DiagnosticLog.log(app, "screen", "other labels=[Customer's order]");
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(DecisionLog.recent(app, 10).isEmpty());
            assertFalse("the captured screen text goes too", DiagnosticLog.read(app).contains("Customer's order"));
            assertTrue("and the offer areas (Forget areas folded in)", AreaMap.cells(app).isEmpty());
            assertEquals(0, AreaMap.unlocated(app));
            assertNotNull(shownTextContaining(content, "No offers yet"));
        }
    }

    @Test
    public void recentDecisionsAppearWithTheirReasons() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertTrue(findChart(content).getContentDescription().toString().startsWith("Chart of the last 1 offers: 0 passed, 1 declined, 0 need review."));
            openTicket(content);
            assertNotNull(shownTextContaining(content, "Below your per-mile rate"));
            assertNotNull(shownTextContaining(content, "Decline tapped · on screen"));
        }
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void whatTheExampleNeedsFollowsTheRulesAsTheyAreSet() {
        FilterStore.save(app, new FilterSettings(true, 700, 0, 0, 0, 0));
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            // The example is the latest fully read offer: 21 min, 7.2 mi, 2 stops; screen readers hear it first.
            assertTrue(star.getContentDescription().toString()
                    .startsWith("An offer like 21 min · 7.2 mi · 2 stops needs $7.00."));
            // $1.50 a mile on its knob, as a screen reader sets it.
            android.os.Bundle dollars = new android.os.Bundle();
            dollars.putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 1.5f);
            assertTrue(star.getAccessibilityNodeProvider().performAction(1, android.R.id.accessibilityActionSetProgress,
                    dollars));
            assertEquals(150, FilterStore.load(app).perMileCents);
            assertTrue(star.getContentDescription().toString()
                    .startsWith("An offer like 21 min · 7.2 mi · 2 stops needs $10.80."));
            // At most 1 stop, saved by an older version (the badge steps from 2).
            FilterStore.save(app, FilterStore.load(app).withMaxStops(1));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertTrue(star.getContentDescription().toString()
                    .startsWith("An offer like 21 min · 7.2 mi · 2 stops is declined: at most 1 stop."));
        }
    }

    @Test
    public void sunCyclesAllFourModesWithoutAChooserAndPersistsAcrossReopen() {
        assertEquals(Appearance.Mode.AUTO, Appearance.mode(app));
        Appearance.Mode[] expected = {Appearance.Mode.DAY, Appearance.Mode.NIGHT,
                Appearance.Mode.SYSTEM, Appearance.Mode.AUTO, Appearance.Mode.DAY};
        for (Appearance.Mode next : expected) {
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                assertEquals("the reopened screen uses the persisted theme",
                        Appearance.resolve(app).night, new Ui(activity.get()).dark);
                View sun = iconDescribed(activity.get().findViewById(android.R.id.content),
                        Appearance.resolve(app).description());
                assertNotNull("the sun or moon remains the only theme control", sun);
                assertTrue(sun.getContentDescription().toString().endsWith("Tap for " + next.label + "."));
                sun.performClick();
                assertEquals(next, Appearance.mode(app));
                assertNull("a tap cycles immediately without any chooser",
                        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
                Shadows.shadowOf(Looper.getMainLooper()).idle();
            }
        }
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertFalse(new Ui(activity.get()).dark);
        }
    }

    @Test
    public void aSamePaletteSelectionUpdatesItsBadgeWithoutRecreatingThePage() {
        org.robolectric.RuntimeEnvironment.setQualifiers("night");
        Appearance.choose(app, Appearance.Mode.NIGHT);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity page = activity.get();
            AppearanceButton sun = find(page.findViewById(android.R.id.content), AppearanceButton.class);
            sun.performClick(); // Night -> System, which is also night.
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue("the same Activity remains", page == activity.get());
            assertTrue("the same button remains", sun == find(page.findViewById(android.R.id.content),
                    AppearanceButton.class));
            assertEquals(Appearance.Mode.SYSTEM, Appearance.mode(app));
            TextView badge = (TextView) sun.getChildAt(0);
            assertEquals("S", badge.getText().toString());
            assertEquals(View.VISIBLE, badge.getVisibility());
            assertTrue(sun.getContentDescription().toString().contains("System, night, follows Android"));
            assertTrue(sun.getContentDescription().toString().endsWith("Tap for Auto."));
            assertNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
        }
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
            // The homepage's own row for it (Settings no longer repeats it).
            shownIcon(activity.get().findViewById(android.R.id.content), "Background offers are off. Fix.")
                    .performClick();
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
}

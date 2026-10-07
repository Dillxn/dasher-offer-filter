package com.local.dasherfilter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.view.View;
import java.time.Duration;
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
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowNotificationListenerService;
import org.robolectric.shadows.ShadowPendingIntent;
import org.robolectric.shadows.ShadowSystemClock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The one notification posted before the first-run notice is accepted ({@link ConsentReminder}), through real
 * Android adapters: an update that brings a new notice mid-dash says once that the app is paused until it is opened,
 * when the accessibility service connects or Dasher's offer notification comes. It rings at most once per notice
 * version, its tap opens the app on the notice, accepting the notice cancels it, and with the notice accepted it is
 * never posted. Starts, as ConsentGateTest does, from a phone that has not accepted the notice.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class ConsentReminderTest extends AndroidAdapterTestBase {
    @Before
    public void notYetAccepted() {
        ConsentedTestApp.forget(app);
        FilterStore.save(app, FilterSettings.of(true, 2000, 0, 0, 0));
    }

    @Test
    public void postedOnceWhenTheAccessibilityServiceConnectsWithoutConsent() {
        ServiceController<OfferFilterService> controller = Robolectric.buildService(OfferFilterService.class).create();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        try {
            controller.get().onServiceConnected();
            idle();

            assertEquals("one notification, and only the reminder", 1, notifications().size());
            Notification reminder = reminder();
            assertNotNull(reminder);
            assertEquals(AppName.NAME + " is paused until you open it",
                    reminder.extras.getString(Notification.EXTRA_TITLE));
            assertEquals("Tap to review and resume filtering.",
                    String.valueOf(reminder.extras.getCharSequence(Notification.EXTRA_TEXT)));
            assertTrue("it alerts only once", (reminder.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
            assertEquals(ConsentReminder.CHANNEL_ID, reminder.getChannelId());
            NotificationChannel channel =
                    app.getSystemService(NotificationManager.class).getNotificationChannel(ConsentReminder.CHANNEL_ID);
            assertNotNull("its own channel, not an offer's", channel);
            assertEquals("one sound, no pop-up over Dasher", NotificationManager.IMPORTANCE_DEFAULT,
                    channel.getImportance());
            assertEquals("kept as posted for this notice", Consent.VERSION, prefs().getInt(
                    ConsentReminder.REMINDED_VERSION, 0));
            assertEquals("nothing of Dasher's is read for it", 0, controller.get().rootFetches);
            assertTrue(DiagnosticLog.read(app).contains("paused-until-opened reminder posted (accessibility connected)"));
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void neverPostedAgainOnASecondConnectOrAnOfferNotification() {
        connectAccessibility();
        assertNotNull(reminder());
        // The user swipes it away; whatever comes next never brings it back for this notice.
        app.getSystemService(NotificationManager.class).cancel(ConsentReminder.NOTIFICATION_ID);

        connectAccessibility();
        ServiceController<OfferNotificationService> listener =
                Robolectric.buildService(OfferNotificationService.class).create();
        try {
            listener.get().onListenerConnected();
            listener.get().onNotificationPosted(doorDashOffer("New Order: Go to Chick-fil-A"), null);
            listener.get().onNotificationPosted(doorDashOffer("$3.50 · 2 stops (6.2 mi) • 21 min"), null);
            idle();
        } finally {
            listener.destroy();
        }

        assertEquals("not posted again", 0, notifications().size());
        assertEquals("posted once in all", 1, count(DiagnosticLog.read(app), "paused-until-opened reminder posted"));
    }

    @Test
    public void dashersOfferNotificationPostsItButAReplayOrAnotherOfDashersChannelsDoesNot() {
        // Dasher's offers last came on "dasher_offers" (kept from before the update).
        FilterStore.recordDoorDashOfferChannel(app, "dasher_offers");
        ServiceController<OfferNotificationService> listener =
                Robolectric.buildService(OfferNotificationService.class).create();
        try {
            // Shown when the listener reconnects after the update: a replay never rings, so no reminder.
            StatusBarNotification shown = dasherPost("dasher_offers", "NEW_ORDER_1");
            ShadowNotificationListenerService dasher = Shadow.extract(listener.get());
            dasher.addActiveNotification(shown);
            listener.get().onListenerConnected();
            // Dasher's other channels (its dash status) are not its offers.
            listener.get().onNotificationPosted(dasherPost("dash_status", "DASHING"), null);
            idle();
            assertNull(reminder());

            listener.get().onNotificationPosted(dasherPost("dasher_offers", "NEW_ORDER_2"), null);
            idle();
            assertNotNull("an offer of Dasher's posts it", reminder());
            assertEquals("and nothing else", 1, notifications().size());
            assertTrue(DiagnosticLog.read(app).contains("paused-until-opened reminder posted (Dasher's notification)"));
            assertTrue("nothing of the offer was read", DecisionLog.recent(app, 10).isEmpty());
        } finally {
            listener.destroy();
        }
    }

    @Test
    public void tappingItOpensTheAppOnTheNotice() {
        connectAccessibility();
        Notification reminder = reminder();
        assertNotNull("it can be tapped", reminder.contentIntent);
        ShadowPendingIntent tap = Shadows.shadowOf(reminder.contentIntent);
        assertTrue("an activity, which Android lets a notification tap start", tap.isActivityIntent());
        assertTrue(tap.isImmutable());
        Intent opens = tap.getSavedIntent();
        assertEquals(MainActivity.class.getName(), opens.getComponent().getClassName());
        assertTrue((opens.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertTrue("it goes when tapped", (reminder.flags & Notification.FLAG_AUTO_CANCEL) != 0);

        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class, opens).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertNotNull("the notice is what it shows", shownTextContaining(content, Consent.TITLE));
            assertNotNull(shownButton(content, Consent.ACCEPT));
        }
    }

    @Test
    public void acceptingTheNoticeCancelsIt() {
        connectAccessibility();
        assertNotNull(reminder());

        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertNotNull("still there while the notice is read", reminder());
            shownButton(content, Consent.ACCEPT).performClick();
            idle();
        }

        assertTrue(Consent.accepted(app));
        assertNull("gone once accepted", reminder());
        assertEquals(0, notifications().size());
        // And never again: the service reconnecting now reads as before.
        connectAccessibility();
        assertNull(reminder());
    }

    @Test
    public void neverPostedWhenTheNoticeIsAlreadyAccepted() {
        Consent.accept(app);
        connectAccessibility();
        ServiceController<OfferNotificationService> listener =
                Robolectric.buildService(OfferNotificationService.class).create();
        try {
            listener.get().onListenerConnected();
            listener.get().onNotificationPosted(doorDashOffer("New Order: Go to Taco Bell"), null);
            idle();

            assertNull(reminder());
            assertEquals("only the offer's own card", 1, notifications().size());
            assertEquals(OfferAlerts.REVIEW_CHANNEL_ID, notifications().getAllNotifications().get(0).getChannelId());
        } finally {
            listener.destroy();
        }
        assertEquals(0, prefs().getInt(ConsentReminder.REMINDED_VERSION, 0));
        assertFalse(DiagnosticLog.read(app).contains("paused-until-opened reminder"));
    }

    // ---- Helpers ----

    /** The accessibility service connects (as after an update) and is torn down again. */
    private void connectAccessibility() {
        ServiceController<OfferFilterService> controller = Robolectric.buildService(OfferFilterService.class).create();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        try {
            controller.get().onServiceConnected();
            idle();
        } finally {
            controller.destroy();
        }
    }

    private Notification reminder() {
        return notifications().getNotification(ConsentReminder.NOTIFICATION_ID);
    }

    private android.content.SharedPreferences prefs() {
        return app.getSharedPreferences(Consent.PREFS, Context.MODE_PRIVATE);
    }

    /** A post of Dasher's on {@code channel}, on its own key, posted now. */
    private StatusBarNotification dasherPost(String channel, String tag) {
        Notification payload = new Notification.Builder(app, channel)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("New Delivery!")
                .setContentText("New Order: Go to Chick-fil-A")
                .build();
        return new StatusBarNotification("com.doordash.driverapp", "com.doordash.driverapp", tag.hashCode(), tag,
                10001, 0, 0, payload, android.os.Process.myUserHandle(), System.currentTimeMillis());
    }

    private static int count(String text, String part) {
        int found = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) found++;
        return found;
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
    }
}

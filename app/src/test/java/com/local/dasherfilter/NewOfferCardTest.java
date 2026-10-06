package com.local.dasherfilter;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import java.time.Duration;
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

import static org.junit.Assert.assertEquals;

/**
 * Dasher's "New Delivery!" / "New Order: Go to …" is a new offer (the user's note): our card for one it could not
 * judge names the store it names. Simulated Android only.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public class NewOfferCardTest {
    private Application app;
    private ServiceController<OfferNotificationService> listener;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        Updater.setEnabled(app, false);
        OfferAlerts.ensureChannel(app);
        FilterStore.save(app, new FilterSettings(true, 1300, 385, 41, 475, 3, true, 0));
        DecisionLog.forgetCache();
        listener = Robolectric.buildService(OfferNotificationService.class).create();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
    }

    @After
    public void stop() {
        listener.destroy();
    }

    private String cardTitleFor(String text) {
        Notification payload = new Notification.Builder(app, "source")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("New Delivery!")
                .setContentText(text)
                .build();
        listener.get().onNotificationPosted(new StatusBarNotification("com.doordash.driverapp",
                "com.doordash.driverapp", 3, "NEW_ORDER", 10001, 0, 0, payload, android.os.Process.myUserHandle(),
                System.currentTimeMillis()), null);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        java.util.List<Notification> cards =
                Shadows.shadowOf(app.getSystemService(NotificationManager.class)).getAllNotifications();
        Notification card = cards.get(cards.size() - 1);
        return String.valueOf(card.extras.getCharSequence(Notification.EXTRA_TITLE));
    }

    @Test
    public void theCardNamesTheStoreDashersNotificationNamesAndOtherwiseSaysDoorDash() {
        assertEquals("Taco Bell offer: open Dasher to check it", cardTitleFor("New Order: Go to Taco Bell"));
        listener.destroy();
        listener = Robolectric.buildService(OfferNotificationService.class).create();
        app.getSystemService(NotificationManager.class).cancelAll();
        assertEquals("DoorDash offer: open Dasher to check it", cardTitleFor("New Delivery! Tap to see it"));
        listener.destroy();
        listener = Robolectric.buildService(OfferNotificationService.class).create();
        app.getSystemService(NotificationManager.class).cancelAll();
        assertEquals("DoorDash offer: open Dasher to check it",
                cardTitleFor("New Order: Go to a place with a name far too long to be a store's own name here"));
    }
}

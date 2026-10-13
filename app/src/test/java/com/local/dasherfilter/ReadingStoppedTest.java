package com.local.dasherfilter;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.provider.Settings;
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
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The 11:06 stop of 12 October 2026 (notes/incident-2026-10-12-1106.md), through real Android adapters: Android
 * unbound the screen reader mid-dash with nothing said, and the notification listener stayed down after two rebind
 * requests. The unbind now logs whether screen reading is still on in Android's settings; Android's stop mid-dash
 * (not the user's own switch-off, not a pause, not outside a dash) posts one notification per dash, cancelled when
 * screen reading is back; and a listener still down after a rebind request has its component turned off and on, at most
 * hourly.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public class ReadingStoppedTest extends AndroidAdapterTestBase {
    @Before
    public void reset() {
        ReadingStopped.forget();
        app.getSharedPreferences(ReadingStopped.PREFS, android.content.Context.MODE_PRIVATE).edit().clear().commit();
        DiagnosticLog.clear(app);
    }

    @After
    public void forget() {
        ReadingStopped.forget();
        screenReadingInSettings(false);
    }

    private void screenReadingInSettings(boolean on) {
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                on ? new ComponentName(app, OfferFilterService.class).flattenToString() : "");
    }

    private Notification stopped() {
        for (StatusBarNotification posted : Shadows.shadowOf(app.getSystemService(NotificationManager.class))
                .getActiveNotifications()) {
            if (posted.getId() == ReadingStopped.NOTIFICATION_ID) return posted.getNotification();
        }
        return null;
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void androidsStopMidDashIsLoggedAndSaidOncePerDash() {
        screenReadingInSettings(true);
        Dashing.seen(app);
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        idle();
        service.get().onUnbind(new Intent());
        idle();
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("screen reader unbound; still on in Android's settings (Android stopped it); "
                + "low memory "));
        assertTrue(log, log.contains(", standby bucket "));
        assertTrue(log, log.contains(", battery saver "));
        Notification notice = stopped();
        assertNotNull("one notification says so", notice);
        assertEquals(AppName.NAME + " stopped reading Dasher", notice.extras.getString(Notification.EXTRA_TITLE));
        assertEquals("Tap to turn it back on", String.valueOf(notice.extras.getCharSequence(Notification.EXTRA_TEXT)));
        assertEquals(ReadingStopped.CHANNEL_ID, notice.getChannelId());
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, app.getSystemService(NotificationManager.class)
                .getNotificationChannel(ReadingStopped.CHANNEL_ID).getImportance());

        // Back: the notice goes. Stopped again in the same dash: not posted again.
        service.get().onServiceConnected();
        idle();
        assertNull("cancelled on reconnect", stopped());
        service.get().onUnbind(new Intent());
        idle();
        assertNull("once per dash", stopped());
        service.destroy();
    }

    @Test
    public void theUsersOwnSwitchOffAPauseOrNoDashSaysNothing() {
        for (String cause : new String[] {"switched off", "paused", "no dash", "notice"}) {
            reset();
            app.getSharedPreferences("dashing", android.content.Context.MODE_PRIVATE).edit().clear().commit();
            screenReadingInSettings(!cause.equals("switched off"));
            if (!cause.equals("no dash")) Dashing.seen(app);
            if (cause.equals("paused")) Dashing.paused(app);
            if (cause.equals("notice")) ConsentedTestApp.forget(app);
            try {
                ReadingStopped.unbound(app);
                assertNull(cause, stopped());
                if (cause.equals("switched off")) {
                    assertTrue(DiagnosticLog.read(app).contains("screen reader unbound; off in Android's settings"));
                }
            } finally {
                ConsentedTestApp.accept(app);
            }
        }
    }

    @Test
    public void aListenerStillDownAfterARebindIsTurnedOffAndOnAtMostHourly() {
        ComponentName listener = new ComponentName(app, OfferNotificationService.class);
        Shadows.shadowOf(app.getSystemService(NotificationManager.class)).setNotificationListenerAccessGranted(listener,
                true);
        ReadingStopped.afterRebind(app);
        ReadingStopped.afterRebind(app);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ReadingStopped.LISTENER_GRACE_MS - 100));
        assertFalse("not yet", DiagnosticLog.read(app).contains("component turned off and on"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        String log = DiagnosticLog.read(app);
        assertEquals(log, 1, count(log, "s after a rebind request while access is allowed; component turned off and on"));
        assertEquals("enabled again", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                app.getPackageManager().getComponentEnabledSetting(listener));

        // Within the hour: not again.
        ReadingStopped.afterRebind(app);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ReadingStopped.LISTENER_GRACE_MS + 100));
        log = DiagnosticLog.read(app);
        assertEquals(log, 1, count(log, "component turned off and on"));
        assertTrue(log, log.contains("turned off and on within the hour already"));

        // Access not allowed: nothing.
        ReadingStopped.forget();
        DiagnosticLog.clear(app);
        Shadows.shadowOf(app.getSystemService(NotificationManager.class)).setNotificationListenerAccessGranted(listener,
                false);
        assertFalse(ReadingStopped.nudgeListener(app));
        assertFalse(DiagnosticLog.read(app).contains("turned off and on"));
    }

    private static int count(String text, String part) {
        int found = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) found++;
        return found;
    }
}

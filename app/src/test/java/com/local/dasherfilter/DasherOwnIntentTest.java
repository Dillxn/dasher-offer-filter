package com.local.dasherfilter;

import android.app.ActivityOptions;
import android.app.Application;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Dasher's own notification tap is sent only as Dasher made it, and with the background-start opt-in each Android
 * asks for: none before 14; "allowed" on 14 and 15; from 16, "always" for the screen reader's own send (no screen of
 * ours is visible then) and "if visible" for the tap screen behind a card. Whether Dasher then shows its offer is for
 * a handset.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
public class DasherOwnIntentTest {
    private static final String DASHER = "com.doordash.driverapp";

    @Test
    @SuppressWarnings("deprecation")
    public void eachAndroidGetsTheStartModeItAsksFor() {
        assertEquals(DasherOwnIntent.NO_MODE, DasherOwnIntent.startMode(33, false));
        assertEquals(DasherOwnIntent.NO_MODE, DasherOwnIntent.startMode(26, true));
        assertEquals(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED, DasherOwnIntent.startMode(34, false));
        assertEquals(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED, DasherOwnIntent.startMode(35, true));
        assertEquals("the screen reader's own send on 16",
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS, DasherOwnIntent.startMode(36, false));
        assertEquals("the visible tap screen on 16",
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE, DasherOwnIntent.startMode(36, true));
        assertEquals(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS, DasherOwnIntent.startMode(37, false));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void theOptionsCarryTheModeOnThisPhone() throws Exception {
        Bundle options = DasherOwnIntent.options(false);
        if (Build.VERSION.SDK_INT < 34) {
            assertNull("Android before 14 asks for none", options);
            return;
        }
        java.lang.reflect.Method from = ActivityOptions.class.getDeclaredMethod("fromBundle", Bundle.class);
        from.setAccessible(true);
        ActivityOptions parsed = (ActivityOptions) from.invoke(null, options);
        assertEquals(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                parsed.getPendingIntentBackgroundActivityStartMode());
    }

    @Test
    public void onlyAnIntentDasherMadeIsDashersOwn() {
        Application app = RuntimeEnvironment.getApplication();
        assertFalse(DasherOwnIntent.fromDasher(null));
        PendingIntent ours = PendingIntent.getActivity(app, 1, new Intent().setComponent(
                new ComponentName(DASHER, DASHER + ".Offer")), PendingIntent.FLAG_IMMUTABLE);
        assertFalse("one of ours is never sent as Dasher's", DasherOwnIntent.fromDasher(ours));
        PendingIntent dashers = PendingIntent.getActivity(app, 2, new Intent().setComponent(
                new ComponentName(DASHER, DASHER + ".Offer")), PendingIntent.FLAG_IMMUTABLE);
        Shadows.shadowOf(dashers).setCreatorPackage(DASHER);
        assertTrue(DasherOwnIntent.fromDasher(dashers));
        PendingIntent broadcast = PendingIntent.getBroadcast(app, 3, new Intent("com.doordash.DISMISS"),
                PendingIntent.FLAG_IMMUTABLE);
        Shadows.shadowOf(broadcast).setCreatorPackage(DASHER);
        // From Android 12 a pending intent says whether it starts a screen: one that does not is never sent.
        assertEquals(Build.VERSION.SDK_INT < 31, DasherOwnIntent.fromDasher(broadcast));
    }
}

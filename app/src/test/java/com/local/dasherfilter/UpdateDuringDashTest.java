package com.local.dasherfilter;

import android.os.Looper;
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
import static org.junit.Assert.assertNull;

/**
 * Split-audit P3: an automatic update installed mid-dash closed Offer Filter, and with it its half of a split screen
 * beside Dasher, while the phone was locked between offers (0.4.35 and 0.4.36 both did, in one dash). Now an automatic
 * install waits while a dash is on; the user's own check still installs. On 0.4.45 nothing held an install back while a
 * dash was on with Dasher off screen and no offer or route up ({@link Updater#heldBack} did not exist: the install went
 * ahead).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class UpdateDuringDashTest extends AndroidAdapterTestBase {
    @Test
    public void anAutomaticInstallWaitsForTheDashToEndButTheUsersOwnCheckDoesNot() {
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        try {
            service.get().onServiceConnected();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull("no dash: it installs", Updater.heldBack(app, false));

            // An offer a moment ago: a dash is on. Dasher is off screen (the phone locked), no offer or route is up.
            Dashing.forgetCache();
            Dashing.seen(app);
            assertFalse(OfferFilterService.isDasherForeground());
            assertFalse(OfferNotificationService.hasActiveOffer());
            assertNull(ActiveRouteStore.load(app));
            assertEquals("Update ready: installs after your dash", Updater.heldBack(app, false));
            assertNull("the user's own check still installs", Updater.heldBack(app, true));

            // Dasher says the dash ended: the next try installs.
            Dashing.ended(app);
            assertNull(Updater.heldBack(app, false));
        } finally {
            service.destroy();
        }
    }
}

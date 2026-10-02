package com.local.dasherfilter;

import android.app.Application;
import android.content.Context;
import android.provider.Settings;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
public final class RestartSuppressionTest {
    private Application app;
    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        Settings.Global.putInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, 7);
        RestartSuppression.clear(app);
    }
    @Test public void storesOnlyBoundedNumericFactsAndExpiresOnElapsedTime() {
        OfferSnapshot offer = new OfferSnapshot(790, 7.2, 21, 2, null, 3.0);
        assertTrue(RestartSuppression.remember(app, offer, 35));
        assertEquals(new HashSet<>(Arrays.asList("elapsed", "boot", "countdown", "pay", "miles", "minutes", "stops")),
                app.getSharedPreferences(RestartSuppression.PREFS, Context.MODE_PRIVATE).getAll().keySet());
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30));
        RestartSuppression.Saved saved = RestartSuppression.load(app);
        assertNotNull(saved);
        assertEquals(offer.fingerprint(), saved.offer.fingerprint());
        assertNull("hotspot context is never persisted by this guard", saved.offer.finalStopHotspotMiles);
        assertEquals(30_000, saved.ageMs);
        ShadowSystemClock.advanceBy(Duration.ofMillis(RestartSuppression.MAX_MS));
        assertNull(RestartSuppression.load(app));
    }
    @Test public void rebootCannotRestoreAnEarlierOffersSuppressionOrAuthority() {
        RestartSuppression.remember(app, new OfferSnapshot(790, 7.2, 21, 2), 35);
        Settings.Global.putInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, 8);
        assertNull(RestartSuppression.load(app));
    }
    @Test public void corruptRecordOnlySuppressesAndContainsNoTapAuthority() {
        app.getSharedPreferences(RestartSuppression.PREFS, Context.MODE_PRIVATE).edit()
                .putString("elapsed", "broken").commit();
        RestartSuppression.Saved saved = RestartSuppression.load(app);
        assertNotNull(saved);
        assertSame(OfferSnapshot.UNKNOWN, saved.offer);
        assertEquals(-1, saved.countdown);
        RestartSuppression.clear(app);
        assertNull(RestartSuppression.load(app));
    }
    @Test public void pauseIsNotADashEnd() {
        assertTrue(OfferEvidence.isPaused(Arrays.asList("Dash paused", "Resume dash")));
        assertTrue(OfferEvidence.isIdle(Arrays.asList("Dash paused", "Resume dash")));
        assertFalse(OfferEvidence.isDashOver(Arrays.asList("Dash paused", "Resume dash", "End dash")));
        assertTrue(OfferEvidence.isDashOver(Arrays.asList("Your dash has ended")));
    }
}

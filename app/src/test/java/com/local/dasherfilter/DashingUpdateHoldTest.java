package com.local.dasherfilter;

import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Silence may expire live display freshness, but must not authorize closing an unknown ongoing shift. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26, 35, 36})
public class DashingUpdateHoldTest {
    private Context app;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("dashing", Context.MODE_PRIVATE).edit().clear().commit();
        ActiveRouteStore.clear(app);
        Dashing.forgetCache();
    }

    @Test public void quietShiftRemainsHeldAcrossCacheResetWithoutConnectedServices() {
        long seen = System.currentTimeMillis() - Dashing.WINDOW_MS - 1;
        app.getSharedPreferences("dashing", Context.MODE_PRIVATE).edit()
                .putLong("seen_at", seen).putLong("started_at", seen).commit();
        assertFalse(Dashing.on(app));
        assertTrue(Dashing.awaitingEnd(app));
        Dashing.forgetCache();
        assertTrue(Dashing.awaitingEnd(app));
    }

    @Test public void onlyPositiveEndReleasesAnObservedShift() {
        assertFalse(Dashing.awaitingEnd(app));
        Dashing.seen(app);
        assertTrue(Dashing.awaitingEnd(app));
        Dashing.ended(app);
        assertFalse(Dashing.awaitingEnd(app));
    }

    @Test public void pausedShiftKeepsInstallHoldAndResumeClearsPause() {
        Dashing.paused(app);
        assertTrue(Dashing.isPaused(app));
        assertTrue(Dashing.awaitingEnd(app));
        Dashing.seen(app);
        assertFalse(Dashing.isPaused(app));
        assertTrue(Dashing.awaitingEnd(app));
        Dashing.paused(app);
        Dashing.ended(app);
        assertFalse(Dashing.isPaused(app));
        assertFalse(Dashing.awaitingEnd(app));
    }

    @Test public void clockMovingBackwardsDoesNotTurnAnOpenShiftIntoInstallPermission() {
        long future = System.currentTimeMillis() + 60_000;
        app.getSharedPreferences("dashing", Context.MODE_PRIVATE).edit().putLong("seen_at", future).commit();
        assertFalse(Dashing.on(app));
        assertTrue(Dashing.awaitingEnd(app));
    }

    @Test public void newShiftAndPositiveEndOverrideOldFutureTimestamps() {
        long future = System.currentTimeMillis() + 60_000;
        app.getSharedPreferences("dashing", Context.MODE_PRIVATE).edit()
                .putLong("seen_at", future).putLong("ended_at", future + 1).commit();
        Dashing.seen(app);
        assertTrue(Dashing.awaitingEnd(app));
        assertTrue(Dashing.on(app));
        app.getSharedPreferences("dashing", Context.MODE_PRIVATE).edit().putLong("seen_at", future).commit();
        Dashing.ended(app);
        assertFalse(Dashing.awaitingEnd(app));
        assertFalse(Dashing.on(app));
    }
}

package com.local.dasherfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Application;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** The offer areas: on from the start but nothing kept without location, approximate, counted once per offer, ranked fairly, and kept off reports. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
public class AreaMapTest {
    private Application app;
    private int offers;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        AreaMap.forgetCache();
        DecisionLog.forgetCache();
    }

    private void grantLocation() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private void at(double latitude, double longitude, long ageMs) {
        Location fix = new Location(LocationManager.NETWORK_PROVIDER);
        fix.setLatitude(latitude);
        fix.setLongitude(longitude);
        fix.setAccuracy(1500);
        fix.setTime(System.currentTimeMillis() - ageMs);
        fix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos() - ageMs * 1_000_000L);
        Shadows.shadowOf(app.getSystemService(LocationManager.class))
                .setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix);
    }

    /** A new, distinct offer as the decision log records it. */
    private DecisionLog.Entry offer(Integer payCents, Double miles, boolean addOn) {
        offers++;
        return new DecisionLog.Entry(System.currentTimeMillis(), DecisionLog.Source.SCREEN, addOn,
                new OfferSnapshot(payCents, miles, 20 + offers, 2), 1000, OfferRule.Result.KEEP,
                "meets enabled rules", DecisionLog.Action.PASSES, true, Collections.emptyList());
    }

    @Test
    public void onByDefaultButNothingIsKeptWhenTurnedOffOrWithoutLocation() {
        assertTrue("the homepage's ground is the map from the start", AreaMap.enabled(app));
        // No location permission yet: nothing is kept, not even a count of located offers.
        at(37.7749, -122.4194, 0);
        DecisionLog.record(app, offer(1300, 6.5, false));
        assertTrue(AreaMap.cells(app).isEmpty());

        int unlocated = AreaMap.unlocated(app);

        // Turned off: nothing more is kept, located or not.
        grantLocation();
        AreaMap.setEnabled(app, false);
        DecisionLog.record(app, offer(1200, 6.0, false));
        assertTrue(AreaMap.cells(app).isEmpty());
        assertEquals(unlocated, AreaMap.unlocated(app));
    }

    @Test
    public void eachNewOfferIsAddedToTheSquareThePhoneIsIn() {
        grantLocation();
        AreaMap.setEnabled(app, true);
        at(37.7749, -122.4194, 60_000);
        DecisionLog.record(app, offer(1200, 6.0, false));
        DecisionLog.record(app, offer(900, null, false));

        List<AreaMap.Cell> cells = AreaMap.cells(app);
        assertEquals(1, cells.size());
        AreaMap.Cell cell = cells.get(0);
        assertEquals(2, cell.offers);
        assertEquals(2100, cell.payCents);
        assertEquals("only the offer whose miles were read counts toward pay per mile", 1, cell.mileOffers);
        assertEquals(1200, cell.bestPayCents);
        // Kept as a square, not a position: its center is the rounded grid point.
        assertEquals(37.77, cell.latitude(), 1e-9);
        assertEquals(-122.41, cell.longitude(), 1e-9);
    }

    @Test
    public void theSameOfferSeenTwiceCountsOnce() {
        grantLocation();
        AreaMap.setEnabled(app, true);
        at(37.7749, -122.4194, 0);
        DecisionLog.Entry fromNotification = new DecisionLog.Entry(System.currentTimeMillis(),
                DecisionLog.Source.NOTIFICATION, false, new OfferSnapshot(1500, 5.0, 25, 2), 1000,
                OfferRule.Result.KEEP, "meets enabled rules", DecisionLog.Action.BELL, true, Collections.emptyList());
        DecisionLog.Entry onScreen = new DecisionLog.Entry(System.currentTimeMillis(), DecisionLog.Source.SCREEN,
                false, new OfferSnapshot(1500, 5.0, 25, 2), 1000, OfferRule.Result.KEEP, "meets enabled rules",
                DecisionLog.Action.PASSES, true, Collections.emptyList());
        DecisionLog.record(app, fromNotification);
        DecisionLog.record(app, onScreen);
        assertEquals(1, AreaMap.totalOffers(AreaMap.cells(app)));
    }

    @Test
    public void unknownPayAddOnsAndMissingOrStaleLocationsAddNothing() {
        AreaMap.setEnabled(app, true);
        at(37.7749, -122.4194, 0);
        DecisionLog.record(app, offer(1200, 6.0, false));
        assertEquals("no permission: counted as without a location", 1, AreaMap.unlocated(app));

        grantLocation();
        DecisionLog.record(app, offer(null, 6.0, false));
        DecisionLog.record(app, offer(1200, 6.0, true));
        assertEquals("unknown pay and add-ons are not area offers", 1, AreaMap.unlocated(app));
        assertTrue(AreaMap.cells(app).isEmpty());

        at(37.7749, -122.4194, AreaMap.FRESH_MS + 60_000);
        DecisionLog.record(app, offer(1200, 6.0, false));
        assertEquals("a stale fix is not used", 2, AreaMap.unlocated(app));
        assertTrue(AreaMap.cells(app).isEmpty());
        assertNull(AreaMap.here(app));
    }

    @Test
    public void squaresRankByPooledPayPerMileOnceTheyHaveEnoughOffers() {
        grantLocation();
        AreaMap.setEnabled(app, true);
        // One square: a short, high-rate trip and two long ones. Pooled, it is $2.00/mi, not the $4 short trip.
        at(37.7749, -122.4194, 0);
        DecisionLog.record(app, offer(800, 2.0, false));
        DecisionLog.record(app, offer(1600, 9.0, false));
        DecisionLog.record(app, offer(1600, 9.0, false));
        // Another at $2.50/mi, and a third with too few offers to rank.
        at(37.8149, -122.3794, 0);
        DecisionLog.record(app, offer(1000, 4.0, false));
        DecisionLog.record(app, offer(1000, 4.0, false));
        DecisionLog.record(app, offer(1000, 4.0, false));
        at(37.7349, -122.4594, 0);
        DecisionLog.record(app, offer(5000, 4.0, false));

        List<AreaMap.Cell> ranked = AreaMap.ranked(AreaMap.cells(app));
        assertEquals(2, ranked.size());
        assertEquals("$2.50/mi", ranked.get(0).perMile());
        assertEquals("$2.00/mi", ranked.get(1).perMile());
    }

    @Test
    public void directionsAreGivenFromWhereYouAre() {
        AreaMap.Cell north = new AreaMap.Cell(1890, -6121);
        assertEquals("", AreaMap.from(null, north));
        assertEquals("2.4 mi N of you", AreaMap.from(new double[] {37.7749, -122.41}, north));
        assertEquals("Around you", AreaMap.from(new double[] {37.81, -122.41}, north));
    }

    @Test
    public void reportsCarryCountsButNeverAPosition() {
        grantLocation();
        AreaMap.setEnabled(app, true);
        at(37.7749, -122.4194, 0);
        DecisionLog.record(app, offer(1200, 6.0, false));
        String report = DiagnosticLog.report(app);
        assertTrue(report.contains("Offer areas: on; 1 squares; 1 offers; 0 without a location"));
        assertFalse(report.contains("37.7"));
        assertFalse(report.contains("122.4"));
    }

    @Test
    public void forgettingRemovesEveryArea() {
        grantLocation();
        AreaMap.setEnabled(app, true);
        at(37.7749, -122.4194, 0);
        DecisionLog.record(app, offer(1200, 6.0, false));
        AreaMap.forget(app);
        AreaMap.forgetCache();
        assertTrue(AreaMap.cells(app).isEmpty());
        assertTrue("forgetting does not turn mapping off", AreaMap.enabled(app));
    }
}

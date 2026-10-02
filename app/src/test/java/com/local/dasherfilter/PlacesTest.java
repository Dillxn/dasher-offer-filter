package com.local.dasherfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.location.Address;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowGeocoder;

/** Place names: asked about only at rounded positions, once each, kept on the phone, and never while off. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
public class PlacesTest {
    private Application app;

    @After
    public void restore() {
        Places.lookup = Places.GEOCODER;
    }

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Places.setEnabled(app, true);
        Places.forget(app);
    }

    private static Address address(String subLocality, String street, String town) {
        Address address = new Address(Locale.US);
        address.setSubLocality(subLocality);
        address.setThoroughfare(street);
        address.setLocality(town);
        return address;
    }

    @Test
    public void positionsAreRoundedToAboutHalfAKilometreBeforeAnyoneIsAsked() {
        assertEquals("41.880,-87.630", Places.key(41.8812, -87.6311));
        assertEquals(Places.key(41.8812, -87.6311), Places.key(41.8789, -87.6288));
        assertFalse(Places.key(41.8812, -87.6311).equals(Places.key(41.8870, -87.6311)));
    }

    @Test
    public void aNeighbourhoodReadsBestThenAStreetThenATown() {
        assertEquals("Wicker Park", Places.nameOf(address("Wicker Park", "N Damen Ave", "Chicago")));
        assertEquals("N Damen Ave", Places.nameOf(address(" ", "N Damen Ave", "Chicago")));
        assertEquals("Chicago", Places.nameOf(address(null, null, "Chicago")));
        assertNull(Places.nameOf(address(null, "", null)));
    }

    @Test
    public void aNameIsLookedUpOnceInTheBackgroundAndKeptOnThePhone() throws Exception {
        ShadowGeocoder.setIsPresent(true);
        List<String> asked = new ArrayList<>();
        Places.lookup = (context, latitude, longitude) -> {
            asked.add(String.format(Locale.US, "%.3f,%.3f", latitude, longitude));
            return Collections.singletonList(address("Wicker Park", null, "Chicago"));
        };
        long before = Places.version();
        assertNull("nothing is known until the lookup answers", Places.name(app, 41.9088, -87.6796));
        long deadline = System.currentTimeMillis() + 5000;
        while (Places.version() == before && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals("Wicker Park", Places.name(app, 41.9088, -87.6796));
        assertEquals("Wicker Park", Places.name(app, 41.9102, -87.6789));
        assertEquals("only the rounded position was asked about, once", List.of("41.910,-87.680"), asked);
        assertTrue(app.getSharedPreferences("places", 0).contains("name:" + Places.key(41.9088, -87.6796)));
    }

    @Test
    public void turnedOffNothingIsAskedAndNothingShows() {
        app.getSharedPreferences("places", 0).edit().putString("name:" + Places.key(41.9, -87.68), "Wicker Park")
                .apply();
        Places.setEnabled(app, false);
        assertNull(Places.name(app, 41.9, -87.68));
        Places.setEnabled(app, true);
        assertEquals("Wicker Park", Places.name(app, 41.9, -87.68));
    }

    @Test
    public void forgettingNamesKeepsWhetherNamesAreOn() {
        app.getSharedPreferences("places", 0).edit().putString("name:" + Places.key(41.9, -87.68), "Wicker Park")
                .apply();
        Places.setEnabled(app, false);
        Places.forget(app);
        assertFalse(Places.enabled(app));
        assertFalse(app.getSharedPreferences("places", 0).contains("name:" + Places.key(41.9, -87.68)));
    }

    @Test public void cacheEvictsAnUnusedNameAndKeepsARecentlyReadName() throws Exception {
        android.content.SharedPreferences prefs = app.getSharedPreferences("places", 0);
        android.content.SharedPreferences.Editor seed = prefs.edit();
        for (int i = 0; i < Places.MAX_NAMES; i++) {
            seed.putString("name:" + Places.key(40 + i * Places.ROUNDING, -87), "Area " + i);
        }
        seed.commit();
        assertEquals("Area 0", Places.name(app, 40, -87));
        ShadowGeocoder.setIsPresent(true);
        AtomicInteger asks = new AtomicInteger();
        Places.lookup = (context, latitude, longitude) -> {
            asks.incrementAndGet();
            return Collections.singletonList(address("New area", null, null));
        };
        long before = Places.version();
        Places.name(app, 42, -87);
        awaitChange(before);
        assertEquals(Places.MAX_NAMES, countNames());
        assertTrue("the recently read oldest name stays", prefs.contains("name:" + Places.key(40, -87)));
        assertFalse("the least recently used name goes", prefs.contains("name:" + Places.key(40.005, -87)));
        assertEquals("New area", Places.name(app, 42, -87));
        assertEquals(1, asks.get());
    }

    @Test public void anOldUnboundedCacheIsTrimmedWhenFirstRead() {
        android.content.SharedPreferences.Editor seed = app.getSharedPreferences("places", 0).edit();
        for (int i = 0; i < Places.MAX_NAMES + 20; i++) {
            seed.putString("name:" + Places.key(40 + i * Places.ROUNDING, -87), "Area " + i);
        }
        seed.commit();
        ShadowGeocoder.setIsPresent(false);
        Places.name(app, 40, -87);
        assertEquals(Places.MAX_NAMES, countNames());
        Places.forget(app);
        assertEquals(0, countNames());
        assertFalse(app.getSharedPreferences("places", 0).contains("recent_names"));
    }

    @Test public void clearingWhileLookupRunsCannotRestoreOldNamesOrCancelANewRequest() throws Exception {
        ShadowGeocoder.setIsPresent(true);
        CountDownLatch firstEntered = new CountDownLatch(1), secondEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1), releaseSecond = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Places.lookup = (context, latitude, longitude) -> {
            int call = calls.incrementAndGet();
            (call == 1 ? firstEntered : secondEntered).countDown();
            await(call == 1 ? releaseFirst : releaseSecond);
            return Collections.singletonList(address(call == 1 ? "Old area" : "Fresh area", null, null));
        };
        try {
            Places.name(app, 40, -87);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            Places.forget(app);
            long cleared = Places.version();
            Places.name(app, 40, -87);
            releaseFirst.countDown();
            assertTrue("a new request survives the stale result", secondEntered.await(5, TimeUnit.SECONDS));
            assertEquals("the old result has completed, but restored nothing", 0, countNames());
            assertEquals(cleared, Places.version());
            releaseSecond.countDown();
            awaitChange(cleared);
            assertEquals("Fresh area", Places.name(app, 40, -87));
            assertEquals(1, countNames());
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
        }
    }

    private long countNames() {
        return app.getSharedPreferences("places", 0).getAll().keySet().stream()
                .filter(key -> key.startsWith("name:")).count();
    }

    private void awaitChange(long before) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (Places.version() == before && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue("lookup finishes", Places.version() != before);
    }

    private static void await(CountDownLatch latch) throws java.io.IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("test lookup timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException(interrupted);
        }
    }
}

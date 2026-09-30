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
}

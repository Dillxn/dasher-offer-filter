package com.local.dasherfilter;

import static org.junit.Assert.*;

import java.time.Instant;
import java.util.TimeZone;
import org.junit.Test;

public class SolarCycleTest {
    private static long at(String utc) { return Instant.parse(utc).toEpochMilli(); }
    private static boolean night(String utc, double latitude, double longitude) {
        return SolarCycle.night(at(utc), latitude, longitude);
    }

    @Test public void summerEveningIsDayWhileWinterEveningIsNightInCincinnati() {
        // 8 pm local daylight time in June; 6 pm local standard time in December.
        assertFalse(night("2026-06-22T00:00:00Z", 39.1, -84.5));
        assertTrue(night("2026-12-21T23:00:00Z", 39.1, -84.5));
    }

    @Test public void morningAndEveningCrossTheActualSunCycleNotSixOclock() {
        // Cincinnati's October sunrise falls between 7 and 8 am EDT; sunset between 7 and 8 pm.
        assertTrue(night("2026-10-03T11:00:00Z", 39.1, -84.5));
        assertFalse(night("2026-10-03T12:00:00Z", 39.1, -84.5));
        assertFalse(night("2026-10-03T23:00:00Z", 39.1, -84.5));
        assertTrue(night("2026-10-04T00:00:00Z", 39.1, -84.5));
    }

    @Test public void longitudeAndOppositeHemisphereAreNotAssumedToBeTheUnitedStates() {
        assertFalse(night("2026-12-21T08:00:00Z", -33.9, 151.2)); // Sydney summer evening
        assertTrue(night("2026-06-21T08:00:00Z", -33.9, 151.2)); // Sydney winter evening
        assertFalse(night("2026-10-03T03:00:00Z", 35.7, 139.7)); // Tokyo midday
        assertTrue(night("2026-10-03T15:00:00Z", 35.7, 139.7)); // Tokyo midnight
    }

    @Test public void polarDayAndNightDoNotCreateNanOrInventCrossings() {
        for (int hour = 0; hour < 24; hour++) {
            String time = String.format(java.util.Locale.US, "%02d:00:00Z", hour);
            assertFalse(night("2026-06-21T" + time, 78.2, 15.6));
            assertTrue(night("2026-12-21T" + time, 78.2, 15.6));
            assertFalse(night("2026-12-21T" + time, -90, 0));
            assertTrue(night("2026-06-21T" + time, -90, 0));
        }
    }

    @Test public void dateLineCoordinatesAreEquivalent() {
        for (int hour = 0; hour < 24; hour++) {
            long time = at("2026-10-03T00:00:00Z") + hour * 3600000L;
            assertEquals(SolarCycle.night(time, 10, -180), SolarCycle.night(time, 10, 180));
        }
    }

    @Test public void invalidCoordinatesStayUnknownButZeroAndPolesAreValid() {
        long time = at("2026-06-21T12:00:00Z");
        assertNull(SolarCycle.night(time, Double.NaN, 0));
        assertNull(SolarCycle.night(time, 0, Double.POSITIVE_INFINITY));
        assertNull(SolarCycle.night(time, 91, 0));
        assertNull(SolarCycle.night(time, 0, 181));
        assertFalse(SolarCycle.night(time, 0, 0));
        assertFalse(SolarCycle.night(time, 90, 0));
    }

    @Test public void leapDayAndYearBoundaryRemainUsable() {
        assertFalse(night("2028-02-29T12:00:00Z", 0, 0));
        assertTrue(night("2028-03-01T00:00:00Z", 0, 0));
        assertFalse(night("2026-12-31T17:00:00Z", 39.1, -84.5));
        assertTrue(night("2027-01-01T05:00:00Z", 39.1, -84.5));
    }

    @Test public void fallbackUsesLocalTimezoneAndDocumentedBoundaries() {
        TimeZone east = TimeZone.getTimeZone("America/New_York");
        assertTrue(SolarCycle.clockNight(at("2026-10-03T09:59:59Z"), east));
        assertFalse(SolarCycle.clockNight(at("2026-10-03T10:00:00Z"), east));
        assertFalse(SolarCycle.clockNight(at("2026-10-03T21:59:59Z"), east));
        assertTrue(SolarCycle.clockNight(at("2026-10-03T22:00:00Z"), east));
        assertFalse(SolarCycle.clockNight(at("2026-12-21T11:00:00Z"), east));
        assertTrue(SolarCycle.clockNight(at("2026-12-21T10:59:59Z"), east));
    }

    @Test public void sunCalculationDoesNotDependOnPhoneTimezone() {
        TimeZone before = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            Boolean east = SolarCycle.night(at("2026-10-03T23:00:00Z"), 39.1, -84.5);
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            assertEquals(east, SolarCycle.night(at("2026-10-03T23:00:00Z"), 39.1, -84.5));
        } finally {
            TimeZone.setDefault(before);
        }
    }
}

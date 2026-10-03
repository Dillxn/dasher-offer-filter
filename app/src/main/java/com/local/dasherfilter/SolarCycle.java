package com.local.dasherfilter;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Offline day/night estimate from the sun's position, not the phone's clock hour. NOAA's general solar-position
 * equations, including the 90.833 degree apparent-sunrise threshold:
 * https://gml.noaa.gov/grad/solcalc/solareqns.PDF
 * This is a theme estimate, not an observation of terrain, weather or the horizon.
 */
final class SolarCycle {
    private static final double SUNRISE_COSINE = Math.cos(Math.toRadians(90.833));

    /** Null for invalid coordinates. Handles polar day/night without inventing a sunrise or dividing by cos(lat). */
    static Boolean night(long at, double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) return null;
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(at);
        double minutes = utc.get(Calendar.HOUR_OF_DAY) * 60d + utc.get(Calendar.MINUTE)
                + utc.get(Calendar.SECOND) / 60d + utc.get(Calendar.MILLISECOND) / 60000d;
        double year = 2 * Math.PI / utc.getActualMaximum(Calendar.DAY_OF_YEAR)
                * (utc.get(Calendar.DAY_OF_YEAR) - 1 + (minutes / 60 - 12) / 24);
        double equation = 229.18 * (0.000075 + 0.001868 * Math.cos(year) - 0.032077 * Math.sin(year)
                - 0.014615 * Math.cos(2 * year) - 0.040849 * Math.sin(2 * year));
        double declination = 0.006918 - 0.399912 * Math.cos(year) + 0.070257 * Math.sin(year)
                - 0.006758 * Math.cos(2 * year) + 0.000907 * Math.sin(2 * year)
                - 0.002697 * Math.cos(3 * year) + 0.00148 * Math.sin(3 * year);
        // UTC has no daylight-saving offset. Positive longitude is east; cosine is periodic across the date line.
        double hourAngle = Math.toRadians((minutes + equation + 4 * longitude) / 4 - 180);
        double lat = Math.toRadians(latitude);
        double zenithCosine = Math.sin(lat) * Math.sin(declination)
                + Math.cos(lat) * Math.cos(declination) * Math.cos(hourAngle);
        return zenithCosine < SUNRISE_COSINE;
    }

    /** Explicitly a clock fallback, never presented as a calculated sunrise/sunset. */
    static boolean clockNight(long at, TimeZone zone) {
        Calendar clock = Calendar.getInstance(zone);
        clock.setTimeInMillis(at);
        int hour = clock.get(Calendar.HOUR_OF_DAY);
        return hour < 6 || hour >= 18;
    }

    private SolarCycle() {}
}

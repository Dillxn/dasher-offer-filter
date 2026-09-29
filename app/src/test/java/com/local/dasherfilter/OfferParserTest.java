package com.local.dasherfilter;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public final class OfferParserTest {
    @Test public void parsesCompactDoorDashOfferMetrics() {
        OfferSnapshot offer = OfferParser.parse(Arrays.asList(
                "$7.90",
                "incl. tips",
                "2 stops (7.2 mi) • 21 min",
                "Chick-fil-A"));
        assertEquals(Integer.valueOf(790), offer.payCents);
        assertEquals(7.2, offer.miles, 0.001);
        assertEquals(Integer.valueOf(21), offer.minutes);
        assertEquals(Integer.valueOf(2), offer.stops);
    }

    @Test public void doesNotTreatWaitEstimateAsOfferDuration() {
        OfferSnapshot offer = OfferParser.parse(Arrays.asList(
                "Finding offers",
                "Zone offer wait",
                "1-2 min"));
        assertNull(offer.minutes);
    }

    @Test public void parsesCompactStopsAndMinutesWithoutMiles() {
        OfferSnapshot offer = OfferParser.parse(Arrays.asList(
                "$12.00",
                "3 stops • 34 min"));
        assertEquals(Integer.valueOf(34), offer.minutes);
        assertEquals(Integer.valueOf(3), offer.stops);
    }
}

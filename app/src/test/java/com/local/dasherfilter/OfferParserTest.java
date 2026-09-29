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

    @Test public void payLabelNeverTakesAnHourlyRateFromTheNextLine() {
        // A rate is never pay: the $30.00 above the label is the only pay figure.
        OfferSnapshot offer = OfferParser.parse(Arrays.asList("$30.00", "Guaranteed", "$12.00/hr"));
        assertEquals(Integer.valueOf(3000), offer.payCents);
    }

    @Test public void payLabelNeverTakesAnIncrementFromTheNextLine() {
        // "+$2.00" adds to something; with two figures on screen and no labeled total, pay is unknown.
        OfferSnapshot offer = OfferParser.parse(
                Arrays.asList("$9.90", "Guaranteed (incl. tips)", "+$2.00 Peak Pay", "2 stops (7.2 mi) • 21 min"));
        assertNull(offer.payCents);
    }

    @Test public void incrementAloneIsNeverPay() {
        // The guaranteed amount is not readable yet; the bonus line must not stand in for it.
        assertNull(OfferParser.parse(Arrays.asList(
                "Guaranteed (incl. tips)", "+$2.00 Peak Pay", "2 stops (7.2 mi) • 21 min")).payCents);
        assertNull(OfferParser.parse(Arrays.asList("Guaranteed", "+$2.00")).payCents);
        assertNull(OfferParser.parse(Arrays.asList("Guaranteed +$2.00 extra")).payCents);
        // A real total beside an increment is still read.
        assertEquals(Integer.valueOf(990),
                OfferParser.parse(Arrays.asList("$9.90 Guaranteed", "+$2.00 Peak Pay")).payCents);
    }

    /** Labels read from a real double-order offer screen (addresses shortened), Sept 2026. */
    private static final java.util.List<String> REAL_DOUBLE_OFFER = Arrays.asList(
            "Decline", "$9.75", "incl. tips", "4 stops (2.6 mi) • 30 min", "Jersey Mike's Subs",
            "53 East 4th Street, 45202 OH", "Chipotle Mexican Grill", "1 Fountain Square Plaza, 45202 OH",
            "Multiple dropoffs (2 stops)", "Guaranteed earnings for completing the offer.", "Accept", "0:46");

    @Test public void readsARealDoubleOrderScreen() {
        OfferSnapshot offer = OfferParser.parse(REAL_DOUBLE_OFFER);
        assertEquals(Integer.valueOf(975), offer.payCents);
        assertEquals(2.6, offer.miles, 0.001);
        assertEquals(Integer.valueOf(30), offer.minutes);
        // "Multiple dropoffs (2 stops)" breaks down the drop-offs; the route line's 4 stops is the total.
        assertEquals(Integer.valueOf(4), offer.stops);
    }

    @Test public void stopBreakdownAloneIsNotTheTotal() {
        assertNull(OfferParser.parse(Arrays.asList("$9.75", "Multiple dropoffs (2 stops)")).stops);
    }
}


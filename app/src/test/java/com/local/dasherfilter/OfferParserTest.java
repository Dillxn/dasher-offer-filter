package com.local.dasherfilter;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public final class OfferParserTest {
    @Test public void hotspotDirectionsAreNotRouteTravelOrFinalStopEvidence() {
        // Synthetic exclusion fixture, not a claim that Dasher exposes final-stop geometry.
        OfferSnapshot offer = OfferParser.parse(Arrays.asList("$15.00", "2 stops (5 mi) • 20 min",
                "Hotspot 0.5 mi · estimated 3 min"));
        assertEquals(5.0, offer.miles, 0);
        assertEquals(Integer.valueOf(20), offer.minutes);
        assertNull(offer.finalStopHotspotMiles);
        OfferSnapshot hotspotOnly = OfferParser.parse(Arrays.asList("Hotspot 0.5 mi · estimated 3 min"));
        assertNull(hotspotOnly.miles);
        assertNull(hotspotOnly.minutes);
        assertNull(hotspotOnly.finalStopHotspotMiles);
    }

    @Test public void parsesHourOnlyDurationsWithoutGuessingWaitsOrRates() {
        assertEquals(Integer.valueOf(60), OfferParser.parse(Arrays.asList("$28.00", "4 stops (29 mi) • 1 hr")).minutes);
        assertEquals(Integer.valueOf(120), OfferParser.parse(Arrays.asList("$40.00", "4 stops (35 mi) • 2 hrs")).minutes);
        assertEquals(Integer.valueOf(71), OfferParser.parse(Arrays.asList("$28.00", "4 stops (29 mi) • 1 hr 11 min")).minutes);
        assertEquals(Integer.valueOf(120), OfferParser.parse(Arrays.asList("Estimated duration 2 hours")).minutes);
        assertNull(OfferParser.parse(Arrays.asList("Zone offer wait 1 hr", "4 stops")).minutes);
        assertNull(OfferParser.parse(Arrays.asList("$25/hr", "4 stops (8 mi)")).minutes);
        assertNull(OfferParser.parse(Arrays.asList("4 stops (29 mi) • 1-2 hr")).minutes);
        assertNull(OfferParser.parse(Arrays.asList("4 stops (29 mi) • 1 hr 11.5 min")).minutes);
        assertNull(OfferParser.parse(Arrays.asList("Estimated duration 1 hr", "Estimated duration 2 hr")).minutes);
    }

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

    @Test public void aPlusAmountBesideATotalBoundsPayButIsNeverPay() {
        OfferSnapshot offer = OfferParser.parse(Arrays.asList(
                "+$1", "$7.35", "incl. tips", "2 stops (7.1 mi) • 23 min"));
        assertNull(offer.payCents);
        assertEquals("Pay ? (at most $8.35), miles 7.1, minutes 23, stops 2", offer.summary());
        // A worded bonus beside a labeled total gets no bound.
        assertTrue(OfferParser.parse(Arrays.asList("$9.90", "Guaranteed (incl. tips)", "+$2.00 Peak Pay",
                "2 stops (7.2 mi) • 21 min")).summary().startsWith("Pay ?, "));
    }

    @Test public void stopBreakdownAloneIsNotTheTotal() {
        assertNull(OfferParser.parse(Arrays.asList("$9.75", "Multiple dropoffs (2 stops)")).stops);
    }

    @Test public void singlePickupBundleCountsNormalizeButNeverBecomeItemFacts() {
        OfferSnapshot offer = OfferParser.parse(Arrays.asList("Very busy", "+$1", "Decline", "$13.00 incl. tips",
                "3 stops (7.0 mi) • 33 min", "Pick up 2 orders", "Multiple dropoffs (2 stops)",
                "Pick up 2 orders", "Multiple dropoffs (2 stops)", "Accept", "0:24"));
        assertEquals(Integer.valueOf(1500), offer.payAtMostCents);
        assertNull(offer.payCents);
        assertNull(offer.items);
        assertFalse(offer.itemCountApplicable);
        OfferSnapshot normalized = OfferParser.parse(Arrays.asList("+$1", "$13.00",
                "3 stops (7.0 mi) • 33 min", "  PICK-UP   2   ORDERS ", "Multiple drop-offs ( 2 stops )"));
        assertEquals(Integer.valueOf(1500), normalized.payAtMostCents);
    }

    @Test public void aBundleCountCannotRelaxMoneyOrAdjacencyGuards() {
        for (String[] prefix : new String[][] {
                {"+$1", "incl. tips", "$13.00"}, {"+$1 Peak Pay", "$13.00"},
                {"+$1", "$13.00", "+$2"}, {"+$1", "$13.00", "$14.00"},
                {"+$1", "$13.001"}, {"+$1", "$13.00", "$2/mi"},
                {"+$1", "$13.00", "up to"}, {"+$1", "$13.00", "2x"}}) {
            java.util.List<String> labels = new java.util.ArrayList<>(Arrays.asList(prefix));
            labels.addAll(Arrays.asList("3 stops (7.0 mi) • 33 min", "Pick up 2 orders", "Multiple dropoffs (2 stops)"));
            assertNull(Arrays.toString(prefix), OfferParser.parse(labels).payAtMostCents);
        }
    }
}

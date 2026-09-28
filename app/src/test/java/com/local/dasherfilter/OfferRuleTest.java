package com.local.dasherfilter;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class OfferRuleTest {
    @Test
    public void parsesAnOfferWithExplicitValues() {
        OfferSnapshot offer = OfferParser.parse(Arrays.asList(
                "$8.50 Guaranteed", "4.2 mi", "Estimated time 24 min", "3 stops"));
        assertEquals(Integer.valueOf(850), offer.payCents);
        assertEquals(4.2, offer.miles, 0.001);
        assertEquals(Integer.valueOf(24), offer.minutes);
        assertEquals(Integer.valueOf(3), offer.stops);
    }

    @Test
    public void declinesWhenKnownFloorFailsEvenIfTimeIsMissing() {
        FilterSettings settings = new FilterSettings(true, 600, 150, 30, 100, 0);
        OfferSnapshot offer = OfferParser.parse(Arrays.asList("$5.00 Guaranteed", "4 mi"));
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(offer, settings).result);
    }

    @Test
    public void leavesAmbiguousOffersForReview() {
        FilterSettings settings = new FilterSettings(true, 600, 150, 30, 100, 0);
        OfferSnapshot ambiguousPay = OfferParser.parse(Arrays.asList("$8.00", "$9.00", "3 mi"));
        assertNull(ambiguousPay.payCents);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(ambiguousPay, settings).result);

        OfferSnapshot missingTime = OfferParser.parse(Arrays.asList("$10.00 Guaranteed", "3 mi"));
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(missingTime, settings).result);
    }

    @Test
    public void ignoresAnUnrelatedEarningsAmountWhenPayIsGuaranteed() {
        OfferSnapshot offer = OfferParser.parse(Arrays.asList(
                "$100.00 Weekly earnings", "Guaranteed", "$8.50", "4 mi"));
        assertEquals(Integer.valueOf(850), offer.payCents);
    }

    @Test
    public void chargesOnlyForStopsBeyondPickupAndDropoff() {
        FilterSettings settings = new FilterSettings(true, 600, 100, 0, 200, 0);
        OfferSnapshot offer = OfferParser.parse(Arrays.asList("$7.00 Guaranteed", "1 mi", "3 stops"));
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(offer, settings).result);
        assertEquals(800, OfferRule.evaluate(offer, settings).requiredCents);
    }

    @Test
    public void readsMileageAndStopCountsSplitAcrossSiblingNodes() {
        OfferSnapshot offer = OfferParser.parse(Arrays.asList("$8.50", "4.2", "mi", "3", "stops"),
                OfferParser.joinMetricSiblings(Arrays.asList("4.2", "mi", "3", "stops")));
        assertEquals(4.2, offer.miles, 0.001);
        assertEquals(Integer.valueOf(3), offer.stops);
    }

    @Test
    public void doesNotJoinUnrelatedNumbersAcrossTheScreen() {
        OfferSnapshot offer = OfferParser.parse(Arrays.asList("$8.50", "4.2", "mi"));
        assertNull(offer.miles);
        assertEquals(0, OfferParser.joinMetricSiblings(Arrays.asList("4.2", "Restaurant", "mi")).size());
    }

    @Test
    public void readsUnicodeSpacingAndSingularMile() {
        assertEquals(4.2, OfferParser.parse(Arrays.asList("$8.50", "4.2\u202fmi")).miles, 0.001);
        assertEquals(1.0, OfferParser.parse(Arrays.asList("$8.50", "1\u00a0mile")).miles, 0.001);
    }

    @Test
    public void usesExplicitTotalDistanceWhenOtherDistancesArePresent() {
        assertEquals(6.4, OfferParser.parse(Arrays.asList("$8.50", "1 mi", "Total distance: 6.4 mi")).miles, 0.001);
        assertEquals(6.4, OfferParser.parse(Arrays.asList("$8.50", "1 mi", "6.4 mi total")).miles, 0.001);
        assertNull(OfferParser.parse(Arrays.asList("$8.50", "Total 4.2 mi", "Total 6.4 mi")).miles);
        assertNull(OfferParser.parse(Arrays.asList("$8.50", "4.2 mi", "6.4 mi")).miles);
    }

    @Test
    public void joinsSplitTotalLabelsWithoutChangingPay() {
        OfferSnapshot offer = OfferParser.parse(Arrays.asList("$8.50", "1 mi", "Total distance", "4.2", "mi"),
                OfferParser.joinMetricSiblings(Arrays.asList("Total distance", "4.2", "mi")));
        assertEquals(Integer.valueOf(850), offer.payCents);
        assertEquals(4.2, offer.miles, 0.001);
    }

    @Test
    public void doesNotTreatAMileageRangeAsAnExactDistance() {
        assertNull(OfferParser.parse(Arrays.asList("$8.50", "3–5 miles")).miles);
        assertNull(OfferParser.parse(Arrays.asList("$8.50", "3 - 5 mi")).miles);
    }

    @Test
    public void readsExplicitRouteCountsWithoutGuessingFromItemsOrOrders() {
        assertEquals(Integer.valueOf(4), OfferParser.parse(Arrays.asList("$8.50", "2 pickups • 2 drop-offs")).stops);
        assertEquals(Integer.valueOf(3), OfferParser.parse(Arrays.asList("$8.50", "Stops: 3")).stops);
        assertNull(OfferParser.parse(Arrays.asList("$8.50", "3 items", "2 orders", "Pickup", "Customer dropoff")).stops);
    }

    @Test
    public void twentyDollarRuleDoesNotDependOnMileageOrStops() {
        FilterSettings settings = new FilterSettings(true, 2000, 0, 0, 0, 0);
        assertEquals(OfferRule.Result.DECLINE,
                OfferRule.evaluate(new OfferSnapshot(1999, null, null, null), settings).result);
        assertEquals(OfferRule.Result.KEEP,
                OfferRule.evaluate(new OfferSnapshot(2000, null, null, null), settings).result);
        assertEquals(OfferRule.Result.KEEP,
                OfferRule.evaluate(new OfferSnapshot(2001, null, null, null), settings).result);
    }

    @Test
    public void maximumStopsIsInclusiveAndWorksWithoutPay() {
        FilterSettings settings = new FilterSettings(true, 0, 0, 0, 0, 3);
        assertEquals(OfferRule.Result.KEEP,
                OfferRule.evaluate(new OfferSnapshot(null, null, null, 2), settings).result);
        assertEquals(OfferRule.Result.KEEP,
                OfferRule.evaluate(new OfferSnapshot(null, null, null, 3), settings).result);
        OfferRule.Decision tooMany = OfferRule.evaluate(new OfferSnapshot(null, null, null, 4), settings);
        assertEquals(OfferRule.Result.DECLINE, tooMany.result);
        assertEquals("DECLINE: 4 stops exceeds maximum 3", tooMany.summary());
    }

    @Test
    public void zeroDisablesMaximumStops() {
        FilterSettings settings = new FilterSettings(true, 2000, 0, 0, 0, 0);
        assertEquals(OfferRule.Result.KEEP,
                OfferRule.evaluate(new OfferSnapshot(2000, null, null, 10), settings).result);
        assertEquals(OfferRule.Result.KEEP,
                OfferRule.evaluate(new OfferSnapshot(2000, null, null, null), settings).result);
    }

    @Test
    public void unknownStopsNeedReviewUnlessPriceAlreadyFails() {
        FilterSettings stopOnly = new FilterSettings(true, 0, 0, 0, 0, 2);
        assertEquals(OfferRule.Result.REVIEW,
                OfferRule.evaluate(new OfferSnapshot(null, null, null, null), stopOnly).result);
        FilterSettings combined = new FilterSettings(true, 2000, 0, 0, 0, 2);
        assertEquals(OfferRule.Result.REVIEW,
                OfferRule.evaluate(new OfferSnapshot(2000, null, null, null), combined).result);
        assertEquals(OfferRule.Result.DECLINE,
                OfferRule.evaluate(new OfferSnapshot(1999, null, null, null), combined).result);
    }

    @Test
    public void eitherPriceOrStopLimitCanDeclineAnOffer() {
        FilterSettings settings = new FilterSettings(true, 2000, 0, 0, 0, 2);
        assertEquals(OfferRule.Result.DECLINE,
                OfferRule.evaluate(new OfferSnapshot(9999, null, null, 3), settings).result);
        assertEquals(OfferRule.Result.DECLINE,
                OfferRule.evaluate(new OfferSnapshot(null, null, null, 3), settings).result);
        assertEquals(OfferRule.Result.REVIEW,
                OfferRule.evaluate(new OfferSnapshot(null, null, null, 2), settings).result);
        assertEquals(OfferRule.Result.DECLINE,
                OfferRule.evaluate(new OfferSnapshot(1999, null, null, 2), settings).result);
        assertEquals(OfferRule.Result.KEEP,
                OfferRule.evaluate(new OfferSnapshot(2000, null, null, 2), settings).result);
    }

    @Test
    public void ambiguousStopCountsDoNotTriggerTheCeiling() {
        FilterSettings settings = new FilterSettings(true, 0, 0, 0, 0, 2);
        for (String[] labels : new String[][] {
                {"$25.00", "2 stops", "4 stops"},
                {"$25.00", "2–4 stops"},
                {"$25.00", "Stops: 2-4"},
                {"$25.00", "2.5 stops"},
                {"$25.00", "Stops: 2.5"},
                {"$25.00", "0 stops"}
        }) {
            OfferSnapshot offer = OfferParser.parse(Arrays.asList(labels));
            assertNull(offer.stops);
            assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(offer, settings).result);
        }
    }
}

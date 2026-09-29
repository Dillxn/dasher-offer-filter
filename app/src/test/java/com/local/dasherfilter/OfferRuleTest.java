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
    public void threeDollarOfferFailsTwentyTwoDollarFloorWithOtherValuesMissing() {
        FilterSettings settings = new FilterSettings(true, 2200, 150, 30, 100, 2);
        OfferSnapshot offer = OfferParser.parse(Arrays.asList(
                "$3.00 Guaranteed", "Accept, 30 seconds", "Decline"));
        assertEquals(Integer.valueOf(300), offer.payCents);
        OfferRule.Decision decision = OfferRule.evaluate(offer, settings);
        assertEquals(OfferRule.Result.DECLINE, decision.result);
        assertEquals(2200, decision.requiredCents);
    }

    @Test public void risingRuleRequiresStrictlyMoreThanLastAcceptedPay() {
        FilterSettings settings = new FilterSettings(true, 2200, 0, 0, 0, 0, true, 2500);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(new OfferSnapshot(2499, null, null, null), settings).result);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(new OfferSnapshot(2500, null, null, null), settings).result);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(new OfferSnapshot(2501, null, null, null), settings).result);
        assertEquals(2501, OfferRule.evaluate(new OfferSnapshot(2500, null, null, null), settings).requiredCents);
    }

    @Test public void risingRuleStartsWithNormalRulesAndCanBeDisabled() {
        FilterSettings first = new FilterSettings(true, 2200, 0, 0, 0, 0, true, 0);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(new OfferSnapshot(2200, null, null, null), first).result);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(new OfferSnapshot(2199, null, null, null), first).result);
        FilterSettings off = new FilterSettings(true, 2200, 0, 0, 0, 0, false, 2500);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(new OfferSnapshot(2200, null, null, null), off).result);
    }

    @Test public void risingRuleCombinesWithPriceAndStopRulesWithoutDoubleCharging() {
        FilterSettings settings = new FilterSettings(true, 2200, 0, 0, 100, 3, true, 2500);
        assertEquals(2501, OfferRule.evaluate(new OfferSnapshot(2501, null, null, 3), settings).requiredCents);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(new OfferSnapshot(2501, null, null, 3), settings).result);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(new OfferSnapshot(9900, null, null, 4), settings).result);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(new OfferSnapshot(null, null, null, 3), settings).result);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(new OfferSnapshot(300, null, null, null), settings).result);
        FilterSettings stricterFlat = new FilterSettings(true, 3000, 0, 0, 0, 0, true, 2500);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(new OfferSnapshot(2600, null, null, null), stricterFlat).result);
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
    @Test public void addOnUsesMarginalEconomicsAndCombinedRoute() {
        FilterSettings settings = new FilterSettings(true, 2000, 150, 0, 0, 0);
        OfferSnapshot active = new OfferSnapshot(2500, 10.0, null, 2);

        AddOnOffer cheapGood = AddOnOffer.parse(active,
                new OfferSnapshot(300, 1.0, null, null),
                Arrays.asList("Add to route", "$3.00", "1 mi"));
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluateAddOn(cheapGood, settings).result);

        AddOnOffer cheapBad = AddOnOffer.parse(active,
                new OfferSnapshot(300, 4.0, null, null),
                Arrays.asList("Add to route", "$3.00", "4 mi"));
        OfferRule.Decision bad = OfferRule.evaluateAddOn(cheapBad, settings);
        assertEquals(OfferRule.Result.DECLINE, bad.result);
        assertEquals(600, bad.requiredCents);
    }

    @Test public void addOnFlatMinimumAppliesToCombinedRouteNotTwice() {
        FilterSettings settings = new FilterSettings(true, 2200, 0, 0, 0, 0);
        OfferSnapshot active = new OfferSnapshot(2200, null, null, null);
        AddOnOffer addOn = AddOnOffer.parse(active,
                new OfferSnapshot(300, null, null, null),
                Arrays.asList("Add to route", "$3.00"));
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluateAddOn(addOn, settings).result);
    }

    @Test public void addOnUsesCombinedStopCeilingAndMarginalStopFee() {
        OfferSnapshot active = new OfferSnapshot(2500, null, null, 2);
        AddOnOffer addOn = AddOnOffer.parse(active,
                new OfferSnapshot(300, null, null, 2),
                Arrays.asList("Add to route", "$3.00", "2 stops"));

        FilterSettings maxStops = new FilterSettings(true, 0, 0, 0, 0, 3);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluateAddOn(addOn, maxStops).result);

        FilterSettings stopFee = new FilterSettings(true, 0, 0, 0, 200, 0);
        OfferRule.Decision fee = OfferRule.evaluateAddOn(addOn, stopFee);
        assertEquals(OfferRule.Result.DECLINE, fee.result);
        assertEquals(400, fee.requiredCents);
    }

    @Test public void addOnDoesNotRequireMarginalPayToBeatPriorFullOrder() {
        FilterSettings settings = new FilterSettings(true, 2000, 100, 0, 0, 0, true, 2500);
        OfferSnapshot active = new OfferSnapshot(2500, 10.0, null, 2);
        AddOnOffer addOn = AddOnOffer.parse(active,
                new OfferSnapshot(300, 1.0, null, null),
                Arrays.asList("Add to route", "$3.00", "1 mi"));
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluateAddOn(addOn, settings).result);
    }

    @Test public void addOnMissingEnabledMarginalMetricRequiresReviewUnlessKnownFailure() {
        FilterSettings settings = new FilterSettings(true, 0, 150, 30, 0, 0);
        OfferSnapshot active = new OfferSnapshot(2500, 10.0, 50, 2);

        AddOnOffer unknownTime = AddOnOffer.parse(active,
                new OfferSnapshot(500, 2.0, null, null),
                Arrays.asList("Add to route", "$5.00", "2 mi"));
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluateAddOn(unknownTime, settings).result);

        AddOnOffer knownMileageFailure = AddOnOffer.parse(active,
                new OfferSnapshot(200, 3.0, null, null),
                Arrays.asList("Add to route", "$2.00", "3 mi"));
        assertEquals(OfferRule.Result.DECLINE,
                OfferRule.evaluateAddOn(knownMileageFailure, settings).result);
    }

}

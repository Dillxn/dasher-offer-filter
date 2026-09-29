package com.local.dasherfilter;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Existing standalone-rule cases retained; add-on fixtures now explicitly identify added values. */
public final class OfferRuleTest {
    private static OfferSnapshot parse(String... labels) {
        return OfferParser.parse(Arrays.asList(labels));
    }

    @Test
    public void parsesAnOfferWithExplicitValues() {
        OfferSnapshot offer = parse("$8.50 Guaranteed", "4.2 mi", "Estimated time 24 min", "3 stops");
        assertEquals(Integer.valueOf(850), offer.payCents);
        assertEquals(4.2, offer.miles, 0.001);
        assertEquals(Integer.valueOf(24), offer.minutes);
        assertEquals(Integer.valueOf(3), offer.stops);
    }

    @Test
    public void declinesWhenKnownFloorFailsEvenIfTimeIsMissing() {
        OfferSnapshot offer = parse("$5.00 Guaranteed", "4 mi");
        FilterSettings settings = new FilterSettings(true, 600, 150, 30, 100, 0);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(offer, settings).result);
    }

    @Test
    public void leavesAmbiguousOffersForReview() {
        FilterSettings settings = new FilterSettings(true, 600, 150, 30, 100, 0);

        // Two competing amounts leave pay unknown.
        OfferSnapshot ambiguousPay = parse("$8.00", "$9.00", "3 mi");
        assertNull(ambiguousPay.payCents);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(ambiguousPay, settings).result);

        // Pay clears the known floor, but the enabled time and stop values are missing.
        OfferSnapshot missingTimeAndStops = parse("$10.00 Guaranteed", "3 mi");
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(missingTimeAndStops, settings).result);
    }

    @Test
    public void ignoresAnUnrelatedEarningsAmountWhenPayIsGuaranteed() {
        OfferSnapshot offer = parse("$100.00 Weekly earnings", "Guaranteed", "$8.50", "4 mi");
        assertEquals(Integer.valueOf(850), offer.payCents);
    }

    @Test
    public void chargesOnlyForStopsBeyondPickupAndDropoff() {
        OfferSnapshot offer = parse("$7.00 Guaranteed", "1 mi", "3 stops");
        FilterSettings settings = new FilterSettings(true, 600, 100, 0, 200, 0);
        OfferRule.Decision decision = OfferRule.evaluate(offer, settings);
        assertEquals(OfferRule.Result.DECLINE, decision.result);
        assertEquals(800L, decision.requiredCents);
    }

    @Test
    public void readsMileageAndStopCountsSplitAcrossSiblingNodes() {
        OfferSnapshot offer = OfferParser.parse(
                Arrays.asList("$8.50", "4.2", "mi", "3", "stops"),
                OfferParser.joinMetricSiblings(Arrays.asList("4.2", "mi", "3", "stops")));
        assertEquals(4.2, offer.miles, 0.001);
        assertEquals(Integer.valueOf(3), offer.stops);
    }

    @Test
    public void doesNotJoinUnrelatedNumbersAcrossTheScreen() {
        assertNull(parse("$8.50", "4.2", "mi").miles);
        assertEquals(0, OfferParser.joinMetricSiblings(Arrays.asList("4.2", "Restaurant", "mi")).size());
    }

    @Test
    public void readsUnicodeSpacingAndSingularMile() {
        assertEquals(4.2, parse("$8.50", "4.2\u202fmi").miles, 0.001);
        assertEquals(1.0, parse("$8.50", "1\u00a0mile").miles, 0.001);
    }

    @Test
    public void usesExplicitTotalDistanceWhenOtherDistancesArePresent() {
        assertEquals(6.4, parse("$8.50", "1 mi", "Total distance: 6.4 mi").miles, 0.001);
        assertEquals(6.4, parse("$8.50", "1 mi", "6.4 mi total").miles, 0.001);

        // Conflicting totals, or several distances with no total, stay unknown.
        assertNull(parse("$8.50", "Total 4.2 mi", "Total 6.4 mi").miles);
        assertNull(parse("$8.50", "4.2 mi", "6.4 mi").miles);
    }

    @Test
    public void joinsSplitTotalLabelsWithoutChangingPay() {
        OfferSnapshot offer = OfferParser.parse(
                Arrays.asList("$8.50", "1 mi", "Total distance", "4.2", "mi"),
                OfferParser.joinMetricSiblings(Arrays.asList("Total distance", "4.2", "mi")));
        assertEquals(Integer.valueOf(850), offer.payCents);
        assertEquals(4.2, offer.miles, 0.001);
    }

    @Test
    public void doesNotTreatAMileageRangeAsAnExactDistance() {
        assertNull(parse("$8.50", "3–5 miles").miles);
        assertNull(parse("$8.50", "3 - 5 mi").miles);
    }

    @Test
    public void readsExplicitRouteCountsWithoutGuessingFromItemsOrOrders() {
        assertEquals(Integer.valueOf(4), parse("$8.50", "2 pickups • 2 drop-offs").stops);
        assertEquals(Integer.valueOf(3), parse("$8.50", "Stops: 3").stops);

        // Item and order counts are not stop counts.
        assertNull(parse("$8.50", "3 items", "2 orders", "Pickup", "Customer dropoff").stops);
    }

    @Test
    public void twentyDollarRuleDoesNotDependOnMileageOrStops() {
        FilterSettings settings = new FilterSettings(true, 2000, 0, 0, 0, 0);
        OfferSnapshot belowFloor = new OfferSnapshot(1999, null, null, null);
        OfferSnapshot atFloor = new OfferSnapshot(2000, null, null, null);
        OfferSnapshot aboveFloor = new OfferSnapshot(2001, null, null, null);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(belowFloor, settings).result);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(atFloor, settings).result);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(aboveFloor, settings).result);
    }

    @Test
    public void threeDollarOfferFailsTwentyTwoDollarFloorWithOtherValuesMissing() {
        OfferSnapshot offer = parse("$3.00 Guaranteed", "Accept, 30 seconds", "Decline");
        assertEquals(Integer.valueOf(300), offer.payCents);

        FilterSettings settings = new FilterSettings(true, 2200, 150, 30, 100, 2);
        OfferRule.Decision decision = OfferRule.evaluate(offer, settings);
        assertEquals(OfferRule.Result.DECLINE, decision.result);
        assertEquals(2200L, decision.requiredCents);
    }

    @Test
    public void risingRuleRequiresStrictlyMoreThanLastAcceptedPay() {
        FilterSettings settings = new FilterSettings(true, 2200, 0, 0, 0, 0, true, 2500);
        OfferSnapshot belowLastAccepted = new OfferSnapshot(2499, null, null, null);
        OfferSnapshot equalToLastAccepted = new OfferSnapshot(2500, null, null, null);
        OfferSnapshot aboveLastAccepted = new OfferSnapshot(2501, null, null, null);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(belowLastAccepted, settings).result);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(equalToLastAccepted, settings).result);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(aboveLastAccepted, settings).result);
        assertEquals(2501L, OfferRule.evaluate(equalToLastAccepted, settings).requiredCents);
    }

    @Test
    public void risingRuleStartsWithNormalRulesAndCanBeDisabled() {
        OfferSnapshot atFloor = new OfferSnapshot(2200, null, null, null);
        OfferSnapshot belowFloor = new OfferSnapshot(2199, null, null, null);

        // With no accepted payout recorded yet, only the normal $22.00 floor applies.
        FilterSettings risingWithoutHistory = new FilterSettings(true, 2200, 0, 0, 0, 0, true, 0);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(atFloor, risingWithoutHistory).result);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(belowFloor, risingWithoutHistory).result);

        // With the rising rule off, the last accepted payout is ignored.
        FilterSettings risingDisabled = new FilterSettings(true, 2200, 0, 0, 0, 0, false, 2500);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(atFloor, risingDisabled).result);
    }

    @Test
    public void risingRuleCombinesWithPriceAndStopRulesWithoutDoubleCharging() {
        FilterSettings settings = new FilterSettings(true, 2200, 0, 0, 100, 3, true, 2500);

        // The $25.01 rising floor replaces the flat-plus-extra-stop requirement instead of adding to it.
        OfferSnapshot threeStops = new OfferSnapshot(2501, null, null, 3);
        assertEquals(2501L, OfferRule.evaluate(threeStops, settings).requiredCents);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(threeStops, settings).result);

        // The stop ceiling still declines high pay, and unknown pay still needs review.
        OfferSnapshot highPayFourStops = new OfferSnapshot(9900, null, null, 4);
        OfferSnapshot unknownPayThreeStops = new OfferSnapshot(null, null, null, 3);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(highPayFourStops, settings).result);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(unknownPayThreeStops, settings).result);

        // Known low pay declines even when the stop count is unknown.
        OfferSnapshot lowPayUnknownStops = new OfferSnapshot(300, null, null, null);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(lowPayUnknownStops, settings).result);

        // A flat floor above the rising floor still applies.
        FilterSettings higherFlatFloor = new FilterSettings(true, 3000, 0, 0, 0, 0, true, 2500);
        OfferSnapshot beatsLastAccepted = new OfferSnapshot(2600, null, null, null);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(beatsLastAccepted, higherFlatFloor).result);
    }

    @Test
    public void maximumStopsIsInclusiveAndWorksWithoutPay() {
        FilterSettings settings = new FilterSettings(true, 0, 0, 0, 0, 3);
        OfferSnapshot twoStops = new OfferSnapshot(null, null, null, 2);
        OfferSnapshot threeStops = new OfferSnapshot(null, null, null, 3);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(twoStops, settings).result);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(threeStops, settings).result);

        OfferRule.Decision decision = OfferRule.evaluate(new OfferSnapshot(null, null, null, 4), settings);
        assertEquals(OfferRule.Result.DECLINE, decision.result);
        assertEquals("DECLINE: 4 stops exceeds maximum 3", decision.summary());
    }

    @Test
    public void zeroDisablesMaximumStops() {
        FilterSettings settings = new FilterSettings(true, 2000, 0, 0, 0, 0);
        OfferSnapshot tenStops = new OfferSnapshot(2000, null, null, 10);
        OfferSnapshot unknownStops = new OfferSnapshot(2000, null, null, null);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(tenStops, settings).result);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(unknownStops, settings).result);
    }

    @Test
    public void unknownStopsNeedReviewUnlessPriceAlreadyFails() {
        // With only a stop ceiling enabled, unknown stops need review.
        FilterSettings stopCeilingOnly = new FilterSettings(true, 0, 0, 0, 0, 2);
        OfferSnapshot nothingKnown = new OfferSnapshot(null, null, null, null);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(nothingKnown, stopCeilingOnly).result);

        // With a price floor as well, unknown stops need review unless the known price already fails.
        FilterSettings settings = new FilterSettings(true, 2000, 0, 0, 0, 2);
        OfferSnapshot atFloor = new OfferSnapshot(2000, null, null, null);
        OfferSnapshot belowFloor = new OfferSnapshot(1999, null, null, null);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(atFloor, settings).result);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(belowFloor, settings).result);
    }

    @Test
    public void eitherPriceOrStopLimitCanDeclineAnOffer() {
        FilterSettings settings = new FilterSettings(true, 2000, 0, 0, 0, 2);

        // Too many stops declines regardless of pay, even when pay is unknown.
        OfferSnapshot highPayTooManyStops = new OfferSnapshot(9999, null, null, 3);
        OfferSnapshot unknownPayTooManyStops = new OfferSnapshot(null, null, null, 3);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(highPayTooManyStops, settings).result);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(unknownPayTooManyStops, settings).result);

        // Within the stop limit, the price decides.
        OfferSnapshot unknownPayAllowedStops = new OfferSnapshot(null, null, null, 2);
        OfferSnapshot lowPayAllowedStops = new OfferSnapshot(1999, null, null, 2);
        OfferSnapshot atFloorAllowedStops = new OfferSnapshot(2000, null, null, 2);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(unknownPayAllowedStops, settings).result);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(lowPayAllowedStops, settings).result);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(atFloorAllowedStops, settings).result);
    }

    @Test
    public void ambiguousStopCountsDoNotTriggerTheCeiling() {
        FilterSettings settings = new FilterSettings(true, 0, 0, 0, 0, 2);
        String[][] screens = {
                {"$25.00", "2 stops", "4 stops"},
                {"$25.00", "2–4 stops"},
                {"$25.00", "Stops: 2-4"},
                {"$25.00", "2.5 stops"},
                {"$25.00", "Stops: 2.5"},
                {"$25.00", "0 stops"}
        };
        for (String[] labels : screens) {
            OfferSnapshot offer = parse(labels);
            assertNull(offer.stops);
            assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(offer, settings).result);
        }
    }

    @Test
    public void addOnUsesMarginalEconomicsAndCombinedRoute() {
        FilterSettings settings = new FilterSettings(true, 2000, 150, 0, 0, 0);
        OfferSnapshot active = new OfferSnapshot(2500, 10.0, null, 2);

        AddOnOffer goodAddOn = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$3.00", "+1 mi"));
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluateAddOn(goodAddOn, settings).result);

        // +4 mi at $1.50/mi needs $6.00 of marginal pay; only +$3.00 is offered.
        AddOnOffer badAddOn = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$3.00", "+4 mi"));
        OfferRule.Decision decision = OfferRule.evaluateAddOn(badAddOn, settings);
        assertEquals(OfferRule.Result.DECLINE, decision.result);
        assertEquals(600L, decision.requiredCents);
    }

    @Test
    public void addOnFlatMinimumAppliesToCombinedRouteNotTwice() {
        OfferSnapshot active = new OfferSnapshot(2200, null, null, null);
        AddOnOffer addOn = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$3.00"));
        FilterSettings settings = new FilterSettings(true, 2200, 0, 0, 0, 0);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluateAddOn(addOn, settings).result);
    }

    @Test
    public void addOnUsesCombinedStopCeilingAndMarginalStopFee() {
        OfferSnapshot active = new OfferSnapshot(2500, null, null, 2);
        AddOnOffer addOn = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$3.00", "+2 stops"));

        // Two added stops bring the combined route to four, above the ceiling of three.
        FilterSettings stopCeiling = new FilterSettings(true, 0, 0, 0, 0, 3);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluateAddOn(addOn, stopCeiling).result);

        // Both added stops are beyond pickup and dropoff: 2 x $2.00 exceeds the +$3.00 offered.
        FilterSettings extraStopFee = new FilterSettings(true, 0, 0, 0, 200, 0);
        OfferRule.Decision decision = OfferRule.evaluateAddOn(addOn, extraStopFee);
        assertEquals(OfferRule.Result.DECLINE, decision.result);
        assertEquals(400L, decision.requiredCents);
    }

    @Test
    public void addOnDoesNotRequireMarginalPayToBeatPriorFullOrder() {
        OfferSnapshot active = new OfferSnapshot(2500, 10.0, null, 2);
        AddOnOffer addOn = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$3.00", "+1 mi"));
        FilterSettings settings = new FilterSettings(true, 2000, 100, 0, 0, 0, true, 2500);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluateAddOn(addOn, settings).result);
    }

    @Test
    public void addOnMissingEnabledMarginalMetricRequiresReviewUnlessKnownFailure() {
        FilterSettings settings = new FilterSettings(true, 0, 150, 30, 0, 0);
        OfferSnapshot active = new OfferSnapshot(2500, 10.0, 50, 2);

        // Added minutes are not shown, so the enabled per-minute rule cannot be checked.
        AddOnOffer missingMinutes = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$5.00", "+2 mi"));
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluateAddOn(missingMinutes, settings).result);

        // +3 mi at $1.50/mi already needs $4.50, so +$2.00 is a known failure.
        AddOnOffer knownFailure = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$2.00", "+3 mi"));
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluateAddOn(knownFailure, settings).result);
    }

    @Test
    public void addOnNeedsIncrementalPayOnlyWhenAMarginalRuleIsEnabled() {
        // No accepted route is known, so only the explicit new total describes the combined route.
        AddOnOffer addOn = AddOnOffer.parse(null, Arrays.asList("Add to route", "New total $30.00"));
        assertNull(addOn.incremental.payCents);

        // The flat minimum applies to the combined route, which is known to pay $30.00.
        FilterSettings flatOnly = new FilterSettings(true, 2000, 0, 0, 0, 0);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluateAddOn(addOn, flatOnly).result);

        // A per-mile rule prices the added miles, which needs the unknown added pay and distance.
        FilterSettings perMile = new FilterSettings(true, 2000, 150, 0, 0, 0);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluateAddOn(addOn, perMile).result);

        // A stop ceiling alone needs the combined stop count, which is unknown here.
        FilterSettings stopCeiling = new FilterSettings(true, 0, 0, 0, 0, 3);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluateAddOn(addOn, stopCeiling).result);
    }

    @Test
    public void risingRuleNeverLetsAnAddOnWithUnknownPayPass() {
        FilterSettings risingOnly = new FilterSettings(true, 0, 0, 0, 0, 0, true, 2500);

        // No readable added pay: the add-on is unclassified, never a passing bell.
        AddOnOffer unknownPay = AddOnOffer.parse(null, Arrays.asList("Add to route", "Pickup nearby"));
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluateAddOn(unknownPay, risingOnly).result);

        // Explicit added pay passes: the standalone baseline does not apply to add-ons.
        AddOnOffer explicitPay = AddOnOffer.parse(null, Arrays.asList("Add to route", "+$3.00"));
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluateAddOn(explicitPay, risingOnly).result);
    }
}

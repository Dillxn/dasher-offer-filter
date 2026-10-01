package com.local.dasherfilter;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
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
    public void perStopIsAMinimumLikePerMileAndPerMinute() {
        // $6 minimum, $1.00/mi, $0.30/min, $4.00 per stop. A double order of 6 mi and 20 min: the minimum, per mile
        // and per minute each ask $6.00, but 4 stops at $4.00 ask $16.00.
        FilterSettings settings = new FilterSettings(true, 600, 100, 30, 400, 0);
        OfferRule.Decision shortOfPerStop = OfferRule.evaluate(new OfferSnapshot(1599, 6.0, 20, 4), settings);
        assertEquals(OfferRule.Result.DECLINE, shortOfPerStop.result);
        assertEquals(1600L, shortOfPerStop.requiredCents);
        assertEquals("dollars per stop", shortOfPerStop.reason);

        // Meeting it passes: a floor, never a fee on top of the others (that would have asked $6 + 2 × $4 = $14).
        OfferRule.Decision meetsIt = OfferRule.evaluate(new OfferSnapshot(1600, 6.0, 20, 4), settings);
        assertEquals(OfferRule.Result.KEEP, meetsIt.result);
        assertEquals(1600L, meetsIt.requiredCents);

        // Every stop counts, the first two too: a single order of 2 stops asks 2 × $4.00.
        assertEquals(800L, OfferRule.evaluate(new OfferSnapshot(900, 6.0, 20, 2), settings).requiredCents);
        // A higher per-mile ask still wins over per stop; nothing is added up.
        OfferRule.Decision byMiles = OfferRule.evaluate(new OfferSnapshot(1500, 20.0, 20, 2), settings);
        assertEquals(2000L, byMiles.requiredCents);
        assertEquals("dollars per mile", byMiles.reason);
    }

    @Test
    public void unknownStopsUnderAPerStopMinimumNeedReviewUnlessAKnownFloorFails() {
        FilterSettings settings = new FilterSettings(true, 600, 0, 0, 400, 0);
        // Pay clears the known minimum, but the stops the per-stop minimum needs are not shown: review, never keep.
        OfferRule.Decision unknownStops = OfferRule.evaluate(new OfferSnapshot(5000, 6.0, 20, null), settings);
        assertEquals(OfferRule.Result.REVIEW, unknownStops.result);
        assertEquals("an enabled value was not found", unknownStops.reason);
        // Nor are stops ever assumed: "3 items" is not a stop count.
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(parse("$50.00 Guaranteed", "3 items"), settings)
                .result);

        // The known $6.00 minimum already fails: that declines even with the stops unknown.
        OfferRule.Decision knownFailure = OfferRule.evaluate(new OfferSnapshot(500, null, null, null), settings);
        assertEquals(OfferRule.Result.DECLINE, knownFailure.result);
        assertEquals(600L, knownFailure.requiredCents);
        assertEquals("flat minimum", knownFailure.reason);

        // A known per-mile failure declines too.
        FilterSettings withPerMile = new FilterSettings(true, 0, 150, 0, 400, 0);
        assertEquals(OfferRule.Result.DECLINE,
                OfferRule.evaluate(new OfferSnapshot(800, 6.0, null, null), withPerMile).result);

        // Unknown pay with known stops is review.
        assertEquals(OfferRule.Result.REVIEW,
                OfferRule.evaluate(new OfferSnapshot(null, 6.0, 20, 4), settings).result);
    }

    @Test
    public void aOneStopReadingIsAMisreadThatNoPerStopAskPricesAtHalf() {
        // "$5.00 · 1 stop": a standalone order is a pickup and a drop-off at least, so "1 stop" is a misread. Priced
        // as read, $4.00 per stop would ask $4.00 and keep it.
        OfferSnapshot oneStop = parse("$5.00 Guaranteed", "3.0 mi", "1 stop");
        assertEquals(Integer.valueOf(1), oneStop.stops);
        FilterSettings perStop = new FilterSettings(true, 0, 0, 0, 400, 0);
        OfferRule.Decision decision = OfferRule.evaluate(oneStop, perStop);
        assertEquals(OfferRule.Result.REVIEW, decision.result);
        assertEquals("an enabled value was not found", decision.reason);
        // Nor is it read as 2 stops ($8.00 would decline it): the count is not known, and never guessed.
        assertEquals(0L, decision.requiredCents);

        // A separate known failure still declines: the $6.00 minimum.
        OfferRule.Decision belowMinimum = OfferRule.evaluate(oneStop, new FilterSettings(true, 600, 0, 0, 400, 0));
        assertEquals(OfferRule.Result.DECLINE, belowMinimum.result);
        assertEquals(600L, belowMinimum.requiredCents);
        assertEquals("flat minimum", belowMinimum.reason);

        // Two stops is a real reading and is priced: 2 × $4.00 declines the $5.00.
        OfferRule.Decision twoStops = OfferRule.evaluate(parse("$5.00 Guaranteed", "3.0 mi", "2 stops"), perStop);
        assertEquals(OfferRule.Result.DECLINE, twoStops.result);
        assertEquals(800L, twoStops.requiredCents);

        // The adaptive per-stop floors treat it alike: $8.00 for "1 stop" would pass the best accepted $7.10/stop
        // (asking $7.10) and a declined $5.00/stop (asking $5.01).
        OfferSnapshot eightForOne = parse("$8.00 Guaranteed", "3.0 mi", "1 stop");
        FilterSettings bestPerStop = new FilterSettings(true, 0, 0, 0, 0, 0, true, 0,
                new AcceptedBest(0, 0, 0, 0, 1420, 2));
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(eightForOne, bestPerStop).result);
        FilterSettings declinedPerStop = new FilterSettings(true, 0, 0, 0, 0, 0, true, 0, AcceptedBest.NONE,
                new DeclinedFloor(0, new AcceptedBest(0, 0, 0, 0, 1000, 2)));
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(eightForOne, declinedPerStop).result);
        // With 2 stops read, both floors apply as usual: $14.20 and $10.01.
        OfferSnapshot eightForTwo = parse("$8.00 Guaranteed", "3.0 mi", "2 stops");
        assertEquals(1420L, OfferRule.evaluate(eightForTwo, bestPerStop).requiredCents);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(eightForTwo, bestPerStop).result);
        assertEquals(1001L, OfferRule.evaluate(eightForTwo, declinedPerStop).requiredCents);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(eightForTwo, declinedPerStop).result);

        // Without a per-stop ask a "1 stop" reading changes nothing: pay and the other rules decide.
        assertEquals(OfferRule.Result.KEEP,
                OfferRule.evaluate(oneStop, new FilterSettings(true, 400, 0, 0, 0, 3)).result);
    }

    @Test
    public void perStopIsDescribedAsAMinimum() {
        FilterSettings settings = new FilterSettings(true, 700, 0, 0, 350, 0);
        assertEquals("at least $7.00 · $3.50 per stop", settings.describe());
        assertEquals("$7 min · $3.50/stop", settings.brief());
        assertTrue(settings.hasAnyRule());
        assertTrue(settings.hasMarginalRule());
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

        // The $25.01 rising floor replaces the flat and per-stop requirements instead of adding to them.
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

    // ---- Adaptive minimum: best accepted pay per minute, mile and stop ----

    /** Best rates from one accepted offer: $14.20 for 24 min (59.2¢/min), 6.0 mi ($2.37/mi), 2 stops ($7.10/stop). */
    private static final AcceptedBest BEST = AcceptedBest.NONE.raisedBy(new OfferSnapshot(1420, 6.0, 24, 2));

    private static FilterSettings adaptive(AcceptedBest best) {
        return new FilterSettings(true, 0, 0, 0, 0, 0, true, 1420, best);
    }

    @Test
    public void adaptiveMinimumRaisesPerMinuteMileAndStopFloorsToTheBestAccepted() {
        // 30 min needs 1420 × 30 / 24 = $17.75; 7.5 mi needs 1420 × 7.5 / 6 = $17.75; 2 stops need $14.20.
        OfferSnapshot justBelow = new OfferSnapshot(1774, 7.5, 30, 2);
        OfferRule.Decision declined = OfferRule.evaluate(justBelow, adaptive(BEST));
        assertEquals(OfferRule.Result.DECLINE, declined.result);
        assertEquals(1775L, declined.requiredCents);
        assertEquals("must match best accepted $0.59/min", declined.reason);

        // Exactly matching the best rate passes: a floor, not "must beat".
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(new OfferSnapshot(1775, 7.5, 30, 2),
                adaptive(BEST)).result);

        // Longer distance makes the per-mile floor the binding one: 1420 × 8 / 6 = $18.93⅓, rounded up.
        OfferRule.Decision byMiles = OfferRule.evaluate(new OfferSnapshot(1893, 8.0, 24, 2), adaptive(BEST));
        assertEquals(1894L, byMiles.requiredCents);
        assertEquals("must match best accepted $2.37/mi", byMiles.reason);

        // A double order: 4 stops at $7.10 each needs $28.40.
        OfferRule.Decision byStops = OfferRule.evaluate(new OfferSnapshot(2800, 6.0, 24, 4), adaptive(BEST));
        assertEquals(OfferRule.Result.DECLINE, byStops.result);
        assertEquals(2840L, byStops.requiredCents);
        assertEquals("must match best accepted $7.10/stop", byStops.reason);
    }

    @Test
    public void adaptiveFloorsAreFloorsNotSurcharges() {
        // A configured $0.50/min rule and the best accepted $0.59/min: the higher applies, nothing is added up.
        FilterSettings settings = new FilterSettings(true, 0, 0, 50, 0, 0, true, 0, BEST);
        assertEquals(1775L, OfferRule.evaluate(new OfferSnapshot(1800, null, 30, null), new FilterSettings(
                true, 0, 0, 50, 0, 0, true, 0, AcceptedBest.NONE.raisedBy(new OfferSnapshot(1420, null, 24, null))))
                .requiredCents);
        // A configured rate above the best still wins.
        FilterSettings strictRule = new FilterSettings(true, 0, 0, 80, 0, 0, true, 0, BEST);
        assertEquals(2400L, OfferRule.evaluate(new OfferSnapshot(2500, 6.0, 30, 2), strictRule).requiredCents);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(new OfferSnapshot(2500, 6.0, 30, 2), settings).result);
    }

    @Test
    public void anUnknownAmountTheBestRateNeedsIsReviewUnlessAnotherFloorAlreadyFails() {
        // Minutes are not shown, so the per-minute floor cannot be checked: review, never keep.
        OfferSnapshot noMinutes = new OfferSnapshot(5000, 6.0, null, 2);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(noMinutes, adaptive(BEST)).result);
        // The known per-mile floor fails: that is a real failure even with minutes unknown.
        OfferSnapshot lowPerMile = new OfferSnapshot(1500, 8.0, null, 2);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(lowPerMile, adaptive(BEST)).result);
    }

    @Test
    public void bestRatesApplyOnlyWhileTheAdaptiveMinimumIsOn() {
        FilterSettings off = new FilterSettings(true, 1000, 0, 0, 0, 0, false, 1420, BEST);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(new OfferSnapshot(1000, 20.0, 60, 4), off).result);
        // With nothing accepted yet the adaptive minimum adds no rate floors.
        FilterSettings fresh = new FilterSettings(true, 1000, 0, 0, 0, 0, true, 0, AcceptedBest.NONE);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(new OfferSnapshot(1000, 20.0, 60, 4), fresh).result);
    }

    @Test
    public void addOnsAreNeverJudgedByBestRates() {
        OfferSnapshot active = new OfferSnapshot(2500, 10.0, null, 2);
        AddOnOffer addOn = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$3.00", "+1 mi"));
        FilterSettings settings = new FilterSettings(true, 2000, 100, 0, 0, 0, true, 2500,
                AcceptedBest.NONE.raisedBy(new OfferSnapshot(5000, 5.0, 10, 2)));
        assertEquals("$5.00/min, $10.00/mi, $25.00/stop", settings.best.summary());
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluateAddOn(addOn, settings).result);
    }

    @Test
    public void onlyAHigherRateSetsANewBestAndImplausibleReadingsNeverDo() {
        AcceptedBest best = AcceptedBest.NONE.raisedBy(new OfferSnapshot(1420, 6.0, 24, 2));
        // Lower rates on every measure change nothing.
        AcceptedBest lower = best.raisedBy(new OfferSnapshot(1000, 6.0, 24, 2));
        assertEquals("$0.59/min, $2.37/mi, $7.10/stop", lower.summary());
        // A better per-minute rate only moves that best.
        AcceptedBest faster = best.raisedBy(new OfferSnapshot(1300, 9.0, 15, 3));
        assertEquals("$0.87/min, $2.37/mi, $7.10/stop", faster.summary());
        // "1 min", "0.1 mi" or "1 stop" is a misread: that offer sets no best at all, not even per stop.
        assertEquals(best.summary(), best.raisedBy(new OfferSnapshot(4500, 0.1, 1, 2)).summary());
        assertEquals(best.summary(), best.raisedBy(new OfferSnapshot(1000, 6.0, 24, 1)).summary());
        assertEquals(best.summary(), best.raisedBy(new OfferSnapshot(null, 6.0, 24, 2)).summary());
        assertEquals("", AcceptedBest.NONE.summary());
    }

    @Test
    public void aShortTripSetsNoDistanceOrTimeBestSoNormalOffersStillPass() {
        // $7.50 for 0.6 mi and 6 min is $12.50/mi and $1.25/min: real, but only because base pay dominates.
        AcceptedBest afterShortTrip = AcceptedBest.NONE.raisedBy(new OfferSnapshot(750, 0.6, 6, 2));
        assertEquals("$3.75/stop", afterShortTrip.summary());
        FilterSettings settings = new FilterSettings(true, 0, 0, 0, 0, 0, true, 750, afterShortTrip);
        // A typical $15 offer for 5 mi and 20 min still passes, instead of needing $62.50.
        OfferRule.Decision typical = OfferRule.evaluate(new OfferSnapshot(1500, 5.0, 20, 2), settings);
        assertEquals(OfferRule.Result.KEEP, typical.result);
        assertEquals(751L, typical.requiredCents);
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
    public void addOnUsesCombinedStopCeilingAndPerStopForTheStopsItAdds() {
        OfferSnapshot active = new OfferSnapshot(2500, null, null, 2);
        AddOnOffer addOn = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$3.00", "+2 stops"));

        // Two added stops bring the combined route to four, above the ceiling of three.
        FilterSettings stopCeiling = new FilterSettings(true, 0, 0, 0, 0, 3);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluateAddOn(addOn, stopCeiling).result);

        // The combined route ($28.00 for 4 stops) meets $2.00 per stop, but 2 added stops at $2.00 ask $4.00 of the
        // +$3.00 the add-on pays.
        FilterSettings perStop = new FilterSettings(true, 0, 0, 0, 200, 0);
        OfferRule.Decision decision = OfferRule.evaluateAddOn(addOn, perStop);
        assertEquals(OfferRule.Result.DECLINE, decision.result);
        assertEquals(400L, decision.requiredCents);
        assertEquals("add-on marginal economics", decision.reason);

        // Only the stops the add-on adds are priced, so an unknown active route does not stop a known failure.
        AddOnOffer noRoute = AddOnOffer.parse(null, Arrays.asList("Add to route", "+$3.00", "+2 stops"));
        OfferRule.Decision withoutRoute = OfferRule.evaluateAddOn(noRoute, perStop);
        assertEquals(OfferRule.Result.DECLINE, withoutRoute.result);
        assertEquals(400L, withoutRoute.requiredCents);
    }

    @Test
    public void addOnPassesWhenItsAddedPayCoversItsAddedStops() {
        OfferSnapshot active = new OfferSnapshot(2500, null, null, 2);
        AddOnOffer addOn = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$4.00", "+2 stops"));
        FilterSettings perStop = new FilterSettings(true, 0, 0, 0, 200, 0);
        OfferRule.Decision decision = OfferRule.evaluateAddOn(addOn, perStop);
        assertEquals(OfferRule.Result.KEEP, decision.result);
        assertEquals(400L, decision.requiredCents);

        // The added stops, miles and minutes are floors side by side: the highest ask ($4.00 for 2 stops, against
        // $3.00 for 3 mi and $1.20 for 12 min) applies, never their sum.
        FilterSettings allRates = new FilterSettings(true, 0, 100, 10, 200, 0);
        AddOnOffer longer = AddOnOffer.parse(new OfferSnapshot(2500, 5.0, 20, 2),
                Arrays.asList("Add to route", "+$4.00", "+2 stops", "+3 mi", "+12 min"));
        OfferRule.Decision highest = OfferRule.evaluateAddOn(longer, allRates);
        assertEquals(OfferRule.Result.KEEP, highest.result);
        assertEquals(400L, highest.requiredCents);
    }

    @Test
    public void addOnStopsThatDisagreeWithTheirNewTotalAreUnknown() {
        // The route had 2 stops and the add-on says +1, but the new total says 4: which is right is not known.
        OfferSnapshot active = new OfferSnapshot(2500, null, null, 2);
        AddOnOffer disagreeing = AddOnOffer.parse(active,
                Arrays.asList("Add to route", "+$3.00", "+1 stop", "New total 4 stops"));
        assertNull(disagreeing.incremental.stops);
        assertNull(disagreeing.combined.stops);
        assertEquals("pay is read as before", Integer.valueOf(300), disagreeing.incremental.payCents);

        // The total of 4 would have broken at most 3 stops, and +1 stop kept $2.00 per stop: both need review now.
        OfferRule.Decision ceiling = OfferRule.evaluateAddOn(disagreeing, new FilterSettings(true, 0, 0, 0, 0, 3));
        assertEquals(OfferRule.Result.REVIEW, ceiling.result);
        OfferRule.Decision perStop = OfferRule.evaluateAddOn(disagreeing, new FilterSettings(true, 0, 0, 0, 200, 0));
        assertEquals(OfferRule.Result.REVIEW, perStop.result);

        // A separate known failure still declines: +3 mi at $1.50/mi asks $4.50 of the +$3.00.
        AddOnOffer withMiles = AddOnOffer.parse(active,
                Arrays.asList("Add to route", "+$3.00", "+1 stop", "+3 mi", "New total 4 stops"));
        assertNull(withMiles.combined.stops);
        assertEquals(OfferRule.Result.DECLINE,
                OfferRule.evaluateAddOn(withMiles, new FilterSettings(true, 0, 150, 0, 200, 3)).result);

        // Figures that agree stand: 2 + 1 = 3.
        AddOnOffer agreeing = AddOnOffer.parse(active,
                Arrays.asList("Add to route", "+$3.00", "+1 stop", "New total 3 stops"));
        assertEquals(Integer.valueOf(1), agreeing.incremental.stops);
        assertEquals(Integer.valueOf(3), agreeing.combined.stops);
        assertEquals(OfferRule.Result.KEEP,
                OfferRule.evaluateAddOn(agreeing, new FilterSettings(true, 0, 0, 0, 200, 3)).result);

        // Without the route's stops there is nothing to disagree with: the explicit figures stand.
        AddOnOffer noRoute = AddOnOffer.parse(null,
                Arrays.asList("Add to route", "+$3.00", "+1 stop", "New total 4 stops"));
        assertEquals(Integer.valueOf(1), noRoute.incremental.stops);
        assertEquals(Integer.valueOf(4), noRoute.combined.stops);
        assertEquals(OfferRule.Result.DECLINE,
                OfferRule.evaluateAddOn(noRoute, new FilterSettings(true, 0, 0, 0, 0, 3)).result);
    }

    @Test
    public void addOnWithoutExplicitAddedStopsNeedsReviewUnderAPerStopMinimum() {
        // The new total says 4 stops and the route had 2, but "added 2" is never worked out from totals.
        OfferSnapshot active = new OfferSnapshot(2500, null, null, 2);
        AddOnOffer totalOnly = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$5.00", "New total 4 stops"));
        assertNull(totalOnly.incremental.stops);
        assertEquals(Integer.valueOf(4), totalOnly.combined.stops);
        FilterSettings perStop = new FilterSettings(true, 0, 0, 0, 200, 0);
        OfferRule.Decision decision = OfferRule.evaluateAddOn(totalOnly, perStop);
        assertEquals(OfferRule.Result.REVIEW, decision.result);

        // A separate known failure still declines: +3 mi at $1.50/mi asks $4.50 of the +$2.00 offered.
        FilterSettings perMileAndStop = new FilterSettings(true, 0, 150, 0, 200, 0);
        AddOnOffer shortPay = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$2.00", "+3 mi"));
        assertNull(shortPay.incremental.stops);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluateAddOn(shortPay, perMileAndStop).result);
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

    @Test
    public void aManualDeclineRaisesOnlyTheRuleThatCameClosest() {
        FilterSettings rules = new FilterSettings(true, 700, 150, 30, 0, 0, true, 0);
        // $15 over 6 mi and 24 min: the minimum asks $7, per mile $9, per minute $7.20. Per mile came closest.
        DeclinedFloor floor = DeclinedFloor.raisedBy(rules, new OfferSnapshot(1500, 6.0, 24, 2));
        assertEquals("$2.50/mi", floor.rates.perMileLabel());
        assertFalse(floor.rates.hasPerMinute());
        assertEquals(0, floor.payCents);

        FilterSettings learned = new FilterSettings(true, 700, 150, 30, 0, 0, true, 0, AcceptedBest.NONE, floor);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(new OfferSnapshot(1500, 6.0, 24, 2), learned).result);
        assertEquals("must beat declined $2.50/mi",
                OfferRule.evaluate(new OfferSnapshot(1500, 6.0, 24, 2), learned).reason);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(new OfferSnapshot(1510, 6.0, 24, 2), learned).result);
        // Add-ons are never judged by it.
        assertTrue(learned.withoutRisingBaseline().declined == floor && !learned.withoutRisingBaseline().risingOffers);
    }

    @Test
    public void aManualDeclineWeighsTheSetPerStopMinimumLikeTheOtherRates() {
        FilterSettings rules = new FilterSettings(true, 700, 0, 0, 400, 0, true, 0);
        // $10 for 6 mi, 24 min and 2 stops: the minimum asks $7, per stop $8. Per stop came closest.
        DeclinedFloor floor = DeclinedFloor.raisedBy(rules, new OfferSnapshot(1000, 6.0, 24, 2));
        assertEquals("$5.00/stop", floor.rates.perStopLabel());
        assertEquals(0, floor.payCents);
    }

    @Test
    public void shortTripsAndMisreadsDoNotSetRateFloorsFromADecline() {
        FilterSettings rules = new FilterSettings(true, 700, 150, 30, 0, 0, true, 0);
        // A 1.2 mi, 8 min hop cannot set a per-mile or per-minute floor, so its payout is what rises.
        DeclinedFloor hop = DeclinedFloor.raisedBy(rules, new OfferSnapshot(900, 1.2, 8, 2));
        assertEquals(900, hop.payCents);
        assertTrue(hop.rates.isEmpty());
        // "1 min" is a misread: nothing is learned.
        assertTrue(DeclinedFloor.raisedBy(rules, new OfferSnapshot(900, 6.0, 1, 2)).isEmpty());
        // Unknown pay teaches nothing.
        assertTrue(DeclinedFloor.raisedBy(rules, new OfferSnapshot(null, 6.0, 24, 2)).isEmpty());
    }

    @Test
    public void theAdaptivePayMinimumNamesTheHighestAcceptedPay() {
        FilterSettings rules = new FilterSettings(true, 1000, 0, 0, 0, 0, true, 1420);
        OfferRule.Decision decision = OfferRule.evaluate(new OfferSnapshot(1400, 5.0, 20, 2), rules);
        assertEquals(OfferRule.Result.DECLINE, decision.result);
        assertEquals("must beat highest accepted payout $14.20", decision.reason);
        assertTrue(rules.describe().contains("more than highest accepted $14.20"));
    }

    // ---- A "+$X" amount beside a total "$Y" (seen while on a delivery, so possibly an add-on) ----

    /** The report's rules: $10 minimum, $1.00 a mile, at most 3 stops, the adaptive minimum on with nothing learned. */
    private static final FilterSettings REPORT_RULES = new FilterSettings(true, 1000, 100, 0, 0, 3, true, 0);

    /** A whole offer screen as Dasher shows it, with a "+$" amount right before its total. */
    private static OfferSnapshot plusOffer(String plus, String total, String route) {
        return parse("Decline", plus, total, "incl. tips", route, "Store A", "Customer dropoff",
                "Guaranteed earnings for completing the offer.", "Accept", "0:47");
    }

    @Test
    public void aPlusAmountBesideATotalDeclinesWhenEvenTheirSumFailsTheSetRules() {
        // Reported: +$1 beside $7.35, 7.1 mi. Pay is $7.35, $8.35 or (as an add-on) $1: all below $10.
        OfferSnapshot offer = plusOffer("+$1", "$7.35", "2 stops (7.1 mi) • 23 min");
        assertNull("pay itself stays unknown", offer.payCents);
        OfferRule.Decision decision = OfferRule.evaluate(offer, REPORT_RULES);
        assertEquals(OfferRule.Result.DECLINE, decision.result);
        assertEquals(1000, decision.requiredCents);
        assertEquals("pay at most $8.35 with its +$ amount; flat minimum", decision.reason);
    }

    @Test
    public void aPlusAmountBesideATotalThatMayPassIsReviewedNeverKept() {
        // Reported: +$1 beside $10.10 (7.5 mi) and beside $10.60 (5.3 mi). The sum passes, so the offer might.
        for (OfferSnapshot offer : new OfferSnapshot[] {
                plusOffer("+$1", "$10.10", "2 stops (7.5 mi) • 23 min"),
                plusOffer("+$1", "$10.60", "2 stops (5.3 mi) • 30 min")}) {
            OfferRule.Decision decision = OfferRule.evaluate(offer, REPORT_RULES);
            assertEquals(OfferRule.Result.REVIEW, decision.result);
            assertEquals("pay unclear beside a +$ amount", decision.reason);
            assertEquals(1000, decision.requiredCents);
        }
    }

    @Test
    public void aPlusAmountDeclinesOnlyBelowTheExactSum() {
        OfferRule.Decision short1Cent = OfferRule.evaluate(
                plusOffer("+$1", "$8.99", "2 stops (5.0 mi) • 20 min"), REPORT_RULES);
        assertEquals(OfferRule.Result.DECLINE, short1Cent.result);
        assertEquals("pay at most $9.99 with its +$ amount; flat minimum", short1Cent.reason);
        OfferRule.Decision exact = OfferRule.evaluate(plusOffer("+$1", "$9.00", "2 stops (5.0 mi) • 20 min"),
                REPORT_RULES);
        assertEquals(OfferRule.Result.REVIEW, exact.result);
        assertEquals("pay unclear beside a +$ amount", exact.reason);
    }

    @Test
    public void aPlusAmountDeclinesWhenEvenTheSumMissesARate() {
        OfferRule.Decision decision = OfferRule.evaluate(
                plusOffer("+$1", "$10.10", "2 stops (12.0 mi) • 30 min"), REPORT_RULES);
        assertEquals(OfferRule.Result.DECLINE, decision.result);
        assertEquals(1200, decision.requiredCents);
        assertEquals("pay at most $11.10 with its +$ amount; dollars per mile", decision.reason);

        FilterSettings perStop = new FilterSettings(true, 0, 0, 0, 600, 3);
        OfferRule.Decision stops = OfferRule.evaluate(plusOffer("+$1", "$10.00", "2 stops (5.0 mi) • 20 min"),
                perStop);
        assertEquals(OfferRule.Result.DECLINE, stops.result);
        assertEquals("pay at most $11.00 with its +$ amount; dollars per stop", stops.reason);
    }

    @Test
    public void anAdaptiveOnlyShortfallStaysReview() {
        // The adaptive minimum never judges an add-on, so as an add-on this offer would not fail it.
        OfferSnapshot offer = plusOffer("+$1", "$7.35", "2 stops (7.1 mi) • 23 min");
        FilterSettings beatHighest = new FilterSettings(true, 500, 0, 0, 0, 3, true, 1500);
        OfferRule.Decision payout = OfferRule.evaluate(offer, beatHighest);
        assertEquals(OfferRule.Result.REVIEW, payout.result);
        assertEquals("pay unclear beside a +$ amount", payout.reason);
        assertEquals(1501, payout.requiredCents);

        FilterSettings bestRate = new FilterSettings(true, 500, 0, 0, 0, 3, true, 0,
                new AcceptedBest(0, 0, 3000, 10.0, 0, 0));
        OfferRule.Decision rate = OfferRule.evaluate(offer, bestRate);
        assertEquals("$3.00/mi learned asks $21.30, but it is adaptive", OfferRule.Result.REVIEW, rate.result);
        assertEquals("pay unclear beside a +$ amount", rate.reason);

        FilterSettings declinedByHand = new FilterSettings(true, 500, 0, 0, 0, 3, true, 0, AcceptedBest.NONE,
                new DeclinedFloor(1200, AcceptedBest.NONE));
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(offer, declinedByHand).result);
    }

    @Test
    public void plusAmountShapesOutsideTheNarrowCaseStayPayNotFound() {
        // Each keeps a sum ($6.00) below the $10 minimum, so a missing guard would show as a decline.
        String route = "2 stops (7.1 mi) • 23 min";
        String[][] cases = {
                {"+$1", "$5.00", "incl. tips", route, "+$2"},                        // two amounts added
                {"+$2.00 Peak Pay", "$5.00", "incl. tips", route},                   // a worded bonus
                {"$5.00 +$1", "incl. tips", route},                                  // on the total's own line
                {"+$1", "$5.00", "incl. tips", route, "$6.00"},                      // a second total
                {"+$1", "$5.00", "incl. tips", route, "$0.50/mi"},                   // a rate
                {"+$1", "$5.00", "incl. tips", route, "per order"},                  // qualifiers
                {"+$1", "$5.00", "incl. tips", route, "each delivery"},
                {"+$1", "$5.00", "incl. tips", route, "/order"},
                {"+$1", "$5.00", "incl. tips", route, "Up to"},
                {"+$1", "$5.00", "incl. tips", route, "2x"},
                {"+$1", "incl. tips", "$5.00", route},                               // not side by side
                {"+$1", "5", "$5.00", route},                                        // a split digit between
                {"+$1", "$5.00", "incl. tips", "3 stops (7.1 mi) • 23 min"},         // stacked: "+$" per delivery?
                {"+$1", "$5.00", "incl. tips", route, "Multiple dropoffs (2 stops)"},
                {"+$1", "$5.00", "incl. tips", "7.1 mi • 23 min"},                   // stops not read
                {"+$1", "$5.00", "incl. tips", "+2 stops (3.8 mi) • +12 min"},       // added travel
                {"Add to route", "+$1", "$5.00", "incl. tips", route},               // an add-on
                {"+$1", "$5.00 Guaranteed", "$6.00 Guaranteed", route},              // conflicting labeled pay
                {"+$1", "$5.001", "incl. tips", route},                              // malformed money
                {"+$1", "incl. tips", route},                                        // no total
        };
        for (String[] labels : cases) {
            OfferRule.Decision decision = OfferRule.evaluate(parse(labels), REPORT_RULES);
            assertEquals(Arrays.toString(labels), OfferRule.Result.REVIEW, decision.result);
            assertEquals(Arrays.toString(labels), "pay not found", decision.reason);
        }
    }

    /** The user's rules at 0.4.41: $10, $2.00 a mile, $0.50 a minute, $4.75 a stop, at most 3, adaptive on, unlearned. */
    private static final FilterSettings USER_RULES_0441 = new FilterSettings(true, 1000, 200, 50, 475, 3, true, 0);

    /** Dasher's real 0.4.41 screen, Decline drawn between the "+$" amount and the total (the address replaced). */
    private static OfferSnapshot realPlusScreen(String total, String... afterTotal) {
        java.util.List<String> labels = new java.util.ArrayList<>(Arrays.asList("Very busy", "+$1", "Decline", total));
        labels.addAll(Arrays.asList(afterTotal));
        labels.addAll(Arrays.asList("McDonald's", "100 Example Ave", "Customer dropoff",
                "Guaranteed earnings for completing the offer.", "Accept", "0:35"));
        return OfferParser.parse(labels);
    }

    @Test
    public void theRealScreenWithDeclineBetweenThePlusAmountAndTheTotalDeclinesBelowTheSum() {
        // Before, "Decline" between "+$1" and "$5.75" kept them apart: pay not found, left for review.
        OfferSnapshot offer = realPlusScreen("$5.75", "2 stops (3.4 mi) • 17 min");
        assertNull("pay itself stays unknown", offer.payCents);
        assertEquals(Integer.valueOf(675), offer.payAtMostCents);
        OfferRule.Decision decision = OfferRule.evaluate(offer, USER_RULES_0441);
        assertEquals(OfferRule.Result.DECLINE, decision.result);
        assertEquals("pay at most $6.75 with its +$ amount; flat minimum", decision.reason);
        assertEquals(1000, decision.requiredCents);

        OfferRule.Decision byArea = OfferRule.evaluate(offer, USER_RULES_0441.withScoreByArea(true));
        assertEquals(OfferRule.Result.DECLINE, byArea.result);
        assertTrue(byArea.reason, byArea.reason.startsWith("pay at most $6.75 with its +$ amount; score "));
        assertTrue(byArea.reason, byArea.reason.endsWith("% (needs 100%)"));
    }

    @Test
    public void theRealScreenWhoseSumMayPassIsReviewedInBothModes() {
        OfferSnapshot offer = realPlusScreen("$9.00", "incl. tips", "2 stops (3.7 mi) • 17 min");
        assertEquals("$9.00 + $1", Integer.valueOf(1000), offer.payAtMostCents);
        for (FilterSettings rules : new FilterSettings[] {USER_RULES_0441, USER_RULES_0441.withScoreByArea(true)}) {
            OfferRule.Decision decision = OfferRule.evaluate(offer, rules);
            assertEquals(OfferRule.Result.REVIEW, decision.result);
            assertEquals("pay unclear beside a +$ amount", decision.reason);
        }
    }

    @Test
    public void onlyOfferChromeMayStandBetweenThePlusAmountAndTheTotal() {
        String route = "2 stops (7.1 mi) • 23 min";
        // The offer's controls, a countdown and the busy badge are chrome: the two still sit side by side.
        for (String[] labels : new String[][] {
                {"+$1", "Decline", "$5.00", route},
                {"+$1", "Accept", "Decline", "$5.00", route},
                {"$5.00", "0:35", "+$1", route},
                {"+$1", "Busy", "$5.00", route},
                {"+$1", "Decline offer", "0:47", "$5.00", route}}) {
            OfferSnapshot offer = parse(labels);
            assertEquals(Arrays.toString(labels), Integer.valueOf(600), offer.payAtMostCents);
            assertEquals(Arrays.toString(labels), OfferRule.Result.DECLINE,
                    OfferRule.evaluate(offer, REPORT_RULES).result);
        }
        // Anything else between them keeps them apart, as before.
        for (String[] labels : new String[][] {
                {"+$1", "Decline", "incl. tips", "$5.00", route},
                {"+$1", "Peak pay", "$5.00", route},
                {"+$1", "Decline", "5", "$5.00", route},
                {"+$1", "Not busy at all", "$5.00", route},
                {"+$1", "9:45 PM", "$5.00", route}}) {
            OfferRule.Decision decision = OfferRule.evaluate(parse(labels), REPORT_RULES);
            assertEquals(Arrays.toString(labels), OfferRule.Result.REVIEW, decision.result);
            assertEquals(Arrays.toString(labels), "pay not found", decision.reason);
        }
    }

    @Test
    public void aPlusAmountMattersOnlyToPayRules() {
        FilterSettings stopsOnly = new FilterSettings(true, 0, 0, 0, 0, 3);
        assertEquals(OfferRule.Result.KEEP,
                OfferRule.evaluate(plusOffer("+$1", "$5.00", "2 stops (7.1 mi) • 23 min"), stopsOnly).result);
        OfferRule.Decision tooMany = OfferRule.evaluate(plusOffer("+$1", "$5.00", "4 stops (7.1 mi) • 23 min"),
                stopsOnly);
        assertEquals(OfferRule.Result.DECLINE, tooMany.result);
        assertEquals("4 stops exceeds maximum 3", tooMany.reason);
    }

    @Test
    public void theReportedAddOnWithOnlyIncrementsStaysReview() {
        java.util.List<String> labels = Arrays.asList("+$5.50", "+2 stops (3.8 mi) • +12 min",
                "Multiple dropoffs (2 stops)", "Guaranteed earnings for completing the offer. Contains restricted items,"
                        + " check the recipient's ID.", "Accept", "Decline");
        assertFalse(AddOnOffer.isLikely(labels));
        OfferSnapshot offer = OfferParser.parse(labels);
        assertNull(offer.payCents);
        OfferRule.Decision decision = OfferRule.evaluate(offer, REPORT_RULES);
        assertEquals(OfferRule.Result.REVIEW, decision.result);
        assertEquals("pay not found", decision.reason);
    }

    // ---- Score by area (the user's choice): a standalone offer passes at a 100% area score ----

    /** The user's rules: $13 pay, $3.85 a mile, $0.41 a minute, $4.75 a stop, at most 3 stops. */
    private static final FilterSettings USER = new FilterSettings(true, 1300, 385, 41, 475, 3);
    private static final FilterSettings USER_BY_AREA = USER.withScoreByArea(true);

    @Test
    public void byAreaTheWorkedExamplePassesThoughStrictDeclinesIt() {
        OfferSnapshot offer = new OfferSnapshot(1500, 6.0, 25, 2);
        OfferRule.Decision strict = OfferRule.evaluate(offer, USER);
        assertEquals("6 mi at $3.85 asks $23.10", OfferRule.Result.DECLINE, strict.result);
        assertEquals(2310, strict.requiredCents);
        assertEquals("strict mode shows the score too", 121, strict.scorePercent);

        OfferRule.Decision byArea = OfferRule.evaluate(offer, USER_BY_AREA);
        assertEquals(OfferRule.Result.KEEP, byArea.result);
        assertEquals("score 121% (needs 100%)", byArea.reason);
        assertEquals(121, byArea.scorePercent);
        assertEquals("the least pay that scores 100%", 1243, byArea.requiredCents);
    }

    @Test
    public void byAreaAnOfferUnder100PercentIsDeclinedWithItsScore() {
        // The user's report: $4.25, 2.1 mi, 16 min, 2 stops, declined by the strict rules.
        OfferRule.Decision decision = OfferRule.evaluate(new OfferSnapshot(425, 2.1, 16, 2), USER_BY_AREA);
        assertEquals(OfferRule.Result.DECLINE, decision.result);
        assertEquals("score 49% (needs 100%)", decision.reason);
        assertEquals(873, decision.requiredCents);
        assertEquals("DECLINE: score 49% (needs 100%); $8.73 would score 100%", decision.summary());
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(new OfferSnapshot(873, 2.1, 16, 2), USER_BY_AREA)
                .result);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(new OfferSnapshot(872, 2.1, 16, 2), USER_BY_AREA)
                .result);
    }

    @Test
    public void byAreaMaxStopsStaysAHardLimit() {
        OfferRule.Decision decision = OfferRule.evaluate(new OfferSnapshot(9000, 6.0, 25, 4), USER_BY_AREA);
        assertEquals("a score far above 100% does not lift it", OfferRule.Result.DECLINE, decision.result);
        assertEquals("4 stops exceeds maximum 3", decision.reason);
        assertTrue(decision.scorePercent > 100);
    }

    @Test
    public void byAreaAnActiveMinimumWhoseAmountIsUnreadIsReviewed() {
        // A missing amount could lift the score, so even very high pay is not kept, and low pay is not declined.
        for (OfferSnapshot offer : new OfferSnapshot[] {
                new OfferSnapshot(9000, null, 25, 2), new OfferSnapshot(9000, 6.0, null, 2),
                new OfferSnapshot(9000, 6.0, 25, null), new OfferSnapshot(9000, 6.0, 25, 1),
                new OfferSnapshot(100, null, 25, 2)}) {
            OfferRule.Decision decision = OfferRule.evaluate(offer, USER_BY_AREA);
            assertEquals(offer.summary(), OfferRule.Result.REVIEW, decision.result);
            assertEquals("an enabled value was not found", decision.reason);
            assertEquals(-1, decision.scorePercent);
        }
        assertEquals("pay not found", OfferRule.evaluate(new OfferSnapshot(null, 6.0, 25, 2), USER_BY_AREA).reason);
        // A spoke with no minimum needs nothing read.
        FilterSettings noMile = new FilterSettings(true, 1300, 0, 41, 475, 3, false, 0).withScoreByArea(true);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(new OfferSnapshot(1500, null, 25, 2), noMile).result);
    }

    @Test
    public void byAreaUnreadStopsUnderAMaxStopsRuleCanStillDeclineOnTheScore() {
        FilterSettings strict = new FilterSettings(true, 1300, 385, 41, 0, 3);
        FilterSettings rules = strict.withScoreByArea(true);
        OfferRule.Decision low = OfferRule.evaluate(new OfferSnapshot(500, 6.0, 25, null), rules);
        assertEquals("a known failure", OfferRule.Result.DECLINE, low.result);
        // $17.00 scores 104% on pay, per mile and per minute, though per mile asks $23.10: strictly a known failure.
        OfferSnapshot offer = new OfferSnapshot(1700, 6.0, 25, null);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(offer, strict).result);
        OfferRule.Decision high = OfferRule.evaluate(offer, rules);
        assertEquals("max stops unknown", OfferRule.Result.REVIEW, high.result);
        assertEquals("an enabled value was not found", high.reason);
        assertEquals(104, high.scorePercent);
    }

    @Test
    public void byAreaThePlusCeilingDeclinesOnlyWhenEvenItScoresUnder100Percent() {
        FilterSettings rules = REPORT_RULES.withScoreByArea(true);
        // $10 pay and $1.00 a mile: two spokes, √(835 ÷ 1000 × 835 ÷ 710) = 99.1%.
        OfferRule.Decision decision = OfferRule.evaluate(plusOffer("+$1", "$7.35", "2 stops (7.1 mi) • 23 min"),
                rules);
        assertEquals(OfferRule.Result.DECLINE, decision.result);
        assertEquals("pay at most $8.35 with its +$ amount; score 99% (needs 100%)", decision.reason);
        assertEquals("√(1000 × 710) = 842.6", 843, decision.requiredCents);
        for (OfferSnapshot offer : new OfferSnapshot[] {
                plusOffer("+$1", "$10.10", "2 stops (7.5 mi) • 23 min"),
                plusOffer("+$1", "$10.60", "2 stops (5.3 mi) • 30 min")}) {
            OfferRule.Decision mayPass = OfferRule.evaluate(offer, rules);
            assertEquals(OfferRule.Result.REVIEW, mayPass.result);
            assertEquals("pay unclear beside a +$ amount", mayPass.reason);
            assertEquals(-1, mayPass.scorePercent);
        }
    }

    @Test
    public void byAreaAnAdaptiveOnlyShortfallBesideAPlusAmountStaysReview() {
        // $10 pay, $1.00 a mile, and the adaptive minimum beating an accepted $15.00. +$1 beside $10.10, 12 mi: on the
        // set minimums $11.10 scores √(1110 ÷ 1000 × 1110 ÷ 1200) = 101%, so some reading may pass (strictly, the
        // 12 mi alone ask $12.00 and decline it). With the adaptive $15.01 it would score 83%, but the adaptive minimum
        // never judges add-ons, so it does not count toward this decline.
        OfferSnapshot offer = plusOffer("+$1", "$10.10", "2 stops (12.0 mi) • 30 min");
        FilterSettings strict = new FilterSettings(true, 1000, 100, 0, 0, 3, true, 1500);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(offer, strict).result);
        OfferRule.Decision decision = OfferRule.evaluate(offer, strict.withScoreByArea(true));
        assertEquals(OfferRule.Result.REVIEW, decision.result);
        assertEquals("pay unclear beside a +$ amount", decision.reason);
        assertEquals("√(1501 × 1200) = 1342.1", 1343, decision.requiredCents);
    }

    @Test
    public void byAreaTheAdaptiveMinimumRaisesTheFloorsTheScoreIsTakenAgainst() {
        FilterSettings rules = new FilterSettings(true, 1300, 385, 41, 475, 3, true, 1420,
                AcceptedBest.NONE.raisedBy(new OfferSnapshot(1420, 6.0, 24, 2)),
                new DeclinedFloor(0, new AcceptedBest(0, 0, 0, 0, 1200, 2))).withScoreByArea(true);
        OfferRule.Decision decision = OfferRule.evaluate(new OfferSnapshot(1500, 6.0, 25, 2), rules);
        assertEquals("121% on the set minimums, 94% on the learned ones", OfferRule.Result.DECLINE, decision.result);
        assertEquals("score 94% (needs 100%)", decision.reason);
        assertEquals(1597, decision.requiredCents);
    }

    @Test
    public void byAreaAddOnsKeepTheStrictRules() {
        // The combined route pays $28.00 for 11 mi, which $3.00 a mile prices at $33.00: strict declines it. By area,
        // √(2800 ÷ 2000 × 2800 ÷ 3300) = 109% would have passed it.
        OfferSnapshot active = new OfferSnapshot(2500, 10.0, null, 2);
        AddOnOffer addOn = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$3.00", "+1 mi"));
        FilterSettings strict = new FilterSettings(true, 2000, 300, 0, 0, 0);
        OfferRule.Decision byArea = OfferRule.evaluateAddOn(addOn, strict.withScoreByArea(true));
        assertEquals(OfferRule.Result.DECLINE, byArea.result);
        assertEquals(OfferRule.evaluateAddOn(addOn, strict).reason, byArea.reason);
        assertEquals(-1, byArea.scorePercent);
    }

    @Test
    public void byAreaWithNoMinimumOnlyMaxStopsAsks() {
        FilterSettings stopsOnly = new FilterSettings(true, 0, 0, 0, 0, 3).withScoreByArea(true);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(new OfferSnapshot(500, 6.0, 25, 2), stopsOnly).result);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(new OfferSnapshot(500, 6.0, 25, 4), stopsOnly)
                .result);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(new OfferSnapshot(500, 6.0, 25, null), stopsOnly)
                .result);
    }

    @Test
    public void theModeIsDescribedAndKeptThroughEveryChange() {
        assertTrue(USER_BY_AREA.describe(), USER_BY_AREA.describe().startsWith(
                "scored by area, 100% needed (max stops is a hard limit) · $13.00 pay · $3.85 per mile"));
        assertTrue(USER.describe().startsWith("at least $13.00"));
        assertTrue(USER_BY_AREA.withEnabled(false).scoreByArea);
        assertTrue(USER_BY_AREA.withMinimums(new int[] {1, 2, 3, 4}).scoreByArea);
        assertTrue(USER_BY_AREA.adoptAdaptive().scoreByArea);
        assertFalse(USER_BY_AREA.withScoreByArea(false).scoreByArea);
        assertFalse("strict is the default", USER.scoreByArea);
    }
}

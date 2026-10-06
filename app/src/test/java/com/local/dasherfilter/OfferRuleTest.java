package com.local.dasherfilter;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The one strict rule (0.5.0): three money minimums at a bar, max stops as a hard limit, the "+$" ceiling and the
 * add-on rule, with their exact reasons; plus the parser cases that feed it.
 */
public final class OfferRuleTest {
    private static OfferSnapshot parse(String... labels) {
        return OfferParser.parse(Arrays.asList(labels));
    }

    private static OfferRule.Decision evaluate(OfferSnapshot offer, FilterSettings rules) {
        return OfferRule.evaluate(offer, rules);
    }

    // ---- What the parser hands the rule ----

    @Test
    public void parsesAnOfferWithExplicitValues() {
        OfferSnapshot offer = parse("$8.50 Guaranteed", "4.2 mi", "Estimated time 24 min", "3 stops");
        assertEquals(Integer.valueOf(850), offer.payCents);
        assertEquals(4.2, offer.miles, 0.001);
        assertEquals(Integer.valueOf(24), offer.minutes);
        assertEquals(Integer.valueOf(3), offer.stops);
    }

    @Test
    public void ignoresAnUnrelatedEarningsAmountWhenPayIsGuaranteed() {
        OfferSnapshot offer = parse("$100.00 Weekly earnings", "Guaranteed", "$8.50", "4 mi");
        assertEquals(Integer.valueOf(850), offer.payCents);
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
        assertEquals(4.2, parse("$8.50", "4.2 mi").miles, 0.001);
        assertEquals(1.0, parse("$8.50", "1 mile").miles, 0.001);
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

    // ---- The three minimums and their reasons ----

    @Test
    public void theHighestMinimumBindsAndNamesTheReason() {
        // $4.00, $1.00 a mile, 25¢ a minute ($15 an hour).
        FilterSettings starters = FilterSettings.of(true, 400, 100, 25, 0);
        OfferRule.Decision byPay = evaluate(new OfferSnapshot(399, 2.0, 10, 2), starters);
        assertEquals(OfferRule.Result.DECLINE, byPay.result);
        assertEquals("flat minimum", byPay.reason);
        assertEquals(400, byPay.requiredCents);
        OfferRule.Decision byMile = evaluate(new OfferSnapshot(900, 10.0, 25, 2), starters);
        assertEquals("dollars per mile", byMile.reason);
        assertEquals(1000, byMile.requiredCents);
        OfferRule.Decision byHour = evaluate(new OfferSnapshot(900, 5.0, 40, 2), starters);
        assertEquals("dollars per hour", byHour.reason);
        assertEquals(1000, byHour.requiredCents);
        assertEquals("DECLINE: required at least $10.00 (dollars per hour)", byHour.summary());
        // A tie names the first of pay, per mile and per hour: $4.00 = 4 mi × $1.00 = 16 min × 25¢.
        assertEquals("flat minimum", evaluate(new OfferSnapshot(399, 4.0, 16, 2), starters).reason);
        assertEquals("dollars per mile", evaluate(new OfferSnapshot(399, 4.0, 15, 2), FilterSettings.of(true, 0, 100,
                25, 0)).reason);
    }

    @Test
    public void aBarScalesEveryMinimumOnceAndPrefixesTheReason() {
        FilterSettings starters = FilterSettings.of(true, 400, 100, 25, 0);
        OfferSnapshot offer = new OfferSnapshot(512, 5.9, 25, 2);
        OfferRule.Decision at82 = evaluate(offer, starters.withMinimumScalePercent(82));
        assertEquals(OfferRule.Result.DECLINE, at82.result);
        assertEquals("82% bar: dollars per hour", at82.reason);
        assertEquals(513, at82.requiredCents);
        assertEquals(82, at82.minimumScalePercent);
        OfferRule.Decision at110 = evaluate(new OfferSnapshot(687, 5.9, 25, 2), starters.withMinimumScalePercent(110));
        assertEquals("⌈1.10 × 625⌉ = 688", 688, at110.requiredCents);
        assertEquals("110% bar: dollars per hour", at110.reason);
        // The minimums themselves are never rewritten by a bar.
        assertArrayEquals(starters.minimums(), starters.withMinimumScalePercent(82).minimums());
    }

    @Test
    public void keepReasonsSayWhetherTheOfferMeetsTheMinimumsOrOnlyTheBar() {
        FilterSettings starters = FilterSettings.of(true, 400, 100, 25, 0);
        OfferSnapshot score85 = new OfferSnapshot(575, 6.6, 27, 2);
        assertEquals(85, AreaScore.scorePercent(starters, score85));

        OfferRule.Decision atMinimums = evaluate(new OfferSnapshot(675, 6.6, 27, 2), starters);
        assertEquals(OfferRule.Result.KEEP, atMinimums.result);
        assertEquals("meets your minimums", atMinimums.reason);
        assertFalse(atMinimums.belowMinimums);

        OfferRule.Decision below = evaluate(score85, starters.withMinimumScalePercent(82));
        assertEquals(OfferRule.Result.KEEP, below.result);
        assertEquals("below your minimums; passes the 82% bar", below.reason);
        assertTrue(below.belowMinimums);
        assertEquals(554, below.requiredCents);

        // Meeting 100% under a lower bar is just meeting the minimums.
        OfferRule.Decision meetsAnyway = evaluate(new OfferSnapshot(700, 6.6, 27, 2),
                starters.withMinimumScalePercent(82));
        assertEquals("meets your minimums", meetsAnyway.reason);
        assertFalse(meetsAnyway.belowMinimums);

        OfferRule.Decision above = evaluate(new OfferSnapshot(743, 6.6, 27, 2), starters.withMinimumScalePercent(110));
        assertEquals(OfferRule.Result.KEEP, above.result);
        assertEquals("meets the 110% bar", above.reason);
        assertFalse(above.belowMinimums);
        assertEquals(743, above.requiredCents);
    }

    @Test
    public void belowMinimumsIsOnlyAKeepBetweenTheBarAndOneHundredPercent() {
        FilterSettings at82 = FilterSettings.of(true, 400, 100, 25, 0).withMinimumScalePercent(82);
        for (int pay = 500; pay <= 700; pay++) {
            OfferRule.Decision decision = evaluate(new OfferSnapshot(pay, 6.6, 27, 2), at82);
            boolean expected = decision.result == OfferRule.Result.KEEP && decision.scorePercent >= 82
                    && decision.scorePercent < 100;
            assertEquals("pay " + pay, expected, decision.belowMinimums);
            if (decision.belowMinimums) assertTrue(decision.scorePercent >= decision.minimumScalePercent);
        }
        // Never at or above 100, never a decline, never a review.
        FilterSettings starters = FilterSettings.of(true, 400, 100, 25, 0);
        assertFalse(evaluate(new OfferSnapshot(575, 6.6, 27, 2), starters).belowMinimums);
        assertFalse(evaluate(new OfferSnapshot(575, 6.6, 27, 2), starters.withMinimumScalePercent(120)).belowMinimums);
        assertFalse(evaluate(new OfferSnapshot(575, null, 27, 2), at82).belowMinimums);
    }

    @Test
    public void theDecisionCarriesTheAutopilotSwitch() {
        FilterSettings on = new FilterSettings(true, 400, 100, 25, 0, true, 70, 82);
        assertTrue(evaluate(new OfferSnapshot(575, 6.6, 27, 2), on).autopilot);
        assertTrue(evaluate(new OfferSnapshot(300, 6.6, 27, 2), on).autopilot);
        assertFalse(evaluate(new OfferSnapshot(575, 6.6, 27, 2), FilterSettings.of(true, 400, 100, 25, 0)).autopilot);
    }

    @Test
    public void declinesWhenAKnownFloorFailsEvenIfTimeIsMissing() {
        OfferSnapshot offer = parse("$5.00 Guaranteed", "4 mi");
        FilterSettings settings = FilterSettings.of(true, 600, 150, 30, 0);
        OfferRule.Decision decision = evaluate(offer, settings);
        assertEquals(OfferRule.Result.DECLINE, decision.result);
        assertEquals("$6.00 = 4 mi × $1.50; minutes unread", 600, decision.requiredCents);
        assertEquals("a needed quantity is unread", -1, decision.scorePercent);
    }

    @Test
    public void leavesAmbiguousOffersForReview() {
        FilterSettings settings = FilterSettings.of(true, 600, 150, 30, 0);

        // Two competing amounts leave pay unknown.
        OfferSnapshot ambiguousPay = parse("$8.00", "$9.00", "3 mi");
        assertNull(ambiguousPay.payCents);
        OfferRule.Decision payNotFound = evaluate(ambiguousPay, settings);
        assertEquals(OfferRule.Result.REVIEW, payNotFound.result);
        assertEquals("pay not found", payNotFound.reason);

        // Pay clears the known floors, but the time the per-hour minimum needs is missing.
        OfferRule.Decision missingTime = evaluate(parse("$10.00 Guaranteed", "3 mi"), settings);
        assertEquals(OfferRule.Result.REVIEW, missingTime.result);
        assertEquals("an enabled value was not found", missingTime.reason);
        assertEquals(600, missingTime.requiredCents);
    }

    @Test
    public void stopsMatterOnlyToMaxStops() {
        // Per stop is retired: four stops ask no more than two, and an unread or misread stop count is no failure.
        FilterSettings rules = FilterSettings.of(true, 600, 100, 30, 0);
        OfferRule.Decision fourStops = evaluate(new OfferSnapshot(1000, 6.0, 20, 4), rules);
        assertEquals(OfferRule.Result.KEEP, fourStops.result);
        assertEquals(600, fourStops.requiredCents);
        assertEquals(OfferRule.Result.KEEP, evaluate(new OfferSnapshot(1000, 6.0, 20, null), rules).result);
        OfferSnapshot oneStop = parse("$5.00 Guaranteed", "3.0 mi", "1 stop");
        assertEquals(Integer.valueOf(1), oneStop.stops);
        assertEquals(OfferRule.Result.KEEP,
                evaluate(oneStop, FilterSettings.of(true, 400, 0, 0, 3)).result);
        // Nor is "3 items" a stop count.
        assertEquals(OfferRule.Result.KEEP, evaluate(parse("$50.00 Guaranteed", "3 items"),
                FilterSettings.of(true, 600, 0, 0, 0)).result);
    }

    @Test
    public void itemCountsAreOfferFiguresNotARule() {
        FilterSettings rules = FilterSettings.of(true, 600, 100, 30, 0);
        OfferSnapshot shopping = new OfferSnapshot(1000, 6.0, 20, 2).withItems(null, true);
        assertEquals("an unread count is no reason to review", OfferRule.Result.KEEP, evaluate(shopping, rules).result);
        assertEquals(OfferRule.Result.KEEP, evaluate(shopping.withItems(40, true), rules).result);
        assertEquals(evaluate(shopping, rules).requiredCents, evaluate(shopping.withItems(40, true), rules).requiredCents);
    }

    @Test
    public void twentyDollarRuleDoesNotDependOnMileageOrStops() {
        FilterSettings settings = FilterSettings.of(true, 2000, 0, 0, 0);
        OfferSnapshot belowFloor = new OfferSnapshot(1999, null, null, null);
        OfferSnapshot atFloor = new OfferSnapshot(2000, null, null, null);
        OfferSnapshot aboveFloor = new OfferSnapshot(2001, null, null, null);
        assertEquals(OfferRule.Result.DECLINE, evaluate(belowFloor, settings).result);
        assertEquals(OfferRule.Result.KEEP, evaluate(atFloor, settings).result);
        assertEquals(OfferRule.Result.KEEP, evaluate(aboveFloor, settings).result);
        assertEquals(99, evaluate(belowFloor, settings).scorePercent);
        assertEquals(100, evaluate(atFloor, settings).scorePercent);
    }

    @Test
    public void threeDollarOfferFailsTwentyTwoDollarFloorWithOtherValuesMissing() {
        OfferSnapshot offer = parse("$3.00 Guaranteed", "Accept, 30 seconds", "Decline");
        assertEquals(Integer.valueOf(300), offer.payCents);

        FilterSettings settings = FilterSettings.of(true, 2200, 150, 30, 2);
        OfferRule.Decision decision = evaluate(offer, settings);
        assertEquals(OfferRule.Result.DECLINE, decision.result);
        assertEquals(2200L, decision.requiredCents);
        assertEquals("flat minimum", decision.reason);
    }

    // ---- Max stops: a hard, inclusive limit the bar never scales ----

    @Test
    public void maximumStopsIsInclusiveAndWorksWithoutPay() {
        FilterSettings settings = FilterSettings.of(true, 0, 0, 0, 3);
        OfferSnapshot twoStops = new OfferSnapshot(null, null, null, 2);
        OfferSnapshot threeStops = new OfferSnapshot(null, null, null, 3);
        assertEquals(OfferRule.Result.KEEP, evaluate(twoStops, settings).result);
        assertEquals(OfferRule.Result.KEEP, evaluate(threeStops, settings).result);

        OfferRule.Decision decision = evaluate(new OfferSnapshot(null, null, null, 4), settings);
        assertEquals(OfferRule.Result.DECLINE, decision.result);
        assertEquals("DECLINE: 4 stops exceeds maximum 3", decision.summary());
    }

    @Test
    public void maxStopsIsNeverScaledAndAHighScoreDoesNotLiftIt() {
        FilterSettings rules = FilterSettings.of(true, 400, 100, 25, 3);
        for (int bar : new int[] {1, 50, 100, 150, 200}) {
            OfferRule.Decision decision = evaluate(new OfferSnapshot(9000, 6.0, 25, 4),
                    rules.withMinimumScalePercent(bar));
            assertEquals(OfferRule.Result.DECLINE, decision.result);
            assertEquals("4 stops exceeds maximum 3", decision.reason);
            assertEquals("the score still says what the pay is worth", 1440, decision.scorePercent);
            assertEquals(OfferRule.Result.KEEP, evaluate(new OfferSnapshot(9000, 6.0, 25, 3),
                    rules.withMinimumScalePercent(bar)).result);
        }
    }

    @Test
    public void zeroDisablesMaximumStops() {
        FilterSettings settings = FilterSettings.of(true, 2000, 0, 0, 0);
        OfferSnapshot tenStops = new OfferSnapshot(2000, null, null, 10);
        OfferSnapshot unknownStops = new OfferSnapshot(2000, null, null, null);
        assertEquals(OfferRule.Result.KEEP, evaluate(tenStops, settings).result);
        assertEquals(OfferRule.Result.KEEP, evaluate(unknownStops, settings).result);
    }

    @Test
    public void unknownStopsNeedReviewUnlessPriceAlreadyFails() {
        // With only a stop ceiling enabled, unknown stops need review.
        FilterSettings stopCeilingOnly = FilterSettings.of(true, 0, 0, 0, 2);
        OfferSnapshot nothingKnown = new OfferSnapshot(null, null, null, null);
        OfferRule.Decision review = evaluate(nothingKnown, stopCeilingOnly);
        assertEquals(OfferRule.Result.REVIEW, review.result);
        assertEquals("an enabled value was not found", review.reason);

        // With a price floor as well, unknown stops need review unless the known price already fails.
        FilterSettings settings = FilterSettings.of(true, 2000, 0, 0, 2);
        OfferSnapshot atFloor = new OfferSnapshot(2000, null, null, null);
        OfferSnapshot belowFloor = new OfferSnapshot(1999, null, null, null);
        assertEquals(OfferRule.Result.REVIEW, evaluate(atFloor, settings).result);
        assertEquals("the score needs no stop count", 100, evaluate(atFloor, settings).scorePercent);
        assertEquals(OfferRule.Result.DECLINE, evaluate(belowFloor, settings).result);
    }

    @Test
    public void eitherPriceOrStopLimitCanDeclineAnOffer() {
        FilterSettings settings = FilterSettings.of(true, 2000, 0, 0, 2);

        // Too many stops declines regardless of pay, even when pay is unknown.
        OfferSnapshot highPayTooManyStops = new OfferSnapshot(9999, null, null, 3);
        OfferSnapshot unknownPayTooManyStops = new OfferSnapshot(null, null, null, 3);
        assertEquals(OfferRule.Result.DECLINE, evaluate(highPayTooManyStops, settings).result);
        assertEquals(OfferRule.Result.DECLINE, evaluate(unknownPayTooManyStops, settings).result);

        // Within the stop limit, the price decides.
        OfferSnapshot unknownPayAllowedStops = new OfferSnapshot(null, null, null, 2);
        OfferSnapshot lowPayAllowedStops = new OfferSnapshot(1999, null, null, 2);
        OfferSnapshot atFloorAllowedStops = new OfferSnapshot(2000, null, null, 2);
        assertEquals(OfferRule.Result.REVIEW, evaluate(unknownPayAllowedStops, settings).result);
        assertEquals(OfferRule.Result.DECLINE, evaluate(lowPayAllowedStops, settings).result);
        assertEquals(OfferRule.Result.KEEP, evaluate(atFloorAllowedStops, settings).result);
    }

    @Test
    public void ambiguousStopCountsDoNotTriggerTheCeiling() {
        FilterSettings settings = FilterSettings.of(true, 0, 0, 0, 2);
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
            assertEquals(OfferRule.Result.REVIEW, evaluate(offer, settings).result);
        }
    }

    @Test
    public void withNoMoneyMinimumPayDoesNotMatter() {
        FilterSettings stopsOnly = FilterSettings.of(true, 0, 0, 0, 3);
        OfferRule.Decision decision = evaluate(new OfferSnapshot(null, null, null, 2), stopsOnly);
        assertEquals(OfferRule.Result.KEEP, decision.result);
        assertEquals("meets your minimums", decision.reason);
        assertEquals(0, decision.requiredCents);
        assertEquals(-1, decision.scorePercent);
        assertEquals(OfferRule.Result.KEEP, evaluate(new OfferSnapshot(1, 99.0, 300, 3), stopsOnly).result);
    }

    // ---- Describing the rules ----

    @Test
    public void rulesAreDescribedWithPerMinuteShownPerHour() {
        FilterSettings rules = FilterSettings.of(true, 400, 100, 25, 3);
        assertEquals("at least $4.00 · $1.00 per mile · $15.00 per hour · at most 3 stops", rules.describe());
        assertEquals("$4 min · $1/mi · $15/hr · ≤3 stops", rules.brief());
        FilterSettings autopilot = new FilterSettings(true, 400, 100, 25, 3, true, 70, 82);
        assertEquals("at least $4.00 · $1.00 per mile · $15.00 per hour · at most 3 stops · Autopilot bar 82% (goal 70%)",
                autopilot.describe());
        assertEquals("$4 min · $1/mi · $15/hr · ≤3 stops · Auto 82%", autopilot.brief());
        assertEquals("$24.60 per hour", FilterSettings.of(true, 0, 0, 41, 0).describe());
        assertEquals("$24.60/hr", FilterSettings.of(true, 0, 0, 41, 0).brief());
        assertTrue(new FilterSettings(true, 400, 0, 0, 1, true, 0, 119).describe()
                .endsWith("at most 1 stop · Autopilot bar 119% (pay first)"));
        assertEquals("No rules set", FilterSettings.of(true, 0, 0, 0, 0).describe());
        assertEquals("No rules set", FilterSettings.of(true, 0, 0, 0, 0).brief());
        assertEquals("at least $4.00 · bar 97%", FilterSettings.of(true, 400, 0, 0, 0)
                .withMinimumScalePercent(97).describe());
    }

    @Test
    public void theRuleSwitchesAndSettersKeepEverythingElse() {
        FilterSettings rules = new FilterSettings(true, 400, 100, 25, 3, true, 50, 82);
        assertTrue(rules.hasAnyRule());
        assertTrue(rules.hasMonetaryRule());
        assertTrue(rules.hasMarginalRule());
        assertFalse(FilterSettings.of(true, 400, 0, 0, 0).hasMarginalRule());
        assertTrue(FilterSettings.of(true, 0, 0, 0, 3).hasAnyRule());
        assertFalse(FilterSettings.of(true, 0, 0, 0, 3).hasMonetaryRule());
        assertEquals("true:400:100:25:3:true:50", rules.rulesKey());
        assertEquals("the bar is not part of a plan's rules", rules.rulesKey(),
                rules.withMinimumScalePercent(120).rulesKey());
        assertEquals("false:400:100:25:3:true:50", rules.withEnabled(false).rulesKey());
        assertEquals(82, rules.withEnabled(false).minimumScalePercent);
        assertEquals(5, rules.withMaxStops(5).maxStops);
        assertEquals(0, rules.withMaxStops(-2).maxStops);
        FilterSettings changed = rules.withMinimums(500, 125, 35);
        assertEquals("true:500:125:35:3:true:50", changed.rulesKey());
        assertEquals(82, changed.minimumScalePercent);
        // Turning Autopilot off puts the bar back at exactly the minimums; on keeps it.
        assertEquals(100, rules.withAutopilot(false, 50).minimumScalePercent);
        assertFalse(rules.withAutopilot(false, 50).autopilot);
        assertEquals(82, rules.withAutopilot(true, 0).minimumScalePercent);
        assertEquals(0, rules.withAutopilot(true, 0).autopilotGoalPercent);
        assertEquals("an unknown goal is the default", 70, rules.withAutopilot(true, 60).autopilotGoalPercent);
        // Money is held to $0–$1,000 and the bar to 1–200%.
        FilterSettings clamped = new FilterSettings(true, -5, 200_000, 100_001, 0, false, 70, 500);
        assertEquals(0, clamped.flatCents);
        assertEquals(FilterSettings.MOST_CENTS, clamped.perMileCents);
        assertEquals(FilterSettings.MOST_CENTS, clamped.perMinuteCents);
        assertEquals(200, clamped.minimumScalePercent);
        assertEquals(1, clamped.withMinimumScalePercent(Integer.MIN_VALUE).minimumScalePercent);
        FilterSettings defaults = FilterSettings.of(true, 400, 100, 25, 0);
        assertFalse(defaults.autopilot);
        assertEquals(70, defaults.autopilotGoalPercent);
        assertEquals(100, defaults.minimumScalePercent);
        assertEquals(1500, defaults.perHourCents());
    }

    // ---- Add-ons: the combined route and the explicit increment, at the bar ----

    @Test
    public void addOnUsesMarginalEconomicsAndCombinedRoute() {
        FilterSettings settings = FilterSettings.of(true, 2000, 150, 0, 0);
        OfferSnapshot active = new OfferSnapshot(2500, 10.0, null, 2);

        AddOnOffer goodAddOn = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$3.00", "+1 mi"));
        OfferRule.Decision good = OfferRule.evaluateAddOn(goodAddOn, settings);
        assertEquals(OfferRule.Result.KEEP, good.result);
        assertEquals("combined route and add-on meet your minimums", good.reason);
        assertEquals(-1, good.scorePercent);

        // +4 mi at $1.50/mi needs $6.00 of marginal pay; only +$3.00 is offered.
        AddOnOffer badAddOn = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$3.00", "+4 mi"));
        OfferRule.Decision decision = OfferRule.evaluateAddOn(badAddOn, settings);
        assertEquals(OfferRule.Result.DECLINE, decision.result);
        assertEquals(600L, decision.requiredCents);
        assertEquals("add-on marginal economics", decision.reason);
    }

    @Test
    public void addOnFlatMinimumAppliesToCombinedRouteNotTwice() {
        OfferSnapshot active = new OfferSnapshot(2200, null, null, null);
        AddOnOffer addOn = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$3.00"));
        FilterSettings settings = FilterSettings.of(true, 2200, 0, 0, 0);
        OfferRule.Decision decision = OfferRule.evaluateAddOn(addOn, settings);
        assertEquals(OfferRule.Result.KEEP, decision.result);
        assertEquals("flat is not an increment floor", 0, decision.requiredCents);
    }

    @Test
    public void theCombinedRouteIsJudgedAtTheBar() {
        // Combined $28.00 for 11 mi and 40 min against $3.00 a mile: $33.00 at 100%, $27.06 at 82%.
        OfferSnapshot active = new OfferSnapshot(2500, 10.0, 30, 2);
        AddOnOffer addOn = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$3.00", "+1 mi", "+10 min"));
        FilterSettings rules = FilterSettings.of(true, 2000, 300, 0, 0);
        OfferRule.Decision at100 = OfferRule.evaluateAddOn(addOn, rules);
        assertEquals(OfferRule.Result.DECLINE, at100.result);
        assertEquals("combined route fails: dollars per mile", at100.reason);
        assertEquals(3300, at100.requiredCents);
        assertEquals(-1, at100.scorePercent);
        OfferRule.Decision at82 = OfferRule.evaluateAddOn(addOn, rules.withMinimumScalePercent(82));
        assertEquals("$3.00 covers ⌈0.82 × 300⌉ = $2.46", OfferRule.Result.KEEP, at82.result);
        assertEquals(246, at82.requiredCents);
        assertTrue("it passes only below the minimums", at82.belowMinimums);
        assertEquals("combined route and add-on below your minimums; pass the 82% bar", at82.reason);
        OfferRule.Decision at70 = OfferRule.evaluateAddOn(addOn, rules.withMinimumScalePercent(70));
        assertEquals(OfferRule.Result.KEEP, at70.result);
        assertTrue(at70.belowMinimums);
        OfferRule.Decision at130 = OfferRule.evaluateAddOn(addOn, rules.withMinimumScalePercent(130));
        assertEquals("combined route fails: 130% bar: dollars per mile", at130.reason);
    }

    @Test
    public void theIncrementNeedsTheHigherOfItsAddedMilesAndMinutesAtTheBar() {
        // +3 mi at $1.00 is $3.00; +12 min at 10¢ is $1.20: the higher applies, never their sum.
        FilterSettings rates = FilterSettings.of(true, 0, 100, 10, 0);
        AddOnOffer longer = AddOnOffer.parse(new OfferSnapshot(2500, 5.0, 20, 2),
                Arrays.asList("Add to route", "+$3.00", "+2 stops", "+3 mi", "+12 min"));
        OfferRule.Decision decision = OfferRule.evaluateAddOn(longer, rates);
        assertEquals(OfferRule.Result.KEEP, decision.result);
        assertEquals(300, decision.requiredCents);
        OfferRule.Decision raised = OfferRule.evaluateAddOn(longer, rates.withMinimumScalePercent(101));
        assertEquals(OfferRule.Result.DECLINE, raised.result);
        assertEquals(303, raised.requiredCents);
        assertEquals("101% bar: add-on marginal economics", raised.reason);
        OfferRule.Decision lowered = OfferRule.evaluateAddOn(longer, rates.withMinimumScalePercent(90));
        assertEquals(270, lowered.requiredCents);
        assertFalse("it meets 100% too", lowered.belowMinimums);
        // The minutes bind once they ask more: +40 min at 10¢ is $4.00.
        AddOnOffer slower = AddOnOffer.parse(new OfferSnapshot(2500, 5.0, 20, 2),
                Arrays.asList("Add to route", "+$3.99", "+3 mi", "+40 min"));
        assertEquals(400, OfferRule.evaluateAddOn(slower, rates).requiredCents);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluateAddOn(slower, rates).result);
    }

    @Test
    public void addOnUsesTheCombinedStopCeilingAndNeverPricesAddedStops() {
        OfferSnapshot active = new OfferSnapshot(2500, null, null, 2);
        AddOnOffer addOn = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$3.00", "+2 stops"));

        // Two added stops bring the combined route to four, above the ceiling of three.
        FilterSettings stopCeiling = FilterSettings.of(true, 0, 0, 0, 3);
        OfferRule.Decision tooMany = OfferRule.evaluateAddOn(addOn, stopCeiling);
        assertEquals(OfferRule.Result.DECLINE, tooMany.result);
        assertEquals("combined route fails: 4 stops exceeds maximum 3", tooMany.reason);

        // Per stop is retired: added stops ask nothing, with or without a known route.
        FilterSettings flatOnly = FilterSettings.of(true, 2000, 0, 0, 0);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluateAddOn(addOn, flatOnly).result);
        AddOnOffer totalOnly = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$5.00", "New total 4 stops"));
        assertNull(totalOnly.incremental.stops);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluateAddOn(totalOnly, flatOnly).result);
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

        // The total of 4 would have broken at most 3 stops: it needs review now.
        OfferRule.Decision ceiling = OfferRule.evaluateAddOn(disagreeing, FilterSettings.of(true, 0, 0, 0, 3));
        assertEquals(OfferRule.Result.REVIEW, ceiling.result);
        assertEquals("add-on has missing or ambiguous incremental/route evidence", ceiling.reason);

        // A separate known failure still declines: +3 mi at $1.50/mi asks $4.50 of the +$3.00.
        AddOnOffer withMiles = AddOnOffer.parse(active,
                Arrays.asList("Add to route", "+$3.00", "+1 stop", "+3 mi", "New total 4 stops"));
        assertNull(withMiles.combined.stops);
        assertEquals(OfferRule.Result.DECLINE,
                OfferRule.evaluateAddOn(withMiles, FilterSettings.of(true, 0, 150, 0, 3)).result);

        // Figures that agree stand: 2 + 1 = 3.
        AddOnOffer agreeing = AddOnOffer.parse(active,
                Arrays.asList("Add to route", "+$3.00", "+1 stop", "New total 3 stops"));
        assertEquals(Integer.valueOf(1), agreeing.incremental.stops);
        assertEquals(Integer.valueOf(3), agreeing.combined.stops);
        assertEquals(OfferRule.Result.KEEP,
                OfferRule.evaluateAddOn(agreeing, FilterSettings.of(true, 0, 0, 0, 3)).result);

        // Without the route's stops there is nothing to disagree with: the explicit figures stand.
        AddOnOffer noRoute = AddOnOffer.parse(null,
                Arrays.asList("Add to route", "+$3.00", "+1 stop", "New total 4 stops"));
        assertEquals(Integer.valueOf(1), noRoute.incremental.stops);
        assertEquals(Integer.valueOf(4), noRoute.combined.stops);
        assertEquals(OfferRule.Result.DECLINE,
                OfferRule.evaluateAddOn(noRoute, FilterSettings.of(true, 0, 0, 0, 3)).result);
    }

    @Test
    public void addOnMissingEnabledMarginalMetricRequiresReviewUnlessKnownFailure() {
        FilterSettings settings = FilterSettings.of(true, 0, 150, 30, 0);
        OfferSnapshot active = new OfferSnapshot(2500, 10.0, 50, 2);

        // Added minutes are not shown, so the per-hour minimum cannot price them.
        AddOnOffer missingMinutes = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$5.00", "+2 mi"));
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluateAddOn(missingMinutes, settings).result);

        // +3 mi at $1.50/mi already needs $4.50, so +$2.00 is a known failure.
        AddOnOffer knownFailure = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$2.00", "+3 mi"));
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluateAddOn(knownFailure, settings).result);
    }

    @Test
    public void addOnNeedsIncrementalPayOnlyWhenARateMinimumIsSet() {
        // No accepted route is known, so only the explicit new total describes the combined route.
        AddOnOffer addOn = AddOnOffer.parse(null, Arrays.asList("Add to route", "New total $30.00"));
        assertNull(addOn.incremental.payCents);

        // The flat minimum applies to the combined route, which is known to pay $30.00.
        FilterSettings flatOnly = FilterSettings.of(true, 2000, 0, 0, 0);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluateAddOn(addOn, flatOnly).result);

        // A per-mile rule prices the added miles, which needs the unknown added pay and distance.
        FilterSettings perMile = FilterSettings.of(true, 2000, 150, 0, 0);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluateAddOn(addOn, perMile).result);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluateAddOn(addOn, FilterSettings.of(true, 2000, 0, 30, 0))
                .result);

        // A stop ceiling alone needs the combined stop count, which is unknown here.
        FilterSettings stopCeiling = FilterSettings.of(true, 0, 0, 0, 3);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluateAddOn(addOn, stopCeiling).result);

        // With a known combined route, unknown added pay is review only under a rate minimum.
        AddOnOffer known = AddOnOffer.parse(null, Arrays.asList("Add to route", "New total $30.00",
                "Total distance: 12 mi", "Total time: 40 min", "Total stops: 3 stops"));
        assertNull(known.incremental.payCents);
        assertEquals(Integer.valueOf(3000), known.combined.payCents);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluateAddOn(known, flatOnly).result);
        OfferRule.Decision rate = OfferRule.evaluateAddOn(known, FilterSettings.of(true, 2000, 100, 0, 0));
        assertEquals(OfferRule.Result.REVIEW, rate.result);
        assertEquals("add-on has missing or ambiguous incremental/route evidence", rate.reason);
    }

    @Test
    public void theReportedAddOnWithOnlyIncrementsStaysReview() {
        java.util.List<String> labels = Arrays.asList("+$5.50", "+2 stops (3.8 mi) • +12 min",
                "Multiple dropoffs (2 stops)", "Guaranteed earnings for completing the offer. Contains restricted items,"
                        + " check the recipient's ID.", "Accept", "Decline");
        assertFalse(AddOnOffer.isLikely(labels));
        OfferSnapshot offer = OfferParser.parse(labels);
        assertNull(offer.payCents);
        OfferRule.Decision decision = evaluate(offer, REPORT_RULES);
        assertEquals(OfferRule.Result.REVIEW, decision.result);
        assertEquals("pay not found", decision.reason);
    }

    // ---- A "+$X" amount beside a total "$Y" (seen while on a delivery, so possibly an add-on) ----

    /** The report's rules: $10 minimum, $1.00 a mile, at most 3 stops. */
    private static final FilterSettings REPORT_RULES = FilterSettings.of(true, 1000, 100, 0, 3);

    /** A whole offer screen as Dasher shows it, with a "+$" amount right before its total. */
    private static OfferSnapshot plusOffer(String plus, String total, String route) {
        return parse("Decline", plus, total, "incl. tips", route, "Store A", "Customer dropoff",
                "Guaranteed earnings for completing the offer.", "Accept", "0:47");
    }

    @Test
    public void aPlusAmountBesideATotalDeclinesWhenEvenTheirSumFailsTheMinimums() {
        // Reported: +$1 beside $7.35, 7.1 mi. Pay is $7.35, $8.35 or (as an add-on) $1: all below $10.
        OfferSnapshot offer = plusOffer("+$1", "$7.35", "2 stops (7.1 mi) • 23 min");
        assertNull("pay itself stays unknown", offer.payCents);
        OfferRule.Decision decision = evaluate(offer, REPORT_RULES);
        assertEquals(OfferRule.Result.DECLINE, decision.result);
        assertEquals(1000, decision.requiredCents);
        assertEquals("pay at most $8.35 with its +$ amount; flat minimum", decision.reason);
        assertEquals("a hypothetical payout is not a score", -1, decision.scorePercent);
        assertEquals("⌊100 × 835 ÷ 1000⌋", 83, AreaScore.passThreshold(REPORT_RULES, offer));
    }

    @Test
    public void aPlusAmountBesideATotalThatMayPassIsReviewedNeverKept() {
        // Reported: +$1 beside $10.10 (7.5 mi) and beside $10.60 (5.3 mi). The sum passes, so the offer might.
        for (OfferSnapshot offer : new OfferSnapshot[] {
                plusOffer("+$1", "$10.10", "2 stops (7.5 mi) • 23 min"),
                plusOffer("+$1", "$10.60", "2 stops (5.3 mi) • 30 min")}) {
            OfferRule.Decision decision = evaluate(offer, REPORT_RULES);
            assertEquals(OfferRule.Result.REVIEW, decision.result);
            assertEquals("pay unclear beside a +$ amount", decision.reason);
            assertEquals(1000, decision.requiredCents);
            assertEquals(Integer.MAX_VALUE, AreaScore.passThreshold(REPORT_RULES, offer));
        }
    }

    @Test
    public void aPlusAmountDeclinesOnlyBelowTheExactSum() {
        OfferRule.Decision short1Cent = evaluate(plusOffer("+$1", "$8.99", "2 stops (5.0 mi) • 20 min"),
                REPORT_RULES);
        assertEquals(OfferRule.Result.DECLINE, short1Cent.result);
        assertEquals("pay at most $9.99 with its +$ amount; flat minimum", short1Cent.reason);
        OfferRule.Decision exact = evaluate(plusOffer("+$1", "$9.00", "2 stops (5.0 mi) • 20 min"), REPORT_RULES);
        assertEquals(OfferRule.Result.REVIEW, exact.result);
        assertEquals("pay unclear beside a +$ amount", exact.reason);
    }

    @Test
    public void aPlusAmountDeclinesWhenEvenTheSumMissesARate() {
        OfferRule.Decision decision = evaluate(plusOffer("+$1", "$10.10", "2 stops (12.0 mi) • 30 min"),
                REPORT_RULES);
        assertEquals(OfferRule.Result.DECLINE, decision.result);
        assertEquals(1200, decision.requiredCents);
        assertEquals("pay at most $11.10 with its +$ amount; dollars per mile", decision.reason);

        FilterSettings perHour = FilterSettings.of(true, 0, 0, 50, 3);
        OfferRule.Decision hours = evaluate(plusOffer("+$1", "$10.00", "2 stops (5.0 mi) • 30 min"), perHour);
        assertEquals(OfferRule.Result.DECLINE, hours.result);
        assertEquals(1500, hours.requiredCents);
        assertEquals("pay at most $11.00 with its +$ amount; dollars per hour", hours.reason);
    }

    @Test
    public void thePlusCeilingIsJudgedAtTheLowerOfTheBarAndTheMinimums() {
        // $8.35 against $10.00: it declines at 100% and under any bar from 84% up, never from a raised bar alone.
        OfferSnapshot offer = plusOffer("+$1", "$7.35", "2 stops (7.1 mi) • 23 min");
        assertEquals("⌊83500 ÷ 1000⌋", 83, AreaScore.passThreshold(REPORT_RULES, offer));
        OfferRule.Decision at84 = evaluate(offer, REPORT_RULES.withMinimumScalePercent(84));
        assertEquals(OfferRule.Result.DECLINE, at84.result);
        assertEquals("pay at most $8.35 with its +$ amount; 84% bar: flat minimum", at84.reason);
        assertEquals(840, at84.requiredCents);
        OfferRule.Decision at83 = evaluate(offer, REPORT_RULES.withMinimumScalePercent(83));
        assertEquals(OfferRule.Result.REVIEW, at83.result);
        assertEquals("pay unclear beside a +$ amount", at83.reason);
        // At 120% a ceiling that meets 100% of the minimums stays review: never stricter than the user's minimums.
        OfferSnapshot meets = plusOffer("+$1", "$9.00", "2 stops (5.0 mi) • 20 min");
        OfferRule.Decision at120 = evaluate(meets, REPORT_RULES.withMinimumScalePercent(120));
        assertEquals(OfferRule.Result.REVIEW, at120.result);
        assertEquals("pay unclear beside a +$ amount", at120.reason);
        assertEquals("the bar still sets what review shows as needed", 1200, at120.requiredCents);
        OfferRule.Decision short120 = evaluate(plusOffer("+$1", "$8.99", "2 stops (5.0 mi) • 20 min"),
                REPORT_RULES.withMinimumScalePercent(120));
        assertEquals(OfferRule.Result.DECLINE, short120.result);
        assertEquals("pay at most $9.99 with its +$ amount; flat minimum", short120.reason);
        assertEquals(1000, short120.requiredCents);
        // Never a pass at any bar.
        for (int bar = 1; bar <= 200; bar++) {
            assertFalse(evaluate(meets, REPORT_RULES.withMinimumScalePercent(bar)).result == OfferRule.Result.KEEP);
        }
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
            OfferRule.Decision decision = evaluate(parse(labels), REPORT_RULES);
            assertEquals(Arrays.toString(labels), OfferRule.Result.REVIEW, decision.result);
            assertEquals(Arrays.toString(labels), "pay not found", decision.reason);
        }
    }

    /** The user's rules at 0.4.41 as 0.5.0 keeps them: $10 (2 × $4.75 a stop is less), $2.00 a mile, $0.50 a minute. */
    private static final FilterSettings USER_RULES_0441 = FilterSettings.of(true, 1000, 200, 50, 3);

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
        OfferSnapshot offer = realPlusScreen("$5.75", "2 stops (3.4 mi) • 17 min");
        assertNull("pay itself stays unknown", offer.payCents);
        assertEquals(Integer.valueOf(675), offer.payAtMostCents);
        OfferRule.Decision decision = evaluate(offer, USER_RULES_0441);
        assertEquals(OfferRule.Result.DECLINE, decision.result);
        assertEquals("pay at most $6.75 with its +$ amount; flat minimum", decision.reason);
        assertEquals(1000, decision.requiredCents);
    }

    @Test
    public void theRealScreenWhoseSumMayPassIsReviewed() {
        OfferSnapshot offer = realPlusScreen("$9.00", "incl. tips", "2 stops (3.7 mi) • 17 min");
        assertEquals("$9.00 + $1", Integer.valueOf(1000), offer.payAtMostCents);
        OfferRule.Decision decision = evaluate(offer, USER_RULES_0441);
        assertEquals(OfferRule.Result.REVIEW, decision.result);
        assertEquals("pay unclear beside a +$ amount", decision.reason);
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
            assertEquals(Arrays.toString(labels), OfferRule.Result.DECLINE, evaluate(offer, REPORT_RULES).result);
        }
        // Anything else between them keeps them apart, as before.
        for (String[] labels : new String[][] {
                {"+$1", "Decline", "incl. tips", "$5.00", route},
                {"+$1", "Peak pay", "$5.00", route},
                {"+$1", "Decline", "5", "$5.00", route},
                {"+$1", "Not busy at all", "$5.00", route},
                {"+$1", "9:45 PM", "$5.00", route}}) {
            OfferRule.Decision decision = evaluate(parse(labels), REPORT_RULES);
            assertEquals(Arrays.toString(labels), OfferRule.Result.REVIEW, decision.result);
            assertEquals(Arrays.toString(labels), "pay not found", decision.reason);
        }
    }

    @Test
    public void aPlusAmountMattersOnlyToPayRules() {
        FilterSettings stopsOnly = FilterSettings.of(true, 0, 0, 0, 3);
        assertEquals(OfferRule.Result.KEEP,
                evaluate(plusOffer("+$1", "$5.00", "2 stops (7.1 mi) • 23 min"), stopsOnly).result);
        OfferRule.Decision tooMany = evaluate(plusOffer("+$1", "$5.00", "4 stops (7.1 mi) • 23 min"), stopsOnly);
        assertEquals(OfferRule.Result.DECLINE, tooMany.result);
        assertEquals("4 stops exceeds maximum 3", tooMany.reason);
    }

    @Test
    public void exactRoundingNeverAsksACentTooManyOrTooFew() {
        // 100 × 1.0301 × 0.97 = 99.9197¢: scaled before the one rounding, $1.00 passes; rounded first it would ask $1.01.
        FilterSettings perMile = FilterSettings.of(true, 0, 100, 0, 0);
        OfferSnapshot oneDollar = new OfferSnapshot(100, 1.0301, 10, 2);
        assertEquals(100, evaluate(oneDollar, perMile.withMinimumScalePercent(97)).requiredCents);
        assertEquals(OfferRule.Result.KEEP, evaluate(oneDollar, perMile.withMinimumScalePercent(97)).result);
        assertEquals(104, evaluate(oneDollar, perMile).requiredCents);
        // A route far past any pay saturates rather than overflowing, and declines.
        OfferRule.Decision huge = evaluate(new OfferSnapshot(100_000, 1e300, 10, 2), perMile);
        assertEquals(OfferRule.Result.DECLINE, huge.result);
        assertEquals(Long.MAX_VALUE, huge.requiredCents);
        assertEquals(0, huge.scorePercent);
        assertEquals(OfferRule.mileageCost(385, 2.1), evaluate(new OfferSnapshot(1, 2.1, 10, 2),
                FilterSettings.of(true, 0, 385, 0, 0)).requiredCents);
        assertEquals(809, OfferRule.mileageCost(385, 2.1));
        assertEquals(971, OfferRule.scaledCost(1001, 97));
        assertEquals(Long.MAX_VALUE, OfferRule.scaledCost(Long.MAX_VALUE, 50));
    }
}

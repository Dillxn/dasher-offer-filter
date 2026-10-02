package com.local.dasherfilter;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.*;

/** Exact boundaries for the global buffer, including independent hotspot proximity and fractional route costs. */
public final class MinimumScaleTest {
    private static FilterSettings even() {
        return new FilterSettings(true, 1000, 200, 50, 500, 0);
    }

    private static OfferSnapshot offer(int pay) {
        return new OfferSnapshot(pay, 5.0, 20, 2);
    }

    @Test public void ninetySevenFitnessCanPassWithoutChangingTheBaselineOrItsDisplayedScore() {
        FilterSettings original = even().withScoreByArea(true);
        FilterSettings buffered = original.withMinimumScalePercent(97);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(offer(970), original).result);
        OfferRule.Decision pass = OfferRule.evaluate(offer(970), buffered);
        assertEquals(OfferRule.Result.KEEP, pass.result);
        assertEquals(970, pass.requiredCents);
        assertEquals(97, pass.scorePercent);
        assertEquals("score 97% (needs 97%)", pass.reason);
        assertTrue(pass.summary().contains("$9.70 would score 97%"));
        assertArrayEquals(original.minimums(), buffered.minimums());
        assertEquals(1.0, AreaScore.score(AreaScore.floors(buffered, offer(1000)), 1000), 0);
    }

    @Test public void aRoundedNinetySevenNeverMasqueradesAsMeetingNinetySeven() {
        FilterSettings rules = even().withScoreByArea(true).withMinimumScalePercent(97);
        OfferRule.Decision fail = OfferRule.evaluate(offer(969), rules);
        assertEquals(OfferRule.Result.DECLINE, fail.result);
        assertEquals(96, fail.scorePercent);
        assertEquals("score 96% (needs 97%)", fail.reason);
        assertEquals(970, fail.requiredCents);
        assertEquals(96, AreaScore.percent(rules, offer(969)));
        assertEquals(97, AreaScore.percent(rules, offer(970)));
        assertEquals("raw baseline stays below 100", 99, AreaScore.percent(rules, offer(999)));
    }

    @Test public void scalingAboveOneHundredKeepsTheSameExactBoundaryAndRawScore() {
        FilterSettings rules = even().withScoreByArea(true).withMinimumScalePercent(125);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(offer(1249), rules).result);
        assertEquals(124, OfferRule.evaluate(offer(1249), rules).scorePercent);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(offer(1250), rules).result);
        assertEquals(125, OfferRule.evaluate(offer(1250), rules).scorePercent);
        assertEquals(1250, OfferRule.evaluate(offer(1250), rules).requiredCents);
    }

    @Test public void strictStillRequiresEveryAxisEvenWhenAreaFitnessWouldPass() {
        FilterSettings rules = new FilterSettings(true, 1000, 400, 0, 0, 0).withMinimumScalePercent(97);
        OfferSnapshot weakMileage = new OfferSnapshot(1500, 5.0, 20, 2);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(weakMileage, rules).result);
        assertEquals(1940, OfferRule.evaluate(weakMileage, rules).requiredCents);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(weakMileage, rules.withScoreByArea(true)).result);
    }

    @Test public void fixedMileageIsScaledBeforeTheOnlyCentRounding() {
        // 100 × 1.0301 × .97 = 99.9197 cents, so $1 passes. Scaling ceil(103.01)=104 would wrongly ask $1.01.
        FilterSettings rules = new FilterSettings(true, 0, 100, 0, 0, 0).withMinimumScalePercent(97);
        OfferSnapshot oneDollar = new OfferSnapshot(100, 1.0301, 1, 2);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(oneDollar, rules).result);
        assertEquals(100, OfferRule.evaluate(oneDollar, rules).requiredCents);
        assertEquals(100, OfferRule.evaluate(oneDollar, rules.withScoreByArea(true)).requiredCents);
        assertEquals(104, OfferRule.evaluate(oneDollar, rules.withMinimumScalePercent(100)).requiredCents);
    }

    @Test public void learnedPayoutFloorIsResolvedBeforeItsScaleAndNeverRewritten() {
        FilterSettings rules = new FilterSettings(true, 500, 0, 0, 0, 0, true, 1000)
                .withMinimumScalePercent(97);
        OfferRule.Decision below = OfferRule.evaluate(offer(970), rules);
        assertEquals(971, below.requiredCents); // ceil((accepted $10.00 + one cent) × .97)
        assertEquals(OfferRule.Result.DECLINE, below.result);
        assertFalse(below.reason.contains("must beat"));
        assertTrue(below.reason.contains("97%"));
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(offer(971), rules).result);
        assertEquals(1000, rules.lastAcceptedCents);
        assertEquals(500, rules.flatCents);
        assertEquals(485, OfferRule.evaluate(offer(971), rules.withAdaptive(false)).requiredCents);
    }

    @Test public void resolvedLearnedRateCeilAndDeclinePlusOneKeepTheirEstablishedMeaning() {
        AcceptedBest best = new AcceptedBest(1001, 3, 0, 0, 0, 0);
        DeclinedFloor declined = new DeclinedFloor(0, new AcceptedBest(1000, 3, 0, 0, 0, 0));
        FilterSettings rules = new FilterSettings(true, 0, 0, 10, 0, 0, true, 0, best, declined)
                .withMinimumScalePercent(97);
        OfferSnapshot shortTrip = new OfferSnapshot(648, 1.0, 2, 2);
        // Best's established floor ceil(1001*2/3)=668 beats declined floor floor(1000*2/3)+1=667.
        assertEquals(648, OfferRule.evaluate(shortTrip, rules).requiredCents);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(shortTrip, rules).result);
        assertEquals(668, OfferRule.evaluate(shortTrip, rules.withMinimumScalePercent(100)).requiredCents);
        assertSame(best, rules.best);
        assertSame(declined, rules.declined);
    }

    @Test public void hotspotMixedAreaRequiresExactNewSearchNotScalingTheOldRequiredPay() {
        FilterSettings pair = new FilterSettings(true, 1000, 0, 0, 0, 0).withHotspotProximity(100)
                .withScoreByArea(true).withMinimumScalePercent(97);
        OfferSnapshot atOneMile = offer(941).withFinalStopHotspotMiles(1.0);
        // sqrt(pay/1000 * 1) >= .97 needs ceil(940.9)=941; scaling the old $10 ask would wrongly need970.
        assertEquals(941, OfferRule.evaluate(atOneMile, pair).requiredCents);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(atOneMile, pair).result);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(offer(940).withFinalStopHotspotMiles(1.0), pair).result);
        FilterSettings five = even().withHotspotProximity(100).withScoreByArea(true).withMinimumScalePercent(97);
        // Five spokes have two pay×hotspot terms and three pay² terms: (2r + 3r²)/5 >= .9409.
        assertEquals(963, OfferRule.evaluate(atOneMile, five).requiredCents);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(offer(963).withFinalStopHotspotMiles(1.0), five).result);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(offer(962).withFinalStopHotspotMiles(1.0), five).result);
    }

    @Test public void loneHotspotHasAnExactScaledBoundaryAndNoInventedPayRequirement() {
        FilterSettings rules = new FilterSettings(true, 0, 0, 0, 0, 0).withHotspotProximity(100)
                .withMinimumScalePercent(80);
        OfferSnapshot boundary = offer(1).withFinalStopHotspotMiles(1.25); // 1/1.25 = .8 exactly.
        for (boolean area : new boolean[] {false, true}) {
            FilterSettings mode = rules.withScoreByArea(area);
            assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(boundary, mode).result);
            assertEquals(OfferRule.Result.DECLINE,
                    OfferRule.evaluate(boundary.withFinalStopHotspotMiles(1.2501), mode).result);
            assertEquals(0, OfferRule.evaluate(boundary, mode).requiredCents);
            assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(offer(1000), mode).result);
        }
    }

    @Test public void zeroHotspotRemainsTheExactReciprocalLimit() {
        FilterSettings rules = even().withHotspotProximity(100).withScoreByArea(true).withMinimumScalePercent(200);
        OfferSnapshot zero = offer(0).withFinalStopHotspotMiles(0.0);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(zero, rules).result);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(offer(1).withFinalStopHotspotMiles(0.0), rules).result);
        assertEquals(1, OfferRule.evaluate(zero, rules).requiredCents);
    }

    @Test public void unknownRemainsReviewAndMaxStopsDoesNotScale() {
        for (boolean area : new boolean[] {false, true}) {
            FilterSettings rules = even().withScoreByArea(area).withMaxStops(3).withMinimumScalePercent(1);
            assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(new OfferSnapshot(1000, null, 20, 2), rules).result);
            assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(new OfferSnapshot(1000, 5.0, 20, 4), rules).result);
            assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(new OfferSnapshot(null, 5.0, 20, 2), rules).result);
        }
    }

    @Test public void aPayCeilingUsesScaledFixedRulesOnlyAndNeverPasses() {
        for (boolean area : new boolean[] {false, true}) {
            FilterSettings rules = new FilterSettings(true, 1000, 0, 0, 0, 0, true, 2000)
                    .withScoreByArea(area).withMinimumScalePercent(97);
            assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(new OfferSnapshot(null, 5.0, 20, 2, 970), rules).result);
            OfferRule.Decision below = OfferRule.evaluate(new OfferSnapshot(null, 5.0, 20, 2, 969), rules);
            assertEquals(OfferRule.Result.DECLINE, below.result);
            assertEquals(970, below.requiredCents);
        }
    }

    @Test public void addOnsRemainStrictButTheirCombinedAndExplicitMarginalFloorsScaleTogether() {
        FilterSettings rules = new FilterSettings(true, 1000, 200, 0, 0, 0, true, 9999)
                .withScoreByArea(true).withMinimumScalePercent(97);
        AddOnOffer near = AddOnOffer.parse(new OfferSnapshot(776, 4.0, 20, 2),
                Arrays.asList("Add to route", "+$1.94", "+1 mi", "+5 min", "+1 stop"));
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluateAddOn(near, rules).result);
        assertEquals(194, OfferRule.evaluateAddOn(near, rules).requiredCents);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluateAddOn(near, rules.withMinimumScalePercent(100)).result);
        AddOnOffer weakIncrement = AddOnOffer.parse(new OfferSnapshot(1000, 4.0, 20, 2),
                Arrays.asList("Add to route", "+$1.93", "+1 mi", "+5 min", "+1 stop"));
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluateAddOn(weakIncrement, rules).result);
        assertEquals(194, OfferRule.evaluateAddOn(weakIncrement, rules).requiredCents);
    }

    @Test public void hundredPercentKeepsTheExistingMonetaryAndHotspotAnswers() {
        for (boolean area : new boolean[] {false, true}) {
            for (int proximity : new int[] {0, 40, 200}) {
                FilterSettings rules = even().withScoreByArea(area).withHotspotProximity(proximity);
                for (int pay : new int[] {0, 1, 969, 970, 999, 1000, 1001, 2000}) {
                    OfferSnapshot facts = offer(pay).withFinalStopHotspotMiles(1.0);
                    OfferRule.Decision expected = OfferRule.evaluate(facts, rules);
                    OfferRule.Decision actual = OfferRule.evaluate(facts, rules.withMinimumScalePercent(100));
                    assertEquals(expected.result, actual.result);
                    assertEquals(expected.requiredCents, actual.requiredCents);
                    assertEquals(expected.reason, actual.reason);
                    assertEquals(expected.summary(), actual.summary());
                    assertEquals(expected.scorePercent, actual.scorePercent);
                }
            }
        }
    }

    @Test public void scaleRangeDoesNotTurnAConfigurationWithoutRulesIntoAnActiveRule() {
        FilterSettings none = new FilterSettings(true, 0, 0, 0, 0, 0);
        assertEquals(100, none.minimumScalePercent);
        assertEquals(1, none.withMinimumScalePercent(Integer.MIN_VALUE).minimumScalePercent);
        assertEquals(200, none.withMinimumScalePercent(Integer.MAX_VALUE).minimumScalePercent);
        assertFalse(none.withMinimumScalePercent(97).hasAnyRule());
        assertEquals("No rules set", none.withMinimumScalePercent(97).describe());
        assertTrue(even().withMinimumScalePercent(97).describe().contains("97% of the following baselines"));
    }
}

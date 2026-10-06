package com.local.dasherfilter;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Exact boundaries of the bar (the old global minimums scale): one scaling of R100 and one rounding up, the same
 * boundary for the score, max stops never scaled, unknown still review, and the "+$" ceiling never stricter than the
 * minimums themselves.
 */
public final class MinimumScaleTest {
    /** Each minimum asks $10.00 of 5 mi and 20 min. */
    private static FilterSettings even() {
        return FilterSettings.of(true, 1000, 200, 50, 0);
    }

    private static OfferSnapshot offer(int pay) {
        return new OfferSnapshot(pay, 5.0, 20, 2);
    }

    @Test public void ninetySevenPassesBelowTheMinimumsWithoutChangingThemOrTheScore() {
        FilterSettings original = even();
        FilterSettings buffered = original.withMinimumScalePercent(97);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(offer(970), original).result);
        OfferRule.Decision pass = OfferRule.evaluate(offer(970), buffered);
        assertEquals(OfferRule.Result.KEEP, pass.result);
        assertEquals(970, pass.requiredCents);
        assertEquals(97, pass.scorePercent);
        assertEquals("below your minimums; passes the 97% bar", pass.reason);
        assertTrue(pass.belowMinimums);
        assertEquals("KEEP: required at least $9.70 (below your minimums; passes the 97% bar)", pass.summary());
        assertArrayEquals(original.minimums(), buffered.minimums());
    }

    @Test public void aCentShortNeverMasqueradesAsMeetingTheBar() {
        FilterSettings rules = even().withMinimumScalePercent(97);
        OfferRule.Decision fail = OfferRule.evaluate(offer(969), rules);
        assertEquals(OfferRule.Result.DECLINE, fail.result);
        assertEquals(96, fail.scorePercent);
        assertEquals("97% bar: flat minimum", fail.reason);
        assertEquals(970, fail.requiredCents);
        assertEquals(96, AreaScore.scorePercent(rules, offer(969)));
        assertEquals(97, AreaScore.scorePercent(rules, offer(970)));
        assertEquals("the score is against the minimums, not the bar", 99, AreaScore.scorePercent(rules, offer(999)));
    }

    @Test public void barsAboveOneHundredKeepTheSameExactBoundaryAndScore() {
        FilterSettings rules = even().withMinimumScalePercent(125);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(offer(1249), rules).result);
        assertEquals(124, OfferRule.evaluate(offer(1249), rules).scorePercent);
        assertEquals("125% bar: flat minimum", OfferRule.evaluate(offer(1249), rules).reason);
        OfferRule.Decision keep = OfferRule.evaluate(offer(1250), rules);
        assertEquals(OfferRule.Result.KEEP, keep.result);
        assertEquals(125, keep.scorePercent);
        assertEquals(1250, keep.requiredCents);
        assertEquals("meets the 125% bar", keep.reason);
        assertFalse(keep.belowMinimums);
    }

    @Test public void everyMinimumIsStillMetOnItsOwnAtAnyBar() {
        // Per mile asks $20.00 of 5 mi; a 97% bar asks $19.40 however well pay and per hour do.
        FilterSettings rules = FilterSettings.of(true, 1000, 400, 0, 0).withMinimumScalePercent(97);
        OfferSnapshot weakMileage = new OfferSnapshot(1500, 5.0, 20, 2);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(weakMileage, rules).result);
        assertEquals(1940, OfferRule.evaluate(weakMileage, rules).requiredCents);
        assertEquals("97% bar: dollars per mile", OfferRule.evaluate(weakMileage, rules).reason);
    }

    @Test public void fixedMileageIsScaledBeforeTheOnlyCentRounding() {
        // 100 × 1.0301 × .97 = 99.9197 cents, so $1 passes. Scaling ceil(103.01)=104 would wrongly ask $1.01.
        FilterSettings rules = FilterSettings.of(true, 0, 100, 0, 0).withMinimumScalePercent(97);
        OfferSnapshot oneDollar = new OfferSnapshot(100, 1.0301, 10, 2);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(oneDollar, rules).result);
        assertEquals(100, OfferRule.evaluate(oneDollar, rules).requiredCents);
        assertEquals(104, OfferRule.evaluate(oneDollar, rules.withMinimumScalePercent(100)).requiredCents);
    }

    @Test public void unknownRemainsReviewAndMaxStopsDoesNotScale() {
        FilterSettings rules = even().withMaxStops(3).withMinimumScalePercent(1);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(new OfferSnapshot(1000, null, 20, 2), rules).result);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(new OfferSnapshot(1000, 5.0, 20, 4), rules).result);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(new OfferSnapshot(null, 5.0, 20, 2), rules).result);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(new OfferSnapshot(1000, 5.0, 20, null), rules).result);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(new OfferSnapshot(10, 5.0, 20, 3), rules).result);
        assertEquals(10, OfferRule.evaluate(new OfferSnapshot(10, 5.0, 20, 3), rules).requiredCents);
    }

    @Test public void aPayCeilingIsJudgedAtTheLowerOfTheBarAndOneHundredAndNeverPasses() {
        FilterSettings at97 = FilterSettings.of(true, 1000, 0, 0, 0).withMinimumScalePercent(97);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(new OfferSnapshot(null, 5.0, 20, 2, 970), at97).result);
        OfferRule.Decision below = OfferRule.evaluate(new OfferSnapshot(null, 5.0, 20, 2, 969), at97);
        assertEquals(OfferRule.Result.DECLINE, below.result);
        assertEquals(970, below.requiredCents);
        assertEquals("pay at most $9.69 with its +$ amount; 97% bar: flat minimum", below.reason);
        // A raised bar never makes the ceiling stricter than the minimums.
        FilterSettings at150 = at97.withMinimumScalePercent(150);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(new OfferSnapshot(null, 5.0, 20, 2, 1000), at150)
                .result);
        OfferRule.Decision short1 = OfferRule.evaluate(new OfferSnapshot(null, 5.0, 20, 2, 999), at150);
        assertEquals(OfferRule.Result.DECLINE, short1.result);
        assertEquals(1000, short1.requiredCents);
        assertEquals("pay at most $9.99 with its +$ amount; flat minimum", short1.reason);
    }

    @Test public void addOnsKeepTheTwoPartRuleWithBothPartsScaledTogether() {
        FilterSettings rules = FilterSettings.of(true, 1000, 200, 0, 0).withMinimumScalePercent(97);
        AddOnOffer near = AddOnOffer.parse(new OfferSnapshot(776, 4.0, 20, 2),
                Arrays.asList("Add to route", "+$1.94", "+1 mi", "+5 min", "+1 stop"));
        OfferRule.Decision kept = OfferRule.evaluateAddOn(near, rules);
        assertEquals(OfferRule.Result.KEEP, kept.result);
        assertEquals(194, kept.requiredCents);
        assertTrue("$9.70 for the whole route meets only the 97% bar", kept.belowMinimums);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluateAddOn(near, rules.withMinimumScalePercent(100)).result);
        AddOnOffer weakIncrement = AddOnOffer.parse(new OfferSnapshot(1000, 4.0, 20, 2),
                Arrays.asList("Add to route", "+$1.93", "+1 mi", "+5 min", "+1 stop"));
        OfferRule.Decision declined = OfferRule.evaluateAddOn(weakIncrement, rules);
        assertEquals(OfferRule.Result.DECLINE, declined.result);
        assertEquals(194, declined.requiredCents);
        assertEquals("97% bar: add-on marginal economics", declined.reason);
    }

    @Test public void oneHundredPercentIsExactlyTheMinimums() {
        for (int maxStops : new int[] {0, 3}) {
            FilterSettings rules = even().withMaxStops(maxStops);
            for (int pay : new int[] {0, 1, 969, 970, 999, 1000, 1001, 2000}) {
                for (OfferSnapshot facts : new OfferSnapshot[] {offer(pay), new OfferSnapshot(pay, null, 20, 4),
                        new OfferSnapshot(null, 5.0, 20, 2, pay)}) {
                    OfferRule.Decision expected = OfferRule.evaluate(facts, rules);
                    OfferRule.Decision actual = OfferRule.evaluate(facts, rules.withMinimumScalePercent(100));
                    assertEquals(expected.result, actual.result);
                    assertEquals(expected.requiredCents, actual.requiredCents);
                    assertEquals(expected.reason, actual.reason);
                    assertEquals(expected.summary(), actual.summary());
                    assertEquals(expected.scorePercent, actual.scorePercent);
                    assertFalse(actual.belowMinimums);
                    assertFalse(actual.reason, actual.reason.contains("% bar"));
                }
            }
        }
    }

    @Test public void theBarRangeDoesNotTurnRulesWithoutAMinimumIntoARule() {
        FilterSettings none = FilterSettings.of(true, 0, 0, 0, 0);
        assertEquals(100, none.minimumScalePercent);
        assertEquals(1, none.withMinimumScalePercent(Integer.MIN_VALUE).minimumScalePercent);
        assertEquals(200, none.withMinimumScalePercent(Integer.MAX_VALUE).minimumScalePercent);
        assertFalse(none.withMinimumScalePercent(97).hasAnyRule());
        assertEquals("No rules set", none.withMinimumScalePercent(97).describe());
        assertTrue(even().withMinimumScalePercent(97).describe().endsWith(" · bar 97%"));
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(offer(1), none.withMinimumScalePercent(200)).result);
    }
}

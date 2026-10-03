package com.local.dasherfilter;

import java.math.BigDecimal;
import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.*;

/** Item economics share exact floors, while strict constraints and compensating area remain separate policies. */
public final class ItemScoreTest {
    private static FilterSettings onlyItems(int cents) {
        return new FilterSettings(true, 0, 0, 0, 0, 0).withPerItem(cents);
    }

    private static OfferSnapshot offer(Integer pay, Integer items, boolean applicable) {
        return new OfferSnapshot(pay, 5.0, 20, 2).withItems(items, applicable);
    }

    @Test public void itemFloorIsAnIndependentMonetaryRequirementScaledExactlyOnce() {
        for (boolean area : new boolean[] {false, true}) {
            FilterSettings rules = onlyItems(37).withScoreByArea(area).withMinimumScalePercent(97);
            // 37 cents × 3 items × 97% = 107.67 cents; $1.08 is the first passing payout.
            OfferRule.Decision fail = OfferRule.evaluate(offer(107, 3, true), rules);
            OfferRule.Decision pass = OfferRule.evaluate(offer(108, 3, true), rules);
            assertEquals(OfferRule.Result.DECLINE, fail.result);
            assertEquals(OfferRule.Result.KEEP, pass.result);
            assertEquals(108, pass.requiredCents);
            assertEquals(96, fail.scorePercent);
            assertEquals(97, pass.scorePercent);
            assertEquals(111, AreaScore.floors(rules, offer(108, 3, true)).wholeCents(AreaScore.ITEM));
        }
    }

    @Test public void strongerOtherSpokesCanCompensateForItemsOnlyInAreaMode() {
        FilterSettings rules = new FilterSettings(true, 1000, 0, 0, 0, 0).withPerItem(200);
        OfferSnapshot shopping = offer(1500, 10, true);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(shopping, rules).result);
        assertEquals(2000, OfferRule.evaluate(shopping, rules).requiredCents);
        OfferRule.Decision area = OfferRule.evaluate(shopping, rules.withScoreByArea(true));
        assertEquals(OfferRule.Result.KEEP, area.result);
        assertEquals(1415, area.requiredCents); // ceil(sqrt(1000 × 2000)).
        assertEquals(106, area.scorePercent);
        assertEquals(OfferRule.Result.DECLINE,
                OfferRule.evaluate(offer(1414, 10, true), rules.withScoreByArea(true)).result);
    }

    @Test public void absentEvidenceExemptsTheItemCountButNeverInventsUnreadPay() {
        for (boolean area : new boolean[] {false, true}) {
            FilterSettings rules = onlyItems(100).withScoreByArea(area);
            OfferSnapshot noItemEvidence = offer(500, null, false);
            AreaScore.Floors floors = AreaScore.floors(rules, noItemEvidence);
            assertFalse(floors.active[AreaScore.ITEM]);
            assertNull(floors.fixedCents[AreaScore.ITEM]);
            assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(noItemEvidence, rules).result);
            assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(offer(null, null, false), rules).result);
            assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(offer(500, null, true), rules).result);
            assertEquals(-1, OfferRule.evaluate(offer(500, null, true), rules).scorePercent);
        }
    }

    @Test public void unreadItemsCannotCompleteAreaButKnownStrictFailureStillDeclines() {
        FilterSettings rules = new FilterSettings(true, 1000, 0, 0, 0, 0).withPerItem(100);
        OfferSnapshot incomplete = offer(500, null, true);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(incomplete, rules).result);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(incomplete, rules.withScoreByArea(true)).result);
    }

    @Test public void independentHotspotAndItemMoneyUseTheSameExactMixedBoundary() {
        FilterSettings rules = onlyItems(100).withHotspotProximity(100).withMinimumScalePercent(97);
        AreaScore.Floors f = AreaScore.floors(rules, offer(941, 10, true).withFinalStopHotspotMiles(1.0));
        assertEquals(941, AreaScore.requiredPay(f, 97)); // sqrt(pay / 1000) >= .97.
        assertFalse(AreaScore.reaches(f, 940, 97));
        assertTrue(AreaScore.reaches(f, 941, 97));
        assertEquals(1, AreaScore.ratios(f, 1)[AreaScore.HOTSPOT], 0);
        assertEquals(2, AreaScore.ratios(f, 2000)[AreaScore.ITEM], 0);
        AreaScore.Floors atHotspot = AreaScore.floors(rules,
                offer(0, 10, true).withFinalStopHotspotMiles(0.0));
        assertEquals(1, AreaScore.requiredPay(atHotspot, 200));
        assertFalse(AreaScore.reaches(atHotspot, 0, 200));
    }

    @Test public void everySixAxisSubsetHasUnitBaselineAndMonotonePayout() {
        for (int mask = 1; mask < (1 << AreaScore.AXES); mask++) {
            FilterSettings rules = new FilterSettings(true, (mask & 1) == 0 ? 0 : 1000,
                    (mask & 2) == 0 ? 0 : 200, (mask & 4) == 0 ? 0 : 50,
                    (mask & 8) == 0 ? 0 : 500, 0).withHotspotProximity((mask & 16) == 0 ? 0 : 100)
                    .withPerItem((mask & 32) == 0 ? 0 : 100);
            AreaScore.Floors f = AreaScore.floors(rules, offer(1000, 10, true).withFinalStopHotspotMiles(1.0));
            assertEquals("mask " + mask, 1, AreaScore.score(f, 1000), 0);
            assertTrue("mask " + mask, AreaScore.reaches(f, 1000));
            assertEquals("mask " + mask, f.needsPay() ? 1000 : 0, AreaScore.requiredPay(f));
            assertTrue("mask " + mask, AreaScore.score(f, 2000) >= AreaScore.score(f, 1000));
        }
    }

    @Test public void itemOffPreservesExistingScoresAndDecisionsForEveryApplicabilityState() {
        FilterSettings old = new FilterSettings(true, 1300, 385, 41, 475, 3).withHotspotProximity(80);
        OfferSnapshot base = new OfferSnapshot(1500, 6.0, 25, 2).withFinalStopHotspotMiles(0.5);
        for (boolean area : new boolean[] {false, true}) {
            FilterSettings rules = old.withScoreByArea(area);
            OfferRule.Decision expected = OfferRule.evaluate(base, rules);
            for (OfferSnapshot next : new OfferSnapshot[] {base.withItems(null, false), base.withItems(null, true),
                    base.withItems(1, true), base.withItems(200, true)}) {
                OfferRule.Decision actual = OfferRule.evaluate(next, rules);
                assertEquals(expected.result, actual.result);
                assertEquals(expected.requiredCents, actual.requiredCents);
                assertEquals(expected.scorePercent, actual.scorePercent);
                assertEquals(expected.reason, actual.reason);
            }
        }
    }

    @Test public void sharedComponentsKeepExactFixedCostAndEstablishedLearnedRounding() {
        AcceptedBest best = new AcceptedBest(1001, 3, 0, 0, 0, 0);
        DeclinedFloor declined = new DeclinedFloor(0, new AcceptedBest(1000, 3, 0, 0, 0, 0));
        FilterSettings rules = new FilterSettings(true, 0, 100, 10, 0, 0, true, 0, best, declined)
                .withMinimumScalePercent(97);
        OfferSnapshot facts = new OfferSnapshot(648, 1.0301, 2, 2);
        AreaScore.Floors f = AreaScore.floors(rules, facts);
        assertEquals(new BigDecimal("103.0100"), f.fixedCents[AreaScore.MILE]);
        assertEquals(100, f.scaledCents(AreaScore.MILE, 97, true));
        assertEquals(104, f.wholeCents(AreaScore.MILE));
        assertEquals(BigDecimal.valueOf(668), f.acceptedCents[AreaScore.MINUTE]);
        assertEquals(BigDecimal.valueOf(667), f.declinedCents[AreaScore.MINUTE]);
        assertEquals(648, f.scaledCents(AreaScore.MINUTE, 97, false));
        assertEquals(648, OfferRule.evaluate(facts, rules).requiredCents);
        assertEquals(AreaScore.fixedFloor(AreaScore.MILE, 100, facts), f.fixedCents[AreaScore.MILE]);
        AreaScore.Floors off = AreaScore.floors(rules.withAdaptive(false), facts);
        assertEquals(f.acceptedCents[AreaScore.MINUTE], off.acceptedCents[AreaScore.MINUTE]);
        assertEquals(f.declinedCents[AreaScore.MINUTE], off.declinedCents[AreaScore.MINUTE]);
        assertEquals(BigDecimal.valueOf(20), off.cents[AreaScore.MINUTE]);
        assertEquals(20, off.wholeCents(AreaScore.MINUTE));
    }

    @Test public void itemRuleDoesNotCreateOrChangeAnyAdaptiveLesson() {
        FilterSettings old = new FilterSettings(true, 500, 140, 20, 100, 0, true, 0);
        OfferSnapshot accepted = offer(1500, 100, true);
        DeclinedFloor before = DeclinedFloor.raisedBy(old, accepted);
        DeclinedFloor after = DeclinedFloor.raisedBy(old.withPerItem(15), accepted);
        assertEquals(before.summary(), after.summary());
        assertEquals(before.payCents, after.payCents);
        assertEquals(before.rates.summary(), after.rates.summary());
    }

    @Test public void payCeilingJudgesFixedItemFloorButNeverPasses() {
        for (boolean area : new boolean[] {false, true}) {
            FilterSettings rules = new FilterSettings(true, 0, 0, 0, 0, 0, true, 10000)
                    .withPerItem(100).withScoreByArea(area);
            assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(
                    new OfferSnapshot(null, 5.0, 20, 2, 999).withItems(10, true), rules).result);
            assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(
                    new OfferSnapshot(null, 5.0, 20, 2, 1000).withItems(10, true), rules).result);
        }
    }

    @Test public void addOnChecksTheCombinedBasketAndExplicitIncrementWithoutLearning() {
        FilterSettings rules = onlyItems(100).withScoreByArea(true);
        OfferSnapshot active = offer(2000, 10, true);
        AddOnOffer pass = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$5.00", "+5 items"));
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluateAddOn(pass, rules).result);
        assertEquals(500, OfferRule.evaluateAddOn(pass, rules).requiredCents);
        AddOnOffer fail = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$4.99", "+5 items"));
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluateAddOn(fail, rules).result);
        assertEquals(500, OfferRule.evaluateAddOn(fail, rules).requiredCents);
        AddOnOffer unknown = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$5.00", "Shop and deliver"));
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluateAddOn(unknown, rules).result);
    }

    @Test public void existingZeroRouteValidityAndOneAddedStopRemainUnchanged() {
        FilterSettings rate = new FilterSettings(true, 0, 100, 10, 0, 0);
        OfferSnapshot zero = new OfferSnapshot(100, 0.0, 0, 2);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(zero, rate).result);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(zero, rate.withScoreByArea(true)).result);
        AreaScore.Floors increment = AreaScore.incrementalFloors(
                new FilterSettings(true, 1000, 0, 0, 300, 0, true, 10000),
                new OfferSnapshot(300, null, null, 1));
        assertFalse(increment.active[AreaScore.PAY]);
        assertEquals(300, increment.scaledCents(AreaScore.STOP, 100, true));
        assertNull(increment.acceptedCents[AreaScore.PAY]);
    }
}

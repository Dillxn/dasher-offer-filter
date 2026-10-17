package com.local.dasherfilter;

import java.math.BigDecimal;
import org.junit.Test;
import static org.junit.Assert.*;

/** Fresh 0.6 exact arithmetic, model separation and fixed-topology safety contracts. */
public final class AreaLearningScoreTest {
    private static final FilterSettings SAVED = FilterSettings.of(true, 250, 100, 25, 3);
    private static final OfferSnapshot OFFER = new OfferSnapshot(800, 10.0, 20, 2);
    /** {@code flat}: what a single order (2 stops, the pickup included) asks of pay per stop, an even number. */
    private static FilterSettings area(int flat, int mile, int minute) {
        return FilterSettings.of(true, flat / 2, mile, minute, 3).withScoreByArea(true);
    }
    private static void boundary(FilterSettings rules, double miles, int minutes, int bar, long expected) {
        OfferSnapshot at = new OfferSnapshot((int) expected, miles, minutes, 2);
        OfferSnapshot below = new OfferSnapshot((int) expected - 1, miles, minutes, 2);
        rules = rules.withMinimumScalePercent(bar);
        assertEquals(expected, AreaScore.requiredPay(rules, at, bar));
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(at, rules).result);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(below, rules).result);
        assertTrue(AreaScore.scorePercent(rules, at) >= bar);
        assertTrue(AreaScore.scorePercent(rules, below) < bar);
    }
    @Test public void threeCurrentAnglesCompensateAndApplyBarOnce() {
        FilterSettings area = SAVED.withScoreByArea(true);
        assertEquals(1000, OfferRule.evaluate(OFFER, SAVED).requiredCents);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(OFFER, SAVED).result);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(OFFER, area).result);
        assertFalse(OfferRule.evaluate(OFFER, area).belowMinimums);
        boundary(area, 10, 20, 100, 708);
        boundary(area, 10, 20, 120, 849);
        assertEquals(113, AreaScore.scorePercent(area, OFFER));
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(OFFER, area.withMinimumScalePercent(120)).result);
    }
    @Test public void oneAndEveryTwoAxisTopologyHaveExactDegenerateFallbacks() {
        boundary(area(502, 0, 0), 10, 20, 120, 603);
        boundary(area(500, 100, 0), 10, 20, 100, 708);
        boundary(area(0, 100, 25), 10, 20, 100, 708);
        // PAY and MINUTE stand opposite; the two-spoke fallback still uses their geometric mean.
        boundary(area(500, 0, 50), 10, 20, 120, 849);
        boundary(area(500, 50, 25), 10, 20, 100, 500);
        boundary(area(500, 50, 25), 10, 20, 150, 750);
    }
    @Test public void rationalAcceptedRatesAreNotRoundedBeforeTheBarOrAggregation() {
        FilterSettings learned = FilterSettings.of(true, 0, 0, 0, 0)
                .withAccepted(new AcceptedFloors(0, 1000, 3.0, 1000, 30)).withAcceptanceLearning(true);
        // Both ask 1000/3 cents at this route. 60% is exactly 200, not ceil(333.333)*.6 = 201.
        OfferSnapshot route = new OfferSnapshot(200, 1.0, 10, 2);
        assertEquals(200, AreaScore.requiredPay(learned, route, 60));
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(route, learned.withMinimumScalePercent(60)).result);
        boundary(learned.withScoreByArea(true), 1, 10, 60, 200);
        assertEquals(333, AreaScore.passThreshold(learned, new OfferSnapshot(1110, 1.0, 10, 2)));
    }
    @Test public void missingAreaFactsCannotTurnAKnownStrictShortfallIntoDecline() {
        OfferSnapshot partial = new OfferSnapshot(1, null, 20, 2);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(partial, SAVED).result);
        FilterSettings area = SAVED.withScoreByArea(true);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(partial, area).result);
        assertEquals(-1, AreaScore.requiredPay(area, partial, 100));
        assertEquals(Integer.MAX_VALUE, AreaScore.passThreshold(area, partial));
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(new OfferSnapshot(1, null, 20, 4), area).result);
    }
    @Test public void knownZeroIsExplicitDegenerateAreaNotInventedInfiniteFitness() {
        OfferSnapshot zero = new OfferSnapshot(800, 0.0, 20, 2);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(zero, SAVED).result);
        OfferRule.Decision result = OfferRule.evaluate(zero, SAVED.withScoreByArea(true));
        assertEquals(OfferRule.Result.REVIEW, result.result);
        assertTrue(result.reason, result.reason.contains("zero route"));
        assertEquals(Integer.MAX_VALUE, AreaScore.passThreshold(SAVED.withScoreByArea(true), zero));
        assertEquals(-1, result.scorePercent);
        assertEquals(0, AreaScore.requiredPay(area(0, 100, 0), zero, 100));
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(zero, area(0, 100, 0)).result);
    }
    @Test public void learnedProofActivatesUnsetAxesWithoutMovingSavedKnobs() {
        FilterSettings before = FilterSettings.of(true, 500, 0, 0, 0).withScoreByArea(true);
        FilterSettings after = before.withAccepted(new AcceptedFloors(1000, 2, 1000, 20, 1000, 40))
                .withAcceptanceLearning(true);
        AreaScore.Floors resolved = AreaScore.floors(after, OFFER);
        assertArrayEquals(new int[] {500, 0, 0, 0, 0, 0}, after.minimums());
        assertTrue(resolved.active[AreaScore.MILE]);
        assertTrue(resolved.active[AreaScore.MINUTE]);
        assertEquals(1001, resolved.effectiveCents()[AreaScore.PAY].intValueExact());
        // Activation changes normalized topology. It is explicitly distinct from fixed-axis monotonicity.
        assertTrue(AreaScore.requiredPay(after, OFFER, 100) < AreaScore.requiredPay(before, OFFER, 100));
        assertTrue(AreaScore.effectiveFloorsRaised(before, after, OFFER));
        assertEquals(1000, AreaScore.requiredPay(after.withAcceptanceLearning(false), OFFER, 100));
        assertTrue(FilterSettings.of(true, 0, 0, 0, 0).withAcceptanceLearning(true)
                .withAccepted(new AcceptedFloors(1, 0, 0, 0, 0)).hasMonetaryRule());
    }
    @Test public void fixedAxesFloorsBarAndStopLimitCannotLoosenRequiredDecisions() {
        OfferSnapshot route = new OfferSnapshot(800, 10.0, 20, 2);
        for (int mask = 1; mask < 8; mask++) for (boolean area : new boolean[] {false, true}) {
            FilterSettings rules = FilterSettings.of(true, (mask & 1) == 0 ? 0 : 500,
                    (mask & 2) == 0 ? 0 : 100, (mask & 4) == 0 ? 0 : 25, 3).withScoreByArea(area);
            long previous = -1;
            for (int bar = 50; bar <= 150; bar++) {
                long required = AreaScore.requiredPay(rules, route, bar);
                assertTrue(required >= previous); previous = required;
                int[] minima = rules.minimums();
                for (int axis = 0; axis < 3; axis++) if (minima[axis] > 0) {
                    int[] raised = minima.clone(); raised[axis]++;
                    assertTrue(AreaScore.requiredPay(rules.withMinimums(raised), route, bar) >= required);
                }
                assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(route,
                        rules.withMinimumScalePercent(bar).withMaxStops(1)).result);
            }
        }
        FilterSettings learned = SAVED.withScoreByArea(true).withAcceptanceLearning(true)
                .withAccepted(new AcceptedFloors(500, 1000, 10, 500, 20));
        for (AcceptedFloors higher : new AcceptedFloors[] {new AcceptedFloors(501,1000,10,500,20),
                new AcceptedFloors(500,1001,10,500,20), new AcceptedFloors(500,1000,10,501,20)}) {
            assertTrue(AreaScore.requiredPay(learned.withAccepted(higher), route, 100)
                    >= AreaScore.requiredPay(learned, route, 100));
        }
    }
    @Test public void thresholdExactlyMatchesBothModelsIncludingUnreadAndCeilingFacts() {
        OfferSnapshot[] offers = {OFFER, new OfferSnapshot(1, null, 20, 2),
                new OfferSnapshot(800, 0.0, 20, 2), new OfferSnapshot(null, 10.0, 20, 2, 700),
                new OfferSnapshot(null, null, 20, 2, 1), new OfferSnapshot(null, 10.0, 20, 2, 1500),
                new OfferSnapshot(800, 10.0, 20, 4), new OfferSnapshot(800, 10.0, 20, null)};
        for (boolean area : new boolean[] {false, true}) for (OfferSnapshot offer : offers) {
            FilterSettings rules = SAVED.withScoreByArea(area).withAcceptanceLearning(true)
                    .withAccepted(new AcceptedFloors(500, 1100, 10, 1100, 30));
            int threshold = AreaScore.passThreshold(rules, offer);
            for (int bar = 1; bar <= 200; bar++) assertEquals("area="+area+" bar="+bar+" "+offer.fingerprint(),
                    threshold >= bar, OfferRule.evaluate(offer, rules.withMinimumScalePercent(bar)).result
                            != OfferRule.Result.DECLINE);
        }
    }
    @Test public void autoAcceptRemainsStrictAtOneHundredOrHigherWithResolvedLearning() {
        FilterSettings area = SAVED.withScoreByArea(true);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(OFFER, area).result);
        assertFalse(AutoAccept.eligible(OFFER, area, true, false, false, 30));
        OfferSnapshot meets = new OfferSnapshot(1000, 10.0, 20, 2);
        assertTrue(AutoAccept.eligible(meets, area.withMinimumScalePercent(50), true, false, false, 30));
        assertFalse(AutoAccept.eligible(meets, area.withMinimumScalePercent(120), true, false, false, 30));
        assertFalse(AutoAccept.eligible(meets, area.withAcceptanceLearning(true)
                .withAccepted(new AcceptedFloors(1000,0,0,0,0)), true, false, false, 30));
    }
    @Test public void copiesAndPlanIdentityPreserveEveryNewSetting() {
        FilterSettings learned = SAVED.withScoreByArea(true).withAcceptanceLearning(true)
                .withAccepted(new AcceptedFloors(800,800,4,800,20));
        for (FilterSettings copy : new FilterSettings[] {learned.withEnabled(false), learned.withMaxStops(2),
                learned.withMinimums(300,120,30), learned.withMinimumScalePercent(70), learned.withAutopilot(true,50)}) {
            assertTrue(copy.scoreByArea); assertTrue(copy.acceptanceLearning); assertEquals(learned.accepted,copy.accepted);
        }
        assertNotEquals(SAVED.rulesKey(), SAVED.withScoreByArea(true).rulesKey());
        assertNotEquals(SAVED.rulesKey(), SAVED.withAcceptanceLearning(true).rulesKey());
        assertNotEquals(learned.rulesKey(), learned.withAccepted(AcceptedFloors.NONE).rulesKey());
        assertEquals(learned.rulesKey(), learned.withMinimumScalePercent(70).rulesKey());
        assertEquals(0, AreaScore.floors(learned, OFFER).fixedCents()[0].compareTo(BigDecimal.valueOf(500)));
    }
    @Test public void addOnsStayStrictSavedOnlyEvenWhileStandaloneUsesAreaAndLearning() {
        AddOnOffer addOn=AddOnOffer.parse(null,java.util.Arrays.asList("Add to route", "Total pay $8.00",
                "Total distance 10 mi", "Total time 20 min", "Total stops 2 stops", "+$5.00", "+2 mi", "+10 min"));
        FilterSettings selected=SAVED.withScoreByArea(true).withAcceptanceLearning(true)
                .withAccepted(new AcceptedFloors(2000,2000,2,2000,10));
        OfferRule.Decision strict=OfferRule.evaluateAddOn(addOn,SAVED);
        OfferRule.Decision restored=OfferRule.evaluateAddOn(addOn,selected);
        assertEquals(OfferRule.Result.DECLINE,strict.result);
        assertEquals(strict.result,restored.result); assertEquals(strict.requiredCents,restored.requiredCents);
        assertEquals("strict",restored.scoreKind); assertFalse(restored.scoringRules.acceptanceLearning);
        FilterSettings savedLow=FilterSettings.of(true,50,10,1,3);
        assertEquals(OfferRule.Result.KEEP,OfferRule.evaluateAddOn(addOn,savedLow).result);
        assertEquals(OfferRule.Result.KEEP,OfferRule.evaluateAddOn(addOn,savedLow.withScoreByArea(true)
                .withAcceptanceLearning(true).withAccepted(selected.accepted)).result);
        assertEquals(OfferRule.Result.DECLINE,OfferRule.evaluateAddOn(addOn,savedLow.withMinimums(2001,10,1)).result);
    }

    @Test public void autopilotAndQualifyingWaitUseSelectedExactModelAndExcludeUnreadAreaValue() {
        long now=1_800_000_000_000L;
        java.util.List<Autopilot.OfferRecord> records=new java.util.ArrayList<>();
        java.util.List<QualifyingWait.Sample> waits=new java.util.ArrayList<>();
        for(int i=0;i<20;i++) {
            records.add(new Autopilot.OfferRecord(OFFER,now-i*180_000L,false,false,false,false,false));
            waits.add(new QualifyingWait.Sample(now-i*180_000L,120_000,OFFER));
        }
        FilterSettings strict=SAVED.withAutopilot(true,50), area=strict.withScoreByArea(true);
        Autopilot.Plan strictPlan=Autopilot.plan(new Autopilot.Inputs(strict,records,waits,null,null,now,1));
        Autopilot.Plan areaPlan=Autopilot.plan(new Autopilot.Inputs(area,records,waits,null,null,now,1));
        assertEquals(0,strictPlan.passAt(100)); assertEquals(20,areaPlan.passAt(100));
        assertEquals(20,areaPlan.mixSize); assertNotEquals(strictPlan.rulesKey,areaPlan.rulesKey);
        assertEquals(0,QualifyingWait.estimate(waits,strict).qualifying);
        assertEquals(20,QualifyingWait.estimate(waits,area).qualifying);
        java.util.List<Autopilot.OfferRecord> partial=new java.util.ArrayList<>();
        for(int i=0;i<20;i++) partial.add(new Autopilot.OfferRecord(new OfferSnapshot(1,null,20,2),
                now-i*180_000L,false,false,false,false,false));
        Autopilot.Plan unread=Autopilot.plan(new Autopilot.Inputs(area,partial,waits,null,null,now,1));
        assertEquals(20,unread.passAt(150)); assertEquals(0,unread.mixSize);
        assertTrue(unread.target>=50 && unread.target<=100);
        FilterSettings learnedOnly=FilterSettings.of(true,0,0,0,0).withAutopilot(true,50)
                .withAcceptanceLearning(true).withAccepted(new AcceptedFloors(500,0,0,0,0));
        assertNotEquals(Autopilot.Mode.NO_RULES,Autopilot.plan(new Autopilot.Inputs(learnedOnly,records,waits,
                null,null,now,1)).mode);
    }

}

package com.local.dasherfilter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** The accepted offer sets independent working rates without becoming a saved/learned minimum or tap authority. */
public final class AutopilotAxesTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final FilterSettings BASE = FilterSettings.of(true, 400, 150, 40, 0)
            .withAutopilot(true, FilterSettings.GOAL_PAY_FIRST);

    private static FilterSettings accepted(FilterSettings rules, OfferSnapshot offer, long at) {
        return rules.withAutopilotAxes(rules.autopilotAxes.withOffer(rules, offer, at));
    }

    private static void boundary(FilterSettings rules, OfferSnapshot offer) {
        for (boolean area : new boolean[] {false, true}) {
            FilterSettings selected = rules.withScoreByArea(area);
            assertEquals(offer.payCents.longValue(), AreaScore.requiredPay(selected, offer, selected.minimumScalePercent));
            assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(offer, selected).result);
            OfferSnapshot lower = new OfferSnapshot(offer.payCents - 1, offer.miles, offer.minutes, offer.stops);
            assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(lower, selected).result);
            assertEquals(selected.minimumScalePercent, AreaScore.scorePercent(selected, offer));
            assertTrue(AreaScore.scorePercent(selected, lower) < selected.minimumScalePercent);
        }
    }

    @Test public void aPersonalAcceptanceCanLowerTwoAxesAndRaiseTheOther() {
        OfferSnapshot offer = new OfferSnapshot(600, 3.0, 20, 2);
        FilterSettings next = accepted(BASE, offer, NOW);
        assertEquals(0, next.autopilotAxes.rate(AreaScore.PAY).display().compareTo(BigDecimal.valueOf(300)));
        assertEquals(0, next.autopilotAxes.rate(AreaScore.MILE).display().compareTo(BigDecimal.valueOf(200)));
        assertEquals(0, next.autopilotAxes.rate(AreaScore.MINUTE).display().compareTo(BigDecimal.valueOf(30)));
        assertArrayEquals(BASE.minimums(), next.minimums());
        assertEquals(BASE.accepted, next.accepted);
        assertEquals(100, next.minimumScalePercent);
        boundary(next, offer);
    }

    @Test public void fractionalStopMileAndMinuteRatesMeetTheAcceptedCentExactlyAtAnUnevenBar() {
        OfferSnapshot offer = new OfferSnapshot(1001, 3.0, 7, 3);
        FilterSettings next = accepted(BASE.withMinimumScalePercent(82), offer, NOW);
        boundary(next, offer);
        assertEquals(82, next.autopilotAxes.referenceBar);
        assertEquals(1001, AreaScore.asks(next, offer).required(82));
    }

    @Test public void aPointSixMileFiveMinuteOfferSetsItsObservedRatesWithoutLearnerSampleGates() {
        OfferSnapshot offer = new OfferSnapshot(945, .6, 5, 2);
        FilterSettings next = accepted(BASE.withMinimumScalePercent(53), offer, NOW);
        assertTrue(next.autopilotAxes.hasAxis(AreaScore.MILE));
        assertTrue(next.autopilotAxes.hasAxis(AreaScore.MINUTE));
        boundary(next, offer);
    }

    @Test public void workingRatesAreNotClampedToFiftyOrOneHundredFiftyPercentOfSavedMinimums() {
        FilterSettings low = FilterSettings.of(true, 1, 1, 1, 0).withAutopilot(true, 0);
        OfferSnapshot high = new OfferSnapshot(100_000, .5, 5, 2);
        boundary(accepted(low, high, NOW), high);
        OfferSnapshot cheap = new OfferSnapshot(1, 50.0, 100, 10);
        boundary(accepted(BASE, cheap, NOW), cheap);
    }

    @Test public void aNewerLowerAcceptanceReplacesAnOlderHigherWorkingRate() {
        FilterSettings high = accepted(BASE, new OfferSnapshot(2400, 3.0, 20, 2), NOW);
        OfferSnapshot next = new OfferSnapshot(600, 3.0, 20, 2);
        FilterSettings low = accepted(high, next, NOW + 60_000);
        assertEquals(NOW + 60_000, low.autopilotAxes.acceptedAt);
        boundary(low, next);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(next, high).result);
    }

    @Test public void missingAxesKeepTheirPreviousWorkingValuesAndStillNeedObservedOfferFacts() {
        FilterSettings first = accepted(BASE, new OfferSnapshot(600, 3.0, 20, 2), NOW);
        FilterSettings next = accepted(first, new OfferSnapshot(900, null, 30, null), NOW + 1000);
        assertEquals(first.autopilotAxes.rate(AreaScore.PAY), next.autopilotAxes.rate(AreaScore.PAY));
        assertEquals(first.autopilotAxes.rate(AreaScore.MILE), next.autopilotAxes.rate(AreaScore.MILE));
        assertEquals(0, next.autopilotAxes.rate(AreaScore.MINUTE).display().compareTo(BigDecimal.valueOf(30)));
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(new OfferSnapshot(900, null, 30, 2), next).result);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(new OfferSnapshot(1, null, 30, 2), next).result);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(new OfferSnapshot(1, null, 30, 2),
                next.withScoreByArea(true)).result);
    }

    @Test public void aMissingMetricOnTheFirstAcceptanceKeepsThatAxisAtItsBaseline() {
        FilterSettings next = accepted(BASE, new OfferSnapshot(600, null, 20, 2), NOW);
        assertFalse(next.autopilotAxes.hasAxis(AreaScore.MILE));
        assertEquals(0, AreaScore.floors(next, new OfferSnapshot(600, 4.0, 20, 2))
                .effectiveCents()[AreaScore.MILE].compareTo(BigDecimal.valueOf(600)));
    }

    @Test public void disabledAxesAreNeverActivatedByAnAcceptanceOrARetainedOverride() {
        FilterSettings payOnly = BASE.withMinimums(400, 0, 0);
        FilterSettings accepted = accepted(payOnly, new OfferSnapshot(600, 3.0, 20, 2), NOW);
        assertTrue(accepted.autopilotAxes.hasAxis(AreaScore.PAY));
        assertFalse(accepted.autopilotAxes.hasAxis(AreaScore.MILE));
        assertFalse(accepted.autopilotAxes.hasAxis(AreaScore.MINUTE));
        FilterSettings none = accepted.withMinimums(0, 0, 0);
        assertFalse(none.hasMonetaryRule());
        assertFalse(none.hasAutopilotAxes());
        assertFalse(AreaScore.floors(none, OfferSnapshot.UNKNOWN).active[AreaScore.PAY]);
        assertEquals(0, AreaScore.requiredPay(none, OfferSnapshot.UNKNOWN, 100));
    }

    @Test public void learningCanSupplyAnActiveAxisButNeverAddItsExtraCentToTheWorkingOverride() {
        AcceptedFloors proof = new AcceptedFloors(900, 3, 900, 3.0, 900, 30);
        FilterSettings baseline = BASE.withMinimums(0, 0, 0).withAcceptanceLearning(true).withAccepted(proof);
        OfferSnapshot offer = new OfferSnapshot(600, 3.0, 20, 2);
        FilterSettings next = accepted(baseline, offer, NOW);
        assertTrue(next.autopilotAxes.hasAxis(AreaScore.PAY));
        assertEquals(601, AreaScore.floors(next, offer).baselineCents()[AreaScore.PAY].intValueExact());
        assertEquals(600, AreaScore.floors(next, offer).effectiveCents()[AreaScore.PAY].intValueExact());
        assertEquals(proof, next.accepted);
        boundary(next, offer);
        FilterSettings raised = next.withAccepted(new AcceptedFloors(1200, 3, 1200, 3.0, 1200, 30));
        assertTrue(AreaScore.effectiveFloorsRaised(next, raised, offer));
        boundary(raised, offer);
    }

    @Test public void knownImplausibleFigureUnknownPayAndHardStopFailureRejectTheEntireAdjustment() {
        for (OfferSnapshot bad : new OfferSnapshot[] {new OfferSnapshot(600, .1, 20, 2),
                new OfferSnapshot(600, 3.0, 1, 2), new OfferSnapshot(600, 3.0, 20, 1),
                new OfferSnapshot(null, 3.0, 20, 2, 600), new OfferSnapshot(0, 3.0, 20, 2)}) {
            assertSame(AutopilotAxes.NONE, AutopilotAxes.NONE.withOffer(BASE, bad, NOW));
        }
        assertSame(AutopilotAxes.NONE, AutopilotAxes.NONE.withOffer(BASE.withMaxStops(2),
                new OfferSnapshot(600, 3.0, 20, 3), NOW));
    }

    @Test public void anOffAutopilotLearnsNothingAndItsStoredShapeChangesNoCutoff() {
        FilterSettings on = accepted(BASE, new OfferSnapshot(600, 3.0, 20, 2), NOW);
        FilterSettings off = on.withAutopilot(false, 0);
        assertFalse(off.hasAutopilotAxes());
        assertEquals(on.autopilotAxes, off.autopilotAxes);
        assertSame(off.autopilotAxes, off.autopilotAxes.withOffer(off, new OfferSnapshot(1200, 3.0, 20, 2), NOW + 1));
        assertEquals(800, OfferRule.evaluate(new OfferSnapshot(600, 3.0, 20, 2), off).requiredCents);
        assertEquals(BASE.withAutopilot(false, 0).scoringKey(), off.scoringKey());
    }

    @Test public void theExistingMarketBarCanMoveTheWorkingShapeOnceTheHoldEnds() {
        OfferSnapshot offer = new OfferSnapshot(1001, 3.0, 7, 3);
        FilterSettings next = accepted(BASE.withMinimumScalePercent(82), offer, NOW);
        assertEquals(501, AreaScore.requiredPay(next, offer, 41));
        assertEquals(1221, AreaScore.requiredPay(next, offer, 100));
        assertEquals(next.rulesKey(), next.withMinimumScalePercent(100).rulesKey());
    }

    @Test public void autoAcceptAlwaysMeetsBothStrictBaselineAndCurrentIndependentWorkingAxes() {
        OfferSnapshot low = new OfferSnapshot(600, 3.0, 20, 2);
        FilterSettings loosened = accepted(BASE, low, NOW);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(low, loosened).result);
        assertTrue(OfferRule.evaluate(low, loosened).belowMinimums);
        assertFalse(AutoAccept.meetsRequirements(low, loosened));
        assertFalse(AutoAccept.eligible(low, loosened, true, false, false, 30));
        // The existing higher-bar guard remains too: a lower working shape cannot bypass 120% of the baseline.
        FilterSettings higherBar = loosened.withMinimumScalePercent(120);
        assertEquals(OfferRule.Result.KEEP,
                OfferRule.evaluate(new OfferSnapshot(800, 3.0, 20, 2), higherBar).result);
        assertFalse(AutoAccept.meetsRequirements(new OfferSnapshot(800, 3.0, 20, 2), higherBar));
        assertTrue(AutoAccept.meetsRequirements(new OfferSnapshot(960, 3.0, 20, 2), higherBar));
        OfferSnapshot high = new OfferSnapshot(1200, 3.0, 20, 2);
        FilterSettings tightened = accepted(BASE, high, NOW);
        assertTrue(AutoAccept.meetsRequirements(high, tightened));
        assertFalse(AutoAccept.meetsRequirements(new OfferSnapshot(1000, 3.0, 20, 2), tightened));
        assertTrue(AutoAccept.meetsRequirements(new OfferSnapshot(800, 3.0, 20, 2),
                tightened.withMinimumScalePercent(50)));
        assertFalse(AutoAccept.meetsRequirements(new OfferSnapshot(700, 3.0, 20, 2),
                tightened.withMinimumScalePercent(50)));
    }

    @Test public void areaCompensationNeverAuthorizesAutomaticAcceptance() {
        FilterSettings working = accepted(BASE, new OfferSnapshot(600, 3.0, 20, 2), NOW).withScoreByArea(true);
        OfferSnapshot compensates = new OfferSnapshot(900, 6.0, 20, 2);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(compensates, working).result);
        assertFalse(AutoAccept.meetsRequirements(compensates, working));
    }

    @Test public void strictAddOnsIgnoreIndependentStandaloneWorkingRates() {
        AddOnOffer addOn = AddOnOffer.parse(null, Arrays.asList("Add to route", "Total pay $8.00",
                "Total distance 10 mi", "Total time 20 min", "Total stops 2 stops", "+$5.00", "+2 mi", "+10 min"));
        FilterSettings changed = accepted(BASE, new OfferSnapshot(600, 3.0, 20, 2), NOW);
        OfferRule.Decision baseline = OfferRule.evaluateAddOn(addOn, BASE);
        OfferRule.Decision next = OfferRule.evaluateAddOn(addOn, changed);
        assertEquals(baseline.result, next.result);
        assertEquals(baseline.requiredCents, next.requiredCents);
        assertFalse(next.scoringRules.hasAutopilotAxes());
    }

    @Test public void aBonusCeilingCannotBeDeclinedByHigherIndependentWorkingRates() {
        FilterSettings high = accepted(BASE, new OfferSnapshot(2400, 3.0, 20, 2), NOW);
        OfferSnapshot ceiling = new OfferSnapshot(null, 3.0, 20, 2, 800);
        for (boolean area : new boolean[] {false, true}) {
            FilterSettings selected = high.withScoreByArea(area);
            assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(ceiling, selected).result);
            assertEquals(AreaScore.passThreshold(selected.withoutAutopilotAxes(), ceiling),
                    AreaScore.passThreshold(selected, ceiling));
        }
    }

    @Test public void newestAnchorReplacesTheHistoricalHighAndHoldsForTwentyFourCountedOffers() {
        FilterSettings working = accepted(BASE.withMinimumScalePercent(82), new OfferSnapshot(600, 3.0, 20, 2), NOW);
        List<Autopilot.OfferRecord> history = new ArrayList<>();
        history.add(new Autopilot.OfferRecord(new OfferSnapshot(9999, 3.0, 20, 2), NOW - 60_000,
                false, false, true, false, false, 100, true, DecisionLog.MODEL, BASE, false, NOW - 30_000));
        Autopilot.Plan first = plan(working, history);
        assertEquals(82, first.floor);
        assertEquals(24, first.floorOffersLeft);
        for (int i = 0; i < 23; i++) history.add(0, new Autopilot.OfferRecord(
                new OfferSnapshot(600 + i, 3.0, 20, 2), NOW + (i + 1) * 180_000L,
                false, false, false, true, false));
        assertEquals(1, plan(working, history).floorOffersLeft);
        history.add(0, new Autopilot.OfferRecord(new OfferSnapshot(900, 3.0, 20, 2), NOW + 24 * 180_000L,
                false, false, false, true, false));
        assertEquals(-1, plan(working, history).floor);
        assertEquals(0, plan(working, history).floorOffersLeft);
        boundary(working, new OfferSnapshot(600, 3.0, 20, 2));
    }

    private static Autopilot.Plan plan(FilterSettings rules, List<Autopilot.OfferRecord> history) {
        return Autopilot.plan(new Autopilot.Inputs(rules, history, Collections.emptyList(), null,
                new Autopilot.State(false, 0, Autopilot.NEVER, -1, Autopilot.NEVER, rules.minimumScalePercent, null),
                NOW + 100_000_000, 1));
    }

    @Test public void copiesPreserveWorkingRatesAndEffectiveRuleIdentityIncludesThem() {
        FilterSettings rules = accepted(BASE, new OfferSnapshot(600, 3.0, 20, 2), NOW);
        for (FilterSettings copy : new FilterSettings[] {rules.withScoreByArea(true), rules.withAccepted(AcceptedFloors.NONE),
                rules.withAcceptanceLearning(true), rules.withEnabled(false), rules.withMaxStops(3),
                rules.withMinimumScalePercent(70), rules.withMinimums(100, 200, 30), rules.withAutopilot(false, 0)}) {
            assertEquals(rules.autopilotAxes, copy.autopilotAxes);
        }
        assertNotEquals(BASE.scoringKey(), rules.scoringKey());
        assertNotEquals(AutoAccept.rulesKey(BASE), AutoAccept.rulesKey(rules));
        assertEquals(BASE.scoringKey(), rules.withoutAutopilotAxes().scoringKey());
    }

    @Test public void localNumericCodecPreservesExactRatesAndRejectsMalformedState() {
        FilterSettings rules = accepted(BASE.withMinimumScalePercent(83), new OfferSnapshot(945, .6, 13, 3), NOW);
        AutopilotAxes restored = AutopilotAxes.decode(rules.autopilotAxes.encode());
        assertEquals(rules.autopilotAxes, restored);
        assertEquals(rules.autopilotAxes.hashCode(), restored.hashCode());
        assertEquals(rules.autopilotAxes.stableKey(), restored.stableKey());
        boundary(rules.withAutopilotAxes(restored), new OfferSnapshot(945, .6, 13, 3));
        for (String invalid : new String[] {null, "", "1|0|100|1/0|-|-", "2|0|100|1/2|-|-",
                "1|0|0|1/2|-|-", "1|0|100|-1/2|-|-", "1|0|100|1/2|-", "1|0|100|NaN/2|-|-"}) {
            assertSame(AutopilotAxes.NONE, AutopilotAxes.decode(invalid));
        }
        char[] huge = new char[5000]; Arrays.fill(huge, '9');
        assertSame(AutopilotAxes.NONE, AutopilotAxes.decode(new String(huge)));
    }
}

package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

public final class EarningsOptimizerTest {
    private static FilterSettings rules() {
        return new FilterSettings(true, 1000, 0, 0, 0, 0);
    }

    private static List<QualifyingWait.Sample> market() {
        List<QualifyingWait.Sample> samples = new ArrayList<>();
        long at = 1_000_000L;
        // Ten minutes of measured waiting: low, medium and high-value offers all take about 20 minutes.
        int[] pays = {600, 650, 700, 1200, 1250, 1300, 2000, 2050, 2100, 2200};
        double[] miles = {2, 2, 2, 3, 3, 3, 8, 8, 8, 8};
        for (int i = 0; i < pays.length; i++) {
            samples.add(new QualifyingWait.Sample(at + i * 60_000L, 60_000L,
                    new OfferSnapshot(pays[i], miles[i], 20, 2)));
        }
        return samples;
    }

    @Test public void sparseHistoryUsesDeterministicPreferenceWithoutClaimingOptimization() {
        List<QualifyingWait.Sample> sparse = new ArrayList<>();
        sparse.add(new QualifyingWait.Sample(1, 60_000, new OfferSnapshot(1000, 2.0, 20, 2)));
        EarningsOptimizer.Result result = EarningsOptimizer.analyze(sparse, Collections.emptyList(), rules(), 50, 0);
        assertEquals(EarningsOptimizer.Status.LEARNING, result.status);
        assertEquals(100, result.recommendedScalePercent);
        assertTrue(result.detail().contains("Learning"));
        assertTrue(result.detail().contains("gross"));
    }

    @Test public void offersEndIsNoStricterThanProfitEnd() {
        EarningsOptimizer.Result offers = EarningsOptimizer.analyze(market(), Collections.emptyList(), rules(), 0, 0);
        EarningsOptimizer.Result profit = EarningsOptimizer.analyze(market(), Collections.emptyList(), rules(), 100, 0);
        assertEquals(EarningsOptimizer.Status.READY, offers.status);
        assertEquals(EarningsOptimizer.Status.READY, profit.status);
        assertTrue("offers=" + offers.recommendedScalePercent + " profit=" + profit.recommendedScalePercent,
                offers.recommendedScalePercent <= profit.recommendedScalePercent);
        assertTrue(offers.matchingOffersPerHour >= profit.matchingOffersPerHour);
        assertTrue(profit.hourlyCents >= offers.hourlyCents);
    }

    @Test public void vehicleCostLowersTheEconomicEstimateWithoutChangingObservedPay() {
        EarningsOptimizer.Result gross = EarningsOptimizer.analyze(market(), Collections.emptyList(), rules(), 100, 0);
        EarningsOptimizer.Result net = EarningsOptimizer.analyze(market(), Collections.emptyList(), rules(), 100, 100);
        assertEquals(EarningsOptimizer.Status.READY, gross.status);
        assertEquals(EarningsOptimizer.Status.READY, net.status);
        assertTrue(net.hourlyCents < gross.hourlyCents);
        assertTrue(net.detail().contains("$1.00/mi"));
        assertTrue(gross.detail().contains("gross"));
    }

    @Test public void acceptanceProxyUsesOnlyConfirmedAcceptedAndDeclinedOutcomes() {
        List<DecisionLog.Entry> entries = new ArrayList<>();
        long now = 10_000;
        for (int i = 0; i < 5; i++) entries.add(accepted(now - i));
        for (int i = 0; i < 5; i++) entries.add(declined(now - 100 - i));
        // Plain PASSED lines are not proof that the driver accepted them.
        for (int i = 0; i < 20; i++) entries.add(passed(now - 1000 - i));
        assertEquals(50, EarningsOptimizer.observedAcceptancePercent(entries));
    }

    @Test public void fewerThanFiveStrongOutcomesDoesNotInventAnAcceptanceRate() {
        List<DecisionLog.Entry> entries = new ArrayList<>();
        entries.add(accepted(1));
        entries.add(declined(2));
        entries.add(passed(3));
        assertEquals(-1, EarningsOptimizer.observedAcceptancePercent(entries));
    }

    private static DecisionLog.Entry accepted(long at) {
        return passed(at).withStep(new DecisionLog.Step(DecisionLog.StepKind.ACCEPTED_OBSERVED, at + 1, ""));
    }

    private static DecisionLog.Entry passed(long at) {
        return new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(1500, 3.0, 20, 2), 1000, OfferRule.Result.KEEP,
                "fixture", DecisionLog.Action.PASSES, true, Collections.emptyList());
    }

    private static DecisionLog.Entry declined(long at) {
        return new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(500, 3.0, 20, 2), 1000, OfferRule.Result.DECLINE,
                "fixture", DecisionLog.Action.DECLINE_TAPPED, true, Collections.emptyList());
    }
}

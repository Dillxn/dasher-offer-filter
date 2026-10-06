package com.local.dasherfilter;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class EarningsModelTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final FilterSettings RULES = new FilterSettings(true, 1000, 0, 0, 0, 0);
    private static final EarningsModel.Config CONFIG = new EarningsModel.Config(true, 50.0, 90, 110);

    /** Twenty-four standalone arrivals, each after two observed minutes; both halves have the same mix. */
    static List<QualifyingWait.Sample> evidence(long now) {
        List<QualifyingWait.Sample> rows = new ArrayList<>();
        for (int i = 0; i < 24; i++) rows.add(new QualifyingWait.Sample(now - (23 - i) * 120_000L,
                120_000, i % 2 == 0 ? new OfferSnapshot(1100, 2.0, 10, 2) : new OfferSnapshot(1000, 12.0, 60, 2)));
        return rows;
    }

    private EarningsModel.Recommendation recommend(List<QualifyingWait.Sample> rows, FilterSettings rules,
                                                   EarningsModel.Config config) {
        return EarningsModel.recommend(rows, rules, config, Collections.emptyList(), EarningsModel.Adjustment.NONE, NOW);
    }

    @Test public void defaultsAreOffAndMissingCostIsDifferentFromExplicitZero() {
        assertFalse(EarningsModel.Config.defaults().enabled);
        assertNull(EarningsModel.Config.defaults().vehicleCostCentsPerMile);
        assertEquals(EarningsModel.Status.OFF, recommend(evidence(NOW), RULES, EarningsModel.Config.defaults()).status);
        assertEquals(EarningsModel.Status.NEEDS_COST,
                recommend(evidence(NOW), RULES, new EarningsModel.Config(true, null, 90, 110)).status);
        assertTrue(recommend(evidence(NOW), RULES, new EarningsModel.Config(true, 0.0, 90, 110)).canAdjust());
    }

    @Test public void invalidCostsAndBoundsAreRejectedRatherThanSilentlyClamped() {
        for (Double cost : Arrays.asList(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0, 1000.01)) {
            assertThrows(IllegalArgumentException.class, () -> new EarningsModel.Config(true, cost, 90, 110));
        }
        assertThrows(IllegalArgumentException.class, () -> new EarningsModel.Config(true, 0.0, 0, 110));
        assertThrows(IllegalArgumentException.class, () -> new EarningsModel.Config(true, 0.0, 100, 201));
        assertThrows(IllegalArgumentException.class, () -> new EarningsModel.Config(true, 0.0, 110, 90));
        assertThrows(IllegalArgumentException.class, () -> new EarningsModel.Snapshot(101, NOW));
        assertThrows(IllegalArgumentException.class, () -> new EarningsModel.Snapshot(-1, NOW));
        assertThrows(IllegalArgumentException.class, () -> new EarningsModel.Snapshot(1, -1));
    }

    @Test public void calculatesCostAdjustedCycleRateAndReplaysStrictRulesExactly() {
        EarningsModel.Recommendation result = recommend(evidence(NOW), RULES, CONFIG);
        assertTrue(result.canAdjust());
        assertEquals(101, result.suggestedPercent);
        assertEquals(16800.0 * 60 / 888, result.currentNetCentsPerHour, .00001);
        assertEquals(12000.0 * 60 / 168, result.suggestedNetCentsPerHour, .00001);
        assertEquals(24, result.readable);
        assertEquals(24, result.matching);
        assertEquals(100, result.matchingPercent, 0);
        assertEquals(30, result.arrivalsPerObservedHour, 0);
        assertEquals(48 * 60_000L, result.observedMs);
        assertEquals(EarningsModel.settingsKey(RULES), result.settingsKey);
        assertEquals(EarningsModel.settingsKey(RULES.withMinimumScalePercent(101)), result.suggestedSettingsKey);
        assertTrue(result.detail().contains("not realized earnings"));
    }

    @Test public void lowersAtMostFivePointsAndNeverLeavesConfiguredBounds() {
        List<QualifyingWait.Sample> rows = new ArrayList<>();
        for (int i = 0; i < 24; i++) rows.add(new QualifyingWait.Sample(NOW - (23-i) * 120_000L, 120_000,
                i % 2 == 0 ? new OfferSnapshot(950, 2.0, 15, 2) : new OfferSnapshot(1000, 2.0, 60, 2)));
        EarningsModel.Recommendation result = recommend(rows, RULES, new EarningsModel.Config(true, 0.0, 50, 150));
        assertTrue(result.canAdjust());
        assertEquals(95, result.suggestedPercent);
        assertFalse(recommend(rows, RULES, new EarningsModel.Config(true, 0.0, 96, 110)).canAdjust());
        assertEquals(EarningsModel.Status.OUTSIDE_RANGE,
                recommend(rows, RULES, new EarningsModel.Config(true, 0.0, 101, 110)).status);
    }

    @Test public void sparseMissingRouteUnknownPayAndDeclaredUnreadItemsNeverAdjust() {
        assertEquals(EarningsModel.Status.LEARNING, recommend(evidence(NOW).subList(0, 10), RULES, CONFIG).status);
        for (OfferSnapshot missing : Arrays.asList(OfferSnapshot.UNKNOWN, new OfferSnapshot(1000, null, 10, 2),
                new OfferSnapshot(1000, 2.0, null, 2), new OfferSnapshot(1000, 2.0, 0, 2),
                new OfferSnapshot(null, 2.0, 10, 2, 2000), new OfferSnapshot(1000, 2.0, 10, 1),
                new OfferSnapshot(1000, Double.NaN, 10, 2), new OfferSnapshot(1000, 2.0, 10, 2).withItems(null, true))) {
            List<QualifyingWait.Sample> rows = evidence(NOW);
            rows.set(0, new QualifyingWait.Sample(rows.get(0).at, 120_000, missing));
            EarningsModel.Recommendation result = recommend(rows, RULES, CONFIG);
            assertEquals(EarningsModel.Status.UNREADABLE, result.status);
            assertNull(result.currentNetCentsPerHour);
            assertFalse(result.canAdjust());
        }
    }

    @Test public void rightCensoredWaitCountsWithoutAnInventedArrival() {
        List<QualifyingWait.Sample> rows = evidence(NOW - 60_000);
        rows.add(new QualifyingWait.Sample(NOW, 60_000, null));
        EarningsModel.Recommendation result = recommend(rows, RULES, CONFIG);
        assertEquals(24, result.readable);
        assertEquals(49 * 60_000L, result.observedMs);
        assertEquals(16800.0 * 60 / 889, result.currentNetCentsPerHour, .00001);
    }

    @Test public void pausedNoRulesAndUnreadableHotspotDoNotAdjust() {
        assertEquals(EarningsModel.Status.NO_RULES, recommend(evidence(NOW), RULES.withEnabled(false), CONFIG).status);
        assertEquals(EarningsModel.Status.NO_RULES,
                recommend(evidence(NOW), new FilterSettings(true, 0, 0, 0, 0, 0), CONFIG).status);
        assertEquals(EarningsModel.Status.UNREADABLE,
                recommend(evidence(NOW), RULES.withHotspotProximity(1), CONFIG).status);
    }

    @Test public void strictAreaMaximumStopsAndLearnedItemRulesUseSharedDecisionModel() {
        List<QualifyingWait.Sample> rows = new ArrayList<>();
        FilterSettings rules = new FilterSettings(true, 1000, 200, 0, 0, 0);
        for (int i = 0; i < 24; i++) rows.add(new QualifyingWait.Sample(NOW - (23-i) * 120_000L, 120_000,
                i % 2 == 0 ? new OfferSnapshot(2000, 2.0, 10, 2) : new OfferSnapshot(900, 1.0, 10, 2)));
        EarningsModel.Config pinned = new EarningsModel.Config(true, 0.0, 100, 100);
        assertEquals(12, recommend(rows, rules, pinned).matching);
        assertEquals(24, recommend(rows, rules.withScoreByArea(true), pinned).matching);
        assertEquals(0, recommend(rows, rules.withMaxStops(1), pinned).matching);
        FilterSettings learned = new FilterSettings(true, 1000, 0, 0, 0, 0, true, 0,
                new AcceptedBest(0, 0, 0, 0, 0, 0, 1200, 2));
        List<QualifyingWait.Sample> shopping = new ArrayList<>();
        for (QualifyingWait.Sample row : rows) shopping.add(new QualifyingWait.Sample(row.at, row.observedMs,
                row.arrival.withItems(2, true)));
        assertEquals(12, recommend(shopping, learned, pinned).matching);
        assertNotEquals(EarningsModel.settingsKey(learned), EarningsModel.settingsKey(rules));
    }

    @Test public void improvementOnlyInOneHalfCannotMoveMinimums() {
        List<QualifyingWait.Sample> rows = evidence(NOW);
        for (int i = 0; i < 12; i++) {
            QualifyingWait.Sample old = rows.get(i);
            rows.set(i, new QualifyingWait.Sample(old.at, old.observedMs, new OfferSnapshot(1100, 2.0, 10, 2)));
        }
        assertEquals(EarningsModel.Status.STEADY, recommend(rows, RULES, CONFIG).status);
    }

    @Test public void reducingModeledLossesDoesNotInventPositiveEarningsAndTiesNeverMove() {
        EarningsModel.Recommendation loss = recommend(evidence(NOW), RULES,
                new EarningsModel.Config(true, 1000.0, 90, 110));
        assertTrue(loss.canAdjust());
        assertTrue(loss.currentNetCentsPerHour < 0);
        assertTrue(loss.suggestedNetCentsPerHour < 0);
        assertTrue(loss.suggestedNetCentsPerHour > loss.currentNetCentsPerHour);
        assertTrue(loss.detail().contains("not realized earnings"));
        List<QualifyingWait.Sample> identical = evidence(NOW);
        for (int i = 0; i < identical.size(); i++) {
            QualifyingWait.Sample old = identical.get(i);
            identical.set(i, new QualifyingWait.Sample(old.at, old.observedMs, new OfferSnapshot(2000, 2.0, 10, 2)));
        }
        EarningsModel.Recommendation tie = recommend(identical, RULES, new EarningsModel.Config(true, 1000.0, 90, 110));
        assertEquals(0, tie.currentNetCentsPerHour, 0);
        assertEquals(EarningsModel.Status.STEADY, tie.status);
        assertEquals(100, tie.suggestedPercent);
    }

    @Test public void cooldownAndFreshExposureSurviveChangedRulesOrConfig() {
        EarningsModel.Adjustment recent = new EarningsModel.Adjustment(NOW - 60_000, NOW - 60_000, "old rules", "old config");
        assertEquals(EarningsModel.Status.COOLDOWN, EarningsModel.recommend(evidence(NOW), RULES, CONFIG,
                Collections.emptyList(), recent, NOW).status);
        EarningsModel.Adjustment future = new EarningsModel.Adjustment(NOW + 1, 0, "", "");
        assertEquals(EarningsModel.Status.COOLDOWN, EarningsModel.recommend(evidence(NOW), RULES, CONFIG,
                Collections.emptyList(), future, NOW).status);
        EarningsModel.Adjustment old = new EarningsModel.Adjustment(NOW - EarningsModel.COOLDOWN_MS, 0, "", "");
        assertEquals(EarningsModel.Status.NEEDS_FRESH_EVIDENCE, EarningsModel.recommend(evidence(NOW - 20 * 60_000L),
                RULES, CONFIG, Collections.emptyList(), old, NOW).status);
        assertTrue(EarningsModel.recommend(evidence(NOW), RULES, CONFIG, Collections.emptyList(), old, NOW).canAdjust());
    }

    @Test public void reportedArPartitionsOnlySupportedObservationWindowWithoutCausalMultiplier() {
        List<QualifyingWait.Sample> rows = evidence(NOW - 48 * 60_000L);
        rows.addAll(evidence(NOW));
        long reportAt = NOW - 48 * 60_000L;
        EarningsModel.Recommendation low = EarningsModel.recommend(rows, RULES, CONFIG,
                Collections.singletonList(new EarningsModel.Snapshot(5, reportAt)), EarningsModel.Adjustment.NONE, NOW);
        EarningsModel.Recommendation high = EarningsModel.recommend(rows, RULES, CONFIG,
                Collections.singletonList(new EarningsModel.Snapshot(99, reportAt)), EarningsModel.Adjustment.NONE, NOW);
        assertTrue(low.usingSinceArReport);
        assertEquals(24, low.readable);
        assertEquals(low.currentNetCentsPerHour, high.currentNetCentsPerHour);
        assertEquals(low.suggestedPercent, high.suggestedPercent);
        EarningsModel.Recommendation sparse = EarningsModel.recommend(rows, RULES, CONFIG,
                Collections.singletonList(new EarningsModel.Snapshot(50, NOW - 60_000)), EarningsModel.Adjustment.NONE, NOW);
        assertFalse(sparse.usingSinceArReport);
        assertEquals(48, sparse.readable);
    }

    @Test public void retentionRejectsFutureAndExpiredDataAndBoundsBothHistories() {
        List<QualifyingWait.Sample> rows = evidence(NOW);
        rows.add(new QualifyingWait.Sample(NOW + 1, 120_000, new OfferSnapshot(1000, 2.0, 10, 2)));
        rows.add(new QualifyingWait.Sample(NOW - QualifyingWait.RETAIN_MS, 120_000, new OfferSnapshot(1000, 2.0, 10, 2)));
        assertEquals(24, EarningsModel.retained(rows, NOW).size());
        List<EarningsModel.Snapshot> ar = new ArrayList<>();
        for (int i = 0; i < 250; i++) ar.add(new EarningsModel.Snapshot(i % 101, NOW - i));
        ar.add(new EarningsModel.Snapshot(1, NOW + 1));
        ar.add(new EarningsModel.Snapshot(1, NOW - EarningsModel.AR_RETAIN_MS));
        assertEquals(200, EarningsModel.retainedAr(ar, NOW).size());
        assertEquals(NOW, EarningsModel.retainedAr(ar, NOW).get(199).at);
        QualifyingWait.Sample clipped = EarningsModel.retained(Collections.singletonList(new QualifyingWait.Sample(
                NOW - QualifyingWait.RETAIN_MS + 1000, 2000, null)), NOW).get(0);
        assertEquals(1000, clipped.observedMs);
    }

    @Test public void everyIndependentRuleAndLearningValueChangesIdentity() {
        String key = EarningsModel.settingsKey(RULES);
        assertNotEquals(key, EarningsModel.settingsKey(RULES.withMinimumScalePercent(99)));
        assertNotEquals(key, EarningsModel.settingsKey(RULES.withScoreByArea(true)));
        assertNotEquals(key, EarningsModel.settingsKey(RULES.withPerItem(10)));
        assertNotEquals(key, EarningsModel.settingsKey(RULES.withAdaptive(true)));
        AcceptedBest a = new AcceptedBest(0, 0, 0, 0, 0, 0, 2000, 3);
        AcceptedBest b = new AcceptedBest(0, 0, 0, 0, 0, 0, 2000, 4);
        assertNotEquals(EarningsModel.settingsKey(new FilterSettings(true, 1000, 0, 0, 0, 0, false, 0, a)),
                EarningsModel.settingsKey(new FilterSettings(true, 1000, 0, 0, 0, 0, false, 0, b)));
    }
}

package com.local.dasherfilter;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class QualifyingWaitTest {
    private static final long WALL = 1_800_000_000_000L;
    private static OfferSnapshot offer(int pay) { return new OfferSnapshot(pay, 3.0, 15, 2); }
    private static FilterSettings rules(int pay) { return new FilterSettings(true, pay, 0, 0, 0, 0, false, 0); }
    private static List<QualifyingWait.Sample> history() {
        List<QualifyingWait.Sample> rows = new ArrayList<>();
        for (int pay : new int[] {500, 1000, 1200, 1400, 700}) {
            rows.add(new QualifyingWait.Sample(WALL, 120_000, offer(pay)));
        }
        return rows;
    }
    private static void tick(QualifyingWait model, long from, long until) {
        for (long at = from; at <= until; at += 5_000) model.heartbeat(at, WALL + at);
    }

    @Test public void coldStartDoesNotInferWaitingFromOldOfferTimestamps() {
        QualifyingWait model = new QualifyingWait();
        model.offer(100, WALL, offer(1200), false);
        assertTrue(model.snapshot(WALL).isEmpty());
        assertEquals(QualifyingWait.Status.LEARNING, model.estimate(rules(1000), WALL).status);
    }
    @Test public void waitingDisplayEvidenceExpiresAndNeverSurvivesAnOfferStopOrRestart() {
        QualifyingWait model = new QualifyingWait();
        assertFalse(model.observingWaiting(0, WALL));
        model.waiting(0, WALL);
        assertTrue(model.observingWaiting(1000, WALL + 1000));
        assertFalse(model.observingWaiting(10_001, WALL + 10_001));
        model.offer(2000, WALL + 2000, offer(1200), false);
        assertFalse(model.observingWaiting(2000, WALL + 2000));
        model.waiting(3000, WALL + 3000);
        assertTrue(model.observingWaiting(3000, WALL + 3000));
        model.stop();
        assertFalse(model.observingWaiting(3000, WALL + 3000));
        QualifyingWait restarted = new QualifyingWait(model.snapshot(WALL + 3000), WALL + 3000);
        assertFalse(restarted.observingWaiting(3000, WALL + 3000));
    }

    @Test public void currentMinimumsRescoreTheSameNumericalHistory() {
        assertEquals(3, QualifyingWait.estimate(history(), rules(1000)).qualifying);
        assertEquals(5, QualifyingWait.estimate(history(), rules(500)).qualifying);
        assertEquals(QualifyingWait.Status.LEARNING, QualifyingWait.estimate(history(), rules(1500)).status);
    }
    @Test public void averageIncludesRightCensoredWaitingWithoutInventingAnotherArrival() {
        List<QualifyingWait.Sample> rows = history();
        rows.add(new QualifyingWait.Sample(WALL, 300_000, null));
        QualifyingWait.Estimate estimate = QualifyingWait.estimate(rows, rules(1000));
        assertEquals(QualifyingWait.Status.READY, estimate.status);
        assertEquals(3, estimate.qualifying);
        assertEquals(300_000, estimate.typicalMs);
        assertTrue(estimate.detail().contains("not a countdown or promise"));
    }
    @Test public void unreadableOffersSuppressNumericPredictionInsteadOfBecomingFailures() {
        List<QualifyingWait.Sample> rows = history();
        rows.add(new QualifyingWait.Sample(WALL, 60_000, OfferSnapshot.UNKNOWN));
        QualifyingWait.Estimate estimate = QualifyingWait.estimate(rows, rules(1000));
        assertEquals(QualifyingWait.Status.UNREADABLE, estimate.status);
        assertEquals(-1, estimate.typicalMs);
        assertEquals(1, estimate.unreadable);
    }
    @Test public void currentBufferAndAdaptiveFloorsAreNotASecondScoringFormula() {
        FilterSettings baseline = rules(1000);
        assertEquals(3, QualifyingWait.estimate(history(), baseline).qualifying);
        assertEquals(4, QualifyingWait.estimate(history(), baseline.withMinimumScalePercent(70)).qualifying);
        FilterSettings learned = new FilterSettings(true, 1000, 0, 0, 0, 0, true, 1200);
        assertEquals(1, QualifyingWait.estimate(history(), learned).qualifying);
        assertEquals(3, QualifyingWait.estimate(history(), learned.withAdaptive(false)).qualifying);
    }
    @Test public void compensatingAreaDecisionIsReusedExactly() {
        FilterSettings settings = new FilterSettings(true, 1000, 100, 0, 0, 0, false, 0);
        List<QualifyingWait.Sample> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) rows.add(new QualifyingWait.Sample(WALL, 120_000,
                new OfferSnapshot(800, 1.0, 15, 2)));
        assertEquals(0, QualifyingWait.estimate(rows, settings).qualifying);
        assertEquals(5, QualifyingWait.estimate(rows, settings.withScoreByArea(true)).qualifying);
    }
    @Test public void unavailableHotspotNeverBecomesAZeroDistance() {
        FilterSettings settings = rules(1000).withHotspotProximity(100);
        QualifyingWait.Estimate estimate = QualifyingWait.estimate(history(), settings);
        assertEquals(QualifyingWait.Status.UNREADABLE, estimate.status);
        assertEquals(-1, estimate.typicalMs);
    }
    @Test public void declaredMissingItemsNeverBecomeOrdinaryDelivery() {
        List<QualifyingWait.Sample> rows = history();
        rows.set(1, new QualifyingWait.Sample(WALL, 120_000, offer(1000).withItems(null, true)));
        QualifyingWait.Estimate estimate = QualifyingWait.estimate(rows, rules(500).withPerItem(100));
        assertEquals(QualifyingWait.Status.UNREADABLE, estimate.status);
    }
    @Test public void stoppedOrRestartedObservationCannotCountOffShiftOrDeliveryGap() {
        QualifyingWait model = new QualifyingWait();
        model.waiting(0, WALL);
        tick(model, 5_000, 60_000);
        model.stop();
        model.offer(3_600_000, WALL + 3_600_000, offer(1500), false);
        assertEquals(60_000, model.snapshot(WALL + 3_600_000).get(0).observedMs);
        assertNull(model.snapshot(WALL + 3_600_000).get(0).arrival);
        QualifyingWait restart = new QualifyingWait(model.snapshot(WALL + 3_600_000), WALL + 3_600_000);
        restart.offer(3_610_000, WALL + 3_610_000, offer(1500), false);
        assertEquals(1, restart.snapshot(WALL + 3_610_000).size());
    }
    @Test public void expiredCoverageDoesNotCountTheUnobservedGapOrItsOffer() {
        QualifyingWait model = new QualifyingWait();
        model.waiting(0, WALL);
        model.heartbeat(5_000, WALL + 5_000);
        model.offer(60_000, WALL + 60_000, offer(1000), false);
        List<QualifyingWait.Sample> rows = model.snapshot(WALL + 60_000);
        assertEquals(1, rows.size());
        assertEquals(5_000, rows.get(0).observedMs);
        assertNull(rows.get(0).arrival);
    }
    @Test public void acceptedDeliveryDoesNotAddOfferHandlingOrRouteTime() {
        QualifyingWait model = new QualifyingWait();
        model.waiting(0, WALL);
        tick(model, 5_000, 30_000);
        model.offer(30_000, WALL + 30_000, offer(1000), false);
        tick(model, 35_000, 45_000);
        model.stop();
        assertEquals(1, model.snapshot(WALL + 45_000).size());
        assertEquals(30_000, model.snapshot(WALL + 45_000).get(0).observedMs);
    }
    @Test public void rejectedOfferHandlingCountsWhenWaitingPositivelyResumes() {
        QualifyingWait model = new QualifyingWait();
        model.waiting(0, WALL);
        tick(model, 5_000, 30_000);
        model.offer(30_000, WALL + 30_000, offer(500), false);
        tick(model, 35_000, 45_000);
        model.waiting(45_000, WALL + 45_000);
        assertEquals(45_000, model.snapshot(WALL + 45_000).stream().mapToLong(row -> row.observedMs).sum());
    }
    @Test public void repeatedPartialAndFullReadsAreOneArrivalAndKnownFactsSurvive() {
        QualifyingWait model = new QualifyingWait();
        model.waiting(0, WALL);
        model.offer(5_000, WALL + 5_000, OfferSnapshot.UNKNOWN, false);
        model.offer(6_000, WALL + 6_000, offer(1000), true);
        model.offer(7_000, WALL + 7_000, OfferSnapshot.UNKNOWN, false);
        assertEquals(1, model.snapshot(WALL + 7_000).size());
        assertEquals(Integer.valueOf(1000), model.snapshot(WALL + 7_000).get(0).arrival.payCents);
    }
    @Test public void newCountdownInstanceIsAnotherArrivalEvenWithIdenticalFigures() {
        QualifyingWait model = new QualifyingWait();
        model.waiting(0, WALL);
        model.offer(5_000, WALL + 5_000, offer(1000), false);
        model.offer(10_000, WALL + 10_000, offer(1000), true);
        assertEquals(2, model.snapshot(WALL + 10_000).size());
    }
    @Test public void snapshotPersistsOnlyObservedExposureAndDoesNotAdvanceWithWallTime() {
        QualifyingWait model = new QualifyingWait();
        model.waiting(0, WALL);
        model.heartbeat(5_000, WALL + 5_000);
        assertEquals(5_000, model.snapshot(WALL + 60_000).get(0).observedMs);
        assertEquals(5_000, model.snapshot(WALL + 120_000).get(0).observedMs);
    }
    @Test public void backwardsMonotonicClockStopsInsteadOfCreatingNegativeTime() {
        QualifyingWait model = new QualifyingWait();
        model.waiting(10_000, WALL);
        model.heartbeat(15_000, WALL + 5_000);
        model.offer(5_000, WALL + 6_000, offer(1000), false);
        assertFalse(model.active());
        assertEquals(5_000, model.snapshot(WALL + 6_000).get(0).observedMs);
    }
    @Test public void boundedRetentionDropsOldAndFutureTimestamps() {
        List<QualifyingWait.Sample> rows = history();
        rows.add(new QualifyingWait.Sample(WALL + 1, 1, offer(1)));
        QualifyingWait model = new QualifyingWait(rows, WALL);
        assertEquals(5, model.snapshot(WALL).size());
        assertTrue(model.snapshot(WALL + QualifyingWait.RETAIN_MS).isEmpty());
        rows.clear();
        for (int i = 0; i < 500; i++) rows.add(new QualifyingWait.Sample(WALL, 1, offer(1)));
        assertEquals(200, new QualifyingWait(rows, WALL).snapshot(WALL).size());
    }
    @Test public void staleInMemoryTimerCannotResurrectExpiredHistory() {
        QualifyingWait model = new QualifyingWait();
        model.waiting(0, WALL);
        model.heartbeat(5_000, WALL + 5_000);
        assertTrue(model.snapshot(WALL + 5_000 + QualifyingWait.RETAIN_MS).isEmpty());
        assertFalse(model.active());
    }
    @Test public void intervalCrossingRetentionBoundaryContributesOnlyItsMeasuredOverlap() {
        long hour = 3_600_000L;
        List<QualifyingWait.Sample> rows = new ArrayList<>();
        rows.add(new QualifyingWait.Sample(WALL - 5 * hour, 23 * hour, offer(1000)));
        QualifyingWait model = new QualifyingWait(rows, WALL);
        assertEquals(19 * hour, model.snapshot(WALL).get(0).observedMs);
        assertEquals(18 * hour, model.snapshot(WALL + hour).get(0).observedMs);
        assertEquals(Integer.valueOf(1000), model.snapshot(WALL + hour).get(0).arrival.payCents);
    }
    @Test public void activeCensorCrossingRetentionCutoffAlsoClipsItsStart() {
        QualifyingWait model = new QualifyingWait();
        model.waiting(0, WALL);
        tick(model, 5_000, 23 * 3_600_000L);
        assertEquals(19 * 3_600_000L,
                model.snapshot(WALL + 28 * 3_600_000L).get(0).observedMs);
    }
    @Test public void clockRollbackCannotMixElapsedDurationWithDifferentWallClockEra() {
        QualifyingWait model = new QualifyingWait();
        model.waiting(0, WALL);
        model.heartbeat(5_000, WALL + 5_000);
        model.offer(10_000, WALL - 3_600_000L, offer(1000), false);
        assertFalse(model.active());
        assertTrue(model.snapshot(WALL - 3_600_000L).isEmpty());
    }
    @Test public void forwardWallClockJumpDoesNotCreateAnArrivalOrExtraWaiting() {
        QualifyingWait model = new QualifyingWait();
        model.waiting(0, WALL);
        model.heartbeat(5_000, WALL + 5_000);
        model.offer(10_000, WALL + 3_600_000L, offer(1000), false);
        assertFalse(model.active());
        assertEquals(1, model.snapshot(WALL + 3_600_000L).size());
        assertEquals(5_000, model.snapshot(WALL + 3_600_000L).get(0).observedMs);
        assertNull(model.snapshot(WALL + 3_600_000L).get(0).arrival);
    }
    @Test public void noMinimumsDoNotClaimAllUnreadOffersQualify() {
        assertEquals(QualifyingWait.Status.NO_RULES, QualifyingWait.estimate(history(), rules(0)).status);
    }
}

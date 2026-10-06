package com.local.dasherfilter;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class QualifyingWaitTest {
    private static final long WALL = 1_800_000_000_000L;
    private static OfferSnapshot offer(int pay) { return new OfferSnapshot(pay, 3.0, 15, 2); }
    private static FilterSettings rules(int pay) { return FilterSettings.of(true, pay, 0, 0, 0); }
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
    @Test public void unreadableArrivalsSuppressTheEstimateOnlyWhenTheyOutnumberReadableOnes() {
        // Five readable arrivals and one that lacks a fact: the usual thresholds decide, and its waiting counts.
        List<QualifyingWait.Sample> rows = history();
        rows.add(new QualifyingWait.Sample(WALL, 60_000, OfferSnapshot.UNKNOWN));
        QualifyingWait.Estimate estimate = QualifyingWait.estimate(rows, rules(1000));
        assertEquals(QualifyingWait.Status.READY, estimate.status);
        assertEquals(1, estimate.unreadable);
        assertEquals(5, estimate.readable);
        assertEquals(3, estimate.qualifying);
        assertEquals(660_000, estimate.observedMs);
        assertEquals(220_000, estimate.typicalMs);
        assertTrue(estimate.detail(), estimate.detail().contains(
                " 1 arrival lacked facts needed by your current minimums. Your current minimums are used."));
        assertFalse(estimate.detail(), estimate.detail().contains("learned"));
        assertFalse(estimate.detail(), estimate.detail().contains("area"));

        // As many unreadable as readable: still not suppressed.
        for (int i = 0; i < 4; i++) rows.add(new QualifyingWait.Sample(WALL, 60_000, OfferSnapshot.UNKNOWN));
        estimate = QualifyingWait.estimate(rows, rules(1000));
        assertEquals(5, estimate.unreadable);
        assertEquals(QualifyingWait.Status.READY, estimate.status);
        assertTrue(estimate.detail(), estimate.detail().contains(" 5 arrivals lacked facts"));

        // More unreadable than readable: no numerical estimate.
        rows.add(new QualifyingWait.Sample(WALL, 60_000, OfferSnapshot.UNKNOWN));
        estimate = QualifyingWait.estimate(rows, rules(1000));
        assertEquals(QualifyingWait.Status.UNREADABLE, estimate.status);
        assertEquals(-1, estimate.typicalMs);
        assertEquals(6, estimate.unreadable);
        assertTrue(estimate.detail(), estimate.detail().endsWith(
                " 6 arrivals lacked facts needed by your current minimums, so no wait is estimated."));
    }
    @Test public void unreadableArrivalsStillLeaveTheOtherThresholdsInCharge() {
        // Readable arrivals below the minimum sample: learning, and the detail says what lacked facts.
        List<QualifyingWait.Sample> rows = new ArrayList<>(history().subList(0, 4));
        rows.add(new QualifyingWait.Sample(WALL, 60_000, OfferSnapshot.UNKNOWN));
        QualifyingWait.Estimate estimate = QualifyingWait.estimate(rows, rules(1000));
        assertEquals(QualifyingWait.Status.LEARNING, estimate.status);
        assertTrue(estimate.detail(), estimate.detail().contains(
                " 1 arrival lacked facts needed by your current minimums. Needs at least 3 matches"));
        // With none unreadable the detail says nothing about it.
        assertFalse(QualifyingWait.estimate(history(), rules(1000)).detail().contains("lacked"));
    }
    @Test public void theCurrentBarIsNotASecondScoringFormula() {
        FilterSettings baseline = rules(1000);
        assertEquals(3, QualifyingWait.estimate(history(), baseline).qualifying);
        // At a 70% bar the minimum asks ⌈0.7 × $10.00⌉ = $7.00, which the $7.00 arrival meets.
        assertEquals(4, QualifyingWait.estimate(history(), baseline.withMinimumScalePercent(70)).qualifying);
        // At 130% it asks $13.00: only the $14.00 arrival.
        assertEquals(1, QualifyingWait.estimate(history(), baseline.withMinimumScalePercent(130)).qualifying);
    }
    @Test public void theSharedStrictRuleDecidesEachArrival() {
        // $8.00 for 1 mi and 15 min against $10.00 minimum pay and $1.00 per mile: every minimum must be met.
        FilterSettings settings = FilterSettings.of(true, 1000, 100, 0, 0);
        List<QualifyingWait.Sample> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) rows.add(new QualifyingWait.Sample(WALL, 120_000,
                new OfferSnapshot(800, 1.0, 15, 2)));
        assertEquals(0, QualifyingWait.estimate(rows, settings).qualifying);
        // ⌈bar × $10.00⌉ ≤ $8.00 exactly up to an 80% bar.
        assertEquals(5, QualifyingWait.estimate(rows, settings.withMinimumScalePercent(80)).qualifying);
        assertEquals(0, QualifyingWait.estimate(rows, settings.withMinimumScalePercent(81)).qualifying);
    }
    @Test public void aRetiredHotspotDistanceIsNeverPartOfAnArrival() {
        @SuppressWarnings("deprecation")
        OfferSnapshot withDistance = offer(1000).withFinalStopHotspotMiles(2.0);
        QualifyingWait.Sample sample = new QualifyingWait.Sample(WALL, 120_000, withDistance);
        assertNull(sample.arrival.finalStopHotspotMiles);
        assertEquals(offer(1000).fingerprint(), sample.arrival.fingerprint());

        // A reread that differs only by that distance is the same arrival, and keeps none.
        QualifyingWait model = new QualifyingWait();
        model.waiting(0, WALL);
        model.offer(5_000, WALL + 5_000, offer(1000), false);
        model.offer(6_000, WALL + 6_000, withDistance, false);
        List<QualifyingWait.Sample> rows = model.snapshot(WALL + 6_000);
        assertEquals(1, rows.size());
        assertNull(rows.get(0).arrival.finalStopHotspotMiles);
        assertEquals(Integer.valueOf(1000), rows.get(0).arrival.payCents);
    }
    @Test public void anUnreadItemCountNoLongerMakesAnArrivalUnreadable() {
        // No rule uses items any more: a shopping offer with its count unread is judged on pay, miles and minutes.
        List<QualifyingWait.Sample> rows = history();
        rows.set(1, new QualifyingWait.Sample(WALL, 120_000, offer(1000).withItems(null, true)));
        QualifyingWait.Estimate estimate = QualifyingWait.estimate(rows, rules(500));
        assertEquals(QualifyingWait.Status.READY, estimate.status);
        assertEquals(0, estimate.unreadable);
        assertEquals(5, estimate.qualifying);
        assertTrue(rows.get(1).arrival.itemCountApplicable);
        assertNull(rows.get(1).arrival.items);
    }
    @Test public void anArrivalNeverKeepsTheBoundOnUnknownPay() {
        OfferSnapshot bounded = new OfferSnapshot(null, 3.0, 15, 2, 900);
        QualifyingWait.Sample sample = new QualifyingWait.Sample(WALL, 60_000, bounded);
        assertNull(sample.arrival.payAtMostCents);
        assertNull(sample.arrival.payCents);
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

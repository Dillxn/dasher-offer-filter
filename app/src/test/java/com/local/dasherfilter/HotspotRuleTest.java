package com.local.dasherfilter;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.*;

/** The fifth spoke is reciprocal final-stop distance, not payout or the driver's current location. */
public final class HotspotRuleTest {
    private static FilterSettings proximity(int hundredths) {
        return new FilterSettings(true, 0, 0, 0, 0, 0).withHotspotProximity(hundredths);
    }

    private static OfferSnapshot offer(Double distance) {
        return new OfferSnapshot(1000, 4.0, 20, 2).withFinalStopHotspotMiles(distance);
    }

    @Test public void distanceValidatesZeroAndRejectsInvalidOrAbsentObservations() {
        assertEquals(Double.valueOf(0), offer(0.0).finalStopHotspotMiles);
        for (Double value : new Double[] {null, -1.0, Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY}) {
            assertNull(offer(value).finalStopHotspotMiles);
        }
        assertNull(new OfferSnapshot(1000, 4.0, 20, 2).finalStopHotspotMiles);
    }

    @Test public void changingHotspotsDoesNotChangeOfferIdentityOrContradictItsFacts() {
        OfferSnapshot before = offer(0.5);
        OfferSnapshot after = offer(8.0);
        assertEquals(before.fingerprint(), after.fingerprint());
        assertTrue(before.agreesWith(after));
        assertFalse(before.contradicts(after));
        assertTrue(before.contradicts(new OfferSnapshot(1001, 4.0, 20, 2).withFinalStopHotspotMiles(0.5)));
    }

    @Test public void removingPayCeilingPreservesIndependentObservation() {
        OfferSnapshot bounded = new OfferSnapshot(null, 4.0, 20, 2, 1000, 0.5);
        OfferSnapshot unbounded = bounded.withoutPayBound();
        assertNull(unbounded.payCents);
        assertNull(unbounded.payAtMostCents);
        assertEquals(Double.valueOf(0.5), unbounded.finalStopHotspotMiles);
    }

    @Test public void strictProximityHasInclusiveExactBoundaryWithoutReciprocalRounding() {
        FilterSettings rules = proximity(50); // 0.50 /mi: no farther than 2 mi.
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(offer(2.0), rules).result);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(offer(0.0), rules).result);
        OfferRule.Decision failure = OfferRule.evaluate(offer(Math.nextUp(2.0)), rules);
        assertEquals(OfferRule.Result.DECLINE, failure.result);
        assertEquals(0, failure.requiredCents);
        assertTrue(failure.reason.contains("nearest hotspot"));
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(offer(Math.nextDown(2.0)), rules).result);
    }

    @Test public void activeUnknownDistanceNeedsReviewAndOffLeavesLegacyBehaviorAlone() {
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(offer(null), proximity(50)).result);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(offer(null), proximity(0)).result);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(offer(100.0), proximity(0)).result);
    }

    @Test public void knownFailuresStillDeclineWhenAnotherActiveFactIsMissing() {
        FilterSettings rules = new FilterSettings(true, 1000, 0, 0, 0, 0).withHotspotProximity(50);
        OfferSnapshot payUnknown = new OfferSnapshot(null, null, null, null).withFinalStopHotspotMiles(3.0);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(payUnknown, rules).result);
        OfferSnapshot hotspotUnknown = new OfferSnapshot(999, 4.0, 20, 2);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(hotspotUnknown, rules).result);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(offer(null), rules).result);
    }

    @Test public void onlyHotspotRuleCanProveFailureButNeverPassUnreadPayInEitherMode() {
        OfferSnapshot unknownPay = new OfferSnapshot(null, null, null, null).withFinalStopHotspotMiles(1.0);
        for (boolean area : new boolean[] {false, true}) {
            FilterSettings rules = proximity(50).withScoreByArea(area);
            assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(unknownPay, rules).result);
            OfferRule.Decision failure = OfferRule.evaluate(unknownPay.withFinalStopHotspotMiles(3.0), rules);
            assertEquals(OfferRule.Result.DECLINE, failure.result);
            assertEquals(0, failure.requiredCents);
            assertFalse(failure.summary().contains("$"));
        }
    }

    @Test public void areaWithUnknownActiveDistanceCannotPassOrFailFromPayoutAlone() {
        FilterSettings rules = new FilterSettings(true, 1000, 0, 0, 0, 0)
                .withHotspotProximity(50).withScoreByArea(true);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(offer(null), rules).result);
        assertEquals(OfferRule.Result.REVIEW,
                OfferRule.evaluate(new OfferSnapshot(1, 4.0, 20, 2), rules).result);
        assertEquals(OfferRule.Result.DECLINE,
                OfferRule.evaluate(new OfferSnapshot(1, 4.0, 20, 4), rules.withMaxStops(3)).result);
    }

    @Test public void addOnCannotInheritThePreviousRoutesEndpointOrInferOneFromTotals() {
        OfferSnapshot route = new OfferSnapshot(1200, 4.0, 20, 2).withFinalStopHotspotMiles(0.0);
        AddOnOffer addOn = AddOnOffer.parse(route,
                Arrays.asList("Add to route", "+$5.00", "adds 2 mi", "adds 10 min", "adds 2 stops"));
        assertNull(addOn.combined.finalStopHotspotMiles);
        assertNull(addOn.incremental.finalStopHotspotMiles);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluateAddOn(addOn, proximity(50)).result);
        FilterSettings knownFailure = new FilterSettings(true, 1800, 0, 0, 0, 0).withHotspotProximity(50);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluateAddOn(addOn, knownFailure).result);
    }

    @Test public void onlyMissingHotspotDoesNotGenerateAnAutomaticUnreadableReport() {
        FilterSettings rules = new FilterSettings(true, 800, 100, 20, 200, 0).withHotspotProximity(50);
        assertTrue(OfferRule.onlyHotspotMissing(offer(null), null, rules));
        assertTrue(OfferRule.onlyHotspotMissing(offer(null), null, rules.withScoreByArea(true)));
        assertFalse(OfferRule.onlyHotspotMissing(offer(null), null, rules.withHotspotProximity(0)));
        assertFalse(OfferRule.onlyHotspotMissing(offer(0.5), null, rules));
        assertFalse(OfferRule.onlyHotspotMissing(new OfferSnapshot(1000, null, 20, 2), null, rules));
        assertFalse(OfferRule.onlyHotspotMissing(new OfferSnapshot(null, 4.0, 20, 2), null, rules));
        assertFalse(OfferRule.onlyHotspotMissing(OfferSnapshot.UNKNOWN, null, proximity(50)));
        // The helper does not suppress a real rule failure: the caller only asks it for unreadable reports.
        assertTrue(OfferRule.onlyHotspotMissing(new OfferSnapshot(100, null, 20, 2), null, rules));
    }

    @Test public void addonReportSuppressionUsesCombinedEndpointAndPreservesOtherUnreadableEvidence() {
        OfferSnapshot route = new OfferSnapshot(1200, 4.0, 20, 2).withFinalStopHotspotMiles(0.0);
        FilterSettings rules = new FilterSettings(true, 800, 100, 20, 100, 0).withHotspotProximity(50);
        AddOnOffer complete = AddOnOffer.parse(route,
                Arrays.asList("Add to route", "+$5.00", "adds 2 mi", "adds 10 min", "adds 2 stops"));
        // The offer argument's old endpoint must never stand in for the combined route's unknown endpoint.
        assertTrue(OfferRule.onlyHotspotMissing(route, complete, rules));
        AddOnOffer missingMiles = AddOnOffer.parse(route,
                Arrays.asList("Add to route", "+$5.00", "adds 10 min", "adds 2 stops"));
        assertFalse(OfferRule.onlyHotspotMissing(route, missingMiles, rules));
        AddOnOffer missingPay = AddOnOffer.parse(route,
                Arrays.asList("Add to route", "adds 2 mi", "adds 10 min", "adds 2 stops"));
        assertFalse(OfferRule.onlyHotspotMissing(route, missingPay, rules));
        assertFalse(OfferRule.onlyHotspotMissing(route, complete, rules.withHotspotProximity(0)));
        AddOnOffer onlyCombinedPayKnown = AddOnOffer.parse(OfferSnapshot.UNKNOWN,
                Arrays.asList("Add to route", "New total pay $17.00", "New total 6 mi", "New total 4 stops"));
        assertNotNull(onlyCombinedPayKnown.combined.payCents);
        assertNull(onlyCombinedPayKnown.incremental.payCents);
        assertFalse(OfferRule.onlyHotspotMissing(route, onlyCombinedPayKnown, proximity(50)));
    }

    @Test public void descriptionsUseReciprocalDistanceUnitsNeverMoney() {
        FilterSettings rules = proximity(50);
        assertEquals("0.5 /mi", FilterSettings.proximityLabel(50));
        assertTrue(rules.describe().contains("final stop to nearest hotspot"));
        assertFalse(rules.describe().contains("$"));
        assertFalse(rules.brief().contains("$"));
    }
}

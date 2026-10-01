package com.local.dasherfilter;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Independent reciprocal-distance scoring, including the sparse geometry and cent boundaries it introduces. */
public final class AreaScoreHotspotTest {
    private static FilterSettings equalMoney() {
        // On this suite's ordinary offer, each original spoke asks exactly $10.
        return new FilterSettings(true, 1000, 200, 50, 500, 0);
    }

    private static OfferSnapshot offer(Integer pay, Double hotspotMiles) {
        return new OfferSnapshot(pay, 5.0, 20, 2).withFinalStopHotspotMiles(hotspotMiles);
    }

    private static AreaScore.Floors floors(FilterSettings rules, Double hotspotMiles) {
        return AreaScore.floors(rules, offer(1000, hotspotMiles));
    }

    @Test
    public void reciprocalSpokeIsIndependentOfPayAndHasNoInventedMonetaryFloor() {
        AreaScore.Floors f = floors(equalMoney().withHotspotProximity(100), 0.5);
        assertTrue(f.readable());
        assertNull(f.cents[AreaScore.HOTSPOT]);
        assertEquals(2, AreaScore.ratios(f, 100)[AreaScore.HOTSPOT], 0);
        assertEquals(2, AreaScore.ratios(f, 5000)[AreaScore.HOTSPOT], 0);
        assertEquals(0.5, AreaScore.ratios(floors(equalMoney().withHotspotProximity(100), 2.0), 1000)
                [AreaScore.HOTSPOT], 0);
        assertEquals(1, AreaScore.ratios(floors(equalMoney().withHotspotProximity(200), 0.5), 1000)
                [AreaScore.HOTSPOT], 0);
    }

    @Test
    public void independentAxisMakesAreaQuadraticPlusLinearInPay() {
        AreaScore.Floors f = floors(equalMoney().withHotspotProximity(100), 1.0);
        // Three monetary-monetary edges contribute r²; the two hotspot edges contribute r.
        assertEquals(1, AreaScore.score(f, 1000), 0);
        assertEquals(Math.sqrt(16 / 5.0), AreaScore.score(f, 2000), 1e-12);
        assertEquals(1000, AreaScore.requiredPay(f));
        assertFalse(AreaScore.reaches(f, 999));
        assertTrue(AreaScore.reaches(f, 1000));
        assertEquals(99, AreaScore.percent(f, 999));
        assertEquals(100, AreaScore.percent(f, 1000));
    }

    @Test
    public void pureAreaStillAllowsCompensationBothWays() {
        AreaScore.Floors near = floors(equalMoney().withHotspotProximity(100), 0.5);
        // 3r² + 4r >= 5: the first passing cent is $7.87, below every monetary floor.
        assertEquals(787, AreaScore.requiredPay(near));
        assertFalse(AreaScore.reaches(near, 786));
        assertTrue(AreaScore.reaches(near, 787));

        AreaScore.Floors far = floors(equalMoney().withHotspotProximity(100), 2.0);
        // 3r² + r >= 5: $11.36 compensates for half the requested proximity.
        assertEquals(1136, AreaScore.requiredPay(far));
        assertFalse(AreaScore.reaches(far, 1135));
        assertTrue(AreaScore.reaches(far, 1136));
    }

    @Test
    public void twoSpokesUseAnExactDecimalDistanceBoundary() {
        FilterSettings rules = new FilterSettings(true, 1000, 0, 0, 0, 0).withHotspotProximity(333);
        AreaScore.Floors f = floors(rules, 0.1);
        // Ratio product is (pay / 1000) × (1 / .333): exactly $3.33 reaches 100%.
        assertEquals(333, AreaScore.requiredPay(f));
        assertTrue(AreaScore.reaches(f, 333));
        assertFalse(AreaScore.reaches(f, 332));
        assertEquals(1, AreaScore.score(f, 333), 1e-12);
        assertEquals(99, AreaScore.percent(f, 332));
    }

    @Test
    public void hotspotAloneNeedsNoPayAndCannotBeFixedByMorePay() {
        FilterSettings rules = new FilterSettings(true, 0, 0, 0, 0, 0).withHotspotProximity(100);
        AreaScore.Floors near = floors(rules, 0.5), far = floors(rules, 2.0);
        assertFalse(near.needsPay());
        assertEquals(200, AreaScore.percent(rules, offer(null, 0.5)));
        assertEquals(0, AreaScore.requiredPay(near));
        assertTrue(AreaScore.reaches(near, 0));
        assertEquals(50, AreaScore.percent(rules, offer(null, 2.0)));
        assertEquals(Long.MAX_VALUE, AreaScore.requiredPay(far));
        assertFalse(AreaScore.reaches(far, Long.MAX_VALUE));
    }

    @Test
    public void unknownDistanceNeverMeansZeroAndAnOffAxisNeedsNoDistance() {
        FilterSettings on = equalMoney().withHotspotProximity(100);
        for (Double missing : new Double[] {null, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            AreaScore.Floors f = floors(on, missing);
            assertFalse(f.readable());
            assertTrue(Double.isNaN(AreaScore.score(f, 1000)));
            assertFalse(AreaScore.reaches(f, 1000));
            assertEquals(-1, AreaScore.percent(f, 1000));
            assertEquals(0, AreaScore.requiredPay(f));
        }
        assertTrue(floors(equalMoney(), null).readable());
        assertEquals(100, AreaScore.percent(equalMoney(), offer(1000, null)));
        assertEquals(-1, AreaScore.percent(on, offer(null, 0.5)));
    }

    @Test
    public void exactZeroIsInfiniteButZeroPayHasZeroMixedArea() {
        FilterSettings only = new FilterSettings(true, 0, 0, 0, 0, 0).withHotspotProximity(100);
        AreaScore.Floors alone = floors(only, 0.0);
        assertTrue(alone.readable());
        assertEquals(Double.POSITIVE_INFINITY, AreaScore.score(alone, 0), 0);
        assertTrue(AreaScore.reaches(alone, 0));
        assertEquals(0, AreaScore.requiredPay(alone));

        AreaScore.Floors mixed = floors(equalMoney().withHotspotProximity(100), 0.0);
        assertEquals(0, AreaScore.score(mixed, 0), 0);
        assertFalse(AreaScore.reaches(mixed, 0));
        assertEquals(Double.POSITIVE_INFINITY, AreaScore.score(mixed, 1), 0);
        assertTrue(AreaScore.reaches(mixed, 1));
        assertEquals(1, AreaScore.requiredPay(mixed));
        assertEquals(0, AreaScore.percent(mixed, 0));
        assertEquals(Integer.MAX_VALUE, AreaScore.percent(mixed, 1));
    }

    @Test
    public void drawnOrderAndSparseCenterClosureKeepImprovementsMonotone() {
        assertArrayEquals(new int[] {0, 4, 1, 2, 3}, AreaScore.DRAW_ORDER);
        assertArrayEquals(new float[] {-150, -30, 30, 150, -90}, AreaScore.ANGLES, 0);
        FilterSettings upper = new FilterSettings(true, 1000, 200, 0, 0, 0).withHotspotProximity(100);
        AreaScore.Floors f = floors(upper, 1.0);
        assertArrayEquals(new int[] {0, 4, 1}, f.activeAxes());
        List<int[]> pairs = AreaScore.pairs(new int[] {0, 1, 4});
        assertEquals(2, pairs.size());
        assertArrayEquals(new int[] {0, 4}, pairs.get(0));
        assertArrayEquals(new int[] {4, 1}, pairs.get(1));
        assertTrue(AreaScore.closesThroughCenter(AreaScore.MILE, AreaScore.PAY));
        assertFalse(AreaScore.closesThroughCenter(AreaScore.PAY, AreaScore.HOTSPOT));
        assertEquals(Math.sqrt(2), AreaScore.score(f, 2000), 1e-12);
        assertEquals(1000, AreaScore.requiredPay(f));
        assertTrue(AreaScore.score(floors(upper, 0.5), 1000) > AreaScore.score(f, 1000));
    }

    @Test
    public void everyActiveSubsetHasAUnitBaselineAndMonotonePayAndProximity() {
        for (int mask = 1; mask < (1 << AreaScore.AXES); mask++) {
            FilterSettings rules = new FilterSettings(true, (mask & 1) == 0 ? 0 : 1000,
                    (mask & 2) == 0 ? 0 : 200, (mask & 4) == 0 ? 0 : 50,
                    (mask & 8) == 0 ? 0 : 500, 0).withHotspotProximity((mask & 16) == 0 ? 0 : 100);
            AreaScore.Floors at = floors(rules, 1.0);
            assertEquals("mask " + mask, 1, AreaScore.score(at, 1000), 0);
            assertTrue("mask " + mask, AreaScore.reaches(at, 1000));
            assertEquals("mask " + mask, at.needsPay() ? 1000 : 0, AreaScore.requiredPay(at));
            assertTrue("pay, mask " + mask, AreaScore.score(at, 2000) >= AreaScore.score(at, 1000));
            assertTrue("proximity, mask " + mask,
                    AreaScore.score(floors(rules, 0.5), 1000) >= AreaScore.score(at, 1000));
        }
    }

    @Test
    public void fifthOffPreservesOldNumericExamplesAndIgnoresNewObservation() {
        FilterSettings old = new FilterSettings(true, 1300, 385, 41, 475, 3);
        OfferSnapshot example = new OfferSnapshot(1500, 6.0, 25, 2);
        for (Double distance : new Double[] {null, 0.0, 0.5, 100.0}) {
            AreaScore.Floors f = AreaScore.floors(old, example.withFinalStopHotspotMiles(distance));
            assertEquals(1.207480540824083, AreaScore.score(f, 1500), 1e-12);
            assertEquals(1243, AreaScore.requiredPay(f));
            assertEquals(121, AreaScore.percent(f, 1500));
            assertFalse(AreaScore.reaches(f, 1242));
            assertTrue(AreaScore.reaches(f, 1243));
        }
        FilterSettings three = new FilterSettings(true, 1300, 385, 41, 0, 0);
        AreaScore.Floors f = AreaScore.floors(three, example);
        assertEquals(0.9218242761510783, AreaScore.score(f, 1500), 1e-12);
        assertEquals(1628, AreaScore.requiredPay(f));
    }
}

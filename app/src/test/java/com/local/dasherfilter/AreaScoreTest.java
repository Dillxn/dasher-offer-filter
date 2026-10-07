package com.local.dasherfilter;

import java.math.BigDecimal;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What the minimums ask of an offer, exactly (R100, the partial known ask, θ and the score), and the constellation's
 * stable drawing geometry, which stays while the retired axes keep their reserved indexes.
 */
public final class AreaScoreTest {
    /** The README's rules as 0.5.0 keeps them: $13 pay, $3.85 a mile, 41¢ a minute ($24.60 an hour). */
    private static final FilterSettings USER = FilterSettings.of(true, 1300, 385, 41, 3);

    private static BigDecimal cents(String value) {
        return new BigDecimal(value);
    }

    private static void same(BigDecimal expected, BigDecimal actual) {
        assertEquals(expected + " vs " + actual, 0, expected.compareTo(actual));
    }

    @Test
    public void theAxesKeepTheirStableIndexesAnglesAndDrawnOrder() {
        assertEquals(0, AreaScore.PAY);
        assertEquals(1, AreaScore.MILE);
        assertEquals(2, AreaScore.MINUTE);
        assertEquals(3, AreaScore.STOP);
        assertEquals(4, AreaScore.HOTSPOT);
        assertEquals(5, AreaScore.ITEM);
        assertEquals(6, AreaScore.AXES);
        assertArrayEquals(new float[] {-150, -30, 30, 150, -90, 90}, AreaScore.ANGLES, 0f);
        assertArrayEquals(new int[] {AreaScore.PAY, AreaScore.HOTSPOT, AreaScore.MILE, AreaScore.MINUTE,
                AreaScore.ITEM, AreaScore.STOP}, AreaScore.DRAW_ORDER);
        // Drawing geometry only: pay–mile and mile–minute are neighbours; minute and pay stand opposite, so the shape
        // closes through the centre there.
        assertTrue(AreaScore.closesThroughCenter(AreaScore.MINUTE, AreaScore.PAY));
        assertFalse(AreaScore.closesThroughCenter(AreaScore.PAY, AreaScore.MILE));
        assertFalse(AreaScore.closesThroughCenter(AreaScore.MILE, AreaScore.MINUTE));
    }

    @Test
    public void theRequirementIsTheHighestSetAskWorkedExactly() {
        // $15.00, 6 mi, 25 min: $13.00, 6 × $3.85 = $23.10, 25 × 41¢ = $10.25.
        OfferSnapshot offer = new OfferSnapshot(1500, 6.0, 25, 2);
        same(cents("2310"), AreaScore.required100(USER, offer));
        same(cents("2310"), AreaScore.knownRequired100(USER, offer));
        AreaScore.Asks asks = AreaScore.asks(USER, offer);
        assertEquals(AreaScore.MILE, asks.axis);
        assertFalse(asks.missing);
        assertEquals("⌊150000 ÷ 2310⌋", 64, AreaScore.scorePercent(USER, offer));
        assertEquals(64, AreaScore.passThreshold(USER, offer));
        assertEquals(64, AreaScore.percent(USER, offer));
        assertEquals("Score 64%", AreaScore.label(64));
        // Max stops never changes the score; over it, θ is 0.
        assertEquals(64, AreaScore.scorePercent(USER, new OfferSnapshot(1500, 6.0, 25, 4)));
        assertEquals(0, AreaScore.passThreshold(USER, new OfferSnapshot(1500, 6.0, 25, 4)));
        // A minimum that is off asks nothing, so pay and per hour decide a short trip.
        FilterSettings noMile = FilterSettings.of(true, 1300, 0, 41, 0);
        same(cents("1300"), AreaScore.required100(noMile, offer));
        assertEquals(AreaScore.PAY, AreaScore.asks(noMile, offer).axis);
        same(cents("1640"), AreaScore.required100(noMile, new OfferSnapshot(1500, 6.0, 40, 2)));
    }

    @Test
    public void anUnreadQuantityLeavesOnlyAPartialRequirement() {
        OfferSnapshot noMiles = new OfferSnapshot(1500, null, 25, 2);
        assertNull(AreaScore.required100(USER, noMiles));
        same(cents("1300"), AreaScore.knownRequired100(USER, noMiles));
        assertTrue(AreaScore.asks(USER, noMiles).missing);
        assertEquals(AreaScore.PAY, AreaScore.asks(USER, noMiles).axis);
        assertEquals(-1, AreaScore.scorePercent(USER, noMiles));
        assertEquals("⌊150000 ÷ 1300⌋", 115, AreaScore.passThreshold(USER, noMiles));
        // With only rate minimums and nothing read there is nothing known at all.
        FilterSettings rates = FilterSettings.of(true, 0, 385, 41, 0);
        OfferSnapshot nothing = new OfferSnapshot(1500, null, null, 2);
        assertNull(AreaScore.knownRequired100(rates, nothing));
        assertEquals(-1, AreaScore.asks(rates, nothing).axis);
        assertEquals(Integer.MAX_VALUE, AreaScore.passThreshold(rates, nothing));
        // Unread stops matter to max stops only, never to the requirement or the score.
        OfferSnapshot noStops = new OfferSnapshot(1500, 6.0, 25, null);
        same(cents("2310"), AreaScore.required100(USER, noStops));
        assertEquals(64, AreaScore.scorePercent(USER, noStops));
    }

    @Test
    public void milesStayExactDecimals() {
        // 2.1 mi × $3.85 is 808.5 cents exactly; 1.0301 mi × $1.00 is 103.01.
        same(cents("808.5"), AreaScore.required100(FilterSettings.of(true, 0, 385, 0, 0),
                new OfferSnapshot(809, 2.1, 16, 2)));
        same(cents("103.01"), AreaScore.fixedFloor(AreaScore.MILE, 100, new OfferSnapshot(100, 1.0301, 1, 2)));
        assertEquals(809, AreaScore.roundedCents(cents("808.5"), 100));
        assertEquals("103.01 × 0.97 = 99.9197", 100, AreaScore.roundedCents(cents("103.01"), 97));
        assertEquals(104, AreaScore.roundedCents(cents("103.01"), 100));
        assertEquals(Long.MAX_VALUE, AreaScore.roundedCents(cents("1e300"), 100));
        assertEquals(0, AreaScore.roundedCents(BigDecimal.ZERO, 150));
    }

    @Test
    public void theFixedAsksAreProductsOfARateAndAReadAmount() {
        OfferSnapshot offer = new OfferSnapshot(1500, 6.0, 25, 3);
        same(cents("1300"), AreaScore.fixedFloor(AreaScore.PAY, 1300, offer));
        same(cents("2310"), AreaScore.fixedFloor(AreaScore.MILE, 385, offer));
        same(cents("1025"), AreaScore.fixedFloor(AreaScore.MINUTE, 41, offer));
        assertNull("off", AreaScore.fixedFloor(AreaScore.MILE, 0, offer));
        assertNull("unread", AreaScore.fixedFloor(AreaScore.MINUTE, 41, new OfferSnapshot(1500, 6.0, null, 3)));
        // The retired axes keep their reserved indexes and ask nothing, whatever a stale caller passes.
        OfferSnapshot shopping = new OfferSnapshot(1500, 6.0, 25, 3).withItems(12, true);
        for (int retired : new int[] {AreaScore.STOP, AreaScore.HOTSPOT, AreaScore.ITEM}) {
            assertNull("the reserved axis " + retired + " asks nothing", AreaScore.fixedFloor(retired, 100, shopping));
        }
        same(BigDecimal.ZERO, AreaScore.required100(FilterSettings.of(true, 0, 385, 0, 0),
                new OfferSnapshot(1500, 0.0, 25, 2)));
    }

    @Test
    public void aPlusCeilingGivesThetaOnlyBelowOneHundredPercent() {
        FilterSettings rules = FilterSettings.of(true, 1000, 100, 0, 3);
        assertEquals("⌊100 × 835 ÷ 1000⌋", 83,
                AreaScore.passThreshold(rules, new OfferSnapshot(null, 7.1, 23, 2, 835)));
        assertEquals("a ceiling at the minimums never declines", Integer.MAX_VALUE,
                AreaScore.passThreshold(rules, new OfferSnapshot(null, 7.1, 23, 2, 1000)));
        assertEquals(99, AreaScore.passThreshold(rules, new OfferSnapshot(null, 7.1, 23, 2, 999)));
        assertEquals("per mile binds: ⌊100 × 1110 ÷ 1200⌋", 92,
                AreaScore.passThreshold(rules, new OfferSnapshot(null, 12.0, 30, 2, 1110)));
        assertEquals(-1, AreaScore.scorePercent(rules, new OfferSnapshot(null, 7.1, 23, 2, 835)));
    }

    @Test
    public void aTinyRequirementSaturatesTheScoreRatherThanOverflowing() {
        FilterSettings perMile = FilterSettings.of(true, 0, 1, 0, 0);
        OfferSnapshot almostThere = new OfferSnapshot(100_000, 1e-9, 10, 2);
        assertEquals(Integer.MAX_VALUE, AreaScore.scorePercent(perMile, almostThere));
        assertEquals(Integer.MAX_VALUE, AreaScore.passThreshold(perMile, almostThere));
        assertEquals(0, AreaScore.scorePercent(FilterSettings.of(true, 100_000, 0, 0, 0),
                new OfferSnapshot(0, 6.0, 25, 2)));
        assertEquals(0, AreaScore.passThreshold(FilterSettings.of(true, 100_000, 0, 0, 0),
                new OfferSnapshot(0, 6.0, 25, 2)));
    }
}

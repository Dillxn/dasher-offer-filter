package com.local.dasherfilter;

import java.math.BigDecimal;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The 0.5.0 requirement, exactly: an offer needs ⌈bar × max(flat, per mile × miles, per minute × minutes) ÷ 100⌉ cents,
 * its score is ⌊100 × pay ÷ R100⌋, and θ is the largest bar at which it is not declined. The vectors are the spec's,
 * with the starter minimums: $4.00, $1.00 a mile and 25¢ a minute ($15 an hour).
 */
public final class RequirementTest {
    private static final FilterSettings STARTERS = FilterSettings.of(true, 400, 100, 25, 0);

    private static OfferSnapshot offer(Integer pay, Double miles, Integer minutes) {
        return new OfferSnapshot(pay, miles, minutes, 2);
    }

    private static OfferRule.Decision at(int bar, OfferSnapshot offer) {
        return OfferRule.evaluate(offer, STARTERS.withMinimumScalePercent(bar));
    }

    @Test
    public void fivePointNineMilesAndTwentyFiveMinutesAsk625At100And513At82And938At150() {
        OfferSnapshot offer = offer(625, 5.9, 25);
        // $4.00, 5.9 × $1.00 = $5.90, 25 × 25¢ = $6.25: per hour binds.
        assertEquals(0, BigDecimal.valueOf(625).compareTo(AreaScore.required100(STARTERS, offer)));
        assertEquals(625, at(100, offer).requiredCents);
        assertEquals("⌈0.82 × 625⌉ = ⌈512.5⌉", 513, at(82, offer).requiredCents);
        assertEquals("⌈1.50 × 625⌉ = ⌈937.5⌉", 938, at(150, offer).requiredCents);
        assertEquals("dollars per hour", at(100, offer(624, 5.9, 25)).reason);
    }

    @Test
    public void at82ACentShortDeclinesAndTheRequirementPasses() {
        OfferRule.Decision short1 = at(82, offer(512, 5.9, 25));
        assertEquals(OfferRule.Result.DECLINE, short1.result);
        assertEquals(513, short1.requiredCents);
        assertEquals("82% bar: dollars per hour", short1.reason);
        assertEquals(81, short1.scorePercent);
        OfferRule.Decision meets = at(82, offer(513, 5.9, 25));
        assertEquals(OfferRule.Result.KEEP, meets.result);
        assertEquals("⌊51300 ÷ 625⌋", 82, meets.scorePercent);
        assertTrue(meets.belowMinimums);
    }

    @Test
    public void scoresNinetyNineAndOneHundredAtTheEdgeOfTheMinimums() {
        OfferRule.Decision short1 = at(100, offer(624, 5.9, 25));
        assertEquals(99, short1.scorePercent);
        assertEquals(OfferRule.Result.DECLINE, short1.result);
        OfferRule.Decision meets = at(100, offer(625, 5.9, 25));
        assertEquals(100, meets.scorePercent);
        assertEquals(OfferRule.Result.KEEP, meets.result);
        assertEquals("meets your minimums", meets.reason);
        assertFalse(meets.belowMinimums);
        assertEquals(99, AreaScore.scorePercent(STARTERS, offer(624, 5.9, 25)));
        assertEquals(100, AreaScore.scorePercent(STARTERS, offer(625, 5.9, 25)));
    }

    @Test
    public void aPartialRequirementGivesThetaFromWhatWasRead() {
        // $8.00, miles unread, 30 min, per mile set: known asks are $4.00 and 30 × 25¢ = $7.50.
        OfferSnapshot offer = offer(800, null, 30);
        assertNull(AreaScore.required100(STARTERS, offer));
        assertEquals(0, BigDecimal.valueOf(750).compareTo(AreaScore.knownRequired100(STARTERS, offer)));
        assertEquals("⌊80000 ÷ 750⌋", 106, AreaScore.passThreshold(STARTERS, offer));
        assertEquals("a needed quantity is unread", -1, AreaScore.scorePercent(STARTERS, offer));
        OfferRule.Decision at107 = at(107, offer);
        assertEquals(OfferRule.Result.DECLINE, at107.result);
        assertEquals("⌈1.07 × 750⌉ = $8.03", 803, at107.requiredCents);
        assertEquals("107% bar: dollars per hour", at107.reason);
        OfferRule.Decision at106 = at(106, offer);
        assertEquals(OfferRule.Result.REVIEW, at106.result);
        assertEquals("an enabled value was not found", at106.reason);
        assertEquals(795, at106.requiredCents);
    }

    @Test
    public void overMaxStopsIsThetaZeroAndDeclinesAtEveryBar() {
        FilterSettings rules = FilterSettings.of(true, 400, 100, 25, 3);
        OfferSnapshot fourStops = new OfferSnapshot(9900, 5.0, 20, 4);
        assertEquals(0, AreaScore.passThreshold(rules, fourStops));
        for (int bar = 1; bar <= 200; bar++) {
            OfferRule.Decision decision = OfferRule.evaluate(fourStops, rules.withMinimumScalePercent(bar));
            assertEquals(OfferRule.Result.DECLINE, decision.result);
            assertEquals("4 stops exceeds maximum 3", decision.reason);
            assertEquals(0, decision.requiredCents);
        }
    }

    @Test
    public void aKnownZeroRouteAsksNothingSoItPassesWithNoScore() {
        FilterSettings perMileOnly = FilterSettings.of(true, 0, 150, 0, 0);
        OfferSnapshot zeroMiles = offer(500, 0.0, 20);
        assertEquals(0, BigDecimal.ZERO.compareTo(AreaScore.required100(perMileOnly, zeroMiles)));
        assertEquals(Integer.MAX_VALUE, AreaScore.passThreshold(perMileOnly, zeroMiles));
        assertEquals(-1, AreaScore.scorePercent(perMileOnly, zeroMiles));
        OfferRule.Decision decision = OfferRule.evaluate(zeroMiles, perMileOnly);
        assertEquals(OfferRule.Result.KEEP, decision.result);
        assertEquals(0, decision.requiredCents);
        assertEquals(-1, decision.scorePercent);
        // Unknown is not zero: unread miles leave it for review.
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(offer(500, null, 20), perMileOnly).result);
    }

    /** The worked example's counted window, newest first: pay ¢ / mi / min, two stops each. */
    static final Object[][] WINDOW = {
            {1625, 6.2, 26}, {975, 11.8, 41}, {700, 2.2, 14}, {1350, 7.0, 28}, {575, 6.6, 27},
            {800, 3.0, 16}, {2250, 12.6, 44}, {600, 7.9, 29}, {1100, 5.1, 23}, {350, 4.8, 19},
            {925, 6.3, 25}, {1490, 9.9, 38}, {400, 2.6, 15}, {1050, 4.4, 20}, {725, 5.5, 24},
            {1700, 8.8, 33}, {875, 7.4, 31}, {500, 3.2, 18}, {1225, 6.0, 26}, {650, 4.1, 22}};

    static OfferSnapshot windowOffer(int i) {
        return new OfferSnapshot((Integer) WINDOW[i][0], (Double) WINDOW[i][1], (Integer) WINDOW[i][2], 2);
    }

    @Test
    public void theTwentyOfferWindowGivesTheSpecThetas() {
        int[] expected = {250, 82, 175, 192, 85, 200, 178, 75, 191, 72, 146, 150, 100, 210, 120, 193, 112, 111, 188,
                118};
        for (int i = 0; i < WINDOW.length; i++) {
            OfferSnapshot offer = windowOffer(i);
            assertEquals("offer " + i, expected[i], AreaScore.passThreshold(STARTERS, offer));
            assertEquals("for a complete offer θ is the score", expected[i], AreaScore.scorePercent(STARTERS, offer));
        }
        // Offers passing at each bar, as the worked example tabulates them.
        int[][] passing = {{72, 20}, {75, 19}, {82, 18}, {86, 16}, {100, 16}, {111, 15}, {112, 14}, {146, 11},
                {150, 10}};
        for (int[] row : passing) {
            int count = 0;
            for (int i = 0; i < WINDOW.length; i++) {
                if (OfferRule.evaluate(windowOffer(i), STARTERS.withMinimumScalePercent(row[0])).result
                        != OfferRule.Result.DECLINE) count++;
            }
            assertEquals("bar " + row[0], row[1], count);
        }
    }

    @Test
    public void theHigherUserMinimumsGiveTheirOwnThetas() {
        FilterSettings higher = FilterSettings.of(true, 500, 125, 35, 0);
        int[] thetas = new int[WINDOW.length];
        for (int i = 0; i < WINDOW.length; i++) thetas[i] = AreaScore.passThreshold(higher, windowOffer(i));
        java.util.Arrays.sort(thetas);
        assertEquals(java.util.Arrays.toString(new int[] {52, 59, 60, 66, 76, 79, 80, 84, 86, 105, 112, 134, 136,
                137, 140, 142, 142, 147, 150, 178}), java.util.Arrays.toString(thetas));
        // $8.75 / 7.4 mi / 31 min scores 80 and needs ⌈0.79 × 1085⌉ = $8.58 at a 79% bar: kept below the minimums.
        OfferRule.Decision decision = OfferRule.evaluate(new OfferSnapshot(875, 7.4, 31, 2),
                higher.withMinimumScalePercent(79));
        assertEquals(OfferRule.Result.KEEP, decision.result);
        assertEquals(858, decision.requiredCents);
        assertEquals(80, decision.scorePercent);
        assertTrue(decision.belowMinimums);
        assertEquals(1085, OfferRule.evaluate(new OfferSnapshot(875, 7.4, 31, 2), higher).requiredCents);
    }

    @Test
    public void theOwnersMigratedMinimumsPassNoneOfTheWindowAtOneHundredAndFourAtFifty() {
        FilterSettings owner = FilterSettings.of(true, 2040, 400, 48, 0);
        int highest = 0, at100 = 0, at50 = 0;
        for (int i = 0; i < WINDOW.length; i++) {
            int theta = AreaScore.passThreshold(owner, windowOffer(i));
            highest = Math.max(highest, theta);
            if (theta >= 100) at100++;
            if (theta >= 50) at50++;
        }
        assertEquals(65, highest);
        assertEquals(0, at100);
        assertEquals(4, at50);
    }

    @Test
    public void milesAreTakenAtTheirExactDecimalValue() {
        // 2.1 × 385 is 808.5 exactly (808.4999… in binary floating point): $8.09, never $8.08.
        FilterSettings perMile = FilterSettings.of(true, 0, 385, 0, 0);
        OfferSnapshot offer = new OfferSnapshot(808, 2.1, 16, 2);
        assertEquals(0, new BigDecimal("808.5").compareTo(AreaScore.required100(perMile, offer)));
        assertEquals(809, OfferRule.evaluate(offer, perMile).requiredCents);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(offer, perMile).result);
        assertEquals("⌊80800 ÷ 808.5⌋", 99, AreaScore.scorePercent(perMile, offer));
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(new OfferSnapshot(809, 2.1, 16, 2), perMile).result);
        assertEquals(100, AreaScore.scorePercent(perMile, new OfferSnapshot(809, 2.1, 16, 2)));
    }

    @Test
    public void scoreMeansTheSameAsPassingAtEveryBar() {
        // pay ≥ ⌈σR/100⌉ ⟺ 100·pay ≥ σR ⟺ ⌊100·pay/R⌋ ≥ σ, so a shown score never claims a missed cutoff.
        for (int i = 0; i < WINDOW.length; i++) {
            OfferSnapshot offer = windowOffer(i);
            int score = AreaScore.scorePercent(STARTERS, offer);
            for (int bar = 1; bar <= 200; bar++) {
                OfferRule.Result result = at(bar, offer).result;
                assertEquals("offer " + i + " at " + bar, score >= bar, result == OfferRule.Result.KEEP);
            }
        }
    }

    @Test
    public void noMoneyMinimumMeansNoRequirementAndNoScore() {
        FilterSettings stopsOnly = FilterSettings.of(true, 0, 0, 0, 3);
        OfferSnapshot offer = new OfferSnapshot(500, 6.0, 25, 2);
        assertNull(AreaScore.required100(stopsOnly, offer));
        assertNull(AreaScore.knownRequired100(stopsOnly, offer));
        assertEquals(Integer.MAX_VALUE, AreaScore.passThreshold(stopsOnly, offer));
        assertEquals(-1, AreaScore.scorePercent(stopsOnly, offer));
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(offer, stopsOnly).result);
        assertEquals(-1, OfferRule.evaluate(offer, stopsOnly).scorePercent);
    }

    @Test
    public void unreadPayHasNoScoreAndThetaWithoutACeiling() {
        OfferSnapshot noPay = offer(null, 5.0, 20);
        assertEquals(-1, AreaScore.scorePercent(STARTERS, noPay));
        assertEquals(Integer.MAX_VALUE, AreaScore.passThreshold(STARTERS, noPay));
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(noPay, STARTERS).result);
        assertEquals("pay not found", OfferRule.evaluate(noPay, STARTERS).reason);
    }
}

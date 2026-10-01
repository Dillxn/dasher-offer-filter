package com.local.dasherfilter;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The area score, against fixtures worked out by hand (and checked with exact fractions): the user's rules are $13
 * pay, $3.85 a mile, $0.41 a minute and $4.75 a stop, at most 3 stops.
 */
public final class AreaScoreTest {
    private static final FilterSettings USER = new FilterSettings(true, 1300, 385, 41, 475, 3);

    private static AreaScore.Floors floors(FilterSettings rules, int pay, Double miles, Integer minutes,
                                           Integer stops) {
        return AreaScore.floors(rules, new OfferSnapshot(pay, miles, minutes, stops));
    }

    @Test
    public void theSpokesPairUpInTheOrderTheyAreDrawn() {
        // Pay top-left (-150°), per mile top-right (-30°), per minute bottom-right (30°), per stop bottom-left (150°):
        // ascending, so index order is the clockwise order on the screen, 120° and 60° apart in turn.
        assertArrayEquals(new float[] {-150, -30, 30, 150, -90}, AreaScore.ANGLES, 0f);
        List<int[]> four = AreaScore.pairs(new int[] {0, 1, 2, 3});
        assertEquals(4, four.size());
        assertArrayEquals("pay–mile", new int[] {AreaScore.PAY, AreaScore.MILE}, four.get(0));
        assertArrayEquals("mile–minute", new int[] {AreaScore.MILE, AreaScore.MINUTE}, four.get(1));
        assertArrayEquals("minute–stop", new int[] {AreaScore.MINUTE, AreaScore.STOP}, four.get(2));
        assertArrayEquals("stop–pay", new int[] {AreaScore.STOP, AreaScore.PAY}, four.get(3));
        // Per stop off: per minute and pay stand opposite each other (30° and 210°), a triangle with no area.
        List<int[]> three = AreaScore.pairs(new int[] {0, 1, 2});
        assertEquals(2, three.size());
        assertArrayEquals(new int[] {AreaScore.PAY, AreaScore.MILE}, three.get(0));
        assertArrayEquals(new int[] {AreaScore.MILE, AreaScore.MINUTE}, three.get(1));
    }

    @Test
    public void theWorkedExampleScoresAbout121Percent() {
        // $15.00, 6 mi, 25 min, 2 stops. Floors: $13.00; 6 × $3.85 = $23.10; 25 × $0.41 = $10.25; 2 × $4.75 = $9.50.
        AreaScore.Floors floors = floors(USER, 1500, 6.0, 25, 2);
        double[] r = AreaScore.ratios(floors, 1500);
        assertEquals(1500 / 1300.0, r[0], 1e-12);   // 1.154
        assertEquals(1500 / 2310.0, r[1], 1e-12);   // 0.649
        assertEquals(1500 / 1025.0, r[2], 1e-12);   // 1.463
        assertEquals(1500 / 950.0, r[3], 1e-12);    // 1.579
        // (1.154 × 0.649 + 0.649 × 1.463 + 1.463 × 1.579 + 1.579 × 1.154) ÷ 4 = 5.8320 ÷ 4 = 1.4580; √ = 1.2075.
        assertEquals(1.207480540824083, AreaScore.score(floors, 1500), 1e-12);
        assertEquals(121, AreaScore.percent(floors, 1500));
        assertTrue(AreaScore.reaches(floors, 1500));
        // 100% needs pay ≥ 1500 ÷ 1.2075 = $12.42…, so $12.43.
        assertEquals(1243, AreaScore.requiredPay(floors));
        assertTrue(AreaScore.reaches(floors, 1243));
        assertFalse(AreaScore.reaches(floors, 1242));
    }

    @Test
    public void theReportedOfferStrictModeDeclinedScores49Percent() {
        // $4.25, 2.1 mi, 16 min, 2 stops: ratios 0.327, 425 ÷ 808.5 = 0.526, 425 ÷ 656 = 0.648, 425 ÷ 950 = 0.447;
        // (0.1719 + 0.3406 + 0.2898 + 0.1463) ÷ 4 = 0.2371; √ = 0.4870.
        AreaScore.Floors floors = floors(USER, 425, 2.1, 16, 2);
        assertEquals(0.4869553429377522, AreaScore.score(floors, 425), 1e-12);
        assertEquals(49, AreaScore.percent(floors, 425));
        assertFalse(AreaScore.reaches(floors, 425));
        assertEquals("425 ÷ 0.48696 = $8.727…", 873, AreaScore.requiredPay(floors));
    }

    @Test
    public void theScoreRisesInProportionToPay() {
        AreaScore.Floors floors = floors(USER, 1500, 6.0, 25, 2);
        assertEquals(2 * AreaScore.score(floors, 750), AreaScore.score(floors, 1500), 1e-12);
        assertEquals(3 * AreaScore.score(floors, 500), AreaScore.score(floors, 1500), 1e-12);
    }

    @Test
    public void anOfferExactlyAtEveryMinimumScoresExactly100Percent() {
        // Each spoke asks $10.00 of 5 mi, 20 min, 2 stops.
        FilterSettings even = new FilterSettings(true, 1000, 200, 50, 500, 0);
        AreaScore.Floors floors = floors(even, 1000, 5.0, 20, 2);
        assertEquals(1.0, AreaScore.score(floors, 1000), 0);
        assertTrue(AreaScore.reaches(floors, 1000));
        assertEquals(100, AreaScore.percent(floors, 1000));
        assertEquals(1000, AreaScore.requiredPay(floors));
        // A cent short is 99.9%, which would round to 100: it is shown as 99, never as reaching.
        assertFalse(AreaScore.reaches(floors, 999));
        assertEquals(99, AreaScore.percent(floors, 999));
    }

    @Test
    public void reachingIsWorkedOutExactlyWhereFloatingPointWouldNotBe() {
        // 2.1 mi × $3.85 is 808.5 cents exactly (in binary floating point, 808.4999…). One spoke: 100% at the
        // strict rules' own ask, rounded up to the cent.
        FilterSettings perMile = new FilterSettings(true, 0, 385, 0, 0, 0);
        AreaScore.Floors floors = floors(perMile, 809, 2.1, 16, 2);
        assertEquals(OfferRule.mileageCost(385, 2.1), AreaScore.requiredPay(floors));
        assertEquals(809, AreaScore.requiredPay(floors));
        assertTrue(AreaScore.reaches(floors, 809));
        assertFalse(AreaScore.reaches(floors, 808));
    }

    @Test
    public void withThreeSpokesTheOppositePairCarriesNoArea() {
        // Per stop off. Pay–mile and mile–minute count; minute–pay stand opposite each other and do not:
        // (1.1538 × 0.6494 + 0.6494 × 1.4634) ÷ 2 = 0.8498; √ = 0.9218.
        FilterSettings noStop = new FilterSettings(true, 1300, 385, 41, 0, 0);
        AreaScore.Floors floors = floors(noStop, 1500, 6.0, 25, 2);
        assertEquals(0.9218242761510783, AreaScore.score(floors, 1500), 1e-12);
        assertEquals(92, AreaScore.percent(floors, 1500));
        assertEquals(1628, AreaScore.requiredPay(floors));
    }

    @Test
    public void withTwoSpokesTheOnePairAndWithOneItsRatio() {
        FilterSettings two = new FilterSettings(true, 1300, 385, 0, 0, 0);
        AreaScore.Floors pair = floors(two, 1500, 6.0, 25, 2);
        // √(1.1538 × 0.6494) = 0.8656.
        assertEquals(Math.sqrt(1500 / 1300.0 * 1500 / 2310.0), AreaScore.score(pair, 1500), 1e-12);
        assertEquals(87, AreaScore.percent(pair, 1500));
        assertEquals("√(1300 × 2310) = 1732.9…", 1733, AreaScore.requiredPay(pair));

        FilterSettings one = new FilterSettings(true, 0, 385, 0, 0, 0);
        AreaScore.Floors alone = floors(one, 1500, 6.0, 25, 2);
        assertEquals(1500 / 2310.0, AreaScore.score(alone, 1500), 1e-12);
        assertEquals("the strict rules' own ask", 2310, AreaScore.requiredPay(alone));
    }

    @Test
    public void theAdaptiveMinimumRaisesEachFloorToWhatItAsksOfThisOffer() {
        // Learned from an accepted $14.20, 6 mi, 24 min, 2 stops: pay must beat $14.20, best $2.367/mi, $0.592/min,
        // $7.10/stop; and a $12.00 two-stop offer declined by hand ($6.00/stop, beaten by a cent).
        FilterSettings rules = new FilterSettings(true, 1300, 385, 41, 475, 3, true, 1420,
                AcceptedBest.NONE.raisedBy(new OfferSnapshot(1420, 6.0, 24, 2)),
                new DeclinedFloor(0, new AcceptedBest(0, 0, 0, 0, 1200, 2)));
        AreaScore.Floors floors = floors(rules, 1500, 6.0, 25, 2);
        // Pay: $14.21. Per mile: the set $23.10 is above the learned $14.20. Per minute: the learned rate over this
        // offer's 25 minutes, 1420 × 25 ÷ 24 = 1479.17, rounded up as the strict rules ask, $14.80, above the set
        // $10.25. Per stop: the learned $14.20 above the set $9.50 and the declined $12.01.
        assertEquals(0, floors.cents[0].compareTo(java.math.BigDecimal.valueOf(1421)));
        assertEquals(0, floors.cents[1].compareTo(java.math.BigDecimal.valueOf(2310)));
        assertEquals(0, floors.cents[2].compareTo(java.math.BigDecimal.valueOf(1480)));
        assertEquals(0, floors.cents[3].compareTo(java.math.BigDecimal.valueOf(1420)));
        assertEquals(0.9393154914470048, AreaScore.score(floors, 1500), 1e-12);
        assertEquals(94, AreaScore.percent(floors, 1500));
        assertEquals(1597, AreaScore.requiredPay(floors));

        // Off, the same learned values do not count.
        AreaScore.Floors off = floors(new FilterSettings(true, 1300, 385, 41, 475, 3, false, 1420, rules.best,
                rules.declined), 1500, 6.0, 25, 2);
        assertEquals(121, AreaScore.percent(off, 1500));
    }

    @Test
    public void aSpokeOnlyTheAdaptiveMinimumHasIsActiveWhileItIsOn() {
        AcceptedBest best = AcceptedBest.NONE.raisedBy(new OfferSnapshot(1420, 6.0, 24, 2));
        FilterSettings learnedOnly = new FilterSettings(true, 0, 0, 0, 0, 0, true, 0, best);
        assertArrayEquals(new boolean[] {false, true, true, true, false}, AreaScore.active(learnedOnly));
        assertArrayEquals(new boolean[] {false, false, false, false, false},
                AreaScore.active(new FilterSettings(true, 0, 0, 0, 0, 0, false, 0, best)));
    }

    @Test
    public void anActiveSpokeWhoseAmountWasNotReadLeavesNoScore() {
        assertFalse(floors(USER, 1500, null, 25, 2).readable());
        assertFalse("no distance is not read", floors(USER, 1500, 0.0, 25, 2).readable());
        assertFalse(floors(USER, 1500, 6.0, null, 2).readable());
        assertFalse("1 stop is a misread", floors(USER, 1500, 6.0, 25, 1).readable());
        assertTrue(Double.isNaN(AreaScore.score(floors(USER, 1500, null, 25, 2), 1500)));
        assertEquals(-1, AreaScore.percent(USER, new OfferSnapshot(1500, null, 25, 2)));
        assertEquals(-1, AreaScore.percent(USER, new OfferSnapshot(null, 6.0, 25, 2)));
        // A spoke with no minimum needs nothing read.
        FilterSettings payOnly = new FilterSettings(true, 1300, 0, 0, 0, 0);
        assertEquals(115, AreaScore.percent(payOnly, new OfferSnapshot(1500, null, null, null)));
        assertEquals("no minimum at all, no score", -1,
                AreaScore.percent(new FilterSettings(true, 0, 0, 0, 0, 3), new OfferSnapshot(1500, 6.0, 25, 2)));
    }
}

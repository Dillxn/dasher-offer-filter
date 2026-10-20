package com.local.dasherfilter;

import static org.junit.Assert.*;
import org.junit.Test;

public class AcceptedFloorsTest {
    @Test public void maximaAreIndependentAndTiesKeepTheirExactOriginalFacts() {
        AcceptedFloors a = AcceptedFloors.NONE.raisedBy(new OfferSnapshot(2000, 10.0, 40, 2))
                .raisedBy(new OfferSnapshot(1500, 3.0, 30, 2))
                .raisedBy(new OfferSnapshot(1800, 9.0, 18, 2));
        assertEquals(2000, a.highestPayCents);
        assertEquals(1500, a.milePay); assertEquals(3, a.miles, 0);
        assertEquals(1800, a.minutePay); assertEquals(18, a.minutes);
        assertSame(a, a.raisedBy(new OfferSnapshot(1000, 2.0, 10, 2)));
        assertSame(a, a.raisedBy(new OfferSnapshot(500, 9.0, 30, 2)));
    }
    @Test public void unknownMeasuresStayUnsetAndReadableShortTripsSetIndependentRateBests() {
        // Unread stops teach nothing on pay per stop (0.6.12).
        assertEquals(0, AcceptedFloors.NONE.raisedBy(new OfferSnapshot(700, null, null, null)).highestPayCents);
        AcceptedFloors a = AcceptedFloors.NONE.raisedBy(new OfferSnapshot(700, null, null, 2));
        assertEquals(700, a.highestPayCents); assertFalse(a.hasPerMile()); assertFalse(a.hasPerMinute());
        a = a.raisedBy(new OfferSnapshot(800, 1.0, 7, 2));
        assertEquals(800, a.highestPayCents);
        assertEquals(800, a.milePay); assertEquals(1.0, a.miles, 0);
        assertEquals(800, a.minutePay); assertEquals(7, a.minutes);
        a = a.raisedBy(new OfferSnapshot(1700, 2.0, null, null));
        assertEquals(1700, a.milePay);
        assertEquals("unread measures preserve their earlier highs", 800, a.minutePay);
        assertEquals("unread stops do not invent a pay best", 800, a.highestPayCents);
    }
    @Test public void theOwnersPointEightMileOfferRaisesMileageWithoutLoweringOtherHighs() {
        AcceptedFloors before = new AcceptedFloors(1341, 2, 390, 2.0, 560, 10);
        AcceptedFloors after = before.raisedBy(new OfferSnapshot(360, 0.8, 10, 2));
        assertEquals(360, after.milePay); assertEquals(0.8, after.miles, 0);
        assertEquals(450, after.milePay / after.miles, 0);
        assertEquals(before.highestPayCents, after.highestPayCents);
        assertEquals(before.minutePay, after.minutePay);
        assertEquals(before.minutes, after.minutes);
        assertSame("a later lower offer cannot lower any learned high", after,
                after.raisedBy(new OfferSnapshot(300, 0.8, 10, 2)));
        assertEquals("the short rate survives stored-pair validation", after,
                new AcceptedFloors(after.highestPayCents, after.payUnits, after.milePay, after.miles,
                        after.minutePay, after.minutes));
    }
    @Test public void commonPlausibilityBoundariesStillApplyToStoredAndObservedRates() {
        AcceptedFloors a = AcceptedFloors.NONE.raisedBy(new OfferSnapshot(500, 0.5, 5, 2));
        assertEquals(500, a.milePay); assertEquals(500, a.minutePay);
        for (OfferSnapshot offer : new OfferSnapshot[] {
                new OfferSnapshot(900, 0.499, 5, 2), new OfferSnapshot(900, 0.5, 4, 2),
                new OfferSnapshot(900, 0.5, 5, 1)}) {
            assertSame(a, a.raisedBy(offer));
        }
        assertTrue(new AcceptedFloors(0, 900, 0.499, 900, 4).isEmpty());
    }
    @Test public void anyKnownImplausibleFigureRejectsTheEntireLessonIncludingPayout() {
        for (OfferSnapshot offer : new OfferSnapshot[] {
                new OfferSnapshot(9000, .1, 20, 2), new OfferSnapshot(9000, 5.0, 1, 2),
                new OfferSnapshot(9000, 5.0, 20, 1)}) {
            assertSame(AcceptedFloors.NONE, AcceptedFloors.NONE.raisedBy(offer));
        }
        assertSame(AcceptedFloors.NONE, AcceptedFloors.NONE.raisedBy(new OfferSnapshot(null, 4.0, 20, 2)));
    }
    @Test public void nonfiniteInputWasNormalizedToUnknownBeforeTheLearnerAndCannotBeRecovered() {
        for (double nonfinite : new double[] {Double.NaN, Double.POSITIVE_INFINITY}) {
            OfferSnapshot offer = new OfferSnapshot(9000, nonfinite, 20, 2);
            assertNull(offer.miles);
            AcceptedFloors a = AcceptedFloors.NONE.raisedBy(offer);
            assertEquals(9000, a.highestPayCents); assertEquals(9000, a.minutePay);
            assertFalse(a.hasPerMile());
        }
    }
    @Test public void decimalRateComparisonRetainsAnImprovementSmallerThanOneDisplayCent() {
        AcceptedFloors a = AcceptedFloors.NONE.raisedBy(new OfferSnapshot(1000, 3.0, null, null));
        AcceptedFloors b = a.raisedBy(new OfferSnapshot(1000, 2.999, null, null));
        assertEquals(2.999, b.miles, 0); assertNotEquals(a.stableKey(), b.stableKey());
        // Unread stops set no pay best (0.6.12).
        assertEquals(b, new AcceptedFloors(0, 1000, 2.999, 0, 0));
    }
    @Test public void malformedStoredPairsCannotBecomeFloors() {
        AcceptedFloors a = new AcceptedFloors(-10, 1000, Double.POSITIVE_INFINITY, 1000, 0);
        assertTrue(a.isEmpty()); assertEquals(AcceptedFloors.NONE.stableKey(), a.stableKey());
    }
}

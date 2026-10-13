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
    @Test public void unknownMeasuresStayUnsetAndShortTripsDoNotSetRateBests() {
        // Unread stops teach nothing on pay per stop (0.6.12).
        assertEquals(0, AcceptedFloors.NONE.raisedBy(new OfferSnapshot(700, null, null, null)).highestPayCents);
        AcceptedFloors a = AcceptedFloors.NONE.raisedBy(new OfferSnapshot(700, null, null, 2));
        assertEquals(700, a.highestPayCents); assertFalse(a.hasPerMile()); assertFalse(a.hasPerMinute());
        a = a.raisedBy(new OfferSnapshot(800, 1.0, 7, 2));
        assertEquals(800, a.highestPayCents); assertFalse(a.hasPerMile()); assertFalse(a.hasPerMinute());
        a = a.raisedBy(new OfferSnapshot(900, 2.0, null, null));
        assertEquals(900, a.milePay); assertFalse(a.hasPerMinute());
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

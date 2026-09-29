package com.local.dasherfilter;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public final class AcceptedOfferTrackerTest {
    @Test public void passingOrDisappearingOffersDoNotCountAsAccepted() {
        AcceptedOfferTracker tracker = new AcceptedOfferTracker();
        tracker.observeOffer(new OfferSnapshot(2500, null, null, null), 1000);
        assertNull(tracker.observeOtherScreen(Arrays.asList("Arrived at store"), 1100));
        assertNull(tracker.observeOtherScreen(Arrays.asList("Looking for offers"), 1200));
    }

    @Test public void requiresAcceptClickAndDeliveryScreenAndRecordsOnlyOnce() {
        AcceptedOfferTracker tracker = new AcceptedOfferTracker();
        OfferSnapshot route = new OfferSnapshot(2500, 8.0, 40, 2);
        tracker.observeOffer(route, 1000);
        tracker.acceptClicked(1100);
        assertNull(tracker.observeOtherScreen(Arrays.asList("Loading"), 1150));
        AcceptedOfferTracker.Acceptance accepted =
                tracker.observeOtherScreen(Arrays.asList("Arrived at store"), 1300);
        assertNotNull(accepted);
        assertEquals(Integer.valueOf(2500), accepted.baselinePay());
        assertEquals(route.fingerprint(), accepted.routeAfter.fingerprint());
        assertFalse(accepted.addOn);
        assertNull(tracker.observeOtherScreen(Arrays.asList("Arrived at store"), 1400));
    }

    @Test public void acceptedAddOnCarriesCombinedRouteAndCombinedBaseline() {
        AcceptedOfferTracker tracker = new AcceptedOfferTracker();
        OfferSnapshot addOn = new OfferSnapshot(500, 2.0, 10, 2);
        OfferSnapshot combined = new OfferSnapshot(3000, 10.0, 50, 4);
        tracker.observeOffer(addOn, combined, true, 1000);
        tracker.acceptClicked(1100);
        AcceptedOfferTracker.Acceptance accepted =
                tracker.observeOtherScreen(Arrays.asList("Confirm pickup"), 1200);
        assertNotNull(accepted);
        assertTrue(accepted.addOn);
        assertEquals(Integer.valueOf(3000), accepted.baselinePay());
        assertEquals(combined.fingerprint(), accepted.routeAfter.fingerprint());
    }

    @Test public void failedOrExpiredAcceptDoesNotAdvanceBaseline() {
        AcceptedOfferTracker tracker = new AcceptedOfferTracker();
        tracker.observeOffer(new OfferSnapshot(2500, null, null, null), 1000);
        tracker.acceptClicked(1100);
        tracker.observeOffer(new OfferSnapshot(3500, null, null, null), 1200);
        assertNull(tracker.observeOtherScreen(Arrays.asList("Arrived at store"), 1300));
        tracker.observeOffer(new OfferSnapshot(2500, null, null, null), 10000);
        tracker.acceptClicked(10100);
        assertNull(tracker.observeOtherScreen(Arrays.asList("Arrived at store"), 26000));
    }

    @Test public void unreadablePayAndStaleOffersCannotBecomeBaseline() {
        AcceptedOfferTracker tracker = new AcceptedOfferTracker();
        tracker.observeOffer(new OfferSnapshot(null, null, null, null), 1000);
        tracker.acceptClicked(1100);
        assertNull(tracker.observeOtherScreen(Arrays.asList("Arrived at store"), 1200));
        tracker.observeOffer(new OfferSnapshot(2500, null, null, null), 1000);
        tracker.acceptClicked(91001);
        assertNull(tracker.observeOtherScreen(Arrays.asList("Arrived at store"), 91100));
    }
}

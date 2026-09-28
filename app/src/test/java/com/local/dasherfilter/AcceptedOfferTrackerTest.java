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
        tracker.observeOffer(new OfferSnapshot(2500, null, null, null), 1000);
        tracker.acceptClicked(1100);
        assertNull(tracker.observeOtherScreen(Arrays.asList("Loading"), 1150));
        assertEquals(Integer.valueOf(2500), tracker.observeOtherScreen(Arrays.asList("Arrived at store"), 1300));
        assertNull(tracker.observeOtherScreen(Arrays.asList("Arrived at store"), 1400));
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

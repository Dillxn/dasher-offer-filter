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
        assertEquals(Integer.valueOf(500), accepted.acceptedOffer.payCents);
        assertEquals(Integer.valueOf(3000), accepted.routeAfter.payCents);
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

    @Test public void deliveryWordsMatchWithPunctuationAndOddSpacing() {
        assertTrue(AcceptedOfferTracker.isDeliveryScreen(Arrays.asList("Arrived at store.")));
        assertTrue(AcceptedOfferTracker.isDeliveryScreen(Arrays.asList("Confirm\u00a0pickup!")));
        AcceptedOfferTracker tracker = new AcceptedOfferTracker();
        tracker.observeOffer(new OfferSnapshot(2500, 7.2, 21, 2), 1000);
        tracker.acceptClicked(1100);
        assertNotNull(tracker.observeOtherScreen(Arrays.asList("Arrived at store."), 2000));
    }

    private AcceptedOfferTracker requested(boolean dispatched, boolean deliveryBefore, int seconds) {
        AcceptedOfferTracker tracker = new AcceptedOfferTracker();
        OfferSnapshot offer = new OfferSnapshot(2000, 4.0, 20, 2);
        tracker.afterScreen(Arrays.asList(deliveryBefore ? "Arrived at store" : "Finding offers"), 900);
        tracker.observeOffer(offer, offer, false, offer, seconds, deliveryBefore, 1000);
        tracker.offerLeftAlone(offer, offer, offer, false, true, seconds, deliveryBefore, 1000);
        tracker.automaticAcceptRequested(offer, 1800, dispatched);
        return tracker;
    }

    @Test public void automaticRequestDoesNotBecomeAManualTapOrInferFromGenericRoute() {
        AcceptedOfferTracker tracker = requested(true, false, 30);
        assertEquals(DecisionLog.StepKind.AUTO_ACCEPT_REQUESTED, tracker.takeNotes().get(0).kind);
        assertNull(tracker.observeOtherScreen(Arrays.asList("Directions"), 2000));
        tracker.afterScreen(Arrays.asList("Directions"), 2000);
        assertTrue(tracker.takeNotes().isEmpty());
        assertEquals(16_801, tracker.nextDeadline());
        assertTrue(tracker.expire(16_801));
        assertEquals(DecisionLog.StepKind.AUTO_ACCEPT_UNCONFIRMED, tracker.takeNotes().get(0).kind);
        assertNull(tracker.observeOtherScreen(Arrays.asList("Arrived at store"), 16_900));
    }

    @Test public void refusedAutomaticRequestCannotUseTheUntappedClosePath() {
        AcceptedOfferTracker tracker = requested(false, false, 30);
        assertEquals(DecisionLog.StepKind.AUTO_ACCEPT_UNCONFIRMED, tracker.takeNotes().get(0).kind);
        assertNull(tracker.observeOtherScreen(Arrays.asList("Arrived at store"), 2000));
        tracker.afterScreen(Arrays.asList("Arrived at store"), 2000);
        assertTrue(tracker.takeNotes().isEmpty());
    }

    @Test public void automaticRequestNeedsDefiniteProgressButNeverPriorOrExpiredRoute() {
        AcceptedOfferTracker tracker = requested(true, false, 30);
        assertNotNull(tracker.observeOtherScreen(Arrays.asList("Arrived at store"), 2000));
        tracker = requested(true, true, 30);
        assertNull(tracker.observeOtherScreen(Arrays.asList("Arrived at store"), 2000));
        assertEquals(DecisionLog.StepKind.AUTO_ACCEPT_UNCONFIRMED, tracker.takeNotes().get(1).kind);
        tracker = requested(true, false, 4);
        assertNull(tracker.observeOtherScreen(Arrays.asList("Arrived at store"), 3000));
        assertEquals(DecisionLog.StepKind.AUTO_ACCEPT_UNCONFIRMED, tracker.takeNotes().get(1).kind);
    }

    @Test public void automaticConfirmationCannotSurviveIdleNewOfferDeclineOrHiddenForeground() {
        for (String screen : Arrays.asList("Finding offers", "Dash now", "New Delivery!")) {
            AcceptedOfferTracker tracker = requested(true, false, 30);
            assertNull(tracker.observeOtherScreen(Arrays.asList(screen), 2000));
            assertNull(tracker.observeOtherScreen(Arrays.asList("Arrived at store"), 2500));
        }
        AcceptedOfferTracker tracker = requested(true, false, 30);
        tracker.declineTapped(2000);
        assertNull(tracker.observeOtherScreen(Arrays.asList("Arrived at store"), 2500));
        tracker = requested(true, false, 30);
        tracker.forgetVisible();
        assertNull(tracker.observeOtherScreen(Arrays.asList("Arrived at store"), 2500));
    }

    @Test public void returningOfferOrFreshCountdownEndsOnlyAutomaticConfirmation() {
        AcceptedOfferTracker tracker = requested(true, false, 30);
        assertNull(tracker.observeOtherScreen(Arrays.asList("Map"), 2000));
        OfferSnapshot offer = new OfferSnapshot(2000, 4.0, 20, 2);
        tracker.observeOffer(offer, offer, false, offer, 28, false, 2100);
        assertNull(tracker.observeOtherScreen(Arrays.asList("Arrived at store"), 2500));
        tracker = requested(true, false, 30);
        tracker.observeOffer(offer, offer, false, offer, 40, false, 2100);
        assertNull(tracker.observeOtherScreen(Arrays.asList("Arrived at store"), 2500));
    }
    // ---- A peek and the wait for offers read before it (0.6.3) ----

    /** The wait read with Dasher's offer notifications at {@code waitStamp}, then a peek with them at {@code peekStamp}. */
    private static AcceptedOfferTracker peekedAfterWaiting(long waitStamp, long peekStamp, boolean expectWaited) {
        long[] stamp = {waitStamp};
        AcceptedOfferTracker tracker = new AcceptedOfferTracker(() -> stamp[0]);
        tracker.afterScreen(Arrays.asList("Finding offers"), 900);
        stamp[0] = peekStamp;
        assertEquals(expectWaited, tracker.peekBegan());
        OfferSnapshot offer = new OfferSnapshot(1750, 16.6, 34, 2);
        tracker.observeOffer(offer, offer, false, offer, 30, false, 1000);
        assertEquals(expectWaited, tracker.waitedFor(offer));
        return tracker;
    }

    /** The owner's 11:57 AM $17.50: the one offer's notification since the wait was read is the one peeked at. */
    @Test public void aPeekedOfferWithNoOtherOfferSinceTheWaitCameWhileTheUserWaited() {
        peekedAfterWaiting(41, 42, true);
    }

    @Test public void anotherOfferSinceTheWaitForgetsItForThePeekedOne() {
        peekedAfterWaiting(41, 43, false);
    }

    @Test public void noOfferPostedSinceTheWaitIsNotThePeekedOnesEither() {
        // The peeked offer's notification was counted before the wait was read: the wait says nothing of it.
        peekedAfterWaiting(42, 42, false);
    }

    @Test public void notificationsNotWatchedThenOrNowProveNothing() {
        peekedAfterWaiting(-1, 0, false);
        peekedAfterWaiting(41, -1, false);
        // The listener lost and connected again between them: the stamp moved by far more than one.
        peekedAfterWaiting(41, (1L << 32) + 42, false);
    }

    @Test public void aDeliveryReadLastIsForgottenAtAPeekAsBefore() {
        long[] stamp = {41};
        AcceptedOfferTracker tracker = new AcceptedOfferTracker(() -> stamp[0]);
        tracker.afterScreen(Arrays.asList("Arrived at store"), 900);
        stamp[0] = 42;
        assertFalse(tracker.peekBegan());
        OfferSnapshot offer = new OfferSnapshot(1750, 16.6, 34, 2);
        tracker.observeOffer(offer, offer, false, offer, 30, false, 1000);
        assertFalse(tracker.waitedFor(offer));
    }

    @Test public void withoutAStampAPeekForgetsTheWaitAsBefore() {
        AcceptedOfferTracker tracker = new AcceptedOfferTracker();
        tracker.afterScreen(Arrays.asList("Finding offers"), 900);
        assertFalse(tracker.peekBegan());
        OfferSnapshot offer = new OfferSnapshot(1750, 16.6, 34, 2);
        tracker.observeOffer(offer, offer, false, offer, 30, false, 1000);
        assertFalse(tracker.waitedFor(offer));
    }
}

package com.local.dasherfilter;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public final class DeclineStateTest {
    @Test
    public void firstOfferAndDifferentOfferHaveNoWaitingPeriod() {
        DeclineState state = new DeclineState();
        assertTrue(state.mayDecline("first", 1000));
        state.declineSent("first", 1000);
        assertFalse(state.mayDecline("first", 1000));
        assertTrue(state.mayDecline("second", 1000));
    }

    @Test
    public void countdownDoesNotCreateAnotherTapOnTheSameOffer() {
        OfferSnapshot offer = new OfferSnapshot(1200, null, null, null);
        String first = DeclineState.offerKey(offer, Arrays.asList("Cafe", "$12.00", "Accept 29", "29", "Decline"));
        String nextTick = DeclineState.offerKey(offer, Arrays.asList("Cafe", "$12.00", "Accept 28", "28", "Decline"));
        String nextOffer = DeclineState.offerKey(offer, Arrays.asList("Pizza", "$12.00", "Accept 28", "28", "Decline"));
        assertEquals(first, nextTick);
        assertNotEquals(first, nextOffer);
    }

    @Test
    public void nextIdenticalOfferCanBeDeclinedAfterPriorScreenDisappears() {
        DeclineState state = new DeclineState();
        state.declineSent("same pay and route", 1000);
        state.offerGone();
        assertTrue(state.mayDecline("same pay and route", 1001));
        // A brief transition with no controls must not lose the pending confirmation.
        assertTrue(state.mayConfirm(1001));
    }

    @Test
    public void confirmationIsImmediateButRequiresRecentActionAndStopsWhenDisabled() {
        DeclineState state = new DeclineState();
        assertFalse(state.mayConfirm(1000));
        state.declineSent("first", 1000);
        assertTrue(state.mayConfirm(1000));
        assertFalse(state.mayConfirm(4000));
        state.reset();
        assertFalse(state.mayConfirm(1001));
        state.declineSent("second", 5000);
        state.confirmationSent(5000);
        assertFalse(state.mayConfirm(5001));
        assertTrue(state.mayConfirm(5250));
        assertFalse(state.mayConfirm(8000));
    }

    @Test
    public void retriesAStillVisibleOfferWithoutBlockingADifferentOffer() {
        DeclineState state = new DeclineState();
        state.declineSent("stuck offer", 1000);
        assertFalse(state.mayDecline("stuck offer", 1249));
        assertTrue(state.mayDecline("stuck offer", 1250));
        state.declineSent("stuck offer", 1250);
        state.declineSent("stuck offer", 1500);
        state.declineSent("stuck offer", 1750);
        assertFalse(state.mayDecline("stuck offer", 2000));
        assertTrue(state.mayDecline("different offer", 1750));
        state.declineSent("different offer", 1750);
        assertTrue(state.mayDecline("different offer", 2000));
        state.reset();
        assertTrue(state.mayDecline("different offer", 1751));
    }
}

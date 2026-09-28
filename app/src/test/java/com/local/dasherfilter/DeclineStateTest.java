package com.local.dasherfilter;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public final class DeclineStateTest {
    @Test
    public void firstOfferAndDifferentOfferHaveNoWaitingPeriod() {
        DeclineState state = new DeclineState();
        assertTrue(state.mayDecline("first"));
        state.declineSent("first", 1000);
        assertFalse(state.mayDecline("first"));
        assertTrue(state.mayDecline("second"));
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
        assertTrue(state.mayDecline("same pay and route"));
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
        state.confirmationSent();
        assertFalse(state.mayConfirm(5001));
    }
}

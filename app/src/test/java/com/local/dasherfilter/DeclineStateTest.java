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
        assertTrue(state.mayConfirm(4000));
        assertFalse(state.mayConfirm(11000));
        state.reset();
        assertFalse(state.mayConfirm(1001));
        state.declineSent("second", 5000);
        state.confirmationSent(5000);
        assertFalse(state.mayConfirm(5001));
        assertFalse(state.mayConfirm(5250));
        assertTrue(state.mayConfirm(7000));
        assertFalse(state.mayConfirm(15000));
    }

    @Test
    public void retriesAStillVisibleOfferWithoutBlockingADifferentOffer() {
        DeclineState state = new DeclineState();
        state.declineSent("stuck offer", 1000);
        assertFalse(state.mayDecline("stuck offer", 2999));
        assertTrue(state.mayDecline("stuck offer", 3000));
        state.declineSent("stuck offer", 3000);
        state.declineSent("stuck offer", 5000);
        state.declineSent("stuck offer", 7000);
        assertFalse(state.mayDecline("stuck offer", 9000));
        assertTrue(state.mayDecline("different offer", 7000));
        state.declineSent("different offer", 7000);
        assertTrue(state.mayDecline("different offer", 9000));
        state.reset();
        assertTrue(state.mayDecline("different offer", 1751));
    }

    @Test public void delayedConfirmationSurvivesTransitionsButExpires() {
        DeclineState state = new DeclineState();
        state.declineSent("low offer", 1000);
        state.offerGone();
        assertTrue(state.hasPendingConfirmation(6000));
        assertTrue(state.mayConfirm(6000));
        assertFalse(state.hasPendingConfirmation(11000));
        assertFalse(state.mayConfirm(11000));
        state.declineSent("new offer", 12000);
        state.reset();
        assertFalse(state.hasPendingConfirmation(12001));
    }

    @Test public void capsConfirmationRequestsWithoutAnInitialWait() {
        DeclineState state = new DeclineState();
        state.declineSent("low offer", 1000);
        assertTrue(state.mayConfirm(1000));
        state.confirmationSent(1000);
        assertFalse(state.mayConfirm(1249));
        assertFalse(state.mayConfirm(1250));
        assertTrue(state.mayConfirm(3000));
        state.confirmationSent(1250);
        state.confirmationSent(1500);
        state.confirmationSent(1750);
        assertFalse(state.mayConfirm(2000));
        state.declineSent("different offer", 2000);
        assertTrue(state.mayConfirm(2000));
    }
    @Test public void confirmationAuthorityUsesCountdownAndDoesNotSlideOnRetries() {
        DeclineState state = new DeclineState();
        state.declineSent("same", 6_000, 30_000);
        assertTrue(state.mayConfirm(16_000));
        state.declineSent("same", 7_000, 29_000);
        assertEquals(39_000, state.confirmationUntil());
        assertFalse(state.mayConfirm(39_000));
    }

    @Test public void takenConfirmationWaitScalesWithReadDurationButRefusalsRemainPrompt() {
        DeclineState state = new DeclineState();
        state.declineSent("offer", 1_000, 45_000);
        state.readDuration(1_400);
        state.confirmationSent(1_000);
        assertFalse(state.mayConfirm(3_799));
        assertTrue(state.mayConfirm(3_800));
        state.confirmationRefused(3_800);
        assertFalse(state.mayConfirm(4_099));
        assertTrue(state.mayConfirm(4_100));
        state.readDuration(10_000);
        state.confirmationSent(4_100);
        assertFalse(state.confirmationExhausted(7_099));
        assertTrue(state.confirmationExhausted(7_100));
    }

    @Test public void firstStepRetryUsesReadLatencyWithoutMovingTheOriginalDeadline() {
        DeclineState state = new DeclineState();
        state.readDuration(1_400);
        state.declineSent("offer", 1_000, 30_000);
        assertEquals(3_800, state.nextDeclineAt());
        assertFalse(state.mayDecline("offer", 3_799));
        assertTrue(state.mayDecline("offer", 3_800));
        state.readDuration(9_000);
        state.declineSent("offer", 3_800, 29_000);
        assertEquals(6_800, state.nextDeclineAt());
        assertEquals(34_000, state.confirmationUntil());
        assertFalse("expired authority cannot restart by retry", state.mayDecline("offer", 34_000));
    }

    @Test public void confirmationAndAuthorityEndCancelFirstStepRetries() {
        DeclineState state = new DeclineState();
        state.declineSent("offer", 1_000, 30_000);
        state.confirmationSent(1_200);
        assertEquals(-1, state.nextDeclineAt());
        assertFalse(state.mayDecline("offer", 5_000));
        state.endConfirmation();
        assertEquals(-1, state.nextDeclineAt());
        assertFalse(state.mayDecline("offer", 6_000));
    }
}

package com.local.dasherfilter;

import java.util.Objects;
import java.util.function.BiPredicate;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The pure pieces of handing a decline back: the app's own taps, Dasher's question for one decline, and its tries. */
public final class DeclineEpisodeTest {
    private static final BiPredicate<Object, Object> SAME = Objects::equals;
    private static final OfferSnapshot OFFER = new OfferSnapshot(790, 7.2, 21, 2);

    // ---- OwnTaps ----

    @Test
    public void eachTapHasAnEchoWindowOfItsOwnThatNoLaterTapStretches() {
        OwnTaps taps = new OwnTaps();
        taps.began("decline", 1_000);
        assertNotNull("during the call", taps.touchEcho(1_040));
        taps.ended(1_010, true);
        assertNotNull(taps.touchEcho(1_160));
        assertNull("just past its window", taps.touchEcho(1_161));
        assertNull("before any tap", taps.touchEcho(999));

        taps.began("confirm", 1_300);
        taps.ended(1_300, false);
        assertNull("between the two windows", taps.touchEcho(1_200));
        assertEquals(1_300, taps.touchEcho(1_420).began);
        assertNull(taps.touchEcho(1_451));
    }

    @Test
    public void aClickIsTheAppsOwnOnlyOnTheNodeItTappedOrWithNoNodeInsideATapsWindow() {
        OwnTaps taps = new OwnTaps();
        taps.began("decline", 1_000);
        taps.ended(1_000, true);
        assertNotNull("its own node, late", taps.clickEcho("decline", 3_500, SAME));
        assertNull("another node 50 ms later is the user's", taps.clickEcho("view offer details", 1_050, SAME));
        assertNull("its own node, from before the tap", taps.clickEcho("decline", 990, SAME));
        assertNull("too late even for its own node", taps.clickEcho("decline", 6_001, SAME));
        assertNotNull("no node, inside the window", taps.clickEcho(null, 1_100, SAME));
        assertNull("no node, past the window", taps.clickEcho(null, 1_151, SAME));

        // A tap Android refused clicked nothing: no click can be its echo once it returned.
        taps.began("confirm", 2_000);
        assertSame("while under way", taps.last(), taps.clickEcho("confirm", 2_001, SAME));
        taps.ended(2_002, false);
        assertNull(taps.clickEcho("confirm", 2_100, SAME));
    }

    @Test
    public void onlyTheLastFewTapsAreKept() {
        OwnTaps taps = new OwnTaps();
        for (int i = 0; i < OwnTaps.KEPT + 3; i++) {
            taps.began("tap " + i, 1_000 + i * 10_000L);
            taps.ended(1_000 + i * 10_000L, true);
        }
        assertNull(taps.clickEcho("tap 0", 1_001, SAME));
        assertNotNull(taps.clickEcho("tap " + (OwnTaps.KEPT + 2), 1_001 + (OwnTaps.KEPT + 2) * 10_000L, SAME));
    }

    // ---- DeclineEpisode ----

    @Test
    public void beforeItsScreenGaveWayTheOfferMayBeDeclinedAgain() {
        DeclineEpisode episode = new DeclineEpisode();
        assertEquals(DeclineEpisode.Back.NONE, episode.offerShowing(1_000, false));
        episode.declined("key", OFFER, 1_000);
        assertEquals("Dasher may have missed the first tap", DeclineEpisode.Back.NONE,
                episode.offerShowing(1_300, false));
    }

    @Test
    public void theOfferBackAfterItsQuestionWithNoConfirmationIsTheUsers() {
        DeclineEpisode episode = new DeclineEpisode();
        episode.declined("key", OFFER, 1_000);
        episode.questionSeen(1_300);
        assertEquals("a question still up beside it", DeclineEpisode.Back.HOLD, episode.offerShowing(1_400, true));
        assertEquals(DeclineEpisode.Back.HAND_BACK, episode.offerShowing(1_500, false));

        // Any screen without the offer counts too (the question unreadable, say).
        DeclineEpisode unreadable = new DeclineEpisode();
        unreadable.declined("key", OFFER, 1_000);
        unreadable.screenLeft(1_200);
        assertEquals(DeclineEpisode.Back.HAND_BACK, unreadable.offerShowing(5_000, false));
    }

    @Test
    public void aConfirmedQuestionClosingOnTheOfferAllowsOneMoreDeclineAfterTwoSeconds() {
        DeclineEpisode episode = new DeclineEpisode();
        episode.declined("key", OFFER, 1_000);
        episode.questionSeen(1_300);
        episode.confirmed();
        assertEquals(DeclineEpisode.Back.HOLD, episode.offerShowing(1_600, false));
        assertTrue(episode.holding(1_600));
        assertEquals(DeclineEpisode.Back.HOLD, episode.offerShowing(3_599, false));
        assertEquals(DeclineEpisode.Back.DECLINE_AGAIN, episode.offerShowing(3_600, false));

        // The same offer declined again keeps the episode: a second time is given up.
        episode.declined("key", OFFER, 3_600);
        assertFalse(episode.holding(3_600));
        episode.questionSeen(3_900);
        episode.confirmed();
        episode.offerShowing(4_200, false);
        assertEquals(DeclineEpisode.Back.GIVE_UP, episode.offerShowing(6_200, false));
    }

    @Test
    public void aDifferentOfferStartsAFreshEpisodeAndAnEpisodeEnds() {
        DeclineEpisode episode = new DeclineEpisode();
        episode.declined("key", OFFER, 1_000);
        assertTrue(episode.covers("other labels", new OfferSnapshot(790, null, null, null)));
        assertFalse(episode.covers("other labels", new OfferSnapshot(610, null, null, null)));
        episode.questionSeen(1_300);
        episode.declined("next", new OfferSnapshot(610, 3.0, 12, 1), 2_000);
        assertFalse("the next offer's screen has not given way", episode.left());

        episode.end();
        assertFalse(episode.active(2_001));
        episode.declined("key", OFFER, 3_000);
        assertFalse(episode.active(3_000 + DeclineEpisode.MAX_MS));
    }

    // ---- DeclineState: tries at the question ----

    @Test
    public void refusedTriesStayPromptAndTakenTriesWaitForDasher() {
        DeclineState state = new DeclineState();
        state.declineSent("offer", 1_000);
        assertTrue(state.mayConfirm(1_300));
        state.confirmationRefused(1_300);
        assertFalse(state.mayConfirm(1_549));
        assertFalse(state.mayConfirm(1_550));
        assertTrue(state.mayConfirm(1_600));
        state.confirmationRefused(1_600);
        assertFalse(state.confirmationExhausted(1_600));
        state.confirmationSent(1_900);
        assertFalse("no fourth try", state.mayConfirm(2_500));
        assertFalse("Dasher may still be acting on the last", state.confirmationExhausted(2_149));
        assertFalse(state.confirmationExhausted(3_899));
        assertTrue(state.confirmationExhausted(3_900));
        assertTrue(state.confirmationTapped());

        state.declineSent("next offer", 3_000);
        assertEquals(0, state.confirmationTries());
        state.confirmationRefused(3_100);
        state.confirmationRefused(3_400);
        state.confirmationRefused(3_700);
        assertTrue("the third refusal ends it at once", state.confirmationExhausted(3_700));
    }

    @Test
    public void theAuthorityLapsesByTimeButNotWhenRevoked() {
        DeclineState state = new DeclineState();
        state.declineSent("offer", 1_000);
        assertFalse(state.confirmationLapsed(10_999));
        assertTrue(state.confirmationLapsed(11_000));
        state.reset();
        assertFalse(state.confirmationLapsed(20_000));
    }
    @org.junit.Test public void aPartialAnimationAloneDoesNotMeanTheUserWentBack() {
        DeclineEpisode episode = new DeclineEpisode();
        OfferSnapshot offer = new OfferSnapshot(790, 7.2, 21, 2);
        episode.declined("offer", offer, 1_000);
        episode.screenLeft(1_400, false);
        episode.offerPresent();
        org.junit.Assert.assertEquals(DeclineEpisode.Back.NONE, episode.offerShowing(1_600, false));
        episode.screenLeft(1_700, true);
        org.junit.Assert.assertEquals(DeclineEpisode.Back.HAND_BACK, episode.offerShowing(1_800, false));
    }

}

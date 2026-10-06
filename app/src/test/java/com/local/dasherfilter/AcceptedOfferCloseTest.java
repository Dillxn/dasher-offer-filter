package com.local.dasherfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * What became of an offer the app left alone, without seeing the user's tap (the user's decision): what came after it
 * decides, and every step is noted for its history line and the acceptance-rate count. Nothing is learned from any of
 * it (0.5.0), so no step names a lesson and no decline by hand is held to teach. Labels are shaped like the user's
 * report, with made-up names.
 */
public class AcceptedOfferCloseTest {
    @Test public void anUnclearScreenBeforeAnyOfferUsesUpTheEarlierWaitingEvidence() {
        tracker.afterScreen(WAITING, 100);
        tracker.afterScreen(PICKUP_UNKNOWN, 500);
        shows(OFFER, 35, 1_000);
        tracker.afterScreen(DELIVERY, 2_000);
        AcceptedOfferTracker.Note note = only(DecisionLog.StepKind.NOT_LEARNED);
        assertNull(note.accepted);
        assertEquals(AcceptedOfferTracker.WAIT_NOT_SEEN, note.detail);
    }

    @Test public void aFreshWaitAfterTheUnclearScreenRestoresPositiveEvidence() {
        tracker.afterScreen(WAITING, 100);
        tracker.afterScreen(PICKUP_UNKNOWN, 500);
        tracker.afterScreen(WAITING, 700);
        shows(OFFER, 35, 1_000);
        tracker.afterScreen(DELIVERY, 2_000);
        assertNotNull(only(DecisionLog.StepKind.ACCEPTED_LEARNED).accepted);
    }

    private static final OfferSnapshot OFFER = new OfferSnapshot(1675, 3.9, 30, 3);
    private static final List<String> DELIVERY = Arrays.asList("Deliver by 9:45 PM", "Delivery for Sam",
            "Complete delivery steps");
    private static final List<String> WAITING = Arrays.asList("Open Settings", "Help", "This dash",
            "You're in a good place to wait for offers", "Zone offer wait", "1-2 min", "Dash Preferences");
    /** The screen Dasher shows for a moment after an offer closes: only its page chrome. */
    private static final List<String> CHROME = Arrays.asList("Sam T", "120 orders completed",
            "trailing_icon", "Home", "Schedule", "Account");
    private static final List<String> PRE_DASH_HOME = Arrays.asList("Side Menu", "This week",
            "Earnings Mode Switcher", "Time mode off", "Time mode on", "Safety tools", "Dash",
            "dx.home_screen.schedule", "Home", "Schedule", "Account");

    /** A stacked offer, such as one that comes during a delivery. */
    private static final OfferSnapshot STACKED = new OfferSnapshot(2000, 5.0, 25, 2);
    /** A pickup screen worded in a way the app does not know (made-up words). */
    private static final List<String> PICKUP_UNKNOWN = Arrays.asList("Heading to Store A", "Store A",
            "Order for Sam");

    private final AcceptedOfferTracker tracker = new AcceptedOfferTracker();

    private void shows(OfferSnapshot offer, int secondsLeft, long now) {
        tracker.observeOffer(offer, offer, false, offer, now);
        tracker.offerLeftAlone(offer, offer, offer, false, true, secondsLeft, false, now);
    }

    private AcceptedOfferTracker.Note only(DecisionLog.StepKind kind) {
        List<AcceptedOfferTracker.Note> notes = tracker.takeNotes();
        assertEquals(notes.toString(), 1, notes.size());
        assertEquals(notes.toString(), kind, notes.get(0).kind);
        return notes.get(0);
    }

    @Test
    public void whatComesAfterAnOfferIsReadFromItsWordsAlone() {
        assertEquals(AcceptedOfferTracker.After.ROUTE, AcceptedOfferTracker.classify(DELIVERY));
        assertEquals(AcceptedOfferTracker.After.ROUTE, AcceptedOfferTracker.classify(
                Arrays.asList("Pick up by 7:30 PM", "Store A", "Directions")));
        assertEquals(AcceptedOfferTracker.After.ROUTE, AcceptedOfferTracker.classify(
                Collections.singletonList("Arrived at store.")));
        assertEquals(AcceptedOfferTracker.After.WAITING, AcceptedOfferTracker.classify(WAITING));
        assertEquals(AcceptedOfferTracker.After.WAITING, AcceptedOfferTracker.classify(
                Collections.singletonList("Finding offers")));
        assertEquals(AcceptedOfferTracker.After.DASH_OVER, AcceptedOfferTracker.classify(PRE_DASH_HOME));
        assertEquals(AcceptedOfferTracker.After.DASH_OVER, AcceptedOfferTracker.classify(
                Collections.singletonList("Dash ended")));
        assertEquals(AcceptedOfferTracker.After.UNCLEAR, AcceptedOfferTracker.classify(CHROME));
        assertEquals(AcceptedOfferTracker.After.EMPTY, AcceptedOfferTracker.classify(Collections.<String>emptyList()));
        // Dasher's headline for a new offer is a new offer (the user's note), never a delivery under way.
        assertEquals(AcceptedOfferTracker.After.NEW_OFFER, AcceptedOfferTracker.classify(
                Arrays.asList("New Delivery!", "New Order: Go to Store A")));
        assertEquals(AcceptedOfferTracker.After.NEW_OFFER, AcceptedOfferTracker.classify(
                Arrays.asList("New Order: Go to Store A", "Delivery for Sam")));
    }

    @Test
    public void anOfferThatClosesWithTimeLeftIntoADeliveryIsAccepted() {
        // The user was waiting for offers: positive evidence no delivery was under way.
        tracker.afterScreen(WAITING, 500);
        shows(OFFER, 35, 1_000);
        shows(OFFER, 31, 5_000);
        tracker.afterScreen(CHROME, 6_000);
        tracker.afterScreen(Collections.<String>emptyList(), 6_300);
        assertTrue("a moment of page chrome is not a next screen", tracker.takeNotes().isEmpty());
        tracker.afterScreen(DELIVERY, 8_000);
        AcceptedOfferTracker.Note note = only(DecisionLog.StepKind.ACCEPTED_LEARNED);
        assertNotNull(note.accepted);
        assertEquals(Integer.valueOf(1675), note.accepted.acceptedOffer.payCents);
        assertEquals(OFFER.fingerprint(), note.accepted.routeAfter.fingerprint());
        assertTrue(!note.accepted.tapSeen && !note.accepted.addOn);
        // 31 s at 5 s, gone by the read at 6 s: about 30 s left.
        assertEquals("it closed with about 0:30 left on its countdown, and Dasher showed a delivery screen 2 s after "
                + "it left", note.detail);
        // Once only.
        tracker.afterScreen(DELIVERY, 9_000);
        assertTrue(tracker.takeNotes().isEmpty());
    }

    @Test
    public void theWaitForOffersAfterItMeansItWasNotAccepted() {
        shows(OFFER, 35, 1_000);
        tracker.afterScreen(CHROME, 2_000);
        tracker.afterScreen(WAITING, 2_500);
        AcceptedOfferTracker.Note note = only(DecisionLog.StepKind.NOT_ACCEPTED);
        assertNull(note.accepted);
        assertEquals("Dasher went back to the wait for offers 1 s after it left", note.detail);

        shows(OFFER, 35, 10_000);
        tracker.afterScreen(PRE_DASH_HOME, 11_000);
        assertEquals("the dash ended or paused 0 s after it left",
                only(DecisionLog.StepKind.NOT_ACCEPTED).detail);
    }

    @Test
    public void anOfferThatMayHaveRunOutIsNotCountedAsAccepted() {
        tracker.afterScreen(WAITING, 500);
        shows(OFFER, 5, 1_000);
        tracker.afterScreen(DELIVERY, 3_000);
        AcceptedOfferTracker.Note note = only(DecisionLog.StepKind.NOT_LEARNED);
        assertNull(note.accepted);
        assertEquals("it may have run out: its countdown showed 0:05, 2 s before a read found it gone", note.detail);

        // No countdown read at all: it may have run out too.
        tracker.afterScreen(WAITING, 9_000);
        shows(new OfferSnapshot(2000, 5.0, 25, 2), -1, 10_000);
        tracker.afterScreen(DELIVERY, 11_000);
        assertEquals("no countdown was read, so it may have run out", only(DecisionLog.StepKind.NOT_LEARNED).detail);
    }

    @Test
    public void aDeliveryAlreadyUnderWayProvesNothing() {
        tracker.afterScreen(DELIVERY, 500);
        shows(OFFER, 35, 1_000);
        tracker.afterScreen(DELIVERY, 3_000);
        assertEquals("a delivery was already under way when it came, so the delivery screen after it proves nothing",
                only(DecisionLog.StepKind.NOT_LEARNED).detail);

        // A route stored as it came says the same.
        AcceptedOfferTracker stored = new AcceptedOfferTracker();
        stored.offerLeftAlone(OFFER, OFFER, OFFER, false, true, 35, true, 1_000);
        stored.afterScreen(DELIVERY, 3_000);
        assertEquals(DecisionLog.StepKind.NOT_LEARNED, stored.takeNotes().get(0).kind);

        // After the wait for offers, a delivery screen counts again.
        tracker.afterScreen(WAITING, 10_000);
        shows(OFFER, 35, 11_000);
        tracker.afterScreen(DELIVERY, 12_000);
        assertNotNull(only(DecisionLog.StepKind.ACCEPTED_LEARNED).accepted);
    }

    @Test
    public void anUnrecognisedScreenCountsNothingOnceItSettlesAndIsKeptForTheReport() {
        shows(OFFER, 35, 1_000);
        List<String> unknown = Arrays.asList("Order details", "Store A", "Items 3");
        tracker.afterScreen(unknown, 2_000);
        // Its numbers ticking keep it the same screen.
        tracker.afterScreen(Arrays.asList("Order details", "Store A", "Items 4"), 4_000);
        tracker.tick(6_999, true);
        assertTrue(tracker.takeNotes().isEmpty());
        assertEquals(2_000 + AcceptedOfferTracker.SETTLE_MS, tracker.nextDeadline());
        // While Dasher cannot be read, nothing settles: that screen starts again at the next read.
        tracker.tick(7_000, false);
        assertTrue(tracker.takeNotes().isEmpty());
        assertEquals(2_000 + AcceptedOfferTracker.AFTER_MS, tracker.nextDeadline());
        tracker.afterScreen(unknown, 8_000);
        tracker.tick(12_999, true);
        assertTrue(tracker.takeNotes().isEmpty());
        tracker.tick(13_000, true);
        AcceptedOfferTracker.Note note = only(DecisionLog.StepKind.NOT_LEARNED);
        assertEquals("Dasher's next screen was neither a delivery nor the wait for offers (its words are in the "
                + "screens log) 11 s after it left", note.detail);
        assertEquals(unknown, note.screen);
        // A delivery screen after the verdict changes nothing.
        tracker.afterScreen(DELIVERY, 14_000);
        assertTrue(tracker.takeNotes().isEmpty());
    }

    @Test
    public void aMinuteWithoutADeliveryOrTheWaitForOffersCountsNothing() {
        shows(OFFER, 35, 1_000);
        tracker.afterScreen(Collections.<String>emptyList(), 2_000);
        tracker.afterScreen(Arrays.asList("New Delivery!", "New Order: Go to Store A"), 3_000);
        assertEquals(2_000 + AcceptedOfferTracker.AFTER_MS, tracker.nextDeadline());
        tracker.tick(61_999, true);
        assertTrue(tracker.takeNotes().isEmpty());
        tracker.tick(62_000, true);
        assertEquals("Dasher showed neither a delivery nor the wait for offers within 60 s",
                only(DecisionLog.StepKind.NOT_LEARNED).detail);
    }

    @Test
    public void anotherOfferFirstOrADeclineOfOursEndsItWithoutAVerdictOfAcceptance() {
        shows(OFFER, 35, 1_000);
        shows(new OfferSnapshot(900, 2.0, 10, 2), 40, 3_000);
        assertEquals("another offer came first", only(DecisionLog.StepKind.NOT_LEARNED).detail);
        tracker.offerDeclinedByApp(new OfferSnapshot(500, 4.0, 20, 2), false, 5_000);
        assertEquals("another offer came first", only(DecisionLog.StepKind.NOT_LEARNED).detail);
        tracker.afterScreen(DELIVERY, 6_000);
        assertTrue("an offer the app declined is never accepted by closing", tracker.takeNotes().isEmpty());
    }

    @Test
    public void addOnsAndUnreadablePayNeedASeenTap() {
        OfferSnapshot increment = new OfferSnapshot(550, 3.8, 12, 2);
        tracker.offerLeftAlone(increment, increment, OFFER, true, true, 35, false, 1_000);
        tracker.afterScreen(DELIVERY, 2_000);
        assertEquals("an add-on counts as accepted only with a seen Accept tap",
                only(DecisionLog.StepKind.NOT_LEARNED).detail);

        OfferSnapshot unknownPay = new OfferSnapshot(null, 7.1, 23, 2);
        tracker.afterScreen(WAITING, 3_000);
        tracker.offerLeftAlone(unknownPay, unknownPay, unknownPay, false, false, 35, false, 4_000);
        tracker.afterScreen(DELIVERY, 5_000);
        assertEquals("its pay was not read", only(DecisionLog.StepKind.NOT_LEARNED).detail);
    }

    @Test
    public void dashersDeclineQuestionCountsAsTheUsersDeclineOnceDasherMovesOn() {
        shows(OFFER, 35, 1_000);
        tracker.declineQuestion(2_000);
        AcceptedOfferTracker.Note asked = only(DecisionLog.StepKind.DECLINE_QUESTION);
        assertEquals(OFFER.fingerprint(), asked.line.fingerprint());
        tracker.declineQuestion(3_000);
        tracker.afterScreen(CHROME, 3_500);
        tracker.afterScreen(Collections.<String>emptyList(), 3_800);
        tracker.afterScreen(WAITING, 5_000);
        AcceptedOfferTracker.Note counted = only(DecisionLog.StepKind.DECLINE_COUNTED);
        assertEquals("Dasher went back to the wait for offers 3 s after it left", counted.detail);
        assertEquals(OFFER.fingerprint(), counted.line.fingerprint());
        assertNull("nothing is held to teach", counted.declined);
        assertNull("a declined offer is never accepted", counted.accepted);
    }

    @Test
    public void goingBackToTheOfferOrEndingTheDashCountsNothing() {
        shows(OFFER, 35, 1_000);
        tracker.declineQuestion(2_000);
        tracker.takeNotes();
        // The sheet closing shows the offer for a frame: not a return.
        shows(OFFER, 33, 2_400);
        assertTrue(tracker.takeNotes().isEmpty());
        tracker.declineQuestion(2_600);
        shows(OFFER, 31, 4_000);
        assertEquals("you went back to the offer", only(DecisionLog.StepKind.DECLINE_DROPPED).detail);
        // It then closes into a delivery: having begun to decline it, that is no Accept.
        tracker.afterScreen(DELIVERY, 6_000);
        assertEquals("you began to decline it, so the delivery screen after it is no Accept",
                only(DecisionLog.StepKind.NOT_LEARNED).detail);

        tracker.afterScreen(WAITING, 7_000);
        shows(OFFER, 35, 8_000);
        tracker.declineQuestion(9_000);
        tracker.takeNotes();
        tracker.afterScreen(PRE_DASH_HOME, 10_000);
        AcceptedOfferTracker.Note dropped = only(DecisionLog.StepKind.DECLINE_DROPPED);
        assertEquals("the dash ended or paused", dropped.detail);
        assertNull(dropped.declined);
    }

    @Test
    public void aQuestionLongAfterTheOfferOrWithoutOneWatchedIsNotTheUsers() {
        tracker.declineQuestion(1_000);
        assertTrue("nothing left alone was on screen", tracker.takeNotes().isEmpty());
        shows(OFFER, 35, 2_000);
        tracker.declineQuestion(2_000 + AcceptedOfferTracker.QUESTION_AGE_MS + 1);
        assertTrue(tracker.takeNotes().isEmpty());
    }

    @Test
    public void aDeclineByHandCountsTheSameWhateverTheRulesSaidAndTeachesNothing() {
        // An offer left for the user's review (miles unread) and one the rules let through count alike.
        OfferSnapshot review = new OfferSnapshot(1675, null, 30, 3);
        tracker.offerLeftAlone(review, review, review, false, false, 35, false, 1_000);
        tracker.declineQuestion(2_000);
        tracker.takeNotes();
        tracker.afterScreen(WAITING, 3_000);
        AcceptedOfferTracker.Note counted = only(DecisionLog.StepKind.DECLINE_COUNTED);
        assertNull(counted.declined);
        assertEquals("Dasher went back to the wait for offers 1 s after it left", counted.detail);

        tracker.offerLeftAlone(OFFER, OFFER, OFFER, false, true, 35, false, 10_000);
        tracker.declineTapped(10_500);
        assertEquals("it counts once Dasher goes back to the wait for offers or another offer comes",
                only(DecisionLog.StepKind.DECLINE_TAPPED).detail);
        tracker.declineQuestion(11_000);
        assertEquals("after your Decline tap", only(DecisionLog.StepKind.DECLINE_QUESTION).detail);
        tracker.afterScreen(WAITING, 13_000);
        counted = only(DecisionLog.StepKind.DECLINE_COUNTED);
        assertNull(counted.declined);
        assertEquals("Dasher went back to the wait for offers 2 s after it left", counted.detail);

        // An add-on's decline by hand counts the same way.
        OfferSnapshot addOn = new OfferSnapshot(500, 2.0, 10, 2);
        tracker.offerLeftAlone(addOn, addOn, addOn, true, true, 35, false, 20_000);
        tracker.declineTapped(20_500);
        assertEquals("it counts once Dasher goes back to the wait for offers or another offer comes",
                only(DecisionLog.StepKind.DECLINE_TAPPED).detail);
    }

    @Test
    public void aSeenTapOnTheOfferIsNotedAndAnAcceptTapNeedsNoCountdown() {
        shows(OFFER, -1, 1_000);
        assertNotNull(tracker.acceptClicked(2_000));
        assertEquals("waiting for a delivery screen", only(DecisionLog.StepKind.ACCEPT_TAPPED).detail);
        // The delivery screen comes after the tap path's 15 s: the tap still counts within the minute.
        tracker.afterScreen(Collections.<String>emptyList(), 3_000);
        assertNotNull(tracker.missedAcceptance(18_000));
        assertEquals(DecisionLog.StepKind.ACCEPT_UNCONFIRMED, only(DecisionLog.StepKind.ACCEPT_UNCONFIRMED).kind);
        tracker.afterScreen(DELIVERY, 20_000);
        AcceptedOfferTracker.Note note = only(DecisionLog.StepKind.ACCEPTED_LEARNED);
        assertTrue(note.accepted.tapSeen);
        assertEquals("you tapped Accept, and Dasher showed a delivery screen 17 s after it left", note.detail);
    }

    @Test
    public void aSeenDeclineTapIsHeldAndCountedOnceDasherGoesBackToTheWaitForOffers() {
        shows(OFFER, 35, 1_000);
        tracker.declineTapped(1_500);
        AcceptedOfferTracker.Note tapped = only(DecisionLog.StepKind.DECLINE_TAPPED);
        assertEquals("it counts once Dasher goes back to the wait for offers or another offer comes", tapped.detail);
        assertNull("nothing is held at the tap itself", tapped.declined);
        tracker.declineQuestion(2_000);
        assertEquals("after your Decline tap", only(DecisionLog.StepKind.DECLINE_QUESTION).detail);
        tracker.afterScreen(WAITING, 3_000);
        AcceptedOfferTracker.Note counted = only(DecisionLog.StepKind.DECLINE_COUNTED);
        assertEquals("Dasher went back to the wait for offers 1 s after it left", counted.detail);
        assertEquals(OFFER.fingerprint(), counted.line.fingerprint());
        assertNull(counted.declined);
    }

    // ---- Accepting without a tap needs positive evidence the user was waiting (S1, S2) ----

    @Test
    public void afterARestartAnOfferWithNothingReadBeforeItCountsNothing() {
        // S1: a fresh tracker (the app restarted mid-delivery), a stacked offer DoorDash pulls, and Dasher back on its
        // delivery screen: that screen proves nothing.
        shows(STACKED, 35, 1_000);
        tracker.afterScreen(DELIVERY, 3_000);
        AcceptedOfferTracker.Note note = only(DecisionLog.StepKind.NOT_LEARNED);
        assertEquals("Dasher's wait for offers wasn't seen before it", note.detail);
        assertNull(note.accepted);
    }

    @Test
    public void anUnclearScreenAfterAnOfferLeavesTheNextOneUncounted() {
        // S2: the user's real acceptance lands on a delivery screen worded in a way the app does not know...
        tracker.afterScreen(WAITING, 500);
        shows(OFFER, 35, 1_000);
        tracker.afterScreen(PICKUP_UNKNOWN, 2_000);
        tracker.tick(7_100, true);
        tracker.afterScreen(PICKUP_UNKNOWN, 7_200);
        assertNull(only(DecisionLog.StepKind.NOT_LEARNED).accepted);
        // ...then a stacked offer DoorDash pulls, and Dasher's delivery screen now shows "Deliver by".
        shows(STACKED, 35, 60_000);
        tracker.afterScreen(DELIVERY, 62_000);
        AcceptedOfferTracker.Note note = only(DecisionLog.StepKind.NOT_LEARNED);
        assertEquals(AcceptedOfferTracker.WAIT_NOT_SEEN, note.detail);
        assertNull(note.accepted);
    }

    @Test
    public void theWaitForOffersCountsForTheNextOfferOnly() {
        tracker.afterScreen(WAITING, 500);
        shows(OFFER, 35, 1_000);
        // Another offer replaces it with nothing read between: the wait was read before the first one.
        shows(STACKED, 35, 3_000);
        assertEquals("another offer came first", only(DecisionLog.StepKind.NOT_LEARNED).detail);
        tracker.afterScreen(DELIVERY, 5_000);
        assertEquals(AcceptedOfferTracker.WAIT_NOT_SEEN, only(DecisionLog.StepKind.NOT_LEARNED).detail);

        // An offer the app declines (or the user took over from it) uses it up too.
        OfferSnapshot failing = new OfferSnapshot(500, 4.0, 20, 2);
        tracker.afterScreen(WAITING, 10_000);
        tracker.observeOffer(failing, 11_000);
        tracker.offerDeclinedByApp(failing, false, 11_000);
        shows(OFFER, 35, 14_000);
        tracker.afterScreen(DELIVERY, 16_000);
        assertEquals(AcceptedOfferTracker.WAIT_NOT_SEEN, only(DecisionLog.StepKind.NOT_LEARNED).detail);

        // The same offer drawn in two frames (the first without its facts yet) keeps the wait read before it.
        OfferSnapshot blank = new OfferSnapshot(null, null, null, null);
        tracker.afterScreen(WAITING, 20_000);
        tracker.observeOffer(blank, 21_000);
        shows(OFFER, 35, 21_200);
        tracker.afterScreen(DELIVERY, 23_000);
        assertNotNull(only(DecisionLog.StepKind.ACCEPTED_LEARNED).accepted);

        // But a next offer drawn the same way after a screen the app does not know (here the pickup of the one just
        // accepted, without a tap) is another offer: the wait was read before the first one.
        tracker.afterScreen(WAITING, 30_000);
        shows(OFFER, 35, 31_000);
        tracker.afterScreen(PICKUP_UNKNOWN, 33_000);
        tracker.observeOffer(blank, 34_000);
        shows(STACKED, 35, 34_200);
        assertEquals("another offer came first", only(DecisionLog.StepKind.NOT_LEARNED).detail);
        tracker.afterScreen(DELIVERY, 36_000);
        assertEquals(AcceptedOfferTracker.WAIT_NOT_SEEN, only(DecisionLog.StepKind.NOT_LEARNED).detail);
    }

    @Test
    public void anOfferBeingDrawnIsNeverANextScreen() {
        tracker.afterScreen(WAITING, 500);
        shows(OFFER, 35, 1_000);
        // The next offer's card drawing in, its buttons not there yet: its "Deliver by" is no delivery under way.
        List<String> drawing = Arrays.asList("Deliver by 9:45 PM", "$18.00", "2 stops (4.0 mi) • 20 min");
        assertEquals(AcceptedOfferTracker.After.OFFER_FACTS, AcceptedOfferTracker.classify(drawing));
        tracker.afterScreen(drawing, 2_000);
        // Facts the words alone do not show (split metric parts) count the same.
        tracker.afterScreen(Collections.singletonList("Deliver by 9:45 PM"), true, 2_200);
        assertTrue(tracker.takeNotes().isEmpty());
        shows(STACKED, 40, 2_500);
        assertEquals("another offer came first", only(DecisionLog.StepKind.NOT_LEARNED).detail);
    }

    @Test
    public void aScreenShowingADeliveryAndTheWaitForOffersAtOnceIsUnclear() {
        assertEquals(AcceptedOfferTracker.After.UNCLEAR, AcceptedOfferTracker.classify(
                Arrays.asList("Deliver by 9:45 PM", "Zone offer wait")));
        assertEquals(AcceptedOfferTracker.After.UNCLEAR, AcceptedOfferTracker.classify(
                Arrays.asList("Arrived at store", "Finding offers")));
        assertEquals(AcceptedOfferTracker.After.UNCLEAR, AcceptedOfferTracker.classify(
                Arrays.asList("Directions", "Dash")));
        tracker.afterScreen(WAITING, 500);
        shows(OFFER, 35, 1_000);
        List<String> both = Arrays.asList("Deliver by 9:45 PM", "Zone offer wait");
        tracker.afterScreen(both, 2_000);
        assertTrue("not a delivery screen", tracker.takeNotes().isEmpty());
        tracker.tick(7_000, true);
        AcceptedOfferTracker.Note note = only(DecisionLog.StepKind.NOT_LEARNED);
        assertTrue(note.detail, note.detail.startsWith("Dasher's next screen was neither a delivery nor the wait"));
        assertEquals(both, note.screen);
    }

    @Test
    public void onlyTheWaitOrTheDashsEndWithNoDeliveryShowingEndsAStoredRoute() {
        assertTrue(AcceptedOfferTracker.showsNoRoute(WAITING));
        assertTrue(AcceptedOfferTracker.showsNoRoute(PRE_DASH_HOME));
        assertTrue(AcceptedOfferTracker.showsNoRoute(Collections.singletonList("Dash ended")));
        assertTrue("an offer drawing over it changes nothing",
                AcceptedOfferTracker.showsNoRoute(Arrays.asList("Finding offers", "$25.00")));
        assertFalse(AcceptedOfferTracker.showsNoRoute(DELIVERY));
        assertFalse(AcceptedOfferTracker.showsNoRoute(Arrays.asList("Deliver by 9:45 PM", "Zone offer wait")));
        assertFalse(AcceptedOfferTracker.showsNoRoute(CHROME));
        assertFalse(AcceptedOfferTracker.showsNoRoute(Collections.<String>emptyList()));
        assertFalse(AcceptedOfferTracker.showsNoRoute(Arrays.asList("New Order: Go to Store A", "Zone offer wait")));
    }

    // ---- A seen Accept tap keeps the guards for standalone offers (S3, S7) ----

    @Test
    public void aSeenAcceptTapStillNeedsTimeLeftAndNoDeliveryUnderWay() {
        // S3: a delivery under way, a stacked offer at 0:02, an Accept tap seen (misread, or failing as it ran out),
        // and Dasher back on its delivery.
        tracker.afterScreen(DELIVERY, 500);
        shows(STACKED, 2, 1_000);
        assertNotNull(tracker.acceptClicked(1_500));
        tracker.takeNotes();
        assertNull(tracker.observeOtherScreen(DELIVERY, 4_000));
        tracker.afterScreen(DELIVERY, 4_000);
        assertEquals(AcceptedOfferTracker.DELIVERY_UNDER_WAY, only(DecisionLog.StepKind.NOT_LEARNED).detail);

        // The same through the tap path's own progress screen, with time left: its verdict is given once.
        tracker.afterScreen(DELIVERY, 10_000);
        tracker.observeOffer(STACKED, STACKED, false, STACKED, 30, false, 11_000);
        tracker.offerLeftAlone(STACKED, STACKED, STACKED, false, true, 30, false, 11_000);
        tracker.acceptClicked(12_000);
        tracker.takeNotes();
        assertNull(tracker.observeOtherScreen(Collections.singletonList("Arrived at store"), 13_000));
        assertEquals(AcceptedOfferTracker.DELIVERY_UNDER_WAY, only(DecisionLog.StepKind.NOT_LEARNED).detail);
        tracker.afterScreen(Collections.singletonList("Arrived at store"), 13_000);
        assertTrue(tracker.takeNotes().isEmpty());

        // The wait for offers read before it, but the tap came as it ran out (0:04, gone 1.5 s later).
        tracker.afterScreen(WAITING, 20_000);
        tracker.observeOffer(OFFER, OFFER, false, OFFER, 4, false, 21_000);
        tracker.acceptClicked(21_500);
        tracker.takeNotes();
        assertNull(tracker.observeOtherScreen(Collections.singletonList("Arrived at store"), 22_500));
        assertEquals("it may have run out: its countdown showed 0:04, 2 s before a read found it gone",
                only(DecisionLog.StepKind.NOT_LEARNED).detail);
    }

    @Test
    public void anOfferCountedFromWhatCameAfterItIsNotCountedAgainByItsTap() {
        // S7: the tap path and what came after the offer both see one acceptance.
        tracker.afterScreen(WAITING, 500);
        shows(OFFER, 35, 1_000);
        assertNotNull(tracker.acceptClicked(2_000));
        tracker.takeNotes();
        assertNull("not a progress step", tracker.observeOtherScreen(DELIVERY, 3_000));
        tracker.afterScreen(DELIVERY, 3_000);
        assertTrue(only(DecisionLog.StepKind.ACCEPTED_LEARNED).accepted.tapSeen);
        assertNull("counted once", tracker.observeOtherScreen(Collections.singletonList("Arrived at store"), 6_000));
        assertTrue(tracker.takeNotes().isEmpty());
    }

    // ---- Dasher's decline question counts only on a clear outcome (S4, S6) ----

    @Test
    public void theQuestionThenTheOfferAgainThenADeliveryCountsNothing() {
        // S4: during a delivery, Decline, the question, back to the offer within a second, then an unseen Accept.
        tracker.afterScreen(DELIVERY, 500);
        shows(STACKED, 35, 1_000);
        tracker.declineQuestion(2_000);
        tracker.declineQuestion(2_500);
        only(DecisionLog.StepKind.DECLINE_QUESTION);
        shows(STACKED, 33, 2_900);
        assertTrue(tracker.takeNotes().isEmpty());
        tracker.afterScreen(DELIVERY, 3_300);
        AcceptedOfferTracker.Note dropped = only(DecisionLog.StepKind.DECLINE_DROPPED);
        assertEquals("Dasher showed a delivery screen next, so you may have accepted it after all", dropped.detail);
        assertNull(dropped.declined);
    }

    @Test
    public void theQuestionThenTheOfferAgainThenAnUnrecognisedScreenCountsNothing() {
        // S6: Decline, View offer details, Accept within about a second, then a pickup screen the app does not know.
        tracker.afterScreen(WAITING, 500);
        shows(OFFER, 35, 1_000);
        tracker.declineQuestion(2_000);
        tracker.declineQuestion(2_900);
        tracker.takeNotes();
        shows(OFFER, 33, 3_200);
        tracker.afterScreen(PICKUP_UNKNOWN, 3_700);
        tracker.tick(8_800, true);
        AcceptedOfferTracker.Note dropped = only(DecisionLog.StepKind.DECLINE_DROPPED);
        assertEquals("Dasher's next screen was neither the wait for offers nor another offer (its words are in the "
                + "screens log)", dropped.detail);
        assertEquals(PICKUP_UNKNOWN, dropped.screen);
        assertNull(dropped.declined);
    }

    @Test
    public void theQuestionCountsOnlyWhenTheWaitForOffersOrTheNextOfferFollows() {
        // The minute runs out: nothing.
        tracker.afterScreen(WAITING, 500);
        shows(OFFER, 35, 1_000);
        tracker.declineQuestion(2_000);
        tracker.takeNotes();
        tracker.afterScreen(Collections.<String>emptyList(), 2_500);
        tracker.tick(2_000 + AcceptedOfferTracker.AFTER_MS, true);
        assertEquals("Dasher showed neither the wait for offers nor another offer within 60 s",
                only(DecisionLog.StepKind.DECLINE_DROPPED).detail);

        // The next offer comes straight after: counted.
        tracker.afterScreen(WAITING, 70_000);
        shows(OFFER, 35, 71_000);
        tracker.declineQuestion(72_000);
        tracker.takeNotes();
        tracker.afterScreen(Collections.<String>emptyList(), 72_500);
        shows(STACKED, 40, 73_000);
        AcceptedOfferTracker.Note counted = only(DecisionLog.StepKind.DECLINE_COUNTED);
        assertEquals("another offer came", counted.detail);
        assertEquals(OFFER.fingerprint(), counted.line.fingerprint());
        assertNull(counted.declined);

        // The next offer after some other screen: nothing.
        tracker.afterScreen(WAITING, 80_000);
        tracker.takeNotes();
        shows(OFFER, 35, 81_000);
        tracker.declineQuestion(82_000);
        tracker.takeNotes();
        tracker.afterScreen(CHROME, 82_500);
        shows(STACKED, 40, 83_000);
        assertEquals("Dasher showed another screen before the next offer came",
                only(DecisionLog.StepKind.DECLINE_DROPPED).detail);

        // Back to the offer within a second, another screen, then the wait: nothing.
        tracker.afterScreen(WAITING, 90_000);
        tracker.takeNotes();
        shows(OFFER, 35, 91_000);
        tracker.declineQuestion(92_000);
        tracker.takeNotes();
        shows(OFFER, 34, 92_300);
        tracker.afterScreen(CHROME, 92_600);
        tracker.afterScreen(WAITING, 93_000);
        assertEquals("you went back to the offer, and Dasher showed another screen before the wait for offers",
                only(DecisionLog.StepKind.DECLINE_DROPPED).detail);

        // The question's sheet closing over the offer for a moment, then the wait: counted.
        shows(OFFER, 35, 100_000);
        tracker.declineQuestion(101_000);
        tracker.takeNotes();
        shows(OFFER, 34, 101_300);
        tracker.afterScreen(WAITING, 101_800);
        assertEquals(OFFER.fingerprint(), only(DecisionLog.StepKind.DECLINE_COUNTED).line.fingerprint());
    }

    // ---- A seen Decline tap is held like the question (S5) ----

    @Test
    public void aSeenDeclineThenBackToTheOfferThenADeliveryCountsNothing() {
        // S5: a seen Decline tap, Cancel back to the offer, then an unseen Accept onto a delivery screen.
        tracker.afterScreen(WAITING, 500);
        shows(OFFER, 35, 1_000);
        tracker.declineTapped(1_500);
        tracker.declineQuestion(2_000);
        shows(OFFER, 31, 4_000);
        tracker.afterScreen(DELIVERY, 6_000);
        List<AcceptedOfferTracker.Note> notes = tracker.takeNotes();
        assertEquals(notes.toString(), 4, notes.size());
        assertEquals(DecisionLog.StepKind.DECLINE_DROPPED, notes.get(2).kind);
        assertEquals("you went back to the offer", notes.get(2).detail);
        assertEquals("you began to decline it, so the delivery screen after it is no Accept", notes.get(3).detail);
        for (AcceptedOfferTracker.Note note : notes) {
            assertNull("nothing is held as a decline", note.declined);
            assertNull("nor accepted", note.accepted);
        }
    }

    @Test
    public void aSeenDeclineTapCountsOnlyOnAClearOutcome() {
        // During a delivery, back to that delivery: a seen tap counts.
        tracker.afterScreen(DELIVERY, 500);
        shows(STACKED, 35, 1_000);
        tracker.declineTapped(1_500);
        tracker.takeNotes();
        tracker.afterScreen(DELIVERY, 2_500);
        AcceptedOfferTracker.Note counted = only(DecisionLog.StepKind.DECLINE_COUNTED);
        assertEquals("Dasher went back to the delivery under way", counted.detail);
        assertEquals(STACKED.fingerprint(), counted.line.fingerprint());
        assertNull(counted.declined);

        // With no delivery under way before, a delivery screen next counts nothing.
        tracker.afterScreen(WAITING, 10_000);
        shows(OFFER, 35, 11_000);
        tracker.declineTapped(11_500);
        tracker.takeNotes();
        tracker.afterScreen(DELIVERY, 12_500);
        assertEquals("a delivery screen came next, and none was under way before",
                only(DecisionLog.StepKind.DECLINE_DROPPED).detail);

        // A later Accept tap on it drops it at once.
        tracker.afterScreen(WAITING, 20_000);
        shows(OFFER, 35, 21_000);
        tracker.declineTapped(21_500);
        tracker.takeNotes();
        tracker.acceptClicked(22_500);
        List<AcceptedOfferTracker.Note> notes = tracker.takeNotes();
        assertEquals(DecisionLog.StepKind.DECLINE_DROPPED, notes.get(0).kind);
        assertEquals("you tapped Accept on it after all", notes.get(0).detail);
        tracker.afterScreen(WAITING, 30_000);
        assertEquals(DecisionLog.StepKind.NOT_ACCEPTED, only(DecisionLog.StepKind.NOT_ACCEPTED).kind);
    }

    @Test
    public void aDeclineThroughDashersNotificationIsNoAcceptance() {
        tracker.afterScreen(WAITING, 500);
        shows(OFFER, 35, 1_000);
        tracker.declineRequestedElsewhere();
        tracker.afterScreen(DELIVERY, 3_000);
        assertEquals("a decline was requested through Dasher's notification, so the delivery screen after it is no "
                + "Accept", only(DecisionLog.StepKind.NOT_LEARNED).detail);
    }

    // ---- The watched line: which offer a decline question the user brought up is about ----

    @Test
    public void theWatchedLineIsTheOfferLeftAloneUntilItsVerdict() {
        assertNull(tracker.watchedLine());
        assertNull(tracker.watchedLine(1_000));
        OfferSnapshot line = new OfferSnapshot(1675, 3.9, 30, 3).withItems(2, true);
        tracker.observeOffer(OFFER, OFFER, false, line, 1_000);
        tracker.offerLeftAlone(line, OFFER, OFFER, false, true, 35, false, 1_000);
        assertEquals("the offer as its history line has it", line.fingerprint(),
                tracker.watchedLine().fingerprint());
        assertEquals(line.fingerprint(),
                tracker.watchedLine(1_000 + AcceptedOfferTracker.QUESTION_AGE_MS).fingerprint());
        assertNull("a question long after the offer was read is not about it",
                tracker.watchedLine(1_001 + AcceptedOfferTracker.QUESTION_AGE_MS));

        tracker.declineQuestion(2_000);
        only(DecisionLog.StepKind.DECLINE_QUESTION);
        assertEquals("once a question was taken to be about it, later reads of it are too", line.fingerprint(),
                tracker.watchedLine(2_000 + 3 * AcceptedOfferTracker.QUESTION_AGE_MS).fingerprint());

        tracker.afterScreen(WAITING, 5_000);
        only(DecisionLog.StepKind.DECLINE_COUNTED);
        assertNull("its verdict ends the watch", tracker.watchedLine());
        assertNull(tracker.watchedLine(5_000));
    }

    @Test
    public void anOfferTheAppDeclinedIsNeverTheWatchedLine() {
        shows(OFFER, 35, 1_000);
        tracker.observeOffer(STACKED, STACKED, false, STACKED, 2_000);
        tracker.offerDeclinedByApp(STACKED, false, 2_000);
        assertNull("another offer came, and the app declined it", tracker.watchedLine());
        assertNull(tracker.watchedLine(2_000));
    }
}

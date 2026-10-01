package com.local.dasherfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Dasher's real delivery and waiting wording, as the user's 0.4.41 report captured it (names made up): what the tab,
 * the guide and learning make of the drop-off leg, the dash's summary, the zone screens, turn-by-turn navigation and
 * Dasher's "End your current dash?". Before this wording was taught, every one of these screens was unclear (or, with
 * a figure on it, an offer being drawn), so an acceptance the user made was never learned.
 */
public class DeliveryWordingTest {
    /** The drop-off card: "Deliver to <name>" over its time, and the customer's drop-off choice. */
    private static final List<String> DROP_OFF_CARD = Arrays.asList("Deliver to Sam", "by 2:39 AM",
            "Leave it at the door");
    /** The drop-off steps, each as Dasher draws it on its own screen. */
    private static final List<String> DROP_OFF_STEPS = Arrays.asList("Leave it at the door", "Hand it to me",
            "Verify correct order", "Check you have the correct order from pick-up", "Take photo of drop-off location",
            "Handed order to customer", "Confirm you handed order directly to customer", "Confirm to complete delivery",
            "Arriving at", "Arriving at 2:39 AM", "Arriving soon",
            "Remember to grab the drinks before delivering the order", "Deliver to Sam");
    /** The dash's summary after a delivery, with what the last offer paid on it. */
    private static final List<String> DASH_SUMMARY = Arrays.asList("This dash so far", "Offers accepted, 1 out of 5",
            "This offer $9.00", "Continue dashing");
    /** The zone screens on the way back. */
    private static final List<String> ZONE = Arrays.asList("Navigate back to old zone",
            "Switch to this zone with peak pay!", "Avg. offer wait", "Dash here");
    /** Turn-by-turn navigation, near a turn and further from one. */
    private static final List<String> NAVIGATION_NEAR = Arrays.asList("300 ft", "Turn left", "mph", "• 2:44 am",
            "Exit");
    private static final List<String> NAVIGATION_FAR = Arrays.asList("0.4 mi", "Turn left onto Elm Rd", "25", "mph",
            "• 2:44 am", "Exit");
    /** Dasher's question before it ends a dash, alone and drawn over the dash's own screen. */
    private static final List<String> END_QUESTION = Arrays.asList("End your current dash?", "End dash", "Go back");
    private static final List<String> DASH_SCREEN = Arrays.asList("This dash",
            "You're in a good place to wait for offers", "Zone offer wait", "1-2 min", "Dash Preferences");
    /** A screen showing neither the dash nor an offer (made up: Dasher's tabs). */
    private static final List<String> NO_DASH = Arrays.asList("Ratings", "Earnings", "Home", "Schedule", "Account");

    private static final OfferSnapshot OFFER = new OfferSnapshot(900, 3.1, 18, 2);

    private final AcceptedOfferTracker tracker = new AcceptedOfferTracker();

    private static AcceptedOfferTracker.After after(List<String> labels) {
        return AcceptedOfferTracker.classify(labels);
    }

    private static List<String> with(List<String> a, List<String> b) {
        List<String> both = new ArrayList<>(a);
        both.addAll(b);
        return both;
    }

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

    // Before: "Deliver to Sam", "by 2:39 AM" and "Leave it at the door" were no marker: UNCLEAR and UNKNOWN.
    @Test
    public void theDropOffCardIsADelivery() {
        assertEquals(AcceptedOfferTracker.After.ROUTE, after(DROP_OFF_CARD));
        assertEquals(DasherScene.ROUTE, DasherScene.of(DROP_OFF_CARD, false));
        assertEquals(AcceptedOfferTracker.After.ROUTE, after(Arrays.asList("Deliver to Sam", "by 2:39 AM")));
        // Case does not matter; "Deliver to " and "Arriving at" begin a label.
        assertEquals(AcceptedOfferTracker.After.ROUTE, after(Collections.singletonList("DELIVER TO SAM P.")));
        // It ends a stored route no more than any delivery does.
        assertFalse(AcceptedOfferTracker.showsNoRoute(DROP_OFF_CARD));
        // Words that only begin alike are still not a delivery.
        assertEquals(AcceptedOfferTracker.After.UNCLEAR, after(Arrays.asList("Delivery fees", "Deliveries")));
    }

    // Before: none of these steps was a marker, so each was UNCLEAR (and UNKNOWN for the tab and guide).
    @Test
    public void theDropOffStepsAreADelivery() {
        for (String step : DROP_OFF_STEPS) {
            List<String> screen = Arrays.asList(step, "Help");
            assertEquals(step, AcceptedOfferTracker.After.ROUTE, after(screen));
            assertEquals(step, DasherScene.ROUTE, DasherScene.of(screen, false));
        }
        assertEquals(AcceptedOfferTracker.After.ROUTE, after(DROP_OFF_STEPS));
    }

    // Before: "This offer $9.00" made the summary an offer being drawn (OFFER_FACTS), and none of its words was the
    // wait for offers (UNKNOWN for the guide, and no stored route ended).
    @Test
    public void theDashSummaryIsTheWaitForOffers() {
        assertEquals(AcceptedOfferTracker.After.WAITING, after(DASH_SUMMARY));
        assertEquals(DasherScene.WAITING, DasherScene.of(DASH_SUMMARY, false));
        assertTrue("it ends a stored route", AcceptedOfferTracker.showsNoRoute(DASH_SUMMARY));
        // Its figures are the dash's, even split across labels, and even as the read's own facts.
        assertEquals(AcceptedOfferTracker.After.WAITING, after(Arrays.asList("This dash so far", "This offer",
                "$9.00", "Continue dashing")));
        assertFalse(AcceptedOfferTracker.offerFacts(OfferParser.parse(DASH_SUMMARY), DASH_SUMMARY));
        assertEquals(AcceptedOfferTracker.After.WAITING, after(Collections.singletonList("Continue dashing")));

        // After an offer the user left alone, it means the offer was not taken.
        shows(OFFER, 35, 1_000);
        tracker.afterScreen(DASH_SUMMARY, 4_000);
        assertEquals("Dasher went back to the wait for offers 0 s after it left",
                only(DecisionLog.StepKind.NOT_ACCEPTED).detail);
    }

    // Before: only "Avg. offer wait" was known; each of the others alone was UNCLEAR, and the way back with
    // navigation on it too.
    @Test
    public void theZoneScreensAreTheWaitForOffers() {
        assertEquals(AcceptedOfferTracker.After.WAITING, after(ZONE));
        for (String label : Arrays.asList("Navigate back to old zone", "Switch to this zone with peak pay!",
                "Dash here")) {
            assertEquals(label, AcceptedOfferTracker.After.WAITING, after(Collections.singletonList(label)));
            assertEquals(label, DasherScene.WAITING, DasherScene.of(Collections.singletonList(label), false));
        }
        // Driving back to a zone: navigation with the zone's words on it is the wait for offers.
        assertEquals(AcceptedOfferTracker.After.WAITING,
                after(with(Collections.singletonList("Navigate back to old zone"), NAVIGATION_NEAR)));
        assertEquals(AcceptedOfferTracker.After.WAITING,
                after(with(Collections.singletonList("Navigate back to old zone"), NAVIGATION_FAR)));
        // "Dash here" is not Dasher's home with its "Dash" button: the dash is on.
        assertFalse(OfferEvidence.isPreDashHome(Collections.singletonList("Dash here")));
    }

    // Before: navigation further from a turn ("0.4 mi") was an offer being drawn (OFFER_FACTS), so after an offer
    // the watch never settled on it and its words never reached the screens log; with the drop-off card on it, it
    // was an offer being drawn too.
    @Test
    public void navigationAloneIsUnclear() {
        assertTrue(DasherScene.showsNavigation(NAVIGATION_NEAR));
        assertTrue(DasherScene.showsNavigation(NAVIGATION_FAR));
        assertEquals(AcceptedOfferTracker.After.UNCLEAR, after(NAVIGATION_NEAR));
        assertEquals(AcceptedOfferTracker.After.UNCLEAR, after(NAVIGATION_FAR));
        assertEquals(DasherScene.UNKNOWN, DasherScene.of(NAVIGATION_FAR, false));
        // Its distance and time are the navigation's own, not an offer's, even as the read's facts.
        assertFalse(AcceptedOfferTracker.offerFacts(OfferParser.parse(NAVIGATION_FAR), NAVIGATION_FAR));
        // With another marker on it, that marker decides; with both kinds, it is no clear answer.
        assertEquals(AcceptedOfferTracker.After.ROUTE, after(with(DROP_OFF_CARD, NAVIGATION_FAR)));
        assertEquals(AcceptedOfferTracker.After.UNCLEAR, after(with(with(DROP_OFF_CARD, ZONE), NAVIGATION_FAR)));
        assertEquals(AcceptedOfferTracker.After.UNCLEAR, after(with(DROP_OFF_CARD, DASH_SCREEN)));
        // Pay over navigation is still an offer being drawn; and an offer's route line is no navigation.
        assertEquals(AcceptedOfferTracker.After.OFFER_FACTS, after(with(NAVIGATION_FAR,
                Collections.singletonList("$7.50"))));
        assertFalse(DasherScene.showsNavigation(Arrays.asList("$7.90", "2 stops (7.2 mi) • 21 min")));
        assertEquals(AcceptedOfferTracker.After.OFFER_FACTS,
                after(Arrays.asList("2 stops (7.2 mi) • 21 min", "Turn left")));

        // After an offer left alone, navigation that stays settles as no answer, and its words go to the screens log.
        tracker.afterScreen(DASH_SCREEN, 500);
        shows(OFFER, 35, 1_000);
        tracker.afterScreen(NAVIGATION_FAR, 2_000);
        tracker.afterScreen(Arrays.asList("0.3 mi", "Turn left onto Elm Rd", "24", "mph", "• 2:44 am", "Exit"), 4_000);
        tracker.tick(7_000, true);
        AcceptedOfferTracker.Note note = only(DecisionLog.StepKind.NOT_LEARNED);
        assertEquals("Dasher's next screen was neither a delivery nor the wait for offers (its words are in the "
                + "screens log) 5 s after it left", note.detail);
        assertEquals(NAVIGATION_FAR, note.screen);
        assertNull(note.accepted);
    }

    // Before: the question drawn over the dash's own screen was the wait for offers (WAITING, and the guide showed
    // over it), and nothing knew the user's "End dash" (no endDashTapped, no END_DASH verdict).
    @Test
    public void theEndDashQuestionEndsTheDashOnlyAfterEndDashAndTheDashScreenGoingAway() {
        assertEquals(AcceptedOfferTracker.After.UNCLEAR, after(END_QUESTION));
        assertEquals(AcceptedOfferTracker.After.UNCLEAR, after(with(DASH_SCREEN, END_QUESTION)));
        assertEquals(DasherScene.UNKNOWN, DasherScene.of(with(DASH_SCREEN, END_QUESTION), false));

        // The user's "End dash", then a screen with no dash on it: the dash is over.
        shows(OFFER, 35, 1_000);
        tracker.afterScreen(with(DASH_SCREEN, END_QUESTION), 3_000);
        assertTrue("the question is no answer at once", tracker.takeNotes().isEmpty());
        tracker.endDashTapped(4_000);
        tracker.afterScreen(Collections.<String>emptyList(), 4_200);
        tracker.afterScreen(NO_DASH, 4_500);
        assertEquals("the dash ended or paused 2 s after it left", only(DecisionLog.StepKind.NOT_ACCEPTED).detail);
        // The next offer has no wait for offers before it: a delivery after it teaches nothing.
        shows(OFFER, 35, 10_000);
        tracker.afterScreen(DROP_OFF_CARD, 12_000);
        assertEquals(AcceptedOfferTracker.WAIT_NOT_SEEN, only(DecisionLog.StepKind.NOT_LEARNED).detail);

        // The tap's click is read after the read of what came next: the same.
        tracker.afterScreen(DASH_SCREEN, 20_000);
        shows(OFFER, 35, 21_000);
        tracker.afterScreen(END_QUESTION, 22_000);
        tracker.afterScreen(NO_DASH, 23_100);
        assertTrue(tracker.takeNotes().isEmpty());
        tracker.endDashTapped(23_000);
        assertEquals("the dash ended or paused 1 s after it left", only(DecisionLog.StepKind.NOT_ACCEPTED).detail);

        // "Go back": the dash's screen again is the wait for offers.
        tracker.afterScreen(DASH_SCREEN, 30_000);
        shows(OFFER, 35, 31_000);
        tracker.afterScreen(END_QUESTION, 32_000);
        tracker.afterScreen(DASH_SCREEN, 33_000);
        assertEquals("Dasher went back to the wait for offers 1 s after it left",
                only(DecisionLog.StepKind.NOT_ACCEPTED).detail);

        // No "End dash" seen: a screen without the dash is only unclear, and so is the question that stays.
        shows(OFFER, 35, 41_000);
        tracker.afterScreen(END_QUESTION, 42_000);
        tracker.afterScreen(NO_DASH, 43_000);
        tracker.tick(48_000, true);
        assertEquals(NO_DASH, only(DecisionLog.StepKind.NOT_LEARNED).screen);
        tracker.afterScreen(DASH_SCREEN, 50_000);
        shows(OFFER, 35, 51_000);
        tracker.afterScreen(END_QUESTION, 52_000);
        tracker.tick(57_000, true);
        assertTrue(only(DecisionLog.StepKind.NOT_LEARNED).detail.startsWith("Dasher's next screen was neither"));
        // An "End dash" with no question read is nothing.
        tracker.afterScreen(DASH_SCREEN, 60_000);
        shows(OFFER, 35, 61_000);
        tracker.afterScreen(NO_DASH, 62_000);
        tracker.endDashTapped(62_500);
        tracker.afterScreen(NO_DASH, 63_000);
        assertTrue(tracker.takeNotes().isEmpty());

        // The tap itself: "End dash" on its own, never the dialog as a whole or "Go back".
        List<String> none = Collections.emptyList();
        assertTrue(new ClickEvidence(none, "", true, "View", false, false, false, -1, none,
                Collections.singletonList("End dash")).endDash());
        assertEquals(ClickEvidence.Verdict.END_DASH, new ClickEvidence(Collections.singletonList("End dash"), "",
                true, "Button", false, false, false, -1, none, none).verdict());
        assertFalse(new ClickEvidence(none, "", true, "View", false, false, false, -1, none, END_QUESTION).endDash());
        assertFalse(new ClickEvidence(none, "", true, "View", false, false, false, -1, none,
                Collections.singletonList("Go back")).endDash());
        assertFalse("never Offer Filter's own", new ClickEvidence(Collections.singletonList("End dash"), "", true,
                "Button", false, false, true, 100, none, none).endDash());
    }

    // Before: "Deliver to Sam" was unclear, so the offer settled as not learned 5 s later instead.
    @Test
    public void anOfferLeftAloneThenDeliverToWithinAMinuteIsLearned() {
        // The user was waiting (the zone screen), the offer closed with time left, and the drop-off card came 45 s
        // later (the pickup leg between went unread).
        tracker.afterScreen(Collections.singletonList("Dash here"), 500);
        shows(OFFER, 35, 1_000);
        shows(OFFER, 30, 6_000);
        tracker.afterScreen(Collections.<String>emptyList(), 7_000);
        assertTrue(tracker.takeNotes().isEmpty());
        tracker.afterScreen(DROP_OFF_CARD, 52_000);
        AcceptedOfferTracker.Note note = only(DecisionLog.StepKind.ACCEPTED_LEARNED);
        assertNotNull(note.accepted);
        assertEquals(Integer.valueOf(900), note.accepted.acceptedOffer.payCents);
        assertFalse(note.accepted.tapSeen);
        assertEquals("it closed with about 0:29 left on its countdown, and Dasher showed a delivery screen 45 s after "
                + "it left", note.detail);

        // With the Accept tap seen, the drop-off steps say the same.
        tracker.afterScreen(DASH_SUMMARY, 60_000);
        shows(OFFER, 35, 61_000);
        tracker.acceptClicked(62_000);
        tracker.takeNotes();
        tracker.afterScreen(Arrays.asList("Verify correct order", "Check you have the correct order from pick-up"),
                63_000);
        AcceptedOfferTracker.Note tapped = only(DecisionLog.StepKind.ACCEPTED_LEARNED);
        assertTrue(tapped.accepted.tapSeen);
        assertEquals("you tapped Accept, and Dasher showed a delivery screen 0 s after it left", tapped.detail);
    }
}

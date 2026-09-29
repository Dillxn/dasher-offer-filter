package com.local.dasherfilter;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/** Controls, confirmation, add-on, notification, and acceptance regressions (spec A3-A5, H1.3-9, H1.13). */
public final class SafetyRegressionTest {
    private static List<String> l(String... labels) { return Arrays.asList(labels); }

    // A3: "Add to route" is the add-on accept control.
    @Test public void addToRouteIsTheAddOnAcceptControl() {
        for (String s : new String[]{"Add to route", "ADD TO ROUTE", "Add to route 77", "Add to route (77)", "Add to route, 30 seconds", "Accept offer", "Accept order"}) assertTrue(s, OfferControls.isButton(s, "accept"));
        for (String s : new String[]{"Add to route?", "Add to route map", "Added to route", "Add to route to earn more"}) assertFalse(s, OfferControls.isButton(s, "accept"));
        assertFalse(OfferControls.isButton("Add to route", "decline"));
    }
    // A4 / H1.7: an unrecognized accept label must fail safe to "not a confirmation".
    @Test public void freshOfferWithUnrecognizedAcceptIsNotAConfirmation() {
        assertFalse(DeclineConfirmation.isSurface(l("$12.00", "2 stops (7.2 mi) • 21 min", "Take this order", "Decline"), false));
        assertFalse(DeclineConfirmation.isSurface(l("Chick-fil-A", "4.1 mi", "Decline offer"), false));
        assertFalse(DeclineConfirmation.isSurface(l("$30.00", "Add to route", "Decline"), OfferControls.isButton("Add to route", "accept")));
        assertEquals(-1, DeclineConfirmation.select(l("$12.00", "7.2 mi", "Decline"), l("Decline"), false));
    }
    @Test public void promptOrCancelStillIdentifyTheConfirmation() {
        assertTrue(DeclineConfirmation.isSurface(l("$12.00", "Are you sure you want to decline?", "Decline"), false));
        assertTrue(DeclineConfirmation.isSurface(l("Decline offer", "Go back"), true));
        assertTrue(DeclineConfirmation.isSurface(l("Declining may lower your acceptance rate. Your acceptance rate may drop", "Decline"), true));
        // Offer evidence + Cancel/Go back without a prompt looks exactly like a fresh offer with a nav control: it confirms
        // only when the adapter proved the candidate shows the declined offer (DeclineState.isDeclinedOffer).
        assertFalse(DeclineConfirmation.isSurface(l("$12.00", "7.2 mi", "Decline offer", "Go back"), true));
        assertTrue(DeclineConfirmation.isSurface(l("$12.00", "7.2 mi", "Decline offer", "Go back"), true, true));
        // A lone Decline with neither prompt nor cancel may be a new offer still rendering: never a confirmation (spec H2.1).
        assertFalse(DeclineConfirmation.isSurface(l("Decline offer"), false));
    }
    // H1.8: countdown labels cannot reset the attempt cap.
    @Test public void countdownVariantsDoNotChangeTheOfferKey() {
        OfferSnapshot o = new OfferSnapshot(1200, 4.2, null, null);
        String[][] ticks = {{"32 secs", "31 secs"}, {"0:32 left", "0:31 left"}, {"32 sec left", "31 sec left"}, {"Expires in 0:32", "Expires in 0:31"}, {"Add to route 77", "Add to route 76"}, {"32s", "31s"}, {"32 seconds", "31 seconds"}};
        for (String[] t : ticks) assertEquals(t[0], DeclineState.offerKey(o, l("Cafe", "$12.00", t[0], "Decline")), DeclineState.offerKey(o, l("Cafe", "$12.00", t[1], "Decline")));
        assertNotEquals(DeclineState.offerKey(o, l("Cafe", "$12.00", "Deliver by 7:45 PM")), DeclineState.offerKey(o, l("Cafe", "$12.00", "Deliver by 8:10 PM")));
    }
    // H1.3: add-on wording and explicit increment tokens are add-on evidence; pay components are not.
    @Test public void addOnEvidenceIncludesIncrementTokensAndMorePhrases() {
        for (List<String> labels : Arrays.asList(l("+$5.00", "+2.1 mi"), l("Add to your route"), l("Adds to your current route"), l("Add this delivery"), l("$4.00", "+1 stop"), l("+2.1 mi"), l("Add to route 77")))
            assertTrue(labels.toString(), AddOnOffer.isLikely(labels));
        assertFalse(AddOnOffer.isLikely(l("$8.75", "Guaranteed (incl. tips)", "+$2.00 Peak Pay")));
        assertFalse("a bare +$ node is a split Peak Pay/boost line as often as an add-on", AddOnOffer.isLikely(l("$3.00", "Guaranteed", "+$2.00", "Peak Pay")));
        assertFalse(AddOnOffer.isLikely(l("+$5.00")));
        assertFalse(AddOnOffer.isLikely(l("$28.24", "+$15.00 Flash offer boost")));
        assertFalse(AddOnOffer.isLikely(l("New order", "$8.00", "4 mi")));
    }
    // H1.9: route miles are summed exactly.
    @Test public void combinedAddOnMilesAreSummedExactly() {
        AddOnOffer a = AddOnOffer.parse(new OfferSnapshot(110, 1.1, null, null), new OfferSnapshot(null, null, null, null), l("Add to route", "+$0.60", "+0.6 mi"));
        assertEquals(1.7, a.combined.miles, 0.0);
        // The exact sum is what the saved route context and the breakdown show (a double sum would read 1.7000000000000002).
        // Third review: a derived route total never decides an add-on, so the rule effect is REVIEW; only displayed totals decide.
        OfferRule.Decision d = OfferRule.evaluateAddOn(a, new FilterSettings(true, 0, 100, 0, 0, 0));
        assertEquals(OfferRule.Result.REVIEW, d.result);
        assertTrue(d.breakdown.toString(), d.breakdown.contains("Route after adding: $1.00/mi: route total not shown (derived 1.7 mi is not used)"));
    }
    // A5 / H1.13: flash offers and merchant names containing exclusion words.
    @Test public void flashOfferNotificationIsRecognized() {
        assertTrue(NotificationOffer.isLikelyOffer(l("Flash offer available", "3.2mi offer from Mr. Pollo. Tap to view offer details.")));
        assertTrue(NotificationOffer.isLikelyOffer(l("$28.24 Flash offer available", "3.2mi offer from Mr. Pollo. ... Tap to view offer details.")));
    }
    @Test public void offerMarkerWinsOverMerchantNameExclusionWords() {
        // H1.13 "(review path)": an offer marker next to an exclusion word is still recognized as an offer, but review-only
        // (never declined, hidden, or rung), because the same shape is also how promotions and balance notices read.
        assertEquals(NotificationOffer.Kind.REVIEW_ONLY, NotificationOffer.classify(l("New Order: Balance Bowls")));
        assertEquals(NotificationOffer.Kind.REVIEW_ONLY, NotificationOffer.classify(l("New Delivery!", "New Order: Go to Deposit Deli")));
        assertEquals(NotificationOffer.Kind.REVIEW_ONLY, NotificationOffer.classify(l("New order", "Go to Promotion Pizza")));
        assertFalse(NotificationOffer.isLikelyOffer(l("New Order: Balance Bowls")));
        assertEquals(NotificationOffer.Kind.OFFER, NotificationOffer.classify(l("New order", "Go to Chick-fil-A")));
        assertFalse(NotificationOffer.isLikelyOffer(l("New message from customer", "Please add a new order for $25, 2 mi")));
        assertFalse(NotificationOffer.isLikelyOffer(l("Weekly earnings", "New offer: $125.00 bonus")));
        assertFalse(NotificationOffer.isLikelyOffer(l("Your deposit of $125.00 is on the way")));
        assertFalse(NotificationOffer.isLikelyOffer(l("Dasher, you have a new message", "Tap to read")));
        // Promotional lead phrases stay hard exclusions even though "new offers" is an offer marker.
        assertFalse(NotificationOffer.isLikelyOffer(l("Dash now and skip the line", "New offers available")));
        assertFalse(NotificationOffer.isLikelyOffer(l("Scheduled dash starts at 6 PM", "New orders expected")));
        assertEquals(NotificationOffer.Kind.REVIEW_ONLY, NotificationOffer.classify(l("Deposit Deli", "New order: 3.2 mi")));
    }
    // H1.4: add-on accepts are gated on route evidence, not the standalone parse.
    @Test public void addOnAcceptWithUnknownStandalonePayStillUpdatesRoute() {
        AcceptedOfferTracker t = new AcceptedOfferTracker();
        t.observeOffer(new OfferSnapshot(null, null, null, null), new OfferSnapshot(3000, null, null, null), true, 1000);
        t.acceptClicked(1100);
        AcceptedOfferTracker.Acceptance a = t.observeOtherScreen(l("Arrived at store"), 1300);
        assertNotNull(a); assertTrue(a.addOn); assertEquals(Integer.valueOf(3000), a.routeAfter.payCents);
    }
    @Test public void unrecordableAddOnAcceptInvalidatesRouteContext() {
        AcceptedOfferTracker t = new AcceptedOfferTracker();
        t.observeOffer(new OfferSnapshot(500, null, null, null), new OfferSnapshot(null, null, null, null), true, 1000);
        t.acceptClicked(1100);
        AcceptedOfferTracker.Acceptance a = t.observeOtherScreen(l("Arrived at store"), 1300);
        assertNotNull(a); assertTrue(a.addOn); assertNull("route context must be invalidated, not saved as a stale base", a.routeAfter);
    }
    // H1.5: an unavailable/expired/taken offer cancels the pending accept.
    @Test public void offerNoLongerAvailableCancelsPendingAccept() {
        for (String gone : new String[]{"This offer is no longer available", "Offer expired", "Offer taken by another Dasher"}) {
            AcceptedOfferTracker t = new AcceptedOfferTracker();
            t.observeOffer(new OfferSnapshot(2500, null, null, null), 1000); t.acceptClicked(1100);
            assertNull(t.observeOtherScreen(l(gone), 1200));
            assertNull(gone, t.observeOtherScreen(l("Arrived at store"), 1300));
        }
    }
    // H1.6: a stale visible offer cannot be credited to a later accept. Leaving the offer screen is the guard; the time
    // window is only a 90 s backstop (H3), because a quiet offer screen is not rescanned before the tap.
    @Test public void staleVisibleOfferIsNotCreditedToALaterAccept() {
        AcceptedOfferTracker t = new AcceptedOfferTracker();
        t.observeOffer(new OfferSnapshot(2500, null, null, null), 1000);
        assertNull(t.observeOtherScreen(l("Loading"), 1100));
        assertEquals(AcceptedOfferTracker.AcceptClick.NONE, t.acceptClicked(1200));
        assertNull(t.observeOtherScreen(l("Arrived at store"), 1300));
        t.observeOtherScreen(l("Looking for offers"), 1500);
        t.observeOffer(new OfferSnapshot(2500, null, null, null), 2000);
        assertEquals(AcceptedOfferTracker.AcceptClick.NONE, t.acceptClicked(92_001));
        t.observeOffer(new OfferSnapshot(2500, null, null, null), 100_000);
        assertEquals(AcceptedOfferTracker.AcceptClick.RECORDED, t.acceptClicked(106_000));
        assertNotNull(t.observeOtherScreen(l("Arrived at store"), 106_100));
    }
}

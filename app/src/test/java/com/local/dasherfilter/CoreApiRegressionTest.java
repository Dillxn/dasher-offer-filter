package com.local.dasherfilter;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/** Regressions that needed a new core API for the adapters to call (spec A5, H1.4, H1.5, H1.6, H1.7). */
public final class CoreApiRegressionTest {
    private static List<String> l(String... s) { return Arrays.asList(s); }

    // H1.5: mid-route, delivery labels are already on screen behind the offer; only NEW ones confirm an accept.
    @Test public void preExistingRouteLabelsDoNotConfirmAFailedAccept() {
        OfferSnapshot offer = new OfferSnapshot(2500, 3.0, null, null);
        AcceptedOfferTracker legacy = new AcceptedOfferTracker();
        legacy.observeOffer(offer, offer, false, 1000); legacy.acceptClicked(1100);
        assertNotNull("0.4.x API: a label already on screen still confirms (adapters must pass labels)", legacy.observeOtherScreen(l("Arrived at customer"), 1300));
        AcceptedOfferTracker t = new AcceptedOfferTracker();
        t.observeOffer(offer, offer, false, l("$25.00", "3.0 mi", "Accept", "Decline", "Arrived at customer"), 1000);
        assertEquals(AcceptedOfferTracker.AcceptClick.RECORDED, t.acceptClicked(1100));
        // Only the label that was already there came back: unknown whether the accept went through. It resolves now, so the
        // dasher's own next route step ("Arrived at store") can never confirm a failed accept.
        assertEquals(AcceptedOfferTracker.Outcome.AMBIGUOUS, t.observeScreen(l("Arrived at customer"), 1300).outcome);
        assertNull(t.observeOtherScreen(l("Arrived at customer", "Arrived at store"), 1400));
        // A new progress label on the first route frame after the tap confirms it.
        AcceptedOfferTracker u = new AcceptedOfferTracker();
        u.observeOffer(offer, offer, false, l("$25.00", "3.0 mi", "Accept", "Decline", "Arrived at customer"), 1000); u.acceptClicked(1100);
        AcceptedOfferTracker.Acceptance a = u.observeOtherScreen(l("Arrived at customer", "Arrived at store"), 1400);
        assertNotNull(a); assertTrue(a.updatesBaseline()); assertEquals(Integer.valueOf(2500), a.baselinePay());
    }
    // H1.4: the adapter learns immediately that an add-on accept could not be recorded.
    @Test public void acceptClickReportsWhetherRouteContextIsKnown() {
        AcceptedOfferTracker t = new AcceptedOfferTracker();
        assertEquals(AcceptedOfferTracker.AcceptClick.NONE, t.acceptClicked(1000));
        t.observeOffer(new OfferSnapshot(null, null, null, null), new OfferSnapshot(null, null, null, null), true, 1000);
        assertEquals(AcceptedOfferTracker.AcceptClick.ROUTE_UNKNOWN, t.acceptClicked(1100));
        AcceptedOfferTracker.Acceptance a = t.observeOtherScreen(l("Arrived at store"), 1200);
        assertNotNull(a); assertNull(a.routeAfter); assertFalse(a.updatesBaseline());
        t.observeOffer(new OfferSnapshot(null, null, null, null), new OfferSnapshot(3000, 6.0, null, 4), true, 2000);
        assertEquals(AcceptedOfferTracker.AcceptClick.RECORDED, t.acceptClicked(2100));
        t.observeOffer(new OfferSnapshot(null, null, null, null), 3000);
        assertEquals("standalone with unreadable pay keeps 0.4.x behavior", AcceptedOfferTracker.AcceptClick.NONE, t.acceptClicked(3100));
    }
    // H1.6: an incomplete or truncated scan also retires the visible offer.
    @Test public void offerNotVisibleRetiresTheVisibleOffer() {
        AcceptedOfferTracker t = new AcceptedOfferTracker();
        t.observeOffer(new OfferSnapshot(2500, null, null, null), 1000); t.offerNotVisible();
        assertEquals(AcceptedOfferTracker.AcceptClick.NONE, t.acceptClicked(1100));
        t.observeOffer(new OfferSnapshot(2500, null, null, null), 2000);
        assertEquals(AcceptedOfferTracker.AcceptClick.NONE, t.acceptClicked(1999));
        assertEquals(AcceptedOfferTracker.AcceptClick.RECORDED, t.acceptClicked(7000));
    }
    // H1.7: a different readable offer revokes confirmation authority without waiting for a notification. Identity is the
    // declined offer's known facts (kept through offerGone), never label keys that countdowns or sheets change.
    @Test public void newOfferIdentityRevokesPendingConfirmation() {
        OfferSnapshot a = new OfferSnapshot(790, 7.2, 21, 2), b = new OfferSnapshot(2500, 7.2, 21, 2), c = new OfferSnapshot(900, null, null, null);
        DeclineState s = new DeclineState();
        assertFalse(s.offerObserved(a, 500));
        s.declineSent("a", a, 1000);
        assertFalse(s.offerObserved(a, 1100)); assertTrue(s.mayConfirm(1100));
        assertTrue(s.offerObserved(b, 1200));
        assertFalse(s.hasPendingConfirmation(1200)); assertFalse(s.mayConfirm(1200));
        assertTrue("the new offer's own first decline stays immediate", s.mayDecline("b", 1200));
        s.declineSent("b", b, 1200); assertTrue(s.mayConfirm(1200));
        assertFalse(s.offerObserved(c, 20000));
        s.declineSent("legacy", 30000);
        assertTrue("without recorded facts nothing proves the same offer", s.offerObserved(a, 30100));
    }
    @Test public void countdownOnlyChangesDoNotRevoke() {
        List<String> tick30 = l("Chick-fil-A", "$7.90", "2 stops (7.2 mi) • 21 min", "Accept 30", "Decline", "0:30 left");
        List<String> tick29 = l("Chick-fil-A", "$7.90", "2 stops (7.2 mi) • 21 min", "Accept 29", "Decline", "0:29 left");
        OfferSnapshot o = OfferParser.parse(tick30);
        DeclineState s = new DeclineState();
        s.declineSent(DeclineState.offerKey(o, tick30), o, 1000);
        assertFalse(s.offerObserved(OfferParser.parse(tick29), 1100));
    }
    // A5: merchant extraction for notifications.
    @Test public void merchantExtractionStopsAtSentenceEnd() {
        assertEquals("Chick-fil-A", NotificationOffer.merchant(l("New Delivery!", "New Order: Go to Chick-fil-A")));
        assertEquals("Mr. Pollo", NotificationOffer.merchant(l("$28.24 Flash offer available", "3.2mi offer from Mr. Pollo. ... Tap to view offer details.")));
        assertEquals("Mr. Pollo", NotificationOffer.merchant(l("3.2mi offer from Mr. Pollo. Tap to view offer details.")));
        assertEquals("P.F. Chang's", NotificationOffer.merchant(l("Offer from P.F. Chang's. Tap to view")));
        assertEquals("Chipotle Mexican Grill", NotificationOffer.merchant(l("New order from Chipotle Mexican Grill")));
        assertEquals("St. Louis Bread Co", NotificationOffer.merchant(l("offer from St. Louis Bread Co. Tap to view offer details.")));
        assertEquals("Taco Bell", NotificationOffer.merchant(l("Go to Taco Bell • 2.1 mi")));
        assertEquals("", NotificationOffer.merchant(l("New order", "$8.50", "4 mi")));
        assertEquals("", NotificationOffer.merchant(null));
        StringBuilder longName = new StringBuilder("Go to "); for (int i = 0; i < 100; i++) longName.append('x');
        assertTrue(NotificationOffer.merchant(l(longName.toString())).length() <= 60);
    }
}

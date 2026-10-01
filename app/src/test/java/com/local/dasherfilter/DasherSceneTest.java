package com.local.dasherfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

/** What the tab and guide over Dasher make of a screen with no sign of an offer on it. */
public class DasherSceneTest {
    @Test public void capturedInDashControlsProveWaitingOnlyTogetherAndWithoutCompetingScreens() {
        assertTrue(OfferEvidence.isIdle(Arrays.asList("This dash", "$0.00", "Dash Preferences", "Safety tools")));
        assertEquals(DasherScene.WAITING, of(false, "This dash", "Dash Preferences", "Safety tools"));
        assertFalse(OfferEvidence.isIdle(Arrays.asList("This dash", "End dash", "Current dash")));
        assertFalse(OfferEvidence.isIdle(Arrays.asList("Safety tools")));
        assertFalse(OfferEvidence.isIdle(Arrays.asList("This dash", "Safety tools", "Dash")));
        assertFalse(OfferEvidence.isIdle(Arrays.asList("This dash", "Safety tools", "Arrived at store")));
        assertFalse(OfferEvidence.isIdle(Arrays.asList("This dash", "Safety tools", "End your current dash?")));
        assertFalse(OfferEvidence.isIdle(Arrays.asList("This dash", "Safety tools", "New Order: Go to Example Cafe")));
        assertFalse(OfferEvidence.isIdle(Arrays.asList("This dash", "Dash Preferences", "Decline")));
        assertFalse(OfferEvidence.isIdle(Arrays.asList("This dash", "Dash Preferences", "Accept")));
        assertFalse(OfferEvidence.isIdle(Arrays.asList("This dash", "Dash Preferences", "0:35")));
        assertFalse(OfferEvidence.isIdle(Arrays.asList("This dash", "Dash Preferences", "Decline offer?")));
        assertEquals(DasherScene.ROUTE, of(false, "This dash", "Safety tools", "Arrived at store"));
    }

    private static DasherScene of(boolean routeStored, String... labels) {
        return DasherScene.of(Arrays.asList(labels), routeStored);
    }

    @Test
    public void onlyTheWaitForOffersIsWaiting() {
        assertEquals(DasherScene.WAITING, of(false, "Finding offers"));
        assertEquals(DasherScene.WAITING, of(false, "Looking for offers..."));
        // The dash's own screen and the dash home, as the user's report shows them.
        assertEquals(DasherScene.WAITING, of(false, "This dash", "You’re in a good place to wait for offers",
                "Zone offer wait", "5-10 min", "Dash Preferences"));
        assertEquals(DasherScene.WAITING, of(false, "Side Menu", "This week", "Earnings Mode Switcher",
                "Time mode off", "Time mode on", "Safety tools", "Dash"));
        assertEquals(DasherScene.WAITING, of(false, "Dash now"));
        // Anything not recognised is unknown, never waiting.
        assertEquals(DasherScene.UNKNOWN, of(false, "Ratings", "Earnings", "Promos", "Help"));
        assertEquals(DasherScene.UNKNOWN, of(false, "Can't start this dash right now", "Got it"));
        assertEquals(DasherScene.UNKNOWN, of(false));
        assertEquals(DasherScene.UNKNOWN, DasherScene.of(null, false));
    }

    @Test
    public void aDeliveryComesFirst() {
        // The user's screenshot: Dasher's delivery screen, with no route stored.
        assertEquals(DasherScene.ROUTE, of(false, "Deliver by 7:45 PM", "Delivery for Sam", "100 Example St",
                "Complete delivery steps"));
        assertEquals(DasherScene.ROUTE, of(false, "Arrived at store"));
        // Whatever else the screen shows.
        assertEquals(DasherScene.ROUTE, of(false, "Finding offers", "Delivery for Sam"));
        assertEquals(DasherScene.ROUTE, of(true, "Zone offer wait", "5-10 min"));
        assertEquals(DasherScene.ROUTE, of(true, "Ratings"));
        // Words that only begin alike are not a delivery.
        assertEquals(DasherScene.UNKNOWN, of(false, "Delivery fees", "Deliveries"));
    }
    @org.junit.Test public void capturedTurnWordsWithDistanceAreNavigationWithoutSpeed() {
        org.junit.Assert.assertTrue(DasherScene.showsNavigation(java.util.Arrays.asList("Turn left", "0.3 mi")));
        org.junit.Assert.assertTrue(DasherScene.showsNavigation(java.util.Arrays.asList("Turn left onto Elm Rd", "300 ft")));
        org.junit.Assert.assertFalse(DasherScene.showsNavigation(java.util.Arrays.asList("Exit", "0.3 mi")));
    }

}

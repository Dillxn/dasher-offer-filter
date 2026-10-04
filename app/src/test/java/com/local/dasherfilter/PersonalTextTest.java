package com.local.dasherfilter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the phone keeps of Dasher's screen text: names, addresses, phone numbers, emails and a customer's own words
 * masked; stores, offer words and figures, buttons and shopping items as read. Fixtures are invented, in the shapes
 * Dasher's screens really have.
 */
public class PersonalTextTest {
    /** A delivery screen as the logs write its labels. */
    private static final String DELIVERY_LINE = "[Deliver to Sam P, by 2:39 AM, Call, Message, 100 Example St, "
            + "Springfield, OH 45000, Leave it at the door, \"100 Example St ..... pls leave it at the desk\", "
            + "McDonald's (32059-SOMEWHERE) (#e34ffd29)]";
    private static final List<String> DELIVERY = Arrays.asList("Deliver to Sam P", "by 2:39 AM", "Call", "Message",
            "100 Example St", "Springfield, OH 45000", "Leave it at the door",
            "\"100 Example St ..... pls leave it at the desk\"", "McDonald's (32059-SOMEWHERE) (#e34ffd29)");

    @Test
    public void aDeliveryScreenKeepsItsStoreButNotItsCustomer() {
        assertEquals(Arrays.asList("Deliver to [name]", "by 2:39 AM", "Call", "Message", "[address]", "[address]",
                "Leave it at the door", "[instructions]", "McDonald's (32059-SOMEWHERE) (#e34ffd29)"),
                PersonalText.mask(DELIVERY));
        assertEquals("[Deliver to [name], by 2:39 AM, Call, Message, [address], [address], Leave it at the door, "
                + "[instructions], McDonald's (32059-SOMEWHERE) (#e34ffd29)]", PersonalText.maskLine(DELIVERY_LINE));
        assertEquals("the line and the labels mask alike", PersonalText.mask(DELIVERY).toString(),
                PersonalText.maskLine(DELIVERY_LINE));
    }

    @Test
    public void customerNamesAfterTheirHeadingsAreMasked() {
        assertEquals("Deliver to [name]", PersonalText.mask("Deliver to Noel G"));
        assertEquals("Delivery for [name]", PersonalText.mask("Delivery for Sam"));
        assertEquals("Drop off for [name]", PersonalText.mask("Drop off for Noel G."));
        assertEquals("Dropoff for [name] · 2.1 mi", PersonalText.mask("Dropoff for Mary Ann S · 2.1 mi"));
        assertEquals("Customer: [name]", PersonalText.mask("Customer: Sam P"));
        assertEquals("Order for [name] $7.90", PersonalText.mask("Order for Jane D. $7.90"));
        // A heading that is a label of its own names the next label.
        assertEquals(Arrays.asList("Customer", "[name]", "Call"),
                PersonalText.mask(Arrays.asList("Customer", "Sam P", "Call")));
        assertEquals(Arrays.asList("Deliver to", "[name]"), PersonalText.mask(Arrays.asList("Deliver to", "Noel G")));
        // Words that only look like names after a heading stay.
        assertEquals("Customer dropoff", PersonalText.mask("Customer dropoff"));
        assertEquals(Arrays.asList("Customer", "Dropoff", "Pickup"),
                PersonalText.mask(Arrays.asList("Customer", "Dropoff", "Pickup")));
        assertEquals("Deliver to Customer", PersonalText.mask("Deliver to Customer"));
        assertEquals("Deliver by 9:45 PM", PersonalText.mask("Deliver by 9:45 PM"));
    }

    @Test
    public void pickupVerificationMasksRecipientInLabelsAndPreviouslyStoredLines() {
        List<String> labels = Arrays.asList("Verify items for Alex Q", "Do not open sealed bags",
                "1 ×", "Example Sandwich", "Confirm pickup", "Continue");
        List<String> masked = Arrays.asList("Verify items for [name]", "Do not open sealed bags",
                "1 ×", "Example Sandwich", "Confirm pickup", "Continue");
        assertEquals(masked, PersonalText.mask(labels));
        assertEquals(masked.toString(), PersonalText.maskLine(labels.toString()));
        assertEquals(masked, PersonalText.mask(masked));
        assertEquals(masked.toString(), PersonalText.maskLine(masked.toString()));
        assertEquals("verify items for: [name]", PersonalText.mask("verify items for: Taylor R."));
        assertEquals(Arrays.asList("Verify items for", "[name]", "Confirm pickup"),
                PersonalText.mask(Arrays.asList("Verify items for", "Alex Q", "Confirm pickup")));
        assertEquals("[Verify items for, [name], Confirm pickup]",
                PersonalText.maskLine("[Verify items for, Alex Q, Confirm pickup]"));
        assertEquals("Verify correct order", PersonalText.mask("Verify correct order"));
    }

    @Test
    public void aNotificationNamingTheCustomerKeepsTheStore() {
        assertEquals("Delivery Update: [name]'s order is ready for pickup at Speedway",
                PersonalText.mask("Delivery Update: Tiaunna's order is ready for pickup at Speedway"));
        assertEquals("Customer's order", PersonalText.mask("Customer's order"));
        assertEquals("McDonald's (32059-NORWOOD RELO)", PersonalText.mask("McDonald's (32059-NORWOOD RELO)"));
    }

    @Test
    public void theDashersOwnNameAboveTheirCompletedOrdersIsMasked() {
        assertEquals(Arrays.asList("[name]", "651 orders completed", "[icon] Pro Shopper", "Home", "…"),
                PersonalText.mask(Arrays.asList("Sam P", "651 orders completed", "[icon] Pro Shopper", "Home", "…")));
        assertEquals("[[name], 651 orders completed, [icon] Pro Shopper, Home, …]",
                PersonalText.maskLine("[Sam P, 651 orders completed, [icon] Pro Shopper, Home, …]"));
        assertEquals("other labels=[Dasher, [name], 1,204 deliveries completed]",
                PersonalText.maskLine("other labels=[Dasher, Sam T, 1,204 deliveries completed]"));
        assertEquals("Good evening, [name]", PersonalText.mask("Good evening, Sam"));
    }

    @Test
    public void addressLinesAndUnitsAreMasked() {
        assertEquals(Arrays.asList("[address]", "[address]", "Apt/Suite: [address]"), PersonalText.mask(
                Arrays.asList("4201 Example Parkway", "Springfield, OH 45229, USA", "Apt/Suite: 504")));
        assertEquals("[address] / [address] / Apt/Suite: [address]", PersonalText.maskLine(
                "4201 Example Parkway / Springfield, OH 45229, USA / Apt/Suite: 504"));
        assertEquals("[address], Apt [address], [address]",
                PersonalText.mask("1234 N Example Ave NW, Apt 5B, San Francisco, CA 94110"));
        assertEquals("[address]", PersonalText.mask("77 5th Ave"));
        assertEquals("Suite [address]", PersonalText.mask("Suite 200"));
        assertEquals("Gate code [address]", PersonalText.mask("Gate code 4321"));
    }

    @Test
    public void phoneNumbersAndEmailsAreMasked() {
        assertEquals("Call [phone]", PersonalText.mask("Call (513) 555-0100"));
        assertEquals("Text [phone] or [phone]", PersonalText.mask("Text +1 513 555 0100 or 513.555.0100"));
        assertEquals("[phone]", PersonalText.mask("5135550100"));
        assertEquals("Questions? [email]", PersonalText.mask("Questions? sam.p+orders@example.com"));
    }

    @Test
    public void aCustomersOwnWordsAreMaskedToTheEndOfTheirLabel() {
        assertEquals("Leave at my door: [instructions]",
                PersonalText.mask("Leave at my door: gate code 1234, ring twice, ask for Sam"));
        assertEquals("Dropoff instructions: [instructions]",
                PersonalText.mask("Dropoff instructions: Blue house behind the church"));
        assertEquals("[instructions]", PersonalText.mask("\"pls leave it by the plant, thx\""));
        assertEquals("Note from customer: [instructions]",
                PersonalText.mask("Note from customer: “side door, code 55”"));
        // In a log line the list's bracket ends a label's own words.
        assertEquals("labels=[Leave at my door: [instructions]] metricParts=[]",
                PersonalText.maskLine("labels=[Leave at my door: gate code 1234, ring twice] metricParts=[]"));
        // Dasher's own drop-off choices stay.
        assertEquals("Leave it at the door", PersonalText.mask("Leave it at the door"));
        assertEquals("Hand it to me", PersonalText.mask("Hand it to me"));
    }

    @Test
    public void offerWordsFiguresButtonsAndShoppingItemsStay() {
        List<String> kept = Arrays.asList("$7.50", "Guaranteed (incl. tips)", "2 stops (7.2 mi) • 21 min",
                "+$2.00 Peak Pay", "Deliver by 9:45 PM", "Accept", "Decline", "0:35", "Very busy", "Customer dropoff",
                "Pickup: McDonald's", "Kroger 2% Reduced Fat Milk, 1 Gallon", "Dr Pepper Zero Sugar 12 pk / 12 fl oz",
                "Lay's Classic Potato Chips 8 oz", "Large Grade A Eggs, 12 ct", "Aisle 12", "Item 3 of 10",
                "New Order: Go to Store A", "Finding offers", "Dash ended", "120 orders completed",
                "Ben & Jerry's Ice Cream, 16 oz");
        assertEquals(kept, PersonalText.mask(kept));
        for (String label : kept) assertEquals(label, PersonalText.maskLine(label));
    }

    @Test
    public void theAppsOwnLogLinesStay() {
        List<String> lines = Arrays.asList(
                "2026-09-30 21:45:12.123 -04:00 [accessibility] first-step Decline REQUESTED: $7.50 · 7.2 mi · 21 min "
                        + "· 2 stops; read after window change (waited 0 ms); sound playing: false",
                "2026-09-30 21:45:12.456 -04:00 [scan] slow read: 230 ms, 1500 nodes, 2 windows (root 12 ms)",
                "Last update attempt epoch ms: 1727654321000",
                "class=Button source=yes target=decline since-own-tap=2500 text=[] desc= above=[] below=[] -> own",
                "offer|$7.50|7.2|21|2|DECLINE: dollars per mile|true|true labels=[$7.50, Accept, Decline]");
        for (String line : lines) assertEquals(line, PersonalText.maskLine(line));
    }

    // ---- Masked since a report carried a payment card page (test card numbers and made-up values only) ----

    @Test
    public void cardNumbersAreMaskedWithSpacesDashesOrNone() {
        assertEquals("[card]", PersonalText.mask("4111 1111 1111 1111"));
        assertEquals("[card]", PersonalText.mask("5555-5555-5555-4444"));
        assertEquals("[card]", PersonalText.mask("378282246310005"));
        assertEquals("Card: [card]", PersonalText.mask("Card: 4111 1111 1111 1111"));
        assertEquals("[Card number, [card], Copy]",
                PersonalText.maskLine("[Card number, 4111 1111 1111 1111, Copy]"));
        // 13 to 19 digits; never part of a longer run, and a run of 12 is no card.
        assertEquals("[card]", PersonalText.mask("4111111111111"));
        assertEquals("[card]", PersonalText.mask("1234567890123"));
        assertEquals("[card]", PersonalText.mask("4111 1111 1111 1111 111"));
        assertEquals("411111111111", PersonalText.mask("411111111111"));
        assertEquals("41111111111111111111", PersonalText.mask("41111111111111111111"));
        // A time in milliseconds, as the app's own lines write it, stays.
        assertEquals("Last update attempt epoch ms: 1727654321000",
                PersonalText.maskLine("Last update attempt epoch ms: 1727654321000"));
    }

    @Test
    public void aCardsSecurityCodeExpiryAndPinAreMasked() {
        assertEquals("CVV [card]", PersonalText.mask("CVV 123"));
        assertEquals("CVC: [card]", PersonalText.mask("CVC: 123"));
        assertEquals("Security code [card]", PersonalText.mask("Security code 123"));
        assertEquals("Exp [card]", PersonalText.mask("Exp 12/34"));
        assertEquals("Exp. date [card]", PersonalText.mask("Exp. date 12/34"));
        assertEquals("Expiry [card]", PersonalText.mask("Expiry 12/2034"));
        assertEquals("Expires: [card]", PersonalText.mask("Expires: 12/34"));
        assertEquals("Valid thru [card]", PersonalText.mask("Valid thru 12/34"));
        assertEquals("PIN [card]", PersonalText.mask("PIN 4321"));
        assertEquals("Exp [card] CVV [card]", PersonalText.mask("Exp 12/34 CVV 123"));
        // The value as the label after its heading.
        assertEquals(Arrays.asList("CVV", "[card]", "Expiry", "[card]", "PIN", "[card]", "Valid thru", "[card]"),
                PersonalText.mask(Arrays.asList("CVV", "123", "Expiry", "12/34", "PIN", "4321", "Valid thru",
                        "12/34")));
        assertEquals("[CVV, [card], Exp:, [card], Done]", PersonalText.maskLine("[CVV, 123, Exp:, 12/34, Done]"));
        // A time after "Expires", or a heading followed by no figure, stays.
        assertEquals("Peak pay expires 9:00 PM", PersonalText.mask("Peak pay expires 9:00 PM"));
        assertEquals("Expires in 5 min", PersonalText.mask("Expires in 5 min"));
        assertEquals(Arrays.asList("PIN", "Change"), PersonalText.mask(Arrays.asList("PIN", "Change")));
    }

    @Test
    public void aTownWithItsZipBeforeItsStateIsMaskedButTheStoreStays() {
        assertEquals("[address]", PersonalText.mask("Springfield, 45000 OH"));
        assertEquals("[address]", PersonalText.mask("45000 OH"));
        assertEquals("[address]", PersonalText.mask("springfield, 45000 oh"));
        assertEquals("[address]", PersonalText.mask("Mount Pleasant, 45000-1234 OH, USA"));
        assertEquals(Arrays.asList("Taco Place", "[address]", "[address]"),
                PersonalText.mask(Arrays.asList("Taco Place", "100 Example St", "Springfield, 45000 OH")));
        assertEquals("[Pickup, Taco Place, [address], [address]]",
                PersonalText.maskLine("[Pickup, Taco Place, 100 Example St, Springfield, 45000 OH]"));
        // Stores with numbers, and money, are no ZIP.
        assertEquals("McDonald's (32059-SOMEWHERE)", PersonalText.mask("McDonald's (32059-SOMEWHERE)"));
        assertEquals("$12345 OH", PersonalText.mask("$12345 OH"));
        assertEquals("Order 45000 ohm", PersonalText.mask("Order 45000 ohm"));
    }

    @Test
    public void theDashersOwnNameBeforeABadgeOrHeadingTheSideMenuIsMasked() {
        assertEquals(Arrays.asList("[name]", "[icon] Pro Shopper"),
                PersonalText.mask(Arrays.asList("Robin Q", "[icon] Pro Shopper")));
        for (String badge : new String[] {"Top Dasher", "Platinum", "Gold", "Silver"}) {
            assertEquals(Arrays.asList("Rewards", "[name]", badge),
                    PersonalText.mask(Arrays.asList("Rewards", "Robin Q", badge)));
            assertEquals("[[name], " + badge + "]", PersonalText.maskLine("[Robin Q, " + badge + "]"));
        }
        assertEquals("[Side Menu, [name], [icon] Pro Shopper, Home]",
                PersonalText.maskLine("[Side Menu, Robin Q, [icon] Pro Shopper, Home]"));
        // Dasher's side menu: the name heads it, before "You're dashing now", "Home" or "Schedule".
        assertEquals(Arrays.asList("[name]", "You're dashing now", "Home", "Schedule"),
                PersonalText.mask(Arrays.asList("Robin Q", "You're dashing now", "Home", "Schedule")));
        assertEquals(Arrays.asList("Side Menu", "[name]", "Home", "Schedule", "Account"),
                PersonalText.mask(Arrays.asList("Side Menu", "Robin Q", "Home", "Schedule", "Account")));
        assertEquals("[[name], You’re dashing now, Home]",
                PersonalText.maskLine("[Robin Q, You’re dashing now, Home]"));
        assertEquals("[Side Menu, [name], Home, Schedule]",
                PersonalText.maskLine("[Side Menu, Robin Q, Home, Schedule]"));
        // Without the side menu, a name-shaped label before "Home" (a zone, a store) stays; so does Dasher's own
        // pre-dash home, whose labels before "Home" are no names.
        assertEquals(Arrays.asList("Taco Place", "Home", "Schedule"),
                PersonalText.mask(Arrays.asList("Taco Place", "Home", "Schedule")));
        List<String> preDashHome = Arrays.asList("Side Menu", "This week", "Earnings Mode Switcher", "Time mode off",
                "Safety tools", "Dash", "dx.home_screen.schedule", "Home", "Schedule", "Account");
        assertEquals(preDashHome, PersonalText.mask(preDashHome));
        // A badge word inside a longer label is no badge.
        assertEquals(Arrays.asList("Kroger Large Eggs", "Gold Peak Tea"),
                PersonalText.mask(Arrays.asList("Kroger Large Eggs", "Gold Peak Tea")));
    }

    @Test
    public void navigationsStreetsAreMaskedButItsDistancesTimesAndTurnsStay() {
        List<String> navigation = Arrays.asList("300 ft", "Turn left onto Elm Rd", "Then", "Toward Exit 40", "25",
                "mph", "• 2:15 am", "12 min", "4.2 mi", "Continue on Oak Ave for 2 mi", "Exit onto I-71 N",
                "Turn right onto 5th Ave", "Head north via Pine St", "Towards Birch Ln", "Keep left", "Turn right",
                "Elm Rd", "Re-center", "Overview", "Mute", "Exit");
        List<String> masked = Arrays.asList("300 ft", "Turn left onto [street]", "Then", "Toward [street]", "25",
                "mph", "• 2:15 am", "12 min", "4.2 mi", "Continue on [street] for 2 mi", "Exit onto [street]",
                "Turn right onto [street]", "Head north via [street]", "Towards [street]", "Keep left", "Turn right",
                "[street]", "Re-center", "Overview", "Mute", "Exit");
        assertEquals(masked, PersonalText.mask(navigation));
        assertEquals(masked, PersonalText.mask(masked));
        // A log line of navigation, marked so or showing a speed and a distance, masks alike.
        String line = "other labels=" + navigation + " metricParts=[]";
        assertEquals("other labels=" + masked + " metricParts=[]", PersonalText.maskLine(line));
        assertEquals("2026-09-30 21:45:12.123 -04:00 [navigation] other labels=[Turn left onto [street], Then]",
                PersonalText.maskLine("2026-09-30 21:45:12.123 -04:00 [navigation] other labels=[Turn left onto "
                        + "Elm Rd, Then]"));
        // Labels and parts masked apart: the caller says it is navigation.
        assertEquals(Arrays.asList("Turn left onto [street]"),
                PersonalText.mask(Arrays.asList("Turn left onto Elm Rd"), true));
        // Away from navigation, "on" and "toward" are words like any other, and a store named like a street stays.
        assertEquals(Arrays.asList("Turn left onto Elm Rd", "Taco Place", "Pickup on Main"),
                PersonalText.mask(Arrays.asList("Turn left onto Elm Rd", "Taco Place", "Pickup on Main")));
        assertEquals("labels=[Taco Place, Continue on Main St]",
                PersonalText.maskLine("labels=[Taco Place, Continue on Main St]"));
    }

    @Test
    public void paymentAccountAndEarningsScreensAreRecognisedByTheirMarkers() {
        List<String> wallet = Arrays.asList("Card details", "Card number", "4111 1111 1111 1111", "Expiry", "12/34",
                "CVV", "123", "Copy card number", "Lock card");
        assertTrue(PersonalText.accountScreen(wallet));
        for (String marker : PersonalText.ACCOUNT_MARKERS) {
            assertTrue(marker, PersonalText.accountScreen(Arrays.asList("Back", marker)));
            assertTrue(marker, PersonalText.accountScreen(Arrays.asList(marker.toLowerCase(java.util.Locale.US))));
            assertTrue(marker, PersonalText.accountText("labels=[Back, " + marker + ": x]"));
        }
        assertTrue(PersonalText.accountScreen(Arrays.asList("Your 1099-NEC is ready")));
        assertTrue(PersonalText.accountScreen(Arrays.asList("Available balance $123.45", "Transfer")));
        assertTrue(PersonalText.accountScreen(Arrays.asList("Earnings history", "Week of Sep 21", "$412.10")));
        assertEquals(PersonalText.LABELS_NOT_KEPT, PersonalText.kept(wallet));
        // Words that merely contain a marker, a house number 1099 and Dasher's offer and dash screens are not one.
        assertFalse(PersonalText.accountScreen(Arrays.asList("Shopping list", "Taxi stand", "Spinach 10 oz",
                "Unpinned", "1099 Example St", "Expiration", "Paying out soon")));
        assertFalse(PersonalText.accountScreen(OFFER));
        assertFalse(PersonalText.accountScreen(Arrays.asList("Finding offers", "This dash so far", "Continue dashing",
                "Dash now", "Side Menu", "Earnings Mode Switcher", "Home", "Schedule", "Account")));
        assertEquals(PersonalText.mask(OFFER).toString(), PersonalText.kept(OFFER));
    }

    /** An offer card as Dasher draws it, with a bare add-on and the chrome beside it. */
    private static final List<String> OFFER = Arrays.asList("$12.50", "Guaranteed (incl. tips)",
            "2 stops (3.1 mi) • 18 min", "Taco Place", "McDonald's (32059-SOMEWHERE)", "Decline", "Accept", "0:35",
            "Very busy", "+$1", "Deliver by 9:45 PM");

    @Test
    public void offerVocabularyIsNeverMaskedByTheNewPatterns() {
        assertEquals(OFFER, PersonalText.mask(OFFER));
        assertEquals(OFFER, PersonalText.mask(OFFER, true));
        for (String label : OFFER) {
            assertEquals(label, PersonalText.mask(label));
            assertEquals(label, PersonalText.maskLine(label));
            assertEquals(label, PersonalText.maskLine(label, true));
        }
        assertEquals("labels=" + OFFER, PersonalText.maskLine("labels=" + OFFER));
    }

    @Test
    public void maskingIsStableAndMaskedTextMasksToItself() {
        String once = PersonalText.maskLine(DELIVERY_LINE);
        assertEquals(once, PersonalText.maskLine(DELIVERY_LINE));
        assertEquals(once, PersonalText.maskLine(once));
        List<String> masked = PersonalText.mask(DELIVERY);
        assertEquals(masked, PersonalText.mask(masked));
        for (String label : masked) assertEquals(label, PersonalText.mask(label));
        String instructions = PersonalText.mask("Leave at my door: gate code 1234, ring twice");
        assertEquals(instructions, PersonalText.mask(instructions));
        assertEquals(instructions, PersonalText.maskLine(instructions));
        assertFalse(once.contains("Sam") || once.contains("Example") || once.contains("45000"));

        // The new kinds too.
        String card = "[Card number, 4111 1111 1111 1111, CVV, 123, Exp 12/34, Robin Q, Gold, Springfield, 45000 OH]";
        String masked1 = PersonalText.maskLine(card);
        assertEquals("[Card number, [card], CVV, [card], Exp [card], [name], Gold, [address]]", masked1);
        assertEquals(masked1, PersonalText.maskLine(masked1));
        String turn = "[300 ft, Turn left onto Elm Rd, 25, mph, Elm Rd]";
        assertEquals(PersonalText.maskLine(turn), PersonalText.maskLine(PersonalText.maskLine(turn)));
        // A whole log, line by line.
        String log = "a [navigation] other labels=[Turn left onto Elm Rd]\nb [screen] other labels=[Turn left onto "
                + "Elm Rd]\n";
        assertEquals("a [navigation] other labels=[Turn left onto [street]]\nb [screen] other labels=[Turn left onto "
                + "Elm Rd]\n", PersonalText.maskLine(log));
    }
    @Test public void aKnownDeliveryStepAllowsMaskedCaptureButNeverOverridesAccountExclusion() {
        assertTrue(PersonalText.recognizedDashScreen(Arrays.asList("Complete delivery steps", "Deliver by 5:40 PM")));
        assertFalse(PersonalText.recognizedDashScreen(Arrays.asList("Complete delivery steps", "Card number", "4111 1111 1111 1111")));
        assertFalse(PersonalText.recognizedDashScreen(Arrays.asList("Directions", "731")));
    }
}

package com.local.dasherfilter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

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
    }
}

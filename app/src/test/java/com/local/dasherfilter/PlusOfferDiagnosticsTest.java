package com.local.dasherfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/**
 * Dasher's busy offer, a bare "+$" amount beside its total, and what a report keeps to explain "pay not found" beside
 * a "+$" amount (the 0.5.1 report's 19:15 offer). Since 0.6.30 the total is the offer's pay, the "+$" amount included
 * (the owner's choice, 21 October 2026); before, the two were only a decline ceiling.
 */
public class PlusOfferDiagnosticsTest {
    private static final String ROUTE = "2 stops (10.6 mi) • 21 min";

    @Test
    public void whyAPlusAmountLeftPayUnreadIsNamedInFixedWords() {
        assertEquals("+$ amount and total not side by side (1 other labels between)",
                reason("+$2", "Peak pay", "Decline", "$10.80", "incl. tips", ROUTE, "Accept", "0:35"));
        assertEquals("a +$ amount with words or beside a total",
                reason("+$2.00 Peak Pay", "Decline", "$10.80", ROUTE, "Accept", "0:35"));
        assertEquals("two +$ amounts", reason("+$2", "+$2.00", "Decline", "$10.80", ROUTE, "Accept", "0:35"));
        assertEquals("no whole total", reason("+$2", "Decline", "$", "10", ".", "80", ROUTE, "Accept", "0:35"));
        assertEquals("two totals",
                reason("+$2", "Decline", "$10.80", ROUTE, "Customer tip $3.00", "Accept", "0:35"));
        assertEquals("a per-order or up-to qualifier",
                reason("+$2", "Decline", "$10.80", ROUTE, "+$2 per order", "Accept", "0:35"));
        assertEquals("added travel", reason("+$2", "Decline", "$10.80", "+1 stop (1.9 mi) • +7 min", "Accept"));
        assertEquals("two totals", reason("Decline", "$10.80", "$12.80", ROUTE, "Accept", "0:35"));
        assertEquals("no +$ amount", reason("Decline", ROUTE, "Accept", "0:35"));
        // Pay read: nothing to explain, whatever the stops or counts.
        assertNull(reason("Decline", "$10.80", ROUTE, "Accept", "0:35"));
        assertNull(reason("Very busy", "+$2", "Decline", "$10.80", ROUTE, "Accept", "0:35"));
        assertNull(reason("+$2", "Decline", "$10.80", ROUTE, "Pick up 1 order", "Accept", "0:35"));
        assertNull(reason("+$2", "Decline", "$10.80", "10.6 mi • 21 min", "Accept", "0:35"));
    }

    @Test
    public void theReasonNeverChangesWhatIsParsed() {
        String[][] screens = {
                {"Very busy", "+$2", "Decline", "$10.80", "incl. tips", ROUTE, "Accept", "0:35"},
                {"+$2", "Peak pay", "Decline", "$10.80", "incl. tips", ROUTE, "Accept", "0:35"},
                {"+$1", "Decline", "$13.00 incl. tips", "3 stops (7.0 mi) • 33 min", "Pick up 2 orders",
                        "Multiple dropoffs (2 stops)", "Accept", "0:24"}};
        int[] pay = {1080, -1, 1300};
        for (int i = 0; i < screens.length; i++) {
            OfferSnapshot offer = OfferParser.parse(Arrays.asList(screens[i]));
            assertEquals(Arrays.toString(screens[i]), pay[i] < 0 ? null : Integer.valueOf(pay[i]), offer.payCents);
            assertNull("never a ceiling", offer.payAtMostCents);
        }
    }

    /**
     * The owner's reports: of 16 October 2026, a "+$3" offer under which Dasher drew a name and a lifetime "N orders
     * completed"; of 20 October 2026, "+$" offers of two stops and one of four, "pay not found". The total is the pay,
     * however many stops, orders or completed orders.
     */
    @Test
    public void aBusyOffersTotalIsItsPayWhateverItsStopsAndOrders() {
        String[] under = {"Pat", "312 orders completed", "Rising Shopper", "trailing_icon", "Home", "Schedule",
                "Account"};
        String[] first = with(new String[] {"+$3", "Decline", "$9.90", "incl. tips", "2 stops (2.9 mi) • 17 min",
                "Qdoba Mexican Eats", "100 Main St", "200 Oak Ave", "Customer dropoff",
                "Guaranteed earnings for completing the offer.", "Accept", "0:13"}, under);
        String[] busy = with(new String[] {"Very busy", "+$3", "Decline", "$11.80", "2 stops (9.4 mi) • 25 min",
                "Chick-fil-A", "100 Main St", "200 Oak Ave", "Customer dropoff",
                "Guaranteed earnings for completing the offer.", "Accept", "0:41"}, under);
        assertNull(reason(first));
        assertEquals(Integer.valueOf(990), OfferParser.parse(Arrays.asList(first)).payCents);
        assertEquals(Integer.valueOf(1180), OfferParser.parse(Arrays.asList(busy)).payCents);
        assertEquals(Integer.valueOf(1180), OfferParser.parse(Arrays.asList(
                with(busy, new String[] {"1,204 orders completed"}))).payCents);
        OfferSnapshot four = OfferParser.parse(Arrays.asList(with(new String[] {"+$2", "Decline", "$14.25",
                "4 stops (8.1 mi) • 36 min", "2 items", "Accept", "0:30"}, under)));
        assertEquals(Integer.valueOf(1425), four.payCents);
        assertEquals(Integer.valueOf(4), four.stops);
        assertEquals(Integer.valueOf(1300), OfferParser.parse(Arrays.asList(with(new String[] {"+$1", "Decline",
                "$13.00", "3 stops (7.0 mi) • 33 min", "Accept", "0:24"}, under))).payCents);
        assertEquals(Integer.valueOf(1300), OfferParser.parse(Arrays.asList(with(new String[] {"+$1", "Decline",
                "$13.00 incl. tips", "3 stops (7.0 mi) • 33 min", "Pick up 2 orders", "Multiple dropoffs (2 stops)",
                "Accept", "0:24"}, under))).payCents);
        assertEquals(Integer.valueOf(990), OfferParser.parse(Arrays.asList("+$3", "Decline", "$9.90",
                "2 stops (2.9 mi) • 17 min", "1 of 2 orders completed", "Accept", "0:13")).payCents);
    }

    private static String[] with(String[] screen, String[] more) {
        String[] all = Arrays.copyOf(screen, screen.length + more.length);
        System.arraycopy(more, 0, all, screen.length, more.length);
        return all;
    }

    private static String reason(String... labels) {
        return OfferParser.noBusyTotalReason(new ArrayList<>(Arrays.asList(labels)), Collections.emptyList());
    }
}

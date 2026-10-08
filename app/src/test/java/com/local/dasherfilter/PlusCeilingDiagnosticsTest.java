package com.local.dasherfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** What a report must keep to explain "pay not found" beside a "+$" amount (the 0.5.1 report's 19:15 offer). */
public class PlusCeilingDiagnosticsTest {
    private static final String ROUTE = "2 stops (10.6 mi) • 21 min";

    @Test
    public void whyAPlusAmountGaveNoCeilingIsNamedInFixedWords() {
        assertEquals("+$ amount and total not side by side (1 other labels between)",
                reason("+$2", "Peak pay", "Decline", "$10.80", "incl. tips", ROUTE, "Accept", "0:35"));
        assertEquals("a +$ amount with words or beside a total",
                reason("+$2.00 Peak Pay", "Decline", "$10.80", ROUTE, "Accept", "0:35"));
        assertEquals("two +$ amounts", reason("+$2", "+$2.00", "Decline", "$10.80", ROUTE, "Accept", "0:35"));
        assertEquals("no whole total", reason("+$2", "Decline", "$", "10", ".", "80", ROUTE, "Accept", "0:35"));
        assertEquals("two totals",
                reason("+$2", "Decline", "$10.80", ROUTE, "Customer tip $3.00", "Accept", "0:35"));
        assertEquals("pickup, dropoff or order counts",
                reason("+$2", "Decline", "$10.80", ROUTE, "Pick up 1 order", "Accept", "0:35"));
        assertEquals("stops not read", reason("+$2", "Decline", "$10.80", "10.6 mi • 21 min", "Accept", "0:35"));
        assertEquals("two totals", reason("Decline", "$10.80", "$12.80", ROUTE, "Accept", "0:35"));
        assertEquals("no +$ amount", reason("Decline", ROUTE, "Accept", "0:35"));
        // Pay read, or a ceiling set: nothing to explain.
        assertNull(reason("Decline", "$10.80", ROUTE, "Accept", "0:35"));
        assertNull(reason("Very busy", "+$2", "Decline", "$10.80", ROUTE, "Accept", "0:35"));
    }

    @Test
    public void theReasonNeverChangesWhatIsParsed() {
        String[][] screens = {
                {"Very busy", "+$2", "Decline", "$10.80", "incl. tips", ROUTE, "Accept", "0:35"},
                {"+$2", "Peak pay", "Decline", "$10.80", "incl. tips", ROUTE, "Accept", "0:35"},
                {"+$1", "Decline", "$13.00 incl. tips", "3 stops (7.0 mi) • 33 min", "Pick up 2 orders",
                        "Multiple dropoffs (2 stops)", "Accept", "0:24"}};
        int[] ceilings = {1280, -1, 1500};
        for (int i = 0; i < screens.length; i++) {
            OfferSnapshot offer = OfferParser.parse(Arrays.asList(screens[i]));
            assertEquals(Arrays.toString(screens[i]), ceilings[i] < 0 ? null : Integer.valueOf(ceilings[i]),
                    offer.payAtMostCents);
        }
    }

    private static String reason(String... labels) {
        return OfferParser.noCeilingReason(new ArrayList<>(Arrays.asList(labels)), Collections.emptyList());
    }
}

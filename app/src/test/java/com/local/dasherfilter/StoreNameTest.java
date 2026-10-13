package com.local.dasherfilter;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The store an offer is from, read from the label after Dasher's trip line (0.6.9), with no authority. */
public class StoreNameTest {
    @Test public void theLabelAfterTheTripLineIsTheStore() {
        // As the 12 October reports read: "2 stops (4.2 mi) • 14 min, DiBella's Subs, [address]".
        assertEquals("DiBella's Subs", StoreName.read(Arrays.asList("$8.70", "incl. tips",
                "2 stops (4.2 mi) • 14 min", "DiBella's Subs", "123 Main St")));
        assertEquals("Chipotle Mexican Grill", StoreName.read(Arrays.asList("$6.25",
                "2 stops (0.9 mi) • 11 min", "Chipotle Mexican Grill", "Accept")));
    }

    @Test public void theOffersOwnWordsAreNeverAStore() {
        for (String next : new String[]{"0:30", "Accept", "Guaranteed earnings for completing the offer.",
                "Multiple dropoffs (2 stops)", "Pick up 2 orders", "$3.50", "12 Oak Ave", "Order for [name]", ""}) {
            assertEquals(next, "", StoreName.read(Arrays.asList("$8.70", "2 stops (4.2 mi) • 14 min", next)));
        }
        assertEquals("", StoreName.read(Arrays.asList("$8.70", "4.2 mi", "14 min", "Taco Bell")));
        assertEquals("", StoreName.read(Collections.singletonList("2 stops (4.2 mi) • 14 min")));
        assertEquals("", StoreName.read(null));
    }

    @Test public void twoOrdersToPickUpMayBeTwoStores() {
        assertTrue(StoreName.singleStore(Arrays.asList("$8.70", "2 stops (4.2 mi) • 14 min", "Taco Bell")));
        assertTrue(StoreName.singleStore(Arrays.asList("$9.56", "4 stops (5.0 mi) • 33 min",
                "Multiple dropoffs (2 stops)")));
        assertFalse(StoreName.singleStore(Arrays.asList("$14.10", "3 stops (7.0 mi) • 33 min", "Pick up 2 orders")));
    }

    @Test public void theSameStoreHoweverItIsSpacedOrCased() {
        assertEquals(StoreName.key("Taco  Bell "), StoreName.key("taco bell"));
    }
}

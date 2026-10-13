package com.local.dasherfilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * The stores an offer is from, read after Dasher's trip line: each pickup store over its address lines (0.6.10, from
 * the owner's 13 offers of 12 October 2026, as his reports kept them, masked), else the label right after the trip line.
 */
public class StoreNameTest {
    /** The owner's 13 offers, one line each: "time | pay:miles:minutes:stops | labels". */
    private static List<List<String>> ownersOffers() throws IOException {
        List<List<String>> offers = new ArrayList<>();
        InputStream in = StoreNameTest.class.getClassLoader().getResourceAsStream("offer-screens-2026-10-12.txt");
        assertNotNull(in);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.startsWith("#") || line.trim().isEmpty()) continue;
                String labels = line.split(" \\| ", 3)[2];
                offers.add(Arrays.asList(labels.split(", ")));
            }
        }
        return offers;
    }

    @Test public void everyStoreOnTheOwnersThirteenOffers() throws IOException {
        List<List<String>> expected = Arrays.asList(
                Collections.singletonList("Chipotle Mexican Grill"),
                Collections.singletonList("Nekter Juice Bar"),
                Collections.singletonList("Insomnia Cookies"),
                Collections.singletonList("DiBella's Subs"),
                // "Pick up 2 orders": two orders from the one store.
                Collections.singletonList("Panda Express"),
                Collections.singletonList("Culver's ButterBurgers and Frozen Custard"),
                Collections.singletonList("Marco's Pizza"),
                Collections.singletonList("Burger King"),
                Collections.singletonList("Culver's ButterBurgers and Frozen Custard"),
                Collections.singletonList("Culver's ButterBurgers and Frozen Custard"),
                // Two stores, each over its own address lines.
                Arrays.asList("Mike’s fish & chicken", "DashMart"),
                Arrays.asList("DashMart", "Gold Star Chili"),
                Arrays.asList("Kroger Express", "DashMart"));
        List<List<String>> offers = ownersOffers();
        assertEquals(expected.size(), offers.size());
        for (int i = 0; i < offers.size(); i++) {
            assertEquals("offer " + (i + 1), expected.get(i), StoreName.stores(offers.get(i)));
            assertEquals("offer " + (i + 1), expected.get(i).size() == 1 ? expected.get(i).get(0) : "",
                    StoreName.read(offers.get(i)));
        }
    }

    @Test public void realAddressesOnThePhoneMarkTheStores() {
        assertEquals(Arrays.asList("Kroger Express", "DashMart"), StoreName.stores(Arrays.asList("Decline", "$11.60",
                "4 stops (6.5 mi) • 23 min", "Kroger Express", "53 East 4th Street", "Cincinnati, OH 45202",
                "DashMart", "1200 Main Street", "Cincinnati, OH 45202", "Multiple dropoffs (2 stops)", "Accept")));
    }

    @Test public void withNoAddressRecognisedTheLabelAfterTheTripLine() {
        assertEquals(Collections.singletonList("DiBella's Subs"), StoreName.stores(Arrays.asList("$8.70",
                "2 stops (4.2 mi) • 14 min", "DiBella's Subs", "Accept")));
    }

    @Test public void theOffersOwnWordsAreNeverAStore() {
        for (String next : new String[]{"0:30", "Accept", "Guaranteed earnings for completing the offer.",
                "Multiple dropoffs (2 stops)", "Pick up 2 orders", "Customer dropoff", "$3.50", "12 Oak Ave",
                "Order for [name]", "[address]", ""}) {
            assertEquals(next, Collections.emptyList(),
                    StoreName.stores(Arrays.asList("$8.70", "2 stops (4.2 mi) • 14 min", next, "Accept")));
        }
        assertEquals(Collections.emptyList(), StoreName.stores(Arrays.asList("$8.70", "4.2 mi", "14 min", "Taco Bell")));
        assertEquals(Collections.emptyList(), StoreName.stores(null));
    }

    @Test public void theSameStoreHoweverItIsSpacedOrCased() {
        assertEquals(StoreName.key("Taco  Bell "), StoreName.key("taco bell"));
    }
}

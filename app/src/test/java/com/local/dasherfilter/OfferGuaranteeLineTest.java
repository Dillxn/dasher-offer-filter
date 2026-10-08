package com.local.dasherfilter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Proposed: Dasher's 2026 offer card ("Guaranteed earnings for completing the offer.") is an offer, not an earnings page. */
public class OfferGuaranteeLineTest {
    /** OfferParserTest.REAL_DOUBLE_OFFER: a real double-order offer screen, Sept 2026 (addresses shortened). */
    static final List<String> REAL_OFFER = Arrays.asList(
            "Decline", "$9.75", "incl. tips", "4 stops (2.6 mi) • 30 min", "Jersey Mike's Subs",
            "53 East 4th Street, 45202 OH", "Chipotle Mexican Grill", "1 Fountain Square Plaza, 45202 OH",
            "Multiple dropoffs (2 stops)", "Guaranteed earnings for completing the offer.", "Accept", "0:46");

    @Test public void aRealOfferCardWithItsGuaranteeLineIsKeptMasked() {
        assertFalse(PersonalText.accountScreen(REAL_OFFER));
        assertFalse(PersonalText.accountText("offer|$9.75|2.6|30|4|DECLINE: x|true|true labels=" + REAL_OFFER));
        assertTrue(PersonalText.recognizedDashScreen(REAL_OFFER));
        String kept = PersonalText.kept(REAL_OFFER);
        assertTrue(kept, kept.contains("Guaranteed earnings for completing the offer."));
        assertTrue(kept, kept.contains("$9.75"));
        assertFalse(kept, kept.contains("53 East") || kept.contains("45202") || kept.contains("Fountain Square"));
        // The add-on and restricted-items variants too.
        assertFalse(PersonalText.accountScreen(Arrays.asList("+$5.50", "+2 stops (3.8 mi) • +12 min",
                "Guaranteed earnings for completing the offer. Contains restricted items, check the recipient's ID.",
                "Accept", "Decline")));
    }

    @Test public void earningsPagesAndWalletsAreStillNeverKept() {
        for (List<String> page : Arrays.asList(
                Arrays.asList("Earnings", "This week", "$412.10"),
                Arrays.asList("Weekly earnings", "$125.00"),
                Arrays.asList("Earnings this week $412.50"),
                Arrays.asList("Earnings history", "Week of Sep 21", "$412.10"),
                Arrays.asList("Total earnings", "Accept", "Decline"),
                Arrays.asList("Guaranteed earnings", "$7.50", "Accept", "Decline"),
                Arrays.asList("Guaranteed earnings for completing the offer.", "Available balance $123.45", "Accept", "Decline"),
                Arrays.asList("Guaranteed earnings for completing the offer.", "Card number", "4111 1111 1111 1111"),
                Arrays.asList("earnings: for completing"))) {
            assertTrue(page.toString(), PersonalText.accountScreen(page));
            assertEquals(page.toString(), PersonalText.LABELS_NOT_KEPT, PersonalText.kept(page));
        }
        // "Earnings Mode" stays no marker, as before.
        assertFalse(PersonalText.accountScreen(Arrays.asList("Earnings Mode Switcher", "Time mode off")));
    }
}

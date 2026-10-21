package com.local.dasherfilter;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * A busy bundle, a bare "+$" amount beside its total: the total is its pay, the amount included, however many orders
 * its counts show (0.6.30, the owner's choice; before, the amount times the orders was only a decline ceiling, and
 * a bundle without its counts had no reading). Add-on wording, added travel and per-order or up-to qualifiers still
 * leave it unread.
 */
public final class BusyBundlePayTest {
    private static OfferSnapshot offer(String total, String route, String... counts) {
        List<String> labels = new ArrayList<>(Arrays.asList("Very busy", "+$1", "Decline", total,
                "incl. tips", route, "Example Restaurant"));
        labels.addAll(Arrays.asList(counts));
        labels.addAll(Arrays.asList("Guaranteed earnings for completing the offer.", "Accept", "0:24"));
        return OfferParser.parse(labels);
    }

    private static OfferSnapshot screenshot() {
        return offer("$13.00", "3 stops (7.0 mi) • 33 min", "Pick up 2 orders", "Multiple dropoffs (2 stops)");
    }

    @Test public void theTotalIsThePayWithEveryOtherFigureAsShown() {
        OfferSnapshot read = screenshot();
        assertEquals(Integer.valueOf(1300), read.payCents);
        assertNull(read.payAtMostCents);
        assertEquals(Integer.valueOf(3), read.stops);
        assertEquals(Double.valueOf(7), read.miles);
        assertEquals(Integer.valueOf(33), read.minutes);
        assertFalse("orders are not shopping items", read.itemCountApplicable);
        assertNull(read.items);
    }

    @Test public void countsChangeNothing() {
        String[][] counts = {
                {}, {"Pick up 2 orders"}, {"Multiple dropoffs (2 stops)"},
                {"Pick up 2 orders", "Multiple dropoffs (1 stop)"},
                {"Pick up 3 orders", "Multiple dropoffs (2 stops)"},
                {"Pick up 2 orders", "Pick up 3 orders", "Multiple dropoffs (2 stops)"},
                {"Pick up 2-3 orders", "Multiple dropoffs (2 stops)"},
                {"Pick up two orders", "Multiple dropoffs (2 stops)"},
                {"Pick up 2 orders", "Multiple dropoffs (2 stops)", "2 pickups"},
                {"Pick up 2 orders", "Multiple pickups (2 stops)", "Multiple dropoffs (2 stops)"},
                {"2 orders"}, {"1 of 2 orders completed"}, {"312 orders completed"}};
        for (String route : new String[] {"2 stops (7.0 mi) • 33 min", "3 stops (7.0 mi) • 33 min",
                "4 stops (7.0 mi) • 33 min"}) {
            for (String[] labels : counts) {
                assertEquals(route + " " + Arrays.toString(labels), Integer.valueOf(1300),
                        offer("$13.00", route, labels).payCents);
            }
        }
    }

    @Test public void wordsThatAddToTheRouteOrCountTheAmountMoreThanOnceLeaveItUnread() {
        for (String[] labels : new String[][] {
                {"Pick up 2 orders", "Multiple dropoffs (2 stops)", "each delivery"},
                {"Pick up 2 orders", "Multiple dropoffs (2 stops)", "Add to route"},
                {"Pick up 2 orders", "Multiple dropoffs (2 stops)", "+2 stops"},
                {"up to 3 orders"}, {"2x"}}) {
            OfferSnapshot read = offer("$13.00", "3 stops (7.0 mi) • 33 min", labels);
            assertNull(Arrays.toString(labels), read.payCents);
            assertNull(Arrays.toString(labels), read.payAtMostCents);
        }
    }

    @Test public void reportedBundlesAreJudgedAtTheirTotals() {
        // The reported rules as 0.5.0 keeps them: $12.75 a stop, $4.09 a mile and 59¢ a minute, at most 3 stops.
        FilterSettings rules = FilterSettings.of(true, 1275, 409, 59, 3);
        OfferSnapshot[] reads = {screenshot(),
                offer("$11.00", "3 stops (8.4 mi) • 42 min", "Pick up 2 orders", "Multiple dropoffs (2 stops)"),
                offer("$11.85", "3 stops (7.6 mi) • 34 min", "Pick up 2 orders", "Multiple dropoffs (2 stops)")};
        for (OfferSnapshot read : reads) {
            OfferRule.Decision decision = OfferRule.evaluate(read, rules);
            assertEquals(OfferRule.Result.DECLINE, decision.result);
            assertEquals("dollars per stop", decision.reason);
            assertTrue("a score of its own", decision.scorePercent >= 0);
            assertSame(read, decision.basis);
        }
    }

    @Test public void aBundleMeetingItsRulesAtItsTotalPasses() {
        // 3 stops, the pickup included (0.6.14): $4.33 a stop asks $12.99, $4.34 asks $13.02.
        assertEquals(OfferRule.Result.KEEP,
                OfferRule.evaluate(screenshot(), FilterSettings.of(true, 433, 0, 0, 3)).result);
        OfferRule.Decision decision = OfferRule.evaluate(screenshot(), FilterSettings.of(true, 434, 0, 0, 3));
        assertEquals(OfferRule.Result.DECLINE, decision.result);
        assertEquals(1302, decision.requiredCents);
    }
}

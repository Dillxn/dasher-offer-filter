package com.local.dasherfilter;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Bare bonuses on a fully counted single-pickup bundle can prove failure, never an exact payout or pass. */
public final class StackedBonusBoundTest {
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

    @Test public void observedOrdersBoundEveryPossiblePerOrderBonusWithoutInventingPay() {
        OfferSnapshot read = screenshot();
        assertNull(read.payCents);
        assertEquals(Integer.valueOf(1500), read.payAtMostCents);
        assertEquals(Integer.valueOf(3), read.stops);
        assertEquals(Double.valueOf(7), read.miles);
        assertEquals(Integer.valueOf(33), read.minutes);
        assertFalse("orders are not shopping items", read.itemCountApplicable);
        assertNull(read.items);
        assertNull("notifications cannot retain a screen-only ceiling", read.withoutPayBound().payAtMostCents);
        assertNull(read.withoutPayBound().payCents);
    }

    @Test public void sharedDropoffsDoNotUnderCountPerOrderBonuses() {
        OfferSnapshot read = offer("$13.00", "3 stops (7.0 mi) • 33 min",
                "Pick up 3 orders", "Multiple dropoffs (2 stops)");
        assertEquals(Integer.valueOf(1600), read.payAtMostCents);
        OfferSnapshot oneDropoff = offer("$13.00", "2 stops (7.0 mi) • 33 min",
                "Pick up 2 orders", "Multiple dropoffs (1 stop)");
        assertEquals("two stops must not fall back to a single bonus", Integer.valueOf(1500), oneDropoff.payAtMostCents);
    }

    @Test public void reportedOffersFailTheirFixedAreaRulesEvenWithEveryObservedBonus() {
        FilterSettings rules = new FilterSettings(true, 1950, 409, 59, 1275, 3)
                .withPerItem(735).withScoreByArea(true);
        OfferSnapshot[] reads = {screenshot(),
                offer("$11.00", "3 stops (8.4 mi) • 42 min", "Pick up 2 orders", "Multiple dropoffs (2 stops)"),
                offer("$11.85", "3 stops (7.6 mi) • 34 min", "Pick up 2 orders", "Multiple dropoffs (2 stops)")};
        for (OfferSnapshot read : reads) {
            OfferRule.Decision decision = OfferRule.evaluate(read, rules);
            assertEquals(OfferRule.Result.DECLINE, decision.result);
            assertTrue(decision.reason, decision.reason.startsWith("pay at most "));
            assertEquals("hypothetical payout is not a plotted score", -1, decision.scorePercent);
            assertSame(read, decision.basis);
            assertNull(decision.basis.payCents);
        }
    }

    @Test public void aCentShortDeclinesButMeetingTheCeilingAlwaysRemainsReview() {
        for (boolean area : new boolean[]{false, true}) {
            FilterSettings rules = new FilterSettings(true, 1501, 0, 0, 0, 3).withScoreByArea(area);
            assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(screenshot(), rules).result);
            rules = new FilterSettings(true, 1500, 0, 0, 0, 3).withScoreByArea(area);
            OfferRule.Decision decision = OfferRule.evaluate(screenshot(), rules);
            assertEquals(OfferRule.Result.REVIEW, decision.result);
            assertEquals(-1, decision.scorePercent);
            assertFalse(AutoAccept.eligible(screenshot(), rules, true, false, false, 24));
            assertEquals(OfferRule.Result.DECLINE,
                    OfferRule.evaluate(screenshot(), rules.withMinimumScalePercent(101)).result);
            assertEquals(OfferRule.Result.REVIEW,
                    OfferRule.evaluate(screenshot(), rules.withMinimumScalePercent(99)).result);
        }
    }

    @Test public void adaptiveFloorsCannotTurnTheCeilingIntoAnAddOnDecline() {
        FilterSettings rules = new FilterSettings(true, 1000, 0, 0, 0, 3, true, 3000);
        for (boolean area : new boolean[]{false, true}) {
            OfferRule.Decision decision = OfferRule.evaluate(screenshot(), rules.withScoreByArea(area));
            assertEquals(OfferRule.Result.REVIEW, decision.result);
            assertEquals(-1, decision.scorePercent);
        }
    }

    @Test public void incompleteConflictingAndMultiplePickupCountsNeverCreateACeiling() {
        String[][] counts = {
                {}, {"Pick up 2 orders"}, {"Multiple dropoffs (2 stops)"},
                {"Pick up 2 orders", "Multiple dropoffs (1 stop)"},
                {"Pick up 1 order", "Multiple dropoffs (2 stops)"},
                {"Pick up 2 orders", "Pick up 3 orders", "Multiple dropoffs (2 stops)"},
                {"Pick up 2 orders", "Multiple dropoffs (2 stops)", "Multiple dropoffs (3 stops)"},
                {"Pick up 2-3 orders", "Multiple dropoffs (2 stops)"},
                {"Pick up two orders", "Multiple dropoffs (2 stops)"},
                {"Pick up 2.5 orders", "Multiple dropoffs (2 stops)"},
                {"Pick up +2 orders", "Multiple dropoffs (2 stops)"},
                {"Pick up 0 orders", "Multiple dropoffs (2 stops)"},
                {"Pick up 2 orders", "Multiple dropoffs (2-3 stops)"},
                {"Pick up 2 orders", "Multiple dropoffs (two stops)"},
                {"Pick up 2 orders", "Multiple dropoffs (2 stops)", "2 pickups"},
                {"Pick up 2 orders", "Multiple pickups (2 stops)", "Multiple dropoffs (2 stops)"},
                {"Pick up 2 orders", "Multiple dropoffs (2 stops)", "each delivery"},
                {"Pick up 2 orders", "Multiple dropoffs (2 stops)", "Add to route"},
                {"Pick up 2 orders", "Multiple dropoffs (2 stops)", "+2 stops"}
        };
        for (String[] labels : counts) {
            OfferSnapshot read = offer("$13.00", "3 stops (7.0 mi) • 33 min", labels);
            assertNull(Arrays.toString(labels), read.payAtMostCents);
            assertNull(Arrays.toString(labels), read.payCents);
        }
        assertNull(offer("$13.00", "4 stops (7.0 mi) • 33 min",
                "Pick up 2 orders", "Multiple dropoffs (2 stops)").payAtMostCents);
    }

    @Test public void twoStopLegacyFallbackCannotIgnoreNewOrderCountEvidence() {
        for (String[] counts : new String[][]{{"Pick up 2 orders"}, {"Pick up 2-3 orders"},
                {"Pick up 2 orders", "Multiple dropoffs (2 stops)"}, {"2 orders"}}) {
            assertNull(Arrays.toString(counts), offer("$13.00", "2 stops (7.0 mi) • 33 min", counts).payAtMostCents);
        }
        assertEquals(Integer.valueOf(1400), offer("$13.00", "2 stops (7.0 mi) • 33 min").payAtMostCents);
    }
}

package com.local.dasherfilter;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.*;

/** Synthetic offer labels: no customer, payment or captured diagnostic fixtures. */
public final class ItemCountTest {
    private static OfferSnapshot parse(String... labels) { return OfferParser.parse(Arrays.asList(labels)); }

    @Test public void explicitShoppingUnitsAreIndependentOfOrdersAndStops() {
        OfferSnapshot offer = parse("Shop & deliver", "$24.00", "Shop for 12 items", "2 orders", "4 stops");
        assertTrue(offer.itemCountApplicable);
        assertEquals(Integer.valueOf(12), offer.items);
        assertEquals(Integer.valueOf(4), offer.stops);
        assertEquals(Integer.valueOf(2400), offer.payCents);
        assertEquals(Integer.valueOf(1), parse("Shop for 1 item").items);
        assertEquals(Integer.valueOf(12), parse("Items: 12").items);
        assertEquals(Integer.valueOf(12), parse("Total items 12").items);
        assertEquals(Integer.valueOf(12), parse("12 total items").items);
        assertEquals(Integer.valueOf(12), parse("12 items in total").items);
        assertEquals(Integer.valueOf(10), parse("New Order: 10 items").items);
        assertEquals(Integer.valueOf(10), parse("New Delivery: 10 items").items);
    }

    @Test public void noItemDeclarationLeavesThisScopedMetricInapplicable() {
        OfferSnapshot ordinary = parse("New order", "$12.00", "2 stops (3 mi) • 20 min", "2 orders");
        assertFalse(ordinary.itemCountApplicable);
        assertNull(ordinary.items);
        assertFalse(OfferSnapshot.UNKNOWN.itemCountApplicable);
        assertFalse(parse("Shoppington Market").itemCountApplicable);
    }

    @Test public void declaredButUnreadOrAmbiguousShoppingDoesNotInventUnits() {
        for (String label : Arrays.asList("Shop & deliver", "Shopping", "Items", "Items: ?", "0 items",
                "-2 items", "1.5 items", "1,5 items", "10000 items", "10–12 items", "10 to 12 items",
                "Items: 10-12", "12+ items", "up to 12 items", "at least 12 items", "about 12 items",
                "3 of 12 items", "12 items remaining", "12 items found", "12 items collected",
                "12 items (3 remaining)", "12 items (estimated)", "Remaining: 12 items", "Items: 2/5",
                "Total items 2 / 5", "Items: 3 of 12", "Items: 12, remaining",
                "12 items/order", "12 items / stop", "Items: 12/order",
                "Items: 12 34", "Total items 12 34", "12 items 34",
                "8 unique items", "Unique items: 8", "8 distinct items")) {
            OfferSnapshot offer = parse("$30.00", label);
            assertTrue(label, offer.itemCountApplicable);
            assertNull(label, offer.items);
        }
        assertNull(parse("Shop & deliver", "6 items", "7 items").items);
        assertNull(parse("12 items", "Items: 10-12").items);
    }

    @Test public void duplicateParentChildAndOrderLabelsNeverSum() {
        assertEquals(Integer.valueOf(12), parse("12 items", "12 items", "Shop for 12 items").items);
        assertNull(parse("First order: 5 items", "Second order: 7 items").items);
        assertNull(parse("First order: 5 items", "Second order: 5 items").items);
        assertNull(parse("Order #1 items: 5", "Order #2 items: 5").items);
        assertNull(parse("5 items, order 1", "5 items, order 2").items);
        assertNull(parse("First order", "5 items", "Second order", "5 items").items);
        assertEquals(Integer.valueOf(10), parse("First order: 5 items", "Second order: 5 items",
                "Total items: 10").items);
        assertEquals(Integer.valueOf(10), parse("First order", "5 items", "Second order", "5 items",
                "Total items: 10").items);
        assertEquals(Integer.valueOf(12), parse("12 items (8 unique)", "8 unique items").items);
        assertEquals(Integer.valueOf(12), parse("Items: 12, 2 orders").items);
    }

    @Test public void siblingItemLabelsPreserveQualificationAndNeverUseOrdersAsItems() {
        assertEquals(Integer.valueOf(12), OfferParser.parse(Arrays.asList("Shop & deliver", "12", "items"),
                OfferParser.joinMetricSiblings(Arrays.asList("12", "items"))).items);
        assertEquals(Integer.valueOf(12), OfferParser.parse(Arrays.asList("Items:", "12"),
                OfferParser.joinMetricSiblings(Arrays.asList("Items:", "12"))).items);
        for (java.util.List<String> labels : Arrays.asList(Arrays.asList("unique", "items", "8"),
                Arrays.asList("12", "items", "remaining"), Arrays.asList("3", "orders"))) {
            assertNull(OfferParser.parse(labels, OfferParser.joinMetricSiblings(labels)).items);
        }
        java.util.List<String> components = Arrays.asList("First order", "5", "items", "Second order", "5", "items");
        assertNull(OfferParser.parse(components, OfferParser.joinMetricSiblings(components)).items);
    }

    @Test public void itemAndStopRatesCannotMasqueradeAsOfferPay() {
        for (String rate : Arrays.asList("$2/item", "$2 / item", "$2 per item", "$2/stop", "$2 per stop")) {
            assertNull(rate, parse(rate).payCents);
            assertEquals(rate, Integer.valueOf(1200), parse("$12.00", "Guaranteed", rate).payCents);
        }
        for (String rate : Arrays.asList("+$2/item", "Extra $2 per item", "+$2/stop", "+$2 per stop",
                "+$2/mi", "+$2 per minute", "New total $2 per item")) {
            AddOnOffer add = AddOnOffer.parse(new OfferSnapshot(1200, 4.0, 20, 2).withItems(12, true),
                    Arrays.asList("Add to route", rate, "+3 items"));
            assertNull(rate, add.incremental.payCents);
            assertNull(rate, add.combined.payCents);
        }
    }

    @Test public void copiesKeepItemEvidenceWhileLegacyDefaultsStayUnchanged() {
        OfferSnapshot bounded = new OfferSnapshot(null, 4.0, 20, 2, 1500, .5).withItems(12, false);
        assertTrue(bounded.itemCountApplicable);
        OfferSnapshot noBound = bounded.withoutPayBound();
        assertNull(noBound.payAtMostCents);
        assertEquals(Integer.valueOf(12), noBound.items);
        assertTrue(noBound.itemCountApplicable);
        assertEquals(Integer.valueOf(12), noBound.withFinalStopHotspotMiles(2.0).items);
        assertTrue(noBound.withItems(null, true).withFinalStopHotspotMiles(null).itemCountApplicable);
        assertNull(noBound.withItems(0, true).items);
        assertNull(noBound.withItems(-1, true).items);
        assertEquals("1200:4.0:20:2", new OfferSnapshot(1200, 4.0, 20, 2).fingerprint());
        assertTrue(bounded.summary().contains("items 12"));
        assertTrue(bounded.withItems(null, true).summary().contains("items ?"));
    }

    @Test public void knownItemContradictionsEndMatchingButItemsAloneDoNotGrantAuthority() {
        OfferSnapshot base = new OfferSnapshot(1200, 4.0, 20, 2);
        OfferSnapshot twelve = base.withItems(12, true), thirteen = base.withItems(13, true);
        assertTrue(twelve.contradicts(thirteen));
        assertFalse(twelve.agreesWith(thirteen));
        assertTrue(twelve.agreesWith(base.withItems(null, true)));
        assertNotEquals(twelve.fingerprint(), thirteen.fingerprint());
        assertFalse(OfferSnapshot.UNKNOWN.withItems(12, true).agreesWith(twelve));
        assertNotEquals(DeclineState.offerKey(twelve, Arrays.asList("12 items", "Accept", "0:42")),
                DeclineState.offerKey(thirteen, Arrays.asList("13 items", "Accept", "0:42")));
        assertEquals(DeclineState.offerKey(twelve, Arrays.asList("12 items", "Accept", "0:42")),
                DeclineState.offerKey(twelve, Arrays.asList("12 items", "Accept", "0:41")));
    }

    @Test public void explicitAddedItemsComposeOnlyKnownRouteUnits() {
        OfferSnapshot active = new OfferSnapshot(2400, 4.0, 20, 2).withItems(12, true);
        for (String label : Arrays.asList("+3 items", "+ 3 items", "Adds 3 items", "3 additional items",
                "3 items more", "Additional items: 3")) {
            AddOnOffer add = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$6", label));
            assertEquals(label, Integer.valueOf(3), add.incremental.items);
            assertEquals(label, Integer.valueOf(15), add.combined.items);
            assertTrue(add.incremental.itemCountApplicable);
        }
        assertNull(parse("+3 items").items);
        AddOnOffer unknown = AddOnOffer.parse(null, Arrays.asList("Add to route", "+$6", "+3 items"));
        assertEquals(Integer.valueOf(3), unknown.incremental.items);
        assertNull(unknown.combined.items);
        assertTrue(unknown.combined.itemCountApplicable);
    }

    @Test public void addOnTotalNeverInventsIncrementOrUsesConflictingCounts() {
        OfferSnapshot active = new OfferSnapshot(2400, 4.0, 20, 2).withItems(12, true);
        AddOnOffer onlyTotal = AddOnOffer.parse(active, Arrays.asList("Add to route", "New total items: 15"));
        assertEquals(Integer.valueOf(15), onlyTotal.combined.items);
        assertNull(onlyTotal.incremental.items);
        AddOnOffer totalAndParts = AddOnOffer.parse(active, Arrays.asList("Add to route", "New total items: 15",
                "First order: 5 items", "Second order: 10 items"));
        assertEquals(Integer.valueOf(15), totalAndParts.combined.items);
        assertNull(totalAndParts.incremental.items);
        AddOnOffer bare = AddOnOffer.parse(active, Arrays.asList("Add to route", "Shop for 3 items"));
        assertNull(bare.incremental.items);
        assertNull(bare.combined.items);
        assertTrue(bare.incremental.itemCountApplicable);
        for (java.util.List<String> labels : Arrays.asList(Arrays.asList("+3 items", "Total items: 16"),
                Arrays.asList("+3 items", "+4 items"), Arrays.asList("+3-4 items", "Total items: 15"))) {
            AddOnOffer conflict = AddOnOffer.parse(active, labels);
            assertNull(conflict.incremental.items);
            assertNull(conflict.combined.items);
        }
        AddOnOffer ordinaryAdded = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$6", "+2 stops"));
        assertFalse(ordinaryAdded.incremental.itemCountApplicable);
        assertTrue(ordinaryAdded.combined.itemCountApplicable);
        assertNull(ordinaryAdded.combined.items);
    }
}

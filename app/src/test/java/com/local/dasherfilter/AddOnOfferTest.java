package com.local.dasherfilter;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public final class AddOnOfferTest {
    @Test public void recognizesAddOnLanguage() {
        assertTrue(AddOnOffer.isLikely(Arrays.asList("Add this order to your route")));
        assertTrue(AddOnOffer.isLikely(Arrays.asList("Another order", "+$5.00")));
        assertFalse(AddOnOffer.isLikely(Arrays.asList("New order", "$8.00", "4 mi")));
    }

    @Test public void treatsOrdinaryAddOnNumbersAsIncremental() {
        OfferSnapshot active = new OfferSnapshot(2500, 8.0, 40, 2);
        OfferSnapshot shown = new OfferSnapshot(500, 2.0, 10, 2);
        AddOnOffer addOn = AddOnOffer.parse(active, shown,
                Arrays.asList("Add to route", "$5.00", "2 mi", "Estimated time 10 min", "2 stops"));
        assertEquals(Integer.valueOf(500), addOn.incremental.payCents);
        assertEquals(2.0, addOn.incremental.miles, 0.001);
        assertEquals(Integer.valueOf(3000), addOn.combined.payCents);
        assertEquals(10.0, addOn.combined.miles, 0.001);
        assertEquals(Integer.valueOf(4), addOn.combined.stops);
    }

    @Test public void convertsExplicitNewTotalsBackToMarginalValues() {
        OfferSnapshot active = new OfferSnapshot(2500, 8.0, 40, 2);
        OfferSnapshot shown = OfferParser.parse(Arrays.asList(
                "Add to route", "Total pay $30.00", "Total distance: 10 mi",
                "Total time: 50 min", "Total stops: 4 stops"));
        AddOnOffer addOn = AddOnOffer.parse(active, shown, Arrays.asList(
                "Add to route", "Total pay $30.00", "Total distance: 10 mi",
                "Total time: 50 min", "Total stops: 4 stops"));
        assertEquals(Integer.valueOf(500), addOn.incremental.payCents);
        assertEquals(2.0, addOn.incremental.miles, 0.001);
        assertEquals(Integer.valueOf(10), addOn.incremental.minutes);
        assertEquals(Integer.valueOf(2), addOn.incremental.stops);
        assertEquals(Integer.valueOf(3000), addOn.combined.payCents);
    }

    @Test public void explicitIncrementAndTotalMustAgree() {
        OfferSnapshot active = new OfferSnapshot(2500, 8.0, null, null);
        OfferSnapshot shown = OfferParser.parse(Arrays.asList(
                "Add to route", "+$5.00", "New total $40.00"));
        AddOnOffer addOn = AddOnOffer.parse(active, shown,
                Arrays.asList("Add to route", "+$5.00", "New total $40.00"));
        assertNull(addOn.incremental.payCents);
        assertNull(addOn.combined.payCents);
    }
}

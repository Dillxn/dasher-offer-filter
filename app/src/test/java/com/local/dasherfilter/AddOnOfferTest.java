package com.local.dasherfilter;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
public final class AddOnOfferTest {
    @Test public void recognizesAddOnLanguage() {
        assertTrue(AddOnOffer.isLikely(Arrays.asList("Add this order to your route")));
        assertTrue(AddOnOffer.isLikely(Arrays.asList("Another order", "+$5.00")));
        assertFalse(AddOnOffer.isLikely(Arrays.asList("New order", "$8.00", "4 mi")));
        assertFalse(AddOnOffer.isLikely(Arrays.asList("Stacked order", "$20.00")));
    }
    @Test public void unlabeledAddOnNumbersDoNotEstablishIncrementalMeaning() {
        AddOnOffer a = AddOnOffer.parse(new OfferSnapshot(2500, 8.0, 40, 2), new OfferSnapshot(500, 2.0, 10, 2), Arrays.asList("Add to route", "$5.00", "2 mi", "Estimated time 10 min", "2 stops"));
        assertNull(a.incremental.payCents); assertNull(a.incremental.miles); assertNull(a.incremental.minutes); assertNull(a.incremental.stops);
    }
    @Test public void explicitIncrementalValuesComposeTheRoute() {
        AddOnOffer a = AddOnOffer.parse(new OfferSnapshot(2500, 8.0, 40, 2), new OfferSnapshot(500, 2.0, 10, 2), Arrays.asList("Add to route", "+$5.00", "+2 mi", "+10 min", "+2 stops"));
        assertEquals(Integer.valueOf(500), a.incremental.payCents); assertEquals(2.0, a.incremental.miles, .001); assertEquals(Integer.valueOf(3000), a.combined.payCents); assertEquals(10.0, a.combined.miles, .001); assertEquals(Integer.valueOf(4), a.combined.stops);
    }
    @Test public void totalsDoNotInventMarginalTravelFromAnOldRoute() {
        AddOnOffer a = AddOnOffer.parse(new OfferSnapshot(2500, 8.0, 40, 2), new OfferSnapshot(3000, 10.0, 50, 4), Arrays.asList("Add to route", "Total pay $30.00", "Total distance: 10 mi", "Total time: 50 min", "Total stops: 4 stops"));
        assertEquals(Integer.valueOf(500), a.incremental.payCents); assertNull(a.incremental.miles); assertNull(a.incremental.minutes); assertNull(a.incremental.stops); assertEquals(Integer.valueOf(3000), a.combined.payCents); assertEquals(10.0, a.combined.miles, .001);
    }
    @Test public void explicitIncrementAndTotalMustAgree() {
        AddOnOffer a = AddOnOffer.parse(new OfferSnapshot(2500, 8.0, null, null), new OfferSnapshot(null, null, null, null), Arrays.asList("Add to route", "+$5.00", "New total $40.00"));
        assertNull(a.incremental.payCents); assertNull(a.combined.payCents);
    }
    @Test public void conflictingIncrementsNeverFallBackToShownNumbers() {
        AddOnOffer a = AddOnOffer.parse(new OfferSnapshot(2500, 8.0, null, null), new OfferSnapshot(500, 2.0, null, null), Arrays.asList("Add to route", "+$5.00", "+$7.00", "+2 mi", "+3 mi"));
        assertNull(a.incremental.payCents); assertNull(a.combined.payCents); assertNull(a.incremental.miles); assertNull(a.combined.miles);
    }
    @Test public void missingRouteDoesNotTurnAddOnIntoStandaloneOffer() {
        AddOnOffer a = AddOnOffer.parse(null, new OfferSnapshot(500, 2.0, null, null), Arrays.asList("Add to route", "+$5.00", "+2 mi"));
        assertNull(a.combined.payCents); assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluateAddOn(a, new FilterSettings(true, 2000, 100, 0, 0, 0)).result);
    }
}

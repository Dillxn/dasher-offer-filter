package com.local.dasherfilter;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Add-on parsing trusts only explicitly marked incremental values and totals that agree with the route. */
public final class AddOnOfferTest {
    @Test
    public void recognizesAddOnLanguage() {
        assertTrue(AddOnOffer.isLikely(Arrays.asList("Add this order to your route")));
        assertTrue(AddOnOffer.isLikely(Arrays.asList("Another order", "+$5.00")));
        assertFalse(AddOnOffer.isLikely(Arrays.asList("New order", "$8.00", "4 mi")));
        assertFalse(AddOnOffer.isLikely(Arrays.asList("Stacked order", "$20.00")));
    }

    @Test
    public void unlabeledAddOnNumbersDoNotEstablishIncrementalMeaning() {
        OfferSnapshot route = new OfferSnapshot(2500, 8.0, 40, 2);
        AddOnOffer addOn = AddOnOffer.parse(route,
                Arrays.asList("Add to route", "$5.00", "2 mi", "Estimated time 10 min", "2 stops"));
        assertNull(addOn.incremental.payCents);
        assertNull(addOn.incremental.miles);
        assertNull(addOn.incremental.minutes);
        assertNull(addOn.incremental.stops);
    }

    @Test
    public void explicitIncrementalValuesComposeTheRoute() {
        OfferSnapshot route = new OfferSnapshot(2500, 8.0, 40, 2);
        AddOnOffer addOn = AddOnOffer.parse(route,
                Arrays.asList("Add to route", "+$5.00", "+2 mi", "+10 min", "+2 stops"));
        assertEquals(Integer.valueOf(500), addOn.incremental.payCents);
        assertEquals(2.0, addOn.incremental.miles, .001);
        assertEquals(Integer.valueOf(3000), addOn.combined.payCents);
        assertEquals(10.0, addOn.combined.miles, .001);
        assertEquals(Integer.valueOf(4), addOn.combined.stops);
    }

    /**
     * Miles composed from the route and an explicit increment add as the decimals they were read as: 3.1 + 2.2 is 5.3,
     * not 5.300000000000001, so $1.00 a mile asks the combined route $5.30, not $5.31 (AGENTS: per mile is multiplied
     * as an exact BigDecimal), and a $5.30 route that meets it exactly is kept.
     */
    @Test
    public void composedMilesAddAsReadSoAnExactlyMeetingRouteIsNotDeclinedByACent() {
        OfferSnapshot route = new OfferSnapshot(300, 3.1, 20, 1);
        AddOnOffer addOn = AddOnOffer.parse(route, Arrays.asList("Add to route", "+$2.30", "+2.2 mi", "+10 min"));
        assertEquals(Double.valueOf(5.3), addOn.combined.miles);
        assertEquals(Integer.valueOf(530), addOn.combined.payCents);
        OfferRule.Decision decision = OfferRule.evaluateAddOn(addOn, FilterSettings.of(true, 0, 100, 0, 0));
        assertEquals(decision.reason, OfferRule.Result.KEEP, decision.result);
    }

    @Test
    public void totalsDoNotInventMarginalTravelFromAnOldRoute() {
        OfferSnapshot route = new OfferSnapshot(2500, 8.0, 40, 2);
        AddOnOffer addOn = AddOnOffer.parse(route, Arrays.asList(
                "Add to route", "Total pay $30.00", "Total distance: 10 mi",
                "Total time: 50 min", "Total stops: 4 stops"));
        assertEquals(Integer.valueOf(500), addOn.incremental.payCents);
        assertNull(addOn.incremental.miles);
        assertNull(addOn.incremental.minutes);
        assertNull(addOn.incremental.stops);
        assertEquals(Integer.valueOf(3000), addOn.combined.payCents);
        assertEquals(10.0, addOn.combined.miles, .001);
    }

    @Test
    public void explicitIncrementAndTotalMustAgree() {
        OfferSnapshot route = new OfferSnapshot(2500, 8.0, null, null);
        AddOnOffer addOn = AddOnOffer.parse(route, Arrays.asList("Add to route", "+$5.00", "New total $40.00"));
        assertNull(addOn.incremental.payCents);
        assertNull(addOn.combined.payCents);
    }

    @Test
    public void conflictingIncrementsNeverFallBackToShownNumbers() {
        OfferSnapshot route = new OfferSnapshot(2500, 8.0, null, null);
        AddOnOffer addOn = AddOnOffer.parse(route,
                Arrays.asList("Add to route", "+$5.00", "+$7.00", "+2 mi", "+3 mi"));
        assertNull(addOn.incremental.payCents);
        assertNull(addOn.combined.payCents);
        assertNull(addOn.incremental.miles);
        assertNull(addOn.combined.miles);
    }

    @Test
    public void missingRouteDoesNotTurnAddOnIntoStandaloneOffer() {
        AddOnOffer addOn = AddOnOffer.parse(null, Arrays.asList("Add to route", "+$5.00", "+2 mi"));
        assertNull(addOn.combined.payCents);
        FilterSettings settings = FilterSettings.of(true, 2000, 100, 0, 0);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluateAddOn(addOn, settings).result);
    }
}

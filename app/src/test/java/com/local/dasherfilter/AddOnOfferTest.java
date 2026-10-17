package com.local.dasherfilter;

import java.util.Arrays;
import java.util.List;
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

    /**
     * Dasher's add-on card as the owner's 16 October 2026 report kept it ("was +1 didnt read it"): no add-on phrase,
     * the added pay alone and a route line of the stops and minutes it adds. It is read as an add-on: $9.40, one stop
     * and 7 minutes more on the route under way. The miles in brackets are not marked as added, so they stay unread.
     */
    private static final List<String> CARD = Arrays.asList("+$9.40", "incl. tips", "+1 stop (1.9 mi) • +7 min",
            "Guaranteed earnings for completing the offer.");
    /** The offer the owner had accepted 3 minutes before it: $10.65 for 1.3 mi and 13 min, 2 stops. */
    private static final OfferSnapshot UNDER_WAY = new OfferSnapshot(1065, 1.3, 13, 2);

    @Test
    public void dashersAddOnCardWithNoPhraseIsReadAsAnAddOn() {
        assertTrue(AddOnOffer.isLikely(CARD));
        List<String> drawn = Arrays.asList("+$9.40", "Decline", "incl. tips", "+1 stop (1.9 mi) • +7 min",
                "Guaranteed earnings for completing the offer.", "Accept", "0:40");
        assertTrue("with its controls and countdown", AddOnOffer.isLikely(drawn));
        for (List<String> labels : Arrays.asList(CARD, drawn)) {
            AddOnOffer addOn = AddOnOffer.parse(UNDER_WAY, labels);
            assertEquals(Integer.valueOf(940), addOn.incremental.payCents);
            assertEquals(Integer.valueOf(1), addOn.incremental.stops);
            assertEquals(Integer.valueOf(7), addOn.incremental.minutes);
            assertNull("not marked as added", addOn.incremental.miles);
            assertEquals(Integer.valueOf(2005), addOn.combined.payCents);
            assertEquals(Integer.valueOf(3), addOn.combined.stops);
            assertEquals(Integer.valueOf(20), addOn.combined.minutes);
            assertNull(addOn.combined.miles);
        }
    }

    @Test
    public void withAnyOtherAmountOrNoAddedStopsItIsNotThatCard() {
        // The owner's "+$3" offer of the same dash: a bonus beside its total, standalone.
        assertFalse(AddOnOffer.isLikely(Arrays.asList("+$3", "Decline", "$9.90", "incl. tips",
                "2 stops (2.9 mi) • 17 min", "Guaranteed earnings for completing the offer.", "Accept", "0:13")));
        // A second amount: the "+$" may be a bonus on it.
        assertFalse(AddOnOffer.isLikely(Arrays.asList("+$1", "$9.40", "incl. tips", "+1 stop (1.9 mi) • +7 min")));
        assertFalse(AddOnOffer.isLikely(Arrays.asList("+$1", "+$9.40", "incl. tips", "+1 stop (1.9 mi) • +7 min")));
        // No stops added, or pay with words.
        assertFalse(AddOnOffer.isLikely(Arrays.asList("+$9.40", "incl. tips", "1 stop (1.9 mi) • 7 min")));
        assertFalse(AddOnOffer.isLikely(Arrays.asList("+$9.40", "incl. tips", "1.9 mi • +7 min")));
        assertFalse(AddOnOffer.isLikely(Arrays.asList("+$2.00 Peak Pay", "+1 stop (1.9 mi) • +7 min")));
    }

    @Test
    public void theCardIsJudgedAsAnAddOnByTheOwnersRules() {
        // The owner's rules then: $4.50 a stop, $16.20 a mile, $0.70 a minute, Autopilot's bar at 50%, scored by area
        // (an add-on is judged strictly whatever the mode).
        FilterSettings rules = FilterSettings.of(true, 450, 1620, 70, 0).withScoreByArea(true).withAutopilot(true, 0)
                .withMinimumScalePercent(50);
        OfferRule.Decision decision = OfferRule.evaluateAddOn(AddOnOffer.parse(UNDER_WAY, CARD), rules);
        // $9.40 covers the $2.45 its 7 minutes ask at 50%; the miles it adds are unread, so it stays the user's.
        assertEquals(OfferRule.Result.REVIEW, decision.result);
        assertEquals(OfferRule.ADD_ON_UNCLEAR, decision.reason);
        assertEquals(245, decision.requiredCents);

        // $2.00 for the same stop and 7 minutes misses those minutes' $2.45 on any route: declined.
        OfferSnapshot richer = new OfferSnapshot(2000, 1.3, 13, 2);
        OfferRule.Decision thin = OfferRule.evaluateAddOn(AddOnOffer.parse(richer,
                Arrays.asList("+$2.00", "incl. tips", "+1 stop (1.9 mi) • +7 min")), rules);
        assertEquals(OfferRule.Result.DECLINE, thin.result);
        assertEquals("50% bar: " + OfferRule.ADD_ON_ECONOMICS, thin.reason);
        assertEquals(245, thin.requiredCents);
    }
}

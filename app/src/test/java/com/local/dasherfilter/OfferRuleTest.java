package com.local.dasherfilter;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class OfferRuleTest {
    @Test
    public void parsesAnOfferWithExplicitValues() {
        OfferSnapshot offer = OfferParser.parse(Arrays.asList(
                "$8.50 Guaranteed", "4.2 mi", "Estimated time 24 min", "3 stops"));
        assertEquals(Integer.valueOf(850), offer.payCents);
        assertEquals(4.2, offer.miles, 0.001);
        assertEquals(Integer.valueOf(24), offer.minutes);
        assertEquals(Integer.valueOf(3), offer.stops);
    }

    @Test
    public void declinesWhenKnownFloorFailsEvenIfTimeIsMissing() {
        FilterSettings settings = new FilterSettings(true, 600, 150, 30, 100);
        OfferSnapshot offer = OfferParser.parse(Arrays.asList("$5.00 Guaranteed", "4 mi"));
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(offer, settings).result);
    }

    @Test
    public void leavesAmbiguousOffersForReview() {
        FilterSettings settings = new FilterSettings(true, 600, 150, 30, 100);
        OfferSnapshot ambiguousPay = OfferParser.parse(Arrays.asList("$8.00", "$9.00", "3 mi"));
        assertNull(ambiguousPay.payCents);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(ambiguousPay, settings).result);

        OfferSnapshot missingTime = OfferParser.parse(Arrays.asList("$10.00 Guaranteed", "3 mi"));
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(missingTime, settings).result);
    }

    @Test
    public void chargesOnlyForStopsBeyondPickupAndDropoff() {
        FilterSettings settings = new FilterSettings(true, 600, 100, 0, 200);
        OfferSnapshot offer = OfferParser.parse(Arrays.asList("$7.00 Guaranteed", "1 mi", "3 stops"));
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(offer, settings).result);
        assertEquals(800, OfferRule.evaluate(offer, settings).requiredCents);
    }
}


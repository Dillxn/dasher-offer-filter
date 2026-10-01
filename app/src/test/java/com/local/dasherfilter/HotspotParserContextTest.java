package com.local.dasherfilter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Synthetic exclusion cases only: none establishes a live source for final-stop or hotspot geometry. */
public final class HotspotParserContextTest {
    @Test public void aWholeHotspotDirectionIsNeitherRouteTravelNorFinalStopEvidence() {
        OfferSnapshot offer = OfferParser.parse(Arrays.asList("$15.00", "2 stops (5 mi) • 20 min",
                "Nearest hotspot: 0.5 mi · estimated 3 min"));
        assertEquals(Double.valueOf(5), offer.miles);
        assertEquals(Integer.valueOf(20), offer.minutes);
        assertEquals(Integer.valueOf(2), offer.stops);
        assertNull(offer.finalStopHotspotMiles);
    }

    @Test public void hotspotHeadingOwnsItsSplitNumberAndUnit() {
        List<String> labels = Arrays.asList("$15.00", "Hotspot", "2.0", "mi");
        List<String> joined = OfferParser.joinMetricSiblings(labels);
        assertTrue(joined.isEmpty());
        OfferSnapshot offer = OfferParser.parse(labels, joined);
        assertEquals(Integer.valueOf(1500), offer.payCents);
        assertNull(offer.miles);
        assertNull(offer.minutes);
        assertNull(offer.finalStopHotspotMiles);
    }

    @Test public void hotspotHeadingOwnsAdjacentBareDistanceAndDurationLabels() {
        OfferSnapshot offer = OfferParser.parse(Arrays.asList("Hot spot", "2.0 mi", "Estimated time: 3 min"));
        assertNull(offer.miles);
        assertNull(offer.minutes);
        assertNull(offer.finalStopHotspotMiles);
        assertTrue(OfferParser.joinMetricSiblings(Arrays.asList("Hotspot", "Total distance", "2", "mi"))
                .isEmpty());
    }

    @Test public void aNestedJoinedCopyCannotRestoreTheExcludedHotspotDistance() {
        // The nested subtree might have contained only "2", "mi"; its parent supplies the hotspot heading.
        OfferSnapshot missing = OfferParser.parse(Arrays.asList("Hotspot", "Total distance", "2", "mi"),
                Arrays.asList("2 mi", "Total 2 mi"));
        assertNull(missing.miles);
        assertNull(missing.finalStopHotspotMiles);

        OfferSnapshot valid = OfferParser.parse(Arrays.asList("Hotspot", "2", "mi",
                "2 stops (5 mi) • 20 min"), Collections.singletonList("2 mi"));
        assertEquals(Double.valueOf(5), valid.miles);
        assertEquals(Integer.valueOf(20), valid.minutes);
        assertNull(valid.finalStopHotspotMiles);
    }

    @Test public void compactOfferEvidenceEndsHotspotContextWithoutBeingDiscarded() {
        OfferSnapshot offer = OfferParser.parse(Arrays.asList("Hotspot", "0.5 mi", "Estimated 3 min",
                "2 stops (5 mi) • 20 min"));
        assertEquals(Double.valueOf(5), offer.miles);
        assertEquals(Integer.valueOf(20), offer.minutes);
        assertEquals(Integer.valueOf(2), offer.stops);
    }

    @Test public void meaningfulOfferHeadingRestoresItsSeparateMetricSiblings() {
        List<String> labels = Arrays.asList("Hotspot", "2", "mi", "Offer details", "Total distance", "5", "mi");
        List<String> joined = OfferParser.joinMetricSiblings(labels);
        assertFalse(joined.contains("2 mi"));
        assertTrue(joined.contains("5 mi"));
        assertTrue(joined.contains("Total 5 mi"));
        assertEquals(Double.valueOf(5), OfferParser.parse(labels, joined).miles);
    }

    @Test public void exclusionPreservesSiblingBoundariesAndUnrelatedRouteMetrics() {
        assertTrue(OfferParser.joinMetricSiblings(Arrays.asList("2", "Hotspot", "mi")).isEmpty());
        OfferSnapshot offer = OfferParser.parse(Arrays.asList("2 stops (5 mi) • 20 min", "Hotspots", "0.5 mi"));
        assertEquals(Double.valueOf(5), offer.miles);
        assertEquals(Integer.valueOf(20), offer.minutes);
    }

    @Test public void addOnHotspotTotalsAndIncrementsAreNotRouteTravel() {
        AddOnOffer addOn = AddOnOffer.parse(new OfferSnapshot(1000, 8.0, 40, 2), Arrays.asList(
                "Add to route", "+$5.00", "Hotspot: total distance 3 mi", "Hotspot: +2 mi",
                "Hotspot: total time 7 min", "Hotspot: +3 min"));
        assertEquals(Integer.valueOf(500), addOn.incremental.payCents);
        assertEquals(Integer.valueOf(1500), addOn.combined.payCents);
        assertNull(addOn.incremental.miles);
        assertNull(addOn.combined.miles);
        assertNull(addOn.incremental.minutes);
        assertNull(addOn.combined.minutes);
        assertNull(addOn.combined.finalStopHotspotMiles);
    }

    @Test public void addOnSplitHotspotContextEndsAtTheActualOfferHeading() {
        AddOnOffer addOn = AddOnOffer.parse(new OfferSnapshot(1000, 8.0, 40, 2), Arrays.asList(
                "Hotspot", "Total distance 3 mi", "+2 mi", "Total time 7 min", "+3 min",
                "Add this order", "+$5.00", "+4 mi", "+10 min", "+2 stops"));
        assertEquals(Double.valueOf(4), addOn.incremental.miles);
        assertEquals(Double.valueOf(12), addOn.combined.miles);
        assertEquals(Integer.valueOf(10), addOn.incremental.minutes);
        assertEquals(Integer.valueOf(50), addOn.combined.minutes);
        assertEquals(Integer.valueOf(4), addOn.combined.stops);
        assertNull(addOn.combined.finalStopHotspotMiles);
    }

    @Test public void hotspotTimeRangesDoNotPoisonSeparateAddOnDuration() {
        AddOnOffer addOn = AddOnOffer.parse(null, Arrays.asList("Hotspot", "Estimated time 2-3 min",
                "Add this order", "+10 min", "Total time 30 min", "Total distance 5 mi"));
        assertEquals(Integer.valueOf(10), addOn.incremental.minutes);
        assertEquals(Integer.valueOf(30), addOn.combined.minutes);
        assertEquals(Double.valueOf(5), addOn.combined.miles);
    }
}

package com.local.dasherfilter;

import org.junit.Test;

import static org.junit.Assert.*;

/** The disclosure uses current shared floors and the selected offer's facts, including unavailable amounts. */
public final class MinimumsDetailsTest {
    private static FilterSettings learned() {
        return new FilterSettings(true, 500, 100, 25, 300, 0, true, 1000,
                new AcceptedBest(900, 15, 1200, 4, 1400, 4),
                new DeclinedFloor(1100, new AcceptedBest(1000, 20, 1200, 5, 1000, 2)), true);
    }

    private static OfferSnapshot offer() {
        return new OfferSnapshot(1600, 5.0, 20, 2);
    }

    @Test public void knownOfferShowsMaximumAcceptedOrDeclinedRequirementsForEachSpoke() {
        String text = MinimumsDetails.describe(learned(), offer());
        assertTrue(text.contains("Payout — Saved $5.00 · Learned $11.01 · Used $11.01"));
        assertTrue(text.contains("Pay/mile — Saved $5.00 · Learned $15.00 · Used $15.00"));
        assertTrue(text.contains("Pay/min — Saved $5.00 · Learned $12.00 · Used $12.00"));
        assertTrue(text.contains("Pay/stop — Saved $6.00 · Learned $10.01 · Used $10.01"));
        assertTrue(text.contains("payout-dollar requirements"));
        assertTrue(text.contains("not per-unit rates"));
        assertTrue(text.contains("Blue is Saved; purple is Learned"));
        assertTrue(text.contains("Stronger spokes can compensate"));
        assertTrue(text.contains("increased learning need not expand the purple outline"));
    }

    @Test public void selectedOfferAmountsChangeRequirementsWithoutInventingUnknownPay() {
        String text = MinimumsDetails.describe(learned(), new OfferSnapshot(null, 2.0, 10, 4));
        assertTrue(text.contains("Pay/mile — Saved $2.00 · Learned $6.00 · Used $6.00"));
        assertTrue(text.contains("Pay/min — Saved $2.50 · Learned $6.00 · Used $6.00"));
        assertTrue(text.contains("Pay/stop — Saved $12.00 · Learned $20.01 · Used $20.01"));
        assertFalse(text.contains("Offer $"));
    }

    @Test public void unknownAmountsAreUnavailableAndDisabledSavedFloorsAreOff() {
        FilterSettings rules = learned().withMinimums(new int[] {0, 100, 0, 300, 0, 75});
        String text = MinimumsDetails.describe(rules, OfferSnapshot.UNKNOWN.withItems(null, true));
        assertTrue(text.contains("Payout — Saved off · Learned $11.01 · Used $11.01"));
        assertTrue(text.contains("Pay/mile — Saved unavailable · Learned unavailable · Used unavailable"));
        assertTrue(text.contains("Pay/min — Saved off · Learned unavailable · Used unavailable"));
        assertTrue(text.contains("Pay/stop — Saved unavailable · Learned unavailable · Used unavailable"));
        assertTrue(text.contains("Pay/item — Saved unavailable · Learned off · Used unavailable"));
        assertTrue(text.contains("Hotspot — distance unavailable"));
        assertTrue(text.contains("actual distance from this offer's final stop"));
    }

    @Test public void turningAdaptiveOffPreservesLearnedValuesButUsesOnlySavedValues() {
        FilterSettings rules = learned().withAdaptive(false).withMinimums(new int[] {500, 0, 25, 300, 0, 0});
        String text = MinimumsDetails.describe(rules, offer());
        assertTrue(text.contains("Payout — Saved $5.00 · Learned $11.01 (not applied) · Used $5.00"));
        assertTrue(text.contains("Pay/mile — Saved off · Learned $15.00 (not applied) · Used off"));
        assertTrue(text.contains("Pay/min — Saved $5.00 · Learned $12.00 (not applied) · Used $5.00"));
        String missing = MinimumsDetails.describe(rules, OfferSnapshot.UNKNOWN);
        assertTrue(missing.contains("Pay/mile — Saved off · Learned unavailable (not applied) · Used off"));
    }

    @Test public void itemRequirementUsesOnlyObservedItemsAndTheGlobalScale() {
        FilterSettings rules = learned().withPerItem(75).withMinimumScalePercent(97);
        String text = MinimumsDetails.describe(rules, offer().withItems(4, true));
        assertTrue(text.contains("Pay/item — Saved $3.00 · Learned off · Used $2.91"));
        assertTrue(text.contains("Only confirmed manual choices train Payout, Pay/mile, Pay/min and Pay/stop"));
        assertTrue(MinimumsDetails.describe(rules, offer())
                .contains("Pay/item — Saved not applicable · Learned off · Used not applicable"));
    }

    @Test public void learnedItemUsesTheActualCountAndTheSameBufferedFloorAsDecisions() {
        AcceptedBest best = AcceptedBest.NONE.raisedBy(offer().withItems(4, true));
        FilterSettings rules = new FilterSettings(true, 0, 0, 0, 0, 0, true, 0, best)
                .withPerItem(75).withMinimumScalePercent(97);
        String text = MinimumsDetails.describe(rules, offer().withItems(2, true));
        assertTrue(text, text.contains("Pay/item — Saved $1.50 · Learned $8.00 · Used $7.76"));
        assertTrue(text, text.contains("Pay/item learns from confirmed manual accepts with an observed total count"));
        assertFalse(text, text.contains("Pay/item is fixed only"));
        String disabled = MinimumsDetails.describe(rules.withAdaptive(false), offer().withItems(2, true));
        assertTrue(disabled, disabled.contains("Pay/item — Saved $1.50 · Learned $8.00 (not applied) · Used ≈$1.46"));
    }

    @Test public void learnedItemNeedsTheCurrentOfferCountEvenWhenTheSavedItemFloorIsOff() {
        AcceptedBest best = AcceptedBest.NONE.raisedBy(offer().withItems(4, true));
        FilterSettings rules = new FilterSettings(true, 0, 0, 0, 0, 0, true, 0, best);
        String unknown = MinimumsDetails.describe(rules, offer().withItems(null, true));
        assertTrue(unknown, unknown.contains("Pay/item — Saved off · Learned unavailable · Used unavailable"));
        String inapplicable = MinimumsDetails.describe(rules, offer());
        assertTrue(inapplicable, inapplicable.contains("Pay/item — Saved off · Learned not applicable · Used not applicable"));
        String disabled = MinimumsDetails.describe(rules.withAdaptive(false), offer().withItems(null, true));
        assertTrue(disabled, disabled.contains("Pay/item — Saved off · Learned unavailable (not applied) · Used off"));
    }

    @Test public void scaleIsSeparateAndFractionalCentDisplayDoesNotRoundBeforeScaling() {
        FilterSettings rules = new FilterSettings(true, 0, 100, 0, 0, 0).withMinimumScalePercent(97);
        String text = MinimumsDetails.describe(rules, new OfferSnapshot(100, 1.0301, null, null));
        assertTrue(text.contains("Pay/mile — Saved ≈$1.04 · Learned off · Used ≈$1.00"));
        assertTrue(text.contains("Scale 97%"));
        assertTrue(text.contains("stored minimums stay unchanged"));
        assertTrue(text.contains("≈ means rounded up to the next cent"));
        assertEquals(100, rules.perMileCents);
        assertTrue(MinimumsDetails.describe(learned().withMinimumScalePercent(97), offer())
                .contains("Payout — Saved $5.00 · Learned $11.01 · Used ≈$10.68"));
    }

    @Test public void strictExplanationAndKnownZeroRouteKeepExistingDifferentAreaPolicy() {
        FilterSettings strict = new FilterSettings(true, 0, 100, 0, 0, 0);
        OfferSnapshot zero = new OfferSnapshot(100, 0.0, null, null);
        String text = MinimumsDetails.describe(strict, zero);
        assertTrue(text.contains("In strict mode, every active Used amount must be met"));
        assertTrue(text.contains("area score is only a reference"));
        assertTrue(text.contains("Pay/mile — Saved $0.00 · Learned off · Used $0.00"));
        assertTrue(MinimumsDetails.describe(strict.withScoreByArea(true), zero)
                .contains("Pay/mile — Saved $0.00 · Learned off · Used unavailable"));
    }

    @Test public void hotspotNeverBecomesDollarsOrUsesRouteMileageAsItsDistance() {
        FilterSettings rules = learned().withHotspotProximity(40).withMinimumScalePercent(125);
        String missing = MinimumsDetails.describe(rules, offer());
        assertTrue(missing.contains("Hotspot — distance unavailable"));
        assertTrue(missing.contains("Saved 0.4 /mi; Used 0.5 /mi reciprocal proximity"));
        String known = MinimumsDetails.describe(rules, offer().withFinalStopHotspotMiles(0.0));
        assertTrue(known.contains("Hotspot — 0 mi from this offer's final stop"));
        assertFalse(known.contains("Hotspot — 5"));
        assertTrue(known.contains("Independent of payout, fixed only"));
    }
}

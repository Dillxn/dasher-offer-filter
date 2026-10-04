package com.local.dasherfilter;

import android.app.Application;
import android.content.Context;
import java.math.BigDecimal;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.*;

/** Accepted total-item rates must survive the same lifecycle as the existing independent learned rates. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class AdaptiveItemsTest {
    private Application app;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE).edit().clear().commit();
        Updater.setEnabled(app, false);
    }

    private static OfferSnapshot items(int pay, Integer count) {
        return new OfferSnapshot(pay, null, null, null).withItems(count, true);
    }

    private FilterSettings reload() { return FilterStore.load(app.createConfigurationContext(
            app.getResources().getConfiguration())); }

    private static FilterSettings onlyLearnedItems(int pay, int count) {
        AcceptedBest best = AcceptedBest.NONE.raisedBy(items(pay, count));
        return new FilterSettings(true, 0, 0, 0, 0, 0, true, 0, best);
    }

    @Test public void confirmedObservedItemRatePersistsDespiteStaleRuleSaveAndUnknownLaterCounts() {
        FilterSettings stale = new FilterSettings(true, 1950, 0, 0, 0, 3).withAdaptive(true);
        FilterStore.save(app, stale);
        assertEquals(FilterStore.AcceptedLesson.RAISED,
                FilterStore.recordAcceptedLesson(app, new OfferSnapshot(1470, 6.8, 33, 2).withItems(2, true)));
        FilterStore.save(app, stale.withMinimumScalePercent(97));
        assertEquals(BigDecimal.valueOf(2940), AreaScore.floors(reload(), items(3000, 4))
                .acceptedCents[AreaScore.ITEM]);
        FilterStore.recordAccepted(app, items(900, null));
        FilterStore.recordAccepted(app, new OfferSnapshot(950, null, null, null));
        assertEquals(BigDecimal.valueOf(2940), AreaScore.floors(reload(), items(3000, 4))
                .acceptedCents[AreaScore.ITEM]);
        assertTrue(reload().describe().contains("$7.35/item"));
    }

    @Test public void independentExactRatioRaisesOnlyForStrictlyHigherRate() {
        AcceptedBest best = AcceptedBest.NONE.raisedBy(items(1001, 3));
        FilterSettings original = new FilterSettings(true, 0, 0, 0, 0, 0, true, 0, best);
        // $10.01 / 3 has a repeating fractional-cent rate; the four-item ask is $13.35.
        assertEquals(BigDecimal.valueOf(1335), AreaScore.floors(original, items(2000, 4))
                .acceptedCents[AreaScore.ITEM]);
        AcceptedBest tied = best.raisedBy(items(2002, 6));
        assertEquals(best.summary(), tied.summary());
        AcceptedBest worse = tied.raisedBy(items(999, 3));
        assertEquals(best.summary(), worse.summary());
        AcceptedBest better = worse.raisedBy(items(1002, 3));
        FilterSettings raised = new FilterSettings(true, 0, 0, 0, 0, 0, true, 0, better);
        assertEquals(BigDecimal.valueOf(1336), AreaScore.floors(raised, items(2000, 4))
                .acceptedCents[AreaScore.ITEM]);
    }

    @Test public void learnedItemBoundarySharesStrictAndAreaBufferWithoutChangingSavedKnob() {
        for (boolean area : new boolean[] {false, true}) {
            FilterSettings rules = onlyLearnedItems(1001, 3).withScoreByArea(area)
                    .withMinimumScalePercent(97).withPerItem(100);
            // Existing accepted-rate policy rounds the exact match to $13.35, then scales once: $12.95.
            OfferRule.Decision before = OfferRule.evaluate(items(1294, 4), rules);
            OfferRule.Decision at = OfferRule.evaluate(items(1295, 4), rules);
            assertEquals(OfferRule.Result.DECLINE, before.result);
            assertEquals(OfferRule.Result.KEEP, at.result);
            assertEquals(1295, at.requiredCents);
            assertEquals(100, rules.perItemCents);
            assertEquals(97, at.scorePercent);
        }
    }

    @Test public void missingDeclaredItemsReviewButInapplicableAndSwitchedOffStayUnchanged() {
        FilterSettings learned = onlyLearnedItems(1000, 2);
        for (boolean area : new boolean[] {false, true}) {
            FilterSettings rules = learned.withScoreByArea(area);
            assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(items(5000, null), rules).result);
            OfferSnapshot ordinary = new OfferSnapshot(1000, 5.0, 20, 2);
            assertFalse(AreaScore.floors(rules, ordinary).active[AreaScore.ITEM]);
            assertNull(AreaScore.floors(rules, ordinary).acceptedCents[AreaScore.ITEM]);
            assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(ordinary, rules).result);
            assertEquals(OfferRule.Result.KEEP,
                    OfferRule.evaluate(items(1, 200), rules.withAdaptive(false)).result);
        }
        FilterSettings fixedFail = learned.withMinimums(new int[] {6000, 0, 0, 0});
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(items(5000, null), fixedFail).result);
        assertEquals(OfferRule.Result.REVIEW,
                OfferRule.evaluate(items(5000, null), fixedFail.withScoreByArea(true)).result);
    }

    @Test public void learningOffRetainsItemBestAndResetPreservesKnobBufferAndStops() {
        FilterSettings original = new FilterSettings(true, 1950, 0, 0, 0, 3)
                .withAdaptive(true).withPerItem(100).withMinimumScalePercent(97);
        FilterStore.save(app, original);
        FilterStore.recordAccepted(app, items(1470, 2));
        for (FilterSettings off : new FilterSettings[] {reload().withAdaptive(false), reload().withEnabled(false)}) {
            FilterStore.save(app, off);
            assertEquals(FilterStore.AcceptedLesson.SWITCHES_OFF,
                    FilterStore.recordAcceptedLesson(app, items(9000, 1)));
            assertEquals(BigDecimal.valueOf(2940), AreaScore.floors(reload(), items(3000, 4))
                    .acceptedCents[AreaScore.ITEM]);
        }
        FilterStore.save(app, reload().withEnabled(true).withAdaptive(true));
        FilterStore.resetAccepted(app);
        assertTrue(reload().best.isEmpty());
        assertNull(AreaScore.floors(reload(), items(3000, 4)).acceptedCents[AreaScore.ITEM]);
        assertArrayEquals(original.minimums(), reload().minimums());
        assertEquals(97, reload().minimumScalePercent);
        assertEquals(3, reload().maxStops);
    }

    @Test public void adoptCopiesExactAcceptedItemRateUpwardAndResetKeepsIt() {
        FilterStore.save(app, onlyLearnedItems(1001, 3).withPerItem(100).withMinimumScalePercent(97));
        // FilterStore intentionally owns learned storage, so confirmed manual lessons use its entry point.
        FilterStore.recordAccepted(app, items(1001, 3));
        FilterStore.save(app, reload().adoptAdaptive());
        assertEquals(334, reload().perItemCents);
        FilterStore.resetAccepted(app);
        assertEquals(334, reload().perItemCents);
        assertEquals(97, reload().minimumScalePercent);
        assertEquals(1296, OfferRule.evaluate(items(1400, 4), reload()).requiredCents);
        assertEquals(500, onlyLearnedItems(1001, 3).withPerItem(500).adoptAdaptive().perItemCents);
    }

    @Test public void itemEvidenceAloneCannotTurnMisreadOffersIntoNewBestAndCannotUsePayBound() {
        for (OfferSnapshot bad : Arrays.asList(new OfferSnapshot(1000, 0.1, 20, 2).withItems(1, true),
                new OfferSnapshot(1000, 3.0, 1, 2).withItems(1, true),
                new OfferSnapshot(1000, 3.0, 20, 1).withItems(1, true),
                new OfferSnapshot(null, 3.0, 20, 2, 1000).withItems(1, true))) {
            assertTrue(AcceptedBest.NONE.raisedBy(bad).isEmpty());
        }
        assertTrue(AcceptedBest.NONE.raisedBy(items(1000, 0)).isEmpty());
        assertFalse(AcceptedBest.NONE.raisedBy(items(1000, 1)).isEmpty());
    }

    @Test public void itemOnlyNewBestReportsRaisedStoredOrUnchangedTruthfully() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0)
                .withAdaptive(true).withPerItem(1000));
        FilterStore.recordAccepted(app, items(1000, 5));
        // Lower payout, higher item rate: still below a stronger saved item knob.
        assertEquals(FilterStore.AcceptedLesson.RECORDED, FilterStore.recordAcceptedLesson(app, items(900, 2)));
        assertEquals(FilterStore.AcceptedLesson.NOTHING_NEW, FilterStore.recordAcceptedLesson(app, items(900, 2)));
        FilterStore.save(app, reload().withPerItem(0));
        assertEquals(FilterStore.AcceptedLesson.RAISED, FilterStore.recordAcceptedLesson(app, items(800, 1)));
        assertEquals(1000, reload().lastAcceptedCents);
    }

    @Test public void legacyAndInvalidItemRecordsAreAbsentWithoutChangingExistingLearnedAxes() {
        app.getSharedPreferences("offer_filter", 0).edit()
                .putInt("best_stop_pay", 940).putInt("best_stops", 2)
                .putInt("best_item_pay", 1470).putInt("best_items", -2).commit();
        assertFalse(reload().best.hasPerItem());
        assertEquals(470, reload().best.forStops(1));
        FilterStore.save(app, new FilterSettings(true, 0, 0, 0, 0, 0).withAdaptive(true));
        FilterStore.recordAccepted(app, items(1470, 2));
        assertTrue(reload().best.hasPerItem());
        assertEquals(735, reload().best.forItems(1));
        assertEquals(470, reload().best.forStops(1));
    }

    @Test public void addOnAndUnreadPayCeilingIgnoreLearnedItemCost() {
        FilterSettings learned = onlyLearnedItems(1000, 1).withPerItem(100);
        AddOnOffer addOn = AddOnOffer.parse(items(2000, 10),
                Arrays.asList("Add to route", "+$5.00", "+5 items"));
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluateAddOn(addOn, learned).result);
        assertEquals(500, OfferRule.evaluateAddOn(addOn, learned).requiredCents);
        for (boolean area : new boolean[] {false, true}) {
            assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(
                    new OfferSnapshot(null, null, null, null, 1000).withItems(5, true),
                    learned.withScoreByArea(area)).result);
        }
    }

    @Test public void areaCompensationStillDiffersFromStrictWithLearnedItemAndMaxStopsStillHard() {
        FilterSettings learned = onlyLearnedItems(1000, 5).withMinimums(new int[] {1000, 0, 0, 0});
        OfferSnapshot offer = new OfferSnapshot(1500, null, null, 2).withItems(10, true);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(offer, learned).result);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(offer, learned.withScoreByArea(true)).result);
        assertEquals(1415, OfferRule.evaluate(offer, learned.withScoreByArea(true)).requiredCents);
        assertEquals(OfferRule.Result.DECLINE,
                OfferRule.evaluate(offer, learned.withScoreByArea(true).withMaxStops(1)).result);
    }
}

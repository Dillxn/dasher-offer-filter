package com.local.dasherfilter;

import android.app.Application;
import android.content.Context;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.*;

/** Learned offers survive ordinary rule edits; decisions, adoption and the common buffer use them consistently. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class AdaptiveMinimumLifecycleTest {
    private Application app;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE).edit().clear().commit();
        Updater.setEnabled(app, false);
    }

    @Test public void learnedPayoutPersistsAndBufferAppliesExactlyOnceInBothModes() {
        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0).withAdaptive(true));
        assertTrue(FilterStore.recordAccepted(app, new OfferSnapshot(1420, null, null, null)));
        FilterSettings learned = reload();
        assertEquals(1420, learned.lastAcceptedCents);
        assertTrue("unread routes do not create rate records", learned.best.isEmpty());
        for (boolean area : new boolean[] {false, true}) {
            FilterStore.save(app, learned.withScoreByArea(area).withMinimumScalePercent(97));
            FilterSettings rules = reload();
            // 97% of the unchanged $14.21 learned floor is $13.7837: the first passing cent is $13.79.
            assertBoundary(rules, 1378, 1379, null, null, null, null, false);
            assertEquals(1420, rules.lastAcceptedCents);
            assertEquals(1000, rules.flatCents);
            FilterStore.save(app, rules.withMinimumScalePercent(100));
            assertBoundary(reload(), 1420, 1421, null, null, null, null, false);
        }
    }

    @Test public void independentAcceptedBestsSurviveStaleRuleCopiesAndFreshContexts() {
        FilterSettings savedBeforeLearning = new FilterSettings(true, 700, 100, 20, 100, 3)
                .withAdaptive(true).withPerItem(50).withMinimumScalePercent(97);
        FilterStore.save(app, savedBeforeLearning);
        assertTrue(FilterStore.recordAccepted(app, new OfferSnapshot(2000, 10.0, 40, 4)));
        assertTrue(FilterStore.recordAccepted(app, new OfferSnapshot(1500, 3.0, 30, 2)));
        assertTrue(FilterStore.recordAccepted(app, new OfferSnapshot(1800, 9.0, 18, 3)));
        // A UI copy taken before the acceptances must not overwrite their independently owned records.
        FilterStore.save(app, savedBeforeLearning.withScoreByArea(true).withPerItem(75));
        FilterSettings learned = reload();
        assertEquals(2000, learned.lastAcceptedCents);
        assertEquals(1500, learned.best.milePay);
        assertEquals(3.0, learned.best.miles, 0);
        assertEquals(1800, learned.best.minutePay);
        assertEquals(18, learned.best.minutes);
        assertEquals(1500, learned.best.stopPay);
        assertEquals(2, learned.best.stops);
        assertEquals(75, learned.perItemCents);
        assertEquals(97, learned.minimumScalePercent);
        OfferSnapshot next = new OfferSnapshot(1941, 4.0, 20, 2).withItems(10, true);
        assertEquals(1941, OfferRule.evaluate(next, learned.withScoreByArea(false)).requiredCents);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(next, learned.withScoreByArea(false)).result);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(
                new OfferSnapshot(1940, 4.0, 20, 2).withItems(10, true),
                learned.withScoreByArea(false)).result);
    }

    @Test public void pausingOrDisablingAdaptiveNeitherLearnsNorForgetsAndResetKeepsSavedRules() {
        FilterSettings fixed = new FilterSettings(true, 700, 0, 0, 0, 3)
                .withAdaptive(true).withPerItem(100).withMinimumScalePercent(97);
        FilterStore.save(app, fixed);
        FilterStore.recordAccepted(app, new OfferSnapshot(2000, 8.0, 40, 2));
        assertEquals(FilterStore.DeclineLesson.TAUGHT,
                FilterStore.learnFromDecline(app, new OfferSnapshot(2500, 8.0, 40, 2)));
        FilterSettings learned = reload();
        String accepted = learned.best.summary(), declined = learned.declined.summary();
        for (FilterSettings off : new FilterSettings[] {learned.withAdaptive(false), learned.withEnabled(false)}) {
            FilterStore.save(app, off);
            assertFalse(FilterStore.recordAccepted(app, new OfferSnapshot(9000, 3.0, 10, 2)));
            assertEquals(FilterStore.DeclineLesson.SWITCHES_OFF,
                    FilterStore.learnFromDecline(app, new OfferSnapshot(9500, 3.0, 10, 2)));
            assertEquals(2000, reload().lastAcceptedCents);
            assertEquals(accepted, reload().best.summary());
            assertEquals(declined, reload().declined.summary());
        }
        FilterStore.save(app, reload().withEnabled(true).withAdaptive(true));
        assertEquals(OfferRule.Result.DECLINE,
                OfferRule.evaluate(new OfferSnapshot(2000, 8.0, 40, 2).withItems(10, true), reload()).result);
        FilterStore.resetAccepted(app);
        FilterSettings reset = reload();
        assertArrayEquals(fixed.minimums(), reset.minimums());
        assertEquals(97, reset.minimumScalePercent);
        assertEquals(3, reset.maxStops);
        assertTrue(reset.enabled && reset.risingOffers);
        assertEquals(0, reset.lastAcceptedCents);
        assertTrue(reset.best.isEmpty());
        assertTrue(reset.declined.isEmpty());
        assertTrue(FilterStore.learningTimes(app)[2] > 0);
        assertEquals(OfferRule.Result.KEEP,
                OfferRule.evaluate(new OfferSnapshot(2000, 8.0, 40, 2).withItems(10, true), reset).result);
    }

    @Test public void learnedAndItemFloorsShareScaleButKeepStrictAndCompensatingMeanings() {
        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0)
                .withAdaptive(true).withPerItem(300).withMinimumScalePercent(97));
        FilterStore.recordAccepted(app, new OfferSnapshot(2000, null, null, null));
        assertEquals(FilterStore.DeclineLesson.TAUGHT,
                FilterStore.learnFromDecline(app, new OfferSnapshot(2500, null, null, null)));
        FilterSettings learned = reload();
        assertEquals(2500, learned.declined.payCents);
        // Strict requires 97% of $30 for ten items; the learned pay baseline is $25.01.
        assertBoundary(learned, 2909, 2910, null, null, null, 10, true);
        // Two area spokes use geometric mean; an independent integer inequality locates the first passing cent.
        long square = 2501L * 3000 * 97 * 97;
        int areaCents = 1;
        while ((long) areaCents * areaCents * 10000 < square) areaCents++;
        assertTrue("the stronger pay spoke may compensate weaker pay/item", areaCents < 2910);
        assertBoundary(learned.withScoreByArea(true), areaCents - 1, areaCents,
                null, null, null, 10, true);
        for (boolean area : new boolean[] {false, true}) {
            FilterSettings rules = learned.withScoreByArea(area);
            assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(
                    new OfferSnapshot(4000, null, null, null).withItems(null, true), rules).result);
            assertBoundary(rules, 2425, 2426, null, null, null, null, false);
        }
        assertEquals("acceptance and manual-decline learning add no learned item rate", 300, learned.perItemCents);
    }

    @Test public void adoptThenResetRetainsTheLearnedProtectionInBothModesAndLeavesItemRuleAlone() {
        FilterStore.save(app, new FilterSettings(true, 700, 150, 30, 100, 3)
                .withAdaptive(true).withPerItem(100).withMinimumScalePercent(97));
        FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2).withItems(10, true));
        FilterStore.learnFromDecline(app, new OfferSnapshot(1500, 6.0, 24, 2).withItems(10, true));
        FilterSettings learned = reload();
        FilterStore.save(app, learned.adoptAdaptive());
        FilterStore.resetAccepted(app);
        FilterSettings adopted = reload();
        assertArrayEquals(new int[] {1501, 237, 60, 710, 0, 100}, adopted.minimums());
        assertTrue(adopted.best.isEmpty() && adopted.declined.isEmpty());
        assertEquals(0, adopted.lastAcceptedCents);
        assertEquals(97, adopted.minimumScalePercent);
        for (boolean area : new boolean[] {false, true}) {
            for (int cents : new int[] {1000, 1450, 1500, 1800, 2200}) {
                for (double miles : new double[] {2.0, 6.0, 9.1}) {
                    OfferSnapshot offer = new OfferSnapshot(cents, miles, 24, 2).withItems(10, true);
                    OfferRule.Decision before = OfferRule.evaluate(offer, learned.withScoreByArea(area));
                    OfferRule.Decision after = OfferRule.evaluate(offer, adopted.withScoreByArea(area));
                    if (before.result == OfferRule.Result.DECLINE) {
                        assertEquals("adoption followed by Reset must not weaken " + offer.summary(),
                                OfferRule.Result.DECLINE, after.result);
                    }
                }
            }
        }
    }

    @Test public void closestManualDeclineLessonRemainsOnExistingLearnedAxesWhenItemsArePresent() {
        FilterStore.save(app, new FilterSettings(true, 700, 200, 0, 0, 0)
                .withAdaptive(true).withPerItem(100));
        OfferSnapshot offer = new OfferSnapshot(1000, 4.0, 20, 2).withItems(10, true);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(offer, reload()).result);
        assertEquals(FilterStore.DeclineLesson.TAUGHT, FilterStore.learnFromDecline(app, offer));
        FilterSettings learned = reload();
        assertEquals(1000, learned.declined.rates.milePay);
        assertEquals(4.0, learned.declined.rates.miles, 0);
        assertEquals(0, learned.declined.payCents);
        assertEquals(100, learned.perItemCents);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(offer, learned).result);
        assertEquals(1001, OfferRule.evaluate(offer, learned).requiredCents);
        assertEquals(FilterStore.DeclineLesson.NOTHING_NEW, FilterStore.learnFromDecline(app, offer));
    }

    @Test public void strictBufferedDeclineDoesNotClaimAnEffectiveFloorRoseWhenFixedFloorStillWins() {
        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0)
                .withAdaptive(true).withMinimumScalePercent(97));
        OfferSnapshot offer = new OfferSnapshot(980, 4.0, 20, 2);
        OfferRule.Decision before = OfferRule.evaluate(offer, reload());
        assertEquals(OfferRule.Result.KEEP, before.result);
        assertEquals(970, before.requiredCents);
        assertEquals(FilterStore.DeclineLesson.NOTHING_NEW, FilterStore.learnFromDecline(app, offer));
        FilterSettings learned = reload();
        assertEquals("the user's chosen learning record is still kept for later rule changes", 980,
                learned.declined.payCents);
        OfferRule.Decision after = OfferRule.evaluate(offer, learned);
        assertEquals(before.result, after.result);
        assertEquals(before.requiredCents, after.requiredCents);
        assertEquals(before.scorePercent, after.scorePercent);
        // Once the fixed floor is lowered, the stored lesson still supplies its original $9.81 baseline.
        FilterStore.save(app, learned.withMinimums(new int[] {500, 0, 0, 0}));
        assertBoundary(reload(), 951, 952, 4.0, 20, 2, null, false);
    }

    @Test public void addOnUsesScaledFixedItemFloorWithoutBorrowingStandaloneLearningOrAreaCompensation() {
        FilterStore.save(app, new FilterSettings(true, 500, 0, 0, 0, 0)
                .withAdaptive(true).withPerItem(100).withMinimumScalePercent(97));
        FilterStore.recordAccepted(app, new OfferSnapshot(6000, 6.0, 30, 2).withItems(10, true));
        OfferSnapshot active = new OfferSnapshot(1000, 3.0, 15, 2).withItems(10, true);
        AddOnOffer pass = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$3.88", "+4 items"));
        AddOnOffer fail = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$3.87", "+4 items"));
        AddOnOffer unread = AddOnOffer.parse(active, Arrays.asList("Add to route", "+$3.88", "Shopping"));
        for (boolean area : new boolean[] {false, true}) {
            FilterSettings rules = reload().withScoreByArea(area);
            assertEquals(OfferRule.Result.KEEP, OfferRule.evaluateAddOn(pass, rules).result);
            assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluateAddOn(fail, rules).result);
            assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluateAddOn(unread, rules).result);
        }
        assertEquals("judging an add-on neither replaces nor resets accepted history", 6000,
                reload().lastAcceptedCents);
    }

    @Test public void acceptedLessonSeparatesNewRecordsFromActualResolvedMinimumChanges() {
        FilterStore.save(app, new FilterSettings(true, 3000, 2000, 1000, 3000, 3).withAdaptive(true));
        OfferSnapshot first = new OfferSnapshot(1500, 4.0, 20, 2);
        assertEquals(FilterStore.AcceptedLesson.RECORDED, FilterStore.recordAcceptedLesson(app, first));
        assertEquals("new best is still kept below stronger fixed floors", 1500, reload().lastAcceptedCents);
        assertEquals(FilterStore.AcceptedLesson.NOTHING_NEW, FilterStore.recordAcceptedLesson(app, first));
        assertEquals(FilterStore.AcceptedLesson.NOTHING_NEW,
                FilterStore.recordAcceptedLesson(app, new OfferSnapshot(1400, 4.0, 20, 2)));
        assertEquals(FilterStore.AcceptedLesson.RAISED,
                FilterStore.recordAcceptedLesson(app, new OfferSnapshot(4000, 4.0, 20, 2)));
        FilterStore.save(app, reload().withAdaptive(false));
        assertEquals(FilterStore.AcceptedLesson.SWITCHES_OFF,
                FilterStore.recordAcceptedLesson(app, new OfferSnapshot(9000, 4.0, 20, 2)));
        assertEquals(FilterStore.AcceptedLesson.PAY_UNKNOWN,
                FilterStore.recordAcceptedLesson(app, new OfferSnapshot(null, 4.0, 20, 2)));
    }

    @Test public void anImprovedRateHiddenByThisOffersCentRoundingIsStillSavedHonestly() {
        FilterStore.save(app, new FilterSettings(true, 0, 0, 0, 0, 0).withAdaptive(true));
        FilterStore.recordAccepted(app, new OfferSnapshot(1000, 3.0, null, null));
        assertEquals(FilterStore.AcceptedLesson.RECORDED,
                FilterStore.recordAcceptedLesson(app, new OfferSnapshot(1000, 2.999, null, null)));
        assertEquals(2.999, reload().best.miles, 0);
        assertTrue(FilterStore.AcceptedLesson.RECORDED.reason.contains("this offer’s current dollar requirements"));
        assertFalse(FilterStore.AcceptedLesson.RECORDED.reason.contains("stronger"));
    }

    private FilterSettings reload() {
        Context fresh = app.createConfigurationContext(app.getResources().getConfiguration());
        return FilterStore.load(fresh);
    }

    private static void assertBoundary(FilterSettings rules, int below, int passing,
                                       Double miles, Integer minutes, Integer stops, Integer items,
                                       boolean itemEvidence) {
        OfferSnapshot fail = new OfferSnapshot(below, miles, minutes, stops).withItems(items, itemEvidence);
        OfferSnapshot pass = new OfferSnapshot(passing, miles, minutes, stops).withItems(items, itemEvidence);
        OfferRule.Decision rejected = OfferRule.evaluate(fail, rules);
        OfferRule.Decision accepted = OfferRule.evaluate(pass, rules);
        assertEquals(rules.describe(), OfferRule.Result.DECLINE, rejected.result);
        assertEquals(rules.describe(), OfferRule.Result.KEEP, accepted.result);
        assertEquals(passing, rejected.requiredCents);
        assertEquals(passing, accepted.requiredCents);
    }
}

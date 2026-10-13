package com.local.dasherfilter;

import android.content.Context;
import java.util.Arrays;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The store skip list (0.6.10): kept like the rules, declining any offer with a store on it, whatever it pays. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SkipListTest {
    private Context context;

    @Before public void fresh() {
        context = RuntimeEnvironment.getApplication();
        SkipList.forgetForTests();
        context.getSharedPreferences("skip_list", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void forget() {
        SkipList.forgetForTests();
    }

    @Test public void addedOnceAndRemovedHoweverItIsSpelled() {
        SkipList.add(context, "DiBella's Subs");
        SkipList.add(context, "dibella's  subs");
        assertEquals(Collections.singletonList("DiBella's Subs"), SkipList.stores(context));
        assertTrue(SkipList.contains(context, "DIBELLA'S SUBS"));
        SkipList.forgetForTests();
        assertEquals("kept on the phone", Collections.singletonList("DiBella's Subs"), SkipList.stores(context));
        SkipList.remove(context, "DiBella's subs");
        assertTrue(SkipList.stores(context).isEmpty());
    }

    @Test public void eitherOfTwoStoresOnTheListSkipsTheOffer() {
        SkipList.add(context, "DashMart");
        assertEquals("DashMart", SkipList.skipped(context, Arrays.asList("Kroger Express", "DashMart")));
        assertNull(SkipList.skipped(context, Collections.singletonList("Kroger Express")));
        assertNull("an unread store is judged by the rules", SkipList.skipped(context, Collections.emptyList()));
    }

    @Test public void clearHistoryLeavesIt() {
        SkipList.add(context, "Burger King");
        DecisionLog.clear(context);
        SkipList.forgetForTests();
        assertTrue(SkipList.contains(context, "Burger King"));
    }

    @Test public void aSkippedOfferIsDeclinedWhateverItPaysAndNeverBelowMinimums() {
        FilterSettings rules = FilterSettings.of(true, 400, 100, 25, 0).withAutopilot(true, 70)
                .withMinimumScalePercent(60);
        OfferSnapshot rich = new OfferSnapshot(2500, 4.0, 15, 2);
        OfferRule.Decision passes = OfferRule.evaluate(rich, rules);
        assertEquals(OfferRule.Result.KEEP, passes.result);
        OfferRule.Decision skipped = OfferRule.skipped(passes);
        assertEquals(OfferRule.Result.DECLINE, skipped.result);
        assertEquals(OfferRule.SKIP_LIST, skipped.reason);
        assertFalse(skipped.belowMinimums);
        assertEquals(passes.scorePercent, skipped.scorePercent);
        assertEquals(60, skipped.minimumScalePercent);
        assertEquals("A store on your skip list", MainActivity.plainReason(OfferRule.SKIP_LIST));
    }

    @Test public void theTicketKnowsTheStoresOfARecentOfferOnly() {
        OfferSnapshot facts = new OfferSnapshot(1160, 6.5, 23, 4);
        SkipList.remember(facts, Arrays.asList("Kroger Express", "DashMart"));
        assertEquals(Arrays.asList("Kroger Express", "DashMart"), SkipList.storesOf(facts));
        assertTrue(SkipList.storesOf(new OfferSnapshot(1161, 6.5, 23, 4)).isEmpty());
    }
}

package com.local.dasherfilter;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What is left in FilterStore of the minimums' growth, retired in 0.6.21 (nothing grows them from then on): the Undo
 * of a growth an earlier version made, which puts the minimums back only while they are still the grown ones and counts
 * as the user's change; "minimums since", moved by every change of a money minimum and never by the bar; a change saved
 * from a copy loaded before another write of the minimums, which never puts the old ones back; and "Let my minimums
 * grow", whose stored choice is removed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36})
public class FilterStoreGrowthTest {
    private static final long DAY = 24 * 60 * 60_000L;
    private Application app;
    private long clock;

    @Before public void setUp() {
        app = RuntimeEnvironment.getApplication();
        prefs().edit().clear().commit();
        clock = 1_791_302_400_000L;
        FilterStore.wallClock = () -> clock;
    }

    @After public void tearDown() {
        FilterStore.wallClock = System::currentTimeMillis;
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE);
    }

    /** Typical minimums and 3 max stops, auto-decline on, Autopilot on at {@code bar}, saved at the clock's time. */
    private FilterSettings typicalAt(int bar) {
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 3));
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        if (bar != FilterSettings.BAR_AT_MINIMUMS) assertTrue(FilterStore.commitAutopilotBar(app, 100, bar));
        return FilterStore.load(app);
    }

    /**
     * A growth of 8 points as 0.6.20 wrote it at {@code at}: the typical minimums grown to $4.32, $1.08 a mile and
     * $16.20 an hour, in effect since then, and Autopilot's bar from {@code barBefore} to {@code barAfter}.
     */
    private Growth.Grown grewAt(long at, int barBefore, int barAfter) {
        FilterSettings before = FilterStore.load(app);
        long now = clock;
        clock = at;
        FilterStore.save(app, before, before.withMinimums(432, 108, 27));
        clock = now;
        assertTrue(FilterStore.commitAutopilotBar(app, barBefore, barAfter));
        return new Growth.Grown(8, before.flatCents, before.perMileCents, before.perMinuteCents, barBefore, 432, 108,
                27, barAfter);
    }

    // ---- Minimums since ----

    @Test public void theMinimumsTakeEffectWhenAMoneyMinimumChangesAndNeverWhenTheBarMoves() {
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 0));
        assertEquals(clock, FilterStore.minimumsSince(app));
        long set = clock;
        clock += 60_000;
        FilterStore.save(app, FilterSettings.of(false, 400, 100, 25, 3));
        assertEquals("pausing or max stops is no change of minimums", set, FilterStore.minimumsSince(app));
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TIER);
        assertTrue(FilterStore.commitAutopilotBar(app, 100, 118));
        FilterStore.setAutopilot(app, false, FilterSettings.GOAL_TIER);
        assertEquals("nor is Autopilot or its bar", set, FilterStore.minimumsSince(app));
        clock += 60_000;
        FilterStore.save(app, FilterSettings.of(false, 400, 100, 26, 3));
        assertEquals("a minimum changed", clock, FilterStore.minimumsSince(app));
        clock += 60_000;
        FilterStore.save(app, FilterSettings.of(false, 0, 100, 26, 3));
        assertEquals("turned off too", clock, FilterStore.minimumsSince(app));
    }

    @Test public void anInstallFromBeforeGetsItsMinimumsSinceAtItsFirstLoadAndTheMigrationSetsIt() {
        // 0.5.0: rules model 2, minimums, no "minimums since".
        prefs().edit().putInt("rules_model", 2).putBoolean("enabled", true).putInt("flat", 400).putInt("mile", 100)
                .putInt("minute", 25).putBoolean("autopilot_on", true).putInt("autopilot_goal", 70)
                .putInt("autopilot_bar_percent", 112).commit();
        assertFalse(prefs().contains("minimums_since"));
        FilterSettings rules = FilterStore.load(app);
        // Rule C's step and the pickup's (0.6.14) each restart it, the second a millisecond after the first.
        assertEquals("its older lines say nothing of which minimums judged them", clock + 1,
                prefs().getLong("minimums_since", 0));
        assertArrayEquals(new int[] {400, 100, 25, 0, 0, 0}, rules.minimums());
        clock += DAY;
        FilterStore.load(app);
        assertEquals("once", clock - DAY + 1, FilterStore.minimumsSince(app));

        // 0.4.x: the 0.5.0 migration builds its scale into the minimums, which take effect then.
        prefs().edit().clear().putBoolean("enabled", true).putInt("flat", 1300).putInt("mile", 385)
                .putInt("minute", 41).putInt("minimum_scale_percent", 90).commit();
        clock += DAY;
        assertArrayEquals(new int[] {1170, 347, 37, 0, 0, 0}, FilterStore.load(app).minimums());
        // Then the pickup's step (0.6.14) restarts it a millisecond later.
        assertEquals(clock + 1, FilterStore.minimumsSince(app));
    }

    // ---- Undo ----

    @Test public void undoPutsTheMinimumsBackOnlyWhileTheyAreStillTheGrownOnes() {
        typicalAt(112);
        long grewAt = clock + 3 * DAY;
        Growth.Grown grown = grewAt(grewAt, 112, 104);
        long undoneAt = grewAt + 60_000;
        assertTrue(FilterStore.undoGrowth(app, grown, grewAt, undoneAt));
        FilterSettings undone = FilterStore.load(app);
        assertArrayEquals(new int[] {400, 100, 25, 0, 0, 0}, undone.minimums());
        assertEquals("the user's change: the minimums take effect now", undoneAt, FilterStore.minimumsSince(app));
        assertEquals("the bar is Autopilot's to move", 104, undone.minimumScalePercent);
        assertEquals(3, undone.maxStops);
        assertFalse("once", FilterStore.undoGrowth(app, grown, grewAt, undoneAt + 1));

        // Grown again, then a minimum changed by hand: nothing to undo.
        long nextAt = undoneAt + 3 * DAY;
        Growth.Grown next = grewAt(nextAt, 104, 112);
        clock = nextAt + 60_000;
        FilterStore.save(app, FilterStore.load(app).withMinimums(500, 108, 27));
        assertFalse(FilterStore.undoGrowth(app, next, nextAt, clock + 1));
        assertArrayEquals(new int[] {500, 108, 27, 0, 0, 0}, FilterStore.load(app).minimums());
        // Changed back to exactly the grown ones: still not the grown ones in effect since the growth.
        FilterStore.save(app, FilterStore.load(app).withMinimums(432, 108, 27));
        assertFalse(FilterStore.undoGrowth(app, next, nextAt, clock + 2));
        assertArrayEquals(new int[] {432, 108, 27, 0, 0, 0}, FilterStore.load(app).minimums());
    }

    // ---- A change saved from a copy loaded before another write of the minimums ----

    @Test public void aChangeMadeFromACopyLoadedBeforeAnUndoNeverPutsTheGrownMinimumsBack() {
        typicalAt(112);
        long grewAt = clock + 3 * DAY;
        Growth.Grown grown = grewAt(grewAt, 112, 104);
        FilterSettings loaded = FilterStore.load(app);
        long undoneAt = grewAt + 60_000;
        assertTrue(FilterStore.undoGrowth(app, grown, grewAt, undoneAt));
        // Pausing from the copy loaded before the Undo writes the switch only.
        FilterStore.save(app, loaded, loaded.withEnabled(false));
        FilterSettings paused = FilterStore.load(app);
        assertFalse(paused.enabled);
        assertArrayEquals(new int[] {400, 100, 25, 0, 0, 0}, paused.minimums());
        assertEquals(undoneAt, FilterStore.minimumsSince(app));
        // One knob set from it writes its own minimum only, and the minimums take effect again.
        clock += 4 * DAY;
        FilterStore.save(app, loaded, loaded.withMinimums(450, 108, 27).withEnabled(false));
        assertArrayEquals(new int[] {450, 100, 25, 0, 0, 0}, FilterStore.load(app).minimums());
        assertEquals(clock, FilterStore.minimumsSince(app));
        // Max stops alone, likewise.
        FilterStore.save(app, loaded, loaded.withMaxStops(2));
        assertEquals(2, FilterStore.load(app).maxStops);
        assertArrayEquals(new int[] {450, 100, 25, 0, 0, 0}, FilterStore.load(app).minimums());
    }

    // ---- Let my minimums grow ----

    @Test public void letMyMinimumsGrowsStoredChoiceIsRemovedAndNothingElseChanges() {
        typicalAt(112);
        prefs().edit().putBoolean("autopilot_grow", false).commit();
        long since = FilterStore.minimumsSince(app);
        FilterSettings rules = FilterStore.load(app);
        assertFalse("retired with the growth it switched (0.6.21)", prefs().contains("autopilot_grow"));
        assertArrayEquals(new int[] {400, 100, 25, 0, 0, 0}, rules.minimums());
        assertEquals(112, rules.minimumScalePercent);
        assertTrue(rules.autopilot);
        assertEquals(since, FilterStore.minimumsSince(app));
    }
}

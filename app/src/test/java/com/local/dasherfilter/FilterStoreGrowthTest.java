package com.local.dasherfilter;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Where the minimums grow (0.5.1): FilterStore's compare-and-set of the minimums, the bar and "minimums since" together,
 * under its lock, against exactly what the evidence was worked out for; its Undo, which puts the minimums back only
 * while they are still the grown ones and counts as the user's change; "minimums since", moved by every change of a
 * money minimum and never by the bar; a change saved from a copy loaded before a growth, which never puts the old
 * minimums back; and "Let my minimums grow", on unless turned off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
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

    /** 30 lines Autopilot decided at {@code bar} over two days since the minimums took effect, newest first. */
    private static List<Autopilot.OfferRecord> lines(long since, int bar) {
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            long at = since + 2 * DAY - 7 * 60_000L * i - (i >= 15 ? DAY : 0);
            lines.add(new Autopilot.OfferRecord(new OfferSnapshot(1000 + i, 6.6, 27, 2), at, false, false, false,
                    false, false, bar, true, DecisionLog.MODEL));
        }
        return lines;
    }

    /** The growth due for {@code rules}: 8 points, from 30 lines at 108% since the minimums took effect. */
    private Growth.Evidence evidence(FilterSettings rules) {
        long since = FilterStore.minimumsSince(app);
        Growth.Evidence due = Growth.evidence(rules, Autopilot.countedLines(lines(since, 108)), since,
                TimeZone.getTimeZone("America/New_York"));
        assertNotNull(due);
        return due;
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
        assertEquals("its older lines say nothing of which minimums judged them", clock,
                prefs().getLong("minimums_since", 0));
        assertArrayEquals(new int[] {400, 100, 25, 0, 0, 0}, rules.minimums());
        assertTrue("and Let my minimums grow is on for it", FilterStore.minimumsGrow(app));
        clock += DAY;
        FilterStore.load(app);
        assertEquals("once", clock - DAY, FilterStore.minimumsSince(app));

        // 0.4.x: the 0.5.0 migration builds its scale into the minimums, which take effect then.
        prefs().edit().clear().putBoolean("enabled", true).putInt("flat", 1300).putInt("mile", 385)
                .putInt("minute", 41).putInt("minimum_scale_percent", 90).commit();
        clock += DAY;
        assertArrayEquals(new int[] {1170, 347, 37, 0, 0, 0}, FilterStore.load(app).minimums());
        assertEquals(clock, FilterStore.minimumsSince(app));
    }

    // ---- The growth's compare-and-set ----

    @Test public void theMinimumsAndTheBarGrowTogetherAndNothingElseChanges() {
        FilterSettings before = typicalAt(112);
        FilterStore.setAutoAcceptEnabled(app, true);
        Growth.Evidence due = evidence(before);
        long at = clock + 3 * DAY;
        assertTrue(FilterStore.growMinimums(app, due, at));
        FilterSettings after = FilterStore.load(app);
        assertArrayEquals(new int[] {432, 108, 27, 0, 0, 0}, after.minimums());
        assertEquals(1620, after.perHourCents());
        assertEquals(104, after.minimumScalePercent);
        assertEquals("the minimums take effect at the growth", at, FilterStore.minimumsSince(app));
        assertEquals("max stops never grows", 3, after.maxStops);
        assertTrue("auto-decline stays as it was", after.enabled);
        assertTrue("Autopilot stays on", after.autopilot);
        assertEquals(FilterSettings.GOAL_TOP_TIER, after.autopilotGoalPercent);
        assertTrue("auto-accept's own switch never changes", FilterStore.autoAcceptEnabled(app));
        assertFalse("once: the evidence was for the minimums before", FilterStore.growMinimums(app, due, at + 1));
        assertArrayEquals(new int[] {432, 108, 27, 0, 0, 0}, FilterStore.load(app).minimums());
    }

    @Test public void anythingChangedSinceTheEvidenceWritesNothing() {
        FilterSettings before = typicalAt(112);
        Growth.Evidence due = evidence(before);
        long at = clock + 3 * DAY;

        // A minimum the user changed.
        clock += 60_000;
        FilterStore.save(app, before.withMinimums(400, 100, 26));
        assertFalse(FilterStore.growMinimums(app, due, at));
        clock += 60_000;
        FilterStore.save(app, before);
        assertFalse("changed back is not the same: the minimums took effect again", FilterStore.growMinimums(app, due,
                at));
        Growth.Evidence again = evidence(FilterStore.load(app));

        // The bar Autopilot moved.
        assertTrue(FilterStore.commitAutopilotBar(app, 112, 117));
        assertFalse(FilterStore.growMinimums(app, again, at));
        assertTrue(FilterStore.commitAutopilotBar(app, 117, 112));

        // Let my minimums grow turned off, or Autopilot turned off.
        FilterStore.setMinimumsGrow(app, false);
        assertFalse(FilterStore.growMinimums(app, again, at));
        FilterStore.setMinimumsGrow(app, true);
        FilterStore.setAutopilot(app, false, FilterSettings.GOAL_TOP_TIER);
        assertFalse(FilterStore.growMinimums(app, again, at));
        assertArrayEquals("nothing was written", new int[] {400, 100, 25, 0, 0, 0}, FilterStore.load(app).minimums());
        assertEquals(100, FilterStore.load(app).minimumScalePercent);

        // As it was: the growth lands.
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertTrue(FilterStore.commitAutopilotBar(app, 100, 112));
        assertTrue(FilterStore.growMinimums(app, again, at));
        assertArrayEquals(new int[] {432, 108, 27, 0, 0, 0}, FilterStore.load(app).minimums());
    }

    // ---- Undo ----

    @Test public void undoPutsTheMinimumsBackOnlyWhileTheyAreStillTheGrownOnes() {
        FilterSettings before = typicalAt(112);
        Growth.Evidence due = evidence(before);
        long grewAt = clock + 3 * DAY;
        assertTrue(FilterStore.growMinimums(app, due, grewAt));
        long undoneAt = grewAt + 60_000;
        assertTrue(FilterStore.undoGrowth(app, due.grown, grewAt, undoneAt));
        FilterSettings undone = FilterStore.load(app);
        assertArrayEquals(new int[] {400, 100, 25, 0, 0, 0}, undone.minimums());
        assertEquals("the user's change: the minimums take effect now", undoneAt, FilterStore.minimumsSince(app));
        assertEquals("the bar is Autopilot's to move", 104, undone.minimumScalePercent);
        assertEquals(3, undone.maxStops);
        assertFalse("once", FilterStore.undoGrowth(app, due.grown, grewAt, undoneAt + 1));

        // Grown again, then a minimum changed by hand: nothing to undo.
        assertTrue(FilterStore.commitAutopilotBar(app, 104, 112));
        Growth.Evidence next = evidence(FilterStore.load(app));
        long nextAt = undoneAt + 3 * DAY;
        assertTrue(FilterStore.growMinimums(app, next, nextAt));
        clock = nextAt + 60_000;
        FilterStore.save(app, FilterStore.load(app).withMinimums(500, 108, 27));
        assertFalse(FilterStore.undoGrowth(app, next.grown, nextAt, clock + 1));
        assertArrayEquals(new int[] {500, 108, 27, 0, 0, 0}, FilterStore.load(app).minimums());
        // Changed back to exactly the grown ones: still not the grown ones in effect since the growth.
        FilterStore.save(app, FilterStore.load(app).withMinimums(432, 108, 27));
        assertFalse(FilterStore.undoGrowth(app, next.grown, nextAt, clock + 2));
        assertArrayEquals(new int[] {432, 108, 27, 0, 0, 0}, FilterStore.load(app).minimums());
    }

    // ---- A change saved from a copy loaded before the growth ----

    @Test public void aChangeMadeFromACopyLoadedBeforeAGrowthNeverPutsTheOldMinimumsBack() {
        FilterSettings loaded = typicalAt(112);
        Growth.Evidence due = evidence(loaded);
        assertTrue(FilterStore.growMinimums(app, due, clock + 3 * DAY));
        // Pausing from that copy writes the switch only.
        FilterStore.save(app, loaded, loaded.withEnabled(false));
        FilterSettings paused = FilterStore.load(app);
        assertFalse(paused.enabled);
        assertArrayEquals(new int[] {432, 108, 27, 0, 0, 0}, paused.minimums());
        assertEquals(clock + 3 * DAY, FilterStore.minimumsSince(app));
        // One knob set from it writes its own minimum only, and the minimums take effect again.
        clock += 4 * DAY;
        FilterStore.save(app, loaded, loaded.withMinimums(450, 100, 25).withEnabled(false));
        assertArrayEquals(new int[] {450, 108, 27, 0, 0, 0}, FilterStore.load(app).minimums());
        assertEquals(clock, FilterStore.minimumsSince(app));
        // Max stops alone, likewise.
        FilterStore.save(app, loaded, loaded.withMaxStops(2));
        assertEquals(2, FilterStore.load(app).maxStops);
        assertArrayEquals(new int[] {450, 108, 27, 0, 0, 0}, FilterStore.load(app).minimums());
    }

    // ---- Let my minimums grow ----

    @Test public void letMyMinimumsGrowIsOnUnlessTurnedOffAndStaysThroughClearHistory() {
        assertTrue(FilterStore.minimumsGrow(app));
        FilterStore.setMinimumsGrow(app, false);
        assertFalse(FilterStore.minimumsGrow(app));
        AutopilotStore.clear(app);
        assertFalse("one of Autopilot's settings: Clear history keeps it", FilterStore.minimumsGrow(app));
        FilterStore.setMinimumsGrow(app, true);
        assertTrue(FilterStore.minimumsGrow(app));
    }
}

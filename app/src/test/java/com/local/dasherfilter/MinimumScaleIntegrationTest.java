package com.local.dasherfilter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.*;

/**
 * The bar (0.5.0: what the minimums scale became) changes the active boundary without editing the user's minimums or
 * past offers. Only Autopilot moves it, between offers ({@link FilterStore#commitAutopilotBar}); a rule save never
 * writes it, no gesture on the homepage moves it, and turning Autopilot off puts it back at exactly 100%.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, qualifiers = "w411dp-h914dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class MinimumScaleIntegrationTest extends AndroidAdapterTestBase {
    /** $10, $2 a mile, $0.30 a minute, at most 3 stops (their $1 a stop folds into minimum pay: 2 × $1 < $10). */
    private static final FilterSettings RULES = FilterSettings.of(true, Math.max(1000, 2 * 100), 200, 30, 3);

    /** Autopilot on, and the bar it moved to: as a commit between offers leaves it. */
    private void autopilotBarAt(int bar) {
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertTrue(FilterStore.commitAutopilotBar(app, FilterStore.load(app).minimumScalePercent, bar));
        assertEquals(bar, FilterStore.load(app).minimumScalePercent);
    }

    @Test public void oldPreferencesDefaultToExactlyTheMinimumsAndOnlyAutopilotMovesTheBar() {
        app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE).edit().clear()
                .putBoolean("enabled", true).putInt("flat", 1000).commit();
        FilterSettings legacy = FilterStore.load(app);
        assertEquals(100, legacy.minimumScalePercent);
        assertFalse(legacy.autopilot);
        int[] before = legacy.minimums();
        // A rule save never writes the bar.
        FilterStore.save(app, legacy.withMinimumScalePercent(97));
        assertEquals(100, FilterStore.load(app).minimumScalePercent);
        // Autopilot's commit does, and the bar persists.
        autopilotBarAt(97);
        Context freshContext = app.createConfigurationContext(app.getResources().getConfiguration());
        FilterSettings reloaded = FilterStore.load(freshContext);
        assertEquals(97, reloaded.minimumScalePercent);
        assertArrayEquals("the bar does not overwrite individual minimums", before, reloaded.minimums());
        // Off, the bar is exactly the minimums again, and they are as they were.
        FilterStore.setAutopilot(app, false, FilterSettings.GOAL_TOP_TIER);
        assertEquals(100, FilterStore.load(freshContext).minimumScalePercent);
        assertArrayEquals(before, FilterStore.load(freshContext).minimums());
    }

    @Test public void ruleCopiesAndSavesKeepTheBarAutopilotSet() {
        FilterStore.save(app, RULES);
        autopilotBarAt(97);
        FilterSettings current = FilterStore.load(app);
        for (FilterSettings copy : new FilterSettings[] {current.withEnabled(false), current.withMaxStops(2),
                current.withMinimums(1100, 250, 40), current.withMinimums(new int[] {1100, 250, 40})}) {
            assertEquals(97, copy.minimumScalePercent);
            assertTrue(copy.autopilot);
        }
        // Saving other minimums or max stops keeps the bar: only Autopilot (or turning it off) moves it.
        FilterStore.save(app, current.withMinimums(1100, 250, 40).withMaxStops(2));
        FilterSettings saved = FilterStore.load(app);
        assertEquals(97, saved.minimumScalePercent);
        assertArrayEquals(new int[] {1100, 250, 40, 0, 0, 0}, saved.minimums());
        assertEquals(2, saved.maxStops);
        FilterStore.save(app, saved.withEnabled(false));
        assertEquals("pausing keeps the bar", 97, FilterStore.load(app).minimumScalePercent);
    }

    @Test public void aBarAutopilotCommittedMovesBothChartLinesAndSurvivesActivityRecreation() throws Exception {
        FilterStore.save(app, RULES);
        DecisionLog.Entry recorded = entry(System.currentTimeMillis(), 970, 97);
        DecisionLog.record(app, recorded);
        String before = DecisionLog.recent(app, 1).get(0).toJson().toString();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = controller.get().findViewById(android.R.id.content);
            settleSky(content);
            assertEquals("exactly the minimums", 1000L, find(content, DecisionChartView.class).payoutThresholdCents());
        }
        autopilotBarAt(97);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = controller.get().findViewById(android.R.id.content);
            settleSky(content);
            DecisionChartView chart = find(content, DecisionChartView.class);
            assertEquals(970L, chart.payoutThresholdCents());
            assertEquals("a recorded 97% tree meets the new 97% reference", chart.treeAt(0)[1],
                    chart.scoreThresholdY(), 0.01f);
            assertEquals(97, FilterStore.load(app).minimumScalePercent);
            assertArrayEquals(RULES.minimums(), FilterStore.load(app).minimums());
            assertEquals("the past offer is not rewritten", before, DecisionLog.recent(app, 1).get(0).toJson().toString());
            controller.recreate();
            content = controller.get().findViewById(android.R.id.content);
            settleSky(content);
            chart = find(content, DecisionChartView.class);
            assertEquals(970L, chart.payoutThresholdCents());
            assertEquals(chart.treeAt(0)[1], chart.scoreThresholdY(), 0.01f);
        }
    }

    @Test public void aDragAcrossTheAutopilotControlMovesNeitherTheBarNorTheMinimums() {
        FilterStore.save(app, RULES);
        autopilotBarAt(97);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = controller.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            RectF control = star.scoreToggleBox();
            assertNotNull(control);
            Ui ui = new Ui(app);
            dragThrough(content, new float[][] {{control.centerX(), control.centerY()},
                    {control.centerX() - ui.dp(24), control.centerY()}}, null);
            FilterSettings saved = FilterStore.load(app);
            assertEquals("only Autopilot moves the bar", 97, saved.minimumScalePercent);
            assertTrue("a drag is not a tap that turns Autopilot off", saved.autopilot);
            assertArrayEquals(RULES.minimums(), saved.minimums());
            assertEquals(970L, find(content, DecisionChartView.class).payoutThresholdCents());
        }
    }

    @Test public void anInterruptedGestureOnTheAutopilotControlLeavesTheRulesAlone() {
        FilterStore.save(app, RULES);
        autopilotBarAt(97);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = controller.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            RectF control = star.scoreToggleBox();
            assertNotNull(control);
            float x = control.centerX(), y = control.centerY();
            long now = SystemClock.uptimeMillis();
            send(star, now, now, MotionEvent.ACTION_DOWN, x, y);
            send(star, now, now + 100, MotionEvent.ACTION_MOVE, x - new Ui(app).dp(40), y);
            send(star, now, now + 120, MotionEvent.ACTION_CANCEL, x - new Ui(app).dp(40), y);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(97, FilterStore.load(app).minimumScalePercent);
            assertTrue(FilterStore.load(app).autopilot);
            assertArrayEquals(RULES.minimums(), FilterStore.load(app).minimums());
        }
    }

    @Test @Config(sdk = 35) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void phoneShowsTheNinetySevenPercentBoundary() throws Exception {
        render("phone-97", false);
    }

    @Test @Config(sdk = 35, qualifiers = "w411dp-h410dp-420dpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void shortWindowShowsTheNinetySevenPercentBoundary() throws Exception {
        render("short-97", false);
    }

    @Test @Config(sdk = 35, qualifiers = "w411dp-h914dp-night-xxhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void nightShowsTheNinetySevenPercentBoundary() throws Exception {
        render("night-97", true);
    }

    private void render(String name, boolean night) throws Exception {
        // Equal $10 asks from minimum pay, per mile (4 mi × $2.50) and per minute (20 min × $0.50) make the skyline's
        // recorded score and the bar directly comparable in this synthetic preview; their $5 a stop folds into minimum
        // pay (2 × $5 = $10).
        FilterSettings previewRules = FilterSettings.of(true, Math.max(1000, 2 * 500), 250, 50, 3);
        FilterStore.save(app, previewRules);
        autopilotBarAt(97);
        FilterSettings atBar = FilterStore.load(app);
        int[] scores = {60, 82, 97, 125, 185, -1, 96, 210, 120, 101, 80, 140, 430, 97};
        long start = System.currentTimeMillis() - scores.length * 61_000L;
        for (int i = 0; i < scores.length; i++) {
            OfferSnapshot facts = new OfferSnapshot(scores[i] < 0 ? null : scores[i] * 10, 4.0, 20, 2);
            OfferRule.Decision decision = OfferRule.evaluate(facts, atBar);
            DecisionLog.Action action = decision.result == OfferRule.Result.REVIEW ? DecisionLog.Action.NEEDS_REVIEW
                    : decision.result == OfferRule.Result.DECLINE ? DecisionLog.Action.DECLINE_TAPPED
                    : DecisionLog.Action.PASSES;
            DecisionLog.record(app, new DecisionLog.Entry(start + i * 61_000L, DecisionLog.Source.SCREEN, false,
                    facts, decision.requiredCents, decision.result, decision.reason, action, true,
                    Collections.emptyList()).withScore(decision.scorePercent)
                    .withBar(decision.minimumScalePercent, decision.autopilot));
        }
        assertEquals("pay as a percent of the $10 the minimums ask", 97, DecisionLog.recent(app, 1).get(0).scorePercent);
        populateSyntheticAtlas();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = controller.get().findViewById(android.R.id.content);
            settleSky(content);
            DecisionChartView chart = find(content, DecisionChartView.class);
            assertNotNull(chart);
            assertEquals(night, new Ui(app).dark);
            assertEquals(970L, chart.payoutThresholdCents());
            assertEquals(chart.treeAt(13)[1], chart.scoreThresholdY(), 0.01f);
            assertTrue(chart.scoreThresholdY() >= 0 && chart.scoreThresholdY() < chart.getHeight());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1400));
            Bitmap bitmap = Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
            content.draw(new Canvas(bitmap));
            File directory = new File("build/reports/minimum-scale");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
            } finally {
                bitmap.recycle();
            }
        }
    }

    /** Independent invented location samples, never a phone trace or an actual Dasher hotspot observation. */
    private void populateSyntheticAtlas() {
        AreaMap.forget(app);
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        AreaMap.setEnabled(app, true);
        // Three ranked cells at $2, $3 and $1 per mile, plus a fourth with too few samples to rank.
        noteOfferAt(37.7749, -122.4194, 1000, 5.0);
        noteOfferAt(37.7749, -122.4194, 1200, 6.0);
        noteOfferAt(37.7749, -122.4194, 800, 4.0);
        noteOfferAt(37.8149, -122.3794, 1500, 5.0);
        noteOfferAt(37.8149, -122.3794, 1800, 6.0);
        noteOfferAt(37.8149, -122.3794, 1200, 4.0);
        noteOfferAt(37.7349, -122.4594, 500, 5.0);
        noteOfferAt(37.7349, -122.4594, 600, 6.0);
        noteOfferAt(37.7349, -122.4594, 400, 4.0);
        noteOfferAt(37.8149, -122.4594, 900, 3.0);
        setLocation(37.7749, -122.4194);
    }

    private static DecisionLog.Entry entry(long at, Integer payout, int score) {
        OfferRule.Result result = payout == null ? OfferRule.Result.REVIEW
                : score < 100 ? OfferRule.Result.DECLINE : OfferRule.Result.KEEP;
        DecisionLog.Action action = result == OfferRule.Result.REVIEW ? DecisionLog.Action.NEEDS_REVIEW
                : result == OfferRule.Result.DECLINE ? DecisionLog.Action.DECLINE_TAPPED : DecisionLog.Action.PASSES;
        return new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(payout, 4.0, 18, 2), 1000, result, "synthetic historical chart fixture", action, true,
                Collections.emptyList()).withScore(score);
    }

    private static void send(View view, long start, long at, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(start, at, action, x, y, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }
}

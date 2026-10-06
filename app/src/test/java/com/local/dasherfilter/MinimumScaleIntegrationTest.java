package com.local.dasherfilter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;
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

/** A global buffer changes the active boundary without editing the user's five minima or past offers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35, 36}, qualifiers = "w411dp-h914dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class MinimumScaleIntegrationTest extends AndroidAdapterTestBase {
    private static final FilterSettings RULES = new FilterSettings(true, 1000, 200, 30, 100, 3)
            .withScoreByArea(true);

    @Test public void oldPreferencesDefaultToFullMinimumsAndTheNewScalePersists() {
        app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE).edit().clear()
                .putBoolean("enabled", true).putInt("flat", 1000).commit();
        FilterSettings legacy = FilterStore.load(app);
        assertEquals(100, legacy.minimumScalePercent);
        int[] before = legacy.minimums();
        FilterStore.save(app, legacy.withMinimumScalePercent(97));
        Context freshContext = app.createConfigurationContext(app.getResources().getConfiguration());
        FilterSettings reloaded = FilterStore.load(freshContext);
        assertEquals(97, reloaded.minimumScalePercent);
        assertArrayEquals("the buffer does not overwrite individual floors", before, reloaded.minimums());
    }

    @Test public void learningAdoptionAndRuleCopiesKeepTheChosenBuffer() {
        FilterSettings settings = RULES.withAdaptive(true).withMinimumScalePercent(97);
        FilterStore.save(app, settings);
        assertTrue(FilterStore.recordAccepted(app, new OfferSnapshot(2000, 6.0, 25, 2)));
        FilterSettings learned = FilterStore.load(app);
        assertEquals(97, learned.minimumScalePercent);
        assertEquals("the actual accepted payout, not a scaled value, is learned", 2000,
                learned.lastAcceptedCents);
        for (FilterSettings copy : new FilterSettings[] {learned.withEnabled(false), learned.withScoreByArea(false),
                learned.withMaxStops(2), learned.withAdaptive(false), learned.withoutRisingBaseline(),
                learned.withHotspotProximity(50), learned.withMinimums(new int[] {1100, 250, 40, 200, 0}),
                learned.adoptAdaptive()}) {
            assertEquals(97, copy.minimumScalePercent);
        }
        FilterStore.save(app, learned.adoptAdaptive());
        assertEquals(2001, FilterStore.load(app).flatCents);
        assertEquals(97, FilterStore.load(app).minimumScalePercent);
        FilterStore.resetAccepted(app);
        assertEquals(97, FilterStore.load(app).minimumScalePercent);
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
    }

    @Test public void accessibleScaleRefreshesBothChartLinesAndSurvivesActivityRecreation() throws Exception {
        FilterStore.save(app, RULES);
        DecisionLog.Entry recorded = entry(System.currentTimeMillis(), 970, 97);
        DecisionLog.record(app, recorded);
        String before = DecisionLog.recent(app, 1).get(0).toJson().toString();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = controller.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            SeekBar slider = find(content, SeekBar.class);
            assertNotNull("main page hosts native selectivity", slider);
            AccessibilityNodeInfo score = slider.createAccessibilityNodeInfo();
            assertNotNull(score.getRangeInfo());
            assertEquals(100f, score.getRangeInfo().getCurrent(), 0.001f);
            assertEquals(1f, score.getRangeInfo().getMin(), 0.001f);
            assertEquals(200f, score.getRangeInfo().getMax(), 0.001f);
            setScale(slider, 97);
            DecisionChartView chart = find(content, DecisionChartView.class);
            assertEquals(970L, chart.payoutThresholdCents());
            assertEquals("a recorded 97% tree meets the new 97% reference", chart.treeAt(0)[1],
                    chart.scoreThresholdY(), 0.01f);
            assertEquals(97, FilterStore.load(app).minimumScalePercent);
            assertArrayEquals(RULES.minimums(), FilterStore.load(app).minimums());
            assertEquals(before, DecisionLog.recent(app, 1).get(0).toJson().toString());
            assertTrue(chart.getContentDescription().toString(), chart.getContentDescription().toString()
                    .contains("97%"));
            assertTrue(slider.getContentDescription().toString().contains("97 percent"));
            assertNull("mode is separate from scale", node(star, MinimumsStarView.SCORE_ID).getRangeInfo());
            assertTrue(act(star, MinimumsStarView.SCORE_ID, AccessibilityNodeInfo.ACTION_CLICK));
            assertFalse("a tap still toggles area mode", FilterStore.load(app).scoreByArea);
            assertEquals("the mode toggle preserves the buffer", 97, FilterStore.load(app).minimumScalePercent);
            controller.recreate();
            content = controller.get().findViewById(android.R.id.content);
            settleSky(content);
            assertEquals(970L, find(content, DecisionChartView.class).payoutThresholdCents());
            assertEquals(97f, find(content, SeekBar.class).createAccessibilityNodeInfo()
                    .getRangeInfo().getCurrent(), 0.001f);
        }
    }

    @Test public void horizontalDragChangesTheScaleWithoutTogglingTheModeOrChangingKnobs() {
        FilterStore.save(app, RULES);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = controller.get().findViewById(android.R.id.content);
            settleSky(content);
            SeekBar slider = find(content, SeekBar.class);
            assertNotNull(slider);
            long now = SystemClock.uptimeMillis();
            float y = slider.getHeight() / 2f;
            send(slider, now, now, MotionEvent.ACTION_DOWN, sliderX(slider, 100), y);
            send(slider, now, now + 100, MotionEvent.ACTION_MOVE, sliderX(slider, 75), y);
            assertEquals("preview is not saved yet", 100, FilterStore.load(app).minimumScalePercent);
            send(slider, now, now + 120, MotionEvent.ACTION_UP, sliderX(slider, 75), y);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            FilterSettings saved = FilterStore.load(app);
            assertEquals(75, saved.minimumScalePercent);
            assertTrue(saved.scoreByArea);
            assertArrayEquals(RULES.minimums(), saved.minimums());
            assertEquals(750L, find(content, DecisionChartView.class).payoutThresholdCents());
        }
    }

    @Test public void interruptedScaleGestureLeavesSavedRulesAlone() {
        FilterStore.save(app, RULES.withMinimumScalePercent(97));
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = controller.get().findViewById(android.R.id.content);
            settleSky(content);
            SeekBar slider = find(content, SeekBar.class);
            assertNotNull(slider);
            float y = slider.getHeight() / 2f;
            long now = SystemClock.uptimeMillis();
            send(slider, now, now, MotionEvent.ACTION_DOWN, sliderX(slider, 97), y);
            send(slider, now, now + 100, MotionEvent.ACTION_MOVE, sliderX(slider, 60), y);
            send(slider, now, now + 120, MotionEvent.ACTION_CANCEL, sliderX(slider, 60), y);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(97, FilterStore.load(app).minimumScalePercent);
            assertEquals(97, slider.getProgress());
            assertTrue(FilterStore.load(app).scoreByArea);
            assertArrayEquals(RULES.minimums(), FilterStore.load(app).minimums());
        }
    }

    @Test public void draggingModeChipNeverAdjustsTheScaleOrTogglesTheMode() {
        FilterStore.save(app, RULES.withMinimumScalePercent(97));
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = controller.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            RectF mode = star.scoreToggleBox();
            assertNotNull(mode);
            dragThrough(content, new float[][] {{mode.centerX(), mode.centerY()},
                    {mode.centerX() + new Ui(app).dp(40), mode.centerY()}}, null);
            assertEquals(97, FilterStore.load(app).minimumScalePercent);
            assertTrue(FilterStore.load(app).scoreByArea);
        }
    }

    @Test @Config(sdk = {35, 36}) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void phoneShowsTheNinetySevenPercentBoundary() throws Exception {
        render("phone-97", false);
    }

    @Test @Config(sdk = {35, 36}, qualifiers = "w411dp-h410dp-420dpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void shortWindowShowsTheNinetySevenPercentBoundary() throws Exception {
        render("short-97", false);
    }

    @Test @Config(sdk = {35, 36}, qualifiers = "w411dp-h914dp-night-xxhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void nightShowsTheNinetySevenPercentBoundary() throws Exception {
        render("night-97", true);
    }

    private void render(String name, boolean night) throws Exception {
        // Equal $10 baseline asks on every monetary spoke make the skyline's recorded score and the
        // constellation's current score directly comparable in this synthetic preview.
        FilterSettings previewRules = new FilterSettings(true, 1000, 250, 50, 500, 3)
                .withScoreByArea(true).withMinimumScalePercent(97);
        FilterStore.save(app, previewRules);
        int[] scores = {60, 82, 97, 125, 185, -1, 96, 210, 120, 101, 80, 140, 430, 97};
        long start = System.currentTimeMillis() - scores.length * 61_000L;
        for (int i = 0; i < scores.length; i++) {
            OfferSnapshot facts = new OfferSnapshot(scores[i] < 0 ? null : scores[i] * 10, 4.0, 20, 2);
            OfferRule.Decision decision = OfferRule.evaluate(facts, previewRules);
            DecisionLog.Action action = decision.result == OfferRule.Result.REVIEW ? DecisionLog.Action.NEEDS_REVIEW
                    : decision.result == OfferRule.Result.DECLINE ? DecisionLog.Action.DECLINE_TAPPED
                    : DecisionLog.Action.PASSES;
            DecisionLog.record(app, new DecisionLog.Entry(start + i * 61_000L, DecisionLog.Source.SCREEN, false,
                    facts, decision.requiredCents, decision.result, decision.reason, action, true,
                    Collections.emptyList()).withScore(decision.scorePercent));
        }
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

    private static void setScale(SeekBar slider, int percent) {
        Bundle value = new Bundle();
        value.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, percent);
        assertTrue(slider.performAccessibilityAction(android.R.id.accessibilityActionSetProgress, value));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static float sliderX(SeekBar slider, int percent) {
        return slider.getPaddingLeft() + (slider.getWidth() - slider.getPaddingLeft() - slider.getPaddingRight())
                * (percent - 1) / 199f;
    }

    private static void send(View view, long start, long at, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(start, at, action, x, y, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }
}

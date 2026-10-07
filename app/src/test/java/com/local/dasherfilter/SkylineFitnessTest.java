package com.local.dasherfilter;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
 * Recorded scores and the current minimums must be readable without changing what an offer meant: buildings are pay,
 * trees the score this version recorded (pay as a percent of the minimums; none for a line decided under the retired
 * rules), a dashed rail at the bar ("Bar 82%") and, only with a minimum pay, a solid rail at what it asks at the bar
 * ({@code ⌈bar × flat ÷ 100⌉}).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, qualifiers = "w411dp-h914dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class SkylineFitnessTest extends AndroidAdapterTestBase {
    private static final FilterSettings RULES = FilterSettings.of(true, 1000, 200, 30, 0);

    @Test public void payoutAndFitnessHaveIndependentHeightsAndScales() {
        DecisionLog.Entry low = offer(1, 1200, 1000, 50);
        DecisionLog.Entry high = offer(2, 1200, 1000, 150);
        DecisionChartView chart = chart(RULES, high, low);
        assertEquals("equal payouts have equal roof flags", chart.flagAt(0)[1], chart.flagAt(1)[1], 0.01f);
        assertTrue("the fitter offer has the taller tree", chart.treeAt(1)[1] < chart.treeAt(0)[1]);
        float[] lowTree = chart.treeAt(0), highTree = chart.treeAt(1);
        float scoreLine = chart.scoreThresholdY();

        chart.setEntries(Arrays.asList(offer(2, 20_000, 1000, 150), low));
        assertEquals("large payout cannot rescale the score tree", lowTree[1], chart.treeAt(0)[1], 0.01f);
        assertEquals(highTree[1], chart.treeAt(1)[1], 0.01f);
        assertEquals(scoreLine, chart.scoreThresholdY(), 0.01f);
        assertTrue("larger payout still has a higher building", chart.flagAt(1)[1] < chart.flagAt(0)[1]);

        float oldRoof = chart.flagAt(0)[1];
        chart.setEntries(Arrays.asList(offer(2, 20_000, 1000, 399), low));
        assertEquals("a fitness change cannot rescale payout", oldRoof, chart.flagAt(0)[1], 0.01f);
    }

    @Test public void treesCrossTheScoreLineAtOneHundredPercentAndUnknownDoesNotBecomeZero() {
        DecisionChartView chart = chart(RULES, offer(4, null, 1000, -1), offer(3, 1000, 1000, 0),
                offer(2, 1000, 1000, 125), offer(1, 1000, 1000, 100));
        assertEquals("fitness 100% meets its own threshold", chart.scoreThresholdY(), chart.treeAt(0)[1], 0.01f);
        assertTrue(chart.treeAt(1)[1] < chart.scoreThresholdY());
        float[] zero = chart.treeAt(2);
        assertNotNull("zero is a recorded score", zero);
        assertEquals("zero height stays on the ground", zero[2], zero[1], 0.01f);
        assertNull("unread score has no tree", chart.treeAt(3));

        chart.setEntries(Collections.singletonList(offer(5, 1200, 1000, Integer.MAX_VALUE)));
        float[] saturated = chart.treeAt(0);
        assertNotNull(saturated);
        assertTrue(Float.isFinite(saturated[1]));
        assertTrue("extreme scores remain inside the chart", saturated[1] >= 0);
        assertTrue("a clipped tree is explicitly marked", chart.treeClippedAt(0));
    }

    @Test public void payoutRailIsTheMinimumPayAtTheBarAndOnlyWithAMinimumPay() {
        DecisionChartView chart = chart(RULES, offer(1, 1200, 1000, 75));
        assertEquals("exactly the minimum pay at a bar of 100%", 1000L, chart.payoutThresholdCents());
        assertTrue(Float.isFinite(chart.payoutThresholdY()));
        List<String> drawn = drawnWords(chart);
        assertTrue(drawn.toString(), drawn.contains("Pay $10.00 min"));
        assertTrue(drawn.toString(), drawn.contains("Bar 100%"));

        // Autopilot's bar: ⌈82 × $10.00 ÷ 100⌉ = $8.20, and the dashed rail names the bar.
        chart.setRules(atBar(RULES, 82));
        assertEquals(820L, chart.payoutThresholdCents());
        drawn = drawnWords(chart);
        assertTrue(drawn.toString(), drawn.contains("Pay $8.20 min"));
        assertTrue(drawn.toString(), drawn.contains("Bar 82%"));
        // Rounded up to the next cent: ⌈97 × $10.01 ÷ 100⌉ = $9.71, ⌈150 × $10.01 ÷ 100⌉ = $15.02.
        chart.setRules(atBar(FilterSettings.of(true, 1001, 0, 0, 0), 97));
        assertEquals(971L, chart.payoutThresholdCents());
        chart.setRules(atBar(FilterSettings.of(true, 1001, 0, 0, 0), 150));
        assertEquals(1502L, chart.payoutThresholdCents());
        assertTrue(drawnWords(chart).contains("Bar 150%"));

        // A per-mile or per-hour minimum is no level line of pay: no solid rail, and no zero-dollar one.
        chart.setRules(FilterSettings.of(true, 0, 9999, 30, 0));
        assertEquals(0L, chart.payoutThresholdCents());
        assertTrue("off draws no misleading zero-dollar minimum line", Float.isNaN(chart.payoutThresholdY()));
        drawn = drawnWords(chart);
        assertFalse(drawn.toString(), drawn.stream().anyMatch(word -> word.startsWith("Pay $")));
        assertTrue("the bar's rail stays", drawn.contains("Bar 100%"));
        assertTrue(chart.getContentDescription().toString().contains("No minimum pay is set."));
    }

    @Test public void tappingATreeSelectsTheSameOfferAsItsBuilding() {
        DecisionLog.Entry older = offer(1, 800, 1000, 70);
        DecisionLog.Entry newer = offer(2, 1600, 1000, 145);
        DecisionChartView chart = chart(RULES, newer, older);
        List<DecisionLog.Entry> selected = new ArrayList<>();
        chart.setOnSelect(selected::add);
        float[] tree = chart.treeAt(0);
        tapChart(chart, tree[0], (tree[1] + tree[2]) / 2);
        assertSame(older, chart.selectedEntry());
        assertSame(older, selected.get(selected.size() - 1));
        float[] flag = chart.flagAt(1);
        tapChart(chart, flag[0], flag[1]);
        assertSame(newer, chart.selectedEntry());
        assertSame(newer, selected.get(selected.size() - 1));
    }

    @Test public void changingCurrentRulesNeverRewritesRecordedScoresAndExplainsTheTwoRails() {
        DecisionLog.Entry known = offer(1, 1200, 1000, 123);
        DecisionChartView chart = chart(RULES, offer(2, 700, 1000, -1), known);
        float treeY = chart.treeAt(0)[1];
        String said = chart.getContentDescription().toString();
        assertTrue(said, said.contains(AutopilotText.SKYLINE_DESCRIPTION));
        assertTrue(said, said.contains("Dashed line: Bar 100%."));
        assertTrue(said, said.contains("Solid line: your minimum pay at the bar, $10.00."));
        assertTrue(said, said.contains("1 recorded scores unavailable: open markers, no trees."));
        for (String retired : new String[] {"advisory", "area", "compensat", "fitness", "spoke"}) {
            assertFalse(retired + ": " + said, said.toLowerCase(java.util.Locale.US).contains(retired));
        }
        assertTrue(chart.choose(known));
        assertTrue(chart.getContentDescription().toString().contains("score 123% of your minimums."));

        chart.setRules(atBar(FilterSettings.of(true, 6000, 2000, 500, 0), 82));
        assertEquals("a recorded score is not worked out again under today's minimums", treeY, chart.treeAt(0)[1],
                0.01f);
        assertEquals(123, known.scorePercent);
        assertEquals(OfferRule.Result.KEEP, known.result);
        said = chart.getContentDescription().toString();
        assertTrue(said, said.contains("Dashed line: Bar 82%."));
        assertTrue(said, said.contains("Solid line: your minimum pay at the bar, $49.20."));
    }

    @Test public void aLineDecidedUnderTheRetiredRulesHasNoTreeWhateverItsAreaScore() throws Exception {
        DecisionLog.Entry now = offer(2, 1200, 1000, 120);
        DecisionLog.Entry retired = legacy(offer(1, 1200, 1000, 121));
        assertEquals(DecisionLog.LEGACY_MODEL, retired.model);
        assertEquals("its area score is kept as recorded", 121, retired.scorePercent);
        DecisionChartView chart = chart(RULES, now, retired);
        assertNull("no tree for a retired area score", chart.treeAt(0));
        assertFalse(chart.treeClippedAt(0));
        assertNotNull("this version's score has its tree", chart.treeAt(1));
        String said = chart.getContentDescription().toString();
        assertTrue(said, said.contains("1 scores from retired rules: open markers, no trees."));
        assertTrue(chart.choose(retired));
        assertTrue(chart.getContentDescription().toString().contains("score from retired rules."));
        // A huge retired score cannot stretch the percent scale either.
        DecisionChartView alone = chart(RULES, now, legacy(offer(1, 1200, 1000, 900)));
        assertEquals(chart.treeAt(1)[1], alone.treeAt(1)[1], 0.01f);
    }

    @Test public void screenReadersCanBrowseHistoryAndOpenTheSelectedOffer() {
        FilterStore.save(app, RULES);
        long now = System.currentTimeMillis();
        DecisionLog.record(app, offer(now - 60_000, 800, 1000, 80));
        DecisionLog.record(app, offer(now, 1600, 1000, 160));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            DecisionChartView chart = find(content, DecisionChartView.class);
            int previous = android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            int next = android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
            assertEquals(Integer.valueOf(1600), chart.selectedEntry().facts.payCents);
            assertTrue(chart.performAccessibilityAction(previous, null));
            assertEquals(Integer.valueOf(800), chart.selectedEntry().facts.payCents);
            assertTrue(chart.getContentDescription().toString().contains("Selected offer 1 of 2: $8.00"));
            assertFalse("the first offer does not wrap", chart.performAccessibilityAction(previous, null));
            assertEquals("browsing alone does not open a ticket", -1,
                    find(content, MinimumsStarView.class).openedOffer());
            assertTrue(chart.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,
                    null));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("the older offer is open", 1, find(content, MinimumsStarView.class).openedOffer());
            assertEquals(now - 60_000, chart.selectedEntry().at);
            assertNotNull(shownTextContaining(content, "80%"));
            activity.get().onBackPressed();
            assertTrue(chart.performAccessibilityAction(next, null));
            assertEquals(Integer.valueOf(1600), chart.selectedEntry().facts.payCents);
            assertFalse("the latest offer does not wrap", chart.performAccessibilityAction(next, null));
            android.os.Bundle invalid = new android.os.Bundle();
            invalid.putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                    Float.NaN);
            assertFalse(chart.performAccessibilityAction(android.R.id.accessibilityActionSetProgress, invalid));
            assertEquals(Integer.valueOf(1600), chart.selectedEntry().facts.payCents);
        }
    }

    @Test public void draggingThePayMinimumOrAutopilotsBarRefreshesTheSkylineWithoutANewOffer() {
        FilterStore.save(app, RULES);
        DecisionLog.Entry recorded = offer(System.currentTimeMillis(), 1200, 1000, 123);
        DecisionLog.record(app, recorded);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            DecisionChartView chart = find(content, DecisionChartView.class);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertEquals(1000L, chart.payoutThresholdCents());
            android.os.Bundle value = new android.os.Bundle();
            value.putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 20f);
            assertTrue(star.getAccessibilityNodeProvider().performAction(AreaScore.PAY,
                    android.R.id.accessibilityActionSetProgress, value));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(2000L, chart.payoutThresholdCents());
            assertEquals(recorded.at, chart.selectedEntry().at);
            assertEquals("the same historical score stays selected", 123, chart.selectedEntry().scorePercent);
            float[] tree = chart.treeAt(0);
            tapChart(chart, tree[0], (tree[1] + tree[2]) / 2);
            assertNotNull("the selected ticket exposes its recorded score as readable text",
                    shownTextContaining(content, "123%"));
            activity.get().onBackPressed();

            // Autopilot moves its bar between offers: both rails follow, and the tree keeps its recorded score (its
            // height against the dashed rail is 123 to 82, on the same percent scale; the page gave Autopilot's status
            // line its row, so the skyline may stand a little shorter).
            FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
            assertTrue(FilterStore.commitAutopilotBar(app, 100, 82));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            layOut(content);
            assertEquals("82% of $20.00", 1640L, chart.payoutThresholdCents());
            assertTrue(chart.getContentDescription().toString().contains("Dashed line: Bar 82%."));
            assertEquals(82, chart.scoreThresholdPercent());
            float[] moved = chart.treeAt(0);
            float bottom = moved[2];
            assertEquals("the recorded score against the bar", 123f / 82f,
                    (bottom - moved[1]) / (bottom - chart.scoreThresholdY()), 0.02f);
            assertEquals(123, chart.selectedEntry().scorePercent);
        }
    }

    @Test @Config(sdk = 35) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void phoneRendersRecordedTreesAndBothReferences() throws Exception {
        renderPage("phone", false, 100);
    }

    @Test @Config(sdk = 35, qualifiers = "w411dp-h410dp-420dpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void shortWindowAndFiftyTwoDpChartKeepTheirReferencesInBounds() throws Exception {
        renderPage("short", false, 82);
        DecisionChartView chart = chart(atBar(RULES, 82), previewEntries().toArray(new DecisionLog.Entry[0]));
        size(chart, 411, 52);
        assertVisibleGeometry(chart);
        export(chart, "compact-52dp");
    }

    @Test @Config(sdk = 35, qualifiers = "w411dp-h914dp-night-xxhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void nightPaletteRendersTheTwoSeries() throws Exception {
        renderPage("night", true, 119);
    }

    /** The page with the preview history, its rules at {@code bar} (Autopilot on unless it is 100). */
    private void renderPage(String name, boolean dark, int bar) throws Exception {
        Appearance.choose(app, dark ? Appearance.Mode.NIGHT : Appearance.Mode.DAY);
        FilterStore.save(app, RULES);
        if (bar != 100) {
            FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
            assertTrue(FilterStore.commitAutopilotBar(app, 100, bar));
        }
        List<DecisionLog.Entry> chronological = previewEntries();
        Collections.reverse(chronological);
        for (DecisionLog.Entry entry : chronological) DecisionLog.record(app, entry);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            DecisionChartView chart = find(content, DecisionChartView.class);
            assertNotNull(chart);
            assertTrue(chart.isShown());
            assertEquals(dark, new Ui(activity.get()).dark);
            assertEquals("the main page supplied the minimum pay at its bar", (bar * 1000L + 99) / 100,
                    chart.payoutThresholdCents());
            assertTrue(chart.getContentDescription().toString().contains("Dashed line: Bar " + bar + "%."));
            assertVisibleGeometry(chart);
            export(content, name);
        }
    }

    /** Synthetic values only; no recovered diagnostic screen text or customer data belongs in these previews. */
    private List<DecisionLog.Entry> previewEntries() {
        int[] scores = {35, 58, 82, 100, 145, 210, 75, -1, 0, 430, 120, 95, 180, 110};
        int[] pays = {550, 750, 1300, 1000, 1650, 2500, 650, 900, 0, 1900, 1250, 950, 2200, 1400};
        List<DecisionLog.Entry> result = new ArrayList<>();
        long start = System.currentTimeMillis() - scores.length * 61_000L;
        for (int i = 0; i < scores.length; i++) {
            result.add(offer(start + i * 61_000L, i == 7 ? null : pays[i], 1000, scores[i]));
        }
        Collections.reverse(result);
        return result;
    }

    private static void assertVisibleGeometry(DecisionChartView chart) {
        for (float lineY : new float[] {chart.scoreThresholdY(), chart.payoutThresholdY()}) {
            assertTrue("reference line is finite", Float.isFinite(lineY));
            assertTrue("reference line stays in the drawing", lineY >= 0 && lineY < chart.getHeight());
        }
        for (int i = 0; i < DecisionChartView.SLOTS; i++) {
            float[] tree = chart.treeAt(i);
            if (tree == null) continue;
            assertTrue("tree does not extend past its view", tree[0] - tree[3] / 2 >= 0
                    && tree[0] + tree[3] / 2 <= chart.getWidth());
            assertTrue("tree height is finite and within the graph", Float.isFinite(tree[1])
                    && tree[1] >= 0 && tree[1] <= tree[2] && tree[2] < chart.getHeight());
        }
    }

    private void export(View view, String name) throws Exception {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1400));
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(new Ui(app).page);
        view.draw(canvas);
        File directory = new File("build/reports/skyline-fitness");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally {
            bitmap.recycle();
        }
    }

    private static DecisionLog.Entry offer(long at, Integer cents, long required, int score) {
        OfferRule.Result result = cents == null ? OfferRule.Result.REVIEW
                : cents < required ? OfferRule.Result.DECLINE : OfferRule.Result.KEEP;
        DecisionLog.Action action = result == OfferRule.Result.REVIEW ? DecisionLog.Action.NEEDS_REVIEW
                : result == OfferRule.Result.DECLINE ? DecisionLog.Action.DECLINE_TAPPED : DecisionLog.Action.PASSES;
        return new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false,
                new OfferSnapshot(cents, 4.0, 18, 2), required, result, "synthetic chart fixture", action, true,
                Collections.emptyList()).withScore(score);
    }

    /** {@code rules} at {@code bar}, as Autopilot holds it (on unless the bar is 100). */
    private static FilterSettings atBar(FilterSettings rules, int bar) {
        return new FilterSettings(rules.enabled, rules.flatCents, rules.perMileCents, rules.perMinuteCents,
                rules.maxStops, bar != 100, FilterSettings.GOAL_TOP_TIER, bar);
    }

    /** {@code entry} as an older version wrote it: no rules model, bar or Autopilot in its JSON. */
    private static DecisionLog.Entry legacy(DecisionLog.Entry entry) throws Exception {
        org.json.JSONObject json = entry.toJson();
        json.remove("model");
        json.remove("bar");
        json.remove("auto");
        return DecisionLog.Entry.fromJson(json);
    }

    /** Every word {@code view} draws. */
    private static List<String> drawnWords(View view) {
        List<String> words = new ArrayList<>();
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap) {
            @Override public void drawText(String text, float x, float y, android.graphics.Paint paint) {
                words.add(text);
                super.drawText(text, x, y, paint);
            }

            @Override public void drawText(CharSequence text, int start, int end, float x, float y,
                                           android.graphics.Paint paint) {
                words.add(text.subSequence(start, end).toString());
                super.drawText(text, start, end, x, y, paint);
            }
        });
        bitmap.recycle();
        return words;
    }

    private DecisionChartView chart(FilterSettings rules, DecisionLog.Entry... newestFirst) {
        DecisionChartView chart = new DecisionChartView(app, new Ui(app));
        chart.setRules(rules);
        chart.setEntries(Arrays.asList(newestFirst));
        size(chart, 411, 160);
        return chart;
    }

    private void size(DecisionChartView chart, int widthDp, int heightDp) {
        Ui ui = new Ui(app);
        int width = ui.dp(widthDp), height = ui.dp(heightDp);
        chart.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        chart.layout(0, 0, width, height);
    }

    private static void tapChart(DecisionChartView chart, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0);
        chart.dispatchTouchEvent(down);
        chart.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }
}

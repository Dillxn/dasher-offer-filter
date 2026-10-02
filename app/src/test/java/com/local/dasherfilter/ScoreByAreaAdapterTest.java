package com.local.dasherfilter;

import android.app.Application;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Score by area through the real page: the toggle by the constellation (its only home), the chart in
 * normalized space, a knob held on a still scale, and the score on the ticket, in the history and in reports. The
 * rules are the user's: $13 pay, $3.85 a mile, $0.41 a minute, $4.75 a stop, at most 3 stops.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, qualifiers = "w411dp-h914dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class ScoreByAreaAdapterTest {
    private static final FilterSettings USER = new FilterSettings(true, 1300, 385, 41, 475, 3);

    private Application app;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        DecisionLog.forgetCache();
        AreaMap.forgetCache();
        ReportOutbox.forgetCache();
    }

    @Test
    public void theToggleByTheChartTurnsScoreByAreaOnAndOffAndSettingsMirrorsIt() throws Exception {
        FilterStore.save(app, USER);
        record(USER, 60_000, 1500, 6.0, 25, 2);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(star.backdrop());
            assertFalse("strict is the default", star.byArea());
            android.graphics.RectF toggle = star.scoreToggleBox();
            assertNotNull("the shared scale and mode control by the chart", toggle);
            Ui ui = new Ui(app);
            assertTrue("a full touch target", toggle.width() >= ui.dp(48) - 1);
            for (int axis = 0; axis < 4; axis++) {
                float[] knob = star.knobAt(axis);
                assertTrue("clear of the knobs", Math.hypot(knob[0] - toggle.centerX(), knob[1] - toggle.centerY())
                        >= ui.dp(24) + toggle.width() / 2 - 1);
            }
            List<android.graphics.RectF> icons = new ArrayList<>();
            star.iconsAt(icons);
            assertTrue("the scene keeps its clouds and stars off it", contains(icons, toggle.centerX(),
                    toggle.centerY()));

            // Screen readers find it as a switch.
            AccessibilityNodeProvider nodes = star.getAccessibilityNodeProvider();
            AccessibilityNodeInfo node = nodes.createAccessibilityNodeInfo(MinimumsStarView.SCORE_ID);
            assertEquals("Minimums 100%. Score by area off. Drag sideways to scale all minimums; tap to change scoring mode.",
                    node.getContentDescription().toString());
            assertEquals(Switch.class.getName(), node.getClassName().toString());
            assertTrue(node.isCheckable());
            assertFalse(node.isChecked());

            // A tap turns it on, saved at once; nothing else changes.
            ViewGroup sky = (ViewGroup) star.getParent();
            tap(sky, toggle.centerX(), toggle.centerY());
            FilterSettings saved = FilterStore.load(app);
            assertTrue(saved.scoreByArea);
            assertArrayEquals(new int[] {1300, 385, 41, 475, 0}, saved.minimums());
            assertTrue(saved.enabled);
            assertEquals(3, saved.maxStops);
            assertTrue(star.byArea());
            assertTrue(nodes.createAccessibilityNodeInfo(MinimumsStarView.SCORE_ID).isChecked());
            assertTrue(star.lastSaid(), star.lastSaid().startsWith("Score by area on."));
            assertNull("a tap on it opens nothing", shownTextContaining(content, "Share report"));
            assertNull("Settings has no mirror of it", findButton(content, "Score by area"));
            assertTrue(DiagnosticLog.read(app), DiagnosticLog.read(app).contains("[rules] score by area on"));

            // A screen reader turns it off again, and on.
            assertTrue(nodes.performAction(MinimumsStarView.SCORE_ID, AccessibilityNodeInfo.ACTION_CLICK, null));
            assertFalse(FilterStore.load(app).scoreByArea);
            assertFalse(star.byArea());
            assertTrue(star.lastSaid(), star.lastSaid().startsWith("Score by area off."));
            assertTrue(nodes.performAction(MinimumsStarView.SCORE_ID, AccessibilityNodeInfo.ACTION_CLICK, null));
            assertTrue(FilterStore.load(app).scoreByArea);
            assertTrue(star.byArea());
        }
        // Kept for the next time the page opens.
        try (ActivityController<MainActivity> again = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = again.get().findViewById(android.R.id.content);
            settleSky(content);
            assertTrue(find(content, MinimumsStarView.class).byArea());
        }
    }

    @Test
    public void theSpokesAreDrawnInTheOrderTheScorePairsThem() {
        FilterStore.save(app, USER);
        for (boolean byArea : new boolean[] {false, true}) {
            FilterStore.save(app, USER.withScoreByArea(byArea));
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                View content = activity.get().findViewById(android.R.id.content);
                settleSky(content);
                MinimumsStarView star = find(content, MinimumsStarView.class);
                for (int axis = 0; axis < 4; axis++) {
                    float[] knob = star.knobAt(axis);
                    double angle = Math.toDegrees(Math.atan2(knob[1] - star.skyY(), knob[0] - star.skyX()));
                    assertEquals("spoke " + axis + (byArea ? " by area" : " strictly"), AreaScore.ANGLES[axis],
                            angle, 0.5);
                }
            }
        }
    }

    @Test
    public void byAreaEveryMinimumStandsAtOneRadiusAndAnOffersAreaIsItsScore() {
        FilterStore.save(app, USER.withScoreByArea(true));
        record(USER.withScoreByArea(true), 120_000, 425, 2.1, 16, 2);
        record(USER.withScoreByArea(true), 60_000, 1500, 6.0, 25, 2);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(star.byArea());
            double first = distance(star, star.knobAt(0));
            for (int axis = 1; axis < 4; axis++) {
                assertEquals("every minimum at 100%", first, distance(star, star.knobAt(axis)), 1);
            }
            List<float[]> minimums = star.minimumShape();
            assertEquals("the minimums' area over the four spokes", 4, minimums.size());
            // The newest, $15.00 for 6 mi, 25 min and 2 stops, scores 120.7%: its area is 1.458 times the minimums'.
            assertEquals(1.207480540824083, Math.sqrt(area(star.offerShape(0)) / area(minimums)), 0.002);
            // $4.25 for 2.1 mi, 16 min, 2 stops scores 48.7%.
            assertEquals(0.4869553429377522, Math.sqrt(area(star.offerShape(1)) / area(minimums)), 0.002);
            assertEquals(121, star.emphasizedScore());
            String said = star.getContentDescription().toString();
            assertTrue(said, said.contains("The newest offer scores 121% by area."));
            assertTrue(said, said.contains("An offer like 25 min · 6 mi · 2 stops needs $12.43 to score 100%."));
            assertTrue(star.levelWords().toString(), star.levelWords().contains("100%"));

            // An older offer chosen on the skyline stands out instead.
            DecisionChartView chart = find(content, DecisionChartView.class);
            chart.select(0);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(49, star.emphasizedScore());
            assertTrue(star.getContentDescription().toString().contains("The chosen offer scores 49% by area."));
        }
    }

    @Test
    public void strictlyEveryRecentOfferIsJoinedIntoItsOwnPolygonAsWell() {
        FilterStore.save(app, USER);
        record(USER, 180_000, 1500, 6.0, 25, 2);
        DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis() - 120_000,
                DecisionLog.Source.SCREEN, false, new OfferSnapshot(900, null, 20, 2), 0, OfferRule.Result.REVIEW,
                "an enabled value was not found", DecisionLog.Action.NEEDS_REVIEW, true, Collections.emptyList()));
        record(USER, 60_000, 425, 2.1, 16, 2);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertFalse(star.byArea());
            assertEquals(4, star.offerShape(0).size());
            assertEquals("the distance it did not say is left out", 3, star.offerShape(1).size());
            assertEquals(4, star.offerShape(2).size());
        }
    }

    @Test
    public void byAreaAKnobHoldsTheScaleStillAndEverythingSettlesWhenLetGo() {
        FilterStore.save(app, USER.withScoreByArea(true));
        record(USER.withScoreByArea(true), 60_000, 1500, 6.0, 25, 2);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            Ui ui = new Ui(app);
            float[] pay = star.knobAt(0);
            float[] mile = star.knobAt(1);
            float[] minute = star.knobAt(2);
            List<float[]> offer = star.offerShape(0);
            double radius = distance(star, mile);
            float[] to = alongSpoke(mile, 1, ui.dp(40));
            dragKnob(content, star, mile, to, () -> {
                assertTrue(star.dragging());
                assertArrayEquals("the other knobs hold still", pay, star.knobAt(0), 0.5f);
                assertArrayEquals(minute, star.knobAt(2), 0.5f);
                assertTrue("the held knob goes out", distance(star, star.knobAt(1)) > radius + ui.dp(30));
                List<float[]> still = star.offerShape(0);
                for (int i = 0; i < offer.size(); i++) {
                    assertArrayEquals("the offers hold still", offer.get(i), still.get(i), 0.5f);
                }
                assertTrue("the minimums' polygon follows the knob", distance(star, star.minimumShape().get(1))
                        > radius + ui.dp(30));
                assertTrue("the chosen offer's score follows what the knob asks: " + star.emphasizedScore(),
                        star.emphasizedScore() < 121);
                assertEquals("nothing saved until let go", 385, FilterStore.load(app).perMileCents);
            });
            FilterSettings saved = FilterStore.load(app);
            assertTrue("a higher per-mile minimum: " + saved.perMileCents, saved.perMileCents > 385);
            assertEquals("in $0.05 steps", 0, saved.perMileCents % 5);
            assertArrayEquals(new int[] {1300, saved.perMileCents, 41, 475, 0}, saved.minimums());
            assertTrue(saved.scoreByArea);
            settleSky(content);
            assertEquals("settled: every minimum at one radius again", distance(star, star.knobAt(0)),
                    distance(star, star.knobAt(1)), 1);
            assertEquals(AreaScore.percent(saved, new OfferSnapshot(1500, 6.0, 25, 2)), star.emphasizedScore());
        }
    }

    @Test
    public void theTicketShowsTheScoreInBothModes() {
        FilterStore.save(app, USER);
        record(USER, 60_000, 1200, 4.0, 20, 2);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            find(content, DecisionChartView.class).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            // $12.00 for 4 mi, 20 min, 2 stops: strictly short of $15.40 for the miles; by area 110%.
            assertNotNull(shownText(content, "Score reference · 110%"));
            assertNotNull(shownText(content, "Below your per-mile rate"));
        }
        DecisionLog.clear(app);
        FilterStore.save(app, USER.withScoreByArea(true));
        record(USER.withScoreByArea(true), 60_000, 425, 2.1, 16, 2);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            find(content, DecisionChartView.class).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull("the reason says the score", shownText(content, "Score 49% (needs 100%)"));
            assertNull("and only once", shownText(content, "Score 49%"));
            String card = find(content, OfferCardView.class).getContentDescription().toString();
            assertTrue("the pay that would score 100%: " + card, card.contains("needed $8.73"));
        }
    }

    @Test
    public void historyLinesAndReportsCarryTheScore() throws Exception {
        FilterSettings rules = USER.withScoreByArea(true);
        FilterStore.save(app, rules);
        DecisionLog.Entry entry = record(rules, 60_000, 1500, 6.0, 25, 2);
        assertEquals(121, entry.scorePercent);
        DecisionLog.flush();
        DecisionLog.forgetCache();
        DecisionLog.Entry stored = DecisionLog.recent(app, 1).get(0);
        assertEquals("kept across a restart", 121, stored.scorePercent);
        String history = DecisionLog.report(app, 5);
        assertTrue(history, history.contains(" | KEEP | pay $15.00 | needed $12.43 | score 121% "
                + "| 6 mi · 25 min · 2 stops | score 121% (needs 100%) | "));

        ProblemReport report = ProblemReport.build(ProblemReport.Kind.USER_REPORT, "0.4.41", rules, stored,
                Collections.emptyList(), null, null, Collections.singletonList(stored));
        assertTrue(report.body, report.body.contains("needed $12.43, score 121%"));
        assertTrue(report.body, report.body.contains("**Rules:** scored by area, 100% needed (max stops is a hard "
                + "limit) · $13.00 pay"));
        String json = report.body.substring(report.body.indexOf("{"), report.body.lastIndexOf("}") + 1);
        JSONObject data = new JSONObject(json);
        assertTrue(data.getJSONObject("rules").getBoolean("scoreByArea"));
        assertEquals(121, data.getJSONObject("entry").getInt("score"));

        String shared = DiagnosticLog.report(app);
        assertTrue(shared.contains("; score by area=true"));
        assertTrue(shared.contains("In words: scored by area, 100% needed"));

        // An add-on's line carries none: add-ons keep the strict rules.
        DecisionLog.Entry addOn = DecisionLog.Entry.of(DecisionLog.Source.SCREEN, true,
                new OfferSnapshot(300, 1.0, null, 1), new OfferRule.Decision(OfferRule.Result.KEEP, 0, "x",
                        OfferSnapshot.UNKNOWN, 140), DecisionLog.Action.PASSES, true, Collections.emptyList());
        assertEquals(-1, addOn.scorePercent);
    }

    // ---- Helpers ----

    /** An offer decided by {@code rules} and recorded {@code ago} ms ago. */
    private DecisionLog.Entry record(FilterSettings rules, long ago, int pay, Double miles, Integer minutes,
                                     Integer stops) {
        OfferSnapshot facts = new OfferSnapshot(pay, miles, minutes, stops);
        OfferRule.Decision decision = OfferRule.evaluate(facts, rules);
        DecisionLog.Entry entry = DecisionLog.Entry.of(DecisionLog.Source.SCREEN, false, facts, decision,
                decision.result == OfferRule.Result.DECLINE ? DecisionLog.Action.DECLINE_TAPPED
                        : DecisionLog.Action.PASSES, true,
                Arrays.asList(DecisionLog.money(pay), stops + " stops (" + miles + " mi) • " + minutes + " min"))
                .withTime(System.currentTimeMillis() - ago);
        DecisionLog.record(app, entry);
        return entry;
    }

    private static double distance(MinimumsStarView star, float[] at) {
        return Math.hypot(at[0] - star.skyX(), at[1] - star.skyY());
    }

    /** A polygon's area (the shoelace formula). */
    private static double area(List<float[]> points) {
        double twice = 0;
        for (int i = 0; i < points.size(); i++) {
            float[] a = points.get(i);
            float[] b = points.get((i + 1) % points.size());
            twice += (double) a[0] * b[1] - (double) b[0] * a[1];
        }
        return Math.abs(twice) / 2;
    }

    private static float[] alongSpoke(float[] from, int axis, float by) {
        double angle = Math.toRadians(AreaScore.ANGLES[axis]);
        return new float[] {(float) (from[0] + Math.cos(angle) * by), (float) (from[1] + Math.sin(angle) * by)};
    }

    private static void settleSky(View content) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
        int width = content.getResources().getDisplayMetrics().widthPixels;
        int height = content.getResources().getDisplayMetrics().heightPixels;
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        content.layout(0, 0, width, height);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800));
    }

    private static void dragKnob(View content, MinimumsStarView star, float[] from, float[] to, Runnable whileHeld) {
        int[] starAt = new int[2];
        int[] contentAt = new int[2];
        star.getLocationInWindow(starAt);
        content.getLocationInWindow(contentAt);
        float dx = starAt[0] - contentAt[0];
        float dy = starAt[1] - contentAt[1];
        long now = android.os.SystemClock.uptimeMillis();
        content.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, from[0] + dx, from[1] + dy,
                0));
        for (int step = 1; step <= 12; step++) {
            float x = from[0] + (to[0] - from[0]) * step / 12;
            float y = from[1] + (to[1] - from[1]) * step / 12;
            content.dispatchTouchEvent(MotionEvent.obtain(now, now + step * 16L, MotionEvent.ACTION_MOVE, x + dx,
                    y + dy, 0));
        }
        if (whileHeld != null) whileHeld.run();
        content.dispatchTouchEvent(MotionEvent.obtain(now, now + 300, MotionEvent.ACTION_UP, to[0] + dx, to[1] + dy,
                0));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void tap(ViewGroup parent, float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0);
        parent.dispatchTouchEvent(down);
        parent.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static boolean contains(List<android.graphics.RectF> boxes, float x, float y) {
        for (android.graphics.RectF box : boxes) if (box.contains(x, y)) return true;
        return false;
    }

    private static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = find(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findButton(View view, String text) {
        if (view instanceof Button && text.contentEquals(((Button) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findButton(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** A text exactly {@code text} actually on screen. */
    private static TextView shownText(View view, String text) {
        if (view instanceof TextView && view.isShown() && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = shownText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TextView shownTextContaining(View view, String text) {
        if (view instanceof TextView && view.isShown() && ((TextView) view).getText().toString().contains(text)) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = shownTextContaining(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
}

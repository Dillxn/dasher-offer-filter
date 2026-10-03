package com.local.dasherfilter;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The user's report from score-by-area mode: "where are my adaptive min values? I'm in area mode but should still see
 * the mins plotted". By area every spoke stands at its effective floor (the higher of the set and the learned
 * minimum), filled in the middle; over it, the set minimums are the solid shape through the knobs, at R × set ÷
 * effective, and the learned ones the dashed shape at R × learned ÷ effective. Before, only their stars and sparkles
 * were drawn, with no shape joining them. Nothing learned draws no dashed shape; while the adaptive minimum is on, a
 * faint dashed outline just outside the set shape says it has learned nothing yet. What the chart strokes is recorded
 * as it draws. The rules are the user's: $13 pay, $3.85 a mile, $0.41 a minute, $4.75 a stop, at most 3 stops.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, qualifiers = "w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class AdaptiveShapeByAreaTest extends AndroidAdapterTestBase {
    private static final FilterSettings USER = new FilterSettings(true, 1300, 385, 41, 475, 3, true, 0)
            .withScoreByArea(true);

    /** One outline the chart stroked: its corners, color, and whether it was dashed. */
    private static final class Stroke {
        final List<float[]> points;
        final int color;
        final boolean dashed;

        Stroke(List<float[]> points, int color, boolean dashed) {
            this.points = points;
            this.color = color;
            this.dashed = dashed;
        }
    }

    /** A canvas that keeps every outline stroked on it. */
    private static final class Recorder extends Canvas {
        final List<Stroke> strokes = new ArrayList<>();

        Recorder(Bitmap bitmap) {
            super(bitmap);
        }

        @Override public void drawPath(Path path, Paint paint) {
            if (paint.getStyle() == Paint.Style.STROKE) {
                float[] along = path.approximate(0.5f);
                List<float[]> points = new ArrayList<>();
                for (int i = 0; i + 2 < along.length; i += 3) points.add(new float[] {along[i + 1], along[i + 2]});
                strokes.add(new Stroke(points, paint.getColor(), paint.getPathEffect() != null));
            }
            super.drawPath(path, paint);
        }
    }

    private static List<Stroke> strokes(MinimumsStarView star) {
        Recorder canvas = new Recorder(Bitmap.createBitmap(star.getWidth(), star.getHeight(),
                Bitmap.Config.ARGB_8888));
        star.draw(canvas);
        return canvas.strokes;
    }

    /** The outline stroked in {@code color} (alpha aside) through every one of {@code corners}; null where none is. */
    private static Stroke through(List<Stroke> strokes, int color, boolean dashed, List<float[]> corners) {
        for (Stroke stroke : strokes) {
            if (stroke.dashed != dashed || (stroke.color & 0xFFFFFF) != (color & 0xFFFFFF)) continue;
            boolean all = true;
            for (float[] corner : corners) all &= nearest(stroke, corner) <= 1.5;
            if (all) return stroke;
        }
        return null;
    }

    private static double nearest(Stroke stroke, float[] at) {
        double best = Double.MAX_VALUE;
        for (float[] point : stroke.points) best = Math.min(best, Math.hypot(point[0] - at[0], point[1] - at[1]));
        return best;
    }

    private static List<Stroke> dashedIn(List<Stroke> strokes, int color) {
        List<Stroke> out = new ArrayList<>();
        for (Stroke stroke : strokes) {
            if (stroke.dashed && (stroke.color & 0xFFFFFF) == (color & 0xFFFFFF)) out.add(stroke);
        }
        return out;
    }

    /** The radius the minimums stand at by area, 100%: the middle polygon's. */
    private static double hundred(MinimumsStarView star) {
        List<float[]> middle = star.minimumShape();
        assertEquals("all four spokes have a minimum", 4, middle.size());
        double radius = distance(star, middle.get(0));
        for (float[] point : middle) assertEquals("every floor at 100%", radius, distance(star, point), 1);
        return radius;
    }

    /** Where a minimum asking {@code cents} stands on spoke {@code axis}, its floor asking {@code floor}. */
    private static float[] at(MinimumsStarView star, int axis, double hundred, double cents, double floor) {
        double angle = spokeAngle(axis);
        double out = hundred * cents / floor;
        return new float[] {(float) (star.skyX() + Math.cos(angle) * out), (float) (star.skyY() + Math.sin(angle) * out)};
    }

    private static double distance(MinimumsStarView star, float[] at) {
        return Math.hypot(at[0] - star.skyX(), at[1] - star.skyY());
    }

    private static double floor(MinimumsStarView star, int axis) {
        double set = star.setAsks(axis);
        double learned = star.learnedAsks(axis);
        return Double.isNaN(set) ? learned : Double.isNaN(learned) ? set : Math.max(set, learned);
    }

    private void record(FilterSettings rules, int pay, double miles, int minutes, int stops) {
        OfferSnapshot facts = new OfferSnapshot(pay, miles, minutes, stops);
        OfferRule.Decision decision = OfferRule.evaluate(facts, rules);
        DecisionLog.record(app, DecisionLog.Entry.of(DecisionLog.Source.SCREEN, false, facts, decision,
                decision.result == OfferRule.Result.DECLINE ? DecisionLog.Action.DECLINE_TAPPED
                        : DecisionLog.Action.PASSES, true,
                Arrays.asList(DecisionLog.money(pay), stops + " stops (" + miles + " mi) • " + minutes + " min"))
                .withTime(System.currentTimeMillis() - 60_000));
    }

    /**
     * Accepted $16.00 for 8 mi, 20 min, 2 stops: it asks more than the set pay ($16.01), per minute ($0.80) and per
     * stop ($8.00), and less per mile ($2.00 against $3.85). The example is $15.00 for 6 mi, 25 min, 2 stops.
     */
    private void learnSome() {
        FilterStore.save(app, USER);
        FilterStore.resetAccepted(app);
        FilterStore.recordAccepted(app, new OfferSnapshot(1600, 8.0, 20, 2));
        record(FilterStore.load(app), 1500, 6.0, 25, 2);
    }

    @Test
    public void byAreaTheLearnedMinimumsAreTheDashedShapeAtTheirShareOfEachFloor() {
        learnSome();
        Ui ui = new Ui(app);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(star.byArea());
            assertTrue("per mile, the set minimum asks more", star.setAsks(1) > star.learnedAsks(1));
            assertTrue("pay, the learned one", star.learnedAsks(0) > star.setAsks(0));
            double hundred = hundred(star);
            List<float[]> learned = new ArrayList<>();
            List<float[]> set = new ArrayList<>();
            for (int axis = 0; axis < 4; axis++) {
                learned.add(at(star, axis, hundred, star.learnedAsks(axis), floor(star, axis)));
                set.add(at(star, axis, hundred, star.setAsks(axis), floor(star, axis)));
            }
            List<Stroke> strokes = strokes(star);
            Stroke dashed = through(strokes, ui.learned, true, learned);
            assertNotNull("the learned minimums' dashed shape, at R × learned ÷ effective", dashed);
            assertEquals("drawn in full", 0xFF, Color.alpha(dashed.color));
            assertTrue("per mile it stands inside the middle, at its share of the set floor",
                    distance(star, learned.get(1)) < hundred * 0.6);
            Stroke solid = through(strokes, ui.accent, false, set);
            assertNotNull("the set minimums' solid shape, at R × set ÷ effective", solid);
            assertEquals(0xFF, Color.alpha(solid.color));
            for (int axis = 0; axis < 4; axis++) {
                float[] knob = star.knobAt(axis);
                assertEquals("knob " + axis + " on the set shape", 0, Math.hypot(knob[0] - set.get(axis)[0],
                        knob[1] - set.get(axis)[1]), 1.5);
            }
            String said = star.getContentDescription().toString();
            assertFalse(said, said.contains("nothing learned yet"));
            assertTrue(said, said.contains("Per mile: set $3.85, adaptive $2.00."));
        }
    }

    @Test
    public void byAreaNothingLearnedDrawsNoDashedShapeOnlyAFaintOutlineWhileTheAdaptiveMinimumIsOn() {
        Ui ui = new Ui(app);
        for (boolean adaptive : new boolean[] {true, false}) {
            FilterSettings rules = new FilterSettings(true, 1300, 385, 41, 475, 3, adaptive, 0).withScoreByArea(true);
            FilterStore.save(app, rules);
            FilterStore.resetAccepted(app);
            DecisionLog.clear(app);
            record(rules, 1500, 6.0, 25, 2);
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                View content = activity.get().findViewById(android.R.id.content);
                settleSky(content);
                MinimumsStarView star = find(content, MinimumsStarView.class);
                assertTrue(star.byArea());
                double hundred = hundred(star);
                List<float[]> set = new ArrayList<>();
                for (int axis = 0; axis < 4; axis++) set.add(at(star, axis, hundred, 1, 1));
                List<Stroke> strokes = strokes(star);
                assertNotNull("the set shape, at 100% with nothing learned", through(strokes, ui.accent, false, set));
                List<Stroke> dashed = dashedIn(strokes, adaptive ? ui.learned : 0xFF8E8A9C);
                String said = star.getContentDescription().toString();
                assertTrue(said, said.contains("Adaptive minimum: nothing learned yet."));
                if (!adaptive) {
                    assertTrue("off with nothing learned: nothing dashed", dashed.isEmpty());
                    continue;
                }
                assertEquals("one faint outline, no learned shape", 1, dashed.size());
                Stroke faint = dashed.get(0);
                assertTrue("faint: " + Integer.toHexString(faint.color), Color.alpha(faint.color) < 0x80);
                for (int axis = 0; axis < 4; axis++) {
                    double out = nearestOnSpoke(star, faint, axis) - hundred;
                    assertTrue("just outside the set shape on spoke " + axis + ": " + out,
                            out > 0 && out <= ui.dp(6));
                }
            }
        }
    }

    @Test
    public void byAreaTheKnobsStillSetTheMinimumsWithTheLearnedShapeShown() {
        learnSome();
        Ui ui = new Ui(app);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            float[] pay = star.knobAt(0);
            assertTrue("the pay knob stands inside the middle, at its share of the learned floor",
                    distance(star, pay) < hundred(star) - ui.dp(10));
            // Pay out past what the adaptive minimum asks: dragged along its spoke from where it stands.
            dragKnob(content, star, pay, alongSpoke(pay, 0, ui.dp(70), 0), null);
            FilterSettings saved = FilterStore.load(app);
            assertTrue("a higher minimum pay: " + saved.flatCents, saved.flatCents > 1601);
            assertEquals("in $0.50 steps", 0, saved.flatCents % 50);
            assertEquals("nothing else", Arrays.toString(new int[] {saved.flatCents, 385, 41, 475, 0, 0}),
                    Arrays.toString(saved.minimums()));
            assertEquals("what was learned stays", 1600, saved.lastAcceptedCents);
            settleSky(content);
            double hundred = hundred(star);
            assertEquals("now the set pay is the floor: its knob at 100%", hundred, distance(star, star.knobAt(0)),
                    1.5);
            List<float[]> learned = new ArrayList<>();
            for (int axis = 0; axis < 4; axis++) {
                learned.add(at(star, axis, hundred, star.learnedAsks(axis), floor(star, axis)));
            }
            assertTrue("the learned pay now inside it", distance(star, learned.get(0)) < hundred - 1);
            assertNotNull("the dashed shape follows", through(strokes(star), ui.learned, true, learned));
            assertFalse("no knob is held", star.dragging());
        }
    }

    /** How far out along spoke {@code axis} the outline's corner on that spoke stands. */
    private static double nearestOnSpoke(MinimumsStarView star, Stroke stroke, int axis) {
        double angle = spokeAngle(axis);
        double best = Double.NaN;
        double square = Double.MAX_VALUE;
        for (float[] point : stroke.points) {
            double dx = point[0] - star.skyX();
            double dy = point[1] - star.skyY();
            double out = dx * Math.cos(angle) + dy * Math.sin(angle);
            double aside = Math.abs(-dx * Math.sin(angle) + dy * Math.cos(angle));
            if (out > 0 && aside < square) {
                square = aside;
                best = out;
            }
        }
        return best;
    }
}

package com.local.dasherfilter;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Looper;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.*;

/** The fifth rule is reciprocal destination distance, never money, pickup distance or a guessed hotspot. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, qualifiers = "w411dp-h914dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class HotspotConstellationTest extends AndroidAdapterTestBase {
    private static final FilterSettings RULES = new FilterSettings(true, 1000, 200, 50, 500, 3)
            .withHotspotProximity(50);

    private static OfferSnapshot offer(Double hotspotMiles) {
        return new OfferSnapshot(1200, 5.0, 20, 2).withFinalStopHotspotMiles(hotspotMiles);
    }

    private static DecisionLog.Entry entry(OfferSnapshot offer, FilterSettings rules) {
        return DecisionLog.Entry.of(DecisionLog.Source.SCREEN, false, offer, OfferRule.evaluate(offer, rules),
                DecisionLog.Action.NEEDS_REVIEW, true, Collections.emptyList());
    }

    private MinimumsStarView show(android.app.Activity activity, FilterSettings rules, OfferSnapshot offer) {
        LoneSky sky = new LoneSky(activity, rules);
        sky.star.show(rules, offer, Collections.singletonList(entry(offer, rules)));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        return sky.star;
    }

    @Test public void unavailableHotspotExplainsSavedRuleAndRejectsAllNumericAccessibilityEdits() {
        FilterStore.save(app, RULES);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            AccessibilityNodeInfo knob = node(star, AreaScore.HOTSPOT);
            assertNotNull(knob);
            assertEquals(Button.class.getName(), knob.getClassName());
            assertNull("an unavailable measurement is not an adjustable slider", knob.getRangeInfo());
            assertFalse(knob.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS));
            assertFalse(knob.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD));
            String said = knob.getContentDescription().toString();
            assertTrue(said, said.contains("0.50 inverse miles"));
            assertTrue(said, said.contains("at most 2 miles"));
            assertTrue(said, said.contains("unavailable"));
            assertFalse(said, said.contains("$"));
            assertTrue(said, said.contains("saved rule is still active"));
            assertFalse(act(star, AreaScore.HOTSPOT, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD));
            assertFalse(act(star, AreaScore.HOTSPOT, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD));
            for (float value : new float[] {0, 0.55f, 1}) {
                Bundle arguments = new Bundle();
                arguments.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value);
                assertFalse(star.getAccessibilityNodeProvider().performAction(AreaScore.HOTSPOT,
                        android.R.id.accessibilityActionSetProgress, arguments));
            }
            assertEquals(50, FilterStore.load(app).hotspotProximityHundredths);
            assertArrayEquals(new int[] {1000, 200, 50, 500},
                    Arrays.copyOf(FilterStore.load(app).minimums(), 4));
            assertEquals("0.50/mi · ≤2 mi", MinimumsStarView.readout(AreaScore.HOTSPOT, 50));
        }
    }

    @Test public void hotspotDragNeitherEnablesChangesNorClearsSavedRulesAndTapExplains() {
        try (ActivityController<android.app.Activity> activity = Robolectric.buildActivity(android.app.Activity.class)
                .setup()) {
            for (int existing : new int[] {0, 50}) {
                LoneSky sky = new LoneSky(activity.get(), RULES.withHotspotProximity(existing));
                final int[] explanations = {0};
                sky.star.setChanges(new MinimumsStarView.Changes() {
                    @Override public void setMinimum(int axis, int value) { sky.saves.add(axis + "=" + value); }
                    @Override public int[] adoptLearned() { return null; }
                    @Override public void restore(int[] values) {}
                    @Override public void explainHotspotUnavailable() { explanations[0]++; }
                });
                float[] start = sky.star.knobAt(AreaScore.HOTSPOT);
                sky.swipe(start, new float[] {start[0], start[1] - sky.ui.dp(70)});
                assertTrue("drag out does not enable/increase", sky.saves.isEmpty());
                start = sky.star.knobAt(AreaScore.HOTSPOT);
                sky.swipe(start, new float[] {sky.star.skyX(), sky.star.skyY()});
                assertTrue("drag in does not silently disable", sky.saves.isEmpty());
                assertEquals(0, explanations[0]);
                // Direct dispatch keeps this tap independent of the scroll changed by the preceding drag.
                start = sky.star.knobAt(AreaScore.HOTSPOT);
                long now = android.os.SystemClock.uptimeMillis();
                for (int action : new int[] {android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP}) {
                    android.view.MotionEvent event = android.view.MotionEvent.obtain(now, now, action,
                            start[0], start[1], 0);
                    sky.star.onTouchEvent(event);
                    event.recycle();
                }
                assertEquals(1, explanations[0]);
                assertTrue(act(sky.star, AreaScore.HOTSPOT, AccessibilityNodeInfo.ACTION_CLICK));
                assertEquals(2, explanations[0]);
                assertTrue(sky.saves.isEmpty());
            }
        }
    }

    @Test public void fiveSpokeAreaPolygonMatchesTheScoreWithActualFinalStopDistance() {
        FilterSettings rules = RULES.withScoreByArea(true);
        OfferSnapshot offer = offer(2.0);
        try (ActivityController<android.app.Activity> activity = Robolectric.buildActivity(android.app.Activity.class)
                .setup()) {
            MinimumsStarView star = show(activity.get(), rules, offer);
            assertEquals(5, star.minimumShape().size());
            assertEquals(5, star.offerShape(0).size());
            double score = AreaScore.score(AreaScore.floors(rules, offer), offer.payCents);
            assertEquals(score * score, area(star.offerShape(0)) / area(star.minimumShape()), 0.00001);
            assertTrue(star.minimumShape().get(1)[1] < star.skyY());
            assertEquals(star.skyX(), star.minimumShape().get(1)[0], 0.01);
            assertTrue(star.getContentDescription().toString().contains("Final stop 2 miles from nearest hotspot"));
        }
    }

    @Test public void sparseTopThreeSpokesCloseThroughCenterAndMatchArea() {
        FilterSettings rules = new FilterSettings(true, 1000, 200, 0, 0, 0).withHotspotProximity(50)
                .withScoreByArea(true);
        OfferSnapshot offer = offer(2.0);
        try (ActivityController<android.app.Activity> activity = Robolectric.buildActivity(android.app.Activity.class)
                .setup()) {
            MinimumsStarView star = show(activity.get(), rules, offer);
            List<float[]> minimum = star.minimumShape();
            assertEquals("three spokes plus the center closing the empty lower half", 4, minimum.size());
            assertEquals(star.skyX(), minimum.get(3)[0], 0.001);
            assertEquals(star.skyY(), minimum.get(3)[1], 0.001);
            double score = AreaScore.score(AreaScore.floors(rules, offer), offer.payCents);
            assertEquals(score * score, area(star.offerShape(0)) / area(minimum), 0.00001);
        }
    }

    @Test public void unknownDistanceIsNotPlottedAndExactZeroRemainsKnown() {
        FilterSettings rules = RULES.withScoreByArea(true);
        try (ActivityController<android.app.Activity> activity = Robolectric.buildActivity(android.app.Activity.class)
                .setup()) {
            MinimumsStarView star = show(activity.get(), rules, offer(null));
            assertEquals("all four original observed facts, no invented fifth point", 4, star.offerShape(0).size());
            assertEquals(-1, AreaScore.percent(rules, offer(null)));
            assertTrue(star.caption(), star.caption().contains("distance from the nearest hotspot"));
            assertTrue(node(star, AreaScore.HOTSPOT).getContentDescription().toString().contains("unavailable"));
            star.show(rules, offer(0.0), Collections.singletonList(entry(offer(0.0), rules)));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
            assertEquals(5, star.offerShape(0).size());
            for (float[] at : star.offerShape(0)) {
                assertTrue(Float.isFinite(at[0]));
                assertTrue(Float.isFinite(at[1]));
            }
            assertTrue(node(star, AreaScore.HOTSPOT).getContentDescription().toString().contains("at nearest hotspot"));
            assertTrue(Double.isNaN(star.learnedAsks(AreaScore.HOTSPOT)));
        }
    }

    @Test public void cardShowsOnlyKnownFinalStopDistance() {
        OfferCardView card = new OfferCardView(app, new Ui(app));
        card.show(entry(offer(null), RULES));
        assertFalse(card.getContentDescription().toString().contains("hotspot"));
        card.show(entry(offer(2.0), RULES));
        assertTrue(card.getContentDescription().toString().contains("Final stop 2 miles from nearest hotspot"));
        card.show(entry(offer(0.0), RULES));
        assertTrue(card.getContentDescription().toString().contains("at nearest hotspot"));
        assertEquals("<0.01", MinimumsStarView.distanceText(0.001));
    }

    @Test @Config(sdk = 35) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void phoneLayoutKeepsTopSpokeAccessibleAndExportsNativePreview() throws Exception {
        renderLayout(false, "phone");
    }

    @Test @Config(sdk = 35, qualifiers = "w411dp-h410dp-420dpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void besideDasherKeepsTopSpokeAccessibleAndExportsNativePreview() throws Exception {
        renderLayout(true, "split");
    }

    private void renderLayout(boolean split, String name) throws Exception {
        FilterSettings rules = RULES.withScoreByArea(true);
        FilterStore.save(app, rules);
        DecisionLog.record(app, entry(offer(2.0), rules));
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        if (split) OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        if (split) Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue("the editable constellation remains in the sky", star.backdrop());
            Bitmap bitmap = Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
            content.draw(new Canvas(bitmap));
            File dir = new File("build/reports/hotspot-ui");
            assertTrue(dir.isDirectory() || dir.mkdirs());
            try (FileOutputStream file = new FileOutputStream(new File(dir, name + ".png"))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, file));
            }
            bitmap.recycle();
            Rect target = new Rect();
            node(star, AreaScore.HOTSPOT).getBoundsInParent(target);
            assertTrue(target.toString(), target.top >= 0 && target.bottom <= star.getHeight()
                    && target.left >= 0 && target.right <= star.getWidth());
            FilterHeroView hero = find(content, FilterHeroView.class);
            RectF counts = new RectF();
            hero.countsAt(counts);
            counts.offset(hero.getLeft(), hero.getTop());
            assertFalse("top knob clear of counts " + counts, RectF.intersects(new RectF(target), counts));
            java.util.ArrayList<RectF> icons = new java.util.ArrayList<>();
            star.iconsAt(icons);
            assertTrue("all five metric icons are drawn", icons.size() >= AreaScore.AXES);
            RectF stopIcon = icons.get(AreaScore.STOP);
            Ui ui = new Ui(app);
            for (int axis = 0; axis < AreaScore.AXES; axis++) {
                float[] knob = star.knobAt(axis);
                float dx = Math.max(0, Math.max(stopIcon.left - knob[0], knob[0] - stopIcon.right));
                float dy = Math.max(0, Math.max(stopIcon.top - knob[1], knob[1] - stopIcon.bottom));
                assertTrue("per-stop icon stays clear of painted knob " + axis, Math.hypot(dx, dy) >= ui.dp(12));
            }
            RectF stopBadge = star.stopsBox();
            assertNotNull(stopBadge);
            assertTrue("max stops remains beside the per-stop icon", Math.hypot(stopBadge.centerX()
                    - stopIcon.centerX(), stopBadge.centerY() - stopIcon.centerY()) <= ui.dp(80));
            for (RectF button : new RectF[] {star.scoreToggleBox(), star.adaptiveBox(), star.stopsBox()}) {
                assertNotNull("all controls shown: score=" + star.scoreToggleBox() + ", adaptive="
                        + star.adaptiveBox() + ", stops=" + star.stopsBox(), button);
                assertFalse("top knob clear of controls " + button, RectF.intersects(new RectF(target), button));
            }
        } finally {
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    private static double area(List<float[]> polygon) {
        double twice = 0;
        for (int i = 0; i < polygon.size(); i++) {
            float[] a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            twice += (double) a[0] * b[1] - (double) b[0] * a[1];
        }
        return Math.abs(twice) / 2;
    }
}

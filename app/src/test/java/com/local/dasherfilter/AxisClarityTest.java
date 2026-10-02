package com.local.dasherfilter;

import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.*;

/** Spoke names must explain their units and leave the actual minimum controls usable. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, qualifiers = "w411dp-h914dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class AxisClarityTest extends AndroidAdapterTestBase {
    private static final FilterSettings RULES = new FilterSettings(true, 1000, 200, 50, 500, 3)
            .withHotspotProximity(50);

    @Test public void expandedConstellationNamesEveryMeasureWithoutCollidingWithOtherNames() {
        FilterStore.save(app, RULES);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = controller.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(star.backdrop());
            assertNamedAxes(star);
            String said = star.getContentDescription().toString().toLowerCase(java.util.Locale.US);
            assertTrue("the chart explains how to read direction: " + said, said.contains("farther out"));
            assertTrue("the chart distinguishes saved and learned shapes: " + said,
                    said.contains("solid") && said.contains("learned") && said.contains("offers"));
        }
    }

    @Test public void spokenValuesDistinguishSavedRatesFromLearnedRatesAndTheIndependentHotspotUnit() {
        FilterSettings learning = new FilterSettings(true, 700, 150, 30, 100, 3, true, 0)
                .withHotspotProximity(50);
        FilterStore.save(app, learning);
        assertTrue(FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2)));
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = controller.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            String mile = node(star, AreaScore.MILE).getContentDescription().toString();
            assertTrue(mile, mile.contains("per mile") && mile.contains("$1.50"));
            assertTrue(mile, mile.contains("adaptive $2.37") && mile.contains("learned"));
            String minute = node(star, AreaScore.MINUTE).getContentDescription().toString();
            assertTrue(minute, minute.contains("per minute") && minute.contains("$0.30"));
            String stop = node(star, AreaScore.STOP).getContentDescription().toString();
            assertTrue(stop, stop.contains("per stop") && stop.contains("$1.00"));
            String hotspot = node(star, AreaScore.HOTSPOT).getContentDescription().toString();
            assertTrue(hotspot, hotspot.contains("0.50 inverse miles") && hotspot.contains("at most 2 miles"));
            assertTrue(hotspot, hotspot.contains("No adaptive minimum"));
            assertFalse("hotspot proximity is never money", hotspot.contains("$"));
        }
    }

    @Test public void visibleAxisNamesDoNotStealAnyOfTheFiveKnobDrags() {
        FilterStore.save(app, RULES);
        // With setup complete the lower spokes have no foreground warning over them. Such a warning deliberately
        // owns its text area; this exercises all five visible knobs with the new labels present.
        org.robolectric.android.controller.ServiceController<OfferFilterService> service =
                Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        org.robolectric.android.controller.ServiceController<OfferNotificationService> listener =
                Robolectric.buildService(OfferNotificationService.class).create();
        listener.get().onListenerConnected();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = controller.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            for (int axis : AreaScore.DRAW_ORDER) {
                // Each preceding save may change the chart scale and move the remaining knobs. Exercise a visible,
                // settled control, rather than starting the next gesture during the previous scale's transition.
                settleSky(content);
                assertNotNull("the named measure remains visible", star.axisLabelBox(axis));
                int[] before = FilterStore.load(app).minimums();
                float[] from = star.knobAt(axis);
                assertNotNull(from);
                double angle = Math.toRadians(AreaScore.ANGLES[axis]);
                float distance = new Ui(app).dp(45);
                float[] to = new float[] {from[0] + (float) Math.cos(angle) * distance,
                        from[1] + (float) Math.sin(angle) * distance};
                dragKnob(content, star, from, to, null);
                int[] after = FilterStore.load(app).minimums();
                assertTrue("drag reaches named axis " + axis, after[axis] > before[axis]);
                for (int other = 0; other < AreaScore.AXES; other++) {
                    if (other != axis) assertEquals("only the chosen spoke changes", before[other], after[other]);
                }
            }
        } finally {
            listener.destroy();
            service.destroy();
        }
    }

    @Test @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void compactHeaderKeepsItsTapToExpandAndThenShowsAllFiveNames() {
        FilterStore.save(app, RULES);
        OfferFilterService.sawDasherBeside(0);
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> controller = built.setup()) {
            View content = controller.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(star.beside());
            for (int axis = 0; axis < AreaScore.AXES; axis++) {
                assertNull("the compact header does not cram in expanded labels", star.axisLabelBox(axis));
                assertNull("the header is a single expand target", star.knobAt(axis));
            }
            tap((ViewGroup) star.getParent(), star.getLeft() + star.getWidth() * 0.6f,
                    star.getTop() + star.getHeight() / 2f);
            settleSky(content);
            assertTrue("the original tap still expands the constellation", star.backdrop());
            assertNamedAxes(star);
            assertEquals(View.GONE, find(content, AreaMapView.class).getVisibility());
            assertArrayEquals("opening the legend never changes a minimum", RULES.minimums(),
                    FilterStore.load(app).minimums());
        } finally {
            OfferFilterService.sawDasherBeside(0);
        }
    }

    private static void assertNamedAxes(MinimumsStarView star) {
        String[] expected = {"Payout $", "Pay / mile", "Pay / min", "Pay / stop", "Near hotspot"};
        List<RectF> labels = new ArrayList<>();
        for (int axis = 0; axis < AreaScore.AXES; axis++) {
            assertEquals("the visible name identifies the actual metric", expected[axis], star.axisLabelText(axis));
            RectF label = star.axisLabelBox(axis);
            assertNotNull("visible label for " + expected[axis], label);
            assertTrue("label stays on screen: " + expected[axis] + " " + label,
                    label.left >= 0 && label.top >= 0 && label.right <= star.getWidth()
                            && label.bottom <= star.getHeight() && label.width() > 0 && label.height() > 0);
            for (RectF previous : labels) assertFalse("axis names must not overwrite one another",
                    RectF.intersects(previous, label));
            labels.add(label);
        }
    }
}

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

/**
 * Spoke names must explain their units and leave the actual minimum controls usable: pay, per mile and per hour (the
 * clock, per minute shown × 60), and max stops by the pin; the retired hotspot and per-item spokes have no name, icon,
 * knob or node.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, qualifiers = "w411dp-h914dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class AxisClarityTest extends AndroidAdapterTestBase {
    private static final FilterSettings RULES = FilterSettings.of(true, 1000, 200, 50, 3);

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
            assertTrue("the chart tells the set minimums from Autopilot's shape and the offers: " + said,
                    said.contains("solid") && said.contains("dashed") && said.contains("autopilot")
                            && said.contains("offers"));
            assertFalse("no learned shape any more: " + said, said.contains("learned"));
        }
    }

    @Test public void spokenValuesNameEachMinimumInItsOwnUnitAndPerHourInDollarsAnHour() {
        FilterStore.save(app, FilterSettings.of(true, 700, 150, 30, 3));
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = controller.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertEquals("Minimum pay, $7.00", node(star, AreaScore.PAY).getContentDescription().toString());
            assertEquals("Minimum per mile, $1.50", node(star, AreaScore.MILE).getContentDescription().toString());
            android.view.accessibility.AccessibilityNodeInfo hour = node(star, AreaScore.MINUTE);
            assertEquals("30 cents a minute is said as 18 dollars an hour",
                    "Minimum per hour of trip time, 18 dollars per hour", hour.getContentDescription().toString());
            assertEquals("its range is in dollars an hour", 18f, hour.getRangeInfo().getCurrent(), 0.001f);
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                assertEquals("$18/hr", String.valueOf(hour.getStateDescription()));
            }
            for (int retired : new int[] {AreaScore.STOP, AreaScore.HOTSPOT, AreaScore.ITEM}) {
                assertNull("no knob for a retired minimum: " + retired, node(star, retired));
            }
            String said = star.getContentDescription().toString();
            assertTrue(said, said.contains("Your minimums: pay $7.00, per mile $1.50, per hour of trip time 18 dollars "
                    + "per hour; at most 3 stops."));
            for (String retired : new String[] {"adaptive", "learned", "per stop", "hotspot", "per item", "per minute"}) {
                assertFalse(retired + ": " + said, said.toLowerCase(java.util.Locale.US).contains(retired));
            }
        }
    }

    @Test public void visibleAxisNamesKeepEveryKnobsDragAndOnlyItsMinimumChanges() {
        FilterStore.save(app, RULES);
        // With setup complete the lower spoke has no foreground warning over it; this exercises the three knobs with
        // their names present.
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
            for (int axis : MinimumsStarView.SPOKES) {
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

    /**
     * Half of a split screen with another app: the constellation, wholly there beside the map, names every spoke as on
     * a whole screen (0.5.1 put a compact copy in the header there, its names hidden until a tap spread it out).
     */
    @Test @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void halfOfASplitScreenNamesEverySpokeBesideTheMap() {
        FilterStore.save(app, RULES);
        OfferFilterService.sawDasherBeside(0);
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> controller = built.setup()) {
            View content = controller.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertEquals("wholly there", 1f, find(content, ScenePage.class).shown(star), 0f);
            assertTrue("as the sky", star.backdrop());
            assertNamedAxes(star);
            AreaMapView map = find(content, AreaMapView.class);
            assertTrue("beside the map", map.isShown() && star.getRight() <= map.getLeft());
            assertArrayEquals("opening the legend never changes a minimum", RULES.minimums(),
                    FilterStore.load(app).minimums());
        } finally {
            OfferFilterService.sawDasherBeside(0);
        }
    }

    private static void assertNamedAxes(MinimumsStarView star) {
        String[] expected = {"Payout $", "Pay / mile", "Pay / hour", "Max stops", "", ""};
        List<RectF> labels = new ArrayList<>();
        for (int axis = 0; axis < AreaScore.AXES; axis++) {
            assertEquals("the visible name identifies the actual metric", expected[axis], star.axisLabelText(axis));
            RectF label = star.axisLabelBox(axis);
            if (expected[axis].isEmpty()) {
                assertNull("a retired spoke has no name: " + axis, label);
                continue;
            }
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

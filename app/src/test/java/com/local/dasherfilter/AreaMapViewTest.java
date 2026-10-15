package com.local.dasherfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowSystemClock;

/**
 * The map as drawn: its land fades toward every edge, its compass and scale stay crisp, and names never collide. A drag
 * moves the land with the finger and it springs back into place when let go (0.6.22).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36}, qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AreaMapViewTest {
    private Application app;
    private Ui ui;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        ui = new Ui(app);
    }

    /** A ranked square: three offers over {@code miles} paying {@code cents} in all. */
    private static AreaMap.Cell square(int row, int col, int cents, double miles) {
        AreaMap.Cell cell = new AreaMap.Cell(row, col);
        cell.offers = 3;
        cell.payCents = cents;
        cell.mileOffers = 3;
        cell.milePayCents = cents;
        cell.miles = miles;
        cell.bestPayCents = cents / 3;
        cell.lastAt = System.currentTimeMillis();
        return cell;
    }

    private Bitmap draw(AreaMapView map, int widthDp, int heightDp) {
        int width = ui.dp(widthDp);
        int height = ui.dp(heightDp);
        map.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        map.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        map.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static int maxAlpha(Bitmap bitmap, int left, int top, int right, int bottom) {
        int most = 0;
        for (int x = Math.max(0, left); x < Math.min(bitmap.getWidth(), right); x++) {
            for (int y = Math.max(0, top); y < Math.min(bitmap.getHeight(), bottom); y++) {
                most = Math.max(most, bitmap.getPixel(x, y) >>> 24);
            }
        }
        return most;
    }

    @Test
    public void theLandFadesTowardEveryEdgeWhileTheCompassAndScaleStayCrisp() {
        AreaMapView map = new AreaMapView(app, ui);
        List<AreaMap.Cell> cells = Arrays.asList(square(1888, -6121, 1500, 5.0), square(1886, -6124, 1000, 5.0));
        map.show(cells, new double[] {1886.5 * AreaMap.CELL_DEGREES, -6123.5 * AreaMap.CELL_DEGREES});
        Bitmap bitmap = draw(map, 360, 200);
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int one = ui.dp(1);

        // The grid runs right across the map: full strength inside, next to nothing at the very edges.
        int inside = maxAlpha(bitmap, ui.dp(60), ui.dp(40), ui.dp(61), height - ui.dp(40));
        int leftEdge = maxAlpha(bitmap, 0, ui.dp(40), 1, height - ui.dp(40));
        int rightEdge = maxAlpha(bitmap, width - 1, ui.dp(60), width, height - ui.dp(40));
        int topEdge = maxAlpha(bitmap, ui.dp(40), 0, width / 2, 1);
        assertTrue("the grid shows inside: " + inside, inside > 20);
        assertTrue("faded at the left edge: " + leftEdge + " vs " + inside, leftEdge * 3 < inside);
        assertTrue("faded at the right edge: " + rightEdge + " vs " + inside, rightEdge * 3 < inside);
        assertTrue("faded at the top edge: " + topEdge + " vs " + inside, topEdge * 3 < inside);

        // The scale bar sits in the faded corner but is drawn after the fade: it stays solid.
        int scaleBar = maxAlpha(bitmap, ui.dp(18) - one, height - ui.dp(18) - one, ui.dp(18) + 2 * one,
                height - ui.dp(18) + 2 * one);
        assertTrue("the scale bar stays crisp: " + scaleBar, scaleBar >= 240);
        // So does the compass in the top-right corner.
        int compass = maxAlpha(bitmap, width - ui.dp(60), 0, width, ui.dp(50));
        assertTrue("the compass stays crisp: " + compass, compass >= 240);
    }

    @Test
    public void theGridRunsUnderTheKeyAndTheInfoColumnWithNoFrameRoundIt() {
        AreaMapView map = new AreaMapView(app, ui);
        List<AreaMap.Cell> cells = Arrays.asList(square(1888, -6121, 1500, 5.0), square(1886, -6124, 1000, 5.0));
        map.show(cells, new double[] {1886.5 * AreaMap.CELL_DEGREES, -6123.5 * AreaMap.CELL_DEGREES});
        Bitmap bitmap = draw(map, 360, 200);
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int one = ui.dp(1);

        // 0.6.21 (the owner: "why does the grid ... not extend to the top and right of its container?"): the land runs
        // into the right-hand column the ⓘ keeps, under the ⓘ ...
        int infoColumn = maxAlpha(bitmap, width - ui.dp(40), ui.dp(60), width - ui.dp(30), height - ui.dp(40));
        assertTrue("the grid runs into the ⓘ's column: " + infoColumn, infoColumn > 20);
        // ... and into the key's row at the top, fading toward the edge.
        int keyRow = maxAlpha(bitmap, ui.dp(40), ui.dp(14), width / 2, ui.dp(19));
        assertTrue("the grid runs into the key's row: " + keyRow, keyRow > 5);
        // No frame round it ("remove its container's outline"): only the faded land is left 3 dp in from its edges.
        int topFrame = maxAlpha(bitmap, ui.dp(40), ui.dp(3) - one, width / 2, ui.dp(3) + 2 * one);
        int leftFrame = maxAlpha(bitmap, ui.dp(3) - one, ui.dp(60), ui.dp(3) + 2 * one, height - ui.dp(60));
        assertTrue("no frame along the top: " + topFrame, topFrame < 20);
        assertTrue("no frame down the left: " + leftFrame, leftFrame < 20);
    }

    @Test
    public void placeNamesThatWouldOverlapAreNotDrawnOverEachOther() {
        AreaMapView map = new AreaMapView(app, ui);
        AreaMap.Cell best = square(1888, -6121, 1500, 5.0);
        AreaMap.Cell nextDoor = square(1888, -6120, 1200, 5.0);
        Map<String, String> names = new HashMap<>();
        names.put(AreaMapView.key(best), "Celeron Avenue");
        names.put(AreaMapView.key(nextDoor), "Kenwood Drive");
        map.setNames(names, null);
        map.show(Arrays.asList(best, nextDoor), new double[] {1888.5 * AreaMap.CELL_DEGREES,
                -6121.5 * AreaMap.CELL_DEGREES});
        draw(map, 360, 200);
        assertEquals("side by side, only the best square's name", 1, map.namesShown());

        AreaMap.Cell farAway = square(1888, -6116, 1200, 5.0);
        names.put(AreaMapView.key(farAway), "Kenwood Drive");
        map.setNames(names, null);
        map.show(Arrays.asList(best, farAway), new double[] {1888.5 * AreaMap.CELL_DEGREES,
                -6121.5 * AreaMap.CELL_DEGREES});
        draw(map, 360, 200);
        assertEquals("apart, still only the best square's name (0.6.8: only the named square)", 1, map.namesShown());
    }

    /** One finger event at ({@code x}, {@code y}) in {@code view}, as the screen delivers it. */
    private static void finger(View view, int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }

    /** Just past the touch slop, where a drag starts. */
    private float past() {
        return ViewConfiguration.get(app).getScaledTouchSlop() + ui.dp(4);
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Android's "animator duration scale" setting; 0 is "remove animations". */
    private static void setAnimatorDurationScale(float scale) throws Exception {
        ValueAnimator.class.getMethod("setDurationScale", float.class).invoke(null, scale);
    }

    @Test
    public void aDragMovesTheLandWithTheFingerAndItSpringsBackIntoPlace() {
        AreaMapView map = new AreaMapView(app, ui);
        AreaMap.Cell best = square(1888, -6121, 1500, 5.0);
        map.show(Arrays.asList(best, square(1886, -6124, 1000, 5.0)),
                new double[] {1886.5 * AreaMap.CELL_DEGREES, -6123.5 * AreaMap.CELL_DEGREES});
        draw(map, 360, 200);
        RectF home = map.areaBounds(best);
        float x = ui.dp(180);
        float y = ui.dp(100);
        float past = past();

        // 0.6.22 (the owner: "i want to be able to drag the map on our app and it spring back into place on release").
        // Past the touch slop the drag starts; from there the land goes the finger's way, all of it at first and a
        // little less the farther it goes: 60 dp right and 30 down moves it about 47 and 23.
        finger(map, MotionEvent.ACTION_DOWN, x, y);
        finger(map, MotionEvent.ACTION_MOVE, x + past, y);
        assertEquals("it starts where the drag does, without a jump", 0, map.pulled().x, 0.01);
        finger(map, MotionEvent.ACTION_MOVE, x + past + ui.dp(60), y + ui.dp(30));
        RectF held = map.areaBounds(best);
        float right = held.left - home.left;
        float down = held.top - home.top;
        assertTrue("it goes with the finger, giving a little: " + right / ui.dp(1) + " dp",
                right > ui.dp(40) && right < ui.dp(60));
        assertEquals("the finger's way", 0.5, down / right, 0.01);
        assertEquals("the square moved as far as the land", map.pulled().x, right, 1);

        // Let go, it heads back; caught on the way, it is held where it was, without a jump.
        finger(map, MotionEvent.ACTION_UP, x + past + ui.dp(60), y + ui.dp(30));
        assertTrue(map.springing());
        ShadowSystemClock.advanceBy(Duration.ofMillis(60));
        PointF caught = map.pulled();
        assertTrue("on its way back: " + caught.x, caught.x > 0 && caught.x < right - ui.dp(5));
        finger(map, MotionEvent.ACTION_DOWN, x, y);
        finger(map, MotionEvent.ACTION_MOVE, x, y + past);
        assertFalse(map.springing());
        assertEquals("held where it was caught", caught.x, map.pulled().x, 1);
        assertEquals(caught.y, map.pulled().y, 1);

        // Let go again: it passes its place a little, like a spring, comes back and rests exactly where it was.
        finger(map, MotionEvent.ACTION_MOVE, x + ui.dp(40), y + past);
        finger(map, MotionEvent.ACTION_UP, x + ui.dp(40), y + past);
        float from = map.pulled().x;
        float most = 0;
        for (long t = 0; t < AreaMapView.SPRING_MS; t += 10) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(10));
            most = Math.min(most, map.pulled().x);
        }
        assertTrue("past its place: " + most + " of " + from, most < -ui.dp(1));
        assertTrue("but only a little: " + most + " of " + from, most > -from * 0.2f);
        ShadowSystemClock.advanceBy(Duration.ofMillis(16));
        assertFalse(map.springing());
        assertEquals(new PointF(0, 0), map.pulled());
        assertEquals("back in its place", home, map.areaBounds(best));
    }

    @Test
    public void aDragIsNeverATapOnASquareTheInfoOrTheMapItself() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            AreaMapView map = new AreaMapView(activity, ui);
            List<AreaMap.Cell> picked = new ArrayList<>();
            int[] taps = {0};
            map.setOnSelect(picked::add);
            map.setOnClickListener(view -> taps[0]++);
            activity.setContentView(map, new ViewGroup.LayoutParams(ui.dp(360), ui.dp(200)));
            AreaMap.Cell other = square(1886, -6124, 1000, 5.0);
            map.show(Arrays.asList(square(1888, -6121, 1500, 5.0), other),
                    new double[] {1886.5 * AreaMap.CELL_DEGREES, -6123.5 * AreaMap.CELL_DEGREES});
            draw(map, 360, 200);

            // Across a square: nothing picked, and not the map's own tap.
            RectF at = map.areaBounds(other);
            float past = past();
            finger(map, MotionEvent.ACTION_DOWN, at.centerX(), at.centerY());
            finger(map, MotionEvent.ACTION_MOVE, at.centerX() + past, at.centerY());
            finger(map, MotionEvent.ACTION_MOVE, at.centerX() + past + ui.dp(28), at.centerY() + ui.dp(10));
            finger(map, MotionEvent.ACTION_UP, at.centerX() + past + ui.dp(28), at.centerY() + ui.dp(10));
            idle();
            assertTrue(picked.isEmpty());
            assertEquals(0, taps[0]);

            // From the ⓘ and back onto it: its key stays shut.
            RectF info = map.helpBounds();
            finger(map, MotionEvent.ACTION_DOWN, info.centerX(), info.centerY());
            finger(map, MotionEvent.ACTION_MOVE, info.centerX() - ui.dp(40), info.centerY() + ui.dp(20));
            finger(map, MotionEvent.ACTION_MOVE, info.centerX(), info.centerY());
            finger(map, MotionEvent.ACTION_UP, info.centerX(), info.centerY());
            idle();
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
            assertEquals(0, taps[0]);

            // A finger that wobbles less than the touch slop still taps: the square is picked, the tap is the map's.
            ShadowSystemClock.advanceBy(Duration.ofMillis(AreaMapView.SPRING_MS + 16));
            at = map.areaBounds(other);
            float wobble = ViewConfiguration.get(app).getScaledTouchSlop() / 3f;
            finger(map, MotionEvent.ACTION_DOWN, at.centerX(), at.centerY());
            finger(map, MotionEvent.ACTION_MOVE, at.centerX() + wobble, at.centerY() + wobble);
            finger(map, MotionEvent.ACTION_UP, at.centerX() + wobble, at.centerY() + wobble);
            idle();
            assertEquals(1, picked.size());
            assertEquals(other.row, picked.get(0).row);
            assertEquals(other.col, picked.get(0).col);
            assertEquals(1, taps[0]);

            // With nothing mapped, a drag is still not the tap that asks for location; a tap still is.
            map.setEmptyMessage("Tap to allow location");
            map.show(Collections.emptyList(), null);
            finger(map, MotionEvent.ACTION_DOWN, ui.dp(180), ui.dp(100));
            finger(map, MotionEvent.ACTION_MOVE, ui.dp(140), ui.dp(110));
            finger(map, MotionEvent.ACTION_UP, ui.dp(140), ui.dp(110));
            idle();
            assertEquals(1, taps[0]);
            finger(map, MotionEvent.ACTION_DOWN, ui.dp(180), ui.dp(100));
            finger(map, MotionEvent.ACTION_UP, ui.dp(180), ui.dp(100));
            idle();
            assertEquals(2, taps[0]);
        }
    }

    @Test
    public void thePageRoundTheMapLeavesADraggedFingerToIt() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            AreaMapView map = new AreaMapView(activity, ui);
            map.show(Collections.singletonList(square(1888, -6121, 1500, 5.0)), null);
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            column.addView(map, new LinearLayout.LayoutParams(ui.dp(360), ui.dp(200)));
            column.addView(new View(activity), new LinearLayout.LayoutParams(ui.dp(360), ui.dp(2000)));
            ScrollView page = new ScrollView(activity);
            page.addView(column);
            activity.setContentView(page);
            page.measure(View.MeasureSpec.makeMeasureSpec(ui.dp(360), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(ui.dp(780), View.MeasureSpec.EXACTLY));
            page.layout(0, 0, ui.dp(360), ui.dp(780));

            // Started sideways, the drag is the map's: going on down, the page that could scroll does not.
            float x = ui.dp(180);
            float y = ui.dp(100);
            float past = past();
            finger(page, MotionEvent.ACTION_DOWN, x, y);
            finger(page, MotionEvent.ACTION_MOVE, x + past, y);
            finger(page, MotionEvent.ACTION_MOVE, x + past + ui.dp(8), y + ui.dp(60));
            finger(page, MotionEvent.ACTION_MOVE, x + past + ui.dp(8), y + ui.dp(120));
            assertEquals("the page stayed put", 0, page.getScrollY());
            assertTrue("the map has the finger: " + map.pulled().y, map.pulled().y > ui.dp(60));
            finger(page, MotionEvent.ACTION_UP, x + past + ui.dp(8), y + ui.dp(120));
            assertTrue(map.springing());
            idle();
        }
    }

    @Test
    public void theEmptyFieldsGoWithTheFingerToo() {
        AreaMapView map = new AreaMapView(app, ui);
        map.setEmptyMessage("Tap to allow location");
        map.show(Collections.emptyList(), null);
        Bitmap rest = draw(map, 360, 200);
        // Below the words: a field boundary at rest (every 56 dp, centred), and none 1 dp either side of where it was.
        int top = ui.dp(140);
        int bottom = ui.dp(170);
        int step = ui.dp(56);
        int boundary = (rest.getWidth() % step) / 2 + 2 * step;
        assertTrue(maxAlpha(rest, boundary - 2, top, boundary + 3, bottom) > 20);

        float x = ui.dp(180);
        float y = ui.dp(100);
        finger(map, MotionEvent.ACTION_DOWN, x, y);
        finger(map, MotionEvent.ACTION_MOVE, x + past(), y);
        finger(map, MotionEvent.ACTION_MOVE, x + past() + ui.dp(40), y);
        int moved = Math.round(map.pulled().x);
        assertTrue("pulled: " + moved, moved > ui.dp(20));
        Bitmap pulled = draw(map, 360, 200);
        assertTrue("the boundary went with the finger",
                maxAlpha(pulled, boundary + moved - 2, top, boundary + moved + 3, bottom) > 20);
        assertEquals("and is gone from where it stood", 0, maxAlpha(pulled, boundary - 2, top, boundary + 3, bottom));
    }

    @Test
    public void withAnimationsOffTheLandStillFollowsAndIsBackInPlaceAtOnce() throws Exception {
        setAnimatorDurationScale(0f);
        try {
            AreaMapView map = new AreaMapView(app, ui);
            AreaMap.Cell best = square(1888, -6121, 1500, 5.0);
            map.show(Collections.singletonList(best), null);
            draw(map, 360, 200);
            RectF home = map.areaBounds(best);
            float x = ui.dp(180);
            float y = ui.dp(100);
            finger(map, MotionEvent.ACTION_DOWN, x, y);
            finger(map, MotionEvent.ACTION_MOVE, x, y + past());
            finger(map, MotionEvent.ACTION_MOVE, x, y + past() + ui.dp(30));
            assertTrue("following a finger is not an animation", map.pulled().y > ui.dp(20));
            finger(map, MotionEvent.ACTION_UP, x, y + past() + ui.dp(30));
            assertFalse(map.springing());
            assertEquals(new PointF(0, 0), map.pulled());
            assertEquals(home, map.areaBounds(best));
        } finally {
            setAnimatorDurationScale(1f);
        }
    }
}

package com.local.dasherfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** The map as drawn: its land fades toward every edge, its compass and scale stay crisp, and names never collide. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35, 36}, qualifiers = "w360dp-h780dp-xxhdpi")
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
        assertEquals("apart, both names", 2, map.namesShown());
    }
}

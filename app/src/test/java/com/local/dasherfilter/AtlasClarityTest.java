package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;
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
import org.robolectric.shadows.ShadowAlertDialog;

/** The atlas names its evidence and its key without making receiving areas look like Dasher hotspots. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, qualifiers = "w411dp-h914dp-xxhdpi")
public class AtlasClarityTest extends AndroidAdapterTestBase {
    @Test public void atlasDescribesPooledRatesSampleGateAndGeographicMeaning() {
        AreaMapView map = new AreaMapView(app, new Ui(app));
        AreaMap.Cell best = square(1801, -6100, 4, 3600, 12);
        AreaMap.Cell next = square(1800, -6100, 3, 2400, 12);
        AreaMap.Cell sparse = square(1800, -6101, 2, 20_000, 2);
        map.show(Arrays.asList(sparse, next, best), new double[] {36.01, -121.99});
        String description = map.getContentDescription().toString();
        assertEquals("a high rate from two samples still cannot win", 0, map.rank(sparse));
        assertEquals(1, map.rank(best));
        assertTrue(description, description.contains("$3.00/mi over 4 offers"));
        assertTrue(description, description.contains("total offered pay divided by total offer miles"));
        assertTrue(description, description.contains("fewer than 3 offers with pay and miles"));
        assertTrue(description, description.contains("phone areas when offers arrived"));
        assertTrue(description, description.contains("not pickups, final stops or Dasher hotspots"));
        assertTrue(description, description.contains("blue dot is You"));
        assertTrue(description, description.contains("not a road route"));
        assertTrue(description, description.contains("not a prediction"));
    }

    @Test public void infoTapOpensTheKeyWithoutSelectingAnAreaOrAskingForLocation() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            AreaMapView map = new AreaMapView(activity, new Ui(activity));
            List<AreaMap.Cell> selected = new ArrayList<>();
            int[] ordinaryClicks = {0};
            map.setOnSelect(selected::add);
            map.setOnClickListener(view -> ordinaryClicks[0]++);
            activity.setContentView(map);
            size(map, 360, 200);
            AreaMap.Cell only = square(1800, -6100, 3, 2400, 12);
            map.show(Collections.singletonList(only), null);
            draw(map);
            RectF info = map.helpBounds();
            assertTrue("info remains a full touch target", info.width() >= new Ui(app).dp(48));
            assertTrue(info.height() >= new Ui(app).dp(48));
            tap(map, info.centerX(), info.centerY());
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            assertEquals("Reading the atlas", Shadows.shadowOf(dialog).getTitle());
            String key = Shadows.shadowOf(dialog).getMessage().toString();
            assertTrue(key, key.contains("including declined offers"));
            assertTrue(key, key.contains("total offered pay divided by total offer miles"));
            assertTrue(key, key.contains("It does not supply the hotspot spoke"));
            assertTrue(selected.isEmpty());
            assertEquals(0, ordinaryClicks[0]);
            assertNull(Shadows.shadowOf(activity).getLastRequestedPermission());
            dialog.dismiss();

            // The same view still lets an ordinary square tap select it and dispatch its normal click.
            tap(map, map.getWidth() / 2f, map.getHeight() / 2f);
            assertEquals(1, selected.size());
            assertEquals(only.row, map.selected().row);
            assertEquals(1, ordinaryClicks[0]);
        }
    }

    @Test public void screenReadersCanOpenTheSameKeyEvenBeforeAnyOffersExist() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            AreaMapView map = new AreaMapView(activity, new Ui(activity));
            activity.setContentView(map);
            map.setEmptyMessage("Tap to allow location");
            map.show(Collections.emptyList(), null);
            assertTrue(map.getContentDescription().toString().startsWith("Tap to allow location."));
            AccessibilityNodeInfo node = map.createAccessibilityNodeInfo();
            assertTrue(node.getActionList().stream().anyMatch(action -> action.getId() == AreaMapView.EXPLAIN_ATLAS
                    && "Explain atlas".contentEquals(action.getLabel())));
            assertTrue(map.performAccessibilityAction(AreaMapView.EXPLAIN_ATLAS, null));
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(dialog.isShowing());
            assertTrue(Shadows.shadowOf(dialog).getMessage().toString().contains("not earnings or a prediction"));
            assertNull(Shadows.shadowOf(activity).getLastRequestedPermission());
            dialog.dismiss();
        }
    }

    @Test public void visibleOfferSquaresCannotSitUnderTheInfoTouchTarget() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            AreaMapView map = new AreaMapView(activity, new Ui(activity));
            activity.setContentView(map);
            AreaMap.Cell best = square(1800, -6100, 3, 3600, 12);
            AreaMap.Cell eastern = square(1801, -6094, 3, 2400, 12);
            List<AreaMap.Cell> selected = new ArrayList<>();
            map.setOnSelect(selected::add);
            map.show(Arrays.asList(best, eastern), null);
            size(map, 411, 124);
            draw(map).recycle();

            // This eastern square previously landed inside the 48 dp info target: tapping visible data opened help.
            RectF square = map.areaBounds(eastern);
            assertTrue(square.centerX() > 0 && square.centerX() < map.getWidth());
            assertTrue(square.centerY() > 0 && square.centerY() < map.getHeight());
            assertFalse("the plotted square must be clear of the help control",
                    RectF.intersects(square, map.helpBounds()));
            tap(map, square.centerX(), square.centerY());
            assertEquals(1, selected.size());
            assertEquals(eastern.row, selected.get(0).row);
            assertEquals(eastern.col, selected.get(0).col);
            assertNull("a data tap must not open help", ShadowAlertDialog.getLatestAlertDialog());
        }
    }

    @Test @Config(sdk = 35) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void atlasKeyRendersAtPhoneAndShortMapSizes() throws Exception {
        preview(360, 220, "phone");
        preview(411, 96, "short-96dp");
        preview(360, 84, "compact-84dp");
    }

    @Test @Config(sdk = 35, qualifiers = "w411dp-h914dp-night-xxhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void atlasKeyRendersAgainstTheNightGround() throws Exception {
        assertTrue(new Ui(app).dark);
        preview(360, 220, "night");
    }

    private void preview(int width, int height, String name) throws Exception {
        AreaMapView map = new AreaMapView(app, new Ui(app));
        List<AreaMap.Cell> cells = Arrays.asList(square(1801, -6100, 5, 5000, 20),
                square(1800, -6100, 3, 2400, 12), square(1800, -6101, 2, 2000, 5));
        map.show(cells, new double[] {36.01, -121.99});
        size(map, width, height);
        Bitmap bitmap = draw(map);
        RectF info = map.helpBounds();
        assertTrue(info.left >= 0 && info.right <= map.getWidth());
        assertTrue(info.top >= 0 && info.bottom <= map.getHeight());
        RectF label = map.hereLabelBounds();
        assertFalse("You remains readable in these phone and short-map scenes", label.isEmpty());
        for (AreaMap.Cell cell : cells) {
            if (cell.ranked()) assertFalse("You must never cross a rank badge",
                    RectF.intersects(label, map.rankBounds(cell)));
        }
        File directory = new File("build/reports/atlas-clarity");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
        bitmap.recycle();
    }

    private static AreaMap.Cell square(int row, int col, int offers, int pay, double miles) {
        AreaMap.Cell cell = new AreaMap.Cell(row, col);
        cell.offers = cell.mileOffers = offers;
        cell.payCents = cell.milePayCents = pay;
        cell.miles = miles;
        return cell;
    }

    private void size(AreaMapView map, int widthDp, int heightDp) {
        Ui ui = new Ui(app);
        int width = ui.dp(widthDp), height = ui.dp(heightDp);
        map.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        map.layout(0, 0, width, height);
    }

    private Bitmap draw(AreaMapView map) {
        Bitmap bitmap = Bitmap.createBitmap(map.getWidth(), map.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(new Ui(app).dark ? 0xFF292921 : 0xFFEDE6CD);
        map.draw(canvas);
        return bitmap;
    }

    private static void tap(View view, float x, float y) {
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(0, 30, MotionEvent.ACTION_UP, x, y, 0);
        view.onTouchEvent(down);
        view.onTouchEvent(up);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        down.recycle();
        up.recycle();
    }
}

package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;

/** The shortcuts remain reachable with the Atlas hidden, in the compact header and with a large font. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NavigationHeaderTest extends AndroidAdapterTestBase {
    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    public void fullScreenHeaderFitsWithLargeFontAndGasWorksWithoutAreaLocation() {
        RuntimeEnvironment.setFontScale(2f);
        dasherInstalled();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertHeaderFits(content);
            render(content, "full-320-large-font");
            iconDescribed(content, "Navigate").performClick();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            ListView list = dialog.getListView();
            assertEquals(3, list.getCount());
            assertFalse(list.getAdapter().isEnabled(0));
            assertTrue(list.getAdapter().getItem(0).toString().contains("Needs 3 offers"));
            assertTrue(list.getAdapter().isEnabled(1));
            assertMenuTargets(list);
            render(dialog.getWindow().getDecorView(), "menu-320-large-font");
            list.performItemClick(null, 1, 1);
            Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
            assertEquals("gas stations near me", opened.getData().getQueryParameter("query"));
            assertNull(opened.getData().getQueryParameter("destination"));
            assertFalse(AreaMap.hasPermission(app));
        }
    }

    @Test @Config(qualifiers = "w320dp-h360dp-xhdpi")
    public void narrowShortHeaderKeepsAllControlsAndItsConstellationReachable() {
        RuntimeEnvironment.setFontScale(2f);
        dasherInstalled();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertHeaderFits(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(star.beside());
            assertTrue(star.getHeight() >= new Ui(app).dp(48));
            assertTrue(iconDescribed(content, "Offer Filter").getWidth() > 0);
            render(content, "short-320-large-font");
            iconDescribed(content, "Navigate").performClick();
            assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
            assertMenuTargets(ShadowAlertDialog.getLatestAlertDialog().getListView());
            render(ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView(),
                    "short-menu-320-large-font");
            assertGasPricesReachable(ShadowAlertDialog.getLatestAlertDialog());
        }
    }

    @Test @Config(qualifiers = "w320dp-h360dp-xhdpi")
    public void besideDasherNavigationWorksWhileAtlasIsHidden() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);
        for (int pay : new int[] {1000, 1100, 1200}) noteOfferAt(37.775, -122.415, pay, 5);
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        OfferFilterService.sawDasherBeside(SystemClock.uptimeMillis());
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertHeaderFits(content);
            assertFalse(find(content, AreaMapView.class).isShown());
            render(content, "split-dasher-320");
            iconDescribed(content, "Navigate").performClick();
            ListView list = ShadowAlertDialog.getLatestAlertDialog().getListView();
            assertTrue(list.getAdapter().isEnabled(0));
            String label = list.getAdapter().getItem(0).toString();
            assertTrue(label.contains("$2.20/mi"));
            assertTrue(label.contains("3 offers"));
            assertTrue(label.contains("Approximate historical area"));
            assertMenuTargets(list);
            render(ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView(), "split-best-menu-320");
            list.performItemClick(null, 0, 0);
            Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
            assertEquals("37.7700,-122.4100", opened.getData().getQueryParameter("destination"));
            assertEquals(0, opened.getFlags());
        } finally {
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    @Test public void gasPricesExplainsComparisonAndLaunchesAFreeTextSearch() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            iconDescribed(content, "Navigate").performClick();
            ListView list = ShadowAlertDialog.getLatestAlertDialog().getListView();
            assertTrue(list.getAdapter().getItem(2).toString().contains("Compare gas prices"));
            assertTrue(list.getAdapter().getItem(2).toString().contains("prices listed in your map app"));
            list.performItemClick(null, 2, 2);
            Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
            assertEquals("gas prices near me", opened.getData().getQueryParameter("query"));
        }
    }

    private void assertHeaderFits(View content) {
        settleSky(content);
        View navigate = iconDescribed(content, "Navigate");
        assertNotNull(navigate);
        assertTrue(navigate.isShown());
        ViewGroup header = (ViewGroup) navigate.getParent();
        Ui ui = new Ui(app);
        int right = header.getPaddingLeft();
        for (int i = 0; i < header.getChildCount(); i++) {
            View child = header.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            assertTrue("ordered nonoverlapping header children", child.getLeft() >= right);
            assertTrue("every control stays within its header", child.getRight() <= header.getWidth());
            right = child.getRight();
            if (child.isClickable()) {
                assertTrue("48 dp touch width", child.getWidth() >= ui.dp(48));
                assertTrue("48 dp touch height", child.getHeight() >= ui.dp(48));
            }
        }
        assertEquals(ui.dp(56), find(content, AppearanceButton.class).getHeight());
    }

    private void assertMenuTargets(ListView list) {
        int width = app.getResources().getDisplayMetrics().widthPixels - new Ui(app).dp(48);
        for (int i = 0; i < list.getCount(); i++) {
            View row = list.getAdapter().getView(i, null, list);
            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            assertTrue("menu row " + i + " has a 48 dp target", row.getMeasuredHeight() >= new Ui(app).dp(48));
        }
    }

    private void render(View view, String name) {
        if (view.getWidth() == 0 || view.getHeight() == 0) {
            layOutDialog(view);
        }
        int width = Math.max(1, view.getWidth()), height = Math.max(1, view.getHeight());
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height,
                android.graphics.Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(bitmap));
        java.io.File directory = new java.io.File("build/navigation-previews");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(directory,
                name + (android.os.Build.VERSION.SDK_INT == 35 ? "" : "-api" + android.os.Build.VERSION.SDK_INT)
                        + ".png"))) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        } finally {
            bitmap.recycle();
        }
    }

    private void assertGasPricesReachable(AlertDialog dialog) {
        View decor = dialog.getWindow().getDecorView();
        layOutDialog(decor);
        ListView list = dialog.getListView();
        // ListView limits an individual scroll to its viewport. At 2x font on API 26 one oversized scroll
        // leaves only the last row's edge showing. Bring it into view in bounded steps, then align its heading
        // rather than jumping to the bottom (the description can be taller than the short viewport).
        for (int step = 0; step < list.getCount() + 2; step++) {
            View target = list.getChildAt(2 - list.getFirstVisiblePosition());
            int by = target == null ? Math.max(1, list.getHeight() / 2)
                    : target.getTop() - list.getPaddingTop();
            if (by == 0) break;
            list.scrollListBy(by);
        }
        View row = list.getChildAt(2 - list.getFirstVisiblePosition());
        assertNotNull("gas prices remains reachable by scrolling in a short large-font window (first "
                + list.getFirstVisiblePosition() + ", children " + list.getChildCount() + ")", row);
        android.graphics.Rect visible = new android.graphics.Rect();
        assertTrue(row.getGlobalVisibleRect(visible));
        assertTrue("the action's heading stays below the dialog title", row.getTop() >= list.getPaddingTop());
        assertTrue("the visible action remains at least 48 dp tall: " + visible.height()
                + " px in " + list.getHeight() + " px list; row " + row.getTop() + ".." + row.getBottom(),
                visible.height() >= new Ui(app).dp(48));
        render(decor, "short-menu-scrolled-320-large-font");
    }

    private void layOutDialog(View view) {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        int width = app.getResources().getDisplayMetrics().widthPixels - new Ui(app).dp(32);
        int height = app.getResources().getDisplayMetrics().heightPixels - new Ui(app).dp(32);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }
}

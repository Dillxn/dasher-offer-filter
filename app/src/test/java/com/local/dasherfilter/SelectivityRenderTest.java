package com.local.dasherfilter;

import android.app.AlertDialog;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.EditText;
import android.os.Looper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.*;

/** Synthetic native renders complement the Android 8/15/16 interaction tests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36}, qualifiers = "w411dp-h410dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class SelectivityRenderTest extends AndroidAdapterTestBase {
    @Test public void compactMinimumsChipAndOpenedNativePanel() { renderScene(true, "compact"); }

    @Test public void denseFullWindowKeepsTheMinimumsTargetClearOfNavigation() { renderScene(false, "short-window"); }

    @Test @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void advancedCostsPanelShowsUnknownCostAndSeparateOptIn() {
        assertTrue(EarningsStore.saveConfig(app, new EarningsModel.Config(false, null, 80, 120)));
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = controller.get().findViewById(android.R.id.content);
            settleSky(content);
            iconDescribed(content, "Settings").performClick();
            View entry = shownButton(content, "Costs and estimates");
            assertNotNull(entry);
            entry.performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            assertTrue(dialog.isShowing());
            View panel = dialog.getWindow().getDecorView();
            Ui ui = new Ui(app);
            panel.measure(View.MeasureSpec.makeMeasureSpec(content.getWidth() - ui.dp(32), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(content.getHeight() - ui.dp(32), View.MeasureSpec.AT_MOST));
            panel.layout(0, 0, panel.getMeasuredWidth(), panel.getMeasuredHeight());
            assertEquals("unknown cost remains blank", "", find(panel, EditText.class).getText().toString());
            assertFalse(EarningsStore.config(app).enabled);
            assertNotNull(findButton(panel, "Enable automatic adjustment…"));
            render(panel, "costs-and-estimates");
            dialog.dismiss();
        }
    }

    private void renderScene(boolean split, String name) {
        FilterStore.save(app, new FilterSettings(true, 1000, 200, 30, 100, 3)
                .withScoreByArea(true).withMinimumScalePercent(97));
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(split);
        try (ActivityController<MainActivity> controller = built.setup()) {
            View content = controller.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            if (star.beside()) { star.performClick(); settleSky(content); }
            assertNotNull(star.scoreToggleBox());
            assertClearOfHeaderControls(content, star);
            render(content, name + "-main");
            AlertDialog dialog = SelectivityControlTest.openCompact(star);
            View panel = dialog.getWindow().getDecorView();
            SeekBar slider = find(panel, SeekBar.class);
            assertEquals(97, slider.getProgress());
            assertTrue(slider.getHeight() >= new Ui(app).dp(48));
            render(panel, name + "-panel");
            dialog.dismiss();
        }
    }

    private static void assertClearOfHeaderControls(View content, MinimumsStarView star) {
        RectF minimums = star.scoreToggleBox();
        int[] origin = new int[2];
        star.getLocationInWindow(origin);
        minimums.offset(origin[0], origin[1]);
        ViewGroup header = (ViewGroup) iconDescribed(content, "Navigate").getParent();
        int checked = 0;
        for (int i = 0; i < header.getChildCount(); i++) {
            View button = header.getChildAt(i);
            if (!button.isClickable() || !button.isShown() || button == star) continue;
            button.getLocationInWindow(origin);
            RectF target = new RectF(origin[0], origin[1], origin[0] + button.getWidth(),
                    origin[1] + button.getHeight());
            assertFalse("minimums target " + minimums + " cannot hide under " + button.getContentDescription()
                    + " " + target, RectF.intersects(minimums, target));
            checked++;
        }
        assertTrue("navigation and Settings targets are inspected", checked >= 2);
    }

    private static void render(View view, String name) {
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(view.getWidth(), view.getHeight(),
                android.graphics.Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(bitmap));
        java.io.File directory = new java.io.File("build/reports/selectivity");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        java.io.File output = new java.io.File(directory, name + "-api" + android.os.Build.VERSION.SDK_INT + ".png");
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(output)) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream));
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        } finally {
            bitmap.recycle();
        }
    }
}

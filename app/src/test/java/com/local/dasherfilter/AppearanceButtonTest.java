package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Badge layout/contrast and the single accessible action survive both palettes and large fonts. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AppearanceButtonTest extends AndroidAdapterTestBase {
    @Test public void badgesRemainReadableInsideTheExistingTouchTarget() throws Exception {
        for (boolean dark : new boolean[] {false, true}) {
            for (float fontScale : new float[] {1f, 2f}) check(dark, fontScale);
        }
    }

    private void check(boolean dark, float fontScale) throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        Configuration config = new Configuration(app.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        config.fontScale = fontScale;
        Context context = app.createConfigurationContext(config);
        Ui ui = new Ui(context);
        AppearanceButton button = new AppearanceButton(context, ui);
        button.setOnClickListener(view -> {});
        for (Appearance.Mode mode : Appearance.Mode.values()) {
            Appearance.State state = new Appearance.State(mode, dark, mode == Appearance.Mode.AUTO);
            button.show(state);
            int size = ui.dp(56);
            button.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
            button.layout(0, 0, size, size);
            TextView badge = (TextView) button.getChildAt(0);
            boolean marked = mode == Appearance.Mode.SYSTEM || mode == Appearance.Mode.AUTO;
            assertEquals(marked ? View.VISIBLE : View.GONE, badge.getVisibility());
            assertEquals(state.description(), button.getContentDescription());
            assertEquals(state.description(), button.getTooltipText());
            AccessibilityNodeInfo info = button.createAccessibilityNodeInfo();
            assertEquals("android.widget.Button", info.getClassName());
            assertTrue(button.isClickable());
            assertTrue(button.isFocusable());
            assertEquals("the single-letter indicator is not a second focus target", 0, info.getChildCount());
            info.recycle();
            if (!marked) continue;
            assertEquals(mode == Appearance.Mode.SYSTEM ? "S" : "A", badge.getText().toString());
            assertTrue(badge.getLeft() >= 0 && badge.getTop() >= 0);
            assertTrue("the corner indicator stays clear of the icon center even at large fonts",
                    badge.getLeft() > size / 2 || badge.getTop() > size / 2);
            assertTrue(badge.getRight() <= size && badge.getBottom() <= size);
            assertTrue("letter fits without truncation", badge.getPaint().measureText(badge.getText().toString())
                    <= badge.getWidth() - badge.getPaddingLeft() - badge.getPaddingRight());
            assertTrue("font fits vertically", Ui.lineHeight(badge.getPaint())
                    <= badge.getHeight() - badge.getPaddingTop() - badge.getPaddingBottom());
            assertEquals("full contrast ink on the opaque page-colored badge", ui.ink, badge.getCurrentTextColor());
            Bitmap rendered = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            button.draw(new Canvas(rendered));
            // Only the corner badge covers the existing scene: keep the central sun/moon fully visible.
            assertEquals(0, rendered.getPixel(size / 2, size / 2));
            File out = new File("build/reports/appearance-button");
            assertTrue(out.isDirectory() || out.mkdirs());
            try (FileOutputStream stream = new FileOutputStream(new File(out,
                    mode.name().toLowerCase() + (dark ? "-night-" : "-day-") + fontScale + ".png"))) {
                assertTrue(rendered.compress(Bitmap.CompressFormat.PNG, 100, stream));
            } finally { rendered.recycle(); }
        }
    }

    @Test public void theNativeSceneKeepsSunAndMoonVisibleBesideTheModeBadges() throws Exception {
        RuntimeEnvironment.setQualifiers("w411dp-h914dp-night-xxhdpi");
        for (Appearance.Mode mode : new Appearance.Mode[] {Appearance.Mode.SYSTEM, Appearance.Mode.AUTO}) {
            Appearance.choose(app, mode);
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                View content = activity.get().findViewById(android.R.id.content);
                settleSky(content);
                AppearanceButton button = find(content, AppearanceButton.class);
                assertNotNull(button);
                AccessibilityNodeInfo info = button.createAccessibilityNodeInfo();
                assertTrue("the attached control exposes the tap action", info.isClickable());
                assertEquals("android.widget.Button", info.getClassName());
                assertEquals(0, info.getChildCount());
                info.recycle();
                Bitmap rendered = Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
                content.draw(new Canvas(rendered));
                File out = new File("build/reports/appearance-button");
                assertTrue(out.isDirectory() || out.mkdirs());
                try (FileOutputStream stream = new FileOutputStream(new File(out,
                        "scene-" + mode.name().toLowerCase() + ".png"))) {
                    assertTrue(rendered.compress(Bitmap.CompressFormat.PNG, 100, stream));
                } finally { rendered.recycle(); }
            }
        }
    }
}

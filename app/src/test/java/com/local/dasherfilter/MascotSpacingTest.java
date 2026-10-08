package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
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

/**
 * The mascot, a small ring at the start of the strip along the top (0.5.1 floated it over the constellation, under the
 * counts), keeps a whole touch target and leaves the radar, its knobs, icons, labels and buttons wholly clear, in a
 * phone's whole screen, a narrow phone, half a split screen beside Dasher and at twice the font. The counts stand in
 * their own row under the header, each a whole target that opens its offer without pausing, wherever the window has
 * room for them (a short split gives their row to the radar and the map).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class MascotSpacingTest extends AndroidAdapterTestBase {
    @Before public void dayPalette() { Appearance.choose(app, Appearance.Mode.DAY); }

    @Test public void phoneHasSmallerUpperLeftMascot() throws Exception { check(false, "phone"); }

    @Test @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void splitLeavesTheAxesAndControlsClear() throws Exception { check(true, "split"); }

    @Test @Config(qualifiers = "w360dp-h396dp-xhdpi")
    public void smallSamsungSplitKeepsTheMascotAtTheStripsStart() throws Exception { check(true, "samsung-split"); }

    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    public void narrowPhoneKeepsAUsableControl() throws Exception { check(false, "narrow"); }

    @Test public void largeFontKeepsLabelsClear() throws Exception {
        org.robolectric.RuntimeEnvironment.setFontScale(2f);
        try { check(false, "large-font"); }
        finally { org.robolectric.RuntimeEnvironment.setFontScale(1f); }
    }

    private void check(boolean split, String name) throws Exception {
        // Every control the constellation has: the three minimums, max stops, and Autopilot on with its bar below
        // 100% (its button reading "82%" and its dashed shape drawn). The old $5.00 per stop is folded into the
        // $10.00 minimum pay (2 x $5.00 = $10.00), as the 0.5.0 update does.
        FilterSettings rules = FilterSettings.of(true, 1000, 200, 50, 3);
        FilterStore.save(app, rules);
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertTrue(FilterStore.commitAutopilotBar(app, 100, 82));
        rules = FilterStore.load(app);
        OfferSnapshot offer = new OfferSnapshot(1200, 5.0, 20, 2).withItems(20, true);
        DecisionLog.record(app, DecisionLog.Entry.of(DecisionLog.Source.SCREEN, false, offer,
                OfferRule.evaluate(offer, rules), DecisionLog.Action.PASSES, true, Collections.emptyList()));
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        ServiceController<OfferNotificationService> listener = Robolectric.buildService(OfferNotificationService.class).create();
        service.get().onServiceConnected();
        listener.get().onListenerConnected();
        if (split) OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        if (split) Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            ScenePage page = find(content, ScenePage.class);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            FilterHeroView hero = find(content, FilterHeroView.class);
            Ui ui = new Ui(app);
            assertTrue(hero.placed());
            // In the page's pixels.
            float x = hero.getLeft() + hero.mascotX(), y = hero.getTop() + hero.mascotY();
            float r = hero.mascotRadius();
            assertTrue("a small drawing", r <= ui.dp(36));
            assertTrue("at least a 44dp control", r * 2 >= ui.dp(44));
            View control = hero.mascotControl();
            assertTrue("a whole 48 dp target", control.getWidth() >= ui.dp(48) && control.getHeight() >= ui.dp(48));
            assertTrue("near the left edge, inside the page", x - r >= 0 && x - r <= ui.dp(16));
            assertTrue("at the top of the page", y - r >= 0 && y - r <= ui.dp(12));
            View header = page.getChildAt(2);
            assertTrue("above the header", y + r <= header.getTop() + 1);
            assertTrue("and above the radar", y + r <= star.getTop());
            RectF counts = new RectF();
            hero.countsAt(counts);
            counts.offset(hero.getLeft(), hero.getTop());
            if (page.countsShown() > 0) {
                assertTrue("the counts in their own row under the header", counts.top >= header.getBottom() - 1);
            }
            List<RectF> obstacles = new ArrayList<>();
            star.iconsAt(obstacles);
            for (int i = 0; i < AreaScore.AXES; i++) obstacles.add(star.axisLabelBox(i));
            obstacles.add(star.autopilotBox());
            obstacles.add(star.stopsBox());
            obstacles.add(star.stopsPinBox());
            assertEquals("the radar wholly there", 1f, page.shown(star), 0f);
            assertFalse("with its dashed shape at 82%", star.autopilotShape().isEmpty());
            for (RectF box : obstacles) {
                if (box == null) continue;
                box.offset(star.getLeft(), star.getTop());
                float nearestX = Math.max(box.left, Math.min(x, box.right));
                float nearestY = Math.max(box.top, Math.min(y, box.bottom));
                assertTrue("the mascot leaves the radar's icons, labels and buttons clear: " + box,
                        Math.hypot(x - nearestX, y - nearestY) >= r);
            }
            Bitmap bitmap = Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
            content.draw(new Canvas(bitmap));
            File out = new File("build/reports/mascot-spacing");
            assertTrue(out.isDirectory() || out.mkdirs());
            try (FileOutputStream stream = new FileOutputStream(new File(out, name + ".png"))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
            } finally { bitmap.recycle(); }
            if (page.countsShown() >= 1) {
                for (DecisionLog.Tally tally : DecisionLog.Tally.values()) {
                    android.widget.Button count = hero.countControl(tally);
                    assertTrue("native count target stays at least 48dp wide", count.getWidth() >= ui.dp(48));
                    assertTrue("native count target stays at least 48dp tall", count.getHeight() >= ui.dp(48));
                }
                tap(page, counts.centerX() - hero.countsAt(new RectF()), counts.centerY());
                assertTrue("inspecting a count never pauses", FilterStore.load(app).enabled);
                assertNotNull("the count opens its existing offer ticket", find(content, OfferCardView.class));
                activity.get().onBackPressed();
            } else {
                for (DecisionLog.Tally tally : DecisionLog.Tally.values()) {
                    assertFalse("with their row given to the radar and the map, the counts take no touch",
                            hero.countControl(tally).isShown());
                }
            }
            tap(page, x, y);
            assertFalse("tap still pauses filtering", FilterStore.load(app).enabled);
        } finally {
            listener.destroy();
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }
}

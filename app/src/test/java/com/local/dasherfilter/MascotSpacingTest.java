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

/** A smaller filter control leaves the offer plot legible without reducing its usable touch target. */
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
    public void smallSamsungSplitAnchorsTheMascotBelowTheCounts() throws Exception { check(true, "samsung-split"); }

    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    public void narrowPhoneKeepsAUsableControl() throws Exception { check(false, "narrow"); }

    @Test public void largeFontKeepsLabelsClear() throws Exception {
        org.robolectric.RuntimeEnvironment.setFontScale(2f);
        try { check(false, "large-font"); }
        finally { org.robolectric.RuntimeEnvironment.setFontScale(1f); }
    }

    private void check(boolean split, String name) throws Exception {
        FilterSettings rules = new FilterSettings(true, 1000, 200, 50, 500, 3)
                .withHotspotProximity(50).withPerItem(50).withScoreByArea(true);
        FilterStore.save(app, rules);
        OfferSnapshot offer = new OfferSnapshot(1200, 5.0, 20, 2).withFinalStopHotspotMiles(2.0).withItems(20, true);
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
            MinimumsStarView star = find(content, MinimumsStarView.class);
            FilterHeroView hero = find(content, FilterHeroView.class);
            Ui ui = new Ui(app);
            assertTrue(hero.placed());
            float x = hero.getLeft() + hero.mascotX(), y = hero.getTop() + hero.mascotY();
            float r = hero.mascotRadius();
            assertTrue("smaller drawing", r <= ui.dp(36));
            assertTrue("at least a 44dp control", r * 2 >= ui.dp(44));
            assertTrue("near the left edge", x - r >= 0 && x - r <= ui.dp(7));
            assertTrue("raised above the plot center", y < star.skyY() - ui.dp(12));
            RectF counts = new RectF();
            hero.countsAt(counts);
            counts.offset(hero.getLeft(), hero.getTop());
            assertTrue("below the counts", y - r >= counts.bottom);
            assertTrue("at the top below the counters, not beside the plot center",
                    y - r <= counts.bottom + ui.dp(20));
            List<RectF> obstacles = new ArrayList<>();
            star.iconsAt(obstacles);
            for (int i = 0; i < AreaScore.AXES; i++) obstacles.add(star.axisLabelBox(i));
            obstacles.add(star.scoreToggleBox());
            obstacles.add(star.adaptiveBox());
            obstacles.add(star.stopsBox());
            for (RectF box : obstacles) {
                if (box == null) continue;
                float nearestX = Math.max(box.left, Math.min(x, box.right));
                float nearestY = Math.max(box.top, Math.min(y, box.bottom));
                assertTrue("mascot leaves axis/control clear: " + box,
                        Math.hypot(x - nearestX, y - nearestY) >= r);
            }
            Bitmap bitmap = Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
            content.draw(new Canvas(bitmap));
            File out = new File("build/reports/mascot-spacing");
            assertTrue(out.isDirectory() || out.mkdirs());
            try (FileOutputStream stream = new FileOutputStream(new File(out, name + ".png"))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
            } finally { bitmap.recycle(); }
            tap((android.view.ViewGroup) star.getParent(), x, y);
            assertFalse("tap still pauses filtering", FilterStore.load(app).enabled);
        } finally {
            listener.destroy();
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }
}

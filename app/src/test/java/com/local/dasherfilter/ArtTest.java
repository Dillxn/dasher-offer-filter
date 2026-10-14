package com.local.dasherfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Looper;
import android.view.View;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

/**
 * The pictures in the round (0.6.16, {@link Art}; Dillon: "we must not degrade performance, only enhance it"): each
 * one is decoded off the main thread for the screen it is shown on (made for 640 dpi, never decoded larger), the
 * homepage draws them in fewer draws than its flat drawings took, and until they are ready, or when one cannot be
 * read, it draws flat and is drawn again once they are.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36}, qualifiers = "w411dp-h900dp-420dpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ArtTest extends AndroidAdapterTestBase {

    @Test
    public void eachPictureIsDecodedForTheScreen() {
        decodedAt(420);
    }

    @Test @Config(qualifiers = "w411dp-h900dp-mdpi")
    public void aScreenOfFewerDotsGetsSmallerPictures() {
        decodedAt(160);
    }

    @Test @Config(qualifiers = "w411dp-h900dp-xxxhdpi")
    public void theDensestScreenGetsThemWhole() {
        decodedAt(640);
    }

    private void decodedAt(int dpi) {
        Ui ui = new Ui(app);
        for (boolean dark : new boolean[] {false, true}) {
            for (Art.Sprite sprite : Art.palette(dark)) {
                Bitmap bitmap = Art.bitmap(ui, sprite);
                assertNotNull("decoded", bitmap);
                assertEquals(sprite.w * dpi / 640f, bitmap.getWidth(), 1f);
                assertEquals(sprite.h * dpi / 640f, bitmap.getHeight(), 1f);
            }
        }
    }

    @Test
    public void picturesAreDecodedOffTheMainThread() throws Exception {
        Art.executorForTests = null;
        Art.Sprite[] palette = Art.palette(false);
        Set<Thread> threads = ConcurrentHashMap.newKeySet();
        CountDownLatch opened = new CountDownLatch(palette.length);
        Art.Source pictures = Art.source;
        Art.source = (resources, sprite) -> {
            threads.add(Thread.currentThread());
            opened.countDown();
            return pictures.open(resources, sprite);
        };
        Ui ui = new Ui(app);
        Art.preload(ui);
        assertTrue("every picture of the palette asked for", opened.await(20, TimeUnit.SECONDS));
        assertFalse("none decoded on the main thread", threads.contains(Thread.currentThread()));
        for (Art.Sprite sprite : palette) {
            for (int wait = 0; wait < 200 && Art.bitmap(ui, sprite) == null; wait++) Thread.sleep(50);
            assertNotNull("ready", Art.bitmap(ui, sprite));
        }
    }

    @Test
    public void theHomepageDrawsThePicturesInFewerDrawsThanFlat() {
        offers();
        Counting round = homepage();
        Art.flatForTests = true;
        Counting flat = homepage();
        assertEquals("flat, no picture", 0, flat.bitmaps);
        // The mascot and its arm, the sun, the clouds, and the chart's buildings and trees.
        assertTrue("pictures drawn: " + round.bitmaps, round.bitmaps >= 10);
        assertTrue("fewer draws in the round: " + round.total() + " against " + flat.total(),
                round.total() < flat.total());
        assertTrue("fewer paths in the round: " + round.paths + " against " + flat.paths, round.paths < flat.paths);
    }

    @Test
    public void flatUntilThePicturesAreReadyThenDrawnAgain() {
        offers();
        List<Runnable> queued = new ArrayList<>();
        Art.executorForTests = queued::add;
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
        controller.setup();
        View content = controller.get().findViewById(android.R.id.content);
        settleSky(content);
        assertEquals("flat while the pictures are on their way", 0, draw(content).bitmaps);
        assertFalse("asked for as the page was made", queued.isEmpty());
        FilterHeroView hero = find(content, FilterHeroView.class);
        DecisionChartView chart = find(content, DecisionChartView.class);
        Shadows.shadowOf(hero).clearWasInvalidated();
        Shadows.shadowOf(chart).clearWasInvalidated();
        for (Runnable decode : new ArrayList<>(queued)) decode.run();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("the mascot drawn again", Shadows.shadowOf(hero).wasInvalidated());
        assertTrue("the chart drawn again", Shadows.shadowOf(chart).wasInvalidated());
        assertTrue("in the round once ready", draw(content).bitmaps >= 10);
        controller.pause().stop().destroy();
    }

    @Test
    public void aPictureThatCannotBeReadIsDrawnFlat() {
        offers();
        Art.source = (resources, sprite) -> {
            throw new IOException("unreadable");
        };
        Counting drawn = homepage();
        assertEquals(0, drawn.bitmaps);
        assertTrue("the page still drawn", drawn.total() > 100);
        assertNull(Art.bitmap(new Ui(app), ArtSprites.MASCOT_ON));
    }

    @Test
    public void aPictureOfAnotherSizeThanItsNumbersIsNeverDrawn() {
        Art.Sprite block = ArtSprites.BLOCK_KEEP;
        float scale = 420 / 640f;
        Bitmap right = Bitmap.createBitmap(Math.round(block.w * scale), Math.round(block.h * scale),
                Bitmap.Config.ARGB_8888);
        Bitmap wrong = Bitmap.createBitmap(Math.round(block.w * scale) + 4, Math.round(block.h * scale),
                Bitmap.Config.ARGB_8888);
        assertTrue(Art.sized(right, block, scale));
        assertFalse(Art.sized(wrong, block, scale));
    }

    /** Some offers of each outcome, so the chart has its buildings and trees. */
    private void offers() {
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 3));
        long wall = System.currentTimeMillis();
        FilterSettings rules = FilterStore.load(app);
        for (int i = 13; i >= 0; i--) {
            OfferSnapshot facts = new OfferSnapshot(300 + 70 * (i % 9), (double) (3 + (i % 5)), 15 + i, 2);
            OfferRule.Decision decision = OfferRule.evaluate(facts, rules);
            DecisionLog.Action action = decision.result == OfferRule.Result.DECLINE
                    ? DecisionLog.Action.CONFIRMATION_TAPPED
                    : decision.result == OfferRule.Result.KEEP ? DecisionLog.Action.PASSES
                    : DecisionLog.Action.NEEDS_REVIEW;
            DecisionLog.record(app, DecisionLog.Entry.of(DecisionLog.Source.SCREEN, false, facts, decision, action,
                    true, Collections.<String>emptyList()).withTime(wall - (2 + 3L * i) * 60_000L));
        }
    }

    /** The homepage made afresh, settled and drawn once: what it drew. */
    private Counting homepage() {
        Art.forgetCache();
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
        controller.setup();
        View content = controller.get().findViewById(android.R.id.content);
        settleSky(content);
        Counting drawn = draw(content);
        controller.pause().stop().destroy();
        return drawn;
    }

    private static Counting draw(View content) {
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, content.getWidth()), Math.max(1, content.getHeight()),
                Bitmap.Config.ARGB_8888);
        Counting counting = new Counting(bitmap);
        content.draw(counting);
        bitmap.recycle();
        return counting;
    }

    /** A canvas that counts what is drawn on it: pictures, paths, other shapes and words. */
    static final class Counting extends Canvas {
        int bitmaps;
        int paths;
        int shapes;
        int text;

        Counting(Bitmap bitmap) { super(bitmap); }

        int total() { return bitmaps + paths + shapes + text; }

        @Override public void drawBitmap(Bitmap b, float x, float y, Paint p) { bitmaps++; super.drawBitmap(b, x, y, p); }
        @Override public void drawBitmap(Bitmap b, Rect s, RectF d, Paint p) { bitmaps++; super.drawBitmap(b, s, d, p); }
        @Override public void drawBitmap(Bitmap b, Rect s, Rect d, Paint p) { bitmaps++; super.drawBitmap(b, s, d, p); }
        @Override public void drawBitmap(Bitmap b, Matrix m, Paint p) { bitmaps++; super.drawBitmap(b, m, p); }
        @Override public void drawPath(Path path, Paint p) { paths++; super.drawPath(path, p); }
        @Override public void drawArc(RectF o, float s, float w, boolean u, Paint p) { shapes++; super.drawArc(o, s, w, u, p); }
        @Override public void drawArc(float l, float t, float r, float b, float s, float w, boolean u, Paint p) {
            shapes++;
            super.drawArc(l, t, r, b, s, w, u, p);
        }
        @Override public void drawCircle(float x, float y, float r, Paint p) { shapes++; super.drawCircle(x, y, r, p); }
        @Override public void drawLine(float a, float b, float c, float d, Paint p) { shapes++; super.drawLine(a, b, c, d, p); }
        @Override public void drawLines(float[] pts, int o, int c, Paint p) { shapes++; super.drawLines(pts, o, c, p); }
        @Override public void drawLines(float[] pts, Paint p) { shapes++; super.drawLines(pts, p); }
        @Override public void drawPoints(float[] pts, int o, int c, Paint p) { shapes++; super.drawPoints(pts, o, c, p); }
        @Override public void drawPoints(float[] pts, Paint p) { shapes++; super.drawPoints(pts, p); }
        @Override public void drawOval(RectF o, Paint p) { shapes++; super.drawOval(o, p); }
        @Override public void drawOval(float l, float t, float r, float b, Paint p) { shapes++; super.drawOval(l, t, r, b, p); }
        @Override public void drawRect(RectF r, Paint p) { shapes++; super.drawRect(r, p); }
        @Override public void drawRect(Rect r, Paint p) { shapes++; super.drawRect(r, p); }
        @Override public void drawRect(float l, float t, float r, float b, Paint p) { shapes++; super.drawRect(l, t, r, b, p); }
        @Override public void drawRoundRect(RectF r, float x, float y, Paint p) { shapes++; super.drawRoundRect(r, x, y, p); }
        @Override public void drawRoundRect(float l, float t, float r, float b, float x, float y, Paint p) {
            shapes++;
            super.drawRoundRect(l, t, r, b, x, y, p);
        }
        @Override public void drawPaint(Paint p) { shapes++; super.drawPaint(p); }
        @Override public void drawColor(int c) { shapes++; super.drawColor(c); }
        @Override public void drawText(char[] t, int i, int c, float x, float y, Paint p) { text++; super.drawText(t, i, c, x, y, p); }
        @Override public void drawText(String t, float x, float y, Paint p) { text++; super.drawText(t, x, y, p); }
        @Override public void drawText(String t, int s, int e, float x, float y, Paint p) { text++; super.drawText(t, s, e, x, y, p); }
        @Override public void drawText(CharSequence t, int s, int e, float x, float y, Paint p) {
            text++;
            super.drawText(t, s, e, x, y, p);
        }
        @Override public void drawTextRun(char[] t, int i, int c, int ci, int cc, float x, float y, boolean r, Paint p) {
            text++;
            super.drawTextRun(t, i, c, ci, cc, x, y, r, p);
        }
        @Override public void drawTextRun(CharSequence t, int s, int e, int cs, int ce, float x, float y, boolean r,
                Paint p) {
            text++;
            super.drawTextRun(t, s, e, cs, ce, x, y, r, p);
        }
    }
}

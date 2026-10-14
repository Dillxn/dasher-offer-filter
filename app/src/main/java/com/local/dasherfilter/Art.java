package com.local.dasherfilter;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The app's pictures in the round (0.6.16; Dillon, 14 October 2026: "let's also upgrade the app's 2d graphics to our
 * 3d art, but we must not degrade performance, only enhance it"): the website's 3D models (offer-filter-site's
 * assets/models.js, lit as the website lights them) drawn once, ahead of time, into small pictures
 * ({@link ArtSprites}, res/drawable-nodpi/art_*.webp, written by tools/art/render-sprites.cjs), so a frame draws a
 * few bitmaps where it built and filled paths and gradients: the mascot's body and its waving arm, the sun, the moon
 * and the clouds, the ground strip's houses, trees and car, and the chart's buildings and trees. The face, the ring,
 * the stars, the windows, the badges and every word stay drawn as before, over them.
 *
 * <p>Each picture is decoded once a process, off the main thread, scaled to the screen's density as it is decoded (they
 * are made for 640 dpi), and kept; MainActivity, the notice and the legal pages ask for their palette's pictures
 * ({@link #preload}) as they are made, before their first frame. Until a picture is ready, or when it cannot be read,
 * each drawing falls back to its flat shapes, and the views and drawables that {@link #watch} are drawn again once the
 * pictures they wait for are ready. Nothing here decides, reads or sends anything.
 *
 * <p>The drawing helpers keep their scratch rectangles and paint between calls, so they run on the main thread only,
 * as every drawing does.
 */
final class Art {
    /** The density the pictures are made for: 4 pixels to the dp. */
    static final int DENSITY = 640;

    /** One picture: its resource, its size and points in its own pixels (at 640 dpi), and its model's unit there. */
    static final class Sprite {
        final int res;
        /** Its width and height in its own pixels. */
        final int w;
        final int h;
        /** Where the point it is placed by stands in it (its anchor), and how many of its pixels make one model unit. */
        final float x;
        final float y;
        final float unit;
        /** Its other points, x then y each, in the order ArtSprites names them. */
        private final float[] points;

        Sprite(int res, int w, int h, float x, float y, float unit, float... points) {
            this.res = res;
            this.w = w;
            this.h = h;
            this.x = x;
            this.y = y;
            this.unit = unit;
            this.points = points.clone();
        }

        float pointX(int index) { return points[2 * index]; }

        float pointY(int index) { return points[2 * index + 1]; }

        int pointCount() { return points.length / 2; }
    }

    /** The mascot's shoulder, on its body's pictures. */
    static final int SHOULDER = 0;
    /** Where the model's origin stands, on an arm's picture (placed as the body is: by the origin and its unit). */
    static final int ORIGIN = 0;
    /** A chart building's points: its front's left and right edge under its roof, where the roof's picture ends, and the
     * row of its front that is stretched down to the street. */
    static final int LEFT = 0;
    static final int RIGHT = 1;
    static final int CAP_END = 2;
    static final int SLICE = 3;
    /** A chart tree's points (its anchor is its tip): the middle of its crown's foot, and the foot's two ends. */
    static final int FOOT = 0;
    static final int FOOT_LEFT = 1;
    static final int FOOT_RIGHT = 2;

    /** Where a picture's bytes come from: the app's resources; tests read the same files from the source tree. */
    interface Source {
        InputStream open(Resources resources, Sprite sprite) throws IOException;
    }

    static final Source RESOURCES = (resources, sprite) -> resources.openRawResource(sprite.res);
    /** Tests may read the pictures elsewhere, decode them on the calling thread, or draw everything flat. */
    static volatile Source source = RESOURCES;
    static volatile Executor executorForTests;
    static volatile boolean flatForTests;

    private static final Map<Integer, Bitmap> READY = new ConcurrentHashMap<>();
    private static final Set<Integer> ASKED = ConcurrentHashMap.newKeySet();
    private static final List<WeakReference<Object>> WATCHERS = new ArrayList<>();
    private static final AtomicBoolean REDRAW_POSTED = new AtomicBoolean();
    private static Executor decoder;

    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Paint FADED = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final RectF DST = new RectF();
    private static final Rect SRC = new Rect();

    private Art() {}

    /** The pictures a page in this palette may draw, asked for at once (the page's first frame comes after). */
    static void preload(Ui ui) {
        for (Sprite sprite : palette(ui.dark)) ask(ui.context, sprite);
    }

    /** Every picture a page in the day or night palette may draw. */
    static Sprite[] palette(boolean dark) {
        return dark
                ? new Sprite[] {ArtSprites.MASCOT_ON_NIGHT, ArtSprites.MASCOT_PAUSED_NIGHT, ArtSprites.MASCOT_OFF_NIGHT,
                        ArtSprites.ARM_ON, ArtSprites.ARM_PAUSED, ArtSprites.ARM_OFF_NIGHT, ArtSprites.MOON,
                        ArtSprites.CLOUD_NIGHT, ArtSprites.CAR_NIGHT, ArtSprites.HOUSE_NIGHT, ArtSprites.TREE_NIGHT,
                        ArtSprites.BLOCK_KEEP_NIGHT, ArtSprites.BLOCK_REVIEW_NIGHT, ArtSprites.BLOCK_DECLINE_NIGHT,
                        ArtSprites.PINE_NIGHT}
                : new Sprite[] {ArtSprites.MASCOT_ON, ArtSprites.MASCOT_PAUSED, ArtSprites.MASCOT_OFF,
                        ArtSprites.ARM_ON, ArtSprites.ARM_PAUSED, ArtSprites.ARM_OFF, ArtSprites.SUN, ArtSprites.CLOUD,
                        ArtSprites.CAR, ArtSprites.HOUSE, ArtSprites.TREE, ArtSprites.BLOCK_KEEP, ArtSprites.BLOCK_REVIEW,
                        ArtSprites.BLOCK_DECLINE, ArtSprites.PINE};
    }

    /** Draws {@code target} (a view or a drawable) again once pictures it waits for are ready. Main thread. */
    static void watch(Object target) {
        synchronized (WATCHERS) {
            for (WeakReference<Object> watcher : WATCHERS) if (watcher.get() == target) return;
            WATCHERS.add(new WeakReference<>(target));
        }
    }

    /** The picture decoded for this screen, or null until it is (asked for the first time it is wanted). */
    static Bitmap bitmap(Ui ui, Sprite sprite) {
        if (flatForTests) return null;
        Bitmap bitmap = READY.get(sprite.res);
        if (bitmap != null) return bitmap;
        ask(ui.context, sprite);
        // Decoded on the calling thread in tests: ready now.
        return READY.get(sprite.res);
    }

    /** Whether the picture is ready to draw (asking for it if not). */
    static boolean ready(Ui ui, Sprite sprite) {
        return bitmap(ui, sprite) != null;
    }

    /** The shared bitmap paint at {@code alpha} (0–255), for one draw call. */
    static Paint faded(int alpha) {
        FADED.setAlpha(Math.max(0, Math.min(255, alpha)));
        return FADED;
    }

    /**
     * Draws the picture with its anchor at ({@code x}, {@code y}), {@code unitPx} pixels to its model's unit; false,
     * drawing nothing, while it is not ready.
     */
    static boolean draw(Ui ui, Canvas canvas, Sprite sprite, float x, float y, float unitPx, Paint paint) {
        Bitmap bitmap = bitmap(ui, sprite);
        if (bitmap == null) return false;
        float f = unitPx / sprite.unit;
        DST.set(x - sprite.x * f, y - sprite.y * f, x + (sprite.w - sprite.x) * f, y + (sprite.h - sprite.y) * f);
        canvas.drawBitmap(bitmap, null, DST, paint != null ? paint : PAINT);
        return true;
    }

    /** As {@link #draw}, with its point {@code index} at ({@code x}, {@code y}) instead of its anchor. */
    static boolean drawBy(Ui ui, Canvas canvas, Sprite sprite, int index, float x, float y, float unitPx, Paint paint) {
        float f = unitPx / sprite.unit;
        return draw(ui, canvas, sprite, x + (sprite.x - sprite.pointX(index)) * f,
                y + (sprite.y - sprite.pointY(index)) * f, unitPx, paint);
    }

    /** Draws the whole picture stretched into ({@code left}, {@code top}, {@code right}, {@code bottom}). */
    static boolean drawInto(Ui ui, Canvas canvas, Sprite sprite, float left, float top, float right, float bottom,
            Paint paint) {
        Bitmap bitmap = bitmap(ui, sprite);
        if (bitmap == null) return false;
        DST.set(left, top, right, bottom);
        canvas.drawBitmap(bitmap, null, DST, paint != null ? paint : PAINT);
        return true;
    }

    /**
     * Draws the picture's rows from {@code from} to {@code to} (its own pixels, top down) across its whole width into
     * ({@code left}, {@code top}, {@code right}, {@code bottom}).
     */
    static boolean drawRows(Ui ui, Canvas canvas, Sprite sprite, float from, float to, float left, float top,
            float right, float bottom, Paint paint) {
        Bitmap bitmap = bitmap(ui, sprite);
        if (bitmap == null) return false;
        float scale = bitmap.getHeight() / (float) sprite.h;
        int first = Math.max(0, Math.min(bitmap.getHeight() - 1, Math.round(from * scale)));
        int last = Math.max(first + 1, Math.min(bitmap.getHeight(), Math.round(to * scale)));
        SRC.set(0, first, bitmap.getWidth(), last);
        DST.set(left, top, right, bottom);
        canvas.drawBitmap(bitmap, SRC, DST, paint != null ? paint : PAINT);
        return true;
    }

    /** Asks for the picture once: decoded on the pictures' own thread (the calling one in tests). */
    private static void ask(Context context, Sprite sprite) {
        if (flatForTests || READY.containsKey(sprite.res) || !ASKED.add(sprite.res)) return;
        Resources resources = context.getApplicationContext() != null
                ? context.getApplicationContext().getResources() : context.getResources();
        executor().execute(() -> decode(resources, sprite));
    }

    private static Executor executor() {
        Executor tests = executorForTests;
        if (tests != null) return tests;
        synchronized (Art.class) {
            if (decoder == null) {
                decoder = Executors.newSingleThreadExecutor(work -> {
                    Thread thread = new Thread(work, "OfferFilter art");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return decoder;
        }
    }

    /**
     * Decodes one picture scaled for the screen (never above its own 640 dpi), checks it came out the size its
     * numbers say (a picture of another size would be drawn wrong: it is left flat), and readies it for drawing. A
     * picture that cannot be read stays flat for the rest of the process.
     */
    private static void decode(Resources resources, Sprite sprite) {
        int dpi = resources.getDisplayMetrics().densityDpi;
        int target = dpi > 0 ? Math.min(DENSITY, dpi) : DENSITY;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = true;
        options.inDensity = DENSITY;
        options.inTargetDensity = target;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = null;
        try (InputStream in = source.open(resources, sprite)) {
            bitmap = BitmapFactory.decodeStream(in, null, options);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            bitmap = null;
        }
        if (bitmap != null && sized(bitmap, sprite, target / (float) DENSITY)) {
            bitmap.setHasMipMap(true);
            bitmap.prepareToDraw();
            READY.put(sprite.res, bitmap);
            redraw();
        } else if (bitmap != null) {
            bitmap.recycle();
        }
    }

    /** Whether the decoded picture is the size its numbers say at this scale, give or take a pixel. */
    static boolean sized(Bitmap bitmap, Sprite sprite, float scale) {
        return Math.abs(bitmap.getWidth() - (int) (sprite.w * scale + 0.5f)) <= 1
                && Math.abs(bitmap.getHeight() - (int) (sprite.h * scale + 0.5f)) <= 1;
    }

    /** Draws the watchers again on the main thread, once for every picture ready by then. */
    private static void redraw() {
        if (!REDRAW_POSTED.compareAndSet(false, true)) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            REDRAW_POSTED.set(false);
            List<Object> targets = new ArrayList<>();
            synchronized (WATCHERS) {
                for (int i = WATCHERS.size() - 1; i >= 0; i--) {
                    Object target = WATCHERS.get(i).get();
                    if (target == null) WATCHERS.remove(i);
                    else targets.add(target);
                }
            }
            for (Object target : targets) {
                if (target instanceof View) ((View) target).invalidate();
                else if (target instanceof Drawable) ((Drawable) target).invalidateSelf();
            }
        });
    }

    /** Forgets every picture (tests: each starts as a process does). */
    static void forgetCache() {
        READY.clear();
        ASKED.clear();
        REDRAW_POSTED.set(false);
        synchronized (WATCHERS) {
            WATCHERS.clear();
        }
    }
}

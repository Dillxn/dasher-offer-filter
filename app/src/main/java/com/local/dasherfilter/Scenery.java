package com.local.dasherfilter;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/**
 * The drawn backdrop behind the pages. {@link Part#SKY} puts a sun, clouds and a few glints (a moon and twinkling
 * stars in dark mode) behind the page title; {@link Part#GROUND} puts rolling hills, houses, trees and a road with a
 * little delivery car along the bottom of the page area. Both move gently: stars twinkle, clouds and birds drift, the
 * car drives, and tilting the phone slides the far layers against the near ones. With Android's animations off,
 * everything rests. Every color sits close to the page color, so text over it keeps its contrast.
 */
final class Scenery extends Drawable {
    enum Part { SKY, GROUND }

    /** How far, in dp, each layer slides at full tilt: the farther away, the less. */
    private static final float STARS_DEPTH = 3;
    private static final float MOON_DEPTH = 5;
    private static final float CLOUD_DEPTH = 9;
    private static final float BIRD_DEPTH = 12;
    private static final float BACK_HILL_DEPTH = 3;
    private static final float FRONT_HILL_DEPTH = 6;
    private static final float ROAD_DEPTH = 9;
    /** Where the stars sit, as shares of the sky (clear of the title at the left), with a size and a phase each. */
    private static final float[][] STARS = {
            {0.40f, 0.22f, 1.4f, 0.1f}, {0.47f, 0.62f, 1.0f, 0.7f}, {0.53f, 0.14f, 1.2f, 0.4f},
            {0.58f, 0.78f, 1.6f, 0.9f}, {0.63f, 0.3f, 0.9f, 0.2f}, {0.7f, 0.12f, 1.5f, 0.55f},
            {0.74f, 0.58f, 1.0f, 0.3f}, {0.8f, 0.3f, 1.3f, 0.8f}, {0.85f, 0.86f, 0.9f, 0.05f},
            {0.9f, 0.2f, 1.1f, 0.6f}, {0.95f, 0.5f, 1.4f, 0.35f}, {0.44f, 0.9f, 0.8f, 0.5f}};

    private final Part part;
    private final Ui ui;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path road = new Path();
    private final Path backHill = new Path();
    private final Path frontHill = new Path();
    private final RectF rect = new RectF();
    private final float[] carAt = new float[2];
    private final float[] carTangent = new float[2];
    private final DashPathEffect roadDash;
    private final Runnable nextFrame = this::invalidateSelf;
    private PathMeasure roadMeasure;

    Scenery(Part part, Ui ui) {
        this.part = part;
        this.ui = ui;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        roadDash = new DashPathEffect(new float[] {ui.dp(8), ui.dp(8)}, 0);
    }

    Part part() {
        return part;
    }

    /** The ground fills its strip, up to a height where the hills would look stretched. */
    private float groundHeight(float height) {
        return Math.min(ui.dp(180), height);
    }

    @Override protected void onBoundsChange(Rect bounds) {
        if (part != Part.GROUND) return;
        float width = bounds.width();
        float bottom = bounds.bottom;
        float top = bottom - groundHeight(bounds.height());
        float g = bottom - top;
        // Past the edges by more than any layer slides.
        float over = ui.dp(ROAD_DEPTH + 6);
        float left = bounds.left - over;
        float right = bounds.right + over;
        float below = bottom + over;

        backHill.reset();
        backHill.moveTo(left, top + g * 0.35f);
        backHill.cubicTo(width * 0.25f, top + g * 0.05f, width * 0.45f, top + g * 0.45f, width * 0.68f, top + g * 0.2f);
        backHill.cubicTo(width * 0.82f, top + g * 0.05f, width * 0.93f, top + g * 0.2f, right, top + g * 0.3f);
        backHill.lineTo(right, below);
        backHill.lineTo(left, below);
        backHill.close();

        frontHill.reset();
        frontHill.moveTo(left, top + g * 0.62f);
        frontHill.cubicTo(width * 0.3f, top + g * 0.45f, width * 0.6f, top + g * 0.7f, right, top + g * 0.52f);
        frontHill.lineTo(right, below);
        frontHill.lineTo(left, below);
        frontHill.close();

        road.reset();
        road.moveTo(left - ui.dp(24), top + g * 0.86f);
        road.cubicTo(width * 0.35f, top + g * 0.7f, width * 0.65f, top + g * 0.92f, right + ui.dp(24),
                top + g * 0.74f);
        roadMeasure = new PathMeasure(road, false);
    }

    @Override public void draw(Canvas canvas) {
        if (part == Part.SKY) drawSky(canvas);
        else drawGround(canvas);
        if (Motion.on()) {
            unscheduleSelf(nextFrame);
            scheduleSelf(nextFrame, SystemClock.uptimeMillis() + 16);
        }
    }

    /** Moves the canvas by a layer's share of the tilt; the caller restores it. */
    private void slide(Canvas canvas, float depth) {
        canvas.save();
        canvas.translate(Tilt.x() * ui.dp(depth), Tilt.y() * ui.dp(depth) * 0.6f);
    }

    private void drawSky(Canvas canvas) {
        Rect bounds = getBounds();
        float width = bounds.width();
        float height = bounds.height();
        // Off to the right of the title, clear of the round button at the edge.
        float sunX = bounds.left + width * 0.66f;
        float sunY = bounds.top + height * 0.5f;
        slide(canvas, STARS_DEPTH);
        drawStars(canvas, bounds);
        canvas.restore();
        slide(canvas, MOON_DEPTH);
        if (ui.dark) {
            fill.setColor(0xFF2E2D29);
            canvas.drawCircle(sunX, sunY, ui.dp(16), fill);
            fill.setColor(ui.page);
            canvas.drawCircle(sunX + ui.dp(7), sunY - ui.dp(5), ui.dp(14), fill);
        } else {
            float glow = 1 + 0.08f * Motion.wave(7f, 0);
            fill.setColor(0x33F4C74E);
            canvas.drawCircle(sunX, sunY, ui.dp(26) * glow, fill);
            fill.setColor(0xFFF6DE92);
            canvas.drawCircle(sunX, sunY, ui.dp(16), fill);
        }
        canvas.restore();
        int cloud = ui.dark ? 0xFF1A1A19 : 0xFFFBFAF7;
        slide(canvas, CLOUD_DEPTH);
        drawCloud(canvas, drift(bounds, 0.55f, 90f), bounds.top + height * 0.4f, ui.dp(13), cloud);
        drawCloud(canvas, drift(bounds, 0.76f, 130f), bounds.top + height * 0.74f, ui.dp(11), cloud);
        canvas.restore();
        slide(canvas, BIRD_DEPTH);
        drawBirds(canvas, drift(bounds, 0.44f, 60f), bounds.top + height * 0.3f + ui.dp(3) * Motion.wave(5f, 0));
        canvas.restore();
    }

    /**
     * Where something that starts at {@code share} of the width is now, drifting right and crossing the whole sky
     * once every {@code period} seconds, wrapping round past the edges.
     */
    private float drift(Rect bounds, float share, float period) {
        float span = bounds.width() + ui.dp(80);
        float at = (share * bounds.width() + ui.dp(40) + Motion.loop(period, 0) * span) % span;
        return bounds.left - ui.dp(40) + at;
    }

    /** Stars that brighten and fade on their own rhythms (dark mode), or a few soft glints by day. */
    private void drawStars(Canvas canvas, Rect bounds) {
        for (float[] star : STARS) {
            float x = bounds.left + bounds.width() * star[0];
            float y = bounds.top + bounds.height() * star[1];
            float twinkle = 0.5f + 0.5f * Motion.wave(2.2f + star[3] * 2.6f, star[3]);
            if (ui.dark) {
                fill.setColor(blend(0xFF2A2927, 0xFF6E6B62, twinkle));
                canvas.drawCircle(x, y, ui.dp(star[2]) * (0.8f + 0.3f * twinkle), fill);
            } else if (star[2] >= 1.3f) {
                fill.setColor(blend(0x00EBD49A, 0xFFEBD49A, twinkle));
                sparkle(canvas, x, y, ui.dp(2.2f + 2.4f * twinkle));
            }
        }
    }

    /** A four-pointed glint. */
    private void sparkle(Canvas canvas, float x, float y, float half) {
        float waist = half * 0.22f;
        path.reset();
        path.moveTo(x, y - half);
        path.quadTo(x + waist, y - waist, x + half, y);
        path.quadTo(x + waist, y + waist, x, y + half);
        path.quadTo(x - waist, y + waist, x - half, y);
        path.quadTo(x - waist, y - waist, x, y - half);
        path.close();
        canvas.drawPath(path, fill);
    }

    /** {@code from} to {@code to} (colors, alpha included) by {@code share}. */
    private static int blend(int from, int to, float share) {
        int color = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int a = (from >>> shift) & 0xFF;
            int b = (to >>> shift) & 0xFF;
            color |= Math.round(a + (b - a) * share) << shift;
        }
        return color;
    }

    /** Two small birds, a pair of curved strokes each. */
    private void drawBirds(Canvas canvas, float x, float y) {
        line.setPathEffect(null);
        line.setStrokeWidth(Math.max(1, ui.dp(1.5f)));
        line.setColor(ui.dark ? 0xFF3A3935 : 0xFFB9B6AC);
        for (int i = 0; i < 2; i++) {
            float bx = x + i * ui.dp(16);
            float by = y + i * ui.dp(7);
            float wing = ui.dp(i == 0 ? 6 : 5);
            // Wings beat slowly, each bird on its own beat.
            float lift = wing * (0.6f + 0.35f * Motion.wave(1.4f, i * 0.37f));
            path.reset();
            path.moveTo(bx - wing, by);
            path.quadTo(bx - wing / 2, by - lift, bx, by);
            path.quadTo(bx + wing / 2, by - lift, bx + wing, by);
            canvas.drawPath(path, line);
        }
    }

    /** A cloud of three puffs on a flat base, {@code size} being the middle puff's radius. */
    private void drawCloud(Canvas canvas, float x, float y, float size, int color) {
        fill.setColor(color);
        canvas.drawCircle(x - size * 0.9f, y + size * 0.25f, size * 0.7f, fill);
        canvas.drawCircle(x, y, size, fill);
        canvas.drawCircle(x + size * 0.95f, y + size * 0.3f, size * 0.62f, fill);
        rect.set(x - size * 1.6f, y + size * 0.2f, x + size * 1.55f, y + size * 0.92f);
        canvas.drawRoundRect(rect, size * 0.36f, size * 0.36f, fill);
    }

    private void drawGround(Canvas canvas) {
        Rect bounds = getBounds();
        float width = bounds.width();
        float g = groundHeight(bounds.height());
        float top = bounds.bottom - g;

        slide(canvas, BACK_HILL_DEPTH);
        fill.setColor(ui.dark ? 0xFF141413 : 0xFFE8E6DE);
        canvas.drawPath(backHill, fill);
        drawHouse(canvas, bounds.left + width * 0.2f, top + g * 0.2f, ui.dp(22));
        drawTree(canvas, bounds.left + width * 0.33f, top + g * 0.3f, ui.dp(9));
        drawHouse(canvas, bounds.left + width * 0.76f, top + g * 0.16f, ui.dp(18));
        drawTree(canvas, bounds.left + width * 0.88f, top + g * 0.21f, ui.dp(8));
        canvas.restore();

        slide(canvas, FRONT_HILL_DEPTH);
        fill.setColor(ui.dark ? 0xFF111110 : 0xFFE1DFD6);
        canvas.drawPath(frontHill, fill);
        drawTree(canvas, bounds.left + width * 0.08f, top + g * 0.6f, ui.dp(11));
        canvas.restore();

        slide(canvas, ROAD_DEPTH);
        line.setPathEffect(null);
        line.setStrokeWidth(ui.dp(18));
        line.setColor(ui.dark ? 0xFF1E1E1D : 0xFFD6D3C9);
        canvas.drawPath(road, line);
        line.setStrokeWidth(ui.dp(2));
        line.setColor(ui.dark ? 0xFF2C2C2A : 0xFFEFEDE7);
        line.setPathEffect(roadDash);
        canvas.drawPath(road, line);
        line.setPathEffect(null);
        if (roadMeasure != null) {
            // Across the page once every 16 seconds; parked two thirds along with animations off.
            float along = Motion.on() ? Motion.loop(16f, 0.64f) : 0.64f;
            roadMeasure.getPosTan(roadMeasure.getLength() * along, carAt, carTangent);
            drawCar(canvas);
        }
        canvas.restore();
    }

    /** A house sitting on the hill at (x, ground), {@code size} wide, with one lit window. */
    private void drawHouse(Canvas canvas, float x, float ground, float size) {
        float wall = size * 0.75f;
        fill.setColor(ui.dark ? 0xFF1C1C1B : 0xFFDCD9CF);
        rect.set(x - size / 2, ground - wall, x + size / 2, ground + size * 0.15f);
        canvas.drawRect(rect, fill);
        fill.setColor(ui.dark ? 0xFF232321 : 0xFFD2CFC4);
        path.reset();
        path.moveTo(x - size * 0.62f, ground - wall);
        path.lineTo(x, ground - wall - size * 0.5f);
        path.lineTo(x + size * 0.62f, ground - wall);
        path.close();
        canvas.drawPath(path, fill);
        fill.setColor(ui.dark ? 0xFF3B3524 : 0xFFF2E6C4);
        float pane = size * 0.2f;
        rect.set(x - size * 0.3f, ground - wall + size * 0.18f, x - size * 0.3f + pane, ground - wall + size * 0.18f
                + pane);
        canvas.drawRect(rect, fill);
    }

    private void drawTree(Canvas canvas, float x, float ground, float size) {
        fill.setColor(ui.dark ? 0xFF1D1C1A : 0xFFD3CEC2);
        rect.set(x - size * 0.12f, ground - size * 1.1f, x + size * 0.12f, ground + size * 0.2f);
        canvas.drawRect(rect, fill);
        fill.setColor(ui.dark ? 0xFF18201A : 0xFFD2DBC8);
        canvas.drawCircle(x, ground - size * 1.3f, size, fill);
    }

    /** A little round car with a parcel on its roof and eyes in its windshield, riding the road. */
    private void drawCar(Canvas canvas) {
        float angle = (float) Math.toDegrees(Math.atan2(carTangent[1], carTangent[0]));
        canvas.save();
        // A gentle bounce on the road.
        canvas.translate(carAt[0], carAt[1] - ui.dp(0.8f) * Math.abs(Motion.wave(0.9f, 0)));
        canvas.rotate(angle);
        float u = ui.dp(1);
        int body = ui.dark ? 0xFF2D4C73 : 0xFFA9C1E0;
        int glass = ui.dark ? 0xFF3E5F87 : 0xFFE3ECF7;
        int dark = ui.dark ? 0xFF3A3A38 : 0xFF6E6C67;

        fill.setColor(ui.dark ? 0xFF6B5A3E : 0xFFDCC59C);
        rect.set(-9 * u, -27 * u, 3 * u, -19 * u);
        canvas.drawRoundRect(rect, 1.5f * u, 1.5f * u, fill);
        fill.setColor(body);
        rect.set(-16 * u, -20 * u, 10 * u, -8 * u);
        canvas.drawRoundRect(rect, 6 * u, 6 * u, fill);
        rect.set(-20 * u, -13 * u, 20 * u, -1 * u);
        canvas.drawRoundRect(rect, 5 * u, 5 * u, fill);
        fill.setColor(glass);
        rect.set(-1 * u, -18 * u, 9 * u, -11 * u);
        canvas.drawRoundRect(rect, 3 * u, 3 * u, fill);
        fill.setColor(dark);
        canvas.drawCircle(2.5f * u, -14.5f * u, 1.3f * u, fill);
        canvas.drawCircle(6.5f * u, -14.5f * u, 1.3f * u, fill);
        canvas.drawCircle(-11 * u, -1 * u, 4.2f * u, fill);
        canvas.drawCircle(11 * u, -1 * u, 4.2f * u, fill);
        fill.setColor(ui.dark ? 0xFF807F7A : 0xFFE9E7E1);
        canvas.drawCircle(-11 * u, -1 * u, 1.6f * u, fill);
        canvas.drawCircle(11 * u, -1 * u, 1.6f * u, fill);
        canvas.restore();
    }

    @Override public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        line.setAlpha(alpha);
    }

    @Override public void setColorFilter(ColorFilter filter) {
        fill.setColorFilter(filter);
        line.setColorFilter(filter);
    }

    @SuppressWarnings("deprecation")
    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}

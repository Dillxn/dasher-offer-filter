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

/**
 * The drawn backdrop behind the pages. {@link Part#SKY} puts a sun and clouds (a moon and stars in dark mode) behind
 * the page title; {@link Part#GROUND} puts rolling hills, houses, trees and a road with a little delivery car along the
 * bottom of the page area. Every color sits close to the page color, so text over it keeps its contrast.
 */
final class Scenery extends Drawable {
    enum Part { SKY, GROUND }

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

    Scenery(Part part, Ui ui) {
        this.part = part;
        this.ui = ui;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    Part part() {
        return part;
    }

    /** How tall the ground is for a page area {@code height} high. */
    private float groundHeight(float height) {
        return Math.min(ui.dp(150), height * 0.4f);
    }

    @Override protected void onBoundsChange(Rect bounds) {
        if (part != Part.GROUND) return;
        float width = bounds.width();
        float bottom = bounds.bottom;
        float top = bottom - groundHeight(bounds.height());
        float g = bottom - top;

        backHill.reset();
        backHill.moveTo(bounds.left, top + g * 0.35f);
        backHill.cubicTo(width * 0.25f, top + g * 0.05f, width * 0.45f, top + g * 0.45f, width * 0.68f, top + g * 0.2f);
        backHill.cubicTo(width * 0.82f, top + g * 0.05f, width * 0.93f, top + g * 0.2f, bounds.right, top + g * 0.3f);
        backHill.lineTo(bounds.right, bottom);
        backHill.lineTo(bounds.left, bottom);
        backHill.close();

        frontHill.reset();
        frontHill.moveTo(bounds.left, top + g * 0.62f);
        frontHill.cubicTo(width * 0.3f, top + g * 0.45f, width * 0.6f, top + g * 0.7f, bounds.right, top + g * 0.52f);
        frontHill.lineTo(bounds.right, bottom);
        frontHill.lineTo(bounds.left, bottom);
        frontHill.close();

        road.reset();
        road.moveTo(bounds.left - ui.dp(10), top + g * 0.86f);
        road.cubicTo(width * 0.35f, top + g * 0.7f, width * 0.65f, top + g * 0.92f, bounds.right + ui.dp(10),
                top + g * 0.74f);
        PathMeasure measure = new PathMeasure(road, false);
        measure.getPosTan(measure.getLength() * 0.64f, carAt, carTangent);
    }

    @Override public void draw(Canvas canvas) {
        if (part == Part.SKY) drawSky(canvas);
        else drawGround(canvas);
    }

    private void drawSky(Canvas canvas) {
        Rect bounds = getBounds();
        float width = bounds.width();
        float height = bounds.height();
        float sunX = bounds.right - ui.dp(52);
        float sunY = bounds.top + height * 0.55f;
        if (ui.dark) {
            fill.setColor(0xFF2E2D29);
            canvas.drawCircle(sunX, sunY, ui.dp(16), fill);
            fill.setColor(ui.page);
            canvas.drawCircle(sunX + ui.dp(7), sunY - ui.dp(5), ui.dp(14), fill);
            fill.setColor(0xFF3A3935);
            float[][] stars = {{0.42f, 0.25f}, {0.58f, 0.7f}, {0.7f, 0.2f}, {0.93f, 0.85f}, {0.5f, 0.9f}};
            for (float[] star : stars) {
                canvas.drawCircle(bounds.left + width * star[0], bounds.top + height * star[1], ui.dp(1.6f), fill);
            }
        } else {
            fill.setColor(0x33F4C74E);
            canvas.drawCircle(sunX, sunY, ui.dp(26), fill);
            fill.setColor(0xFFF6DE92);
            canvas.drawCircle(sunX, sunY, ui.dp(16), fill);
        }
        int cloud = ui.dark ? 0xFF1A1A19 : 0xFFFBFAF7;
        drawCloud(canvas, bounds.left + width * 0.6f, bounds.top + height * 0.42f, ui.dp(14), cloud);
        drawCloud(canvas, bounds.right - ui.dp(20), bounds.top + height * 0.78f, ui.dp(11), cloud);
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

        fill.setColor(ui.dark ? 0xFF141413 : 0xFFE8E6DE);
        canvas.drawPath(backHill, fill);
        drawHouse(canvas, bounds.left + width * 0.2f, top + g * 0.2f, ui.dp(22));
        drawTree(canvas, bounds.left + width * 0.33f, top + g * 0.3f, ui.dp(9));
        drawHouse(canvas, bounds.left + width * 0.76f, top + g * 0.16f, ui.dp(18));
        drawTree(canvas, bounds.left + width * 0.88f, top + g * 0.21f, ui.dp(8));

        fill.setColor(ui.dark ? 0xFF111110 : 0xFFE1DFD6);
        canvas.drawPath(frontHill, fill);
        drawTree(canvas, bounds.left + width * 0.08f, top + g * 0.6f, ui.dp(11));

        line.setPathEffect(null);
        line.setStrokeWidth(ui.dp(18));
        line.setColor(ui.dark ? 0xFF1E1E1D : 0xFFD6D3C9);
        canvas.drawPath(road, line);
        line.setStrokeWidth(ui.dp(2));
        line.setColor(ui.dark ? 0xFF2C2C2A : 0xFFEFEDE7);
        line.setPathEffect(new DashPathEffect(new float[] {ui.dp(8), ui.dp(8)}, 0));
        canvas.drawPath(road, line);
        line.setPathEffect(null);
        drawCar(canvas);
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
        canvas.translate(carAt[0], carAt[1]);
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

package com.local.dasherfilter;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * The app's small line icons, drawn on a 24-unit grid so they scale to any size without image files: a clock for
 * minutes, a road for miles, a pin for stops, a coin for pay, a bag for the pickup, a house for the drop-off, a
 * rising line for the adaptive minimum, dots for the stop count, sliders for settings, a back arrow, a warning
 * sign, and the chevron at the end of a row that opens something.
 */
final class Glyph extends Drawable {
    enum Shape { CLOCK, ROAD, PIN, COIN, BAG, HOME, TREND, STOPS, SLIDERS, BACK, SIGN, CHEVRON }

    private final Shape shape;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();

    Glyph(Shape shape, int color, int sizePx) {
        this.shape = shape;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(color);
        fill.setColor(color);
        fill.setTextAlign(Paint.Align.CENTER);
        fill.setFakeBoldText(true);
        fill.setTextSize(13f);
        setBounds(0, 0, sizePx, sizePx);
    }

    /** Draws a glyph of {@code size} pixels with its top-left corner at (x, y), outside any drawable bounds. */
    static void draw(Canvas canvas, Shape shape, int color, float x, float y, float size) {
        Glyph glyph = new Glyph(shape, color, Math.round(size));
        canvas.save();
        canvas.translate(x, y);
        glyph.draw(canvas);
        canvas.restore();
    }

    @Override public void draw(Canvas canvas) {
        float scale = getBounds().width() / 24f;
        canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(scale, scale);
        path.reset();
        switch (shape) {
            case CLOCK:
                canvas.drawCircle(12, 12, 9, stroke);
                canvas.drawLine(12, 12, 12, 7, stroke);
                canvas.drawLine(12, 12, 15.5f, 14, stroke);
                break;
            case ROAD:
                canvas.drawLine(5, 21, 9, 3, stroke);
                canvas.drawLine(19, 21, 15, 3, stroke);
                canvas.drawLine(12, 4.5f, 12, 7, stroke);
                canvas.drawLine(12, 10.5f, 12, 13.5f, stroke);
                canvas.drawLine(12, 17, 12, 20, stroke);
                break;
            case PIN:
                path.moveTo(12, 21);
                path.cubicTo(12, 21, 5, 14.5f, 5, 10);
                path.cubicTo(5, 6.1f, 8.1f, 3, 12, 3);
                path.cubicTo(15.9f, 3, 19, 6.1f, 19, 10);
                path.cubicTo(19, 14.5f, 12, 21, 12, 21);
                path.close();
                canvas.drawPath(path, stroke);
                canvas.drawCircle(12, 10, 2.5f, stroke);
                break;
            case COIN:
                canvas.drawCircle(12, 12, 9, stroke);
                canvas.drawText("$", 12, 16.5f, fill);
                break;
            case BAG:
                oval.set(5, 8, 19, 21);
                canvas.drawRoundRect(oval, 2, 2, stroke);
                oval.set(9, 3.5f, 15, 10.5f);
                canvas.drawArc(oval, 180, 180, false, stroke);
                break;
            case HOME:
                path.moveTo(3.5f, 11.5f);
                path.lineTo(12, 4);
                path.lineTo(20.5f, 11.5f);
                canvas.drawPath(path, stroke);
                path.reset();
                path.moveTo(6.5f, 10);
                path.lineTo(6.5f, 20);
                path.lineTo(17.5f, 20);
                path.lineTo(17.5f, 10);
                canvas.drawPath(path, stroke);
                path.reset();
                path.moveTo(10.5f, 20);
                path.lineTo(10.5f, 15);
                path.lineTo(13.5f, 15);
                path.lineTo(13.5f, 20);
                canvas.drawPath(path, stroke);
                break;
            case TREND:
                path.moveTo(3, 17);
                path.lineTo(9, 11);
                path.lineTo(13, 15);
                path.lineTo(21, 7);
                canvas.drawPath(path, stroke);
                path.reset();
                path.moveTo(15, 7);
                path.lineTo(21, 7);
                path.lineTo(21, 13);
                canvas.drawPath(path, stroke);
                break;
            case STOPS:
                canvas.drawLine(3, 12, 21, 12, stroke);
                canvas.drawCircle(5, 12, 2.2f, fill);
                canvas.drawCircle(12, 12, 2.2f, fill);
                canvas.drawCircle(19, 12, 2.2f, fill);
                break;
            case BACK:
                canvas.drawLine(20, 12, 4.5f, 12, stroke);
                path.moveTo(11, 5);
                path.lineTo(4, 12);
                path.lineTo(11, 19);
                canvas.drawPath(path, stroke);
                break;
            case SIGN:
                path.moveTo(12, 3.5f);
                path.lineTo(21, 19.5f);
                path.lineTo(3, 19.5f);
                path.close();
                canvas.drawPath(path, stroke);
                canvas.drawLine(12, 9.5f, 12, 13.5f, stroke);
                canvas.drawCircle(12, 16.5f, 1.1f, fill);
                break;
            case CHEVRON:
                path.moveTo(9.5f, 6);
                path.lineTo(15.5f, 12);
                path.lineTo(9.5f, 18);
                canvas.drawPath(path, stroke);
                break;
            case SLIDERS:
                canvas.drawLine(3, 7, 21, 7, stroke);
                canvas.drawLine(3, 17, 21, 17, stroke);
                canvas.drawCircle(9, 7, 2.6f, fill);
                canvas.drawCircle(15, 17, 2.6f, fill);
                break;
        }
        canvas.restore();
    }

    @Override public int getIntrinsicWidth() {
        return getBounds().width();
    }

    @Override public int getIntrinsicHeight() {
        return getBounds().height();
    }

    @Override public void setAlpha(int alpha) {
        stroke.setAlpha(alpha);
        fill.setAlpha(alpha);
    }

    @Override public void setColorFilter(ColorFilter filter) {
        stroke.setColorFilter(filter);
        fill.setColorFilter(filter);
    }

    @SuppressWarnings("deprecation")
    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}

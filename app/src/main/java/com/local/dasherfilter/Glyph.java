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
 * rising line for the adaptive minimum, the funnel that stands for the filter itself, a chart, a folded map,
 * sliders for settings, a back arrow, a bell, a warning sign, a sparkle, a letter and a refresh arrow.
 */
final class Glyph extends Drawable {
    enum Shape { CLOCK, ROAD, PIN, COIN, BAG, HOME, TREND, FUNNEL, STOPS, CHART, MAP, SLIDERS, BACK, BELL, SIGN, SPARKLE, LETTER, REFRESH }

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
            case FUNNEL:
                path.moveTo(3, 4);
                path.lineTo(21, 4);
                path.lineTo(14, 12.5f);
                path.lineTo(14, 20);
                path.lineTo(10, 18);
                path.lineTo(10, 12.5f);
                path.close();
                canvas.drawPath(path, stroke);
                break;
            case STOPS:
                canvas.drawLine(3, 12, 21, 12, stroke);
                canvas.drawCircle(5, 12, 2.2f, fill);
                canvas.drawCircle(12, 12, 2.2f, fill);
                canvas.drawCircle(19, 12, 2.2f, fill);
                break;
            case CHART:
                canvas.drawLine(3, 21, 21, 21, stroke);
                canvas.drawLine(6.5f, 20, 6.5f, 13, stroke);
                canvas.drawLine(12, 20, 12, 5, stroke);
                canvas.drawLine(17.5f, 20, 17.5f, 10, stroke);
                break;
            case MAP:
                path.moveTo(3, 6);
                path.lineTo(9, 4);
                path.lineTo(15, 6);
                path.lineTo(21, 4);
                path.lineTo(21, 18);
                path.lineTo(15, 20);
                path.lineTo(9, 18);
                path.lineTo(3, 20);
                path.close();
                canvas.drawPath(path, stroke);
                canvas.drawLine(9, 4, 9, 18, stroke);
                canvas.drawLine(15, 6, 15, 20, stroke);
                break;
            case BACK:
                canvas.drawLine(20, 12, 4.5f, 12, stroke);
                path.moveTo(11, 5);
                path.lineTo(4, 12);
                path.lineTo(11, 19);
                canvas.drawPath(path, stroke);
                break;
            case BELL:
                path.moveTo(5, 17);
                path.lineTo(6.5f, 15);
                path.lineTo(6.5f, 10.5f);
                path.cubicTo(6.5f, 7, 9, 4.5f, 12, 4.5f);
                path.cubicTo(15, 4.5f, 17.5f, 7, 17.5f, 10.5f);
                path.lineTo(17.5f, 15);
                path.lineTo(19, 17);
                path.close();
                canvas.drawPath(path, stroke);
                canvas.drawLine(10, 20, 14, 20, stroke);
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
            case SPARKLE:
                path.moveTo(12, 3);
                path.quadTo(13, 11, 21, 12);
                path.quadTo(13, 13, 12, 21);
                path.quadTo(11, 13, 3, 12);
                path.quadTo(11, 11, 12, 3);
                path.close();
                canvas.drawPath(path, stroke);
                break;
            case LETTER:
                oval.set(3, 6, 21, 18);
                canvas.drawRoundRect(oval, 2, 2, stroke);
                path.moveTo(3.5f, 7);
                path.lineTo(12, 13);
                path.lineTo(20.5f, 7);
                canvas.drawPath(path, stroke);
                break;
            case REFRESH:
                oval.set(5, 5, 19, 19);
                canvas.drawArc(oval, -60, 300, false, stroke);
                path.moveTo(15.5f, 3.5f);
                path.lineTo(15.6f, 7.6f);
                path.lineTo(19.5f, 7);
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

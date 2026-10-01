package com.local.dasherfilter;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * The app's small line icons, drawn on a 24-unit grid so they scale to any size without image files: a clock for
 * minutes, a road for miles, a pin for stops, a coin for pay, a bag for the pickup, a house for the drop-off, a
 * rising line for the adaptive minimum, dots for the stop count, sliders for settings, a back arrow, a warning
 * sign, the chevron at the end of a row that opens something, a dashed shape passing into a solid one for making the
 * learned minimums the set ones (in the constellation's learned and set colors, where it is given them), a curved
 * arrow back for undoing that, and an area chart (two crossing spokes and an offer's shape on them) for score by area,
 * its shape filled while the drawable's level is above 0, that is while score by area is on.
 */
final class Glyph extends Drawable {
    enum Shape {
        CLOCK, ROAD, PIN, HOTSPOT, COIN, BAG, HOME, TREND, STOPS, SLIDERS, BACK, SIGN, CHEVRON, SPLIT, ADOPT, UNDO, AREA
    }

    private final Shape shape;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    /**
     * The learned minimums' dashes at this icon's scale, long dashes and short gaps as on the constellation: one dash
     * to each side of the adopt icon's dashed shape (sides of 7.34), bent round its corner, and one gap in the middle
     * of each side; the shape is drawn from the middle of a side, so it starts and ends in a gap.
     */
    private final DashPathEffect dashes = new DashPathEffect(new float[] {4.08f, 3.26f}, 5.71f);
    /** The adopt icon's two shapes: the learned (dashed) one and the set (solid) one; the ink until given colors. */
    private int learnedInk;
    private int setInk;

    Glyph(Shape shape, int color, int sizePx) {
        this.shape = shape;
        learnedInk = color;
        setInk = color;
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

    /** The adopt icon's dashed shape in {@code learned} and its solid one in {@code set}, as on the constellation. */
    void setAccents(int learned, int set) {
        if (learned == learnedInk && set == setInk) return;
        learnedInk = learned;
        setInk = set;
        invalidateSelf();
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
            case HOTSPOT:
                // A destination point within a hot area's rings, distinct from the per-stop pin.
                canvas.drawCircle(12, 12, 3, fill);
                oval.set(6, 6, 18, 18);
                canvas.drawArc(oval, -55, 290, false, stroke);
                oval.set(2.5f, 2.5f, 21.5f, 21.5f);
                canvas.drawArc(oval, -35, 110, false, stroke);
                canvas.drawArc(oval, 145, 110, false, stroke);
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
            case SPLIT:
                // A phone split in two, with a pin in its lower half: Dasher's map under Offer Filter.
                oval.set(6, 2.5f, 18, 21.5f);
                canvas.drawRoundRect(oval, 2.5f, 2.5f, stroke);
                canvas.drawLine(6, 12, 18, 12, stroke);
                canvas.drawCircle(12, 16.3f, 1.8f, fill);
                canvas.drawLine(9, 7.2f, 15, 7.2f, stroke);
                break;
            case ADOPT: {
                // The learned (dashed) shape passing into the set (solid) one, as on the constellation: dashed and
                // purple, then a solid outline with a faint fill in blue, the chevron between them in the ink.
                int ink = stroke.getColor();
                float width = stroke.getStrokeWidth();
                path.moveTo(3.2f, 8.8f);
                path.lineTo(5, 5.6f);
                path.lineTo(8.6f, 12);
                path.lineTo(5, 18.4f);
                path.lineTo(1.4f, 12);
                path.close();
                stroke.setColor(learnedInk);
                stroke.setStrokeWidth(1.6f);
                stroke.setStrokeCap(Paint.Cap.BUTT);
                stroke.setPathEffect(dashes);
                canvas.drawPath(path, stroke);
                stroke.setPathEffect(null);
                stroke.setStrokeCap(Paint.Cap.ROUND);
                path.reset();
                path.moveTo(19, 5.6f);
                path.lineTo(22.6f, 12);
                path.lineTo(19, 18.4f);
                path.lineTo(15.4f, 12);
                path.close();
                int fillInk = fill.getColor();
                fill.setColor((setInk & 0x00FFFFFF) | 0x40000000);
                canvas.drawPath(path, fill);
                fill.setColor(fillInk);
                stroke.setColor(setInk);
                stroke.setStrokeWidth(1.8f);
                canvas.drawPath(path, stroke);
                stroke.setColor(ink);
                stroke.setStrokeWidth(width);
                path.reset();
                path.moveTo(10.9f, 9.8f);
                path.lineTo(13.1f, 12);
                path.lineTo(10.9f, 14.2f);
                canvas.drawPath(path, stroke);
                break;
            }
            case AREA: {
                // An area chart: the constellation's two crossing spokes and an offer's shape on them; lit (score by
                // area on), the shape is filled.
                int ink = stroke.getColor();
                float width = stroke.getStrokeWidth();
                stroke.setColor((learnedInk & 0x00FFFFFF) | 0x80000000);
                stroke.setStrokeWidth(1.1f);
                canvas.drawLine(2.9f, 6.75f, 21.1f, 17.25f, stroke);
                canvas.drawLine(21.1f, 6.75f, 2.9f, 17.25f, stroke);
                path.moveTo(3.35f, 7f);
                path.lineTo(18.05f, 8.5f);
                path.lineTo(20.65f, 17f);
                path.lineTo(6.35f, 15.25f);
                path.close();
                if (getLevel() > 0) {
                    int fillInk = fill.getColor();
                    fill.setColor((setInk & 0x00FFFFFF) | 0x70000000);
                    canvas.drawPath(path, fill);
                    fill.setColor(fillInk);
                }
                stroke.setColor(setInk);
                stroke.setStrokeWidth(1.8f);
                canvas.drawPath(path, stroke);
                stroke.setColor(ink);
                stroke.setStrokeWidth(width);
                break;
            }
            case UNDO:
                // A curved arrow back.
                path.moveTo(9, 14);
                path.lineTo(4, 9);
                path.lineTo(9, 4);
                canvas.drawPath(path, stroke);
                path.reset();
                path.moveTo(4, 9);
                path.lineTo(14.5f, 9);
                oval.set(9, 9, 20, 20);
                path.arcTo(oval, -90, 180);
                path.lineTo(11, 20);
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

    @Override protected boolean onLevelChange(int level) {
        invalidateSelf();
        return true;
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

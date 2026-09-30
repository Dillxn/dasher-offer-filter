package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * The drawn shapes the screen is dressed in instead of plain boxes: a wavy underline for headings, a price tag for
 * each rule field, a ticket with a torn-off stub for an opened offer, a speech bubble for the mascot, and a switch
 * whose knob is the mascot's face. All are backgrounds of ordinary views, so the views keep their meaning.
 */
final class Decor {
    private Decor() {}

    /** Shared plumbing: paints and a translucent, unfiltered drawable. */
    private abstract static class Shape extends Drawable {
        final Ui ui;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        final RectF rect = new RectF();

        Shape(Ui ui) {
            this.ui = ui;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
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

    /** A hand-drawn wave under a heading. */
    static final class Squiggle extends Shape {
        Squiggle(Ui ui) {
            super(ui);
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float y = bounds.bottom - ui.dp(4);
            float wave = ui.dp(8);
            path.reset();
            path.moveTo(bounds.left, y);
            for (float x = bounds.left; x < bounds.right; x += wave) {
                path.quadTo(x + wave / 4, y - ui.dp(2.5f), x + wave / 2, y);
                path.quadTo(x + wave * 3 / 4, y + ui.dp(2.5f), x + wave, y);
            }
            line.setColor((ui.accent & 0x00FFFFFF) | 0x99000000);
            line.setStrokeWidth(ui.dp(2));
            canvas.save();
            canvas.clipRect(bounds);
            canvas.drawPath(path, line);
            canvas.restore();
        }
    }

    /** A price tag pointing left, with a punched hole and a loop of string. */
    static final class Tag extends Shape {
        Tag(Ui ui) {
            super(ui);
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float point = ui.dp(18);
            float radius = ui.dp(10);
            float middle = b.exactCenterY();
            path.reset();
            path.moveTo(b.left + point, b.top + ui.dp(1));
            path.lineTo(b.right - radius, b.top + ui.dp(1));
            rect.set(b.right - 2 * radius - ui.dp(1), b.top + ui.dp(1), b.right - ui.dp(1), b.top + 2 * radius);
            path.arcTo(rect, -90, 90);
            path.lineTo(b.right - ui.dp(1), b.bottom - radius);
            rect.set(b.right - 2 * radius - ui.dp(1), b.bottom - 2 * radius - ui.dp(1), b.right - ui.dp(1),
                    b.bottom - ui.dp(1));
            path.arcTo(rect, 0, 90);
            path.lineTo(b.left + point, b.bottom - ui.dp(1));
            path.lineTo(b.left + ui.dp(1), middle);
            path.close();
            fill.setColor(ui.dark ? 0xFF2A2721 : 0xFFFFF8E8);
            canvas.drawPath(path, fill);
            line.setColor(ui.dark ? 0xFF4A4337 : 0xFFE4D5AE);
            line.setStrokeWidth(Math.max(1, ui.dp(1.5f)));
            canvas.drawPath(path, line);
            float holeX = b.left + point + ui.dp(1);
            fill.setColor(ui.page);
            canvas.drawCircle(holeX, middle, ui.dp(4), fill);
            canvas.drawCircle(holeX, middle, ui.dp(4), line);
            // The string: a loop through the hole.
            line.setColor(ui.dark ? 0xFF6E6557 : 0xFFB9A57A);
            line.setStrokeWidth(Math.max(1, ui.dp(1.2f)));
            path.reset();
            path.moveTo(holeX, middle);
            path.cubicTo(holeX - ui.dp(10), middle - ui.dp(14), holeX - ui.dp(16), middle + ui.dp(2),
                    b.left + ui.dp(3), b.top + ui.dp(6));
            canvas.drawPath(path, line);
        }
    }

    /**
     * An offer ticket: a stub on top for the stamp, torn along a perforation with a notch on each side. The stub is
     * {@link #STUB_DP} tall until {@link #setStub} says how tall its content turned out.
     */
    static final class Ticket extends Shape {
        static final int STUB_DP = 52;
        private int stub;

        Ticket(Ui ui) {
            super(ui);
            stub = ui.dp(STUB_DP);
        }

        void setStub(int height) {
            if (height == stub || height <= 0) return;
            stub = height;
            invalidateSelf();
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float notch = ui.dp(9);
            float cut = b.top + stub;
            rect.set(b.left + ui.dp(1), b.top + ui.dp(1), b.right - ui.dp(1), b.bottom - ui.dp(1));
            path.reset();
            path.addRoundRect(rect, ui.dp(14), ui.dp(14), Path.Direction.CW);
            Path notches = new Path();
            notches.addCircle(b.left, cut, notch, Path.Direction.CW);
            notches.addCircle(b.right, cut, notch, Path.Direction.CW);
            path.op(notches, Path.Op.DIFFERENCE);
            fill.setColor(ui.dark ? 0xFF22211F : 0xFFFFFDF7);
            canvas.drawPath(path, fill);
            line.setColor(ui.dark ? 0xFF3A3935 : 0xFFE3DFD2);
            line.setStrokeWidth(Math.max(1, ui.dp(1.5f)));
            canvas.drawPath(path, line);
            line.setPathEffect(new DashPathEffect(new float[] {ui.dp(5), ui.dp(5)}, 0));
            canvas.drawLine(b.left + notch + ui.dp(4), cut, b.right - notch - ui.dp(4), cut, line);
            line.setPathEffect(null);
        }
    }

    /** A speech bubble whose tail points down at the mascot. {@link #TAIL_DP} is the tail's height. */
    static final class Bubble extends Shape {
        static final int TAIL_DP = 14;

        Bubble(Ui ui) {
            super(ui);
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float tail = ui.dp(TAIL_DP);
            float middle = b.exactCenterX();
            rect.set(b.left + ui.dp(1), b.top + ui.dp(1), b.right - ui.dp(1), b.bottom - tail);
            path.reset();
            path.addRoundRect(rect, ui.dp(20), ui.dp(20), Path.Direction.CW);
            Path point = new Path();
            point.moveTo(middle - ui.dp(12), b.bottom - tail - ui.dp(2));
            point.lineTo(middle - ui.dp(2), b.bottom - ui.dp(1));
            point.lineTo(middle + ui.dp(12), b.bottom - tail - ui.dp(2));
            point.close();
            path.op(point, Path.Op.UNION);
            fill.setColor(ui.surface);
            canvas.drawPath(path, fill);
            line.setColor(ui.dark ? 0x40FFFFFF : 0x330B0B0B);
            line.setStrokeWidth(Math.max(1, ui.dp(1.5f)));
            canvas.drawPath(path, line);
        }
    }

    /**
     * A rubber stamp for an offer's outcome, "PASSED", "DECLINED" or "REVIEW", pressed on at a slight angle in the
     * outcome's ink. The word is also its accessibility text.
     */
    @SuppressLint("ViewConstructor")
    static final class Stamp extends View {
        private final Ui ui;
        private final OfferRule.Result result;
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        Stamp(Context context, Ui ui, OfferRule.Result result) {
            super(context);
            this.ui = ui;
            this.result = result;
            ink.setFakeBoldText(true);
            ink.setTextAlign(Paint.Align.CENTER);
            ink.setLetterSpacing(0.12f);
            setContentDescription(Ui.resultLabel(result));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        private String word() {
            return Ui.resultLabel(result).toUpperCase(java.util.Locale.US);
        }

        private int color() {
            if (result == OfferRule.Result.KEEP) return ui.dark ? 0xFF53C953 : 0xFF0E8A0E;
            if (result == OfferRule.Result.DECLINE) return ui.dark ? 0xFFFF6B6B : 0xFFC62828;
            return ui.dark ? 0xFFF5B83D : 0xFFA86A00;
        }

        private float size() {
            return Math.min(ui.sp(15), ui.dp(22));
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            ink.setTextSize(size());
            int width = Math.round(ink.measureText(word()) + ui.dp(34));
            setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(Math.round(size() * 2.4f), heightSpec));
        }

        @Override protected void onDraw(Canvas canvas) {
            ink.setTextSize(size());
            ink.setColor(color());
            canvas.save();
            canvas.rotate(-7, getWidth() / 2f, getHeight() / 2f);
            rect.set(ui.dp(8), getHeight() * 0.18f, getWidth() - ui.dp(8), getHeight() * 0.82f);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(ui.dp(2.5f));
            // A worn border: long dashes with small gaps, like ink that did not quite take.
            ink.setPathEffect(new DashPathEffect(new float[] {ui.dp(22), ui.dp(2), ui.dp(9), ui.dp(3)}, 0));
            canvas.drawRoundRect(rect, ui.dp(5), ui.dp(5), ink);
            ink.setPathEffect(null);
            ink.setStyle(Paint.Style.FILL);
            Paint.FontMetrics metrics = ink.getFontMetrics();
            canvas.drawText(word(), getWidth() / 2f, getHeight() / 2f - (metrics.ascent + metrics.descent) / 2, ink);
            canvas.restore();
        }
    }

    /** A switch knob with the mascot's face: awake when on, asleep when off. */
    static final class FaceKnob extends Shape {
        private final boolean on;

        FaceKnob(Ui ui, boolean on) {
            super(ui);
            this.on = on;
        }

        @Override public int getIntrinsicWidth() {
            return ui.dp(32);
        }

        @Override public int getIntrinsicHeight() {
            return ui.dp(32);
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float radius = Math.min(b.width(), b.height()) / 2f - ui.dp(3);
            fill.setColor(0x26000000);
            canvas.drawCircle(cx, cy + ui.dp(1.5f), radius, fill);
            fill.setColor(0xFFFFFFFF);
            canvas.drawCircle(cx, cy, radius, fill);
            line.setColor(on ? ui.accent : ui.inkMuted);
            line.setStrokeWidth(ui.dp(2));
            canvas.drawCircle(cx, cy, radius, line);
            Mascot.face(canvas, on ? Mascot.Mood.HAPPY : Mascot.Mood.SLEEPY, cx, cy + ui.dp(1), radius * 1.25f,
                    0xFF2B2A27);
        }
    }

    /** The switch's track: a pill, tinted when on. */
    static final class Track extends Shape {
        private final boolean on;

        Track(Ui ui, boolean on) {
            super(ui);
            this.on = on;
        }

        @Override public int getIntrinsicHeight() {
            return ui.dp(32);
        }

        @Override public int getIntrinsicWidth() {
            return ui.dp(58);
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            rect.set(b.left + ui.dp(2), b.top + ui.dp(5), b.right - ui.dp(2), b.bottom - ui.dp(5));
            fill.setColor(on ? (ui.accent & 0x00FFFFFF) | 0x80000000 : ui.gridline);
            canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill);
        }
    }
}

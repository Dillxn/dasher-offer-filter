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
 * The few drawn shapes the screen keeps: a ticket with a torn-off stub for an opened offer, its rubber stamp, and a
 * switch whose knob is the mascot's face. The shapes are backgrounds of ordinary views, so the views keep their
 * meaning.
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

    /**
     * A rubber stamp for an offer's outcome, "PASSED", "DECLINED" or "REVIEW", pressed on at a slight angle in the
     * outcome's ink; it thumps down when its ticket opens. The word is also its accessibility text.
     */
    @SuppressLint("ViewConstructor")
    static final class Stamp extends View {
        private final Ui ui;
        private final OfferRule.Result result;
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final DashPathEffect worn;
        private long pressedAt;

        Stamp(Context context, Ui ui, OfferRule.Result result) {
            super(context);
            this.ui = ui;
            this.result = result;
            ink.setFakeBoldText(true);
            ink.setTextAlign(Paint.Align.CENTER);
            ink.setLetterSpacing(0.12f);
            worn = new DashPathEffect(new float[] {ui.dp(22), ui.dp(2), ui.dp(9), ui.dp(3)}, 0);
            setContentDescription(Ui.resultLabel(result));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            pressedAt = android.os.SystemClock.uptimeMillis();
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
            float press = Motion.settle(pressedAt, 320);
            ink.setTextSize(size());
            ink.setColor(color());
            ink.setAlpha(Math.round(255 * Math.min(1f, press * 1.6f)));
            canvas.save();
            // Comes down from a little larger and lands at its slant.
            float scale = 1 + 0.45f * (1 - press);
            canvas.scale(scale, scale, getWidth() / 2f, getHeight() / 2f);
            canvas.rotate(-7 - 5 * (1 - press), getWidth() / 2f, getHeight() / 2f);
            rect.set(ui.dp(8), getHeight() * 0.18f, getWidth() - ui.dp(8), getHeight() * 0.82f);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(ui.dp(2.5f));
            // A worn border: long dashes with small gaps, like ink that did not quite take.
            ink.setPathEffect(worn);
            canvas.drawRoundRect(rect, ui.dp(5), ui.dp(5), ink);
            ink.setPathEffect(null);
            ink.setStyle(Paint.Style.FILL);
            Paint.FontMetrics metrics = ink.getFontMetrics();
            canvas.drawText(word(), getWidth() / 2f, getHeight() / 2f - (metrics.ascent + metrics.descent) / 2, ink);
            canvas.restore();
            if (press < 1) Motion.next(this);
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

package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * The app's mascot: the filter funnel with a face. Its mood follows the filter: cheerful while on, asleep while
 * paused, blank while off. Decoration only; the words next to it say what the state is.
 */
final class Mascot {
    enum Mood { HAPPY, SLEEPY, IDLE }

    static final int CHEEK = 0x66F28B8B;

    private Mascot() {}

    static Mood moodOf(FilterHeroView.State state) {
        return state == FilterHeroView.State.ON ? Mood.HAPPY
                : state == FilterHeroView.State.PAUSED ? Mood.SLEEPY : Mood.IDLE;
    }

    /** A face about {@code size} across, centered at (x, y). */
    static void face(Canvas canvas, Mood mood, float x, float y, float size, int ink) {
        float u = size / 28f;
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeWidth(Math.max(1f, 1.8f * u));
        stroke.setColor(ink);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF oval = new RectF();
        float eyeY = y - 3 * u;
        float eyeX = 6.5f * u;
        if (mood != Mood.IDLE) {
            fill.setColor(CHEEK);
            canvas.drawCircle(x - 11 * u, y + 3 * u, 3 * u, fill);
            canvas.drawCircle(x + 11 * u, y + 3 * u, 3 * u, fill);
        }
        fill.setColor(ink);
        switch (mood) {
            case HAPPY:
                for (int side = -1; side <= 1; side += 2) {
                    fill.setColor(ink);
                    canvas.drawCircle(x + side * eyeX, eyeY, 2.6f * u, fill);
                    fill.setColor(0xFFFFFFFF);
                    canvas.drawCircle(x + side * eyeX - 0.9f * u, eyeY - 1f * u, 0.9f * u, fill);
                }
                oval.set(x - 4.5f * u, y - 1 * u, x + 4.5f * u, y + 6 * u);
                canvas.drawArc(oval, 20, 140, false, stroke);
                break;
            case SLEEPY:
                for (int side = -1; side <= 1; side += 2) {
                    oval.set(x + side * eyeX - 3 * u, eyeY - 2.5f * u, x + side * eyeX + 3 * u, eyeY + 2 * u);
                    canvas.drawArc(oval, 10, 160, false, stroke);
                }
                canvas.drawCircle(x, y + 4.5f * u, 1.4f * u, stroke);
                break;
            case IDLE:
                canvas.drawCircle(x - eyeX, eyeY, 1.8f * u, fill);
                canvas.drawCircle(x + eyeX, eyeY, 1.8f * u, fill);
                canvas.drawLine(x - 3 * u, y + 4.5f * u, x + 3 * u, y + 4.5f * u, stroke);
                break;
        }
    }

    /** "z z" drifting up from a sleeping mascot, the second one smaller and higher. */
    static void snore(Canvas canvas, float x, float y, float size, int ink) {
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(ink);
        text.setFakeBoldText(true);
        text.setTextSize(size);
        canvas.drawText("z", x, y, text);
        text.setTextSize(size * 0.75f);
        canvas.drawText("z", x + size * 0.7f, y - size * 0.7f, text);
    }

    /**
     * The whole character standing up: rim on top, a body narrowing to the spout, two feet, little arms (one waving
     * when happy) and the face.
     */
    static void figure(Canvas canvas, Mood mood, float cx, float top, float height, Ui ui) {
        int color = mood == Mood.IDLE ? ui.inkMuted : ui.accent;
        float u = height / 100f;
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(Math.max(1.5f, 2.6f * u));
        stroke.setColor(color);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF oval = new RectF();
        Path path = new Path();

        float rimY = top + 12 * u;
        float half = 40 * u;
        float neckY = top + 68 * u;
        float neck = 9 * u;
        float spoutY = top + 84 * u;

        // Arms first, so the body covers where they join.
        float armY = top + 40 * u;
        float side = half - (half - neck) * (armY - rimY) / (neckY - rimY);
        path.moveTo(cx - side, armY);
        path.quadTo(cx - side - 12 * u, armY + 4 * u, cx - side - 14 * u, armY + 16 * u);
        if (mood == Mood.HAPPY) {
            path.moveTo(cx + side, armY);
            path.quadTo(cx + side + 12 * u, armY - 2 * u, cx + side + 16 * u, armY - 16 * u);
        } else {
            path.moveTo(cx + side, armY);
            path.quadTo(cx + side + 12 * u, armY + 4 * u, cx + side + 14 * u, armY + 16 * u);
        }
        canvas.drawPath(path, stroke);

        // Feet under the spout.
        fill.setColor(color);
        oval.set(cx - 15 * u, spoutY + 2 * u, cx - 2 * u, spoutY + 10 * u);
        canvas.drawOval(oval, fill);
        oval.set(cx + 2 * u, spoutY + 2 * u, cx + 15 * u, spoutY + 10 * u);
        canvas.drawOval(oval, fill);

        path.reset();
        path.moveTo(cx - half, rimY);
        path.lineTo(cx - neck, neckY);
        path.lineTo(cx - neck, spoutY);
        path.lineTo(cx + neck, spoutY);
        path.lineTo(cx + neck, neckY);
        path.lineTo(cx + half, rimY);
        path.close();
        fill.setColor(ui.surface);
        canvas.drawPath(path, fill);
        fill.setColor((color & 0x00FFFFFF) | 0x2E000000);
        canvas.drawPath(path, fill);
        canvas.drawPath(path, stroke);
        oval.set(cx - half, rimY - 8 * u, cx + half, rimY + 8 * u);
        fill.setColor(ui.surface);
        canvas.drawOval(oval, fill);
        fill.setColor((color & 0x00FFFFFF) | 0x55000000);
        canvas.drawOval(oval, fill);
        canvas.drawOval(oval, stroke);

        face(canvas, mood, cx, top + 34 * u, 30 * u, ui.ink);
        if (mood == Mood.SLEEPY) snore(canvas, cx + half - 2 * u, rimY - 6 * u, 14 * u, ui.inkSecondary);
    }

    /** The mascot on its own, for empty pages and the footer. Decorative, so screen readers skip it. */
    @SuppressLint("ViewConstructor")
    static final class Figure extends View {
        private final Ui ui;
        private final int heightDp;
        private Mood mood;

        Figure(Context context, Ui ui, Mood mood, int heightDp) {
            super(context);
            this.ui = ui;
            this.mood = mood;
            this.heightDp = heightDp;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setMood(Mood mood) {
            if (mood == this.mood) return;
            this.mood = mood;
            invalidate();
        }

        Mood mood() {
            return mood;
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int height = ui.dp(heightDp);
            setMeasuredDimension(resolveSize(Math.round(height * 1.25f), widthSpec), resolveSize(height, heightSpec));
        }

        @Override protected void onDraw(Canvas canvas) {
            figure(canvas, mood, getWidth() / 2f, 0, getHeight(), ui);
        }
    }
}

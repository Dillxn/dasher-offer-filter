package com.local.dasherfilter;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * The app's mascot: the filter funnel with a face. Its mood follows the filter: cheerful while on, asleep while
 * paused, blank while off. Decoration only; the words next to it say what the state is.
 */
final class Mascot {
    enum Mood { HAPPY, BLINK, SLEEPY, IDLE }

    static final int CHEEK = 0x66F28B8B;
    /** Reused every frame (drawing happens on the main thread only). */
    private static final Paint STROKE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF OVAL = new RectF();

    private Mascot() {}

    static Mood moodOf(FilterHeroView.State state) {
        return state == FilterHeroView.State.ON ? Mood.HAPPY
                : state == FilterHeroView.State.PAUSED ? Mood.SLEEPY : Mood.IDLE;
    }

    /** A face about {@code size} across, centered at (x, y). */
    static void face(Canvas canvas, Mood mood, float x, float y, float size, int ink) {
        float u = size / 28f;
        Paint stroke = STROKE;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeWidth(Math.max(1f, 1.8f * u));
        stroke.setColor(ink);
        Paint fill = FILL;
        RectF oval = OVAL;
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
            case BLINK:
                for (int side = -1; side <= 1; side += 2) {
                    canvas.drawLine(x + side * eyeX - 2.4f * u, eyeY, x + side * eyeX + 2.4f * u, eyeY, stroke);
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
}

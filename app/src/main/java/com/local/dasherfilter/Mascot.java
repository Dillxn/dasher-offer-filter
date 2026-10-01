package com.local.dasherfilter;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * The app's mascot: the filter funnel with a face. Its mood follows the filter: cheerful while on, asleep while
 * paused, blank while off. Decoration only; the words next to it say what the state is.
 */
final class Mascot {
    /** CHEER is the beaming face of a wave: eyes squeezed shut in a smile and the mouth open. */
    enum Mood { HAPPY, BLINK, CHEER, SLEEPY, IDLE }

    static final int CHEEK = 0x66F28B8B;
    private static final int TONGUE = 0xFFF07A7A;
    /** Reused every frame (drawing happens on the main thread only). */
    private static final Paint STROKE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF OVAL = new RectF();
    /** The cheeks' blush, soft at its edge, for the last radius drawn (centred on 0, 0; moved by the canvas). */
    private static Shader blush;
    private static float blushRadius;

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
            float radius = 3.8f * u;
            if (blush == null || blushRadius != radius) {
                blush = new RadialGradient(0, 0, radius, new int[] {CHEEK, CHEEK, CHEEK & 0x00FFFFFF},
                        new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
                blushRadius = radius;
            }
            fill.setShader(blush);
            for (int side = -1; side <= 1; side += 2) {
                canvas.save();
                canvas.translate(x + side * 11 * u, y + 3 * u);
                canvas.drawCircle(0, 0, radius, fill);
                canvas.restore();
            }
            fill.setShader(null);
        }
        fill.setColor(ink);
        switch (mood) {
            case HAPPY:
                for (int side = -1; side <= 1; side += 2) {
                    fill.setColor(ink);
                    canvas.drawCircle(x + side * eyeX, eyeY, 2.6f * u, fill);
                    fill.setColor(0xFFFFFFFF);
                    canvas.drawCircle(x + side * eyeX - 0.9f * u, eyeY - 1f * u, 0.9f * u, fill);
                    canvas.drawCircle(x + side * eyeX + 1.05f * u, eyeY + 1.1f * u, 0.45f * u, fill);
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
            case CHEER:
                for (int side = -1; side <= 1; side += 2) {
                    oval.set(x + side * eyeX - 3 * u, eyeY - 1.5f * u, x + side * eyeX + 3 * u, eyeY + 3.5f * u);
                    canvas.drawArc(oval, 200, 140, false, stroke);
                }
                fill.setColor(ink);
                oval.set(x - 4.5f * u, y - 0.5f * u, x + 4.5f * u, y + 6.5f * u);
                canvas.drawArc(oval, 0, 180, true, fill);
                fill.setColor(TONGUE);
                oval.set(x - 2.4f * u, y + 2.6f * u, x + 2.4f * u, y + 5.9f * u);
                canvas.drawArc(oval, 0, 180, true, fill);
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

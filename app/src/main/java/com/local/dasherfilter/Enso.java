package com.local.dasherfilter;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * One brush-drawn circle, an ensō, painted as filled outlines: the brush lands full, stays full through most of the
 * sweep with the gentle wobble of a hand-drawn line, and lifts away thin, fraying into dry streaks. The streaks are
 * painted in the page's color, so the circle must sit on the plain page.
 */
final class Enso {
    /** Where the brush lands: a little left of the bottom, sweeping clockwise up the left side. */
    private static final float START_DEGREES = 118;
    private static final int STEPS = 180;
    /** Dry streaks in the lifting brush: offset across it (share of its width), where one starts, widest share. */
    private static final float[][] STREAKS = {{-0.22f, 0.72f, 0.11f}, {0.2f, 0.82f, 0.09f}, {0.0f, 0.9f, 0.07f}};

    private final Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint streak = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float[] outer = new float[2 * (STEPS + 1)];
    private final float[] inner = new float[2 * (STEPS + 1)];

    /**
     * Paints the stroke around ({@code cx}, {@code cy}).
     *
     * @param sweep degrees the finished stroke goes round
     * @param drawn share of the stroke drawn so far, 0 to 1, for drawing itself in
     */
    void draw(Canvas canvas, int color, int page, float cx, float cy, float radius, float thickness, float sweep,
              float drawn) {
        brush.setColor(color);
        streak.setColor(page);
        float end = Math.max(1f / STEPS, Math.min(1f, drawn));
        band(canvas, brush, cx, cy, radius, thickness, sweep, 0f, end, 0f, -1f);
        // Where the brush landed is round; where it lifts, it just thins away.
        double landed = Math.toRadians(START_DEGREES);
        float middle = wobble(radius, 0);
        canvas.drawCircle(cx + middle * (float) Math.cos(landed), cy + middle * (float) Math.sin(landed),
                thickness * profile(0) / 2f, brush);
        for (float[] dry : STREAKS) {
            if (end > dry[1]) band(canvas, streak, cx, cy, radius, thickness, sweep, dry[1], end, dry[0], dry[2]);
        }
    }

    /**
     * Fills the band of the stroke from {@code from} to {@code to} (shares of the sweep), its middle {@code offset}
     * brush-widths off the stroke's middle line. A negative {@code share} is the whole brush; otherwise the band
     * widens from nothing to {@code share} of the full brush width by the end.
     */
    private void band(Canvas canvas, Paint paint, float cx, float cy, float radius, float thickness, float sweep,
                      float from, float to, float offset, float share) {
        int first = Math.round(STEPS * from);
        int last = Math.max(first + 1, Math.round(STEPS * to));
        int count = last - first;
        for (int i = 0; i <= count; i++) {
            float t = (first + i) / (float) STEPS;
            double angle = Math.toRadians(START_DEGREES + sweep * t);
            float full = thickness * profile(t);
            float width = share < 0 ? full : thickness * share * smoothstep(from, 1f, t);
            float middle = wobble(radius, t) + offset * full;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            outer[2 * i] = cx + (middle + width / 2f) * cos;
            outer[2 * i + 1] = cy + (middle + width / 2f) * sin;
            inner[2 * i] = cx + (middle - width / 2f) * cos;
            inner[2 * i + 1] = cy + (middle - width / 2f) * sin;
        }
        path.reset();
        path.moveTo(outer[0], outer[1]);
        for (int i = 1; i <= count; i++) path.lineTo(outer[2 * i], outer[2 * i + 1]);
        for (int i = count; i >= 0; i--) path.lineTo(inner[2 * i], inner[2 * i + 1]);
        path.close();
        canvas.drawPath(path, paint);
    }

    /** The stroke's middle line: a circle drawn by hand, drifting a little inward as it closes. */
    private static float wobble(float radius, float t) {
        return radius * (1f + 0.011f * (float) Math.sin(2 * Math.PI * 1.4 * t + 0.6)
                + 0.005f * (float) Math.sin(2 * Math.PI * 3.1 * t + 1.3)) - radius * 0.02f * t * t;
    }

    /** Brush width along the stroke, as a share of the full width. */
    private static float profile(float t) {
        float landing = t < 0.05f ? 0.72f + 0.28f * (t / 0.05f) : 1f;
        float lift = smoothstep(0.42f, 1f, t);
        float texture = 1f + 0.05f * (float) Math.sin(11 * t);
        return landing * (1f - 0.74f * lift) * texture;
    }

    private static float smoothstep(float from, float to, float t) {
        float x = Math.max(0f, Math.min(1f, (t - from) / (to - from)));
        return x * x * (3f - 2f * x);
    }
}

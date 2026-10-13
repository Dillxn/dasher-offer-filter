package com.local.dasherfilter;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Autopilot's acceptance meter (0.6.6), drawn before the status line's "7% / 70%": a short track whose fill is the
 * acceptance rate now and whose tick is the goal. It stands for the sentence "AR 7% → 70%: about 63 more accepts",
 * which stays in the chip's details and for screen readers (the owner, 12 October 2026: say each thing once).
 */
final class AcceptanceMeter extends Drawable {
    static final int WIDTH_DP = 36;
    static final int HEIGHT_DP = 6;

    private final Ui ui;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int ratePercent;
    private int goalPercent;
    private int fillColor;

    AcceptanceMeter(Ui ui) {
        this.ui = ui;
    }

    /** Rate and goal, each 0 to 100; {@code color} the fill's. */
    void set(int ratePercent, int goalPercent, int color) {
        this.ratePercent = Math.max(0, Math.min(100, ratePercent));
        this.goalPercent = Math.max(0, Math.min(100, goalPercent));
        fillColor = color;
        invalidateSelf();
    }

    int rate() { return ratePercent; }
    int goal() { return goalPercent; }

    @Override public int getIntrinsicWidth() { return Math.round(ui.dp(WIDTH_DP)); }
    @Override public int getIntrinsicHeight() { return Math.round(ui.dp(HEIGHT_DP + 6)); }

    @Override public void draw(Canvas canvas) {
        float left = getBounds().left;
        float width = getBounds().width();
        float middle = getBounds().exactCenterY();
        float half = ui.dp(HEIGHT_DP) / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ui.gridline);
        rect.set(left, middle - half, left + width, middle + half);
        canvas.drawRoundRect(rect, half, half, paint);
        if (ratePercent > 0) {
            paint.setColor(fillColor);
            rect.set(left, middle - half, left + Math.max(2 * half, width * ratePercent / 100f), middle + half);
            canvas.drawRoundRect(rect, half, half, paint);
        }
        paint.setColor(ui.ink);
        float x = left + width * goalPercent / 100f;
        float tick = Math.max(1, ui.dp(2));
        rect.set(x - tick / 2, middle - half - ui.dp(3), x + tick / 2, middle + half + ui.dp(3));
        canvas.drawRect(rect, paint);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}

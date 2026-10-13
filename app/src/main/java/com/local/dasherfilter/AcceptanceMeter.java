package com.local.dasherfilter;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * The acceptance meter (0.6.6; 0.6.8 as the owner's mockup draws it): the status words "ACCEPTANCE 7% / 70%" over a
 * long track across the rest of the chips' row, whose fill is the acceptance rate now, amber below the goal and green
 * at or above it, with a tick at the goal. Drawn as the status words' background, the track along their bottom. With
 * Autopilot off it shows the rate alone, no goal and no tick, since nothing pursues one. It stands for the sentence
 * "AR 7% → 70%: about 63 more accepts", which stays in the chip's details and for screen readers (the owner, 12
 * October 2026: say each thing once).
 */
final class AcceptanceMeter extends Drawable {
    static final int HEIGHT_DP = 6;
    /** The track's middle this far above the words' bottom. */
    static final int BOTTOM_DP = 9;
    /** The room the words leave under them for the track. */
    static final int ROOM_DP = 14;

    private final Ui ui;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int ratePercent;
    private int goalPercent;
    private int fillColor;

    AcceptanceMeter(Ui ui) {
        this.ui = ui;
    }

    /** Rate and goal, each 0 to 100 (goal 0: none, no tick); {@code color} the fill's. */
    void set(int ratePercent, int goalPercent, int color) {
        this.ratePercent = Math.max(0, Math.min(100, ratePercent));
        this.goalPercent = Math.max(0, Math.min(100, goalPercent));
        fillColor = color;
        invalidateSelf();
    }

    int rate() { return ratePercent; }
    int goal() { return goalPercent; }

    @Override public void draw(Canvas canvas) {
        float left = getBounds().left + ui.dp(2);
        float width = Math.max(0, getBounds().width() - ui.dp(8));
        float middle = getBounds().bottom - ui.dp(BOTTOM_DP);
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
        if (goalPercent <= 0) return;
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

package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The filter's own control inside Dasher: a small tab fixed to the left edge, with the mascot's face in a ring. Blue
 * and awake while auto-decline is on, amber and asleep while paused, grey with no rules yet. It cannot be moved.
 */
@SuppressLint("ViewConstructor")
final class DasherTab extends View {
    static final int WIDTH_DP = 46;
    static final int HEIGHT_DP = 56;

    private final Ui ui;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final DashPathEffect dashed;
    private FilterHeroView.State state = FilterHeroView.State.OFF;

    DasherTab(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        dashed = new DashPathEffect(new float[] {ui.dp(4), ui.dp(3)}, 0);
        setClickable(true);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void show(FilterHeroView.State next) {
        state = next;
        setContentDescription(state == FilterHeroView.State.ON ? "Offer Filter: auto-decline on. Tap to pause."
                : state == FilterHeroView.State.PAUSED ? "Offer Filter: paused. Tap to resume."
                : "Offer Filter: no rules yet. Tap to set them up.");
        invalidate();
    }

    FilterHeroView.State state() {
        return state;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(ui.dp(WIDTH_DP), ui.dp(HEIGHT_DP));
    }

    @Override protected void onDraw(Canvas canvas) {
        float width = getWidth();
        float height = getHeight();
        float round = height / 2;
        int color = state == FilterHeroView.State.ON ? ui.accent
                : state == FilterHeroView.State.PAUSED ? Ui.WARNING : ui.inkMuted;
        // Flush with the screen's edge on the left, rounded on the right.
        rect.set(-round, 0, width, height);
        fill.setColor(isPressed() ? ui.gridline : ui.surface);
        canvas.drawRoundRect(rect, round, round, fill);
        line.setPathEffect(null);
        line.setStrokeWidth(Math.max(1, ui.dp(1)));
        line.setColor(ui.dark ? 0x40FFFFFF : 0x330B0B0B);
        canvas.drawRoundRect(rect, round, round, line);

        float cx = width / 2 - ui.dp(1);
        float cy = height / 2;
        float radius = ui.dp(16);
        line.setColor(color);
        line.setStrokeWidth(ui.dp(3));
        line.setPathEffect(state == FilterHeroView.State.PAUSED ? dashed : null);
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        if (state == FilterHeroView.State.OFF) canvas.drawCircle(cx, cy, radius, line);
        else canvas.drawArc(rect, 135, state == FilterHeroView.State.ON ? 320 : 240, false, line);
        line.setPathEffect(null);
        Mascot.face(canvas, Mascot.moodOf(state), cx, cy + ui.dp(1), ui.dp(15),
                state == FilterHeroView.State.OFF ? ui.inkMuted : ui.ink);
    }

    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }
}

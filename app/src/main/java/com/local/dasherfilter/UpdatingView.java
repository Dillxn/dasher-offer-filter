package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The mascot while Offer Filter updates itself: its brush ring sweeps round and round and it looks up, waiting. With
 * Android's animations off the ring rests, still showing the mascot at work.
 */
@SuppressLint("ViewConstructor")
final class UpdatingView extends View {
    private final Ui ui;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    UpdatingView(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), ui.dp(170));
    }

    @Override protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = ui.dp(64);
        fill.setColor((ui.accent & 0x00FFFFFF) | 0x14000000);
        canvas.drawCircle(cx, cy, radius - ui.dp(8), fill);
        float turn = Motion.on() ? Motion.loop(1.6f, 0) * 360 : 0;
        float sweep = 110 + 90 * (Motion.on() ? Motion.wave(1.6f, 0) : 1);
        line.setColor(ui.accent);
        line.setStrokeWidth(ui.dp(8));
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawArc(rect, turn - 90, sweep, false, line);
        line.setStrokeWidth(ui.dp(3));
        line.setColor((ui.accent & 0x00FFFFFF) | 0x66000000);
        canvas.drawArc(rect, turn - 90 + sweep + 14, 40, false, line);
        Mascot.face(canvas, Mascot.Mood.HAPPY, cx, cy, ui.dp(34), ui.ink);
        Motion.next(this);
    }
}

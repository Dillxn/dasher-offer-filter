package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** A small green dot with a ring that keeps widening from it: live. With Android's animations off it rests. */
@SuppressLint("ViewConstructor")
final class LiveDot extends View {
    private static final int GREEN = 0xFF2FA84F;
    private final Ui ui;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

    LiveDot(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(1, ui.dp(1.5f)));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(ui.dp(22), ui.dp(22));
    }

    @Override protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        if (Motion.on()) {
            float pulse = Motion.loop(1.8f, 0);
            ring.setColor((GREEN & 0x00FFFFFF) | (Math.round(0xB0 * (1 - pulse)) << 24));
            canvas.drawCircle(cx, cy, ui.dp(4) + ui.dp(6) * pulse, ring);
        }
        fill.setColor(GREEN);
        canvas.drawCircle(cx, cy, ui.dp(4), fill);
        Motion.next(this);
    }
}

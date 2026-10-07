package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * "Back to map" over Dasher's wait for offers, under the filter tab (the owner's approval, A1): after a peek left
 * Dasher up, or a card opened it, while the user was in a navigation app, and the offer ended without an acceptance.
 * A tap opens that app as its launcher would; it goes by itself after {@link OfferFilterService#CHIP_SHOW_MS}. It
 * names no app (the words are the same for every map), and it is never over an offer, its question or a delivery.
 */
@SuppressLint("ViewConstructor")
final class BackToMapChip extends View {
    /** Its height, a full touch target. */
    static final int HEIGHT_DP = 48;
    static final String LABEL = "Back to map";

    private final Ui ui;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();

    BackToMapChip(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(1, ui.dp(1)));
        text.setFakeBoldText(true);
        text.setTextSize(Math.min(ui.sp(14), ui.dp(18)));
        setClickable(true);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription(LABEL);
    }

    /** The pill drawn inside its touch target: a little shorter than it, centred. */
    private float pillHeight() {
        return ui.dp(36);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = Math.round(pillHeight() + ui.dp(4) + text.measureText(LABEL) + ui.dp(14));
        setMeasuredDimension(Math.max(width, ui.dp(48)), ui.dp(HEIGHT_DP));
    }

    @Override protected void onDraw(Canvas canvas) {
        float height = pillHeight();
        float top = (getHeight() - height) / 2;
        float round = height / 2;
        pill.set(0, top, getWidth(), top + height);
        fill.setColor(isPressed() ? ui.gridline : ui.surface);
        canvas.drawRoundRect(pill, round, round, fill);
        line.setColor(ui.dark ? 0x40FFFFFF : 0x330B0B0B);
        canvas.drawRoundRect(pill, round, round, line);
        float icon = ui.dp(20);
        Glyph.draw(canvas, Glyph.Shape.PIN, ui.accent, (height - icon) / 2 + ui.dp(4), top + (height - icon) / 2, icon);
        text.setColor(ui.ink);
        Paint.FontMetrics metrics = text.getFontMetrics();
        canvas.drawText(LABEL, height + ui.dp(4), top + height / 2 - (metrics.ascent + metrics.descent) / 2, text);
    }

    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }
}

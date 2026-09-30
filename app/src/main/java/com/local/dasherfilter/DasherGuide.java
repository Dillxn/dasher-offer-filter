package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import java.util.List;
import java.util.Locale;

/**
 * The best offer area, pointed out over Dasher beside the filter tab: an arrow toward it and one short line, "3.6 mi
 * NE · $3.00/mi". North is up, as on Dasher's map while waiting for offers; the compass point says the same in words
 * for when the map is turned. Touches pass through it to Dasher.
 */
@SuppressLint("ViewConstructor")
final class DasherGuide extends View {
    static final int HEIGHT_DP = 34;

    private final Ui ui;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final RectF pill = new RectF();
    private String label = "";
    /** Degrees clockwise from north; NaN draws a dot (the best area is around you). */
    private float bearing = Float.NaN;

    DasherGuide(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(1, ui.dp(1)));
        text.setFakeBoldText(true);
        text.setTextSize(Math.min(ui.sp(13), ui.dp(17)));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** One line and the way to point. */
    static final class Pointer {
        final String label;
        /** Degrees clockwise from north; NaN when the best area is around you. */
        final float bearing;

        Pointer(String label, float bearing) {
            this.label = label;
            this.bearing = bearing;
        }
    }

    /**
     * What to show: the best ranked area from here, or null when there is none to point at (no ranked area, no
     * recent position, or a delivery under way).
     */
    static Pointer best(Context context) {
        if (!AreaMap.enabled(context) || ActiveRouteStore.load(context) != null) return null;
        List<AreaMap.Cell> ranked = AreaMap.ranked(AreaMap.cells(context));
        if (ranked.isEmpty()) return null;
        AreaMap.Cell best = ranked.get(0);
        double[] way = AreaMap.offset(AreaMap.here(context), best);
        if (way == null) return null;
        if (way[0] < 1) return new Pointer("Best area here · " + best.perMile(), Float.NaN);
        return new Pointer(String.format(Locale.US, "%.1f mi %s · %s", way[0], AreaMap.compassPoint(way[1]),
                best.perMile()), (float) way[1]);
    }

    /** @return whether anything changed */
    boolean show(String label, float bearing) {
        boolean same = label.equals(this.label) && (Float.compare(bearing, this.bearing) == 0);
        if (same) return false;
        this.label = label;
        this.bearing = bearing;
        requestLayout();
        invalidate();
        return true;
    }

    String label() {
        return label;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = Math.round(ui.dp(HEIGHT_DP) + text.measureText(label) + ui.dp(12));
        setMeasuredDimension(width, ui.dp(HEIGHT_DP));
    }

    @Override protected void onDraw(Canvas canvas) {
        float height = getHeight();
        float round = height / 2;
        pill.set(0, 0, getWidth(), height);
        fill.setColor(ui.surface);
        canvas.drawRoundRect(pill, round, round, fill);
        line.setColor(ui.dark ? 0x40FFFFFF : 0x330B0B0B);
        canvas.drawRoundRect(pill, round, round, line);

        float cx = round;
        float cy = round;
        fill.setColor(ui.learned);
        if (Float.isNaN(bearing)) {
            canvas.drawCircle(cx, cy, ui.dp(4), fill);
        } else {
            float size = ui.dp(9);
            arrow.reset();
            arrow.moveTo(0, -size);
            arrow.lineTo(size * 0.7f, size * 0.8f);
            arrow.lineTo(0, size * 0.4f);
            arrow.lineTo(-size * 0.7f, size * 0.8f);
            arrow.close();
            canvas.save();
            canvas.translate(cx, cy);
            canvas.rotate(bearing);
            canvas.drawPath(arrow, fill);
            canvas.restore();
        }
        text.setColor(ui.ink);
        Paint.FontMetrics metrics = text.getFontMetrics();
        canvas.drawText(label, height, cy - (metrics.ascent + metrics.descent) / 2, text);
    }
}

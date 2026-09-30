package com.local.dasherfilter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One offer as a picture: its pay as a bar against a tick for the pay the rules needed, then its route from the
 * pickup bag to the drop-off house with a dot for each stop, labeled with miles and minutes. Unknown values are
 * shown as unknown, never guessed.
 */
@SuppressLint("ViewConstructor")
final class OfferCardView extends View {
    private final Ui ui;
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private DecisionLog.Entry entry;

    OfferCardView(Context context, Ui ui) {
        super(context);
        this.ui = ui;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void show(DecisionLog.Entry entry) {
        this.entry = entry;
        setContentDescription(pay() + (needed() > 0 ? ", needed " + DecisionLog.money(needed()) : "") + ". "
                + route());
        invalidate();
    }

    /** Vertical positions derived from the text sizes, so large system fonts push the drawing down, not over. */
    private float payBaseline;
    private float neededBaseline;
    private boolean crowded;
    private float barTop;
    private float routeLabelBaseline;
    private float routeY;

    /** @param width the view's width: when "Paid" and "needed" do not fit side by side, "needed" gets its own line */
    private void layoutLines(float width) {
        text.setFakeBoldText(false);
        text.setTextSize(ui.sp(13));
        Paint.FontMetrics small = text.getFontMetrics();
        float neededWidth = entry == null || needed() == 0 ? 0
                : text.measureText("needed " + DecisionLog.money(needed()));
        text.setFakeBoldText(true);
        text.setTextSize(ui.sp(15));
        Paint.FontMetrics big = text.getFontMetrics();
        crowded = entry != null && neededWidth > 0 && text.measureText(pay()) + neededWidth + ui.dp(12) > width;
        payBaseline = -big.ascent;
        float lines = big.descent - big.ascent;
        neededBaseline = crowded ? lines - small.ascent : payBaseline;
        if (crowded) lines += small.descent - small.ascent;
        barTop = lines + ui.dp(8);
        routeLabelBaseline = barTop + ui.dp(12) + ui.dp(14) - small.ascent;
        routeY = routeLabelBaseline + small.descent + ui.dp(4) + ui.dp(11);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        layoutLines(MeasureSpec.getSize(widthSpec));
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), resolveSize(Math.round(routeY + ui.dp(13)), heightSpec));
    }

    private long needed() {
        return entry.requiredCents > 0 && entry.requiredCents < Long.MAX_VALUE ? entry.requiredCents : 0;
    }

    private String pay() {
        return entry.facts.payCents == null ? "Pay unknown" : "Paid " + DecisionLog.money(entry.facts.payCents);
    }

    /** "7.2 mi · 21 min · 2 stops", with "?" for anything not read. */
    private String route() {
        return route(true);
    }

    /** @param withStops false where space is short: the dots on the route line already show the stops */
    private String route(boolean withStops) {
        OfferSnapshot facts = entry.facts;
        List<String> parts = new ArrayList<>();
        parts.add(facts.miles == null ? "? mi" : String.format(Locale.US, "%.1f mi", facts.miles));
        parts.add(facts.minutes == null ? "? min" : facts.minutes + " min");
        if (withStops || facts.stops == null) {
            parts.add(facts.stops == null ? "? stops" : facts.stops + (facts.stops == 1 ? " stop" : " stops"));
        }
        return String.join(" · ", parts);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (entry == null) return;
        float width = getWidth();
        layoutLines(width);
        int color = Ui.resultColor(entry.result);

        // Pay against the pay the rules needed: side by side, or "needed" on its own line when crowded.
        long needed = needed();
        text.setFakeBoldText(true);
        text.setTextSize(ui.sp(15));
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(ui.ink);
        CharSequence paid = Ui.fit(text, pay(), width, 0.75f);
        canvas.drawText(paid, 0, paid.length(), 0, payBaseline, text);
        if (needed > 0) {
            text.setFakeBoldText(false);
            text.setTextSize(ui.sp(13));
            text.setColor(ui.inkSecondary);
            text.setTextAlign(crowded ? Paint.Align.LEFT : Paint.Align.RIGHT);
            CharSequence need = Ui.fit(text, "needed " + DecisionLog.money(needed), width, 0.75f);
            canvas.drawText(need, 0, need.length(), crowded ? 0 : width, neededBaseline, text);
        }
        float barBottom = barTop + ui.dp(12);
        rect.set(0, barTop, width, barBottom);
        fill.setColor(ui.gridline);
        canvas.drawRoundRect(rect, ui.dp(6), ui.dp(6), fill);
        Integer pay = entry.facts.payCents;
        float scale = Math.max(pay == null ? 0 : pay, needed) * 1.15f;
        if (pay != null && scale > 0) {
            rect.set(0, barTop, Math.max(ui.dp(12), width * pay / scale), barBottom);
            fill.setColor(color);
            canvas.drawRoundRect(rect, ui.dp(6), ui.dp(6), fill);
        }
        if (needed > 0 && scale > 0) {
            float x = width * needed / scale;
            line.setColor(ui.ink);
            line.setStrokeWidth(ui.dp(3));
            line.setPathEffect(null);
            canvas.drawLine(x, barTop - ui.dp(5), x, barBottom + ui.dp(5), line);
        }

        // The route: pickup bag, a dot per stop, drop-off house.
        float icon = ui.dp(22);
        int routeInk = ui.inkSecondary;
        Glyph.draw(canvas, Glyph.Shape.BAG, routeInk, 0, routeY - icon / 2, icon);
        Glyph.draw(canvas, Glyph.Shape.HOME, routeInk, width - icon, routeY - icon / 2, icon);
        float lineLeft = icon + ui.dp(8);
        float lineRight = width - icon - ui.dp(8);
        line.setColor(ui.baseline);
        line.setStrokeWidth(ui.dp(2));
        line.setPathEffect(new DashPathEffect(new float[] {ui.dp(6), ui.dp(5)}, 0));
        canvas.drawLine(lineLeft, routeY, lineRight, routeY, line);
        line.setPathEffect(null);
        Integer stops = entry.facts.stops;
        if (stops != null && stops > 0) {
            int dots = Math.min(stops, 8);
            fill.setColor(ui.ink);
            for (int i = 0; i < dots; i++) {
                float x = dots == 1 ? (lineLeft + lineRight) / 2
                        : lineLeft + ui.dp(6) + (lineRight - lineLeft - ui.dp(12)) * i / (dots - 1);
                canvas.drawCircle(x, routeY, ui.dp(4), fill);
            }
        }
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(false);
        text.setTextSize(ui.sp(13));
        text.setColor(ui.inkSecondary);
        float room = width - 2 * (icon + ui.dp(8));
        String full = route(true);
        CharSequence label = Ui.fit(text, text.measureText(full) * 0.8f <= room ? full : route(false), room, 0.8f);
        canvas.drawText(label, 0, label.length(), width / 2, routeLabelBaseline, text);
    }
}
